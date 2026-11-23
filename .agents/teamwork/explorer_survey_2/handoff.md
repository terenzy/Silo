# Handoff Report — `explorer_survey_2` (Daemon & Enforcement Engine Deep Audit)

**Handoff Type:** Hard (Task Complete — Read-Only Deep Audit)  
**Target Repository:** `/home/antigravity/terenzy/Silo` (`silod` v3.0.0)  
**Target Device Context:** Samsung Galaxy A14 5G (`SM-A146P`), Dimensity 700, One UI 7 / Android 15 (API 35), Linux 6.6.127, KernelSU-Next + SUSFS  
**Date:** 2026-11-22

---

## Executive Summary

Every single user-reported symptom — unresponsive permission controls in per-app & Monitor views, revoking (`Cabut`) flipping back to allowed (`Normal`), and `Kunci Blokir` waiting ~15–20s before force-stopping the app and revoking — is caused by **concrete, fixable bugs in `silod` and its IPC bridge**, NOT primarily by unavoidable Android limits. Specifically: (1) `parse_appops_get_output` unconditionally overwrites `Uid mode: <OP>: ignore` with historical package-level `allow` lines while `set_app_permission_action` never sets `--uid` AppOps; (2) `GetStatus` (9 blocking subprocesses) and the 5s supervisor loop hold the global `state_mtx` while `SiloClient` serializes every RPC over one 10s-timeout mutex; and (3) `Kunci Blokir` polls only once every **15–20 seconds** (`>= 15` gate inside a 5s loop) and never sets Android's `user-fixed` permission flag, so Android grants the permission normally until the delayed `pm revoke` fires and causes Android's `PermissionManagerService` to kill (`killUid`) the running app. Beyond the reported issues, this audit identified **30 distinct bugs, security loopholes, and logic errors** across permissions, IPC, firewall, Netlink/ipset, freezer, automation, sysctl hardening, and module lifecycle scripts.

---

## 1. Observation (File Inventory, Architecture & Direct Evidence)

### 1.1 Complete Daemon & Enforcement File Inventory

Every file in `daemon/`, `module/`, `scripts/`, plus the app-side IPC/permission/monitor bridge was read in full (zero skimming):

| Path | Lines | Role / Subsystem |
|---|---:|---|
| `daemon/Cargo.toml` | 23 | Rust 2021 binary `silod` v3.0.0 (`libc`, `serde`, `serde_json`), `opt-level = "z"`, `lto = true`, `panic = "abort"` |
| `daemon/src/main.rs` | 1,310 | Entry point, CLI flags, `DaemonState`, `dispatch_command` (47 RPCs), signal handler, 4 background threads (signal poller, NFLOG listener, 400ms fast-thaw loop, 5s supervisor loop) |
| `daemon/src/protocol.rs` | 907 | `RawIpcEnvelope`, `DaemonCommand` enum (47 variants), `parse_ipc_request`, `IpcResponse`, all response DTO structs (`MAX_IPC_LINE_BYTES = 512 KiB`) |
| `daemon/src/ipc.rs` | 336 | `SO_PEERCRED` auth (`uid == 0 \|\| uid == self_euid`), `read_line_bounded`, `run_ipc_server` (Unix stream socket, max 16 clients), `run_stdio_bridge` (`--bridge`), `run_single_rpc` (`--once`/`--status`) |
| `daemon/src/config.rs` | 918 | `SiloConfig` v3 schema, `FirewallConfig`, `FreezerConfig`, `ScopesConfig`, `AppPermGuard`, `HardeningConfig`, `AutomationConfig`, `SecurityPreset`, `sanitize()`, `save_atomic()`, CIDR/hostname/pkg validators, `PROTECTED_PACKAGES` |
| `daemon/src/scopes.rs` | 859 | `PERM_CATEGORIES` (10 categories), `set_app_permission_action`, `bulk_revoke_category`, `inspect_app_permissions`, `parse_appops_get_output`, `get_privacy_access_log`, `parse_dumpsys_appops_op`, `PermissionEnforcer`, legacy `AppIsolationScope`, `ClipboardGuard` |
| `daemon/src/firewall.rs` | 870 | `probe_capabilities`, `apply_firewall`, `apply_table_with_fallback`, `ensure_hook_jumps`, `flush_firewall`, `build_restore_script` (`iptables-restore --noflush -w` / `ip6tables-restore`) |
| `daemon/src/netlink.rs` | 1,183 | Raw Netlink clients: `NETLINK_ROUTE` (`classify_active_interfaces`), `NETLINK_SOCK_DIAG` (`list_active_connections`, `destroy_socket`, `destroy_uid_sockets`, `destroy_all_user_sockets`, `destroy_matching_connection`), `NETLINK_NETFILTER` pure-Rust `ipset` (`ipset_atomic_sync`) & `NFLOG` group 42 (`run_nflog_loop`) |
| `daemon/src/freezer.rs` | 391 | `FreezerEngine`: cgroup v2 `cgroup.freeze` (`SoftCgroup`), `pm suspend` + `am force-stop` + `set-standby-bucket restricted` (`DeepStop`), `fast_foreground_thaw_tick`, `policy_tick` |
| `daemon/src/automation.rs` | 528 | `AutomationEngine`: Lockscreen Sensors Kill-Switch (`cmd/dumpsys sensor_privacy`), USB/ADB Lockscreen Guard, Wi-Fi/BT Idle Auto-Off, Auto-Reboot BFU, Private DNS (`settings`), Radio Privacy (`settings`) |
| `daemon/src/hardening.rs` | 388 | `HardeningEngine`: 26+ `/proc/sys` kernel sysctls with in-memory `original_values` snapshot & `rollback_all` |
| `daemon/src/sentinel.rs` | 519 | Read-only `run_sentinel_audit` (SELinux, dmesg, eBPF, firewall, User CA, listeners, `/data/local/tmp`, root procs, LSM stealth, TCP timestamps) & `run_live_proof` (8 live kernel/framework proofs) |
| `daemon/src/sys.rs` | 350 | `run_cmd` (direct `Command::new`, no shell, 2–3 helper threads + 15ms `try_wait` timeout poll), `resolve_bin`, `boottime_secs`, `read_uid_package_map`, `is_ce_unlocked`, `detect_screen_on`, `detect_device_locked`, `foreground_app_uids` |
| `daemon/src/telemetry.rs` | 98 | RAM-only bounded ring buffer (`TELEMETRY_RING_CAPACITY = 2048`) for NFLOG `BlockedPacketEvent` |
| `module/service.sh` | 76 | Post-boot launcher (`sys.boot_completed=1` + 5s), 2-failure Dead-Man Switch (`.boot_fail_count`), PID kill + `silod` start |
| `module/customize.sh` | 34 | ARM64 KSU module installer (`0700 /data/adb/silo`, zero `/system` overlays) |
| `module/action.sh` | 46 | KSU Action button health check / manual restart |
| `module/uninstall.sh` | 27 | Graceful SIGTERM + `--emergency-flush` + `rm -rf /data/adb/silo` |
| `module/module.prop` | 7 | Module metadata (`id=silo`, `version=v3.0.0`, `versionCode=30000`) |
| `scripts/build_module.sh` | 46 | Packages static aarch64 musl `silod` + `module/*` into `silo-ksu-module.zip` with zero-mount gate |
| App IPC/UI Boundary Read | — | `SiloClient.kt`, `SiloModels.kt`, `SiloRepository.kt`, `SiloViewModel.kt`, `AppInventory.kt`, `AppsScreen.kt`, `TrafficScreen.kt`, `SiloStrings.kt`, `MainActivity.kt`, `tiles/*.kt` |

### 1.2 Architecture Overview

```
Silo.apk (Unprivileged App, ZERO INTERNET perm)
  │  Single Kotlin Mutex + 10s timeout (SiloClient.kt:39,53)
  ▼  stdin/stdout JSON-RPC lines
su -c "/data/adb/modules/silo/bin/silod --bridge"  (ipc.rs:197, 15s read timeout)
  │  Unix stream socket /data/adb/silo/silod.sock (0600, SO_PEERCRED uid==0)
  ▼
silod (Root Daemon, 1 global Arc<Mutex<DaemonState>> state_mtx)
  ├── Main Thread: UnixListener accept loop (40ms poll) → spawns 1 thread/client (max 16) → dispatch_command()
  ├── Signal Thread: polls AtomicBool GLOBAL_STOP every 100ms
  ├── NFLOG Thread: NETLINK_NETFILTER group 42 → locks state_mtx & clones uid_map per packet → TelemetryRing
  ├── Fast-Thaw Thread: every 400ms (screen on & frozen non-empty) → locks state_mtx → thaws fg UIDs
  └── Supervisor Thread: every 5s → locks state_mtx across ALL external commands:
        ├── Boot Guard clear (>=45s) & Temp Unblock expiry + SOCK_DESTROY
        ├── Firewall sync on screen/VPN/expiry change (or ensure_hook_jumps every 30s)
        ├── FreezerEngine::policy_tick()
        ├── PermissionEnforcer::tick() (Auto-Cabut on fg-exit/15s; Kunci Blokir ONLY every >=15s!)
        ├── ClipboardGuard::tick() (every 10s)
        ├── check_new_installed_apps() (every 15s)
        └── AutomationEngine::tick() (Lockscreen sensors, USB/ADB lock, Wi-Fi/BT auto-off, BFU reboot)
```

### 1.3 Direct Visual Evidence from User Screenshot (`/home/antigravity/Screenshot_20261122_223732_Silo.jpg`)

Inspecting `Screenshot_20261122_223732_Silo.jpg` (App: **Kamera** `com.sec.android.app.camera • UID 10095`, Tab: **Izin & AppOps**):
1. **Risk Banner** lists active granted permissions: `Foto & Media, Mikrofon, Latar & Overlay` — notice **`Kamera` is NOT in that list**, proving `runtimeGranted == false` (`pm revoke` DID revoke `android.permission.CAMERA`).
2. **`Kamera` row**:
   - Top-right badge: **`DICABUT`** in **grey** (hits the `else` branch in `AppsScreen.kt:865`, NOT green `PALSU KOSONG` or green `DICABUT`!).
   - Highlighted action pill: **`Normal`** in **green** (`AppsScreen.kt:897`: `active = appopsMode == "default" || appopsMode == "allow"`).
   - **`Cabut`** pill: **NOT highlighted** (`AppsScreen.kt:918`: requires `appopsMode == "ignore" || appopsMode == "deny"`).
   - **`Kunci Blokir`** toggle: **CHECKED (orange)**!
   - **Direct proof**: `inspect_app_permissions("com.sec.android.app.camera")` returned `appops_mode = "allow"` (or `"default"`) for `"camera"` **even though `CAMERA` was revoked AND `Kunci Blokir` was active!**
3. **`Lokasi` row**:
   - Top-right badge: **`PALSU KOSONG`** (`appopsMode == "ignore"`).
   - Highlighted action pills: **BOTH `Palsukan` (green) AND `Cabut` (red)** are active simultaneously (`AppsScreen.kt:911` and `AppsScreen.kt:918` both evaluate to `true` when `!runtimeGranted && appopsMode == "ignore"`).

### 1.4 Daemon & Enforcement Feature Inventory

| Subsystem | Features Implemented | Enforcement Mechanism |
|---|---|---|
| **Firewall** (`firewall.rs`, `netlink.rs`) | Dual-mode (`default_allow`/`default_deny`), `REJECT`/`DROP`, per-UID Wi-Fi/Cell/VPN/LAN/Screen-Off/HTTP/Localhost/VPN-exempt, Temp Unblock timers, Auto-Block new apps, LAN Isolation, DNS Leak Guard, IPv6 VPN Leak Guard, Anti-Port-Scan (`xt_recent`), TTL/HL 64 normalization, IP/CIDR Blocklist (Netlink `ipset` + fallback chain), `SOCK_DESTROY`, Emergency Flush | `iptables-restore --noflush -w`, `ip6tables-restore`, `NETLINK_NETFILTER` ipset/NFLOG, `NETLINK_SOCK_DIAG` `SOCK_DESTROY` |
| **Permissions & Enforcer** (`scopes.rs`) | 10 permission categories, 4 per-category actions (`allow`, `foreground`, `ignore`, `revoke`), `Auto-Cabut` (`auto_revoke_on_bg`), `Kunci Blokir` (`locked_deny`), Bulk Revoke, Privacy Access Timeline, Legacy `AppIsolationScope`, Auto-Clear Clipboard | `pm revoke --user 0`, `cmd appops set --user 0`, `cmd appops get --user 0`, `dumpsys appops --op`, `dumpsys/cmd clipboard` |
| **App Freezer** (`freezer.rs`) | `SoftCgroup` (cgroup v2 freeze + 400ms fast foreground thaw), `DeepStop` (`pm suspend` + `am force-stop` + `set-standby-bucket restricted`), auto-freeze on background grace / screen-off, kill sockets on freeze | `/sys/fs/cgroup/uid_*/cgroup.freeze`, `pm suspend/unsuspend`, `am force-stop/set-standby-bucket` |
| **Automation & Radio** (`automation.rs`) | Separated Mic/Cam hardware mute (`sensor_privacy`), Lockscreen Sensors Kill-Switch, USB/ADB Lockscreen Guard, Wi-Fi/BT Idle Auto-Off, Auto-Reboot to BFU (`CLOCK_BOOTTIME`, >=15m floor, CE-unlock check), Private DNS (DoT), Radio Privacy (MAC rand, Wi-Fi/BLE bg scan, Captive Portal), Panic Lockdown, Panic BFU Reboot | `cmd/dumpsys sensor_privacy`, `settings get/put global`, `svc usb/wifi/bluetooth/power`, `dumpsys bluetooth_manager` |
| **Kernel Hardening** (`hardening.rs`) | 26 sysctl parameters (kptr, perf, unpriv eBPF, BPF JIT, suid_dumpable, fs links/fifos, dmesg, tcp_timestamps, syncookies, rp_filter, redirects, source_route, rfc1337, ASLR, Yama ptrace, IPv6 tempaddr) + TCP congestion & qdisc + rollback | Direct `/proc/sys/*` reads/writes |
| **Monitoring & Verification** (`telemetry.rs`, `netlink.rs`, `sentinel.rs`) | RAM-only NFLOG blocked packet ring (2,048), Live TCP/UDP socket inspector + per-socket kill, Privacy Access Timeline, Stealth Sentinel Audit (score 0–100), Live Enforcement Proof (8 checks) | `NFLOG` group 42, `NETLINK_SOCK_DIAG`, `dumpsys appops --op`, `iptables -S/-C`, `/proc` |

---

## 2. Deep Technical Answer to User's Question: "kunci blokir harus tunggu dulu baru beberapa saat berhenti paksa dan kecabut izinnya, apa ini batasannya?"

**Short Answer:** **NO — the 15–20 second delay is NOT an Android OS limitation; it is a bug/design flaw in `silod` (`scopes.rs:568, 606` + `main.rs:1074`).** The app process closing ("berhenti paksa") **IS** a genuine Android OS security behavior whenever a granted runtime permission is revoked from a running process (`pm revoke`), **HOWEVER**, with proper Android permission flags + AppOps enforcement, a locked permission **should NEVER have been grantable by the app popup in the first place**, and even if granted externally via Settings, AppOps can cut access in **<1 second without waiting 15–20s** (and without even needing to wait for a kill).

### 2.1 Clear Separation: Genuine Android OS Limits vs. Fixable Silo Bugs

| Behavior | Genuine Android OS Limitation? | Silo Bug / Suboptimal Design? | Exact Mechanism & Proof |
|---|---|---|---|
| **Waiting ~15–20 seconds before `Kunci Blokir` reacts** | **NO.** | **YES — 100% Silo bug (`main.rs:1074`, `scopes.rs:568, 606`).** | Supervisor sleeps **5s** (`main.rs:1074`), and `locked_deny` is gated behind `do_periodic_sweep` (`>= 15` seconds, `scopes.rs:568, 606`). Combined with 5s tick granularity + `state_mtx` blocking, drift detection takes **15–21 seconds**. |
| **App is able to pop up the "Allow Camera/Mic" dialog & get granted while `Kunci Blokir` is ON** | **PARTIAL** (a non-Xposed root daemon cannot intercept Settings UI taps inline inside `PermissionController`), **BUT** Android natively supports preventing in-app permission prompts! | **YES — Fixable omission in Silo.** | Silo never sets `pm set-permission-flags --user 0 <pkg> <perm> user-fixed user-set` (verified in AOSP `PackageManagerShellCommand.java` / official Android docs). Setting `user-fixed` (`FLAG_PERMISSION_USER_FIXED`) tells Android's `PermissionController` to **immediately auto-deny every in-app `requestPermissions()` call without ever showing a dialog**! Only manual override inside Android System Settings can re-grant it. |
| **App suddenly force-stops ("berhenti paksa") when permission is revoked while app is running** | **YES — Genuine Android OS security mechanism.** | **PARTIAL** (Silo triggers it unnecessarily every 15s even when already revoked, due to `parse_appops_get_output` bug!). | Inside AOSP `PermissionManagerService`, calling `pm revoke` on a **currently granted** runtime permission triggers `ActivityManagerService.killUid(..., ApplicationExitInfo.REASON_PERMISSION_CHANGE)` because Android processes cache granted permission results and hold open binder handles/FDs. **However:** (1) Changing **AppOps** (`cmd appops set --user 0 --uid <pkg> <op> ignore` or `deny`) takes effect **immediately without killing the process** (and `CameraService`/`AudioFlinger` actively mute/disconnect active streams via `OnOpChangedListener`!), and (2) Silo's `parse_appops_get_output` bug (`scopes.rs:299`) makes `Kunci Blokir` falsely think an *already-revoked* permission drifted to `allow`, calling `pm revoke` repeatedly every 15s! |
| **Instant interception (0ms) of a user manually toggling a permission back ON in Android Settings** | **YES — Genuine non-hooked daemon boundary.** | **Fixable from 15–20s down to ~0.5–1.5s** (or near-instant via event-driven triggers). | Without LSPosed/Xposed hooks into `system_server`'s `PermissionManagerService` or DevicePolicyManager Device Owner (`FLAG_PERMISSION_POLICY_FIXED`), a standalone root daemon cannot synchronously veto a user clicking "Allow" inside `com.google.android.permissioncontroller`. **However**, a dedicated lightweight watcher checking **only** when a guarded app is in foreground or when `packages.list` / `runtime-permissions.xml` / `appops.xml` changes via Linux **`inotify`** (zero CPU when idle!) can detect an external grant in **<200ms–1s** instead of 15–20s! |
| **KernelSU / Root daemon vs. LSPosed / Custom ROM** | **Genuine architectural trade-off** (aligned with Silo's Zero-Mount / Stealth design). | Silo already has root (`uid=0`), which gives full access to `inotify` on `/data/system/users/0/runtime-permissions.xml` & `/data/system/appops.xml` (or `cmd appops` fast polling on fg change), `pm set-permission-flags`, `cmd appops set --uid`, and `revoked-compat`. | No LSPosed or ROM modification needed to make `Kunci Blokir` feel instantaneous and rock-solid! |

### 2.2 Concrete Multi-Layer Fix Strategy for `Kunci Blokir`

1. **Prevent in-app re-granting natively (`user-fixed`)**: When `Kunci Blokir` is enabled on a category, run:
   - `pm revoke --user 0 <pkg> <perm>` (only if currently granted — avoids needless process kills!)
   - `pm set-permission-flags --user 0 <pkg> <perm> user-set user-fixed` (blocks in-app permission dialogs natively in Android!)
   - `cmd appops set --user 0 --uid <pkg> <op> ignore` (and package mode for non-runtime ops, plus `revoked-compat` fallback for Android 16+!)
   - When `Kunci Blokir` is disabled (or user switches to `Normal`), run `pm clear-permission-flags --user 0 <pkg> <perm> user-fixed` so the app can prompt again.
2. **Event-driven + fast re-enforcement (<1s instead of 15–20s)**:
   - Run `PermissionEnforcer` on its **own dedicated thread** (never holding `state_mtx` while spawning commands!).
   - Watch `/data/system/users/0/` (or `/data/misc_de/0/apexdata/com.android.permission/runtime-permissions.xml` on Android 12–16!) with Linux **`inotify` (`IN_MOVED_TO | IN_CLOSE_WRITE`)** + check guarded apps immediately whenever a guarded UID enters foreground (1s lightweight `/proc` check when screen is on and `guards` is non-empty).
3. **Eliminate false-positive drift loops**:
   - Fix `parse_appops_get_output` (`scopes.rs:297-309`) so `Uid mode: <OP>: ignore` is NEVER overwritten by package-level `allow`.
   - Check BOTH real runtime grant state (via `dumpsys package` or `cmd package` / `appops` UID mode) and AppOps mode; if runtime perm is already revoked and `Uid mode` is `ignore`, do **NOT** call `pm revoke` again!
4. **Honest UX messaging**: Clearly tell the user in the `Kunci Blokir` tooltip that if they manually re-grant a locked permission in Android Settings while the app is open, Android itself restarts/closes the app when the runtime permission is revoked (whereas `Palsukan` blocks access silently without closing the app).

---

## 3. Logic Chain — Tracing Every User-Reported Symptom

### Chain 1: Why "saat dicabut malah diizinkan" (Revoking turns into Allowed!) & Screenshot Proof
1. **Observation**: `scopes.rs:151-177` runs `pm revoke --user 0 <pkg> <perm>` followed immediately by `cmd appops set --user 0 <pkg> <op> ignore` (package mode only, NO `--uid`).
2. **Android OS Behavior** (verified via AOSP `AppOps.md`, `AppOpsService.java`, `AppOpsNext/DEVICE_FINDINGS.md`):
   - Runtime-permission-backed ops (`CAMERA`, `RECORD_AUDIO`, `FINE_LOCATION`, etc.) live in **UID mode** (`setUidMode`), NOT package mode (`setMode`).
   - `pm revoke` triggers `PermissionPolicyService` asynchronously, which sets `Uid mode: CAMERA: ignore` and leaves/resets package mode to default (`allow`). On Android 15/16, direct package `setMode` on runtime ops is also ignored/rejected (`should not be set directly`).
   - If the app has ever used that permission (like `com.sec.android.app.camera` using `CAMERA`), `cmd appops get --user 0 <pkg>` outputs TWO lines:
     ```
     Uid mode: CAMERA: ignore
     CAMERA: allow; time=+3m12s ago; duration=+15s
     ```
3. **Observation**: `parse_appops_get_output` (`scopes.rs:297-309`) executes `if !raw_line.trim().starts_with("Uid mode:") { e.0 = mode.clone(); }`, **unconditionally overwriting `"ignore"` with `"allow"`!**
4. **Observation**: `AppsScreen.kt:897` highlights `Normal` (green) whenever `appopsMode == "default" || appopsMode == "allow"`, and `AppsScreen.kt:918` only highlights `Cabut` when `!runtimeGranted && (appopsMode == "ignore" || appopsMode == "deny")`.
5. **Observation**: In `Screenshot_20261122_223732_Silo.jpg`, `Kamera` has `!runtimeGranted` (grey `DICABUT`) + `Kunci Blokir` checked, yet **`Normal` is highlighted green** — while `Lokasi` (never accessed, no historical `allow; time=` line) shows `PALSU KOSONG` with BOTH `Palsukan` and `Cabut` lit up.
6. **Additional compounding cause**: In `scopes.rs:229-238` (`inspect_app_permissions`), multi-op categories pick `ignore` (prio 4) over `allow` (prio 2), masking active `allow` ops; and in `Monitor -> Akses Izin` (`scopes.rs:413-418`), `parse_dumpsys_appops_op` takes `.split('/').next()` on `OP (allow/ignore):`, always displaying `allow` even after revocation!

### Chain 2: Why "bagian izin baik di perappnya atau dibagian monitornya itu ga bisa diklik atau tidak respon"
1. **Observation**: `SiloClient.kt:39-85` uses ONE `Mutex` + ONE `su -c silod --bridge` subprocess with `withTimeout(10_000L)`.
2. **Observation**: `SiloViewModel.kt:104-114` polls `get_status` every 4s on every screen; `SiloViewModel.kt:166-193` ALSO polls `get_live_connections` + `get_telemetry` + `get_status` every 3s on `Monitor` tab, AND triggers `get_privacy_access_log` (11 sequential `dumpsys appops --op` subprocesses, `scopes.rs:348-354`).
3. **Observation**: In `silod`, `GetStatus` (`main.rs:216-275, 340-343`) acquires global `state_mtx` and runs **9 sequential Android CLI subprocesses** (`dumpsys sensor_privacy` + 8x `settings get global`), taking ~1–2.5s per call while blocking EVERY other RPC and background thread.
4. **Observation**: The 5s supervisor thread (`main.rs:1092-1160`) ALSO holds `state_mtx` across `sync_firewall`, `freezer.policy_tick`, `enforcer.tick` (which falsely triggers every 15s due to Chain 1!), `clipboard.tick`, and `automation.tick`.
5. **Observation**: Clicking `Cabut` on a multi-op category like `media` (`scopes.rs:52-68, 151-183`) runs **12 sequential subprocesses** (5 `pm revoke` + 6 `cmd appops set` + 1 `cmd appops get`). Queued behind `get_status`/`get_privacy_access_log`/`state_mtx`, it easily hits `SiloClient`'s 10s timeout (or `ipc.rs:266`'s 15s bridge socket timeout), killing the bridge (`SiloClient.kt:80-82`).
6. **Observation**: `AppsScreen.kt:117` renders `AppControlSheet` inside a `ModalBottomSheet` window **above** `MainActivity.kt:109`'s `SnackbarHost`, so ALL error/timeout toasts are **completely invisible** behind the sheet, and buttons have zero pending/loading state.
7. **Observation**: On `Monitor -> Akses Izin` (`TrafficScreen.kt:176-179`), clicking `Cabut`:
   - Fails on protected packages with UID >= 10000 (`scopes.rs:435` doesn't filter `is_protected_package`);
   - Races `setAppPermissionAction` and `refreshPrivacyAccessLog` concurrently;
   - Even on success, the historical `Access:` entry stays in `dumpsys appops` for 7 days and still renders with `(allow)` due to `scopes.rs:414` `.split('/').next()` — making the button appear completely dead!

---

## 4. Exhaustive Bug, Security Loophole & Logic Error Catalog

### Severity Legend
- **CRITICAL**: Core feature broken, causes user-reported bug directly, or severe system/security break.
- **HIGH**: Major security bypass/loophole, stability hazard, or significant broken feature.
- **MEDIUM**: Logic flaw, race condition, edge-case failure, or degraded reliability.
- **LOW**: Minor defense-in-depth, UX/status inaccuracy, or durability polish.

---

#### [D-01] CRITICAL — `parse_appops_get_output` overwrites `Uid mode: ignore` with package-level `allow`
- **Location**: `daemon/src/scopes.rs:263-312` (specifically lines 297–309)
- **Code Evidence**:
  ```rust
  map.entry(op)
      .and_modify(|e: &mut (String, Option<String>, bool)| {
          if !raw_line.trim().starts_with("Uid mode:") {
              e.0 = mode.clone();
          }
  ```
- **Root Cause**: When an app has used a runtime permission before, `cmd appops get --user 0 <pkg>` prints `Uid mode: CAMERA: ignore` followed by `CAMERA: allow; time=+...`. The parser unconditionally overwrites `"ignore"` with `"allow"`, contradicting Android's `AppOpsService` evaluation where non-`allow` UID mode takes precedence.
- **User Impact**: Directly causes **"saat dicabut malah diizinkan"** (shown in user screenshot for `com.sec.android.app.camera`!) AND causes `PermissionEnforcer` to falsely detect drift every 15s forever.
- **Concrete Fix**: Parse `uid_mode` and `pkg_mode` separately per op, then compute effective mode using Android precedence: if `uid_mode` is `deny`/`errored`/`ignore`/`foreground`, it dominates package `allow`/`default`; otherwise combine stricter of `uid_mode` and `pkg_mode`. Recognize `"errored"` as `"deny"`.

#### [D-02] CRITICAL — `set_app_permission_action` never uses `--uid` for runtime-permission AppOps, ignores `pm revoke` errors, and races with `PermissionPolicyService`
- **Location**: `daemon/src/scopes.rs:124-184` (lines 143–183)
- **Code Evidence**:
  ```rust
  let appops_mode = match clean_action.as_str() {
      "allow" | "default" => "default",
      "foreground" => "foreground",
      "ignore" => "ignore",
      "revoke" | "deny" => "ignore", ...
  };
  if clean_action == "revoke" || clean_action == "deny" {
      for &perm in spec.runtime_perms {
          let _ = run_cmd(&pm, &["revoke", "--user", "0", package_name, perm], None, 2000);
      }
  }
  ...
  run_cmd(&cmd_bin, &["appops", "set", "--user", "0", package_name, op, appops_mode], None, 2500)
  ```
- **Root Cause**:
  1. Runtime-permission AppOps are UID-scoped in AOSP (`cmd appops set --user 0 --uid <pkg> <op> <mode>`). Setting only package mode leaves `Uid mode` as `allow` (which AOSP `AppOpsService` checks first / rejects package writes on Android 15–16).
  2. `pm revoke` exit status & stderr are discarded (`let _ =`), and `if ok_count == 0 && !last_err.is_empty()` returns `Ok` when stderr is empty.
  3. Both `"ignore"` and `"revoke"` write `"ignore"`, making them indistinguishable in state (both pills light up on `Lokasi` in screenshot).
  4. `action == "allow"` doesn't reset `--uid` mode, doesn't clear `revoked-compat` or `user-fixed` flags.
  5. `media` misses Android 14/15 `android.permission.READ_MEDIA_VISUAL_USER_SELECTED`; `background_location` misses restricting `FINE_LOCATION`/`COARSE_LOCATION` to `foreground`.
- **User Impact**: `Palsukan` (`ignore`) and `Saat Dibuka` (`foreground`) fail to actually block runtime permissions on Android 14–16; `Cabut` silently fails on fixed/special perms or reads back as `allow`; `Normal` cannot undo `--uid`/`revoked-compat` locks.
- **Concrete Fix**:
  - Tag each `PermCategorySpec` op with whether it is UID-scoped (runtime-perm-backed + `RUN_ANY_IN_BACKGROUND`) vs package-scoped.
  - For runtime-perm ops: execute `cmd appops set --user 0 --uid <pkg> <op> <mode>` (and reset stale package override to `default`, respecting AOSP's warning not to leave conflicting pkg+uid modes). For `ignore` on Android 15/16 if `set --uid` fails/no-ops, fallback to `cmd package set-permission-flags --user 0 <pkg> <perm> revoked-compat` (and clear `revoked-compat` on `allow`/`foreground`).
  - For `revoke`: run `pm revoke` only on declared/granted runtime perms, capture/report errors if all fail, set `--uid` op to `ignore` (or `deny` for non-runtime ops to distinguish from `ignore`), and call `cmd appops write-settings` if needed.
  - Add `android.permission.READ_MEDIA_VISUAL_USER_SELECTED` to `media.runtime_perms`.
  - Return `runtime_granted: bool` inside `CategoryPermLiveState` (read from `dumpsys package` or `pm` in daemon) so UI and `PermissionEnforcer` have one authoritative source of truth.

#### [D-03] CRITICAL — `inspect_app_permissions` multi-op priority masks `allow` behind any single `ignore` op
- **Location**: `daemon/src/scopes.rs:220-254` (lines 229–238)
- **Code Evidence**:
  ```rust
  let prio = match mode.as_str() {
      "ignore" | "deny" => 4,
      "foreground" => 3,
      "allow" => 2,
      _ => 1,
  };
  if prio > best_priority { best_priority = prio; best_mode = mode.clone(); }
  ```
- **Root Cause**: Highest priority is given to `ignore`/`deny` instead of highest exposure (`allow`). In multi-op categories (`media` has `MANAGE_EXTERNAL_STORAGE`; `background` has `GET_USAGE_STATS`/`SYSTEM_ALERT_WINDOW`), if even ONE op defaults to `ignore`, the entire category reports `"ignore"` even when all other ops are `"allow"`.
- **User Impact**: UI falsely displays protected `PALSU KOSONG` while the app retains active `allow` access; `PermissionEnforcer` (`scopes.rs:596, 613`) sees `"ignore"` and skips re-enforcement!
- **Concrete Fix**: Invert aggregation to **fail-closed (least-restrictive active op wins)**: if any op in the category is `allow` -> report `"allow"`; else if any is `foreground` -> `"foreground"`; else if all present ops are `deny` -> `"deny"`; else if any is `ignore` (and none looser) -> `"ignore"`; else `"default"`. For `PermissionEnforcer`, consider a category drifted if **ANY** op in `spec.appops` is NOT `ignore`/`deny` (or if any `runtime_perm` is still granted).

#### [D-04] CRITICAL — Global `state_mtx` held across blocking CLI subprocesses + single-bridge serialization causes UI freezes & timeouts ("tidak respon")
- **Location**: `daemon/src/main.rs:216-275, 340-344, 779-797, 969-974, 1039-1042, 1054-1061, 1092-1160`; `daemon/src/ipc.rs:266`; `app/.../SiloClient.kt:39-85`
- **Code Evidence**:
  - `main.rs:340-343`: `GetStatus` locks `state_mtx`, then calls `st.status()` which executes **9 sequential subprocesses** (`dumpsys sensor_privacy` + 8× `settings get global`).
  - `main.rs:1092-1160`: Supervisor locks `state_mtx` every 5s across firewall, freezer, enforcer (`cmd appops`/`pm revoke`), clipboard (`dumpsys`), and automation (`dumpsys`/`settings`).
  - `main.rs:779-797`: `SetAppPermGuard` locks `state_mtx` while revoking every category in `locked_deny`.
  - `main.rs:1039-1042`: NFLOG thread locks `state_mtx` AND clones entire `uid_map` on **every blocked packet**!
  - `main.rs:1054-1061`: 400ms fast-thaw thread blocks on `state_mtx`, stalling frozen app launches!
  - `ipc.rs:266`: Hardcoded 15s bridge socket read timeout contradicts `SiloRepository.kt:125,230,312` (`20s`–`25s` timeouts).
- **Root Cause**: Coarse single mutex protects both fast in-memory state and slow multi-second Android Binder CLI invocations; Kotlin client serializes all polling + user actions through one `Mutex` with a 10s timeout.
- **User Impact**: Permission buttons freeze/time out silently (especially behind `ModalBottomSheet` where snackbars are hidden); Monitor tab starves user actions; frozen apps lag/ANR on launch; NFLOG drops packets; bulk revoke/blocklist import fails at 15s.
- **Concrete Fix**:
  1. **Never hold `state_mtx` across any external subprocess (`run_cmd`)!** Snapshot required config/state under a <1ms lock, drop the lock, execute subprocesses, then re-lock briefly to commit results.
  2. Cache `sensor_privacy`, `private_dns`, and `radio_privacy` in a separate `Arc<RwLock<CachedSystemState>>` refreshed asynchronously by the supervisor (and invalidated on write commands) so `GetStatus` executes in **<1ms with 0 subprocesses**!
  3. Store `uid_map` in a separate `Arc<RwLock<UidPackageIndex>>` so NFLOG never touches `state_mtx` or clones the map.
  4. Raise `ipc.rs:266` bridge socket timeout to `45s` (or pass per-request timeout / keepalive) and parallelize `get_privacy_access_log` across scoped threads (or single `dumpsys appops` pass/batched ops) to cut latency from ~5s to <400ms.
  5. Stop double-polling (`onForegroundChanged` 4s + `TrafficScreen` 3s both hitting `get_status`) and give user mutation commands priority over background polls on the client.

#### [D-05] CRITICAL — `Kunci Blokir` (`locked_deny`) polls only every 15–20s, never sets `user-fixed`, and `SetAppPermissionAction` ignores active locks
- **Location**: `daemon/src/scopes.rs:567-621`; `daemon/src/main.rs:207-214, 760-798, 1074`
- **Code Evidence**:
  ```rust
  // main.rs:1074
  thread::sleep(Duration::from_secs(5));
  // scopes.rs:568 & 606
  let do_periodic_sweep = now.saturating_sub(self.last_strict_sweep_boottime) >= 15;
  if !guard.locked_deny.is_empty() && do_periodic_sweep { ... }
  ```
- **Root Cause**:
  1. `locked_deny` only checks every 15–21s.
  2. Never sets `pm set-permission-flags --user 0 <pkg> <perm> user-set user-fixed` to block in-app permission dialogs.
  3. `sync_all_subsystems()` (`main.rs:207-214`) never enforces `cfg.guards.locked_deny` on daemon boot.
  4. `SetAppPermGuard` (`main.rs:792-796`) re-revokes ALL `locked_deny` categories even when only toggling `auto_revoke_on_bg`.
  5. `SetAppPermissionAction` (`main.rs:760-767`) never checks or updates `cfg.scopes.guards`, so tapping `Normal`/`Saat Dibuka`/`Palsukan` on a locked category gets silently overwritten 15s later!
  6. `scopes.rs:589` & `607` calls `inspect_app_permissions(pkg)` twice if both guards are set on one app.
- **User Impact**: Exact user-reported `Kunci Blokir` delay + unexpected process force-stop after ~15–20s; manual mode overrides silently revert.
- **Concrete Fix**: See Section 2.2 (use `user-fixed`, dedicated `inotify` + 1s foreground watcher thread, enforce `locked_deny` at startup, only apply newly added locks in `SetAppPermGuard`, and reject or auto-unlock `locked_deny` when user explicitly selects `allow`/`foreground`/`ignore` via `SetAppPermissionAction`—or inform UI).

#### [D-06] CRITICAL — `TriggerPanicLockdown` & switching to `DefaultDeny` fails to block apps that had prior `DefaultAllow` custom rules!
- **Location**: `daemon/src/main.rs:911-944`; `daemon/src/firewall.rs:745-778`
- **Code Evidence**:
  ```rust
  // main.rs:915-919
  st.config.firewall.enabled = true;
  st.config.firewall.mode = FirewallMode::DefaultDeny;
  st.temp_unblock_until.clear();
  let firewall_locked = st.sync_firewall().is_ok();
  ```
  In `firewall.rs:750-773` (`FirewallMode::DefaultDeny`), ANY app in `cfg.rules` with `allow_wifi == true` / `allow_cellular == true` / `allow_vpn == true` gets `-j RETURN` (ALLOWED)!
- **Root Cause**: In `DefaultAllow` mode, toggling ANY per-app option (e.g., `block_cleartext_http = true`, `allow_screen_off = false`, `allow_lan = true`, or blocking only Cellular) saves an `AppFirewallRule` with `allow_wifi = true` (default). When the user activates **Panic Lockdown** (which promises to cut ALL user app internet!), `DefaultDeny` treats those saved `allow_* = true` flags as explicit allowlist entries!
- **User Impact**: **Major security loophole** — during Panic Lockdown, apps with previous custom rules continue sending/receiving internet traffic!
- **Concrete Fix**: Add a `panic_lockdown_active: bool` flag (or in `TriggerPanicLockdown` temporarily ignore `cfg.rules` allow entries in `SILO_OUT` while lockdown is active, OR separate `DefaultAllow` deny rules from `DefaultDeny` allowlist rules). Ensure `DefaultDeny` in Panic Lockdown drops **all** non-protected UIDs unconditionally until user explicitly exits lockdown!

#### [D-07] HIGH — `main.rs:1081-1085` + `sys.rs:204-239` hardcodes `locked_now = false` while screen is on / Samsung AOD active, breaking Auto-Reboot BFU & Lockscreen Sensors!
- **Location**: `daemon/src/main.rs:1080-1085`; `daemon/src/sys.rs:204-239`
- **Code Evidence**:
  ```rust
  let screen_now = detect_screen_on();
  let locked_now = if !screen_now || tick_count.is_multiple_of(3) {
      detect_device_locked()
  } else {
      false
  };
  ```
- **Root Cause**:
  1. On 2 out of every 3 supervisor ticks when `screen_now == true` (lockscreen awake, notification wake, or Samsung Always-On Display!), `locked_now` is hardcoded to `false`!
  2. On Samsung One UI 7, Always-On Display (AOD) keeps `/sys/class/backlight/panel/brightness > 0` while `mWakefulness=Dozing`, so `detect_screen_on()` returns `true` all night!
- **User Impact**: Every notification or AOD cycle resets `AutomationEngine.locked_since_boottime = None` (Auto-Reboot BFU **never triggers**!), unmutes Mic/Camera while the phone is **still locked** (`automation.rs:73-75`), and disables screen-off firewall, Auto-Cabut on screen-off, and screen-off freezer!
- **Concrete Fix**: Never default `locked_now` to `false` — preserve previous `st.device_locked` when skipping `detect_device_locked()`, and ALWAYS poll `detect_device_locked()` whenever `st.device_locked == true` or `screen_changed`. For `detect_screen_on()`, verify `dumpsys power` `mWakefulness=Awake` (or `/sys/class/lcd/panel/...` / `dumpsys display` `mState=ON`, excluding `DOZE`/`DOZE_SUSPEND`) whenever backlight > 0 on Samsung!

#### [D-08] HIGH — Security Loophole: Multi-user / Work Profile / Samsung Dual Messenger / Secure Folder UIDs completely bypass Firewall!
- **Location**: `daemon/src/firewall.rs:578-579, 595-596, 637-643, 652-658, 673-677, 775-776`
- **Code Evidence**:
  ```rust
  s.push_str("-A SILO_OUT -m owner --uid-owner 10000-19999 -j SILO_DENY_UID\n");
  s.push_str("-A SILO_OUT -m owner --uid-owner 110000-119999 -j SILO_DENY_UID\n");
  ```
- **Root Cause**: Hardcoded ranges only cover User 0 (`10000-19999`) and User 1 (`110000-119999`). On Samsung One UI 7, Work Profile is User 10 (`1010000-1019999`), Dual Messenger is User 95 (`9510000-9519999`), and Secure Folder is User 150 (`15010000-15019999`). Also UIDs `20000-89999` are ignored.
- **User Impact**: Apps in Work Profile, Dual Messenger (e.g., 2nd WhatsApp/Telegram), and Secure Folder completely bypass Default-Deny, LAN Isolation, DNS Leak Guard, IPv6 VPN Leak Guard, and Cleartext HTTP Block!
- **Concrete Fix**: Dynamically enumerate active Android users from `/data/system/users/` or `/data/user/` (or `uid_map` / `pm list users`), exempting `U*100000..U*100000+9999` and `U*100000+90000..U*100000+99999`, and applying user-app guards to `U*100000+10000..U*100000+89999` for every present user ID `U` (including 0, 10, 95, 150)!

#### [D-09] HIGH — Security Loophole: Missing `xfrm+` (Android native IKEv2/IPsec VPN) & `eth+`/`rndis+`/`v4-pdp+`/`ap+` interfaces in Firewall & Netlink classifier
- **Location**: `daemon/src/firewall.rs:17-26`; `daemon/src/netlink.rs:363-385`
- **Code Evidence**: `VPN_IFACES = &["tun+", "wg+", "ppp+", "ipsec+"]`; `CELL_IFACES = &["ccmni+", "v4-ccmni+", "rmnet+", "v4-rmnet+", "pdp+", "clat+"]`; `WIFI_IFACES = &["wlan+", "swlan+"]`.
- **Root Cause**: Android 12–16 native IPsec/IKEv2 VPNs use **`xfrm+`**, Samsung Exynos/MTK cellular can use `v4-pdp+`/`wwan+`/`seth_lte+`, hotspot uses `ap+`, USB reverse tethering/Ethernet uses `eth+`/`rndis+`/`ncm+`/`usb+`.
- **User Impact**: IPv6 VPN Leak Guard fails to activate for native IPsec VPNs; per-app VPN/Wi-Fi/Cellular blocks and DNS Leak Guard are bypassed over `xfrm+`, `eth+`, `rndis+`, `v4-pdp+`.
- **Concrete Fix**: Add `xfrm+` to `VPN_IFACES` and `push_classified_iface`; add `ap+` to `WIFI_IFACES`; add `v4-pdp+`, `wwan+`, `seth_lte+` to `CELL_IFACES`; handle `eth+`/`rndis+`/`ncm+` explicitly (or block all `! -o lo` minus allowed interfaces in per-app deny rules!).

#### [D-10] HIGH — Security Loophole: Client-supplied `package_name` bypasses protected package checks in `SetAppFirewallRule`, `SetFreezerEntry`, and `KillConnection`
- **Location**: `daemon/src/main.rs:399-417, 535-550, 592-606, 624-646`; `daemon/src/netlink.rs:625-655`; `daemon/src/sys.rs:158-176`
- **Code Evidence**:
  - `main.rs:400`: `if is_protected_uid(uid) || is_protected_package(&rule.package_name)` — trusts caller's `rule.package_name`! If empty `""` (as `TrafficScreen.kt:143` sends when `packageName == null`) or spoofed, UID `10xxx` of `dev.silo.app`, `com.rifsxd.ksunext`, or `com.samsung.android.honeyboard` is NOT blocked by `is_protected_uid(uid)`.
  - `main.rs:535-550` (`KillConnection`): calls `destroy_matching_connection` WITHOUT checking `is_protected_uid(sk.uid)` or `sk.state != 10` (LISTEN)!
  - `sys.rs:173`: `read_uid_package_map` only keeps the first package per UID (`or_insert_with`), missing protected packages sharing a UID.
- **User Impact**: Accidental or malicious freezing/blocking of Silo, KernelSU-Next, or keyboard; `KillConnection` can tear down root/`adbd`/`netd` or `LISTEN` sockets.
- **Concrete Fix**: Build `uid_to_pkgs: HashMap<u32, Vec<String>>` from `/data/system/packages.list`; check `is_protected_uid(uid) || uid_has_protected_pkg(uid)` in `SetAppFirewallRule`, `SetFreezerEntry`, `FreezeUidNow`, and `KillConnection` (and skip `LISTEN`/`TIME_WAIT` sockets in `destroy_matching_connection`).

#### [D-11] HIGH — `Monitor -> Akses Izin` (`parse_dumpsys_appops_op`) misparses modes, drops running background accesses, shows un-revocable protected apps, and runs 11 sequential `dumpsys` calls
- **Location**: `daemon/src/scopes.rs:328-459`
- **Code Evidence**:
  - Line 409: `t.starts_with(op_name) && t.ends_with(':')` matches prefix-colliding ops (e.g. `CAMERA` matches `CAMERA_SANDBOXED`).
  - Line 414: `.split('/').next()` reads the first mode (`allow`) on `OP (allow/ignore):` or `OP (allow / switch ignore):`, showing revoked ops as still `allow`!
  - Line 435: only skips `uid < 10000 || pkg == "dev.silo.app"`, NOT `is_protected_package(pkg)`.
  - Line 456: `in_matching_op = false;` stops after the FIRST `Access:` line (ordered by uid-state, NOT recency), dropping active `[fg-s]`/`[bg]` `(running)` entries!
  - Lines 348–354: 11 sequential `dumpsys appops --op` subprocesses (~2–6s).
- **User Impact**: Clicking `Cabut` in Monitor appears broken (protected packages error out; revoked packages still show `allow` and stay in list); real-time background spying (`[bg]`/`[fg-s]` running) can be hidden behind an older `[top-s]` line.
- **Concrete Fix**: Require exact op token boundary (`t.strip_prefix(op_name)` followed by `' '` or `'('` or `':'`); parse both UID mode and package mode (pick stricter / effective mode); filter out `is_protected_package(pkg)`; scan ALL `Access:` lines under the matching op and keep the freshest/running one; run `dumpsys appops --op` queries concurrently via bounded worker threads (e.g. 4 threads) or single filtered parse, and have `TrafficScreen` show live effective status (`DICABUT`/`PALSU`) and disable/hide `Cabut` for already-revoked entries.

#### [D-12] HIGH — `freezer.rs` cgroup path discovery can freeze shared cgroups + `pm suspend` state lost across daemon restarts
- **Location**: `daemon/src/freezer.rs:13-18, 110-138, 163-179, 244-264, 268-327`
- **Code Evidence**:
  - `freezer.rs:310-318`: reads `0::/rel_path` from `/proc/<pid>/cgroup` and writes `"1"` to `/sys/fs/cgroup/<rel_path>/cgroup.freeze` **without checking that `<rel_path>` belongs exclusively to `uid_<uid>`**!
  - `freezer.rs:14`: `suspended_packages: BTreeSet<String>` is RAM-only. After daemon restart/crash, `thaw_all()` and `FreezerStatus` forget suspended packages, leaving apps permanently greyed-out/suspended in Android!
  - `freezer.rs:244-264`: `DeepStop` auto-suspends on background/screen-off and never unsuspends; `SoftCgroup` `already_handled` ignores newly spawned PIDs for a frozen UID; `last_fg_seen.unwrap_or(0)` freezes newly added entries immediately without grace delay.
- **User Impact**: Potential system hang on OEMs using shared cgroups; apps permanently trapped in suspended state after daemon restart; DeepStop auto-freeze locks user out of apps.
- **Concrete Fix**: Strictly require `trimmed.split('/').any(|seg| seg == format!("uid_{}", uid))` before writing `cgroup.freeze`; probe live suspended state from `pm list packages --user 0` / `dumpsys package` (or persist `suspended_packages` / query `cmd package` on startup and in `thaw_all()`); seed `last_fg_seen` on entry insertion; re-freeze new PIDs under `uid_<uid>` on each tick.

#### [D-13] HIGH — `module/service.sh` & `uninstall.sh` can kill a random Android system process on boot via stale PID reuse + socket unlink race
- **Location**: `module/service.sh:58-66`; `module/uninstall.sh:11-18`; `module/action.sh:31-37`; `daemon/src/ipc.rs:149`
- **Code Evidence**:
  ```sh
  if [ -f "$PID_FILE" ]; then
    OLD_PID="$(cat "$PID_FILE" 2>/dev/null | tr -cd '0-9')"
    if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
      kill -TERM "$OLD_PID" 2>/dev/null || true
  ```
- **Root Cause**: `/data/adb/silo/silod.pid` is on persistent `/data` and survives reboot! After reboot, `OLD_PID` belongs to a completely different Android process, which `service.sh` blindly sends `SIGTERM` to! Additionally, if an old `silod` takes >1s in `thaw_all()`, `ipc.rs:149` (`fs::remove_file(socket_path)`) deletes the NEW `silod`'s Unix socket; and `action.sh` can spawn duplicate daemons without stopping the old one.
- **User Impact**: Random Android service crash at boot; daemon unreachable if socket is deleted during restart; duplicate daemons fighting over iptables/appops.
- **Concrete Fix**: In `service.sh`/`uninstall.sh`/`action.sh`, verify `[ -r "/proc/$OLD_PID/cmdline" ] && grep -q silod "/proc/$OLD_PID/cmdline"` before signaling, wait up to 5s for exit before starting new `silod`, and acquire an exclusive `flock` on `/data/adb/silo/silod.lock` inside `silod` (only unlinking `socket_path` on exit if inode matches the socket `silod` bound!).

#### [D-14] HIGH — `firewall.rs` IPv6 failure permanently disables IPv4 extensions (`NFLOG`, `xt_recent`) and silently drops ipset (up to 48,000 CIDRs!)
- **Location**: `daemon/src/firewall.rs:334-380`
- **Code Evidence**: `apply_table_with_fallback` mutates `&mut KernelCapabilities` directly on an `ip6tables-restore` error (lines 338–367), disabling `caps.nflog_available` / `caps.xt_recent_available` for IPv4 too! And line 375 hardcodes `use_ipset = false` on retry even when the failure was `TTL`/`HL`/`recent`.
- **User Impact**: One IPv6 OEM quirk permanently kills IPv4 NFLOG telemetry and anti-port-scan; any non-ipset target error silently drops up to 48,000 blocklisted CIDRs (truncating to `FALLBACK_CHAIN_MAX_CIDRS = 2000`).
- **Concrete Fix**: Track v4 and v6 capabilities separately (`nflog_v4`/`nflog_v6`, `recent_v4`/`recent_v6`), and keep `use_ipset` enabled on retry unless the error actually mentions `set` / `ipset`.

#### [D-15] HIGH — `netlink.rs` `ipset_atomic_sync` fails with `-EEXIST` when growing blocklist size & 50k synchronous round-trips time out bridge
- **Location**: `daemon/src/netlink.rs:875-904`
- **Code Evidence**: Line 888 calls `ipset_create_set(&fd, proto, set_name, family, rev, maxelem)` on an already-existing set whose `maxelem` was smaller (e.g., 1024 -> 5000); Linux `ip_set_create` rejects parameter mismatch with `EEXIST`. Lines 895–899 perform up to 50,000 sequential `sendto` + `recv` ACK calls, exceeding `ipc.rs:266`'s 15s read timeout.
- **User Impact**: Importing a larger blocklist after a smaller one silently falls back to the 2,000-entry linear iptables chain or times out!
- **Concrete Fix**: Always create `set_name` with a fixed `maxelem = 65535` (or ignore `EEXIST` on `set_name` since `tmp_name` is swapped in atomically!), and batch Netlink `IPSET_CMD_ADD` requests without per-item `NLM_F_ACK` (only checking error replies or batching 256 per `recv`), plus increase bridge read timeout.

#### [D-16] HIGH — `firewall.rs` inbound blocklist bypassed by `ESTABLISHED,RELATED` & missing in fallback mode; `is_vpn_app` bypasses its own per-app firewall rules
- **Location**: `daemon/src/firewall.rs:544-567, 609-617`
- **Code Evidence**:
  - `SILO_IN` puts `-m conntrack --ctstate ESTABLISHED,RELATED -j RETURN` (line 545) BEFORE `-m set --match-set ... src -j SILO_DENY_IPSET` (line 563), and omits inbound IP blocklist entirely when `!use_ipset`.
  - `SILO_OUT` step 3 (`lines 610-617`) emits `-m owner --uid-owner <vpn_uid> -j RETURN` BEFORE IP blocklist, LAN, HTTP, and per-app Wi-Fi/Cell/Screen-Off rules!
- **User Impact**: Active connections from newly blocklisted IPs survive; fallback blocklist doesn't filter ingress; marking an app as VPN provider completely disables its own Wi-Fi/Cell/Screen-Off/HTTP rules.
- **Concrete Fix**: Place `ip_blocklist` check BEFORE `ESTABLISHED,RELATED` in `SILO_IN` (and add `SILO_BLK_IP_IN` when `!use_ipset`); restrict `is_vpn_app` exemption strictly to DNS Leak Guard, IPv6 Leak Guard, and LAN (not skipping per-app interface/screen-off rules).

#### [D-17] HIGH — `ClipboardGuard` (`probe_clipboard_metadata_hash`) broken by `"null"` substring check & `cmd clipboard` unsupported on Android 12–16
- **Location**: `daemon/src/scopes.rs:714-795` (lines 728–729, 781)
- **Code Evidence**:
  ```rust
  if t.contains("null") || t.contains("empty") { return None; }
  ```
- **Root Cause**: Standard `dumpsys clipboard` lines like `ClipDescription { text/plain label=null ... }` contain `"null"`, causing `probe_clipboard_metadata_hash()` to ALWAYS return `None`! Additionally `cmd clipboard clear` / `set-text` does not exist on standard AOSP/One UI 7 Android 15 (requiring `service call clipboard` or helper).
- **User Impact**: Background Auto-Clear Clipboard never fires!
- **Concrete Fix**: Check specifically for `Primary clip: null` or `mPrimaryClip=null` / empty clip count rather than any `"null"` substring; verify/support Android 12–16 clipboard clear command (`cmd clipboard` if supported, else `service call clipboard` with API-versioned transaction or `am broadcast` to Silo's internal clear receiver).

#### [D-18] MEDIUM — `Auto-Cabut` (`auto_revoke_on_bg`) misses shared-UID apps & apps with Foreground Services (`oom_score_adj <= 100`)
- **Location**: `daemon/src/scopes.rs:558-585`; `daemon/src/sys.rs:158-176, 271-303`
- **Root Cause**:
  1. `fg_pkgs` maps `fg_uids` through single-value `uid_map` (`HashMap<u32, String>`): any 2nd package sharing a UID is ALWAYS treated as background and revoked while open!
  2. `foreground_app_uids()` treats `oom_score_adj <= 100` as foreground, which includes background Foreground Services (`FGS`, adj 50–100) and visible overlays — so apps keeping a foreground service active (the exact spyware case!) NEVER trigger `Auto-Cabut` when closed!
- **Concrete Fix**: Look up `pkg -> uid` directly via `pkg_to_uid` map; distinguish **top visible activity** (`oom_score_adj == 0` or `dumpsys activity` top resumed package) from background FGS (`oom_score_adj == 50..100`) for `Auto-Cabut`!

#### [D-19] MEDIUM — `firewall.rs:275-293` doesn't run `SOCK_DESTROY` on single-interface blocks or `DefaultDeny` switch; `netlink.rs` skips UDP/QUIC and opens 1 socket per kill
- **Location**: `daemon/src/firewall.rs:275-293`; `daemon/src/netlink.rs:559-623`
- **Root Cause**: `currently_blocked` only includes UIDs with `!allow_wifi && !allow_cellular && !allow_vpn` (or screen-off). Blocking Wi-Fi while on Wi-Fi (with cellular still allowed) or switching to `DefaultDeny` doesn't trigger `SOCK_DESTROY`. `destroy_uid_sockets` only kills `IPPROTO_TCP` (not connected UDP/QUIC) and opens/closes a new Netlink FD per socket. Also `1u32 << sk.state` is unchecked if `sk.state >= 32`.
- **Concrete Fix**: Trigger `SOCK_DESTROY` whenever an active interface is newly denied for a UID (comparing against `ifaces.wifi`/`cellular`/`vpn`) and on `DefaultAllow -> DefaultDeny` transition; reuse one Netlink FD across kills; use `checked_shl`; optionally destroy connected `IPPROTO_UDP` sockets too.

#### [D-20] MEDIUM — `is_ce_unlocked()` fallback `/data/user/0/android` exists in BFU (Auto-Reboot loop risk!)
- **Location**: `daemon/src/sys.rs:192-200`
- **Code Evidence**: `Path::new("/data/user/0/android").exists()`
- **Root Cause**: Under FBE, the directory entry `/data/user/0/android` (via `/data/data/android`) can exist even in BFU before first unlock. If `getprop` transiently fails, `is_ce_unlocked()` falsely returns `true`, arming Auto-Reboot BFU while already in BFU!
- **Concrete Fix**: Fail-safe to `false` if `sys.user.0.ce_available` isn't `"true"`/`"1"` (or verify `dumpsys user` contains `RUNNING_UNLOCKED` for user 0, never `.exists()` on a directory).

#### [D-21] MEDIUM — `ApplyPreset` fails to reset automation toggles when switching from `Paranoid` back to `Balanced` or `Safe`
- **Location**: `daemon/src/config.rs:545-617`
- **Code Evidence**: `Paranoid` enables `wifi_auto_off_enabled`, `bluetooth_auto_off_enabled`, `usb_adb_lock_guard`, `sensors_off_on_lock`. Switching to `Safe` leaves `wifi_auto_off_enabled` and `bluetooth_auto_off_enabled` enabled; switching to `Balanced` leaves all 4 stuck ON!
- **Concrete Fix**: Explicitly set all automation/hardening preset fields in `Safe`, `Balanced`, and `Hardened` branches.

#### [D-22] MEDIUM — `enforce_usb_adb_lockdown` omits `adb_wifi_enabled`; `get_radio_privacy` conflates standard & enhanced MAC randomization; `set_sensor_privacy("all")` masks partial failure
- **Location**: `daemon/src/automation.rs:144-153, 221-294, 308-322, 388-391`
- **Root Cause**:
  1. `enforce_usb_adb_lockdown` disables `adb_enabled` but leaves Wireless ADB (`adb_wifi_enabled`) active on lockscreen.
  2. `get_radio_privacy` uses `mac_conn || mac_enh` (false ON on stock ROMs where standard MAC randomization is already 1) and turning it OFF writes `0` to `wifi_connected_mac_randomization_enabled` too (weakening baseline privacy!).
  3. `apply_sensor_privacy_target("all", ...)` succeeds if only 1 of 2 sensors worked (`ok_any`).
  4. `parse_sensor_privacy_dump` line 388 uses `t.contains("state_type=1")` without token boundary check.
- **Concrete Fix**: Add `settings put global adb_wifi_enabled 0` to USB/ADB lock guard; read/write ONLY `wifi_enhanced_mac_randomization` (keep `wifi_connected_mac_randomization_enabled = 1` when turning enhanced OFF); require ALL target sensors to succeed for `"all"`; match exact tokens in `parse_sensor_privacy_dump`.

#### [D-23] MEDIUM — `HardeningEngine` loses `original_values` across daemon restart & fails to roll back cleared `tcp_congestion`/`default_qdisc`
- **Location**: `daemon/src/hardening.rs:21, 243-262, 278-300`
- **Root Cause**: `original_values` is RAM-only (restarting `silod` captures already-hardened values as "original"). Clearing `tcp_congestion` or `default_qdisc` to `""` omits them from `build_specs`, so `apply()` never restores their original values.
- **Concrete Fix**: Persist boot-time original sysctl snapshot in `/data/adb/silo/sysctl_orig.json` keyed by boot ID (`/proc/sys/kernel/random/boot_id`) so rollback survives daemon restarts within the same boot; restore `tcp_congestion`/`default_qdisc` from `original_values` when cleared to `""`.

#### [D-24] MEDIUM — `SiloClient.kt` never drains `errorStream` (stderr pipe deadlock risk) & `ipc.rs` doesn't retry once on daemon restart
- **Location**: `app/app/src/main/java/dev/silo/app/ipc/SiloClient.kt:105-116`; `daemon/src/ipc.rs:203-238`
- **Root Cause**: `pb.redirectErrorStream(false)` leaves `p.errorStream` unread (blocks child if >64KB written); `run_stdio_bridge` returns an immediate error on broken socket instead of reconnecting once transparently when `silod` restarts.
- **Concrete Fix**: Redirect bridge stderr to `/dev/null` (or drain it on a daemon thread / logcat); in `run_stdio_bridge`, on write/read failure, reconnect once and retry the request before returning an error.

#### [D-25] MEDIUM — `sys.rs:77-80` zombie process leak on `try_wait` error & `netlink.rs:935-937` ignores NFLOG bind ACK error
- **Location**: `daemon/src/sys.rs:77-80`; `daemon/src/netlink.rs:919-938, 1076`
- **Root Cause**: `child.try_wait()` `Err` calls `child.kill()` without `child.wait()`. `nflog_send_cfg` discards the `NLMSG_ERROR` ACK body, so a failed bind spins silently. `parse_ip_packet_headers` assumes IPv6 payload starts immediately at offset 40 without walking extension headers.
- **Concrete Fix**: Call `let _ = child.wait();` after `child.kill()`; validate `parse_nl_ack` in `nflog_send_cfg` and log/retry on failure; walk IPv6 extension headers (0, 43, 44, 60) in `parse_ip_packet_headers`.

#### [D-26] LOW — `config.rs` silent wipe on corrupt `config.json` & missing parent-dir `fsync`
- **Location**: `daemon/src/config.rs:619-663`
- **Root Cause**: Parse error in `load_or_default` returns defaults silently; next save overwrites the user's file without backup. `save_atomic` syncs the temp file but not the parent directory fd after `rename`.
- **Concrete Fix**: Copy unparseable `config.json` to `config.json.corrupt.<ts>` before falling back to defaults; `fsync` parent dir after `fs::rename`.

#### [D-27] LOW — `sentinel.rs` Live Proof false negatives on non-`ignore` guards and intentionally unlocked ADB
- **Location**: `daemon/src/sentinel.rs:363-373, 404-417`; `daemon/src/scopes.rs:672-699`
- **Root Cause**: `count_live_ignored_ops` only counts `": ignore"` (misses `deny`/`foreground`/`revoked-compat`); line 408 marks `Hardware Sensors & Radio/ADB` failed whenever `adb_enabled_live && usb_adb_lock_guard`, even when the device is unlocked (where user is allowed to re-enable ADB until next lock).
- **Concrete Fix**: Count `ignore`, `deny`, `foreground` (using the fixed parser); evaluate USB/ADB lock guard proof only as informational when unlocked or pass when `device_locked => !adb_enabled_live`.

#### [D-28] CROSS-BOUNDARY (App UI/VM bugs directly compounding Daemon behavior — flag for App Auditor/Implementer)
- **`AppsScreen.kt:116-129` + `MainActivity.kt:109`**: `ModalBottomSheet` hides `Scaffold`'s `SnackbarHost` — all `Izin error:` / `Guard error:` toasts are invisible while the per-app sheet is open!
- **`AppsScreen.kt:895-923`**: `Palsukan` (`appopsMode == "ignore"`) and `Cabut` (`!runtimeGranted && (appopsMode == "ignore" || "deny")`) highlight simultaneously; `Normal` highlights even when `!runtimeGranted`!
- **`TrafficScreen.kt:176-179`**: `setAppPermissionAction` and `refreshPrivacyAccessLog` launch concurrently (race); need sequential await + visual feedback.
- **`AppsScreen.kt:297-301, 825-828`**: `setFreezerEntry` and `freezeUidNow` launch concurrently (race can freeze in `SoftCgroup` instead of `DeepStop`).

---

## 5. Daemon Interface Contracts

### 5.1 CLI Modes (`silod`)
| Command / Flags | Purpose | Exit Code |
|---|---|---|
| `silod [--socket PATH] [--config PATH] [--boot-marker PATH]` | Run persistent root daemon (default paths: `/data/adb/silo/silod.sock`, `/data/adb/silo/config.json`, `/data/adb/silo/.boot_fail_count`) | `0` clean stop / `1` bind error |
| `silod --bridge [--socket PATH]` | Persistent newline-delimited JSON-RPC stdio↔Unix-socket bridge (used by `SiloClient.kt` via `su -c`) | `0` on stdin EOF |
| `silod --once '<JSON>' [--socket PATH]` | Single JSON-RPC request/response printed to stdout | `0` ok / `1` err / `2` bad arg |
| `silod --status [--socket PATH]` | Shorthand for `--once '{"id":1,"cmd":"get_status"}'` | `0` ok / `1` err |
| `silod --emergency-flush [--config PATH]` | Flushes all `SILO_*` iptables/ip6tables chains & ipsets, thaws all `/sys/fs/cgroup/uid_*` freeze nodes, unsuspends configured freezer packages | `0` |
| `silod --version` | Prints `silod 3.0.0` | `0` |

### 5.2 IPC Wire Protocol
- **Transport**: Unix domain stream socket `/data/adb/silo/silod.sock` (`0600` in `0700` dir `/data/adb/silo`).
- **Authentication**: Linux `SO_PEERCRED` requiring `cred.uid == 0 || cred.uid == geteuid()`.
- **Framing**: Newline-delimited UTF-8 JSON (`\n`). Max request line: `512 KiB` (`MAX_IPC_LINE_BYTES`). Max bridge response line: `2 MiB` (`MAX_IPC_LINE_BYTES * 4`).
- **Request Envelope**: `{"id": <u64>, "cmd": "<snake_case_name>", "params": { ... }}` (`params` optional/null for 0-arg commands).
- **Response Envelope**: `{"id": <u64>, "ok": true, "data": <JSON>}` or `{"id": <u64>, "ok": false, "error": "<message>"}`.
- **Map Keys**: `BTreeMap<u32, V>` fields (`firewall.rules`, `freezer.entries`) serialize as JSON string keys (`"10277"`) and deserialize both string & integer keys via `deserialize_u32_map`.

### 5.3 Complete RPC Command Catalog (47 Commands — `daemon/src/protocol.rs:288-511`)
- **Core / Config (5)**: `ping`, `get_status` -> `DaemonStatus`, `get_config` -> `SiloConfig`, `apply_preset {preset}` -> `SiloConfig`, `export_config_json` -> `{json}`, `import_config_json {json}` -> `SiloConfig` (6 total)
- **Firewall (8)**: `set_firewall_config {config}`, `set_app_firewall_rule {uid, rule}`, `remove_app_firewall_rule {uid}`, `set_temp_unblock {uid, duration_minutes}`, `clear_temp_unblock {uid}`, `replace_ip_blocklist {entries}`, `emergency_flush_firewall` (7)
- **Sockets & Telemetry (5)**: `kill_uid_sockets {uid}`, `kill_connection {family, local_ip, local_port, remote_ip, remote_port}`, `get_live_connections`, `get_telemetry {limit?}`, `clear_telemetry`
- **Freezer (7)**: `set_freezer_enabled {enabled, background_grace_secs}`, `set_freezer_entry {uid, entry}`, `remove_freezer_entry {uid}`, `freeze_uid_now {uid}`, `thaw_uid_now {uid}`, `thaw_all_now`, `get_freezer_status`
- **Permissions, Scopes & Clipboard (10)**: `set_app_scope {package_name, scope}`, `apply_hardened_scope {package_name}`, `clear_app_scope {package_name}`, `inspect_app_permissions {package_name}` -> `AppLivePermissions`, `set_app_permission_action {package_name, category, action}` -> `AppLivePermissions`, `set_app_perm_guard {package_name, guard}` -> `ScopesConfig`, `bulk_revoke_category {category, packages}` -> `{category, revoked_count}`, `get_privacy_access_log {limit?}` -> `[PrivacyAccessEvent]`, `set_auto_clear_clipboard {enabled, clear_secs}`, `clear_clipboard_now`
- **Hardening (3)**: `set_hardening_config {config}`, `rollback_hardening`, `get_hardening_status`
- **Automation, Sensors, DNS, Radio & Panic (9)**: `set_automation_config {config}`, `set_sensors_off {enabled}`, `set_sensor_privacy {sensor, enabled}`, `get_private_dns`, `set_private_dns {mode, specifier}`, `get_radio_privacy`, `set_radio_privacy {enhanced_mac_randomization?, disable_wifi_ble_scanning?, disable_captive_portal?}`, `trigger_panic_lockdown` -> `PanicLockdownReport`, `trigger_panic_reboot {confirm_token: "CONFIRM_SILO_BFU_REBOOT"}`
- **Audit & Proof (2)**: `run_sentinel_audit` -> `SentinelReport`, `run_live_proof` -> `LiveProofReport`

*(Proposed backward-compatible DTO extension for permission fix: add optional `runtime_granted: bool` and `effective_state: String` fields to `CategoryPermLiveState` with `@SerialName` defaults in `SiloModels.kt` — `SiloClient.kt` already sets `ignoreUnknownKeys = true`!)*

### 5.4 Runtime State & Config Files (`/data/adb/silo`, `0700`)
- `/data/adb/silo/config.json` (`0600`): `SiloConfig` JSON (schema v3: `schema_version`, `firewall`, `freezer`, `scopes`, `hardening`, `automation`). Atomic write via `config.json.tmp.<pid>` + `rename`.
- `/data/adb/silo/silod.sock` (`0600`): Root-only Unix stream socket.
- `/data/adb/silo/silod.pid` (`0600`): Daemon PID written by `service.sh` / `action.sh`.
- `/data/adb/silo/.boot_fail_count` (`0600`): Boot stability counter armed by `service.sh`, cleared by `silod` after `BOOT_STABILITY_SECS = 45`.
- *(Proposed additions)*: `/data/adb/silo/silod.lock` (flock singleton guard), `/data/adb/silo/sysctl_orig.json` (boot_id-scoped sysctl rollback snapshot).

---

## 6. Caveats

1. **No Live Android Device Execution**: This environment is a Linux devbox without an attached `SM-A146P` One UI 7 device; all Android 15/16 `AppOpsService` and `PackageManagerShellCommand` behaviors were verified against official AOSP documentation/sources (`AppOps.md`, `AppOpsService.java`, `PackageManagerShellCommand.java`, Android Developer docs, and published Android 15/16 device verification records). One UI 7-specific `dumpsys appops` / `dumpsys clipboard` output nuances should be tested on-device after fixes.
2. **Offline Cargo Cache Unavailable**: `cargo test --offline` could not resolve `libc` from crates.io in this offline environment (no pre-populated Cargo cache). Unit test compilation/execution will require network-enabled Cargo or vendored crates during implementation/verification.
3. **App UI Scope**: Read key app files (`SiloClient.kt`, `SiloModels.kt`, `SiloRepository.kt`, `SiloViewModel.kt`, `AppInventory.kt`, `AppsScreen.kt`, `TrafficScreen.kt`, `MainActivity.kt`, `tiles/*.kt`) strictly to trace end-to-end daemon interactions; deep UI/Compose/Theme/HomeScreen/SystemScreen auditing belongs to the app survey explorer.

---

## 7. Conclusion

Silo v3.0.0's daemon has strong foundational engineering (zero-shell `run_cmd`, `SO_PEERCRED` auth, native Netlink `SOCK_DESTROY`/`ipset`/`NFLOG`, 3-layer boot-guard), **but suffers from 6 critical bugs and 11 high-severity loopholes** that directly cause every issue reported by the user:
1. **Immediate Priority (Fixes all 3 user complaints)**: Fix `parse_appops_get_output` (`D-01`), use `--uid` AppOps + `revoked-compat` + error checking (`D-02`), invert multi-op aggregation to fail-closed (`D-03`), decouple `state_mtx` from all blocking subprocesses & cache `GetStatus` (`D-04`), upgrade `Kunci Blokir` to use `user-fixed` + event-driven/`inotify` <1s enforcer (`D-05`), and fix `Monitor -> Akses Izin` parsing & concurrency (`D-11`).
2. **High-Priority Security & Stability**: Fix `TriggerPanicLockdown` bypassing prior custom rules (`D-06`), `device_locked = false` / Samsung AOD bug (`D-07`), multi-user/Work Profile/Dual Messenger/Secure Folder firewall bypass (`D-08`), missing `xfrm+`/interface coverage (`D-09`), UID-based protected-package verification & `KillConnection` guard (`D-10`), cgroup scope verification & `DeepStop` persistence (`D-12`), stale PID kill in `service.sh` (`D-13`), IPv6 capability mutation & ipset `EEXIST`/batching (`D-14`, `D-15`, `D-16`), and `ClipboardGuard` (`D-17`).

---

## 8. Verification Method

1. **Code Inspection**: Confirm every cited bug by viewing the exact `File:Line` ranges in `daemon/src/{scopes,main,ipc,firewall,netlink,freezer,automation,sys,hardening,config,sentinel}.rs` and `module/{service,action,uninstall}.sh`.
2. **Daemon Unit Tests (once crates available)**:
   ```bash
   cd /home/antigravity/terenzy/Silo/daemon && cargo test
   ```
   Add regression unit tests for:
   - `parse_appops_get_output` with `Uid mode: CAMERA: ignore` followed by `CAMERA: allow; time=+2m ago` -> must return `"ignore"`, NOT `"allow"`.
   - `inspect_app_permissions` multi-op category where 1 op is `ignore` and 1 op is `allow` -> must return `"allow"`, NOT `"ignore"`.
   - `parse_dumpsys_appops_op` with `CAMERA (allow/ignore):`, `CAMERA_SANDBOXED (allow):`, protected packages, and multiple `Access:` lines where the 2nd line is `(running)`.
   - `build_restore_script` for Panic Lockdown with pre-existing `DefaultAllow` custom rules, Work Profile/Dual Messenger/Secure Folder UIDs (`1010050`, `9510050`, `15010050`), and `xfrm+` VPN interface.
   - `discover_uid_cgroup_freeze_paths` rejecting shared cgroup paths lacking `uid_<uid>`.
   - `probe_clipboard_metadata_hash` with `ClipDescription { text/plain label=null }` returning `Some(...)`.
   - `AutomationEngine` preserving `device_locked` & BFU timer across screen wake / AOD ticks.
3. **Cross-Compilation & Module Packaging Check**:
   ```bash
   cd /home/antigravity/terenzy/Silo/daemon && cargo build --release --target aarch64-unknown-linux-musl
   cd /home/antigravity/terenzy/Silo && ./scripts/build_module.sh
   ```
4. **On-Device Verification (`SM-A146P` One UI 7)**:
   - Revoke `Kamera` on `com.sec.android.app.camera` -> verify `cmd appops get --user 0 com.sec.android.app.camera` and confirm Silo UI highlights `Cabut` (NOT `Normal`) and `Kunci Blokir` stops looping.
   - Enable `Kunci Blokir` -> open target app -> verify in-app permission dialog is suppressed immediately (`user-fixed`), and if manually granted in Android Settings, verify re-revocation occurs in **<1–2s** (not 15–20s).
   - Rapidly tap permission buttons in per-app sheet and Monitor -> `Akses Izin` while traffic polling is active -> verify <1s response and zero bridge timeouts.
5. **Invalidation Conditions**: Any finding regarding One UI 7's exact `dumpsys appops` / `dumpsys clipboard` formatting or `cmd package set-permission-flags` support should be re-checked if Samsung customizes `AppOpsCommand`/`PackageManagerShellCommand` output on a newer One UI patch.
