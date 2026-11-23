# Implementation Blueprint — M1 Explorer 1 (`iter1_exp1_scopes`: `daemon/src/scopes.rs`)

**Handoff Type:** Hard (Read-Only Technical Exploration & Line-Level Implementation Blueprint)  
**Target File (Exclusive Blueprint Scope):** `/home/antigravity/terenzy/Silo/daemon/src/scopes.rs` (currently 859 lines)  
**Cross-Module Compatibility Verified Against (Read-Only):** `daemon/src/{main,protocol,config,sys,sentinel}.rs`, `daemon/Cargo.toml`, `daemon/Cargo.lock` (`libc 0.2.191`, `rustc 1.100.0`), `app/.../ipc/SiloModels.kt`, `app/.../data/AppInventory.kt`, `sub_orch_m3_android_app/iter1_explorer_1/handoff.md`  
**Covered Findings:** `D-01`, `D-02`, `D-03`, `D-05`, `D-11`, `D-17`, `D-18`, `D-27`, `CB-01`, `CB-02`, `CB-03`, `CB-05`, `CB-08` (scopes user-id), `CB-09`, `CB-10`, `CB-15`, `CB-19`  

---

## 1. Cross-File Interface & Compile-Compatibility Guarantees (READ FIRST!)

Before editing `daemon/src/scopes.rs`, the M1 Worker MUST honor these exact contracts across M1, M2 (untouched), and M3 (Android App):

1. **`CategoryPermLiveState` exact field names (`daemon/src/protocol.rs:603-608` ↔ `SiloModels.kt:227-243`)**:
   - **CRITICAL**: Existing `protocol.rs:606` and `SiloModels.kt:230` use **`last_access: Option<String>`** (NOT `last_access_ago`, which was only illustrative in `PROJECT.md:90`; M3's blueprint explicitly reads `last_access` first and falls back to `last_access_ago`).
   - Keep `pub last_access: Option<String>` in `protocol.rs` and add the 4 `#[serde(default)]` fields:
     ```rust
     #[derive(Debug, Clone, Serialize, Deserialize)]
     pub struct CategoryPermLiveState {
         pub category: String,
         pub appops_mode: String,
         pub last_access: Option<String>,
         pub running_now: bool,
         #[serde(default)]
         pub runtime_granted: Option<bool>,
         #[serde(default)]
         pub has_runtime_perms: bool,
         #[serde(default)]
         pub supports_foreground: bool,
         #[serde(default)]
         pub system_fixed: bool,
     }
     ```
2. **M2 Compile Compatibility (`daemon/src/sentinel.rs:9,363` — DO NOT EDIT `sentinel.rs` in M1!)**:
   - `sentinel.rs` calls `crate::scopes::count_live_ignored_ops(&cfg.scopes) -> usize`.
   - Keep `pub fn count_live_ignored_ops(cfg: &ScopesConfig) -> usize` with its **exact** signature, upgrading its internal implementation to use the fixed `parse_appops_get_output` parser and count unique `"ignore" | "deny" | "foreground"` ops (`D-27`).
3. **Backward-Compatible Existing `scopes.rs` Public Signatures**:
   - Keep ALL existing public functions/types compiling with unchanged signatures so nothing in `main.rs` or tests breaks during incremental edits:
     - `find_category(id: &str) -> Option<&'static PermCategorySpec>`
     - `category_for_op(op: &str) -> Option<&'static str>`
     - `set_app_permission_action(package_name: &str, category: &str, action: &str) -> Result<AppLivePermissions, String>` (delegates to `set_app_permission_action_for_user(..., 0)`)
     - `bulk_revoke_category(category: &str, packages: &[String]) -> Result<usize, String>` (delegates to `bulk_revoke_category_with_uids(..., &HashMap::new())`)
     - `inspect_app_permissions(package_name: &str) -> AppLivePermissions` (delegates to `inspect_app_permissions_for_user(..., 0)`)
     - `parse_appops_get_output(output: &str) -> HashMap<String, (String, Option<String>, bool)>` (delegates to `parse_appops_get_detailed`)
     - `get_privacy_access_log(uid_map: &HashMap<u32, String>, limit: usize) -> Vec<PrivacyAccessEvent>`
     - `parse_dumpsys_appops_op(dump: &str, op_name: &str, uid_map: &HashMap<u32, String>, out: &mut Vec<PrivacyAccessEvent>)`
     - `parse_relative_age_from_access_line(line: &str) -> (u64, String)`
     - `PermissionEnforcer::new()`, `PermissionEnforcer::tick(&mut self, cfg, screen_on, fg_uids, uid_map)` (plus new `tick_with_index` and `run_permission_enforcer_loop`)
     - `apply_app_scope`, `clear_app_scope`, `sync_all_scopes`, `count_live_ignored_ops`
     - `ClipboardGuard::new()`, `ClipboardGuard::clear_now(&mut self)`, `ClipboardGuard::tick(&mut self, cfg)`
4. **New Additive Public Exports from `scopes.rs` for `main.rs` (M1 Explorer 2 coordination)**:
   - `inspect_app_permissions_for_user(package_name: &str, user_id: u32) -> AppLivePermissions`
   - `set_app_permission_action_for_user(package_name: &str, category: &str, action: &str, user_id: u32) -> Result<AppLivePermissions, String>`
   - `bulk_revoke_category_with_uids(category: &str, packages: &[String], pkg_to_uid: &HashMap<String, u32>) -> Result<usize, String>`
   - `apply_lock_flags_for_category(package_name: &str, category: &str, user_id: u32) -> usize`
   - `clear_lock_flags_for_category(package_name: &str, category: &str, user_id: u32) -> usize`
   - `enforce_locked_guards_once(cfg: &ScopesConfig, pkg_to_uid: &HashMap<String, u32>) -> Vec<String>`
   - `EnforcerMetrics` (`new()`, `record(String)`, `actions_count() -> u64`, `last_event() -> Option<String>`)
   - `run_permission_enforcer_loop<F>(stop: Arc<AtomicBool>, metrics: Arc<EnforcerMetrics>, snapshot_fn: F)`
   - `clear_clipboard_system() -> Result<(), String>`
   - `reset_all_scopes_for_uninstall(scopes: &ScopesConfig, pkg_to_uid: &HashMap<String, u32>) -> (usize, usize)`
   - `top_activity_app_uids() -> Vec<u32>` (self-contained `/proc` reader for `oom_score_adj <= 0`; if Explorer 3 adds `sys::top_foreground_app_uids()`, Worker may delegate to it to avoid duplication)
   - `resolve_pkg_user_id(pkg: &str, pkg_to_uid: &HashMap<String, u32>) -> u32`
   - Pure unit-testable functions: `combine_uid_and_pkg_mode`, `aggregate_category_appops_mode`, `parse_appops_get_detailed`, `parse_dumpsys_package_runtime_perms`, `evaluate_category_drift`, `is_pkg_in_top_fg`, `parse_clipboard_metadata_hash`, `collect_uninstall_reset_plan`.
5. **No External References to `PERM_CATEGORIES` / `PermCategorySpec`**:
   - Verified via `grep -rn`: ONLY `daemon/src/scopes.rs` references `PERM_CATEGORIES` and `PermCategorySpec`. Changing `spec.appops` from `&[&str]` to `&[AppOpSpec]` has zero external compile impact.
6. **Zero New Dependencies**:
   - Uses ONLY `std` + `libc` (already in `daemon/Cargo.toml`: `libc = "0.2"`, resolved `0.2.191`). Verified musl-static & `cargo clippy --all-targets -- -D warnings` clean.

---

## 2. Exact Line-Level Implementation Blueprint (`daemon/src/scopes.rs`)

### Edit Block 1 — Imports (`scopes.rs:1-10`)

Replace lines `1-10` with:

```rust
use crate::config::{
    is_protected_package, is_protected_uid, is_valid_perm_category, validate_package_name,
    AppIsolationScope, ScopesConfig,
};
use crate::protocol::{AppLivePermissions, CategoryPermLiveState, PrivacyAccessEvent};
use crate::sys::{boottime_secs, getprop, parse_proc_status_uid, resolve_bin, run_cmd, CmdOutput};
use std::collections::hash_map::DefaultHasher;
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::ffi::CString;
use std::fs;
use std::hash::{Hash, Hasher};
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;
```

---

### Edit Block 2 — `AppOpSpec`, `PermCategorySpec`, `PERM_CATEGORIES`, `category_for_op` (`scopes.rs:11-117`)

**Fixes:** `D-02`, `CB-02`, `CB-09`, `PROJECT.md § Interface Contracts § 2`

**Design Details:**
- Add `AppOpSpec { name, uid_scoped, is_special }` to tag:
  - `uid_scoped`: `true` for runtime-permission-backed ops + `RUN_ANY_IN_BACKGROUND`; `false` for package-only/special ops (`READ_CLIPBOARD`, `RUN_IN_BACKGROUND`, `WAKE_LOCK`, `SYSTEM_ALERT_WINDOW`, `GET_USAGE_STATS`, `MANAGE_EXTERNAL_STORAGE`).
  - `is_special`: `true` for special AppOps (`MANAGE_EXTERNAL_STORAGE`, `SYSTEM_ALERT_WINDOW`, `GET_USAGE_STATS`) cleanly separated from `runtime_perms`.
- Add `has_runtime_perms: bool` (`false` for `clipboard`, `background`; `true` for the other 8).
- Add `supports_foreground: bool` (`true` for `camera`, `microphone`, `location`, `sensors`; `false` for the other 6).
- Add Android 14+ `android.permission.READ_MEDIA_VISUAL_USER_SELECTED` (+ `READ_MEDIA_VISUAL_USER_SELECTED` AppOp) to `media`, `android.permission.SEND_SMS` & `android.permission.RECEIVE_MMS` (+ ops) to `sms_calllog`, and `android.permission.BODY_SENSORS_BACKGROUND` to `sensors`.

Replace lines `11-117` with:

```rust
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
```

---

### Edit Block 3 — `parse_appops_get_output`, Strictness Precedence, `dumpsys package` Runtime Parser & Fail-Closed Inspection (`scopes.rs:203-324` + helpers)

**Fixes:** `D-01`, `D-03`, `CB-01`, `CB-08`, `CB-09`

#### Exact `(uid_mode, pkg_mode)` Precedence Table (`combine_uid_and_pkg_mode`)
First normalize `"errored"` -> `"deny"` (`normalize_appops_mode`).  
Strictness rank for combining `uid_mode` and `pkg_mode` of the **same** op:  
`deny (5)` > `ignore (4)` > `foreground (3)` > `allow (2)` > `default (1)`.

| `uid_mode` | `pkg_mode` | Effective Mode | Rationale |
|---|---|---|---|
| `Some("deny"\|"errored")` | Any (`None`/`default`/`allow`/`foreground`/`ignore`/`deny`) | **`"deny"`** | Hard UID block; never overwritten by package history. |
| `Some("ignore")` | `None`/`default`/`allow`/`foreground`/`ignore` | **`"ignore"`** | Fixes D-01 (`Uid mode: CAMERA: ignore` + `CAMERA: allow; time=+3m ago` -> `"ignore"`). |
| `Some("ignore")` | `Some("deny"\|"errored")` | **`"deny"`** | Stricter package deny wins over UID ignore. |
| `Some("foreground")` | `None`/`default`/`allow`/`foreground` | **`"foreground"`** | Fixes `Uid mode: FINE_LOCATION: foreground` + `FINE_LOCATION: allow; time=+12s` -> `"foreground"`. |
| `Some("foreground")` | `Some("ignore")` / `Some("deny")` | **`"ignore"`** / **`"deny"`** | Stricter package restriction wins over foreground. |
| `Some("allow")` | `None`/`default`/`allow` | **`"allow"`** | Active UID allow preserved over `default`. |
| `Some("allow")` | `Some("foreground"\|"ignore"\|"deny")` | `pkg_mode` | Effective strictness precedence (and `PermissionEnforcer` additionally checks `entry.uid_mode` directly on `uid_scoped` ops so stale v2 pkg-only overrides are upgraded to `--uid`!). |
| `Some("default")` | `Some(m)` | `m` | AOSP `MODE_DEFAULT` UID mode delegates to package mode. |
| `None` | `Some(p)` | `p` | Package-only op (e.g. `READ_CLIPBOARD`). |
| `None` | `None` | `"default"` | Unlisted op. |

*(Order-independent: produces the exact same result whether `Uid mode:` appears before or after the package line!)*

#### Exact Multi-Op Fail-Closed Aggregation Table (`aggregate_category_appops_mode`)
Exposure rank across **different** ops within the same category:  
`allow (4)` > `foreground (3)` > `ignore (2)` > `deny (1)` > `default (0)`.

| Present Op Modes in Category | Aggregated `appops_mode` | Rationale |
|---|---|---|
| `[]` or `["default", ...]` | `"default"` | No active overrides or history. |
| Any op is `"allow"` (e.g. `["ignore", "allow"]`, `["deny", "allow"]`) | **`"allow"`** | Fixes D-03 (fail-closed: never hide active exposure behind another op's `ignore`!). |
| No `"allow"`, any op is `"foreground"` (e.g. `["ignore", "foreground"]`) | **`"foreground"`** | Exposure while foreground beats `ignore`/`deny`. |
| No `"allow"`/`"foreground"`, any op is `"ignore"` (e.g. `["ignore", "deny"]`, `["ignore", "default"]`) | **`"ignore"`** | Mix of `ignore` and `deny`/`default` reports `"ignore"`. |
| All non-default present ops are `"deny"` (and >=1 is `"deny"`) | **`"deny"`** | Pure hard-denied category (`clipboard`/`background` after `revoke`, or special op deny). |

#### Exact `runtime_granted` Edge Cases (`parse_dumpsys_package_runtime_perms`)
1. `!spec.has_runtime_perms` (`clipboard`, `background`) -> `runtime_granted = None`, `system_fixed = false`.
2. `!rt_info.user_section_found` (`dumpsys package` unavailable/failed/truncated) -> `runtime_granted = None`, `system_fixed = false` (Android App cleanly falls back to `PackageManager`).
3. `rt_info.user_section_found == true`:
   - Collect `declared` from BOTH `requested permissions:` (indentation-tracked, excluding `declared permissions:` and `install permissions:`) AND any permission listed under `User <user_id>: ... runtime permissions:`.
   - Collect `granted` (`granted=true`), `system_fixed` (`SYSTEM_FIXED`), `user_fixed` (`USER_FIXED`), `revoked_compat` (`REVOKED_COMPAT`) ONLY from `User <user_id>:`'s `runtime permissions:` block (with legacy fallback when no `User X:` header exists and `user_id == 0`).
   - If any perm in `spec.runtime_perms` is in `rt_info.granted` -> `Some(true)`.
   - Else if any perm in `spec.runtime_perms` is in `rt_info.declared` (covers BOTH explicitly `granted=false` in `runtime permissions:` AND untouched manifest-declared perms listed only in `requested permissions:`!) -> `Some(false)`.
   - Else (package does NOT declare any runtime perm in `spec.runtime_perms`) -> `None` (cleanly distinguishes "not declared by app" from "declared & revoked"; App falls back to `localRuntimeGranted = false`).
   - `system_fixed = spec.runtime_perms.iter().any(|&p| rt_info.system_fixed.contains(p))`.

**Exact Code for Parsers & Inspection:**

```rust
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
        let val = sub[..end]
            .trim()
            .trim_end_matches("(running)")
            .trim();
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
            let perm = trimmed
                .split([':', ' '])
                .next()
                .unwrap_or("")
                .trim();
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
/// concurrently (2 scoped threads) to halve latency and avoid duplicate subprocesses in `PermissionEnforcer`.
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

        let (runtime_granted, system_fixed) = if !spec.has_runtime_perms || !rt_info.user_section_found
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

/// Live per-app AppOps + runtime permission inspection for user 0 (backward-compatible wrapper).
pub fn inspect_app_permissions(package_name: &str) -> AppLivePermissions {
    inspect_app_permissions_for_user(package_name, 0)
}

/// Live per-app AppOps + runtime permission inspection for a specific Android `user_id`
/// (supports User 0, Work Profile u10+, Samsung Dual Messenger u95, Secure Folder u150).
pub fn inspect_app_permissions_for_user(package_name: &str, user_id: u32) -> AppLivePermissions {
    inspect_app_permissions_detailed(package_name, user_id).0
}
```

---

### Edit Block 4 — `set_app_permission_action` & `set_app_permission_action_for_user`, `bulk_revoke_category`, `apply_lock_flags_for_category`, `clear_lock_flags_for_category` (`scopes.rs:119-201`)

**Fixes:** `D-02`, `D-05`, `CB-02`, `CB-03`, `CB-08`

**Key Mechanics Verified:**
1. Reject `"foreground"` cleanly if `!spec.supports_foreground`.
2. Inspect BOTH `out.stdout` AND `out.stderr` + `out.timed_out` + `out.status_code` via pure `classify_pm_cmd_output` and `classify_appops_cmd_output` (catches `"should not be set directly"`, `"SYSTEM_FIXED"`, `"Unknown operation"`, `"has not requested permission"`, `"Exception"`, `"Error:"`, non-zero exit with empty stderr).
3. Pre-fetch `PackageRuntimePermInfo` once for runtime categories so `pm revoke`/`pm grant`/`set-permission-flags` ONLY run on declared perms (huge speedup on `media` & `sms_calllog`!).
4. Distinct semantics for all 4 actions:
   - `"allow"` (`"default"`): clear `user-fixed` & `revoked-compat` flags (only if set when `rt_info` known), reset `--uid` + pkg AppOps to `"default"` (NEVER `"allow"` on special ops like `MANAGE_EXTERNAL_STORAGE`/`SYSTEM_ALERT_WINDOW`!), then for declared runtime perms that are revoked, run `pm grant --user <u_id>`.
   - `"foreground"`: clear `user-fixed` & `revoked-compat`, set `--uid` (for `uid_scoped` ops) to `"foreground"` + reset pkg override to `"default"` (with pkg fallback if `--uid` rejected).
   - `"ignore"` (`Palsukan`): **NO `pm revoke`!** Set `--uid` to `"ignore"` + reset conflicting pkg override to `"default"`; if `--uid` fails on a runtime op, try pkg `"ignore"` + `cmd package set-permission-flags --user <u_id> <pkg> <perm> revoked-compat` fallback. For `!uid_scoped` ops, set pkg `"ignore"`.
   - `"revoke"` (`"deny"`, `Cabut`): run `pm revoke --user <u_id>` on declared currently-granted runtime perms (skip already-revoked & undeclared; detect `SYSTEM_FIXED`), set runtime-backed ops to `"ignore"` (`--uid` + clear pkg to `default` + `revoked-compat` fallback if `--uid` fails), and set non-runtime/special ops (`!spec.has_runtime_perms || op.is_special`) to `"deny"` (with `"ignore"` fallback if OEM rejects `"deny"`).
5. Fail with clear diagnostic `Err(...)` whenever `ok_count == 0` (even if stderr was empty!).
6. Add `apply_lock_flags_for_category` (`pm set-permission-flags --user <u_id> <pkg> <perm> user-set user-fixed` with single-flag fallback) and `clear_lock_flags_for_category` (`pm clear-permission-flags --user <u_id> <pkg> <perm> user-fixed`).

**Exact Code:**

```rust
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
                let primary =
                    run_appops_set(&cmd_bin, &user_str, package_name, op.name, "default", op.uid_scoped);
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
                    match run_appops_set(&cmd_bin, &user_str, package_name, op.name, "foreground", true) {
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
                    match run_appops_set(&cmd_bin, &user_str, package_name, op.name, "foreground", false)
                    {
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
                    match run_appops_set(&cmd_bin, &user_str, package_name, op.name, "ignore", true) {
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
                    match run_appops_set(&cmd_bin, &user_str, package_name, op.name, "ignore", false) {
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
                        // Already revoked; count as satisfied so pure AppOps sync still succeeds
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
        let target_mode = if use_deny_for_non_runtime && (!spec.has_runtime_perms || op.is_special) {
            "deny"
        } else {
            "ignore"
        };

        if op.uid_scoped {
            match run_appops_set(cmd_bin, user_str, package_name, op.name, target_mode, true) {
                CmdOutcome::Ok => {
                    ok_count += 1;
                    let _ = run_appops_set(cmd_bin, user_str, package_name, op.name, "default", false);
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
    let user_str = user_id.to_string();
    let mut cleared = 0usize;

    for perm in perms {
        if rt_info.user_section_found && !rt_info.user_fixed.contains(perm) {
            continue;
        }
        if let Ok(out) = run_cmd(
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
        ) {
            if matches!(classify_pm_cmd_output(&out), CmdOutcome::Ok) {
                cleared += 1;
            }
        }
    }
    cleared
}

/// Bulk revokes a single permission category across a list of user packages (user 0 wrapper).
pub fn bulk_revoke_category(category: &str, packages: &[String]) -> Result<usize, String> {
    bulk_revoke_category_with_uids(category, packages, &HashMap::new())
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
```

---

### Edit Block 5 — Monitor `Akses Izin`: `get_privacy_access_log` & `parse_dumpsys_appops_op` (`scopes.rs:326-475`)

**Fixes:** `D-11`, `CB-05`

**Exact Improvements:**
1. **Bounded parallel `dumpsys appops --op`** via `std::thread::scope` with max `4` concurrent workers + `AtomicUsize` work queue (borrows `uid_map` with zero cloning, adds `READ_MEDIA_VISUAL_USER_SELECTED` for Android 14+).
2. **Exact op-name token boundary** (`matches_op_header`: `strip_prefix(op_name)` followed by `' '`, `'('`, or `':'`) — `CAMERA` NEVER matches `CAMERA_SANDBOXED`.
3. **Strict `(uid/pkg)` mode tuple parsing** (`parse_dumpsys_op_mode` + tracking separate `Uid ...:` op header `current_uid_op_mode` combined via `combine_uid_and_pkg_mode`!) — handles BOTH inline `CAMERA (allow/ignore):` / `CAMERA (allow / switch ignore):` AND separate UID-level `CAMERA (ignore):` before `Package ...:`!
4. **Protected package exclusion**: `is_protected_uid(current_uid) || is_protected_package(pkg)` (excludes `dev.silo.app`, keyboards, KSU managers, system UI, isolated UIDs).
5. **Scan ALL `Access:` lines under the matching op** (do NOT stop after the first line!): keep `best_event: Option<PrivacyAccessEvent>` favoring `running_now == true` over `false`, then smallest `age_secs`, flushing on next `Uid`, `Package`, next op header (`is_any_op_header`), or EOF. Ignore `Reject:` lines!
6. Overflow-safe `parse_android_uid_token` using `checked_mul` / `checked_add`.

**Exact Code:**

```rust
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
    let token = trimmed_line
        .split([' ', '(', ':'])
        .next()
        .unwrap_or("");
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
```

---

### Edit Block 6 — `PermissionEnforcer`, `PermStateWatcher` (`inotify`), `EnforcerMetrics`, `top_activity_app_uids`, Startup Helper, Legacy Non-Overwrite, `count_live_ignored_ops` (`scopes.rs:531-700`)

**Fixes:** `D-05`, `D-18`, `D-27`, `CB-03`, `CB-10`

**Key Mechanics Verified:**
1. **`top_activity_app_uids()` (`D-18`)**: Reads `/proc/<pid>/oom_score_adj` and `/proc/<pid>/status`, keeping ONLY user app UIDs (`(uid % 100_000) >= 10_000 && (uid % 100_000) < 90_000`) with `adj <= 0` (`FOREGROUND_APP_ADJ = 0`). Background Foreground Services (`adj == 50..100`) are **NOT** considered top foreground, so `Auto-Cabut` fires immediately when leaving an app that holds a background FGS!
2. **Shared-UID Safe `is_pkg_in_top_fg()` (`D-18`, `CB-03`)**: Looks up `pkg_to_uid.get(pkg)` in `top_fg_uid_set` instead of mapping through single-value `uid_map: HashMap<u32, String>`, so all packages sharing a UID are accurately recognized as foreground!
3. **Zero-False-Positive 3-State Drift Decision (`evaluate_category_drift`)**:
   - `DriftAction::AlreadyEnforced` -> ZERO `pm revoke`, ZERO `cmd appops`, ZERO force-stop loop!
   - `DriftAction::NeedsFullRevoke` -> runtime perm is currently granted (and not all-`SYSTEM_FIXED`) -> `set_app_permission_action_for_user(..., "revoke", user_id)` + `apply_lock_flags_for_category` (when locked).
   - `DriftAction::NeedsAppOpsOnlyRepair` -> runtime perm already revoked (or AppOps-only category, or `SYSTEM_FIXED`) and only AppOps drifted -> `enforce_category_appops_blocked` WITHOUT calling `pm revoke`!
4. **Dedicated `run_permission_enforcer_loop` + `PermStateWatcher` (`inotify`)**:
   - Runs on its own thread completely OUTSIDE `state_mtx` (snapshots `(ScopesConfig, screen_on, pkg_to_uid)` via closure in <1ms).
   - On startup, runs `enforce_locked_guards_once` asynchronously so boot isn't delayed and `locked_deny` is enforced at startup (`CB-10`).
   - Watches `/data/misc_de/0/apexdata/com.android.permission`, `/data/system/users/0`, `/data/system` (filename-filtered for `"appops"`/`"permission"`), plus dynamic multi-user paths `/data/misc_de/<u_id>/apexdata/com.android.permission` and `/data/system/users/<u_id>` when guarded packages have `user_id != 0`.
   - Graceful fallback to chunked sleep when `inotify` or Android dirs are unavailable (host test env / non-root).
   - Fast active-guard poll (`1500ms` when `screen_on && !guards.is_empty()`, `5000ms` when `!screen_on`), inspecting ONLY packages that are currently in top foreground, just entered/left foreground, triggered by `inotify` (with 120ms burst debounce), or due for 15s background sweep.
   - Single inspection per package per tick (`inspect_app_permissions_detailed`), NEVER double-inspecting when both `auto_revoke_on_bg` and `locked_deny` exist.
5. **Legacy v2 `sync_all_scopes` Non-Overwrite (`CB-10`)**:
   - Only applies legacy `scope` categories that are `true` AND NOT managed by v3 `cfg.guards[pkg]` (`locked_deny` ∪ `auto_revoke_on_bg`). NEVER resets disabled legacy booleans to `"default"` during boot sync!

**Exact Code:**

```rust
/// Scans `/proc/<pid>/oom_score_adj` and `/proc/<pid>/status` for top-visible foreground
/// user app UIDs (`oom_score_adj <= 0`, i.e., Android `FOREGROUND_APP_ADJ = 0`).
/// Deliberately excludes background Foreground Services (`oom_score_adj == 50..100`) so
/// `Auto-Cabut` triggers immediately when leaving an app that keeps an FGS running.
pub fn top_activity_app_uids() -> Vec<u32> {
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
        if adj > 0 {
            continue;
        }
        if let Ok(status) = fs::read_to_string(path.join("status")) {
            if let Some(uid) = parse_proc_status_uid(&status) {
                if !is_protected_uid(uid) && !uids.contains(&uid) {
                    uids.push(uid);
                }
            }
        }
    }
    uids
}

/// Shared-UID-safe foreground check: resolves `pkg` via `pkg_to_uid` so sibling packages
/// sharing the same UID are all recognized as foreground while their UID has `oom_score_adj <= 0`.
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
            // All granted perms are SYSTEM_FIXED: cannot pm revoke, fall through to verify AppOps only
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
        // If runtime status was unknown for a runtime category, run full revoke to be safe;
        // if runtime is confirmed revoked (or AppOps-only / SYSTEM_FIXED), repair AppOps ONLY!
        if spec.has_runtime_perms && !rt_info.user_section_found {
            return DriftAction::NeedsFullRevoke;
        }
        return DriftAction::NeedsAppOpsOnlyRepair;
    }

    // 4. If AppOps query succeeded but NO op for this category appeared at all:
    //    - For AppOps-only categories (clipboard/background), absent means Android default (allow) -> repair!
    //    - For runtime categories where runtime perms are confirmed revoked, absent op means default -> repair AppOps only!
    if appops_query_ok && !any_op_present {
        if !spec.has_runtime_perms {
            return DriftAction::NeedsAppOpsOnlyRepair;
        }
        if rt_info.user_section_found {
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

/// Lightweight Linux `inotify` watcher for Android permission & AppOps state directories.
/// Gracefully returns `None` on non-Android/host environments where paths do not exist.
struct PermStateWatcher {
    fd: i32,
    /// Watch descriptor -> whether filenames must be filtered for `"appops"` / `"permission"`.
    wd_filter: HashMap<i32, bool>,
    watched_paths: HashSet<String>,
}

impl PermStateWatcher {
    fn new() -> Option<Self> {
        let fd = unsafe { libc::inotify_init1(libc::IN_NONBLOCK | libc::IN_CLOEXEC) };
        if fd < 0 {
            return None;
        }
        let mut watcher = Self {
            fd,
            wd_filter: HashMap::new(),
            watched_paths: HashSet::new(),
        };
        watcher.add_dir("/data/misc_de/0/apexdata/com.android.permission", false);
        watcher.add_dir("/data/system/users/0", true);
        watcher.add_dir("/data/system", true);

        if watcher.wd_filter.is_empty() {
            // None of the Android paths exist (e.g., host Linux test environment)
            return None;
        }
        Some(watcher)
    }

    fn add_dir(&mut self, path: &str, filter_names: bool) {
        if self.watched_paths.contains(path) || !Path::new(path).is_dir() {
            return;
        }
        let Ok(cpath) = CString::new(path) else {
            return;
        };
        let mask = libc::IN_CLOSE_WRITE | libc::IN_MOVED_TO | libc::IN_CREATE;
        let wd = unsafe { libc::inotify_add_watch(self.fd, cpath.as_ptr(), mask) };
        if wd >= 0 {
            self.wd_filter.insert(wd, filter_names);
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
            self.add_dir(&apex, false);
            self.add_dir(&legacy, true);
        }
    }

    /// Polls `inotify` fd up to `timeout_ms`. Returns `true` iff a relevant permission/appops
    /// file event occurred.
    fn wait_event(&self, timeout_ms: i32) -> bool {
        let mut pfd = libc::pollfd {
            fd: self.fd,
            events: libc::POLLIN,
            revents: 0,
        };
        let rc = unsafe { libc::poll(&mut pfd, 1, timeout_ms) };
        if rc <= 0 || (pfd.revents & libc::POLLIN) == 0 {
            return false;
        }
        self.drain_events()
    }

    fn drain_events(&self) -> bool {
        #[repr(C, align(8))]
        struct AlignedBuf([u8; 4096]);

        let mut buf = AlignedBuf([0u8; 4096]);
        let hdr_size = std::mem::size_of::<libc::inotify_event>();
        let mut relevant = false;

        loop {
            let n = unsafe {
                libc::read(
                    self.fd,
                    buf.0.as_mut_ptr().cast::<libc::c_void>(),
                    buf.0.len(),
                )
            };
            if n <= 0 {
                break;
            }
            let total = n as usize;
            let mut offset = 0usize;
            while offset + hdr_size <= total {
                let ev = unsafe {
                    std::ptr::read_unaligned(
                        buf.0.as_ptr().add(offset).cast::<libc::inotify_event>(),
                    )
                };
                let name_len = ev.len as usize;
                let step = hdr_size.saturating_add(name_len);
                let name_end = offset.saturating_add(step).min(total);

                if let Some(&filter_names) = self.wd_filter.get(&ev.wd) {
                    if !filter_names {
                        relevant = true;
                    } else if name_len > 0 && offset + hdr_size < name_end {
                        let raw_name = &buf.0[offset + hdr_size..name_end];
                        let nul_trimmed = raw_name.split(|&b| b == 0).next().unwrap_or(&[]);
                        let name = String::from_utf8_lossy(nul_trimmed).to_ascii_lowercase();
                        if name.contains("appops") || name.contains("permission") {
                            relevant = true;
                        }
                    }
                }

                if step == 0 || offset.saturating_add(step) > total {
                    break;
                }
                offset += step;
            }
        }
        relevant
    }
}

impl Drop for PermStateWatcher {
    fn drop(&mut self) {
        if self.fd >= 0 {
            unsafe {
                libc::close(self.fd);
            }
        }
    }
}

/// Real-time Daemon Permission Enforcer:
/// - **Auto-Revoke on Background (`auto_revoke_on_bg`)**: revokes configured categories whenever
///   a package leaves top-visible foreground (`oom_score_adj <= 0`, shared-UID safe) or screen turns off.
/// - **Strict Lock (`locked_deny`)**: event-driven (`inotify`) + 1.5s active-guard foreground watcher
///   with `user-fixed` proactive lock flags and zero false-positive force-stop loops.
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

    /// Backward-compatible wrapper accepting `uid_map: &HashMap<u32, String>`.
    pub fn tick(
        &mut self,
        cfg: &ScopesConfig,
        screen_on: bool,
        fg_uids: &[u32],
        uid_map: &HashMap<u32, String>,
    ) {
        let pkg_to_uid: HashMap<String, u32> = uid_map
            .iter()
            .map(|(&uid, pkg)| (pkg.clone(), uid))
            .collect();
        let _ = self.tick_with_index(cfg, screen_on, fg_uids, &pkg_to_uid, false);
    }

    /// Runs one lock-free enforcement cycle using shared-UID-safe `pkg_to_uid` and
    /// top-visible foreground UIDs (`oom_score_adj <= 0`). Returns human-readable event strings
    /// for every enforcement action actually performed.
    pub fn tick_with_index(
        &mut self,
        cfg: &ScopesConfig,
        screen_on: bool,
        top_fg_uids: &[u32],
        pkg_to_uid: &HashMap<String, u32>,
        inotify_triggered: bool,
    ) -> Vec<String> {
        let mut events = Vec::new();
        if cfg.guards.is_empty() {
            self.was_foreground.clear();
            return events;
        }
        self.was_foreground.retain(|p| cfg.guards.contains_key(p));

        let fg_uid_set: HashSet<u32> = if screen_on {
            top_fg_uids.iter().copied().collect()
        } else {
            HashSet::new()
        };

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

            let is_fg = is_pkg_in_top_fg(pkg, screen_on, &fg_uid_set, pkg_to_uid);
            let was_fg = self.was_foreground.contains(pkg);
            let just_left_fg = was_fg && !is_fg;
            let just_entered_fg = !was_fg && is_fg;

            if is_fg {
                self.was_foreground.insert(pkg.clone());
            } else {
                self.was_foreground.remove(pkg);
            }

            let check_auto =
                !guard.auto_revoke_on_bg.is_empty() && !is_fg && (just_left_fg || do_periodic_sweep);
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
                                let msg = format!("Auto-revoked {} on {}", cat, pkg);
                                self.actions_count += 1;
                                self.last_event = Some(msg.clone());
                                events.push(msg);
                            }
                        }
                        DriftAction::NeedsAppOpsOnlyRepair => {
                            let (ok, _, _) = enforce_category_appops_blocked(
                                &cmd_bin, &user_str, pkg, spec, true,
                            );
                            if ok > 0 {
                                let msg = format!("Auto-revoked {} AppOps on {}", cat, pkg);
                                self.actions_count += 1;
                                self.last_event = Some(msg.clone());
                                events.push(msg);
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
                    match evaluate_category_drift(
                        spec,
                        &detailed_ops,
                        &rt_info,
                        appops_ok,
                        false,
                    ) {
                        DriftAction::AlreadyEnforced => {}
                        DriftAction::NeedsFullRevoke => {
                            if set_app_permission_action_for_user(pkg, cat, "revoke", user_id)
                                .is_ok()
                            {
                                let _ = apply_lock_flags_for_category(pkg, cat, user_id);
                                let msg = format!("Re-locked {} on {}", cat, pkg);
                                self.actions_count += 1;
                                self.last_event = Some(msg.clone());
                                events.push(msg);
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
                                let msg = format!("Re-locked {} AppOps on {}", cat, pkg);
                                self.actions_count += 1;
                                self.last_event = Some(msg.clone());
                                events.push(msg);
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
/// (<1ms) to fetch `(ScopesConfig, screen_on, pkg_to_uid)`.
pub fn run_permission_enforcer_loop<F>(
    stop: Arc<AtomicBool>,
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

    while !stop.load(Ordering::Relaxed) {
        let (cfg, screen_on, pkg_to_uid) = snapshot_fn();

        if cfg.guards.is_empty() {
            enforcer.was_foreground.clear();
            sleep_interruptible(&stop, 1500);
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

        let wait_ms = if screen_on { 1500 } else { 5000 };
        let inotify_triggered = if let Some(ref w) = watcher {
            // Chunked poll (max 500ms per poll) so shutdown signal responds promptly
            let mut remaining = wait_ms;
            let mut hit = false;
            while remaining > 0 && !stop.load(Ordering::Relaxed) {
                let slice = remaining.min(500);
                if w.wait_event(slice) {
                    hit = true;
                    break;
                }
                remaining -= slice;
            }
            if hit && !stop.load(Ordering::Relaxed) {
                // Brief debounce for atomic XML write+rename bursts
                sleep_interruptible(&stop, 120);
                let _ = w.drain_events();
            }
            hit
        } else {
            sleep_interruptible(&stop, wait_ms as u64);
            false
        };

        if stop.load(Ordering::Relaxed) {
            break;
        }

        let (cfg, screen_on, pkg_to_uid) = snapshot_fn();
        if cfg.guards.is_empty() {
            continue;
        }
        let top_fg = if screen_on {
            top_activity_app_uids()
        } else {
            Vec::new()
        };
        for ev in enforcer.tick_with_index(&cfg, screen_on, &top_fg, &pkg_to_uid, inotify_triggered)
        {
            metrics.record(ev);
        }
    }
}

fn sleep_interruptible(stop: &AtomicBool, total_ms: u64) {
    let mut elapsed = 0u64;
    while elapsed < total_ms && !stop.load(Ordering::Relaxed) {
        let step = (total_ms - elapsed).min(150);
        thread::sleep(Duration::from_millis(step));
        elapsed += step;
    }
}
```

#### Legacy v2 `sync_all_scopes` Non-Overwrite (`scopes.rs:625-700`, `CB-10`, `D-27`):

Replace lines `625-700` with:

```rust
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
```

---

### Edit Block 7 — `ClipboardGuard` (`scopes.rs:701-795`)

**Fixes:** `D-17`, `CB-15`

**Exact Improvements:**
1. **`parse_clipboard_metadata_hash(dump: &str) -> Option<u64>`** (pure & unit-testable):
   - Tracks `in_primary_user` so an empty clipboard on Work Profile / Dual Messenger / Secure Folder (`user=95`, `user=150`) never masks User 0's active clip!
   - Checks **explicit empty clip markers** (`mPrimaryClip=null`, `mPrimaryClip: null`, `Primary clip: null`, `primaryClip=null`, `No primary clip`, `Primary clip is empty`, `Primary clip: empty`, `primaryClipPackage=null`, `mPrimaryClipPackage=null`).
   - NEVER rejects `ClipDescription { text/plain label=null timestamp=... }` because of `label=null` or `extras=null`!
   - Maintains Zero-Log Hygiene: hashes metadata lines ONLY (strips/skips raw `{T:...}` text payloads).
2. **`clear_clipboard_system() -> Result<(), String>`** + `ClipboardGuard::clear_now(&mut self)`:
   - Verifies BOTH `stdout` AND `stderr` via `clipboard_cmd_succeeded` (rejects `"Unknown command"`, `"Usage:"`, `"Exception"`, `"Error:"`, `"Permission Denial"`, `"No shell command"` even if exit code is `0`!).
   - Tries in order:
     1. `cmd clipboard clear`
     2. `cmd clipboard set-text ""`
     3. `su 2000 -c ...` and `su -u 2000 -c ...` (KernelSU-Next compatible!) for `cmd clipboard clear` / `cmd clipboard set-text ''`
     4. SDK-aware `service call clipboard` fallback (verifying `Result: Parcel(00000000` with no exception):
        - API >= 34 (Android 14–16): `service call clipboard 3 s16 com.android.shell s16 "" i32 0 i32 0`
        - API == 33 (Android 13): `service call clipboard 3 s16 com.android.shell s16 "" i32 0`
        - API <= 32 (Android 12): `service call clipboard 2 s16 com.android.shell i32 0`

**Exact Code:**

```rust
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
    for args in [&["clipboard", "clear"][..], &["clipboard", "set-text", ""][..]] {
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
        &["call", "clipboard", "3", "s16", "com.android.shell", "s16", "", "i32", "0", "i32", "0"]
    } else if sdk == 33 {
        &["call", "clipboard", "3", "s16", "com.android.shell", "s16", "", "i32", "0"]
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
```

---

### Edit Block 8 — F12 Uninstall Scope Cleanup (`CB-19`)

Insert immediately before `#[cfg(test)] mod tests` (right after `probe_clipboard_metadata_hash`):

```rust
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
        for cat in guard.auto_revoke_on_bg.iter().chain(guard.locked_deny.iter()) {
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
                    .unwrap_or(false);
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
```

---

## 3. Coordination Notes for `main.rs` & `config.rs` (M1 Explorer 2 / Worker Wiring)

To ensure ZERO deadlocks and 100% alignment between `scopes.rs` and `main.rs` / `config.rs`:
1. **`config.rs` `SiloConfig::sanitize()`**: Enforce mutual exclusivity of `AppPermGuard` (`locked_deny` takes precedence and strips any duplicate category from `auto_revoke_on_bg`, per `PROJECT.md:110`).
2. **`main.rs` `SetAppPermissionAction`**:
   - Resolve `user_id = resolve_pkg_user_id(&package_name, &pkg_to_uid)` without holding `state_mtx`.
   - Call `set_app_permission_action_for_user(&package_name, &category, &action, user_id)` **OUTSIDE** `state_mtx`.
   - On `Ok(live)`: if normalized `action` is `"allow" | "default" | "foreground" | "ignore"` (any explicit manual override loosening a lock!), briefly lock `state_mtx` to remove `&category` from `config.scopes.guards[pkg].locked_deny` (removing the entry if empty) and `save_config()`, then drop `state_mtx` and call `clear_lock_flags_for_category(&package_name, &category, user_id)` (already cleared for `allow`/`foreground`, also clears for `ignore` so `Kunci Blokir` never fights the user!).
3. **`main.rs` `SetAppPermGuard`**:
   - Lock `state_mtx` briefly (<1ms): diff `old_locked` vs new sanitized `locked_deny`, save config, clone `scopes` response, DROP `state_mtx`!
   - **OUTSIDE `state_mtx`**:
     - For each `cat` in `added_locked`: call `set_app_permission_action_for_user(&pkg, cat, "revoke", user_id)` + `apply_lock_flags_for_category(&pkg, cat, user_id)`.
     - For each `cat` in `removed_locked`: call `clear_lock_flags_for_category(&pkg, cat, user_id)`.
4. **`main.rs` Dedicated `PermissionEnforcer` Thread**:
   - Store `enforcer_metrics: Arc<EnforcerMetrics>` on `DaemonState` (read in `status()` with ZERO subprocesses / ZERO lock contention!).
   - Spawn `run_permission_enforcer_loop(stop, metrics, snapshot_fn)` in `run_daemon()` (and remove `st.enforcer.tick(...)` from the 5s supervisor loop!).
   - On `ImportConfigJson`, spawn a one-shot thread running `enforce_locked_guards_once(&scopes_cfg, &pkg_to_uid)` after saving config.
5. **`main.rs` `ClearClipboardNow` & `ClipboardGuard`**:
   - Call `clear_clipboard_system()` OUTSIDE `state_mtx` (or hold a dedicated `Mutex<ClipboardGuard>` outside `state_mtx` for supervisor `clipboard.tick(&scopes_cfg)`!).
6. **`main.rs` `--uninstall-cleanup`**:
   - Run full `--emergency-flush` + load `config.json` + build `pkg_to_uid` from `/data/system/packages.list` + call `reset_all_scopes_for_uninstall(&cfg.scopes, &pkg_to_uid)`.

---

## 4. Comprehensive Unit Test Plan (`daemon/src/scopes.rs` `#[cfg(test)] mod tests`)

Keep all 3 existing tests passing and add the following **14 targeted unit tests** (17 total in `scopes.rs`, zero Android runtime dependencies, deterministic, sub-10ms total):

1. **`perm_categories_metadata_and_android14_coverage`** (`D-02`, `CB-09`):
   - Assert exact 10 categories; `has_runtime_perms == false` ONLY for `clipboard` and `background`; `supports_foreground == true` ONLY for `camera`, `microphone`, `location`, `sensors`.
   - Assert `media.runtime_perms` includes `READ_MEDIA_VISUAL_USER_SELECTED` and does NOT contain `MANAGE_EXTERNAL_STORAGE`; assert `MANAGE_EXTERNAL_STORAGE` is `is_special == true, uid_scoped == false`.
   - Assert `background.appops` has `SYSTEM_ALERT_WINDOW` and `GET_USAGE_STATS` as `is_special == true`, and `background.runtime_perms` is empty.
   - Assert `sms_calllog.runtime_perms` includes `SEND_SMS` and `RECEIVE_MMS`; `sensors.runtime_perms` includes `BODY_SENSORS_BACKGROUND`.
   - Assert `category_for_op("READ_MEDIA_VISUAL_USER_SELECTED") == Some("media")`, `category_for_op("SEND_SMS") == Some("sms_calllog")`.

2. **`rejects_unsupported_foreground_category`** (`CB-02`):
   - Assert `set_app_permission_action("com.example.app", "clipboard", "foreground").is_err()`
   - Assert `set_app_permission_action("com.example.app", "background", "foreground").is_err()`
   - Assert `set_app_permission_action("com.example.app", "contacts", "foreground").is_err()`
   - Assert `set_app_permission_action("com.example.app", "media", "foreground").is_err()`
   - Assert `set_app_permission_action("com.example.app", "sms_calllog", "foreground").is_err()`
   - Assert `set_app_permission_action("com.example.app", "background_location", "foreground").is_err()`

3. **`parse_appops_uid_mode_never_overwritten_by_pkg_history`** (`D-01`, `CB-01` — direct screenshot repro!):
   - Feed both line orders:
     - `Uid mode: CAMERA: ignore\nCAMERA: allow; time=+3m12s ago; duration=+15s\n`
     - `CAMERA: allow; time=+3m12s ago; duration=+15s\nUid mode: CAMERA: ignore\n`
   - Assert `effective_mode == "ignore"`, `uid_mode == Some("ignore")`, `pkg_mode == Some("allow")`, `last_access == Some("+3m12s ago")` in BOTH orders!
   - Also test `Uid mode: RECORD_AUDIO: errored\nRECORD_AUDIO: allow; time=+5s ago` -> `effective_mode == "deny"`.
   - Also test `Uid mode: FINE_LOCATION: foreground\nFINE_LOCATION: allow; time=+12s ago (running)` -> `effective_mode == "foreground"`, `running_now == true`, `last_access == Some("+12s ago")`.

4. **`combine_uid_and_pkg_mode_full_precedence_matrix`** (`D-01`):
   - Exhaustively verify all key `(uid_mode, pkg_mode)` pairs: `(deny, allow) -> deny`, `(errored, default) -> deny`, `(ignore, allow) -> ignore`, `(ignore, foreground) -> ignore`, `(ignore, deny) -> deny`, `(foreground, allow) -> foreground`, `(foreground, ignore) -> ignore`, `(allow, default) -> allow`, `(default, ignore) -> ignore`, `(None, ignore) -> ignore`, `(Some("ignore"), None) -> ignore`, `(None, None) -> default`.

5. **`aggregate_category_appops_mode_is_fail_closed`** (`D-03`):
   - Assert `aggregate_category_appops_mode(&["ignore", "allow"]) == "allow"`
   - Assert `aggregate_category_appops_mode(&["deny", "allow"]) == "allow"`
   - Assert `aggregate_category_appops_mode(&["ignore", "foreground"]) == "foreground"`
   - Assert `aggregate_category_appops_mode(&["ignore", "deny"]) == "ignore"`
   - Assert `aggregate_category_appops_mode(&["deny", "deny"]) == "deny"`
   - Assert `aggregate_category_appops_mode(&["deny", "default"]) == "deny"`
   - Assert `aggregate_category_appops_mode(&["ignore", "default"]) == "ignore"`
   - Assert `aggregate_category_appops_mode(&[]) == "default"`
   - Assert `aggregate_category_appops_mode(&["default"]) == "default"`

6. **`parse_dumpsys_package_runtime_perms_multiuser_and_flags`** (`D-02`, `CB-08`):
   - Multi-user sample containing `declared permissions:`, `requested permissions:` (with `CAMERA`, `RECORD_AUDIO`, `READ_MEDIA_IMAGES`, `ACCESS_FINE_LOCATION` — where `ACCESS_FINE_LOCATION` is only in `requested permissions:` and omitted from `runtime permissions:`), `install permissions:`, `User 0:` (`CAMERA: granted=false, flags=[ USER_SET|USER_FIXED]`, `RECORD_AUDIO: granted=true, flags=[ SYSTEM_FIXED|GRANTED_BY_DEFAULT]`, `READ_MEDIA_IMAGES: granted=false, flags=[ REVOKED_COMPAT]`), and `User 10:` (`CAMERA: granted=true, flags=[ USER_SET]`).
   - Verify `user_id = 0`: `CAMERA` declared & NOT granted & `user_fixed`; `RECORD_AUDIO` granted & `system_fixed`; `READ_MEDIA_IMAGES` declared & NOT granted & `revoked_compat`; `ACCESS_FINE_LOCATION` declared & NOT granted; `User 10` grant does NOT leak into `User 0`!
   - Verify `user_id = 10`: `CAMERA` granted (`true`), `RECORD_AUDIO` NOT granted!
   - Verify `build_app_live_permissions`: `camera.runtime_granted == Some(false)`, `microphone.runtime_granted == Some(true)` & `system_fixed == true`, `location.runtime_granted == Some(false)` (declared untouched), `contacts.runtime_granted == None` (undeclared!), `clipboard.runtime_granted == None` & `has_runtime_perms == false`.

7. **`cmd_output_classifiers_inspect_stdout_stderr_and_timeout`** (`D-02`, `CB-02`):
   - Verify `classify_pm_cmd_output`:
     - `timed_out = true` -> `Failed`
     - `status_code = 0`, `stdout = "SecurityException: Permission android.permission.CAMERA is SYSTEM_FIXED"` -> `SystemFixed`
     - `status_code = 1`, `stdout = "Package has not requested permission ..."` -> `SkippedUndeclared`
     - `status_code = 1`, `stdout = ""`, `stderr = ""` -> `Failed("exit code 1")`
   - Verify `classify_appops_cmd_output`:
     - `status_code = 0`, `stdout = "Error: CAMERA should not be set directly"` -> `Failed`
     - `status_code = 1`, `stderr = "Unknown operation: READ_MEDIA_VISUAL_USER_SELECTED"` -> `SkippedUndeclared`

8. **`enforcer_drift_prevents_false_positive_force_stop_loop`** (`D-01`, `D-05`, `CB-03`):
   - Simulate `com.sec.android.app.camera` after `Cabut`: `rt_info` has `CAMERA` declared & NOT granted, `detailed_ops` parsed from `Uid mode: CAMERA: ignore\nCAMERA: allow; time=+3m ago`.
   - Assert `evaluate_category_drift(camera_spec, &ops, &rt, true, false) == DriftAction::AlreadyEnforced`!
   - Now flip `rt_info.granted.insert("android.permission.CAMERA")` -> assert `DriftAction::NeedsFullRevoke`!
   - Now mark `CAMERA` as `SYSTEM_FIXED` + `granted=true`, with `Uid mode: CAMERA: ignore` -> assert `DriftAction::AlreadyEnforced` (no infinite loop on `SYSTEM_FIXED`!); with `Uid mode: CAMERA: allow` -> assert `DriftAction::NeedsAppOpsOnlyRepair` (repairs AppOps without calling `pm revoke`!).
   - Now test stale v2 pkg-only override (`Uid mode: CAMERA: allow` + `CAMERA: ignore`) with `runtime_granted = false` -> assert `DriftAction::NeedsAppOpsOnlyRepair`!

9. **`enforcer_shared_uid_and_fgs_top_fg_logic`** (`D-18`, `CB-03`):
   - Construct `pkg_to_uid` where `com.samsung.a => 10250` and `com.samsung.b => 10250` (shared UID!), plus `com.spy.fgs => 10300`.
   - When `top_fg_uids = {10250}` and `screen_on = true`: assert BOTH `is_pkg_in_top_fg("com.samsung.a", ...)` AND `is_pkg_in_top_fg("com.samsung.b", ...)` are `true`, while `is_pkg_in_top_fg("com.spy.fgs", ...)` (whose FGS has `oom_score_adj = 50`, thus NOT in `top_fg_uids`) is `false`!
   - When `screen_on = false`: assert ALL return `false`.
   - Assert `resolve_pkg_user_id` extracts `0` for `10250`, `10` for `1_010_250`, `95` for `9_510_250`, `150` for `15_010_250`.

10. **`parse_dumpsys_appops_op_boundary_tuple_protected_and_multi_access`** (`D-11`, `CB-05`):
    - Sample with:
      1. `Uid u0a277: Package com.example.cam:` containing `CAMERA_SANDBOXED (allow): Access: [top-s] ... (-1s)` (MUST be ignored when querying `CAMERA`!), followed by `CAMERA (allow/ignore):` with TWO `Access:` lines — first `[top-s] (-3h)` and second `[bg] (-2s) (running)` — plus a `Reject: [bg] (-1s)` line. Assert 1 event emitted with `mode == "ignore"`, `running_now == true`, `age_secs == 2`!
      2. `Uid u0a278:` with UID-level `CAMERA (ignore):` BEFORE `Package com.example.split:` `CAMERA (allow):` `Access: [top-s] (-45s)` -> assert `mode == "ignore"`!
      3. `Uid u0a279: Package com.samsung.android.honeyboard:` (protected package with UID >= 10000!) -> assert excluded!
      4. `Uid u95a50: Package com.example.dual:` -> assert `uid == 9_510_050` parsed cleanly!

11. **`clipboard_metadata_hash_handles_label_null_and_empty_markers`** (`D-17`, `CB-15`):
    - Sample with `Per-user clipboard (user=0):\n  ClipDescription { text/plain label=null timestamp=1763850000000 }\n  primaryClipPackage=com.example.browser\n  mPrimaryClip=ClipData { text/plain {T:my_secret_password} }\nPer-user clipboard (user=95):\n  mPrimaryClip=null\n` -> assert `parse_clipboard_metadata_hash` returns **`Some(h1)`**!
    - Change ONLY the `{T:different_secret}` line while keeping metadata identical -> assert `Some(h2) == Some(h1)` (proves raw clipboard content is NEVER hashed!).
    - Sample with `mPrimaryClip=null`, `Primary clip: null`, `mPrimaryClip: null`, `No primary clip` under user 0 -> assert ALL return **`None`**!

12. **`service_call_parcel_ok_validation`** (`D-17`, `CB-15`):
    - Assert `Result: Parcel(00000000    '....')` -> `true`.
    - Assert `Result: Parcel(ffffffff ... SecurityException)` -> `false`.
    - Assert `Error: ...` or `status_code = 1` -> `false`.

13. **`collect_uninstall_reset_plan_merges_apps_and_guards_safely`** (`CB-19`):
    - Populate `ScopesConfig` with:
      - `scopes.apps["com.example.v2"]` having `isolate_contacts = true, block_camera_mic = true`
      - `scopes.guards["com.example.v2"]` having `locked_deny = ["location"]`
      - `scopes.guards["com.example.v3"]` having `auto_revoke_on_bg = ["microphone"], locked_deny = ["clipboard"]`
      - `scopes.apps["com.android.systemui"]` (protected!) and `scopes.guards["bad;pkg"]` (invalid!)
    - Assert plan contains `com.example.v2 -> {"contacts", "camera", "microphone", "location"}` and `com.example.v3 -> {"microphone", "clipboard"}`, and EXCLUDES `com.android.systemui` and `bad;pkg`!

14. **`enforcer_metrics_thread_safe_recording`** (`D-04`, `D-05`):
    - Verify `EnforcerMetrics::new()` starts at `0` / `None`, `record(...)` increments `actions_count()` and updates `last_event()`.

---

## 5. Verification Command for M1 Worker

After applying all M1 edits (including `protocol.rs`, `config.rs`, `main.rs`, `ipc.rs`, `sys.rs`, `scopes.rs`, `Cargo.toml`):
```bash
cd /home/antigravity/terenzy/Silo/daemon && cargo clippy --all-targets -- -D warnings && cargo test --verbose
```
All existing 26+ tests plus the 14 new `scopes.rs` tests must compile with **zero warnings** and pass **100%**.
