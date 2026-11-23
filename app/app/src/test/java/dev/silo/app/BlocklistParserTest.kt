package dev.silo.app

import dev.silo.app.data.BlocklistParser
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class BlocklistParserTest {

    @Test
    fun blocklistParserValidatesStrictIpv4OctetsAndPrefixBounds() {
        val valid = listOf("8.8.8.8", "198.51.100.0/24", "203.0.113.42/32", "10.0.0.0/8")
        for (v in valid) {
            assertEquals("Expected valid IPv4/CIDR: $v", v, BlocklistParser.validateEntry(v))
        }

        val invalid = listOf(
            "999.1.1.1",
            "256.0.0.1",
            "1.2.3.256",
            "01.2.3.4",
            "1.2.3",
            "1.2.3.4.5",
            "198.51.100.0/0",
            "198.51.100.0/33",
            "198.51.100.0/24/16",
            "0.0.0.0",
            "0.1.2.3/8",
            "127.0.0.1",
            "127.10.0.0/8",
            "not-an-ip",
            "",
        )
        for (bad in invalid) {
            assertNull("Expected rejected IPv4/CIDR: '$bad'", BlocklistParser.validateEntry(bad))
        }
    }

    @Test
    fun blocklistParserValidatesStrictIpv6AndRejectsTimestampsAndMalformedColons() {
        val valid = listOf(
            "2001:db8:dead::/48",
            "2001:db8::1",
            "2001:0db8:85a3:0000:0000:8a2e:0370:7334",
            "::ffff:192.0.2.1",
            "fe80::1/64",
            "2606:4700:4700::1111/128",
        )
        for (v in valid) {
            assertEquals("Expected valid IPv6/CIDR: $v", v.lowercase(), BlocklistParser.validateEntry(v))
        }

        val invalid = listOf(
            "12:30:00",
            "23:59:59",
            ":::",
            "2001:db8::1::2",
            "1:2:3",
            "aa:bb:cc:dd:ee:ff",
            ":2001:db8::1",
            "2001:db8::1:",
            "1:2:3:4::5:6:7:8",
            "2001:db8::12345",
            "2001:db8::/0",
            "2001:db8::/129",
            "::",
            "::1",
            "::1/128",
            "::1/64",
            "::/48",
            "fe80::1%wlan0",
        )
        for (bad in invalid) {
            assertNull("Expected rejected IPv6/CIDR: '$bad'", BlocklistParser.validateEntry(bad))
        }
    }

    @Test
    fun blocklistParserExtractsMultipleIpsPerLineAndStripsInlineComments() {
        val raw = """
            198.51.100.1, 198.51.100.2; 203.0.113.5 2001:db8::1 # 8.8.4.4
            198.51.100.10 // 198.51.100.11
            ; 198.51.100.99
            # 198.51.100.98
            // 198.51.100.97
            Updated 12:30:00 198.51.100.7
            203.0.113.42 ; note
        """.trimIndent()

        val parsed = BlocklistParser.parseLines(raw)
        assertEquals(
            listOf(
                "198.51.100.1",
                "198.51.100.2",
                "203.0.113.5",
                "2001:db8::1",
                "198.51.100.10",
                "198.51.100.7",
                "203.0.113.42",
            ),
            parsed,
        )
    }

    @Test
    fun blocklistParserEnforcesJsonByteCapAndStreamingReadCap() {
        val manyIps = (1..40).joinToString("\n") { "198.51.100.$it" }
        val detailed = BlocklistParser.parseLinesDetailed(manyIps, maxJsonBytes = 200)
        assertTrue("Expected truncatedByByteCap=true", detailed.truncatedByByteCap)
        assertTrue(detailed.entries.isNotEmpty())
        val serializedLen = 128 + detailed.entries.sumOf { it.length + 3 }
        assertTrue("Serialized byte count $serializedLen exceeded cap 200", serializedLen <= 200)

        val streamBytes = manyIps.toByteArray(Charsets.UTF_8)
        val streamRes = BlocklistParser.parseStream(
            ByteArrayInputStream(streamBytes),
            maxReadBytes = 40L,
        )
        assertTrue("Expected truncatedByStreamCap=true", streamRes.truncatedByStreamCap)
        assertTrue("Expected fewer than 40 entries when capped at 40 bytes", streamRes.entries.size < 10)
    }
}
