package dev.silo.app

import dev.silo.app.ipc.BoundedStderrBuffer
import dev.silo.app.ipc.BridgeProtocolException
import dev.silo.app.ipc.DaemonRpcException
import dev.silo.app.ipc.SiloClient
import dev.silo.app.ipc.SiloJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SiloClientProtocolTest {

    @Test
    fun parseAndValidateEnvelopeAcceptsMatchingId() {
        val resp = SiloClient.parseAndValidateEnvelope(
            SiloJson,
            """{"id":7,"ok":true,"data":{"version":"3.0.0"}}""",
            expectedId = 7L,
        )
        assertEquals(7L, resp.id)
        assertTrue(resp.ok)
        assertEquals("3.0.0", resp.data?.jsonObject?.get("version")?.jsonPrimitive?.content)
    }

    @Test
    fun parseAndValidateEnvelopeRejectsMismatchedId() {
        try {
            SiloClient.parseAndValidateEnvelope(
                SiloJson,
                """{"id":6,"ok":true,"data":{}}""",
                expectedId = 7L,
            )
            fail("Expected BridgeProtocolException on mismatched ID")
        } catch (e: BridgeProtocolException) {
            assertTrue(e.message!!.contains("expected 7"))
            assertTrue(e.message!!.contains("got 6"))
        }

        try {
            SiloClient.parseAndValidateEnvelope(
                SiloJson,
                """{"id":0,"ok":false,"error":"bridge error"}""",
                expectedId = 1L,
            )
            fail("Expected BridgeProtocolException on zero ID")
        } catch (_: BridgeProtocolException) {
            // expected
        }
    }

    @Test
    fun parseAndValidateEnvelopeRejectsNonJsonPreambleAndMalformedJson() {
        for (bad in listOf("su: warning banner", "{not valid json", "", "   ")) {
            try {
                SiloClient.parseAndValidateEnvelope(SiloJson, bad, expectedId = 1L)
                fail("Expected BridgeProtocolException for '$bad'")
            } catch (_: BridgeProtocolException) {
                // expected
            }
        }
    }

    @Test
    fun boundedStderrBufferKeepsTrailingBytesWithoutOverflow() {
        val buf = BoundedStderrBuffer(maxBytes = 2048)
        val total = ByteArray(5000) { i -> ('a'.code + (i % 26)).toByte() }
        // Append in varied chunks to exercise wrap-around logic
        buf.append(total.copyOfRange(0, 1000), 1000)
        buf.append(total.copyOfRange(1000, 3500), 2500)
        buf.append(total.copyOfRange(3500, 5000), 1500)

        val expected = String(total.copyOfRange(5000 - 2048, 5000), Charsets.UTF_8)
        assertEquals(expected, buf.snapshot())
    }

    @Test
    fun callRawCancelsBlockingReadOnTimeoutAndRecoversBridge() = runBlocking {
        val hungProcess = BlockingFakeProcess()
        val healthyProcess = ScriptedFakeProcess { reqLine ->
            val id = extractId(reqLine)
            """{"id":$id,"ok":true,"data":{"pong":true}}"""
        }
        val spawnCount = AtomicInteger(0)
        val client = SiloClient(processFactory = {
            if (spawnCount.getAndIncrement() == 0) hungProcess else healthyProcess
        })

        val startMs = System.nanoTime() / 1_000_000L
        val firstRes = client.callRaw("get_status", timeoutMs = 250L)
        val elapsedMs = (System.nanoTime() / 1_000_000L) - startMs

        assertTrue("Expected timeout failure", firstRes.isFailure)
        assertTrue("Expected fast timeout recovery (<1500ms), took ${elapsedMs}ms", elapsedMs < 1500L)
        assertTrue("Expected destroyForcibly() on hung bridge", hungProcess.destroyForciblyCalled.get())

        val secondRes = client.callRaw("ping", timeoutMs = 1_000L)
        assertTrue("Expected bridge recovery on next RPC: ${secondRes.exceptionOrNull()}", secondRes.isSuccess)
        assertEquals(true, secondRes.getOrNull()?.jsonObject?.get("pong")?.jsonPrimitive?.booleanOrNull)
        assertEquals(2, spawnCount.get())

        client.disconnect()
    }

    @Test
    fun callRawIncludesStderrOnPrematureEofAndResetsBridgeOnIdMismatch() = runBlocking {
        val spawnCount = AtomicInteger(0)
        val client = SiloClient(processFactory = {
            when (spawnCount.getAndIncrement()) {
                0 -> EofWithStderrFakeProcess("su: permission denied\n")
                1 -> ScriptedFakeProcess { """{"id":999,"ok":true,"data":{}}""" }
                else -> ScriptedFakeProcess { req ->
                    """{"id":${extractId(req)},"ok":true,"data":{"recovered":true}}"""
                }
            }
        })

        val eofRes = client.callRaw("get_status", timeoutMs = 1_000L)
        assertTrue(eofRes.isFailure)
        val eofMsg = eofRes.exceptionOrNull()?.message.orEmpty()
        assertTrue("Expected stderr snippet in EOF error, got: $eofMsg", eofMsg.contains("su: permission denied"))

        val mismatchRes = client.callRaw("get_status", timeoutMs = 1_000L)
        assertTrue(mismatchRes.isFailure)
        assertTrue(mismatchRes.exceptionOrNull() is BridgeProtocolException)

        val recoveredRes = client.callRaw("get_status", timeoutMs = 1_000L)
        assertTrue("Expected fresh bridge after ID mismatch", recoveredRes.isSuccess)
        assertEquals(3, spawnCount.get())

        client.disconnect()
    }

    @Test
    fun callRawKeepsBridgeAliveOnStructuredDaemonRpcException() = runBlocking {
        val spawnCount = AtomicInteger(0)
        val reqNum = AtomicInteger(0)
        val client = SiloClient(processFactory = {
            spawnCount.incrementAndGet()
            ScriptedFakeProcess { req ->
                val id = extractId(req)
                if (reqNum.getAndIncrement() == 0) {
                    """{"id":$id,"ok":false,"error":"protected"}"""
                } else {
                    """{"id":$id,"ok":true,"data":{"done":true}}"""
                }
            }
        })

        val first = client.callRaw("set_app_firewall_rule", timeoutMs = 1_000L)
        assertTrue(first.isFailure)
        assertTrue(first.exceptionOrNull() is DaemonRpcException)
        assertEquals("protected", first.exceptionOrNull()?.message)

        val second = client.callRaw("get_status", timeoutMs = 1_000L)
        assertTrue(second.isSuccess)
        assertEquals(1, spawnCount.get())

        client.disconnect()
    }

    @Test
    fun callRawCancelsBlockingReadOnCallerCancellationAndRecoversBridge() = runBlocking {
        val hungProcess = BlockingFakeProcess()
        val healthyProcess = ScriptedFakeProcess { reqLine ->
            """{"id":${extractId(reqLine)},"ok":true,"data":{"alive":true}}"""
        }
        val spawnCount = AtomicInteger(0)
        val client = SiloClient(processFactory = {
            if (spawnCount.getAndIncrement() == 0) hungProcess else healthyProcess
        })

        val job = launch(Dispatchers.Default) {
            client.callRaw("get_status", timeoutMs = 10_000L)
        }
        delay(120L)
        val cancelStartMs = System.nanoTime() / 1_000_000L
        job.cancel()
        job.join()
        val cancelElapsedMs = (System.nanoTime() / 1_000_000L) - cancelStartMs

        assertTrue("Expected fast cancellation (<1500ms), took ${cancelElapsedMs}ms", cancelElapsedMs < 1500L)
        assertTrue("Expected destroyForcibly() on caller cancel", hungProcess.destroyForciblyCalled.get())

        val recovered = client.callRaw("ping", timeoutMs = 1_000L)
        assertTrue("Expected bridge recovery after caller cancel: ${recovered.exceptionOrNull()}", recovered.isSuccess)
        assertEquals(2, spawnCount.get())

        client.disconnect()
    }

    @Test
    fun callRawTimesOutWaitingForMutexWithoutLeakingLock() = runBlocking {
        val slowProcess = BlockingFakeProcess()
        val healthyProcess = ScriptedFakeProcess { reqLine ->
            """{"id":${extractId(reqLine)},"ok":true,"data":{"ok":true}}"""
        }
        val spawnCount = AtomicInteger(0)
        val client = SiloClient(processFactory = {
            if (spawnCount.getAndIncrement() == 0) slowProcess else healthyProcess
        })

        val holder = launch(Dispatchers.Default) {
            client.callRaw("slow_cmd", timeoutMs = 700L)
        }
        delay(80L)

        val waiterStartMs = System.nanoTime() / 1_000_000L
        val waiterRes = client.callRaw("fast_cmd", timeoutMs = 150L)
        val waiterElapsedMs = (System.nanoTime() / 1_000_000L) - waiterStartMs

        assertTrue("Expected waiter timeout failure", waiterRes.isFailure)
        assertTrue("Expected waiter to time out promptly (<600ms), took ${waiterElapsedMs}ms", waiterElapsedMs < 600L)
        assertTrue(waiterRes.exceptionOrNull()?.message.orEmpty().contains("timed out"))

        holder.join()
        val after = client.callRaw("ping", timeoutMs = 1_000L)
        assertTrue("Expected clean recovery after both calls: ${after.exceptionOrNull()}", after.isSuccess)
        client.disconnect()
    }

    private fun extractId(reqLine: String): Long {
        val m = Regex(""""id"\s*:\s*(\d+)""").find(reqLine)
        return m?.groupValues?.get(1)?.toLong() ?: 0L
    }

    private class BlockingFakeProcess : Process() {
        private val alive = AtomicBoolean(true)
        val destroyForciblyCalled = AtomicBoolean(false)
        private val unblockLatch = CountDownLatch(1)
        private val outBuf = ByteArrayOutputStream()
        private val errStream = ByteArrayInputStream(ByteArray(0))

        private val blockingStdout = object : InputStream() {
            override fun read(): Int {
                unblockLatch.await()
                throw IOException("Bridge process destroyed")
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                unblockLatch.await()
                throw IOException("Bridge process destroyed")
            }
        }

        override fun getOutputStream(): OutputStream = outBuf
        override fun getInputStream(): InputStream = blockingStdout
        override fun getErrorStream(): InputStream = errStream
        override fun waitFor(): Int {
            unblockLatch.await()
            return 137
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = unblockLatch.await(timeout, unit)
        override fun exitValue(): Int = if (alive.get()) throw IllegalThreadStateException() else 137
        override fun isAlive(): Boolean = alive.get()
        override fun destroy() {
            alive.set(false)
            unblockLatch.countDown()
        }
        override fun destroyForcibly(): Process {
            destroyForciblyCalled.set(true)
            alive.set(false)
            unblockLatch.countDown()
            return this
        }
    }

    private class EofWithStderrFakeProcess(stderrText: String) : Process() {
        private val alive = AtomicBoolean(true)
        private val outBuf = ByteArrayOutputStream()
        private val stdoutStream = ByteArrayInputStream(ByteArray(0))
        private val stderrStream = ByteArrayInputStream(stderrText.toByteArray(Charsets.UTF_8))

        override fun getOutputStream(): OutputStream = outBuf
        override fun getInputStream(): InputStream = stdoutStream
        override fun getErrorStream(): InputStream = stderrStream
        override fun waitFor(): Int = 1
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = 1
        override fun isAlive(): Boolean = alive.get()
        override fun destroy() { alive.set(false) }
        override fun destroyForcibly(): Process { alive.set(false); return this }
    }

    private class ScriptedFakeProcess(
        private val handler: (String) -> String?,
    ) : Process() {
        private val alive = AtomicBoolean(true)
        private val responseBytes = LinkedBlockingQueue<Int>()
        private val lineBuf = StringBuilder()
        private val errStream = ByteArrayInputStream(ByteArray(0))

        private val reqOut = object : OutputStream() {
            override fun write(b: Int) {
                if (b == '\n'.code) {
                    val line = lineBuf.toString().trim()
                    lineBuf.setLength(0)
                    val resp = handler(line)
                    if (resp == null) {
                        responseBytes.put(-1)
                    } else {
                        for (byteVal in (resp + "\n").toByteArray(Charsets.UTF_8)) {
                            responseBytes.put(byteVal.toInt() and 0xFF)
                        }
                    }
                } else if (b != '\r'.code) {
                    lineBuf.append(b.toChar())
                }
            }
        }

        private val respIn = object : InputStream() {
            override fun read(): Int {
                val next = responseBytes.take()
                return next
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len <= 0) return 0
                val first = responseBytes.take()
                if (first == -1) return -1
                b[off] = first.toByte()
                var count = 1
                while (count < len) {
                    val next = responseBytes.poll() ?: break
                    if (next == -1) {
                        responseBytes.put(-1)
                        break
                    }
                    b[off + count] = next.toByte()
                    count++
                }
                return count
            }
        }

        override fun getOutputStream(): OutputStream = reqOut
        override fun getInputStream(): InputStream = respIn
        override fun getErrorStream(): InputStream = errStream
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = if (alive.get()) throw IllegalThreadStateException() else 0
        override fun isAlive(): Boolean = alive.get()
        override fun destroy() {
            alive.set(false)
            responseBytes.offer(-1)
        }
        override fun destroyForcibly(): Process {
            alive.set(false)
            responseBytes.offer(-1)
            return this
        }
    }
}
