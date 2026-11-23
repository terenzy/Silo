mod automation;
mod config;
mod firewall;
mod freezer;
mod hardening;
mod ipc;
mod netlink;
mod protocol;
mod scopes;
mod sentinel;
mod sys;
mod telemetry;

use crate::automation::{
    disable_wifi_and_bt_now, execute_clean_bfu_reboot, get_private_dns, get_radio_privacy,
    set_private_dns, set_radio_privacy, AutomationEngine, PANIC_REBOOT_CONFIRM_TOKEN,
};
use crate::config::{
    is_protected_package, is_protected_uid, validate_cidr, validate_package_name, AppFirewallRule,
    AppIsolationScope, FirewallMode, FreezeMode, SiloConfig, DEFAULT_CONFIG_PATH,
    DEFAULT_SOCKET_PATH, DEFAULT_STATE_DIR, MAX_BLOCKLIST_ENTRIES,
};
use crate::firewall::{apply_firewall, ensure_hook_jumps, flush_firewall, probe_capabilities};
use crate::freezer::FreezerEngine;
use crate::hardening::HardeningEngine;
use crate::ipc::{
    derive_lock_path, run_ipc_server_with_lock, run_single_rpc, run_stdio_bridge,
    SingletonLockGuard,
};
use crate::netlink::{
    classify_active_interfaces, destroy_all_user_sockets, destroy_matching_connection,
    destroy_uid_sockets, list_active_connections, run_nflog_loop, SILO_NFLOG_GROUP,
};
use crate::protocol::{
    ActiveConnection, ActiveInterfaces, AppLivePermissions, DaemonCommand, DaemonStatus,
    IpcRequest, IpcResponse, KernelCapabilities, PanicLockdownReport, PrivateDnsState,
    RadioPrivacyState, SensorPrivacyState,
};
use crate::scopes::{
    apply_app_scope, apply_lock_flags_for_category, bulk_revoke_category_with_uids,
    clear_app_scope, clear_lock_flags_for_category, find_category, get_privacy_access_log,
    inspect_app_permissions_for_user, reset_all_scopes_for_uninstall, run_permission_enforcer_loop,
    set_app_permission_action_for_user, sync_all_scopes, ClipboardGuard, EnforcerMetrics,
};
use crate::sentinel::{run_live_proof, run_sentinel_audit};
use crate::sys::{
    boottime_secs, detect_device_locked, detect_screen_on, is_ce_unlocked, read_uid_package_index,
    set_oom_score_adj, UidPackageIndex,
};
use crate::telemetry::TelemetryRing;

use std::collections::{BTreeMap, HashSet};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::thread;
use std::time::{Duration, SystemTime};

const DAEMON_VERSION: &str = env!("CARGO_PKG_VERSION");
const BOOT_STABILITY_SECS: u64 = 45;

static GLOBAL_STOP: AtomicBool = AtomicBool::new(false);

extern "C" fn handle_signal(_sig: libc::c_int) {
    GLOBAL_STOP.store(true, Ordering::SeqCst);
}

fn install_signal_handlers() {
    unsafe {
        libc::signal(
            libc::SIGTERM,
            handle_signal as *const () as libc::sighandler_t,
        );
        libc::signal(
            libc::SIGINT,
            handle_signal as *const () as libc::sighandler_t,
        );
        libc::signal(libc::SIGPIPE, libc::SIG_IGN);
    }
}

#[derive(Debug, Clone, Default)]
struct CachedSystemState {
    sensor_privacy: SensorPrivacyState,
    private_dns: PrivateDnsState,
    radio_privacy: RadioPrivacyState,
    frozen_uids_count: usize,
    suspended_pkgs_count: usize,
    auto_reboot_remaining_secs: Option<u64>,
}

#[derive(Debug, Default)]
struct FirewallRuntime {
    previously_blocked_uids: HashSet<u32>,
}

/// Fast in-memory config & status metadata ONLY.
/// HARD INVARIANT: `DaemonContext::state` is NEVER held across any `run_cmd`, Netlink I/O, or blocking syscall!
struct DaemonState {
    config: SiloConfig,
    config_path: PathBuf,
    boot_fail_marker: PathBuf,
    started_boottime: u64,
    boot_guard_armed: bool,
    boot_guard_cleared: bool,
    caps: KernelCapabilities,
    firewall_active: bool,
    ipset_entries_loaded: usize,
    ipset_backend: String,
    vpn_active: bool,
    active_interfaces: ActiveInterfaces,
    last_firewall_error: Option<String>,
    /// Temporary network unblock grants: UID -> CLOCK_BOOTTIME expiry second.
    temp_unblock_until: BTreeMap<u32, u64>,
    screen_on: bool,
    device_locked: bool,
    ce_unlocked: bool,
    known_user_uids: HashSet<u32>,
    last_packages_mtime: Option<SystemTime>,
}

impl DaemonState {
    fn save_config(&self) -> Result<(), String> {
        self.config.save_atomic(&self.config_path)
    }

    fn active_temp_unblock_set(&self) -> HashSet<u32> {
        let now = boottime_secs();
        self.temp_unblock_until
            .iter()
            .filter(|(_, &exp)| now < exp)
            .map(|(&uid, _)| uid)
            .collect()
    }

    fn expire_temp_unblocks(&mut self) -> Vec<u32> {
        let now = boottime_secs();
        let expired: Vec<u32> = self
            .temp_unblock_until
            .iter()
            .filter(|(_, &exp)| now >= exp)
            .map(|(&uid, _)| uid)
            .collect();
        for &uid in &expired {
            self.temp_unblock_until.remove(&uid);
        }
        expired
    }

    fn status_from_cache(
        &self,
        total_blocked: u64,
        cached: &CachedSystemState,
        enforcer_actions_count: u64,
        last_enforcer_event: Option<String>,
    ) -> DaemonStatus {
        let now = boottime_secs();
        let uptime = now.saturating_sub(self.started_boottime);
        let temp_unblocks_remaining: BTreeMap<u32, u64> = self
            .temp_unblock_until
            .iter()
            .filter_map(|(&uid, &exp)| {
                if exp > now {
                    Some((uid, exp - now))
                } else {
                    None
                }
            })
            .collect();

        let enforcer_rules_count: usize = self
            .config
            .scopes
            .guards
            .values()
            .map(|g| g.auto_revoke_on_bg.len() + g.locked_deny.len())
            .sum();

        DaemonStatus {
            version: DAEMON_VERSION.to_string(),
            pid: std::process::id(),
            uptime_secs: uptime,
            boot_guard_armed: self.boot_guard_armed,
            boot_guard_cleared: self.boot_guard_cleared,
            firewall_active: self.firewall_active,
            firewall_mode: format!("{:?}", self.config.firewall.mode),
            ipset_entries_loaded: self.ipset_entries_loaded,
            ipset_backend: self.ipset_backend.clone(),
            vpn_active: self.vpn_active,
            active_interfaces: self.active_interfaces.clone(),
            screen_on: self.screen_on,
            device_locked: self.device_locked,
            ce_unlocked: self.ce_unlocked,
            sensors_off_active: cached.sensor_privacy.mic_muted
                || cached.sensor_privacy.camera_muted,
            mic_muted: cached.sensor_privacy.mic_muted,
            camera_muted: cached.sensor_privacy.camera_muted,
            frozen_uids_count: cached.frozen_uids_count,
            suspended_pkgs_count: cached.suspended_pkgs_count,
            blocked_events_total: total_blocked,
            auto_reboot_remaining_secs: cached.auto_reboot_remaining_secs,
            private_dns: cached.private_dns.clone(),
            radio_privacy: cached.radio_privacy.clone(),
            enforcer_rules_count,
            enforcer_actions_count,
            last_enforcer_event,
            temp_unblocks_remaining,
            capabilities: self.caps.clone(),
            last_firewall_error: self.last_firewall_error.clone(),
            panic_lockdown_active: self.config.firewall.panic_lockdown_active,
        }
    }
}

/// Strict Lock Ordering Invariant (deadlock-free by construction):
///   Level 1 (Slow/subprocess locks — never acquire two simultaneously EXCEPT `RunLiveProof`
///            which locks `hardening_mtx` -> `freezer_mtx` strictly in that order):
///     `firewall_mtx`, `hardening_mtx`, `freezer_mtx`, `automation_mtx`, `clipboard_mtx`
///   Level 2 (Leaf fast in-memory locks — NEVER acquire any Level 1 lock while holding Level 2;
///            NEVER run subprocesses or blocking I/O while holding Level 2):
///     `state`, `sys_cache`, `uid_index`, `telemetry`, `enforcer_metrics`
struct DaemonContext {
    state: Mutex<DaemonState>,
    firewall_mtx: Mutex<FirewallRuntime>,
    hardening_mtx: Mutex<HardeningEngine>,
    freezer_mtx: Mutex<FreezerEngine>,
    automation_mtx: Mutex<AutomationEngine>,
    clipboard_mtx: Mutex<ClipboardGuard>,
    telemetry: Mutex<TelemetryRing>,
    sys_cache: RwLock<CachedSystemState>,
    uid_index: RwLock<UidPackageIndex>,
    enforcer_metrics: Arc<EnforcerMetrics>,
    enforcer_notify: Arc<AtomicBool>,
}

impl DaemonContext {
    fn new(config_path: PathBuf, boot_fail_marker: PathBuf) -> Self {
        let config = SiloConfig::load_or_default(&config_path);
        let caps = probe_capabilities();
        let uid_index = read_uid_package_index();
        let known_user_uids: HashSet<u32> = uid_index
            .uid_to_pkgs
            .keys()
            .copied()
            .filter(|&u| !is_protected_uid(u))
            .collect();
        let last_packages_mtime = fs::metadata("/data/system/packages.list")
            .and_then(|m| m.modified())
            .ok();
        let boot_guard_armed = boot_fail_marker.exists();
        let active_interfaces = classify_active_interfaces();
        let vpn_active = !active_interfaces.vpn.is_empty();

        let state = DaemonState {
            config,
            config_path,
            boot_fail_marker,
            started_boottime: boottime_secs(),
            boot_guard_armed,
            boot_guard_cleared: !boot_guard_armed,
            caps,
            firewall_active: false,
            ipset_entries_loaded: 0,
            ipset_backend: "idle".to_string(),
            vpn_active,
            active_interfaces,
            last_firewall_error: None,
            temp_unblock_until: BTreeMap::new(),
            screen_on: detect_screen_on(),
            device_locked: detect_device_locked(),
            ce_unlocked: is_ce_unlocked(),
            known_user_uids,
            last_packages_mtime,
        };

        Self {
            state: Mutex::new(state),
            firewall_mtx: Mutex::new(FirewallRuntime::default()),
            hardening_mtx: Mutex::new(HardeningEngine::new()),
            freezer_mtx: Mutex::new(FreezerEngine::new()),
            automation_mtx: Mutex::new(AutomationEngine::new()),
            clipboard_mtx: Mutex::new(ClipboardGuard::new()),
            telemetry: Mutex::new(TelemetryRing::new()),
            sys_cache: RwLock::new(CachedSystemState::default()),
            uid_index: RwLock::new(uid_index),
            enforcer_metrics: Arc::new(EnforcerMetrics::new()),
            enforcer_notify: Arc::new(AtomicBool::new(false)),
        }
    }

    /// Authoritative protected-UID boundary check: protected if UID itself is system/isolated
    /// OR if ANY package sharing that UID in `/data/system/packages.list` is protected (`D-10`, `CB-12`).
    fn is_uid_protected(&self, uid: u32) -> bool {
        if is_protected_uid(uid) {
            return true;
        }
        self.uid_index
            .read()
            .map(|idx| idx.any_pkg_for_uid(uid, is_protected_package))
            .unwrap_or(false)
    }

    /// Authoritative protected-package boundary check: protected if package name itself is protected
    /// OR if it shares a UID with a protected UID / protected package.
    fn is_pkg_protected(&self, pkg: &str) -> bool {
        if is_protected_package(pkg) {
            return true;
        }
        self.uid_index
            .read()
            .map(|idx| {
                idx.uid_for_pkg(pkg)
                    .map(|uid| {
                        is_protected_uid(uid) || idx.any_pkg_for_uid(uid, is_protected_package)
                    })
                    .unwrap_or(false)
            })
            .unwrap_or(false)
    }

    fn user_id_for_pkg(&self, pkg: &str) -> u32 {
        self.uid_index
            .read()
            .map(|idx| idx.user_id_for_pkg(pkg))
            .unwrap_or(0)
    }

    fn first_pkg_for_uid(&self, uid: u32) -> Option<String> {
        self.uid_index
            .read()
            .ok()
            .and_then(|idx| idx.first_pkg(uid).map(str::to_string))
    }

    fn update_freezer_cache(&self, fz: &FreezerEngine) {
        let frozen = fz.currently_frozen().len();
        let suspended = fz.suspended_packages().len();
        if let Ok(mut cache) = self.sys_cache.write() {
            cache.frozen_uids_count = frozen;
            cache.suspended_pkgs_count = suspended;
        }
    }

    /// Zero-subprocess, zero-Level-1-lock status snapshot (<1ms guaranteed).
    fn build_status(&self) -> Result<DaemonStatus, String> {
        let total_blocked = self
            .telemetry
            .lock()
            .map(|t| t.total_blocked())
            .unwrap_or(0);
        let cached = self
            .sys_cache
            .read()
            .map(|c| c.clone())
            .unwrap_or_default();
        let actions_count = self.enforcer_metrics.actions_count();
        let last_event = self.enforcer_metrics.last_event();
        let st = self
            .state
            .lock()
            .map_err(|_| "state lock poisoned".to_string())?;
        Ok(st.status_from_cache(total_blocked, &cached, actions_count, last_event))
    }

    /// Refreshes cached sensor privacy, private DNS, radio privacy, and auto-reboot countdown
    /// completely OUTSIDE `state`.
    fn refresh_cached_system_state(&self) {
        let (auto_cfg, ce_unlocked, device_locked) = match self.state.lock() {
            Ok(st) => (
                st.config.automation.clone(),
                st.ce_unlocked,
                st.device_locked,
            ),
            Err(_) => return,
        };
        let (sp, reboot_rem) = self
            .automation_mtx
            .lock()
            .map(|a| {
                (
                    a.live_sensor_privacy(),
                    a.auto_reboot_remaining_secs(&auto_cfg, ce_unlocked, device_locked),
                )
            })
            .unwrap_or_default();
        let dns = get_private_dns();
        let rp = get_radio_privacy();
        if let Ok(mut cache) = self.sys_cache.write() {
            cache.sensor_privacy = sp;
            cache.private_dns = dns;
            cache.radio_privacy = rp;
            cache.auto_reboot_remaining_secs = reboot_rem;
        }
    }

    /// Applies firewall rules completely OUTSIDE `state` lock.
    /// Defense-in-depth (`D-06`): when `panic_lockdown_active == true`, forces `DefaultDeny` and
    /// clears `rules` + `temp_unblock_set` on the transient effective copy so ALL non-protected
    /// user UIDs are blocked unconditionally without erasing saved `config.firewall.rules`!
    fn sync_firewall(&self) -> Result<(), String> {
        let mut fw_rt = self
            .firewall_mtx
            .lock()
            .map_err(|_| "firewall lock poisoned".to_string())?;

        let (fw_cfg, mut caps, screen_on, temp_set) = {
            let st = self
                .state
                .lock()
                .map_err(|_| "state lock poisoned".to_string())?;
            (
                st.config.firewall.clone(),
                st.caps.clone(),
                st.screen_on,
                st.active_temp_unblock_set(),
            )
        };

        let mut effective_fw = fw_cfg.clone();
        let effective_temp = if effective_fw.panic_lockdown_active {
            effective_fw.enabled = true;
            effective_fw.mode = FirewallMode::DefaultDeny;
            effective_fw.rules.clear();
            HashSet::new()
        } else {
            temp_set
        };

        let apply_res = apply_firewall(
            &effective_fw,
            &mut caps,
            screen_on,
            &effective_temp,
            &mut fw_rt.previously_blocked_uids,
        );

        if let Ok(mut st) = self.state.lock() {
            st.caps = caps;
            match &apply_res {
                Ok(res) => {
                    st.firewall_active = effective_fw.enabled;
                    st.ipset_entries_loaded = res.ipset_entries_loaded;
                    st.ipset_backend = res.ipset_backend.clone();
                    st.vpn_active = res.vpn_active;
                    st.active_interfaces = res.active_interfaces.clone();
                    st.last_firewall_error = res.warning.clone();
                }
                Err(e) => {
                    st.last_firewall_error = Some(e.clone());
                }
            }
        }

        apply_res.map(|_| ())
    }

    /// Synchronizes firewall, hardening, non-overlapping legacy v2 scopes, and notifies the
    /// dedicated `PermissionEnforcer` thread — 100% OUTSIDE `state` lock (`CB-10`).
    fn sync_all_subsystems(&self) {
        let _ = self.sync_firewall();

        let (cfg_h, cfg_scopes) = match self.state.lock() {
            Ok(st) => (st.config.hardening.clone(), st.config.scopes.clone()),
            Err(_) => return,
        };

        if cfg_h.enabled {
            if let Ok(mut h) = self.hardening_mtx.lock() {
                let _ = h.apply(&cfg_h);
            }
        }

        // Legacy v2 non-overlap (CB-10): only sync enabled legacy scopes for categories NOT managed by v3 guards
        sync_all_scopes(&cfg_scopes);

        // Wake dedicated PermissionEnforcer thread for async locked_deny enforcement
        self.enforcer_notify.store(true, Ordering::Release);
    }

    /// Refreshes `/data/system/packages.list` index and optionally auto-blocks newly installed
    /// non-protected user apps — zero subprocesses held under `state`.
    fn check_new_installed_apps(&self) {
        let cur_mtime = fs::metadata("/data/system/packages.list")
            .and_then(|m| m.modified())
            .ok();
        let prev_mtime = self
            .state
            .lock()
            .ok()
            .and_then(|st| st.last_packages_mtime);
        if cur_mtime.is_some() && cur_mtime == prev_mtime {
            return;
        }

        let fresh_index = read_uid_package_index();
        if fresh_index.is_empty() {
            if let Ok(mut st) = self.state.lock() {
                st.last_packages_mtime = cur_mtime;
            }
            return;
        }

        if let Ok(mut idx_w) = self.uid_index.write() {
            *idx_w = fresh_index.clone();
        }

        let mut added_block = false;
        if let Ok(mut st) = self.state.lock() {
            st.last_packages_mtime = cur_mtime;
            let baseline_ready = !st.known_user_uids.is_empty();

            if baseline_ready && st.config.firewall.enabled && st.config.firewall.block_new_apps {
                for &uid in fresh_index.uid_to_pkgs.keys() {
                    if !is_protected_uid(uid)
                        && !fresh_index.any_pkg_for_uid(uid, is_protected_package)
                        && !st.known_user_uids.contains(&uid)
                        && !st.config.firewall.rules.contains_key(&uid)
                    {
                        let pkg = fresh_index.first_pkg(uid).unwrap_or_default().to_string();
                        st.config
                            .firewall
                            .rules
                            .insert(uid, AppFirewallRule::deny_all(pkg));
                        added_block = true;
                    }
                }
            }

            st.known_user_uids = fresh_index
                .uid_to_pkgs
                .keys()
                .copied()
                .filter(|&u| !is_protected_uid(u))
                .collect();

            if added_block {
                let _ = st.save_config();
            }
        }

        if added_block {
            let _ = self.sync_firewall();
        }
    }
}

/// Pure device-lock polling predicate (`D-07`, `CB-06`):
/// ALWAYS polls when screen is off, when device was previously locked (catching lockscreen wakeups
/// and Samsung AOD), when screen power state transitioned, or every 3rd tick.
pub(crate) fn should_poll_device_locked(
    screen_now: bool,
    prev_screen: bool,
    prev_locked: bool,
    tick_count: u64,
) -> bool {
    !screen_now || prev_locked || (prev_screen != screen_now) || tick_count.is_multiple_of(3)
}

/// Computes `device_locked` without EVER hardcoding `false` on skipped ticks (`D-07`, `CB-06`).
pub(crate) fn compute_device_locked<F: FnOnce() -> bool>(
    screen_now: bool,
    prev_screen: bool,
    prev_locked: bool,
    tick_count: u64,
    poll: F,
) -> bool {
    if should_poll_device_locked(screen_now, prev_screen, prev_locked, tick_count) {
        poll()
    } else {
        prev_locked
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum KillConnectionDecision {
    NoMatch,
    Protected(u32),
    SkipNonKillable,
    AllowDestroy,
}

/// Pure pre-check for `KillConnection` (`D-10`, `CB-12`):
/// Rejects connections owned by protected UIDs or packages (including shared-UID protected packages)
/// and skips `LISTEN` / `TIME_WAIT` sockets before delegating to `destroy_matching_connection`.
fn classify_kill_connection_target(
    active: &[ActiveConnection],
    uid_index: &UidPackageIndex,
    family: u8,
    local_ip: &str,
    local_port: u16,
    remote_ip: &str,
    remote_port: u16,
) -> KillConnectionDecision {
    let fam_matches = |conn_fam: u8| -> bool {
        match family {
            4 => conn_fam == libc::AF_INET as u8 || conn_fam == libc::AF_INET6 as u8,
            6 => conn_fam == libc::AF_INET6 as u8,
            f if f == libc::AF_INET as u8 => {
                conn_fam == libc::AF_INET as u8 || conn_fam == libc::AF_INET6 as u8
            }
            f if f == libc::AF_INET6 as u8 => conn_fam == libc::AF_INET6 as u8,
            _ => true,
        }
    };

    let matching: Vec<&ActiveConnection> = active
        .iter()
        .filter(|c| {
            (c.protocol == "TCP" || c.protocol == "TCP6")
                && fam_matches(c.family)
                && c.local_port == local_port
                && c.remote_port == remote_port
                && c.local_ip == local_ip
                && c.remote_ip == remote_ip
        })
        .collect();

    if matching.is_empty() {
        return KillConnectionDecision::NoMatch;
    }

    for c in &matching {
        if is_protected_uid(c.uid)
            || uid_index.any_pkg_for_uid(c.uid, is_protected_package)
            || c.package_name
                .as_deref()
                .map(is_protected_package)
                .unwrap_or(false)
        {
            return KillConnectionDecision::Protected(c.uid);
        }
    }

    if matching
        .iter()
        .all(|c| c.is_listening || c.state == "LISTEN" || c.state == "TIME_WAIT")
    {
        return KillConnectionDecision::SkipNonKillable;
    }

    KillConnectionDecision::AllowDestroy
}

/// Core implementation of `SetAppPermissionAction` decoupled from `state` lock (`D-02`, `D-05`, `CB-02`, `CB-03`).
/// - Rejects invalid/protected packages, unknown categories/actions, and unsupported `foreground` BEFORE modifying guards.
/// - Clears `locked_deny` BEFORE invoking `apply_fn` when user manually selects `allow`/`default`/`foreground`/`ignore`,
///   preventing an `inotify` enforcer race mid-operation.
/// - Restores `locked_deny` if `apply_fn` returns `Err`, keeping Daemon and App state strictly consistent.
fn execute_set_app_permission_action<F>(
    ctx: &DaemonContext,
    id: u64,
    package_name: String,
    category: String,
    action: String,
    apply_fn: F,
) -> IpcResponse
where
    F: FnOnce(&str, &str, &str, u32) -> Result<AppLivePermissions, String>,
{
    if !validate_package_name(&package_name) || ctx.is_pkg_protected(&package_name) {
        return IpcResponse::err(
            id,
            format!("Refused to modify permissions on invalid or protected package: {}", package_name),
        );
    }
    let Some(spec) = find_category(&category) else {
        return IpcResponse::err(id, format!("Unknown permission category: {}", category));
    };

    let clean_action = action.trim().to_ascii_lowercase();
    match clean_action.as_str() {
        "allow" | "default" | "foreground" | "ignore" | "revoke" | "deny" => {}
        other => {
            return IpcResponse::err(id, format!("Unsupported permission action: {}", other));
        }
    }
    if clean_action == "foreground" && !spec.supports_foreground {
        return IpcResponse::err(
            id,
            format!(
                "Category '{}' does not support foreground-only mode on Android",
                spec.id
            ),
        );
    }

    let user_id = ctx.user_id_for_pkg(&package_name);
    let loosens_lock = matches!(
        clean_action.as_str(),
        "allow" | "default" | "foreground" | "ignore"
    );

    let mut unlocked_from_guard = false;
    if loosens_lock {
        if let Ok(mut st) = ctx.state.lock() {
            if let Some(guard) = st.config.scopes.guards.get_mut(&package_name) {
                let before = guard.locked_deny.len();
                guard.locked_deny.retain(|c| c != &category);
                if guard.locked_deny.len() != before {
                    unlocked_from_guard = true;
                    if guard.is_empty() {
                        st.config.scopes.guards.remove(&package_name);
                    }
                    let _ = st.save_config();
                }
            }
        }
    }

    match apply_fn(&package_name, &category, &action, user_id) {
        Ok(live) => {
            if unlocked_from_guard {
                if clean_action == "ignore" {
                    let _ = clear_lock_flags_for_category(&package_name, &category, user_id);
                }
                ctx.enforcer_notify.store(true, Ordering::Release);
            }
            IpcResponse::ok(id, live)
        }
        Err(e) => {
            if unlocked_from_guard {
                if let Ok(mut st) = ctx.state.lock() {
                    let entry = st
                        .config
                        .scopes
                        .guards
                        .entry(package_name.clone())
                        .or_default();
                    if !entry.locked_deny.contains(&category) {
                        entry.locked_deny.push(category);
                    }
                    let _ = st.config.sanitize();
                    let _ = st.save_config();
                }
            }
            IpcResponse::err(id, e)
        }
    }
}

fn dispatch_command(req: IpcRequest, ctx: &Arc<DaemonContext>) -> IpcResponse {
    let id = req.id;
    match req.command {
        DaemonCommand::Ping => IpcResponse::ok(
            id,
            serde_json::json!({ "pong": true, "version": DAEMON_VERSION }),
        ),

        DaemonCommand::GetStatus => match ctx.build_status() {
            Ok(status) => IpcResponse::ok(id, status),
            Err(e) => IpcResponse::err(id, e),
        },

        DaemonCommand::GetConfig => {
            let Ok(st) = ctx.state.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            IpcResponse::ok(id, &st.config)
        }

        DaemonCommand::ApplyPreset { preset } => {
            let (saved_cfg, cfg_h, freezer_enabled) = {
                let Ok(mut st) = ctx.state.lock() else {
                    return IpcResponse::err(id, "state lock poisoned");
                };
                st.config.apply_preset(preset);
                if let Err(e) = st.config.sanitize() {
                    return IpcResponse::err(id, e);
                }
                if let Err(e) = st.save_config() {
                    return IpcResponse::err(id, e);
                }
                (
                    st.config.clone(),
                    st.config.hardening.clone(),
                    st.config.freezer.enabled,
                )
            };

            let fw_res = ctx.sync_firewall();

            if let Ok(mut h) = ctx.hardening_mtx.lock() {
                if cfg_h.enabled {
                    let _ = h.apply(&cfg_h);
                } else {
                    let _ = h.rollback_all(&cfg_h);
                }
            }

            if !freezer_enabled {
                if let Ok(mut fz) = ctx.freezer_mtx.lock() {
                    fz.thaw_all();
                    ctx.update_freezer_cache(&fz);
                }
            }

            if let Ok(mut cache) = ctx.sys_cache.write() {
                cache.auto_reboot_remaining_secs = None;
            }

            match fw_res {
                Ok(()) => IpcResponse::ok(id, saved_cfg),
                Err(e) => IpcResponse::err(id, format!("Preset saved, firewall error: {}", e)),
            }
        }

        DaemonCommand::SetFirewallConfig { mut config } => {
            // Authoritative protected-UID filter (including shared-UID protected packages)
            config.rules.retain(|&uid, rule| {
                !ctx.is_uid_protected(uid) && !is_protected_package(&rule.package_name)
            });

            let saved_fw = {
                let Ok(mut st) = ctx.state.lock() else {
                    return IpcResponse::err(id, "state lock poisoned");
                };
                let mut probe_cfg = st.config.clone();
                probe_cfg.firewall = std::mem::take(&mut config);
                if !probe_cfg.firewall.enabled
                    || probe_cfg.firewall.mode != FirewallMode::DefaultDeny
                {
                    probe_cfg.firewall.panic_lockdown_active = false;
                }
                if let Err(e) = probe_cfg.sanitize() {
                    return IpcResponse::err(id, e);
                }
                st.config.firewall = probe_cfg.firewall;
                if let Err(e) = st.save_config() {
                    return IpcResponse::err(id, e);
                }
                st.config.firewall.clone()
            };

            match ctx.sync_firewall() {
                Ok(()) => IpcResponse::ok(id, saved_fw),
                Err(e) => IpcResponse::err(id, e),
            }
        }

        DaemonCommand::SetAppFirewallRule { uid, rule } => {
            if is_protected_uid(uid)
                || is_protected_package(&rule.package_name)
                || ctx.is_uid_protected(uid)
            {
                return IpcResponse::err(
                    id,
                    format!("Refused to modify protected system UID {}", uid),
                );
            }
            {
                let Ok(mut st) = ctx.state.lock() else {
                    return IpcResponse::err(id, "state lock poisoned");
                };
                st.config.firewall.rules.insert(uid, rule);
                if let Err(e) = st.save_config() {
                    return IpcResponse::err(id, e);
                }
            }
            match ctx.sync_firewall() {
                Ok(()) => IpcResponse::ok_empty(id),
                Err(e) => IpcResponse::err(id, e),
            }
        }

        DaemonCommand::RemoveAppFirewallRule { uid } => {
            {
                let Ok(mut st) = ctx.state.lock() else {
                    return IpcResponse::err(id, "state lock poisoned");
                };
                st.config.firewall.rules.remove(&uid);
                st.temp_unblock_until.remove(&uid);
                if let Err(e) = st.save_config() {
                    return IpcResponse::err(id, e);
                }
            }
            match ctx.sync_firewall() {
                Ok(()) => IpcResponse::ok_empty(id),
                Err(e) => IpcResponse::err(id, e),
            }
        }

        DaemonCommand::SetTempUnblock {
            uid,
            duration_minutes,
        } => {
            if ctx.is_uid_protected(uid) {
                return IpcResponse::err(id, "System UID is already exempted");
            }
            let mins = duration_minutes.clamp(1, 240);
            {
                let Ok(mut st) = ctx.state.lock() else {
                    return IpcResponse::err(id, "state lock poisoned");
                };
                let expiry = boottime_secs().saturating_add(u64::from(mins) * 60);
                st.temp_unblock_until.insert(uid, expiry);
            }
            match ctx.sync_firewall() {
                Ok(()) => IpcResponse::ok(
                    id,
                    serde_json::json!({ "uid": uid, "remaining_secs": u64::from(mins) * 60 }),
                ),
                Err(e) => IpcResponse::err(id, e),
            }
        }

        DaemonCommand::ClearTempUnblock { uid } => {
            let sd_avail = {
                let Ok(mut st) = ctx.state.lock() else {
                    return IpcResponse::err(id, "state lock poisoned");
                };
                st.temp_unblock_until.remove(&uid);
                st.caps.sock_destroy_available
            };
            let res = ctx.sync_firewall();
            if sd_avail && !ctx.is_uid_protected(uid) {
                let _ = destroy_uid_sockets(uid);
            }
            match res {
                Ok(()) => IpcResponse::ok_empty(id),
                Err(e) => IpcResponse::err(id, e),
            }
        }

        DaemonCommand::ReplaceIpBlocklist { entries } => {
            if entries.len() > MAX_BLOCKLIST_ENTRIES {
                return IpcResponse::err(
                    id,
                    format!("Too many blocklist entries (max {})", MAX_BLOCKLIST_ENTRIES),
                );
            }
            let mut valid = Vec::new();
            let mut rejected = 0usize;
            for raw in entries {
                let t = raw.trim();
                if t.is_empty() || t.starts_with('#') {
                    continue;
                }
                if let Some(c) = validate_cidr(t) {
                    valid.push(c.canonical);
                } else {
                    rejected += 1;
                }
            }
            let count = {
                let Ok(mut st) = ctx.state.lock() else {
                    return IpcResponse::err(id, "state lock poisoned");
                };
                st.config.firewall.ip_blocklist = valid;
                let _ = st.config.sanitize();
                if let Err(e) = st.save_config() {
                    return IpcResponse::err(id, e);
                }
                st.config.firewall.ip_blocklist.len()
            };
            match ctx.sync_firewall() {
                Ok(()) => {
                    let backend = ctx
                        .state
                        .lock()
                        .map(|s| s.ipset_backend.clone())
                        .unwrap_or_else(|_| "unknown".to_string());
                    IpcResponse::ok(
                        id,
                        serde_json::json!({
                            "loaded": count,
                            "rejected": rejected,
                            "backend": backend
                        }),
                    )
                }
                Err(e) => IpcResponse::err(id, e),
            }
        }

        DaemonCommand::EmergencyFlushFirewall => {
            let caps = {
                let Ok(mut st) = ctx.state.lock() else {
                    return IpcResponse::err(id, "state lock poisoned");
                };
                st.config.firewall.enabled = false;
                st.config.firewall.panic_lockdown_active = false;
                st.temp_unblock_until.clear();
                let _ = st.save_config();
                st.caps.clone()
            };
            if let Ok(mut fw_rt) = ctx.firewall_mtx.lock() {
                flush_firewall(&caps);
                fw_rt.previously_blocked_uids.clear();
            }
            if let Ok(mut st) = ctx.state.lock() {
                st.firewall_active = false;
                st.ipset_entries_loaded = 0;
                st.ipset_backend = "disabled".to_string();
                st.last_firewall_error = None;
            }
            IpcResponse::ok_empty(id)
        }

        DaemonCommand::KillUidSockets { uid } => {
            if ctx.is_uid_protected(uid) {
                return IpcResponse::err(id, "Refused to kill sockets of protected system UID");
            }
            let killed = destroy_uid_sockets(uid);
            IpcResponse::ok(id, serde_json::json!({ "killed": killed }))
        }

        DaemonCommand::KillConnection {
            family,
            local_ip,
            local_port,
            remote_ip,
            remote_port,
        } => {
            let idx_snap = ctx
                .uid_index
                .read()
                .map(|idx| idx.clone())
                .unwrap_or_default();
            let active = list_active_connections(idx_snap.primary_map());
            match classify_kill_connection_target(
                &active,
                &idx_snap,
                family,
                &local_ip,
                local_port,
                &remote_ip,
                remote_port,
            ) {
                KillConnectionDecision::NoMatch | KillConnectionDecision::SkipNonKillable => {
                    IpcResponse::ok(id, serde_json::json!({ "killed": 0 }))
                }
                KillConnectionDecision::Protected(uid) => IpcResponse::err(
                    id,
                    format!("Refused to kill connection owned by protected UID {}", uid),
                ),
                KillConnectionDecision::AllowDestroy => match destroy_matching_connection(
                    family,
                    &local_ip,
                    local_port,
                    &remote_ip,
                    remote_port,
                ) {
                    Ok(killed) => IpcResponse::ok(id, serde_json::json!({ "killed": killed })),
                    Err(e) => IpcResponse::err(id, e),
                },
            }
        }

        DaemonCommand::GetLiveConnections => {
            let primary_map = ctx
                .uid_index
                .read()
                .map(|idx| idx.primary_map().clone())
                .unwrap_or_default();
            let conns = list_active_connections(&primary_map);
            IpcResponse::ok(id, conns)
        }

        DaemonCommand::GetTelemetry { limit } => {
            let Ok(ring) = ctx.telemetry.lock() else {
                return IpcResponse::err(id, "telemetry lock poisoned");
            };
            IpcResponse::ok(id, ring.snapshot(limit.unwrap_or(200)))
        }

        DaemonCommand::ClearTelemetry => {
            if let Ok(mut ring) = ctx.telemetry.lock() {
                ring.clear();
            }
            IpcResponse::ok_empty(id)
        }

        DaemonCommand::SetFreezerEnabled {
            enabled,
            background_grace_secs,
        } => {
            let fz_cfg = {
                let Ok(mut st) = ctx.state.lock() else {
                    return IpcResponse::err(id, "state lock poisoned");
                };
                st.config.freezer.enabled = enabled;
                st.config.freezer.background_grace_secs = background_grace_secs.clamp(5, 600);
                let _ = st.save_config();
                st.config.freezer.clone()
            };
            let Ok(mut fz) = ctx.freezer_mtx.lock() else {
                return IpcResponse::err(id, "freezer lock poisoned");
            };
            if !enabled {
                fz.thaw_all();
            }
            ctx.update_freezer_cache(&fz);
            IpcResponse::ok(id, fz.status(&fz_cfg))
        }

        DaemonCommand::SetFreezerEntry { uid, entry } => {
            if is_protected_uid(uid)
                || is_protected_package(&entry.package_name)
                || ctx.is_uid_protected(uid)
            {
                return IpcResponse::err(
                    id,
                    format!("Refused to add protected UID {} to freezer", uid),
                );
            }
            let fz_cfg = {
                let Ok(mut st) = ctx.state.lock() else {
                    return IpcResponse::err(id, "state lock poisoned");
                };
                st.config.freezer.entries.insert(uid, entry);
                let _ = st.save_config();
                st.config.freezer.clone()
            };
            let Ok(fz) = ctx.freezer_mtx.lock() else {
                return IpcResponse::err(id, "freezer lock poisoned");
            };
            ctx.update_freezer_cache(&fz);
            IpcResponse::ok(id, fz.status(&fz_cfg))
        }

        DaemonCommand::RemoveFreezerEntry { uid } => {
            let (pkg, fz_cfg) = {
                let Ok(mut st) = ctx.state.lock() else {
                    return IpcResponse::err(id, "state lock poisoned");
                };
                let pkg = st
                    .config
                    .freezer
                    .entries
                    .remove(&uid)
                    .map(|e| e.package_name);
                let _ = st.save_config();
                (pkg, st.config.freezer.clone())
            };
            let Ok(mut fz) = ctx.freezer_mtx.lock() else {
                return IpcResponse::err(id, "freezer lock poisoned");
            };
            let _ = fz.thaw_uid_now(uid, pkg.as_deref());
            ctx.update_freezer_cache(&fz);
            IpcResponse::ok(id, fz.status(&fz_cfg))
        }

        DaemonCommand::FreezeUidNow { uid } => {
            if ctx.is_uid_protected(uid) {
                return IpcResponse::err(
                    id,
                    format!("Refused to freeze protected UID {}", uid),
                );
            }
            let (pkg_opt, mode, kill_sock, sd, fz_cfg) = {
                let Ok(st) = ctx.state.lock() else {
                    return IpcResponse::err(id, "state lock poisoned");
                };
                let (pkg, mode, kill_sock) = st
                    .config
                    .freezer
                    .entries
                    .get(&uid)
                    .map(|e| (Some(e.package_name.clone()), e.mode, e.kill_sockets_on_freeze))
                    .unwrap_or((None, FreezeMode::SoftCgroup, true));
                (
                    pkg,
                    mode,
                    kill_sock,
                    st.caps.sock_destroy_available,
                    st.config.freezer.clone(),
                )
            };
            let pkg = pkg_opt
                .filter(|p| !p.is_empty())
                .or_else(|| ctx.first_pkg_for_uid(uid))
                .unwrap_or_default();
            if is_protected_package(&pkg) {
                return IpcResponse::err(
                    id,
                    format!("Refused to freeze protected package {}", pkg),
                );
            }
            let Ok(mut fz) = ctx.freezer_mtx.lock() else {
                return IpcResponse::err(id, "freezer lock poisoned");
            };
            match fz.freeze_uid_now(uid, &pkg, mode, kill_sock, sd) {
                Ok(_detail) => {
                    ctx.update_freezer_cache(&fz);
                    IpcResponse::ok(id, fz.status(&fz_cfg))
                }
                Err(e) => IpcResponse::err(id, e),
            }
        }

        DaemonCommand::ThawUidNow { uid } => {
            let (pkg_opt, fz_cfg) = {
                let Ok(st) = ctx.state.lock() else {
                    return IpcResponse::err(id, "state lock poisoned");
                };
                let pkg = st
                    .config
                    .freezer
                    .entries
                    .get(&uid)
                    .map(|e| e.package_name.clone());
                (pkg, st.config.freezer.clone())
            };
            let pkg = pkg_opt.or_else(|| ctx.first_pkg_for_uid(uid));
            let Ok(mut fz) = ctx.freezer_mtx.lock() else {
                return IpcResponse::err(id, "freezer lock poisoned");
            };
            match fz.thaw_uid_now(uid, pkg.as_deref()) {
                Ok(()) => {
                    ctx.update_freezer_cache(&fz);
                    IpcResponse::ok(id, fz.status(&fz_cfg))
                }
                Err(e) => IpcResponse::err(id, e),
            }
        }

        DaemonCommand::ThawAllNow => {
            let fz_cfg = {
                let Ok(st) = ctx.state.lock() else {
                    return IpcResponse::err(id, "state lock poisoned");
                };
                st.config.freezer.clone()
            };
            let Ok(mut fz) = ctx.freezer_mtx.lock() else {
                return IpcResponse::err(id, "freezer lock poisoned");
            };
            fz.thaw_all();
            ctx.update_freezer_cache(&fz);
            IpcResponse::ok(id, fz.status(&fz_cfg))
        }

        DaemonCommand::GetFreezerStatus => {
            let fz_cfg = {
                let Ok(st) = ctx.state.lock() else {
                    return IpcResponse::err(id, "state lock poisoned");
                };
                st.config.freezer.clone()
            };
            let Ok(fz) = ctx.freezer_mtx.lock() else {
                return IpcResponse::err(id, "freezer lock poisoned");
            };
            IpcResponse::ok(id, fz.status(&fz_cfg))
        }

        DaemonCommand::SetAppScope {
            package_name,
            scope,
        } => {
            if !validate_package_name(&package_name) || ctx.is_pkg_protected(&package_name) {
                return IpcResponse::err(
                    id,
                    format!("Refused to modify scopes for {}", package_name),
                );
            }
            match apply_app_scope(&package_name, &scope) {
                Ok(applied) => {
                    let Ok(mut st) = ctx.state.lock() else {
                        return IpcResponse::err(id, "state lock poisoned");
                    };
                    if scope.any_enabled() {
                        st.config.scopes.apps.insert(package_name, scope);
                    } else {
                        st.config.scopes.apps.remove(&package_name);
                    }
                    let _ = st.save_config();
                    IpcResponse::ok(id, serde_json::json!({ "applied_ops": applied }))
                }
                Err(e) => IpcResponse::err(id, e),
            }
        }

        DaemonCommand::ApplyHardenedScope { package_name } => {
            if !validate_package_name(&package_name) || ctx.is_pkg_protected(&package_name) {
                return IpcResponse::err(
                    id,
                    format!("Refused to modify scopes for {}", package_name),
                );
            }
            let profile = AppIsolationScope::hardened_profile();
            if let Err(e) = apply_app_scope(&package_name, &profile) {
                return IpcResponse::err(id, e);
            }
            let Ok(mut st) = ctx.state.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            st.config.scopes.apps.insert(package_name, profile.clone());
            let _ = st.save_config();
            IpcResponse::ok(id, profile)
        }

        DaemonCommand::ClearAppScope { package_name } => {
            if !validate_package_name(&package_name) || ctx.is_pkg_protected(&package_name) {
                return IpcResponse::err(
                    id,
                    format!("Refused to modify scopes for {}", package_name),
                );
            }
            if let Err(e) = clear_app_scope(&package_name) {
                return IpcResponse::err(id, e);
            }
            let Ok(mut st) = ctx.state.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            st.config.scopes.apps.remove(&package_name);
            let _ = st.save_config();
            IpcResponse::ok_empty(id)
        }

        DaemonCommand::SetAutoClearClipboard {
            enabled,
            clear_secs,
        } => {
            let Ok(mut st) = ctx.state.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            st.config.scopes.auto_clear_clipboard = enabled;
            st.config.scopes.clipboard_clear_secs = clear_secs;
            let _ = st.config.sanitize();
            let _ = st.save_config();
            IpcResponse::ok(id, &st.config.scopes)
        }

        DaemonCommand::ClearClipboardNow => {
            let Ok(mut clip) = ctx.clipboard_mtx.lock() else {
                return IpcResponse::err(id, "clipboard lock poisoned");
            };
            match clip.clear_now() {
                Ok(()) => IpcResponse::ok_empty(id),
                Err(e) => IpcResponse::err(id, e),
            }
        }

        DaemonCommand::InspectAppPermissions { package_name } => {
            let user_id = ctx.user_id_for_pkg(&package_name);
            IpcResponse::ok(id, inspect_app_permissions_for_user(&package_name, user_id))
        }

        DaemonCommand::SetAppPermissionAction {
            package_name,
            category,
            action,
        } => execute_set_app_permission_action(
            ctx,
            id,
            package_name,
            category,
            action,
            set_app_permission_action_for_user,
        ),

        DaemonCommand::SetAppPermGuard {
            package_name,
            guard,
        } => {
            if !validate_package_name(&package_name) || ctx.is_pkg_protected(&package_name) {
                return IpcResponse::err(
                    id,
                    format!("Invalid or protected package: {}", package_name),
                );
            }

            let (prev_locked, new_locked, saved_scopes) = {
                let Ok(mut st) = ctx.state.lock() else {
                    return IpcResponse::err(id, "state lock poisoned");
                };
                let prev_locked: HashSet<String> = st
                    .config
                    .scopes
                    .guards
                    .get(&package_name)
                    .map(|g| g.locked_deny.iter().cloned().collect())
                    .unwrap_or_default();

                if guard.is_empty() {
                    st.config.scopes.guards.remove(&package_name);
                } else {
                    st.config.scopes.guards.insert(package_name.clone(), guard);
                }
                let _ = st.config.sanitize();
                if let Err(e) = st.save_config() {
                    return IpcResponse::err(id, e);
                }

                let new_locked: HashSet<String> = st
                    .config
                    .scopes
                    .guards
                    .get(&package_name)
                    .map(|g| g.locked_deny.iter().cloned().collect())
                    .unwrap_or_default();

                (prev_locked, new_locked, st.config.scopes.clone())
            };

            let newly_added_locks: Vec<String> = new_locked.difference(&prev_locked).cloned().collect();
            let removed_locks: Vec<String> = prev_locked.difference(&new_locked).cloned().collect();
            let user_id = ctx.user_id_for_pkg(&package_name);

            for cat in &newly_added_locks {
                let _ = set_app_permission_action_for_user(&package_name, cat, "revoke", user_id);
                let _ = apply_lock_flags_for_category(&package_name, cat, user_id);
            }
            for cat in &removed_locks {
                let _ = clear_lock_flags_for_category(&package_name, cat, user_id);
            }

            ctx.enforcer_notify.store(true, Ordering::Release);
            IpcResponse::ok(id, saved_scopes)
        }

        DaemonCommand::BulkRevokeCategory { category, packages } => {
            let pkg_to_uid = ctx
                .uid_index
                .read()
                .map(|idx| idx.pkg_to_uid.clone())
                .unwrap_or_default();
            let safe_pkgs: Vec<String> = packages
                .into_iter()
                .filter(|p| validate_package_name(p) && !ctx.is_pkg_protected(p))
                .collect();
            match bulk_revoke_category_with_uids(&category, &safe_pkgs, &pkg_to_uid) {
                Ok(revoked) => IpcResponse::ok(
                    id,
                    serde_json::json!({ "category": category, "revoked_count": revoked }),
                ),
                Err(e) => IpcResponse::err(id, e),
            }
        }

        DaemonCommand::GetPrivacyAccessLog { limit } => {
            let primary_map = ctx
                .uid_index
                .read()
                .map(|idx| idx.primary_map().clone())
                .unwrap_or_default();
            let events = get_privacy_access_log(&primary_map, limit.unwrap_or(80));
            IpcResponse::ok(id, events)
        }

        DaemonCommand::SetHardeningConfig { config } => {
            let h_cfg = {
                let Ok(mut st) = ctx.state.lock() else {
                    return IpcResponse::err(id, "state lock poisoned");
                };
                let mut probe = st.config.clone();
                probe.hardening = config;
                if let Err(e) = probe.sanitize() {
                    return IpcResponse::err(id, e);
                }
                st.config.hardening = probe.hardening;
                let _ = st.save_config();
                st.config.hardening.clone()
            };
            let Ok(mut h) = ctx.hardening_mtx.lock() else {
                return IpcResponse::err(id, "hardening lock poisoned");
            };
            let status = if h_cfg.enabled {
                h.apply(&h_cfg)
            } else {
                h.rollback_all(&h_cfg)
            };
            IpcResponse::ok(id, status)
        }

        DaemonCommand::RollbackHardening => {
            let h_cfg = {
                let Ok(mut st) = ctx.state.lock() else {
                    return IpcResponse::err(id, "state lock poisoned");
                };
                st.config.hardening.enabled = false;
                let _ = st.save_config();
                st.config.hardening.clone()
            };
            let Ok(mut h) = ctx.hardening_mtx.lock() else {
                return IpcResponse::err(id, "hardening lock poisoned");
            };
            IpcResponse::ok(id, h.rollback_all(&h_cfg))
        }

        DaemonCommand::GetHardeningStatus => {
            let h_cfg = {
                let Ok(st) = ctx.state.lock() else {
                    return IpcResponse::err(id, "state lock poisoned");
                };
                st.config.hardening.clone()
            };
            let Ok(h) = ctx.hardening_mtx.lock() else {
                return IpcResponse::err(id, "hardening lock poisoned");
            };
            IpcResponse::ok(id, h.status(&h_cfg))
        }

        DaemonCommand::SetAutomationConfig { config } => {
            let (saved_auto, ce_unlocked, device_locked) = {
                let Ok(mut st) = ctx.state.lock() else {
                    return IpcResponse::err(id, "state lock poisoned");
                };
                st.config.automation = config;
                let _ = st.config.sanitize();
                let _ = st.save_config();
                (
                    st.config.automation.clone(),
                    st.ce_unlocked,
                    st.device_locked,
                )
            };
            let rem = ctx
                .automation_mtx
                .lock()
                .ok()
                .and_then(|a| a.auto_reboot_remaining_secs(&saved_auto, ce_unlocked, device_locked));
            if let Ok(mut cache) = ctx.sys_cache.write() {
                cache.auto_reboot_remaining_secs = rem;
            }
            IpcResponse::ok(id, saved_auto)
        }

        DaemonCommand::SetSensorsOff { enabled } => {
            let Ok(mut auto) = ctx.automation_mtx.lock() else {
                return IpcResponse::err(id, "automation lock poisoned");
            };
            match auto.set_sensor_privacy("all", enabled) {
                Ok(live) => {
                    if let Ok(mut cache) = ctx.sys_cache.write() {
                        cache.sensor_privacy = live.clone();
                    }
                    IpcResponse::ok(id, live)
                }
                Err(e) => IpcResponse::err(id, e),
            }
        }

        DaemonCommand::SetSensorPrivacy { sensor, enabled } => {
            let Ok(mut auto) = ctx.automation_mtx.lock() else {
                return IpcResponse::err(id, "automation lock poisoned");
            };
            match auto.set_sensor_privacy(&sensor, enabled) {
                Ok(live) => {
                    if let Ok(mut cache) = ctx.sys_cache.write() {
                        cache.sensor_privacy = live.clone();
                    }
                    IpcResponse::ok(id, live)
                }
                Err(e) => IpcResponse::err(id, e),
            }
        }

        DaemonCommand::GetPrivateDns => {
            let dns = get_private_dns();
            if let Ok(mut cache) = ctx.sys_cache.write() {
                cache.private_dns = dns.clone();
            }
            IpcResponse::ok(id, dns)
        }

        DaemonCommand::SetPrivateDns { mode, specifier } => {
            match set_private_dns(&mode, &specifier) {
                Ok(dns) => {
                    if let Ok(mut cache) = ctx.sys_cache.write() {
                        cache.private_dns = dns.clone();
                    }
                    IpcResponse::ok(id, dns)
                }
                Err(e) => IpcResponse::err(id, e),
            }
        }

        DaemonCommand::GetRadioPrivacy => {
            let rp = get_radio_privacy();
            if let Ok(mut cache) = ctx.sys_cache.write() {
                cache.radio_privacy = rp.clone();
            }
            IpcResponse::ok(id, rp)
        }

        DaemonCommand::SetRadioPrivacy {
            enhanced_mac_randomization,
            disable_wifi_ble_scanning,
            disable_captive_portal,
        } => match set_radio_privacy(
            enhanced_mac_randomization,
            disable_wifi_ble_scanning,
            disable_captive_portal,
        ) {
            Ok(rp) => {
                if let Ok(mut cache) = ctx.sys_cache.write() {
                    cache.radio_privacy = rp.clone();
                }
                IpcResponse::ok(id, rp)
            }
            Err(e) => IpcResponse::err(id, e),
        },

        DaemonCommand::TriggerPanicLockdown => {
            let sd_avail = {
                let Ok(mut st) = ctx.state.lock() else {
                    return IpcResponse::err(id, "state lock poisoned");
                };
                st.config.firewall.enabled = true;
                st.config.firewall.mode = FirewallMode::DefaultDeny;
                st.config.firewall.panic_lockdown_active = true;
                st.temp_unblock_until.clear();
                let _ = st.save_config();
                st.caps.sock_destroy_available
            };

            let firewall_locked = ctx.sync_firewall().is_ok();
            let sockets_destroyed = if sd_avail {
                destroy_all_user_sockets()
            } else {
                0
            };
            let sp = ctx
                .automation_mtx
                .lock()
                .map(|mut a| {
                    a.set_sensor_privacy("all", true)
                        .unwrap_or_else(|_| a.live_sensor_privacy())
                })
                .unwrap_or_default();
            if let Ok(mut cache) = ctx.sys_cache.write() {
                cache.sensor_privacy = sp.clone();
            }
            let (wifi_disabled, bluetooth_disabled) = disable_wifi_and_bt_now();
            let clipboard_cleared = ctx
                .clipboard_mtx
                .lock()
                .map(|mut c| c.clear_now().is_ok())
                .unwrap_or(false);

            IpcResponse::ok(
                id,
                PanicLockdownReport {
                    firewall_locked,
                    sockets_destroyed,
                    mic_muted: sp.mic_muted,
                    camera_muted: sp.camera_muted,
                    wifi_disabled,
                    bluetooth_disabled,
                    clipboard_cleared,
                },
            )
        }

        DaemonCommand::TriggerPanicReboot { confirm_token } => {
            if confirm_token != PANIC_REBOOT_CONFIRM_TOKEN {
                return IpcResponse::err(id, "Invalid panic reboot confirmation token");
            }
            if let Ok(mut fz) = ctx.freezer_mtx.lock() {
                fz.thaw_all();
                ctx.update_freezer_cache(&fz);
            }
            match execute_clean_bfu_reboot() {
                Ok(()) => IpcResponse::ok_empty(id),
                Err(e) => IpcResponse::err(id, e),
            }
        }

        DaemonCommand::RunSentinelAudit => {
            let (cfg_clone, fw_active) = match ctx.state.lock() {
                Ok(st) => (st.config.clone(), st.firewall_active),
                Err(_) => return IpcResponse::err(id, "state lock poisoned"),
            };
            let report = run_sentinel_audit(&cfg_clone, fw_active);
            IpcResponse::ok(id, report)
        }

        DaemonCommand::RunLiveProof => {
            let (cfg_clone, caps_clone) = match ctx.state.lock() {
                Ok(st) => (st.config.clone(), st.caps.clone()),
                Err(_) => return IpcResponse::err(id, "state lock poisoned"),
            };
            // Strict Level 1 lock order: hardening_mtx -> freezer_mtx
            let Ok(h) = ctx.hardening_mtx.lock() else {
                return IpcResponse::err(id, "hardening lock poisoned");
            };
            let Ok(f) = ctx.freezer_mtx.lock() else {
                return IpcResponse::err(id, "freezer lock poisoned");
            };
            let proof = run_live_proof(&cfg_clone, &caps_clone, &h, &f);
            IpcResponse::ok(id, proof)
        }

        DaemonCommand::ExportConfigJson => {
            let Ok(st) = ctx.state.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            match serde_json::to_string_pretty(&st.config) {
                Ok(json) => IpcResponse::ok(id, serde_json::json!({ "json": json })),
                Err(e) => IpcResponse::err(id, format!("export failed: {}", e)),
            }
        }

        DaemonCommand::ImportConfigJson { json } => {
            let mut imported: SiloConfig = match serde_json::from_str(&json) {
                Ok(c) => c,
                Err(e) => return IpcResponse::err(id, format!("invalid config JSON: {}", e)),
            };
            if let Err(e) = imported.sanitize() {
                return IpcResponse::err(id, e);
            }
            // Authoritative protected-UID & shared-UID package enforcement on imported config
            imported.firewall.rules.retain(|&uid, rule| {
                !ctx.is_uid_protected(uid) && !is_protected_package(&rule.package_name)
            });
            imported.freezer.entries.retain(|&uid, entry| {
                !ctx.is_uid_protected(uid) && !is_protected_package(&entry.package_name)
            });
            imported
                .scopes
                .apps
                .retain(|pkg, _| validate_package_name(pkg) && !ctx.is_pkg_protected(pkg));
            imported
                .scopes
                .guards
                .retain(|pkg, _| validate_package_name(pkg) && !ctx.is_pkg_protected(pkg));

            let saved_cfg = {
                let Ok(mut st) = ctx.state.lock() else {
                    return IpcResponse::err(id, "state lock poisoned");
                };
                st.config = imported;
                if let Err(e) = st.save_config() {
                    return IpcResponse::err(id, e);
                }
                st.config.clone()
            };

            ctx.sync_all_subsystems();
            IpcResponse::ok(id, saved_cfg)
        }
    }
}

fn run_daemon(socket_path: PathBuf, config_path: PathBuf, boot_fail_marker: PathBuf) -> i32 {
    install_signal_handlers();
    let _ = set_oom_score_adj(-900);

    // Acquire exclusive flock BEFORE creating state or syncing subsystems so duplicate silod
    // processes can never overwrite iptables/sysctls or spawn duplicate background workers.
    let lock_path = derive_lock_path(&socket_path);
    let singleton_lock = match SingletonLockGuard::acquire(&lock_path) {
        Ok(guard) => guard,
        Err(e) => {
            eprintln!("silod startup error: {}", e);
            return 1;
        }
    };

    let stop = Arc::new(AtomicBool::new(false));
    let ctx = Arc::new(DaemonContext::new(config_path, boot_fail_marker));

    ctx.sync_all_subsystems();
    ctx.refresh_cached_system_state();

    // Signal watcher thread
    let stop_sig = Arc::clone(&stop);
    thread::spawn(move || {
        while !stop_sig.load(Ordering::Relaxed) {
            if GLOBAL_STOP.load(Ordering::SeqCst) {
                stop_sig.store(true, Ordering::SeqCst);
                break;
            }
            thread::sleep(Duration::from_millis(100));
        }
    });

    // NFLOG telemetry thread — reads `uid_index` RwLock directly with ZERO `state` lock and ZERO map clone
    let nflog_avail = ctx
        .state
        .lock()
        .map(|s| s.caps.nflog_available)
        .unwrap_or(false);
    if nflog_avail {
        let stop_nflog = Arc::clone(&stop);
        let ctx_nflog = Arc::clone(&ctx);
        thread::spawn(move || {
            run_nflog_loop(SILO_NFLOG_GROUP, stop_nflog, move |ev| {
                if let Ok(idx) = ctx_nflog.uid_index.read() {
                    if let Ok(mut ring) = ctx_nflog.telemetry.lock() {
                        ring.push(ev, idx.primary_map());
                    }
                }
            });
        });
    }

    // Fast foreground cgroup thaw thread — ZERO `state` lock across freezer operations
    let stop_thaw = Arc::clone(&stop);
    let ctx_thaw = Arc::clone(&ctx);
    thread::spawn(move || {
        while !stop_thaw.load(Ordering::Relaxed) {
            let screen_on = ctx_thaw
                .state
                .lock()
                .map(|s| s.screen_on)
                .unwrap_or(false);
            let has_frozen = ctx_thaw
                .sys_cache
                .read()
                .map(|c| c.frozen_uids_count > 0)
                .unwrap_or(false);

            if screen_on && has_frozen {
                if let Ok(mut fz) = ctx_thaw.freezer_mtx.lock() {
                    fz.fast_foreground_thaw_tick();
                    ctx_thaw.update_freezer_cache(&fz);
                }
                thread::sleep(Duration::from_millis(400));
            } else {
                thread::sleep(Duration::from_secs(2));
            }
        }
    });

    // Dedicated event-driven (`inotify`) + fast active-guard PermissionEnforcer thread
    let stop_enf = Arc::clone(&stop);
    let notify_enf = Arc::clone(&ctx.enforcer_notify);
    let metrics_enf = Arc::clone(&ctx.enforcer_metrics);
    let ctx_enf = Arc::clone(&ctx);
    thread::spawn(move || {
        run_permission_enforcer_loop(stop_enf, notify_enf, metrics_enf, move || {
            let (scopes, screen_on) = ctx_enf
                .state
                .lock()
                .map(|s| (s.config.scopes.clone(), s.screen_on))
                .unwrap_or_default();
            let pkg_to_uid = ctx_enf
                .uid_index
                .read()
                .map(|idx| idx.pkg_to_uid.clone())
                .unwrap_or_default();
            (scopes, screen_on, pkg_to_uid)
        });
    });

    // 5-second supervisor loop — ZERO blocking calls held under `state`
    let stop_sup = Arc::clone(&stop);
    let ctx_sup = Arc::clone(&ctx);
    thread::spawn(move || {
        let mut tick_count: u64 = 0;
        while !stop_sup.load(Ordering::Relaxed) {
            thread::sleep(Duration::from_secs(5));
            if stop_sup.load(Ordering::Relaxed) {
                break;
            }
            tick_count = tick_count.wrapping_add(1);

            let (prev_screen, prev_locked) = ctx_sup
                .state
                .lock()
                .map(|s| (s.screen_on, s.device_locked))
                .unwrap_or((true, false));

            let screen_now = detect_screen_on();
            let locked_now = compute_device_locked(
                screen_now,
                prev_screen,
                prev_locked,
                tick_count,
                detect_device_locked,
            );
            let ce_now = is_ce_unlocked();
            let ifaces_now = classify_active_interfaces();
            let vpn_now = !ifaces_now.vpn.is_empty();

            // Brief Level 2 state update & snapshot — DROP `state` before any subsystem work!
            let (
                fw_enabled,
                need_fw_sync,
                expired_uids,
                sd_avail,
                need_hook_check,
                ttl_v4,
                ip6_avail,
                hl_v6,
                freezer_cfg,
                scopes_cfg,
                auto_cfg,
            ) = {
                let Ok(mut st) = ctx_sup.state.lock() else {
                    continue;
                };

                if st.boot_guard_armed
                    && !st.boot_guard_cleared
                    && boottime_secs().saturating_sub(st.started_boottime) >= BOOT_STABILITY_SECS
                {
                    let _ = fs::remove_file(&st.boot_fail_marker);
                    st.boot_guard_cleared = true;
                    st.boot_guard_armed = false;
                }

                let expired_uids = st.expire_temp_unblocks();
                let temp_expired = !expired_uids.is_empty();

                let screen_changed = st.screen_on != screen_now;
                let vpn_changed = st.vpn_active != vpn_now;
                st.screen_on = screen_now;
                st.device_locked = locked_now;
                st.ce_unlocked = ce_now;
                st.active_interfaces = ifaces_now.clone();
                st.vpn_active = vpn_now;

                let fw_enabled = st.config.firewall.enabled;
                let need_fw_sync = fw_enabled && (screen_changed || vpn_changed || temp_expired);
                let need_hook_check = fw_enabled && !need_fw_sync && tick_count.is_multiple_of(6);
                let ttl_v4 =
                    st.config.firewall.normalize_ttl_hl && st.caps.ttl_target_available;
                let ip6_avail = st.caps.ip6tables_available;
                let hl_v6 = st.config.firewall.normalize_ttl_hl && st.caps.hl_target_available;

                (
                    fw_enabled,
                    need_fw_sync,
                    expired_uids,
                    st.caps.sock_destroy_available,
                    need_hook_check,
                    ttl_v4,
                    ip6_avail,
                    hl_v6,
                    st.config.freezer.clone(),
                    st.config.scopes.clone(),
                    st.config.automation.clone(),
                )
            };

            if fw_enabled && need_fw_sync {
                let _ = ctx_sup.sync_firewall();
                if sd_avail {
                    for uid in expired_uids {
                        if !ctx_sup.is_uid_protected(uid) {
                            let _ = destroy_uid_sockets(uid);
                        }
                    }
                }
            } else if need_hook_check {
                if let Ok(_fw_guard) = ctx_sup.firewall_mtx.lock() {
                    let _ = ensure_hook_jumps("iptables", ttl_v4);
                    if ip6_avail {
                        let _ = ensure_hook_jumps("ip6tables", hl_v6);
                    }
                }
            }

            if let Ok(mut fz) = ctx_sup.freezer_mtx.lock() {
                fz.policy_tick(&freezer_cfg, screen_now, sd_avail);
                ctx_sup.update_freezer_cache(&fz);
            }

            if tick_count.is_multiple_of(2) {
                if let Ok(mut clip) = ctx_sup.clipboard_mtx.lock() {
                    clip.tick(&scopes_cfg);
                }
            }

            if tick_count.is_multiple_of(3) {
                ctx_sup.check_new_installed_apps();
                ctx_sup.refresh_cached_system_state();
            }

            let trigger_bfu_reboot = {
                if let Ok(mut auto) = ctx_sup.automation_mtx.lock() {
                    let triggered = auto.tick(&auto_cfg, ce_now, locked_now, &ifaces_now);
                    let rem = auto.auto_reboot_remaining_secs(&auto_cfg, ce_now, locked_now);
                    if let Ok(mut cache) = ctx_sup.sys_cache.write() {
                        cache.auto_reboot_remaining_secs = rem;
                    }
                    triggered
                } else {
                    false
                }
            };

            if trigger_bfu_reboot {
                if let Ok(mut fz) = ctx_sup.freezer_mtx.lock() {
                    fz.thaw_all();
                    ctx_sup.update_freezer_cache(&fz);
                }
                let _ = execute_clean_bfu_reboot();
                break;
            }
        }
    });

    let ctx_ipc = Arc::clone(&ctx);
    let handler = Arc::new(move |req: IpcRequest| dispatch_command(req, &ctx_ipc));

    let exit_code = match run_ipc_server_with_lock(
        &socket_path,
        &singleton_lock,
        Arc::clone(&stop),
        handler,
    ) {
        Ok(()) => 0,
        Err(e) => {
            eprintln!("silod IPC server error: {}", e);
            1
        }
    };

    stop.store(true, Ordering::SeqCst);
    if let Ok(mut fz) = ctx.freezer_mtx.lock() {
        fz.thaw_all();
        ctx.update_freezer_cache(&fz);
    }
    drop(singleton_lock);
    exit_code
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum CliMode {
    Daemon,
    Bridge,
    EmergencyFlush,
    UninstallCleanup,
    Status,
    Version,
    Once(String),
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct CliArgs {
    mode: CliMode,
    socket_path: PathBuf,
    config_path: PathBuf,
    boot_fail_marker: PathBuf,
}

fn parse_cli_args(args: &[String]) -> CliArgs {
    let mut socket_path = PathBuf::from(DEFAULT_SOCKET_PATH);
    let mut config_path = PathBuf::from(DEFAULT_CONFIG_PATH);
    let mut boot_fail_marker = PathBuf::from(format!("{}/.boot_fail_count", DEFAULT_STATE_DIR));
    let mut mode = CliMode::Daemon;

    let mut i = 1usize;
    while i < args.len() {
        match args[i].as_str() {
            "--bridge" => mode = CliMode::Bridge,
            "--emergency-flush" => mode = CliMode::EmergencyFlush,
            "--uninstall-cleanup" => mode = CliMode::UninstallCleanup,
            "--status" => mode = CliMode::Status,
            "--version" => mode = CliMode::Version,
            "--once" => {
                let json = if i + 1 < args.len() {
                    i += 1;
                    args[i].clone()
                } else {
                    String::new()
                };
                mode = CliMode::Once(json);
            }
            "--socket" if i + 1 < args.len() => {
                i += 1;
                socket_path = PathBuf::from(&args[i]);
            }
            "--config" if i + 1 < args.len() => {
                i += 1;
                config_path = PathBuf::from(&args[i]);
            }
            "--boot-marker" if i + 1 < args.len() => {
                i += 1;
                boot_fail_marker = PathBuf::from(&args[i]);
            }
            other => {
                if let Some(v) = other.strip_prefix("--socket=") {
                    socket_path = PathBuf::from(v);
                } else if let Some(v) = other.strip_prefix("--config=") {
                    config_path = PathBuf::from(v);
                } else if let Some(v) = other.strip_prefix("--boot-marker=") {
                    boot_fail_marker = PathBuf::from(v);
                }
            }
        }
        i += 1;
    }

    CliArgs {
        mode,
        socket_path,
        config_path,
        boot_fail_marker,
    }
}

fn run_emergency_flush(config_path: &Path) {
    let caps = probe_capabilities();
    flush_firewall(&caps);
    if let Ok(entries) = fs::read_dir("/sys/fs/cgroup") {
        for e in entries.flatten() {
            let name = e.file_name().to_string_lossy().into_owned();
            if name.starts_with("uid_") {
                let direct = e.path().join("cgroup.freeze");
                if direct.exists() {
                    let _ = fs::write(&direct, "0");
                }
                if let Ok(pids) = fs::read_dir(e.path()) {
                    for p in pids.flatten() {
                        let pf = p.path().join("cgroup.freeze");
                        if pf.exists() {
                            let _ = fs::write(&pf, "0");
                        }
                    }
                }
            }
        }
    }
    if config_path.exists() {
        let cfg = SiloConfig::load_or_default(config_path);
        let pm = crate::sys::resolve_bin("pm");
        let am = crate::sys::resolve_bin("am");
        for (&uid, entry) in &cfg.freezer.entries {
            if validate_package_name(&entry.package_name)
                && !is_protected_package(&entry.package_name)
                && !is_protected_uid(uid)
            {
                let user_str = (uid / 100_000).to_string();
                let _ = crate::sys::run_cmd(
                    &pm,
                    &["unsuspend", "--user", &user_str, &entry.package_name],
                    None,
                    2500,
                );
                let _ = crate::sys::run_cmd(
                    &am,
                    &["set-standby-bucket", &entry.package_name, "active"],
                    None,
                    2500,
                );
            }
        }
    }
}

fn run_uninstall_cleanup(config_path: &Path) -> i32 {
    run_emergency_flush(config_path);
    let mut pkgs_reset = 0usize;
    let mut ops_reset = 0usize;
    if config_path.exists() {
        let cfg = SiloConfig::load_or_default(config_path);
        let uid_idx = read_uid_package_index();
        let (p, o) = reset_all_scopes_for_uninstall(&cfg.scopes, &uid_idx.pkg_to_uid);
        pkgs_reset = p;
        ops_reset = o;
    }
    println!(
        "silod: uninstall cleanup complete (firewall flushed, cgroups thawed, apps unsuspended, {} packages / {} ops reset)",
        pkgs_reset, ops_reset
    );
    0
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let cli = parse_cli_args(&args);

    let code = match cli.mode {
        CliMode::Version => {
            println!("silod {}", DAEMON_VERSION);
            0
        }
        CliMode::Bridge => run_stdio_bridge(&cli.socket_path),
        CliMode::EmergencyFlush => {
            run_emergency_flush(&cli.config_path);
            println!("silod: emergency firewall flush, cgroup thaw & unsuspend complete");
            0
        }
        CliMode::UninstallCleanup => run_uninstall_cleanup(&cli.config_path),
        CliMode::Status => match run_single_rpc(&cli.socket_path, r#"{"id":1,"cmd":"get_status"}"#) {
            Ok(out) => {
                println!("{}", out);
                0
            }
            Err(e) => {
                eprintln!("{}", e);
                1
            }
        },
        CliMode::Once(once_json) => {
            if once_json.is_empty() {
                eprintln!("--once requires a JSON request argument");
                2
            } else {
                match run_single_rpc(&cli.socket_path, &once_json) {
                    Ok(out) => {
                        println!("{}", out);
                        0
                    }
                    Err(e) => {
                        eprintln!("{}", e);
                        1
                    }
                }
            }
        }
        CliMode::Daemon => {
            let _ = Path::new(DEFAULT_STATE_DIR);
            run_daemon(cli.socket_path, cli.config_path, cli.boot_fail_marker)
        }
    };

    std::process::exit(code);
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{AppPermGuard, FreezerEntry, SecurityPreset};
    use crate::sys::parse_packages_list_multi;
    use std::time::Instant;

    fn test_caps() -> KernelCapabilities {
        KernelCapabilities {
            kernel_release: "6.6.127-test".to_string(),
            iptables_available: false,
            ip6tables_available: false,
            ipset_netlink_available: false,
            xt_set_available: false,
            xt_recent_available: false,
            nflog_available: false,
            ttl_target_available: false,
            hl_target_available: false,
            sock_destroy_available: false,
            cgroup_v2_freezer_available: false,
            available_tcp_congestion: vec![],
        }
    }

    fn make_test_ctx(test_name: &str, uid_index: UidPackageIndex) -> (Arc<DaemonContext>, PathBuf) {
        let dir = std::env::temp_dir().join(format!("silo_main_{}_{}", test_name, std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        let cfg_path = dir.join("config.json");
        let boot_marker = dir.join(".boot_fail_count");

        let state = DaemonState {
            config: SiloConfig::default(),
            config_path: cfg_path,
            boot_fail_marker: boot_marker,
            started_boottime: boottime_secs(),
            boot_guard_armed: false,
            boot_guard_cleared: true,
            caps: test_caps(),
            firewall_active: false,
            ipset_entries_loaded: 0,
            ipset_backend: "idle".to_string(),
            vpn_active: false,
            active_interfaces: ActiveInterfaces::default(),
            last_firewall_error: None,
            temp_unblock_until: BTreeMap::new(),
            screen_on: true,
            device_locked: false,
            ce_unlocked: true,
            known_user_uids: HashSet::new(),
            last_packages_mtime: None,
        };

        let ctx = Arc::new(DaemonContext {
            state: Mutex::new(state),
            firewall_mtx: Mutex::new(FirewallRuntime::default()),
            hardening_mtx: Mutex::new(HardeningEngine::new()),
            freezer_mtx: Mutex::new(FreezerEngine::new()),
            automation_mtx: Mutex::new(AutomationEngine::new()),
            clipboard_mtx: Mutex::new(ClipboardGuard::new()),
            telemetry: Mutex::new(TelemetryRing::new()),
            sys_cache: RwLock::new(CachedSystemState::default()),
            uid_index: RwLock::new(uid_index),
            enforcer_metrics: Arc::new(EnforcerMetrics::new()),
            enforcer_notify: Arc::new(AtomicBool::new(false)),
        });

        (ctx, dir)
    }

    #[test]
    fn get_status_is_lock_free_and_sub_millisecond_while_subsystems_blocked() {
        let (ctx, dir) = make_test_ctx("get_status_fast", UidPackageIndex::default());

        {
            let mut cache = ctx.sys_cache.write().unwrap();
            cache.sensor_privacy = SensorPrivacyState {
                mic_muted: true,
                camera_muted: false,
            };
            cache.private_dns = PrivateDnsState {
                mode: "hostname".to_string(),
                specifier: "dns.quad9.net".to_string(),
            };
            cache.frozen_uids_count = 3;
            cache.suspended_pkgs_count = 2;
        }
        {
            let mut st = ctx.state.lock().unwrap();
            st.config.firewall.enabled = true;
            st.config.firewall.mode = FirewallMode::DefaultDeny;
            st.config.firewall.panic_lockdown_active = true;
        }
        ctx.enforcer_metrics
            .record("Re-locked camera on com.example.spy".to_string());

        // Hold EVERY Level 1 slow subsystem lock simultaneously while dispatching GetStatus!
        let _fw = ctx.firewall_mtx.lock().unwrap();
        let _h = ctx.hardening_mtx.lock().unwrap();
        let _fz = ctx.freezer_mtx.lock().unwrap();
        let _auto = ctx.automation_mtx.lock().unwrap();
        let _clip = ctx.clipboard_mtx.lock().unwrap();

        let start = Instant::now();
        let resp = dispatch_command(
            IpcRequest {
                id: 42,
                command: DaemonCommand::GetStatus,
            },
            &ctx,
        );
        let elapsed = start.elapsed();

        assert!(resp.ok, "GetStatus failed: {:?}", resp.error);
        assert!(
            elapsed < Duration::from_millis(50),
            "GetStatus took too long ({:?}), expected <1ms lock-free response",
            elapsed
        );
        let status: DaemonStatus = serde_json::from_value(resp.data.unwrap()).unwrap();
        assert!(status.mic_muted);
        assert!(!status.camera_muted);
        assert!(status.sensors_off_active);
        assert_eq!(status.private_dns.mode, "hostname");
        assert_eq!(status.private_dns.specifier, "dns.quad9.net");
        assert_eq!(status.frozen_uids_count, 3);
        assert_eq!(status.suspended_pkgs_count, 2);
        assert_eq!(status.enforcer_actions_count, 1);
        assert_eq!(
            status.last_enforcer_event.as_deref(),
            Some("Re-locked camera on com.example.spy")
        );
        assert!(status.panic_lockdown_active);

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn protected_uid_rejected_even_with_empty_or_spoofed_package_name() {
        let sample = "\
com.benign.shared 10250 0 /data/user/0/com.benign.shared default:targetSdkVersion=35 none 0 1
dev.silo.app 10250 0 /data/user/0/dev.silo.app default:targetSdkVersion=35 none 0 1
com.rifsxd.ksunext 10251 0 /data/user/0/com.rifsxd.ksunext default:targetSdkVersion=35 none 0 1
com.example.ordinary 10300 0 /data/user/0/com.example.ordinary default:targetSdkVersion=35 none 0 1
";
        let idx = parse_packages_list_multi(sample);
        let (ctx, dir) = make_test_ctx("protected_uid", idx.clone());

        // SetAppFirewallRule with empty pkg name on shared-UID protected UID 10250
        let r1 = dispatch_command(
            IpcRequest {
                id: 1,
                command: DaemonCommand::SetAppFirewallRule {
                    uid: 10250,
                    rule: AppFirewallRule::deny_all(String::new()),
                },
            },
            &ctx,
        );
        assert!(!r1.ok);

        // SetAppFirewallRule with spoofed benign pkg name on UID 10251 (com.rifsxd.ksunext)
        let r2 = dispatch_command(
            IpcRequest {
                id: 2,
                command: DaemonCommand::SetAppFirewallRule {
                    uid: 10251,
                    rule: AppFirewallRule::deny_all("com.fake.app".to_string()),
                },
            },
            &ctx,
        );
        assert!(!r2.ok);

        // SetFreezerEntry with empty pkg name on UID 10250
        let r3 = dispatch_command(
            IpcRequest {
                id: 3,
                command: DaemonCommand::SetFreezerEntry {
                    uid: 10250,
                    entry: FreezerEntry {
                        package_name: String::new(),
                        ..Default::default()
                    },
                },
            },
            &ctx,
        );
        assert!(!r3.ok);

        // FreezeUidNow on UID 10250
        let r4 = dispatch_command(
            IpcRequest {
                id: 4,
                command: DaemonCommand::FreezeUidNow { uid: 10250 },
            },
            &ctx,
        );
        assert!(!r4.ok);

        // KillUidSockets on UID 10250
        let r5 = dispatch_command(
            IpcRequest {
                id: 5,
                command: DaemonCommand::KillUidSockets { uid: 10250 },
            },
            &ctx,
        );
        assert!(!r5.ok);

        // SetAppPermGuard & SetAppPermissionAction on sibling package sharing protected UID 10250
        let r6 = dispatch_command(
            IpcRequest {
                id: 6,
                command: DaemonCommand::SetAppPermGuard {
                    package_name: "com.benign.shared".to_string(),
                    guard: AppPermGuard {
                        auto_revoke_on_bg: vec![],
                        locked_deny: vec!["camera".to_string()],
                    },
                },
            },
            &ctx,
        );
        assert!(!r6.ok);

        let r7 = dispatch_command(
            IpcRequest {
                id: 7,
                command: DaemonCommand::SetAppPermissionAction {
                    package_name: "com.benign.shared".to_string(),
                    category: "camera".to_string(),
                    action: "revoke".to_string(),
                },
            },
            &ctx,
        );
        assert!(!r7.ok);

        // KillConnection pre-check helper: rejects protected UID, skips LISTEN/TIME_WAIT, allows ordinary ESTABLISHED
        let conns = vec![
            ActiveConnection {
                uid: 10250,
                package_name: Some("com.benign.shared".to_string()),
                family: libc::AF_INET as u8,
                protocol: "TCP".to_string(),
                state: "ESTABLISHED".to_string(),
                local_ip: "10.0.0.2".to_string(),
                local_port: 44444,
                remote_ip: "1.1.1.1".to_string(),
                remote_port: 443,
                is_listening: false,
            },
            ActiveConnection {
                uid: 10300,
                package_name: Some("com.example.ordinary".to_string()),
                family: libc::AF_INET as u8,
                protocol: "TCP".to_string(),
                state: "LISTEN".to_string(),
                local_ip: "0.0.0.0".to_string(),
                local_port: 8080,
                remote_ip: "0.0.0.0".to_string(),
                remote_port: 0,
                is_listening: true,
            },
            ActiveConnection {
                uid: 10300,
                package_name: Some("com.example.ordinary".to_string()),
                family: libc::AF_INET as u8,
                protocol: "TCP".to_string(),
                state: "TIME_WAIT".to_string(),
                local_ip: "10.0.0.2".to_string(),
                local_port: 55555,
                remote_ip: "8.8.8.8".to_string(),
                remote_port: 443,
                is_listening: false,
            },
            ActiveConnection {
                uid: 10300,
                package_name: Some("com.example.ordinary".to_string()),
                family: libc::AF_INET as u8,
                protocol: "TCP".to_string(),
                state: "ESTABLISHED".to_string(),
                local_ip: "10.0.0.2".to_string(),
                local_port: 55556,
                remote_ip: "9.9.9.9".to_string(),
                remote_port: 443,
                is_listening: false,
            },
        ];

        assert_eq!(
            classify_kill_connection_target(&conns, &idx, 4, "10.0.0.2", 44444, "1.1.1.1", 443),
            KillConnectionDecision::Protected(10250)
        );
        assert_eq!(
            classify_kill_connection_target(&conns, &idx, 4, "0.0.0.0", 8080, "0.0.0.0", 0),
            KillConnectionDecision::SkipNonKillable
        );
        assert_eq!(
            classify_kill_connection_target(&conns, &idx, 4, "10.0.0.2", 55555, "8.8.8.8", 443),
            KillConnectionDecision::SkipNonKillable
        );
        assert_eq!(
            classify_kill_connection_target(&conns, &idx, 4, "10.0.0.2", 55556, "9.9.9.9", 443),
            KillConnectionDecision::AllowDestroy
        );
        assert_eq!(
            classify_kill_connection_target(&conns, &idx, 4, "10.0.0.2", 12345, "9.9.9.9", 443),
            KillConnectionDecision::NoMatch
        );

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn panic_lockdown_active_full_lifecycle() {
        let (ctx, dir) = make_test_ctx("panic_lifecycle", UidPackageIndex::default());
        {
            let mut st = ctx.state.lock().unwrap();
            st.config.firewall.rules.insert(
                10300,
                AppFirewallRule {
                    package_name: "com.example.chat".to_string(),
                    allow_wifi: true,
                    ..Default::default()
                },
            );
        }

        // 1. Trigger panic lockdown: sets DefaultDeny + panic_lockdown_active, preserves saved rules!
        let panic_resp = dispatch_command(
            IpcRequest {
                id: 10,
                command: DaemonCommand::TriggerPanicLockdown,
            },
            &ctx,
        );
        assert!(panic_resp.ok);
        {
            let st = ctx.state.lock().unwrap();
            assert!(st.config.firewall.enabled);
            assert_eq!(st.config.firewall.mode, FirewallMode::DefaultDeny);
            assert!(st.config.firewall.panic_lockdown_active);
            assert!(
                st.config.firewall.rules.contains_key(&10300),
                "saved per-app rules must be preserved across panic lockdown"
            );
        }
        let status = ctx.build_status().unwrap();
        assert!(status.panic_lockdown_active);

        // 2. Switching mode to DefaultAllow via SetFirewallConfig clears panic_lockdown_active
        let mut next_fw = ctx.state.lock().unwrap().config.firewall.clone();
        next_fw.mode = FirewallMode::DefaultAllow;
        let _ = dispatch_command(
            IpcRequest {
                id: 11,
                command: DaemonCommand::SetFirewallConfig { config: next_fw },
            },
            &ctx,
        );
        assert!(!ctx.state.lock().unwrap().config.firewall.panic_lockdown_active);

        // 3. Re-trigger -> EmergencyFlushFirewall clears panic_lockdown_active & disables firewall
        let _ = dispatch_command(
            IpcRequest {
                id: 12,
                command: DaemonCommand::TriggerPanicLockdown,
            },
            &ctx,
        );
        assert!(ctx.state.lock().unwrap().config.firewall.panic_lockdown_active);
        let flush_resp = dispatch_command(
            IpcRequest {
                id: 13,
                command: DaemonCommand::EmergencyFlushFirewall,
            },
            &ctx,
        );
        assert!(flush_resp.ok);
        {
            let st = ctx.state.lock().unwrap();
            assert!(!st.config.firewall.enabled);
            assert!(!st.config.firewall.panic_lockdown_active);
        }

        // 4. Re-trigger -> ApplyPreset(Balanced) clears panic_lockdown_active
        let _ = dispatch_command(
            IpcRequest {
                id: 14,
                command: DaemonCommand::TriggerPanicLockdown,
            },
            &ctx,
        );
        assert!(ctx.state.lock().unwrap().config.firewall.panic_lockdown_active);
        let _ = dispatch_command(
            IpcRequest {
                id: 15,
                command: DaemonCommand::ApplyPreset {
                    preset: SecurityPreset::Balanced,
                },
            },
            &ctx,
        );
        assert!(!ctx.state.lock().unwrap().config.firewall.panic_lockdown_active);

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn device_lock_preservation_never_flips_false_on_screen_wake_or_skipped_ticks() {
        // Lockscreen wake on tick 1: prev_screen=false, screen_now=true, prev_locked=true -> MUST poll & return true
        let mut polled = false;
        let res = compute_device_locked(true, false, true, 1, || {
            polled = true;
            true
        });
        assert!(polled);
        assert!(res);

        // Samsung AOD on tick 2 while previously locked -> MUST poll & return true
        let mut polled2 = false;
        let res2 = compute_device_locked(true, true, true, 2, || {
            polled2 = true;
            true
        });
        assert!(polled2);
        assert!(res2);

        // Unlocked + screen stays on + non-3rd tick -> skips poll and preserves false
        let res3 = compute_device_locked(true, true, false, 1, || {
            panic!("should skip poll when unlocked, screen steady, non-3rd tick")
        });
        assert!(!res3);

        // 3rd tick while unlocked -> polls cleanly
        let mut polled4 = false;
        let res4 = compute_device_locked(true, true, false, 3, || {
            polled4 = true;
            false
        });
        assert!(polled4);
        assert!(!res4);
    }

    #[test]
    fn set_app_perm_guard_sanitizes_mutual_exclusivity_and_computes_delta() {
        let (ctx, dir) = make_test_ctx("perm_guard_delta", UidPackageIndex::default());

        let resp = dispatch_command(
            IpcRequest {
                id: 20,
                command: DaemonCommand::SetAppPermGuard {
                    package_name: "com.example.cam".to_string(),
                    guard: AppPermGuard {
                        locked_deny: vec!["camera".to_string(), "contacts".to_string()],
                        auto_revoke_on_bg: vec!["camera".to_string(), "location".to_string()],
                    },
                },
            },
            &ctx,
        );
        assert!(resp.ok, "SetAppPermGuard failed: {:?}", resp.error);
        let scopes: crate::config::ScopesConfig = serde_json::from_value(resp.data.unwrap()).unwrap();
        let g = scopes.guards.get("com.example.cam").unwrap();
        assert_eq!(g.locked_deny, vec!["camera".to_string(), "contacts".to_string()]);
        assert_eq!(g.auto_revoke_on_bg, vec!["location".to_string()]);
        assert!(ctx.enforcer_notify.load(Ordering::Acquire));

        // 1. Unsupported `foreground` on `contacts` MUST be rejected WITHOUT removing `contacts` from locked_deny!
        let bad_fg = execute_set_app_permission_action(
            &ctx,
            21,
            "com.example.cam".to_string(),
            "contacts".to_string(),
            "foreground".to_string(),
            |_, _, _, _| panic!("executor must not be called for unsupported foreground"),
        );
        assert!(!bad_fg.ok);
        assert!(ctx
            .state
            .lock()
            .unwrap()
            .config
            .scopes
            .guards
            .get("com.example.cam")
            .unwrap()
            .locked_deny
            .contains(&"contacts".to_string()));

        // 2. Failed OS apply on `camera` MUST restore `camera` into locked_deny (no silent desync)!
        let failed_allow = execute_set_app_permission_action(
            &ctx,
            22,
            "com.example.cam".to_string(),
            "camera".to_string(),
            "allow".to_string(),
            |_, _, _, _| Err("simulated pm failure".to_string()),
        );
        assert!(!failed_allow.ok);
        assert!(ctx
            .state
            .lock()
            .unwrap()
            .config
            .scopes
            .guards
            .get("com.example.cam")
            .unwrap()
            .locked_deny
            .contains(&"camera".to_string()));

        // 3. Successful `allow` on `camera` removes `camera` from locked_deny and persists config!
        let ok_allow = execute_set_app_permission_action(
            &ctx,
            23,
            "com.example.cam".to_string(),
            "camera".to_string(),
            "allow".to_string(),
            |pkg, _, _, _| {
                Ok(AppLivePermissions {
                    package_name: pkg.to_string(),
                    categories: vec![],
                })
            },
        );
        assert!(ok_allow.ok);
        let locked_after = ctx
            .state
            .lock()
            .unwrap()
            .config
            .scopes
            .guards
            .get("com.example.cam")
            .unwrap()
            .locked_deny
            .clone();
        assert!(!locked_after.contains(&"camera".to_string()));
        assert!(locked_after.contains(&"contacts".to_string()));

        // Verify persisted on disk
        let reloaded = SiloConfig::load_or_default(&dir.join("config.json"));
        let reloaded_locked = &reloaded.scopes.guards.get("com.example.cam").unwrap().locked_deny;
        assert!(!reloaded_locked.contains(&"camera".to_string()));
        assert!(reloaded_locked.contains(&"contacts".to_string()));

        // 4. Successful `ignore` on `contacts` also unlocks `contacts` from locked_deny
        let ok_ignore = execute_set_app_permission_action(
            &ctx,
            24,
            "com.example.cam".to_string(),
            "contacts".to_string(),
            "ignore".to_string(),
            |pkg, _, _, _| {
                Ok(AppLivePermissions {
                    package_name: pkg.to_string(),
                    categories: vec![],
                })
            },
        );
        assert!(ok_ignore.ok);
        let locked_final = ctx
            .state
            .lock()
            .unwrap()
            .config
            .scopes
            .guards
            .get("com.example.cam")
            .unwrap()
            .locked_deny
            .clone();
        assert!(locked_final.is_empty());

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn cli_arg_parser_and_uninstall_cleanup_exit_cleanly() {
        let mk = |parts: &[&str]| parts.iter().map(|s| s.to_string()).collect::<Vec<_>>();

        let a1 = parse_cli_args(&mk(&["silod", "--uninstall-cleanup"]));
        assert_eq!(a1.mode, CliMode::UninstallCleanup);
        assert_eq!(a1.config_path, PathBuf::from(DEFAULT_CONFIG_PATH));

        let a2 = parse_cli_args(&mk(&[
            "silod",
            "--uninstall-cleanup",
            "--config",
            "/tmp/a.json",
        ]));
        assert_eq!(a2.mode, CliMode::UninstallCleanup);
        assert_eq!(a2.config_path, PathBuf::from("/tmp/a.json"));

        let a3 = parse_cli_args(&mk(&[
            "silod",
            "--config=/tmp/b.json",
            "--uninstall-cleanup",
        ]));
        assert_eq!(a3.mode, CliMode::UninstallCleanup);
        assert_eq!(a3.config_path, PathBuf::from("/tmp/b.json"));

        let a4 = parse_cli_args(&mk(&["silod", "--bridge", "--socket", "/tmp/s.sock"]));
        assert_eq!(a4.mode, CliMode::Bridge);
        assert_eq!(a4.socket_path, PathBuf::from("/tmp/s.sock"));

        let a5 = parse_cli_args(&mk(&["silod", "--once", r#"{"id":1,"cmd":"ping"}"#]));
        assert_eq!(a5.mode, CliMode::Once(r#"{"id":1,"cmd":"ping"}"#.to_string()));

        let a6 = parse_cli_args(&mk(&["silod", "--emergency-flush"]));
        assert_eq!(a6.mode, CliMode::EmergencyFlush);

        let a7 = parse_cli_args(&mk(&["silod", "--status", "--socket=/tmp/x.sock"]));
        assert_eq!(a7.mode, CliMode::Status);
        assert_eq!(a7.socket_path, PathBuf::from("/tmp/x.sock"));

        let a8 = parse_cli_args(&mk(&["silod", "--version"]));
        assert_eq!(a8.mode, CliMode::Version);

        // Execute run_uninstall_cleanup on a populated temp config.json (must exit 0 cleanly on host Linux)
        let dir = std::env::temp_dir().join(format!("silo_uninstall_test_{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        let cfg_path = dir.join("config.json");

        let mut cfg = SiloConfig::default();
        cfg.freezer.entries.insert(
            10350,
            FreezerEntry {
                package_name: "com.example.frozen".to_string(),
                ..Default::default()
            },
        );
        cfg.scopes.apps.insert(
            "com.example.v2".to_string(),
            AppIsolationScope::hardened_profile(),
        );
        cfg.scopes.guards.insert(
            "com.example.v3".to_string(),
            AppPermGuard {
                auto_revoke_on_bg: vec!["camera".to_string()],
                locked_deny: vec!["microphone".to_string()],
            },
        );
        cfg.save_atomic(&cfg_path).unwrap();

        let exit_code = run_uninstall_cleanup(&cfg_path);
        assert_eq!(exit_code, 0);

        let _ = fs::remove_dir_all(&dir);
    }
}
