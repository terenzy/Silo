use crate::config::{is_protected_package, validate_package_name, AppIsolationScope, ScopesConfig};
use crate::sys::{boottime_secs, resolve_bin, run_cmd};
use std::collections::hash_map::DefaultHasher;
use std::hash::{Hash, Hasher};

// Verified valid Android 12-16 AppOps names only (no invalid/non-AppOp permissions!)
const OPS_CONTACTS: &[&str] = &["READ_CONTACTS", "WRITE_CONTACTS"];
const OPS_STORAGE: &[&str] = &[
    "READ_EXTERNAL_STORAGE",
    "WRITE_EXTERNAL_STORAGE",
    "READ_MEDIA_IMAGES",
    "READ_MEDIA_VIDEO",
    "READ_MEDIA_AUDIO",
    "MANAGE_EXTERNAL_STORAGE",
    "LEGACY_STORAGE",
];
const OPS_SMS_CALLLOG: &[&str] = &[
    "READ_SMS",
    "RECEIVE_SMS",
    "READ_CALL_LOG",
    "WRITE_CALL_LOG",
];
const OPS_CLIPBOARD: &[&str] = &["READ_CLIPBOARD"];
const OPS_SENSORS: &[&str] = &["BODY_SENSORS", "ACTIVITY_RECOGNITION"];
const OPS_BACKGROUND: &[&str] = &[
    "RUN_IN_BACKGROUND",
    "RUN_ANY_IN_BACKGROUND",
    "WAKE_LOCK",
    "GET_USAGE_STATS",
    "READ_DEVICE_IDENTIFIERS",
];
const OPS_LOCATION: &[&str] = &["COARSE_LOCATION", "FINE_LOCATION"];
const OPS_CAMERA_MIC: &[&str] = &["CAMERA", "RECORD_AUDIO"];

/// Applies GrapheneOS-inspired fake-empty App Isolation Scopes via `cmd appops set --user 0 <pkg> <op> ignore`.
/// Returns number of AppOps successfully updated.
pub fn apply_app_scope(package_name: &str, scope: &AppIsolationScope) -> Result<usize, String> {
    if !validate_package_name(package_name) {
        return Err(format!("Invalid package name: {}", package_name));
    }
    if is_protected_package(package_name) {
        return Err(format!(
            "Refused to isolate protected system/core package: {}",
            package_name
        ));
    }

    let groups: &[(&[&str], bool)] = &[
        (OPS_CONTACTS, scope.isolate_contacts),
        (OPS_STORAGE, scope.isolate_storage),
        (OPS_SMS_CALLLOG, scope.isolate_sms_calllog),
        (OPS_CLIPBOARD, scope.block_clipboard_read),
        (OPS_SENSORS, scope.restrict_sensors),
        (OPS_BACKGROUND, scope.restrict_background),
        (OPS_LOCATION, scope.isolate_location),
        (OPS_CAMERA_MIC, scope.block_camera_mic),
    ];

    let cmd_bin = resolve_bin("cmd");
    let mut ok_count = 0usize;
    let mut last_err: Option<String> = None;

    for &(ops, isolated) in groups {
        let mode = if isolated { "ignore" } else { "default" };
        for &op in ops {
            match run_cmd(
                &cmd_bin,
                &["appops", "set", "--user", "0", package_name, op, mode],
                None,
                2500,
            ) {
                Ok(out) if out.success() => ok_count += 1,
                Ok(out) => {
                    let msg = out.stderr.trim();
                    if !msg.is_empty() {
                        last_err = Some(msg.to_string());
                    }
                }
                Err(e) => last_err = Some(e),
            }
        }
    }

    if ok_count == 0 {
        if let Some(e) = last_err {
            return Err(format!("appops failed: {}", e));
        }
    }
    Ok(ok_count)
}

pub fn clear_app_scope(package_name: &str) -> Result<usize, String> {
    apply_app_scope(package_name, &AppIsolationScope::default())
}

pub fn sync_all_scopes(cfg: &ScopesConfig) {
    for (pkg, scope) in &cfg.apps {
        if scope.any_enabled() {
            let _ = apply_app_scope(pkg, scope);
        }
    }
}

/// Counts live `ignore` appops verified via `cmd appops get --user 0 <pkg>` for Live Proof.
pub fn count_live_ignored_ops(cfg: &ScopesConfig) -> usize {
    let cmd_bin = resolve_bin("cmd");
    let mut total = 0usize;
    for (pkg, scope) in &cfg.apps {
        if !scope.any_enabled() || !validate_package_name(pkg) {
            continue;
        }
        if let Ok(out) = run_cmd(&cmd_bin, &["appops", "get", "--user", "0", pkg], None, 2000) {
            if out.success() {
                total += out
                    .stdout
                    .lines()
                    .filter(|l| l.contains(": ignore"))
                    .count();
            }
        }
    }
    total
}

/// Zero-logging Auto-Clear Clipboard guard.
#[derive(Debug, Default)]
pub struct ClipboardGuard {
    last_clip_fingerprint: Option<u64>,
    clip_seen_at_boottime: u64,
    already_cleared_fingerprint: Option<u64>,
}

impl ClipboardGuard {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn clear_now(&mut self) -> Result<(), String> {
        let cmd_bin = resolve_bin("cmd");
        // Try standard Android 14-16 `cmd clipboard` variants (as root, then via shell UID 2000 if OEM restricts UID 0)
        for args in [
            &["clipboard", "clear"][..],
            &["clipboard", "set-text", ""][..],
        ] {
            if let Ok(out) = run_cmd(&cmd_bin, args, None, 2000) {
                if out.success() {
                    self.already_cleared_fingerprint = self.last_clip_fingerprint;
                    return Ok(());
                }
            }
        }
        // Fallback: execute under shell UID 2000 (Android ClipboardService shell command checks shell UID on some OEMs)
        let su_bin = resolve_bin("su");
        for cmd_str in ["cmd clipboard clear", "cmd clipboard set-text ''"] {
            if let Ok(out) = run_cmd(&su_bin, &["2000", "-c", cmd_str], None, 2500) {
                if out.success() {
                    self.already_cleared_fingerprint = self.last_clip_fingerprint;
                    return Ok(());
                }
            }
        }
        Err("OEM ClipboardService rejected shell clear; use Clear Now inside Silo app".to_string())
    }

    pub fn tick(&mut self, cfg: &ScopesConfig) {
        if !cfg.auto_clear_clipboard {
            return;
        }
        let Some(fp) = probe_clipboard_metadata_hash() else {
            self.last_clip_fingerprint = None;
            return;
        };

        let now = boottime_secs();
        if self.last_clip_fingerprint != Some(fp) {
            self.last_clip_fingerprint = Some(fp);
            self.clip_seen_at_boottime = now;
            return;
        }

        if self.already_cleared_fingerprint == Some(fp) {
            return;
        }

        if now.saturating_sub(self.clip_seen_at_boottime) >= u64::from(cfg.clipboard_clear_secs) {
            let _ = self.clear_now();
            self.already_cleared_fingerprint = Some(fp);
        }
    }
}

fn probe_clipboard_metadata_hash() -> Option<u64> {
    let dumpsys = resolve_bin("dumpsys");
    let out = run_cmd(&dumpsys, &["clipboard"], None, 2000).ok()?;
    if !out.success() {
        return None;
    }
    let mut meta_lines = Vec::new();
    for line in out.stdout.lines() {
        let t = line.trim();
        if t.starts_with("ClipDescription")
            || t.starts_with("mPrimaryClipTimestamp")
            || t.starts_with("primaryClipPackage")
            || t.contains("timestamp=")
            || t.contains("Primary clip:")
        {
            if t.contains("null") || t.contains("empty") {
                return None;
            }
            meta_lines.push(t);
        }
    }
    if meta_lines.is_empty() {
        return None;
    }
    let mut hasher = DefaultHasher::new();
    for l in meta_lines {
        l.hash(&mut hasher);
    }
    Some(hasher.finish())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_invalid_or_protected_packages() {
        let profile = AppIsolationScope::hardened_profile();
        assert!(apply_app_scope("bad;rm -rf /", &profile).is_err());
        assert!(apply_app_scope("com.android.systemui", &profile).is_err());
        assert!(apply_app_scope("dev.silo.app", &profile).is_err());
    }
}
