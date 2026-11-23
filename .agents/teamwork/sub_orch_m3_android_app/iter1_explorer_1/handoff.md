# Implementation Blueprint — `iter1_explorer_1` (Workstream 1: Permission State Machine, `AppsScreen`, `AppInventory`, `SiloModels` & `SiloStrings`)

**Status:** Complete read-only forensic analysis & implementation-ready Kotlin/test blueprint grounded in direct file inspection (`SiloModels.kt`, `AppInventory.kt`, `AppsScreen.kt`, `SiloStrings.kt`, `Theme.kt`, `ProtocolAndParserTest.kt`, `SiloClient.kt`, `SiloViewModel.kt`, `SiloRepository.kt`, `TrafficScreen.kt`, `HomeScreen.kt`, `SystemScreen.kt`, `MainActivity.kt`, `tiles/*.kt`, `daemon/src/scopes.rs`, `daemon/src/protocol.rs`, `daemon/src/config.rs`), `ORIGINAL_REQUEST.md`, `PROJECT.md § Interface Contracts § 2 & § 3`, and `/home/antigravity/Screenshot_20261122_223732_Silo.jpg`.

---

## 1. Cross-Workstream Contract & Critical Pitfalls (Read First!)

Before implementing, note 4 critical findings observed directly in the repository:

1. **Exact DTO field name in `daemon/src/protocol.rs:606` vs `PROJECT.md:90`**:
   - Existing `daemon/src/protocol.rs:606` and `SiloModels.kt:230` use `@SerialName("last_access") val lastAccess: String? = null` (whereas the illustrative snippet in `PROJECT.md:90` wrote `"last_access_ago"`).
   - **Solution**: Keep `@SerialName("last_access") val lastAccess: String? = null` AND add additive `@SerialName("last_access_ago") val lastAccessAgo: String? = null` with `val effectiveLastAccess: String? get() = lastAccess?.takeIf { it.isNotBlank() } ?: lastAccessAgo?.takeIf { it.isNotBlank() }`. Because `SiloJson` uses `explicitNulls = false` and `ignoreUnknownKeys = true`, this is 100% compatible with BOTH field names!
2. **Exact data class name in `AppInventory.kt:27`**:
   - The class is **`InstalledApp`** (NOT `InstalledAppInfo`). Therefore `reloadSingleApp` MUST return **`InstalledApp?`**.
3. **CRITICAL JVM Unit Test Pitfall with `android.util.LruCache` (`APP-L05`)**:
   - `app/app/build.gradle.kts` does **not** set `unitTests.isReturnDefaultValues = true`. Eagerly declaring `private val iconCache = android.util.LruCache<String, ImageBitmap>(150)` inside `object AppInventory` will crash **every** JVM unit test calling `AppInventory.evaluateOfflineRisk(...)` or `AppInventory.isProtectedUid(...)` with `RuntimeException: Method <init> in android.util.LruCache not mocked`!
   - **Mandatory fix**: Declare `private val iconCache by lazy { android.util.LruCache<String, ImageBitmap>(150) }` so `LruCache` is only allocated when `loadAppIcon()` runs on Android.
4. **Exact val names in `SiloStrings.kt:315, 551`**:
   - The bilingual instances are named **`StringsId`** and **`StringsEn`** (not `IndonesianStrings`/`EnglishStrings`). Preserve those exact public symbol names so `resolveStrings()` and tests remain compatible.

### Workstream 1 ↔ Workstream 2 (`SiloUiState` / `SiloViewModel`) Interface Contract
`AppsScreen.kt` depends on the following exact additive fields in `SiloUiState` and methods in `SiloViewModel` (coordinate with Workstream 2 coder):
- **`SiloUiState` additive fields**:
  ```kotlin
  val snackbarSeq: Long = 0L,                     // monotonic counter so identical repeated errors re-trigger banner/snackbar
  val snackbarIsError: Boolean = false,           // styles inline sheet banner crimson vs emerald
  val loadingAppPerms: Set<String> = emptySet(),  // packages currently running inspectAppPermissions
  val appPermsErrors: Map<String, String> = emptyMap(), // error string per package when inspectAppPermissions fails
  val pendingPermKeys: Set<String> = emptySet(),  // e.g. "$pkg:$cat:$action", "$pkg:$cat:guard_auto", "$pkg:$cat:guard_lock", "$pkg:*:revoke_all", "bulk:$cat"
  ```
- **`SiloViewModel` additive/updated methods**:
  - `fun setActiveInspectedPackage(packageName: String?)` — tracks currently open sheet package so `onForegroundChanged(true)` refreshes `liveAppPerms[pkg]` (`APP-H11`).
  - `fun ensureEntryAndFreeze(uid: Int, entry: FreezerEntry)` — sequentially calls `setFreezerEntry` then `freezeUidNow` in ONE coroutine (`APP-M02`).
  - `fun clearLegacyAppScope(packageName: String)` — calls `repo.clearAppScope(packageName)` and removes `packageName` from `config.scopes.apps` (`APP-M18`).
  - `toggleAppAutoRevoke(pkg, cat, enabled)` / `toggleAppStrictLock(pkg, cat, enabled)` — use `current.withAutoRevoke(cat, enabled)` / `current.withStrictLock(cat, enabled)` inside ViewModel mutation `Mutex`, tracking `"$pkg:$cat:guard_auto"` / `"$pkg:$cat:guard_lock"` in `pendingPermKeys`, refreshing single app via `AppInventory.reloadSingleApp(ctx, pkg)` (`APP-H04`, `APP-M19`).
  - `setAppPermissionAction(pkg, cat, action)` — tracks `"$pkg:$cat:$action"` in `pendingPermKeys`, refreshes single app via `AppInventory.reloadSingleApp(ctx, pkg)`, and when `action == "allow"` strips `cat` from `config.scopes.guards[pkg].lockedDeny` (matching daemon contract `PROJECT.md § Interface Contracts § 2` so `Kunci Blokir` unchecks immediately!).

---

## 2. Blueprint — Permission State Machine (`APP-C01`, `APP-C02`, `APP-H07`)

### Current Defects Observed
- `AppsScreen.kt:859-866`: Badge checks `appopsMode == "ignore"` (`PALSU KOSONG`) **before** `!runtimeGranted` → revoked+ignored `Lokasi` wrongly displays `PALSU KOSONG`.
- `AppsScreen.kt:895-901`: `Normal` pill uses `active = appopsMode == "default" || appopsMode == "allow"`, ignoring `runtimeGranted` → revoked `Kamera` (`runtimeGranted=false, appopsMode="default"/"allow"`) wrongly lights `Normal` green while badge says gray `DICABUT` ("saat dicabut malah diizinkan").
- `AppsScreen.kt:910-922`: `Palsukan` (`appopsMode == "ignore"`) and `Cabut` (`!runtimeGranted && (appopsMode == "ignore" || appopsMode == "deny")`) overlap whenever `!runtimeGranted && appopsMode == "ignore"` → `Lokasi` lights **both** `Palsukan` (green) AND `Cabut` (red) simultaneously.
- `AppsScreen.kt:902-908`: `Saat Dibuka` (`foreground`) is shown on all 10 categories even when Android rejects `foreground` (`background_location`, `contacts`, `media`, `sms_calllog`, `clipboard`, `background`).

### Exact Pure Kotlin State Machine (Top-Level in `AppsScreen.kt` or `dev.silo.app.ui.screens`)
Place as public top-level declarations (zero Android/Compose runtime dependencies inside the functions so pure JVM JUnit tests execute directly in <1ms):

```kotlin
enum class PermEffectiveUiMode {
    /** Runtime granted (or AppOps-only allowed) AND AppOps allows normal access ("default"/"allow"). */
    NORMAL,
    /** Runtime granted (or AppOps-only with foreground) AND AppOps mode == "foreground". */
    FOREGROUND,
    /** Runtime still granted (or AppOps-only), AppOps mode == "ignore" (silent fake-empty block). */
    FAKE_EMPTY,
    /** Actively revoked/blocked: (!runtimeGranted && appopsMode in {"ignore","deny"}) OR (appopsMode == "deny"). */
    REVOKED,
    /** Runtime permission NOT granted AND AppOps not actively ignoring/denying (e.g. default/allow/foreground). */
    UNGRANTED;

    val isNormalPillActive: Boolean get() = this == NORMAL
    val isForegroundPillActive: Boolean get() = this == FOREGROUND
    val isFakeEmptyPillActive: Boolean get() = this == FAKE_EMPTY
    val isRevokedPillActive: Boolean get() = this == REVOKED
}

val ALL_PERM_CATEGORIES: List<String> = listOf(
    "camera",
    "microphone",
    "location",
    "background_location",
    "contacts",
    "media",
    "sms_calllog",
    "clipboard",
    "sensors",
    "background",
)

val APPOPS_ONLY_CATEGORIES: Set<String> = setOf("clipboard", "background")

/** Exact contract from PROJECT.md § Interface Contracts § 2. */
val FOREGROUND_SUPPORTED_CATEGORIES: Set<String> = setOf(
    "camera",
    "microphone",
    "location",
    "sensors",
)

/**
 * Pure, strictly mutually-exclusive UI mode resolver.
 * Guarantee: Across ALL possible inputs, AT MOST ONE action pill is EVER active (`0` for UNGRANTED, `1` for others),
 * and `Normal` is NEVER active when `hasRuntimePerms && !runtimeGranted`.
 */
fun resolvePermUiMode(
    hasRuntimePerms: Boolean,
    runtimeGranted: Boolean,
    appopsMode: String,
): PermEffectiveUiMode {
    val mode = appopsMode.trim().lowercase()
    return if (hasRuntimePerms) {
        if (!runtimeGranted) {
            when (mode) {
                "ignore", "deny" -> PermEffectiveUiMode.REVOKED
                else -> PermEffectiveUiMode.UNGRANTED
            }
        } else {
            when (mode) {
                "ignore" -> PermEffectiveUiMode.FAKE_EMPTY
                "deny" -> PermEffectiveUiMode.REVOKED
                "foreground" -> PermEffectiveUiMode.FOREGROUND
                else -> PermEffectiveUiMode.NORMAL
            }
        }
    } else {
        when (mode) {
            "ignore" -> PermEffectiveUiMode.FAKE_EMPTY
            "deny" -> PermEffectiveUiMode.REVOKED
            "foreground" -> PermEffectiveUiMode.FOREGROUND
            else -> PermEffectiveUiMode.NORMAL
        }
    }
}

/**
 * Convenience resolver combining daemon `CategoryPermLiveState?` with local `PackageManager` fallback.
 * Safe against older daemons that omit `has_runtime_perms` / `runtime_granted` (defaults are guarded by known category sets).
 */
fun resolvePermUiMode(
    category: String,
    liveState: CategoryPermLiveState?,
    localRuntimeGranted: Boolean,
): PermEffectiveUiMode {
    val hasRuntime = resolveHasRuntimePerms(category, liveState)
    val effectiveGranted = if (!hasRuntime) {
        false
    } else {
        liveState?.runtimeGranted ?: localRuntimeGranted
    }
    val mode = liveState?.appopsMode ?: "default"
    return resolvePermUiMode(
        hasRuntimePerms = hasRuntime,
        runtimeGranted = effectiveGranted,
        appopsMode = mode,
    )
}

fun resolveHasRuntimePerms(
    category: String,
    liveState: CategoryPermLiveState?,
): Boolean = (liveState?.hasRuntimePerms ?: true) && !APPOPS_ONLY_CATEGORIES.contains(category)

fun resolveSupportsForeground(
    category: String,
    liveState: CategoryPermLiveState?,
): Boolean {
    val daemonFlag = liveState?.supportsForeground ?: true
    return if (category in ALL_PERM_CATEGORIES) {
        daemonFlag && FOREGROUND_SUPPORTED_CATEGORIES.contains(category)
    } else {
        daemonFlag
    }
}
```

### Complete Truth Table & Badge Mapping (Verified Against Both Screenshot Regressions)

| Scenario | `hasRuntimePerms` | `runtimeGranted` | `appopsMode` | `PermEffectiveUiMode` | Active Pill (Strictly ≤1) | Badge (`runningNow == false`) |
|---|---|---|---|---|---|---|
| **Screenshot `Kamera`** | `true` | `false` | `"default"` / `"allow"` | **`UNGRANTED`** | **ZERO pills lit** (`Normal` = OFF!) | `DICABUT` / `REVOKED` (`onSurfaceVariant` gray) |
| Runtime ungranted + stale fg | `true` | `false` | `"foreground"` | **`UNGRANTED`** | **ZERO pills lit** | `DICABUT` / `REVOKED` (`onSurfaceVariant` gray) |
| **Screenshot `Lokasi`** | `true` | `false` | `"ignore"` | **`REVOKED`** | **ONLY `Cabut`** (`SiloCrimson`) | `DICABUT` / `REVOKED` (`SiloEmerald`) |
| Runtime revoked + deny | `true` | `false` | `"deny"` | **`REVOKED`** | **ONLY `Cabut`** (`SiloCrimson`) | `DICABUT` / `REVOKED` (`SiloEmerald`) |
| **Screenshot `Mikrofon`** | `true` | `true` | `"default"` / `"allow"` | **`NORMAL`** | **ONLY `Normal`** (`primary`) | `AKTIF` / `GRANTED` (`SiloAmber`) |
| Runtime foreground | `true` | `true` | `"foreground"` | **`FOREGROUND`** | **ONLY `Saat Dibuka`** (`SiloCyan`) | `SAAT DIBUKA` / `FOREGROUND` (`SiloCyan`) |
| Runtime fake-empty | `true` | `true` | `"ignore"` | **`FAKE_EMPTY`** | **ONLY `Palsukan`** (`SiloEmerald`) | `PALSU KOSONG` / `FAKE EMPTY` (`SiloEmerald`) |
| `SYSTEM_FIXED` + AppOps deny | `true` | `true` | `"deny"` | **`REVOKED`** | **ONLY `Cabut`** (`SiloCrimson`) | `DICABUT` / `REVOKED` (`SiloEmerald`) |
| AppOps-only (`clipboard`/`background`) allowed | `false` | `false` | `"default"` / `"allow"` | **`NORMAL`** | **ONLY `Normal`** (`primary`) | `AKTIF` / `GRANTED` (`SiloAmber`) |
| AppOps-only fake-empty | `false` | `false` | `"ignore"` | **`FAKE_EMPTY`** | **ONLY `Palsukan`** (`SiloEmerald`) | `PALSU KOSONG` / `FAKE EMPTY` (`SiloEmerald`) |
| AppOps-only denied | `false` | `false` | `"deny"` | **`REVOKED`** | **ONLY `Cabut`** (`SiloCrimson`) | `DICABUT` / `REVOKED` (`SiloEmerald`) |
| AppOps-only foreground (defensive) | `false` | `false` | `"foreground"` | **`FOREGROUND`** | **ONLY `Saat Dibuka`** (if shown) | `SAAT DIBUKA` / `FOREGROUND` (`SiloCyan`) |
| Any row with `runningNow == true` | any | any | any | same `uiMode` | same single pill as `uiMode` | **`SEDANG DIPAKAI` / `IN USE NOW` (`SiloCrimson`)** |

**Badge derivation in `PermissionCategoryControlRow`**:
```kotlin
val (statusLabel, statusColor) = when {
    runningNow -> s.permStateRunning to SiloCrimson
    uiMode == PermEffectiveUiMode.REVOKED -> s.permStateDenied to SiloEmerald
    uiMode == PermEffectiveUiMode.FAKE_EMPTY -> s.permStateIgnored to SiloEmerald
    uiMode == PermEffectiveUiMode.FOREGROUND -> s.permStateForeground to SiloCyan
    uiMode == PermEffectiveUiMode.NORMAL -> s.permStateGranted to SiloAmber
    else -> s.permStateDenied to MaterialTheme.colorScheme.onSurfaceVariant // UNGRANTED
}
```

**`systemFixed == true` handling**:
- Render `MiniTag(s.permSystemFixedBadge, SiloAmber)` beside/below the status badge in the row header AND render a concise `Text(s.permSystemFixedNote, style = MaterialTheme.typography.labelSmall, color = SiloAmber)` beneath the description so preinstalled Samsung permissions locked by Android OS are explained honestly.

---

## 3. Blueprint — `SiloModels.kt` (`PROJECT.md § Interface Contracts § 2 & § 3`, `APP-M19`)

Target file: `/home/antigravity/terenzy/Silo/app/app/src/main/java/dev/silo/app/ipc/SiloModels.kt`

1. **Add shared `SiloJson` (top-level val)** and have `SiloClient.kt:33-37` reference `val json: Json = SiloJson`:
   ```kotlin
   import kotlinx.serialization.json.Json

   val SiloJson: Json = Json {
       ignoreUnknownKeys = true
       encodeDefaults = true
       explicitNulls = false
   }
   ```
2. **`FirewallConfig` (lines 68–85)** — add additive field with default `false`:
   ```kotlin
   @SerialName("panic_lockdown_active") val panicLockdownActive: Boolean = false,
   ```
3. **`DaemonStatus` (lines 263–294)** — add additive field with default `false`:
   ```kotlin
   @SerialName("panic_lockdown_active") val panicLockdownActive: Boolean = false,
   ```
4. **`CategoryPermLiveState` (lines 226–232)** — preserve `"last_access"`, add `"last_access_ago"` alias + all 4 v3 additive fields:
   ```kotlin
   @Serializable
   data class CategoryPermLiveState(
       val category: String = "",
       @SerialName("appops_mode") val appopsMode: String = "default",
       @SerialName("last_access") val lastAccess: String? = null,
       @SerialName("last_access_ago") val lastAccessAgo: String? = null,
       @SerialName("running_now") val runningNow: Boolean = false,
       @SerialName("runtime_granted") val runtimeGranted: Boolean? = null,
       @SerialName("has_runtime_perms") val hasRuntimePerms: Boolean = true,
       @SerialName("supports_foreground") val supportsForeground: Boolean = true,
       @SerialName("system_fixed") val systemFixed: Boolean = false,
   ) {
       val effectiveLastAccess: String?
           get() = lastAccess?.takeIf { it.isNotBlank() } ?: lastAccessAgo?.takeIf { it.isNotBlank() }
   }
   ```
5. **`AppPermGuard` (lines 126–132, `APP-M19`)** — distinct count + pure mutually-exclusive mutators:
   ```kotlin
   @Serializable
   data class AppPermGuard(
       @SerialName("auto_revoke_on_bg") val autoRevokeOnBg: List<String> = emptyList(),
       @SerialName("locked_deny") val lockedDeny: List<String> = emptyList(),
   ) {
       fun totalCount(): Int = (autoRevokeOnBg.asSequence() + lockedDeny.asSequence()).toSet().size

       /** Enforces mutual exclusivity matching daemon (`locked_deny` takes precedence). */
       fun normalized(): AppPermGuard {
           val locked = lockedDeny.distinct()
           val lockedSet = locked.toSet()
           val auto = autoRevokeOnBg.distinct().filterNot { it in lockedSet }
           return copy(autoRevokeOnBg = auto, lockedDeny = locked)
       }

       fun withAutoRevoke(category: String, enabled: Boolean): AppPermGuard {
           val nextAuto = if (enabled) (autoRevokeOnBg + category).distinct() else autoRevokeOnBg.filter { it != category }
           val nextLocked = if (enabled) lockedDeny.filter { it != category } else lockedDeny
           return copy(autoRevokeOnBg = nextAuto, lockedDeny = nextLocked)
       }

       fun withStrictLock(category: String, enabled: Boolean): AppPermGuard {
           val nextLocked = if (enabled) (lockedDeny + category).distinct() else lockedDeny.filter { it != category }
           val nextAuto = if (enabled) autoRevokeOnBg.filter { it != category } else autoRevokeOnBg
           return copy(autoRevokeOnBg = nextAuto, lockedDeny = nextLocked)
       }
   }
   ```

---

## 4. Blueprint — `AppInventory.kt` (`APP-H08`, `APP-M08`, `APP-H04`, `APP-L05`)

Target file: `/home/antigravity/terenzy/Silo/app/app/src/main/java/dev/silo/app/data/AppInventory.kt`

1. **Bounded lazy `LruCache` (`APP-L05`)**:
   - Remove `import java.util.concurrent.ConcurrentHashMap` (line 16).
   - Replace line 42 (`private val iconCache = ConcurrentHashMap<String, ImageBitmap>()`) with:
     ```kotlin
     private val iconCache by lazy { android.util.LruCache<String, ImageBitmap>(150) }
     ```
   - Update `loadAppIcon` (lines 215, 220): `iconCache.get(packageName)` and `iconCache.put(packageName, bmp)`.
2. **Expose `isProtectedPackage` (needed by `TrafficScreen.kt` `APP-H14`)**:
   ```kotlin
   fun isProtectedPackage(packageName: String?): Boolean =
       !packageName.isNullOrBlank() && protectedPackages.contains(packageName)
   ```
3. **Separate Runtime Permissions from Special AppOps Permissions & Align Android 12–16 (`APP-H08`)**:
   Replace `permissionToCategory` (lines 67–88) with two explicit maps aligned 100% with `daemon/src/scopes.rs`:
   ```kotlin
   /** Dangerous runtime permissions revocable via `pm revoke` (aligned with daemon `scopes.rs`). */
   private val runtimePermissionToCategory = mapOf(
       Manifest.permission.CAMERA to "camera",
       Manifest.permission.RECORD_AUDIO to "microphone",
       Manifest.permission.ACCESS_FINE_LOCATION to "location",
       Manifest.permission.ACCESS_COARSE_LOCATION to "location",
       Manifest.permission.ACCESS_BACKGROUND_LOCATION to "background_location",
       Manifest.permission.READ_CONTACTS to "contacts",
       Manifest.permission.WRITE_CONTACTS to "contacts",
       Manifest.permission.READ_EXTERNAL_STORAGE to "media",
       Manifest.permission.WRITE_EXTERNAL_STORAGE to "media",
       "android.permission.READ_MEDIA_IMAGES" to "media",
       "android.permission.READ_MEDIA_VIDEO" to "media",
       "android.permission.READ_MEDIA_AUDIO" to "media",
       "android.permission.READ_MEDIA_VISUAL_USER_SELECTED" to "media",
       Manifest.permission.READ_SMS to "sms_calllog",
       Manifest.permission.RECEIVE_SMS to "sms_calllog",
       Manifest.permission.SEND_SMS to "sms_calllog",
       Manifest.permission.RECEIVE_MMS to "sms_calllog",
       Manifest.permission.READ_CALL_LOG to "sms_calllog",
       Manifest.permission.WRITE_CALL_LOG to "sms_calllog",
       "android.permission.BODY_SENSORS" to "sensors",
       "android.permission.BODY_SENSORS_BACKGROUND" to "sensors",
       "android.permission.ACTIVITY_RECOGNITION" to "sensors",
   )

   /**
    * Special AppOps permissions (NOT revocable via `pm revoke`).
    * Included in `declaredCategories` so the app's relevant category card surfaces in UI,
    * but EXCLUDED from `grantedCategories` because `REQUESTED_PERMISSION_GRANTED` does not
    * reflect AppOps `ignore`/`deny` state (fixes `Latar & Overlay` permanently stuck on `Kamera`).
    */
   private val specialAppOpPermissionToCategory = mapOf(
       "android.permission.MANAGE_EXTERNAL_STORAGE" to "media",
       "android.permission.SYSTEM_ALERT_WINDOW" to "background",
       "android.permission.PACKAGE_USAGE_STATS" to "background",
   )
   ```
   Update `evaluateOfflineRisk` (lines 126–133):
   ```kotlin
   val declaredCats = LinkedHashSet<String>()
   for (p in declaredPermissions) {
       (runtimePermissionToCategory[p] ?: specialAppOpPermissionToCategory[p])?.let { declaredCats.add(it) }
   }
   val grantedCats = LinkedHashSet<String>()
   for (p in grantedPermissions) {
       runtimePermissionToCategory[p]?.let { grantedCats.add(it) }
   }
   ```
4. **Shared `buildInstalledApp`, `GET_ACTIVITIES | GET_PROVIDERS`, Binder Fallback & Fast `reloadSingleApp` (`APP-H04`, `APP-M08`)**:
   ```kotlin
   private const val FULL_PACKAGE_FLAGS =
       PackageManager.GET_PERMISSIONS or
       PackageManager.GET_ACTIVITIES or
       PackageManager.GET_RECEIVERS or
       PackageManager.GET_SERVICES or
       PackageManager.GET_PROVIDERS

   private const val LIGHT_PACKAGE_FLAGS =
       PackageManager.GET_PERMISSIONS or
       PackageManager.GET_RECEIVERS or
       PackageManager.GET_SERVICES

   val appComparator: Comparator<InstalledApp> = compareBy<InstalledApp>(
       { it.isProtected },
       { !it.hasLauncher },
       { it.isSystem },
       { it.label.lowercase() },
   )

   private fun getPackageInfoSafe(pm: PackageManager, packageName: String): PackageInfo? {
       return try {
           pm.getPackageInfo(packageName, FULL_PACKAGE_FLAGS)
       } catch (_: Exception) {
           runCatching { pm.getPackageInfo(packageName, LIGHT_PACKAGE_FLAGS) }.getOrNull()
               ?: runCatching { pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS) }.getOrNull()
       }
   }

   private fun buildInstalledApp(pm: PackageManager, pkgInfo: PackageInfo): InstalledApp? {
       val appInfo = pkgInfo.applicationInfo ?: return null
       val uid = appInfo.uid
       val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
       val hasLauncher = runCatching { pm.getLaunchIntentForPackage(pkgInfo.packageName) != null }
           .getOrDefault(false)
       val reqPerms = pkgInfo.requestedPermissions ?: emptyArray()
       val reqFlags = pkgInfo.requestedPermissionsFlags ?: IntArray(0)
       val declaredList = reqPerms.toList()
       val grantedList = buildList {
           for (i in reqPerms.indices) {
               val f = if (i < reqFlags.size) reqFlags[i] else 0
               if ((f and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) {
                   add(reqPerms[i])
               }
           }
       }
       val hasNet = declaredList.contains(Manifest.permission.INTERNET)
       val components = buildList {
           pkgInfo.activities?.forEach { it.name?.let(::add) }
           pkgInfo.receivers?.forEach { it.name?.let(::add) }
           pkgInfo.services?.forEach { it.name?.let(::add) }
           pkgInfo.providers?.forEach { it.name?.let(::add) }
       }
       val riskResult = evaluateOfflineRisk(declaredList, grantedList, components)
       val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
           .getOrDefault(pkgInfo.packageName)
       val protected = isProtectedUid(uid) || protectedPackages.contains(pkgInfo.packageName)
       return InstalledApp(
           uid = uid,
           packageName = pkgInfo.packageName,
           label = label,
           isSystem = isSystem,
           hasLauncher = hasLauncher,
           hasInternetPermission = hasNet,
           isProtected = protected,
           riskLevel = riskResult.level,
           grantedSensitiveCategories = riskResult.grantedCategories,
           declaredSensitiveCategories = riskResult.declaredCategories,
           detectedTrackers = riskResult.detectedTrackers,
       )
   }

   /** Fast (~5ms) single-package refresh after per-app permission/guard changes (`APP-H04`). */
   suspend fun reloadSingleApp(context: Context, packageName: String): InstalledApp? =
       withContext(Dispatchers.IO) {
           val pm = context.packageManager
           val pkgInfo = getPackageInfoSafe(pm, packageName) ?: return@withContext null
           runCatching { buildInstalledApp(pm, pkgInfo) }.getOrNull()
       }

   suspend fun loadInstalledApps(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
       val pm = context.packageManager
       val packages: List<PackageInfo> = try {
           pm.getInstalledPackages(FULL_PACKAGE_FLAGS)
       } catch (_: Exception) {
           // Binder TransactionTooLargeException / DeadObjectException fallback:
           // enumerate bare package names then inspect each package individually.
           try {
               pm.getInstalledPackages(0).mapNotNull { stub ->
                   getPackageInfoSafe(pm, stub.packageName) ?: stub
               }
           } catch (_: Exception) {
               runCatching { pm.getInstalledPackages(LIGHT_PACKAGE_FLAGS) }
                   .getOrElse {
                       runCatching { pm.getInstalledPackages(PackageManager.GET_PERMISSIONS) }
                           .getOrDefault(emptyList())
                   }
           }
       }
       packages.mapNotNull { pkgInfo ->
           runCatching { buildInstalledApp(pm, pkgInfo) }.getOrNull()
       }.sortedWith(appComparator)
   }
   ```

---

## 5. Blueprint — `AppsScreen.kt` (All 14 UI/UX/Logic Fixes)

Target file: `/home/antigravity/terenzy/Silo/app/app/src/main/java/dev/silo/app/ui/screens/AppsScreen.kt`

### Pure Helpers to Add (Unit-Testable on JVM)
1. `formatTempUnblockMinutes(remainingSecs: Long): Long = ((remainingSecs + 59L) / 60L).coerceAtLeast(1L)` (`APP-L01`).
2. `partitionPermCategories(...)` (`APP-H10`):
   ```kotlin
   fun partitionPermCategories(
       allCategories: List<String>,
       declaredCategories: Set<String>,
       grantedCategories: Set<String>,
       guard: AppPermGuard,
       liveByCat: Map<String, CategoryPermLiveState>,
   ): Pair<List<String>, List<String>> {
       val primary = mutableListOf<String>()
       val undeclared = mutableListOf<String>()
       for (cat in allCategories) {
           val live = liveByCat[cat]
           val mode = live?.appopsMode?.trim()?.lowercase() ?: "default"
           val hasNonDefaultAppOp = mode != "default" && mode != "allow"
           val hasGuard = guard.autoRevokeOnBg.contains(cat) || guard.lockedDeny.contains(cat)
           val showPrimary = APPOPS_ONLY_CATEGORIES.contains(cat) ||
               declaredCategories.contains(cat) ||
               grantedCategories.contains(cat) ||
               hasGuard ||
               hasNonDefaultAppOp ||
               live?.runningNow == true
           if (showPrimary) primary.add(cat) else undeclared.add(cat)
       }
       return primary to undeclared
   }
   ```

### Exact Line-by-Line Changes in `AppsScreen.kt`

| Item / Defect | Current Lines | Concrete Implementation Change |
|---|---|---|
| **`AUDITABLE_CATEGORIES`** (`APP-H08`) | `52–63` | Remove `"background"` (now AppOps-only, never in `grantedSensitiveCategories`), keeping `"all"` + the 8 runtime-permission categories (`camera`, `microphone`, `location`, `background_location`, `contacts`, `media`, `sms_calllog`, `sensors`). |
| **`rememberSaveable`** (`APP-M17`) | `72–75` | Replace `remember` with `rememberSaveable` for `query`, `filter`, `auditCategory`, `selectedAppPkg`, plus `var selectedSheetInitialTab by rememberSaveable { mutableStateOf(AppSheetTab.NETWORK) }`. Add `import androidx.compose.runtime.saveable.rememberSaveable`. |
| **Show non-launcher user apps** (`APP-H09`) | `90` | Change `AppListFilter.USER -> !app.isSystem && app.hasLauncher` to `AppListFilter.USER -> !app.isSystem`. In `MinimalAppRow` (line `409`), show `MiniTag(s.bgAppBadge, SiloAmber)` when `!app.isSystem && !app.hasLauncher`. |
| **`RESTRICTED` filter** (`APP-L02`) | `94` | Change to `val hasNetBlock = r != null && (!r.allowWifi || !r.allowCellular || !r.allowVpn || !r.allowScreenOff || r.blockCleartextHttp || r.blockLocalhost)`. |
| **`ModalBottomSheet` config** (`APP-H06`) | `116–129` | Pass `sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)` and `initialTab = selectedSheetInitialTab` to `AppControlSheet`. |
| **Master firewall OFF hint** (`APP-L04`) | `175` & `661` | When `!fw.enabled && filter != AppListFilter.AUDIT` (in `AppsScreen`) and when `!fw.enabled` (top of `AppSheetTab.NETWORK`), render a compact amber/surface hint banner `s.firewallOffRuleHint`. |
| **Audit category chips & Bulk Revoke** (`APP-H06`, `APP-H04`) | `195–227` | Replace raw `Surface(clickable)` on category chips with `Surface(onClick = { auditCategory = cat }, modifier = Modifier.defaultMinSize(minHeight = 40.dp))`. On `bulkRevokeCategory` button, disable & show spinner while `state.pendingPermKeys.contains("bulk:$auditCategory")` and use `defaultMinSize(minHeight = 42.dp)`. |
| **Open `PERMS` tab from `AUDIT`** (`APP-L04`) | `249`, `280`, `542` | In `AuditAppRow` `onOpen`: set `selectedSheetInitialTab = AppSheetTab.PERMS; selectedAppPkg = app.packageName`. In `MinimalAppRow` `onOpen`: set `selectedSheetInitialTab = AppSheetTab.NETWORK; selectedAppPkg = app.packageName`. In `AppControlSheet`: `var tab by rememberSaveable(app.packageName, initialTab) { mutableStateOf(initialTab) }`. |
| **Atomic freeze** (`APP-M02`) | `293–303`, `823–832` | Replace split `setFreezerEntry` + `freezeUidNow` calls with `vm.ensureEntryAndFreeze(app.uid, entry)`. |
| **`AuditAppRow` `Cabut` target + spinner** (`APP-H06`, `APP-H04`) | `354–365` | Replace `Modifier.height(32.dp)` with `Modifier.defaultMinSize(minHeight = 40.dp).minimumInteractiveComponentSize()`, `contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)`, and pass `isPending = state.pendingPermKeys.any { it.startsWith("${app.packageName}:") }` to disable & show a `14.dp` spinner while revoking. |
| **`G:N` beside `BEKU`/`SUSPEND`** (`APP-L03`) | `409–415` | Separate `if (guardCount > 0) MiniTag("G:$guardCount", SiloEmerald)` out of the `else if` branch so it renders alongside `BEKU`/`SUSPEND` and `BG`. |
| **Temp unblock rounding** (`APP-L01`) | `421`, `711` | Replace `(secs / 60) + 1` with `formatTempUnblockMinutes(secs)`. |
| **`InlineIconToggle` touch target & a11y** (`APP-H06`) | `500–518` | Add `contentDescription: String` parameter (`s.a11yToggleWifi` / `s.a11yToggleCellular` / `s.a11yToggleFreeze`); replace raw `Surface(Modifier.size(34.dp).clip(CircleShape).clickable)` with Material `Surface(onClick = onClick, shape = CircleShape, modifier = Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 40.dp).minimumInteractiveComponentSize(), color = bg)` with inner `Box(Modifier.size(38.dp), contentAlignment = Alignment.Center)`. |
| **Active inspected pkg + inline feedback banner** (`APP-C06`, `APP-H11`) | `544–593` | Replace `LaunchedEffect(app.packageName)` with `DisposableEffect(app.packageName) { vm.setActiveInspectedPackage(app.packageName); vm.inspectAppPermissions(app.packageName); onDispose { vm.setActiveInspectedPackage(null) } }`. Capture `state.snackbarMessage` + `state.snackbarIsError` keyed on `state.snackbarSeq` into sheet-local `sheetBanner` and render a visible, dismissible inline banner right below the sheet header (crimson for errors, emerald for confirmations, `Close` button calling `sheetBanner = null; vm.clearSnackbar()`). |
| **Revoke All Sensitive spinner** (`APP-H04`) | `635–645` | Check `val revokingAll = state.pendingPermKeys.contains("${app.packageName}:*:revoke_all")`; disable button and show `CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)` while running. |
| **`Izin & AppOps` loading, error+Retry, legacy scope banner, declared/undeclared split** (`APP-H04`, `APP-H10`, `APP-H12`, `APP-M16`, `APP-M18`) | `742–786` | 1. Render `s.permsHonestNote` and updated `s.enforcerHint`. <br>2. If `(state.config.scopes.apps[app.packageName]?.activeCount() ?: 0) > 0`, render legacy scope card (`s.legacyScopeTitle`, `s.legacyScopeDesc`) with button `s.clearLegacyScope` -> `vm.clearLegacyAppScope(app.packageName)`. <br>3. If `state.loadingAppPerms.contains(app.packageName) && livePerms == null`: render loading spinner + `s.loadingAppPermsLabel` (NEVER flash green `Normal`!). <br>4. Else if `state.appPermsErrors[app.packageName] != null && livePerms == null`: render crimson error card with message + `s.retryAction` button calling `vm.inspectAppPermissions(app.packageName)`. <br>5. Else: if `appPermsErrors[pkg] != null`, show compact warning+Retry strip above cards. Partition `ALL_PERM_CATEGORIES` via `partitionPermCategories(...)`: render `primaryCats` inside `SiloGroup`, and if `undeclaredCats.isNotEmpty()`, render an expandable toggle (`var showUndeclared by rememberSaveable(app.packageName) { mutableStateOf(false) }`) showing `"${s.undeclaredPermsSection} (${undeclaredCats.size})"` that expands to show undeclared categories with `isUndeclared = true`. |
| **`PermissionCategoryControlRow` state machine, hide unsupported `Saat Dibuka`, `systemFixed`, per-button spinners** (`APP-C01`, `APP-C02`, `APP-H06`, `APP-H07`, `APP-M19`) | `845–943` | Compute `uiMode = resolvePermUiMode(cat, catState, runtimeGranted)` and `supportsFg = resolveSupportsForeground(cat, catState)`. Compute `pendingAction` / `rowBusy` from `state.pendingPermKeys`. <br>- Show `MiniTag(statusLabel, statusColor)` + optional `MiniTag(s.permSystemFixedBadge, SiloAmber)` when `catState?.systemFixed == true`. <br>- Show `s.permSystemFixedNote` if `systemFixed`; show `s.undeclaredPermHint` if `isUndeclared`; show `catState?.effectiveLastAccess` when non-blank. <br>- Action pills row: render `Normal` (`active = uiMode.isNormalPillActive`), conditionally render `Saat Dibuka` ONLY when `supportsFg || uiMode == PermEffectiveUiMode.FOREGROUND` (`active = uiMode.isForegroundPillActive`), render `Palsukan` (`active = uiMode.isFakeEmptyPillActive`), render `Cabut` (`active = uiMode.isRevokedPillActive`). Disable all pills while `rowBusy`, show spinner inside the active pending action pill. <br>- Enforcer pills row: `Auto-Cabut` (`s.enforcerAutoRevoke`) and `Kunci Blokir` (`s.enforcerStrictLock`) with `loading` spinner for `"$pkg:$cat:guard_auto"` / `"$pkg:$cat:guard_lock"`. |
| **`SmallActionPill` & `EnforcerPill` touch targets** (`APP-H06`) | `945–1005` | Replace raw `Surface(modifier.clickable)` with Material 3 interactive `Surface(onClick = onClick, enabled = enabled && !loading, modifier = modifier.defaultMinSize(minHeight = 40.dp).minimumInteractiveComponentSize(), ...)`. Inner container uses `defaultMinSize(minHeight = 40.dp)` and `padding(vertical = 8.dp, horizontal = 4.dp)`. Support inline `CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)` when `loading == true`. |

---

## 6. Blueprint — `SiloStrings.kt` Complete Bilingual Specification (`APP-M15` + Workstreams 1, 2, 3)

Target file: `/home/antigravity/terenzy/Silo/app/app/src/main/java/dev/silo/app/ui/i18n/SiloStrings.kt`

### A. Updated Existing Properties in `StringsId` & `StringsEn`

| Property | `StringsId` (Indonesian) | `StringsEn` (English) |
|---|---|---|
| `enforcerAutoRevoke` | `"Auto-Cabut"` | `"Auto-Revoke"` |
| `enforcerHint` | `"Auto-Cabut: izin dicabut otomatis saat app ditutup/layar mati. Kunci Blokir: kunci izin (user-fixed) & blokir ulang cepat (<2d). Catatan Android: mencabut izin runtime saat app berjalan akan memaksa OS me-restart app — gunakan Palsukan (AppOps) untuk blokir senyap tanpa menutup app."` | `"Auto-Revoke: revokes automatically when backgrounded/screen off. Lock Blocked: locks permission (user-fixed) & re-enforces fast (<2s). Android note: revoking a runtime permission while an app is running causes Android to restart its process — use Fake Empty (AppOps) to block silently without closing the app."` |

### B. New `val` Properties on `SiloStrings` (Add to Data Class, `StringsId`, and `StringsEn`)

| Workstream | Property | `StringsId` | `StringsEn` |
|---|---|---|---|
| W1 (`AppsScreen`) | `bgAppBadge` | `"BG"` | `"BG"` |
| W1 (`AppsScreen`) | `permSystemFixedBadge` | `"SISTEM TETAP"` | `"SYSTEM FIXED"` |
| W1 (`AppsScreen`) | `permSystemFixedNote` | `"Dikunci OS (SYSTEM_FIXED) — Android melarang pm revoke; gunakan Palsukan (AppOps)."` | `"OS-locked (SYSTEM_FIXED) — Android blocks pm revoke; use Fake Empty (AppOps)."` |
| W1 (`AppsScreen`) | `loadingAppPermsLabel` | `"Memuat status izin real-time..."` | `"Loading live permission state..."` |
| W1 (`AppsScreen`) | `retryAction` | `"Coba Lagi"` | `"Retry"` |
| W1 (`AppsScreen`) | `permsLoadFailed` | `"Gagal membaca status izin dari daemon"` | `"Failed to read live permissions from daemon"` |
| W1 (`AppsScreen`) | `undeclaredPermsSection` | `"Izin Lain (Tidak Dideklarasikan)"` | `"Other Permissions (Undeclared)"` |
| W1 (`AppsScreen`) | `undeclaredPermHint` | `"Tidak tercantum di manifest APK"` | `"Not declared in APK manifest"` |
| W1 (`AppsScreen`) | `legacyScopeTitle` | `"Aturan Isolasi Lama (v2) Aktif"` | `"Legacy Isolation Scope (v2) Active"` |
| W1 (`AppsScreen`) | `legacyScopeDesc` | `"Aplikasi ini masih memiliki aturan scope v2 lama. Bersihkan agar dikelola penuh lewat Izin & AppOps v3."` | `"This app still has a legacy v2 scope rule. Clear it to manage permissions cleanly via v3."` |
| W1 (`AppsScreen`) | `clearLegacyScope` | `"Bersihkan Aturan Lama"` | `"Clear Legacy Rule"` |
| W1 (`AppsScreen`) | `firewallOffRuleHint` | `"Firewall utama nonaktif — aturan disimpan & aktif saat Firewall dinyalakan."` | `"Master firewall is off — rules are saved & apply when Firewall is enabled."` |
| W1 (`AppsScreen`) | `vpnAppOverrideHint` | `"Aplikasi VPN dibebaskan dari DNS leak guard & filter antarmuka tertentu."` | `"VPN provider apps are exempted from DNS leak guard & certain interface filters."` |
| W1 (`AppsScreen`) | `dismiss` | `"Tutup"` | `"Dismiss"` |
| W1 (`AppsScreen`) | `a11yToggleWifi` | `"Ubah akses Wi-Fi"` | `"Toggle Wi-Fi access"` |
| W1 (`AppsScreen`) | `a11yToggleCellular` | `"Ubah akses seluler"` | `"Toggle cellular access"` |
| W1 (`AppsScreen`) | `a11yToggleFreeze` | `"Bekukan atau cairkan aplikasi"` | `"Freeze or thaw app"` |
| W1/W2 (`Global`) | `a11yRefresh` | `"Muat ulang"` | `"Refresh"` |
| W2 (`TrafficScreen`) | `loadingAccessLog` | `"Memindai log akses izin..."` | `"Scanning permission access log..."` |
| W2 (`TrafficScreen`) | `accessLogLoadFailed` | `"Gagal memuat log akses izin"` | `"Failed to load permission access log"` |
| W2 (`TrafficScreen`) | `protectedBadge` | `"TERLINDUNGI"` | `"PROTECTED"` |
| W3 (`HomeScreen`) | `presetCustom` | `"Kustom"` | `"Custom"` |
| W3 (`HomeScreen`) | `panicLockdownActiveBanner` | `"PANIC LOCKDOWN AKTIF — Semua koneksi aplikasi diblokir total"` | `"PANIC LOCKDOWN ACTIVE — All app traffic is hard-blocked"` |
| W3 (`SystemScreen`) | `rollbackConfirmTitle` | `"Kembalikan Sysctl ke Default?"` | `"Rollback Sysctls to Kernel Defaults?"` |
| W3 (`SystemScreen`) | `rollbackConfirmBody` | `"Pengerasan kernel akan dimatikan dan seluruh parameter sysctl dikembalikan ke nilai awal sebelum Silo aktif."` | `"Kernel hardening will be disabled and all modified sysctl parameters will be restored to their original values."` |
| W3 (`SystemScreen`) | `emergencyFlushConfirmTitle` | `"Jalankan Flush Darurat Firewall?"` | `"Run Emergency Firewall Flush?"` |
| W3 (`SystemScreen`) | `emergencyFlushConfirmBody` | `"Semua rantai SILO_* di iptables/ip6tables akan dicabut dan firewall dimatikan seketika."` | `"All SILO_* chains in iptables/ip6tables will be detached and the firewall disabled immediately."` |
| W3 (`SystemScreen`) | `confirmAction` | `"Lanjutkan"` | `"Confirm"` |
| W3 (`SystemScreen`) | `customDnsHostHint` | `"Hostname DoT (mis. dns.quad9.net)"` | `"DoT Hostname (e.g. dns.quad9.net)"` |
| W3 (`SystemScreen`) | `blocklistEmptyHint` | `"Belum ada IP/CIDR dalam daftar blokir."` | `"No IP/CIDR entries in the blocklist yet."` |
| W3 (`SystemScreen`) | `freezerGraceLabel` | `"Jeda Beku Latar"` | `"Background Freeze Grace"` |
| W3 (`SystemScreen`) | `loadingAudit` | `"Menjalankan audit keamanan..."` | `"Running security audit..."` |
| W3 (`tiles/`) | `tileOffline` | `"Offline"` | `"Offline"` |
| W3 (`tiles/`) | `tileLockdownDeny` | `"Blokir Default"` | `"Default-Deny"` |
| W3 (`tiles/`) | `tileLockdownAllow` | `"Izinkan Default"` | `"Default-Allow"` |
| W3 (`tiles/`) | `tileOff` | `"Mati"` | `"Off"` |
| W3 (`tiles/`) | `tileSensorsMuted` | `"Mic/Cam Mati"` | `"Mic/Cam Muted"` |
| W3 (`tiles/`) | `tileSensorsLive` | `"Aktif"` | `"Active"` |
| W3 (`tiles/`) | `tilePanicTap2x` | `"Ketuk 2x -> BFU"` | `"Tap 2x -> BFU"` |
| W3 (`tiles/`) | `tilePanicTapAgain` | `"Ketuk lagi (5d)"` | `"Tap again (5s)"` |
| W3 (`tiles/`) | `tilePanicRebooting` | `"Reboot..."` | `"Rebooting..."` |
| W3 (`tiles/`) | `tilePanicFailed` | `"Gagal (Offline)"` | `"Failed (Offline)"` |

### C. Bilingual Toast Helper Methods Inside `SiloStrings` Body (`APP-M15`)
Matches existing `categoryLabel(id)` / `categoryDesc(id)` pattern in `SiloStrings.kt:254-312` so `SiloViewModel` calls `private fun strings() = resolveStrings(_ui.value.languagePref)` and NEVER hardcodes Indonesian or English toasts:

```kotlin
fun actionLabel(action: String): String = when (action.trim().lowercase()) {
    "allow", "default" -> permActionNormal
    "foreground" -> permActionFg
    "ignore" -> permActionIgnore
    "revoke", "deny" -> permActionRevoke
    else -> action
}

fun toastPresetApplied(presetName: String) = if (isIndonesian) "Preset $presetName diterapkan" else "Preset $presetName applied"
fun toastPresetFailed(err: String) = if (isIndonesian) "Gagal menerapkan preset: $err" else "Failed to apply preset: $err"
fun toastFirewallError(err: String) = if (isIndonesian) "Kesalahan firewall: $err" else "Firewall error: $err"
fun toastRuleError(err: String) = if (isIndonesian) "Kesalahan aturan: $err" else "Rule error: $err"
fun toastResetError(err: String) = if (isIndonesian) "Gagal mereset aturan: $err" else "Reset failed: $err"
fun toastTempUnblockGranted(mins: Int) = if (isIndonesian) "Akses internet sementara diberikan (${mins}m)" else "Temporary internet granted (${mins}m)"
fun toastTempUnblockFailed(err: String) = if (isIndonesian) "Gagal memberi akses sementara: $err" else "Temp unblock failed: $err"
fun toastTempUnblockRevoked() = if (isIndonesian) "Akses sementara dicabut & koneksi TCP diputus" else "Temporary access revoked & TCP cut"
fun toastRevokeFailed(err: String) = if (isIndonesian) "Gagal mencabut: $err" else "Revoke failed: $err"
fun toastFileUnreadable() = if (isIndonesian) "File tidak dapat dibaca" else "Unable to read file"
fun toastFileTooLarge() = if (isIndonesian) "File terlalu besar untuk diimpor" else "File is too large to import"
fun toastNoValidCidr() = if (isIndonesian) "Tidak ditemukan IP/CIDR valid di file" else "No valid IP/CIDR entries found in file"
fun toastBlocklistLoaded(loaded: Int) = if (isIndonesian) "Memuat $loaded IP/CIDR ke kernel" else "Loaded $loaded IP/CIDR entries into kernel"
fun toastBlocklistImportFailed(err: String) = if (isIndonesian) "Gagal impor daftar blokir: $err" else "Blocklist import failed: $err"
fun toastCidrAdded(cidr: String) = if (isIndonesian) "Ditambahkan: $cidr" else "Added: $cidr"
fun toastCidrRemoved(cidr: String) = if (isIndonesian) "Dihapus: $cidr" else "Removed: $cidr"
fun toastInvalidCidr(detail: String) = if (isIndonesian) "IP/CIDR tidak valid: $detail" else "Invalid IP/CIDR: $detail"
fun toastBlocklistCleared() = if (isIndonesian) "Daftar blokir IP dikosongkan" else "IP blocklist cleared"
fun toastFlushSuccess() = if (isIndonesian) "Flush darurat berhasil: semua aturan SILO_* dicabut" else "Emergency flush complete: all SILO_* rules removed"
fun toastFlushFailed(err: String) = if (isIndonesian) "Flush gagal: $err" else "Flush failed: $err"
fun toastKilledTcp(count: Int) = if (isIndonesian) "Memutus $count koneksi TCP aktif" else "Severed $count active TCP connections"
fun toastSockDestroyError(err: String) = if (isIndonesian) "Gagal memutus TCP: $err" else "SOCK_DESTROY error: $err"
fun toastKilledSocket(count: Int) = if (isIndonesian) "Diputus: $count soket" else "Killed: $count socket(s)"
fun toastKillSocketFailed(err: String) = if (isIndonesian) "Gagal memutus soket: $err" else "Failed to kill socket: $err"
fun toastAppBlocked(label: String) = if (isIndonesian) "Jaringan $label diblokir" else "Blocked network for $label"
fun toastTelemetryError(err: String) = if (isIndonesian) "Gagal menghapus log: $err" else "Failed to clear logs: $err"
fun toastFreezerError(err: String) = if (isIndonesian) "Kesalahan pembeku: $err" else "Freezer error: $err"
fun toastAppFrozen() = if (isIndonesian) "Aplikasi dibekukan" else "App frozen"
fun toastFreezeFailed(err: String) = if (isIndonesian) "Gagal membekukan: $err" else "Failed to freeze: $err"
fun toastAppThawed() = if (isIndonesian) "Aplikasi dicairkan" else "App thawed"
fun toastThawFailed(err: String) = if (isIndonesian) "Gagal mencairkan: $err" else "Failed to thaw: $err"
fun toastThawAllDone() = if (isIndonesian) "Semua aplikasi dicairkan" else "All apps thawed"
fun toastPermActionApplied(cat: String, action: String) =
    if (isIndonesian) "Izin ${categoryLabel(cat)} diatur ke ${actionLabel(action)}"
    else "${categoryLabel(cat)} set to ${actionLabel(action)}"
fun toastPermError(err: String) = if (isIndonesian) "Kesalahan izin: $err" else "Permission error: $err"
fun toastGuardUpdated() = if (isIndonesian) "Penjaga izin diperbarui" else "Permission guard updated"
fun toastGuardError(err: String) = if (isIndonesian) "Kesalahan penjaga izin: $err" else "Guard error: $err"
fun toastRevokedAllForApp(count: Int, appLabel: String) =
    if (isIndonesian) "Mencabut $count kategori izin sensitif pada $appLabel"
    else "Revoked $count sensitive permission categories for $appLabel"
fun toastBulkRevoked(cat: String, count: Int) =
    if (isIndonesian) "Berhasil mencabut izin ${categoryLabel(cat)} pada $count aplikasi"
    else "Revoked ${categoryLabel(cat)} permission on $count apps"
fun toastBulkRevokeFailed(err: String) = if (isIndonesian) "Cabut massal gagal: $err" else "Bulk revoke failed: $err"
fun toastLegacyScopeCleared() = if (isIndonesian) "Aturan isolasi lama dibersihkan" else "Legacy isolation rule cleared"
fun toastClipboardCleared() = if (isIndonesian) "Clipboard dibersihkan" else "Clipboard cleared"
fun toastClipboardClearFailed(err: String) = if (isIndonesian) "Gagal membersihkan clipboard: $err" else "Failed to clear clipboard: $err"
fun toastHardeningError(err: String) = if (isIndonesian) "Kesalahan hardening: $err" else "Hardening error: $err"
fun toastHardeningRolledBack() = if (isIndonesian) "Seluruh sysctl dikembalikan ke default kernel" else "All sysctls restored to kernel defaults"
fun toastAutomationError(err: String) = if (isIndonesian) "Kesalahan otomasi: $err" else "Automation error: $err"
fun toastSensorError(err: String) = if (isIndonesian) "Kesalahan privasi sensor: $err" else "Sensor privacy error: $err"
fun toastPrivateDnsSet(label: String) = "Private DNS: $label"
fun toastPrivateDnsError(err: String) = if (isIndonesian) "Kesalahan Private DNS: $err" else "Private DNS error: $err"
fun toastRadioError(err: String) = if (isIndonesian) "Kesalahan privasi radio: $err" else "Radio privacy error: $err"
fun toastPanicLockdownActive(killed: Int) =
    if (isIndonesian) "Panic Lockdown aktif: $killed TCP diputus, semua aplikasi diblokir, Mic/Cam & Radio dimatikan"
    else "Panic Lockdown active: $killed TCP cut, all apps blocked, Mic/Cam & Radios off"
fun toastPanicLockdownError(err: String) = if (isIndonesian) "Kesalahan Panic Lockdown: $err" else "Panic Lockdown error: $err"
fun toastPanicRebootFailed(err: String) = if (isIndonesian) "Panic reboot gagal: $err" else "Panic reboot failed: $err"
fun toastAuditScore(score: Int) = if (isIndonesian) "Skor Keamanan: $score/100" else "Security Score: $score/100"
fun toastAuditFailed(err: String) = if (isIndonesian) "Audit gagal: $err" else "Audit failed: $err"
fun toastLiveProofFailed(err: String) = if (isIndonesian) "Uji Bukti Nyata gagal: $err" else "Live proof failed: $err"
fun toastDisguiseToggled(enabled: Boolean) = when {
    enabled && isIndonesian -> "Samaran NetDiag aktif"
    enabled -> "NetDiag disguise enabled"
    isIndonesian -> "Ikon Silo dikembalikan"
    else -> "Silo icon restored"
}
fun toastExportSuccess() = if (isIndonesian) "Konfigurasi diekspor" else "Configuration exported"
fun toastExportWriteFailed() = if (isIndonesian) "Gagal menulis file cadangan" else "Failed to write backup file"
fun toastExportFailed(err: String) = if (isIndonesian) "Ekspor gagal: $err" else "Export failed: $err"
fun toastImportEmpty() = if (isIndonesian) "File cadangan kosong atau tidak terbaca" else "Backup file is empty or unreadable"
fun toastImportSuccess() = if (isIndonesian) "Konfigurasi dipulihkan" else "Configuration restored"
fun toastImportFailed(err: String) = if (isIndonesian) "Impor gagal: $err" else "Import failed: $err"
```

---

## 7. JVM Unit Test Specification (`ProtocolAndParserTest.kt` / New `PermissionStateMachineTest.kt`)

Target directory: `/home/antigravity/terenzy/Silo/app/app/src/test/java/dev/silo/app/`
Run command: `cd /home/antigravity/terenzy/Silo/app && ./gradlew testDebugUnitTest --no-daemon`

Add the following concrete JVM tests:

1. **`permUiModeScreenshotKameraRegressionNeverLightsNormal`**:
   - Assert `resolvePermUiMode(hasRuntimePerms = true, runtimeGranted = false, appopsMode = "default") == PermEffectiveUiMode.UNGRANTED`
   - Assert `resolvePermUiMode(hasRuntimePerms = true, runtimeGranted = false, appopsMode = "allow") == PermEffectiveUiMode.UNGRANTED`
   - Assert `UNGRANTED.isNormalPillActive == false`, `isForegroundPillActive == false`, `isFakeEmptyPillActive == false`, `isRevokedPillActive == false`.
2. **`permUiModeScreenshotLokasiRegressionLightsOnlyRevoke`**:
   - Assert `resolvePermUiMode(hasRuntimePerms = true, runtimeGranted = false, appopsMode = "ignore") == PermEffectiveUiMode.REVOKED`
   - Assert `REVOKED.isRevokedPillActive == true` AND `REVOKED.isFakeEmptyPillActive == false` AND `REVOKED.isNormalPillActive == false` AND `REVOKED.isForegroundPillActive == false`.
3. **`permUiModeExhaustiveMutualExclusivityInvariant`**:
   - Iterate all combinations of `hasRuntime in [true, false]`, `granted in [true, false]`, `mode in ["default", "allow", "foreground", "ignore", "deny", "", "UNKNOWN", " IGNORE "]`:
     - Verify `listOf(m.isNormalPillActive, m.isForegroundPillActive, m.isFakeEmptyPillActive, m.isRevokedPillActive).count { it } <= 1`.
     - Verify whenever `hasRuntime && !granted`, `m` is strictly `REVOKED` or `UNGRANTED` (NEVER `NORMAL`, `FOREGROUND`, `FAKE_EMPTY`).
   - Explicitly assert:
     - `(true, true, "default") -> NORMAL`, `(true, true, "allow") -> NORMAL`
     - `(true, true, "foreground") -> FOREGROUND`
     - `(true, true, "ignore") -> FAKE_EMPTY`
     - `(true, true, "deny") -> REVOKED`
     - `(true, false, "deny") -> REVOKED`, `(true, false, "foreground") -> UNGRANTED`
     - `(false, false, "default") -> NORMAL`, `(false, false, "allow") -> NORMAL`
     - `(false, false, "ignore") -> FAKE_EMPTY`, `(false, false, "deny") -> REVOKED`, `(false, false, "foreground") -> FOREGROUND`.
4. **`permUiModeLiveStateFallbackAndSupportsForeground`**:
   - Live `CategoryPermLiveState(category = "camera", appopsMode = "ignore", runtimeGranted = false)` overrides `localRuntimeGranted = true` -> `REVOKED`.
   - Live `CategoryPermLiveState(category = "camera", appopsMode = "ignore", runtimeGranted = null)` falls back to `localRuntimeGranted = true` -> `FAKE_EMPTY`.
   - Legacy daemon omitting `has_runtime_perms`/`supports_foreground` for `"clipboard"` and `"background"` still resolves `resolveHasRuntimePerms == false` and `resolveSupportsForeground == false`.
   - Verify exact `resolveSupportsForeground` set: `true` for `camera`, `microphone`, `location`, `sensors`; `false` for `background_location`, `contacts`, `media`, `sms_calllog`, `clipboard`, `background`.
5. **`partitionPermCategoriesSurfacesDeclaredAppOpsAndActiveGuards`**:
   - App declaring only `setOf("camera")` with empty guard and default live state -> `primary == listOf("camera", "clipboard", "background")`, `undeclared.size == 7`.
   - Undeclared `"location"` with `lockedDeny = listOf("location")` OR undeclared `"contacts"` with `appopsMode = "ignore"` -> promoted into `primary`.
6. **`tempUnblockMinutesRoundingMatchesBoundaryCases`**:
   - Assert `formatTempUnblockMinutes(0) == 1L`, `(1) == 1L`, `(59) == 1L`, `(60) == 1L`, `(61) == 2L`, `(900) == 15L`, `(901) == 16L`.
7. **`appInventoryExcludesSpecialAppOpsFromGrantedAndCoversAndroid14Perms`**:
   - Passing `SYSTEM_ALERT_WINDOW` and `MANAGE_EXTERNAL_STORAGE` in BOTH `declaredPermissions` and `grantedPermissions`:
     - `declaredCategories` contains `"background"` and `"media"`.
     - `grantedCategories` is EMPTY (`!contains("background") && !contains("media")`), `level == PrivacyRiskLevel.LOW`.
   - Passing `READ_MEDIA_VISUAL_USER_SELECTED`, `SEND_SMS`, `RECEIVE_MMS`, `BODY_SENSORS_BACKGROUND` in `declaredPermissions` & `grantedPermissions`:
     - `grantedCategories` contains `"media"`, `"sms_calllog"`, `"sensors"`.
   - Passing Activity + ContentProvider tracker component names (`com.facebook.ads.AudienceNetworkActivity`, `com.facebook.ads.AudienceNetworkContentProvider`, `com.google.android.gms.ads.MobileAdsInitProvider`):
     - `detectedTrackers` contains `"Meta Ads"` and `"Google AdMob"`.
   - Verify `AppInventory.isProtectedPackage("dev.silo.app") == true`, `isProtectedPackage("com.samsung.android.honeyboard") == true`, `isProtectedPackage("com.example.userapp") == false`, `isProtectedPackage(null) == false`.
8. **`appPermGuardMutualExclusivityAndDistinctTotalCount`**:
   - `AppPermGuard(autoRevokeOnBg = listOf("camera", "location"), lockedDeny = listOf("location")).totalCount() == 2`.
   - `.normalized()` strips `"location"` from `autoRevokeOnBg` (`lockedDeny` takes precedence).
   - `withAutoRevoke("camera", true)` removes `"camera"` from `lockedDeny`; `withStrictLock("camera", true)` removes `"camera"` from `autoRevokeOnBg`.
9. **`siloModelsV3AdditiveFieldsSerializeAndDeserializeCompatibly`**:
   - Old daemon JSON without `runtime_granted`/`has_runtime_perms`/`supports_foreground`/`system_fixed`/`panic_lockdown_active` decodes cleanly via `SiloJson` with safe defaults (`runtimeGranted == null`, `hasRuntimePerms == true`, `supportsForeground == true`, `systemFixed == false`, `panicLockdownActive == false`).
   - Full v3 JSON with all additive fields + unknown extra field decodes cleanly; both `"last_access"` and `"last_access_ago"` populate `effectiveLastAccess`.
   - `FirewallConfig(panicLockdownActive = true)` and `DaemonStatus(panicLockdownActive = true)` encode `"panic_lockdown_active":true` and round-trip identically through `SiloJson`.
10. **`bilingualStringsCoverNewPermissionAndToastCopy`**:
    - Verify `StringsId.enforcerAutoRevoke == "Auto-Cabut"` and `StringsEn.enforcerAutoRevoke == "Auto-Revoke"` (short, non-truncating).
    - Verify distinct non-blank ID vs EN outputs for `permSystemFixedNote`, `enforcerHint`, `toastPermActionApplied("camera", "revoke")`, `toastPanicLockdownActive(3)`, `emergencyFlushConfirmTitle`, `rollbackConfirmTitle`, and `tilePanicTapAgain`.

---

## 8. Caveats & Verification Checklist
- **Strictly read-only exploration**: Zero source, test, build, or config files in the repository were modified.
- **Verification after Workstream 1 implementation**:
  1. `cd /home/antigravity/terenzy/Silo/app && ./gradlew testDebugUnitTest --no-daemon` (all new + existing JVM unit tests pass — confirms lazy `LruCache` does not trigger Android stub crash).
  2. `cd /home/antigravity/terenzy/Silo/app && ./gradlew assembleRelease --no-daemon` (confirms R8/ProGuard + Compose Material 3 compilation).
  3. `/opt/android-sdk/build-tools/35.0.0/aapt2 dump permissions /home/antigravity/terenzy/Silo/app/app/build/outputs/apk/release/app-release.apk` (must NOT contain `android.permission.INTERNET`).
