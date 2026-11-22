use std::collections::HashMap;
use std::fs;
use std::io::{Read, Write};
use std::path::Path;
use std::process::{Command, Stdio};
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

/// Executes an external binary DIRECTLY without a shell (`/system/bin/sh` is NEVER invoked),
/// with optional stdin payload and a strict wall-clock timeout so a hung Android service
/// can never deadlock `silod`.
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

    // Drain stdout & stderr concurrently on bounded reader threads to avoid pipe-buffer deadlocks.
    let stdout_pipe = child.stdout.take();
    let stderr_pipe = child.stderr.take();

    let out_handle = thread::spawn(move || read_bounded(stdout_pipe, 512 * 1024));
    let err_handle = thread::spawn(move || read_bounded(stderr_pipe, 128 * 1024));

    let deadline = Instant::now() + Duration::from_millis(timeout_ms.max(100));
    let mut timed_out = false;
    let status_code = loop {
        match child.try_wait() {
            Ok(Some(status)) => break status.code().unwrap_or(-1),
            Ok(None) => {
                if Instant::now() >= deadline {
                    timed_out = true;
                    let _ = child.kill();
                    let _ = child.wait();
                    break -1;
                }
                thread::sleep(Duration::from_millis(15));
            }
            Err(_) => {
                let _ = child.kill();
                break -1;
            }
        }
    };

    let stdout = out_handle.join().unwrap_or_default();
    let stderr = err_handle.join().unwrap_or_default();

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
    let mut buf = Vec::with_capacity(4096);
    let mut chunk = [0u8; 4096];
    while buf.len() < max_bytes {
        match reader.read(&mut chunk) {
            Ok(0) => break,
            Ok(n) => {
                let remain = max_bytes - buf.len();
                buf.extend_from_slice(&chunk[..n.min(remain)]);
            }
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

/// Returns true once the primary user (user 0) has completed First Unlock (Credential-Encrypted storage active).
/// Prevents Auto-Reboot BFU loops when the phone is ALREADY in BFU state.
pub fn is_ce_unlocked() -> bool {
    if let Some(v) = getprop("sys.user.0.ce_available") {
        if v == "true" || v == "1" {
            return true;
        }
    }
    // Secondary heuristic: /data/user_ce/0 or /data/data/android readable after CE unlock
    Path::new("/data/user/0/android").exists()
}

/// Fast zero-fork check for display backlight state on Samsung/MediaTek/AOSP devices,
/// with graceful fallback to `dumpsys power`.
pub fn detect_screen_on() -> bool {
    let candidate_paths = [
        "/sys/class/backlight/panel/brightness",
        "/sys/class/leds/lcd-backlight/brightness",
        "/sys/devices/platform/panel/backlight/panel/brightness",
    ];
    for p in candidate_paths {
        if let Ok(val) = fs::read_to_string(p) {
            if let Ok(num) = val.trim().parse::<i64>() {
                return num > 0;
            }
        }
    }
    // Check any backlight device under /sys/class/backlight/*/brightness
    if let Ok(entries) = fs::read_dir("/sys/class/backlight") {
        for entry in entries.flatten() {
            let bp = entry.path().join("brightness");
            if let Ok(val) = fs::read_to_string(bp) {
                if let Ok(num) = val.trim().parse::<i64>() {
                    return num > 0;
                }
            }
        }
    }
    // Fallback to dumpsys power (bounded 2s timeout)
    let bin = resolve_bin("dumpsys");
    if let Ok(out) = run_cmd(&bin, &["power"], None, 2000) {
        for line in out.stdout.lines() {
            let trimmed = line.trim();
            if trimmed.starts_with("mWakefulness=") {
                return trimmed.contains("Awake");
            }
        }
    }
    true
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
}
