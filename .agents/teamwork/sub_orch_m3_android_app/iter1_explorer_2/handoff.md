# Explorer Handoff — `iter1_explorer_2` (Workstream 2: IPC Client, Repository, ViewModel Concurrency & TrafficScreen)

**Scope:** Complete, implementation-ready Kotlin & JVM unit-test blueprint for:
- `app/app/src/main/java/dev/silo/app/ipc/SiloClient.kt` (`APP-C03`, `APP-H02`, `APP-H03`, `APP-M11`, `PROJECT.md § Interface Contracts § 1`)
- `app/app/src/main/java/dev/silo/app/data/SiloRepository.kt` (`APP-H20`, `APP-M05`, `APP-M07`, `APP-M18`)
- `app/app/src/main/java/dev/silo/app/ui/SiloViewModel.kt` (`APP-C04`, `APP-C05`, `APP-C06`, `APP-H04`, `APP-H05`, `APP-H11`, `APP-H12`, `APP-M01`, `APP-M02`, `APP-M03`, `APP-M04`, `APP-M05`, `APP-M06`, `APP-M14`, `APP-M15`, `APP-M16`, `APP-M17`, `APP-M18`, `APP-M19`)
- `app/app/src/main/java/dev/silo/app/ui/screens/TrafficScreen.kt` (`APP-C04`, `APP-H05`, `APP-H06`, `APP-H12`, `APP-H13`, `APP-H14`, `APP-M16`, `APP-M17`, `APP-L06`)
- `app/app/src/main/java/dev/silo/app/SiloApp.kt` (verified singleton lifecycle)
- `app/app/src/test/java/dev/silo/app/` (concrete JVM unit-test specifications)

---

## 0. Cross-Workstream Contracts & Verified Symbol Alignment

Before implementing Workstream 2, note these verified symbol/contract dependencies shared with Workstream 1 (`AppInventory.kt`, `SiloModels.kt`, `AppsScreen.kt`, `SystemScreen.kt`) and M1 (`daemon/`):

1. **Exact Freezer DTO class name (`SiloModels.kt:88`)**: The model is `FreezerEntry` (NOT `FreezerAppEntry`). `ensureEntryAndFreeze(uid: Int, entry: FreezerEntry)` MUST use `FreezerEntry`.
2. **`AppInventory` helper methods required from Workstream 1 (`AppInventory.kt`)**:
   - Currently `protectedPackages` (`AppInventory.kt:44`) is `private` and there is no `isProtectedPackage` or `reloadSingleApp`.
   - Workstream 1 MUST expose (or Workstream 2 coordinate with Workstream 1):
     ```kotlin
     fun isProtectedPackage(packageName: String?): Boolean =
         !packageName.isNullOrBlank() && protectedPackages.contains(packageName.trim())

     suspend fun reloadSingleApp(context: Context, packageName: String): InstalledApp?
     ```
3. **`CategoryPermLiveState` field naming (`SiloModels.kt:227-232` vs `daemon/src/protocol.rs:603-608`)**:
   - Existing wire field is `@SerialName("last_access") val lastAccess: String? = null` (NOT `last_access_ago`). Preserve `last_access` and add additive fields with defaults (`runtimeGranted: Boolean? = null`, `hasRuntimePerms: Boolean = true`, `supportsForeground: Boolean = false`, `systemFixed: Boolean = false`).
4. **`FirewallConfig` & `DaemonStatus` additive field (`PROJECT.md § Interface Contracts § 3`)**:
   - `@SerialName("panic_lockdown_active") val panicLockdownActive: Boolean = false` on both DTOs (`SiloModels.kt:69, 264`).
5. **`SiloApp.kt` (`SiloApp.kt:1-9`)**:
   - Verified singleton `val repository: SiloRepository by lazy { SiloRepository() }` shared by `SiloViewModel` and all 3 Quick Settings `TileService`s (`LockdownTileService`, `SensorsOffTileService`, `PanicRebootTileService`).
   - Hardening `SiloClient` (cancellable timeout, stderr drain, `resp.id == reqId`, clean teardown) automatically protects both the UI and all 3 QS tiles. No edits required in `SiloApp.kt`.
6. **Build / SDK Baseline (`app/app/build.gradle.kts:14, 95-96`)**:
   - `minSdk = 31`, `compileSdk = 35`, Java/Kotlin 17, `junit:4.13.2`, `kotlinx-coroutines-test:1.9.0` already declared. `java.time.format.DateTimeFormatter` + `Instant` + `ZoneId` are natively available without desugaring.

---

## 1. `SiloClient.kt` Remediation Blueprint (`APP-C03`, `APP-H02`, `APP-H03`, `APP-M11`)

### 1.1 Observed Defects (Exact Lines)
- **`SiloClient.kt:51-77` (`APP-C03`)**: `withTimeout(timeoutMs)` sits *inside* `mutex.withLock` and wraps synchronous blocking Java I/O (`ensureBridgeConnected()`, `w.write()`, `r.readLine()`) with zero suspension points. Timeout never interrupts a stalled `su`/`silod --bridge`; `mutex` stays locked indefinitely and freezes every control in the app. Also, callers waiting to enter `mutex.withLock` are not bounded by `timeoutMs`.
- **`SiloClient.kt:105` (`PROJECT.md § Interface Contracts § 1`)**: Uses `ProcessBuilder("su", "-c", "$silodPath --bridge")` without `exec`, keeping an intermediate shell process alive and causing `process.destroy()` to signal `sh` rather than `silod --bridge`.
- **`SiloClient.kt:106-115` (`APP-H02`)**: `pb.redirectErrorStream(false)` leaves `p.errorStream` completely unread. Emitting >64 KB on stderr deadlocks the subprocess pipe, and `su` root-denial/module diagnostics are silently dropped on EOF.
- **`SiloClient.kt:72-76` (`APP-H03`)**: Decodes `IpcEnvelopeResponse` without checking `resp.id == reqId` and without explicitly rejecting non-JSON preamble lines.
- **`SiloClient.kt:122-129` (`APP-M11`)**: `closeLocked()` only calls `process?.destroy()` (SIGTERM, no grace wait or `destroyForcibly()`) and never closes `process?.errorStream`.

### 1.2 Critical JVM / ART Deadlock Avoidance Note
On OpenJDK and Android ART, `BufferedReader.readLine()` acquires `synchronized (lock)` across the underlying blocking `InputStream.read()`, and `BufferedReader.close()` ALSO acquires `synchronized (lock)`.
- **NEVER** call `reader.close()` from the timeout/cancellation watchdog thread while the `Dispatchers.IO` thread is blocked inside `reader.readLine()`, or the watchdog thread (`kotlinx.coroutines.DefaultExecutor`) will deadlock!
- **Safe interruption sequence**:
  1. Watchdog (`invokeOnCompletion`) calls **ONLY** non-blocking `handle.abortNow()` -> `process.destroyForcibly()` (`SIGKILL`), which immediately closes the child's stdout/stdin pipe ends in the Linux kernel.
  2. Kernel immediately unblocks `reader.readLine()` (EOF / `IOException`) or `writer.write()` (`EPIPE`) on the `Dispatchers.IO` thread with zero lock contention.
  3. The `Dispatchers.IO` thread exits the blocked I/O call, releases `BufferedReader.lock`, and runs `closeLocked(gracefulWaitMs = 0L)` while still holding `mutex` to close `writer`, `reader`, `errorStream`, join `stderrThread` briefly, reap `process`, and set `bridge = null`.

### 1.3 Concrete Implementation Blueprint (`SiloClient.kt`)

```kotlin
package dev.silo.app.ipc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
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

class SiloClient(
    private val silodPath: String = "/data/adb/modules/silo/bin/silod",
    private val processFactory: () -> Process = {
        ProcessBuilder("su", "-c", "exec $silodPath --bridge")
            .redirectErrorStream(false)
            .start()
    },
) {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private class BridgeHandle(
        val process: Process,
        val writer: BufferedWriter,
        val reader: BufferedReader,
        val stderrBuffer: BoundedStderrBuffer,
        val stderrThread: Thread,
    ) {
        private val closed = AtomicBoolean(false)

        /** Non-blocking abort safe to call from watchdog / invokeOnCompletion without touching BufferedReader.lock. */
        fun abortNow() {
            runCatching { process.destroyForcibly() }
        }

        /** Full teardown called from the IO thread after readLine()/write() has returned (APP-M11). */
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
        try {
            val data = withTimeout(effectiveTimeoutMs) {
                val timeoutJob = coroutineContext[Job]
                mutex.withLock {
                    executeLocked(cmd, params, timeoutJob)
                }
            }
            Result.success(data)
        } catch (e: TimeoutCancellationException) {
            if (callerJob?.isCancelled == true) throw e
            Result.failure(IOException("RPC '$cmd' timed out after ${effectiveTimeoutMs}ms", e))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
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

    private fun executeLocked(
        cmd: String,
        params: JsonElement?,
        timeoutJob: Job?,
    ): JsonElement? {
        val ioDone = AtomicBoolean(false)
        val aborted = AtomicBoolean(false)
        val cancelCause = AtomicReference<Throwable?>(null)
        val activeHandle = AtomicReference<BridgeHandle?>(null)

        val reg = timeoutJob?.invokeOnCompletion { cause ->
            if (cause != null && !ioDone.get()) {
                cancelCause.compareAndSet(null, cause)
                aborted.set(true)
                activeHandle.get()?.abortNow()
                bridge?.abortNow()
            }
        }

        try {
            if (aborted.get() || timeoutJob?.isActive == false) {
                throw cancelCause.get() ?: CancellationException("Cancelled before bridge connect")
            }

            val handle = ensureBridgeConnectedLocked()
            activeHandle.set(handle)

            // Guard race where timeout/cancel fired during processFactory()
            if (aborted.get() || timeoutJob?.isActive == false) {
                closeLocked(gracefulWaitMs = 0L)
                throw cancelCause.get() ?: CancellationException("Cancelled during bridge connect")
            }

            val reqId = nextId.getAndIncrement()
            val payload = json.encodeToString(
                IpcEnvelopeRequest(id = reqId, cmd = cmd, params = params)
            )

            handle.writer.write(payload)
            handle.writer.newLine()
            handle.writer.flush()

            val responseLine = handle.reader.readLine()
            if (aborted.get()) {
                closeLocked(gracefulWaitMs = 0L)
                throw cancelCause.get() ?: CancellationException("Cancelled during RPC read")
            }

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
                val stderrSnippet = handle.stderrBuffer.snapshot()
                closeLocked(gracefulWaitMs = 0L)
                if (stderrSnippet.isNotBlank()) {
                    throw BridgeProtocolException("${e.message} [stderr: ${stderrSnippet.take(300)}]", e)
                }
                throw e
            }

            if (!resp.ok) {
                throw DaemonRpcException(resp.error ?: "Daemon returned an unknown error")
            }
            return resp.data
        } catch (t: Throwable) {
            if (t !is DaemonRpcException) {
                closeLocked(gracefulWaitMs = 0L)
            }
            if (aborted.get()) {
                val cause = cancelCause.get()
                if (cause is CancellationException) throw cause
            }
            throw t
        } finally {
            ioDone.set(true)
            reg?.dispose()
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
        /**
         * Pure testable JSON-RPC envelope parser & response ID validator (APP-H03).
         * Rejects non-JSON preambles, malformed JSON, and mismatched resp.id != expectedId.
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
```

### 1.4 Edge-Case Proof Matrix for `SiloClient`
| Scenario | Behavior |
|---|---|
| Caller waits on `mutex.withLock` > `timeoutMs` | `withTimeout` cancels `mutex.lock()` immediately -> returns `Result.failure(IOException("RPC ... timed out"))`, never leaks lock. |
| Bridge stalls inside `r.readLine()` | `withTimeout` fires `invokeOnCompletion` -> `handle.abortNow()` (`destroyForcibly()`) -> kernel closes pipe -> `readLine()` unblocks immediately -> `closeLocked(0L)` clears `bridge` -> `mutex` unlocks -> `callRaw` returns `Result.failure(IOException("RPC ... timed out"))` within `timeoutMs`. |
| Parent job cancelled (`monitorJob.cancel()`) mid-read | `invokeOnCompletion` calls `abortNow()` -> `readLine()` unblocks -> `closeLocked(0L)` cleans up -> `CancellationException` rethrown cleanly to honour structured concurrency. |
| `su` emits >64 KB on stderr | `silo-bridge-stderr` daemon thread drains continuously into 2 KB ring buffer; zero pipe deadlock; last 2 KB preserved for error reporting. |
| `su` denied / binary missing (EOF) | `stderrThread.join(50L)` captures trailing stderr -> `IOException("Root bridge terminated ... [stderr: ...]")`. |
| Non-JSON banner or `resp.id != reqId` | `parseAndValidateEnvelope` throws `BridgeProtocolException` -> `closeLocked(0L)` resets desynced bridge -> caller receives descriptive failure. |
| Daemon returns `{"id":N,"ok":false,"error":"..."}` | `resp.id == reqId` passes -> throws `DaemonRpcException` -> bridge stays open and healthy for subsequent calls. |

---

## 2. `SiloRepository.kt` Remediation Blueprint (`APP-H20`, `APP-M05`, `APP-M07`, `APP-M18`)

### 2.1 Alignment with `daemon/src/config.rs:731-817` (`validate_cidr`)
Direct verification of `daemon/src/config.rs` establishes these exact rules:
- Max entry length: `64` chars (`config.rs:733`).
- IPv4 (`config.rs:744-769`):
  - 4 decimal octets `0..255`, no leading zeros (Rust `Ipv4Addr::from_str` rejects `01.2.3.4`).
  - Prefix (if `/p` present): `1..=32` (`prefix == 0 || prefix > 32` rejected).
  - Reject loopback (`octets[0] == 127`) and unspecified (`0.0.0.0`).
  - Mask IP by `prefix`; reject if `masked[0] == 127 || (prefix <= 8 && masked[0] == 0)`.
- IPv6 (`config.rs:770-795`):
  - Pure RFC 4291 numeric syntax (NO DNS resolution, NO `%zone`, NO `:::`, at most one `::`, 8 16-bit hex groups when uncompressed or `<= 7` groups when compressed, optional trailing IPv4 dotted-quad counting as 2 groups).
  - Prefix (if `/p` present): `1..=128` (`prefix == 0 || prefix > 128` rejected).
  - Reject loopback (`::1`) and unspecified (`::`) both before and after masking by `prefix`.

### 2.2 Concrete `BlocklistParser` & Bounded Stream Blueprint (`SiloRepository.kt`)

```kotlin
data class BlocklistParseResult(
    val entries: List<String>,
    val truncatedByByteCap: Boolean = false,
    val truncatedByEntryCap: Boolean = false,
    val truncatedByStreamCap: Boolean = false,
)

object BlocklistParser {
    const val MAX_ENTRIES: Int = 50_000
    // Daemon MAX_IPC_LINE_BYTES = 4 MiB; keep serialized JSON safely under 3.5 MiB (APP-H20)
    const val MAX_SERIALIZED_JSON_BYTES: Int = 3_500_000
    const val MAX_STREAM_READ_BYTES: Long = 8L * 1024L * 1024L // 8 MiB SAF read cap (APP-M05)
    private const val MAX_LINE_CHARS: Int = 65_536
    private val tokenSplitRegex = Regex("""[\s,;]+""")

    /**
     * Strictly validates a single IPv4/IPv6 or CIDR token matching daemon/src/config.rs:validate_cidr.
     * Pure numeric parser — NEVER calls InetAddress.getByName (zero DNS lookups).
     * Returns normalized token if valid & safe, or null if invalid/unsafe.
     */
    fun validateEntry(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty() || s.length > 64) return null
        val slashIdx = s.indexOf('/')
        if (slashIdx != -1 && s.indexOf('/', slashIdx + 1) != -1) return null

        val ipPart = if (slashIdx == -1) s else s.substring(0, slashIdx)
        val prefixStr = if (slashIdx == -1) null else s.substring(slashIdx + 1)

        return if (ipPart.contains(':')) {
            if (isValidSafeIpv6(ipPart, prefixStr)) s.lowercase(java.util.Locale.ROOT) else null
        } else {
            if (isValidSafeIpv4(ipPart, prefixStr)) s else null
        }
    }

    private fun parsePrefix(prefixStr: String?, maxPrefix: Int): Int? {
        if (prefixStr == null) return maxPrefix
        if (prefixStr.isEmpty() || prefixStr.length > 3) return null
        if (prefixStr.length > 1 && prefixStr[0] == '0') return null
        var value = 0
        for (ch in prefixStr) {
            if (ch !in '0'..'9') return null
            value = value * 10 + (ch - '0')
        }
        return if (value in 1..maxPrefix) value else null
    }

    private fun parseIpv4Octets(ip: String): IntArray? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0 until 4) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3) return null
            if (part.length > 1 && part[0] == '0') return null
            var v = 0
            for (ch in part) {
                if (ch !in '0'..'9') return null
                v = v * 10 + (ch - '0')
            }
            if (v !in 0..255) return null
            octets[i] = v
        }
        return octets
    }

    private fun isValidSafeIpv4(ipPart: String, prefixStr: String?): Boolean {
        val octets = parseIpv4Octets(ipPart) ?: return false
        val prefix = parsePrefix(prefixStr, 32) ?: return false

        val ipBits = (octets[0].toLong() shl 24) or
            (octets[1].toLong() shl 16) or
            (octets[2].toLong() shl 8) or
            octets[3].toLong()
        // Reject loopback (127.0.0.0/8) and unspecified (0.0.0.0)
        if (octets[0] == 127 || ipBits == 0L) return false

        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val masked = ipBits and mask
        val m0 = ((masked ushr 24) and 0xFFL).toInt()
        if (m0 == 127 || (prefix <= 8 && m0 == 0)) return false
        return true
    }

    private fun parseHexGroup(group: String): Int? {
        if (group.isEmpty() || group.length > 4) return null
        var v = 0
        for (ch in group) {
            val digit = when (ch) {
                in '0'..'9' -> ch - '0'
                in 'a'..'f' -> ch - 'a' + 10
                in 'A'..'F' -> ch - 'A' + 10
                else -> return null
            }
            v = (v shl 4) or digit
        }
        return v
    }

    private fun parseIpv6Half(side: String, allowTrailingIpv4: Boolean): List<Int>? {
        if (side.isEmpty()) return emptyList()
        val rawGroups = side.split(':')
        val out = ArrayList<Int>(rawGroups.size + 1)
        for (i in rawGroups.indices) {
            val g = rawGroups[i]
            if (g.isEmpty()) return null // catches stray single ':' or ':::'
            if (g.contains('.')) {
                if (!allowTrailingIpv4 || i != rawGroups.lastIndex) return null
                val octets = parseIpv4Octets(g) ?: return null
                out.add((octets[0] shl 8) or octets[1])
                out.add((octets[2] shl 8) or octets[3])
            } else {
                out.add(parseHexGroup(g) ?: return null)
            }
        }
        return out
    }

    private fun isValidSafeIpv6(ipPart: String, prefixStr: String?): Boolean {
        if (ipPart.contains(":::")) return false
        if (ipPart.startsWith(":") && !ipPart.startsWith("::")) return false
        if (ipPart.endsWith(":") && !ipPart.endsWith("::")) return false

        val prefix = parsePrefix(prefixStr, 128) ?: return false

        val firstDc = ipPart.indexOf("::")
        val words = IntArray(8)
        if (firstDc == -1) {
            val groups = parseIpv6Half(ipPart, allowTrailingIpv4 = true) ?: return false
            if (groups.size != 8) return false
            for (i in 0 until 8) words[i] = groups[i]
        } else {
            // Reject multiple "::"
            if (ipPart.indexOf("::", firstDc + 2) != -1) return false
            val leftStr = ipPart.substring(0, firstDc)
            val rightStr = ipPart.substring(firstDc + 2)
            val left = parseIpv6Half(leftStr, allowTrailingIpv4 = false) ?: return false
            val right = parseIpv6Half(rightStr, allowTrailingIpv4 = true) ?: return false
            if (left.size + right.size >= 8) return false // "::" must compress >= 1 group
            for (i in left.indices) words[i] = left[i]
            val rightStart = 8 - right.size
            for (i in right.indices) words[rightStart + i] = right[i]
        }

        if (isIpv6Unspecified(words) || isIpv6Loopback(words)) return false

        val masked = IntArray(8)
        for (i in 0 until 8) {
            val bitStart = i * 16
            masked[i] = when {
                prefix <= bitStart -> 0
                prefix >= bitStart + 16 -> words[i]
                else -> {
                    val keep = prefix - bitStart
                    words[i] and ((0xFFFF shl (16 - keep)) and 0xFFFF)
                }
            }
        }
        if (isIpv6Unspecified(masked) || isIpv6Loopback(masked)) return false
        return true
    }

    private fun isIpv6Unspecified(w: IntArray): Boolean = w.all { it == 0 }

    private fun isIpv6Loopback(w: IntArray): Boolean =
        w[0] == 0 && w[1] == 0 && w[2] == 0 && w[3] == 0 &&
            w[4] == 0 && w[5] == 0 && w[6] == 0 && w[7] == 1

    /**
     * Extracts ALL valid IPv4/IPv6/CIDR tokens from multi-IP lines after stripping inline '#' and '//'
     * comments and skipping full-line ';', '#', '//' comments. Enforces entry & JSON-byte caps.
     */
    fun parseLinesDetailed(
        rawText: String,
        maxEntries: Int = MAX_ENTRIES,
        maxJsonBytes: Int = MAX_SERIALIZED_JSON_BYTES,
    ): BlocklistParseResult {
        val out = LinkedHashSet<String>()
        var approxJsonBytes = 128 // envelope overhead
        var hitByteCap = false
        var hitEntryCap = false

        outer@ for (line in rawText.lineSequence()) {
            val trimmedStart = line.trimStart()
            if (trimmedStart.isEmpty() ||
                trimmedStart.startsWith("#") ||
                trimmedStart.startsWith("//") ||
                trimmedStart.startsWith(";")
            ) continue

            val stripped = trimmedStart
                .substringBefore('#')
                .substringBefore("//")
                .trim()
            if (stripped.isEmpty()) continue

            for (tok in stripped.split(tokenSplitRegex)) {
                if (tok.isEmpty()) continue
                val valid = validateEntry(tok) ?: continue
                if (out.contains(valid)) continue
                if (out.size >= maxEntries) {
                    hitEntryCap = true
                    break@outer
                }
                val entryBytes = valid.length + 3 // `"...",`
                if (approxJsonBytes + entryBytes > maxJsonBytes) {
                    hitByteCap = true
                    break@outer
                }
                out.add(valid)
                approxJsonBytes += entryBytes
            }
        }
        return BlocklistParseResult(
            entries = out.toList(),
            truncatedByByteCap = hitByteCap,
            truncatedByEntryCap = hitEntryCap,
        )
    }

    /** Backward-compatible convenience API for existing callers & unit tests. */
    fun parseLines(
        rawText: String,
        maxEntries: Int = MAX_ENTRIES,
        maxJsonBytes: Int = MAX_SERIALIZED_JSON_BYTES,
    ): List<String> = parseLinesDetailed(rawText, maxEntries, maxJsonBytes).entries

    /**
     * Streaming SAF-safe parser enforcing an 8 MiB raw byte cap + 3.5 MiB JSON payload cap
     * without allocating the entire input file in RAM (APP-M05, APP-H20).
     */
    fun parseStream(
        inputStream: java.io.InputStream,
        maxEntries: Int = MAX_ENTRIES,
        maxJsonBytes: Int = MAX_SERIALIZED_JSON_BYTES,
        maxReadBytes: Long = MAX_STREAM_READ_BYTES,
    ): BlocklistParseResult {
        var streamTruncated = false
        val bounded = object : java.io.InputStream() {
            private var remaining = maxReadBytes
            override fun read(): Int {
                if (remaining <= 0L) {
                    if (inputStream.read() != -1) streamTruncated = true
                    return -1
                }
                val b = inputStream.read()
                if (b != -1) remaining--
                return b
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len <= 0) return 0
                if (remaining <= 0L) {
                    if (inputStream.read() != -1) streamTruncated = true
                    return -1
                }
                val toRead = minOf(len.toLong(), remaining).toInt()
                val n = inputStream.read(b, off, toRead)
                if (n > 0) remaining -= n
                return n
            }
            override fun close() { inputStream.close() }
        }

        val out = LinkedHashSet<String>()
        var approxJsonBytes = 128
        var hitByteCap = false
        var hitEntryCap = false

        java.io.BufferedReader(java.io.InputStreamReader(bounded, Charsets.UTF_8)).use { br ->
            val lineBuf = StringBuilder(256)
            outer@ while (true) {
                lineBuf.setLength(0)
                var chInt = br.read()
                if (chInt == -1) break
                while (chInt != -1 && chInt != '\n'.code) {
                    if (chInt != '\r'.code && lineBuf.length < MAX_LINE_CHARS) {
                        lineBuf.append(chInt.toChar())
                    }
                    chInt = br.read()
                }
                val trimmedStart = lineBuf.toString().trimStart()
                if (trimmedStart.isEmpty() ||
                    trimmedStart.startsWith("#") ||
                    trimmedStart.startsWith("//") ||
                    trimmedStart.startsWith(";")
                ) continue

                val stripped = trimmedStart
                    .substringBefore('#')
                    .substringBefore("//")
                    .trim()
                if (stripped.isEmpty()) continue

                for (tok in stripped.split(tokenSplitRegex)) {
                    if (tok.isEmpty()) continue
                    val valid = validateEntry(tok) ?: continue
                    if (out.contains(valid)) continue
                    if (out.size >= maxEntries) {
                        hitEntryCap = true
                        break@outer
                    }
                    val entryBytes = valid.length + 3
                    if (approxJsonBytes + entryBytes > maxJsonBytes) {
                        hitByteCap = true
                        break@outer
                    }
                    out.add(valid)
                    approxJsonBytes += entryBytes
                }
            }
        }
        return BlocklistParseResult(
            entries = out.toList(),
            truncatedByByteCap = hitByteCap,
            truncatedByEntryCap = hitEntryCap,
            truncatedByStreamCap = streamTruncated,
        )
    }
}
```

### 2.3 `SiloRepository.kt` Timeout Audit (Aligned with Daemon `--bridge` `read_timeout = 45s`)
Update timeouts in `SiloRepository.kt` (all slow RPCs strictly `<= 40s < 45s` daemon bridge limit):

| Method (`SiloRepository.kt`) | Current Timeout | Target Timeout | Rationale |
|---|---|---|---|
| Default (`SiloClient.callRaw`) | `10_000L` | `12_000L` | Fast RPCs (`get_status`, `get_config`, `set_firewall_config`, etc.) |
| `applyPreset` (line 90) | `10_000L` (default) | `25_000L` | Re-syncs firewall + hardening + freezer + automation |
| `replaceIpBlocklist` (line 125) | `25_000L` | `35_000L` | Up to 50k entries via batched ipset netlink |
| `inspectAppPermissions` (line 205) | `10_000L` (default) | `15_000L` | `cmd appops get` + `dumpsys package` |
| `setAppPermissionAction` (line 214) | `10_000L` (default) | `15_000L` | `pm revoke`/`grant` + `--uid` AppOps + live inspect |
| `setAppPermGuard` (line 221) | `10_000L` (default) | `25_000L` | May revoke multiple `locked_deny` categories + flags |
| `bulkRevokeCategory` (line 230) | `25_000L` | `35_000L` | Multiple packages sequentially |
| `getPrivacyAccessLog` (line 235) | `15_000L` | `25_000L` | 11 `dumpsys appops --op` queries on One UI 7 |
| `triggerPanicLockdown` (line 291) | `15_000L` | `25_000L` | Firewall + SOCK_DESTROY + sensors + radios + clipboard |
| `runSentinelAudit` (line 299) | `15_000L` | `25_000L` | SELinux + CA + sockets + proc scan |
| `runLiveProof` (line 302) | `15_000L` | `25_000L` | Live iptables + cgroups + appops + sysctl verification |
| `importConfigJson` (line 312) | `20_000L` | `30_000L` | Full daemon config restore & subsystem sync |

### 2.4 Legacy `scopes.apps` (`APP-M18`)
- Keep `clearAppScope(packageName: String)` (`SiloRepository.kt:199-202`) so `SiloViewModel.clearLegacyAppScope(packageName)` and `AppsScreen` can clear legacy v2 `scopes.apps` entries if present in imported/migrated configs.
- Keep `setAppScope`/`applyHardenedScope` annotated `@Deprecated("Legacy v2 scope API; use setAppPermissionAction / setAppPermGuard")` or remove if unused across `app/` (verified: zero callers anywhere in `app/`).

---

## 3. `SiloViewModel.kt` Remediation Blueprint (All 17+ ViewModel Defects)

### 3.1 `SheetFeedback` & Expanded `SiloUiState`
Add at top of `SiloViewModel.kt` (before line 21):
```kotlin
data class SheetFeedback(
    val id: Long,
    val message: String,
    val isError: Boolean,
)
```
Expand `SiloUiState` (`SiloViewModel.kt:21-41`) with:
- `val loadingAccessLog: Boolean = false` (`APP-M16`)
- `val accessLogError: String? = null` (`APP-H12`)
- `val loadingAppPerms: Set<String> = emptySet()` (`APP-M16`)
- `val appPermErrors: Map<String, String> = emptyMap()` (`APP-H12`)
- `val pendingPermKeys: Set<String> = emptySet()` (`APP-H04`, `APP-H05`)
- `val liveProofLoading: Boolean = false` (`APP-M12`)
- `val sentinelLoading: Boolean = false` (`APP-M13`)
- `val sheetFeedback: SheetFeedback? = null` (`APP-C06`)
- `val activeInspectedPackage: String? = null` (`APP-H11`)

Add public UI helpers:
- `fun clearSheetFeedback() { _ui.update { it.copy(sheetFeedback = null) } }`
- `fun setActiveInspectedPackage(packageName: String?) { _ui.update { it.copy(activeInspectedPackage = packageName) } }`
- `fun setMonitorAccessTabActive(active: Boolean) { monitorAccessTabActive = active }`

### 3.2 Concurrency & Priority Fields (`APP-C04`, `APP-C05`, `APP-H11`, `APP-M01`)
```kotlin
private val configMutex = Mutex()
private val inFlightMutations = AtomicInteger(0)
private val feedbackSeq = AtomicLong(1L)
private val consecutiveStatusFailures = AtomicInteger(0)

private var monitorJob: Job? = null
private var accessLogJob: Job? = null
private var foregroundSyncJob: Job? = null
private var resumeReloadJob: Job? = null
private var refreshAllJob: Job? = null

@Volatile private var monitorActive: Boolean = false
@Volatile private var monitorAccessTabActive: Boolean = false
@Volatile private var lastFullRefreshMs: Long = 0L

private fun nowMs(): Long = System.nanoTime() / 1_000_000L
private fun strings(): SiloStrings = resolveStrings(_ui.value.languagePref)
private fun loc(id: String, en: String): String = if (strings().isIndonesian) id else en

private fun emitFeedback(message: String, isError: Boolean = false, inSheet: Boolean = false) {
    _ui.update { cur ->
        cur.copy(
            snackbarMessage = message,
            sheetFeedback = if (inSheet) SheetFeedback(feedbackSeq.getAndIncrement(), message, isError) else cur.sheetFeedback,
        )
    }
}

private suspend inline fun <T> withMutationTracking(block: suspend () -> T): T {
    inFlightMutations.incrementAndGet()
    try {
        return block()
    } finally {
        inFlightMutations.decrementAndGet()
    }
}

private fun recordStatusFailure(err: Throwable?) {
    val count = consecutiveStatusFailures.incrementAndGet()
    if (count >= 2) {
        val s = strings()
        val detail = err?.message?.takeIf { it.isNotBlank() } ?: s.daemonOfflineDesc
        _ui.update { it.copy(connected = false, connectionError = detail) }
    }
}
```

### 3.3 Method-by-Method Blueprint (Every Method in `SiloViewModel.kt`)

| Line(s) | Method | Exact Bug(s) | Exact Fix Strategy |
|---|---|---|---|
| `83-115` | `onForegroundChanged(isResumed)` | `APP-M01` (doubles cold start), `APP-H11` (never refreshes `liveAppPerms`/`freezerStatus`/`hardeningStatus`/`privacyAccessLog`; sticks `connected = true` on daemon death) | Cancel `foregroundSyncJob` & `resumeReloadJob`. If `!isResumed` return. Skip immediate reload if `refreshAllJob?.isActive == true || (nowMs() - lastFullRefreshMs) < 1_500L`. Otherwise launch `resumeReloadJob`: load apps, call `repo.getStatus()`; on success reset `consecutiveStatusFailures`, fetch `config`, `freezerStatus`, `hardeningStatus`, update `_ui` (`connected = true, connectionError = null`), refresh `activeInspectedPackage` live perms if set, and call `refreshPrivacyAccessLog()` if `monitorActive && monitorAccessTabActive`; on failure call `recordStatusFailure(err)` (still updating `apps`). In 4s `foregroundSyncJob`: `if (inFlightMutations.get() > 0 || !_ui.value.connected) continue`; call `repo.getStatus()`, reset failures on success, call `recordStatusFailure(err)` on failure. |
| `117-164` | `refreshAll()` | `APP-M01`, `APP-M17` (ignores `activeInspectedPackage` and `privacyAccessLog`), `APP-M15` | Deduplicate via `if (refreshAllJob?.isActive == true) return`. Record `lastFullRefreshMs = nowMs()`. On status failure set `connected = false` with localized fallback (`strings().daemonOfflineDesc`). On success reset `consecutiveStatusFailures`, update all state, AND if `_ui.value.activeInspectedPackage != null` await `inspectAppPermissionsSuspend(pkg)`, AND if `monitorActive && monitorAccessTabActive` trigger `refreshPrivacyAccessLog(force = true)`. |
| `166-201` | `setMonitorPollingActive`, `refreshMonitorOnce`, `refreshPrivacyAccessLog` | `APP-C04` (non-suspending fire-and-forget 3s loop + eager `refreshPrivacyAccessLog` causing mutex pileup), `APP-H12` (silent failure), `APP-M16` (no spinner) | Make `suspend fun refreshMonitorOnce()` genuinely suspending, skipping if `inFlightMutations.get() > 0`, updating `consecutiveStatusFailures` on status failure. `setMonitorPollingActive(true)` runs `while (isActive)` calling `refreshMonitorOnce()` then `delay(3_000L)` (NO eager `refreshPrivacyAccessLog()`). `setMonitorPollingActive(false)` cancels BOTH `monitorJob` and `accessLogJob`. Deduplicate `refreshPrivacyAccessLog(force: Boolean = false)` via `accessLogJob`, toggle `loadingAccessLog`, capture `accessLogError` + emit localized error toast on `.onFailure` (rethrowing `CancellationException`). |
| `203-213` | `applyPreset(preset)` | `APP-M03` (stale `hardeningStatus`/`freezerStatus`/`sentinelReport`), `APP-C05`, `APP-M15` | Run inside `withMutationTracking` + `configMutex.withLock`. On success update `config = newCfg`, refresh `hardeningStatus`, `freezerStatus`, `sentinelReport` (`sentinelLoading`), and `refreshLightStatus()`. Emit localized preset name via `strings()` (`s.presetSafe`..`s.presetLockdown`). |
| `215-225` | `updateFirewallConfig(transform)` | `APP-C05` (captures `_ui.value` before coroutine, no rollback/optimistic), `APP-M15` | Inside `withMutationTracking` + `configMutex.withLock`: read `prev = _ui.value.config.firewall`, compute `next = transform(prev)`, optimistically set `config.firewall = next`, call `repo.setFirewallConfig(next)`; on success commit `saved` + `refreshLightStatus()`; on failure rollback `config.firewall = prev` + emit localized error toast. |
| `227-240` | `updateAppFirewallRule(uid, rule)` + NEW `mutateAppFirewallRule(uid, pkg, transform)` + NEW `blockAppFromMonitor(uid, pkg, appLabel)` | `APP-C05`, `APP-H14`, `APP-C06`, `APP-M15` | Serialize all under `withMutationTracking` + `configMutex.withLock`. Snapshot `prevRule = _ui.value.config.firewall.rules[uid]`, apply optimistic rule update immediately, call `repo.setAppFirewallRule(uid, nextRule)`; on success call `refreshLightStatus()`; on failure restore `prevRule` (or remove if `prevRule == null`) + `emitFeedback(..., isError = true, inSheet = true)`. `blockAppFromMonitor` reads existing rule inside `configMutex` (preserving `isVpnApp`, `allowLan`, `blockCleartextHttp`, `blockLocalhost`) and sets `allowWifi=false, allowCellular=false, allowVpn=false, allowScreenOff=false`, emitting localized confirmation toast (`"Internet diblokir untuk $appLabel"` / `"Blocked internet for $appLabel"`). |
| `242-255` | `removeAppFirewallRule(uid)` | `APP-C05`, `APP-C06`, `APP-M15` | Inside `withMutationTracking` + `configMutex.withLock`: snapshot `prevRule`, optimistically remove `uid`, call `repo.removeAppFirewallRule(uid)`; rollback + `emitFeedback(..., isError = true, inSheet = true)` on failure. |
| `257-277` | `setTempUnblock`, `clearTempUnblock` | `APP-M15` (English-only toasts), `APP-C06` | Wrap in `withMutationTracking`; localize both success and error via `emitFeedback(..., inSheet = true)`. |
| `279-306` | `importIpBlocklistFromUri(uri)` | `APP-M05` (unbounded `.readText()` OOM), `APP-H20` (exceeds IPC limit), `APP-C06`, `APP-M15` | Stream via `contentResolver.openInputStream(uri)?.use { BlocklistParser.parseStream(it) }` on `Dispatchers.IO`. Serialize RPC under `withMutationTracking` + `configMutex.withLock`. Inspect `loaded` and `rejected`; refresh `config` + `refreshLightStatus()`; report truncation/rejected counts via localized `emitFeedback(..., inSheet = true)`. |
| `308-322` | `addSingleBlocklistCidr(cidr)` + NEW `removeSingleBlocklistCidr(cidr)` | `APP-C05`, `APP-M04` (ignores `rejected > 0`, no client validation), `APP-M14` (no delete), `APP-C06`, `APP-M15` | Client-side validate via `BlocklistParser.validateEntry(clean)` first -> immediate error `emitFeedback(..., isError = true, inSheet = true)` if invalid. Inside `withMutationTracking` + `configMutex.withLock`, read latest `ipBlocklist`, check duplicate, send `replaceIpBlocklist`, inspect `rejected = obj["rejected"]?.jsonPrimitive?.intOrNull ?: 0` -> show error if `rejected > 0`, else commit refreshed `config` + `refreshLightStatus()` + confirmation `emitFeedback`. Add `removeSingleBlocklistCidr(cidr)` filtering `it != cidr` under `configMutex`. |
| `324-333` | `clearIpBlocklist()` | `APP-H12` (missing `.onFailure`), `APP-C05`, `APP-C06`, `APP-M15` | Serialize under `withMutationTracking` + `configMutex.withLock`, add `.onFailure { emitFeedback(..., isError = true, inSheet = true) }`, localize success. |
| `335-346` | `emergencyFlushFirewall()` | `APP-C05`, `APP-M15` | Serialize under `withMutationTracking` + `configMutex.withLock`, refresh config & light status, localize success/error. |
| `348-368` | `killUidSockets`, `killConnection` | `APP-C04` (calls `refreshMonitorOnce`), `APP-C06`, `APP-M15` | Wrap in `withMutationTracking`, await `refreshMonitorOnce()` cleanly after mutation finishes (outside `withMutationTracking` so `refreshMonitorOnce` doesn't skip!), localize feedback (`inSheet = true` on `killUidSockets`). |
| `370-376` | `clearTelemetry()` | `APP-H12` (missing `.onFailure`), `APP-M15` | Add `.onFailure { emitFeedback(..., isError = true) }`, await `refreshMonitorOnce()` on success, localize confirmation. |
| `378-461` | `setFreezerEnabled`, `setFreezerEntry`, `removeFreezerEntry`, NEW `ensureEntryAndFreeze`, `freezeUidNow`, `thawUidNow`, `thawAllNow` | `APP-C05`, `APP-M02` (un-chained freeze race), `APP-M03` (`setFreezerEntry` missing `refreshLightStatus`), `APP-H12` (`removeFreezerEntry` & `thawAllNow` missing `.onFailure`), `APP-C06`, `APP-M15` | Serialize config mutations under `configMutex` with optimistic updates + rollback. Add `ensureEntryAndFreeze(uid: Int, entry: FreezerEntry)`: inside `withMutationTracking` + `configMutex.withLock`, if `entries[uid] != entry` await `repo.setFreezerEntry(uid, entry)` (abort & `emitFeedback(isError=true, inSheet=true)` on failure), then sequentially await `repo.freezeUidNow(uid)`, update `freezerStatus`, call `refreshLightStatus()`, emit localized feedback. Add `refreshLightStatus()` to `setFreezerEntry`. Add `.onFailure` to `removeFreezerEntry` and `thawAllNow`. |
| `465-475` | `inspectAppPermissions(pkg)` | `APP-H12` (missing `.onFailure`), `APP-M16` (no loading state), `APP-C06` | Extract `suspend fun inspectAppPermissionsSuspend(pkg)`: add `pkg` to `loadingAppPerms`, remove from `appPermErrors`, call `repo.inspectAppPermissions(pkg)`; on success store in `liveAppPerms`, remove from `loadingAppPerms`; on failure remove from `loadingAppPerms`, record `appPermErrors[pkg] = msg`, and `emitFeedback(..., isError = true, inSheet = true)`. |
| `477-491` | `setAppPermissionAction(pkg, cat, action)` | `APP-H04` (400-pkg scan, no pending spinner), `APP-C06`, `APP-M15`, `PROJECT.md § 2` (`allow` clears `locked_deny`) | Guard double-tap with `val key = "$pkg:$cat"`. Add `key` to `pendingPermKeys`. Inside `withMutationTracking`: if `action in setOf("allow", "default")` and `cat` in `guards[pkg]?.lockedDeny`, optimistically strip `cat` from `lockedDeny` under `configMutex` (keeping `prevGuards` for rollback). Call `repo.setAppPermissionAction(pkg, cat, action)`: on success update `liveAppPerms[pkg]`, clear `appPermErrors[pkg]`, call `reloadSingleAppIntoState(pkg)` (~5ms!), sync config if `lockedDeny` changed, call `refreshLightStatus()`, emit localized action confirmation via `emitFeedback(..., inSheet = true)`. On failure rollback `lockedDeny` if mutated + `emitFeedback(..., isError = true, inSheet = true)`. Finally remove `key` from `pendingPermKeys`. |
| `NEW` | `revokeFromAccessLog(pkg, cat)` | `APP-H05` (Monitor `Akses Izin` race, no spinner, no toast) | Key `"$pkg:$cat"`, ignore if already in `pendingPermKeys`. Add to `pendingPermKeys`. Inside `withMutationTracking`: call `repo.setAppPermissionAction(pkg, cat, "revoke")`. On success: update `liveAppPerms[pkg]`, call `reloadSingleAppIntoState(pkg)`, cancel stale `accessLogJob` and sequentially await `refreshPrivacyAccessLogSuspend()`, call `refreshLightStatus()`, emit localized confirmation toast (`"Izin ${s.categoryLabel(cat)} pada $label dicabut"` / `"Revoked ${s.categoryLabel(cat)} for $label"`). On failure emit localized error toast. Finally remove key from `pendingPermKeys`. |
| `493-529` | `toggleAppAutoRevoke`, `toggleAppStrictLock`, `saveAppPermGuard` | `APP-C05` (lost update), `APP-H04` (400-pkg scan), `APP-M19` (not mutually exclusive), `APP-C06`, `APP-M15` | Key `"$pkg:$cat:guard"`. Move reading `_ui.value.config.scopes.guards[pkg]` **INSIDE** `configMutex.withLock`! Enforce mutual exclusivity: enabling `autoRevokeOnBg` strips `cat` from `lockedDeny`; enabling `lockedDeny` strips `cat` from `autoRevokeOnBg`. Apply optimistic `guards` update immediately; call `repo.setAppPermGuard(pkg, nextGuard)`. On success: commit server `scopes`, call `reloadSingleAppIntoState(pkg)` (~5ms!), await `inspectAppPermissionsSuspend(pkg)`, call `refreshLightStatus()`, emit localized `emitFeedback(..., inSheet = true)`. On failure: rollback `guards` to `prevGuards` + `emitFeedback(..., isError = true, inSheet = true)`. Finally remove key from `pendingPermKeys`. |
| `531-547` | `revokeAllSensitiveForApp(app)` | `APP-H04` (400-pkg scan, no pending state), `APP-C06`, `APP-M15` | Key `"${app.packageName}:all"`. Track `successCount`, `failCount`, `lastErr`, `lastLive`. Update `liveAppPerms[app.packageName]` (via `lastLive` or `inspectAppPermissionsSuspend`), call `reloadSingleAppIntoState(app.packageName)` (~5ms), `refreshLightStatus()`, localized `emitFeedback(..., isError = (successCount == 0 && failCount > 0), inSheet = true)`. |
| `549-561` | `bulkRevokeCategory(cat, pkgs)` | `APP-H04`, `APP-M15` | Key `"bulk:$cat"`. On success: if `pkgs.size <= 15` reload each via `reloadSingleAppIntoState`, else `loadInstalledApps`; refresh light status + localized toast. On failure localized error toast. |
| `NEW` | `clearLegacyAppScope(pkg)` | `APP-M18` | Under `withMutationTracking` + `configMutex.withLock`, call `repo.clearAppScope(pkg)`, remove `pkg` from `config.scopes.apps`, call `refreshLightStatus()`, emit localized feedback (`inSheet = true`). |
| `563-569` | `setAutoClearClipboard(enabled, clearSecs)` | `APP-H12` (missing `.onFailure`), `APP-C05`, `APP-M15` | Under `configMutex`, optimistic update + rollback + localized error toast on `.onFailure`. |
| `571-581` | `clearClipboardNow()` | `APP-M15` | Localize success & error toasts. |
| `583-598` | `updateHardeningConfig(transform)` + NEW `refreshHardeningStatus()` | `APP-C05`, `APP-H12`, `APP-M13`, `APP-M15` | Read latest `_ui.value.config.hardening` INSIDE `configMutex.withLock`, apply optimistic update, rollback on failure + localized error toast. Add `refreshHardeningStatus()` with `.onFailure` handling (`APP-H12`, `APP-M13`). |
| `600-612` | `rollbackHardening()` | `APP-H12` (missing `.onFailure`), `APP-C05`, `APP-M15` | Under `configMutex`, add `.onFailure` with localized error toast, localize success toast. |
| `614-624` | `updateAutomationConfig(transform)` | `APP-C05`, `APP-M15` | Read latest `_ui.value.config.automation` INSIDE `configMutex.withLock`, apply optimistic update, rollback on failure + localized error toast. |
| `626-674` | `setSensorPrivacy`, `setPrivateDns`, `setRadioPrivacy` | `APP-M15` | Wrap in `withMutationTracking`, localize all toasts. |
| `676-696` | `triggerPanicLockdown`, `triggerPanicReboot` | `APP-M15` | Wrap in `withMutationTracking`, localize all toasts. |
| `698-718` | `runSentinelAudit`, `runLiveProof` | `APP-H12`, `APP-M12`, `APP-M13`, `APP-C06`, `APP-M15` | Toggle `sentinelLoading` / `liveProofLoading` around RPCs; emit errors via `emitFeedback(..., isError = true, inSheet = true)` so failures show inside `LiveProofSheet` / `SentinelAuditSheet`; localize all toasts. |
| `720-724` | `setDisguiseEnabled(enabled)` | `APP-M15` | Localize toast. |
| `726-742` | `exportConfigToUri(uri)` | `APP-M06` (null `openOutputStream` returns `true`!), `APP-M15` | Fix to `openOutputStream(uri)?.use { it.write(...); it.flush(); true } ?: false`. Localize toasts. |
| `744-764` | `importConfigFromUri(uri)` | `APP-M05` (unbounded `.readText()` OOM), `APP-C05`, `APP-M15` | Read via bounded 2 MiB chunk reader (`MAX_CONFIG_IMPORT_BYTES = 2 * 1024 * 1024`), distinguish `TooLarge` vs `Unreadable` vs blank with localized error toast, serialize import under `configMutex`. |
| `766-772` | `refreshLightStatus()` | `APP-H11` | Reset `consecutiveStatusFailures` on success; call `recordStatusFailure` on failure (do NOT ignore disconnect). |

---

## 4. `TrafficScreen.kt` Remediation Blueprint (`APP-C04`, `APP-H05`, `APP-H06`, `APP-H12`, `APP-H13`, `APP-H14`, `APP-M16`, `APP-M17`, `APP-L06`)

### 4.1 Observed Defects (Exact Lines)
- **`TrafficScreen.kt:38` (`APP-M17`)**: `remember` resets selected `TrafficTab` on rotation/split-screen.
- **`TrafficScreen.kt:40-49` (`APP-C04`)**: `DisposableEffect` + `LaunchedEffect(tab)` triggered duplicate `refreshPrivacyAccessLog()` RPCs.
- **`TrafficScreen.kt:95-97` (`APP-H06`, `APP-L06`)**: Refresh `IconButton` is `32.dp`, has `contentDescription = null`, and does not reflect `state.loadingAccessLog`.
- **`TrafficScreen.kt:114, 132, 169` (`APP-H13`)**: All 3 `LazyColumn`s use `itemsIndexed(...)` with NO `key` lambda; live reordering during 3s polls can route `Kill`/`Block`/`Cabut` clicks to the wrong item!
- **`TrafficScreen.kt:135-152, 338-347` (`APP-H14`, `APP-L06`)**: `SocketRow` exposes Kill on protected UIDs/packages, exposes Block on protected packages (`UID >= 10000`), forbids blocking `isUserListener` listening sockets, wipes existing `AppFirewallRule` fields when blocking, emits no toast, uses `34.dp` icon buttons, and passes `contentDescription = null` instead of `s.killSocket` / `s.blockApp`.
- **`TrafficScreen.kt:160-185, 239-246` (`APP-H05`, `APP-H06`, `APP-H12`, `APP-M16`)**: `TrafficTab.ACCESS` shows no loading spinner (`loadingAccessLog`) and no error banner (`accessLogError`); `onRevoke` fires two racing coroutines (`setAppPermissionAction` + `refreshPrivacyAccessLog`); `PrivacyAccessRow` never disables/marks already revoked/ignored rows (`ev.mode in setOf("ignore", "deny")`), never hides `Cabut` on protected packages, shows no pending spinner, and forces `Modifier.height(32.dp)`.
- **`TrafficScreen.kt:252-254` (`APP-L06`)**: Allocates a new `SimpleDateFormat` inside `remember(ev.timestampMs)` for every row.

### 4.2 Concrete Implementation Details (`TrafficScreen.kt`)

1. **Hoisted Thread-Safe Formatter (`APP-L06`)**:
   ```kotlin
   private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
   private fun formatEventTime(timestampMs: Long): String =
       TIME_FMT.format(Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault()))
   ```
2. **State Persistence & Single-Source Polling (`APP-M17`, `APP-C04`)**:
   ```kotlin
   var tab by rememberSaveable { mutableStateOf(TrafficTab.BLOCKED) }

   DisposableEffect(Unit) {
       vm.setMonitorPollingActive(true)
       onDispose {
           vm.setMonitorAccessTabActive(false)
           vm.setMonitorPollingActive(false)
       }
   }

   LaunchedEffect(tab) {
       val isAccess = tab == TrafficTab.ACCESS
       vm.setMonitorAccessTabActive(isAccess)
       if (isAccess) {
           vm.refreshPrivacyAccessLog(force = false)
       }
   }
   ```
3. **Accessible Refresh Button on `ACCESS` Header (`APP-H06`, `APP-M16`, `APP-L06`)**:
   ```kotlin
   IconButton(
       onClick = { vm.refreshPrivacyAccessLog(force = true) },
       enabled = !state.loadingAccessLog,
       modifier = Modifier.minimumInteractiveComponentSize(),
   ) {
       if (state.loadingAccessLog) {
           CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
       } else {
           Icon(
               Icons.Rounded.Refresh,
               contentDescription = if (s.isIndonesian) "Muat ulang" else "Refresh",
               modifier = Modifier.size(20.dp),
           )
       }
   }
   ```
4. **Crash-Proof Stable Keys on All 3 `LazyColumn`s (`APP-H13`)**:
   Use occurrence-disambiguated content keys inside `remember(...)` (prevents `IllegalArgumentException: Key already used` on duplicate millisecond packets or dual sockets while staying stable across list updates):
   - `BLOCKED`: `"${ev.timestampMs}_${ev.uid}_${ev.protocol}_${ev.srcPort}_${ev.dstIp}:${ev.dstPort}_${ev.reason}#$occ"`
   - `LIVE`: `"${conn.uid}_${conn.family}_${conn.protocol}_${conn.state}_${conn.localIp}:${conn.localPort}_${conn.remoteIp}:${conn.remotePort}#$occ"`
   - `ACCESS`: `"${ev.packageName}_${ev.category}_${ev.op}#$occ"`
5. **`SocketRow` Fixes (`APP-H14`, `APP-H06`, `APP-L06`)**:
   - Compute `val isProtected = AppInventory.isProtectedUid(conn.uid) || AppInventory.isProtectedPackage(conn.packageName)`
   - `onKill`: `if (!conn.isListening && conn.protocol.startsWith("TCP") && !isProtected) { { vm.killConnection(conn) } } else null`
   - `onBlock`: `if (!isProtected) { { vm.blockAppFromMonitor(conn.uid, conn.packageName ?: "", appLabelMap[conn.packageName] ?: conn.packageName ?: "UID ${conn.uid}") } } else null` (Notice: works for BOTH outbound sockets AND `isUserListener` listening sockets!)
   - Display `appLabelMap[conn.packageName] ?: conn.packageName ?: "UID ${conn.uid}"`
   - Buttons: `Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 40.dp).minimumInteractiveComponentSize()` with `contentDescription = s.killSocket` and `contentDescription = s.blockApp`!
6. **`TrafficTab.ACCESS` & `PrivacyAccessRow` Fixes (`APP-H05`, `APP-H06`, `APP-H12`, `APP-M16`)**:
   - Render `accessLogError` banner with localized Retry button (`vm.refreshPrivacyAccessLog(force = true)`) above/in place of list when `state.accessLogError != null`.
   - When `state.loadingAccessLog && state.privacyAccessLog.isEmpty()`: centered `CircularProgressIndicator` + localized loading label (`"Memindai log akses izin..."` / `"Scanning permission access log..."`).
   - When `state.loadingAccessLog && state.privacyAccessLog.isNotEmpty()`: `LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 4.dp))`.
   - Inside `PrivacyAccessRow`:
     - `val isProtected = AppInventory.isProtectedUid(ev.uid) || AppInventory.isProtectedPackage(ev.packageName)`
     - `val isBlocked = ev.mode == "ignore" || ev.mode == "deny"`
     - `val isPending = state.pendingPermKeys.contains("${ev.packageName}:${ev.category}")`
     - Show `s.permStateIgnored` (`PALSU KOSONG` / `FAKE EMPTY`, emerald) when `ev.mode == "ignore"` or `s.permStateDenied` (`DICABUT` / `REVOKED`, emerald) when `ev.mode == "deny"`.
     - Action slot:
       - If `isProtected`: omit `Cabut` button (no broken click on protected system apps).
       - Else if `isBlocked`: render disabled tonal status pill (`enabled = false`, `Modifier.defaultMinSize(minHeight = 40.dp)`) showing emerald check icon + `if (ev.mode == "ignore") s.permStateIgnored else s.permStateDenied`.
       - Else: `FilledTonalButton(onClick = { vm.revokeFromAccessLog(ev.packageName, ev.category) }, enabled = !isPending, modifier = Modifier.defaultMinSize(minHeight = 40.dp).minimumInteractiveComponentSize(), ...)` showing `CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)` while `isPending`, else `Text(s.revokeOne, color = SiloCrimson)`.

---

## 5. Concrete JVM Unit Test Specification

Add/expand JVM unit tests under `app/app/src/test/java/dev/silo/app/` (zero Android framework / zero root `su` required; runs via `./gradlew testDebugUnitTest --no-daemon`):

### 5.1 `BlocklistParser` Test Cases (`ProtocolAndParserTest.kt` or `BlocklistParserTest.kt`)
1. `blocklistParserValidatesStrictIpv4OctetsAndPrefixBounds`:
   - Accepts: `8.8.8.8`, `198.51.100.0/24`, `203.0.113.42/32`, `10.0.0.0/8`.
   - Rejects: `999.1.1.1`, `256.0.0.1`, `1.2.3.256`, `01.2.3.4` (leading zero), `1.2.3`, `1.2.3.4.5`, `198.51.100.0/0`, `198.51.100.0/33`, `198.51.100.0/24/16`, `0.0.0.0`, `0.1.2.3/8`, `127.0.0.1`, `127.10.0.0/8`.
2. `blocklistParserValidatesStrictIpv6AndRejectsTimestampsAndMalformedColons`:
   - Accepts: `2001:db8:dead::/48`, `2001:db8::1`, `2001:0db8:85a3:0000:0000:8a2e:0370:7334`, `::ffff:192.0.2.1`, `fe80::1/64`, `2606:4700:4700::1111/128`.
   - Rejects: `12:30:00`, `23:59:59`, `:::`, `2001:db8::1::2`, `1:2:3`, `aa:bb:cc:dd:ee:ff`, `:2001:db8::1`, `2001:db8::1:`, `1:2:3:4::5:6:7:8`, `2001:db8::12345`, `2001:db8::/0`, `2001:db8::/129`, `::`, `::1`, `::1/128`, `::1/64`, `::/48`, `fe80::1%wlan0`.
3. `blocklistParserExtractsMultipleIpsPerLineAndStripsInlineComments`:
   - Input containing `"198.51.100.1, 198.51.100.2; 203.0.113.5 2001:db8::1 # 8.8.4.4"`, `"198.51.100.10 // 198.51.100.11"`, `"; 198.51.100.99"`, `"Updated 12:30:00 198.51.100.7"`, `"203.0.113.42 ; note"`.
   - Asserts exact expected list (`198.51.100.1`, `198.51.100.2`, `203.0.113.5`, `2001:db8::1`, `198.51.100.10`, `198.51.100.7`, `203.0.113.42`) — excludes commented-out IPs and `12:30:00`.
4. `blocklistParserEnforcesJsonByteCapAndStreamingReadCap`:
   - `parseLinesDetailed(manyIps, maxJsonBytes = 200)` returns `truncatedByByteCap == true` and serialized JSON byte length `<= 200`.
   - `parseStream(ByteArrayInputStream(...), maxReadBytes = 40L)` stops at 40 bytes with `truncatedByStreamCap == true`.

### 5.2 `SiloClient` Test Cases (`SiloClientProtocolTest.kt`)
1. `parseAndValidateEnvelopeAcceptsMatchingId`:
   - Valid `{"id":7,"ok":true,"data":{"version":"3.0.0"}}` with `expectedId = 7L` returns decoded `IpcEnvelopeResponse(id = 7L, ok = true, ...)`.
2. `parseAndValidateEnvelopeRejectsMismatchedId`:
   - `{"id":6,"ok":true,"data":{}}` with `expectedId = 7L` throws `BridgeProtocolException` containing `"expected 7"` and `"got 6"`.
   - `{"id":0,"ok":false,"error":"bridge error"}` with `expectedId = 1L` throws `BridgeProtocolException`.
3. `parseAndValidateEnvelopeRejectsNonJsonPreambleAndMalformedJson`:
   - `"su: warning banner"` -> throws `BridgeProtocolException`.
   - `"{not valid json"` -> throws `BridgeProtocolException`.
   - `""` -> throws `BridgeProtocolException`.
4. `boundedStderrBufferKeepsTrailingBytesWithoutOverflow`:
   - Appending 5,000 bytes to `BoundedStderrBuffer(maxBytes = 2048)` returns exact trailing 2,048 bytes.
5. `callRawCancelsBlockingReadOnTimeoutAndRecoversBridge` (`APP-C03`, `APP-M11`):
   - Inject fake `Process` #1 whose `inputStream.read()` blocks on a `CountDownLatch`/`PipedInputStream` until `destroyForcibly()` closes it; fake `Process` #2 responds normally to `{"id":2,...}`.
   - Execute `runBlocking { client.callRaw("get_status", timeoutMs = 250L) }` -> asserts `isFailure`, elapsed wall-clock `< 1500ms`, `fake1.destroyForciblyCalled == true`.
   - Execute second `client.callRaw("ping", timeoutMs = 1000L)` -> asserts `isSuccess` (proving `mutex` released & bridge reset cleanly).
6. `callRawIncludesStderrOnPrematureEofAndResetsBridgeOnIdMismatch` (`APP-H02`, `APP-H03`):
   - Fake process writing `"su: permission denied\n"` to `errorStream` and closing `inputStream` -> `callRaw` failure message contains `"su: permission denied"`.
   - Fake process returning `{"id":999,"ok":true,"data":{}}\n` -> `callRaw` fails with `BridgeProtocolException` and tears down process so next call invokes `processFactory` again.
7. `callRawKeepsBridgeAliveOnStructuredDaemonRpcException`:
   - Fake process answers req `1` with `{"id":1,"ok":false,"error":"protected"}` and req `2` with `{"id":2,"ok":true,"data":{"done":true}}` -> 1st fails with `DaemonRpcException`, 2nd succeeds with `spawnCount == 1`.

---

## 6. Verification Checklist (For Implementer & Reviewer)
- `cd /home/antigravity/terenzy/Silo/app && ./gradlew testDebugUnitTest assembleRelease --no-daemon`
- `/opt/android-sdk/build-tools/35.0.0/aapt2 dump permissions app/build/outputs/apk/release/app-release.apk` (confirm ZERO `android.permission.INTERNET`)
- Confirm zero unused imports, zero hardcoded unlocalized ViewModel toasts, zero blocking `readLine()` deadlocks on timeout, and 100% alignment with `PROJECT.md § Interface Contracts § 1–3`.
