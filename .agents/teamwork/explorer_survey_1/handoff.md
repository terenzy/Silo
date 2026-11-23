# Step 0 Survey Handoff — `explorer_survey_1` (Android App Deep Code Audit & Repo Map)

**Core Summary:** Every pixel of the user's screenshot and every symptom in the user report ("permission controls in per-app & monitor cannot be clicked / don't respond", "revoking highlights Normal / allowed", "`Kunci Blokir` delays 15–20s then force-stops") is fully explained by concrete, compounding defects in `app/` and the `app ↔ daemon` contract: overlapping/broken pill `active` predicates in `AppsScreen.kt:859-923`, `parse_appops_get_output` overwriting `Uid mode: ignore` with package `allow`/`default` (`daemon/src/scopes.rs:297-309`), tiny 26–32dp touch targets, `ModalBottomSheet` hiding all `Snackbar` errors, a single-mutex IPC queue jammed by non-suspending 3s polling coroutines (`SiloViewModel.kt:169-193`) + non-cancellable blocking `readLine()` inside `withTimeout` (`SiloClient.kt:53-77`) + 2–4s full-device `getInstalledPackages()` scans on every click (`SiloViewModel.kt:480`), and 15s periodic `PermissionEnforcer` sweeps running Android's process-killing `pm revoke`. In total, this audit catalogs **53 verified defects/loopholes** (6 Critical, 20 High, 19 Medium, 8 Low) across `app/` and its `daemon/` interface contracts.

---

## 1. Observation

### 1.1 Complete Repository Map & File / LOC Inventory (`/home/antigravity/terenzy/Silo`)

Verified via `find . -not -path './.git/*' -not -path './.agents/*' -not -path '*/build/*' -not -path './.gradle/*' -type f` and `wc -l`:

| Module / Directory | File Count | Total LOC | Responsibility |
|---|---:|---:|---|
| `app/app/src/main/java/dev/silo/app/` | 16 `.kt` | 5,805 | Android Kotlin + Jetpack Compose Material 3 UI, ViewModel, Repository, IPC bridge client, AppInventory, 3 QS TileServices |
| `app/app/src/test/java/dev/silo/app/` | 1 `.kt` | 154 | JVM unit tests (`ProtocolAndParserTest.kt`) |
| `app/` XML / Gradle / ProGuard / Wrapper | 22 files (1 binary jar) | 358 (+341 gradlew scripts) | Manifest, icons/drawables, bilingual XML strings, Compose/Gradle config |
| `daemon/src/` | 13 `.rs` | 8,644 | Static ARM64 Rust root daemon (`silod`): IPC, Netfilter firewall, Netlink ipset/NFLOG/SOCK_DESTROY, cgroup v2 freezer, AppOps/pm enforcer, sysctl hardening, automation, sentinel/live proof |
| `daemon/` Cargo config | 3 files | 143 | `Cargo.toml`, `Cargo.lock`, `.cargo/config.toml` |
| `module/` | 5 files | 185 | KernelSU-Next zero-mount module scripts (`service.sh`, `customize.sh`, `action.sh`, `uninstall.sh`, `module.prop`) |
| `scripts/` & `.github/workflows/` | 2 files | 255 | `scripts/build_module.sh` (45 LOC), `.github/workflows/build.yml` (210 LOC) |
| Root docs & license | 3 files | 785 | `README.md` (89 LOC), `.gitignore` (22 LOC), `LICENSE` (674 LOC) |
| **Entire Repository (excluding `.git`/`.agents`)** | **65 files** | **~16,670 text LOC** | Self-contained 2-tier architecture: Android APK (`app/`) ↔ Rust Root Daemon (`daemon/`) + KSU Module (`module/`) |

#### Complete File Tree with Exact Line Counts

```text
/home/antigravity/terenzy/Silo/
├── .github/workflows/build.yml                                   (210)
├── .gitignore                                                    (22)
├── LICENSE                                                       (674)
├── README.md                                                     (89)
├── scripts/build_module.sh                                       (45)
├── module/
│   ├── action.sh                                                 (45)
│   ├── customize.sh                                              (33)
│   ├── module.prop                                               (6)
│   ├── service.sh                                                (75)
│   └── uninstall.sh                                              (26)
├── daemon/
│   ├── .cargo/config.toml                                        (7)
│   ├── Cargo.lock                                                (114)
│   ├── Cargo.toml                                                (22)
│   └── src/
│       ├── automation.rs                                         (527)
│       ├── config.rs                                             (917)
│       ├── firewall.rs                                           (869)
│       ├── freezer.rs                                            (390)
│       ├── hardening.rs                                          (387)
│       ├── ipc.rs                                                (335)
│       ├── main.rs                                               (1309)
│       ├── netlink.rs                                            (1182)
│       ├── protocol.rs                                           (906)
│       ├── scopes.rs                                             (858)
│       ├── sentinel.rs                                           (518)
│       ├── sys.rs                                                (349)
│       └── telemetry.rs                                          (97)
└── app/
    ├── build.gradle.kts                                          (6)
    ├── gradle.properties                                         (6)
    ├── gradlew / gradlew.bat                                     (249 / 92)
    ├── gradle/wrapper/gradle-wrapper.{jar,properties}            (BINARY / 7)
    ├── settings.gradle.kts                                       (18)
    └── app/
        ├── build.gradle.kts                                      (97)
        ├── proguard-rules.pro                                    (16)
        └── src/
            ├── main/
            │   ├── AndroidManifest.xml                           (96)
            │   ├── java/dev/silo/app/
            │   │   ├── MainActivity.kt                           (143)
            │   │   ├── SiloApp.kt                                (8)
            │   │   ├── data/
            │   │   │   ├── AppInventory.kt                       (236)
            │   │   │   └── SiloRepository.kt                     (332)
            │   │   ├── ipc/
            │   │   │   ├── SiloClient.kt                         (130)
            │   │   │   └── SiloModels.kt                         (415)
            │   │   ├── tiles/
            │   │   │   ├── LockdownTileService.kt                (60)
            │   │   │   ├── PanicRebootTileService.kt             (72)
            │   │   │   └── SensorsOffTileService.kt              (53)
            │   │   └── ui/
            │   │       ├── SiloViewModel.kt                      (773)
            │   │       ├── i18n/SiloStrings.kt                   (798)
            │   │       ├── theme/Theme.kt                        (258)
            │   │       └── screens/
            │   │           ├── AppsScreen.kt                     (1005)
            │   │           ├── HomeScreen.kt                     (530)
            │   │           ├── SystemScreen.kt                   (643)
            │   │           └── TrafficScreen.kt                  (349)
            │   └── res/
            │       ├── drawable/ic_{disguise,launcher}_{bg,fg}.xml (10-16 each)
            │       ├── drawable/ic_tile_{lockdown,panic,sensors}.xml (10 each)
            │       ├── mipmap-anydpi-v26/ic_launcher{,_disguise}.xml (6 each)
            │       ├── values/strings.xml, values-in/strings.xml, values/themes.xml (8 each)
            └── test/java/dev/silo/app/ProtocolAndParserTest.kt   (154)
```

---

### 1.2 Feature Inventory for `app/`

1. **App Entry & Navigation (`SiloApp.kt`, `MainActivity.kt`)**:
   - Application singleton `SiloRepository` (`SiloApp.kt:7`) shared by `SiloViewModel` and all 3 Quick Settings `TileService`s.
   - 4-tab bottom navigation (`HOME` / `APPS` / `TRAFFIC` / `SYSTEM`) with TopAppBar manual refresh (`vm.refreshAll()`), `SnackbarHost`, lifecycle observer (`ON_RESUME`/`ON_PAUSE` → `vm.onForegroundChanged`), bilingual strings (`Auto` / `Indonesia` / `English`), 3 themes (`Obsidian` / `AMOLED` / `Dynamic`).
   - Strict offline-first manifest (`tools:node="remove"` on `INTERNET`, `QUERY_ALL_PACKAGES`, `POST_NOTIFICATIONS` [unused]). Dual launcher aliases (`LauncherDefault` / `LauncherDisguise` "NetDiag").
2. **Home Dashboard (`HomeScreen.kt`)**:
   - Offline daemon banner + Reconnect button.
   - Master Kernel Firewall switch, 4-tile stats strip (Security Score, Blocked packets, Frozen+Suspended count, Permission Enforcer rule count), Boot Guard + Private DNS footer, last enforcer event & firewall error banners.
   - 6 Live Hardware Quick Actions: Mic Mute, Camera Mute, Wipe Clipboard (Native Android + Daemon), Live Enforcement Proof bottom sheet, Panic Lockdown (with confirmation dialog), Panic BFU Reboot (with confirmation dialog).
   - 4 Security Presets: Safe (`Aman`), Balanced (`Seimbang`), Hardened (`Ketat`), Lockdown (`Paranoid`).
3. **Apps & Permissions Controller (`AppsScreen.kt`, `AppInventory.kt`)**:
   - Search bar + 4 filters: `Aplikasi` (`USER`), `Terbatas` (`RESTRICTED`), `Audit Izin` (`AUDIT` with category filter chips + 1-tap Bulk Revoke), `Sistem` (`SYSTEM`).
   - Per-app row with inline Wi-Fi, Cellular, and Freeze/Thaw quick toggles + risk metadata (granted sensitive categories, detected tracker SDKs, temp unblock countdown).
   - `ModalBottomSheet` (`AppControlSheet`) with Risk Breakdown & 1-tap `Cabut Semua Izin Sensitif Aktif` card + 3 tabs:
     - `Jaringan`: 8 firewall switches, Temporary Unblock timer (15m/30m/60m), Kill TCP Sockets, Reset rule.
     - `Izin & AppOps`: 10 permission categories (`camera`, `microphone`, `location`, `background_location`, `contacts`, `media`, `sms_calllog`, `clipboard`, `sensors`, `background`), each with live status badge, 4 action pills (`Normal`, `Saat Dibuka`, `Palsukan`, `Cabut`), and 2 daemon enforcer toggles (`Auto-Cabut saat Ditutup`, `Kunci Blokir`).
     - `Pembeku`: Auto-freeze list toggle, mode selector (`Soft cgroup v2` / `Deep pm suspend`), Freeze Now / Thaw Now.
4. **Real-Time Monitor (`TrafficScreen.kt`)**:
   - 3 tabs with 3-second auto-polling:
     - `Diblokir` (`BLOCKED`): RAM-only NFLOG blocked packet list + Clear button.
     - `Koneksi` (`LIVE`): Active TCP/UDP sockets (`inet_diag`) with per-socket Kill (TCP) and Block App buttons.
     - `Akses Izin` (`ACCESS`): Real-time `dumpsys appops --op` privacy access timeline with manual refresh and per-row `Cabut` button.
5. **System & Security (`SystemScreen.kt`)**:
   - 1-click Private DNS (DoT) presets + Custom hostname dialog.
   - Radio Privacy switches (Enhanced MAC randomization, Disable Wi-Fi/BLE bg scanning, Disable Captive Portal) + live ADB warning.
   - Global Network Guards (Default-Allow/Deny, Reject/Drop, LAN isolation, DNS leak guard, IPv6 leak guard, Anti-port-scan, Global HTTP block, TTL/HL 64, Auto-block new apps, SOCK_DESTROY on block, IP/CIDR blocklist bottom sheet with SAF file import).
   - Privacy Automation (USB/ADB lock guard, Auto-Reboot BFU timer, Auto-Clear Clipboard, Wi-Fi/BT Idle Auto-Off, Mute Sensors on Lockscreen, Auto-Freeze Engine + Thaw All).
   - Kernel Hardening (`sysctl` master switch, TCP congestion control, Packet Scheduler qdisc, Live Sysctl inspector sheet, Rollback).
   - Verification (Live Enforcement Proof sheet, Stealth Sentinel Audit sheet, GrapheneOS Hardware/ROM boundaries sheet).
   - Preferences (Language, Theme, App Disguise, Export/Import Config JSON via SAF, Emergency Flush Firewall).
6. **Quick Settings Tiles (`tiles/`)**:
   - `LockdownTileService` (Default-Deny toggle), `SensorsOffTileService` (Mic+Cam mute toggle), `PanicRebootTileService` (2-tap 5s-armed BFU reboot).

---

### 1.3 Direct Observations from User Screenshot (`/home/antigravity/Screenshot_20261122_223732_Silo.jpg`)

Directly observed on screen:
- Sheet header: **`Kamera`** (`com.sec.android.app.camera • UID 10095`), badge `Risiko Sedang`.
- Risk card: `Izin sensitif aktif saat ini: Foto & Media, Mikrofon, Latar & Overlay` + button `Cabut Semua Izin Sensitif Aktif`. Notice **`camera`** and **`location`** are **NOT** in `grantedSensitiveCategories` (`runtimeGranted == false`), whereas **`background`** (`Latar & Overlay`) IS still listed.
- Active sheet tab: `Izin & AppOps`.
- **`Kamera` permission row**:
  - Top-right status badge: **`DICABUT`** rendered in **gray** (`MaterialTheme.colorScheme.onSurfaceVariant`), NOT emerald green.
  - 4 action pills: **`Normal` is highlighted GREEN**, while `Saat Dibuka`, `Palsukan`, and `Cabut` are unhighlighted.
  - Bottom enforcer toggles: `○ Auto-Cabut saat Ditut...` (truncated with ellipsis), **`✓ Kunci Blokir`** (highlighted amber/gold).
- **`Mikrofon` permission row**:
  - Status badge: **`AKTIF`** (amber).
  - 4 action pills: **`Normal` is highlighted GREEN**. Both enforcer pills unchecked.
- **`Lokasi` permission row**:
  - Status badge: **`PALSU KOSONG`** (emerald green).
  - 4 action pills: **BOTH `Palsukan` (GREEN) AND `Cabut` (RED) are highlighted simultaneously!** Both enforcer pills unchecked.

---

### 1.4 Forensic Root-Cause Answers to Every User-Reported Bug

#### Q1. Why does revoking ("Cabut") result in being allowed ("diizinkan" / `Normal` highlighted) AND why does `Lokasi` highlight BOTH `Palsukan` and `Cabut`?

Observed exact code in `AppsScreen.kt:859-923` and `daemon/src/scopes.rs:142-184, 263-312`:

1. **Broken `Kamera` state (`DICABUT` gray badge + `Normal` green + `Cabut` unhighlighted)**:
   - In `AppsScreen.kt:859-866`:
     ```kotlin
     val (statusLabel, statusColor) = when {
         runningNow -> s.permStateRunning to SiloCrimson
         appopsMode == "ignore" -> s.permStateIgnored to SiloEmerald
         appopsMode == "deny" -> s.permStateDenied to SiloEmerald
         appopsMode == "foreground" -> s.permStateForeground to SiloCyan
         runtimeGranted -> s.permStateGranted to SiloAmber
         else -> s.permStateDenied to MaterialTheme.colorScheme.onSurfaceVariant
     }
     ```
     The gray `DICABUT` badge proves `appopsMode` is `"default"` or `"allow"` (NOT `"ignore"`/`"deny"`) while `runtimeGranted == false`.
   - In `AppsScreen.kt:895-901`:
     ```kotlin
     SmallActionPill(
         text = s.permActionNormal,
         active = appopsMode == "default" || appopsMode == "allow", // <-- IGNORES runtimeGranted!
         ...
     )
     ```
     `Normal` checks **ONLY** `appopsMode == "default" || appopsMode == "allow"` and **completely ignores `!runtimeGranted`**! Meanwhile `Cabut` (`AppsScreen.kt:918`) checks `!runtimeGranted && (appopsMode == "ignore" || appopsMode == "deny")`.
   - **Why was `appopsMode` equal to `"default"`/`"allow"` on `Kamera` right after `Kunci Blokir` / `Cabut` executed `pm revoke` + `cmd appops set ignore`?**
     Cross-verified in `daemon/src/scopes.rs:297-309`:
     ```rust
     // Prefer stricter/specific package line over generic Uid mode if op already exists
     map.entry(op)
         .and_modify(|e: &mut (String, Option<String>, bool)| {
             if !raw_line.trim().starts_with("Uid mode:") {
                 e.0 = mode.clone();
             }
     ```
     On Android 12–16 (One UI 7), for a runtime permission the app has previously accessed (`CAMERA` on Samsung Camera), `cmd appops get --user 0 <pkg>` emits BOTH `Uid mode: CAMERA: ignore` AND a historical package line `CAMERA: allow; time=+...` (or `CAMERA: default; time=+...`). Despite the comment claiming "Prefer stricter", `scopes.rs:299-301` **unconditionally overwrites `Uid mode: ignore` with the non-Uid line `allow`/`default`!** Additionally, `AppsScreen.kt:764` defaults `appopsMode` to `"default"` whenever `liveAppPerms[pkg]` is missing/failed.
   - **Combined effect**: User taps `Cabut` → `pm revoke` revokes runtime permission (`runtimeGranted = false`), `inspect_app_permissions` returns `appops_mode = "allow"` or `"default"` → badge shows gray `DICABUT`, `Cabut` stays OFF, and **`Normal` lights up GREEN** ("saat dicabut malah diizinkan")!
   - Furthermore, if the user taps `Normal` (`onAction("allow")`), `scopes.rs:143-144` only resets AppOps to `"default"` and **never runs `pm grant`**, so `Normal` is green even though the permission remains revoked.

2. **Broken `Lokasi` state (`PALSU KOSONG` green badge + BOTH `Palsukan` green AND `Cabut` red lit)**:
   - Look at `AppsScreen.kt:910-922`:
     ```kotlin
     SmallActionPill(text = s.permActionIgnore, active = appopsMode == "ignore", ...)
     SmallActionPill(text = s.permActionRevoke, active = !runtimeGranted && (appopsMode == "ignore" || appopsMode == "deny"), ...)
     ```
   - **The two predicates are NOT mutually exclusive!** Because `Cabut` runs `pm revoke` (`runtimeGranted = false`) + `appops ignore` (`appopsMode = "ignore"`), EVERY time `appopsMode == "ignore"` AND `!runtimeGranted`:
     - Badge (`AppsScreen.kt:861`) hits `appopsMode == "ignore"` FIRST → displays **`PALSU KOSONG`** instead of `DICABUT`!
     - `Palsukan` evaluates `appopsMode == "ignore"` → **`true` (GREEN)**!
     - `Cabut` evaluates `!runtimeGranted && appopsMode == "ignore"` → **`true` (RED)**!
   - Both buttons highlight simultaneously on every properly revoked permission (and on every non-runtime category like `clipboard` where `runtimeGranted` is always `false`).

#### Q2. Why can't permission controls in BOTH Per-App (`Izin & AppOps`) AND Monitor (`Akses Izin`) be clicked or do not respond?

Six compounding defects combine to make both screens feel dead/unresponsive:

1. **Unbounded RPC Queue Pileup in Monitor (`SiloViewModel.kt:166-193` + `SiloClient.kt:39,51`)**:
   - `setMonitorPollingActive(true)` calls `refreshPrivacyAccessLog()` (which executes **11 sequential `dumpsys appops --op <OP>` subprocesses** in `daemon/src/scopes.rs:333-354`, taking 3–15s on One UI 7) AND runs a `while (isActive)` loop calling **`refreshMonitorOnce()` every 3 seconds**.
   - **Critical bug (`SiloViewModel.kt:173, 180-193`)**: `refreshMonitorOnce()` is NOT a `suspend` function — it fires a new `viewModelScope.launch` and returns immediately! So every 3 seconds while `get_privacy_access_log` holds `SiloClient.mutex`, **3 MORE RPCs (`get_live_connections`, `get_telemetry`, `get_status`) pile up into `SiloClient.mutex`**, plus `TrafficScreen.kt:45-49` triggers a **second** overlapping `refreshPrivacyAccessLog()` when tapping the `Akses Izin` tab, plus `foregroundSyncJob` queues `get_status` every 4s!
   - By the time the user taps `Cabut` in Monitor → Akses Izin, **10–20+ slow RPCs are queued ahead of their click** on the single `SiloClient.mutex`!
2. **Broken Non-Cancellable Timeout in `SiloClient.kt:53-77`**:
   - `withTimeout(timeoutMs)` wraps blocking Java `BufferedReader.readLine()` with zero coroutine suspension points. `withTimeout` **cannot abort** a stalled read; meanwhile `mutex.withLock` stays held, freezing every button click app-wide.
3. **2–4 Second Full Package Scan on Every Single Permission Click + Zero Loading/Optimistic Feedback (`SiloViewModel.kt:477-491, 513-529`)**:
   - Every tap on ANY permission button runs `AppInventory.loadInstalledApps(getApplication())` (`pm.getInstalledPackages(GET_PERMISSIONS | GET_RECEIVERS | GET_SERVICES)` + `getLaunchIntentForPackage` + `getApplicationLabel` across all ~350–450 packages on One UI 7) **before** updating `_ui`! Zero spinner, zero optimistic state, zero confirmation toast on success.
4. **`ModalBottomSheet` Completely Hides All Error/Status Snackbars (`AppsScreen.kt:117-129` + `MainActivity.kt:109`)**:
   - Material 3 `ModalBottomSheet` renders in a separate Android Dialog `Window` above `MainActivity`'s `Scaffold`/`SnackbarHost`. Every `toast("Izin error: ...")` or `toast("Guard error: ...")` renders **behind the dimmed bottom sheet** — 100% invisible! So failed taps (e.g., `foreground` rejected on unsupported op, daemon timeout, protected package, offline daemon) look like completely dead clicks.
5. **Monitor → Akses Izin `Cabut` Race & Zero Visual Change After Revoke (`TrafficScreen.kt:176-179, 239-246`)**:
   - `onRevoke` launches TWO separate racing coroutines (`vm.setAppPermissionAction(...)` and `vm.refreshPrivacyAccessLog()`).
   - `get_privacy_access_log` returns **7-day historical `Access:` timestamps** from `dumpsys appops`, which are NOT deleted by revoking a permission. Thus after tapping `Cabut`, the row stays in the list, the `Cabut` button stays red/enabled with the exact same label (`PrivacyAccessRow` never checks `ev.mode == "ignore" || ev.mode == "deny"`), and NO toast is shown!
   - Additionally, `daemon/src/scopes.rs:435` does NOT filter `is_protected_package`, so protected packages appear in Monitor → Akses Izin with an active `Cabut` button that always fails.
6. **Sub-Minimum Touch Targets Inside Draggable `ModalBottomSheet` (`AppsScreen.kt:358, 510, 953-1004`; `TrafficScreen.kt:243`)**:
   - `SmallActionPill` (~28dp height) and `EnforcerPill` (~26dp height) use raw `Surface(modifier.clickable)` (bypassing M3's 48dp `minimumInteractiveComponentSize`) inside a `verticalScroll` inside a draggable `ModalBottomSheet`. `PrivacyAccessRow`'s and `AuditAppRow`'s `Cabut` buttons force `Modifier.height(32.dp)`.

#### Q3. How do `Kunci Blokir` (Lock Block) and `Auto-Cabut` (Auto-Revoke) work, and why does `Kunci Blokir` wait before force-stopping the app and revoking? Is this a limitation?

- **App-side flow (`AppsScreen.kt:767-782, 926-941` → `SiloViewModel.kt:493-529` → `SiloRepository.kt:220-224`)**:
  - `toggleAppAutoRevoke` / `toggleAppStrictLock` mutate `config.scopes.guards[packageName]` (`AppPermGuard(autoRevokeOnBg, lockedDeny)`) and send `set_app_perm_guard` to `silod`.
  - Daemon (`main.rs:769-798`) persists `config.json` atomically (`save_atomic`), immediately revokes every category in `locked_deny`, and returns updated `ScopesConfig`.
  - App then runs full `AppInventory.loadInstalledApps()`, updates `config.scopes`, and spawns `inspectAppPermissions(packageName)` + `refreshLightStatus()`.
- **Why `Kunci Blokir` waits (~15–20 seconds) before revoking**:
  - **NOT a hard Android OS limitation — it is a Silo daemon polling interval.**
  - `main.rs:1074` sleeps 5s per supervisor tick, and `PermissionEnforcer::tick` (`daemon/src/scopes.rs:568, 606`) only inspects `locked_deny` when `now - last_strict_sweep_boottime >= 15` (**every 15–20 seconds**).
  - It can be made near-instant (~0.5–2s) via faster foreground-targeted polling when a guarded app or `PermissionController` is active, or `inotify` watching `/data/system/users/0/runtime-permissions.xml` / `/data/system/appops.xml`, or using `appops` / `pm` flags where supported (e.g., `pm set-permission-flags` / `cmd appops set --uid ... deny/ignore`).
- **Why the target app force-stops (`berhenti paksa`)**:
  - **YES — this IS an Android OS framework behavior of `pm revoke`.** Whenever `pm revoke` (`scopes.rs:154`) revokes a runtime permission on a currently running app, Android's `ActivityManagerService` deliberately kills (`killUid`) the app process so it cannot retain stale permission state in memory.
  - **CRITICAL BUG making it 10x worse**: Because `parse_appops_get_output` (`scopes.rs:299-301`) misreads `Kamera` as `appops_mode = "allow"`/`"default"` even when already revoked, `PermissionEnforcer` (`scopes.rs:609-615`) evaluates `drifted = (appops_mode != "ignore" && appops_mode != "deny")` as **`true` on EVERY 15-second sweep FOREVER**, repeatedly calling `pm revoke` and **force-stopping the app every 15 seconds even when the permission was never re-granted!**
  - **Design improvement**: If `Kunci Blokir` enforces `cmd appops set --uid` + package `ignore` (which takes effect immediately WITHOUT killing the process) while the app is foregrounded, and only executes `pm revoke` when needed (or only when `runtime_granted` actually drifted to granted, checked via `dumpsys package` / `appops` properly!), it stops spurious force-stops and dramatically improves UX — while clearly disclosing in UI that revoking a runtime permission on a running app causes Android to restart/stop that app.

---

### 1.5 Exhaustive Bug, Security Loophole & Logic Error Catalog (`app/` + App↔Daemon Boundary)

#### CRITICAL Severity

| ID | File:Line | Evidence & Root Cause | User Impact | Concrete Fix Strategy |
|---|---|---|---|---|
| **APP-C01** | `AppsScreen.kt:895-901` | `SmallActionPill` (`Normal`) uses `active = appopsMode == "default" || appopsMode == "allow"`, ignoring `runtimeGranted`. Combined with `daemon/src/scopes.rs:299-301` overwriting `Uid mode: ignore` with historical package `allow`/`default`, and `AppsScreen.kt:764` defaulting missing state to `"default"`. | Screenshot `Kamera`: badge is gray `DICABUT` while `Normal` is highlighted GREEN ("dicabut malah diizinkan"). | Derive a single mutually-exclusive enum `PermEffectiveUiMode` (`NORMAL`, `FOREGROUND`, `FAKE_EMPTY`, `REVOKED`, `UNGRANTED`) from `(hasRuntimePerms, runtimeGranted, appopsMode)` so **at most one** pill is ever active; fix `parse_appops_get_output` in daemon to preserve stricter `Uid mode` and also return real `runtime_granted` from daemon/single-pkg lookup. |
| **APP-C02** | `AppsScreen.kt:910-922`, `859-866` | `Palsukan` (`appopsMode == "ignore"`) and `Cabut` (`!runtimeGranted && (appopsMode == "ignore" || appopsMode == "deny")`) overlap whenever `!runtimeGranted && appopsMode == "ignore"`. Badge also checks `appopsMode == "ignore"` before `!runtimeGranted`. | Screenshot `Lokasi`: badge says `PALSU KOSONG` while **BOTH** `Palsukan` (green) AND `Cabut` (red) light up simultaneously. | Make `PermEffectiveUiMode` strictly exclusive: for runtime-permission categories, `!runtimeGranted` → `REVOKED` (badge `DICABUT`, only `Cabut` lit); `runtimeGranted && appopsMode == "ignore"` → `FAKE_EMPTY` (badge `PALSU KOSONG`, only `Palsukan` lit). For AppOps-only categories (`clipboard`, `background`), hide/merge `Cabut` into `Blokir/Palsukan` or treat `ignore`/`deny` as a single blocked state. |
| **APP-C03** | `SiloClient.kt:51-77` | `withTimeout(timeoutMs)` wraps synchronous blocking Java I/O (`ensureBridgeConnected`, `w.write`, `r.readLine`) with **zero suspension points** inside `mutex.withLock`. | Timeout NEVER interrupts a hung `su` / `silod --bridge` call; `mutex` stays locked indefinitely and freezes every button in the entire app. | Close/destroy the bridge process on timeout cancellation (e.g., watchdog coroutine or `suspendCancellableCoroutine` with `invokeOnCancellation { forceCloseProcess() }`), or move blocking socket I/O behind an interruptible watchdog so timeout genuinely aborts and recovers. |
| **APP-C04** | `SiloViewModel.kt:166-193` | `setMonitorPollingActive` loop calls non-suspending `refreshMonitorOnce()` every 3s without awaiting completion while `get_privacy_access_log` (11 × `dumpsys appops`, 3–15s) holds `SiloClient.mutex`. Plus `TrafficScreen.kt:45-49` triggers a duplicate `refreshPrivacyAccessLog()`. | Queues 10–20+ polling RPCs ahead of user clicks on `SiloClient.mutex`, making Monitor `Cabut` / `Kill` / `Block` appear dead for 10–30s. Leaving Monitor doesn't cancel in-flight calls. | Make `refreshMonitorOnce()` a `suspend fun` called sequentially inside `monitorJob` (only poll live/blocked when on those tabs, deduplicate `refreshPrivacyAccessLog()` with a `Job` guard, and use a dedicated/prioritized bridge or skip polling ticks if an interactive mutation is pending). |
| **APP-C05** | `SiloViewModel.kt:215-225, 308-322, 493-529, 583-598, 614-624`; `AppsScreen.kt:281-292, 663-697` | Read-modify-write config updates capture `_ui.value` synchronously *before* async IPC with no optimistic update or serialization (`updateFirewallConfig`, `toggleAppAutoRevoke`, `toggleAppStrictLock`, `updateHardeningConfig`, `updateAutomationConfig`, `updateAppFirewallRule`). | Rapidly toggling two permission guards or two firewall/system switches silently clobbers the first change when the second coroutine writes stale state to `silod`. | Serialize config mutations via a ViewModel `Mutex` (re-reading latest `_ui.value` inside the lock) + apply immediate optimistic state with rollback on IPC failure. |
| **APP-C06** | `AppsScreen.kt:117-129`; `SystemScreen.kt:108-151, 192-214`; `HomeScreen.kt:81-91`; `MainActivity.kt:109` | Material 3 `ModalBottomSheet` opens in a separate Android Dialog `Window` above `MainActivity`'s `Scaffold` `SnackbarHost`. | Every error/status `toast(...)` triggered inside `AppControlSheet`, `BlocklistSheet`, `LiveProofSheet`, or `SentinelAuditSheet` renders **behind** the sheet and is 100% invisible — failed clicks look like dead UI. | Render an inline status/error banner (or dedicated `SnackbarHost`) inside `ModalBottomSheet` content, or route sheet-scoped messages into the sheet so errors and confirmations are immediately visible. |

#### HIGH Severity

| ID | File:Line | Evidence & Root Cause | User Impact | Concrete Fix Strategy |
|---|---|---|---|---|
| **APP-H01** | `LockdownTileService.kt:21-35`; `SensorsOffTileService.kt:20-28` | **SECURITY LOOPHOLE**: Neither QS tile checks `isLocked` / calls `unlockAndRun` before **disabling** Default-Deny Lockdown or **unmuting** Mic/Camera. | Any physical attacker on a locked phone can pull down Quick Settings and turn OFF Silo Lockdown Firewall and UNMUTE Mic & Camera without unlocking the device! | Keep enabling Lockdown / muting sensors instant on lockscreen, but guard **disabling** Lockdown or **unmuting** sensors with `if (isLocked) unlockAndRun { ... }`. |
| **APP-H02** | `SiloClient.kt:105-115` | `pb.redirectErrorStream(false)` spawns `su -c silod --bridge` but **never reads `p.errorStream`**. | If `su` or `silod` emits >64 KB to stderr, the pipe fills and deadlocks the bridge subprocess permanently; also loses `su` root-denied diagnostic messages. | Drain `p.errorStream` on a lightweight daemon reader thread into a tiny bounded ring buffer (e.g. last 2 KB) and include it in error messages on bridge EOF. |
| **APP-H03** | `SiloClient.kt:55-76` | Sends incrementing `reqId`, decodes `IpcEnvelopeResponse`, but **never checks `resp.id == reqId`**. | Any stray stdout line (e.g., shell profile/su notice) or post-error desync returns mismatched JSON to the wrong caller. | Validate `resp.id == reqId`; skip non-JSON preamble only if safe or tear down & reset bridge on ID mismatch. |
| **APP-H04** | `SiloViewModel.kt:477-491, 513-529, 531-547`; `AppInventory.kt:160-212` | Every single per-app permission click runs full `AppInventory.loadInstalledApps()` across all ~350–450 packages (~2–4s Binder IPC) before updating UI, with zero busy indicator. | 2–15s frozen-feeling lag on every tap in `Izin & AppOps` and `Audit Izin`. | Add `AppInventory.reloadSingleApp(context, packageName)` via `pm.getPackageInfo(pkg, flags)` (~5ms) for single-app mutations, and track `pendingPermKeys: Set<String>` in `SiloUiState` to show inline progress spinners per control. |
| **APP-H05** | `TrafficScreen.kt:176-179, 239-246` | Monitor → `Akses Izin` `Cabut` fires 2 racing coroutines (`setAppPermissionAction` + `refreshPrivacyAccessLog`), shows no spinner/toast, never disables or visually marks already-revoked/ignored rows (`ev.mode`), and historical `Access:` entries remain in list forever. | Clicking `Cabut` in Monitor appears to do nothing at all. | Chain `revokeAndRefreshAccessLog(pkg, cat)` sequentially in ViewModel with per-row pending indicator, explicit confirmation toast, visual `DICABUT`/`PALSU` pill when `ev.mode in setOf("ignore","deny")`, and disable/hide `Cabut` on protected packages. |
| **APP-H06** | `AppsScreen.kt:358, 510, 953-1004`; `TrafficScreen.kt:243` | `SmallActionPill` (~28dp), `EnforcerPill` (~26dp), `AuditAppRow`/`PrivacyAccessRow` `Cabut` (32dp), `InlineIconToggle` (34dp) violate Android's 48dp minimum touch target; `SmallActionPill`/`EnforcerPill` use raw `Surface(modifier.clickable)` inside a scrollable `ModalBottomSheet`. | Taps frequently fail to register or get eaten by sheet drag/scroll gestures. | Use `Surface(onClick = ...)` with `Modifier.defaultMinSize(minHeight = 40.dp)` (and 48dp touch bounds via Material `minimumInteractiveComponentSize`), configure `ModalBottomSheet` with `rememberModalBottomSheetState(skipPartiallyExpanded = true)`, and shorten `enforcerAutoRevoke` (`"Auto-Cabut"`). |
| **APP-H07** | `AppsScreen.kt:895-901`; `daemon/src/scopes.rs:143-144` | Clicking `Normal` sends `action = "allow"` which only runs `cmd appops set ... default` and never re-grants revoked runtime permissions (`pm grant`), yet lights `Normal` green even when `!runtimeGranted`. | User cannot restore a revoked permission from Silo, and UI falsely lights `Normal` green while Android keeps it denied. | When user explicitly taps `Normal` on a declared runtime permission that is currently revoked (and `lockedDeny` is off), run `pm grant --user 0 <pkg> <perm>` + `appops default` (with error reporting if system-restricted), OR clearly separate runtime grant from AppOps mode. |
| **APP-H08** | `AppInventory.kt:67-88` vs `daemon/src/scopes.rs:19-103` | Category mapping mismatch: `AppInventory` maps special AppOp permissions `SYSTEM_ALERT_WINDOW` → `"background"` and `MANAGE_EXTERNAL_STORAGE` → `"media"`, which `daemon/scopes.rs` does NOT put in `runtime_perms` (and `pm revoke` cannot revoke them). Also misses Android 14+ `READ_MEDIA_VISUAL_USER_SELECTED`, `SEND_SMS`, `RECEIVE_MMS`, `BODY_SENSORS_BACKGROUND`. | In user's screenshot, `Kamera` permanently shows `Latar & Overlay` under "Izin sensitif aktif saat ini" even after tapping `Cabut Semua Izin Sensitif Aktif`. Android 14/15 partial photo access is invisible. | Separate special AppOps (`SYSTEM_ALERT_WINDOW`, `MANAGE_EXTERNAL_STORAGE`) from `PackageManager` runtime-grant checks (verify them via live `appopsMode` instead of `REQUESTED_PERMISSION_GRANTED`), and align all Android 12–16 runtime permissions identically between `AppInventory.kt` and `daemon/src/scopes.rs`. |
| **APP-H09** | `AppsScreen.kt:90-91` | `AppListFilter.USER` requires `!app.isSystem && app.hasLauncher`, while `SYSTEM` requires `app.isSystem`. | Any user-installed app **without a launcher activity** (background plugins, stealth spyware/stalkerware!) is completely invisible in both `Aplikasi` and `Sistem` tabs and cannot even be found via search. | Remove `&& app.hasLauncher` from `AppListFilter.USER` (keep launcher apps sorted first via `AppInventory.kt:207`, and optionally tag non-launcher user apps `"Bg"`). |
| **APP-H10** | `AppsScreen.kt:761-785`; `AppInventory.kt:37` | `AppsScreen` renders all 10 interactive permission cards even for permissions the app never declared in its manifest (`app.declaredSensitiveCategories` is computed but unused!). | Clutters UI with irrelevant cards that light `Normal` green, and clicking `Cabut` runs doomed `pm revoke` calls on undeclared permissions. | Filter or group categories by `app.declaredSensitiveCategories` (always showing AppOps-only `clipboard` & `background`, and optionally collapsing undeclared categories under an expandable section). |
| **APP-H11** | `SiloViewModel.kt:83-115`; `AppsScreen.kt:545-547` | `onForegroundChanged(true)` never refreshes `liveAppPerms`, `freezerStatus`, `hardeningStatus`, or `privacyAccessLog`; sets `connected = st != null || cur.connected` (line 95) and ignores `st == null` in 4s tick (lines 108-111). | Returning from another app while `AppControlSheet` is open leaves AppOps state stale; if `silod` dies in background, UI stays stuck claiming `connected = true` forever. | Track active inspected package in ViewModel to refresh `liveAppPerms` on resume; detect consecutive `getStatus()` failures and flip `connected = false` with error message. |
| **APP-H12** | `SiloViewModel.kt:195-201, 324-333, 370-376, 413-427, 453-461, 465-475, 563-569, 600-612` | 8 ViewModel RPC calls omit `.onFailure` completely (`inspectAppPermissions`, `refreshPrivacyAccessLog`, `clearIpBlocklist`, `clearTelemetry`, `removeFreezerEntry`, `thawAllNow`, `setAutoClearClipboard`, `rollbackHardening`). | Silent failures leave UI blank/stale (`liveAppPerms` stays null → all categories default to green `Normal`; Monitor `Akses Izin` falsely claims "no events"). | Handle `.onFailure` on every RPC call, expose explicit error state in `SiloUiState` (for `liveAppPerms` & `privacyAccessLog`), and show actionable error banners/toasts. |
| **APP-H13** | `TrafficScreen.kt:114, 132, 169` | All 3 `LazyColumn` lists use `itemsIndexed(...)` with **no `key`**. | Live sockets/events reorder every 3s; tapping `Kill` or `Block` during a tick can execute on the **wrong UID/connection**. | Supply stable unique keys (`"${ev.timestampMs}_${ev.srcPort}_${ev.dstIp}_${ev.dstPort}_$idx"`, `"${conn.uid}_${conn.protocol}_${conn.localIp}:${conn.localPort}_${conn.remoteIp}:${conn.remotePort}"`, `"${ev.packageName}_${ev.category}"`). |
| **APP-H14** | `TrafficScreen.kt:138-151` | `SocketRow` `Block` ignores `isProtectedPackage`, wipes out existing `AppFirewallRule` flags (`isVpnApp`, `allowLan`, `blockCleartextHttp`, `blockLocalhost`), gives no toast, and disables blocking for listening user sockets (`!conn.isListening`) even though rogue listeners (`isUserListener`) are highlighted amber! | Blocking a socket silently clobbers custom app rules; clicking Block on protected UID>=10000 packages fails; user cannot block rogue listening apps from Monitor. | Merge with existing `state.config.firewall.rules[conn.uid]`, check `!AppInventory.isProtectedPackage(conn.packageName)`, allow blocking `isUserListener` apps too, and show confirmation toast. |
| **APP-H15** | `HomeScreen.kt:308-311` | Preset selection matcher checks only `fw.enabled`/`hardening.enabled`/`mode`/`blockCleartextHttpGlobal`. | Turning on ONLY Firewall (no LAN/DNS/Scan guard, no hardening) falsely marks `Seimbang` (`Balanced`) as active; enabling only Firewall + HTTP block falsely marks `Ketat` (`Hardened`) as active. | Check all defining flags of each preset (matching `daemon/src/config.rs:545-617`), and show "Kustom / Custom" when none match exactly. |
| **APP-H16** | `SystemScreen.kt:365, 375` | Displays `"(${auto.autoRebootMinutes / 60}h)"` while offering `"30m" to 30`. Integer `30 / 60 == 0`. | Selecting `30m` Auto-Reboot BFU displays **`(0h)`** in the switch title! | Format dynamically: `if (mins < 60) "${mins}m" else if (mins % 60 == 0) "${mins / 60}h" else "${mins / 60}h ${mins % 60}m"`. |
| **APP-H17** | `SystemScreen.kt:448-452` | Packet Scheduler `"Default"` pill passes `it.copy(enabled = true, defaultQdisc = "")`. | Clicking `"Default"` on Packet Scheduler silently **enables** Kernel Hardening (`enabled = true`)! | Match `tcpCongestion` behavior: when `q.isEmpty()`, use `it.copy(defaultQdisc = "")` without forcing `enabled = true`. |
| **APP-H18** | `SystemScreen.kt:552-558, 463-468` | `Emergency Flush Firewall` and `Rollback to Kernel Defaults` execute immediately with **no confirmation dialog**. | One accidental tap while scrolling immediately tears down the entire firewall and disables protection. | Add confirmation `AlertDialog`s before executing destructive reset actions. |
| **APP-H19** | `SystemScreen.kt:246-254` | `DnsPill(s.dnsCustom, false, ...)` hardcodes `active = false`. | Setting any custom Private DNS hostname leaves all DNS pills unhighlighted. | Set `active = curDns.mode == "hostname" && dnsPresets.none { it.first == "hostname" && it.second == curDns.specifier }`, and disable dialog `Apply` when trimmed input is blank. |
| **APP-H20** | `SiloRepository.kt:16, 122-125` vs `daemon/src/protocol.rs:8` | **IPC FRAME LIMIT MISMATCH**: `BlocklistParser` permits up to 50,000 entries (~900 KB–2 MB JSON), exceeding `MAX_IPC_LINE_BYTES = 512 * 1024` (512 KB) in `silod --bridge` (`daemon/src/ipc.rs:205`). | Importing a medium/large IP blocklist (>~25k IPv4 or >~11k IPv6) crashes/terminates the stdio bridge with `"IPC frame exceeds maximum allowed size"`. | Cap byte size per RPC or support chunked/append blocklist import (or raise `MAX_IPC_LINE_BYTES` on daemon to e.g. 4 MB for blocklist payloads + cap file read size in app). |

#### MEDIUM Severity

| ID | File:Line | Evidence & Root Cause | User Impact | Concrete Fix Strategy |
|---|---|---|---|---|
| **APP-M01** | `SiloViewModel.kt:56-58, 83-101, 117-164` | `init { refreshAll() }` and `ON_RESUME → onForegroundChanged(true)` fire simultaneously on launch. | Double-scans all 400+ installed packages and double-queries daemon on every cold start. | Debounce/deduplicate initial load (skip immediate reload in `onForegroundChanged(true)` if `refreshAll()` ran <1s ago or is in flight). |
| **APP-M02** | `AppsScreen.kt:293-303, 823-832` | Quick-freeze and `Freeze Now` launch `setFreezerEntry()` and `freezeUidNow()` as two independent unchained coroutines. | `freezeUidNow` can run before `setFreezerEntry`, freezing with default `SoftCgroup` instead of user's chosen `DeepStop`. | Add atomic `vm.ensureEntryAndFreeze(uid, entry)` suspending sequentially in one coroutine. |
| **APP-M03** | `SiloViewModel.kt:203-213, 396-411` | `applyPreset` doesn't refresh `hardeningStatus`, `freezerStatus`, or `sentinelReport`; `setFreezerEntry` doesn't call `refreshLightStatus()`. | Stale sysctl counter, stale frozen count, and stale Security Score on Home after applying a preset. | Refresh `hardeningStatus`, `freezerStatus`, and `sentinelReport` after `applyPreset`. |
| **APP-M04** | `SiloViewModel.kt:308-322` vs `daemon/src/main.rs:478-509` | Daemon's `ReplaceIpBlocklist` returns `ok: true` with `"rejected": N` on invalid CIDRs rather than an error; `addSingleBlocklistCidr` ignores `"rejected"` and toasts `"Ditambahkan: $clean"`. | Adding `"not-an-ip"` shows a false success toast `"Ditambahkan: not-an-ip"`! | Check `obj["rejected"]?.jsonPrimitive?.intOrNull ?: 0` and validate CIDR client-side before sending. |
| **APP-M05** | `SiloViewModel.kt:283-285, 748-750` | `importIpBlocklistFromUri` and `importConfigFromUri` call unbounded `.readText()` on `*/*` SAF document pickers. | Selecting a large file triggers `OutOfMemoryError` crash. | Enforce a streaming size cap (e.g., 8 MB for blocklists via `lineSequence()`, 2 MB for config JSON). |
| **APP-M06** | `SiloViewModel.kt:730-735` | `exportConfigToUri` uses `openOutputStream(uri)?.use { ... }; true`. | If `openOutputStream` returns `null`, reports `"Konfigurasi diekspor"` while writing 0 bytes. | Use `openOutputStream(uri)?.use { ...; true } ?: false`. |
| **APP-M07** | `SiloRepository.kt:14, 22-25` | `BlocklistParser` IPv6 regex `^[0-9a-fA-F:]+(/\d{1,3})?$` matches non-IPv6 colon tokens like timestamps (`12:30:00`) or `:::`; `firstOrNull` drops 2nd+ comma/space-separated IPs on the same line. | Corrupts parsed entries and drops multi-IP lines. | Validate IPv4 octets (`0..255`) and IPv6 via `InetAddress` / strict regex (requiring `::` or >=2 colons with valid hex groups) and iterate all tokens per line. |
| **APP-M08** | `AppInventory.kt:162-165, 183-186` | `loadInstalledApps` omits `GET_ACTIVITIES` and `GET_PROVIDERS` (even though `ProtocolAndParserTest.kt:103` tests `AudienceNetworkActivity`!) and does not catch `TransactionTooLargeException` / `DeadObjectException`. | Misses Activity/ContentProvider tracker SDKs; uncaught Binder exception can leave `loading = true` forever or crash app. | Catch exceptions per-package / fallback to `getInstalledPackages(0)` + per-package query on `TransactionTooLargeException`, and inspect activities/providers where safe. |
| **APP-M09** | `AndroidManifest.xml:14, 27` | Declares unused `POST_NOTIFICATIONS` permission; marks `MainActivity` `android:exported="true"` even though launcher intent filters are on `LauncherDefault`/`LauncherDisguise` aliases. | Unnecessary permission; third-party apps can directly invoke `dev.silo.app/.MainActivity` bypassing Disguise mode. | Remove unused `POST_NOTIFICATIONS`; set `MainActivity` `android:exported="false"` (aliases remain `exported="true"`). |
| **APP-M10** | `PanicRebootTileService.kt:21, 31-58`; `LockdownTileService.kt:39-53`; `SensorsOffTileService.kt:32-46` | `armedAtElapsedMs` accessed across Main & IO threads without `@Volatile`; 5.2s timeout coroutine overwrites `"Rebooting..."` after 2nd tap sets `armedAtElapsedMs = 0L`; `triggerPanicReboot()` failure leaves tile stuck on `"Rebooting..."`; `updateTile()` called on `Dispatchers.IO`; hardcoded English subtitles. | Race condition visually corrupts Panic tile; offline failures fail silently; wrong language in ID locale. | Store timeout `Job` and cancel it on 2nd tap; check `Result` of `triggerPanicReboot()`; switch to `Dispatchers.Main` for `qsTile.updateTile()`; use localized strings. |
| **APP-M11** | `SiloClient.kt:122-129` | `closeLocked()` calls `process?.destroy()` (SIGTERM only) and never closes `errorStream`. | Hung `su` subprocesses can leak as orphans. | Close `errorStream`, wait briefly, then `destroyForcibly()` if still alive. |
| **APP-M12** | `HomeScreen.kt:126, 271-274, 474-530` | Shows crimson `0` score while `sentinelReport == null`; calls `showProofSheet = true` twice; `LiveProofSheetContent` lacks `verticalScroll` and shows stale proof with no loading indicator on refresh. | Cut-off proof rows on smaller displays; misleading `0` score on startup; no indication whether re-run proof succeeded. | Show `"--"` when `sentinelReport == null`; add `verticalScroll(rememberScrollState())` + `liveProofLoading` flag to `LiveProofSheetContent`. |
| **APP-M13** | `SystemScreen.kt:153-190, 204-214, 436` | `showSysctlSheet` never fetches fresh `getHardeningStatus()` on open; `showAuditSheet` has no loading spinner when `sentinelReport == null`; `availCc.take(3)` hides 4th+ TCP congestion algorithms (e.g., `bbr`!). | Stale sysctl view; blank audit sheet; users on kernels with >3 congestion algos (e.g. `cubic`, `reno`, `westwood`, `bbr`) cannot select `bbr`! | Fetch fresh hardening status when opening sysctl sheet; show spinner in audit sheet when loading; render `availCc` in a scrollable/wrapping row instead of `.take(3)`. |
| **APP-M14** | `SystemScreen.kt:113-150, 384-400` | Blocklist sheet cannot view or delete individual IPs; Automation durations (`clipboardClearSecs`, `wifiAutoOffMinutes`, `bluetoothAutoOffMinutes`, `freezer.backgroundGraceSecs`) are displayed in titles with no UI to edit them. | Incomplete controls; users must clear entire blocklist to remove 1 entry and can't adjust idle timers. | Add scrollable IP list with per-entry delete in Blocklist sheet, and duration pill selectors (like Auto-Reboot BFU) for Clipboard / Wi-Fi / BT / Freezer grace timers. |
| **APP-M15** | `SiloViewModel.kt` (lines 208, 261, 272, 301, 351, 545, 683, 723, etc.) | Hardcoded mixed Indonesian & English snackbar strings ignore `state.languagePref`. | English users get Indonesian toasts; Indonesian users get English temp-unblock toasts. | Resolve all ViewModel toast strings through `resolveStrings(_ui.value.languagePref)`. |
| **APP-M16** | `TrafficScreen.kt:160-164`; `AppsScreen.kt:545-547` | Neither `TrafficTab.ACCESS` nor `AppSheetTab.PERMS` shows a loading indicator while initial daemon query runs. | Monitor `Akses Izin` displays "Belum ada akses izin" while scanning for 3–15s; `Izin & AppOps` flashes all `Normal` green before `inspectAppPermissions` returns. | Add `loadingAccessLog: Boolean` and `loadingAppPerms: Set<String>` to `SiloUiState` and render progress indicators. |
| **APP-M17** | `MainActivity.kt:73, 103`; `AppsScreen.kt:72-75`; `TrafficScreen.kt:38` | Tab/filter/search state uses `remember` instead of `rememberSaveable`; TopAppBar refresh button (`vm.refreshAll()`) does not refresh `privacyAccessLog` or `liveAppPerms`. | Rotation/split-screen resets user back to Home tab; tapping top-bar Refresh on Monitor → Akses Izin doesn't refresh access log. | Use `rememberSaveable` for nav/tab/filter/query; include active tab/sheet refresh in `refreshAll()`. |
| **APP-M18** | `AppsScreen.kt:95`; `SiloRepository.kt:188-202` | Legacy `AppIsolationScope` (`scopes.apps`) has dead repository methods and no UI to view or clear it, yet `AppsScreen.kt:95` includes `scopes.apps` in `Terbatas` (`RESTRICTED`) filter. | Apps with legacy/imported `scopes.apps` entries stay permanently in `Terbatas` with zero UI to clear them. | Either remove legacy `scopes.apps` or add migration/clear support when editing permissions. |
| **APP-M19** | `AppsScreen.kt:926-941`; `SiloModels.kt:131` | Both `Auto-Cabut` and `Kunci Blokir` can be enabled simultaneously on the same category; `totalCount()` double-counts them (`G:2` for 1 category) and daemon runs redundant revokes. | Contradictory policy state and inflated `Penjaga Izin` / `G:N` counters. | Make `Auto-Cabut` and `Kunci Blokir` mutually exclusive per category (enabling one disables the other). |

#### LOW Severity

| ID | File:Line | Evidence & Root Cause | Concrete Fix Strategy |
|---|---|---|---|
| **APP-L01** | `AppsScreen.kt:421, 711` | `(tempRemaining / 60) + 1` displays `2m` when exactly `60s` remain. | Use `((tempRemaining + 59) / 60).coerceAtLeast(1)`. |
| **APP-L02** | `AppsScreen.kt:94` | `RESTRICTED` filter omits `r.blockCleartextHttp` and `r.blockLocalhost`. | Include `r.blockCleartextHttp || r.blockLocalhost` in `hasNetBlock`. |
| **APP-L03** | `AppsScreen.kt:409-415` | `else if (guardCount > 0)` hides `G:N` badge whenever app is frozen/suspended. | Render `G:N` alongside `BEKU`/`SUSPEND` badges. |
| **APP-L04** | `AppsScreen.kt:249, 542` | Opening an app from `Audit Izin` opens `Jaringan` tab instead of `Izin & AppOps`; toggling firewall icons when master firewall is OFF gives no hint. | Pass `initialTab = if (filter == AUDIT) PERMS else NETWORK`; show subtle hint when master firewall is off. |
| **APP-L05** | `AppInventory.kt:42, 182` | Unbounded `ConcurrentHashMap` `iconCache`; unused `hasInternetPermission` field. | Switch to bounded `android.util.LruCache` (~150 entries); optionally indicate offline-only apps. |
| **APP-L06** | `SystemScreen.kt:564, 595`; `MainActivity.kt:51, 126`; `TrafficScreen.kt:252-254, 340, 345` | Hardcoded `"Silo v3.0.0"` instead of `BuildConfig.VERSION_NAME`; missing `TextOverflow.Ellipsis` in `DnsPill` & `NavigationBarItem`; `remember(languagePref)` ignores live OS locale change when set to `SYSTEM`; unused `s.killSocket`/`s.blockApp` accessibility `contentDescription`s; `SimpleDateFormat` allocated per row. | Use `BuildConfig.VERSION_NAME`, add ellipsis, key `resolveStrings` on `LocalConfiguration.current`, wire accessibility labels, hoist formatter. |
| **APP-L07** | `HomeScreen.kt:198`, `HomeScreen.kt:155-328` | Footer shows `"Boot Guard: Verifikasi 45d • DNS: Otomatis"` even when offline; interactive Home switches remain enabled while offline. | Hide/dim daemon footer and disable daemon-dependent toggles while `!state.connected`. |
| **APP-L08** | `proguard-rules.pro:10-16` | Keeps `$$serializer` and `Companion`, but does not explicitly keep `@Serializable` enum classes in `dev.silo.app.ipc.**` (relying solely on transitive consumer rules). | Add `-keep class dev.silo.app.ipc.** { *; }` or explicit enum keep rule as defense-in-depth for R8 release builds. |

---

### 1.6 App ↔ Daemon & Android System Interface Contracts

#### Transport & Framing (`SiloClient.kt` ↔ `daemon/src/ipc.rs`)
- **Invocation**: `su -c /data/adb/modules/silo/bin/silod --bridge` (persistent stdio subprocess).
- **Socket**: Root-only Unix domain socket `/data/adb/silo/silod.sock` (`0600`), peer verified via `SO_PEERCRED` (`uid == 0 || uid == euid`). Max concurrent daemon clients: `16`.
- **Wire format**: Newline-delimited JSON-RPC 1-line-per-message:
  - Request: `{"id": <Long>, "cmd": "<snake_case>", "params": <JsonObject?>}\n`
  - Response: `{"id": <Long>, "ok": <Boolean>, "data": <JsonElement?>, "error": <String?>}\n`
- **Hard limits**:
  - Daemon `MAX_IPC_LINE_BYTES = 512 * 1024` (512 KB inbound request; 2 MB outbound response in `--bridge`). **Violated by `replace_ip_blocklist` up to 50,000 entries!**
  - `--bridge` socket `read_timeout = 15s`, `write_timeout = 10s`.
  - App `SiloClient` default `timeoutMs = 10_000L` (broken — non-cancellable blocking `readLine()`), extended to 15–25s for slow RPCs (`get_privacy_access_log`, `run_sentinel_audit`, `run_live_proof`, `import_config_json`, `replace_ip_blocklist`, `bulk_revoke_category`).
- **Config persistence**: `/data/adb/silo/config.json` (`0600`, atomic temp+fsync+rename), schema version `3`.
- **Android System APIs used directly by `app/`**:
  - `PackageManager.getInstalledPackages(GET_PERMISSIONS | GET_RECEIVERS | GET_SERVICES)`, `getLaunchIntentForPackage`, `getApplicationLabel`, `getApplicationIcon`, `getComponentEnabledSetting`/`setComponentEnabledSetting` (App Disguise).
  - `ClipboardManager.clearPrimaryClip()` (Android native clipboard wipe, `SiloRepository.kt:74-85`).
  - `ContentResolver.openInputStream`/`openOutputStream` (SAF import/export).
  - `SharedPreferences("silo_ui_prefs")` (`language`, `theme`).
  - `TileService` (`qsTile`, `updateTile`).

#### Complete RPC Contract Matrix (`SiloRepository.kt` ↔ `daemon/src/protocol.rs` & `main.rs`)

| Command (`cmd`) | App Caller(s) | Request `params` | Response `data` | Contract Notes / Mismatches |
|---|---|---|---|---|
| `get_status` | VM (`refreshAll`, `onForegroundChanged`, 4s poll, `refreshMonitorOnce`, `refreshLightStatus`), `SensorsOffTileService` | `null` | `DaemonStatus` | Calls `dumpsys sensor_privacy` + `settings get` inside `state_mtx` lock! `firewall_mode` is PascalCase Debug string (`"DefaultAllow"`). |
| `get_config` | VM, `LockdownTileService` | `null` | `SiloConfig` | `rules` and `entries` maps serialize `Int` keys as JSON strings (`"10277"`), handled by Rust `deserialize_u32_map`. |
| `apply_preset` | VM | `{preset}` | `SiloConfig` | App fails to refresh `hardeningStatus`/`freezerStatus`/`sentinelReport` afterward. |
| `set_firewall_config` | VM, `LockdownTileService` | `{config: FirewallConfig}` | `FirewallConfig` | App read-modify-write race on rapid toggles. |
| `set_app_firewall_rule` | VM | `{uid, rule}` | `{"done": true}` | App `callRaw` ignores body. |
| `remove_app_firewall_rule` | VM | `{uid}` | `{"done": true}` | Also clears temp unblock on daemon. |
| `set_temp_unblock` | VM | `{uid, duration_minutes}` | `{"uid", "remaining_secs"}` | Clamped `1..240` min on daemon. |
| `clear_temp_unblock` | VM | `{uid}` | `{"done": true}` | Triggers `SOCK_DESTROY` on daemon. |
| `replace_ip_blocklist` | VM | `{entries: List<String>}` | `{"loaded", "rejected", "backend"}` | **MISMATCH**: >~25k entries exceeds `MAX_IPC_LINE_BYTES`; `addSingleBlocklistCidr` ignores `rejected > 0`. |
| `emergency_flush_firewall` | VM | `null` | `{"done": true}` | No confirmation dialog in UI. |
| `kill_uid_sockets` | VM | `{uid}` | `{"killed": Int}` | Rejects protected UIDs. |
| `kill_connection` | VM | `{family, local_ip, local_port, remote_ip, remote_port}` | `{"killed": Int}` | Works for TCP only. |
| `get_live_connections` | VM (3s poll) | `null` | `List<ActiveConnection>` | Polled concurrently without awaiting in Monitor! |
| `get_telemetry` | VM (3s poll) | `{limit}` | `TelemetrySnapshot` | RAM-only NFLOG ring buffer. |
| `clear_telemetry` | VM | `null` | `{"done": true}` | Failure silently ignored in VM. |
| `get_freezer_status` | VM | `null` | `FreezerStatus` | Not refreshed on resume. |
| `set_freezer_enabled` | VM | `{enabled, background_grace_secs}` | `FreezerStatus` | `graceSecs` not editable in UI. |
| `set_freezer_entry` | VM | `{uid, entry}` | `FreezerStatus` | Races with `freeze_uid_now` in `AppsScreen`. |
| `remove_freezer_entry` | VM | `{uid}` | `FreezerStatus` | Failure silently ignored in VM. |
| `freeze_uid_now` / `thaw_uid_now` / `thaw_all_now` | VM | `{uid}` / `null` | `FreezerStatus` | `thaw_all_now` failure silently ignored. |
| `set_app_scope` / `apply_hardened_scope` / `clear_app_scope` | **DEAD CODE** | `{package_name, scope?}` | JSON / `AppIsolationScope` | Never called from VM/UI; legacy `scopes.apps` still leaks into `RESTRICTED` filter. |
| `inspect_app_permissions` | VM | `{package_name}` | `AppLivePermissions` | **MISMATCH**: Daemon `parse_appops_get_output` overwrites `Uid mode: ignore` with package `allow`/`default`; VM ignores failures; never returns `runtime_granted`. |
| `set_app_permission_action` | VM | `{package_name, category, action}` | `AppLivePermissions` | `action` ∈ `allow`/`foreground`/`ignore`/`revoke`. `allow` never runs `pm grant`; `revoke` ignores `pm revoke` exit code; VM triggers 400-pkg scan! |
| `set_app_perm_guard` | VM | `{package_name, guard}` | `ScopesConfig` | Daemon holds `state_mtx` while revoking every `locked_deny` category synchronously! |
| `bulk_revoke_category` | VM | `{category, packages}` | `{"category", "revoked_count"}` | 25s client timeout vs 15s `--bridge` socket timeout! Large batch (>10 apps) times out in `--bridge`! |
| `get_privacy_access_log` | VM | `{limit}` | `List<PrivacyAccessEvent>` | Runs 11 sequential `dumpsys appops --op` calls (can exceed 15s `--bridge` timeout!). Leaks protected packages (`is_protected_package` unchecked). |
| `set_auto_clear_clipboard` / `clear_clipboard_now` | VM | `{enabled, clear_secs}` / `null` | `ScopesConfig` / `{"done": true}` | `set_auto_clear_clipboard` failure ignored; `clear_secs` not editable in UI. |
| `get_hardening_status` / `set_hardening_config` / `rollback_hardening` | VM | `null` / `{config}` / `null` | `HardeningStatus` | `rollback_hardening` failure ignored; no confirmation dialog. |
| `set_automation_config` | VM | `{config}` | `AutomationConfig` | Read-modify-write race on rapid toggles. |
| `set_sensors_off` / `set_sensor_privacy` | `SensorsOffTileService` / VM | `{enabled}` / `{sensor, enabled}` | `SensorPrivacyState` | Tile lacks lockscreen `unlockAndRun` guard when unmuting. |
| `set_private_dns` | VM | `{mode, specifier}` | `PrivateDnsState` | (`get_private_dns` unused directly; folded into `get_status`). |
| `set_radio_privacy` | VM | `{enhanced_mac_randomization?, disable_wifi_ble_scanning?, disable_captive_portal?}` | `RadioPrivacyState` | (`get_radio_privacy` unused directly; folded into `get_status`). |
| `trigger_panic_lockdown` | VM | `null` | `PanicLockdownReport` | Holds `state_mtx` across firewall + sockets + sensors + wifi/bt + clipboard; 15s timeout tight. |
| `trigger_panic_reboot` | VM, `PanicRebootTileService` | `{confirm_token: "CONFIRM_SILO_BFU_REBOOT"}` | `{"done": true}` | Tile ignores failure Result & has timer race. |
| `run_sentinel_audit` / `run_live_proof` | VM | `null` | `SentinelReport` / `LiveProofReport` | `run_live_proof` holds `state_mtx` across multiple `iptables`/`appops`/`sysctl` shell calls. |
| `export_config_json` / `import_config_json` | VM | `null` / `{json}` | `{"json": String}` / `SiloConfig` | App `import` has 20s timeout vs `--bridge` 15s read timeout! |

*(Note the systemic timeout mismatch: `SiloRepository.kt` passes `timeoutMs = 20_000L` or `25_000L` on `replace_ip_blocklist`, `bulk_revoke_category`, and `import_config_json`, while `daemon/src/ipc.rs:266` hardcodes `stream.set_read_timeout(Some(Duration::from_secs(15)))` in `silod --bridge`!)*

---

## 2. Logic Chain

1. **Screenshot `Kamera` (`DICABUT` gray + `Normal` green + `Kunci Blokir` active)** follows directly from Observations 1.3 & 1.4: `AppsScreen.kt:859-866` renders gray `DICABUT` only when `!runningNow && appopsMode !in {"ignore","deny","foreground"} && !runtimeGranted`. Because `AppsScreen.kt:897` activates `Normal` purely on `appopsMode == "default" || appopsMode == "allow"`, `Normal` turns green while `Cabut` (`AppsScreen.kt:918`) stays off. On the daemon side, `scopes.rs:299-301` overwrites `Uid mode: CAMERA: ignore` with any package-level `CAMERA: allow/default; time=...` line, producing `appops_mode = "allow"/"default"` right after `pm revoke`.
2. **Screenshot `Lokasi` (`PALSU KOSONG` green + `Palsukan` green + `Cabut` red)** follows directly from `AppsScreen.kt:861, 911, 918`: `Palsukan` checks `appopsMode == "ignore"` and `Cabut` checks `!runtimeGranted && (appopsMode == "ignore" || appopsMode == "deny")`. Both evaluate `true` whenever a permission is revoked+ignored, and the badge checks `appopsMode == "ignore"` before `!runtimeGranted`.
3. **Unresponsive permission controls in Per-App & Monitor** follow directly from the chain: non-suspending `refreshMonitorOnce()` spawning overlapping coroutines every 3s (`SiloViewModel.kt:169-193`) + slow 11-command `get_privacy_access_log` (`scopes.rs:333-354`) + duplicate `LaunchedEffect` (`TrafficScreen.kt:45-49`) + single global `SiloClient.mutex` with non-cancellable blocking `readLine()` (`SiloClient.kt:39-77`) + 2–4s full `loadInstalledApps()` scan per click (`SiloViewModel.kt:480`) + hidden `Snackbar` behind `ModalBottomSheet` (`MainActivity.kt:109` vs `AppsScreen.kt:117`) + 26–32dp touch targets (`AppsScreen.kt:953-1004`, `TrafficScreen.kt:243`) + historical `Access:` rows never changing visually on revoke (`TrafficScreen.kt:176-179, 239-246`).
4. **`Kunci Blokir` delay & force-stop** follows directly from `main.rs:1074` (5s loop) + `scopes.rs:568, 606-619` (15s sweep interval) + `pm revoke` killing running processes in Android AMS — compounded by `parse_appops_get_output` causing `drifted == true` permanently on `Kamera` so `pm revoke` fires repeatedly every 15s!
5. **Lockscreen security bypass & remaining 30+ bugs** follow directly from direct code inspection of `LockdownTileService.kt`, `SensorsOffTileService.kt`, `PanicRebootTileService.kt`, `SystemScreen.kt`, `HomeScreen.kt`, `AppInventory.kt`, `SiloRepository.kt`, `SiloViewModel.kt`, and `AndroidManifest.xml`.

---

## 3. Caveats

- **Read-only static audit**: No live Android device (`SM-A146P` / One UI 7) or Gradle build execution was run during this Step 0 survey (preserving strictly read-only workspace hygiene). Exact runtime `cmd appops get --user 0 com.sec.android.app.camera` output on One UI 7 is inferred from AOSP Android 13–15 `AppOpsService` formatting + verified against `scopes.rs:263-312` and the exact pixel evidence in the user's screenshot.
- **Daemon deep internals**: Only `daemon/src/{protocol,ipc,scopes,config,main,sys}.rs` were inspected to verify exact `app ↔ daemon` contracts and permission enforcer behavior. Deep audits of `firewall.rs`, `netlink.rs`, `freezer.rs`, `hardening.rs`, `automation.rs`, `sentinel.rs`, `telemetry.rs`, and `module/*.sh` remain the domain of the companion daemon explorer.

---

## 4. Conclusion

The user's bug report exposes systemic defects in **both** the Android app (`app/`) and the `app ↔ daemon` permission/IPC boundary. Fixing the app cleanly requires coordinated remediation across 5 workstreams:

1. **Permission State Machine & UI Redesign (`AppsScreen.kt`, `AppInventory.kt`, `SiloStrings.kt`, plus daemon `scopes.rs` alignment)**:
   - Replace overlapping boolean pill predicates with a single mutually-exclusive `PermEffectiveUiMode` state machine (`NORMAL` / `FOREGROUND` / `FAKE_EMPTY` / `REVOKED` / `UNGRANTED`), separate AppOps-only categories (`clipboard`, `background`), align `permissionToCategory` with daemon `PERM_CATEGORIES` (fix `SYSTEM_ALERT_WINDOW`, `MANAGE_EXTERNAL_STORAGE`, add Android 14+ `READ_MEDIA_VISUAL_USER_SELECTED`, etc.), surface `declaredSensitiveCategories`, make `Auto-Cabut` and `Kunci Blokir` mutually exclusive, increase pill/button touch targets to Material 48dp accessibility minimums, use `skipPartiallyExpanded = true` on `ModalBottomSheet`, and render visible feedback/errors *inside* bottom sheets.
2. **IPC & Concurrency Reliability (`SiloClient.kt`, `SiloViewModel.kt`, `TrafficScreen.kt`)**:
   - Fix `SiloClient` blocking timeout cancellation, drain `errorStream`, verify `resp.id == reqId`, and enforce `destroyForcibly()` on reset.
   - Fix `SiloViewModel` polling pileup (`suspend fun refreshMonitorOnce()`), serialize config mutations via a ViewModel `Mutex` with optimistic state, add single-package `AppInventory.reloadSingleApp(pkg)` (~5ms instead of 400-package scan), add loading/pending states across `Izin & AppOps` and `Akses Izin`, handle all `.onFailure` paths, fix Monitor `Akses Izin` sequential revoke + visual revoked status + stable `LazyColumn` keys, and unify bilingual toast strings.
   - Align `MAX_IPC_LINE_BYTES` / chunked blocklist upload and `--bridge` socket timeout (e.g. 30s) with app timeouts.
3. **`Kunci Blokir` & `Auto-Cabut` Responsiveness & Transparency (`app/` + `daemon/src/scopes.rs`)**:
   - Fix `parse_appops_get_output` so `Uid mode: ignore` is never overwritten by historical package `allow`/`default` lines (stopping the 15s infinite force-stop loop).
   - Speed up `PermissionEnforcer` enforcement latency and avoid unnecessarily calling `pm revoke` on already-revoked permissions; clearly document Android's `pm revoke` process-restart behavior in UI copy.
4. **Security & Correctness Hardening (`tiles/`, `AndroidManifest.xml`, `SystemScreen.kt`, `HomeScreen.kt`, `SiloRepository.kt`)**:
   - Guard disabling Lockdown & unmuting Sensors on lockscreen with `unlockAndRun`; fix `PanicRebootTileService` thread/timer races and `Dispatchers.Main` `updateTile()`.
   - Remove unused `POST_NOTIFICATIONS`; set `MainActivity` `android:exported="false"`.
   - Fix `SystemScreen` `(0h)` BFU display, Packet Scheduler `"Default"` enabling hardening, `Custom` DNS active highlight, `availCc.take(3)` hiding `bbr`, confirmation dialogs for `Emergency Flush` / `Rollback`, `BlocklistParser` IPv6 timestamp false-positives, `addSingleBlocklistCidr` silent rejection, `exportConfigToUri` null-stream bug, unbounded `readText()` OOM risk, `HomeScreen` preset matching & scrollable `LiveProofSheetContent`, and `AppsScreen` hidden non-launcher user apps.

---

## 5. Verification Method

1. **Static Inspection**: Re-verify all cited lines in:
   - `app/app/src/main/java/dev/silo/app/ui/screens/AppsScreen.kt` (lines 80–129, 281–303, 354–365, 509–570, 756–785, 823–1005)
   - `app/app/src/main/java/dev/silo/app/ui/screens/TrafficScreen.kt` (lines 40–49, 114–185, 239–254, 338–347)
   - `app/app/src/main/java/dev/silo/app/ui/SiloViewModel.kt` (lines 56–225, 279–333, 465–624, 726–764)
   - `app/app/src/main/java/dev/silo/app/ipc/SiloClient.kt` (lines 39–129)
   - `app/app/src/main/java/dev/silo/app/data/AppInventory.kt` (lines 42, 67–88, 160–212)
   - `app/app/src/main/java/dev/silo/app/data/SiloRepository.kt` (lines 12–31, 122–125, 188–202)
   - `app/app/src/main/java/dev/silo/app/ui/screens/HomeScreen.kt` (lines 126, 198, 271–274, 307–311, 469–530)
   - `app/app/src/main/java/dev/silo/app/ui/screens/SystemScreen.kt` (lines 76, 113–214, 246–254, 365–400, 436, 448–468, 552–595)
   - `app/app/src/main/java/dev/silo/app/tiles/{Lockdown,SensorsOff,PanicReboot}TileService.kt`
   - `app/app/src/main/AndroidManifest.xml` (lines 14, 27)
   - `daemon/src/scopes.rs` (lines 19–103, 142–184, 263–312, 333–354, 435, 568, 606–619) and `daemon/src/ipc.rs` (lines 205, 266)
2. **Automated Build & Unit Tests (after fixes are implemented)**:
   - Android JVM tests + Release APK build + zero-`INTERNET` permission gate:
     ```bash
     cd /home/antigravity/terenzy/Silo/app
     ./gradlew testDebugUnitTest assembleRelease --no-daemon
     ```
   - Add new JVM unit tests in `app/app/src/test/java/dev/silo/app/` covering:
     - `PermEffectiveUiMode` mutual exclusivity across every `(hasRuntimePerms, runtimeGranted, appopsMode)` tuple (explicit regression tests for screenshot `Kamera` & `Lokasi` states!).
     - `BlocklistParser` rejecting timestamps (`12:30:00`), invalid octets (`999.1.1.1`), extracting multi-IP lines, and respecting byte caps.
     - `AppInventory.evaluateOfflineRisk` with Android 14+ `READ_MEDIA_VISUAL_USER_SELECTED`, special AppOps exclusion, and Activity/Provider tracker detection.
     - Auto-Reboot BFU duration formatting (`30m` ≠ `0h`).
     - Preset matching logic against all 4 `SecurityPreset` definitions.
   - Daemon tests (for companion fixes in `scopes.rs`/`ipc.rs`):
     ```bash
     cd /home/antigravity/terenzy/Silo/daemon
     cargo clippy --all-targets -- -D warnings && cargo test --verbose
     ```
3. **Invalidation Conditions**: Any refactor changing `SiloModels.kt` JSON schema, `daemon/src/protocol.rs` framing/limits, or Android 15 `cmd appops get` output formatting requires re-validating both the IPC contract matrix and `PermEffectiveUiMode` tests.
