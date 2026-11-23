# Independent Code Review Handoff — Primary Reviewer (`iter1_rev1_primary`)

- **Milestone:** M1 (`daemon-core`, Round 3 — Iteration 1 Parallel Verification Wave)
- **Reviewer Role:** Primary Reviewer — Full Build/Clippy/Test Owner & Deep Core/Contract Audit
- **Caller / Parent ID:** `4a7c924fa663faf42ab4924648ebab40`
- **Verdict:** **`APPROVE`**

---

## 1. Independent Verification Commands & Exact Outputs

All commands were executed independently from disk without relying on Worker logs, with zero modifications to the git working tree.

### 1.1 Full Canonical Daemon Verification Suite
- **Cwd:** `/home/antigravity/terenzy/Silo/daemon`
- **Command:**
  ```bash
  cargo clippy --all-targets -- -D warnings && cargo test --verbose && cargo build && ./target/debug/silod --version
  ```
- **Exit Code:** `0`
- **Clippy Result:** `0` warnings across `--bin silod`, all tests, and all 7 untouched M2 modules (`Finished 'dev' profile [unoptimized + debuginfo] target(s) in 0.29s`).
- **Unit Test Result:**
  ```text
  test result: ok. 71 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 2.11s
  ```
  Verified breakdown by module:
  - `config::tests`: 11 passed (`atomic_save_and_reload_roundtrip`, `cidr_validation_normalizes_and_blocks_dangerous_entries`, `critical_uids_are_always_protected`, `hostname_validation_accepts_dot_and_rejects_injection`, `load_or_default_backs_up_corrupt_config_with_0600`, `package_name_validation_rejects_injection`, `preset_reset_matrix_leaves_zero_stale_toggles`, `presets_never_auto_enable_auto_reboot`, `sanitize_enforces_guard_mutual_exclusivity_and_panic_invariant`, `sanitize_enforces_safety_invariants`, `u32_map_deserializes_from_serde_json_value_string_keys`)
  - `ipc::tests`: 7 passed (`bounded_reader_enforces_limit`, `bridge_reconnects_once_transparently_after_server_restart`, `derive_lock_path_handles_sock_and_custom_extensions`, `four_mib_frame_accepted_and_oversized_rejected`, `shutdown_preserves_replaced_socket_inode`, `singleton_lock_rejects_second_instance_and_releases_on_drop`, `unix_socket_server_handles_authenticated_rpc`)
  - `protocol::tests`: 5 passed (`category_perm_live_state_backward_compatible_deserialization`, `daemon_status_includes_default_panic_lockdown_active`, `ipc_framing_constants_support_full_50k_blocklist`, `parses_all_v2_and_v3_commands`, `parses_set_firewall_config_with_string_uid_keys_without_error`)
  - `scopes::tests`: 17 passed
  - `sys::tests`: 12 passed
  - `tests` (`main.rs`): 6 passed (`cli_arg_parser_and_uninstall_cleanup_exit_cleanly`, `device_lock_preservation_never_flips_false_on_screen_wake_or_skipped_ticks`, `get_status_is_lock_free_and_sub_millisecond_while_subsystems_blocked`, `panic_lockdown_active_full_lifecycle`, `protected_uid_rejected_even_with_empty_or_spoofed_package_name`, `set_app_perm_guard_sanitizes_mutual_exclusivity_and_computes_delta`)
  - Untouched M2 modules (`automation`, `firewall`, `freezer`, `hardening`, `netlink`, `sentinel`, `telemetry`): 13 passed
- **Build & Version Output:** `silod 3.0.0` (exit `0`).

### 1.2 M1 File-Ownership Scope Verification
- **Command (Cwd `/home/antigravity/terenzy/Silo`):**
  ```bash
  git diff --stat -- daemon/ && git status --short -- daemon/
  ```
- **Exact Output:**
  ```text
   daemon/Cargo.toml      |    2 +-
   daemon/src/config.rs   |  276 ++++-
   daemon/src/ipc.rs      |  602 ++++++++-
   daemon/src/main.rs     | 2657 +++++++++++++++++++++++++++++++---------
   daemon/src/protocol.rs |  125 +-
   daemon/src/scopes.rs   | 3178 +++++++++++++++++++++++++++++++++++++++++++-----
   daemon/src/sys.rs      |  793 +++++++++++-
   7 files changed, 6667 insertions(+), 966 deletions(-)
  ```
  - **Confirmed:** Strictly ONLY the 7 M1-owned files under `daemon/` were modified.
  - **Confirmed:** Zero modifications to M2 files (`firewall.rs`, `netlink.rs`, `freezer.rs`, `automation.rs`, `hardening.rs`, `sentinel.rs`, `telemetry.rs`), `daemon/Cargo.lock`, or `daemon/.cargo/config.toml`. Zero untracked files under `daemon/`.

### 1.3 Isolated Live Binary Smoke Verification
Executed against compiled `/home/antigravity/terenzy/Silo/daemon/target/debug/silod` in an isolated `mktemp -d` (`chmod 700`, removed via `trap` before reporting):
1. `--version` -> `silod 3.0.0` (exit `0`).
2. `--uninstall-cleanup --config "$PROBE/config.json"` -> `silod: uninstall cleanup complete (firewall flushed, cgroups thawed, apps unsuspended, 0 packages / 0 ops reset)` (exit `0`).
3. Live daemon startup -> created `silod.sock` (`0600`) and `silod.lock` (`0600`).
4. Duplicate instance launch on same socket -> rejected immediately with exit code `1` (`silod startup error: another silod instance is already running (lock held: ...)`).
5. `--status` -> returned valid JSON with `"version":"3.0.0"`, `"panic_lockdown_active":false`, `"ce_unlocked":false` (fail-safe), `"private_dns":{"mode":"opportunistic","specifier":""}`.
6. `--once '{"id":7,"cmd":"ping"}'` -> `{"id":7,"ok":true,"data":{"pong":true,"version":"3.0.0"}}`.
7. `--bridge` multi-request session (`id:10` `ping`, `id:11` `get_config`) -> matched both request IDs cleanly with `"panic_lockdown_active":false` in `firewall`.
8. Graceful `SIGTERM` shutdown -> exited `0`, unlinked `silod.sock`, preserved `silod.lock` (never unlinks flocked file).

---

## 2. Deep Code & Contract Review Findings

### 2.1 `DaemonContext` Lock Hierarchy & Concurrency (`daemon/src/main.rs`)
- **Verified (`main.rs:216-546`, `742-2010`):**
  - Strictly enforces two-tier lock hierarchy:
    - **Level 1 (slow/subprocess locks):** `firewall_mtx`, `hardening_mtx`, `freezer_mtx`, `automation_mtx`, `clipboard_mtx`.
    - **Level 2 (fast leaf locks):** `state`, `sys_cache`, `uid_index`, `telemetry`, `enforcer_metrics`.
  - Audited all 49 `dispatch_command` handlers (`main.rs:742-1734`), `sync_firewall`, `sync_all_subsystems`, `refresh_cached_system_state`, `check_new_installed_apps`, and all 5 background threads (signal watcher, NFLOG, fast thaw, dedicated `PermissionEnforcer`, 5s supervisor):
    - `state` mutex is **NEVER** held across any `run_cmd`, Netlink I/O, or blocking syscall.
    - The only dual Level 1 lock acquisition is `RunLiveProof` (`main.rs:1675-1680`: `hardening_mtx` -> `freezer_mtx`); no reverse acquisition exists anywhere in the crate.
    - The only dual Level 2 lock acquisition is the NFLOG callback (`main.rs:1780-1784`: `uid_index.read()` -> `telemetry.lock()`); no reverse acquisition exists anywhere in the crate.
    - No Level 2 -> Level 1 inversion exists anywhere (`update_freezer_cache` and `sync_firewall` strictly go Level 1 -> Level 2).
    - Zero deadlock paths found.

### 2.2 `GetStatus` `<1ms` Fast Path (`daemon/src/main.rs:151-213, 346-364`)
- **Verified:** `build_status()` reads `telemetry`, `sys_cache`, `enforcer_metrics`, and `state` sequentially with zero nested locks, zero Level 1 locks, and zero subprocesses.
- **Verified:** Exposes `panic_lockdown_active: self.config.firewall.panic_lockdown_active` (`main.rs:211`).
- **Verified:** `sys_cache` is updated immediately on write RPCs (`SetSensorsOff`, `SetSensorPrivacy`, `GetPrivateDns`, `SetPrivateDns`, `GetRadioPrivacy`, `SetRadioPrivacy`, `TriggerPanicLockdown`, `SetAutomationConfig`, `ApplyPreset`, and all freezer operations/ticks) and refreshed asynchronously every 15s on supervisor ticks (`main.rs:1958-1961`).
- **Verified:** Test `get_status_is_lock_free_and_sub_millisecond_while_subsystems_blocked` (`main.rs:2277-2341`) holds all 5 Level 1 locks simultaneously while dispatching `GetStatus`.

### 2.3 IPC Framing, Singleton `flock`, `umask(0o077)`, Inode Guard & `--bridge` (`daemon/src/ipc.rs`, `protocol.rs`, `main.rs`)
- **Verified (`ipc.rs:106-163`, `main.rs:1742-1749, 1990-2009`):**
  - `SingletonLockGuard::acquire(&derive_lock_path(&socket_path))` acquires `flock(LOCK_EX | LOCK_NB)` with `0600` permissions **BEFORE** `DaemonContext::new` and `sync_all_subsystems()`, is statically required by `run_ipc_server_with_lock(&socket_path, &singleton_lock, ...)`, remains held through shutdown `thaw_all()`, and releases via `LOCK_UN` on drop without unlinking the lockfile.
- **Verified (`ipc.rs:165-182, 239-252`):** `BIND_UMASK_LOCK` + `UmaskGuard::restrict_077()` wraps stale-socket cleanup + `UnixListener::bind` + `0o600` chmod, eliminating any world-readable socket creation race.
- **Verified (`ipc.rs:184-204, 254-257`):** `SocketUnlinkGuard` verifies `(meta.dev(), meta.ino())` via `symlink_metadata` before removing the socket on exit.
- **Verified (`protocol.rs:8-9`, `ipc.rs:61-100, 339-347, 396-405`):** `MAX_IPC_LINE_BYTES = 4 * 1024 * 1024` (4 MiB), `MAX_IPC_RESPONSE_BYTES = 8 * 1024 * 1024` (8 MiB); `read_line_bounded` handles exact-limit boundaries and returns `InvalidData` with clean JSON framing error responses.
- **Verified (`ipc.rs:18-19, 353-471`):** `--bridge` sets `BRIDGE_READ_TIMEOUT = 45s`, `BRIDGE_WRITE_TIMEOUT = 15s`; `run_stdio_bridge_io` performs a single transparent reconnect+retry **ONLY** when `had_cached_conn && is_reconnectable_err(&first_err)` (`BrokenPipe | ConnectionReset | ConnectionAborted | UnexpectedEof | NotConnected`). Explicitly does **NOT** retry on `TimedOut`, `WouldBlock`, or `InvalidData`, and drops `conn = None` on timeout to prevent response-ID desync.

### 2.4 Panic Lockdown & Defense-in-Depth `sync_firewall` (`daemon/src/main.rs`, `config.rs`)
- **Verified (`main.rs:420-428, 1595-1644`):**
  - `TriggerPanicLockdown` sets `enabled = true`, `mode = DefaultDeny`, `panic_lockdown_active = true`, clears `temp_unblock_until`, and preserves saved `config.firewall.rules` untouched.
  - `sync_firewall()` clones `FirewallConfig` into `effective_fw` and clears `effective_fw.rules` + uses an empty `effective_temp` while `panic_lockdown_active == true`, enforcing unconditional lockdown even against untouched M2 `firewall.rs`.
  - Cleared cleanly on `SetFirewallConfig` non-deny/disabled (`main.rs:820-824` + `config.rs:478-480`), `ApplyPreset` (`config.rs:563, 586, 619, 652`), and `EmergencyFlushFirewall` (`main.rs:982`).

### 2.5 Authoritative Protected-UID / Shared-UID Protected-Package Boundary (`daemon/src/main.rs`, `sys.rs`)
- **Verified (`main.rs:294-320, 586-643`, `sys.rs:241-271`):**
  - `is_uid_protected(uid)` checks `is_protected_uid(uid) || uid_index.any_pkg_for_uid(uid, is_protected_package)` (including multi-user `uid % 100_000` appId fallback for u10+/u95/u150).
  - `is_pkg_protected(pkg)` checks `is_protected_package(pkg)` AND whether any sibling package sharing `pkg`'s UID is protected.
  - Enforced across `SetFirewallConfig`, `SetAppFirewallRule`, `SetTempUnblock`, `ClearTempUnblock`, `KillUidSockets`, `KillConnection` (`classify_kill_connection_target` rejects protected UIDs/packages and skips `LISTEN`/`TIME_WAIT` while keeping `netlink::destroy_matching_connection` called cleanly), `SetFreezerEntry`, `FreezeUidNow`, `SetAppScope`, `ApplyHardenedScope`, `ClearAppScope`, `SetAppPermissionAction`, `SetAppPermGuard`, `BulkRevokeCategory`, and `ImportConfigJson`.

### 2.6 Presets, Sanitize, Durability, Device-Lock, OOM, `--uninstall-cleanup` & Cross-Boundary Parity
- **Verified (`config.rs:472-556, 558-662`):**
  - `SiloConfig::apply_preset` explicitly resets every firewall, hardening, clipboard, freezer (Safe), and automation toggle across `Safe`/`Balanced`/`Hardened`/`Paranoid` — cross-checked field-for-field against `app/.../HomeScreen.kt:39-134` (`matchSecurityPreset`) with 100% parity.
  - `SiloConfig::sanitize()` enforces `panic_lockdown_active` coherence and `AppPermGuard` mutual exclusivity (`locked_deny` strips duplicates from `auto_revoke_on_bg`).
- **Verified (`config.rs:664-767`):**
  - `load_or_default` reads raw bytes (`fs::read` + `from_slice`) and backs up malformed or non-UTF-8 configs to `<path>.corrupt.<ts>` (`0600` + parent-dir `fsync`).
  - `save_atomic` writes to `{path}.tmp.{pid}.{seq}` (`0600`) + `file.sync_all()` + atomic `fs::rename` + `0600` + parent-dir `fsync`.
- **Verified (`main.rs:551-573, 1857-1863`):** `should_poll_device_locked` / `compute_device_locked` preserves `device_locked` across screen-wake/AOD and never hardcodes `false`.
- **Verified (`main.rs:1738`, `sys.rs:660-663`):** `set_oom_score_adj(-900)` executes best-effort at daemon startup.
- **Verified (`main.rs:2031-2152`):** `--uninstall-cleanup [--config PATH]` runs `run_emergency_flush` (multi-user `--user (uid / 100_000)` unsuspend + standby-bucket active) + `reset_all_scopes_for_uninstall` (without `pm grant`), matching `module/uninstall.sh:415` verbatim.
- **Verified (`protocol.rs` exact diff & `Cargo.toml`):**
  - Zero frozen M2 structs (`KernelCapabilities`, `ActiveConnection`, `BlockedPacketEvent`, `TelemetrySnapshot`, `FreezerStatus`, `PrivateDnsState`, `RadioPrivacyState`, `SensorPrivacyState`, `HardeningStatus`, `SysctlItemStatus`, `SentinelReport`, `AuditFinding`, `LiveProofReport`, `LiveProofItem`) or enums had fields/variants altered.
  - `CategoryPermLiveState` preserves `last_access` and adds 4 `#[serde(default)]` fields (`runtime_granted`, `has_runtime_perms`, `supports_foreground`, `system_fixed`) matching `SiloModels.kt:257-270`.
  - `DaemonStatus` & `FirewallConfig` add `#[serde(default)] pub panic_lockdown_active: bool` matching `SiloModels.kt:93, 332`.
  - `daemon/Cargo.toml:6` updated to Silo `v3.0.0` with zero new crates.

---

## 3. Non-Blocking Observations (Informational Only)

None of the following are defects or blockers; recorded for completeness:
1. `daemon/src/config.rs:747-753` (`backup_corrupt_config`): if `backup_path` were a bare relative filename with empty parent, parent-dir `fsync` is skipped (unlike `save_atomic:722-731` which falls back to `Path::new(".")`). Not reachable on Android (`/data/adb/silo/config.json`) or tests.
2. `daemon/src/main.rs:767-773` (`ApplyPreset`) & `main.rs:828-831` (`SetFirewallConfig`): in-memory `st.config` is updated before `st.save_config()` returns `Err` on disk-full failure (matches prior codebase convention).
3. `daemon/src/main.rs:1965`: if `automation.tick()` mutes sensors via `sensors_off_on_lock` on a non-3rd supervisor tick, `sys_cache.sensor_privacy` refreshes up to 10s later on the next 3rd tick (`main.rs:1960`), intentionally avoiding extra `dumpsys sensor_privacy` forks every 5s.

---

## 4. Final Verdict

**`APPROVE`** — All M1 requirements, frozen interface contracts (`PROJECT.md` §§1–4), lock-hierarchy invariants, security boundaries, file-ownership constraints, clippy `-D warnings`, all 71 unit tests, debug binary build, and isolated live smoke probes pass with zero defects.
