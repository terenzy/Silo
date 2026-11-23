# Forensic Integrity Audit Report — Milestone M1 (`daemon-core`, Round 3)

- **Auditor:** `iter1_auditor_m1` (`teamwork_preview_auditor`)
- **Audit Type:** **INTERMEDIATE MILESTONE AUDIT** (Milestone M1: `daemon-core`)
- **Audit Timestamp (UTC):** `2026-11-23T05:30:02Z`
- **Audited Worker Handoff:** `/home/antigravity/terenzy/Silo/.agents/teamwork/sub_orch_m1_daemon_core_r3/iter1_worker_r3/handoff.md`
- **Final Verdict:** **`CLEAN`**

---

## Executive Summary

Independent static, diff, test-integrity, and runtime forensic verification across all 7 M1-owned files found **ZERO integrity violations, ZERO cheating, ZERO unauthorized out-of-scope edits, ZERO lint suppressions, ZERO stubs/mocked returns, ZERO weakened/ignored tests, and ZERO build/clippy/test failures**. Every technical claim in `iter1_worker_r3/handoff.md` matches the actual code on disk and passes independent execution, including live testing against the compiled `silod` binary.

---

## 1. Scope & Diff Forensics

### 1.1 Owned Sub-Scope Compliance (`daemon/`)
Command run in `/home/antigravity/terenzy/Silo`:
```text
git status --short -- daemon/
 M daemon/Cargo.toml
 M daemon/src/config.rs
 M daemon/src/ipc.rs
 M daemon/src/main.rs
 M daemon/src/protocol.rs
 M daemon/src/scopes.rs
 M daemon/src/sys.rs

git diff --stat -- daemon/
 daemon/Cargo.toml      |    2 +-
 daemon/src/config.rs   |  276 ++++-
 daemon/src/ipc.rs      |  602 ++++++++-
 daemon/src/main.rs     | 2657 +++++++++++++++++++++++++++++++---------
 daemon/src/protocol.rs |  125 +-
 daemon/src/scopes.rs   | 3178 +++++++++++++++++++++++++++++++++++++++++++-----
 daemon/src/sys.rs      |  793 +++++++++++-
 7 files changed, 6667 insertions(+), 966 deletions(-)
```
- **ONLY the 7 exclusive M1 files** were modified under `daemon/`.
- **Zero M2-owned files modified**: `daemon/src/{firewall,netlink,freezer,automation,hardening,sentinel,telemetry}.rs`, `daemon/Cargo.lock`, and `daemon/.cargo/config.toml` are 100% untouched.
- **Sibling Wave 1 tracks untouched by M1**: pre-existing modifications in `app/`, `module/`, `scripts/`, `.github/`, `tests/`, `PROJECT.md`, and `TEST_INFRA.md` match `ORIGINAL_REQUEST.md` Round 3 context.

### 1.2 Stray Files / Artifacts Forensics
- `find daemon -maxdepth 3 -not -path 'daemon/target*'`: contains ONLY the expected canonical Rust crate files (zero stray probes/temp files).
- `find .agents/teamwork -type f ! -name '*.md'`: returned empty (strictly `.md` coordination metadata only).
- `/tmp/silo_*` check after test execution: zero stray M1 unit-test temp directories (`silo_main_*`, `silo_ipc_*`, `silo_cfg_*`, `silo_corrupt_*`, `silo_inotify_*`, `silo_uninstall_*`). The only `/tmp/silo_*` directories present (`/tmp/silo_e2e_daemon_*`) belong to the concurrent sibling E2E testing track.
- Auditor scratch probe directories (`/tmp/teamwork_probe.vY792F` and first probe) were staged with `chmod 700` and cleanly removed before reporting.

---

## 2. Anti-Cheating & Implementation Authenticity Forensics

### 2.1 Suppression / Stub / Cheat Scan
Regex scan across all 6 M1 Rust source files (`config.rs`, `ipc.rs`, `main.rs`, `protocol.rs`, `scopes.rs`, `sys.rs`) for `#[allow`, `#[ignore`, `todo!`, `unimplemented!`, `assert!(true)`, `panic!("skip`:
- **Result:** **0 matches**. Zero lint suppressions or test skips anywhere in M1 code.

### 2.2 File-by-File Code Authenticity Verification

1. **`daemon/Cargo.toml`**
   - `daemon/Cargo.toml:6`: Updated description to `"Silo v3.0.0 kernel-backed privacy and security root daemon for Android Linux 6.x"` (`CB-30`).
   - `daemon/Cargo.toml:12-15`: Strictly unchanged dependencies (`libc`, `serde`, `serde_json`).

2. **`daemon/src/sys.rs`**
   - **Process-group isolation (`CB-17`, `D-25`):** `run_cmd` (`sys.rs:48-136`) executes binaries directly via `Command::new(program)` (never `/system/bin/sh`), uses async-signal-safe `libc::setpgid(0, 0)` in `pre_exec` (`sys.rs:68-73`), kills `-pgid` with `SIGKILL` strictly guarded by `pid > 1 && pid <= i32::MAX as u32`, falls back to `child.kill()`, and guarantees `child.wait()` in `kill_process_group_and_reap` (`sys.rs:31-43`). Stdout (1 MiB) and stderr (128 KiB) are drained via `read_bounded` (`sys.rs:138-158`) over `mpsc` channels with `recv_timeout` (`sys.rs:88-128`), preventing both early-close `SIGPIPE` and grandchild pipe deadlocks.
   - **Multi-package shared-UID & multi-user index (`D-10`, `D-18`, `CB-12`):** `UidPackageIndex` (`sys.rs:229-307`) maintains `uid_to_pkgs`, `pkg_to_uid`, and zero-copy `primary_uid_map` with `uid % 100_000` fallback (`sys.rs:241-252`), while keeping `read_uid_package_map()` and `parse_packages_list_str()` intact for M2 `sentinel.rs`.
   - **Fail-safe CE unlock (`D-20`):** `is_ce_unlocked()` (`sys.rs:372-388`) checks `getprop("sys.user.0.ce_available")` then `parse_user0_unlocked_from_dumpsys` (`sys.rs:339-367`), defaulting to `false` with zero `/data/user/0/android` `.exists()` fallback.
   - **Samsung AOD-aware screen detection (`D-07`):** `detect_screen_on()` (`sys.rs:497-537`) & `parse_screen_on_from_dumpsys()` (`sys.rs:407-465`) fast-path backlight `<= 0` to `false` and cross-check `dumpsys power`/`display` so `Dozing`/`Asleep`/`Dreaming`/`DOZE`/`DOZE_SUSPEND`/`OFF`/`ON_SUSPEND`/`DOZE_INVERTED` return `false`.
   - **Top resumed activity vs FGS (`D-18`):** `is_top_activity_oom_adj(adj)` (`sys.rs:615-617`) strictly checks `adj == 0` in `top_activity_uids()` (`sys.rs:623-656`), while keeping `foreground_app_uids()` (`<= 100`) intact for M2 `freezer.rs`.
   - **OOM protection (`CB-18`) & RAII `InotifyWatcher` (`D-05`):** `set_oom_score_adj()` (`sys.rs:660-663`) clamps `[-1000, 1000]` and writes `/proc/self/oom_score_adj`. `InotifyWatcher` (`sys.rs:672-800`) calls real `libc::inotify_init1(IN_NONBLOCK | IN_CLOEXEC)` wrapped in `OwnedFd`, `libc::inotify_add_watch`, `libc::poll`, non-blocking `libc::read`, and overflow-checked `parse_inotify_names` (`checked_add`, `sys.rs:768-800`).

3. **`daemon/src/protocol.rs`**
   - `MAX_IPC_LINE_BYTES = 4 * 1024 * 1024` (`protocol.rs:8`) and `MAX_IPC_RESPONSE_BYTES = 8 * 1024 * 1024` (`protocol.rs:9`).
   - `PrivateDnsState::default()` (`protocol.rs:589-596`) returns `"opportunistic"`.
   - `CategoryPermLiveState` (`protocol.rs:612-626`) preserves wire-compatible `last_access: Option<String>` (confirmed compatible with `HEAD` and `app/.../SiloModels.kt:260-269`) and adds 4 `#[serde(default)]` fields (`runtime_granted`, `has_runtime_perms`, `supports_foreground`, `system_fixed`).
   - `DaemonStatus` (`protocol.rs:689-690`) adds `#[serde(default)] pub panic_lockdown_active: bool`.
   - All 49 `DaemonCommand` variants and 14 M2-frozen response structs remain intact.

4. **`daemon/src/config.rs`**
   - `FirewallConfig::panic_lockdown_active` (`config.rs:189-190`) defaults to `false` with `#[serde(default)]`.
   - `SiloConfig::sanitize()` (`config.rs:472-556`) enforces `panic_lockdown_active` coherence (`!enabled || mode != DefaultDeny => false`, `:478-480`) and `AppPermGuard` mutual exclusivity (`locked_deny` takes precedence and strips duplicates from `auto_revoke_on_bg`, `:491-504`).
   - `SiloConfig::apply_preset()` (`config.rs:558-662`) explicitly resets all firewall, hardening, clipboard, and automation fields across `Safe`, `Balanced`, `Hardened`, and `Paranoid` (`D-21`).
   - Durability (`D-26`): `load_or_default` (`config.rs:664-678`) reads raw bytes (`fs::read` + `serde_json::from_slice`) and invokes `backup_corrupt_config` (`config.rs:736-767`, `0600` + parent-dir `sync_all`); `save_atomic` (`config.rs:680-733`) writes unique `<path>.tmp.<pid>.<seq>` (`0600`), calls `file.sync_all()`, atomically renames, sets `0600`, and calls `dir.sync_all()` on the parent directory.

5. **`daemon/src/ipc.rs`**
   - `derive_lock_path` (`ipc.rs:106-114`) maps `*.sock` -> `*.lock` and appends `.lock` otherwise.
   - `SingletonLockGuard` (`ipc.rs:118-163`) opens lockfile `0600`, calls real `libc::flock(fd, LOCK_EX | LOCK_NB)`, returns clear error on `EWOULDBLOCK`, and drops via `LOCK_UN` without unlinking the lockfile (preventing flock-unlink inode race).
   - `BIND_UMASK_LOCK` + `UmaskGuard::restrict_077` (`ipc.rs:25`, `:165-182`, `:239-252`) sets `libc::umask(0o077)` around `UnixListener::bind`, restores umask on drop, sets `0600`, and captures `(m.dev(), m.ino())`.
   - `SocketUnlinkGuard` (`ipc.rs:184-204`) unlinks the socket on exit ONLY if `symlink_metadata` matches `(exp_dev, exp_ino)`.
   - `run_ipc_server_with_lock` (`ipc.rs:222-300`) statically requires `&SingletonLockGuard`; `verify_peer_cred` (`ipc.rs:29-57`) enforces `SO_PEERCRED` (`uid == 0 || uid == euid`).
   - `BRIDGE_READ_TIMEOUT = 45s`, `BRIDGE_WRITE_TIMEOUT = 15s` (`ipc.rs:18-19`); `run_stdio_bridge_io` (`ipc.rs:388-471`) implements genuine single transparent reconnect+retry on cached-socket disconnect, enforces `MAX_IPC_RESPONSE_BYTES`, and emits clean JSON error responses on `InvalidData` framing errors (`ipc.rs:339-347`, `:399-405`).

6. **`daemon/src/scopes.rs`**
   - `PERM_CATEGORIES` (`scopes.rs:69-188`) covers all 10 categories with accurate `has_runtime_perms`, `supports_foreground`, Android 14+ `READ_MEDIA_VISUAL_USER_SELECTED`, `SEND_SMS`/`RECEIVE_MMS`, `BODY_SENSORS_BACKGROUND`, and `is_special` separation (`MANAGE_EXTERNAL_STORAGE`, `SYSTEM_ALERT_WINDOW`, `GET_USAGE_STATS`).
   - `combine_uid_and_pkg_mode` & `parse_appops_get_detailed` (`scopes.rs:247-374`) enforce order-independent strictness precedence (`deny > ignore > foreground > allow > default`) so `Uid mode` is never overwritten by package history (`D-01`/`CB-01`).
   - `aggregate_category_appops_mode` (`scopes.rs:280-300`) aggregates fail-closed (`allow > foreground > ignore > deny > default`, `D-03`).
   - `parse_dumpsys_package_runtime_perms` (`scopes.rs:400-509`) tracks indentation cleanly across multi-user blocks (`User 0:`, `User 10:`, etc.) and flags (`SYSTEM_FIXED`, `USER_FIXED`, `REVOKED_COMPAT`).
   - `inspect_app_permissions_detailed` (`scopes.rs:524-559`) executes `cmd appops get` and `dumpsys package` concurrently on 2 scoped threads.
   - `set_app_permission_action_for_user` (`scopes.rs:864-1216`) + `classify_pm_cmd_output`/`classify_appops_cmd_output` (`scopes.rs:667-738`) implement real `Allow`/`Foreground`/`Ignore`/`Revoke` semantics with `--uid`, `revoked-compat` fallback, `pm grant`/`pm revoke`, stdout+stderr+timeout inspection, and clean rejection of unsupported `foreground` categories (`D-02`/`CB-02`).
   - `apply_lock_flags_for_category` / `clear_lock_flags_for_category` (`scopes.rs:1327-1429`) apply/clear `user-set`/`user-fixed` with both `pm` and `cmd package` fallbacks.
   - `get_privacy_access_log` & `parse_dumpsys_appops_op` (`scopes.rs:1455-1694`) use bounded 4-worker concurrency, exact op-name token boundaries (`matches_op_header`), strict `(uid/pkg)` + UID-header mode parsing, protected UID/package filtering, and full `Access:` line scanning (`D-11`/`CB-05`).
   - `evaluate_category_drift` (`scopes.rs:1803-1896`), `is_pkg_in_top_fg` (`scopes.rs:1773-1786`), `EnforcerMetrics` (`scopes.rs:1901-1925`), `enforce_locked_guards_once` (`scopes.rs:2153-2208`), and `run_permission_enforcer_loop` (`scopes.rs:2214-2306`) provide genuine 3-state drift prevention (eliminating false-positive force-stop loops), shared-UID top-FG detection, and real `InotifyWatcher` + `pending_notify` event-driven enforcement (`D-05`/`D-18`/`CB-03`).
   - `sync_all_scopes` (`scopes.rs:2374-2397`) applies only enabled legacy toggles and skips v3-guarded categories (`CB-10`); `count_live_ignored_ops` (`scopes.rs:2401-2430`) uses the fixed parser.
   - `ClipboardGuard` / `parse_clipboard_metadata_hash` / `clear_clipboard_system` (`scopes.rs:2434-2680`) fix the `label=null` false-empty bug while skipping `{T:...}` raw clipboard payloads and validating SDK-aware `service call clipboard` Parcel results (`D-17`/`CB-15`).
   - `collect_uninstall_reset_plan` & `reset_all_scopes_for_uninstall` (`scopes.rs:2684-2818`) reset both `scopes.apps` and `scopes.guards` AppOps to `default` and clear `user-fixed`/`revoked-compat` without calling `pm grant` (`CB-19`).
   - *Informational note (non-violation):* In `scopes.rs:1147-1149` (`PermAction::Revoke`), when `dumpsys package` succeeds (`user_section_found == true`) and confirms a runtime permission is already ungranted, `ok_count` is incremented before `enforce_category_appops_blocked`. If all subsequent AppOps commands fail in that specific already-revoked state, the call still returns `Ok(live_reinspection)` (which accurately reports live AppOps state rather than fabricating success). On host Linux or whenever a permission is actually granted, `ok_count` starts at 0 and failure returns `Err`.

7. **`daemon/src/main.rs`**
   - **Deadlock-free decoupled lock hierarchy (`D-04`, `CB-04`):** `DaemonContext` (`main.rs:223-235`) separates Level 1 slow locks (`firewall_mtx`, `hardening_mtx`, `freezer_mtx`, `automation_mtx`, `clipboard_mtx`) from Level 2 fast leaf locks (`state`, `sys_cache`, `uid_index`, `telemetry`, `enforcer_metrics`). Audited **every** `state.lock()` call site across `main.rs` (39 production call sites): **ZERO subprocesses (`run_cmd`), ZERO Netlink I/O, and ZERO Level 1 lock acquisitions ever occur while holding `state`**. Lock ordering (`Level 1 -> Level 2`, and `hardening_mtx -> freezer_mtx` solely in `RunLiveProof:1675-1680`) is strictly acyclic.
   - **`<1ms` `GetStatus`:** `build_status` (`main.rs:346-364`) reads exclusively Level 2 caches (`telemetry`, `sys_cache`, `enforcer_metrics`, `state`) with zero subprocesses and zero Level 1 locks.
   - **Startup/shutdown lifecycle:** `run_daemon` (`main.rs:1736-2010`) calls `set_oom_score_adj(-900)` (`:1738`), acquires `SingletonLockGuard` on `derive_lock_path(&socket_path)` BEFORE `DaemonContext::new` & `sync_all_subsystems()` (`:1742-1754`), spawns dedicated `run_permission_enforcer_loop` thread (`:1818-1836`), passes `&singleton_lock` to `run_ipc_server_with_lock` (`:1990-1995`), and drops `singleton_lock` only after `fz.thaw_all()` (`:2004-2008`).
   - **Panic Lockdown defense-in-depth (`D-06`):** `TriggerPanicLockdown` (`main.rs:1595-1644`) persists `panic_lockdown_active = true` without deleting `config.firewall.rules`; `sync_firewall` (`main.rs:420-428`) clears transient `rules` and `temp_unblock_set` on a clone while `panic_lockdown_active == true`; `SetFirewallConfig`, `ApplyPreset`, and `EmergencyFlushFirewall` clear `panic_lockdown_active` cleanly.
   - **Protected UID & shared-UID package boundary (`D-10`, `CB-12`):** `is_uid_protected` (`main.rs:294-302`), `is_pkg_protected` (`main.rs:306-320`), and `classify_kill_connection_target` (`main.rs:586-643`) enforce protection across `SetAppFirewallRule`, `SetFirewallConfig`, `SetTempUnblock`, `ClearTempUnblock`, `SetFreezerEntry`, `FreezeUidNow`, `KillUidSockets`, `KillConnection` (skipping `LISTEN`/`TIME_WAIT`), `SetAppScope`, `ApplyHardenedScope`, `ClearAppScope`, `SetAppPermissionAction`, `SetAppPermGuard`, `BulkRevokeCategory`, `ImportConfigJson`, and `check_new_installed_apps`.
   - **Permission Action / Guard Consistency:** `execute_set_app_permission_action` (`main.rs:650-740`) validates category/action/foreground BEFORE modifying `locked_deny`, clears `locked_deny` BEFORE applying loosening actions (preventing `inotify` race), clears `user-fixed` on `ignore` unlock, and rolls back `locked_deny` on OS failure. `SetAppPermGuard` (`main.rs:1352-1410`) runs delta `revoke`/`apply_lock_flags`/`clear_lock_flags` outside `state` and notifies the enforcer.
   - **Device-lock preservation (`D-07`, `CB-06`):** `should_poll_device_locked` & `compute_device_locked` (`main.rs:551-573`) never default to `false` on skipped ticks and always poll on screen-off, `prev_locked`, screen transitions, or every 3rd tick.
   - **`--uninstall-cleanup` CLI (`CB-19`):** `parse_cli_args` (`main.rs:2031-2085`) & `run_uninstall_cleanup` (`main.rs:2136-2152`) execute `run_emergency_flush` (including multi-user `--user (uid / 100_000)` unsuspend + standby-bucket active) and `reset_all_scopes_for_uninstall`, exiting `0`.

---

## 3. Test Integrity Forensics

- **Pre-existing test preservation:** Programmatic `git diff` audit of all `#[cfg(test)] mod tests` blocks confirmed **0 pre-existing tests deleted and 0 assertions weakened** across all 6 files (only 11 formatting/temp-dir cleanup lines changed in `ipc.rs` tests).
- **Unit test inventory (exact `#[test]` count verification):**
  - `daemon/src/sys.rs`: **12 tests** (all genuine — real process-group `SIGKILL` grandchild liveness verification via `kill(gpid, 0)`, real `InotifyWatcher` write+rename detection, AOD/CE/shared-UID parsers).
  - `daemon/src/protocol.rs`: **5 tests** (wire framing constants, backward-compatible deserialization + roundtrips).
  - `daemon/src/config.rs`: **11 tests** (real 0600 corrupt JSON + non-UTF-8 binary backup verification, complete preset reset matrix, guard mutual exclusivity + panic coherence).
  - `daemon/src/ipc.rs`: **7 tests** (real `flock` duplicate-instance rejection, real `0600` socket permission assertion, real inode-mismatch preserve test, real 4 MiB framing boundary, real live server restart + transparent single reconnect test).
  - `daemon/src/scopes.rs`: **17 tests** (full `combine_uid_and_pkg_mode` precedence matrix, fail-closed aggregation, multi-user `dumpsys package` flags, stdout/stderr/timeout classifiers, 3-state drift loop prevention, shared-UID top-FG, `dumpsys appops` boundary/tuple/protected/multi-access, `label=null` + zero-content clipboard hash, `Parcel` validation, uninstall plan).
  - `daemon/src/main.rs`: **6 tests** (real contention test holding ALL 5 Level 1 mutexes simultaneously while timing `dispatch_command(GetStatus)`, shared-UID/empty/spoofed protected-UID rejection + `KillConnection` classifier, full `panic_lockdown_active` lifecycle, device-lock preservation, guard delta + rollback on failed apply, CLI parser + `run_uninstall_cleanup`).
  - Untouched M2 modules (`automation`, `firewall`, `freezer`, `hardening`, `netlink`, `sentinel`, `telemetry`): **13 tests**.
  - **Total:** **58 M1 tests + 13 M2 tests = 71 tests** (0 `#[ignore]`, 0 empty bodies, 0 tautological assertions).

---

## 4. Independent Runtime Verification Evidence

Executed independently by auditor in `/home/antigravity/terenzy/Silo/daemon` (UTC timestamp logged in `progress.md` prior to execution):

1. **Clippy (strict `-D warnings`, `--all-targets`):**
   - Command: `cargo clippy --all-targets -- -D warnings`
   - Result: **Exit `0`**, **0 warnings** across all targets (bin + tests + untouched M2 modules).

2. **Target-Scoped & Full Unit Tests:**
   - `cargo test --verbose ipc::` -> **`7 passed; 0 failed; 0 ignored`** (`0.21s`)
   - `cargo test --verbose scopes::` -> **`17 passed; 0 failed; 0 ignored`** (`0.01s`)
   - `cargo test --verbose sys::` -> **`12 passed; 0 failed; 0 ignored`** (`0.23s`)
   - `cargo test --verbose config::` -> **`11 passed; 0 failed; 0 ignored`** (`0.02s`)
   - `cargo test --verbose protocol::` -> **`5 passed; 0 failed; 0 ignored`** (`0.00s`)
   - `cargo test --verbose main::` -> `0 passed; 71 filtered out` (expected Rust behavior: crate-root tests in `src/main.rs` register under `tests::*`, not `main::*`).
   - `cargo test --verbose` (verifying all 6 `tests::*` crate-root tests in `main.rs` + all M1/M2 modules) -> **`71 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 1.57s`**.
   - `cargo test -- --list` -> confirmed exact 71-test manifest matching every `#[test]` on disk.

3. **Live Compiled Binary Forensic Probe (isolated `mktemp -d`, `chmod 700`, deleted on exit):**
   - `./target/debug/silod --version` -> `silod 3.0.0` (exit `0`)
   - `./target/debug/silod --uninstall-cleanup --config "$PROBE/config.json"` -> `silod: uninstall cleanup complete (firewall flushed, cgroups thawed, apps unsuspended, 0 packages / 0 ops reset)` (exit `0`)
   - Live daemon spawn (`--socket`, `--config`, `--boot-marker`):
     - Verified socket mode `600` (`silod.sock`) and lockfile mode `600` (`silod.lock`).
     - Second instance against same socket rejected immediately (`DUP_RC=1`: `silod startup error: another silod instance is already running (lock held: .../silod.lock)`).
     - Live `--once '{"id":77,"cmd":"ping"}'` -> `{"id":77,"ok":true,"data":{"pong":true,"version":"3.0.0"}}`.
     - Live `--bridge` `{"id":88,"cmd":"get_status"}` -> `{"id":88,"ok":true,"data":{...,"ce_unlocked":false,"panic_lockdown_active":false,"version":"3.0.0",...}}`.
     - `SIGTERM` shutdown -> clean exit `0` (`STOP_RC=0`), `SOCK_CLEANED=yes`, `LOCK_PRESERVED=yes`.

---

## Final Verdict

**`CLEAN`** — Milestone M1 (`daemon-core`, Round 3) passes all forensic integrity, scope isolation, implementation authenticity, test integrity, and independent runtime verification gates with zero violations.
