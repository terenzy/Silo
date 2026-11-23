# Dispatch Record — `sub_orch_m1_daemon_core`

- **Received Timestamp**: 2026-11-22T16:12:02Z
- **Dispatched By**: Project Orchestrator (`5ee4b475a7063ff35a048e6a278d18c3`)
- **My Conversation ID**: `0157cc336b37e260aca5822fec95c2f1`
- **Milestone**: M1 (`daemon-core`)
- **Working Directory**: `/home/antigravity/terenzy/Silo`
- **State Folder**: `/home/antigravity/terenzy/Silo/.agents/teamwork/sub_orch_m1_daemon_core`

## Exclusive File Ownership
- `daemon/src/config.rs`
- `daemon/src/protocol.rs`
- `daemon/src/ipc.rs`
- `daemon/src/sys.rs`
- `daemon/src/scopes.rs`
- `daemon/src/main.rs`
- `daemon/Cargo.toml`

## Required Remediations Summary
1. **Permissions & AppOps (`D-01`, `D-02`, `D-03`, `CB-01`, `CB-02`, `CB-08` scopes user-id, `CB-09`, F1)**:
   - `parse_appops_get_output` separate `uid_mode`/`pkg_mode` + Android strictness precedence + `"errored"` -> `"deny"`.
   - `inspect_app_permissions` fail-closed multi-op aggregation (`allow` > `foreground` > `ignore`/`deny` > `default`).
   - Populate `CategoryPermLiveState`: `runtime_granted: Option<bool>`, `has_runtime_perms: bool`, `supports_foreground: bool`, `system_fixed: bool` (`#[serde(default)]`).
   - `set_app_permission_action`: `--user <u_id>`, `--uid` AppOps + clear conflicting pkg override to `default`, `revoked-compat` fallback, capture stdout+stderr & `pm revoke` failures (`SYSTEM_FIXED`, skip undeclared), reject unsupported `foreground`, on `allow` clear flags + reset `--uid`/pkg + `pm grant` revoked declared perms + auto-remove from `guards[pkg].locked_deny`, distinguish `ignore` vs `revoke`, add `READ_MEDIA_VISUAL_USER_SELECTED`, `SEND_SMS`, `RECEIVE_MMS`, `BODY_SENSORS_BACKGROUND`.
2. **Permission Enforcer `Kunci Blokir` & `Auto-Cabut` (`D-05`, `D-18`, `CB-03`, `CB-10`, F2)**:
   - `set_app_perm_guard`: immediate `revoke` + `user-fixed`/`user-set` on add outside `state_mtx`; clear `user-fixed` on remove; mutual exclusivity (`locked_deny` strips `auto_revoke_on_bg`); apply ONLY newly added locks.
   - Dedicated event-driven thread: Linux `inotify` (`/data/system/users/0/` & `/data/misc_de/0/apexdata/com.android.permission/`, graceful fallback) + 1–2s active-guard foreground check when screen on.
   - Eliminate false-positive force-stop loops (check real `runtime_granted` AND effective `appops_mode`; never `pm revoke` if already revoked).
   - Startup enforcement of `locked_deny` async; prevent legacy v2 `scopes.apps` from overwriting v3 `scopes.guards`.
   - `Auto-Cabut` shared-UID `uid_to_pkgs` + top visible activity (`oom_score_adj == 0` / top resumed) vs background FGS (`50..100`).
3. **Monitor `Akses Izin` & ClipboardGuard (`D-11`, `D-17`, `CB-05`, `CB-15`, F3/F7)**:
   - `parse_dumpsys_appops_op`: exact op token boundary, `(allow/ignore)` strict mode, filter `is_protected_package`, scan ALL `Access:` lines keeping freshest/`(running)`, bounded parallel `dumpsys appops --op` worker threads.
   - `ClipboardGuard`: fix `label=null` false-empty bug & Android 12–16 clear compatibility.
4. **IPC, Concurrency, Lock Discipline & Process Safety (`D-04`, `D-06`-flags, `D-07`, `D-10`, `D-20`, `D-21`, `D-24`, `D-25`-sys, `D-26`, `CB-04`, `CB-06`, `CB-07`, `CB-12`, `CB-17`, `CB-18`, `CB-19`, `CB-23`, `CB-30`, F4/F7/F8/F9/F11/F12)**:
   - Never hold `state_mtx` across external subprocesses; `Arc<RwLock<CachedSystemState>>` (<1ms `GetStatus`, 0 subprocesses); `Arc<RwLock<UidPackageIndex>>`.
   - Preserve `device_locked` across skipped ticks (`main.rs`); Samsung AOD-aware `detect_screen_on()` (`sys.rs`).
   - Fail-safe `is_ce_unlocked()` (`sys.rs`).
   - `run_cmd()` `setpgid(0, 0)`, `kill(-pid, SIGKILL)` on timeout, guaranteed `child.wait()`, non-hanging reader threads (`sys.rs`).
   - Server-side protected-package validation via `UidPackageIndex` in `SetAppFirewallRule`, `SetFreezerEntry`, `FreezeUidNow`, `KillUidSockets`, `KillConnection`.
   - `MAX_IPC_LINE_BYTES = 4 * 1024 * 1024`, `--bridge` read 45s / write 15s + single reconnect+retry, `libc::umask(0o077)`, `flock` `/data/adb/silo/silod.lock`, `(st_dev, st_ino)` socket cleanup.
   - `panic_lockdown_active: bool` (`#[serde(default)]`) in `FirewallConfig` & `DaemonStatus` + set/clear lifecycle in `main.rs`.
   - `ApplyPreset` explicit resets (`config.rs`); corrupt `config.json` backup + parent-dir `fsync` (`config.rs`).
   - `/proc/self/oom_score_adj = -900`, `silod --uninstall-cleanup [--config PATH]`, `daemon/Cargo.toml` description.

## Canonical Verification Command
`cd /home/antigravity/terenzy/Silo/daemon && cargo clippy --all-targets -- -D warnings && cargo test --verbose`
