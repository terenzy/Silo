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
use crate::ipc::{run_ipc_server, run_single_rpc, run_stdio_bridge};
use crate::netlink::{
    classify_active_interfaces, destroy_all_user_sockets, destroy_matching_connection,
    destroy_uid_sockets, list_active_connections, run_nflog_loop, SILO_NFLOG_GROUP,
};
use crate::protocol::{
    ActiveInterfaces, DaemonCommand, DaemonStatus, IpcRequest, IpcResponse, KernelCapabilities,
    PanicLockdownReport,
};
use crate::scopes::{
    apply_app_scope, bulk_revoke_category, clear_app_scope, get_privacy_access_log,
    inspect_app_permissions, set_app_permission_action, sync_all_scopes, ClipboardGuard,
    PermissionEnforcer,
};
use crate::sentinel::{run_live_proof, run_sentinel_audit};
use crate::sys::{
    boottime_secs, detect_device_locked, detect_screen_on, foreground_app_uids, is_ce_unlocked,
    read_uid_package_map,
};
use crate::telemetry::TelemetryRing;

use std::collections::{BTreeMap, HashMap, HashSet};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
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
    previously_blocked_uids: HashSet<u32>,
    /// Temporary network unblock grants: UID -> CLOCK_BOOTTIME expiry second.
    temp_unblock_until: BTreeMap<u32, u64>,
    screen_on: bool,
    device_locked: bool,
    ce_unlocked: bool,
    known_user_uids: HashSet<u32>,
    last_packages_mtime: Option<SystemTime>,
    uid_map: HashMap<u32, String>,
    freezer: FreezerEngine,
    hardening: HardeningEngine,
    automation: AutomationEngine,
    clipboard: ClipboardGuard,
    enforcer: PermissionEnforcer,
}

impl DaemonState {
    fn new(config_path: PathBuf, boot_fail_marker: PathBuf) -> Self {
        let config = SiloConfig::load_or_default(&config_path);
        let caps = probe_capabilities();
        let uid_map = read_uid_package_map();
        let known_user_uids: HashSet<u32> = uid_map
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

        Self {
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
            previously_blocked_uids: HashSet::new(),
            temp_unblock_until: BTreeMap::new(),
            screen_on: detect_screen_on(),
            device_locked: detect_device_locked(),
            ce_unlocked: is_ce_unlocked(),
            known_user_uids,
            last_packages_mtime,
            uid_map,
            freezer: FreezerEngine::new(),
            hardening: HardeningEngine::new(),
            automation: AutomationEngine::new(),
            clipboard: ClipboardGuard::new(),
            enforcer: PermissionEnforcer::new(),
        }
    }

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

    fn sync_firewall(&mut self) -> Result<(), String> {
        let temp_set = self.active_temp_unblock_set();
        match apply_firewall(
            &self.config.firewall,
            &mut self.caps,
            self.screen_on,
            &temp_set,
            &mut self.previously_blocked_uids,
        ) {
            Ok(res) => {
                self.firewall_active = self.config.firewall.enabled;
                self.ipset_entries_loaded = res.ipset_entries_loaded;
                self.ipset_backend = res.ipset_backend;
                self.vpn_active = res.vpn_active;
                self.active_interfaces = res.active_interfaces;
                self.last_firewall_error = res.warning;
                Ok(())
            }
            Err(e) => {
                self.last_firewall_error = Some(e.clone());
                Err(e)
            }
        }
    }

    fn sync_all_subsystems(&mut self) {
        let _ = self.sync_firewall();
        let cfg_h = self.config.hardening.clone();
        if cfg_h.enabled {
            let _ = self.hardening.apply(&cfg_h);
        }
        sync_all_scopes(&self.config.scopes);
    }

    fn status(&self, total_blocked: u64) -> DaemonStatus {
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

        let sp = self.automation.live_sensor_privacy();
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
            sensors_off_active: sp.mic_muted || sp.camera_muted,
            mic_muted: sp.mic_muted,
            camera_muted: sp.camera_muted,
            frozen_uids_count: self.freezer.currently_frozen().len(),
            suspended_pkgs_count: self.freezer.suspended_packages().len(),
            blocked_events_total: total_blocked,
            auto_reboot_remaining_secs: self.automation.auto_reboot_remaining_secs(
                &self.config.automation,
                self.ce_unlocked,
                self.device_locked,
            ),
            private_dns: get_private_dns(),
            radio_privacy: get_radio_privacy(),
            enforcer_rules_count,
            enforcer_actions_count: self.enforcer.actions_count,
            last_enforcer_event: self.enforcer.last_event.clone(),
            temp_unblocks_remaining,
            capabilities: self.caps.clone(),
            last_firewall_error: self.last_firewall_error.clone(),
        }
    }

    fn check_new_installed_apps(&mut self) {
        let cur_mtime = fs::metadata("/data/system/packages.list")
            .and_then(|m| m.modified())
            .ok();
        if cur_mtime.is_some() && cur_mtime == self.last_packages_mtime {
            return;
        }
        self.last_packages_mtime = cur_mtime;
        let fresh_map = read_uid_package_map();
        if fresh_map.is_empty() {
            return;
        }

        let baseline_ready = !self.known_user_uids.is_empty();
        let mut added_block = false;

        if baseline_ready && self.config.firewall.enabled && self.config.firewall.block_new_apps {
            for (&uid, pkg) in &fresh_map {
                if !is_protected_uid(uid)
                    && !is_protected_package(pkg)
                    && !self.known_user_uids.contains(&uid)
                    && !self.config.firewall.rules.contains_key(&uid)
                {
                    self.config
                        .firewall
                        .rules
                        .insert(uid, AppFirewallRule::deny_all(pkg.clone()));
                    added_block = true;
                }
            }
        }

        self.known_user_uids = fresh_map
            .keys()
            .copied()
            .filter(|&u| !is_protected_uid(u))
            .collect();
        self.uid_map = fresh_map;

        if added_block {
            let _ = self.save_config();
            let _ = self.sync_firewall();
        }
    }
}

fn dispatch_command(
    req: IpcRequest,
    state_mtx: &Arc<Mutex<DaemonState>>,
    telemetry_mtx: &Arc<Mutex<TelemetryRing>>,
) -> IpcResponse {
    let id = req.id;
    match req.command {
        DaemonCommand::Ping => IpcResponse::ok(
            id,
            serde_json::json!({ "pong": true, "version": DAEMON_VERSION }),
        ),

        DaemonCommand::GetStatus => {
            let total = telemetry_mtx
                .lock()
                .map(|t| t.total_blocked())
                .unwrap_or(0);
            let Ok(st) = state_mtx.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            IpcResponse::ok(id, st.status(total))
        }

        DaemonCommand::GetConfig => {
            let Ok(st) = state_mtx.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            IpcResponse::ok(id, &st.config)
        }

        DaemonCommand::ApplyPreset { preset } => {
            let Ok(mut st) = state_mtx.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            st.config.apply_preset(preset);
            if let Err(e) = st.config.sanitize() {
                return IpcResponse::err(id, e);
            }
            if let Err(e) = st.save_config() {
                return IpcResponse::err(id, e);
            }
            let fw_res = st.sync_firewall();
            let cfg_h = st.config.hardening.clone();
            if cfg_h.enabled {
                let _ = st.hardening.apply(&cfg_h);
            } else {
                let _ = st.hardening.rollback_all(&cfg_h);
            }
            if !st.config.freezer.enabled {
                st.freezer.thaw_all();
            }
            match fw_res {
                Ok(()) => IpcResponse::ok(id, &st.config),
                Err(e) => IpcResponse::err(id, format!("Preset saved, firewall error: {}", e)),
            }
        }

        DaemonCommand::SetFirewallConfig { mut config } => {
            let Ok(mut st) = state_mtx.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            let mut probe_cfg = st.config.clone();
            probe_cfg.firewall = std::mem::take(&mut config);
            if let Err(e) = probe_cfg.sanitize() {
                return IpcResponse::err(id, e);
            }
            st.config.firewall = probe_cfg.firewall;
            if let Err(e) = st.save_config() {
                return IpcResponse::err(id, e);
            }
            match st.sync_firewall() {
                Ok(()) => IpcResponse::ok(id, &st.config.firewall),
                Err(e) => IpcResponse::err(id, e),
            }
        }

        DaemonCommand::SetAppFirewallRule { uid, rule } => {
            if is_protected_uid(uid) || is_protected_package(&rule.package_name) {
                return IpcResponse::err(
                    id,
                    format!("Refused to modify protected system UID {}", uid),
                );
            }
            let Ok(mut st) = state_mtx.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            st.config.firewall.rules.insert(uid, rule);
            if let Err(e) = st.save_config() {
                return IpcResponse::err(id, e);
            }
            match st.sync_firewall() {
                Ok(()) => IpcResponse::ok_empty(id),
                Err(e) => IpcResponse::err(id, e),
            }
        }

        DaemonCommand::RemoveAppFirewallRule { uid } => {
            let Ok(mut st) = state_mtx.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            st.config.firewall.rules.remove(&uid);
            st.temp_unblock_until.remove(&uid);
            if let Err(e) = st.save_config() {
                return IpcResponse::err(id, e);
            }
            match st.sync_firewall() {
                Ok(()) => IpcResponse::ok_empty(id),
                Err(e) => IpcResponse::err(id, e),
            }
        }

        DaemonCommand::SetTempUnblock {
            uid,
            duration_minutes,
        } => {
            if is_protected_uid(uid) {
                return IpcResponse::err(id, "System UID is already exempted");
            }
            let mins = duration_minutes.clamp(1, 240);
            let Ok(mut st) = state_mtx.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            let expiry = boottime_secs().saturating_add(u64::from(mins) * 60);
            st.temp_unblock_until.insert(uid, expiry);
            match st.sync_firewall() {
                Ok(()) => IpcResponse::ok(
                    id,
                    serde_json::json!({ "uid": uid, "remaining_secs": u64::from(mins) * 60 }),
                ),
                Err(e) => IpcResponse::err(id, e),
            }
        }

        DaemonCommand::ClearTempUnblock { uid } => {
            let Ok(mut st) = state_mtx.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            st.temp_unblock_until.remove(&uid);
            let res = st.sync_firewall();
            if st.caps.sock_destroy_available {
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
            let Ok(mut st) = state_mtx.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            st.config.firewall.ip_blocklist = valid;
            let _ = st.config.sanitize();
            if let Err(e) = st.save_config() {
                return IpcResponse::err(id, e);
            }
            let count = st.config.firewall.ip_blocklist.len();
            match st.sync_firewall() {
                Ok(()) => IpcResponse::ok(
                    id,
                    serde_json::json!({
                        "loaded": count,
                        "rejected": rejected,
                        "backend": st.ipset_backend
                    }),
                ),
                Err(e) => IpcResponse::err(id, e),
            }
        }

        DaemonCommand::EmergencyFlushFirewall => {
            let Ok(mut st) = state_mtx.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            st.config.firewall.enabled = false;
            flush_firewall(&st.caps);
            st.firewall_active = false;
            st.ipset_entries_loaded = 0;
            st.previously_blocked_uids.clear();
            st.temp_unblock_until.clear();
            let _ = st.save_config();
            IpcResponse::ok_empty(id)
        }

        DaemonCommand::KillUidSockets { uid } => {
            if is_protected_uid(uid) {
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
        } => match destroy_matching_connection(
            family,
            &local_ip,
            local_port,
            &remote_ip,
            remote_port,
        ) {
            Ok(killed) => IpcResponse::ok(id, serde_json::json!({ "killed": killed })),
            Err(e) => IpcResponse::err(id, e),
        },

        DaemonCommand::GetLiveConnections => {
            let uid_map = state_mtx
                .lock()
                .map(|st| st.uid_map.clone())
                .unwrap_or_else(|_| read_uid_package_map());
            let conns = list_active_connections(&uid_map);
            IpcResponse::ok(id, conns)
        }

        DaemonCommand::GetTelemetry { limit } => {
            let Ok(ring) = telemetry_mtx.lock() else {
                return IpcResponse::err(id, "telemetry lock poisoned");
            };
            IpcResponse::ok(id, ring.snapshot(limit.unwrap_or(200)))
        }

        DaemonCommand::ClearTelemetry => {
            if let Ok(mut ring) = telemetry_mtx.lock() {
                ring.clear();
            }
            IpcResponse::ok_empty(id)
        }

        DaemonCommand::SetFreezerEnabled {
            enabled,
            background_grace_secs,
        } => {
            let Ok(mut st) = state_mtx.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            st.config.freezer.enabled = enabled;
            st.config.freezer.background_grace_secs = background_grace_secs.clamp(5, 600);
            if !enabled {
                st.freezer.thaw_all();
            }
            let _ = st.save_config();
            let status = st.freezer.status(&st.config.freezer);
            IpcResponse::ok(id, status)
        }

        DaemonCommand::SetFreezerEntry { uid, entry } => {
            if is_protected_uid(uid) || is_protected_package(&entry.package_name) {
                return IpcResponse::err(
                    id,
                    format!("Refused to add protected UID {} to freezer", uid),
                );
            }
            let Ok(mut st) = state_mtx.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            st.config.freezer.entries.insert(uid, entry);
            let _ = st.save_config();
            let status = st.freezer.status(&st.config.freezer);
            IpcResponse::ok(id, status)
        }

        DaemonCommand::RemoveFreezerEntry { uid } => {
            let Ok(mut st) = state_mtx.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            let pkg = st
                .config
                .freezer
                .entries
                .remove(&uid)
                .map(|e| e.package_name);
            let _ = st.freezer.thaw_uid_now(uid, pkg.as_deref());
            let _ = st.save_config();
            let status = st.freezer.status(&st.config.freezer);
            IpcResponse::ok(id, status)
        }

        DaemonCommand::FreezeUidNow { uid } => {
            let Ok(mut st) = state_mtx.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            let (pkg, mode, kill_sock) = st
                .config
                .freezer
                .entries
                .get(&uid)
                .map(|e| (e.package_name.clone(), e.mode, e.kill_sockets_on_freeze))
                .unwrap_or_else(|| {
                    (
                        st.uid_map.get(&uid).cloned().unwrap_or_default(),
                        FreezeMode::SoftCgroup,
                        true,
                    )
                });
            let sd = st.caps.sock_destroy_available;
            match st.freezer.freeze_uid_now(uid, &pkg, mode, kill_sock, sd) {
                Ok(_detail) => IpcResponse::ok(id, st.freezer.status(&st.config.freezer)),
                Err(e) => IpcResponse::err(id, e),
            }
        }

        DaemonCommand::ThawUidNow { uid } => {
            let Ok(mut st) = state_mtx.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            let pkg = st
                .config
                .freezer
                .entries
                .get(&uid)
                .map(|e| e.package_name.clone())
                .or_else(|| st.uid_map.get(&uid).cloned());
            match st.freezer.thaw_uid_now(uid, pkg.as_deref()) {
                Ok(()) => IpcResponse::ok(id, st.freezer.status(&st.config.freezer)),
                Err(e) => IpcResponse::err(id, e),
            }
        }

        DaemonCommand::ThawAllNow => {
            let Ok(mut st) = state_mtx.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            st.freezer.thaw_all();
            IpcResponse::ok(id, st.freezer.status(&st.config.freezer))
        }

        DaemonCommand::GetFreezerStatus => {
            let Ok(st) = state_mtx.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            IpcResponse::ok(id, st.freezer.status(&st.config.freezer))
        }

        DaemonCommand::SetAppScope {
            package_name,
            scope,
        } => {
            if !validate_package_name(&package_name) || is_protected_package(&package_name) {
                return IpcResponse::err(
                    id,
                    format!("Refused to modify scopes for {}", package_name),
                );
            }
            match apply_app_scope(&package_name, &scope) {
                Ok(applied) => {
                    let Ok(mut st) = state_mtx.lock() else {
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
            let profile = AppIsolationScope::hardened_profile();
            if let Err(e) = apply_app_scope(&package_name, &profile) {
                return IpcResponse::err(id, e);
            }
            let Ok(mut st) = state_mtx.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            st.config.scopes.apps.insert(package_name, profile.clone());
            let _ = st.save_config();
            IpcResponse::ok(id, profile)
        }

        DaemonCommand::ClearAppScope { package_name } => {
            if let Err(e) = clear_app_scope(&package_name) {
                return IpcResponse::err(id, e);
            }
            let Ok(mut st) = state_mtx.lock() else {
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
            let Ok(mut st) = state_mtx.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            st.config.scopes.auto_clear_clipboard = enabled;
            st.config.scopes.clipboard_clear_secs = clear_secs;
            let _ = st.config.sanitize();
            let _ = st.save_config();
            IpcResponse::ok(id, &st.config.scopes)
        }

        DaemonCommand::ClearClipboardNow => {
            let Ok(mut st) = state_mtx.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            match st.clipboard.clear_now() {
                Ok(()) => IpcResponse::ok_empty(id),
                Err(e) => IpcResponse::err(id, e),
            }
        }

        DaemonCommand::InspectAppPermissions { package_name } => {
            IpcResponse::ok(id, inspect_app_permissions(&package_name))
        }

        DaemonCommand::SetAppPermissionAction {
            package_name,
            category,
            action,
        } => match set_app_permission_action(&package_name, &category, &action) {
            Ok(live) => IpcResponse::ok(id, live),
            Err(e) => IpcResponse::err(id, e),
        },

        DaemonCommand::SetAppPermGuard {
            package_name,
            guard,
        } => {
            if !validate_package_name(&package_name) || is_protected_package(&package_name) {
                return IpcResponse::err(
                    id,
                    format!("Invalid or protected package: {}", package_name),
                );
            }
            let Ok(mut st) = state_mtx.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            if guard.is_empty() {
                st.config.scopes.guards.remove(&package_name);
            } else {
                st.config.scopes.guards.insert(package_name.clone(), guard.clone());
            }
            let _ = st.config.sanitize();
            if let Err(e) = st.save_config() {
                return IpcResponse::err(id, e);
            }
            // Immediately apply any newly locked categories
            if let Some(saved_guard) = st.config.scopes.guards.get(&package_name).cloned() {
                for cat in &saved_guard.locked_deny {
                    let _ = set_app_permission_action(&package_name, cat, "revoke");
                }
            }
            IpcResponse::ok(id, &st.config.scopes)
        }

        DaemonCommand::BulkRevokeCategory { category, packages } => {
            match bulk_revoke_category(&category, &packages) {
                Ok(revoked) => IpcResponse::ok(
                    id,
                    serde_json::json!({ "category": category, "revoked_count": revoked }),
                ),
                Err(e) => IpcResponse::err(id, e),
            }
        }

        DaemonCommand::GetPrivacyAccessLog { limit } => {
            let uid_map = state_mtx
                .lock()
                .map(|s| s.uid_map.clone())
                .unwrap_or_default();
            let events = get_privacy_access_log(&uid_map, limit.unwrap_or(80));
            IpcResponse::ok(id, events)
        }

        DaemonCommand::SetHardeningConfig { config } => {
            let Ok(mut st) = state_mtx.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            let mut probe = st.config.clone();
            probe.hardening = config;
            if let Err(e) = probe.sanitize() {
                return IpcResponse::err(id, e);
            }
            st.config.hardening = probe.hardening;
            let _ = st.save_config();
            let h_cfg = st.config.hardening.clone();
            let status = if h_cfg.enabled {
                st.hardening.apply(&h_cfg)
            } else {
                st.hardening.rollback_all(&h_cfg)
            };
            IpcResponse::ok(id, status)
        }

        DaemonCommand::RollbackHardening => {
            let Ok(mut st) = state_mtx.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            st.config.hardening.enabled = false;
            let _ = st.save_config();
            let h_cfg = st.config.hardening.clone();
            let status = st.hardening.rollback_all(&h_cfg);
            IpcResponse::ok(id, status)
        }

        DaemonCommand::GetHardeningStatus => {
            let Ok(st) = state_mtx.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            IpcResponse::ok(id, st.hardening.status(&st.config.hardening))
        }

        DaemonCommand::SetAutomationConfig { config } => {
            let Ok(mut st) = state_mtx.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            st.config.automation = config;
            let _ = st.config.sanitize();
            let _ = st.save_config();
            IpcResponse::ok(id, &st.config.automation)
        }

        DaemonCommand::SetSensorsOff { enabled } => {
            let Ok(mut st) = state_mtx.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            match st.automation.set_sensor_privacy("all", enabled) {
                Ok(live) => IpcResponse::ok(id, live),
                Err(e) => IpcResponse::err(id, e),
            }
        }

        DaemonCommand::SetSensorPrivacy { sensor, enabled } => {
            let Ok(mut st) = state_mtx.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            match st.automation.set_sensor_privacy(&sensor, enabled) {
                Ok(live) => IpcResponse::ok(id, live),
                Err(e) => IpcResponse::err(id, e),
            }
        }

        DaemonCommand::GetPrivateDns => IpcResponse::ok(id, get_private_dns()),

        DaemonCommand::SetPrivateDns { mode, specifier } => {
            match set_private_dns(&mode, &specifier) {
                Ok(dns) => IpcResponse::ok(id, dns),
                Err(e) => IpcResponse::err(id, e),
            }
        }

        DaemonCommand::GetRadioPrivacy => IpcResponse::ok(id, get_radio_privacy()),

        DaemonCommand::SetRadioPrivacy {
            enhanced_mac_randomization,
            disable_wifi_ble_scanning,
            disable_captive_portal,
        } => match set_radio_privacy(
            enhanced_mac_randomization,
            disable_wifi_ble_scanning,
            disable_captive_portal,
        ) {
            Ok(rp) => IpcResponse::ok(id, rp),
            Err(e) => IpcResponse::err(id, e),
        },

        DaemonCommand::TriggerPanicLockdown => {
            let Ok(mut st) = state_mtx.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            st.config.firewall.enabled = true;
            st.config.firewall.mode = FirewallMode::DefaultDeny;
            st.temp_unblock_until.clear();
            let _ = st.save_config();
            let firewall_locked = st.sync_firewall().is_ok();
            let sockets_destroyed = if st.caps.sock_destroy_available {
                destroy_all_user_sockets()
            } else {
                0
            };
            let sp = st
                .automation
                .set_sensor_privacy("all", true)
                .unwrap_or_else(|_| st.automation.live_sensor_privacy());
            let (wifi_disabled, bluetooth_disabled) = disable_wifi_and_bt_now();
            let clipboard_cleared = st.clipboard.clear_now().is_ok();

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
            if let Ok(mut st) = state_mtx.lock() {
                st.freezer.thaw_all();
            }
            match execute_clean_bfu_reboot() {
                Ok(()) => IpcResponse::ok_empty(id),
                Err(e) => IpcResponse::err(id, e),
            }
        }

        DaemonCommand::RunSentinelAudit => {
            let (cfg_clone, fw_active) = match state_mtx.lock() {
                Ok(st) => (st.config.clone(), st.firewall_active),
                Err(_) => return IpcResponse::err(id, "state lock poisoned"),
            };
            let report = run_sentinel_audit(&cfg_clone, fw_active);
            IpcResponse::ok(id, report)
        }

        DaemonCommand::RunLiveProof => {
            let Ok(st) = state_mtx.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            let proof = run_live_proof(&st.config, &st.caps, &st.hardening, &st.freezer);
            IpcResponse::ok(id, proof)
        }

        DaemonCommand::ExportConfigJson => {
            let Ok(st) = state_mtx.lock() else {
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
            let Ok(mut st) = state_mtx.lock() else {
                return IpcResponse::err(id, "state lock poisoned");
            };
            st.config = imported;
            if let Err(e) = st.save_config() {
                return IpcResponse::err(id, e);
            }
            st.sync_all_subsystems();
            IpcResponse::ok(id, &st.config)
        }
    }
}

fn run_daemon(socket_path: PathBuf, config_path: PathBuf, boot_fail_marker: PathBuf) -> i32 {
    install_signal_handlers();

    let stop = Arc::new(AtomicBool::new(false));
    let telemetry = Arc::new(Mutex::new(TelemetryRing::new()));
    let state = Arc::new(Mutex::new(DaemonState::new(config_path, boot_fail_marker)));

    if let Ok(mut st) = state.lock() {
        st.sync_all_subsystems();
    }

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

    let nflog_avail = state
        .lock()
        .map(|s| s.caps.nflog_available)
        .unwrap_or(false);
    if nflog_avail {
        let stop_nflog = Arc::clone(&stop);
        let tel_nflog = Arc::clone(&telemetry);
        let st_nflog = Arc::clone(&state);
        thread::spawn(move || {
            run_nflog_loop(SILO_NFLOG_GROUP, stop_nflog, move |ev| {
                let uid_map = st_nflog
                    .lock()
                    .map(|s| s.uid_map.clone())
                    .unwrap_or_default();
                if let Ok(mut ring) = tel_nflog.lock() {
                    ring.push(ev, &uid_map);
                }
            });
        });
    }

    let stop_thaw = Arc::clone(&stop);
    let st_thaw = Arc::clone(&state);
    thread::spawn(move || {
        while !stop_thaw.load(Ordering::Relaxed) {
            let should_check = st_thaw
                .lock()
                .map(|s| s.screen_on && !s.freezer.currently_frozen().is_empty())
                .unwrap_or(false);
            if should_check {
                if let Ok(mut st) = st_thaw.lock() {
                    st.freezer.fast_foreground_thaw_tick();
                }
                thread::sleep(Duration::from_millis(400));
            } else {
                thread::sleep(Duration::from_secs(2));
            }
        }
    });

    let stop_sup = Arc::clone(&stop);
    let st_sup = Arc::clone(&state);
    thread::spawn(move || {
        let mut tick_count: u64 = 0;
        while !stop_sup.load(Ordering::Relaxed) {
            thread::sleep(Duration::from_secs(5));
            if stop_sup.load(Ordering::Relaxed) {
                break;
            }
            tick_count = tick_count.wrapping_add(1);

            let screen_now = detect_screen_on();
            let locked_now = if !screen_now || tick_count.is_multiple_of(3) {
                detect_device_locked()
            } else {
                false
            };
            let ce_now = is_ce_unlocked();
            let ifaces_now = classify_active_interfaces();
            let vpn_now = !ifaces_now.vpn.is_empty();

            let mut trigger_bfu_reboot = false;

            if let Ok(mut st) = st_sup.lock() {
                if st.boot_guard_armed
                    && !st.boot_guard_cleared
                    && boottime_secs().saturating_sub(st.started_boottime) >= BOOT_STABILITY_SECS
                {
                    let _ = fs::remove_file(&st.boot_fail_marker);
                    st.boot_guard_cleared = true;
                    st.boot_guard_armed = false;
                }

                // Expire any temporary network unblock grants & re-lock + SOCK_DESTROY immediately
                let expired_uids = st.expire_temp_unblocks();
                let temp_expired = !expired_uids.is_empty();

                let screen_changed = st.screen_on != screen_now;
                let vpn_changed = st.vpn_active != vpn_now;
                st.screen_on = screen_now;
                st.device_locked = locked_now;
                st.ce_unlocked = ce_now;
                st.active_interfaces = ifaces_now.clone();
                st.vpn_active = vpn_now;

                if st.config.firewall.enabled && (screen_changed || vpn_changed || temp_expired) {
                    let _ = st.sync_firewall();
                    if temp_expired && st.caps.sock_destroy_available {
                        for uid in expired_uids {
                            let _ = destroy_uid_sockets(uid);
                        }
                    }
                } else if st.config.firewall.enabled && tick_count.is_multiple_of(6) {
                    let ttl_v4 =
                        st.config.firewall.normalize_ttl_hl && st.caps.ttl_target_available;
                    let _ = ensure_hook_jumps("iptables", ttl_v4);
                    if st.caps.ip6tables_available {
                        let hl_v6 =
                            st.config.firewall.normalize_ttl_hl && st.caps.hl_target_available;
                        let _ = ensure_hook_jumps("ip6tables", hl_v6);
                    }
                }

                let freezer_cfg = st.config.freezer.clone();
                let sd_avail = st.caps.sock_destroy_available;
                st.freezer.policy_tick(&freezer_cfg, screen_now, sd_avail);

                if !st.config.scopes.guards.is_empty() {
                    let scopes_cfg = st.config.scopes.clone();
                    let fg_uids = foreground_app_uids();
                    let uid_map = st.uid_map.clone();
                    st.enforcer.tick(&scopes_cfg, screen_now, &fg_uids, &uid_map);
                }

                if tick_count.is_multiple_of(2) {
                    let scopes_cfg = st.config.scopes.clone();
                    st.clipboard.tick(&scopes_cfg);
                }

                if tick_count.is_multiple_of(3) {
                    st.check_new_installed_apps();
                }

                let auto_cfg = st.config.automation.clone();
                if st
                    .automation
                    .tick(&auto_cfg, ce_now, locked_now, &ifaces_now)
                {
                    st.freezer.thaw_all();
                    trigger_bfu_reboot = true;
                }
            }

            if trigger_bfu_reboot {
                let _ = execute_clean_bfu_reboot();
                break;
            }
        }
    });

    let st_ipc = Arc::clone(&state);
    let tel_ipc = Arc::clone(&telemetry);
    let handler = Arc::new(move |req: IpcRequest| dispatch_command(req, &st_ipc, &tel_ipc));

    let exit_code = match run_ipc_server(&socket_path, Arc::clone(&stop), handler) {
        Ok(()) => 0,
        Err(e) => {
            eprintln!("silod IPC server error: {}", e);
            1
        }
    };

    stop.store(true, Ordering::SeqCst);
    if let Ok(mut st) = state.lock() {
        st.freezer.thaw_all();
    }
    exit_code
}

fn main() {
    let args: Vec<String> = std::env::args().collect();

    let mut socket_path = PathBuf::from(DEFAULT_SOCKET_PATH);
    let mut config_path = PathBuf::from(DEFAULT_CONFIG_PATH);
    let mut boot_fail_marker = PathBuf::from(format!("{}/.boot_fail_count", DEFAULT_STATE_DIR));

    let mut mode = "daemon";
    let mut once_json = String::new();

    let mut i = 1usize;
    while i < args.len() {
        match args[i].as_str() {
            "--bridge" => mode = "bridge",
            "--emergency-flush" => mode = "emergency_flush",
            "--status" => mode = "status",
            "--version" => {
                println!("silod {}", DAEMON_VERSION);
                return;
            }
            "--once" => {
                mode = "once";
                if i + 1 < args.len() {
                    once_json = args[i + 1].clone();
                    i += 1;
                }
            }
            "--socket" if i + 1 < args.len() => {
                socket_path = PathBuf::from(&args[i + 1]);
                i += 1;
            }
            "--config" if i + 1 < args.len() => {
                config_path = PathBuf::from(&args[i + 1]);
                i += 1;
            }
            "--boot-marker" if i + 1 < args.len() => {
                boot_fail_marker = PathBuf::from(&args[i + 1]);
                i += 1;
            }
            _ => {}
        }
        i += 1;
    }

    let code = match mode {
        "bridge" => run_stdio_bridge(&socket_path),
        "emergency_flush" => {
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
                let cfg = SiloConfig::load_or_default(&config_path);
                let pm = crate::sys::resolve_bin("pm");
                for entry in cfg.freezer.entries.values() {
                    if validate_package_name(&entry.package_name)
                        && !is_protected_package(&entry.package_name)
                    {
                        let _ = crate::sys::run_cmd(
                            &pm,
                            &["unsuspend", "--user", "0", &entry.package_name],
                            None,
                            2500,
                        );
                    }
                }
            }
            println!("silod: emergency firewall flush, cgroup thaw & unsuspend complete");
            0
        }
        "status" => match run_single_rpc(&socket_path, r#"{"id":1,"cmd":"get_status"}"#) {
            Ok(out) => {
                println!("{}", out);
                0
            }
            Err(e) => {
                eprintln!("{}", e);
                1
            }
        },
        "once" => {
            if once_json.is_empty() {
                eprintln!("--once requires a JSON request argument");
                2
            } else {
                match run_single_rpc(&socket_path, &once_json) {
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
        _ => {
            let _ = Path::new(DEFAULT_STATE_DIR);
            run_daemon(socket_path, config_path, boot_fail_marker)
        }
    };

    std::process::exit(code);
}
