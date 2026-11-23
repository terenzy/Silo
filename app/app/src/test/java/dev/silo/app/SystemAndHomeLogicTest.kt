package dev.silo.app

import dev.silo.app.ipc.*
import dev.silo.app.ui.screens.formatDurationLabel
import dev.silo.app.ui.screens.formatSecondsLabel
import dev.silo.app.ui.screens.isCustomPrivateDns
import dev.silo.app.ui.screens.matchSecurityPreset
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class SystemAndHomeLogicTest {

    private fun safeConfig(): SiloConfig = SiloConfig(
        firewall = FirewallConfig(enabled = false, mode = FirewallMode.DEFAULT_ALLOW),
        hardening = HardeningConfig(enabled = false),
        freezer = FreezerConfig(enabled = false),
        scopes = ScopesConfig(autoClearClipboard = false),
        automation = AutomationConfig(
            sensorsOffOnLock = false,
            usbAdbLockGuard = false,
            wifiAutoOffEnabled = false,
            bluetoothAutoOffEnabled = false,
        ),
    )

    private fun balancedConfig(): SiloConfig = SiloConfig(
        firewall = FirewallConfig(
            enabled = true,
            mode = FirewallMode.DEFAULT_ALLOW,
            isolateLan = true,
            dnsLeakGuard = true,
            ipv6LeakGuard = true,
            antiPortScan = true,
            blockCleartextHttpGlobal = false,
            normalizeTtlHl = false,
            blockNewApps = false,
        ),
        hardening = HardeningConfig(enabled = true),
        scopes = ScopesConfig(autoClearClipboard = true, clipboardClearSecs = 60),
        automation = AutomationConfig(
            sensorsOffOnLock = false,
            usbAdbLockGuard = false,
            wifiAutoOffEnabled = false,
            bluetoothAutoOffEnabled = false,
        ),
    )

    private fun hardenedConfig(): SiloConfig = SiloConfig(
        firewall = FirewallConfig(
            enabled = true,
            mode = FirewallMode.DEFAULT_ALLOW,
            isolateLan = true,
            dnsLeakGuard = true,
            ipv6LeakGuard = true,
            antiPortScan = true,
            blockCleartextHttpGlobal = true,
            normalizeTtlHl = true,
            ttlValue = 64,
            blockNewApps = true,
        ),
        hardening = HardeningConfig(enabled = true),
        scopes = ScopesConfig(autoClearClipboard = true, clipboardClearSecs = 45),
        automation = AutomationConfig(
            sensorsOffOnLock = true,
            usbAdbLockGuard = false,
            wifiAutoOffEnabled = false,
            bluetoothAutoOffEnabled = false,
        ),
    )

    private fun paranoidConfig(): SiloConfig = hardenedConfig().let { base ->
        base.copy(
            firewall = base.firewall.copy(
                mode = FirewallMode.DEFAULT_DENY,
                blockAction = BlockAction.REJECT,
            ),
            scopes = base.scopes.copy(clipboardClearSecs = 30),
            automation = base.automation.copy(
                wifiAutoOffEnabled = true,
                bluetoothAutoOffEnabled = true,
                usbAdbLockGuard = true,
            ),
        )
    }

    @Test
    fun formatDurationLabelFormatsMinutesHoursAndMixedCorrectly() {
        assertEquals("0m", formatDurationLabel(0))
        assertEquals("0m", formatDurationLabel(-15))
        assertEquals("15m", formatDurationLabel(15))
        assertEquals("30m", formatDurationLabel(30)) // Regression for APP-H16: never "0h"!
        assertEquals("59m", formatDurationLabel(59))
        assertEquals("1h", formatDurationLabel(60))
        assertEquals("1h 30m", formatDurationLabel(90))
        assertEquals("4h", formatDurationLabel(240))
        assertEquals("12h", formatDurationLabel(720))
        assertEquals("18h", formatDurationLabel(1080))
        assertEquals("24h", formatDurationLabel(1440))
    }

    @Test
    fun formatSecondsLabelFormatsSecondsMinutesAndMixedCorrectly() {
        assertEquals("0s", formatSecondsLabel(0))
        assertEquals("0s", formatSecondsLabel(-5))
        assertEquals("10s", formatSecondsLabel(10))
        assertEquals("20s", formatSecondsLabel(20))
        assertEquals("45s", formatSecondsLabel(45))
        assertEquals("59s", formatSecondsLabel(59))
        assertEquals("1m", formatSecondsLabel(60))
        assertEquals("1m 30s", formatSecondsLabel(90))
        assertEquals("2m", formatSecondsLabel(120))
        assertEquals("5m", formatSecondsLabel(300))
    }

    @Test
    fun matchSecurityPresetMatchesAllFourCanonicalPresets() {
        assertEquals(SecurityPreset.SAFE, matchSecurityPreset(safeConfig()))
        assertEquals(SecurityPreset.BALANCED, matchSecurityPreset(balancedConfig()))
        assertEquals(SecurityPreset.HARDENED, matchSecurityPreset(hardenedConfig()))
        assertEquals(SecurityPreset.PARANOID, matchSecurityPreset(paranoidConfig()))
    }

    @Test
    fun matchSecurityPresetAllowsManualAutoRebootOptIn() {
        assertEquals(
            SecurityPreset.SAFE,
            matchSecurityPreset(safeConfig().copy(automation = AutomationConfig(autoRebootEnabled = true))),
        )
        assertEquals(
            SecurityPreset.BALANCED,
            matchSecurityPreset(
                balancedConfig().let {
                    it.copy(
                        automation = it.automation.copy(autoRebootEnabled = true),
                        freezer = it.freezer.copy(enabled = true),
                    )
                }
            ),
        )
    }

    @Test
    fun matchSecurityPresetReturnsNullForPartialOrCustomConfigs() {
        val fwOnly = SiloConfig(firewall = FirewallConfig(enabled = true))
        assertNull(matchSecurityPreset(fwOnly))

        val fwHttpOnly = SiloConfig(
            firewall = FirewallConfig(enabled = true, blockCleartextHttpGlobal = true)
        )
        assertNull(matchSecurityPreset(fwHttpOnly))

        val denyOnly = SiloConfig(
            firewall = FirewallConfig(enabled = true, mode = FirewallMode.DEFAULT_DENY)
        )
        assertNull(matchSecurityPreset(denyOnly))

        assertNull(matchSecurityPreset(safeConfig().copy(freezer = FreezerConfig(enabled = true))))
        assertNull(matchSecurityPreset(balancedConfig().let { it.copy(firewall = it.firewall.copy(isolateLan = false)) }))
        assertNull(matchSecurityPreset(balancedConfig().let { it.copy(scopes = it.scopes.copy(clipboardClearSecs = 45)) }))
        assertNull(matchSecurityPreset(hardenedConfig().let { it.copy(automation = it.automation.copy(sensorsOffOnLock = false)) }))
        assertNull(matchSecurityPreset(hardenedConfig().let { it.copy(firewall = it.firewall.copy(ttlValue = 128)) }))
        assertNull(matchSecurityPreset(paranoidConfig().let { it.copy(firewall = it.firewall.copy(blockAction = BlockAction.DROP)) }))
        assertNull(matchSecurityPreset(paranoidConfig().let { it.copy(automation = it.automation.copy(usbAdbLockGuard = false)) }))
    }

    @Test
    fun matchSecurityPresetReturnsNullWhenPanicLockdownActive() {
        assertNull(matchSecurityPreset(paranoidConfig(), panicLockdownActive = true))
        assertNull(matchSecurityPreset(safeConfig(), panicLockdownActive = true))

        val panicCfg = paranoidConfig().let {
            it.copy(firewall = it.firewall.copy(panicLockdownActive = true))
        }
        assertNull(matchSecurityPreset(panicCfg))
    }

    @Test
    fun isCustomPrivateDnsDistinguishesPresetsAndBlankFromCustomHostnames() {
        assertFalse(isCustomPrivateDns("off", ""))
        assertFalse(isCustomPrivateDns("opportunistic", ""))
        assertFalse(isCustomPrivateDns("hostname", ""))
        assertFalse(isCustomPrivateDns("hostname", "   "))
        assertFalse(isCustomPrivateDns("hostname", "dns.quad9.net"))
        assertFalse(isCustomPrivateDns("hostname", "DNS.MULLVAD.NET"))
        assertTrue(isCustomPrivateDns("hostname", "dns.custom.example"))
    }

    @Test
    fun panicLockdownActiveRoundTripsAndDefaultsFalseWhenOmitted() {
        val cfg = SiloConfig(firewall = FirewallConfig(enabled = true, panicLockdownActive = true))
        val encoded = SiloJson.encodeToString(cfg)
        assertTrue(encoded.contains("\"panic_lockdown_active\":true"))
        assertEquals(cfg, SiloJson.decodeFromString<SiloConfig>(encoded))

        val legacyStatus = SiloJson.decodeFromString<DaemonStatus>("""{"version":"3.0.0"}""")
        assertFalse(legacyStatus.panicLockdownActive)
    }
}
