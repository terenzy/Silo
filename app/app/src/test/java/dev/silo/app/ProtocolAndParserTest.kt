package dev.silo.app

import dev.silo.app.data.AppInventory
import dev.silo.app.data.BlocklistParser
import dev.silo.app.ipc.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ProtocolAndParserTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun siloConfigRoundTripsWithSnakeCaseRustSchema() {
        val cfg = SiloConfig(
            firewall = FirewallConfig(
                enabled = true,
                mode = FirewallMode.DEFAULT_DENY,
                blockAction = BlockAction.REJECT,
                isolateLan = true,
                dnsLeakGuard = true,
                ipv6LeakGuard = true,
                rules = mapOf(
                    10234 to AppFirewallRule(
                        packageName = "com.example.app",
                        allowWifi = true,
                        allowCellular = false,
                        allowVpn = true,
                    )
                ),
                ipBlocklist = listOf("198.51.100.0/24"),
            ),
            automation = AutomationConfig(
                autoRebootEnabled = false,
                autoRebootMinutes = 720,
            ),
        )

        val encoded = json.encodeToString(cfg)
        assertTrue(encoded.contains("\"default_deny\""))
        assertTrue(encoded.contains("\"isolate_lan\":true"))
        assertTrue(encoded.contains("\"dns_leak_guard\":true"))
        assertTrue(encoded.contains("\"auto_reboot_minutes\":720"))

        val decoded = json.decodeFromString<SiloConfig>(encoded)
        assertEquals(cfg, decoded)
    }

    @Test
    fun blocklistParserExtractsCleanIpsAndSkipsComments() {
        val sample = """
            # FireHOL Level 1 sample
            198.51.100.0/24
            203.0.113.42 ; comment
            2001:db8:dead::/48
            not_an_ip_line
            198.51.100.0/24
            0.0.0.0   203.0.113.99
        """.trimIndent()

        val parsed = BlocklistParser.parseLines(sample)
        assertTrue(parsed.contains("198.51.100.0/24"))
        assertTrue(parsed.contains("203.0.113.42"))
        assertTrue(parsed.contains("2001:db8:dead::/48"))
        assertEquals(4, parsed.size)
    }

    @Test
    fun protectedUidsCoverCoreSystemAndIsolatedAcrossUsers() {
        assertTrue(AppInventory.isProtectedUid(0))
        assertTrue(AppInventory.isProtectedUid(1000))
        assertTrue(AppInventory.isProtectedUid(1051))
        assertTrue(AppInventory.isProtectedUid(2000))
        assertTrue(AppInventory.isProtectedUid(101_000))
        assertTrue(AppInventory.isProtectedUid(99_001))
        assertFalse(AppInventory.isProtectedUid(10_234))
        assertFalse(AppInventory.isProtectedUid(110_234))
    }
}
