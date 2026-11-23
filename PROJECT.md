# Project: Silo v3.0.0 — Full Codebase Deep Audit & Bug/Security Remediation

## Architecture
Silo v3.0.0 is an offline-first Android privacy & firewall suite targeting Samsung Galaxy A14 5G (`SM-A146P`, One UI 7 / Android 15 API 35, Linux 6.6.127, KernelSU-Next + SUSFS):
- **Android App (`app/`)**: Unprivileged Kotlin + Jetpack Compose Material 3 APK (`dev.silo.app`, `minSdk=31`, `compileSdk/targetSdk=35`, ZERO `INTERNET` permission enforced by manifest `tools:node="remove"` and `aapt2` CI gate). Communicates with root daemon over a persistent `su -c "/data/adb/modules/silo/bin/silod --bridge"` newline-delimited JSON-RPC pipe.
- **Native Root Daemon (`daemon/`)**: Static ARM64 musl Rust binary (`silod`, edition 2021, deps `libc`/`serde`/`serde_json`). Exposes root-only Unix domain socket `/data/adb/silo/silod.sock` (`0600`, `SO_PEERCRED` `uid == 0 || uid == euid`, `flock` singleton `/data/adb/silo/silod.lock`).
  - **Core Lock & Concurrency Discipline**: `state_mtx` MUST NEVER be held across external subprocesses (`run_cmd` / `cmd` / `pm` / `dumpsys` / `settings` / `iptables-restore`). System status snapshots (`sensor_privacy`, `private_dns`, `radio_privacy`) live in a dedicated `Arc<RwLock<CachedSystemState>>` refreshed asynchronously so `GetStatus` executes in <1ms with zero subprocesses. `uid_to_pkgs: Arc<RwLock<UidPackageIndex>>` serves NFLOG and protected-package lookups without locking `state_mtx`. `PermissionEnforcer` runs on a dedicated event-driven (`inotify` + 1s active-guard) thread.
- **KernelSU Zero-Mount Module & Packaging (`module/`, `scripts/`, `.github/workflows/build.yml`)**: Zero `/system`, `/vendor`, `/product`, `/system_ext`, `/odm` mounts. Boot service (`service.sh`), installer (`customize.sh`), KSU Action recovery (`action.sh`), uninstaller (`uninstall.sh`), and module packager (`scripts/build_module.sh`).
- **E2E Testing Suite (`tests/e2e/`)**: Opaque-box, requirement-driven 4-tier test suite (Tier 1 Feature Coverage, Tier 2 Boundary/Corner, Tier 3 Pairwise Cross-Feature, Tier 4 Real-World Workloads) executing against real compiled `silod` binaries, real Android JVM/APK builds (`./gradlew`, `aapt2`), real shell module scripts in isolated sandboxes, and real module packaging (`build_module.sh`).

### Build & Test Toolchain (Verified on Devbox)
- **Rust**: `rustc`/`cargo` 1.100.0 (`/opt/cargo/bin`), `clippy`, `rustfmt`, bundled `rust-lld` (`/opt/rustup` writable; install `aarch64-unknown-linux-musl` target via `rustup target add aarch64-unknown-linux-musl` if available).
  - Canonical Daemon Commands:
    - `cd /home/antigravity/terenzy/Silo/daemon && cargo clippy --all-targets -- -D warnings && cargo test --verbose`
    - Cross-compile (when musl target installed): `cd /home/antigravity/terenzy/Silo/daemon && cargo build --release --target aarch64-unknown-linux-musl`
- **Android / Gradle**: JDK 21 (`/usr/lib/jvm/java-21-openjdk-amd64`), `ANDROID_HOME=/opt/android-sdk` (platforms 34/35/36, build-tools 35.0.0 with `aapt2`), Gradle wrapper `app/gradlew` (8.10.2).
  - Canonical App Commands:
    - `cd /home/antigravity/terenzy/Silo/app && ./gradlew testDebugUnitTest assembleRelease --no-daemon`
    - Zero-INTERNET gate: `/opt/android-sdk/build-tools/35.0.0/aapt2 dump permissions app/build/outputs/apk/release/app-release.apk` (must NOT contain `android.permission.INTERNET`)
- **Module & Scripts**: `sh -n module/*.sh`, `bash -n scripts/build_module.sh`, `./scripts/build_module.sh <silod_bin> <out_zip>` + zero-mount check (`system|vendor|product|system_ext|odm`).

---

## Code Layout & Exclusive File Ownership

| Path / Directory | Owner Milestone | Purpose |
|---|---|---|
| `daemon/src/{config,protocol,ipc,sys,scopes,main}.rs`, `daemon/Cargo.toml` | **M1** (`daemon-core`) | Core daemon config, IPC protocol/bridge/flock, process execution, permission/AppOps/enforcer/clipboard, decoupled `state_mtx` & `main.rs` |
| `daemon/src/{firewall,netlink,freezer,automation,hardening,sentinel,telemetry}.rs` (+ wiring in `daemon/src/main.rs` after M1 done) | **M2** (`daemon-subsystems`) | Netfilter firewall, Netlink ipset/NFLOG/SOCK_DESTROY, cgroup/suspend freezer, automation/sensors/radio, sysctl hardening, sentinel/proof |
| `app/**` (Kotlin sources, tests, Manifest, ProGuard, Gradle, resources) | **M3** (`android-app`) | Android Compose UI, ViewModel, Repository, IPC bridge client, AppInventory, QS Tiles, unit tests |
| `module/**`, `scripts/**`, `.github/workflows/build.yml` | **M4** (`module-scripts-ci`) | KernelSU module scripts, packaging script, GitHub Actions CI |
| `tests/e2e/**`, `TEST_INFRA.md`, `TEST_READY.md` | **E2E Testing Track** | Independent opaque-box E2E test harness & Tiers 1–4 test suites |
| Whole repository (after M1–M4 & `TEST_READY.md`) | **M5** (`final-milestone`) | Phase 1 Tiers 1–4 E2E integration pass & Phase 2 Tier 5 adversarial coverage hardening |

*(Strict rule: `.agents/teamwork/` holds ONLY agent coordination metadata — NEVER source code, tests, or data.)*

---

## Feature Inventory
Every feature discovered in Step 0 Survey (100% assigned — zero unassigned features; all 111 cataloged survey findings `APP-C01..L08`, `D-01..D-28`, `CB-01..CB-30` mapped):

| # | Feature | Description & Key Defects to Remediate | Milestone | Source |
|---|---------|----------------------------------------|-----------|--------|
| F1 | Per-App Permission & AppOps Control | 10 permission categories (`camera`, `microphone`, `location`, `background_location`, `contacts`, `media`, `sms_calllog`, `clipboard`, `sensors`, `background`), 4 actions (`allow`, `foreground`, `ignore`, `revoke`), bulk revoke. Fix `parse_appops_get_output` UID-vs-pkg overwrite (`D-01`/`CB-01`), `--uid` AppOps + `revoked-compat` + `pm revoke`/`pm grant` & stdout/stderr error handling (`D-02`/`CB-02`), fail-closed multi-op aggregation (`D-03`), `runtime_granted`/`system_fixed` reporting, Android 14+ `READ_MEDIA_VISUAL_USER_SELECTED`, special AppOps (`SYSTEM_ALERT_WINDOW`/`MANAGE_EXTERNAL_STORAGE`) separation (`APP-H08`/`CB-09`), mutually exclusive UI `PermEffectiveUiMode` (`APP-C01`/`APP-C02`), `declaredSensitiveCategories` filtering (`APP-H10`), disable unsupported `foreground` pills. | M1, M3 | Survey (`explorer_survey_1,2,3`) |
| F2 | Permission Enforcer (`Kunci Blokir` & `Auto-Cabut`) | Proactive `user-fixed` permission flags on `locked_deny` (cleared on unlock/`allow`), dedicated event-driven `inotify` + fast foreground watcher (<1–2s instead of 15–20s), startup enforcement of `locked_deny`, stop false-positive infinite 15s force-stop loops (`D-05`/`CB-03`), clear/guard `locked_deny` conflict on manual action, fix `Auto-Cabut` shared-UID (`uid_to_pkgs`) and top-activity vs FGS (`oom_score_adj`) detection (`D-18`), mutually exclusive `Auto-Cabut`/`Kunci Blokir` UI toggles + honest Android `pm revoke` process-restart copy (`APP-M19`). | M1, M3 | Survey (`explorer_survey_1,2,3`) |
| F3 | Real-Time Privacy Monitor & Live Traffic | `Monitor -> Akses Izin` exact op-name token boundary, `(allow/ignore)` strict mode parsing, scanning all `Access:` lines for freshest/`(running)` entry, filtering `is_protected_package`, bounded concurrent `dumpsys` queries (`D-11`/`CB-05`). Sequential revoke+refresh + pending spinner + toast + revoked badge in `TrafficScreen` (`APP-H05`), stable `LazyColumn` keys (`APP-H13`), `SocketRow` protected checks & rule-preserving Block + `isUserListener` blocking (`APP-H14`), non-overlapping `suspend fun refreshMonitorOnce()` (`APP-C04`). | M1, M2, M3 | Survey (`explorer_survey_1,2,3`) |
| F4 | App ↔ Daemon IPC Bridge, Concurrency & UI Responsiveness | Decouple `state_mtx` from all blocking subprocesses, `Arc<RwLock<CachedSystemState>>` for <1ms `GetStatus`, `Arc<RwLock<UidPackageIndex>>` (`D-04`/`CB-04`). Raise `MAX_IPC_LINE_BYTES` to 4 MiB (`APP-H20`), raise `--bridge` read timeout to 45s + auto-reconnect/retry once (`CB-04`/`CB-23`/`D-24`). `SiloClient` cancellable timeout + stderr drain thread + `resp.id == reqId` verification + `destroyForcibly()` (`APP-C03`/`APP-H02`/`APP-H03`/`APP-M11`). ViewModel mutation `Mutex` + optimistic updates (`APP-C05`/`CB-11`), single-app `AppInventory.reloadSingleApp(pkg)` (~5ms) (`APP-H04`), visible inline feedback/Snackbar inside `ModalBottomSheet` (`APP-C06`), >=40–48dp pill touch targets & `skipPartiallyExpanded=true` (`APP-H06`), complete `.onFailure` & loading/reconnect state handling (`APP-H11`/`APP-H12`/`APP-M01`/`APP-M16`/`APP-M17`). | M1, M3 | Survey (`explorer_survey_1,2,3`) |
| F5 | Kernel Firewall, Panic Lockdown & IP Blocklist | True `panic_lockdown_active` blocking ALL non-protected UIDs even with prior `DefaultAllow` rules (`D-06`). Dynamic multi-user UID ranges covering Work Profile (u10+), Dual Messenger (u95), Secure Folder (u150) & app IDs 10000–89999 (`D-08`/`CB-08`). Add `xfrm+`, `ap+`, `v4-pdp+`, `wwan+`, `seth_lte+`, `eth+`/`rndis+`/`ncm+` (`D-09`). Separate IPv4/IPv6 capabilities & keep `ipset` on non-ipset errors (`D-14`/`CB-16`). `ipset` fixed `maxelem=65535` + batched adds (`D-15`). `SILO_IN` blocklist before `ESTABLISHED,RELATED` + fallback `SILO_BLK_IP_IN`; scope `is_vpn_app` exemption (`D-16`). `SOCK_DESTROY` on active-interface denies & `DefaultDeny` switch + reused Netlink FD + `checked_shl` (`D-19`), `NLMSG_ERROR` break in `classify_active_interfaces` (`CB-24`), NFLOG ACK validation & IPv6 ext-header walk (`D-25`). Strict `BlocklistParser` IPv4/IPv6 validation, multi-IP lines, `rejected > 0` check, per-IP removal UI, streaming SAF cap (`APP-M04`/`APP-M05`/`APP-M07`/`APP-M14`). | M2, M3 | Survey (`explorer_survey_1,2,3`) |
| F6 | App Freezer (`SoftCgroup` & `DeepStop`) | Verify cgroup path contains exact `uid_<uid>` segment before writing `cgroup.freeze` (`D-12`). Recover/repopulate `suspended_packages` from live Android state on startup and in `thaw_all()` (`D-12`/`CB-14`). Seed `last_fg_seen` on insert & re-freeze newly spawned PIDs under `uid_<uid>`. Multi-user `--user <uid/100000>` support (`CB-08`). Atomic sequential `ensureEntryAndFreeze` in `SiloViewModel`/`AppsScreen` (`APP-M02`/`CB-11`). | M2, M3 | Survey (`explorer_survey_1,2,3`) |
| F7 | Privacy Automation, Sensors, Clipboard, Radio & DNS | Preserve `device_locked` across screen-wake/AOD ticks + Samsung AOD-aware `detect_screen_on()` (`D-07`/`CB-06`). Fail-safe `is_ce_unlocked()` without `/data/user/0/android` `.exists()` fallback (`D-20`). Fix `ClipboardGuard` `label=null` false-empty bug & Android 12–16 clipboard clear compatibility (`D-17`/`CB-15`). Add `adb_wifi_enabled 0` to USB/ADB lock guard, enhanced-only MAC randomization, brace-tracked `parse_sensor_privacy_dump` + strict `"all"` check (`D-22`/`CB-20`), BT idle `[]`/`none` check (`CB-21`). Fix `SystemScreen` `30m -> (0h)` display (`APP-H16`), custom DNS active pill & blank validation (`APP-H19`), automation duration pickers (`APP-M14`). | M1, M2, M3 | Survey (`explorer_survey_1,2,3`) |
| F8 | Kernel Hardening & Security Presets | Persist boot-ID-keyed `/data/adb/silo/sysctl_orig.json` so rollback survives daemon restarts; restore cleared `tcp_congestion`/`default_qdisc` to original values (`D-23`/`CB-26`). Reset ALL automation/hardening fields cleanly across `Safe`/`Balanced`/`Hardened`/`Paranoid` presets (`D-21`). Fix `SystemScreen` Packet Scheduler `"Default"` falsely enabling hardening (`APP-H17`), wrap/scroll `availCc` instead of `.take(3)` (`APP-M13`), confirmation dialogs for `Emergency Flush` & `Rollback` (`APP-H18`), refresh hardening/freezer/sentinel on `applyPreset` (`APP-M03`), exact `HomeScreen` preset matcher (`APP-H15`). | M1, M2, M3 | Survey (`explorer_survey_1,2,3`) |
| F9 | Verification, Stealth Sentinel, Config Durability & App Discovery | Fix `Sentinel` UDP outbound false-positive listeners (`CB-22`) & Live Proof multi-mode + unlocked-ADB logic (`D-27`). Corrupt `config.json` backup before fallback + parent-dir `fsync` (`D-26`). Legacy v2 `scopes.apps` migration/non-override (`CB-10`/`APP-M18`). Show non-launcher user apps in `Aplikasi` (`APP-H09`), include `blockCleartextHttp`/`blockLocalhost` in `RESTRICTED` (`APP-L02`), fix `G:N` hidden when frozen (`APP-L03`), temp-unblock rounding (`APP-L01`), open `PERMS` tab from `Audit Izin` (`APP-L04`), bounded `LruCache` icons & Binder exception safety + Activity/Provider tracker scan (`APP-M08`/`APP-L05`), scrollable `LiveProofSheet` + `"--"` loading score (`APP-M12`), `exportConfigToUri` null-stream check & streaming SAF caps (`APP-M05`/`APP-M06`). | M1, M2, M3 | Survey (`explorer_survey_1,2,3`) |
| F10 | Lockscreen Physical Security, Quick Settings Tiles & Offline/Disguise Polish | Require `if (isLocked) unlockAndRun` before disabling Lockdown or unmuting Mic/Camera in `LockdownTileService` & `SensorsOffTileService` (`APP-H01`/`CB-13`). Fix `PanicRebootTileService` `@Volatile`/timeout `Job` race, failure Result check, `Dispatchers.Main` `updateTile()` (`APP-M10`). Set `MainActivity` `android:exported="false"`, remove unused `POST_NOTIFICATIONS` (`APP-M09`/`CB-30`). Bilingual ViewModel toast strings (`APP-M15`), `BuildConfig.VERSION_NAME` & live locale/ellipsis/a11y polish (`APP-L06`/`APP-L07`), R8 enum ProGuard rule (`APP-L08`), zero-`INTERNET` APK gate. | M3 | Survey (`explorer_survey_1,3`) |
| F11 | Protected Package & Process/IPC Privilege Boundary | Build `uid_to_pkgs: HashMap<u32, Vec<String>>` from `/data/system/packages.list`; verify `is_protected_uid(uid) || uid_has_protected_pkg(uid)` on `SetAppFirewallRule`, `SetFreezerEntry`, `FreezeUidNow`, `KillConnection` (skipping `LISTEN`/`TIME_WAIT` sockets) (`D-10`/`CB-12`). Set `libc::umask(0o077)` before `UnixListener::bind`, acquire exclusive `flock` on `/data/adb/silo/silod.lock`, verify socket inode before unlinking on exit (`CB-07`/`CB-23`). Put `run_cmd` children in dedicated process group (`setpgid(0,0)`) with `kill(-pid, SIGKILL)` + guaranteed `wait()` + reader thread drop safety (`CB-17`/`D-25`). | M1, M2 | Survey (`explorer_survey_2,3`) |
| F12 | KernelSU Module Lifecycle, Packaging & CI Security | Verify `/proc/$PID/cmdline` or `/proc/$PID/exe` matches `silod` before signaling in `service.sh`, `uninstall.sh`, `action.sh`; wait for old daemon exit before starting new one (`CB-07`/`D-13`). Set `/proc/self/oom_score_adj = -900` inside `silod`, 300s boot wait, bounded restart watchdog, clear `disable` marker in `customize.sh` & `action.sh` (`CB-18`). Add `silod --uninstall-cleanup [--config PATH]` to reset Silo-applied AppOps & permission flags on uninstall (`CB-19`). Portable `MODDIR`, `$ARCH` fallback, explicit module copy & expanded zero-mount check (`system|vendor|product|system_ext|odm`) (`CB-28`), least-privilege CI & env-escaped GHA inputs (`CB-29`), updated `Cargo.toml` description (`CB-30`). | M1, M4 | Survey (`explorer_survey_2,3`) |

---

## Milestones

| # | Name | Scope | Dependencies | Status | Sub-Orch Conv ID |
|---|------|-------|--------------|--------|------------------|
| M1 | `daemon-core` | Fix `daemon/src/{config,protocol,ipc,sys,scopes,main}.rs` & `daemon/Cargo.toml` (F1, F2, F3-daemon, F4-daemon, F7-core, F8-presets, F9-config/scopes, F11-core, F12-daemon CLI/oom/flock). Keep full `daemon/` building & passing `cargo clippy --all-targets -- -D warnings && cargo test`. | none | IN_PROGRESS | `4a7c924fa663faf42ab4924648ebab40` (R3) |
| M2 | `daemon-subsystems` | Fix `daemon/src/{firewall,netlink,freezer,automation,hardening,sentinel,telemetry}.rs` (+ any `main.rs` wiring hooks) covering F3-netlink, F5-daemon, F6-daemon, F7-daemon, F8-daemon, F9-sentinel, F11-netlink/freezer. Verify `cargo clippy --all-targets -- -D warnings && cargo test --verbose` + ARM64 musl cross-compile check. | M1 | PLANNED | — |
| M3 | `android-app` | Fix all 53 `APP-*` defects across `app/**` (F1–F10 app side) against frozen Interface Contracts below. Add comprehensive JVM unit tests. Verify `./gradlew testDebugUnitTest assembleRelease --no-daemon` + `aapt2` zero-`INTERNET` gate. | none | IN_PROGRESS | `515b14d491c04e3348a65d4cfc35e02c` (R3) |
| M4 | `module-scripts-ci` | Fix `module/{service,customize,action,uninstall}.sh`, `scripts/build_module.sh`, `.github/workflows/build.yml` (F12). Verify `sh -n`, `bash -n`, and isolated shell behavior tests. | none | IN_PROGRESS | `168b53c44acee7813323a1f5dd623b11` (R3) |
| E2E | `e2e-testing-track` | Independent opaque-box E2E test infrastructure & 4-tier test suite (`tests/e2e/`, `TEST_INFRA.md`, `TEST_READY.md`) covering ALL 12 features (F1–F12): Tier 1 >=60, Tier 2 >=60, Tier 3 >=12, Tier 4 >=6 (>=138 tests total). | none (parallel) | IN_PROGRESS | `f8a740c89ae8d5a8ab44ca85a23a342c` (R3) |
| M5 | `final-milestone` | Phase 1: Wait for `TEST_READY.md`, execute Tiers 1→2→3→4 sequentially until 100% pass + full ARM64 module ZIP + Release APK + zero-mount & zero-INTERNET gates. Phase 2: Tier 5 adversarial coverage hardening (Challenger-initiated white-box loop until no gaps remain). | M1, M2, M3, M4, E2E | PLANNED | — |

---

## Interface Contracts

### 1. App (`app/`) ↔ Daemon (`daemon/`) IPC & Wire Protocol
- **Transport**: `su -c "exec /data/adb/modules/silo/bin/silod --bridge"` ↔ `/data/adb/silo/silod.sock` (`0600`, `SO_PEERCRED` `uid == 0 || uid == euid`, `umask(0o077)`).
- **Framing & Limits**:
  - Newline-delimited JSON-RPC: Request `{"id": <u64>, "cmd": "<snake_case>", "params": <object|null>}\n`; Response `{"id": <u64>, "ok": <bool>, "data": <json|null>, "error": <string|null>}\n`.
  - **MUST match `resp.id == req.id`** on client (`SiloClient.kt`).
  - `MAX_IPC_LINE_BYTES = 4 * 1024 * 1024` (4 MiB) in `daemon/src/protocol.rs` (supports full 50,000-entry `replace_ip_blocklist` without truncation; response limit `8 MiB`).
  - `--bridge` socket `read_timeout = 45s`, `write_timeout = 15s`, with single transparent reconnect+retry on broken pipe when `silod` restarts.
  - Client `SiloClient` enforces genuine cancellation on timeout (closing/destroying stalled bridge subprocess), drains `stderr` on a background thread (capped ring buffer), and uses `destroyForcibly()` on teardown.

### 2. Permission Inspection & Action DTO Contract (`daemon/src/protocol.rs` & `scopes.rs` ↔ `app/.../SiloModels.kt`, `AppsScreen.kt`, `TrafficScreen.kt`)
All existing fields MUST remain backward-compatible; additive fields use `#[serde(default)]` in Rust and default values in Kotlin (`ignoreUnknownKeys = true`):
- **`CategoryPermLiveState`**:
  ```json
  {
    "category": "camera",
    "appops_mode": "ignore",
    "last_access_ago": "3m12s ago",
    "running_now": false,
    "runtime_granted": false,
    "has_runtime_perms": true,
    "supports_foreground": true,
    "system_fixed": false
  }
  ```
  - `appops_mode`: effective mode (`"allow"`, `"foreground"`, `"ignore"`, `"deny"`, `"default"`) computed with strictness precedence (`Uid mode` restrictive values NEVER overwritten by package history; multi-op categories aggregate fail-closed so any `"allow"` > `"foreground"` > `"ignore"`/`"deny"` > `"default"`).
  - `runtime_granted`: `Option<bool>` (JSON `bool|null`): live runtime grant status (`Some(true)` if any declared runtime permission in category is granted, `Some(false)` if none are granted, `None` for AppOps-only categories like `clipboard` / `background` or if unavailable — App falls back cleanly to `PackageManager` when `null`).
  - `has_runtime_perms`: `bool` (`false` for `clipboard`, `background`; `true` for the 8 runtime-permission categories).
  - `supports_foreground`: `bool` (`true` for `camera`, `microphone`, `location`, `sensors`; `false` for `background_location`, `contacts`, `media`, `sms_calllog`, `clipboard`, `background`).
  - `system_fixed`: `bool` (`true` if Android marks runtime permission `SYSTEM_FIXED`, explaining non-revocable OS constraints honestly in UI).
- **`set_app_permission_action {package_name, category, action}`**:
  - `action` ∈ `"allow"` | `"foreground"` | `"ignore"` | `"revoke"` (accepts `"default"` as `"allow"`, `"deny"` as `"revoke"`).
  - `"revoke"`: revokes declared runtime perms via `pm revoke --user <u_id>`, sets UID+pkg AppOps to `"ignore"` (or `"deny"` for non-runtime ops) with `revoked-compat` fallback if needed, fails with clear error if all commands fail (inspecting BOTH stdout and stderr!).
  - `"ignore"` (`Palsukan`): sets UID AppOps to `"ignore"` (with `revoked-compat` fallback if needed) WITHOUT calling `pm revoke` (blocks data silently without killing app process).
  - `"foreground"` (`Saat Dibuka`): rejects cleanly if `!supports_foreground`; otherwise clears `revoked-compat`/`user-fixed` and sets UID AppOps to `"foreground"`.
  - `"allow"` (`Normal`): clears `revoked-compat`/`user-fixed`, resets UID+pkg AppOps to `"default"` (and for declared runtime perms that are revoked, runs `pm grant --user <u_id>` so tapping `Normal` genuinely restores permission if user chooses `Normal`!), and if `category` was in `guards[pkg].locked_deny`, automatically removes `category` from `locked_deny` (persisting config) so `Kunci Blokir` does not fight the user's explicit override!
- **`set_app_perm_guard {package_name, guard}`**:
  - `auto_revoke_on_bg` and `locked_deny` are mutually exclusive per category (sanitized on daemon too: `locked_deny` takes precedence and strips duplicates from `auto_revoke_on_bg`).
  - Adding a category to `locked_deny`: immediately applies `revoke` + `pm set-permission-flags --user <u_id> <pkg> <perm> user-set user-fixed` (best-effort) WITHOUT holding `state_mtx` across subprocesses.
  - Removing a category from `locked_deny`: clears `user-fixed` via `pm clear-permission-flags --user <u_id> <pkg> <perm> user-fixed` (best-effort).
- **`PrivacyAccessEvent` (`get_privacy_access_log`)**:
  - Excludes protected packages (`is_protected_package(pkg)`).
  - `mode` reflects effective strictest mode parsed from `(uid/pkg)` tuples (never blindly `.split('/').next()`).
  - Scans ALL `Access:` lines per op, prioritizing `(running)` or freshest timestamp.

### 3. Firewall, Lockdown & Daemon Status Contract (`daemon` ↔ `app`)
- **`DaemonStatus`**: add additive `#[serde(default)] pub panic_lockdown_active: bool` (Kotlin default `false`).
- **`FirewallConfig`**: add additive `#[serde(default)] pub panic_lockdown_active: bool` (Kotlin default `false`).
  - When `panic_lockdown_active == true`, `SILO_OUT` blocks ALL non-protected user UIDs unconditionally (ignoring saved `DefaultAllow` per-app `allow_*` rules) until user changes mode/applies preset/disables firewall/clears lockdown.
- **`replace_ip_blocklist` response**: `{"loaded": usize, "rejected": usize, "backend": String}` (unchanged; App MUST inspect `rejected > 0` and report error instead of false success).

### 4. Daemon CLI ↔ KernelSU Module Scripts Contract (`daemon/src/main.rs` ↔ `module/*.sh`)
- **Single-instance lock**: `/data/adb/silo/silod.lock` (`flock(LOCK_EX | LOCK_NB)` held by `silod` for its lifetime). On clean shutdown, `silod` unlinks `/data/adb/silo/silod.sock` ONLY if its `(st_dev, st_ino)` matches the socket `silod` created.
- **PID safety**: `module/service.sh`, `module/action.sh`, `module/uninstall.sh` MUST verify `/proc/$PID/cmdline` (or `/proc/$PID/exe`) contains `silod` before sending `SIGTERM`/`SIGKILL`, and wait up to 5s for exit before spawning a new daemon.
- **OOM protection**: `silod` writes `-900` to `/proc/self/oom_score_adj` on startup (best-effort, non-fatal if unprivileged in host tests).
- **Uninstall cleanup CLI**: `silod --uninstall-cleanup [--config PATH]` — executes full `--emergency-flush` (iptables/ip6tables/ipset/cgroup thaw/unsuspend) AND resets all Silo-configured AppOps (`cfg.scopes.apps` + `cfg.scopes.guards`) back to `default` and clears `user-fixed`/`revoked-compat` flags before `uninstall.sh` removes `/data/adb/silo`.
- **Disable marker cleanup**: `customize.sh` removes `/data/adb/modules/silo/disable` (if present from prior boot-guard trigger); `action.sh` removes `$MODDIR/disable` when user explicitly recovers daemon.

---

## Consolidated Survey Reference Index
- App Audit (53 findings `APP-C01..L08` + Repo Map): `/home/antigravity/terenzy/Silo/.agents/teamwork/explorer_survey_1/handoff.md`
- Daemon & Enforcement Audit (28 findings `D-01..D-28` + OS limitation proof): `/home/antigravity/terenzy/Silo/.agents/teamwork/explorer_survey_2/handoff.md`
- Module, Scripts, Cross-Boundary & Toolchain Audit (30 findings `CB-01..CB-30`): `/home/antigravity/terenzy/Silo/.agents/teamwork/explorer_survey_3/handoff.md`
