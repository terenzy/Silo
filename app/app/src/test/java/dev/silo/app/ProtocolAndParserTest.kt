package dev.silo.app

import dev.silo.app.data.AppInventory
import dev.silo.app.data.AppLanguagePref
import dev.silo.app.data.BlocklistParser
import dev.silo.app.data.PrivacyRiskLevel
import dev.silo.app.ipc.*
import dev.silo.app.ui.i18n.resolveStrings
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
    fun siloConfigV2RoundTripsWithSnakeCaseRustSchema() {
        val cfg = SiloConfig(
            schemaVersion = 2,
            firewall = FirewallConfig(
                enabled = true,
                mode = FirewallMode.DEFAULT_DENY,
                blockAction = BlockAction.REJECT,
                isolateLan = true,
                dnsLeakGuard = true,
                ipv6LeakGuard = true,
                antiPortScan = true,
                rules = mapOf(
                    10277 to AppFirewallRule(
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
        assertTrue(encoded.contains("\"anti_port_scan\":true"))
        assertTrue(encoded.contains("\"10277\""))

        val decoded = json.decodeFromString<SiloConfig>(encoded)
        assertEquals(cfg, decoded)
    }

    @Test
    fun offlineRiskClassifierDetectsTrackersAndSensitivePermissions() {
        val (risk, sensCount, trackers) = AppInventory.evaluateOfflineRisk(
            permissions = listOf(
                "android.permission.READ_CONTACTS",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.CAMERA",
                "android.permission.RECORD_AUDIO",
                "android.permission.READ_SMS",
            ),
            componentNames = listOf(
                "com.facebook.ads.AudienceNetworkActivity",
                "com.appsflyer.SingleInstallBroadcastReceiver",
                "com.google.firebase.analytics.FirebaseAnalyticsService",
            ),
        )
        assertEquals(PrivacyRiskLevel.HIGH, risk)
        assertEquals(5, sensCount)
        assertEquals(3, trackers.size)
    }

    @Test
    fun bilingualStringsProvideDistinctIndonesianAndEnglishLabels() {
        val id = resolveStrings(AppLanguagePref.INDONESIAN)
        val en = resolveStrings(AppLanguagePref.ENGLISH)
        assertEquals("Beranda", id.tabHome)
        assertEquals("Home", en.tabHome)
        assertEquals("Aplikasi", id.tabApps)
        assertEquals("Apps", en.tabApps)
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
        """.trimIndent()

        val parsed = BlocklistParser.parseLines(sample)
        assertEquals(3, parsed.size)
        assertTrue(parsed.contains("198.51.100.0/24"))
        assertTrue(parsed.contains("203.0.113.42"))
        assertTrue(parsed.contains("2001:db8:dead::/48"))
    }

    @Test
    fun protectedUidsCoverCoreSystemAndIsolatedAcrossUsers() {
        assertTrue(AppInventory.isProtectedUid(0))
        assertTrue(AppInventory.isProtectedUid(1000))
        assertTrue(AppInventory.isProtectedUid(1051))
        assertTrue(AppInventory.isProtectedUid(2000))
        assertTrue(AppInventory.isProtectedUid(101_000))
        assertTrue(AppInventory.isProtectedUid(99_001))
        assertFalse(AppInventory.isProtectedUid(10_277))
        assertFalse(AppInventory.isProtectedUid(110_277))
    }
}
