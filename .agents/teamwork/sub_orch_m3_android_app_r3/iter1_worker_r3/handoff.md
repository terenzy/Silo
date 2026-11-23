# Handoff Report — M3 (`android-app`, Round 3 Resumption) Worker (`iter1_worker_r3`)

**Milestone:** M3 (`android-app`) — Full remediation of all 53 `APP-*` defects (`APP-C01..C06`, `APP-H01..H20`, `APP-M01..M19`, `APP-L01..L08`), screenshot regressions (`Kamera` & `Lokasi`), and `PROJECT.md § Interface Contracts § 1–3`  
**Status:** **COMPLETE & VERIFIED** (all canonical builds, unit tests, lint/R8, and `aapt2` security gates passed strictly after final edit)  
**Exclusive Ownership Respected:** Modifications strictly limited to `app/**` (17 modified files + 4 new JVM unit test files; zero edits to `daemon/`, `module/`, `scripts/`, `.github/`, or `tests/`).

---

## 1. Exact List of Modified & Added Files (`app/**`)

### Modified Source, Manifest & ProGuard Files (17)
1. `app/app/proguard-rules.pro`
2. `app/app/src/main/AndroidManifest.xml`
3. `app/app/src/main/java/dev/silo/app/MainActivity.kt`
4. `app/app/src/main/java/dev/silo/app/data/AppInventory.kt`
5. `app/app/src/main/java/dev/silo/app/data/SiloRepository.kt`
6. `app/app/src/main/java/dev/silo/app/ipc/SiloClient.kt`
7. `app/app/src/main/java/dev/silo/app/ipc/SiloModels.kt`
8. `app/app/src/main/java/dev/silo/app/tiles/LockdownTileService.kt`
9. `app/app/src/main/java/dev/silo/app/tiles/PanicRebootTileService.kt`
10. `app/app/src/main/java/dev/silo/app/tiles/SensorsOffTileService.kt`
11. `app/app/src/main/java/dev/silo/app/ui/SiloViewModel.kt`
12. `app/app/src/main/java/dev/silo/app/ui/i18n/SiloStrings.kt` (refactored in R2 to `@Immutable interface SiloStrings` + `object StringsId` / `object StringsEn` to avoid the JVM 255-parameter constructor cliff)
13. `app/app/src/main/java/dev/silo/app/ui/screens/AppsScreen.kt`
14. `app/app/src/main/java/dev/silo/app/ui/screens/HomeScreen.kt`
15. `app/app/src/main/java/dev/silo/app/ui/screens/SystemScreen.kt`
16. `app/app/src/main/java/dev/silo/app/ui/screens/TrafficScreen.kt`
17. `app/app/src/main/java/dev/silo/app/ui/theme/Theme.kt`

### Added JVM Unit Test Suites (4 new + 1 existing preserved)
1. `app/app/src/test/java/dev/silo/app/BlocklistParserTest.kt` (new, 4 tests)
2. `app/app/src/test/java/dev/silo/app/PermissionStateMachineTest.kt` (new, 10 tests)
3. `app/app/src/test/java/dev/silo/app/SiloClientProtocolTest.kt` (new, expanded in R3 to 9 tests)
4. `app/app/src/test/java/dev/silo/app/SystemAndHomeLogicTest.kt` (new, 8 tests)
5. `app/app/src/test/java/dev/silo/app/ProtocolAndParserTest.kt` (existing, 5 tests — all passing)

---

## 2. Additional Critical Fixes Made During Round 3 Resumption

During full line-by-line inspection and live test execution in R3, we uncovered and fixed **5 additional issues** (including 1 critical real-world deadlock caught via `jstack`):

1. **CRITICAL — Fixed `SiloClient.kt` Timeout & Cancellation Deadlock (`APP-C03`)**:
   - **Discovery**: Running `./gradlew testDebugUnitTest` hung inside `SiloClientProtocolTest.callRawCancelsBlockingReadOnTimeoutAndRecoversBridge` (explaining why R2 stalled during Gradle execution!). Capturing `jstack` on `Gradle Test Executor 1` proved the exact cause: `timeoutJob.invokeOnCompletion { ... }` defaults to `onCancelling = false` in `kotlinx.coroutines`, so `TimeoutCoroutine` does **not** invoke completion handlers until its body finishes — deadlocking forever when `BufferedReader.readLine()` blocks synchronously inside `withTimeout`.
   - **Fix (`SiloClient.kt`)**:
     - Separated mutex acquisition under `withTimeout(effectiveTimeoutMs) { mutex.lock() }` with remaining timeout budget tracking.
     - Replaced `invokeOnCompletion` with `coroutineScope` running a dedicated active watchdog coroutine (`launch(Dispatchers.Default)`) alongside `async(Dispatchers.IO) { executeBlockingIoLocked(...) }`, coordinated by an `AtomicInteger` state machine (`STATE_RUNNING -> STATE_DONE / STATE_TIMED_OUT / STATE_CANCELLED`).
     - Both timeout expiration AND caller cancellation immediately fire non-blocking `process.destroyForcibly()` (`abortNow()`), unblocking `BufferedReader.readLine()` without touching `BufferedReader.lock`, while `withContext(NonCancellable) { runCatching { ioDeferred.await() } }` ensures `closeLocked(0L)` completes before `mutex.unlock()`.
     - Atomically claims `STATE_RUNNING -> STATE_DONE` before returning or throwing `DaemonRpcException`, preventing any post-completion abort of a healthy bridge.
   - **Added Tests (`SiloClientProtocolTest.kt`)**:
     - `callRawCancelsBlockingReadOnCallerCancellationAndRecoversBridge`
     - `callRawTimesOutWaitingForMutexWithoutLeakingLock`
2. **Hardened `unlockAndRun` `inFlight` Guard in `LockdownTileService.kt` & `SensorsOffTileService.kt` (`APP-H01`)**:
   - Added `var delegatedToUnlock = false` so the outer `finally` block only clears `inFlight` when `!delegatedToUnlock` (closing a subtle race if `unlockAndRun` ever executes synchronously while device unlocks mid-tap).
3. **Serialized Post-Allow Guard Sync under `configMutex` (`SiloViewModel.kt`, `APP-C05`)**:
   - In `setAppPermissionAction`, wrapped the post-`"allow"` `repo.getConfig()` sync inside `configMutex.withLock` and merged only `config.scopes = freshCfg.scopes` so it cannot overwrite concurrent unrelated optimistic config updates.
4. **Eliminated Remaining English-Only Fragment in `SiloViewModel.kt` (`APP-M15`)**:
   - Localized `"$rejected rejected"` in `removeSingleBlocklistCidr` (`"$rejected ditolak"` when Indonesian).
5. **Added `snackbarSeq` to `SiloUiState` & `MainActivity.kt` (`APP-C06`)**:
   - Keyed `LaunchedEffect(state.snackbarSeq, state.snackbarMessage)` in `MainActivity.kt` so consecutive identical error/status messages reliably re-trigger.

---

## 3. Verification Matrix — All 53 `APP-*` Defects & `PROJECT.md` Interface Contracts

### Interface Contracts Cross-Verified Against `daemon/src/{protocol,config,scopes}.rs` (Read-Only)
- **§ 1 IPC & Wire Protocol**: `su -c "exec /data/adb/modules/silo/bin/silod --bridge"`, strict `resp.id == req.id` validation (`parseAndValidateEnvelope`), non-JSON preamble rejection, continuous 2 KiB ring-buffered `stderr` drain, genuinely cancellable watchdog timeout + `destroyForcibly()` teardown, `MAX_SERIALIZED_JSON_BYTES = 3_500_000` (< daemon 4 MiB `MAX_IPC_LINE_BYTES`), RPC timeouts `<= 35s` (< daemon 45s `--bridge` read timeout).
- **§ 2 Permission Inspection & Action DTO**: `CategoryPermLiveState` preserves `last_access` and supports `last_access_ago`, `running_now`, `runtime_granted: Boolean? = null`, `has_runtime_perms`, `supports_foreground`, `system_fixed` — 100% aligned with `daemon/src/protocol.rs:612-626`. `AppInventory.runtimePermissionToCategory` & `specialAppOpPermissionToCategory` match `daemon/src/scopes.rs:69-188` verbatim. `setAppPermissionAction("allow")` clears `locked_deny` conflict; `AppPermGuard` enforces mutual exclusivity matching daemon.
- **§ 3 Firewall, Lockdown & Daemon Status**: `panic_lockdown_active: Boolean = false` added to both `FirewallConfig` and `DaemonStatus` (`SiloModels.kt:93, 332`), honored in `HomeScreen` (`matchSecurityPreset`, panic banner, master switch reset) and `LockdownTileService`; `replace_ip_blocklist` checks `rejected > 0` across add/remove/import paths.

### Defect-by-Defect Status (53 / 53 Remediated)
| Severity | IDs | Status & Key Evidence |
|---|---|---|
| **Critical (6)** | `APP-C01` | **FIXED** — `PermEffectiveUiMode` + `resolvePermUiMode` in `AppsScreen.kt:46-157`; `Normal` never active when `hasRuntimePerms && !runtimeGranted` (screenshot `Kamera` regression tested in `PermissionStateMachineTest`). |
| | `APP-C02` | **FIXED** — Strictly mutually exclusive `REVOKED` vs `FAKE_EMPTY` pills & badge order in `AppsScreen.kt:107-135, 1344-1351` (screenshot `Lokasi` regression + exhaustive invariant tested in `PermissionStateMachineTest`). |
| | `APP-C03` | **FIXED** — Active coroutine watchdog + `AtomicInteger` state machine + `destroyForcibly()` + `NonCancellable` teardown in `SiloClient.kt:141-298` (3 concurrency tests in `SiloClientProtocolTest`). |
| | `APP-C04` | **FIXED** — `suspend fun refreshMonitorOnce()`, sequential 3s poll skipping during `inFlightMutations > 0`, single deduplicated `accessLogJob` cancelled on dispose (`SiloViewModel.kt:337-411`, `TrafficScreen.kt:60-74`). |
| | `APP-C05` | **FIXED** — All config mutations serialized under `configMutex` with optimistic state & failure rollback (`SiloViewModel.kt`). |
| | `APP-C06` | **FIXED** — `SiloInlineFeedbackBanner` (`Theme.kt:270-315`) + `SheetFeedback` rendered inside all interactive `ModalBottomSheet`s (`AppsScreen`, `HomeScreen`, `SystemScreen`). |
| **High (20)** | `APP-H01` | **FIXED** — `LockdownTileService` & `SensorsOffTileService` allow instant defensive lock/mute on lockscreen, and require `if (isLocked) unlockAndRun` + fresh config re-read before disabling lockdown or unmuting sensors. |
| | `APP-H02` | **FIXED** — `BoundedStderrBuffer(2048)` + `silo-bridge-stderr` daemon reader thread (`SiloClient.kt:30-67, 333-355`), tested in `SiloClientProtocolTest`. |
| | `APP-H03` | **FIXED** — `SiloClient.parseAndValidateEnvelope` verifies JSON object syntax and `resp.id == expectedId`, tearing down bridge on mismatch, tested in `SiloClientProtocolTest`. |
| | `APP-H04` | **FIXED** — `AppInventory.reloadSingleApp(ctx, pkg)` (~5ms) replaces 400-package scans on per-app permission/guard edits; `pendingPermKeys` drives per-control spinners & button disable (`AppsScreen`, `TrafficScreen`). |
| | `APP-H05` | **FIXED** — `SiloViewModel.revokeFromAccessLog` chains revoke -> single-app reload -> access-log refresh -> toast; `PrivacyAccessRow` shows pending spinner, protected badge, and emerald `PALSU KOSONG`/`DICABUT` pill when blocked. |
| | `APP-H06` | **FIXED** — `skipPartiallyExpanded = true` on all `ModalBottomSheet`s; Material 3 `Surface(onClick)` + `defaultMinSize(minHeight = 40.dp)` + `minimumInteractiveComponentSize()` on pills/buttons; concise `"Auto-Cabut"`/`"Auto-Revoke"`. |
| | `APP-H07` | **FIXED** — `Normal` reflects true runtime+AppOps mode and clears `lockedDeny`; `Saat Dibuka` hidden/disabled when `!resolveSupportsForeground(cat, catState)`. |
| | `APP-H08` | **FIXED** — `AppInventory` separates `specialAppOpPermissionToCategory` (`SYSTEM_ALERT_WINDOW`, `MANAGE_EXTERNAL_STORAGE`, `PACKAGE_USAGE_STATS`) from `runtimePermissionToCategory`, adds Android 14+ `READ_MEDIA_VISUAL_USER_SELECTED`/`SEND_SMS`/`RECEIVE_MMS`/`BODY_SENSORS_BACKGROUND`; `"background"` removed from `AUDITABLE_CATEGORIES`. |
| | `APP-H09` | **FIXED** — `AppListFilter.USER` shows `!app.isSystem` (including non-launcher user apps) with `BG` badge (`AppsScreen.kt:237, 620-622`). |
| | `APP-H10` | **FIXED** — `partitionPermCategories` surfaces declared/granted/AppOps-only/active-guard/non-default categories first and collapses undeclared categories under an expandable section (`AppsScreen.kt:184-207, 1164-1252`). |
| | `APP-H11` | **FIXED** — `activeInspectedPackage` tracked via `DisposableEffect`; `onForegroundChanged(true)` refreshes inspected perms, freezer, hardening, and Monitor access log; 2 consecutive `getStatus()` failures flip `connected = false`. |
| | `APP-H12` | **FIXED** — Every RPC in `SiloViewModel` handles `.onFailure`; error banners with Retry added to `AppSheetTab.PERMS` and `TrafficTab.ACCESS`. |
| | `APP-H13` | **FIXED** — Occurrence-disambiguated stable content keys on all 3 `LazyColumn`s in `TrafficScreen.kt:38-49, 80-96, 171, 189, 285`. |
| | `APP-H14` | **FIXED** — `SocketRow` checks `isProtectedUid || isProtectedPackage`, preserves existing `AppFirewallRule` flags via `blockAppFromMonitor`, permits blocking `isUserListener` sockets, emits localized confirmation toast. |
| | `APP-H15` | **FIXED** — Exact `matchSecurityPreset` aligned with `daemon/src/config.rs` (`HomeScreen.kt:39-134`), renders `Kustom`/`Custom` or `Panic Lockdown` badge when unmatched (tested in `SystemAndHomeLogicTest`). |
| | `APP-H16` | **FIXED** — `formatDurationLabel(30) == "30m"` (`SystemScreen.kt:34-41, 627`), tested in `SystemAndHomeLogicTest`. |
| | `APP-H17` | **FIXED** — Packet Scheduler `"Default"` clears `defaultQdisc = ""` without forcing `enabled = true` (`SystemScreen.kt:748-751`). |
| | `APP-H18` | **FIXED** — Explicit `AlertDialog` confirmations before `Rollback to Kernel Defaults` and `Emergency Flush Firewall` (`SystemScreen.kt:135-175`). |
| | `APP-H19` | **FIXED** — `isCustomPrivateDns` highlights Custom DNS pill; Custom DNS dialog disables Apply on blank input (`SystemScreen.kt:61-70, 107-133, 492-509`). |
| | `APP-H20` | **FIXED** — `BlocklistParser` caps serialized JSON at 3.5 MiB (< 4 MiB daemon limit); all slow RPC timeouts `<= 35s` (< 45s daemon bridge timeout). |
| **Medium (19)** | `APP-M01` | **FIXED** — Cold-start load deduplicated via `refreshAllJob?.isActive` and `lastFullRefreshMs < 1500ms` guard. |
| | `APP-M02` | **FIXED** — Atomic sequential `SiloViewModel.ensureEntryAndFreeze(uid, entry)`. |
| | `APP-M03` | **FIXED** — `applyPreset` refreshes `hardeningStatus`, `freezerStatus`, `sentinelReport`, and status; `setFreezerEntry` calls `refreshLightStatusSuspend()`. |
| | `APP-M04` | **FIXED** — `addSingleBlocklistCidr` validates client-side via `BlocklistParser.validateEntry` and checks daemon `rejected > 0`. |
| | `APP-M05` | **FIXED** — Streaming 8 MiB SAF cap in `BlocklistParser.parseStream` and bounded 2 MiB `readBoundedUtf8` for config JSON import. |
| | `APP-M06` | **FIXED** — `exportConfigToUri` returns `false` when `openOutputStream(uri)` is `null`. |
| | `APP-M07` | **FIXED** — Pure numeric IPv4/RFC 4291 IPv6 validation rejecting timestamps/malformed colons/loopback/unspecified + multi-IP line extraction (`BlocklistParserTest`). |
| | `APP-M08` | **FIXED** — `AppInventory` adds `GET_ACTIVITIES | GET_PROVIDERS` with Binder exception per-package fallback. |
| | `APP-M09` | **FIXED** — `POST_NOTIFICATIONS` removed; `.MainActivity` `android:exported="false"`; aliases remain `exported="true"`. |
| | `APP-M10` | **FIXED** — `PanicRebootTileService` uses `@Volatile`, cancels `disarmJob` on 2nd tap, checks `triggerPanicReboot()` Result, updates tiles on `Dispatchers.Main.immediate`, uses localized subtitles across all 3 tiles. |
| | `APP-M11` | **FIXED** — `BridgeHandle.close` closes `writer`/`reader`/`errorStream`, escalates to `destroyForcibly()`, and joins/interrupts `stderrThread`. |
| | `APP-M12` | **FIXED** — Home score shows `"--"` when null/offline; removed duplicate `showProofSheet = true`; `LiveProofSheetContent` has `verticalScroll`, `liveProofLoading`, retry fallback, and inline feedback. |
| | `APP-M13` | **FIXED** — `showSysctlSheet` calls `refreshHardeningStatus()` on open; `SentinelAuditSheetContent` shows spinner/`--/100`/retry; `FlowRow` shows all `availCc` algorithms. |
| | `APP-M14` | **FIXED** — `BlocklistSheet` renders scrollable CIDR list with per-entry delete; duration pill pickers added for Auto-Reboot BFU, Clipboard, Wi-Fi, BT, and Freezer grace. |
| | `APP-M15` | **FIXED** — All ViewModel toasts/banners resolved via bilingual `SiloStrings`. |
| | `APP-M16` | **FIXED** — Explicit `loadingAccessLog` and `loadingAppPerms` indicators in `TrafficScreen` and `AppsScreen`. |
| | `APP-M17` | **FIXED** — `rememberSaveable` on nav/filter/tab/query/selected sheet; `refreshAll()` refreshes inspected perms & active Monitor access log. |
| | `APP-M18` | **FIXED** — Legacy v2 `scopes.apps` banner + `Clear Legacy Rule` action (`clearLegacyAppScope`) in `AppSheetTab.PERMS`; unused v2 write methods deprecated. |
| | `APP-M19` | **FIXED** — `AppPermGuard` enforces mutual exclusivity & distinct union `totalCount()`; honest Android `pm revoke` process-restart copy in `enforcerHint`. |
| **Low (8)** | `APP-L01` | **FIXED** — `formatTempUnblockMinutes` ceiling formula (`60s -> 1m`, `61s -> 2m`). |
| | `APP-L02` | **FIXED** — `RESTRICTED` filter includes `blockCleartextHttp` and `blockLocalhost`. |
| | `APP-L03` | **FIXED** — `G:N` tag renders alongside `BEKU`/`SUSPEND`. |
| | `APP-L04` | **FIXED** — Audit row opens `PERMS` tab; master firewall OFF hint shown in `AppsScreen` & `Network` tab. |
| | `APP-L05` | **FIXED** — Lazy bounded `LruCache<String, ImageBitmap>(150)`; offline-only apps tagged `OFFLINE` with hint. |
| | `APP-L06` | **FIXED** — `BuildConfig.VERSION_NAME`, `TextOverflow.Ellipsis` on `DnsPill`/`NavigationBarItem`, `LocalConfiguration`-reactive `resolveStrings`, hoisted `DateTimeFormatter`, a11y `contentDescription` labels. |
| | `APP-L07` | **FIXED** — Offline Home hides live Boot Guard/DNS footer, displays `"--"` stat tiles, disables daemon controls while keeping Reconnect & native Wipe Clipboard active. |
| | `APP-L08` | **FIXED** — `proguard-rules.pro` explicitly preserves `Signature, EnclosingMethod`, `@Serializable` classes, and `dev.silo.app.ipc.**` enums (`values()`, `valueOf()`, `<fields>`). |

---

## 4. Canonical Verification Commands & Real Output (Strictly After Final Edit)

### 1. Gradle JVM Unit Tests + Release APK (with R8 & Vital Lint)
```bash
cd /home/antigravity/terenzy/Silo/app && ./gradlew testDebugUnitTest assembleRelease --no-daemon
```
- **Exit Code:** `0` (`BUILD SUCCESSFUL in 6m 24s`, 75 actionable tasks: 36 executed, 39 up-to-date)
- **Unit Test Results (36 / 36 PASSED, 0 failures, 0 errors, 0 skipped):**
  - `dev.silo.app.BlocklistParserTest`: **4/4 passed** (`0.308s`)
  - `dev.silo.app.PermissionStateMachineTest`: **10/10 passed** (`0.358s`)
  - `dev.silo.app.ProtocolAndParserTest`: **5/5 passed** (`0.036s`)
  - `dev.silo.app.SiloClientProtocolTest`: **9/9 passed** (`1.428s`)
  - `dev.silo.app.SystemAndHomeLogicTest`: **8/8 passed** (`0.047s`)
- **Release Tasks Verified:** `compileReleaseKotlin`, `lintVitalAnalyzeRelease`, `minifyReleaseWithR8`, `lintVitalReportRelease`, `lintVitalRelease`, `shrinkReleaseRes`, `packageRelease`, `assembleRelease` all passed cleanly.
- **Release APK Output:** `/home/antigravity/terenzy/Silo/app/app/build/outputs/apk/release/app-release.apk` (`2,826,312 bytes` / `2.7M`).

### 2. Zero-`INTERNET` & Removed `POST_NOTIFICATIONS` Gate (`aapt2 dump permissions`)
```bash
/opt/android-sdk/build-tools/35.0.0/aapt2 dump permissions /home/antigravity/terenzy/Silo/app/app/build/outputs/apk/release/app-release.apk
```
- **Actual Output:**
  ```text
  package: dev.silo.app
  uses-permission: name='android.permission.QUERY_ALL_PACKAGES'
  permission: dev.silo.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
  uses-permission: name='dev.silo.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
  ```
- **Verified:** **ZERO** `android.permission.INTERNET`, **ZERO** `android.permission.POST_NOTIFICATIONS`, and `android.permission.QUERY_ALL_PACKAGES` is present.

### 3. Compiled Release Manifest Inspection (`aapt2 dump xmltree`)
```bash
/opt/android-sdk/build-tools/35.0.0/aapt2 dump xmltree /home/antigravity/terenzy/Silo/app/app/build/outputs/apk/release/app-release.apk --file AndroidManifest.xml
```
- **Verified:**
  - `versionCode=30000`, `versionName="3.0.0"`, `minSdkVersion=31`, `targetSdkVersion=35`, `allowBackup=false`.
  - `dev.silo.app.MainActivity`: **`android:exported=false`**, `launchMode=2` (`singleTask`).
  - `dev.silo.app.LauncherDefault`: `enabled=true`, **`exported=true`**, `targetActivity="dev.silo.app.MainActivity"`, `MAIN`/`LAUNCHER`.
  - `dev.silo.app.LauncherDisguise`: `enabled=false`, **`exported=true`**, `targetActivity="dev.silo.app.MainActivity"`, `MAIN`/`LAUNCHER`.
  - All 3 Quick Settings tile services intact with `exported=true`, `permission="android.permission.BIND_QUICK_SETTINGS_TILE"`, and `android.service.quicksettings.action.QS_TILE`:
    - `dev.silo.app.tiles.LockdownTileService`
    - `dev.silo.app.tiles.SensorsOffTileService`
    - `dev.silo.app.tiles.PanicRebootTileService`

---

## 5. Caveats & Unexplored Module Flags
- **On-device runtime validation**: All Android JVM unit tests, R8 minification, vital lint, and compiled APK manifest/permission checks ran natively on the devbox. Physical One UI 7 / Android 15 device behavior (`TileService.unlockAndRun`, `PackageManager` live queries, Compose touch gesture feel inside `ModalBottomSheet`, live `su -c "exec silod --bridge"` pipe) relies on M1/M2 daemon deployment and real device / M5 E2E integration verification.
- **Unexplored module flags**: None within `app/**` — 100% of Android app source, resource, manifest, ProGuard, and test files were inspected and verified.
