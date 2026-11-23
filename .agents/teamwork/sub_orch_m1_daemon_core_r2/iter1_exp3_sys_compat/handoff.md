# M1 Explorer 3 Handoff — `daemon/src/sys.rs`, `daemon/Cargo.toml` & M2 Compile-Compatibility Matrix

- **Explorer**: `iter1_exp3_sys_compat` (READ-ONLY)
- **Milestone**: M1 (`daemon-core`)
- **Assigned Scope**: `daemon/src/sys.rs`, `daemon/Cargo.toml`, and Exhaustive M2 Compile-Compatibility Interface Matrix across all 7 M2 files (`firewall.rs`, `netlink.rs`, `freezer.rs`, `automation.rs`, `hardening.rs`, `sentinel.rs`, `telemetry.rs`).
- **Remediated Findings**: `D-07`, `D-10`, `D-18`, `D-20`, `D-25`, `CB-03`, `CB-12`, `CB-17`, `CB-18`, `CB-30`, plus RAII `InotifyWatcher` for `PermissionEnforcer` (`D-05`) and M1/M2 zero-breakage contract.

---

## 1. Executive Summary & Critical Discoveries

1. **Existing `foreground_app_uids()` Return Type Is `Vec<u32>` (NOT `HashSet<u32>`)**:
   - `daemon/src/sys.rs:271` defines `pub fn foreground_app_uids() -> Vec<u32>`, called directly by M2 `daemon/src/freezer.rs:186, 206`.
   - **Action**: Preserve `pub fn foreground_app_uids() -> Vec<u32>` (still `oom_score_adj <= 100`) verbatim for `freezer.rs`, and add `pub fn top_activity_uids() -> HashSet<u32>` + `pub fn is_top_activity_oom_adj(adj: i32) -> bool` (`adj == 0`) for `PermissionEnforcer` (`Auto-Cabut`, `D-18`).
2. **Clippy `-D warnings` Trap on `parse_packages_list_str`**:
   - `silod` is a binary crate (`[[bin]]`, no `lib.rs`). `parse_packages_list_str` (`sys.rs:167`) is currently only called by `read_uid_package_map` (`sys.rs:163`) and unit tests. If `read_uid_package_map` stops calling `parse_packages_list_str`, `cargo clippy --all-targets -- -D warnings` will FAIL with `dead_code`.
   - **Action**: Keep `read_uid_package_map()` delegating to `parse_packages_list_str` (preserving `sentinel.rs:139` and avoiding `dead_code`), while introducing `UidPackageIndex`, `parse_packages_list_multi`, and `read_uid_package_index`.
3. **14 Protocol Structs Are FROZEN by M2 Struct Literals Without `..Default::default()`**:
   - M1 **CAN** safely add fields to `CategoryPermLiveState`, `AppLivePermissions`, `PrivacyAccessEvent`, `DaemonStatus`, `PanicLockdownReport`, `FirewallConfig` (M2 tests use `..Default::default()`), `AutomationConfig` (M2 test uses `..Default::default()`), `ScopesConfig`, `AppPermGuard`, `SiloConfig`.
   - M1 **MUST NOT** add/rename/remove fields on `KernelCapabilities`, `ActiveConnection`, `BlockedPacketEvent`, `TelemetrySnapshot`, `FreezerStatus`, `PrivateDnsState`, `RadioPrivacyState`, `SensorPrivacyState`, `HardeningStatus`, `SysctlItemStatus`, `SentinelReport`, `AuditFinding`, `LiveProofReport`, `LiveProofItem`, or add enum variants to `FirewallMode`, `BlockAction`, `FreezeMode`.
4. **M2 Lock-Decoupling Blueprint for `main.rs` Without Editing Any M2 File**:
   - `FreezerEngine`, `AutomationEngine`, and `HardeningEngine` are `!Clone` and take `&mut self` across blocking operations (or `&self` inside `sentinel::run_live_proof`).
   - Extracting them out of `DaemonState` into dedicated `Arc<Mutex<FreezerEngine>>`, `Arc<Mutex<AutomationEngine>>`, `Arc<Mutex<HardeningEngine>>`, plus `Arc<RwLock<UidPackageIndex>>` (exposing `primary_map(&self) -> &HashMap<u32, String>` for zero-copy calls to `telemetry::TelemetryRing::push` and `netlink::list_active_connections`), lets `main.rs` keep `state_mtx` strictly a <1ms leaf lock (ZERO subprocesses held under `state_mtx`) while compiling cleanly against all 7 unmodified M2 files!
   - Furthermore, `netlink::dump_inet_sockets` and `netlink::RawInetSocket` are `pub`, allowing `main.rs` to guard `KillConnection` against protected UIDs, protected packages, and `LISTEN`/`TIME_WAIT` sockets right inside M1 (`D-10`/`CB-12`) without touching `netlink.rs`.

---

## 2. Exact Line-Level Blueprint — `daemon/src/sys.rs`

Current file: `daemon/src/sys.rs` (350 lines). All changes below preserve 100% backward compatibility with all M2 imports and existing tests, require zero new crates, and compile cleanly on both `x86_64-unknown-linux-gnu` and `aarch64-unknown-linux-musl` under `cargo clippy --all-targets -- -D warnings`.

### 2.1 Imports (`daemon/src/sys.rs:1-8`)

Replace lines 1–7 with:
```rust
use std::collections::{HashMap, HashSet};
use std::ffi::CString;
use std::fs;
use std::io::{Read, Write};
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::os::unix::ffi::OsStrExt;
use std::os::unix::process::CommandExt;
use std::path::Path;
use std::process::{Child, Command, Stdio};
use std::sync::mpsc;
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
```

### 2.2 Process-Group & Deadlock-Proof `run_cmd()` (`daemon/src/sys.rs:23-112`, fixes `CB-17`, `D-25`)

**Root Causes Fixed**:
- Child was not isolated in its own process group (`setpgid(0, 0)`), so killing child on timeout left grandchildren holding stdout/stderr pipes open and `out_handle.join()` / `err_handle.join()` deadlocked forever (`CB-17`).
- `child.try_wait()` `Err(_)` branch called `child.kill()` without `child.wait()`, leaking zombie processes (`D-25`).
- `read_bounded` stopped reading at `max_bytes` and dropped the pipe early, risking `SIGPIPE` (`status_code = -1`) on large `dumpsys` output (>512 KiB).

**Exact Replacement for lines 23–112**:
```rust
/// Sends `SIGKILL` to the child's dedicated process group (`-pgid`, strictly guarded against
/// `pid <= 1` or `i32` overflow so `kill(0, ..)` / `kill(-1, ..)` can NEVER be called),
/// falls back to `child.kill()`, and guarantees `child.wait()` so no zombie is ever leaked.
fn kill_process_group_and_reap(child: &mut Child) {
    let pid = child.id();
    if pid > 1 && pid <= i32::MAX as u32 {
        let pgid = pid as libc::pid_t;
        // SAFETY: `pgid > 1`, so `-pgid < -1` targets ONLY the child's process group created via
        // `setpgid(0, 0)` in `pre_exec` (or returns `ESRCH` harmlessly if `setpgid` was denied).
        unsafe {
            let _ = libc::kill(-pgid, libc::SIGKILL);
        }
    }
    let _ = child.kill();
    let _ = child.wait();
}

/// Executes an external binary DIRECTLY without a shell (`/system/bin/sh` is NEVER invoked),
/// with process-group isolation (`setpgid(0, 0)`), optional stdin payload, bounded non-deadlocking
/// pipe readers, and a strict wall-clock timeout so a hung Android service can never deadlock `silod`.
pub fn run_cmd(
    program: &str,
    args: &[&str],
    stdin_data: Option<&str>,
    timeout_ms: u64,
) -> Result<CmdOutput, String> {
    let mut cmd = Command::new(program);
    cmd.args(args)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    if stdin_data.is_some() {
        cmd.stdin(Stdio::piped());
    } else {
        cmd.stdin(Stdio::null());
    }

    // SAFETY: `libc::setpgid(0, 0)` is strictly async-signal-safe (POSIX.1-2008), performs zero
    // allocations or locks, and places the spawned child in its own process group (`pgid == pid`)
    // before `exec` so timeout cleanup kills the entire process subtree.
    unsafe {
        cmd.pre_exec(|| {
            let _ = libc::setpgid(0, 0);
            Ok(())
        });
    }

    let mut child = cmd
        .spawn()
        .map_err(|e| format!("failed to spawn {}: {}", program, e))?;

    if let Some(payload) = stdin_data {
        if let Some(mut stdin) = child.stdin.take() {
            let data = payload.as_bytes().to_vec();
            thread::spawn(move || {
                let _ = stdin.write_all(&data);
            });
        }
    }

    // Drain stdout & stderr concurrently on bounded reader threads sending over channels so
    // an escaped grandchild holding pipe FDs open can never block `run_cmd` indefinitely.
    let stdout_pipe = child.stdout.take();
    let stderr_pipe = child.stderr.take();
    let (out_tx, out_rx) = mpsc::channel();
    let (err_tx, err_rx) = mpsc::channel();

    thread::spawn(move || {
        let _ = out_tx.send(read_bounded(stdout_pipe, 1024 * 1024));
    });
    thread::spawn(move || {
        let _ = err_tx.send(read_bounded(stderr_pipe, 128 * 1024));
    });

    let deadline = Instant::now() + Duration::from_millis(timeout_ms.max(100));
    let mut timed_out = false;
    let status_code = loop {
        match child.try_wait() {
            Ok(Some(status)) => break status.code().unwrap_or(-1),
            Ok(None) => {
                if Instant::now() >= deadline {
                    timed_out = true;
                    kill_process_group_and_reap(&mut child);
                    break -1;
                }
                thread::sleep(Duration::from_millis(15));
            }
            Err(_) => {
                kill_process_group_and_reap(&mut child);
                break -1;
            }
        }
    };

    let drain_wait = if timed_out {
        Duration::from_millis(250)
    } else {
        Duration::from_millis(500)
    };
    let stdout = out_rx.recv_timeout(drain_wait).unwrap_or_default();
    let stderr = err_rx.recv_timeout(drain_wait).unwrap_or_default();

    Ok(CmdOutput {
        status_code,
        stdout,
        stderr,
        timed_out,
    })
}

fn read_bounded<R: Read>(pipe: Option<R>, max_bytes: usize) -> String {
    let Some(mut reader) = pipe else {
        return String::new();
    };
    let mut buf = Vec::with_capacity(4096.min(max_bytes));
    let mut chunk = [0u8; 4096];
    loop {
        match reader.read(&mut chunk) {
            Ok(0) => break,
            Ok(n) => {
                if buf.len() < max_bytes {
                    let remain = max_bytes - buf.len();
                    buf.extend_from_slice(&chunk[..n.min(remain)]);
                }
            }
            Err(ref e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(_) => break,
        }
    }
    String::from_utf8_lossy(&buf).into_owned()
}
```

### 2.3 Multi-Package `UidPackageIndex` (`daemon/src/sys.rs:157-176`, fixes `D-10`, `D-18`, `CB-03`, `CB-12`)

**Keep Existing Functions Unchanged** (`read_uid_package_map` + `parse_packages_list_str` — required by `sentinel.rs:139` and existing test; keeps `parse_packages_list_str` live for clippy).

**Add Directly After Line 176**:
```rust
/// Multi-package bidirectional UID index parsed from `/data/system/packages.list`.
/// Correctly tracks shared-UID packages (common on Samsung One UI), supports multi-user
/// UID resolution (`uid % 100_000` appId fallback for Work Profile u10+, Dual Messenger u95,
/// Secure Folder u150), and maintains a zero-copy `primary_uid_map` for M2 APIs taking
/// `&HashMap<u32, String>`.
#[derive(Debug, Clone, Default)]
pub struct UidPackageIndex {
    pub uid_to_pkgs: HashMap<u32, Vec<String>>,
    pub pkg_to_uid: HashMap<String, u32>,
    pub primary_uid_map: HashMap<u32, String>,
}

impl UidPackageIndex {
    pub fn is_empty(&self) -> bool {
        self.uid_to_pkgs.is_empty()
    }

    pub fn pkgs_for_uid(&self, uid: u32) -> &[String] {
        if let Some(pkgs) = self.uid_to_pkgs.get(&uid) {
            return pkgs.as_slice();
        }
        if uid >= 100_000 {
            let app_id = uid % 100_000;
            if let Some(pkgs) = self.uid_to_pkgs.get(&app_id) {
                return pkgs.as_slice();
            }
        }
        &[]
    }

    pub fn first_pkg(&self, uid: u32) -> Option<&str> {
        self.pkgs_for_uid(uid).first().map(String::as_str)
    }

    pub fn uid_for_pkg(&self, pkg: &str) -> Option<u32> {
        self.pkg_to_uid.get(pkg).copied()
    }

    pub fn user_id_for_pkg(&self, pkg: &str) -> u32 {
        self.pkg_to_uid
            .get(pkg)
            .map(|&uid| uid / 100_000)
            .unwrap_or(0)
    }

    pub fn any_pkg_for_uid<F>(&self, uid: u32, mut f: F) -> bool
    where
        F: FnMut(&str) -> bool,
    {
        self.pkgs_for_uid(uid).iter().any(|p| f(p.as_str()))
    }

    /// Returns a zero-copy `&HashMap<u32, String>` for unmodified M2 APIs
    /// (`netlink::list_active_connections`, `telemetry::TelemetryRing::push`).
    pub fn primary_map(&self) -> &HashMap<u32, String> {
        &self.primary_uid_map
    }
}

pub fn parse_packages_list_multi(raw: &str) -> UidPackageIndex {
    let mut index = UidPackageIndex::default();
    for line in raw.lines() {
        let mut parts = line.split_whitespace();
        let Some(pkg) = parts.next() else { continue };
        let Some(uid_str) = parts.next() else { continue };
        let Ok(uid) = uid_str.parse::<u32>() else {
            continue;
        };
        let list = index.uid_to_pkgs.entry(uid).or_default();
        if !list.iter().any(|existing| existing == pkg) {
            list.push(pkg.to_string());
        }
        index.pkg_to_uid.entry(pkg.to_string()).or_insert(uid);
        index
            .primary_uid_map
            .entry(uid)
            .or_insert_with(|| pkg.to_string());
    }
    index
}

pub fn read_uid_package_index() -> UidPackageIndex {
    let Ok(content) = fs::read_to_string("/data/system/packages.list") else {
        return UidPackageIndex::default();
    };
    parse_packages_list_multi(&content)
}
```
> **Clippy note for Worker**: Ensure every method on `UidPackageIndex` is called from `main.rs`/`scopes.rs`:
> - `read_uid_package_index`, `is_empty`, `first_pkg`, `any_pkg_for_uid`, `primary_map` -> called in `main.rs`.
> - `uid_for_pkg`, `user_id_for_pkg`, `pkgs_for_uid` (via `first_pkg`/`any_pkg_for_uid`) -> called in `scopes.rs` (`PermissionEnforcer` + multi-user `--user` lookup).
> If any helper method is unused in the bin target after combining Explorer 1 + Explorer 2 blueprints, either wire it or remove unused convenience wrappers so `cargo clippy --all-targets -- -D warnings` passes with zero warnings!

### 2.4 Fail-Safe `is_ce_unlocked()` (`daemon/src/sys.rs:190-200`, fixes `D-20`)

**Root Cause Fixed**: Removed `Path::new("/data/user/0/android").exists()`, which can return `true` in BFU under FBE and trigger Auto-Reboot BFU bootloops.

**Exact Replacement for lines 190–200**:
```rust
fn parse_user_state_token(line: &str) -> Option<bool> {
    if line.contains("RUNNING_UNLOCKED") {
        Some(true)
    } else if line.contains("RUNNING_LOCKED")
        || line.contains("RUNNING_UNLOCKING")
        || line.contains("BOOTING")
        || line.contains("STOPPING")
        || line.contains("SHUTDOWN")
    {
        Some(false)
    } else {
        None
    }
}

/// Parses `dumpsys user` output for primary user 0 (`UserInfo{0:...}`).
/// Returns `Some(true)` ONLY when user 0 is explicitly `RUNNING_UNLOCKED`,
/// `Some(false)` for locked/unlocking/booting/stopping states, and `None` if absent.
pub fn parse_user0_unlocked_from_dumpsys(dump: &str) -> Option<bool> {
    let mut in_user_0 = false;
    for raw_line in dump.lines() {
        let t = raw_line.trim();
        if t.is_empty() {
            continue;
        }
        if t.starts_with("UserInfo{") {
            in_user_0 = t.starts_with("UserInfo{0:");
            if in_user_0 {
                if let Some(res) = parse_user_state_token(t) {
                    return Some(res);
                }
            }
            continue;
        }
        if t.starts_with("User 0:") || t.starts_with("0 ->") {
            if let Some(res) = parse_user_state_token(t) {
                return Some(res);
            }
        }
        if in_user_0 && t.contains("State:") {
            if let Some(res) = parse_user_state_token(t) {
                return Some(res);
            }
        }
    }
    None
}

/// Returns true ONLY when primary user (user 0) has authoritatively completed First Unlock
/// (Credential-Encrypted storage active). Fail-safe to `false` when unavailable or uncertain
/// so Auto-Reboot BFU can NEVER loop while already in BFU state.
pub fn is_ce_unlocked() -> bool {
    if let Some(v) = getprop("sys.user.0.ce_available") {
        let low = v.trim().to_ascii_lowercase();
        if low == "true" || low == "1" {
            return true;
        }
        if low == "false" || low == "0" {
            return false;
        }
    }
    let bin = resolve_bin("dumpsys");
    run_cmd(&bin, &["user"], None, 1500)
        .ok()
        .filter(|o| o.success())
        .and_then(|o| parse_user0_unlocked_from_dumpsys(&o.stdout))
        .unwrap_or(false)
}
```

### 2.5 Samsung AOD-Aware `detect_screen_on()` (`daemon/src/sys.rs:202-239`, fixes `D-07`)

**Root Cause Fixed**: On Samsung One UI 7, Always-On Display (AOD) keeps `/sys/class/backlight/panel/brightness > 0` while `mWakefulness=Dozing` and `mState=DOZE` / `DOZE_SUSPEND`, previously making `detect_screen_on()` return `true` all night.

**Exact Replacement for lines 202–239**:
```rust
fn extract_kv_token<'a>(line: &'a str, key: &str) -> Option<&'a str> {
    let idx = line.find(key)?;
    let rest = &line[idx + key.len()..];
    let val = rest
        .trim_start()
        .split(|c: char| c.is_whitespace() || c == ',' || c == ';' || c == ')' || c == '}')
        .next()?;
    if val.is_empty() {
        None
    } else {
        Some(val)
    }
}

/// Pure parser cross-checking `dumpsys power` and `dumpsys display` so Samsung AOD
/// (`mWakefulness=Dozing`, `Display Power: state=DOZE`, `mState=DOZE`, `mState=DOZE_SUSPEND`,
/// `mState=OFF`) strictly returns `Some(false)`.
pub fn parse_screen_on_from_dumpsys(power_dump: &str, display_dump: &str) -> Option<bool> {
    let mut power_awake: Option<bool> = None;
    let mut power_display_on: Option<bool> = None;

    for raw_line in power_dump.lines() {
        let t = raw_line.trim();
        if power_awake.is_none() {
            if let Some(val) = extract_kv_token(t, "mWakefulness=") {
                match val {
                    "Awake" => power_awake = Some(true),
                    "Dozing" | "Asleep" | "Dreaming" => power_awake = Some(false),
                    _ => {}
                }
            }
        }
        if power_display_on.is_none() && t.starts_with("Display Power:") {
            if let Some(val) = extract_kv_token(t, "state=") {
                match val {
                    "ON" => power_display_on = Some(true),
                    "OFF" | "DOZE" | "DOZE_SUSPEND" | "ON_SUSPEND" | "DOZE_INVERTED" => {
                        power_display_on = Some(false);
                    }
                    _ => {}
                }
            }
        }
    }

    let mut display_on: Option<bool> = None;
    for raw_line in display_dump.lines() {
        if display_on.is_some() {
            break;
        }
        let t = raw_line.trim();
        let val = extract_kv_token(t, "mState=")
            .or_else(|| extract_kv_token(t, "mScreenState="))
            .or_else(|| extract_kv_token(t, "Display State="));
        if let Some(state) = val {
            match state {
                "ON" => display_on = Some(true),
                "OFF" | "DOZE" | "DOZE_SUSPEND" | "ON_SUSPEND" | "DOZE_INVERTED" => {
                    display_on = Some(false);
                }
                _ => {}
            }
        }
    }

    if power_awake == Some(false)
        || power_display_on == Some(false)
        || display_on == Some(false)
    {
        return Some(false);
    }
    if power_awake == Some(true) || power_display_on == Some(true) || display_on == Some(true) {
        return Some(true);
    }
    None
}

fn read_backlight_brightness() -> Option<i64> {
    let candidate_paths = [
        "/sys/class/backlight/panel/brightness",
        "/sys/class/leds/lcd-backlight/brightness",
        "/sys/devices/platform/panel/backlight/panel/brightness",
    ];
    for p in candidate_paths {
        if let Ok(val) = fs::read_to_string(p) {
            if let Ok(num) = val.trim().parse::<i64>() {
                return Some(num);
            }
        }
    }
    if let Ok(entries) = fs::read_dir("/sys/class/backlight") {
        for entry in entries.flatten() {
            let bp = entry.path().join("brightness");
            if let Ok(val) = fs::read_to_string(bp) {
                if let Ok(num) = val.trim().parse::<i64>() {
                    return Some(num);
                }
            }
        }
    }
    None
}

/// Detects whether the display is genuinely actively ON.
/// - Zero-fork fast path when sysfs backlight brightness is 0 (screen definitely off).
/// - When backlight > 0 (or sysfs unavailable), cross-checks `dumpsys power` / `dumpsys display`
///   so Samsung Always-On Display (`Dozing`/`DOZE`/`DOZE_SUSPEND`) returns `false`.
pub fn detect_screen_on() -> bool {
    let brightness = read_backlight_brightness();
    if matches!(brightness, Some(n) if n <= 0) {
        return false;
    }

    let bin = resolve_bin("dumpsys");
    let power_out = run_cmd(&bin, &["power"], None, 1500)
        .ok()
        .filter(|o| o.success())
        .map(|o| o.stdout)
        .unwrap_or_default();

    let power_only = parse_screen_on_from_dumpsys(&power_out, "");
    if power_only == Some(false) {
        return false;
    }

    let power_has_display_on = power_out
        .lines()
        .any(|l| l.trim().starts_with("Display Power:") && l.contains("state=ON"));
    if power_only == Some(true) && power_has_display_on {
        return true;
    }

    let display_out = if !power_out.is_empty() || Path::new("/system/bin/dumpsys").exists() {
        run_cmd(&bin, &["display"], None, 1500)
            .ok()
            .filter(|o| o.success())
            .map(|o| o.stdout)
            .unwrap_or_default()
    } else {
        String::new()
    };

    if let Some(on) = parse_screen_on_from_dumpsys(&power_out, &display_out) {
        return on;
    }

    brightness.map(|n| n > 0).unwrap_or(true)
}
```

### 2.6 Top Visible Activity vs Background FGS (`daemon/src/sys.rs:269-313`, fixes `D-18`)

**Keep `pub fn foreground_app_uids() -> Vec<u32>` (lines 271–303) UNCHANGED** so `freezer.rs` (`oom_score_adj <= 100`) keeps exact compatibility.

**Add Directly After `parse_proc_status_uid` (after line 312)**:
```rust
/// Returns `true` ONLY for Android's top visible resumed Activity process (`FOREGROUND_APP_ADJ == 0`),
/// distinguishing it from background Foreground Services (`oom_score_adj` 50..=100) and persistent
/// system services (`< 0`).
pub fn is_top_activity_oom_adj(adj: i32) -> bool {
    adj == 0
}

/// Zero-fork `/proc` scan returning ordinary user-app UIDs (appId `10000..90000` across all users)
/// currently hosting a top visible resumed activity (`oom_score_adj == 0`).
/// Excludes background Foreground Services (`50..=100`) so `Auto-Cabut` revokes spyware FGS apps
/// immediately after the user leaves the app.
pub fn top_activity_uids() -> HashSet<u32> {
    let mut uids = HashSet::new();
    let Ok(entries) = fs::read_dir("/proc") else {
        return uids;
    };
    for entry in entries.flatten() {
        let name = entry.file_name();
        let Some(name_str) = name.to_str() else {
            continue;
        };
        if !name_str.bytes().all(|b| b.is_ascii_digit()) {
            continue;
        }
        let path = entry.path();
        let Ok(adj_raw) = fs::read_to_string(path.join("oom_score_adj")) else {
            continue;
        };
        let Ok(adj) = adj_raw.trim().parse::<i32>() else {
            continue;
        };
        if !is_top_activity_oom_adj(adj) {
            continue;
        }
        if let Ok(status) = fs::read_to_string(path.join("status")) {
            if let Some(uid) = parse_proc_status_uid(&status) {
                let app_id = uid % 100_000;
                if (10_000..90_000).contains(&app_id) {
                    uids.insert(uid);
                }
            }
        }
    }
    uids
}
```
> **Note on `dumpsys activity` fallback**: Keeping `top_activity_uids()` 100% zero-fork `/proc`-based guarantees <1ms cost on the 1s active-guard thread and zero `dumpsys` overhead when the user is on the homescreen or lockscreen (where no third-party app has `oom_score_adj == 0`). If Explorer 1's `scopes.rs` additionally wants `parse_top_resumed_packages_from_dumpsys(dump: &str) -> HashSet<String>`, add it ONLY if called from `scopes.rs` (otherwise clippy `-D warnings` will flag `dead_code` in the binary target).

### 2.7 OOM Score Protection (`CB-18`) & Safe RAII `InotifyWatcher` (`D-05`)

**Add Immediately Before `#[cfg(test)]` (line 314)**:
```rust
/// Best-effort OOM killer protection (e.g. `-900` so Android LMKD does not evict `silod`).
/// Clamps to Linux valid range `[-1000, 1000]`. Returns `false` without panicking when unprivileged.
pub fn set_oom_score_adj(score: i32) -> bool {
    let clamped = score.clamp(-1000, 1000);
    fs::write("/proc/self/oom_score_adj", format!("{}\n", clamped)).is_ok()
}

/// Standard `inotify` mask for watching Android permission/AppOps parent directories
/// (catches `AtomicFile` temp-write + atomic `rename()` replacements).
pub const INOTIFY_PERM_WATCH_MASK: u32 =
    libc::IN_CLOSE_WRITE | libc::IN_MOVED_TO | libc::IN_CREATE | libc::IN_MODIFY;

/// Lightweight, zero-extra-crate RAII Linux `inotify` watcher (`IN_NONBLOCK | IN_CLOEXEC`).
/// Automatically closes its file descriptor on drop via `OwnedFd`.
/// All `libc` 0.2 symbols verified compatible on both `x86_64-unknown-linux-gnu` and
/// `aarch64-unknown-linux-musl`.
pub struct InotifyWatcher {
    fd: OwnedFd,
    watch_descriptors: HashSet<i32>,
}

impl InotifyWatcher {
    pub fn new() -> std::io::Result<Self> {
        let raw = unsafe { libc::inotify_init1(libc::IN_NONBLOCK | libc::IN_CLOEXEC) };
        if raw < 0 {
            return Err(std::io::Error::last_os_error());
        }
        // SAFETY: `raw >= 0` is a freshly created fd exclusively owned by `InotifyWatcher`.
        let fd = unsafe { OwnedFd::from_raw_fd(raw) };
        Ok(Self {
            fd,
            watch_descriptors: HashSet::new(),
        })
    }

    pub fn add_watch(&mut self, path: &Path, mask: u32) -> std::io::Result<i32> {
        let c_path = CString::new(path.as_os_str().as_bytes()).map_err(|_| {
            std::io::Error::new(std::io::ErrorKind::InvalidInput, "path contains NUL byte")
        })?;
        let wd = unsafe { libc::inotify_add_watch(self.fd.as_raw_fd(), c_path.as_ptr(), mask) };
        if wd < 0 {
            return Err(std::io::Error::last_os_error());
        }
        self.watch_descriptors.insert(wd);
        Ok(wd)
    }

    pub fn watch_count(&self) -> usize {
        self.watch_descriptors.len()
    }

    /// Blocks in `poll()` for up to `timeout_ms` waiting for inotify events.
    /// Returns:
    /// - `Ok(Some(names))`: at least one event fired (all queued events drained; `names` contains
    ///   deduplicated filenames, or empty for direct-file/queue-overflow events).
    /// - `Ok(None)`: timed out or interrupted by signal (`EINTR`).
    pub fn wait_events(&self, timeout_ms: u64) -> std::io::Result<Option<Vec<String>>> {
        let mut pfd = libc::pollfd {
            fd: self.fd.as_raw_fd(),
            events: libc::POLLIN,
            revents: 0,
        };
        let nfds: libc::nfds_t = 1;
        let timeout = timeout_ms.min(libc::c_int::MAX as u64) as libc::c_int;
        let rc = unsafe { libc::poll(&mut pfd, nfds, timeout) };
        if rc < 0 {
            let err = std::io::Error::last_os_error();
            if err.kind() == std::io::ErrorKind::Interrupted {
                return Ok(None);
            }
            return Err(err);
        }
        if rc == 0 || (pfd.revents & libc::POLLIN) == 0 {
            return Ok(None);
        }

        let mut names = Vec::new();
        let mut got_any = false;
        let mut buf = [0u8; 4096];
        for _ in 0..64 {
            let n = unsafe {
                libc::read(
                    self.fd.as_raw_fd(),
                    buf.as_mut_ptr().cast::<libc::c_void>(),
                    buf.len(),
                )
            };
            if n > 0 {
                got_any = true;
                parse_inotify_names(&buf[..n as usize], &mut names);
            } else if n == 0 {
                break;
            } else {
                let err = std::io::Error::last_os_error();
                match err.kind() {
                    std::io::ErrorKind::WouldBlock => break,
                    std::io::ErrorKind::Interrupted => continue,
                    _ if got_any => break,
                    _ => return Err(err),
                }
            }
        }

        if got_any {
            Ok(Some(names))
        } else {
            Ok(None)
        }
    }
}

/// Bounds-checked parser for packed Linux `struct inotify_event` records (16-byte header + NUL-padded name).
fn parse_inotify_names(buf: &[u8], out: &mut Vec<String>) {
    const HDR_LEN: usize = 16;
    let mut offset = 0usize;
    while offset + HDR_LEN <= buf.len() {
        let name_len = u32::from_ne_bytes([
            buf[offset + 12],
            buf[offset + 13],
            buf[offset + 14],
            buf[offset + 15],
        ]) as usize;
        let Some(next) = offset
            .checked_add(HDR_LEN)
            .and_then(|v| v.checked_add(name_len))
        else {
            break;
        };
        if next > buf.len() {
            break;
        }
        if name_len > 0 {
            let raw_name = &buf[offset + HDR_LEN..next];
            let end = raw_name
                .iter()
                .position(|&b| b == 0)
                .unwrap_or(raw_name.len());
            let name = String::from_utf8_lossy(&raw_name[..end]).into_owned();
            if !name.is_empty() && !out.contains(&name) {
                out.push(name);
            }
        }
        offset = next;
    }
}
```
> **Recommended Android directories for `PermissionEnforcer` to watch with `InotifyWatcher` (best-effort `add_watch` on existing paths)**:
> - `/data/misc_de/0/apexdata/com.android.permission` (Android 12–16 `runtime-permissions.xml`)
> - `/data/system/users/0` (legacy/OEM `runtime-permissions.xml`)
> - `/data/system` (`appops.xml`, `packages.list`)
> Filter triggered `names` (when non-empty) for `n.contains("runtime-permissions") || n.contains("appops") || n == "packages.list"` so unrelated `/data/system` writes don't cause spurious enforcement runs.

---

## 3. Exact Line-Level Blueprint — `daemon/Cargo.toml` (`CB-30`)

File: `daemon/Cargo.toml` (23 lines).
- **Line 6** — replace:
  ```toml
  description = "Silo v2 kernel-backed privacy and security root daemon for Android Linux 6.x"
  ```
  with:
  ```toml
  description = "Silo v3.0.0 kernel-backed privacy and security root daemon for Android Linux 6.x"
  ```
- **Dependencies**: Confirmed **ZERO new crates needed** (`libc = "0.2.191"`, `serde = "1.0.229"`, `serde_json = "1.0.154"` in `daemon/Cargo.lock`). Do NOT modify `daemon/Cargo.lock` or `daemon/.cargo/config.toml`.

---

## 4. Exhaustive M2 Compile-Compatibility Interface Matrix (CRITICAL)

Every single line (including `#[cfg(test)]`) of all 7 M2 files (`firewall.rs`, `netlink.rs`, `freezer.rs`, `automation.rs`, `hardening.rs`, `sentinel.rs`, `telemetry.rs`) was inspected directly. M1 is strictly forbidden from editing any M2 file; therefore M1 MUST obey every constraint below.

### 4.1 M1 Structs & Enums — Mutability vs Freeze Classification

| M1 Type & File | Referenced / Constructed in M2? | M2 Uses `..Default::default()`? | M1 Edit Rule |
|---|---|---|---|
| `CategoryPermLiveState` (`protocol.rs`) | **None** | N/A | **SAFE TO EXTEND** (add `runtime_granted`, `has_runtime_perms`, `supports_foreground`, `system_fixed` with `#[serde(default)]`). Keep `last_access` backward-compatible! |
| `AppLivePermissions` (`protocol.rs`) | **None** | N/A | **SAFE TO EXTEND** |
| `PrivacyAccessEvent` (`protocol.rs`) | **None** | N/A | **SAFE TO EXTEND** |
| `DaemonStatus` (`protocol.rs`) | **None** | N/A | **SAFE TO EXTEND** (add `#[serde(default)] pub panic_lockdown_active: bool`) |
| `PanicLockdownReport` (`protocol.rs`) | **None** | N/A | **SAFE TO EXTEND** |
| `FirewallConfig` (`config.rs`) | `firewall.rs:836, 860` (tests) | **YES** (`..Default::default()`) | **SAFE TO EXTEND** (add `#[serde(default)] pub panic_lockdown_active: bool` defaulting to `false` in `Default`). Do NOT rename/remove existing 15 fields. |
| `AutomationConfig` (`config.rs`) | `automation.rs:464` (test) | **YES** (`..Default::default()`) | **SAFE TO EXTEND** (with `Default`). Keep existing 8 fields. |
| `SiloConfig` (`config.rs`) | `sentinel.rs:509` (`SiloConfig::default()`) | N/A | **SAFE TO EXTEND** (with `Default`). Keep `firewall`, `freezer`, `scopes`, `hardening`, `automation`. |
| `ScopesConfig` / `AppPermGuard` (`config.rs`) | `sentinel.rs:363, 364, 367, 370` (field reads only) | N/A | **SAFE TO EXTEND** (keep `apps` and `guards` fields!). |
| `KernelCapabilities` (`protocol.rs`) | `firewall.rs:136-149`, `818-831` | **NO!** (all 12 fields explicit) | **FROZEN IN M1 — DO NOT ADD/RENAME/REMOVE FIELDS** |
| `ActiveConnection` (`protocol.rs`) | `netlink.rs:539-550` | **NO!** (all 10 fields explicit) | **FROZEN IN M1 — DO NOT ADD/RENAME/REMOVE FIELDS** |
| `BlockedPacketEvent` (`protocol.rs`) | `netlink.rs:1060-1071, 1084-1095`; `telemetry.rs:71-82` | **NO!** (all 10 fields explicit) | **FROZEN IN M1 — DO NOT ADD/RENAME/REMOVE FIELDS** |
| `TelemetrySnapshot` (`protocol.rs`) | `telemetry.rs:45-49` | **NO!** (all 3 fields explicit) | **FROZEN IN M1 — DO NOT ADD/RENAME/REMOVE FIELDS** |
| `FreezerStatus` (`protocol.rs`) | `freezer.rs:38-45` | **NO!** (all 6 fields explicit) | **FROZEN IN M1 — DO NOT ADD/RENAME/REMOVE FIELDS** |
| `PrivateDnsState` (`protocol.rs`) | `automation.rs:170` | **NO!** (all 2 fields explicit) | **FROZEN IN M1 — DO NOT ADD/RENAME/REMOVE FIELDS** |
| `RadioPrivacyState` (`protocol.rs`) | `automation.rs:229-234` | **NO!** (all 4 fields explicit) | **FROZEN IN M1 — DO NOT ADD/RENAME/REMOVE FIELDS** |
| `SensorPrivacyState` (`protocol.rs`) | `automation.rs:402-405` | **NO!** (all 2 fields explicit) | **FROZEN IN M1 — DO NOT ADD/RENAME/REMOVE FIELDS** |
| `HardeningStatus` (`protocol.rs`) | `hardening.rs:331-335` | **NO!** (all 3 fields explicit) | **FROZEN IN M1 — DO NOT ADD/RENAME/REMOVE FIELDS** |
| `SysctlItemStatus` (`protocol.rs`) | `hardening.rs:319-328` | **NO!** (all 8 fields explicit) | **FROZEN IN M1 — DO NOT ADD/RENAME/REMOVE FIELDS** |
| `SentinelReport` (`protocol.rs`) | `sentinel.rs:270-278` | **NO!** (all 7 fields explicit) | **FROZEN IN M1 — DO NOT ADD/RENAME/REMOVE FIELDS** |
| `AuditFinding` (`protocol.rs`) | `sentinel.rs` (18 literal sites) | **NO!** (all 6 fields explicit) | **FROZEN IN M1 — DO NOT ADD/RENAME/REMOVE FIELDS** |
| `LiveProofReport` (`protocol.rs`) | `sentinel.rs:419-430` | **NO!** (all 10 fields explicit) | **FROZEN IN M1 — DO NOT ADD/RENAME/REMOVE FIELDS** |
| `LiveProofItem` (`protocol.rs`) | `sentinel.rs` (8 literal sites) | **NO!** (all 3 fields explicit) | **FROZEN IN M1 — DO NOT ADD/RENAME/REMOVE FIELDS** |
| `ActiveInterfaces` (`protocol.rs`) | `netlink.rs:266, 345, 363-385, 1171` | Uses `::default()` & reads `wifi/cellular/vpn/other` | Keep `Default` & existing 4 `Vec<String>` fields unchanged |
| `FirewallMode` (`config.rs`) | `firewall.rs:698` (exhaustive `match`) | N/A | **FROZEN — DO NOT ADD VARIANTS** (`DefaultAllow`, `DefaultDeny`) |
| `BlockAction` (`config.rs`) | `firewall.rs:519` (exhaustive `match`) | N/A | **FROZEN — DO NOT ADD VARIANTS** (`Reject`, `Drop`) |
| `FreezeMode` (`config.rs`) | `freezer.rs:81` (exhaustive `match`) | N/A | **FROZEN — DO NOT ADD VARIANTS** (`SoftCgroup`, `DeepStop`) |
| `AuditSeverity` (`protocol.rs`) | `sentinel.rs` (`Pass`/`Info`/`Warn`/`Critical`) | N/A | Keep all 4 variants unchanged |
| `AppFirewallRule` / `FreezerEntry` / `HardeningConfig` / `CanonicalCidr` (`config.rs`) | `firewall.rs`, `freezer.rs`, `hardening.rs`, `netlink.rs` | Field reads + `AppFirewallRule::deny_all` + `HardeningConfig::default()` | Keep all existing fields & constructors unchanged |

### 4.2 Every M1 Function & Constant Called by M2 Files (MUST Preserve Exact Signatures)

| M1 Module | Exact Symbol & Signature | M2 Callers |
|---|---|---|
| `crate::sys` | `pub struct CmdOutput { pub status_code: i32, pub stdout: String, pub stderr: String, pub timed_out: bool }` + `pub fn success(&self) -> bool` | `firewall.rs`, `freezer.rs`, `automation.rs`, `sentinel.rs` |
| `crate::sys` | `pub fn run_cmd(program: &str, args: &[&str], stdin_data: Option<&str>, timeout_ms: u64) -> Result<CmdOutput, String>` | `firewall.rs`, `freezer.rs`, `automation.rs`, `sentinel.rs` |
| `crate::sys` | `pub fn resolve_bin(name: &str) -> String` | `firewall.rs`, `freezer.rs`, `automation.rs`, `sentinel.rs` |
| `crate::sys` | `pub fn kernel_release() -> String` | `firewall.rs:7, 137` |
| `crate::sys` | `pub fn boottime_secs() -> u64` | `freezer.rs:4, 145, 185, 204`; `automation.rs:3, 51, 62` |
| `crate::sys` | `pub fn now_millis() -> u64` | `netlink.rs:3, 1061, 1085`; `sentinel.rs:10, 272, 420` |
| `crate::sys` | `pub fn foreground_app_uids() -> Vec<u32>` | `freezer.rs:4, 186, 206` (**MUST remain `Vec<u32>` & `oom_score_adj <= 100`**) |
| `crate::sys` | `pub fn parse_proc_status_uid(status: &str) -> Option<u32>` | `freezer.rs:4, 304`; `sentinel.rs:10, 486` |
| `crate::sys` | `pub fn read_uid_package_map() -> HashMap<u32, String>` | `sentinel.rs:10, 139` (passed to `netlink::list_active_connections(&uid_map)`) |
| `crate::scopes` | `pub fn count_live_ignored_ops(cfg: &ScopesConfig) -> usize` | `sentinel.rs:9, 363` (**Explorer 1 MUST keep this exact signature!**) |
| `crate::config` | `pub const MIN_AUTO_REBOOT_MINUTES: u32` | `automation.rs:1, 50, 130` |
| `crate::config` | `pub fn validate_cidr(input: &str) -> Option<CanonicalCidr>` | `firewall.rs:1, 183` |
| `crate::config` | `pub fn validate_hostname(host: &str) -> bool` | `automation.rs:1, 185` |
| `crate::config` | `pub fn validate_package_name(pkg: &str) -> bool` | `freezer.rs:1, 94, 113, 150` |
| `crate::config` | `pub fn is_protected_uid(uid: u32) -> bool` | `freezer.rs:1, 70, 232` |
| `crate::config` | `pub fn is_protected_package(pkg: &str) -> bool` | `freezer.rs:1, 70, 150, 232` |
| `crate::ipc` | *(No M2 file imports `crate::ipc`)* | None |

### 4.3 M1 (`main.rs`) -> M2 Engine Signatures & Lock-Decoupling Compatibility Guide (For Explorer 2 & Worker)

All M2 engine methods that `main.rs` calls, their exact receiver/parameter types, blocking characteristics, and exact zero-M2-edit lock-decoupling patterns:

| M2 Module & Item | Exact Signature | Blocks / Spawns Subprocesses? | M1 Lock-Decoupling & Compatibility Pattern (ZERO M2 edits!) |
|---|---|---|---|
| `firewall::probe_capabilities` | `() -> KernelCapabilities` | Yes (`iptables`/`ip6tables` × 7) | Call at startup before spawning server, or outside `state_mtx`. |
| `firewall::apply_firewall` | `(cfg: &FirewallConfig, caps: &mut KernelCapabilities, screen_on: bool, temp_unblocked_uids: &HashSet<u32>, previously_blocked_uids: &mut HashSet<u32>) -> Result<FirewallApplyResult, String>` | **YES** (`iptables-restore`, `ip6tables-restore`, Netlink ipset, `SOCK_DESTROY`) | Hold dedicated `firewall_op_mtx: Arc<Mutex<()>>` (serializes kernel rule writes without blocking RPCs). Snapshot `(cfg.firewall, caps, screen_on, temp_set, previously_blocked_uids)` under <1ms `state_mtx`, **DROP `state_mtx`**, run `apply_firewall(...)` on local copies, then briefly re-lock `state_mtx` to commit results! |
| `firewall::ensure_hook_jumps` | `(ipt_name: &str, mangle_ttl_enabled: bool) -> Result<(), String>` | **YES** (`iptables -C/-I/-D`) | Snapshot `caps` & `normalize_ttl_hl` under `state_mtx`, drop `state_mtx`, run under `firewall_op_mtx`. |
| `firewall::flush_firewall` | `(caps: &KernelCapabilities)` | **YES** (`iptables`/`ip6tables` × many) | Clone `caps` under `state_mtx`, drop `state_mtx`, run under `firewall_op_mtx`. |
| `netlink::classify_active_interfaces` | `() -> ActiveInterfaces` | Netlink (up to 2s timeout) | Call 100% OUTSIDE `state_mtx`. |
| `netlink::list_active_connections` | `(uid_map: &HashMap<u32, String>) -> Vec<ActiveConnection>` | Netlink (up to 2s timeout) | **Type note**: takes `&HashMap<u32, String>`. Clone `uid_index.read().primary_map().clone()` (<0.1ms), drop read lock, call `list_active_connections(&primary)`. |
| `netlink::dump_inet_sockets` | `(family: u8, protocol: u8) -> io::Result<Vec<RawInetSocket>>` | Netlink | **M1 Security Workaround (`D-10`/`CB-12`)**: Since `netlink.rs` cannot be edited in M1, `KillConnection` in `main.rs` can inspect matching sockets via `dump_inet_sockets` BEFORE calling `destroy_matching_connection` (or directly call `netlink::destroy_socket(&sk)` on verified non-protected, non-`LISTEN` (`state != 10`), non-`TIME_WAIT` (`state != 6`) sockets!) while checking `is_protected_uid(sk.uid) || uid_index.any_pkg_for_uid(sk.uid, is_protected_package)`! |
| `netlink::destroy_socket` | `(sk: &RawInetSocket) -> io::Result<()>` | Netlink | Public! Enables safe `KillConnection` in `main.rs` without editing `netlink.rs`. |
| `netlink::destroy_uid_sockets` / `destroy_all_user_sockets` / `destroy_matching_connection` | `(u32)->usize` / `()->usize` / `(u8,&str,u16,&str,u16)->Result<usize,String>` | Netlink | Call 100% OUTSIDE `state_mtx`. Check `is_protected_uid(uid) \|\| uid_index.any_pkg_for_uid(uid, is_protected_package)` before `KillUidSockets`! |
| `netlink::run_nflog_loop` | `<F: FnMut(BlockedPacketEvent)>(group: u16, stop: Arc<AtomicBool>, on_event: F)` | Dedicated blocking thread | Inside callback: acquire `uid_index.read()`, lock `telemetry_mtx`, call `ring.push(ev, idx.primary_map())` -> **ZERO `state_mtx` lock, ZERO map clone!** |
| `telemetry::TelemetryRing::push` | `(&mut self, mut event: BlockedPacketEvent, uid_map: &HashMap<u32, String>)` | No (~100ns) | **Type note**: expects `&HashMap<u32, String>`; pass `idx.primary_map()` directly! |
| `freezer::FreezerEngine` | `!Clone`; `freeze_uid_now`, `thaw_uid_now`, `thaw_all`, `policy_tick` take `&mut self`; `status`, `currently_frozen`, `suspended_packages`, `count_verified_frozen_pids` take `&self` | **YES** (`pm`/`am` in `freeze_uid_now`, `thaw_uid_now`, `thaw_all`, `policy_tick`) | **Extract out of `DaemonState`** into `Arc<Mutex<FreezerEngine>>`! Cache `frozen_uids_count` & `suspended_pkgs_count` in `Arc<RwLock<CachedSystemState>>` (or `AtomicUsize`) updated after every freezer op/tick so `GetStatus` NEVER waits on `freezer_mtx`! |
| `automation::AutomationEngine` | `!Clone`; `set_sensor_privacy(&mut self, ..)`, `tick(&mut self, ..)`, `live_sensor_privacy(&self)`, `auto_reboot_remaining_secs(&self, ..)` | **YES** (`dumpsys`/`cmd`/`settings`/`svc`) | **Extract out of `DaemonState`** into `Arc<Mutex<AutomationEngine>>`! Refresh `sensor_privacy`, `private_dns`, `radio_privacy`, and `auto_reboot_remaining_secs` into `Arc<RwLock<CachedSystemState>>` on supervisor ticks & immediately after write RPCs (`SetSensorsOff`, `SetSensorPrivacy`, `SetPrivateDns`, `SetRadioPrivacy`, `TriggerPanicLockdown`) so `GetStatus` executes in **<1ms with ZERO subprocesses**! |
| `automation::{get_private_dns, set_private_dns, get_radio_privacy, set_radio_privacy, disable_wifi_and_bt_now, execute_clean_bfu_reboot, probe_real_sensor_privacy}` | Free functions | **YES** (`settings`/`svc`/`dumpsys`) | Call 100% OUTSIDE `state_mtx`! |
| `hardening::HardeningEngine` | `!Clone`; `apply(&mut self, &HardeningConfig)`, `rollback_all(&mut self, &HardeningConfig)`, `status(&self, &HardeningConfig)` | Fast `/proc/sys` I/O (no forks) | **Extract out of `DaemonState`** into `Arc<Mutex<HardeningEngine>>`. |
| `sentinel::run_sentinel_audit` | `(cfg: &SiloConfig, firewall_active: bool) -> SentinelReport` | `/proc` + Netlink | Snapshot `(cfg, firewall_active)` under `state_mtx`, drop `state_mtx`, call outside lock. |
| `sentinel::run_live_proof` | `(cfg: &SiloConfig, caps: &KernelCapabilities, hardening: &HardeningEngine, freezer: &FreezerEngine) -> LiveProofReport` | **YES** (`iptables`, `ip6tables`, `cmd appops get`, `settings`, `dumpsys`) | Snapshot `(cfg, caps)` under <1ms `state_mtx`, **DROP `state_mtx`**, lock `hardening_mtx` then `freezer_mtx` (strict lock order!), and run `run_live_proof(&cfg, &caps, &h, &f)` completely outside `state_mtx`! |

**Strict Deadlock-Free Lock Hierarchy for `main.rs`**:
1. Subsystem locks (ONLY acquired outside `state_mtx`; if multiple needed, strict order: `firewall_op_mtx` -> `hardening_mtx` -> `freezer_mtx` -> `automation_mtx` -> `clipboard_mtx`).
2. Leaf locks (held for **<1ms only**, NEVER held across subprocesses or while acquiring any subsystem lock):
   - `state_mtx` (`Mutex<DaemonState>`)
   - `uid_index` (`RwLock<UidPackageIndex>`)
   - `cached_sys` (`RwLock<CachedSystemState>`)
   - `telemetry_mtx` (`Mutex<TelemetryRing>`)

> **Important Note on `PanicLockdown` in M1 vs M2 (`D-06`)**:
> M1 adds `#[serde(default)] pub panic_lockdown_active: bool` to `FirewallConfig` (`config.rs`) and `DaemonStatus` (`protocol.rs`), and sets/clears it in `main.rs` (`TriggerPanicLockdown` sets `true`; `SetFirewallConfig`/`ApplyPreset`/`EmergencyFlushFirewall` clears `false`). Full `SILO_OUT` unconditional drop behavior for `panic_lockdown_active` in `firewall.rs` is implemented in M2 (which owns `firewall.rs`). Existing M2 tests in `firewall.rs` continue passing untouched in M1 because `FirewallConfig` test literals use `..Default::default()`.

---

## 5. Unit Test Plan for `daemon/src/sys.rs`

Keep all 4 existing tests in `daemon/src/sys.rs:314-350` unchanged and add the following 8 targeted tests inside `daemon/src/sys.rs` `#[cfg(test)] mod tests`:

1. **`run_cmd_timeout_kills_process_group_and_avoids_pipe_deadlock`**:
   - Spawn `run_cmd("/bin/sh", &["-c", "sleep 30 & echo $!; sleep 30"], None, 200)`.
   - Assert wall-clock duration `< Duration::from_secs(3)` (proves reader threads did NOT deadlock on grandchild's inherited stdout/stderr pipe FDs!).
   - Assert `out.timed_out && !out.success() && out.status_code == -1`.
   - If grandchild PID was printed on `out.stdout`, sleep 50ms and assert `unsafe { libc::kill(gpid, 0) } != 0` (proves process-group `SIGKILL` actually terminated the grandchild!).
2. **`parse_packages_list_multi_handles_shared_uids_and_multi_user`**:
   - Input with shared UID `10150` (`com.samsung.shared.a`, `com.samsung.android.honeyboard`, duplicate `com.samsung.shared.a`), plus normal UID `10201` (`com.example.one`) and explicit multi-user UID `1010202` (`com.work.app`).
   - Assert `pkgs_for_uid(10150) == &["com.samsung.shared.a", "com.samsung.android.honeyboard"]` (deduplicated).
   - Assert `first_pkg(10150) == Some("com.samsung.shared.a")` and `primary_map().get(&10150).map(String::as_str) == Some("com.samsung.shared.a")`.
   - Assert multi-user appId fallback: `any_pkg_for_uid(1010150, |p| p == "com.samsung.android.honeyboard")` is `true` (Work Profile `u10`) and `any_pkg_for_uid(9510150, |p| p == "com.samsung.android.honeyboard")` is `true` (Samsung Dual Messenger `u95`)!
   - Assert `uid_for_pkg("com.example.one") == Some(10201)`, `user_id_for_pkg("com.example.one") == 0`, `user_id_for_pkg("com.work.app") == 10`.
3. **`top_activity_oom_adj_distinguishes_top_from_fgs`**:
   - Assert `is_top_activity_oom_adj(0)` is `true`.
   - Assert `is_top_activity_oom_adj(50)`, `is_top_activity_oom_adj(100)`, `is_top_activity_oom_adj(200)`, `is_top_activity_oom_adj(-900)` are all `false`.
   - Call `let _ = top_activity_uids();` to verify zero-panic execution on Linux host.
4. **`parse_screen_on_from_dumpsys_handles_samsung_aod_and_states`**:
   - `mWakefulness=Dozing` -> `Some(false)`.
   - `mWakefulness=Asleep` -> `Some(false)`.
   - `mWakefulness=Awake` + `Display Power: state=DOZE` -> `Some(false)`.
   - Empty power dump + `mState=DOZE` / `mState=DOZE_SUSPEND` / `mState=OFF` -> `Some(false)`.
   - `mWakefulness=Awake` + `Display Power: state=ON` + `mState=ON` -> `Some(true)`.
   - Unrelated `mState=0` line preceding `mState=DOZE_SUSPEND` -> `Some(false)`.
   - Both empty -> `None`.
5. **`parse_user0_unlocked_from_dumpsys_is_strict_and_failsafe`**:
   - `UserInfo{0:Owner:c13}` + `State: RUNNING_UNLOCKED` -> `Some(true)`.
   - `UserInfo{0:Owner:c13}` + `State: RUNNING_LOCKED` followed by `UserInfo{10:Work:1030}` + `State: RUNNING_UNLOCKED` -> `Some(false)` (Work Profile must NOT mark user 0 unlocked!).
   - `UserInfo{0:Owner:c13}` + `State: RUNNING_UNLOCKING` -> `Some(false)`.
   - Empty / unrelated dump -> `None`.
   - Assert `!is_ce_unlocked()` on non-Android Linux host (proving fail-safe `false` without `/data/user/0/android` fallback!).
6. **`set_oom_score_adj_never_panics`**:
   - Calls `set_oom_score_adj(-900)`, `set_oom_score_adj(0)`, `set_oom_score_adj(-5000)`, `set_oom_score_adj(5000)` cleanly without panicking.
7. **`inotify_watcher_detects_write_and_atomic_rename_in_temp_dir`**:
   - Creates unique temp dir `std::env::temp_dir().join(format!("silo_inotify_test_{}", std::process::id()))`.
   - Instantiates `InotifyWatcher::new().unwrap()`, adds watch on dir with `INOTIFY_PERM_WATCH_MASK`, asserts `watch_count() == 1`.
   - Asserts `wait_events(40).unwrap().is_none()` when idle.
   - Writes `runtime-permissions.xml.tmp` and renames to `runtime-permissions.xml`.
   - Asserts `wait_events(500).unwrap()` is `Some(names)` containing `"runtime-permissions.xml"`.
   - Removes temp dir cleanly.
8. **`parse_inotify_names_handles_packed_and_truncated_buffers`**:
   - Constructs a packed 2-event buffer (`appops.xml\0\0` + `runtime-permissions.xml\0`) and asserts exact deduplicated extraction; also tests truncated/oversized `len` buffers without panicking.

---

## 6. Verification Command for Step 1b Worker

```bash
cd /home/antigravity/terenzy/Silo/daemon && cargo clippy --all-targets -- -D warnings && cargo test --verbose
```
And verify `git status` shows ONLY M1 exclusive files modified (`daemon/src/{config,protocol,ipc,sys,scopes,main}.rs`, `daemon/Cargo.toml`) — ZERO edits to any M2 file!
