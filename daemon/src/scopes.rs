use crate::config::{
    is_protected_package, is_protected_uid, is_valid_perm_category, validate_package_name,
    AppIsolationScope, ScopesConfig,
};
use crate::protocol::{AppLivePermissions, CategoryPermLiveState, PrivacyAccessEvent};
use crate::sys::{
    boottime_secs, getprop, resolve_bin, run_cmd, top_activity_uids, CmdOutput, InotifyWatcher,
    INOTIFY_PERM_WATCH_MASK,
};
use std::collections::hash_map::DefaultHasher;
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::hash::{Hash, Hasher};
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AppOpSpec {
    pub name: &'static str,
    /// True for runtime-permission-backed ops (and `RUN_ANY_IN_BACKGROUND`) enforced via
    /// Android's `AppOpsService.setUidMode` (`cmd appops set --user <u_id> --uid <pkg> <op> <mode>`).
    pub uid_scoped: bool,
    /// True for special access AppOps (`MANAGE_EXTERNAL_STORAGE`, `SYSTEM_ALERT_WINDOW`,
    /// `GET_USAGE_STATS`) that are NOT dangerous runtime permissions (`pm revoke` rejects them).
    pub is_special: bool,
}

pub struct PermCategorySpec {
    pub id: &'static str,
    pub runtime_perms: &'static [&'static str],
    pub appops: &'static [AppOpSpec],
    /// True for the 8 runtime-permission categories; false for AppOps-only (`clipboard`, `background`).
    pub has_runtime_perms: bool,
    /// True ONLY for categories where Android supports `foreground` AppOps mode
    /// (`camera`, `microphone`, `location`, `sensors`).
    pub supports_foreground: bool,
}

const fn uid_op(name: &'static str) -> AppOpSpec {
    AppOpSpec {
        name,
        uid_scoped: true,
        is_special: false,
    }
}

const fn pkg_op(name: &'static str) -> AppOpSpec {
    AppOpSpec {
        name,
        uid_scoped: false,
        is_special: false,
    }
}

const fn special_op(name: &'static str) -> AppOpSpec {
    AppOpSpec {
        name,
        uid_scoped: false,
        is_special: true,
    }
}

/// Verified Android 12-16 runtime permissions + AppOps categories.
/// Strictly honest: `media` controls MediaStore/Gallery access (NOT POSIX `/sdcard` folder names),
/// and special AppOps (`MANAGE_EXTERNAL_STORAGE`, `SYSTEM_ALERT_WINDOW`, `GET_USAGE_STATS`) are
/// kept cleanly separated from `runtime_perms`.
pub const PERM_CATEGORIES: &[PermCategorySpec] = &[
    PermCategorySpec {
        id: "camera",
        runtime_perms: &["android.permission.CAMERA"],
        appops: &[uid_op("CAMERA")],
        has_runtime_perms: true,
        supports_foreground: true,
    },
    PermCategorySpec {
        id: "microphone",
        runtime_perms: &["android.permission.RECORD_AUDIO"],
        appops: &[uid_op("RECORD_AUDIO")],
        has_runtime_perms: true,
        supports_foreground: true,
    },
    PermCategorySpec {
        id: "location",
        runtime_perms: &[
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
        ],
        appops: &[uid_op("FINE_LOCATION"), uid_op("COARSE_LOCATION")],
        has_runtime_perms: true,
        supports_foreground: true,
    },
    PermCategorySpec {
        id: "background_location",
        runtime_perms: &["android.permission.ACCESS_BACKGROUND_LOCATION"],
        appops: &[
            uid_op("MONITOR_LOCATION"),
            uid_op("MONITOR_HIGH_POWER_LOCATION"),
        ],
        has_runtime_perms: true,
        supports_foreground: false,
    },
    PermCategorySpec {
        id: "contacts",
        runtime_perms: &[
            "android.permission.READ_CONTACTS",
            "android.permission.WRITE_CONTACTS",
        ],
        appops: &[uid_op("READ_CONTACTS"), uid_op("WRITE_CONTACTS")],
        has_runtime_perms: true,
        supports_foreground: false,
    },
    PermCategorySpec {
        id: "media",
        runtime_perms: &[
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
        ],
        appops: &[
            uid_op("READ_MEDIA_IMAGES"),
            uid_op("READ_MEDIA_VIDEO"),
            uid_op("READ_MEDIA_AUDIO"),
            uid_op("READ_MEDIA_VISUAL_USER_SELECTED"),
            uid_op("READ_EXTERNAL_STORAGE"),
            uid_op("WRITE_EXTERNAL_STORAGE"),
            special_op("MANAGE_EXTERNAL_STORAGE"),
        ],
        has_runtime_perms: true,
        supports_foreground: false,
    },
    PermCategorySpec {
        id: "sms_calllog",
        runtime_perms: &[
            "android.permission.READ_SMS",
            "android.permission.RECEIVE_SMS",
            "android.permission.SEND_SMS",
            "android.permission.RECEIVE_MMS",
            "android.permission.READ_CALL_LOG",
            "android.permission.WRITE_CALL_LOG",
        ],
        appops: &[
            uid_op("READ_SMS"),
            uid_op("RECEIVE_SMS"),
            uid_op("SEND_SMS"),
            uid_op("RECEIVE_MMS"),
            uid_op("READ_CALL_LOG"),
            uid_op("WRITE_CALL_LOG"),
        ],
        has_runtime_perms: true,
        supports_foreground: false,
    },
    PermCategorySpec {
        id: "clipboard",
        runtime_perms: &[],
        appops: &[pkg_op("READ_CLIPBOARD")],
        has_runtime_perms: false,
        supports_foreground: false,
    },
    PermCategorySpec {
        id: "sensors",
        runtime_perms: &[
            "android.permission.BODY_SENSORS",
            "android.permission.BODY_SENSORS_BACKGROUND",
            "android.permission.ACTIVITY_RECOGNITION",
        ],
        appops: &[uid_op("BODY_SENSORS"), uid_op("ACTIVITY_RECOGNITION")],
        has_runtime_perms: true,
        supports_foreground: true,
    },
    PermCategorySpec {
        id: "background",
        runtime_perms: &[],
        appops: &[
            pkg_op("RUN_IN_BACKGROUND"),
            uid_op("RUN_ANY_IN_BACKGROUND"),
            pkg_op("WAKE_LOCK"),
            special_op("SYSTEM_ALERT_WINDOW"),
            special_op("GET_USAGE_STATS"),
        ],
        has_runtime_perms: false,
        supports_foreground: false,
    },
];

pub fn find_category(id: &str) -> Option<&'static PermCategorySpec> {
    PERM_CATEGORIES.iter().find(|c| c.id == id)
}

pub fn category_for_op(op: &str) -> Option<&'static str> {
    let up = op.trim().to_ascii_uppercase();
    for spec in PERM_CATEGORIES {
        if spec.appops.iter().any(|o| o.name == up) {
            return Some(spec.id);
        }
    }
    None
}

/// Resolves the Android user ID (`uid / 100_000`) for `pkg` from `pkg_to_uid`, defaulting to user `0`.
pub fn resolve_pkg_user_id(pkg: &str, pkg_to_uid: &HashMap<String, u32>) -> u32 {
    pkg_to_uid.get(pkg).map(|&uid| uid / 100_000).unwrap_or(0)
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParsedAppOpEntry {
    pub uid_mode: Option<String>,
    pub pkg_mode: Option<String>,
    pub effective_mode: String,
    pub last_access: Option<String>,
    pub running_now: bool,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct PackageRuntimePermInfo {
    /// Permissions declared by the package (from `requested permissions:` and `runtime permissions:`).
    pub declared: HashSet<String>,
    /// Runtime permissions currently granted for the inspected user (`granted=true`).
    pub granted: HashSet<String>,
    /// Permissions carrying `SYSTEM_FIXED` for the inspected user.
    pub system_fixed: HashSet<String>,
    /// Permissions carrying `USER_FIXED` for the inspected user.
    pub user_fixed: HashSet<String>,
    /// Permissions carrying `REVOKED_COMPAT` for the inspected user.
    pub revoked_compat: HashSet<String>,
    /// True if the target `User <user_id>:` block (or legacy single-user `runtime permissions:`) was parsed.
    pub user_section_found: bool,
}

fn normalize_appops_mode(raw: &str) -> Option<&'static str> {
    match raw.trim().to_ascii_lowercase().as_str() {
        "allow" => Some("allow"),
        "foreground" => Some("foreground"),
        "ignore" => Some("ignore"),
        "deny" | "errored" => Some("deny"),
        "default" => Some("default"),
        _ => None,
    }
}

/// Strictness rank for combining `Uid mode` and package mode of the SAME op:
/// `deny` (5) > `ignore` (4) > `foreground` (3) > `allow` (2) > `default` (1).
fn op_strictness_rank(mode: &str) -> u8 {
    match mode {
        "deny" => 5,
        "ignore" => 4,
        "foreground" => 3,
        "allow" => 2,
        "default" => 1,
        _ => 0,
    }
}

/// Combines `Uid mode` and package mode for a single op using Android strictness precedence.
/// Guarantees restrictive `Uid mode` (`deny`, `ignore`, `foreground`) is NEVER overwritten
/// by permissive package history (`allow; time=+...` or `default`), regardless of line order.
pub fn combine_uid_and_pkg_mode(uid_mode: Option<&str>, pkg_mode: Option<&str>) -> String {
    let u = uid_mode.and_then(normalize_appops_mode);
    let p = pkg_mode.and_then(normalize_appops_mode);
    match (u, p) {
        (Some(um), Some(pm)) => {
            if op_strictness_rank(um) >= op_strictness_rank(pm) {
                um.to_string()
            } else {
                pm.to_string()
            }
        }
        (Some(um), None) => um.to_string(),
        (None, Some(pm)) => pm.to_string(),
        (None, None) => "default".to_string(),
    }
}

/// Fail-closed multi-op aggregation for a permission category:
/// `allow` (4) > `foreground` (3) > `ignore` (2) > `deny` (1) > `default` (0).
pub fn aggregate_category_appops_mode(modes: &[&str]) -> String {
    let mut best_mode = "default";
    let mut best_prio = 0u8;
    for raw in modes {
        let Some(norm) = normalize_appops_mode(raw) else {
            continue;
        };
        let prio = match norm {
            "allow" => 4,
            "foreground" => 3,
            "ignore" => 2,
            "deny" => 1,
            _ => 0,
        };
        if prio > best_prio {
            best_prio = prio;
            best_mode = norm;
        }
    }
    best_mode.to_string()
}

/// Parses `cmd appops get --user <u_id> <pkg>` stdout into per-op detailed state,
/// keeping `uid_mode` and `pkg_mode` separate and computing strictness-precedence `effective_mode`.
pub fn parse_appops_get_detailed(output: &str) -> HashMap<String, ParsedAppOpEntry> {
    let mut map: HashMap<String, ParsedAppOpEntry> = HashMap::new();
    for raw_line in output.lines() {
        let trimmed = raw_line.trim();
        if trimmed.is_empty() || trimmed.starts_with("No operations") {
            continue;
        }
        let (is_uid_line, line) = if let Some(rest) = trimmed.strip_prefix("Uid mode:") {
            (true, rest.trim())
        } else {
            (false, trimmed)
        };
        let Some((op_raw, rest_raw)) = line.split_once(':') else {
            continue;
        };
        let op = op_raw.trim().to_ascii_uppercase();
        if op.is_empty() || op.contains(' ') {
            continue;
        }
        let rest = rest_raw.trim();
        let raw_mode = rest.split([';', ' ', ',']).next().unwrap_or("");
        let Some(mode) = normalize_appops_mode(raw_mode) else {
            continue;
        };

        let running =
            rest.contains("(running)") || rest.contains("running;") || rest.contains("running=true");
        let last_access = extract_access_time(rest);

        let entry = map.entry(op).or_insert_with(|| ParsedAppOpEntry {
            uid_mode: None,
            pkg_mode: None,
            effective_mode: "default".to_string(),
            last_access: None,
            running_now: false,
        });

        if is_uid_line {
            let replace = entry
                .uid_mode
                .as_deref()
                .map(|prev| op_strictness_rank(mode) >= op_strictness_rank(prev))
                .unwrap_or(true);
            if replace {
                entry.uid_mode = Some(mode.to_string());
            }
        } else {
            let replace = entry
                .pkg_mode
                .as_deref()
                .map(|prev| op_strictness_rank(mode) >= op_strictness_rank(prev))
                .unwrap_or(true);
            if replace {
                entry.pkg_mode = Some(mode.to_string());
            }
        }

        if running {
            entry.running_now = true;
            if last_access.is_some() {
                entry.last_access = last_access;
            }
        } else if entry.last_access.is_none() && last_access.is_some() {
            entry.last_access = last_access;
        }

        entry.effective_mode =
            combine_uid_and_pkg_mode(entry.uid_mode.as_deref(), entry.pkg_mode.as_deref());
    }
    map
}

/// Backward-compatible wrapper: parses `cmd appops get --user <u_id> <pkg>` stdout into
/// `OP_NAME -> (effective_mode, last_access, running_now)`.
pub fn parse_appops_get_output(output: &str) -> HashMap<String, (String, Option<String>, bool)> {
    parse_appops_get_detailed(output)
        .into_iter()
        .map(|(op, e)| (op, (e.effective_mode, e.last_access, e.running_now)))
        .collect()
}

fn extract_access_time(text: &str) -> Option<String> {
    if let Some(idx) = text.find("time=") {
        let sub = &text[idx + 5..];
        let end = sub.find(';').unwrap_or(sub.len());
        let val = sub[..end].trim().trim_end_matches("(running)").trim();
        if !val.is_empty() {
            return Some(val.to_string());
        }
    }
    None
}

/// Pure parser for `dumpsys package <pkg>` runtime permissions & flags for a specific Android `user_id`.
/// Tracks indentation cleanly so `declared permissions:` / `install permissions:` / other users
/// (`User 10:`, `User 95:`, `User 150:`) never leak into `user_id`'s runtime grant state.
pub fn parse_dumpsys_package_runtime_perms(raw: &str, user_id: u32) -> PackageRuntimePermInfo {
    let mut info = PackageRuntimePermInfo::default();
    let mut in_requested_perms = false;
    let mut requested_indent: usize = 0;

    let mut saw_any_user_header = false;
    let mut current_user: Option<u32> = None;
    let mut user_indent: usize = 0;

    let mut in_runtime_perms = false;
    let mut runtime_indent: usize = 0;

    for raw_line in raw.lines() {
        let trimmed = raw_line.trim();
        if trimmed.is_empty() {
            continue;
        }
        let indent = raw_line.len() - raw_line.trim_start().len();

        if in_requested_perms && indent <= requested_indent {
            in_requested_perms = false;
        }
        if in_runtime_perms && indent <= runtime_indent {
            in_runtime_perms = false;
        }
        if current_user.is_some() && indent <= user_indent && !trimmed.starts_with("User ") {
            current_user = None;
        }

        if trimmed == "requested permissions:" {
            in_requested_perms = true;
            requested_indent = indent;
            in_runtime_perms = false;
            continue;
        }

        if let Some(rest) = trimmed.strip_prefix("User ") {
            if let Some((uid_str, _)) = rest.split_once(':') {
                if let Ok(parsed_user) = uid_str.trim().parse::<u32>() {
                    saw_any_user_header = true;
                    current_user = Some(parsed_user);
                    user_indent = indent;
                    in_requested_perms = false;
                    in_runtime_perms = false;
                    if parsed_user == user_id {
                        info.user_section_found = true;
                    }
                    continue;
                }
            }
        }

        if trimmed == "runtime permissions:" {
            let user_matches =
                current_user == Some(user_id) || (!saw_any_user_header && user_id == 0);
            if user_matches {
                info.user_section_found = true;
                in_runtime_perms = true;
                runtime_indent = indent;
            } else {
                in_runtime_perms = false;
            }
            continue;
        }

        if in_requested_perms {
            let perm = trimmed.split([':', ' ']).next().unwrap_or("").trim();
            if perm.contains('.') {
                info.declared.insert(perm.to_string());
            }
            continue;
        }

        if in_runtime_perms {
            let Some((perm_raw, rest)) = trimmed.split_once(':') else {
                continue;
            };
            let perm = perm_raw.trim();
            if !perm.contains('.') {
                continue;
            }
            info.declared.insert(perm.to_string());

            if rest.contains("granted=true") {
                info.granted.insert(perm.to_string());
            }

            if let Some(flag_start) = rest.find("flags=[") {
                let after = &rest[flag_start + 7..];
                let flag_body = after.split(']').next().unwrap_or("");
                for tok in flag_body.split(['|', ',', ' ', '\t']) {
                    match tok.trim() {
                        "SYSTEM_FIXED" => {
                            info.system_fixed.insert(perm.to_string());
                        }
                        "USER_FIXED" => {
                            info.user_fixed.insert(perm.to_string());
                        }
                        "REVOKED_COMPAT" => {
                            info.revoked_compat.insert(perm.to_string());
                        }
                        _ => {}
                    }
                }
            }
        }
    }

    info
}

fn query_package_runtime_perms(package_name: &str, user_id: u32) -> PackageRuntimePermInfo {
    if !validate_package_name(package_name) {
        return PackageRuntimePermInfo::default();
    }
    let dumpsys = resolve_bin("dumpsys");
    match run_cmd(&dumpsys, &["package", package_name], None, 2500) {
        Ok(out) if out.success() => parse_dumpsys_package_runtime_perms(&out.stdout, user_id),
        _ => PackageRuntimePermInfo::default(),
    }
}

/// Internal single-pass detailed inspection running `cmd appops get` and `dumpsys package`
/// concurrently (2 scoped threads) to halve latency and avoid duplicate subprocesses.
fn inspect_app_permissions_detailed(
    package_name: &str,
    user_id: u32,
) -> (
    AppLivePermissions,
    HashMap<String, ParsedAppOpEntry>,
    PackageRuntimePermInfo,
    bool,
) {
    let (raw_ops, appops_query_ok, rt_info) = if validate_package_name(package_name) {
        let user_str = user_id.to_string();
        std::thread::scope(|s| {
            let h_ops = s.spawn(|| {
                let cmd_bin = resolve_bin("cmd");
                match run_cmd(
                    &cmd_bin,
                    &["appops", "get", "--user", &user_str, package_name],
                    None,
                    2500,
                ) {
                    Ok(out) if out.success() => (parse_appops_get_detailed(&out.stdout), true),
                    _ => (HashMap::new(), false),
                }
            });
            let h_rt = s.spawn(|| query_package_runtime_perms(package_name, user_id));
            let (ops, ops_ok) = h_ops.join().unwrap_or_default();
            let rt = h_rt.join().unwrap_or_default();
            (ops, ops_ok, rt)
        })
    } else {
        (HashMap::new(), false, PackageRuntimePermInfo::default())
    };

    let live = build_app_live_permissions(package_name, &raw_ops, &rt_info);
    (live, raw_ops, rt_info, appops_query_ok)
}

/// Pure builder turning parsed AppOps + runtime permission info into `AppLivePermissions`.
pub fn build_app_live_permissions(
    package_name: &str,
    raw_ops: &HashMap<String, ParsedAppOpEntry>,
    rt_info: &PackageRuntimePermInfo,
) -> AppLivePermissions {
    let mut categories = Vec::with_capacity(PERM_CATEGORIES.len());
    for spec in PERM_CATEGORIES {
        let mut present_modes: Vec<&str> = Vec::with_capacity(spec.appops.len());
        let mut last_access: Option<String> = None;
        let mut running_now = false;

        for op in spec.appops {
            if let Some(entry) = raw_ops.get(op.name) {
                present_modes.push(entry.effective_mode.as_str());
                if entry.running_now {
                    running_now = true;
                    if entry.last_access.is_some() {
                        last_access = entry.last_access.clone();
                    }
                } else if last_access.is_none() && entry.last_access.is_some() {
                    last_access = entry.last_access.clone();
                }
            }
        }

        let best_mode = aggregate_category_appops_mode(&present_modes);

        let (runtime_granted, system_fixed) = if !spec.has_runtime_perms
            || !rt_info.user_section_found
        {
            (None, false)
        } else {
            let any_granted = spec
                .runtime_perms
                .iter()
                .any(|&p| rt_info.granted.contains(p));
            let any_declared = spec
                .runtime_perms
                .iter()
                .any(|&p| rt_info.declared.contains(p));
            let any_sys_fixed = spec
                .runtime_perms
                .iter()
                .any(|&p| rt_info.system_fixed.contains(p));
            let rg = if any_granted {
                Some(true)
            } else if any_declared {
                Some(false)
            } else {
                None
            };
            (rg, any_sys_fixed)
        };

        categories.push(CategoryPermLiveState {
            category: spec.id.to_string(),
            appops_mode: best_mode,
            last_access,
            running_now,
            runtime_granted,
            has_runtime_perms: spec.has_runtime_perms,
            supports_foreground: spec.supports_foreground,
            system_fixed,
        });
    }

    AppLivePermissions {
        package_name: package_name.to_string(),
        categories,
    }
}

/// Live per-app AppOps + runtime permission inspection for a specific Android `user_id`
/// (supports User 0, Work Profile u10+, Samsung Dual Messenger u95, Secure Folder u150).
pub fn inspect_app_permissions_for_user(package_name: &str, user_id: u32) -> AppLivePermissions {
    inspect_app_permissions_detailed(package_name, user_id).0
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum PermAction {
    Allow,
    Foreground,
    Ignore,
    Revoke,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum CmdOutcome {
    Ok,
    SkippedUndeclared,
    SystemFixed(String),
    Failed(String),
}

fn combined_cmd_output(out: &CmdOutput) -> String {
    let so = out.stdout.trim();
    let se = out.stderr.trim();
    match (so.is_empty(), se.is_empty()) {
        (true, true) => String::new(),
        (false, true) => so.to_string(),
        (true, false) => se.to_string(),
        (false, false) => format!("{} | {}", se, so),
    }
}

pub(crate) fn classify_pm_cmd_output(out: &CmdOutput) -> CmdOutcome {
    if out.timed_out {
        return CmdOutcome::Failed("pm command timed out".to_string());
    }
    let msg = combined_cmd_output(out);
    let low = msg.to_ascii_lowercase();

    if low.contains("system_fixed")
        || low.contains("fixed by policy")
        || low.contains("cannot revoke")
        || low.contains("non-revocable")
    {
        return CmdOutcome::SystemFixed(msg);
    }
    if low.contains("has not requested permission")
        || low.contains("not a changeable permission")
        || low.contains("unknown permission")
        || low.contains("does not request")
        || low.contains("is not a runtime permission")
    {
        return CmdOutcome::SkippedUndeclared;
    }

    let has_err_text = low.contains("exception")
        || low.contains("error:")
        || low.contains("failed")
        || low.contains("operation not allowed")
        || low.contains("unknown package");

    if out.success() && !has_err_text {
        CmdOutcome::Ok
    } else {
        let detail = if msg.is_empty() {
            format!("exit code {}", out.status_code)
        } else {
            msg
        };
        CmdOutcome::Failed(detail)
    }
}

pub(crate) fn classify_appops_cmd_output(out: &CmdOutput) -> CmdOutcome {
    if out.timed_out {
        return CmdOutcome::Failed("cmd appops timed out".to_string());
    }
    let msg = combined_cmd_output(out);
    let low = msg.to_ascii_lowercase();

    if low.contains("unknown operation") || low.contains("invalid op") {
        return CmdOutcome::SkippedUndeclared;
    }

    let has_err_text = low.contains("exception")
        || low.contains("error:")
        || low.contains("should not be set directly")
        || low.contains("cannot set")
        || low.contains("failed")
        || low.contains("unknown package")
        || low.contains("bad argument")
        || low.contains("no package");

    if out.success() && !has_err_text {
        CmdOutcome::Ok
    } else {
        let detail = if msg.is_empty() {
            format!("exit code {}", out.status_code)
        } else {
            msg
        };
        CmdOutcome::Failed(detail)
    }
}

fn target_declared_perms(
    spec: &PermCategorySpec,
    rt_info: &PackageRuntimePermInfo,
) -> Vec<&'static str> {
    if rt_info.user_section_found {
        spec.runtime_perms
            .iter()
            .copied()
            .filter(|&p| rt_info.declared.contains(p))
            .collect()
    } else {
        spec.runtime_perms.to_vec()
    }
}

fn clear_perm_flag_best_effort(
    cmd_bin: &str,
    user_str: &str,
    package_name: &str,
    perm: &str,
    flag: &str,
) -> bool {
    match run_cmd(
        cmd_bin,
        &[
            "package",
            "clear-permission-flags",
            "--user",
            user_str,
            package_name,
            perm,
            flag,
        ],
        None,
        2000,
    ) {
        Ok(out) => matches!(classify_pm_cmd_output(&out), CmdOutcome::Ok),
        Err(_) => false,
    }
}

fn set_perm_flag_best_effort(
    cmd_bin: &str,
    user_str: &str,
    package_name: &str,
    perm: &str,
    flag: &str,
) -> bool {
    match run_cmd(
        cmd_bin,
        &[
            "package",
            "set-permission-flags",
            "--user",
            user_str,
            package_name,
            perm,
            flag,
        ],
        None,
        2000,
    ) {
        Ok(out) => matches!(classify_pm_cmd_output(&out), CmdOutcome::Ok),
        Err(_) => false,
    }
}

fn run_appops_set(
    cmd_bin: &str,
    user_str: &str,
    package_name: &str,
    op_name: &str,
    mode: &str,
    uid_scoped: bool,
) -> CmdOutcome {
    let res = if uid_scoped {
        run_cmd(
            cmd_bin,
            &[
                "appops",
                "set",
                "--user",
                user_str,
                "--uid",
                package_name,
                op_name,
                mode,
            ],
            None,
            2500,
        )
    } else {
        run_cmd(
            cmd_bin,
            &["appops", "set", "--user", user_str, package_name, op_name, mode],
            None,
            2500,
        )
    };
    match res {
        Ok(out) => classify_appops_cmd_output(&out),
        Err(e) => CmdOutcome::Failed(e),
    }
}

/// Applies real per-app permission / AppOps action for user 0 (backward-compatible wrapper).
pub fn set_app_permission_action(
    package_name: &str,
    category: &str,
    action: &str,
) -> Result<AppLivePermissions, String> {
    set_app_permission_action_for_user(package_name, category, action, 0)
}

/// Applies real per-app permission / AppOps action for a specific Android `user_id`:
/// - `allow` (`default`): clears `user-fixed`/`revoked-compat`, resets UID+pkg AppOps to `default`,
///   and grants declared revoked runtime perms via `pm grant --user <u_id>`.
/// - `foreground`: rejects cleanly if `!spec.supports_foreground`; clears lock/compat flags and sets
///   UID AppOps to `foreground` (clearing conflicting pkg override to `default`).
/// - `ignore`: AppOps-only fake-empty (`--uid ignore` + pkg `default` + `revoked-compat` fallback);
///   NEVER calls `pm revoke` (blocks silently without killing running app).
/// - `revoke` (`deny`): revokes declared runtime perms via `pm revoke --user <u_id>` + sets
///   runtime ops to `--uid ignore` (with `revoked-compat` fallback) and non-runtime/special ops
///   to `deny` (with `ignore` fallback).
pub fn set_app_permission_action_for_user(
    package_name: &str,
    category: &str,
    action: &str,
    user_id: u32,
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
    let parsed_action = match clean_action.as_str() {
        "allow" | "default" => PermAction::Allow,
        "foreground" => PermAction::Foreground,
        "ignore" => PermAction::Ignore,
        "revoke" | "deny" => PermAction::Revoke,
        other => return Err(format!("Unsupported permission action: {}", other)),
    };

    if parsed_action == PermAction::Foreground && !spec.supports_foreground {
        return Err(format!(
            "Category '{}' does not support foreground-only mode on Android",
            spec.id
        ));
    }

    let rt_info = if spec.has_runtime_perms {
        query_package_runtime_perms(package_name, user_id)
    } else {
        PackageRuntimePermInfo::default()
    };
    let target_perms = target_declared_perms(spec, &rt_info);

    let pm = resolve_bin("pm");
    let cmd_bin = resolve_bin("cmd");
    let user_str = user_id.to_string();

    let mut ok_count = 0usize;
    let mut system_fixed_hit = false;
    let mut need_revoked_compat_fallback = false;
    let mut last_err = String::new();

    match parsed_action {
        PermAction::Allow => {
            // 1. Clear user-fixed & revoked-compat flags before resetting AppOps / granting
            for &perm in &target_perms {
                if !rt_info.user_section_found || rt_info.user_fixed.contains(perm) {
                    let _ = clear_perm_flag_best_effort(
                        &cmd_bin,
                        &user_str,
                        package_name,
                        perm,
                        "user-fixed",
                    );
                }
                if !rt_info.user_section_found || rt_info.revoked_compat.contains(perm) {
                    let _ = clear_perm_flag_best_effort(
                        &cmd_bin,
                        &user_str,
                        package_name,
                        perm,
                        "revoked-compat",
                    );
                }
            }

            // 2. Reset both UID and package AppOps to Android default (never "allow" on special ops!)
            for op in spec.appops {
                let primary = run_appops_set(
                    &cmd_bin,
                    &user_str,
                    package_name,
                    op.name,
                    "default",
                    op.uid_scoped,
                );
                let secondary = run_appops_set(
                    &cmd_bin,
                    &user_str,
                    package_name,
                    op.name,
                    "default",
                    !op.uid_scoped,
                );
                match primary {
                    CmdOutcome::Ok => ok_count += 1,
                    CmdOutcome::SkippedUndeclared => {}
                    CmdOutcome::Failed(e) | CmdOutcome::SystemFixed(e) => {
                        if matches!(secondary, CmdOutcome::Ok) {
                            ok_count += 1;
                        } else {
                            last_err = e;
                        }
                    }
                }
            }

            // 3. Re-grant declared runtime perms that are currently revoked
            for &perm in &target_perms {
                if rt_info.user_section_found && rt_info.granted.contains(perm) {
                    continue;
                }
                match run_cmd(
                    &pm,
                    &["grant", "--user", &user_str, package_name, perm],
                    None,
                    2000,
                ) {
                    Ok(out) => match classify_pm_cmd_output(&out) {
                        CmdOutcome::Ok => ok_count += 1,
                        CmdOutcome::SkippedUndeclared => {}
                        CmdOutcome::SystemFixed(e) => {
                            system_fixed_hit = true;
                            last_err = e;
                        }
                        CmdOutcome::Failed(e) => last_err = e,
                    },
                    Err(e) => last_err = e,
                }
            }
        }

        PermAction::Foreground => {
            for &perm in &target_perms {
                if !rt_info.user_section_found || rt_info.user_fixed.contains(perm) {
                    let _ = clear_perm_flag_best_effort(
                        &cmd_bin,
                        &user_str,
                        package_name,
                        perm,
                        "user-fixed",
                    );
                }
                if !rt_info.user_section_found || rt_info.revoked_compat.contains(perm) {
                    let _ = clear_perm_flag_best_effort(
                        &cmd_bin,
                        &user_str,
                        package_name,
                        perm,
                        "revoked-compat",
                    );
                }
            }

            for op in spec.appops {
                if op.uid_scoped {
                    match run_appops_set(
                        &cmd_bin,
                        &user_str,
                        package_name,
                        op.name,
                        "foreground",
                        true,
                    ) {
                        CmdOutcome::Ok => {
                            ok_count += 1;
                            // Clear any conflicting package override so UID foreground governs cleanly
                            let _ = run_appops_set(
                                &cmd_bin,
                                &user_str,
                                package_name,
                                op.name,
                                "default",
                                false,
                            );
                        }
                        CmdOutcome::SkippedUndeclared => {}
                        CmdOutcome::Failed(e) | CmdOutcome::SystemFixed(e) => {
                            if matches!(
                                run_appops_set(
                                    &cmd_bin,
                                    &user_str,
                                    package_name,
                                    op.name,
                                    "foreground",
                                    false
                                ),
                                CmdOutcome::Ok
                            ) {
                                ok_count += 1;
                            } else {
                                last_err = e;
                            }
                        }
                    }
                } else {
                    match run_appops_set(
                        &cmd_bin,
                        &user_str,
                        package_name,
                        op.name,
                        "foreground",
                        false,
                    ) {
                        CmdOutcome::Ok => ok_count += 1,
                        CmdOutcome::SkippedUndeclared => {}
                        CmdOutcome::Failed(e) | CmdOutcome::SystemFixed(e) => last_err = e,
                    }
                }
            }
        }

        PermAction::Ignore => {
            // AppOps-only: NEVER call `pm revoke`!
            for op in spec.appops {
                if op.uid_scoped {
                    match run_appops_set(&cmd_bin, &user_str, package_name, op.name, "ignore", true)
                    {
                        CmdOutcome::Ok => {
                            ok_count += 1;
                            let _ = run_appops_set(
                                &cmd_bin,
                                &user_str,
                                package_name,
                                op.name,
                                "default",
                                false,
                            );
                        }
                        CmdOutcome::SkippedUndeclared => {}
                        CmdOutcome::Failed(e) | CmdOutcome::SystemFixed(e) => {
                            need_revoked_compat_fallback = true;
                            if matches!(
                                run_appops_set(
                                    &cmd_bin,
                                    &user_str,
                                    package_name,
                                    op.name,
                                    "ignore",
                                    false
                                ),
                                CmdOutcome::Ok
                            ) {
                                ok_count += 1;
                            } else {
                                last_err = e;
                            }
                        }
                    }
                } else {
                    match run_appops_set(
                        &cmd_bin,
                        &user_str,
                        package_name,
                        op.name,
                        "ignore",
                        false,
                    ) {
                        CmdOutcome::Ok => ok_count += 1,
                        CmdOutcome::SkippedUndeclared => {}
                        CmdOutcome::Failed(e) | CmdOutcome::SystemFixed(e) => last_err = e,
                    }
                }
            }

            if need_revoked_compat_fallback && spec.has_runtime_perms {
                for &perm in &target_perms {
                    if set_perm_flag_best_effort(
                        &cmd_bin,
                        &user_str,
                        package_name,
                        perm,
                        "revoked-compat",
                    ) {
                        ok_count += 1;
                    }
                }
            }
        }

        PermAction::Revoke => {
            // 1. Revoke declared runtime perms (skipping already-revoked perms to avoid needless killUid)
            if spec.has_runtime_perms {
                for &perm in &target_perms {
                    if rt_info.user_section_found && !rt_info.granted.contains(perm) {
                        ok_count += 1;
                        continue;
                    }
                    match run_cmd(
                        &pm,
                        &["revoke", "--user", &user_str, package_name, perm],
                        None,
                        2000,
                    ) {
                        Ok(out) => match classify_pm_cmd_output(&out) {
                            CmdOutcome::Ok => ok_count += 1,
                            CmdOutcome::SkippedUndeclared => {}
                            CmdOutcome::SystemFixed(e) => {
                                system_fixed_hit = true;
                                last_err = e;
                            }
                            CmdOutcome::Failed(e) => last_err = e,
                        },
                        Err(e) => last_err = e,
                    }
                }
            }

            // 2. Enforce AppOps: "ignore" for runtime-backed ops, "deny" (with "ignore" fallback)
            //    for non-runtime / special ops
            let (appops_ok, appops_need_compat, appops_err) =
                enforce_category_appops_blocked(&cmd_bin, &user_str, package_name, spec, true);
            ok_count += appops_ok;
            need_revoked_compat_fallback |= appops_need_compat;
            if !appops_err.is_empty() && last_err.is_empty() {
                last_err = appops_err;
            }

            if need_revoked_compat_fallback && spec.has_runtime_perms {
                for &perm in &target_perms {
                    if set_perm_flag_best_effort(
                        &cmd_bin,
                        &user_str,
                        package_name,
                        perm,
                        "revoked-compat",
                    ) {
                        ok_count += 1;
                    }
                }
            }
        }
    }

    if ok_count == 0 {
        let detail = if last_err.is_empty() {
            "all pm/appops commands failed"
        } else {
            &last_err
        };
        if system_fixed_hit {
            return Err(format!(
                "Category '{}' on {} is SYSTEM_FIXED by Android and overrides failed: {}",
                spec.id, package_name, detail
            ));
        }
        return Err(format!(
            "Failed to apply '{}' on {} ({}): {}",
            clean_action, package_name, spec.id, detail
        ));
    }

    Ok(inspect_app_permissions_for_user(package_name, user_id))
}

/// Shared AppOps-only blocking helper used by both `Revoke` and `PermissionEnforcer`
/// (`NeedsAppOpsOnlyRepair` — repairs AppOps WITHOUT calling `pm revoke`!).
/// Returns `(ok_count, need_revoked_compat_fallback, last_err)`.
fn enforce_category_appops_blocked(
    cmd_bin: &str,
    user_str: &str,
    package_name: &str,
    spec: &PermCategorySpec,
    use_deny_for_non_runtime: bool,
) -> (usize, bool, String) {
    let mut ok_count = 0usize;
    let mut need_compat = false;
    let mut last_err = String::new();

    for op in spec.appops {
        let target_mode = if use_deny_for_non_runtime && (!spec.has_runtime_perms || op.is_special)
        {
            "deny"
        } else {
            "ignore"
        };

        if op.uid_scoped {
            match run_appops_set(cmd_bin, user_str, package_name, op.name, target_mode, true) {
                CmdOutcome::Ok => {
                    ok_count += 1;
                    let _ =
                        run_appops_set(cmd_bin, user_str, package_name, op.name, "default", false);
                }
                CmdOutcome::SkippedUndeclared => {}
                CmdOutcome::Failed(e) | CmdOutcome::SystemFixed(e) => {
                    let mut recovered = false;
                    if target_mode == "deny"
                        && matches!(
                            run_appops_set(cmd_bin, user_str, package_name, op.name, "ignore", true),
                            CmdOutcome::Ok
                        )
                    {
                        let _ = run_appops_set(
                            cmd_bin,
                            user_str,
                            package_name,
                            op.name,
                            "default",
                            false,
                        );
                        ok_count += 1;
                        recovered = true;
                    }
                    if !recovered {
                        if spec.has_runtime_perms {
                            need_compat = true;
                        }
                        if matches!(
                            run_appops_set(
                                cmd_bin,
                                user_str,
                                package_name,
                                op.name,
                                target_mode,
                                false
                            ),
                            CmdOutcome::Ok
                        ) || (target_mode == "deny"
                            && matches!(
                                run_appops_set(
                                    cmd_bin,
                                    user_str,
                                    package_name,
                                    op.name,
                                    "ignore",
                                    false
                                ),
                                CmdOutcome::Ok
                            ))
                        {
                            ok_count += 1;
                        } else {
                            last_err = e;
                        }
                    }
                }
            }
        } else {
            match run_appops_set(cmd_bin, user_str, package_name, op.name, target_mode, false) {
                CmdOutcome::Ok => ok_count += 1,
                CmdOutcome::SkippedUndeclared => {}
                CmdOutcome::Failed(e) | CmdOutcome::SystemFixed(e) => {
                    if target_mode == "deny"
                        && matches!(
                            run_appops_set(cmd_bin, user_str, package_name, op.name, "ignore", false),
                            CmdOutcome::Ok
                        )
                    {
                        ok_count += 1;
                    } else {
                        last_err = e;
                    }
                }
            }
        }
    }

    (ok_count, need_compat, last_err)
}

/// Applies Android `user-set` + `user-fixed` flags to all declared runtime permissions in `category`
/// so Android's PermissionController auto-denies in-app permission dialogs natively (`Kunci Blokir`).
/// Best-effort; returns count of permissions whose flags were updated.
pub fn apply_lock_flags_for_category(package_name: &str, category: &str, user_id: u32) -> usize {
    if !validate_package_name(package_name) || is_protected_package(package_name) {
        return 0;
    }
    let Some(spec) = find_category(category) else {
        return 0;
    };
    if !spec.has_runtime_perms {
        return 0;
    }
    let rt_info = query_package_runtime_perms(package_name, user_id);
    let perms = target_declared_perms(spec, &rt_info);
    if perms.is_empty() {
        return 0;
    }

    let pm = resolve_bin("pm");
    let cmd_bin = resolve_bin("cmd");
    let user_str = user_id.to_string();
    let mut updated = 0usize;

    for perm in perms {
        if rt_info.user_section_found && rt_info.system_fixed.contains(perm) {
            continue;
        }
        // Try multi-flag AOSP syntax first, fall back to single-flag calls if OEM rejects multi-flag.
        let combined_ok = run_cmd(
            &pm,
            &[
                "set-permission-flags",
                "--user",
                &user_str,
                package_name,
                perm,
                "user-set",
                "user-fixed",
            ],
            None,
            2000,
        )
        .map(|o| matches!(classify_pm_cmd_output(&o), CmdOutcome::Ok))
        .unwrap_or(false);

        if combined_ok {
            updated += 1;
        } else {
            let s1 = set_perm_flag_best_effort(&cmd_bin, &user_str, package_name, perm, "user-set");
            let s2 =
                set_perm_flag_best_effort(&cmd_bin, &user_str, package_name, perm, "user-fixed");
            if s1 || s2 {
                updated += 1;
            }
        }
    }
    updated
}

/// Clears Android `user-fixed` permission flag for all declared runtime permissions in `category`
/// when `Kunci Blokir` is removed or user manually overrides the category. Best-effort.
pub fn clear_lock_flags_for_category(package_name: &str, category: &str, user_id: u32) -> usize {
    if !validate_package_name(package_name) || is_protected_package(package_name) {
        return 0;
    }
    let Some(spec) = find_category(category) else {
        return 0;
    };
    if !spec.has_runtime_perms {
        return 0;
    }
    let rt_info = query_package_runtime_perms(package_name, user_id);
    let perms = target_declared_perms(spec, &rt_info);
    let pm = resolve_bin("pm");
    let cmd_bin = resolve_bin("cmd");
    let user_str = user_id.to_string();
    let mut cleared = 0usize;

    for perm in perms {
        if rt_info.user_section_found && !rt_info.user_fixed.contains(perm) {
            continue;
        }
        let pm_ok = run_cmd(
            &pm,
            &[
                "clear-permission-flags",
                "--user",
                &user_str,
                package_name,
                perm,
                "user-fixed",
            ],
            None,
            2000,
        )
        .map(|out| matches!(classify_pm_cmd_output(&out), CmdOutcome::Ok))
        .unwrap_or(false);

        if pm_ok || clear_perm_flag_best_effort(&cmd_bin, &user_str, package_name, perm, "user-fixed")
        {
            cleared += 1;
        }
    }
    cleared
}

/// Multi-user bulk revoke deriving each package's Android user ID from `pkg_to_uid`.
pub fn bulk_revoke_category_with_uids(
    category: &str,
    packages: &[String],
    pkg_to_uid: &HashMap<String, u32>,
) -> Result<usize, String> {
    if !is_valid_perm_category(category) {
        return Err(format!("Invalid permission category: {}", category));
    }
    let mut count = 0usize;
    for pkg in packages {
        if !validate_package_name(pkg) || is_protected_package(pkg) {
            continue;
        }
        let user_id = resolve_pkg_user_id(pkg, pkg_to_uid);
        if set_app_permission_action_for_user(pkg, category, "revoke", user_id).is_ok() {
            count += 1;
        }
    }
    Ok(count)
}

/// Queries real-time Privacy Access Timeline via bounded parallel `dumpsys appops --op <OP>` calls
/// (max 4 concurrent workers via `std::thread::scope`, avoiding full multi-MB dumpsys truncation).
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
        "READ_MEDIA_VISUAL_USER_SELECTED",
        "BODY_SENSORS",
    ];

    let next_idx = AtomicUsize::new(0);
    let worker_count = 4usize.min(ops_to_audit.len());
    let per_worker: Vec<Vec<PrivacyAccessEvent>> = thread::scope(|s| {
        let mut handles = Vec::with_capacity(worker_count);
        for _ in 0..worker_count {
            let idx_ref = &next_idx;
            let dumpsys_ref = dumpsys.as_str();
            handles.push(s.spawn(move || {
                let mut local = Vec::new();
                loop {
                    let i = idx_ref.fetch_add(1, Ordering::Relaxed);
                    if i >= ops_to_audit.len() {
                        break;
                    }
                    let op = ops_to_audit[i];
                    if let Ok(out) = run_cmd(dumpsys_ref, &["appops", "--op", op], None, 2000) {
                        if out.success() {
                            parse_dumpsys_appops_op(&out.stdout, op, uid_map, &mut local);
                        }
                    }
                }
                local
            }));
        }
        handles
            .into_iter()
            .map(|h| h.join().unwrap_or_default())
            .collect()
    });

    let mut all_events: Vec<PrivacyAccessEvent> = per_worker.into_iter().flatten().collect();

    // Sort: currently running FIRST, then most recent access age ascending, deterministic tie-break
    all_events.sort_by(|a, b| {
        b.running_now
            .cmp(&a.running_now)
            .then_with(|| a.age_secs.cmp(&b.age_secs))
            .then_with(|| a.package_name.cmp(&b.package_name))
            .then_with(|| a.category.cmp(&b.category))
    });

    // Deduplicate by (package_name, category) keeping the freshest / running event
    let mut seen = HashSet::new();
    all_events.retain(|e| seen.insert((e.package_name.clone(), e.category.clone())));
    all_events.truncate(limit.clamp(10, 200));
    all_events
}

fn matches_op_header(trimmed_line: &str, op_name: &str) -> bool {
    if !trimmed_line.ends_with(':') {
        return false;
    }
    let Some(rest) = trimmed_line.strip_prefix(op_name) else {
        return false;
    };
    matches!(rest.as_bytes().first(), Some(b' ') | Some(b'(') | Some(b':'))
}

fn is_any_op_header(trimmed_line: &str) -> bool {
    if !trimmed_line.ends_with(':') {
        return false;
    }
    let token = trimmed_line.split([' ', '(', ':']).next().unwrap_or("");
    token.len() >= 3
        && token
            .bytes()
            .all(|b| b.is_ascii_uppercase() || b.is_ascii_digit() || b == b'_')
}

/// Extracts the strictest effective mode from a `dumpsys appops` op header such as
/// `CAMERA (allow):`, `CAMERA (allow/ignore):`, `CAMERA (allow / switch ignore):`,
/// or `RECORD_AUDIO (default / switch errored):`.
fn parse_dumpsys_op_mode(header_line: &str) -> String {
    let Some(l) = header_line.find('(') else {
        return "allow".to_string();
    };
    let Some(r_rel) = header_line[l + 1..].find(')') else {
        return "allow".to_string();
    };
    let inside = &header_line[l + 1..l + 1 + r_rel];
    let mut best_mode: Option<&'static str> = None;
    let mut best_rank = 0u8;
    for segment in inside.split('/') {
        for token in segment.split_whitespace() {
            if let Some(norm) = normalize_appops_mode(token) {
                let rank = op_strictness_rank(norm);
                if rank > best_rank {
                    best_rank = rank;
                    best_mode = Some(norm);
                }
            }
        }
    }
    best_mode.unwrap_or("allow").to_string()
}

/// Parses `dumpsys appops --op <OP>` text output across Android 12-16 / One UI 7.
/// - Enforces exact op-name token boundaries (`CAMERA` does not match `CAMERA_SANDBOXED`).
/// - Parses `(uid/pkg)` tuples & UID-level op headers via strictness precedence.
/// - Excludes protected UIDs & protected packages (`is_protected_package`).
/// - Scans ALL `Access:` lines under the matching op, keeping `(running)` or freshest timestamp.
pub fn parse_dumpsys_appops_op(
    dump: &str,
    op_name: &str,
    uid_map: &HashMap<u32, String>,
    out: &mut Vec<PrivacyAccessEvent>,
) {
    let category = category_for_op(op_name).unwrap_or("background").to_string();
    let mut current_uid: u32 = 0;
    let mut current_pkg: Option<String> = None;
    let mut in_package_block = false;
    let mut current_uid_op_mode: Option<String> = None;
    let mut current_mode = "allow".to_string();
    let mut in_matching_op = false;
    let mut best_event: Option<PrivacyAccessEvent> = None;

    let flush_best = |best: &mut Option<PrivacyAccessEvent>, dst: &mut Vec<PrivacyAccessEvent>| {
        if let Some(ev) = best.take() {
            dst.push(ev);
        }
    };

    for raw_line in dump.lines() {
        let t = raw_line.trim();
        if t.is_empty() {
            continue;
        }

        if let Some(rest) = t.strip_prefix("Uid ") {
            flush_best(&mut best_event, out);
            let token = rest.trim_end_matches(':').trim();
            current_uid = parse_android_uid_token(token).unwrap_or(0);
            current_pkg = uid_map.get(&current_uid).cloned();
            in_package_block = false;
            current_uid_op_mode = None;
            in_matching_op = false;
            continue;
        }

        if let Some(rest) = t.strip_prefix("Package ") {
            flush_best(&mut best_event, out);
            let pkg = rest.trim_end_matches(':').trim().to_string();
            current_pkg = if validate_package_name(&pkg) {
                Some(pkg)
            } else {
                None
            };
            in_package_block = true;
            in_matching_op = false;
            continue;
        }

        // Exact op-name token boundary check (prevents CAMERA matching CAMERA_SANDBOXED)
        if matches_op_header(t, op_name) {
            let parsed_mode = parse_dumpsys_op_mode(t);
            if !in_package_block {
                // UID-level op mode printed under `Uid u0aXXX:` before `Package ...:`
                current_uid_op_mode = Some(parsed_mode);
                in_matching_op = false;
            } else {
                flush_best(&mut best_event, out);
                current_mode = combine_uid_and_pkg_mode(
                    current_uid_op_mode.as_deref(),
                    Some(parsed_mode.as_str()),
                );
                in_matching_op = true;
            }
            continue;
        }

        // Stop if another uppercase op header starts (e.g. CAMERA_SANDBOXED (allow):)
        if in_matching_op && is_any_op_header(t) {
            flush_best(&mut best_event, out);
            in_matching_op = false;
            continue;
        }

        // Ignore Reject: lines; scan ALL Access: lines under the matching op
        let is_access_line = t.starts_with("Access:")
            || (t.contains("time=+") && !t.starts_with("Reject:") && !t.contains("rejectTime=+"));

        if in_matching_op && is_access_line {
            let Some(ref pkg) = current_pkg else {
                continue;
            };
            if is_protected_uid(current_uid) || is_protected_package(pkg) {
                continue;
            }
            let running_now = t.contains("(running)") || t.contains("running=true");
            let (age_secs, age_label) = parse_relative_age_from_access_line(t);

            // Only include events from the last 7 days (or running now)
            if !running_now && age_secs > 7 * 86_400 {
                continue;
            }

            let is_better = match &best_event {
                None => true,
                Some(prev) => {
                    (running_now && !prev.running_now)
                        || (running_now == prev.running_now && age_secs < prev.age_secs)
                }
            };
            if is_better {
                best_event = Some(PrivacyAccessEvent {
                    uid: current_uid,
                    package_name: pkg.clone(),
                    category: category.clone(),
                    op: op_name.to_string(),
                    mode: current_mode.clone(),
                    last_access_label: age_label,
                    age_secs,
                    running_now,
                });
            }
        }
    }

    flush_best(&mut best_event, out);
}

fn parse_android_uid_token(token: &str) -> Option<u32> {
    if let Ok(n) = token.parse::<u32>() {
        return Some(n);
    }
    // Handle `u0a277` -> 10277, `u10a42` -> 1_010_042, `u95a100` -> 9_510_100, `u150a55` -> 15_010_055
    if let Some(rest) = token.strip_prefix('u') {
        if let Some((user_str, app_str)) = rest.split_once('a') {
            let user = user_str.parse::<u32>().ok()?;
            let app = app_str.parse::<u32>().ok()?;
            return user
                .checked_mul(100_000)?
                .checked_add(10_000)?
                .checked_add(app);
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
            num = num
                .saturating_mul(10)
                .saturating_add((bytes[i] - b'0') as u64);
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

/// Shared-UID-safe foreground check: resolves `pkg` via `pkg_to_uid` so sibling packages
/// sharing the same UID are all recognized as foreground while their UID has `oom_score_adj == 0`.
pub fn is_pkg_in_top_fg(
    pkg: &str,
    screen_on: bool,
    top_fg_uids: &HashSet<u32>,
    pkg_to_uid: &HashMap<String, u32>,
) -> bool {
    if !screen_on {
        return false;
    }
    pkg_to_uid
        .get(pkg)
        .map(|uid| top_fg_uids.contains(uid))
        .unwrap_or(false)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DriftAction {
    /// Runtime perm is not granted AND every present op is effectively `ignore`/`deny`
    /// (with no permissive UID mode). Do nothing — zero `pm revoke`, zero force-stop loop!
    AlreadyEnforced,
    /// Runtime perm is granted (and revocable) — run full `revoke` (`pm revoke` + AppOps).
    NeedsFullRevoke,
    /// Runtime perm is already revoked (or AppOps-only / `SYSTEM_FIXED`) and only AppOps drifted —
    /// repair AppOps without calling `pm revoke`.
    NeedsAppOpsOnlyRepair,
}

/// Pure drift decision engine for `PermissionEnforcer`.
/// Inspects BOTH live `rt_info` (`runtime_granted` + `system_fixed`) AND per-op `detailed_ops`
/// (both `effective_mode` and `uid_mode` for `uid_scoped` ops).
pub fn evaluate_category_drift(
    spec: &PermCategorySpec,
    detailed_ops: &HashMap<String, ParsedAppOpEntry>,
    rt_info: &PackageRuntimePermInfo,
    appops_query_ok: bool,
    fail_closed_if_unknown: bool,
) -> DriftAction {
    // 1. Check live runtime permission grant state first
    if spec.has_runtime_perms && rt_info.user_section_found {
        let any_declared = spec
            .runtime_perms
            .iter()
            .any(|&p| rt_info.declared.contains(p));
        let any_granted = spec
            .runtime_perms
            .iter()
            .any(|&p| rt_info.granted.contains(p));

        if any_granted {
            let all_granted_system_fixed = spec
                .runtime_perms
                .iter()
                .filter(|&&p| rt_info.granted.contains(p))
                .all(|&p| rt_info.system_fixed.contains(p));
            if !all_granted_system_fixed {
                return DriftAction::NeedsFullRevoke;
            }
            // All granted perms are SYSTEM_FIXED: cannot pm revoke, verify AppOps only
        } else if !any_declared {
            // App declares zero runtime perms in this category; check only if any special/appop is actively allowed
            let any_op_exposed = spec.appops.iter().any(|op| {
                detailed_ops
                    .get(op.name)
                    .map(|e| matches!(e.effective_mode.as_str(), "allow" | "foreground"))
                    .unwrap_or(false)
            });
            return if any_op_exposed {
                DriftAction::NeedsAppOpsOnlyRepair
            } else {
                DriftAction::AlreadyEnforced
            };
        }
    }

    // 2. If both probes failed transiently, avoid false-positive thrash unless fail-closed transition
    if !appops_query_ok && !rt_info.user_section_found {
        return if fail_closed_if_unknown {
            if spec.has_runtime_perms {
                DriftAction::NeedsFullRevoke
            } else {
                DriftAction::NeedsAppOpsOnlyRepair
            }
        } else {
            DriftAction::AlreadyEnforced
        };
    }

    // 3. Evaluate per-op AppOps state (fail-closed across all ops in category)
    let mut any_op_present = false;
    let mut any_op_drifted = false;
    for op in spec.appops {
        if let Some(entry) = detailed_ops.get(op.name) {
            any_op_present = true;
            let effective_blocked = matches!(entry.effective_mode.as_str(), "ignore" | "deny");
            let uid_permissive = op.uid_scoped
                && matches!(entry.uid_mode.as_deref(), Some("allow") | Some("foreground"));
            if !effective_blocked || uid_permissive {
                any_op_drifted = true;
                break;
            }
        }
    }

    if any_op_drifted {
        if spec.has_runtime_perms && !rt_info.user_section_found {
            return DriftAction::NeedsFullRevoke;
        }
        return DriftAction::NeedsAppOpsOnlyRepair;
    }

    // 4. If AppOps query succeeded but NO op for this category appeared at all:
    //    - For AppOps-only categories (clipboard/background), absent means Android default (allow) -> repair!
    //    - For runtime categories where runtime perms are confirmed revoked, absent op means default -> repair AppOps only!
    if appops_query_ok && !any_op_present {
        if !spec.has_runtime_perms || rt_info.user_section_found {
            return DriftAction::NeedsAppOpsOnlyRepair;
        }
        if fail_closed_if_unknown {
            return DriftAction::NeedsFullRevoke;
        }
    }

    DriftAction::AlreadyEnforced
}

/// Lock-free atomic + low-contention metrics shared between the dedicated `PermissionEnforcer`
/// thread and `DaemonStatus` (zero `state_mtx` contention!).
#[derive(Debug, Default)]
pub struct EnforcerMetrics {
    actions_count: AtomicU64,
    last_event: Mutex<Option<String>>,
}

impl EnforcerMetrics {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn record(&self, event: String) {
        self.actions_count.fetch_add(1, Ordering::Relaxed);
        if let Ok(mut guard) = self.last_event.lock() {
            *guard = Some(event);
        }
    }

    pub fn actions_count(&self) -> u64 {
        self.actions_count.load(Ordering::Relaxed)
    }

    pub fn last_event(&self) -> Option<String> {
        self.last_event.lock().ok().and_then(|g| g.clone())
    }
}

/// Lightweight Android permission & AppOps watcher wrapping canonical `crate::sys::InotifyWatcher`.
/// Gracefully returns `None` on non-Android/host environments where none of the Android dirs exist.
struct PermStateWatcher {
    inner: InotifyWatcher,
    watched_paths: HashSet<String>,
}

impl PermStateWatcher {
    fn new() -> Option<Self> {
        let inner = InotifyWatcher::new().ok()?;
        let mut watcher = Self {
            inner,
            watched_paths: HashSet::new(),
        };
        watcher.add_dir("/data/misc_de/0/apexdata/com.android.permission");
        watcher.add_dir("/data/system/users/0");
        watcher.add_dir("/data/system");

        if watcher.inner.watch_count() == 0 {
            return None;
        }
        Some(watcher)
    }

    fn add_dir(&mut self, path: &str) {
        if self.watched_paths.contains(path) || !Path::new(path).is_dir() {
            return;
        }
        if self
            .inner
            .add_watch(Path::new(path), INOTIFY_PERM_WATCH_MASK)
            .is_ok()
        {
            self.watched_paths.insert(path.to_string());
        }
    }

    fn ensure_user_watches(&mut self, user_ids: &[u32]) {
        for &uid in user_ids {
            if uid == 0 {
                continue;
            }
            let apex = format!("/data/misc_de/{}/apexdata/com.android.permission", uid);
            let legacy = format!("/data/system/users/{}", uid);
            self.add_dir(&apex);
            self.add_dir(&legacy);
        }
    }

    fn is_relevant_names(names: &[String]) -> bool {
        if names.is_empty() {
            return true;
        }
        names.iter().any(|n| {
            let low = n.to_ascii_lowercase();
            low.contains("appops") || low.contains("permission")
        })
    }

    fn wait_relevant(&self, timeout_ms: u64) -> bool {
        match self.inner.wait_events(timeout_ms) {
            Ok(Some(names)) => Self::is_relevant_names(&names),
            _ => false,
        }
    }

    fn drain(&self) {
        let _ = self.inner.wait_events(0);
    }
}

/// Real-time Daemon Permission Enforcer:
/// - **Auto-Revoke on Background (`auto_revoke_on_bg`)**: revokes configured categories whenever
///   a package leaves top-visible foreground (`oom_score_adj == 0`, shared-UID safe) or screen turns off.
/// - **Strict Lock (`locked_deny`)**: event-driven (`inotify`) + 1.5s active-guard foreground watcher
///   with `user-fixed` proactive lock flags and zero false-positive force-stop loops.
#[derive(Debug, Default)]
pub struct PermissionEnforcer {
    was_foreground: HashSet<String>,
    last_strict_sweep_boottime: u64,
}

impl PermissionEnforcer {
    pub fn new() -> Self {
        Self::default()
    }

    /// Runs one lock-free enforcement cycle using shared-UID-safe `pkg_to_uid` and
    /// top-visible foreground UIDs (`oom_score_adj == 0`). Returns human-readable event strings
    /// for every enforcement action actually performed.
    pub fn tick_with_index(
        &mut self,
        cfg: &ScopesConfig,
        screen_on: bool,
        top_fg_uids: &HashSet<u32>,
        pkg_to_uid: &HashMap<String, u32>,
        inotify_triggered: bool,
    ) -> Vec<String> {
        let mut events = Vec::new();
        if cfg.guards.is_empty() {
            self.was_foreground.clear();
            return events;
        }
        self.was_foreground.retain(|p| cfg.guards.contains_key(p));

        let now = boottime_secs();
        let do_periodic_sweep = now.saturating_sub(self.last_strict_sweep_boottime) >= 15;
        if do_periodic_sweep {
            self.last_strict_sweep_boottime = now;
        }

        let cmd_bin = resolve_bin("cmd");

        for (pkg, guard) in &cfg.guards {
            if !validate_package_name(pkg) || is_protected_package(pkg) {
                continue;
            }

            let is_fg = is_pkg_in_top_fg(pkg, screen_on, top_fg_uids, pkg_to_uid);
            let was_fg = self.was_foreground.contains(pkg);
            let just_left_fg = was_fg && !is_fg;
            let just_entered_fg = !was_fg && is_fg;

            if is_fg {
                self.was_foreground.insert(pkg.clone());
            } else {
                self.was_foreground.remove(pkg);
            }

            let check_auto = !guard.auto_revoke_on_bg.is_empty()
                && !is_fg
                && (just_left_fg || do_periodic_sweep);
            let check_locked = !guard.locked_deny.is_empty()
                && (inotify_triggered || is_fg || just_entered_fg || do_periodic_sweep);

            if !check_auto && !check_locked {
                continue;
            }

            // Single detailed inspection per package per tick (never double-inspect!)
            let user_id = resolve_pkg_user_id(pkg, pkg_to_uid);
            let user_str = user_id.to_string();
            let (_live, detailed_ops, rt_info, appops_ok) =
                inspect_app_permissions_detailed(pkg, user_id);

            // 1. Auto-Revoke on Background / Screen-Off
            if check_auto {
                for cat in &guard.auto_revoke_on_bg {
                    let Some(spec) = find_category(cat) else {
                        continue;
                    };
                    match evaluate_category_drift(
                        spec,
                        &detailed_ops,
                        &rt_info,
                        appops_ok,
                        just_left_fg,
                    ) {
                        DriftAction::AlreadyEnforced => {}
                        DriftAction::NeedsFullRevoke => {
                            if set_app_permission_action_for_user(pkg, cat, "revoke", user_id)
                                .is_ok()
                            {
                                events.push(format!("Auto-revoked {} on {}", cat, pkg));
                            }
                        }
                        DriftAction::NeedsAppOpsOnlyRepair => {
                            let (ok, _, _) = enforce_category_appops_blocked(
                                &cmd_bin, &user_str, pkg, spec, true,
                            );
                            if ok > 0 {
                                events.push(format!("Auto-revoked {} AppOps on {}", cat, pkg));
                            }
                        }
                    }
                }
            }

            // 2. Strict Lock (`locked_deny`) — fast event-driven / active-guard re-enforcement
            if check_locked {
                for cat in &guard.locked_deny {
                    let Some(spec) = find_category(cat) else {
                        continue;
                    };
                    match evaluate_category_drift(spec, &detailed_ops, &rt_info, appops_ok, false) {
                        DriftAction::AlreadyEnforced => {}
                        DriftAction::NeedsFullRevoke => {
                            if set_app_permission_action_for_user(pkg, cat, "revoke", user_id)
                                .is_ok()
                            {
                                let _ = apply_lock_flags_for_category(pkg, cat, user_id);
                                events.push(format!("Re-locked {} on {}", cat, pkg));
                            }
                        }
                        DriftAction::NeedsAppOpsOnlyRepair => {
                            let (ok, need_compat, _) = enforce_category_appops_blocked(
                                &cmd_bin, &user_str, pkg, spec, true,
                            );
                            if need_compat && spec.has_runtime_perms {
                                for perm in target_declared_perms(spec, &rt_info) {
                                    let _ = set_perm_flag_best_effort(
                                        &cmd_bin,
                                        &user_str,
                                        pkg,
                                        perm,
                                        "revoked-compat",
                                    );
                                }
                            }
                            let _ = apply_lock_flags_for_category(pkg, cat, user_id);
                            if ok > 0 {
                                events.push(format!("Re-locked {} AppOps on {}", cat, pkg));
                            }
                        }
                    }
                }
            }
        }

        events
    }
}

/// Startup / config-import async enforcement helper for all configured `scopes.guards.locked_deny`.
/// Runs completely outside `state_mtx`, enforcing only drifted categories + ensuring `user-fixed`
/// flags without triggering unnecessary force-stops on already-revoked apps.
pub fn enforce_locked_guards_once(
    cfg: &ScopesConfig,
    pkg_to_uid: &HashMap<String, u32>,
) -> Vec<String> {
    let mut events = Vec::new();
    let cmd_bin = resolve_bin("cmd");

    for (pkg, guard) in &cfg.guards {
        if guard.locked_deny.is_empty()
            || !validate_package_name(pkg)
            || is_protected_package(pkg)
        {
            continue;
        }
        let user_id = resolve_pkg_user_id(pkg, pkg_to_uid);
        let user_str = user_id.to_string();
        let (_live, detailed_ops, rt_info, appops_ok) =
            inspect_app_permissions_detailed(pkg, user_id);

        for cat in &guard.locked_deny {
            let Some(spec) = find_category(cat) else {
                continue;
            };
            match evaluate_category_drift(spec, &detailed_ops, &rt_info, appops_ok, false) {
                DriftAction::AlreadyEnforced => {
                    // Best-effort ensure user-fixed flag on startup if missing
                    if spec.has_runtime_perms && rt_info.user_section_found {
                        let missing_user_fixed = target_declared_perms(spec, &rt_info)
                            .into_iter()
                            .any(|p| {
                                !rt_info.system_fixed.contains(p) && !rt_info.user_fixed.contains(p)
                            });
                        if missing_user_fixed {
                            let _ = apply_lock_flags_for_category(pkg, cat, user_id);
                        }
                    }
                }
                DriftAction::NeedsFullRevoke => {
                    if set_app_permission_action_for_user(pkg, cat, "revoke", user_id).is_ok() {
                        let _ = apply_lock_flags_for_category(pkg, cat, user_id);
                        events.push(format!("Startup locked {} on {}", cat, pkg));
                    }
                }
                DriftAction::NeedsAppOpsOnlyRepair => {
                    let (ok, _, _) =
                        enforce_category_appops_blocked(&cmd_bin, &user_str, pkg, spec, true);
                    let _ = apply_lock_flags_for_category(pkg, cat, user_id);
                    if ok > 0 {
                        events.push(format!("Startup locked {} AppOps on {}", cat, pkg));
                    }
                }
            }
        }
    }
    events
}

/// Dedicated event-driven (`inotify`) + fast active-guard (1.5s when screen on) PermissionEnforcer
/// thread loop. NEVER holds `state_mtx` across any subprocess — calls `snapshot_fn()` briefly
/// (<1ms) to fetch `(ScopesConfig, screen_on, pkg_to_uid)`, and responds immediately to
/// `enforcer_notify` from `SetAppPermGuard` / `ImportConfigJson` / `sync_all_subsystems`.
pub fn run_permission_enforcer_loop<F>(
    stop: Arc<AtomicBool>,
    enforcer_notify: Arc<AtomicBool>,
    metrics: Arc<EnforcerMetrics>,
    snapshot_fn: F,
) where
    F: Fn() -> (ScopesConfig, bool, HashMap<String, u32>) + Send + 'static,
{
    let mut enforcer = PermissionEnforcer::new();
    let mut watcher = PermStateWatcher::new();

    // Startup async enforcement for all configured locked_deny rules
    {
        let (cfg, _screen_on, pkg_to_uid) = snapshot_fn();
        if !cfg.guards.is_empty() && !stop.load(Ordering::Relaxed) {
            for ev in enforce_locked_guards_once(&cfg, &pkg_to_uid) {
                metrics.record(ev);
            }
        }
    }

    let mut pending_notify = false;

    while !stop.load(Ordering::Relaxed) {
        let (cfg, screen_on, pkg_to_uid) = snapshot_fn();

        if cfg.guards.is_empty() {
            enforcer.was_foreground.clear();
            if sleep_interruptible(&stop, &enforcer_notify, 1500) {
                pending_notify = true;
            }
            continue;
        }

        if let Some(ref mut w) = watcher {
            let user_ids: Vec<u32> = cfg
                .guards
                .keys()
                .map(|p| resolve_pkg_user_id(p, &pkg_to_uid))
                .collect();
            w.ensure_user_watches(&user_ids);
        }

        let wait_ms: u64 = if screen_on { 1500 } else { 5000 };
        let triggered = if std::mem::take(&mut pending_notify)
            || enforcer_notify.swap(false, Ordering::AcqRel)
        {
            true
        } else if let Some(ref w) = watcher {
            // Chunked poll (max 250ms per slice) so shutdown and enforcer_notify respond promptly
            let mut remaining = wait_ms;
            let mut hit_inotify = false;
            let mut hit_notify = false;
            while remaining > 0 && !stop.load(Ordering::Relaxed) {
                if enforcer_notify.swap(false, Ordering::AcqRel) {
                    hit_notify = true;
                    break;
                }
                let slice = remaining.min(250);
                if w.wait_relevant(slice) {
                    hit_inotify = true;
                    break;
                }
                remaining = remaining.saturating_sub(slice);
            }
            if hit_inotify && !stop.load(Ordering::Relaxed) {
                // Brief debounce for atomic XML write+rename bursts
                sleep_debounce(&stop, 120);
                w.drain();
            }
            hit_inotify || hit_notify
        } else {
            sleep_interruptible(&stop, &enforcer_notify, wait_ms)
        };

        if stop.load(Ordering::Relaxed) {
            break;
        }

        let (cfg, screen_on, pkg_to_uid) = snapshot_fn();
        if cfg.guards.is_empty() {
            continue;
        }
        let top_fg = if screen_on {
            top_activity_uids()
        } else {
            HashSet::new()
        };
        for ev in enforcer.tick_with_index(&cfg, screen_on, &top_fg, &pkg_to_uid, triggered) {
            metrics.record(ev);
        }
    }
}

fn sleep_debounce(stop: &AtomicBool, total_ms: u64) {
    let mut elapsed = 0u64;
    while elapsed < total_ms && !stop.load(Ordering::Relaxed) {
        let step = (total_ms - elapsed).min(40);
        thread::sleep(Duration::from_millis(step));
        elapsed += step;
    }
}

fn sleep_interruptible(stop: &AtomicBool, notify: &AtomicBool, total_ms: u64) -> bool {
    let mut elapsed = 0u64;
    while elapsed < total_ms && !stop.load(Ordering::Relaxed) {
        if notify.swap(false, Ordering::AcqRel) {
            return true;
        }
        let step = (total_ms - elapsed).min(150);
        thread::sleep(Duration::from_millis(step));
        elapsed += step;
    }
    notify.swap(false, Ordering::AcqRel)
}

fn legacy_scope_pairs(scope: &AppIsolationScope) -> [(&'static str, bool); 9] {
    [
        ("contacts", scope.isolate_contacts),
        ("media", scope.isolate_storage),
        ("sms_calllog", scope.isolate_sms_calllog),
        ("clipboard", scope.block_clipboard_read),
        ("sensors", scope.restrict_sensors),
        ("background", scope.restrict_background),
        ("location", scope.isolate_location),
        ("camera", scope.block_camera_mic),
        ("microphone", scope.block_camera_mic),
    ]
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

    let mut applied = 0usize;
    for (cat, enabled) in legacy_scope_pairs(scope) {
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

/// Non-destructive legacy v2 scope sync:
/// - ONLY applies enabled (`true`) legacy toggles (NEVER resets disabled legacy toggles to `default`,
///   which previously wiped out unrelated v3 per-app permission restrictions on reboot!).
/// - NEVER overwrites packages/categories managed by v3 `cfg.guards` (`locked_deny` or `auto_revoke_on_bg`).
pub fn sync_all_scopes(cfg: &ScopesConfig) {
    for (pkg, scope) in &cfg.apps {
        if !scope.any_enabled() || !validate_package_name(pkg) || is_protected_package(pkg) {
            continue;
        }
        let guarded_cats: HashSet<&str> = cfg
            .guards
            .get(pkg)
            .map(|g| {
                g.auto_revoke_on_bg
                    .iter()
                    .chain(g.locked_deny.iter())
                    .map(String::as_str)
                    .collect()
            })
            .unwrap_or_default();

        for (cat, enabled) in legacy_scope_pairs(scope) {
            if enabled && !guarded_cats.contains(cat) {
                let _ = set_app_permission_action(pkg, cat, "ignore");
            }
        }
    }
}

/// Counts live restricted (`ignore`, `deny`, `foreground`) unique AppOps verified via
/// `cmd appops get --user 0 <pkg>` using the fixed strictness parser (M2 `sentinel.rs` compatible).
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
        if !validate_package_name(pkg) || is_protected_package(pkg) {
            continue;
        }
        if let Ok(out) = run_cmd(&cmd_bin, &["appops", "get", "--user", "0", pkg], None, 2000) {
            if out.success() {
                let parsed = parse_appops_get_output(&out.stdout);
                total += parsed
                    .values()
                    .filter(|(mode, _, _)| {
                        matches!(mode.as_str(), "ignore" | "deny" | "foreground")
                    })
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

fn clipboard_cmd_succeeded(out: &CmdOutput) -> bool {
    if !out.success() {
        return false;
    }
    let low = combined_cmd_output(out).to_ascii_lowercase();
    !(low.contains("unknown command")
        || low.contains("no shell command")
        || low.contains("usage:")
        || low.contains("exception")
        || low.contains("error:")
        || low.contains("permission denial")
        || low.contains("not found")
        || low.contains("inaccessible"))
}

pub(crate) fn service_call_parcel_ok(out: &CmdOutput) -> bool {
    if !out.success() {
        return false;
    }
    let low = combined_cmd_output(out).to_ascii_lowercase();
    if low.contains("exception") || low.contains("error") || low.contains("permission denial") {
        return false;
    }
    out.stdout.contains("Result: Parcel(00000000")
}

/// Stateless Android 12-16 clipboard clear helper callable outside `state_mtx`.
pub fn clear_clipboard_system() -> Result<(), String> {
    let cmd_bin = resolve_bin("cmd");
    for args in [
        &["clipboard", "clear"][..],
        &["clipboard", "set-text", ""][..],
    ] {
        if let Ok(out) = run_cmd(&cmd_bin, args, None, 2000) {
            if clipboard_cmd_succeeded(&out) {
                return Ok(());
            }
        }
    }

    let su_bin = resolve_bin("su");
    for args in [
        &["2000", "-c", "cmd clipboard clear"][..],
        &["-u", "2000", "-c", "cmd clipboard clear"][..],
        &["2000", "-c", "cmd clipboard set-text ''"][..],
        &["-u", "2000", "-c", "cmd clipboard set-text ''"][..],
    ] {
        if let Ok(out) = run_cmd(&su_bin, args, None, 2500) {
            if clipboard_cmd_succeeded(&out) {
                return Ok(());
            }
        }
    }

    // Android 12-16 SDK-aware `service call clipboard` fallback (verifying EX_NONE Parcel(00000000))
    let service_bin = resolve_bin("service");
    let sdk: u32 = getprop("ro.build.version.sdk")
        .and_then(|v| v.trim().parse::<u32>().ok())
        .unwrap_or(35);
    let service_args: &[&str] = if sdk >= 34 {
        &[
            "call",
            "clipboard",
            "3",
            "s16",
            "com.android.shell",
            "s16",
            "",
            "i32",
            "0",
            "i32",
            "0",
        ]
    } else if sdk == 33 {
        &[
            "call",
            "clipboard",
            "3",
            "s16",
            "com.android.shell",
            "s16",
            "",
            "i32",
            "0",
        ]
    } else {
        &["call", "clipboard", "2", "s16", "com.android.shell", "i32", "0"]
    };
    if let Ok(out) = run_cmd(&service_bin, service_args, None, 2500) {
        if service_call_parcel_ok(&out) {
            return Ok(());
        }
    }

    Err(
        "OEM ClipboardService rejected headless clear (Android focus restriction); cleared via Silo native API"
            .to_string(),
    )
}

impl ClipboardGuard {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn clear_now(&mut self) -> Result<(), String> {
        clear_clipboard_system()?;
        self.already_cleared_fingerprint = self.last_clip_fingerprint;
        Ok(())
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

fn is_explicit_empty_clip_line(trimmed: &str) -> bool {
    let low = trimmed.to_ascii_lowercase();
    low == "mprimaryclip=null"
        || low == "mprimaryclip: null"
        || low == "primary clip: null"
        || low == "primaryclip=null"
        || low == "primaryclip: null"
        || low == "no primary clip"
        || low == "primary clip is empty"
        || low == "primary clip: empty"
        || low.starts_with("mprimaryclip=null ")
        || low.starts_with("mprimaryclip=null;")
        || low.starts_with("mprimaryclip: null ")
        || low.starts_with("primary clip: null ")
        || low.starts_with("primary clip: null;")
        || low == "primaryclippackage=null"
        || low == "mprimaryclippackage=null"
}

fn detect_clipboard_user_header(trimmed: &str) -> Option<u32> {
    let low = trimmed.to_ascii_lowercase();
    if !(low.contains("user") && (low.contains("clipboard") || low.starts_with("user "))) {
        return None;
    }
    if let Some(pos) = low.find("user=") {
        let digits: String = low[pos + 5..]
            .chars()
            .take_while(|c| c.is_ascii_digit())
            .collect();
        if !digits.is_empty() {
            return digits.parse::<u32>().ok();
        }
    }
    if let Some(pos) = low.find("user ") {
        let digits: String = low[pos + 5..]
            .chars()
            .take_while(|c| c.is_ascii_digit())
            .collect();
        if !digits.is_empty() {
            return digits.parse::<u32>().ok();
        }
    }
    None
}

/// Pure zero-logging clipboard metadata fingerprinter.
/// - Treats ONLY explicit empty clip indicators (`mPrimaryClip=null`, `Primary clip: null`, etc.)
///   under user 0 as empty — NEVER rejects `ClipDescription { text/plain label=null ... }`!
/// - Never hashes raw clipboard text content (`{T:...}` lines).
pub fn parse_clipboard_metadata_hash(dump: &str) -> Option<u64> {
    let mut in_primary_user = true;
    let mut meta_lines: Vec<&str> = Vec::new();

    for line in dump.lines() {
        let t = line.trim();
        if t.is_empty() {
            continue;
        }
        if let Some(uid) = detect_clipboard_user_header(t) {
            in_primary_user = uid == 0;
            continue;
        }
        if !in_primary_user {
            continue;
        }
        if is_explicit_empty_clip_line(t) {
            return None;
        }
        // Skip raw text payload lines (Zero-Log / Zero-Content Hygiene)
        if t.contains("{T:") {
            continue;
        }
        if t.starts_with("ClipDescription")
            || t.starts_with("mPrimaryClipTimestamp")
            || t.starts_with("primaryClipPackage")
            || t.starts_with("mPrimaryClipPackage")
            || t.starts_with("mPrimaryClipUid")
            || t.starts_with("primaryClipUid")
            || t.contains("timestamp=")
            || t.starts_with("Primary clip:")
        {
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

fn probe_clipboard_metadata_hash() -> Option<u64> {
    let dumpsys = resolve_bin("dumpsys");
    let out = run_cmd(&dumpsys, &["clipboard"], None, 2000).ok()?;
    if !out.success() {
        return None;
    }
    parse_clipboard_metadata_hash(&out.stdout)
}

/// Pure helper computing the exact set of `(package_name -> categories)` to reset on uninstall
/// from BOTH `scopes.apps` (legacy v2) and `scopes.guards` (v3), skipping invalid/protected packages.
pub fn collect_uninstall_reset_plan(
    scopes: &ScopesConfig,
) -> BTreeMap<String, BTreeSet<&'static str>> {
    let mut plan: BTreeMap<String, BTreeSet<&'static str>> = BTreeMap::new();

    for (pkg, scope) in &scopes.apps {
        if !scope.any_enabled() || !validate_package_name(pkg) || is_protected_package(pkg) {
            continue;
        }
        let entry = plan.entry(pkg.clone()).or_default();
        for (cat, enabled) in legacy_scope_pairs(scope) {
            if enabled {
                entry.insert(cat);
            }
        }
    }

    for (pkg, guard) in &scopes.guards {
        if guard.is_empty() || !validate_package_name(pkg) || is_protected_package(pkg) {
            continue;
        }
        let entry = plan.entry(pkg.clone()).or_default();
        for cat in guard
            .auto_revoke_on_bg
            .iter()
            .chain(guard.locked_deny.iter())
        {
            if let Some(spec) = find_category(cat) {
                entry.insert(spec.id);
            }
        }
    }

    plan.retain(|_, cats| !cats.is_empty());
    plan
}

/// Resets ALL Silo-configured AppOps (`scopes.apps` + `scopes.guards`) back to `default`
/// and clears Silo-applied permission flags (`user-fixed`, `revoked-compat`) across every
/// configured package and Android user ID. Deliberately does NOT call `pm grant` so revoked
/// dangerous permissions remain safely ungranted until the user explicitly approves a prompt.
/// Returns `(packages_cleaned, ops_reset)`.
pub fn reset_all_scopes_for_uninstall(
    scopes: &ScopesConfig,
    pkg_to_uid: &HashMap<String, u32>,
) -> (usize, usize) {
    let plan = collect_uninstall_reset_plan(scopes);
    if plan.is_empty() {
        return (0, 0);
    }

    let cmd_bin = resolve_bin("cmd");
    let pm_bin = resolve_bin("pm");
    let mut packages_cleaned = 0usize;
    let mut ops_reset = 0usize;

    for (pkg, cats) in plan {
        let user_id = resolve_pkg_user_id(&pkg, pkg_to_uid);
        let user_str = user_id.to_string();
        let rt_info = query_package_runtime_perms(&pkg, user_id);
        let mut pkg_touched = false;

        for cat in cats {
            let Some(spec) = find_category(cat) else {
                continue;
            };

            for op in spec.appops {
                let primary = run_appops_set(
                    &cmd_bin,
                    &user_str,
                    &pkg,
                    op.name,
                    "default",
                    op.uid_scoped,
                );
                let secondary = run_appops_set(
                    &cmd_bin,
                    &user_str,
                    &pkg,
                    op.name,
                    "default",
                    !op.uid_scoped,
                );
                if matches!(primary, CmdOutcome::Ok) || matches!(secondary, CmdOutcome::Ok) {
                    ops_reset += 1;
                    pkg_touched = true;
                }
            }

            if spec.has_runtime_perms {
                for perm in target_declared_perms(spec, &rt_info) {
                    let cleared_uf = run_cmd(
                        &pm_bin,
                        &[
                            "clear-permission-flags",
                            "--user",
                            &user_str,
                            &pkg,
                            perm,
                            "user-fixed",
                        ],
                        None,
                        2000,
                    )
                    .map(|o| matches!(classify_pm_cmd_output(&o), CmdOutcome::Ok))
                    .unwrap_or(false)
                        || clear_perm_flag_best_effort(
                            &cmd_bin,
                            &user_str,
                            &pkg,
                            perm,
                            "user-fixed",
                        );
                    let cleared_rc = clear_perm_flag_best_effort(
                        &cmd_bin,
                        &user_str,
                        &pkg,
                        perm,
                        "revoked-compat",
                    );
                    if cleared_uf || cleared_rc {
                        pkg_touched = true;
                    }
                }
            }
        }

        if pkg_touched {
            packages_cleaned += 1;
        }
    }

    (packages_cleaned, ops_reset)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::AppPermGuard;

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

    #[test]
    fn perm_categories_metadata_and_android14_coverage() {
        assert_eq!(PERM_CATEGORIES.len(), 10);
        for spec in PERM_CATEGORIES {
            let expect_runtime = !matches!(spec.id, "clipboard" | "background");
            assert_eq!(spec.has_runtime_perms, expect_runtime, "{}", spec.id);
            let expect_fg = matches!(spec.id, "camera" | "microphone" | "location" | "sensors");
            assert_eq!(spec.supports_foreground, expect_fg, "{}", spec.id);
        }

        let media = find_category("media").unwrap();
        assert!(media
            .runtime_perms
            .contains(&"android.permission.READ_MEDIA_VISUAL_USER_SELECTED"));
        assert!(!media
            .runtime_perms
            .contains(&"android.permission.MANAGE_EXTERNAL_STORAGE"));
        let manage_op = media
            .appops
            .iter()
            .find(|o| o.name == "MANAGE_EXTERNAL_STORAGE")
            .unwrap();
        assert!(manage_op.is_special);
        assert!(!manage_op.uid_scoped);

        let bg = find_category("background").unwrap();
        assert!(bg.runtime_perms.is_empty());
        assert!(bg
            .appops
            .iter()
            .find(|o| o.name == "SYSTEM_ALERT_WINDOW")
            .unwrap()
            .is_special);
        assert!(bg
            .appops
            .iter()
            .find(|o| o.name == "GET_USAGE_STATS")
            .unwrap()
            .is_special);

        let sms = find_category("sms_calllog").unwrap();
        assert!(sms.runtime_perms.contains(&"android.permission.SEND_SMS"));
        assert!(sms
            .runtime_perms
            .contains(&"android.permission.RECEIVE_MMS"));

        let sensors = find_category("sensors").unwrap();
        assert!(sensors
            .runtime_perms
            .contains(&"android.permission.BODY_SENSORS_BACKGROUND"));

        assert_eq!(
            category_for_op("READ_MEDIA_VISUAL_USER_SELECTED"),
            Some("media")
        );
        assert_eq!(category_for_op("SEND_SMS"), Some("sms_calllog"));
    }

    #[test]
    fn rejects_unsupported_foreground_category() {
        for cat in [
            "clipboard",
            "background",
            "contacts",
            "media",
            "sms_calllog",
            "background_location",
        ] {
            let err = set_app_permission_action("com.example.app", cat, "foreground")
                .expect_err("unsupported foreground category must error");
            assert!(
                err.contains("does not support foreground"),
                "unexpected error for {}: {}",
                cat,
                err
            );
        }
    }

    #[test]
    fn parse_appops_uid_mode_never_overwritten_by_pkg_history() {
        let order_uid_first = "\
Uid mode: CAMERA: ignore
CAMERA: allow; time=+3m12s ago; duration=+15s
Uid mode: RECORD_AUDIO: errored
RECORD_AUDIO: allow; time=+5s ago
Uid mode: FINE_LOCATION: foreground
FINE_LOCATION: allow; time=+12s ago (running)
";
        let order_pkg_first = "\
CAMERA: allow; time=+3m12s ago; duration=+15s
Uid mode: CAMERA: ignore
RECORD_AUDIO: allow; time=+5s ago
Uid mode: RECORD_AUDIO: errored
FINE_LOCATION: allow; time=+12s ago (running)
Uid mode: FINE_LOCATION: foreground
";
        for sample in [order_uid_first, order_pkg_first] {
            let parsed = parse_appops_get_detailed(sample);
            let cam = parsed.get("CAMERA").unwrap();
            assert_eq!(cam.effective_mode, "ignore");
            assert_eq!(cam.uid_mode.as_deref(), Some("ignore"));
            assert_eq!(cam.pkg_mode.as_deref(), Some("allow"));
            assert_eq!(cam.last_access.as_deref(), Some("+3m12s ago"));

            let mic = parsed.get("RECORD_AUDIO").unwrap();
            assert_eq!(mic.effective_mode, "deny");
            assert_eq!(mic.uid_mode.as_deref(), Some("deny"));

            let loc = parsed.get("FINE_LOCATION").unwrap();
            assert_eq!(loc.effective_mode, "foreground");
            assert!(loc.running_now);
            assert_eq!(loc.last_access.as_deref(), Some("+12s ago"));
        }
    }

    #[test]
    fn combine_uid_and_pkg_mode_full_precedence_matrix() {
        assert_eq!(combine_uid_and_pkg_mode(Some("deny"), Some("allow")), "deny");
        assert_eq!(
            combine_uid_and_pkg_mode(Some("errored"), Some("default")),
            "deny"
        );
        assert_eq!(
            combine_uid_and_pkg_mode(Some("ignore"), Some("allow")),
            "ignore"
        );
        assert_eq!(
            combine_uid_and_pkg_mode(Some("ignore"), Some("foreground")),
            "ignore"
        );
        assert_eq!(
            combine_uid_and_pkg_mode(Some("ignore"), Some("deny")),
            "deny"
        );
        assert_eq!(
            combine_uid_and_pkg_mode(Some("foreground"), Some("allow")),
            "foreground"
        );
        assert_eq!(
            combine_uid_and_pkg_mode(Some("foreground"), Some("ignore")),
            "ignore"
        );
        assert_eq!(
            combine_uid_and_pkg_mode(Some("allow"), Some("default")),
            "allow"
        );
        assert_eq!(
            combine_uid_and_pkg_mode(Some("default"), Some("ignore")),
            "ignore"
        );
        assert_eq!(combine_uid_and_pkg_mode(None, Some("ignore")), "ignore");
        assert_eq!(combine_uid_and_pkg_mode(Some("ignore"), None), "ignore");
        assert_eq!(combine_uid_and_pkg_mode(None, None), "default");
    }

    #[test]
    fn aggregate_category_appops_mode_is_fail_closed() {
        assert_eq!(aggregate_category_appops_mode(&["ignore", "allow"]), "allow");
        assert_eq!(aggregate_category_appops_mode(&["deny", "allow"]), "allow");
        assert_eq!(
            aggregate_category_appops_mode(&["ignore", "foreground"]),
            "foreground"
        );
        assert_eq!(
            aggregate_category_appops_mode(&["ignore", "deny"]),
            "ignore"
        );
        assert_eq!(aggregate_category_appops_mode(&["deny", "deny"]), "deny");
        assert_eq!(aggregate_category_appops_mode(&["deny", "default"]), "deny");
        assert_eq!(
            aggregate_category_appops_mode(&["ignore", "default"]),
            "ignore"
        );
        assert_eq!(aggregate_category_appops_mode(&[]), "default");
        assert_eq!(aggregate_category_appops_mode(&["default"]), "default");
    }

    #[test]
    fn parse_dumpsys_package_runtime_perms_multiuser_and_flags() {
        let sample = "\
  declared permissions:
    com.example.INTERNAL: prot=signature
  requested permissions:
    android.permission.CAMERA
    android.permission.RECORD_AUDIO
    android.permission.READ_MEDIA_IMAGES
    android.permission.ACCESS_FINE_LOCATION
  install permissions:
    android.permission.INTERNET: granted=true
  User 0: ceDataInode=1234 installed=true
    runtime permissions:
      android.permission.CAMERA: granted=false, flags=[ USER_SET|USER_FIXED]
      android.permission.RECORD_AUDIO: granted=true, flags=[ SYSTEM_FIXED|GRANTED_BY_DEFAULT]
      android.permission.READ_MEDIA_IMAGES: granted=false, flags=[ REVOKED_COMPAT]
  User 10: ceDataInode=5678 installed=true
    runtime permissions:
      android.permission.CAMERA: granted=true, flags=[ USER_SET]
      android.permission.RECORD_AUDIO: granted=false, flags=[ USER_SET]
";
        let u0 = parse_dumpsys_package_runtime_perms(sample, 0);
        assert!(u0.user_section_found);
        assert!(u0.declared.contains("android.permission.CAMERA"));
        assert!(!u0.granted.contains("android.permission.CAMERA"));
        assert!(u0.user_fixed.contains("android.permission.CAMERA"));
        assert!(u0.granted.contains("android.permission.RECORD_AUDIO"));
        assert!(u0.system_fixed.contains("android.permission.RECORD_AUDIO"));
        assert!(u0.revoked_compat.contains("android.permission.READ_MEDIA_IMAGES"));
        assert!(u0
            .declared
            .contains("android.permission.ACCESS_FINE_LOCATION"));
        assert!(!u0
            .granted
            .contains("android.permission.ACCESS_FINE_LOCATION"));

        let u10 = parse_dumpsys_package_runtime_perms(sample, 10);
        assert!(u10.user_section_found);
        assert!(u10.granted.contains("android.permission.CAMERA"));
        assert!(!u10.granted.contains("android.permission.RECORD_AUDIO"));

        let ops = parse_appops_get_detailed("Uid mode: CAMERA: ignore\nCAMERA: allow; time=+2m ago");
        let live = build_app_live_permissions("com.example.app", &ops, &u0);
        let find_cat = |id: &str| live.categories.iter().find(|c| c.category == id).unwrap();

        assert_eq!(find_cat("camera").runtime_granted, Some(false));
        assert_eq!(find_cat("camera").appops_mode, "ignore");
        assert!(!find_cat("camera").system_fixed);

        assert_eq!(find_cat("microphone").runtime_granted, Some(true));
        assert!(find_cat("microphone").system_fixed);

        assert_eq!(find_cat("location").runtime_granted, Some(false));
        assert_eq!(find_cat("contacts").runtime_granted, None);
        assert_eq!(find_cat("clipboard").runtime_granted, None);
        assert!(!find_cat("clipboard").has_runtime_perms);
    }

    #[test]
    fn cmd_output_classifiers_inspect_stdout_stderr_and_timeout() {
        let mk = |status_code, stdout: &str, stderr: &str, timed_out| CmdOutput {
            status_code,
            stdout: stdout.to_string(),
            stderr: stderr.to_string(),
            timed_out,
        };

        assert!(matches!(
            classify_pm_cmd_output(&mk(-1, "", "", true)),
            CmdOutcome::Failed(_)
        ));
        assert!(matches!(
            classify_pm_cmd_output(&mk(
                0,
                "SecurityException: Permission android.permission.CAMERA is SYSTEM_FIXED",
                "",
                false
            )),
            CmdOutcome::SystemFixed(_)
        ));
        assert_eq!(
            classify_pm_cmd_output(&mk(
                1,
                "Package has not requested permission android.permission.CAMERA",
                "",
                false
            )),
            CmdOutcome::SkippedUndeclared
        );
        assert_eq!(
            classify_pm_cmd_output(&mk(1, "", "", false)),
            CmdOutcome::Failed("exit code 1".to_string())
        );

        assert!(matches!(
            classify_appops_cmd_output(&mk(
                0,
                "Error: CAMERA should not be set directly",
                "",
                false
            )),
            CmdOutcome::Failed(_)
        ));
        assert_eq!(
            classify_appops_cmd_output(&mk(
                1,
                "",
                "Unknown operation: READ_MEDIA_VISUAL_USER_SELECTED",
                false
            )),
            CmdOutcome::SkippedUndeclared
        );
    }

    #[test]
    fn enforcer_drift_prevents_false_positive_force_stop_loop() {
        let cam_spec = find_category("camera").unwrap();
        let ops = parse_appops_get_detailed("Uid mode: CAMERA: ignore\nCAMERA: allow; time=+3m ago");
        let mut rt = PackageRuntimePermInfo {
            user_section_found: true,
            ..Default::default()
        };
        rt.declared.insert("android.permission.CAMERA".to_string());

        // Already revoked + UID ignore + old pkg allow history => NO drift, ZERO force-stop loop!
        assert_eq!(
            evaluate_category_drift(cam_spec, &ops, &rt, true, false),
            DriftAction::AlreadyEnforced
        );

        // Runtime granted again => NeedsFullRevoke
        rt.granted.insert("android.permission.CAMERA".to_string());
        assert_eq!(
            evaluate_category_drift(cam_spec, &ops, &rt, true, false),
            DriftAction::NeedsFullRevoke
        );

        // SYSTEM_FIXED + granted=true + UID ignore => AlreadyEnforced (never loop pm revoke!)
        rt.system_fixed
            .insert("android.permission.CAMERA".to_string());
        assert_eq!(
            evaluate_category_drift(cam_spec, &ops, &rt, true, false),
            DriftAction::AlreadyEnforced
        );

        // SYSTEM_FIXED + granted=true + UID allow => AppOps-only repair (never pm revoke!)
        let ops_allow = parse_appops_get_detailed("Uid mode: CAMERA: allow");
        assert_eq!(
            evaluate_category_drift(cam_spec, &ops_allow, &rt, true, false),
            DriftAction::NeedsAppOpsOnlyRepair
        );

        // Stale v2 pkg-only override (Uid mode: allow, pkg: ignore) with runtime revoked => AppOps-only repair
        rt.granted.clear();
        rt.system_fixed.clear();
        let stale_pkg_only = parse_appops_get_detailed("Uid mode: CAMERA: allow\nCAMERA: ignore");
        assert_eq!(
            evaluate_category_drift(cam_spec, &stale_pkg_only, &rt, true, false),
            DriftAction::NeedsAppOpsOnlyRepair
        );
    }

    #[test]
    fn enforcer_shared_uid_and_fgs_top_fg_logic() {
        let mut pkg_to_uid = HashMap::new();
        pkg_to_uid.insert("com.samsung.a".to_string(), 10250);
        pkg_to_uid.insert("com.samsung.b".to_string(), 10250);
        pkg_to_uid.insert("com.spy.fgs".to_string(), 10300);
        pkg_to_uid.insert("com.work.app".to_string(), 1_010_250);
        pkg_to_uid.insert("com.dual.app".to_string(), 9_510_250);
        pkg_to_uid.insert("com.knox.app".to_string(), 15_010_250);

        let mut top_fg = HashSet::new();
        top_fg.insert(10250);

        assert!(is_pkg_in_top_fg("com.samsung.a", true, &top_fg, &pkg_to_uid));
        assert!(is_pkg_in_top_fg("com.samsung.b", true, &top_fg, &pkg_to_uid));
        assert!(!is_pkg_in_top_fg("com.spy.fgs", true, &top_fg, &pkg_to_uid));

        // Screen off => no app is foreground
        assert!(!is_pkg_in_top_fg("com.samsung.a", false, &top_fg, &pkg_to_uid));
        assert!(!is_pkg_in_top_fg("com.samsung.b", false, &top_fg, &pkg_to_uid));

        assert_eq!(resolve_pkg_user_id("com.samsung.a", &pkg_to_uid), 0);
        assert_eq!(resolve_pkg_user_id("com.work.app", &pkg_to_uid), 10);
        assert_eq!(resolve_pkg_user_id("com.dual.app", &pkg_to_uid), 95);
        assert_eq!(resolve_pkg_user_id("com.knox.app", &pkg_to_uid), 150);
    }

    #[test]
    fn parse_dumpsys_appops_op_boundary_tuple_protected_and_multi_access() {
        let sample = "\
Uid u0a277:
  Package com.example.cam:
    CAMERA_SANDBOXED (allow):
      Access: [top-s] 2026-11-22 15:12:00.000 (-1s)
    CAMERA (allow/ignore):
      Access: [top-s] 2026-11-22 12:00:00.000 (-3h)
      Access: [bg] 2026-11-22 15:11:58.000 (-2s) (running)
      Reject: [bg] 2026-11-22 15:11:59.000 (-1s)
Uid u0a278:
  CAMERA (ignore):
  Package com.example.split:
    CAMERA (allow):
      Access: [top-s] 2026-11-22 15:11:15.000 (-45s)
Uid u0a279:
  Package com.samsung.android.honeyboard:
    CAMERA (allow):
      Access: [top-s] 2026-11-22 15:11:50.000 (-10s)
Uid u95a50:
  Package com.example.dual:
    CAMERA (allow):
      Access: [top-s] 2026-11-22 15:11:30.000 (-30s)
";
        let mut out = Vec::new();
        parse_dumpsys_appops_op(sample, "CAMERA", &HashMap::new(), &mut out);
        assert_eq!(out.len(), 3);

        let cam = out.iter().find(|e| e.package_name == "com.example.cam").unwrap();
        assert_eq!(cam.mode, "ignore");
        assert!(cam.running_now);
        assert_eq!(cam.age_secs, 2);

        let split = out
            .iter()
            .find(|e| e.package_name == "com.example.split")
            .unwrap();
        assert_eq!(split.mode, "ignore");
        assert_eq!(split.age_secs, 45);

        assert!(!out
            .iter()
            .any(|e| e.package_name == "com.samsung.android.honeyboard"));

        let dual = out
            .iter()
            .find(|e| e.package_name == "com.example.dual")
            .unwrap();
        assert_eq!(dual.uid, 9_510_050);
    }

    #[test]
    fn clipboard_metadata_hash_handles_label_null_and_empty_markers() {
        let active1 = "\
Per-user clipboard (user=0):
  ClipDescription { text/plain label=null timestamp=1763850000000 }
  primaryClipPackage=com.example.browser
  mPrimaryClip=ClipData { text/plain {T:my_secret_password} }
Per-user clipboard (user=95):
  mPrimaryClip=null
";
        let active2 = "\
Per-user clipboard (user=0):
  ClipDescription { text/plain label=null timestamp=1763850000000 }
  primaryClipPackage=com.example.browser
  mPrimaryClip=ClipData { text/plain {T:different_secret_value} }
Per-user clipboard (user=95):
  mPrimaryClip=null
";
        let h1 = parse_clipboard_metadata_hash(active1).expect("label=null must not treat clip as empty");
        let h2 = parse_clipboard_metadata_hash(active2).expect("active clip must hash");
        assert_eq!(h1, h2, "raw clipboard text must NEVER influence metadata hash");

        for empty in [
            "Per-user clipboard (user=0):\n  mPrimaryClip=null\n",
            "Per-user clipboard (user=0):\n  Primary clip: null\n",
            "Per-user clipboard (user=0):\n  mPrimaryClip: null\n",
            "Per-user clipboard (user=0):\n  No primary clip\n",
        ] {
            assert_eq!(parse_clipboard_metadata_hash(empty), None);
        }
    }

    #[test]
    fn service_call_parcel_ok_validation() {
        let mk = |status_code, stdout: &str, stderr: &str| CmdOutput {
            status_code,
            stdout: stdout.to_string(),
            stderr: stderr.to_string(),
            timed_out: false,
        };
        assert!(service_call_parcel_ok(&mk(
            0,
            "Result: Parcel(00000000    '....')",
            ""
        )));
        assert!(!service_call_parcel_ok(&mk(
            0,
            "Result: Parcel(ffffffff ... SecurityException)",
            ""
        )));
        assert!(!service_call_parcel_ok(&mk(1, "Result: Parcel(00000000)", "")));
        assert!(!service_call_parcel_ok(&mk(0, "Error: denied", "")));
    }

    #[test]
    fn collect_uninstall_reset_plan_merges_apps_and_guards_safely() {
        let mut scopes = ScopesConfig::default();
        scopes.apps.insert(
            "com.example.v2".to_string(),
            AppIsolationScope {
                isolate_contacts: true,
                block_camera_mic: true,
                ..Default::default()
            },
        );
        scopes.guards.insert(
            "com.example.v2".to_string(),
            AppPermGuard {
                auto_revoke_on_bg: vec![],
                locked_deny: vec!["location".to_string()],
            },
        );
        scopes.guards.insert(
            "com.example.v3".to_string(),
            AppPermGuard {
                auto_revoke_on_bg: vec!["microphone".to_string()],
                locked_deny: vec!["clipboard".to_string()],
            },
        );
        scopes.apps.insert(
            "com.android.systemui".to_string(),
            AppIsolationScope::hardened_profile(),
        );
        scopes.guards.insert(
            "bad;pkg".to_string(),
            AppPermGuard {
                auto_revoke_on_bg: vec!["camera".to_string()],
                locked_deny: vec![],
            },
        );

        let plan = collect_uninstall_reset_plan(&scopes);
        assert_eq!(plan.len(), 2);
        let v2: BTreeSet<&str> = ["contacts", "camera", "microphone", "location"]
            .into_iter()
            .collect();
        assert_eq!(plan.get("com.example.v2"), Some(&v2));
        let v3: BTreeSet<&str> = ["microphone", "clipboard"].into_iter().collect();
        assert_eq!(plan.get("com.example.v3"), Some(&v3));
        assert!(!plan.contains_key("com.android.systemui"));
        assert!(!plan.contains_key("bad;pkg"));
    }

    #[test]
    fn enforcer_metrics_thread_safe_recording() {
        let m = EnforcerMetrics::new();
        assert_eq!(m.actions_count(), 0);
        assert_eq!(m.last_event(), None);
        m.record("Re-locked camera on com.example.app".to_string());
        assert_eq!(m.actions_count(), 1);
        assert_eq!(
            m.last_event().as_deref(),
            Some("Re-locked camera on com.example.app")
        );
    }
}
