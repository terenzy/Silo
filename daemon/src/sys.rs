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

#[derive(Debug, Clone)]
pub struct CmdOutput {
    pub status_code: i32,
    pub stdout: String,
    pub stderr: String,
    pub timed_out: bool,
}

impl CmdOutput {
    pub fn success(&self) -> bool {
        !self.timed_out && self.status_code == 0
    }
}

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

/// Resolves an Android system binary across standard PATH locations.
pub fn resolve_bin(name: &str) -> String {
    for dir in ["/system/bin", "/vendor/bin", "/sbin", "/usr/sbin", "/usr/bin", "/bin"] {
        let candidate = format!("{}/{}", dir, name);
        if Path::new(&candidate).exists() {
            return candidate;
        }
    }
    name.to_string()
}

/// Returns monotonic CLOCK_BOOTTIME seconds (continues ticking during Android deep sleep / Doze).
pub fn boottime_secs() -> u64 {
    let mut ts = libc::timespec {
        tv_sec: 0,
        tv_nsec: 0,
    };
    let rc = unsafe { libc::clock_gettime(libc::CLOCK_BOOTTIME, &mut ts) };
    if rc == 0 && ts.tv_sec >= 0 {
        ts.tv_sec as u64
    } else {
        // Fallback to /proc/uptime
        fs::read_to_string("/proc/uptime")
            .ok()
            .and_then(|s| s.split_whitespace().next()?.parse::<f64>().ok())
            .map(|v| v.max(0.0) as u64)
            .unwrap_or(0)
    }
}

pub fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

pub fn kernel_release() -> String {
    fs::read_to_string("/proc/sys/kernel/osrelease")
        .map(|s| s.trim().to_string())
        .unwrap_or_else(|_| "unknown".to_string())
}

/// Parses `/data/system/packages.list` into a UID -> primary package name map (plus reverse lookup).
pub fn read_uid_package_map() -> HashMap<u32, String> {
    let mut map = HashMap::new();
    let Ok(content) = fs::read_to_string("/data/system/packages.list") else {
        return map;
    };
    parse_packages_list_str(&content, &mut map);
    map
}

pub fn parse_packages_list_str(content: &str, out: &mut HashMap<u32, String>) {
    for line in content.lines() {
        let mut parts = line.split_whitespace();
        let Some(pkg) = parts.next() else { continue };
        let Some(uid_str) = parts.next() else { continue };
        if let Ok(uid) = uid_str.parse::<u32>() {
            out.entry(uid).or_insert_with(|| pkg.to_string());
        }
    }
}

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
        self.uid_for_pkg(pkg).map(|uid| uid / 100_000).unwrap_or(0)
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

pub fn getprop(name: &str) -> Option<String> {
    let bin = resolve_bin("getprop");
    let out = run_cmd(&bin, &[name], None, 1500).ok()?;
    if out.success() {
        let v = out.stdout.trim().to_string();
        if !v.is_empty() {
            return Some(v);
        }
    }
    None
}

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

/// Checks whether Android keyguard / device lock is currently active.
pub fn detect_device_locked() -> bool {
    let bin = resolve_bin("dumpsys");
    if let Ok(out) = run_cmd(&bin, &["trust"], None, 2000) {
        for line in out.stdout.lines() {
            let trimmed = line.trim();
            if trimmed.contains("deviceLocked=1") || trimmed.contains("deviceLocked=true") {
                return true;
            }
            if trimmed.contains("deviceLocked=0") || trimmed.contains("deviceLocked=false") {
                return false;
            }
        }
    }
    if let Ok(out) = run_cmd(&bin, &["window", "policy"], None, 2000) {
        for line in out.stdout.lines() {
            let trimmed = line.trim();
            if trimmed.contains("isStatusBarKeyguard=true")
                || trimmed.contains("mShowingLockscreen=true")
                || trimmed.contains("showing=true")
            {
                return true;
            }
        }
    }
    false
}

/// Scans `/proc/<pid>/oom_score_adj` and `/proc/<pid>/status` to find UIDs currently in
/// foreground/visible/perceptible state (`oom_score_adj <= 100`). Zero fork overhead!
pub fn foreground_app_uids() -> Vec<u32> {
    let mut uids = Vec::new();
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
        if adj > 100 {
            continue;
        }
        if let Ok(status) = fs::read_to_string(path.join("status")) {
            if let Some(uid) = parse_proc_status_uid(&status) {
                if (uid % 100_000) >= 10_000 && !uids.contains(&uid) {
                    uids.push(uid);
                }
            }
        }
    }
    uids
}

pub fn parse_proc_status_uid(status: &str) -> Option<u32> {
    for line in status.lines() {
        if let Some(rest) = line.strip_prefix("Uid:") {
            return rest.split_whitespace().next()?.parse::<u32>().ok();
        }
    }
    None
}

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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_packages_list_extracts_uids() {
        let sample = "\
com.example.one 10201 0 /data/user/0/com.example.one default:targetSdkVersion=35 none 0 1
com.example.two 10202 0 /data/user/0/com.example.two default:targetSdkVersion=35 none 0 1
malformed_line
";
        let mut map = HashMap::new();
        parse_packages_list_str(sample, &mut map);
        assert_eq!(map.get(&10201).map(String::as_str), Some("com.example.one"));
        assert_eq!(map.get(&10202).map(String::as_str), Some("com.example.two"));
        assert_eq!(map.len(), 2);
    }

    #[test]
    fn parse_proc_status_uid_works() {
        let status = "Name:\tfoo\nPid:\t1234\nUid:\t10234\t10234\t10234\t10234\n";
        assert_eq!(parse_proc_status_uid(status), Some(10234));
    }

    #[test]
    fn run_cmd_timeout_works_without_hanging() {
        let out = run_cmd("/bin/sleep", &["5"], None, 150).unwrap();
        assert!(out.timed_out);
        assert!(!out.success());
    }

    #[test]
    fn boottime_is_positive() {
        assert!(boottime_secs() > 0);
    }

    #[test]
    fn run_cmd_timeout_kills_process_group_and_avoids_pipe_deadlock() {
        let started = Instant::now();
        let out = run_cmd("/bin/sh", &["-c", "sleep 30 & echo $!; sleep 30"], None, 200).unwrap();
        let elapsed = started.elapsed();

        assert!(
            elapsed < Duration::from_secs(3),
            "run_cmd took too long ({:?}), possible pipe deadlock",
            elapsed
        );
        assert!(out.timed_out);
        assert!(!out.success());
        assert_eq!(out.status_code, -1);

        if let Some(gpid) = out
            .stdout
            .lines()
            .next()
            .and_then(|l| l.trim().parse::<libc::pid_t>().ok())
        {
            let mut dead = false;
            for _ in 0..15 {
                let rc = unsafe { libc::kill(gpid, 0) };
                if rc != 0 {
                    dead = true;
                    break;
                }
                thread::sleep(Duration::from_millis(20));
            }
            assert!(
                dead,
                "grandchild PID {} survived process-group SIGKILL",
                gpid
            );
        }
    }

    #[test]
    fn parse_packages_list_multi_handles_shared_uids_and_multi_user() {
        let sample = "\
com.samsung.shared.a 10150 0 /data/user/0/com.samsung.shared.a default:targetSdkVersion=35 none 0 1
com.samsung.android.honeyboard 10150 0 /data/user/0/com.samsung.android.honeyboard default:targetSdkVersion=35 none 0 1
com.samsung.shared.a 10150 0 /data/user/0/com.samsung.shared.a default:targetSdkVersion=35 none 0 1
com.example.one 10201 0 /data/user/0/com.example.one default:targetSdkVersion=35 none 0 1
com.work.app 1010202 0 /data/user/10/com.work.app default:targetSdkVersion=35 none 0 1
";
        let idx = parse_packages_list_multi(sample);
        assert!(!idx.is_empty());
        assert_eq!(
            idx.pkgs_for_uid(10150),
            &[
                "com.samsung.shared.a".to_string(),
                "com.samsung.android.honeyboard".to_string()
            ]
        );
        assert_eq!(idx.first_pkg(10150), Some("com.samsung.shared.a"));
        assert_eq!(
            idx.primary_map().get(&10150).map(String::as_str),
            Some("com.samsung.shared.a")
        );
        // Multi-user appId fallback (Work Profile u10 & Samsung Dual Messenger u95)
        assert!(idx.any_pkg_for_uid(1_010_150, |p| p == "com.samsung.android.honeyboard"));
        assert!(idx.any_pkg_for_uid(9_510_150, |p| p == "com.samsung.android.honeyboard"));
        assert_eq!(idx.uid_for_pkg("com.example.one"), Some(10201));
        assert_eq!(idx.user_id_for_pkg("com.example.one"), 0);
        assert_eq!(idx.user_id_for_pkg("com.work.app"), 10);
    }

    #[test]
    fn top_activity_oom_adj_distinguishes_top_from_fgs() {
        assert!(is_top_activity_oom_adj(0));
        assert!(!is_top_activity_oom_adj(50));
        assert!(!is_top_activity_oom_adj(100));
        assert!(!is_top_activity_oom_adj(200));
        assert!(!is_top_activity_oom_adj(-900));
        let _ = top_activity_uids();
    }

    #[test]
    fn parse_screen_on_from_dumpsys_handles_samsung_aod_and_states() {
        assert_eq!(
            parse_screen_on_from_dumpsys("mWakefulness=Dozing\n", ""),
            Some(false)
        );
        assert_eq!(
            parse_screen_on_from_dumpsys("mWakefulness=Asleep\n", ""),
            Some(false)
        );
        assert_eq!(
            parse_screen_on_from_dumpsys(
                "mWakefulness=Awake\nDisplay Power: state=DOZE\n",
                ""
            ),
            Some(false)
        );
        assert_eq!(
            parse_screen_on_from_dumpsys("", "  mState=DOZE\n"),
            Some(false)
        );
        assert_eq!(
            parse_screen_on_from_dumpsys("", "  mState=DOZE_SUSPEND\n"),
            Some(false)
        );
        assert_eq!(
            parse_screen_on_from_dumpsys("", "  mState=OFF\n"),
            Some(false)
        );
        assert_eq!(
            parse_screen_on_from_dumpsys(
                "mWakefulness=Awake\nDisplay Power: state=ON\n",
                "mState=ON\n"
            ),
            Some(true)
        );
        assert_eq!(
            parse_screen_on_from_dumpsys("", "mState=0\nmState=DOZE_SUSPEND\n"),
            Some(false)
        );
        assert_eq!(parse_screen_on_from_dumpsys("", ""), None);
    }

    #[test]
    fn parse_user0_unlocked_from_dumpsys_is_strict_and_failsafe() {
        let unlocked = "\
Users:
  UserInfo{0:Owner:c13} serialNo=0
    State: RUNNING_UNLOCKED
";
        assert_eq!(parse_user0_unlocked_from_dumpsys(unlocked), Some(true));

        let locked_with_work_unlocked = "\
Users:
  UserInfo{0:Owner:c13} serialNo=0
    State: RUNNING_LOCKED
  UserInfo{10:Work:1030} serialNo=10
    State: RUNNING_UNLOCKED
";
        assert_eq!(
            parse_user0_unlocked_from_dumpsys(locked_with_work_unlocked),
            Some(false)
        );

        let unlocking = "\
  UserInfo{0:Owner:c13} serialNo=0
    State: RUNNING_UNLOCKING
";
        assert_eq!(parse_user0_unlocked_from_dumpsys(unlocking), Some(false));
        assert_eq!(parse_user0_unlocked_from_dumpsys(""), None);
        assert_eq!(
            parse_user0_unlocked_from_dumpsys("unrelated output"),
            None
        );
        // Non-Android Linux host must fail-safe to false (no /data/user/0/android fallback!)
        assert!(!is_ce_unlocked());
    }

    #[test]
    fn set_oom_score_adj_never_panics() {
        let _ = set_oom_score_adj(-900);
        let _ = set_oom_score_adj(0);
        let _ = set_oom_score_adj(-5000);
        let _ = set_oom_score_adj(5000);
    }

    #[test]
    fn inotify_watcher_detects_write_and_atomic_rename_in_temp_dir() {
        let dir = std::env::temp_dir().join(format!("silo_inotify_test_{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();

        let mut watcher = InotifyWatcher::new().unwrap();
        watcher.add_watch(&dir, INOTIFY_PERM_WATCH_MASK).unwrap();
        assert_eq!(watcher.watch_count(), 1);
        assert!(watcher.wait_events(40).unwrap().is_none());

        let tmp = dir.join("runtime-permissions.xml.tmp");
        let dst = dir.join("runtime-permissions.xml");
        fs::write(&tmp, b"<runtime-permissions/>").unwrap();
        fs::rename(&tmp, &dst).unwrap();

        let names = watcher
            .wait_events(500)
            .unwrap()
            .expect("expected inotify events after write + rename");
        assert!(
            names.iter().any(|n| n == "runtime-permissions.xml"),
            "expected runtime-permissions.xml in {:?}",
            names
        );

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn parse_inotify_names_handles_packed_and_truncated_buffers() {
        let mut buf = Vec::new();
        // Record 1: appops.xml\0\0 (12 bytes)
        buf.extend_from_slice(&1i32.to_ne_bytes()); // wd
        buf.extend_from_slice(&libc::IN_MOVED_TO.to_ne_bytes()); // mask
        buf.extend_from_slice(&0u32.to_ne_bytes()); // cookie
        buf.extend_from_slice(&12u32.to_ne_bytes()); // len
        buf.extend_from_slice(b"appops.xml\0\0");

        // Record 2: runtime-permissions.xml\0 (24 bytes)
        buf.extend_from_slice(&1i32.to_ne_bytes());
        buf.extend_from_slice(&libc::IN_CLOSE_WRITE.to_ne_bytes());
        buf.extend_from_slice(&0u32.to_ne_bytes());
        buf.extend_from_slice(&24u32.to_ne_bytes());
        buf.extend_from_slice(b"runtime-permissions.xml\0");

        // Record 3: duplicate appops.xml\0\0 (should be deduplicated)
        buf.extend_from_slice(&1i32.to_ne_bytes());
        buf.extend_from_slice(&libc::IN_CLOSE_WRITE.to_ne_bytes());
        buf.extend_from_slice(&0u32.to_ne_bytes());
        buf.extend_from_slice(&12u32.to_ne_bytes());
        buf.extend_from_slice(b"appops.xml\0\0");

        let mut names = Vec::new();
        parse_inotify_names(&buf, &mut names);
        assert_eq!(names, vec!["appops.xml", "runtime-permissions.xml"]);

        // Truncated / oversized length must not panic
        let mut bad = Vec::new();
        bad.extend_from_slice(&1i32.to_ne_bytes());
        bad.extend_from_slice(&0u32.to_ne_bytes());
        bad.extend_from_slice(&0u32.to_ne_bytes());
        bad.extend_from_slice(&u32::MAX.to_ne_bytes());
        let mut names2 = Vec::new();
        parse_inotify_names(&bad, &mut names2);
        assert!(names2.is_empty());
    }
}
