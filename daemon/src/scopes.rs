use crate::config::{is_protected_package, validate_package_name, AppIsolationScope, ScopesConfig};
use crate::sys::{boottime_secs, resolve_bin, run_cmd};
use std::collections::hash_map::DefaultHasher;
use std::hash::{Hash, Hasher};

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
const OPS_SENSORS: &[&str] = &[
    "BODY_SENSORS",
    "ACTIVITY_RECOGNITION",
    "HIGH_SAMPLING_RATE_SENSORS",
];
const OPS_BACKGROUND: &[&str] = &[
    "RUN_IN_BACKGROUND",
    "RUN_ANY_IN_BACKGROUND",
    "WAKE_LOCK",
    "GET_USAGE_STATS",
    "READ_DEVICE_IDENTIFIERS",
];
const OPS_LOCATION: &[&str] = &[
    "COARSE_LOCATION",
    "FINE_LOCATION",
    "MONITOR_LOCATION",
    "MONITOR_HIGH_POWER_LOCATION",
];
const OPS_CAMERA_MIC: &[&str] = &["CAMERA", "RECORD_AUDIO"];

/// Applies GrapheneOS-inspired fake-empty App Isolation Scopes via `cmd appops set <pkg> <op> ignore`.
/// Setting `ignore` (instead of `deny`) makes Android silently return empty cursors/results
/// so apps continue running without crashing or repeatedly nagging for permissions.
pub fn apply_app_scope(package_name: &str, scope: &AppIsolationScope) -> Result<(), String> {
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
    for &(ops, isolated) in groups {
        let mode = if isolated { "ignore" } else { "default" };
        for &op in ops {
            // Best-effort per op because some ops vary slightly across Android 12-16 OEMs
            let _ = run_cmd(
                &cmd_bin,
                &["appops", "set", "--user", "0", package_name, op, mode],
                None,
                2500,
            );
        }
    }
    Ok(())
}

/// Resets all Silo-managed appops for a package cleanly back to Android's `default`.
pub fn clear_app_scope(package_name: &str) -> Result<(), String> {
    apply_app_scope(package_name, &AppIsolationScope::default())
}

/// Re-applies all configured isolation scopes (e.g. after boot).
pub fn sync_all_scopes(cfg: &ScopesConfig) {
    for (pkg, scope) in &cfg.apps {
        if scope.any_enabled() {
            let _ = apply_app_scope(pkg, scope);
        }
    }
}

/// Privacy-preserving Auto-Clear Clipboard tracker:
/// Computes only a non-reversible hash of clipboard metadata (never stores or logs clipboard text!)
/// and clears the clipboard automatically once the configured timeout elapses.
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
        // Verified on Android 14-16: `cmd clipboard set-text ""` replaces primary clip cleanly.
        // Also attempt `cmd clipboard clear` if supported by OEM framework.
        let r1 = run_cmd(&cmd_bin, &["clipboard", "clear"], None, 2000);
        if let Ok(out) = r1 {
            if out.success() {
                self.already_cleared_fingerprint = self.last_clip_fingerprint;
                return Ok(());
            }
        }
        let r2 = run_cmd(&cmd_bin, &["clipboard", "set-text", ""], None, 2000)
            .map_err(|e| format!("clipboard clear failed: {}", e))?;
        if r2.success() {
            self.already_cleared_fingerprint = self.last_clip_fingerprint;
            Ok(())
        } else {
            Err(format!(
                "clipboard clear returned error: {}",
                r2.stderr.trim()
            ))
        }
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

/// Reads only the primary clip metadata header from `dumpsys clipboard` and hashes it in RAM.
/// Returns `None` when the clipboard is empty. Never persists or logs clipboard contents.
fn probe_clipboard_metadata_hash() -> Option<u64> {
    let dumpsys = resolve_bin("dumpsys");
    let out = run_cmd(&dumpsys, &["clipboard"], None, 2000).ok()?;
    if !out.success() {
        return None;
    }

    // Extract only metadata lines (timestamp / clip description / package owner)
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
        assert!(apply_app_scope("com.rifsxd.ksunext", &profile).is_err());
    }
}
