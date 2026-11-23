package dev.silo.app.ipc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

class DaemonRpcException(message: String) : Exception(message)

class BridgeProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Fixed-capacity thread-safe ring buffer for stderr draining (APP-H02). */
internal class BoundedStderrBuffer(private val maxBytes: Int = 2048) {
    private val ring = ByteArray(maxBytes)
    private var head = 0
    private var size = 0

    @Synchronized
    fun append(data: ByteArray, len: Int) {
        if (len <= 0) return
        if (len >= maxBytes) {
            System.arraycopy(data, len - maxBytes, ring, 0, maxBytes)
            head = 0
            size = maxBytes
            return
        }
        for (i in 0 until len) {
            val writePos = (head + size) % maxBytes
            if (size == maxBytes) {
                ring[head] = data[i]
                head = (head + 1) % maxBytes
            } else {
                ring[writePos] = data[i]
                size++
            }
        }
    }

    @Synchronized
    fun snapshot(): String {
        if (size == 0) return ""
        val out = ByteArray(size)
        val firstChunk = minOf(size, maxBytes - head)
        System.arraycopy(ring, head, out, 0, firstChunk)
        if (firstChunk < size) {
            System.arraycopy(ring, 0, out, firstChunk, size - firstChunk)
        }
        return String(out, Charsets.UTF_8).trim()
    }
}

/**
 * SELinux-clean persistent root bridge client.
 *
 * Opens a single `su -c "exec /data/adb/modules/silo/bin/silod --bridge"` subprocess
 * that speaks newline-delimited JSON-RPC over stdin/stdout to the root-only
 * Unix domain socket `/data/adb/silo/silod.sock` (0600, verified by SO_PEERCRED).
 *
 * - Zero SELinux policy patches required.
 * - Zero TCP/UDP ports opened.
 * - Zero shell-invocation spam while UI is open.
 * - Genuinely cancellable timeouts via active coroutine watchdog + non-blocking `destroyForcibly()` (APP-C03).
 * - Continuous daemon stderr draining into a 2 KiB ring buffer (APP-H02).
 * - Strict JSON-RPC envelope and response ID verification (APP-H03).
 */
class SiloClient(
    private val silodPath: String = "/data/adb/modules/silo/bin/silod",
    private val processFactory: () -> Process = {
        ProcessBuilder("su", "-c", "exec $silodPath --bridge")
            .redirectErrorStream(false)
            .start()
    },
) {
    val json: Json = SiloJson

    private class BridgeHandle(
        val process: Process,
        val writer: BufferedWriter,
        val reader: BufferedReader,
        val stderrBuffer: BoundedStderrBuffer,
        val stderrThread: Thread,
    ) {
        private val closed = AtomicBoolean(false)

        /**
         * Non-blocking abort safe to invoke from a timeout/cancellation watchdog without touching
         * `BufferedReader.lock` (avoids deadlocking the coroutine dispatcher).
         */
        fun abortNow() {
            runCatching { process.destroyForcibly() }
        }

        /** Full teardown called from the IO thread after `readLine()`/`write()` has exited (APP-M11). */
        fun close(gracefulWaitMs: Long = 150L) {
            if (!closed.compareAndSet(false, true)) return
            runCatching { writer.close() }
            runCatching { reader.close() }
            runCatching { process.errorStream.close() }
            runCatching {
                if (process.isAlive) {
                    process.destroy()
                    if (gracefulWaitMs > 0L) {
                        process.waitFor(gracefulWaitMs, TimeUnit.MILLISECONDS)
                    }
                }
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor(100L, TimeUnit.MILLISECONDS)
                }
            }
            runCatching { stderrThread.join(50L) }
            if (stderrThread.isAlive) {
                runCatching { stderrThread.interrupt() }
            }
        }
    }

    private val mutex = Mutex()
    private val nextId = AtomicLong(1L)

    @Volatile
    private var bridge: BridgeHandle? = null

    suspend fun callRaw(
        cmd: String,
        params: JsonElement? = null,
        timeoutMs: Long = 12_000L,
    ): Result<JsonElement?> = withContext(Dispatchers.IO) {
        val effectiveTimeoutMs = timeoutMs.coerceAtLeast(100L)
        val callerJob = coroutineContext[Job]
        val startNs = System.nanoTime()
        try {
            withTimeout(effectiveTimeoutMs) {
                mutex.lock()
            }
        } catch (e: TimeoutCancellationException) {
            if (callerJob?.isCancelled == true) throw e
            return@withContext Result.failure(
                IOException("RPC '$cmd' timed out after ${effectiveTimeoutMs}ms waiting for bridge lock", e)
            )
        } catch (e: CancellationException) {
            throw e
        }

        try {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            val remainingMs = (effectiveTimeoutMs - elapsedMs).coerceAtLeast(1L)
            val data = executeLocked(cmd, params, remainingMs, effectiveTimeoutMs)
            Result.success(data)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            mutex.unlock()
        }
    }

    suspend inline fun <reified T> call(
        cmd: String,
        params: JsonElement? = null,
        timeoutMs: Long = 12_000L,
    ): Result<T> {
        return callRaw(cmd, params, timeoutMs).mapCatching { elem ->
            requireNotNull(elem) { "Empty response payload for $cmd" }
            json.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), elem)
        }
    }

    private suspend fun executeLocked(
        cmd: String,
        params: JsonElement?,
        remainingTimeoutMs: Long,
        totalTimeoutMs: Long,
    ): JsonElement? = coroutineScope {
        val state = AtomicInteger(STATE_RUNNING)
        val activeHandle = AtomicReference<BridgeHandle?>(null)

        fun triggerAbort(targetState: Int) {
            if (state.compareAndSet(STATE_RUNNING, targetState)) {
                activeHandle.get()?.abortNow()
                bridge?.abortNow()
            }
        }

        // Dedicated watchdog on Default dispatcher fires immediately when either
        // `remainingTimeoutMs` elapses OR the parent coroutine is cancelled, calling
        // non-blocking `destroyForcibly()` to unblock `BufferedReader.readLine()`.
        val watchdog = launch(Dispatchers.Default) {
            try {
                delay(remainingTimeoutMs)
                triggerAbort(STATE_TIMED_OUT)
            } catch (e: CancellationException) {
                if (state.get() == STATE_RUNNING) {
                    triggerAbort(STATE_CANCELLED)
                }
                throw e
            }
        }

        val ioDeferred = async(Dispatchers.IO) {
            try {
                executeBlockingIoLocked(cmd, params, totalTimeoutMs, state, activeHandle)
            } finally {
                state.compareAndSet(STATE_RUNNING, STATE_DONE)
                watchdog.cancel()
            }
        }

        try {
            ioDeferred.await()
        } catch (e: CancellationException) {
            triggerAbort(STATE_CANCELLED)
            // Ensure IO thread finishes closeLocked(0L) BEFORE mutex.unlock() releases the bridge lock!
            withContext(NonCancellable) {
                runCatching { ioDeferred.await() }
            }
            throw e
        } finally {
            watchdog.cancel()
        }
    }

    private fun executeBlockingIoLocked(
        cmd: String,
        params: JsonElement?,
        totalTimeoutMs: Long,
        state: AtomicInteger,
        activeHandle: AtomicReference<BridgeHandle?>,
    ): JsonElement? {
        fun throwIfAborted(cause: Throwable? = null) {
            when (state.get()) {
                STATE_TIMED_OUT -> {
                    closeLocked(gracefulWaitMs = 0L)
                    throw IOException("RPC '$cmd' timed out after ${totalTimeoutMs}ms", cause)
                }
                STATE_CANCELLED -> {
                    closeLocked(gracefulWaitMs = 0L)
                    throw CancellationException("RPC '$cmd' cancelled")
                }
            }
        }

        try {
            throwIfAborted()

            val handle = ensureBridgeConnectedLocked()
            activeHandle.set(handle)
            throwIfAborted()

            val reqId = nextId.getAndIncrement()
            val payload = json.encodeToString(
                IpcEnvelopeRequest(id = reqId, cmd = cmd, params = params)
            )

            handle.writer.write(payload)
            handle.writer.newLine()
            handle.writer.flush()

            val responseLine = handle.reader.readLine()
            throwIfAborted()

            if (responseLine == null) {
                runCatching { handle.stderrThread.join(50L) }
                val stderrSnippet = handle.stderrBuffer.snapshot()
                closeLocked(gracefulWaitMs = 0L)
                val base = "Root bridge terminated — ensure root is granted to Silo in KernelSU-Next and Silo module is installed."
                val msg = if (stderrSnippet.isNotBlank()) "$base [stderr: ${stderrSnippet.take(300)}]" else base
                throw IOException(msg)
            }

            val resp = try {
                parseAndValidateEnvelope(json, responseLine, reqId)
            } catch (e: BridgeProtocolException) {
                runCatching { handle.stderrThread.join(50L) }
                val stderrSnippet = handle.stderrBuffer.snapshot()
                closeLocked(gracefulWaitMs = 0L)
                if (stderrSnippet.isNotBlank()) {
                    throw BridgeProtocolException("${e.message} [stderr: ${stderrSnippet.take(300)}]", e)
                }
                throw e
            }

            // Atomically claim completion so watchdog can never abort this healthy bridge.
            if (!state.compareAndSet(STATE_RUNNING, STATE_DONE)) {
                throwIfAborted()
            }

            if (!resp.ok) {
                throw DaemonRpcException(resp.error ?: "Daemon returned an unknown error")
            }
            return resp.data
        } catch (t: Throwable) {
            if (t !is DaemonRpcException) {
                closeLocked(gracefulWaitMs = 0L)
            }
            throwIfAborted(t)
            throw t
        }
    }

    private fun ensureBridgeConnectedLocked(): BridgeHandle {
        val current = bridge
        if (current != null && current.process.isAlive) {
            return current
        }
        closeLocked(gracefulWaitMs = 150L)

        val p = try {
            processFactory()
        } catch (e: Exception) {
            throw IOException("Unable to invoke su: ${e.message ?: "KernelSU root unavailable"}", e)
        }

        val stderrBuf = BoundedStderrBuffer(maxBytes = 2048)
        val stderrThread = Thread({
            try {
                val tmp = ByteArray(512)
                val err = p.errorStream
                while (true) {
                    val n = err.read(tmp)
                    if (n == -1) break
                    if (n > 0) stderrBuf.append(tmp, n)
                }
            } catch (_: Throwable) {
                // Stream closed on process exit/teardown
            }
        }, "silo-bridge-stderr").apply {
            isDaemon = true
            start()
        }

        val handle = BridgeHandle(
            process = p,
            writer = BufferedWriter(OutputStreamWriter(p.outputStream, Charsets.UTF_8)),
            reader = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)),
            stderrBuffer = stderrBuf,
            stderrThread = stderrThread,
        )
        bridge = handle
        return handle
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        mutex.withLock { closeLocked(gracefulWaitMs = 150L) }
    }

    private fun closeLocked(gracefulWaitMs: Long = 150L) {
        val current = bridge
        bridge = null
        current?.close(gracefulWaitMs = gracefulWaitMs)
    }

    companion object {
        private const val STATE_RUNNING = 0
        private const val STATE_DONE = 1
        private const val STATE_TIMED_OUT = 2
        private const val STATE_CANCELLED = 3

        /**
         * Pure testable JSON-RPC envelope parser & response ID validator (APP-H03).
         * Rejects non-JSON preambles, malformed JSON, and mismatched `resp.id != expectedId`.
         */
        fun parseAndValidateEnvelope(
            json: Json,
            responseLine: String,
            expectedId: Long,
        ): IpcEnvelopeResponse {
            val trimmed = responseLine.trim()
            if (trimmed.isEmpty() || !trimmed.startsWith("{")) {
                throw BridgeProtocolException(
                    "Unexpected non-JSON preamble from root bridge (expected id=$expectedId): ${trimmed.take(120)}"
                )
            }
            val resp = try {
                json.decodeFromString<IpcEnvelopeResponse>(trimmed)
            } catch (e: Exception) {
                throw BridgeProtocolException(
                    "Malformed JSON-RPC response from root bridge (expected id=$expectedId): ${e.message}",
                    e,
                )
            }
            if (resp.id != expectedId) {
                throw BridgeProtocolException(
                    "IPC response ID mismatch: expected $expectedId, got ${resp.id}"
                )
            }
            return resp
        }
    }
}

