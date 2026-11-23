# Handoff Report — `e2e_explorer_daemon` (Daemon Black-Box E2E Surfaces & Harness Blueprint)

**Role:** Read-Only Explorer (`e2e_explorer_daemon`) for Independent E2E Testing Track (`e2e_testing_orchestrator`, conv `042425bf98efe04f6055f145dfece762`)  
**Scope:** Daemon black-box E2E surfaces (`F1–F9`, `F11`, `F12`) across `daemon/src/*.rs`, `daemon/Cargo.toml`, `daemon/.cargo/config.toml`, cross-checked against `PROJECT.md § Feature Inventory`, `PROJECT.md § Interface Contracts`, `TEST_INFRA.md`, `SiloModels.kt`, and `module/*.sh`.  
**Important Baseline vs. Target Distinction:** All `daemon/src/*.rs` files are currently at baseline (mtime `2026-11-22 15:49:21Z`, before M1/M2 edits). Every section below clearly distinguishes **Current Baseline Code** from the **Frozen Target Contract (`PROJECT.md § Interface Contracts`)** so E2E tests assert the **target requirements** and NEVER weaken assertions for pre-fix bugs.

---

## 1. CLI & Lifecycle Surface (`main.rs`, `ipc.rs`, `config.rs`)

### 1.1 Exact CLI Flags, Argument Syntax, Stdout/Stderr & Exit Codes (`daemon/src/main.rs:1188-1309`)

Default path constants (`daemon/src/config.rs:37-39`, `daemon/src/main.rs:1191-1193`):
- `--socket PATH` — default `/data/adb/silo/silod.sock`
- `--config PATH` — default `/data/adb/silo/config.json`
- `--boot-marker PATH` — default `/data/adb/silo/.boot_fail_count`

| Invocation | Mode | Stdout / Stderr Contract | Exit Code | Notes (Baseline vs. Target) |
|---|---|---|---|---|
| `silod [--socket P] [--config P] [--boot-marker P]` | `daemon` (default) | Silent stdout; on fatal IPC/lock failure prints `silod IPC server error: <msg>` to stderr (`main.rs:1176`) | `0` clean stop (`SIGTERM`/`SIGINT`), `1` bind/lock error | Installs `SIGTERM`/`SIGINT` (`GLOBAL_STOP`), ignores `SIGPIPE` (`main.rs:64-76`). On exit calls `freezer.thaw_all()` (`main.rs:1182-1184`). **Target (`PROJECT.md #4`)**: writes `-900` to `/proc/self/oom_score_adj` (best-effort, non-fatal unprivileged), sets `umask(0o077)` before `bind()`, acquires `flock(LOCK_EX \| LOCK_NB)` on `silod.lock`, and unlinks socket ONLY if `(st_dev, st_ino)` matches bound socket. |
| `silod --version` | version | Prints `silod 3.0.0\n` to stdout (`main.rs:1204-1207`) | `0` | Uses `env!("CARGO_PKG_VERSION")` (`Cargo.toml:3`). |
| `silod --bridge [--socket P]` | stdio↔Unix-socket bridge | Reads newline-delimited JSON-RPC from stdin; writes newline-delimited `IpcResponse` JSON to stdout (`ipc.rs:197-248`) | `0` on stdin EOF | Baseline: `15s` socket read timeout, `10s` write timeout, no auto-retry on broken pipe. **Target (`PROJECT.md #1`)**: `45s` read timeout, `15s` write timeout, 4 MiB request / 8 MiB response framing, transparent **single reconnect+retry** when `silod` restarts between requests! |
| `silod --once '<JSON>' [--socket P]` | single RPC | Prints raw response JSON line to stdout (`main.rs:1292`); stderr error if missing arg (`--once requires a JSON request argument`) or socket failure (`cannot connect to silod: ...`) | `0` transport ok (even if `resp.ok == false`), `1` socket/IO error, `2` missing/empty JSON arg | `main.rs:1208-1214, 1285-1301`. |
| `silod --status [--socket P]` | status RPC shorthand | Equivalent to `--once '{"id":1,"cmd":"get_status"}'`; prints JSON line to stdout, or error to stderr | `0` ok, `1` connect/IO error | `main.rs:1275-1284`. Used by `module/action.sh:24,40`. |
| `silod --emergency-flush [--config P]` | recovery flush | Prints `silod: emergency firewall flush, cgroup thaw & unsuspend complete\n` to stdout (`main.rs:1272`) | `0` | Probes caps, calls `flush_firewall(&caps)`, thaws `/sys/fs/cgroup/uid_*/[*/]cgroup.freeze`, and if `config_path.exists()` calls `pm unsuspend --user <u_id> <pkg>` for configured freezer entries (`main.rs:1234-1274`). |
| `silod --uninstall-cleanup [--config P]` | **Target F12 (`PROJECT.md #4`)** | Must execute full `--emergency-flush` **PLUS** reset all Silo-configured AppOps (`cfg.scopes.apps` + `cfg.scopes.guards`) to `default` and clear `user-fixed` & `revoked-compat` permission flags | `0` | **Not in baseline yet** (M1 delivers it). E2E tests for F12 MUST test this flag directly against `silod` with sandboxed `cmd`/`pm` shims and assert both flush and AppOps/flag resets! |

### 1.2 Isolation of `silod.lock`, `sysctl_orig.json`, `config.json.corrupt.*` for Temp Instances
- **Current Baseline**: Neither `silod.lock` nor `sysctl_orig.json` exists yet (`HardeningEngine.original_values` is RAM-only in `hardening.rs:21`; no `flock` in `ipc.rs`). `config.json.tmp.<pid>` is created as a sibling of `config_path` (`config.rs:638`).
- **Frozen Target Contract & Harness Isolation Design**:
  - Production default dir is `/data/adb/silo/` (`config.rs:37`).
  - When `silod_harness.py` launches an isolated daemon in a per-test temp dir `<TMP>` (`chmod 0700`):
    ```text
    silod --socket <TMP>/silod.sock --config <TMP>/config.json --boot-marker <TMP>/.boot_fail_count
    ```
    M1/M2 MUST resolve companion files relative to the passed paths — NEVER hardcoding `/data/adb/silo/`:
    - `silod.lock` → sibling of `--socket` (`socket_path.parent().join("silod.lock")`, i.e., `<TMP>/silod.lock`).
    - `sysctl_orig.json` → sibling of `--config` (`config_path.parent().join("sysctl_orig.json")`, i.e., `<TMP>/sysctl_orig.json`), keyed by `/proc/sys/kernel/random/boot_id`.
    - `config.json.corrupt.<ts>` → sibling of `--config` (`<TMP>/config.json.corrupt.*`) when `config.json` fails JSON parsing on startup (`D-26`).
  - **Crucial E2E Assertion**: On this Linux devbox, `/data/adb/silo` does NOT exist and is NOT writable by `antigravity` (`uid=1001`). If M1/M2 mistakenly hardcodes `/data/adb/silo/silod.lock` or `/data/adb/silo/sysctl_orig.json`, our black-box tests in `<TMP>` will immediately catch it (failure to create lock or missing `<TMP>/sysctl_orig.json`)!

### 1.3 Verified `silod` Startup Behavior on This Linux Devbox
Traced line-by-line across `DaemonState::new` (`main.rs:109-153`) and `run_daemon` (`main.rs:1007-1186`) against verified devbox state:
- `/data/adb/silo`: absent (unused when `--socket`, `--config`, `--boot-marker` point to `<TMP>`).
- `/data/system/packages.list`: absent → `read_uid_package_map()` (`sys.rs:158-165`) returns empty `HashMap` cleanly (non-fatal).
- `/sys/fs/cgroup`: exists AND `/proc/mounts` contains `none /sys/fs/cgroup cgroup2 rw,relatime 0 0` → `cgroup_v2_freezer_available == true`, no `uid_*` subdirectories exist (safe no-op on thaw/freeze discovery).
- `/sys/class/backlight`: absent → `detect_screen_on()` (`sys.rs:204-239`) falls through to `dumpsys power` (PATH-resolved!) and defaults to `true` if absent.
- `/proc/sys/kernel/random/boot_id`: readable (`3a92bebf-1d6e-4f35-a65d-b97a586301a7`) and `/proc/sys/*` hardening keys (`kernel.kptr_restrict=1`, `kernel.dmesg_restrict=0`, `kernel.unprivileged_bpf_disabled=2`, `net.ipv4.tcp_timestamps=1`, `fs.suid_dumpable=0`, `net.ipv4.tcp_available_congestion_control="reno cubic"`) are ALL readable unprivileged! (Writes return `EACCES`, which `HardeningEngine::apply` tolerates gracefully after capturing `original_values`!)
- `NETLINK_ROUTE` (`classify_active_interfaces`): succeeds unprivileged on Linux.
- `NETLINK_NETFILTER`: `probe_ipset_netlink()` returns `None` unprivileged; `run_nflog_loop` exits cleanly if unprivileged (non-fatal).
- `NETLINK_SOCK_DIAG`: `list_active_connections` succeeds unprivileged on Linux! Note: `probe_sock_destroy_supported()` (`netlink.rs:658-683`) checks `errno != EOPNOTSUPP && errno != ENOSYS`; unprivileged `EPERM` satisfies that condition, so `sock_destroy_available` may report `true` even unprivileged (actual `destroy_socket` fails with `EPERM`, handled gracefully).
- **Result**: With default config (`firewall.enabled = false`), `silod` starts cleanly on the Linux devbox as non-root `antigravity`, binds `<TMP>/silod.sock` (`0600`), passes `SO_PEERCRED` (`cred.uid == self_euid`), and serves RPCs immediately!
- **With Firewall Enabled (`firewall.enabled = true`)**: See Section 2.1 below — because `/sbin/iptables-restore` exists on host, enabling firewall requires launching `silod` inside an ephemeral `unshare -Urm` sandbox with a single bind-mounted `xtables-nft-multi` shim (empirically verified working on this devbox!).

---

## 2. External Command Resolution (`sys.rs` `resolve_bin` & `run_cmd`) & Devbox Interception Blueprint

### 2.1 Line-by-Line Audit of `resolve_bin` (`daemon/src/sys.rs:115-123`) & `run_cmd` (`sys.rs:26-93`)
```rust
// daemon/src/sys.rs:115-123
pub fn resolve_bin(name: &str) -> String {
    for dir in ["/system/bin", "/vendor/bin", "/sbin", "/usr/sbin", "/usr/bin", "/bin"] {
        let candidate = format!("{}/{}", dir, name);
        if Path::new(&candidate).exists() {
            return candidate;
        }
    }
    name.to_string()
}
```
- `run_cmd` (`sys.rs:26-93`) invokes `Command::new(program)` directly (NEVER `/bin/sh`). When `resolve_bin` returns bare `name`, Linux `execvp` searches the child process's `$PATH`.
- **Empirical Probe Results on This Linux Devbox** (verified directly via `test -e` & `readlink -f`):

| Tool | `resolve_bin(tool)` Result on Devbox | Symlink Target (`readlink -f`) | Interception Method for `silod_harness.py` |
|---|---|---|---|
| `cmd` | `"cmd"` (**`$PATH` fallback!**) | — | Drop executable shim in `<TMP>/bin/cmd`, prepend `<TMP>/bin` to `PATH` |
| `pm` | `"pm"` (**`$PATH` fallback!**) | — | Drop executable shim in `<TMP>/bin/pm` |
| `am` | `"am"` (**`$PATH` fallback!**) | — | Drop executable shim in `<TMP>/bin/am` |
| `dumpsys` | `"dumpsys"` (**`$PATH` fallback!**) | — | Drop executable shim in `<TMP>/bin/dumpsys` |
| `settings` | `"settings"` (**`$PATH` fallback!**) | — | Drop executable shim in `<TMP>/bin/settings` |
| `svc` | `"svc"` (**`$PATH` fallback!**) | — | Drop executable shim in `<TMP>/bin/svc` (note: handles `svc power reboot` so `/sbin/reboot` fallback is NEVER reached!) |
| `getprop` | `"getprop"` (**`$PATH` fallback!**) | — | Drop executable shim in `<TMP>/bin/getprop` |
| `iptables` | **`"/sbin/iptables"`** | `/usr/sbin/xtables-nft-multi` | **Ephemeral `unshare -Urm` + SINGLE `mount --bind <TMP>/xtables_shim /usr/sbin/xtables-nft-multi`** |
| `ip6tables` | **`"/sbin/ip6tables"`** | `/usr/sbin/xtables-nft-multi` | Handled by the SAME single bind mount above (dispatch via `os.path.basename(sys.argv[0])`!) |
| `iptables-restore` | **`"/sbin/iptables-restore"`** | `/usr/sbin/xtables-nft-multi` | Handled by the SAME single bind mount above! |
| `ip6tables-restore` | **`"/sbin/ip6tables-restore"`** | `/usr/sbin/xtables-nft-multi` | Handled by the SAME single bind mount above! |
| `service` | `"/sbin/service"` | `/usr/sbin/service` | Only invoked if `ClipboardGuard` adds `service call clipboard` fallback; can optionally bind-mount `<TMP>/service_shim` over `/sbin/service` inside `unshare -Urm` |
| `su` | `"/usr/bin/su"` | `/usr/bin/su` | Only invoked in `ClipboardGuard::clear_now` (`scopes.rs:727`) if `cmd clipboard` fails; keep `cmd clipboard` shim succeeding unless testing failure path |
| `reboot` | `"/sbin/reboot"` | `/usr/bin/systemctl` | Only invoked in `execute_clean_bfu_reboot` (`automation.rs:415`) if `svc power reboot` fails — NEVER reached when `<TMP>/bin/svc` succeeds! |

### 2.2 Empirically Verified `unshare -Urm` Multi-Call `xtables` Shim & `SO_PEERCRED` Proof
We empirically verified all 3 critical properties on this devbox (and cleaned up `/tmp` afterward):
1. **Why real `iptables-restore` in `unshare -Urn` does NOT work**: On this devbox kernel, `unshare -Urn /sbin/iptables-restore` fails on `-m owner --uid-owner 0-9999` (`Extension owner revision 0 not supported` / `Bad value for "--uid-owner"`), `xt_recent`, and `NFLOG`.
2. **Why `unshare -Urm` + single bind mount of `/usr/sbin/xtables-nft-multi` works 100%**:
   - `/sbin -> usr/sbin` and all 4 firewall tools symlink to `/usr/sbin/xtables-nft-multi`.
   - `unshare -Urm sh -c "mount --bind '<TMP>/xtables_shim' /usr/sbin/xtables-nft-multi && exec '<SILOD_BIN>' ..."` runs completely unprivileged, leaves **ZERO** host changes (private mount namespace vanishes when `silod` exits), and dispatches `os.path.basename(sys.argv[0])` cleanly to `iptables`, `ip6tables`, `iptables-restore`, and `ip6tables-restore`!
   - **Pitfall verified**: Inside `xtables_shim`, ONLY read `sys.stdin.read()` when `tool.endswith("-restore")` (otherwise `--version` / `-C` / `-S` could block if stdin weren't `/dev/null`).
3. **`SO_PEERCRED` across `unshare -Urm` verified**:
   - Host Python client (`uid=1001`) connecting to Unix socket bound by `silod` inside `unshare -Urm` (`euid=0`) was verified to yield `peer_uid=0, euid=0, pass=True` — BOTH host Python socket clients AND host `silod --bridge` / `--once` / `--status` processes authenticate seamlessly!

**Conclusion**: Combining `$PATH` shims (`cmd`, `pm`, `am`, `dumpsys`, `settings`, `svc`, `getprop`) + `unshare -Urm` single-bind `xtables-nft-multi` shim enables **100% genuine black-box E2E testing of the compiled `silod` binary for ALL of F1, F2, F3, F4, F5, F6, F7, F8, F9, F11, and F12!**

### 2.3 Exhaustive Command Invocation Contract Table (`argv` & `stdin`) for Test-Double Shims

Every shim should append a structured JSONL record `{ts, tool, argv, stdin}` to `<TMP>/cmd_log.jsonl` and mutate/read a shared JSON state file `<TMP>/android_state.json` (with `fcntl.flock`) so state persists realistically across commands (e.g., `pm revoke` + `cmd appops set` updates state read back by `cmd appops get` & `dumpsys package`!).

| Tool | Exact `argv` Patterns (Baseline + Target) | `stdin` | Source Location(s) | Realistic Android 15 / One UI 7 Responder Behavior |
|---|---|---|---|---|
| `cmd` | `appops get --user <u_id> <pkg>` | none | `scopes.rs:210, 688` | Print realistic One UI 7 lines including `Uid mode: <OP>: <mode>` AND `<OP>: <pkg_mode>; time=+3m12s ago; duration=+5s` (plus `(running)` when simulated active) |
| `cmd` | `appops set --user <u_id> [--uid] <pkg> <OP> <mode>` | none | `scopes.rs:164` + target `D-02`/`CB-02` | Record `--uid` vs pkg mode separately in `android_state.json`; support fault-injection mode (e.g., reject pkg-mode or return stdout-only error `Error: ...` with exit 1 to test `D-02` error checking & `revoked-compat` fallback!) |
| `cmd` | `package set-permission-flags --user <u_id> <pkg> <perm> <flags...>` / `clear-permission-flags ...` | none | Target `D-02`/`CB-02`/`CB-03` | Update per-perm flags (`user-set`, `user-fixed`, `revoked-compat`, `system-fixed`) in `android_state.json` |
| `cmd` | `sensor_privacy <enable\|disable> 0 <microphone\|camera>` | none | `automation.rs:311` | Update `sensor_privacy.{mic,camera}` in state; support partial-failure injection (fail `camera` while succeeding `microphone` to test `D-22`/`CB-20` strict `"all"` check!) |
| `cmd` | `clipboard clear` / `clipboard set-text ""` | none | `scopes.rs:717-718` | Clear clipboard state in `android_state.json` (or fail when fault-injected) |
| `pm` | `revoke --user <u_id> <pkg> <perm>` | none | `scopes.rs:154` | Mark runtime perm revoked; return exit 1 + stderr/stdout if perm is flagged `system-fixed`! |
| `pm` | `grant --user <u_id> <pkg> <perm>` | none | Target `PROJECT.md #2` (`allow`) | Mark runtime perm granted (if declared and not blocked) |
| `pm` | `set-permission-flags --user <u_id> <pkg> <perm> <flags...>` | none | Target `PROJECT.md #2` (`locked_deny` / `revoked-compat`) | Add flags (`user-set`, `user-fixed`, `revoked-compat`) to perm state |
| `pm` | `clear-permission-flags --user <u_id> <pkg> <perm> <flags...>` | none | Target `PROJECT.md #2` (unlock / `allow` / `--uninstall-cleanup`) | Remove specified flags from perm state |
| `pm` | `suspend --user <u_id> <pkg>` / `unsuspend --user <u_id> <pkg>` | none | `freezer.rs:125,153,174`, `main.rs:1265` | Add/remove `pkg` in `suspended_packages` state |
| `pm` | `list packages --user <u_id>` (if called by target F6) | none | Target `D-12`/`CB-14` | Output `package:<pkg>` lines |
| `am` | `force-stop <pkg>` | none | `freezer.rs:118` | Record force-stop in `cmd_log.jsonl`, return 0 |
| `am` | `set-standby-bucket <pkg> <restricted\|active>` | none | `freezer.rs:98,121,154,175` | Record bucket state, return 0 |
| `dumpsys` | `appops --op <OP>` (11 ops: `CAMERA`, `RECORD_AUDIO`, `FINE_LOCATION`, `COARSE_LOCATION`, `READ_CLIPBOARD`, `READ_CONTACTS`, `READ_SMS`, `READ_CALL_LOG`, `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `BODY_SENSORS`) | none | `scopes.rs:333-349` | Emit Android 15 / One UI 7 format with `Uid u0a277:`, `Package <pkg>:`, `<OP> (allow/ignore):`, prefix-trap `<OP>_SANDBOXED (allow):`, multiple `Access:` lines (`[top-s]` older + `[fg-s] ... (running)` newer), protected packages (`com.samsung.android.honeyboard`, `dev.silo.app`) |
| `dumpsys` | `package <pkg>` (or `package`) | none | Target F1/F2/F6 (`D-02`, `D-12`) | Emit realistic `requested permissions:`, `runtime permissions:` (`<perm>: granted=true/false, flags=[ USER_SET\|USER_FIXED\|SYSTEM_FIXED ]`), and `User 0: ... suspended=true/false` |
| `dumpsys` | `sensor_privacy` | none | `automation.rs:328` | Emit Android 15 brace-structured `users: { user_id=0 toggles: { sensor=1 toggle_type=1 state_type=1/2 } toggles: { sensor=2 ... } }` + distractor `user_id=10` & trailing `listeners: { enabled=true }` block to test `CB-20`! |
| `dumpsys` | `clipboard` | none | `scopes.rs:768` | Emit `ClipDescription { text/plain label=null }` + `mPrimaryClipTimestamp=...` (tests `D-17`/`CB-15` `label=null` bug!) vs `Primary clip: null` when empty |
| `dumpsys` | `power` / `display` | none | `sys.rs:230` + target `D-07` | Emit `mWakefulness=Awake\|Dozing\|Asleep` (`power`) and `mState=ON\|DOZE\|OFF` (`display`) |
| `dumpsys` | `trust` / `window policy` | none | `sys.rs:244, 255` | Emit `deviceLocked=1\|0` (`trust`) and `mShowingLockscreen=true\|false` (`window policy`) |
| `dumpsys` | `bluetooth_manager` | none | `automation.rs:443` | Emit `Connected devices: []` / `Connected devices: none` / `ConnectionState: STATE_CONNECTED` (`CB-21`) |
| `dumpsys` | `user` | none | Target `D-20` | Emit `UserInfo{0:...} state=RUNNING_UNLOCKED` (when CE unlocked) |
| `settings` | `get global <key>` / `put global <key> <val>` | none | `automation.rs:146,190,205,222-291,427` | Persist/return keys: `private_dns_mode`, `private_dns_specifier`, `wifi_connected_mac_randomization_enabled`, `wifi_enhanced_mac_randomization`, `wifi_scan_always_enabled`, `ble_scan_always_enabled`, `captive_portal_mode`, `captive_portal_detection_enabled`, `adb_enabled`, `adb_wifi_enabled`, `wifi_on`, `bluetooth_on` |
| `svc` | `usb setFunctions [none]` / `wifi disable` / `bluetooth disable` / `power reboot` | none | `automation.rs:100,116,149,151,157,161,410` | Record invocation and return exit 0 (CRITICAL: `svc power reboot` returns 0 so real `/sbin/reboot` is never invoked!) |
| `getprop` | `<prop_name>` (e.g. `sys.user.0.ce_available`) | none | `sys.rs:178-193` | Return value from `android_state.json["props"]` |
| `iptables` / `ip6tables` | `--version`, `-m set --help`, `-m recent --help`, `-j NFLOG --help`, `-t mangle -j TTL --help`, `-t mangle -j HL --help`, `-w -t <table> -C/-I/-D/-F/-X ...`, `-w -C OUTPUT -j SILO_OUT`, `-w -S <chain>` | none | `firewall.rs:65-120,399-454`, `sentinel.rs:294-318,434` | Return realistic `--help` tokens (`--match-set`, `--rcheck`, `--nflog-group`, `--ttl-set`, `--hl-set`); track installed hooks & chains in `android_state.json` so `-C` and `-S` reflect live committed rules for `run_live_proof`! |
| `iptables-restore` / `ip6tables-restore` | `--noflush -w` | **Full restore script** (`*filter\n...COMMIT\n[*mangle\n...COMMIT\n]`) | `firewall.rs:461-474` | Save exact `stdin` script to `<TMP>/last_ipt_v4.rules` / `last_ipt_v6.rules` + append to `cmd_log.jsonl`; support optional fault injection (e.g., reject IPv6 `NFLOG` or `HL` on first call to test `D-14` IPv4/IPv6 capability isolation!) |

---

## 3. Exact Wire Protocol, 49 RPC Commands & DTO Schemas (`protocol.rs`, `config.rs`, `SiloModels.kt`)

### 3.1 Framing & Envelopes (`protocol.rs:8-23, 516-556`, `ipc.rs:49-88`)
- **Transport**: Unix stream socket (`0600` in `0700` dir, `SO_PEERCRED` `cred.uid == 0 || cred.uid == geteuid()`).
- **Framing**: Newline-delimited UTF-8 JSON (`\n`).
  - Baseline `MAX_IPC_LINE_BYTES = 512 * 1024` (`protocol.rs:8`), bridge response limit `4 * MAX_IPC_LINE_BYTES` (`ipc.rs:225,259`).
  - **Target (`PROJECT.md #1`)**: `MAX_IPC_LINE_BYTES = 4 * 1024 * 1024` (4 MiB), response limit `8 * 1024 * 1024` (8 MiB).
- **Request**: `{"id": <u64>, "cmd": "<snake_case>", "params": <object|null>}\n` (`params` omitted or `null` for 0-arg commands).
- **Success Response**: `{"id": <u64>, "ok": true, "data": <json>}\n` (`error` omitted; `ok_empty` returns `"data": {"done": true}`).
- **Error Response**: `{"id": <u64>, "ok": false, "error": "<message>"}\n` (`data` omitted; malformed JSON envelope returns `id: 0`, known envelope with invalid params/cmd echoes `req.id`).
- **`BTreeMap<u32, V>` Serialization**: `firewall.rules`, `freezer.entries`, `temp_unblocks_remaining`, `per_uid_blocked` serialize keys as JSON strings (`"10277"`) and deserialize BOTH JSON string keys (`"10277"`) and integer keys (`10277`) via `deserialize_u32_map` (`config.rs:66-110`).

### 3.2 Exact Enum Values & Schema Quirks (Verified Against BOTH `config.rs` & `SiloModels.kt`)

| Enum / Type | Field Name(s) | Exact JSON String Values | Critical Quirk to Know |
|---|---|---|---|
| `FirewallMode` (`config.rs:114`) | `firewall.mode` | `"default_allow"`, `"default_deny"` | **QUIRK**: `DaemonStatus.firewall_mode` (`main.rs:247`, `SiloModels.kt:271`) uses `format!("{:?}", mode)` → **`"DefaultAllow"`** / **`"DefaultDeny"`** (PascalCase)! |
| `BlockAction` (`config.rs:124`) | `firewall.block_action` | `"reject"`, `"drop"` | Rust/Kotlin name is `BlockAction` (NOT `FirewallRejectMode`). |
| `FreezeMode` (`config.rs:235`) | `freezer.entries[uid].mode` | `"soft_cgroup"`, `"deep_stop"` | Default is `"soft_cgroup"`. |
| `SecurityPreset` (`config.rs:432`) | `apply_preset.params.preset` | `"safe"`, `"balanced"`, `"hardened"`, `"paranoid"` | `apply_preset` NEVER enables `auto_reboot_enabled` (`config.rs:890`). |
| `AuditSeverity` (`protocol.rs:739`) | `findings[].severity` | `"pass"`, `"info"`, `"warn"`, `"critical"` | — |
| `VALID_PERM_CATEGORIES` (`config.rs:13-24`) | `category`, `auto_revoke_on_bg[]`, `locked_deny[]` | `"camera"`, `"microphone"`, `"location"`, `"background_location"`, `"contacts"`, `"media"`, `"sms_calllog"`, `"clipboard"`, `"sensors"`, `"background"` | Exactly 10 categories. `supports_foreground == true` ONLY for `camera`, `microphone`, `location`, `sensors`; `has_runtime_perms == false` ONLY for `clipboard`, `background` (`PROJECT.md #2`). |

### 3.3 CRITICAL Schema Check — `CategoryPermLiveState` & Target Additive Fields
1. **`CategoryPermLiveState`** (`protocol.rs:603-608`, `SiloModels.kt:227-232`, `PROJECT.md #2`):
   - **Existing backward-compatible fields** (present in BOTH Rust & Kotlin):
     - `category: String`
     - `appops_mode: String` (`"allow" | "foreground" | "ignore" | "deny" | "default"`)
     - **`last_access: Option<String>`** — **NOTE**: `PROJECT.md:90` wrote `"last_access_ago"` in its illustrative JSON snippet while explicitly stating on line 84 *"All existing fields MUST remain backward-compatible"*. Both `protocol.rs:606` and `SiloModels.kt:230` use **`last_access`**! E2E tests should verify `last_access` (accepting `last_access` or `last_access_ago` only if checking timestamp presence, while strictly checking `appops_mode` and the 4 additive fields below).
     - `running_now: bool`
   - **Target additive fields (`#[serde(default)]`, `PROJECT.md #2`)**:
     - `runtime_granted: Option<bool>` (`true` | `false` | `null`; `null` for AppOps-only `clipboard`/`background` or unavailable)
     - `has_runtime_perms: bool` (`false` for `clipboard`/`background`, `true` for the other 8)
     - `supports_foreground: bool` (`true` for `camera`/`microphone`/`location`/`sensors`, `false` for the other 6)
     - `system_fixed: bool` (`true` if Android marks perm `SYSTEM_FIXED`)
2. **`DaemonStatus`** (`protocol.rs:640-671`, `PROJECT.md #3`):
   - Existing 29 fields: `version`, `pid`, `uptime_secs`, `boot_guard_armed`, `boot_guard_cleared`, `firewall_active`, `firewall_mode`, `ipset_entries_loaded`, `ipset_backend`, `vpn_active`, `active_interfaces`, `screen_on`, `device_locked`, `ce_unlocked`, `sensors_off_active`, `mic_muted`, `camera_muted`, `frozen_uids_count`, `suspended_pkgs_count`, `blocked_events_total`, `auto_reboot_remaining_secs`, `private_dns`, `radio_privacy`, `enforcer_rules_count`, `enforcer_actions_count`, `last_enforcer_event`, `temp_unblocks_remaining`, `capabilities`, `last_firewall_error`.
   - **Target additive field**: `panic_lockdown_active: bool` (`#[serde(default)]`).
3. **`FirewallConfig`** (`config.rs:181-209`, `PROJECT.md #3`):
   - Existing 15 fields: `enabled`, `mode`, `block_action`, `block_new_apps`, `isolate_lan`, `dns_leak_guard`, `ipv6_leak_guard`, `block_cleartext_http_global`, `anti_port_scan`, `normalize_ttl_hl`, `ttl_value`, `nflog_enabled`, `sock_destroy_on_block`, `rules`, `ip_blocklist`.
   - **Target additive field**: `panic_lockdown_active: bool` (`#[serde(default)]`).

### 3.4 Complete 49-Command RPC Reference (`protocol.rs:288-510`)

| Group | `cmd` | Request `params` | Response `data` |
|---|---|---|---|
| **Core (6)** | `ping` | `null` | `{"pong": true, "version": "3.0.0"}` |
| | `get_status` | `null` | `DaemonStatus` (target: <1ms cached, 0 subprocesses) |
| | `get_config` | `null` | `SiloConfig` |
| | `apply_preset` | `{"preset": "safe"\|"balanced"\|"hardened"\|"paranoid"}` | `SiloConfig` |
| | `export_config_json` | `null` | `{"json": "<pretty JSON>"}` |
| | `import_config_json` | `{"json": "<JSON string>"}` | `SiloConfig` |
| **Firewall (7)** | `set_firewall_config` | `{"config": FirewallConfig}` | `FirewallConfig` |
| | `set_app_firewall_rule` | `{"uid": u32, "rule": AppFirewallRule}` | `{"done": true}` |
| | `remove_app_firewall_rule` | `{"uid": u32}` | `{"done": true}` |
| | `set_temp_unblock` | `{"uid": u32, "duration_minutes": u32}` (clamped `1..=240`) | `{"uid": u32, "remaining_secs": u64}` |
| | `clear_temp_unblock` | `{"uid": u32}` | `{"done": true}` |
| | `replace_ip_blocklist` | `{"entries": [String]}` (max `50_000`) | `{"loaded": usize, "rejected": usize, "backend": String}` |
| | `emergency_flush_firewall` | `null` | `{"done": true}` |
| **Net/Tel (5)** | `kill_uid_sockets` | `{"uid": u32}` | `{"killed": usize}` |
| | `kill_connection` | `{"family": u8, "local_ip": String, "local_port": u16, "remote_ip": String, "remote_port": u16}` | `{"killed": usize}` |
| | `get_live_connections` | `null` | `[ActiveConnection]` |
| | `get_telemetry` | `{"limit": usize?}` (optional, clamped `1..=2048`, default `200`) | `TelemetrySnapshot` |
| | `clear_telemetry` | `null` | `{"done": true}` |
| **Freezer (7)** | `set_freezer_enabled` | `{"enabled": bool, "background_grace_secs": u32}` (clamped `5..=600`) | `FreezerStatus` |
| | `set_freezer_entry` | `{"uid": u32, "entry": FreezerEntry}` | `FreezerStatus` |
| | `remove_freezer_entry` | `{"uid": u32}` | `FreezerStatus` |
| | `freeze_uid_now` | `{"uid": u32}` | `FreezerStatus` |
| | `thaw_uid_now` | `{"uid": u32}` | `FreezerStatus` |
| | `thaw_all_now` | `null` | `FreezerStatus` |
| | `get_freezer_status` | `null` | `FreezerStatus` |
| **Perms/Scopes (10)** | `set_app_scope` | `{"package_name": String, "scope": AppIsolationScope}` | `{"applied_ops": usize}` |
| | `apply_hardened_scope` | `{"package_name": String}` | `AppIsolationScope` |
| | `clear_app_scope` | `{"package_name": String}` | `{"done": true}` |
| | `inspect_app_permissions` | `{"package_name": String}` | `AppLivePermissions` |
| | `set_app_permission_action` | `{"package_name": String, "category": String, "action": "allow"\|"foreground"\|"ignore"\|"revoke"\|"default"\|"deny"}` | `AppLivePermissions` |
| | `set_app_perm_guard` | `{"package_name": String, "guard": AppPermGuard}` | `ScopesConfig` |
| | `bulk_revoke_category` | `{"category": String, "packages": [String]}` | `{"category": String, "revoked_count": usize}` |
| | `get_privacy_access_log` | `{"limit": usize?}` (optional, clamped `10..=200`, default `80`) | `[PrivacyAccessEvent]` |
| | `set_auto_clear_clipboard` | `{"enabled": bool, "clear_secs": u32}` (clamped `10..=3600`) | `ScopesConfig` |
| | `clear_clipboard_now` | `null` | `{"done": true}` |
| **Hardening (3)** | `set_hardening_config` | `{"config": HardeningConfig}` | `HardeningStatus` |
| | `rollback_hardening` | `null` | `HardeningStatus` |
| | `get_hardening_status` | `null` | `HardeningStatus` |
| **Auto/Radio (9)** | `set_automation_config` | `{"config": AutomationConfig}` | `AutomationConfig` |
| | `set_sensors_off` | `{"enabled": bool}` | `SensorPrivacyState` |
| | `set_sensor_privacy` | `{"sensor": "microphone"\|"mic"\|"camera"\|"cam"\|"all"\|"both", "enabled": bool}` | `SensorPrivacyState` |
| | `get_private_dns` | `null` | `PrivateDnsState` |
| | `set_private_dns` | `{"mode": "off"\|"opportunistic"\|"hostname", "specifier": String}` | `PrivateDnsState` |
| | `get_radio_privacy` | `null` | `RadioPrivacyState` |
| | `set_radio_privacy` | `{"enhanced_mac_randomization": bool?, "disable_wifi_ble_scanning": bool?, "disable_captive_portal": bool?}` | `RadioPrivacyState` |
| | `trigger_panic_lockdown` | `null` | `PanicLockdownReport` |
| | `trigger_panic_reboot` | `{"confirm_token": "CONFIRM_SILO_BFU_REBOOT"}` | `{"done": true}` |
| **Audit/Proof (2)** | `run_sentinel_audit` | `null` | `SentinelReport` |
| | `run_live_proof` | `null` | `LiveProofReport` |

### 3.5 Exact `PROTECTED_PACKAGES` & `is_protected_uid` Boundaries (`config.rs:42-62, 666-677`)
- **`PROTECTED_PACKAGES` (19 exact strings)**:
  `android`, `com.android.systemui`, `com.android.phone`, `com.android.settings`, `com.android.networkstack`, `com.android.networkstack.tethering`, `com.android.providers.settings`, `com.android.providers.telephony`, `com.android.shell`, `com.android.bluetooth`, `com.android.se`, `com.samsung.android.honeyboard`, `com.google.android.inputmethod.latin`, `dev.silo.app`, `com.rifsxd.ksunext`, `me.weishu.kernelsu`, `com.sukisu.ultra`, `com.resukisu.resukisu`, `me.bmax.apatch`.
- **`is_protected_uid(uid: u32)`**: `let app_id = uid % 100_000; !(10_000..90_000).contains(&app_id)`.
  - **Protected**: `app_id` in `0..=9999` (e.g., `0`, `1000`, `1051`, `2000`, `9999`, `100_000`, `101_000`, `1_000_000`, `9_500_000`, `15_000_000`) AND `app_id` in `90_000..=99_999` (e.g., `90_000`, `99_000`, `99_999`, `199_000`).
  - **Non-protected user app UIDs**: `app_id` in `10_000..=89_999` across every Android user `U` (`10_000`, `10_277`, `89_999`, `110_277`, Work Profile `1_010_050`, Dual Messenger `9_510_050`, Secure Folder `15_010_050`).
- **Validation Limits (`config.rs:29-35, 679-797`)**:
  - `validate_package_name`: len `3..=128`, contains `.`, each dot segment starts with ASCII alpha/`_` and contains only ASCII alnum/`_`.
  - `validate_hostname`: len `3..=128`, contains `.`, each label `1..=63`, alnum/`-`, no leading/trailing `-`.
  - `validate_cidr`: rejects `/0`, loopback (`127.0.0.0/8`, `::1`), unspecified (`0.0.0.0/8`, `::`), masks host bits (`192.168.1.42/24` → `192.168.1.0/24`), omits `/32` and `/128`.
  - Clamping: `auto_reboot_minutes ∈ [15, 10080]`, `wifi/bt_auto_off_minutes ∈ [2, 1440]`, `clipboard_clear_secs ∈ [10, 3600]`, `background_grace_secs ∈ [5, 600]`, `ttl_value == 0 → 64`, `set_temp_unblock duration_minutes ∈ [1, 240]`.

---

## 4. Devbox Rust Toolchain & Build Check (Read-Only Verified)

- **Toolchain**: `rustc 1.100.0`, `cargo 1.100.0` at `/opt/cargo/bin`, `CARGO_HOME=/opt/cargo`, `RUSTUP_HOME=/opt/rustup` (both writable by `antigravity`). Installed components: `clippy`, `rustfmt`, `rust-std-x86_64-unknown-linux-gnu`, bundled `rust-lld`.
- **Current Build Artifact Status**:
  - `daemon/target/debug/silod`: **does NOT exist yet**.
  - `/opt/cargo/registry/cache`: **currently empty** (12 locked crates in `daemon/Cargo.lock`, root deps `libc 0.2.191`, `serde 1.0.229`, `serde_json 1.0.154`).
  - `aarch64-unknown-linux-musl` target: **not yet installed** (`daemon/.cargo/config.toml` already configures `linker = "rust-lld"`, `+crt-static`, `relocation-model=static`).
- **Canonical Build Commands for `silod_harness.py`**:
  - Host debug binary (for E2E): `cargo build --manifest-path /home/antigravity/terenzy/Silo/daemon/Cargo.toml` → yields `/home/antigravity/terenzy/Silo/daemon/target/debug/silod`.
  - Respect `SILOD_BIN` env override and `SILO_SKIP_BUILD=1` per `TEST_INFRA.md:89-93`. Never modify tracked repo files; fail loudly (exit 1, zero silent skip) if `silod` binary cannot be found or built.

---

## 5. Concrete E2E Test Matrix Proposal (`F1–F9`, `F11`, `F12`)

Every test below targets the **frozen requirements in `PROJECT.md`** via **live compiled `silod`** (over real AF_UNIX sockets / `--bridge` / CLI with `$PATH` Android CLI shims + `unshare -Urm` `xtables-nft-multi` shim), with explicit annotations for the **only** narrow kernel-path cases needing a `cargo test` fallback.

### 5.1 Tier 1 (Feature Happy-Path Coverage — `>= 5` Tests per Feature)

| Feature | Proposed Live `silod` Black-Box Test Cases (Minimum 5 per Feature) |
|---|---|
| **F1** (Per-App Perm & AppOps) | `T1-F1-01`: `inspect_app_permissions` parses One UI 7 `Uid mode: CAMERA: ignore` + historical `CAMERA: allow; time=+3m12s ago` → returns `appops_mode == "ignore"`, `running_now == false`, plus all 4 additive fields (`runtime_granted`, `has_runtime_perms`, `supports_foreground`, `system_fixed`).<br>`T1-F1-02`: `set_app_permission_action(..., "camera", "revoke")` invokes `pm revoke` + `--uid` AppOps `ignore` and reads back `appops_mode == "ignore"`, `runtime_granted == false`.<br>`T1-F1-03`: `set_app_permission_action(..., "camera", "ignore")` (`Palsukan`) sets `--uid` AppOps `ignore` WITHOUT invoking `pm revoke` (leaves `runtime_granted == true`).<br>`T1-F1-04`: `set_app_permission_action(..., "camera", "allow")` (`Normal`) clears `user-fixed`/`revoked-compat`, resets UID+pkg AppOps to `default`, and runs `pm grant` on declared revoked runtime perm.<br>`T1-F1-05`: `set_app_permission_action(..., "location", "foreground")` succeeds (`supports_foreground == true`) AND `bulk_revoke_category("media", [pkg1, pkg2])` revokes including Android 14+ `READ_MEDIA_VISUAL_USER_SELECTED` and returns `revoked_count == 2`. |
| **F2** (Enforcer: `Kunci Blokir` & `Auto-Cabut`) | `T1-F2-01`: `set_app_perm_guard` with `locked_deny: ["camera"]` persists in `config.json`, immediately invokes `pm revoke` + `pm set-permission-flags ... user-fixed` + `--uid` AppOps `ignore`.<br>`T1-F2-02`: Removing `"camera"` from `locked_deny` via `set_app_perm_guard` invokes `pm clear-permission-flags ... user-fixed`.<br>`T1-F2-03`: Manual override via `set_app_permission_action(pkg, "camera", "allow")` while `"camera"` is in `locked_deny` automatically removes `"camera"` from `config.scopes.guards[pkg].locked_deny` and persists config.<br>`T1-F2-04`: Restarting `silod` with `locked_deny: ["microphone"]` in `config.json` enforces `locked_deny` on daemon startup without waiting 15s.<br>`T1-F2-05`: `set_app_perm_guard` with `auto_revoke_on_bg: ["location"]` updates `DaemonStatus.enforcer_rules_count == 1` and survives daemon restart. |
| **F3** (Privacy Monitor & Traffic) | `T1-F3-01`: `get_privacy_access_log` parses `dumpsys appops --op` with `(allow/ignore)` strict tuple → reports `"ignore"` (NOT `"allow"`).<br>`T1-F3-02`: `get_privacy_access_log` scans ALL `Access:` lines per op and surfaces fresher / `(running)` `[fg-s]` entry first ahead of older `[top-s]` line.<br>`T1-F3-03`: `get_privacy_access_log` excludes protected packages (`dev.silo.app`, `com.samsung.android.honeyboard`, `com.rifsxd.ksunext`) and UIDs `< 10000`.<br>`T1-F3-04`: `get_live_connections` returns real host TCP/UDP sockets from `NETLINK_SOCK_DIAG` with valid `family`/`protocol`/`state`/`local_port`.<br>`T1-F3-05`: `get_telemetry` and `clear_telemetry` return valid `TelemetrySnapshot` (`total_blocked`, `per_uid_blocked`, `events`) and reset cleanly. |
| **F4** (IPC Bridge & Concurrency) | `T1-F4-01`: Raw AF_UNIX socket JSON-RPC `ping` echoes exact `id` and returns `{"pong": true, "version": "3.0.0"}`; socket permissions strictly `0600` in `0700` dir.<br>`T1-F4-02`: `get_status` executes in `< 50ms` with **0 subprocesses** (verified via `cmd_log.jsonl` delta == 0!) even while a slow `dumpsys`/`cmd` RPC is in flight on another client.<br>`T1-F4-03`: `silod --bridge` handles multiple sequential RPCs over persistent stdin/stdout pipe, echoing matching `id`s.<br>`T1-F4-04`: `silod --bridge` transparently reconnects and succeeds on next RPC after `silod` is killed and restarted on the same `--socket`.<br>`T1-F4-05`: `silod --once '{"id":7,"cmd":"ping"}'` and `silod --status` return valid JSON on stdout and exit `0`; 8 concurrent socket clients all succeed cleanly. |
| **F5** (Kernel Firewall, Panic & Blocklist) | *(All live `silod` inside `unshare -Urm` with `xtables-nft-multi` shim!)*<br>`T1-F5-01`: `set_firewall_config` (`enabled: true, mode: "default_allow", isolate_lan: true, dns_leak_guard: true, anti_port_scan: true, normalize_ttl_hl: true`) commits IPv4+IPv6 scripts via `iptables-restore --noflush -w` containing `SILO_OUT`, `SILO_IN`, `SILO_LAN_CHK`, `SILO_DENY_DNS`, `SILO_DENY_SCAN`, `*mangle` `SILO_MANGLE_POST` + `TTL`/`HL` 64, and attaches hooks.<br>`T1-F5-02`: `set_app_firewall_rule` (per-UID Wi-Fi/Cell/VPN/HTTP/Localhost) emits exact interface rules including **`xfrm+`**, `ap+`, `v4-pdp+`, `wwan+`, `seth_lte+`, `eth+`/`rndis+`/`ncm+` and persists string-keyed `"10277"` rule.<br>`T1-F5-03`: `set_temp_unblock(10277, 15)` inserts `-A SILO_OUT -m owner --uid-owner 10277 -j RETURN` and reports `temp_unblocks_remaining["10277"] > 0`; `clear_temp_unblock(10277)` removes it.<br>`T1-F5-04`: `replace_ip_blocklist` canonicalizes IPv4/IPv6 CIDRs, strips comments, places `SILO_IN` blocklist BEFORE `ESTABLISHED,RELATED` (including fallback `SILO_BLK_IP_IN`), and returns `{loaded, rejected: 0, backend}`.<br>`T1-F5-05`: `trigger_panic_lockdown` sets `panic_lockdown_active == true` in `DaemonStatus` & `FirewallConfig`, blocks ALL non-protected user UIDs in `SILO_OUT` even when prior `DefaultAllow` `allow_wifi=true` rules existed, and `emergency_flush_firewall` clears lockdown & flushes all chains. |
| **F6** (App Freezer) | `T1-F6-01`: `set_freezer_enabled(true, 30)` + `get_freezer_status` persists config and returns `FreezerStatus` with `cgroup_v2_available: true`.<br>`T1-F6-02`: `set_freezer_entry(10277, DeepStop)` + `freeze_uid_now(10277)` invokes `am force-stop`, `am set-standby-bucket ... restricted`, and `pm suspend --user 0 com.example.target`, adding package to `suspended_packages`.<br>`T1-F6-03`: `thaw_uid_now(10277)` invokes `pm unsuspend --user 0` + `am set-standby-bucket ... active` and removes package from `suspended_packages`.<br>`T1-F6-04`: `thaw_all_now` unsuspends all suspended packages (including packages recovered from live `dumpsys package` after daemon restart!).<br>`T1-F6-05`: Multi-user UID `1010277` (Work Profile user 10) in `DeepStop` invokes `pm suspend --user 10` and `pm unsuspend --user 10` (`CB-08`). |
| **F7** (Automation, Sensors, Clipboard, Radio, DNS) | `T1-F7-01`: `set_sensor_privacy("microphone", true)` & `set_sensor_privacy("camera", false)` invoke `cmd sensor_privacy enable/disable 0 <target>` and read back separated `mic_muted`/`camera_muted` from `dumpsys sensor_privacy`.<br>`T1-F7-02`: `set_private_dns("hostname", "dns.quad9.net")` + `get_private_dns` invokes `settings put global private_dns_specifier/mode` and reads back exact state.<br>`T1-F7-03`: `set_radio_privacy(enhanced_mac=true, scan_off=true, captive_off=true)` + `get_radio_privacy` writes `wifi_enhanced_mac_randomization`, `wifi_scan_always_enabled`, `ble_scan_always_enabled`, `captive_portal_mode`; turning enhanced MAC OFF does NOT write `wifi_connected_mac_randomization_enabled 0` (`D-22`).<br>`T1-F7-04`: `set_auto_clear_clipboard(true, 30)` + `clear_clipboard_now` persists config and invokes `cmd clipboard clear`.<br>`T1-F7-05`: `set_automation_config` persists all toggles & timers (`auto_reboot_minutes`, `wifi_auto_off_minutes`, `bluetooth_auto_off_minutes`, `sensors_off_on_lock`, `usb_adb_lock_guard`); `trigger_panic_reboot("CONFIRM_SILO_BFU_REBOOT")` invokes `svc power reboot` via shim. |
| **F8** (Kernel Hardening & Presets) | `T1-F8-01`: `get_hardening_status` reads real host `/proc/sys` items (`>= 26` specs, `supported == true` on host Linux).<br>`T1-F8-02`: `set_hardening_config(enabled: true, ...)` captures `original_values` (`snapshot_saved == true`) and writes boot-ID-keyed `<TMP>/sysctl_orig.json`.<br>`T1-F8-03`: Restarting `silod` reloads `<TMP>/sysctl_orig.json` matching `/proc/sys/kernel/random/boot_id` so `snapshot_saved == true` survives daemon restart (`D-23`).<br>`T1-F8-04`: `rollback_hardening` disables hardening (`enabled == false`) and restores snapshot cleanly.<br>`T1-F8-05`: `apply_preset` cycling `paranoid` → `hardened` → `balanced` → `safe` cleanly resets ALL automation & hardening flags (`wifi_auto_off_enabled`, `bluetooth_auto_off_enabled`, `usb_adb_lock_guard`, `sensors_off_on_lock`, `auto_clear_clipboard`, `hardening.enabled`) with zero Paranoid leftovers (`D-21`), and never enables `auto_reboot_enabled`. |
| **F9** (Sentinel, Proof, Durability) | `T1-F9-01`: `run_sentinel_audit` returns valid `SentinelReport` (`0 <= score <= 100`, non-empty `findings` with valid `AuditSeverity`).<br>`T1-F9-02`: `run_live_proof` with firewall enabled (in `unshare -Urm` shim) returns 8 `LiveProofItem`s with `ipv4_hook_attached == true`, `ipv4_silo_rules_count > 0`, `ipv6_hook_attached == true`, and verified AppOps/DNS/Hardening items.<br>`T1-F9-03`: `export_config_json` → `import_config_json` round-trips full v3 config (firewall rules, freezer entries, guards, hardening, automation) losslessly.<br>`T1-F9-04`: Corrupt `config.json` on startup triggers automatic backup file `<TMP>/config.json.corrupt.*` and falls back to clean defaults (`D-26`).<br>`T1-F9-05`: Legacy v2 `scopes.apps` entries do NOT silently overwrite v3 `scopes.guards` categories on startup/import (`CB-10`). |
| **F11** (Privilege & Protected Boundary) | `T1-F11-01`: `flock` singleton on `<TMP>/silod.lock` rejects a 2nd `silod` targeting the same `--socket` (non-zero exit) while keeping the 1st daemon and socket inode intact.<br>`T1-F11-02`: Socket file created with `0600` inside `0700` dir; clean `SIGTERM` shutdown unlinks socket ONLY if inode matches (replacing socket path with a different file before `SIGTERM` leaves the replacement untouched!).<br>`T1-F11-03`: `set_app_firewall_rule`, `set_temp_unblock`, `kill_uid_sockets`, `set_freezer_entry`, `freeze_uid_now` reject ALL protected UIDs (`0`, `1000`, `1051`, `9999`, `90000`, `99000`, `100000`, `101000`, `199000`).<br>`T1-F11-04`: `set_app_firewall_rule`, `set_freezer_entry`, `set_app_permission_action`, `set_app_perm_guard`, `set_app_scope` reject ALL 19 `PROTECTED_PACKAGES` (including `dev.silo.app`, `com.rifsxd.ksunext`, `com.samsung.android.honeyboard`).<br>`T1-F11-05`: Process-group timeout cleanup in `run_cmd`: a shim that spawns a background sleep grandchild holding stdout open still terminates promptly on timeout without hanging `silod` (`CB-17`). |
| **F12** (Daemon CLI / Lifecycle portion) | `T1-F12-01`: `silod --version` prints `silod 3.0.0` and exits `0`.<br>`T1-F12-02`: `silod --emergency-flush --config <TMP>/config.json` flushes all `SILO_*` chains and unsuspends configured freezer packages.<br>`T1-F12-03`: `silod --uninstall-cleanup --config <TMP>/config.json` flushes firewall + unsuspends freezer packages + resets all AppOps (`scopes.apps` & `scopes.guards`) to `default` and clears `user-fixed`/`revoked-compat` flags (`CB-19`).<br>`T1-F12-04`: `daemon/Cargo.toml` description updated from `"Silo v2..."` to v3 (`CB-30`).<br>*(Plus shell/packaging/CI tests owned by module/CI explorer blueprint!)* |

### 5.2 Tier 2 (Boundary, Corner & Negative Error Cases — `>= 5` Tests per Feature)

| Feature | Proposed Negative / Boundary / Corner Test Cases |
|---|---|
| **F1** | `T2-F1-01`: `set_app_permission_action` rejects `"foreground"` on unsupported categories (`clipboard`, `contacts`, `media`, `sms_calllog`, `background`, `background_location`).<br>`T2-F1-02`: Multi-op fail-closed aggregation (`D-03`): in `media`, if `MANAGE_EXTERNAL_STORAGE` is `ignore` while `READ_MEDIA_IMAGES` is `allow`, `inspect_app_permissions` MUST return `"allow"` (NOT `"ignore"`).<br>`T2-F1-03`: Error reporting when `cmd appops set` fails writing error ONLY to `stdout` (empty `stderr`) → `set_app_permission_action` returns `ok: false` (never silent `ok: true`, `D-02`).<br>`T2-F1-04`: `SYSTEM_FIXED` permission reported honestly (`system_fixed: true`) when `dumpsys package` reports `SYSTEM_FIXED` and `pm revoke` fails.<br>`T2-F1-05`: Command injection & invalid inputs rejected (`package_name: "com.bad;id"`, `"../etc"`, unknown `category: "root"`, unknown `action: "hack"`, protected pkg `dev.silo.app`). |
| **F2** | `T2-F2-01`: Mutually exclusive sanitization: sending same category `"camera"` in BOTH `auto_revoke_on_bg` and `locked_deny` strips `"camera"` from `auto_revoke_on_bg` (`locked_deny` wins, `PROJECT.md #2`).<br>`T2-F2-02`: Invalid/duplicate categories in `AppPermGuard` are stripped & deduplicated; empty guard removes package entry from `config.scopes.guards`.<br>`T2-F2-03`: Rejects protected package (`com.android.systemui`, `dev.silo.app`) and invalid package name (`"bad pkg"`) in `set_app_perm_guard`.<br>`T2-F2-04`: Setting `auto_revoke_on_bg` on a package that already has `locked_deny` does NOT unnecessarily re-revoke unchanged `locked_deny` categories (`D-05`).<br>`T2-F2-05`: `Kunci Blokir` does NOT loop-revoke when `Uid mode: CAMERA: ignore` + `CAMERA: allow; time=+1m ago` and `runtime_granted == false` (`D-01`/`D-05`). |
| **F3** | `T2-F3-01`: Exact op-name token boundary (`D-11`): `CAMERA_SANDBOXED (allow):` in `dumpsys appops --op CAMERA` MUST NOT match `"CAMERA"`.<br>`T2-F3-02`: Stale access older than 7 days (`-8d2h`) is excluded unless `(running)` is present.<br>`T2-F3-03`: `get_privacy_access_log` clamps `limit` (`0` → `>= 10`, `9999` → `<= 200`) and `get_telemetry` clamps `limit` (`0` → `1`, `99999` → `<= 2048`).<br>`T2-F3-04`: `kill_connection` refuses/skips protected UID (`0`, `1000`) and `LISTEN` sockets (`D-10`/`CB-12`).<br>`T2-F3-05`: `get_privacy_access_log` gracefully handles failing/empty `dumpsys appops` without crashing or blocking concurrent `get_status`. |
| **F4** | `T2-F4-01`: 4 MiB framing boundary: valid JSON-RPC payload `> 512 KiB` (e.g. `1.2 MiB` `replace_ip_blocklist`) succeeds over socket & `--bridge`; oversized payload `> 4 MiB` is rejected without OOM/crash.<br>`T2-F4-02`: Malformed JSON returns `{"id": 0, "ok": false, "error": "..."}`; unknown `cmd` echoes caller's `id` with `ok: false`; invalid UTF-8 closes connection without killing daemon.<br>`T2-F4-03`: `MAX_CONCURRENT_CLIENTS` (16): opening > 16 simultaneous sockets never panics or wedges daemon; closing clients restores capacity.<br>`T2-F4-04`: `silod --once` with missing arg exits `2`; `silod --once` / `--status` against nonexistent socket exits `1`; `silod --bridge` when daemon down returns valid JSON error with caller's `id` and exits `0` on EOF.<br>`T2-F4-05`: `BTreeMap<u32, _>` accepts BOTH integer keys (`10277`) and string keys (`"10277"`), and rejects non-numeric key (`"abc"`). |
| **F5** | `T2-F5-01`: `replace_ip_blocklist` rejects loopback (`127.0.0.1`, `::1`), unspecified (`0.0.0.0/0`, `::`), invalid prefix (`/33`, `/129`, `/0`), and > `50_000` entries; mixed valid+invalid batch returns exact `loaded` and `rejected > 0`.<br>`T2-F5-02`: IPv6 `ip6tables-restore` failure (e.g. rejecting `HL` or `NFLOG`) MUST NOT disable IPv4 `nflog_available` / `xt_recent_available` (`D-14`/`CB-16`), and non-ipset error does not drop `use_ipset`.<br>`T2-F5-03`: `is_vpn_app: true` does NOT bypass its own per-app `allow_wifi: false` / `allow_screen_off: false` / `ip_blocklist` rules (`D-16`).<br>`T2-F5-04`: Multi-user UIDs (`1010050` Work Profile u10, `9510050` Dual Messenger u95, `15010050` Secure Folder u150, `89999`) are covered by `DefaultDeny`/LAN/DNS/HTTP rules while `1001000` / `9501000` / `15001000` / `99000` remain exempted (`D-08`/`CB-08`).<br>`T2-F5-05`: `set_temp_unblock` clamps `duration_minutes` (`0 → 1m`, `9999 → 240m`); `ttl_value: 0` sanitizes to `64`. |
| **F6** | `T2-F6-01`: `freeze_uid_now` with `DeepStop` and empty/invalid `package_name` returns clean error (`ok: false`).<br>`T2-F6-02`: `set_freezer_enabled` clamps `background_grace_secs` (`1 → 5`, `9999 → 600`).<br>`T2-F6-03`: `freeze_uid_now` in `SoftCgroup` with 0 running PIDs degrades honestly (calls `am set-standby-bucket ... restricted`, does not falsely claim frozen UIDs).<br>`T2-F6-04`: `remove_freezer_entry` on a suspended UID immediately thaws/unsuspends it and removes from `entries`.<br>`T2-F6-05`: **`cargo test` fallback** (kernel cgroup path): `discover_uid_cgroup_freeze_paths` / cgroup path validator MUST reject shared cgroup paths lacking exact `uid_<uid>` segment (`D-12`). |
| **F7** | `T2-F7-01`: `set_private_dns` rejects unsupported mode (`"invalid"`), blank hostname (`""`), injection hostname (`"dns.quad9.net; rm -rf /"`), single-label `"localhost"`, leading dash `"-bad.host"`.<br>`T2-F7-02`: `set_sensor_privacy` rejects unknown sensor `"gps"`, AND `"all"` returns error when only 1 of 2 sensors succeeds (`D-22`/`CB-20`).<br>`T2-F7-03`: `dumpsys sensor_privacy` with trailing `listeners: { enabled=true }` after closing `}` of `sensor=2` (and `user_id=10` muted) MUST NOT falsely report `camera_muted == true` (`CB-20`).<br>`T2-F7-04`: `trigger_panic_reboot` rejects empty or wrong `confirm_token` without invoking `svc power reboot`; `set_automation_config` clamps `auto_reboot_minutes < 15 → 15` and `wifi/bt_auto_off_minutes < 2 → 2`; `set_auto_clear_clipboard` clamps `clear_secs < 10 → 10`.<br>`T2-F7-05`: `is_ce_unlocked` returns `false` (fail-safe!) when `getprop sys.user.0.ce_available` is empty/fails (`D-20`). |
| **F8** | `T2-F8-01`: `set_hardening_config` rejects unsafe `tcp_congestion` (`"bbr;id"`, `""` over 24 chars, `"../x"`) and unsafe `default_qdisc` (`"fq\ncake"`).<br>`T2-F8-02`: Stale `sysctl_orig.json` with mismatched `boot_id` is NOT loaded as current boot's original values (`D-23`).<br>`T2-F8-03`: Clearing `tcp_congestion`/`default_qdisc` back to `""` restores original value from snapshot instead of silently omitting it (`D-23`/`CB-26`, verified via live status/config + `cargo test` fallback).<br>`T2-F8-04`: `rollback_hardening` when hardening was never enabled returns `ok: true` safely with `enabled: false`.<br>`T2-F8-05`: `apply_preset` with invalid preset `"ultra"` returns `ok: false` preserving existing config unchanged. |
| **F9** | `T2-F9-01`: `import_config_json` rejects invalid JSON, unsafe CIDRs (`127.0.0.1`), and strips protected UIDs/packages on import.<br>`T2-F9-02`: `run_sentinel_audit` does NOT count outbound UDP (`remote_port == 0`, ephemeral local port) as `app_listening_ports` (`CB-22`).<br>`T2-F9-03`: `run_live_proof` does NOT fail `Hardware Sensors & Radio/ADB` solely because `adb_enabled_live == true` while `device_locked == false` (`D-27`), and counts `deny`/`foreground` modes in AppOps proof.<br>`T2-F9-04`: Missing `config.json` on startup initializes cleanly with `schema_version == 3`; truncated/binary `config.json` creates `.corrupt.*` backup without crashing.<br>`T2-F9-05`: `export_config_json` never leaks transient runtime state or invalid fields; `config.json` on disk always has `0600` permissions. |
| **F11** | `T2-F11-01`: Protected UID boundary precision: `9999`, `90000`, `99999`, `109999`, `190000` rejected; `10000`, `89999`, `110000`, `189999` accepted.<br>`T2-F11-02`: Spoofed/empty `package_name` bypass defense (`D-10`/`CB-12`): **`cargo test` fallback** verifying `uid_to_pkgs` lookup from `packages.list` blocks `SetAppFirewallRule` / `SetFreezerEntry` / `FreezeUidNow` / `KillConnection` for a UID owning `dev.silo.app` or `com.rifsxd.ksunext` even when caller sends `package_name: ""` or `"com.fake.app"`.<br>`T2-F11-03`: Duplicate `silod` race: spawning 3 `silod` processes simultaneously against the same `--socket` results in exactly 1 winner serving RPCs and 2 clean non-zero exits.<br>`T2-F11-04`: `set_app_scope` / `apply_hardened_scope` / `clear_app_scope` reject protected packages and shell metacharacters.<br>`T2-F11-05`: `kill_uid_sockets` refuses UID `0`, `1000`, `1051`, `2000`, `99000`, `100000`. |
| **F12** | `T2-F12-01`: `silod --uninstall-cleanup` with nonexistent `--config` path exits `0` safely (still runs firewall flush).<br>`T2-F12-02`: `silod --emergency-flush` with corrupt `--config` exits `0` safely without crashing.<br>`T2-F12-03`: `silod --uninstall-cleanup` skips protected packages even if manually injected into `config.json`.<br>*(Plus module script negative cases — stale non-`silod` PID, missing binary, invalid ELF — owned by module/CI explorer blueprint!)* |

### 5.3 Narrow `cargo test` Fallback Boundaries (Explicitly Scoped)
ONLY the following kernel/Android-hardcoded behaviors cannot be driven purely through `--socket`/`--config` temp paths on Linux and should be covered by invoking `cargo test --manifest-path daemon/Cargo.toml <filter>` from the E2E suite (never duplicating what live `silod` already verifies!):
1. `/data/system/packages.list` multi-package shared-UID `uid_to_pkgs` index (`D-10`/`CB-12` spoofed-package protection & `D-18` shared-UID `Auto-Cabut`).
2. `/sys/fs/cgroup/uid_<uid>` shared-cgroup safety path check (`D-12` `discover_uid_cgroup_freeze_paths`).
3. Raw Netlink packet/protocol logic (`D-09`/`CB-24` `NETLINK_ROUTE` `xfrm+`/`NLMSG_ERROR`, `D-15` ipset maxelem/batching, `D-25` IPv6 extension-header walk in `parse_ip_packet_headers`).
4. Fast simulated-time ticks (`D-07` `AutomationEngine` AOD/screen-wake `device_locked` & 15m BFU countdown, `D-17` `probe_clipboard_metadata_hash` with `ClipDescription { text/plain label=null }`).

### 5.4 Tier 3 (Pairwise Cross-Feature Combinations — Daemon-Exercised Pairs)
Recommended daemon pairwise tests contributing to the `>= 12` Tier 3 pool:
1. `F1 × F2`: Revoke + `Kunci Blokir` → manual `allow` override auto-unlocks `locked_deny` & clears `user-fixed` → `inspect_app_permissions` confirms consistent state.
2. `F1 × F3`: `set_app_permission_action(..., "camera", "revoke")` → `get_privacy_access_log` reflects `"ignore"` strict mode on `(allow/ignore)` entry.
3. `F2 × F4`: Concurrent `get_status` (`< 50ms`, 0 subprocesses) while `set_app_perm_guard` applies `locked_deny` across multiple categories with slow `pm`/`cmd` shims.
4. `F4 × F5`: `> 1 MiB` `replace_ip_blocklist` payload transmitted over `silod --bridge` → verifies 4 MiB framing + `iptables-restore` script generation + `rejected` count accuracy.
5. `F5 × F7`: `trigger_panic_lockdown` simultaneously activates firewall `panic_lockdown_active`, mutes mic & camera (`cmd sensor_privacy`), disables Wi-Fi & BT (`svc`), and clears clipboard (`cmd clipboard`), returning complete `PanicLockdownReport`.
6. `F5 × F8`: `trigger_panic_lockdown` followed by `apply_preset("balanced")` clears `panic_lockdown_active`, restores `DefaultAllow` with Balanced guards, and enables hardening with `sysctl_orig.json`.
7. `F5 × F11`: `trigger_panic_lockdown` with prior `DefaultAllow` rules + multi-user UIDs still exempts protected system ranges (`0-9999`, `90000-109999`) while blocking all user apps.
8. `F6 × F11`: `set_freezer_entry` + `freeze_uid_now` rejects protected UIDs/packages while successfully suspending & thawing ordinary user apps (including multi-user `--user 10`).
9. `F6 × F12`: `DeepStop` suspended app + `silod` crash/restart → `--uninstall-cleanup` unsuspends it and cleans up firewall/AppOps.
10. `F7 × F8`: `apply_preset("paranoid")` → `apply_preset("safe")` resets every F7 automation toggle AND rolls back F8 hardening cleanly.
11. `F8 × F9`: Enable hardening + create `sysctl_orig.json` → `export_config_json` → corrupt `config.json` → restart (`config.json.corrupt.*` created) → `import_config_json` → `run_live_proof` passes.
12. `F4 × F11`: `flock` singleton active + `silod --bridge` attached → kill & restart `silod` → `--bridge` transparently reconnects and serves next RPC on new socket inode.

### 5.5 Tier 4 (Real-World Workloads — Alignment with `TEST_INFRA.md § 4`)
All 7 planned Tier 4 scenarios (`T4-S01` through `T4-S07`) in `TEST_INFRA.md § 4` map cleanly onto our verified `silod_harness.py` architecture (`$PATH` shims + `unshare -Urm` `xtables-nft-multi` shim + host socket / `--bridge` / CLI):
- `T4-S01` (One UI 7 Samsung Camera full lifecycle: F1/F2/F3/F4) → 100% live `silod`.
- `T4-S02` (Panic Lockdown with prior `DefaultAllow` rules & multi-user u10/u95/u150 + `xfrm+`: F5/F4/F7/F11) → 100% live `silod` inside `unshare -Urm`.
- `T4-S03` (Large >512 KiB / ~25,000+ CIDR blocklist over `--bridge` + export/import + corrupt recovery: F4/F5/F9) → 100% live `silod`.
- `T4-S04` (Crash/restart + `silod.lock` + `--bridge` transparent reconnect + `module/*.sh` PID check + `--uninstall-cleanup`: F4/F6/F8/F11/F12) → 100% live `silod` + `module_harness.py`.
- `T4-S05` (Preset switching Paranoid→Balanced→Safe→Hardened + sensors/ADB/clipboard + rollback + Live Proof/Sentinel: F7/F8/F9) → 100% live `silod`.
- `T4-S07` (Mixed `SoftCgroup` + `DeepStop` freezer + protected UID/package defense + restart durability: F6/F11/F5) → 100% live `silod`.

---

## 6. Key Design Recommendations for `tests/e2e/harness/silod_harness.py`

1. **`SiloDaemonSandbox` context manager**:
   - Creates `mktemp -d` (`chmod 0700`) with `bin/` (PATH shims), `state.json`, `cmd_log.jsonl`, `silod.sock`, `config.json`, `.boot_fail_count`.
   - Default launch wraps `silod` in `unshare -Urm sh -c "mount --bind '<TMP>/bin/xtables_shim' /usr/sbin/xtables-nft-multi && exec '<SILOD_BIN>' --socket ... --config ... --boot-marker ..."` with `PATH=<TMP>/bin:$PATH` (and optional `use_unshare=False` mode for raw host tests).
   - Waits until `silod.sock` exists and responds to `{"id": 1, "cmd": "ping"}` (up to 5s); on `__exit__`, sends `SIGTERM`, waits cleanly (escalates to `SIGKILL`), and removes `<TMP>`.
2. **Stateful Android 15 / One UI 7 Shim Engine** (`tests/e2e/harness/android_shims.py`):
   - Single Python dispatcher invoked by thin symlinks/wrappers for `cmd`, `pm`, `am`, `dumpsys`, `settings`, `svc`, `getprop`, and `xtables_shim`.
   - Uses `fcntl.flock` on `<TMP>/state.lock` so concurrent `silod` worker threads never corrupt `state.json` or `cmd_log.jsonl`.
   - Exposes helper methods on `SiloDaemonSandbox`: `set_appops(...)`, `set_runtime_perm(...)`, `set_dumpsys_appops(...)`, `set_sensor_privacy(...)`, `inject_fault(tool, matcher, exit_code, stdout, stderr)`, `read_cmd_log()`, `last_iptables_script(v6=False)`, `restart()`.
3. **Clients**:
   - `UnixRpcClient`: raw AF_UNIX stream client supporting custom `id`, oversized/malformed frames, timing (`elapsed_ms`), and concurrent client threads.
   - `BridgeClient`: persistent subprocess wrapper around `silod --bridge --socket <TMP>/silod.sock` testing real bridge framing, timeouts, and transparent reconnect after daemon restart.
   - `run_silod_cli(args, env=...)`: invokes `--version`, `--once`, `--status`, `--emergency-flush`, `--uninstall-cleanup` capturing `(returncode, stdout, stderr)`.
