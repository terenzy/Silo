package dev.silo.app.ipc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicLong

class DaemonRpcException(message: String) : Exception(message)

/**
 * SELinux-clean persistent root bridge client.
 *
 * Opens a single `su -c /data/adb/modules/silo/bin/silod --bridge` subprocess
 * that speaks newline-delimited JSON-RPC over stdin/stdout to the root-only
 * Unix domain socket `/data/adb/silo/silod.sock` (0600, verified by SO_PEERCRED).
 *
 * - Zero SELinux policy patches required.
 * - Zero TCP/UDP ports opened.
 * - Zero shell-invocation spam while UI is open.
 */
class SiloClient(
    private val silodPath: String = "/data/adb/modules/silo/bin/silod",
) {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val mutex = Mutex()
    private val nextId = AtomicLong(1L)

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    suspend fun callRaw(
        cmd: String,
        params: JsonElement? = null,
        timeoutMs: Long = 10_000L,
    ): Result<JsonElement?> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                withTimeout(timeoutMs) {
                    ensureBridgeConnected()
                    val reqId = nextId.getAndIncrement()
                    val payload = json.encodeToString(
                        IpcEnvelopeRequest(id = reqId, cmd = cmd, params = params)
                    )
                    val w = writer ?: error("Bridge writer unavailable")
                    val r = reader ?: error("Bridge reader unavailable")

                    w.write(payload)
                    w.newLine()
                    w.flush()

                    val responseLine = r.readLine()
                        ?: run {
                            closeLocked()
                            error("Root bridge terminated — ensure root is granted to Silo in KernelSU-Next and Silo module is installed.")
                        }

                    val resp = json.decodeFromString<IpcEnvelopeResponse>(responseLine)
                    if (!resp.ok) {
                        throw DaemonRpcException(resp.error ?: "Daemon returned an unknown error")
                    }
                    resp.data
                }
            }.onFailure {
                // Keep bridge open for structured daemon RPC errors; reset on transport/timeout failures
                if (it !is DaemonRpcException) {
                    closeLocked()
                }
            }
        }
    }

    suspend inline fun <reified T> call(
        cmd: String,
        params: JsonElement? = null,
        timeoutMs: Long = 10_000L,
    ): Result<T> {
        return callRaw(cmd, params, timeoutMs).mapCatching { elem ->
            requireNotNull(elem) { "Empty response payload for $cmd" }
            json.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), elem)
        }
    }

    private fun ensureBridgeConnected() {
        val current = process
        if (current != null && current.isAlive && writer != null && reader != null) {
            return
        }
        closeLocked()

        val pb = ProcessBuilder("su", "-c", "$silodPath --bridge")
        pb.redirectErrorStream(false)
        val p = try {
            pb.start()
        } catch (e: Exception) {
            error("Unable to invoke su: ${e.message ?: "KernelSU root unavailable"}")
        }

        process = p
        writer = BufferedWriter(OutputStreamWriter(p.outputStream, Charsets.UTF_8))
        reader = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8))
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        mutex.withLock { closeLocked() }
    }

    private fun closeLocked() {
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        runCatching { process?.destroy() }
        writer = null
        reader = null
        process = null
    }
}
