package dev.silo.app

import dev.silo.app.data.AppInventory
import dev.silo.app.data.AppLanguagePref
import dev.silo.app.data.PrivacyRiskLevel
import dev.silo.app.ipc.*
import dev.silo.app.ui.i18n.StringsEn
import dev.silo.app.ui.i18n.StringsId
import dev.silo.app.ui.i18n.resolveStrings
import dev.silo.app.ui.screens.ALL_PERM_CATEGORIES
import dev.silo.app.ui.screens.PermEffectiveUiMode
import dev.silo.app.ui.screens.formatTempUnblockMinutes
import dev.silo.app.ui.screens.partitionPermCategories
import dev.silo.app.ui.screens.resolveHasRuntimePerms
import dev.silo.app.ui.screens.resolvePermUiMode
import dev.silo.app.ui.screens.resolveSupportsForeground
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class PermissionStateMachineTest {

    @Test
    fun permUiModeScreenshotKameraRegressionNeverLightsNormal() {
        val defaultMode = resolvePermUiMode(hasRuntimePerms = true, runtimeGranted = false, appopsMode = "default")
        val allowMode = resolvePermUiMode(hasRuntimePerms = true, runtimeGranted = false, appopsMode = "allow")

        assertEquals(PermEffectiveUiMode.UNGRANTED, defaultMode)
        assertEquals(PermEffectiveUiMode.UNGRANTED, allowMode)

        assertFalse(defaultMode.isNormalPillActive)
        assertFalse(defaultMode.isForegroundPillActive)
        assertFalse(defaultMode.isFakeEmptyPillActive)
        assertFalse(defaultMode.isRevokedPillActive)
    }

    @Test
    fun permUiModeScreenshotLokasiRegressionLightsOnlyRevoke() {
        val mode = resolvePermUiMode(hasRuntimePerms = true, runtimeGranted = false, appopsMode = "ignore")

        assertEquals(PermEffectiveUiMode.REVOKED, mode)
        assertTrue(mode.isRevokedPillActive)
        assertFalse(mode.isFakeEmptyPillActive)
        assertFalse(mode.isNormalPillActive)
        assertFalse(mode.isForegroundPillActive)
    }

    @Test
    fun permUiModeExhaustiveMutualExclusivityInvariant() {
        val modes = listOf("default", "allow", "foreground", "ignore", "deny", "", "UNKNOWN", " IGNORE ", "ALLOW")
        for (hasRuntime in listOf(true, false)) {
            for (granted in listOf(true, false)) {
                for (rawMode in modes) {
                    val m = resolvePermUiMode(hasRuntime, granted, rawMode)
                    val activeCount = listOf(
                        m.isNormalPillActive,
                        m.isForegroundPillActive,
                        m.isFakeEmptyPillActive,
                        m.isRevokedPillActive,
                    ).count { it }
                    assertTrue(
                        "Expected <= 1 active pill for ($hasRuntime, $granted, '$rawMode'), got $activeCount ($m)",
                        activeCount <= 1,
                    )
                    if (hasRuntime && !granted) {
                        assertTrue(
                            "Ungranted runtime perm must resolve to REVOKED or UNGRANTED, got $m for '$rawMode'",
                            m == PermEffectiveUiMode.REVOKED || m == PermEffectiveUiMode.UNGRANTED,
                        )
                    }
                }
            }
        }

        // Explicit truth-table verification
        assertEquals(PermEffectiveUiMode.NORMAL, resolvePermUiMode(true, true, "default"))
        assertEquals(PermEffectiveUiMode.NORMAL, resolvePermUiMode(true, true, "allow"))
        assertEquals(PermEffectiveUiMode.FOREGROUND, resolvePermUiMode(true, true, "foreground"))
        assertEquals(PermEffectiveUiMode.FAKE_EMPTY, resolvePermUiMode(true, true, "ignore"))
        assertEquals(PermEffectiveUiMode.REVOKED, resolvePermUiMode(true, true, "deny"))
        assertEquals(PermEffectiveUiMode.REVOKED, resolvePermUiMode(true, false, "deny"))
        assertEquals(PermEffectiveUiMode.UNGRANTED, resolvePermUiMode(true, false, "foreground"))

        assertEquals(PermEffectiveUiMode.NORMAL, resolvePermUiMode(false, false, "default"))
        assertEquals(PermEffectiveUiMode.NORMAL, resolvePermUiMode(false, false, "allow"))
        assertEquals(PermEffectiveUiMode.FAKE_EMPTY, resolvePermUiMode(false, false, "ignore"))
        assertEquals(PermEffectiveUiMode.REVOKED, resolvePermUiMode(false, false, "deny"))
        assertEquals(PermEffectiveUiMode.FOREGROUND, resolvePermUiMode(false, false, "foreground"))
    }

    @Test
    fun permUiModeLiveStateFallbackAndSupportsForeground() {
        // Live runtimeGranted overrides stale local PackageManager granted=true
        val liveRevoked = CategoryPermLiveState(
            category = "camera",
            appopsMode = "ignore",
            runtimeGranted = false,
            hasRuntimePerms = true,
            supportsForeground = true,
        )
        assertEquals(
            PermEffectiveUiMode.REVOKED,
            resolvePermUiMode("camera", liveRevoked, localRuntimeGranted = true),
        )

        // When daemon omits runtime_granted (null), fallback cleanly to PackageManager
        val legacyLive = CategoryPermLiveState(
            category = "camera",
            appopsMode = "ignore",
            runtimeGranted = null,
        )
        assertEquals(
            PermEffectiveUiMode.FAKE_EMPTY,
            resolvePermUiMode("camera", legacyLive, localRuntimeGranted = true),
        )

        // Legacy daemon omitting has_runtime_perms/supports_foreground on clipboard/background
        assertFalse(resolveHasRuntimePerms("clipboard", CategoryPermLiveState(category = "clipboard")))
        assertFalse(resolveHasRuntimePerms("background", CategoryPermLiveState(category = "background")))
        assertFalse(resolveSupportsForeground("clipboard", CategoryPermLiveState(category = "clipboard")))
        assertFalse(resolveSupportsForeground("background", CategoryPermLiveState(category = "background")))

        // Exact foreground capability matrix (PROJECT.md § Interface Contracts § 2)
        for (cat in listOf("camera", "microphone", "location", "sensors")) {
            assertTrue("Expected supportsForeground=true for $cat", resolveSupportsForeground(cat, null))
        }
        for (cat in listOf("background_location", "contacts", "media", "sms_calllog", "clipboard", "background")) {
            assertFalse("Expected supportsForeground=false for $cat", resolveSupportsForeground(cat, null))
        }
    }

    @Test
    fun partitionPermCategoriesSurfacesDeclaredAppOpsAndActiveGuards() {
        val (primary, undeclared) = partitionPermCategories(
            allCategories = ALL_PERM_CATEGORIES,
            declaredCategories = setOf("camera"),
            grantedCategories = emptySet(),
            guard = AppPermGuard(),
            liveByCat = emptyMap(),
        )
        assertEquals(listOf("camera", "clipboard", "background"), primary)
        assertEquals(7, undeclared.size)

        // Undeclared categories with active guard or non-default AppOps are promoted to primary
        val (primary2, undeclared2) = partitionPermCategories(
            allCategories = ALL_PERM_CATEGORIES,
            declaredCategories = setOf("camera"),
            grantedCategories = emptySet(),
            guard = AppPermGuard(lockedDeny = listOf("location")),
            liveByCat = mapOf("contacts" to CategoryPermLiveState(category = "contacts", appopsMode = "ignore")),
        )
        assertTrue(primary2.containsAll(listOf("camera", "location", "contacts", "clipboard", "background")))
        assertEquals(5, undeclared2.size)
    }

    @Test
    fun tempUnblockMinutesRoundingMatchesBoundaryCases() {
        assertEquals(1L, formatTempUnblockMinutes(-5L))
        assertEquals(1L, formatTempUnblockMinutes(0L))
        assertEquals(1L, formatTempUnblockMinutes(1L))
        assertEquals(1L, formatTempUnblockMinutes(59L))
        assertEquals(1L, formatTempUnblockMinutes(60L))
        assertEquals(2L, formatTempUnblockMinutes(61L))
        assertEquals(15L, formatTempUnblockMinutes(900L))
        assertEquals(16L, formatTempUnblockMinutes(901L))
    }

    @Test
    fun appInventoryExcludesSpecialAppOpsFromGrantedAndCoversAndroid14Perms() {
        val specialOnly = AppInventory.evaluateOfflineRisk(
            declaredPermissions = listOf(
                "android.permission.SYSTEM_ALERT_WINDOW",
                "android.permission.MANAGE_EXTERNAL_STORAGE",
                "android.permission.PACKAGE_USAGE_STATS",
            ),
            grantedPermissions = listOf(
                "android.permission.SYSTEM_ALERT_WINDOW",
                "android.permission.MANAGE_EXTERNAL_STORAGE",
                "android.permission.PACKAGE_USAGE_STATS",
            ),
            componentNames = emptyList(),
        )
        assertTrue(specialOnly.declaredCategories.contains("background"))
        assertTrue(specialOnly.declaredCategories.contains("media"))
        assertTrue("Special AppOps must not inflate granted runtime categories", specialOnly.grantedCategories.isEmpty())
        assertEquals(PrivacyRiskLevel.LOW, specialOnly.level)

        val android14 = AppInventory.evaluateOfflineRisk(
            declaredPermissions = listOf(
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
                "android.permission.SEND_SMS",
                "android.permission.RECEIVE_MMS",
                "android.permission.BODY_SENSORS_BACKGROUND",
            ),
            grantedPermissions = listOf(
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
                "android.permission.SEND_SMS",
                "android.permission.RECEIVE_MMS",
                "android.permission.BODY_SENSORS_BACKGROUND",
            ),
            componentNames = listOf(
                "com.facebook.ads.AudienceNetworkActivity",
                "com.facebook.ads.AudienceNetworkContentProvider",
                "com.google.android.gms.ads.MobileAdsInitProvider",
            ),
        )
        assertTrue(android14.grantedCategories.contains("media"))
        assertTrue(android14.grantedCategories.contains("sms_calllog"))
        assertTrue(android14.grantedCategories.contains("sensors"))
        assertTrue(android14.detectedTrackers.contains("Meta Ads"))
        assertTrue(android14.detectedTrackers.contains("Google AdMob"))

        assertTrue(AppInventory.isProtectedPackage("dev.silo.app"))
        assertTrue(AppInventory.isProtectedPackage("com.samsung.android.honeyboard"))
        assertFalse(AppInventory.isProtectedPackage("com.example.userapp"))
        assertFalse(AppInventory.isProtectedPackage(null))
        assertFalse(AppInventory.isProtectedPackage("   "))
    }

    @Test
    fun appPermGuardMutualExclusivityAndDistinctTotalCount() {
        val overlapping = AppPermGuard(
            autoRevokeOnBg = listOf("camera", "location"),
            lockedDeny = listOf("location"),
        )
        assertEquals(2, overlapping.totalCount())

        val normalized = overlapping.normalized()
        assertEquals(listOf("camera"), normalized.autoRevokeOnBg)
        assertEquals(listOf("location"), normalized.lockedDeny)

        val switchedToAuto = normalized.withAutoRevoke("location", true)
        assertTrue(switchedToAuto.autoRevokeOnBg.contains("location"))
        assertFalse(switchedToAuto.lockedDeny.contains("location"))

        val switchedToLock = switchedToAuto.withStrictLock("camera", true)
        assertTrue(switchedToLock.lockedDeny.contains("camera"))
        assertFalse(switchedToLock.autoRevokeOnBg.contains("camera"))
    }

    @Test
    fun siloModelsV3AdditiveFieldsSerializeAndDeserializeCompatibly() {
        val legacyPerm = SiloJson.decodeFromString<CategoryPermLiveState>(
            """{"category":"camera","appops_mode":"ignore","last_access":"2m ago","running_now":false}"""
        )
        assertNull(legacyPerm.runtimeGranted)
        assertTrue(legacyPerm.hasRuntimePerms)
        assertTrue(legacyPerm.supportsForeground)
        assertFalse(legacyPerm.systemFixed)
        assertEquals("2m ago", legacyPerm.effectiveLastAccess)

        val v3Perm = SiloJson.decodeFromString<CategoryPermLiveState>(
            """
            {
              "category":"location",
              "appops_mode":"ignore",
              "last_access_ago":"12s ago",
              "running_now":true,
              "runtime_granted":false,
              "has_runtime_perms":true,
              "supports_foreground":true,
              "system_fixed":true,
              "future_extra_field":"ignored"
            }
            """.trimIndent()
        )
        assertEquals("12s ago", v3Perm.effectiveLastAccess)
        assertEquals(false, v3Perm.runtimeGranted)
        assertTrue(v3Perm.systemFixed)
        assertTrue(v3Perm.runningNow)

        val fw = FirewallConfig(enabled = true, panicLockdownActive = true)
        val fwJson = SiloJson.encodeToString(fw)
        assertTrue(fwJson.contains("\"panic_lockdown_active\":true"))
        assertEquals(fw, SiloJson.decodeFromString<FirewallConfig>(fwJson))

        val st = DaemonStatus(version = "3.0.0", panicLockdownActive = true)
        val stJson = SiloJson.encodeToString(st)
        assertTrue(stJson.contains("\"panic_lockdown_active\":true"))
        assertEquals(st, SiloJson.decodeFromString<DaemonStatus>(stJson))
    }

    @Test
    fun bilingualStringsCoverNewPermissionAndToastCopy() {
        assertEquals("Auto-Cabut", StringsId.enforcerAutoRevoke)
        assertEquals("Auto-Revoke", StringsEn.enforcerAutoRevoke)
        assertNotEquals(StringsId.permSystemFixedNote, StringsEn.permSystemFixedNote)
        assertNotEquals(StringsId.enforcerHint, StringsEn.enforcerHint)
        assertNotEquals(
            StringsId.toastPermActionApplied("camera", "revoke"),
            StringsEn.toastPermActionApplied("camera", "revoke"),
        )
        assertNotEquals(
            StringsId.toastPanicLockdownActive(3),
            StringsEn.toastPanicLockdownActive(3),
        )
        assertNotEquals(StringsId.emergencyFlushConfirmTitle, StringsEn.emergencyFlushConfirmTitle)
        assertNotEquals(StringsId.rollbackConfirmTitle, StringsEn.rollbackConfirmTitle)
        assertNotEquals(StringsId.tileTapAgain5s, StringsEn.tileTapAgain5s)

        assertTrue(resolveStrings(AppLanguagePref.SYSTEM, Locale("id", "ID")).isIndonesian)
        assertFalse(resolveStrings(AppLanguagePref.SYSTEM, Locale.US).isIndonesian)
    }
}
