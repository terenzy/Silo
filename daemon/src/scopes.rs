use crate::config::{
    is_protected_package, is_valid_perm_category, validate_package_name, AppIsolationScope,
    ScopesConfig,
};
use crate::protocol::{AppLivePermissions, CategoryPermLiveState, PrivacyAccessEvent};
use crate::sys::{boottime_secs, resolve_bin, run_cmd};
use std::collections::hash_map::DefaultHasher;
use std::collections::{HashMap, HashSet};
use std::hash::{Hash, Hasher};

pub struct PermCategorySpec {
    pub id: &'static str,
    pub runtime_perms: &'static [&'static str],
    pub appops: &'static [&'static str],
}

/// Verified Android 12-16 runtime permissions + AppOps categories.
/// Strictly honest: `media` controls MediaStore/Gallery access (NOT POSIX `/sdcard` folder names).
pub const PERM_CATEGORIES: &[PermCategorySpec] = &[
    PermCategorySpec {
        id: "camera",
        runtime_perms: &["android.permission.CAMERA"],
        appops: &["CAMERA"],
    },
    PermCategorySpec {
        id: "microphone",
        runtime_perms: &["android.permission.RECORD_AUDIO"],
        appops: &["RECORD_AUDIO"],
    },
    PermCategorySpec {
        id: "location",
        runtime_perms: &[
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
        ],
        appops: &["FINE_LOCATION", "COARSE_LOCATION"],
    },
    PermCategorySpec {
        id: "background_location",
        runtime_perms: &["android.permission.ACCESS_BACKGROUND_LOCATION"],
        appops: &["MONITOR_LOCATION", "MONITOR_HIGH_POWER_LOCATION"],
    },
    PermCategorySpec {
        id: "contacts",
        runtime_perms: &[
            "android.permission.READ_CONTACTS",
            "android.permission.WRITE_CONTACTS",
        ],
        appops: &["READ_CONTACTS", "WRITE_CONTACTS"],
    },
    PermCategorySpec {
        id: "media",
        runtime_perms: &[
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
        ],
        appops: &[
            "READ_MEDIA_IMAGES",
            "READ_MEDIA_VIDEO",
            "READ_MEDIA_AUDIO",
            "READ_EXTERNAL_STORAGE",
            "WRITE_EXTERNAL_STORAGE",
            "MANAGE_EXTERNAL_STORAGE",
        ],
    },
    PermCategorySpec {
        id: "sms_calllog",
        runtime_perms: &[
            "android.permission.READ_SMS",
            "android.permission.RECEIVE_SMS",
            "android.permission.READ_CALL_LOG",
            "android.permission.WRITE_CALL_LOG",
        ],
        appops: &["READ_SMS", "RECEIVE_SMS", "READ_CALL_LOG", "WRITE_CALL_LOG"],
    },
    PermCategorySpec {
        id: "clipboard",
        runtime_perms: &[],
        appops: &["READ_CLIPBOARD"],
    },
    PermCategorySpec {
        id: "sensors",
        runtime_perms: &[
            "android.permission.BODY_SENSORS",
            "android.permission.ACTIVITY_RECOGNITION",
        ],
        appops: &["BODY_SENSORS", "ACTIVITY_RECOGNITION"],
    },
    PermCategorySpec {
        id: "background",
        runtime_perms: &[],
        appops: &[
            "RUN_IN_BACKGROUND",
            "RUN_ANY_IN_BACKGROUND",
            "WAKE_LOCK",
            "SYSTEM_ALERT_WINDOW",
            "GET_USAGE_STATS",
        ],
    },
];

pub fn find_category(id: &str) -> Option<&'static PermCategorySpec> {
    PERM_CATEGORIES.iter().find(|c| c.id == id)
}

pub fn category_for_op(op: &str) -> Option<&'static str> {
    let up = op.trim().to_ascii_uppercase();
    for spec in PERM_CATEGORIES {
        if spec.appops.iter().any(|&o| o == up) {
            return Some(spec.id);
        }
    }
    None
}

/// Applies real per-app permission / AppOps action:
/// - `allow` (`default`): resets AppOps to Android default (does not silently re-grant revoked runtime perms).
/// - `foreground`: restricts AppOps to `foreground` (only usable while actively open on screen).
/// - `ignore`: sets AppOps to `ignore` (fake-empty / silent block without crashing app).
/// - `revoke`: runs real `pm revoke --user 0 <pkg> <perm>` on every runtime permission + `appops ignore`.
pub fn set_app_permission_action(
    package_name: &str,
    category: &str,
    action: &str,
) -> Result<AppLivePermissions, String> {
    if !validate_package_name(package_name) {
        return Err(format!("Invalid package name: {}", package_name));
    }
    if is_protected_package(package_name) {
        return Err(format!(
            "Refused to modify permissions on protected system package: {}",
            package_name
        ));
    }
    let Some(spec) = find_category(category) else {
        return Err(format!("Unknown permission category: {}", category));
    };

    let clean_action = action.trim().to_ascii_lowercase();
    let appops_mode = match clean_action.as_str() {
        "allow" | "default" => "default",
        "foreground" => "foreground",
        "ignore" => "ignore",
        "revoke" | "deny" => "ignore",
        other => return Err(format!("Unsupported permission action: {}", other)),
    };

    if clean_action == "revoke" || clean_action == "deny" {
        let pm = resolve_bin("pm");
        for &perm in spec.runtime_perms {
            let _ = run_cmd(&pm, &["revoke", "--user", "0", package_name, perm], None, 2000);
        }
    }

    let cmd_bin = resolve_bin("cmd");
    let mut ok_count = 0usize;
    let mut last_err = String::new();
    for &op in spec.appops {
        match run_cmd(
            &cmd_bin,
            &["appops", "set", "--user", "0", package_name, op, appops_mode],
            None,
            2500,
        ) {
            Ok(out) if out.success() => ok_count += 1,
            Ok(out) => {
                let err = out.stderr.trim();
                if !err.is_empty() {
                    last_err = err.to_string();
                }
            }
            Err(e) => last_err = e,
        }
    }

    if ok_count == 0 && !last_err.is_empty() {
        return Err(format!("appops set failed: {}", last_err));
    }

    Ok(inspect_app_permissions(package_name))
}

/// Bulk revokes a single permission category across a list of user packages (used by Global Audit).
pub fn bulk_revoke_category(category: &str, packages: &[String]) -> Result<usize, String> {
    if !is_valid_perm_category(category) {
        return Err(format!("Invalid permission category: {}", category));
    }
    let mut count = 0usize;
    for pkg in packages {
        if !validate_package_name(pkg) || is_protected_package(pkg) {
            continue;
        }
        if set_app_permission_action(pkg, category, "revoke").is_ok() {
            count += 1;
        }
    }
    Ok(count)
}

/// Live per-app AppOps inspection via `cmd appops get --user 0 <pkg>`.
pub fn inspect_app_permissions(package_name: &str) -> AppLivePermissions {
    let mut raw_ops: HashMap<String, (String, Option<String>, bool)> = HashMap::new();
    if validate_package_name(package_name) {
        let cmd_bin = resolve_bin("cmd");
        if let Ok(out) = run_cmd(
            &cmd_bin,
            &["appops", "get", "--user", "0", package_name],
            None,
            2500,
        ) {
            if out.success() {
                raw_ops = parse_appops_get_output(&out.stdout);
            }
        }
    }

    let mut categories = Vec::with_capacity(PERM_CATEGORIES.len());
    for spec in PERM_CATEGORIES {
        let mut best_mode = "default".to_string();
        let mut best_priority = 0u8;
        let mut last_access: Option<String> = None;
        let mut running_now = false;

        for &op in spec.appops {
            if let Some((mode, access, running)) = raw_ops.get(op) {
                let prio = match mode.as_str() {
                    "ignore" | "deny" => 4,
                    "foreground" => 3,
                    "allow" => 2,
                    _ => 1,
                };
                if prio > best_priority {
                    best_priority = prio;
                    best_mode = mode.clone();
                }
                if *running {
                    running_now = true;
                }
                if last_access.is_none() && access.is_some() {
                    last_access = access.clone();
                }
            }
        }

        categories.push(CategoryPermLiveState {
            category: spec.id.to_string(),
            appops_mode: best_mode,
            last_access,
            running_now,
        });
    }

    AppLivePermissions {
        package_name: package_name.to_string(),
        categories,
    }
}

/// Parses `cmd appops get --user 0 <pkg>` stdout into `OP_NAME -> (mode, last_access, running_now)`.
pub fn parse_appops_get_output(output: &str) -> HashMap<String, (String, Option<String>, bool)> {
    let mut map = HashMap::new();
    for raw_line in output.lines() {
        let mut line = raw_line.trim();
        if line.is_empty() || line.starts_with("No operations") {
            continue;
        }
        if let Some(rest) = line.strip_prefix("Uid mode:") {
            line = rest.trim();
        }
        let Some((op_raw, rest_raw)) = line.split_once(':') else {
            continue;
        };
        let op = op_raw.trim().to_ascii_uppercase();
        if op.is_empty() || op.contains(' ') {
            continue;
        }
        let rest = rest_raw.trim();
        let mode = rest
            .split([';', ' ', ','])
            .next()
            .unwrap_or("default")
            .trim()
            .to_ascii_lowercase();
        if !matches!(
            mode.as_str(),
            "allow" | "ignore" | "deny" | "default" | "foreground"
        ) {
            continue;
        }
        let running = rest.contains("(running)") || rest.contains("running;");
        let last_access = extract_access_time(rest);

        // Prefer stricter/specific package line over generic Uid mode if op already exists
        map.entry(op)
            .and_modify(|e: &mut (String, Option<String>, bool)| {
                if !raw_line.trim().starts_with("Uid mode:") {
                    e.0 = mode.clone();
                }
                if e.1.is_none() && last_access.is_some() {
                    e.1 = last_access.clone();
                }
                if running {
                    e.2 = true;
                }
            })
            .or_insert((mode, last_access, running));
    }
    map
}

fn extract_access_time(text: &str) -> Option<String> {
    if let Some(idx) = text.find("time=") {
        let sub = &text[idx + 5..];
        let end = sub.find(';').unwrap_or(sub.len());
        let val = sub[..end].trim();
        if !val.is_empty() {
            return Some(val.to_string());
        }
    }
    None
}

/// Queries real-time Privacy Access Timeline via targeted `dumpsys appops --op <OP>` calls
/// (avoids full multi-MB dumpsys truncation and executes in ~15ms per op!).
pub fn get_privacy_access_log(
    uid_map: &HashMap<u32, String>,
    limit: usize,
) -> Vec<PrivacyAccessEvent> {
    let dumpsys = resolve_bin("dumpsys");
    let ops_to_audit = [
        "CAMERA",
        "RECORD_AUDIO",
        "FINE_LOCATION",
        "COARSE_LOCATION",
        "READ_CLIPBOARD",
        "READ_CONTACTS",
        "READ_SMS",
        "READ_CALL_LOG",
        "READ_MEDIA_IMAGES",
        "READ_MEDIA_VIDEO",
        "BODY_SENSORS",
    ];

    let mut all_events = Vec::new();
    for &op in &ops_to_audit {
        if let Ok(out) = run_cmd(&dumpsys, &["appops", "--op", op], None, 2000) {
            if out.success() {
                parse_dumpsys_appops_op(&out.stdout, op, uid_map, &mut all_events);
            }
        }
    }

    // Sort: currently running FIRST, then most recent access age ascending!
    all_events.sort_by(|a, b| {
        b.running_now
            .cmp(&a.running_now)
            .then_with(|| a.age_secs.cmp(&b.age_secs))
    });

    // Deduplicate by (package_name, category) keeping the freshest event
    let mut seen = HashSet::new();
    all_events.retain(|e| seen.insert((e.package_name.clone(), e.category.clone())));
    all_events.truncate(limit.clamp(10, 200));
    all_events
}

/// Parses `dumpsys appops --op <OP>` text output across Android 12-16 / One UI 7.
pub fn parse_dumpsys_appops_op(
    dump: &str,
    op_name: &str,
    uid_map: &HashMap<u32, String>,
    out: &mut Vec<PrivacyAccessEvent>,
) {
    let category = category_for_op(op_name).unwrap_or("background").to_string();
    let mut current_uid: u32 = 0;
    let mut current_pkg: Option<String> = None;
    let mut current_mode = "allow".to_string();
    let mut in_matching_op = false;

    for raw_line in dump.lines() {
        let t = raw_line.trim();
        if t.is_empty() {
            continue;
        }

        if let Some(rest) = t.strip_prefix("Uid ") {
            let token = rest.trim_end_matches(':').trim();
            current_uid = parse_android_uid_token(token).unwrap_or(0);
            current_pkg = uid_map.get(&current_uid).cloned();
            in_matching_op = false;
            continue;
        }

        if let Some(rest) = t.strip_prefix("Package ") {
            let pkg = rest.trim_end_matches(':').trim().to_string();
            current_pkg = if validate_package_name(&pkg) {
                Some(pkg)
            } else {
                None
            };
            in_matching_op = false;
            continue;
        }

        // Op header line: `CAMERA (allow):` or `RECORD_AUDIO (foreground):` or `CAMERA (allow/allow):`
        if t.starts_with(op_name) && t.ends_with(':') {
            in_matching_op = true;
            if let (Some(l), Some(r)) = (t.find('('), t.find(')')) {
                if r > l + 1 {
                    current_mode = t[l + 1..r]
                        .split('/')
                        .next()
                        .unwrap_or("allow")
                        .trim()
                        .to_ascii_lowercase();
                }
            }
            continue;
        }

        // Stop if another op header starts
        if in_matching_op && t.ends_with(':') && t.contains('(') && !t.starts_with("Access:") {
            in_matching_op = false;
            continue;
        }

        if in_matching_op && (t.starts_with("Access:") || t.contains("time=+")) {
            let Some(ref pkg) = current_pkg else {
                continue;
            };
            // Focus on real user/third-party/visible apps (UID >= 10000) & skip Silo itself
            if (current_uid % 100_000) < 10_000 || pkg == "dev.silo.app" {
                continue;
            }
            let running_now = t.contains("(running)") || t.contains("running=true");
            let (age_secs, age_label) = parse_relative_age_from_access_line(t);

            // Only include events from the last 7 days (or running now)
            if !running_now && age_secs > 7 * 86_400 {
                continue;
            }

            out.push(PrivacyAccessEvent {
                uid: current_uid,
                package_name: pkg.clone(),
                category: category.clone(),
                op: op_name.to_string(),
                mode: current_mode.clone(),
                last_access_label: age_label,
                age_secs,
                running_now,
            });
            in_matching_op = false; // Take the freshest access entry for this pkg+op
        }
    }
}

fn parse_android_uid_token(token: &str) -> Option<u32> {
    if let Ok(n) = token.parse::<u32>() {
        return Some(n);
    }
    // Handle `u0a277` -> 10277, `u10a42` -> 1_010_042
    if let Some(rest) = token.strip_prefix('u') {
        if let Some((user_str, app_str)) = rest.split_once('a') {
            let user = user_str.parse::<u32>().ok()?;
            let app = app_str.parse::<u32>().ok()?;
            return Some(user * 100_000 + 10_000 + app);
        }
    }
    None
}

/// Parses Android duration expressions like `(-2m15s44ms)` or `time=+1h5m10s ago` into seconds + short label.
pub fn parse_relative_age_from_access_line(line: &str) -> (u64, String) {
    // Look for `(-...)` first (Android 13-16 `Access:` format)
    let raw_dur = if let Some(start) = line.find("(-") {
        let sub = &line[start + 2..];
        sub.find(')').map(|end| sub[..end].trim())
    } else if let Some(start) = line.find("time=+") {
        let sub = &line[start + 6..];
        let end = sub.find([' ', ';']).unwrap_or(sub.len());
        Some(sub[..end].trim())
    } else {
        None
    };

    let Some(dur) = raw_dur else {
        return (u64::MAX / 2, "unknown".to_string());
    };

    let mut total_secs = 0u64;
    let mut num = 0u64;
    let bytes = dur.as_bytes();
    let mut i = 0usize;
    while i < bytes.len() {
        if bytes[i].is_ascii_digit() {
            num = num.saturating_mul(10).saturating_add((bytes[i] - b'0') as u64);
            i += 1;
        } else {
            let start = i;
            while i < bytes.len() && bytes[i].is_ascii_alphabetic() {
                i += 1;
            }
            match &dur[start..i] {
                "d" => total_secs = total_secs.saturating_add(num.saturating_mul(86_400)),
                "h" => total_secs = total_secs.saturating_add(num.saturating_mul(3_600)),
                "m" => total_secs = total_secs.saturating_add(num.saturating_mul(60)),
                "s" => total_secs = total_secs.saturating_add(num),
                "ms" => {}
                _ => {}
            }
            num = 0;
        }
    }

    let label = if total_secs < 60 {
        format!("{}s", total_secs)
    } else if total_secs < 3600 {
        format!("{}m", total_secs / 60)
    } else if total_secs < 86_400 {
        format!("{}h {}m", total_secs / 3600, (total_secs % 3600) / 60)
    } else {
        format!("{}d", total_secs / 86_400)
    };
    (total_secs, label)
}

/// Real-time Daemon Permission Enforcer:
/// - **Auto-Revoke on Background**: revokes configured categories whenever the app leaves foreground or screen turns off.
/// - **Strict Lock (`locked_deny`)**: re-revokes & re-ignores if Android/user popup re-enabled a locked category externally.
#[derive(Debug, Default)]
pub struct PermissionEnforcer {
    was_foreground: HashSet<String>,
    last_strict_sweep_boottime: u64,
    pub actions_count: u64,
    pub last_event: Option<String>,
}

impl PermissionEnforcer {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn tick(
        &mut self,
        cfg: &ScopesConfig,
        screen_on: bool,
        fg_uids: &[u32],
        uid_map: &HashMap<u32, String>,
    ) {
        if cfg.guards.is_empty() {
            return;
        }

        let fg_pkgs: HashSet<&str> = if screen_on {
            fg_uids
                .iter()
                .filter_map(|u| uid_map.get(u).map(String::as_str))
                .collect()
        } else {
            HashSet::new()
        };

        let now = boottime_secs();
        let do_periodic_sweep = now.saturating_sub(self.last_strict_sweep_boottime) >= 15;
        if do_periodic_sweep {
            self.last_strict_sweep_boottime = now;
        }

        for (pkg, guard) in &cfg.guards {
            if !validate_package_name(pkg) || is_protected_package(pkg) {
                continue;
            }
            let is_fg = fg_pkgs.contains(pkg.as_str());
            let just_left_fg = self.was_foreground.contains(pkg) && !is_fg;

            if is_fg {
                self.was_foreground.insert(pkg.clone());
            } else {
                self.was_foreground.remove(pkg);
            }

            // 1. Auto-Revoke on Background / Screen-Off
            if !guard.auto_revoke_on_bg.is_empty() && !is_fg && (just_left_fg || do_periodic_sweep)
            {
                let live = inspect_app_permissions(pkg);
                for cat in &guard.auto_revoke_on_bg {
                    let needs_revoke = just_left_fg
                        || live
                            .categories
                            .iter()
                            .find(|c| &c.category == cat)
                            .map(|c| c.appops_mode != "ignore" && c.appops_mode != "deny")
                            .unwrap_or(false);
                    if needs_revoke && set_app_permission_action(pkg, cat, "revoke").is_ok() {
                        self.actions_count += 1;
                        self.last_event = Some(format!("Auto-revoked {} on {}", cat, pkg));
                    }
                }
            }

            // 2. Strict Lock (`locked_deny`) — re-enforce immediately if externally re-enabled!
            if !guard.locked_deny.is_empty() && do_periodic_sweep {
                let live = inspect_app_permissions(pkg);
                for cat in &guard.locked_deny {
                    let drifted = live
                        .categories
                        .iter()
                        .find(|c| &c.category == cat)
                        .map(|c| c.appops_mode != "ignore" && c.appops_mode != "deny")
                        .unwrap_or(false);
                    if drifted && set_app_permission_action(pkg, cat, "revoke").is_ok() {
                        self.actions_count += 1;
                        self.last_event = Some(format!("Re-locked {} on {}", cat, pkg));
                    }
                }
            }
        }
    }
}

/// Legacy compatibility helper: applies AppIsolationScope via verified AppOps `ignore`.
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

    let pairs: &[(&str, bool)] = &[
        ("contacts", scope.isolate_contacts),
        ("media", scope.isolate_storage),
        ("sms_calllog", scope.isolate_sms_calllog),
        ("clipboard", scope.block_clipboard_read),
        ("sensors", scope.restrict_sensors),
        ("background", scope.restrict_background),
        ("location", scope.isolate_location),
        ("camera", scope.block_camera_mic),
        ("microphone", scope.block_camera_mic),
    ];

    let mut applied = 0usize;
    for &(cat, enabled) in pairs {
        let action = if enabled { "ignore" } else { "default" };
        if set_app_permission_action(package_name, cat, action).is_ok() {
            applied += 1;
        }
    }
    Ok(applied)
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
    let mut pkgs: HashSet<&str> = HashSet::new();
    for (pkg, scope) in &cfg.apps {
        if scope.any_enabled() {
            pkgs.insert(pkg.as_str());
        }
    }
    for pkg in cfg.guards.keys() {
        pkgs.insert(pkg.as_str());
    }
    let mut total = 0usize;
    for pkg in pkgs {
        if !validate_package_name(pkg) {
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
        let su_bin = resolve_bin("su");
        for cmd_str in ["cmd clipboard clear", "cmd clipboard set-text ''"] {
            if let Ok(out) = run_cmd(&su_bin, &["2000", "-c", cmd_str], None, 2500) {
                if out.success() {
                    self.already_cleared_fingerprint = self.last_clip_fingerprint;
                    return Ok(());
                }
            }
        }
        Err("OEM ClipboardService rejected shell clear; cleared via Silo native API".to_string())
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
        assert!(set_app_permission_action("com.android.systemui", "camera", "revoke").is_err());
    }

    #[test]
    fn parses_appops_get_output_accurately() {
        let sample = "\
Uid mode: COARSE_LOCATION: foreground
CAMERA: allow; time=+2m15s123ms ago; duration=+5s100ms
RECORD_AUDIO: ignore; rejectTime=+10s50ms ago
FINE_LOCATION: foreground; time=+12s ago (running)
";
        let parsed = parse_appops_get_output(sample);
        assert_eq!(parsed.get("CAMERA").map(|v| v.0.as_str()), Some("allow"));
        assert_eq!(
            parsed.get("RECORD_AUDIO").map(|v| v.0.as_str()),
            Some("ignore")
        );
        assert_eq!(
            parsed.get("FINE_LOCATION").map(|v| (v.0.as_str(), v.2)),
            Some(("foreground", true))
        );
    }

    #[test]
    fn parses_dumpsys_appops_privacy_access_events() {
        let sample = "\
Uid u0a277:
  Package com.example.chat:
    RECORD_AUDIO (allow):
      Access: [top-s] 2026-11-22 15:10:02.123 (-2m15s44ms) duration=+5s12ms
Uid u0a309:
  Package org.fossify.messages:
    RECORD_AUDIO (foreground):
      Access: [fg-s] 2026-11-22 15:12:00.000 (-12s10ms) (running)
Uid 1000:
  Package android:
    RECORD_AUDIO (allow):
      Access: [top-s] 2026-11-22 15:12:01.000 (-1s)
";
        let mut events = Vec::new();
        parse_dumpsys_appops_op(sample, "RECORD_AUDIO", &HashMap::new(), &mut events);
        assert_eq!(events.len(), 2);
        assert_eq!(events[0].uid, 10277);
        assert_eq!(events[0].package_name, "com.example.chat");
        assert_eq!(events[0].age_secs, 135);
        assert_eq!(events[0].last_access_label, "2m");
        assert!(!events[0].running_now);
        assert_eq!(events[1].uid, 10309);
        assert!(events[1].running_now);
        assert_eq!(events[1].age_secs, 12);
    }
}
