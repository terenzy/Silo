use crate::config::{
    AppFirewallRule, AppIsolationScope, AppPermGuard, AutomationConfig, FirewallConfig,
    FreezerEntry, HardeningConfig, SecurityPreset,
};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

pub const MAX_IPC_LINE_BYTES: usize = 512 * 1024;

/// Raw wire envelope avoiding `#[serde(flatten)]` (which broke `BTreeMap<u32, _>` in v1.0.0!).
#[derive(Debug, Clone, Deserialize)]
struct RawIpcEnvelope {
    pub id: u64,
    pub cmd: String,
    #[serde(default)]
    pub params: serde_json::Value,
}

#[derive(Debug, Clone)]
pub struct IpcRequest {
    pub id: u64,
    pub command: DaemonCommand,
}

#[derive(Debug, Clone)]
pub enum DaemonCommand {
    Ping,
    GetStatus,
    GetConfig,
    ApplyPreset {
        preset: SecurityPreset,
    },
    SetFirewallConfig {
        config: FirewallConfig,
    },
    SetAppFirewallRule {
        uid: u32,
        rule: AppFirewallRule,
    },
    RemoveAppFirewallRule {
        uid: u32,
    },
    SetTempUnblock {
        uid: u32,
        duration_minutes: u32,
    },
    ClearTempUnblock {
        uid: u32,
    },
    ReplaceIpBlocklist {
        entries: Vec<String>,
    },
    EmergencyFlushFirewall,
    KillUidSockets {
        uid: u32,
    },
    KillConnection {
        family: u8,
        local_ip: String,
        local_port: u16,
        remote_ip: String,
        remote_port: u16,
    },
    GetLiveConnections,
    GetTelemetry {
        limit: Option<usize>,
    },
    ClearTelemetry,
    SetFreezerEnabled {
        enabled: bool,
        background_grace_secs: u32,
    },
    SetFreezerEntry {
        uid: u32,
        entry: FreezerEntry,
    },
    RemoveFreezerEntry {
        uid: u32,
    },
    FreezeUidNow {
        uid: u32,
    },
    ThawUidNow {
        uid: u32,
    },
    ThawAllNow,
    GetFreezerStatus,
    SetAppScope {
        package_name: String,
        scope: AppIsolationScope,
    },
    ApplyHardenedScope {
        package_name: String,
    },
    ClearAppScope {
        package_name: String,
    },
    InspectAppPermissions {
        package_name: String,
    },
    SetAppPermissionAction {
        package_name: String,
        category: String,
        action: String,
    },
    SetAppPermGuard {
        package_name: String,
        guard: AppPermGuard,
    },
    BulkRevokeCategory {
        category: String,
        packages: Vec<String>,
    },
    GetPrivacyAccessLog {
        limit: Option<usize>,
    },
    SetAutoClearClipboard {
        enabled: bool,
        clear_secs: u32,
    },
    ClearClipboardNow,
    SetHardeningConfig {
        config: HardeningConfig,
    },
    RollbackHardening,
    GetHardeningStatus,
    SetAutomationConfig {
        config: AutomationConfig,
    },
    SetSensorsOff {
        enabled: bool,
    },
    SetSensorPrivacy {
        sensor: String,
        enabled: bool,
    },
    GetPrivateDns,
    SetPrivateDns {
        mode: String,
        specifier: String,
    },
    GetRadioPrivacy,
    SetRadioPrivacy {
        enhanced_mac_randomization: Option<bool>,
        disable_wifi_ble_scanning: Option<bool>,
        disable_captive_portal: Option<bool>,
    },
    TriggerPanicLockdown,
    TriggerPanicReboot {
        confirm_token: String,
    },
    RunSentinelAudit,
    RunLiveProof,
    ExportConfigJson,
    ImportConfigJson {
        json: String,
    },
}

#[derive(Deserialize)]
struct PresetParams {
    preset: SecurityPreset,
}
#[derive(Deserialize)]
struct FirewallCfgParams {
    config: FirewallConfig,
}
#[derive(Deserialize)]
struct AppFwRuleParams {
    uid: u32,
    rule: AppFirewallRule,
}
#[derive(Deserialize)]
struct UidParams {
    uid: u32,
}
#[derive(Deserialize)]
struct TempUnblockParams {
    uid: u32,
    duration_minutes: u32,
}
#[derive(Deserialize)]
struct BlocklistParams {
    entries: Vec<String>,
}
#[derive(Deserialize)]
struct KillConnParams {
    family: u8,
    local_ip: String,
    local_port: u16,
    remote_ip: String,
    remote_port: u16,
}
#[derive(Deserialize)]
struct LimitParams {
    #[serde(default)]
    limit: Option<usize>,
}
#[derive(Deserialize)]
struct FreezerEnableParams {
    enabled: bool,
    background_grace_secs: u32,
}
#[derive(Deserialize)]
struct FreezerEntryParams {
    uid: u32,
    entry: FreezerEntry,
}
#[derive(Deserialize)]
struct AppScopeParams {
    package_name: String,
    scope: AppIsolationScope,
}
#[derive(Deserialize)]
struct PkgParams {
    package_name: String,
}
#[derive(Deserialize)]
struct PermActionParams {
    package_name: String,
    category: String,
    action: String,
}
#[derive(Deserialize)]
struct PermGuardParams {
    package_name: String,
    guard: AppPermGuard,
}
#[derive(Deserialize)]
struct BulkRevokeParams {
    category: String,
    packages: Vec<String>,
}
#[derive(Deserialize)]
struct ClipParams {
    enabled: bool,
    clear_secs: u32,
}
#[derive(Deserialize)]
struct HardeningCfgParams {
    config: HardeningConfig,
}
#[derive(Deserialize)]
struct AutomationCfgParams {
    config: AutomationConfig,
}
#[derive(Deserialize)]
struct EnabledParams {
    enabled: bool,
}
#[derive(Deserialize)]
struct SensorPrivacyParams {
    sensor: String,
    enabled: bool,
}
#[derive(Deserialize)]
struct PrivateDnsParams {
    mode: String,
    #[serde(default)]
    specifier: String,
}
#[derive(Deserialize)]
struct RadioPrivacyParams {
    #[serde(default)]
    enhanced_mac_randomization: Option<bool>,
    #[serde(default)]
    disable_wifi_ble_scanning: Option<bool>,
    #[serde(default)]
    disable_captive_portal: Option<bool>,
}
#[derive(Deserialize)]
struct PanicParams {
    confirm_token: String,
}
#[derive(Deserialize)]
struct ImportParams {
    json: String,
}

/// Parses a single JSON-RPC line directly without `#[serde(flatten)]`, guaranteeing
/// `BTreeMap<u32, _>` string keys deserialize cleanly on every command.
pub fn parse_ipc_request(line: &str) -> Result<IpcRequest, (u64, String)> {
    let env: RawIpcEnvelope =
        serde_json::from_str(line).map_err(|e| (0, format!("malformed JSON envelope: {}", e)))?;
    let id = env.id;
    let p = env.params;

    let cmd = match env.cmd.as_str() {
        "ping" => DaemonCommand::Ping,
        "get_status" => DaemonCommand::GetStatus,
        "get_config" => DaemonCommand::GetConfig,
        "apply_preset" => {
            let v: PresetParams = serde_json::from_value(p).map_err(|e| (id, e.to_string()))?;
            DaemonCommand::ApplyPreset { preset: v.preset }
        }
        "set_firewall_config" => {
            let v: FirewallCfgParams =
                serde_json::from_value(p).map_err(|e| (id, e.to_string()))?;
            DaemonCommand::SetFirewallConfig { config: v.config }
        }
        "set_app_firewall_rule" => {
            let v: AppFwRuleParams = serde_json::from_value(p).map_err(|e| (id, e.to_string()))?;
            DaemonCommand::SetAppFirewallRule {
                uid: v.uid,
                rule: v.rule,
            }
        }
        "remove_app_firewall_rule" => {
            let v: UidParams = serde_json::from_value(p).map_err(|e| (id, e.to_string()))?;
            DaemonCommand::RemoveAppFirewallRule { uid: v.uid }
        }
        "set_temp_unblock" => {
            let v: TempUnblockParams =
                serde_json::from_value(p).map_err(|e| (id, e.to_string()))?;
            DaemonCommand::SetTempUnblock {
                uid: v.uid,
                duration_minutes: v.duration_minutes,
            }
        }
        "clear_temp_unblock" => {
            let v: UidParams = serde_json::from_value(p).map_err(|e| (id, e.to_string()))?;
            DaemonCommand::ClearTempUnblock { uid: v.uid }
        }
        "replace_ip_blocklist" => {
            let v: BlocklistParams = serde_json::from_value(p).map_err(|e| (id, e.to_string()))?;
            DaemonCommand::ReplaceIpBlocklist { entries: v.entries }
        }
        "emergency_flush_firewall" => DaemonCommand::EmergencyFlushFirewall,
        "kill_uid_sockets" => {
            let v: UidParams = serde_json::from_value(p).map_err(|e| (id, e.to_string()))?;
            DaemonCommand::KillUidSockets { uid: v.uid }
        }
        "kill_connection" => {
            let v: KillConnParams = serde_json::from_value(p).map_err(|e| (id, e.to_string()))?;
            DaemonCommand::KillConnection {
                family: v.family,
                local_ip: v.local_ip,
                local_port: v.local_port,
                remote_ip: v.remote_ip,
                remote_port: v.remote_port,
            }
        }
        "get_live_connections" => DaemonCommand::GetLiveConnections,
        "get_telemetry" => {
            let limit = if p.is_null() {
                None
            } else {
                serde_json::from_value::<LimitParams>(p)
                    .map_err(|e| (id, e.to_string()))?
                    .limit
            };
            DaemonCommand::GetTelemetry { limit }
        }
        "clear_telemetry" => DaemonCommand::ClearTelemetry,
        "set_freezer_enabled" => {
            let v: FreezerEnableParams =
                serde_json::from_value(p).map_err(|e| (id, e.to_string()))?;
            DaemonCommand::SetFreezerEnabled {
                enabled: v.enabled,
                background_grace_secs: v.background_grace_secs,
            }
        }
        "set_freezer_entry" => {
            let v: FreezerEntryParams =
                serde_json::from_value(p).map_err(|e| (id, e.to_string()))?;
            DaemonCommand::SetFreezerEntry {
                uid: v.uid,
                entry: v.entry,
            }
        }
        "remove_freezer_entry" => {
            let v: UidParams = serde_json::from_value(p).map_err(|e| (id, e.to_string()))?;
            DaemonCommand::RemoveFreezerEntry { uid: v.uid }
        }
        "freeze_uid_now" => {
            let v: UidParams = serde_json::from_value(p).map_err(|e| (id, e.to_string()))?;
            DaemonCommand::FreezeUidNow { uid: v.uid }
        }
        "thaw_uid_now" => {
            let v: UidParams = serde_json::from_value(p).map_err(|e| (id, e.to_string()))?;
            DaemonCommand::ThawUidNow { uid: v.uid }
        }
        "thaw_all_now" => DaemonCommand::ThawAllNow,
        "get_freezer_status" => DaemonCommand::GetFreezerStatus,
        "set_app_scope" => {
            let v: AppScopeParams = serde_json::from_value(p).map_err(|e| (id, e.to_string()))?;
            DaemonCommand::SetAppScope {
                package_name: v.package_name,
                scope: v.scope,
            }
        }
        "apply_hardened_scope" => {
            let v: PkgParams = serde_json::from_value(p).map_err(|e| (id, e.to_string()))?;
            DaemonCommand::ApplyHardenedScope {
                package_name: v.package_name,
            }
        }
        "clear_app_scope" => {
            let v: PkgParams = serde_json::from_value(p).map_err(|e| (id, e.to_string()))?;
            DaemonCommand::ClearAppScope {
                package_name: v.package_name,
            }
        }
        "inspect_app_permissions" => {
            let v: PkgParams = serde_json::from_value(p).map_err(|e| (id, e.to_string()))?;
            DaemonCommand::InspectAppPermissions {
                package_name: v.package_name,
            }
        }
        "set_app_permission_action" => {
            let v: PermActionParams =
                serde_json::from_value(p).map_err(|e| (id, e.to_string()))?;
            DaemonCommand::SetAppPermissionAction {
                package_name: v.package_name,
                category: v.category,
                action: v.action,
            }
        }
        "set_app_perm_guard" => {
            let v: PermGuardParams =
                serde_json::from_value(p).map_err(|e| (id, e.to_string()))?;
            DaemonCommand::SetAppPermGuard {
                package_name: v.package_name,
                guard: v.guard,
            }
        }
        "bulk_revoke_category" => {
            let v: BulkRevokeParams =
                serde_json::from_value(p).map_err(|e| (id, e.to_string()))?;
            DaemonCommand::BulkRevokeCategory {
                category: v.category,
                packages: v.packages,
            }
        }
        "get_privacy_access_log" => {
            let limit = if p.is_null() {
                None
            } else {
                serde_json::from_value::<LimitParams>(p)
                    .map_err(|e| (id, e.to_string()))?
                    .limit
            };
            DaemonCommand::GetPrivacyAccessLog { limit }
        }
        "set_auto_clear_clipboard" => {
            let v: ClipParams = serde_json::from_value(p).map_err(|e| (id, e.to_string()))?;
            DaemonCommand::SetAutoClearClipboard {
                enabled: v.enabled,
                clear_secs: v.clear_secs,
            }
        }
        "clear_clipboard_now" => DaemonCommand::ClearClipboardNow,
        "set_hardening_config" => {
            let v: HardeningCfgParams =
                serde_json::from_value(p).map_err(|e| (id, e.to_string()))?;
            DaemonCommand::SetHardeningConfig { config: v.config }
        }
        "rollback_hardening" => DaemonCommand::RollbackHardening,
        "get_hardening_status" => DaemonCommand::GetHardeningStatus,
        "set_automation_config" => {
            let v: AutomationCfgParams =
                serde_json::from_value(p).map_err(|e| (id, e.to_string()))?;
            DaemonCommand::SetAutomationConfig { config: v.config }
        }
        "set_sensors_off" => {
            let v: EnabledParams = serde_json::from_value(p).map_err(|e| (id, e.to_string()))?;
            DaemonCommand::SetSensorsOff { enabled: v.enabled }
        }
        "set_sensor_privacy" => {
            let v: SensorPrivacyParams =
                serde_json::from_value(p).map_err(|e| (id, e.to_string()))?;
            DaemonCommand::SetSensorPrivacy {
                sensor: v.sensor,
                enabled: v.enabled,
            }
        }
        "get_private_dns" => DaemonCommand::GetPrivateDns,
        "set_private_dns" => {
            let v: PrivateDnsParams =
                serde_json::from_value(p).map_err(|e| (id, e.to_string()))?;
            DaemonCommand::SetPrivateDns {
                mode: v.mode,
                specifier: v.specifier,
            }
        }
        "get_radio_privacy" => DaemonCommand::GetRadioPrivacy,
        "set_radio_privacy" => {
            let v: RadioPrivacyParams =
                serde_json::from_value(p).map_err(|e| (id, e.to_string()))?;
            DaemonCommand::SetRadioPrivacy {
                enhanced_mac_randomization: v.enhanced_mac_randomization,
                disable_wifi_ble_scanning: v.disable_wifi_ble_scanning,
                disable_captive_portal: v.disable_captive_portal,
            }
        }
        "trigger_panic_lockdown" => DaemonCommand::TriggerPanicLockdown,
        "trigger_panic_reboot" => {
            let v: PanicParams = serde_json::from_value(p).map_err(|e| (id, e.to_string()))?;
            DaemonCommand::TriggerPanicReboot {
                confirm_token: v.confirm_token,
            }
        }
        "run_sentinel_audit" => DaemonCommand::RunSentinelAudit,
        "run_live_proof" => DaemonCommand::RunLiveProof,
        "export_config_json" => DaemonCommand::ExportConfigJson,
        "import_config_json" => {
            let v: ImportParams = serde_json::from_value(p).map_err(|e| (id, e.to_string()))?;
            DaemonCommand::ImportConfigJson { json: v.json }
        }
        other => return Err((id, format!("unknown command: {}", other))),
    };

    Ok(IpcRequest { id, command: cmd })
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IpcResponse {
    pub id: u64,
    pub ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub data: Option<serde_json::Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

impl IpcResponse {
    pub fn ok<T: Serialize>(id: u64, payload: T) -> Self {
        match serde_json::to_value(payload) {
            Ok(val) => Self {
                id,
                ok: true,
                data: Some(val),
                error: None,
            },
            Err(e) => Self::err(id, format!("response serialization failed: {}", e)),
        }
    }

    pub fn ok_empty(id: u64) -> Self {
        Self {
            id,
            ok: true,
            data: Some(serde_json::json!({ "done": true })),
            error: None,
        }
    }

    pub fn err(id: u64, msg: impl Into<String>) -> Self {
        Self {
            id,
            ok: false,
            data: None,
            error: Some(msg.into()),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct KernelCapabilities {
    pub kernel_release: String,
    pub iptables_available: bool,
    pub ip6tables_available: bool,
    pub ipset_netlink_available: bool,
    pub xt_set_available: bool,
    pub xt_recent_available: bool,
    pub nflog_available: bool,
    pub ttl_target_available: bool,
    pub hl_target_available: bool,
    pub sock_destroy_available: bool,
    pub cgroup_v2_freezer_available: bool,
    pub available_tcp_congestion: Vec<String>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct ActiveInterfaces {
    pub wifi: Vec<String>,
    pub cellular: Vec<String>,
    pub vpn: Vec<String>,
    pub other: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PrivateDnsState {
    pub mode: String,
    pub specifier: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct SensorPrivacyState {
    pub mic_muted: bool,
    pub camera_muted: bool,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct RadioPrivacyState {
    pub enhanced_mac_randomization: bool,
    pub disable_wifi_ble_scanning: bool,
    pub disable_captive_portal: bool,
    pub adb_enabled_live: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CategoryPermLiveState {
    pub category: String,
    pub appops_mode: String,
    pub last_access: Option<String>,
    pub running_now: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppLivePermissions {
    pub package_name: String,
    pub categories: Vec<CategoryPermLiveState>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PrivacyAccessEvent {
    pub uid: u32,
    pub package_name: String,
    pub category: String,
    pub op: String,
    pub mode: String,
    pub last_access_label: String,
    pub age_secs: u64,
    pub running_now: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PanicLockdownReport {
    pub firewall_locked: bool,
    pub sockets_destroyed: usize,
    pub mic_muted: bool,
    pub camera_muted: bool,
    pub wifi_disabled: bool,
    pub bluetooth_disabled: bool,
    pub clipboard_cleared: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DaemonStatus {
    pub version: String,
    pub pid: u32,
    pub uptime_secs: u64,
    pub boot_guard_armed: bool,
    pub boot_guard_cleared: bool,
    pub firewall_active: bool,
    pub firewall_mode: String,
    pub ipset_entries_loaded: usize,
    pub ipset_backend: String,
    pub vpn_active: bool,
    pub active_interfaces: ActiveInterfaces,
    pub screen_on: bool,
    pub device_locked: bool,
    pub ce_unlocked: bool,
    pub sensors_off_active: bool,
    pub mic_muted: bool,
    pub camera_muted: bool,
    pub frozen_uids_count: usize,
    pub suspended_pkgs_count: usize,
    pub blocked_events_total: u64,
    pub auto_reboot_remaining_secs: Option<u64>,
    pub private_dns: PrivateDnsState,
    pub radio_privacy: RadioPrivacyState,
    pub enforcer_rules_count: usize,
    pub enforcer_actions_count: u64,
    pub last_enforcer_event: Option<String>,
    /// Active temporary unblock timers (UID -> remaining seconds).
    pub temp_unblocks_remaining: BTreeMap<u32, u64>,
    pub capabilities: KernelCapabilities,
    pub last_firewall_error: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BlockedPacketEvent {
    pub timestamp_ms: u64,
    pub uid: Option<u32>,
    pub package_name: Option<String>,
    pub family: u8,
    pub protocol: String,
    pub src_ip: String,
    pub src_port: u16,
    pub dst_ip: String,
    pub dst_port: u16,
    pub reason: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ActiveConnection {
    pub uid: u32,
    pub package_name: Option<String>,
    pub family: u8,
    pub protocol: String,
    pub state: String,
    pub local_ip: String,
    pub local_port: u16,
    pub remote_ip: String,
    pub remote_port: u16,
    pub is_listening: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TelemetrySnapshot {
    pub total_blocked: u64,
    pub per_uid_blocked: BTreeMap<u32, u64>,
    pub events: Vec<BlockedPacketEvent>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SysctlItemStatus {
    pub key: String,
    pub label: String,
    pub category: String,
    pub desired: String,
    pub current: Option<String>,
    pub original: Option<String>,
    pub supported: bool,
    pub enforced: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HardeningStatus {
    pub enabled: bool,
    pub snapshot_saved: bool,
    pub items: Vec<SysctlItemStatus>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FreezerStatus {
    pub enabled: bool,
    pub cgroup_v2_available: bool,
    pub currently_frozen_uids: Vec<u32>,
    pub suspended_packages: Vec<String>,
    pub configured_count: usize,
    pub last_action_detail: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AuditSeverity {
    Pass,
    Info,
    Warn,
    Critical,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuditFinding {
    pub id: String,
    pub category: String,
    pub title: String,
    pub severity: AuditSeverity,
    pub detail: String,
    pub recommendation: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SentinelReport {
    pub score: u32,
    pub timestamp_ms: u64,
    pub selinux_enforcing: bool,
    pub user_ca_count: usize,
    pub app_listening_ports: usize,
    pub suspicious_root_procs: usize,
    pub findings: Vec<AuditFinding>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LiveProofItem {
    pub subsystem: String,
    pub verified: bool,
    pub evidence: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LiveProofReport {
    pub timestamp_ms: u64,
    pub ipv4_hook_attached: bool,
    pub ipv4_silo_rules_count: usize,
    pub ipv6_hook_attached: bool,
    pub ipv6_silo_rules_count: usize,
    pub sysctl_enforced_count: usize,
    pub sysctl_total_supported: usize,
    pub verified_frozen_pids: usize,
    pub suspended_packages_count: usize,
    pub items: Vec<LiveProofItem>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_set_firewall_config_with_string_uid_keys_without_error() {
        let payload = r#"{
            "id": 15,
            "cmd": "set_firewall_config",
            "params": {
                "config": {
                    "enabled": true,
                    "mode": "default_allow",
                    "block_action": "reject",
                    "isolate_lan": true,
                    "dns_leak_guard": true,
                    "anti_port_scan": true,
                    "rules": {
                        "10277": {
                            "package_name": "com.example.social",
                            "allow_wifi": true,
                            "allow_cellular": false,
                            "allow_vpn": true,
                            "allow_lan": false,
                            "allow_screen_off": false,
                            "block_cleartext_http": true,
                            "block_localhost": false,
                            "is_vpn_app": false
                        }
                    },
                    "ip_blocklist": ["198.51.100.0/24"]
                }
            }
        }"#;
        let req = parse_ipc_request(payload).unwrap();
        assert_eq!(req.id, 15);
        match req.command {
            DaemonCommand::SetFirewallConfig { config } => {
                assert!(config.enabled);
                assert!(config.anti_port_scan);
                assert!(config.rules.contains_key(&10277));
                assert!(!config.rules[&10277].allow_cellular);
            }
            _ => panic!("wrong command"),
        }
    }

    #[test]
    fn parses_all_v2_and_v3_commands() {
        let r1 = parse_ipc_request(
            r#"{"id":1,"cmd":"set_temp_unblock","params":{"uid":10277,"duration_minutes":15}}"#,
        )
        .unwrap();
        assert!(matches!(
            r1.command,
            DaemonCommand::SetTempUnblock {
                uid: 10277,
                duration_minutes: 15
            }
        ));

        let r2 = parse_ipc_request(
            r#"{"id":2,"cmd":"set_private_dns","params":{"mode":"hostname","specifier":"dns.quad9.net"}}"#,
        )
        .unwrap();
        assert!(matches!(r2.command, DaemonCommand::SetPrivateDns { .. }));

        let r3 = parse_ipc_request(r#"{"id":3,"cmd":"run_live_proof"}"#).unwrap();
        assert!(matches!(r3.command, DaemonCommand::RunLiveProof));

        let r4 = parse_ipc_request(
            r#"{"id":4,"cmd":"set_sensor_privacy","params":{"sensor":"microphone","enabled":true}}"#,
        )
        .unwrap();
        assert!(matches!(r4.command, DaemonCommand::SetSensorPrivacy { .. }));

        let r5 = parse_ipc_request(
            r#"{"id":5,"cmd":"set_app_permission_action","params":{"package_name":"com.example.app","category":"camera","action":"revoke"}}"#,
        )
        .unwrap();
        assert!(matches!(
            r5.command,
            DaemonCommand::SetAppPermissionAction { .. }
        ));

        let r6 = parse_ipc_request(
            r#"{"id":6,"cmd":"set_app_perm_guard","params":{"package_name":"com.example.app","guard":{"auto_revoke_on_bg":["camera"],"locked_deny":["microphone"]}}}"#,
        )
        .unwrap();
        assert!(matches!(r6.command, DaemonCommand::SetAppPermGuard { .. }));

        let r7 = parse_ipc_request(
            r#"{"id":7,"cmd":"bulk_revoke_category","params":{"category":"camera","packages":["com.a.b"]}}"#,
        )
        .unwrap();
        assert!(matches!(
            r7.command,
            DaemonCommand::BulkRevokeCategory { .. }
        ));

        let r8 = parse_ipc_request(r#"{"id":8,"cmd":"trigger_panic_lockdown"}"#).unwrap();
        assert!(matches!(r8.command, DaemonCommand::TriggerPanicLockdown));

        let r9 = parse_ipc_request(
            r#"{"id":9,"cmd":"set_radio_privacy","params":{"enhanced_mac_randomization":true,"disable_wifi_ble_scanning":true}}"#,
        )
        .unwrap();
        assert!(matches!(r9.command, DaemonCommand::SetRadioPrivacy { .. }));

        let r10 = parse_ipc_request(
            r#"{"id":10,"cmd":"get_privacy_access_log","params":{"limit":50}}"#,
        )
        .unwrap();
        assert!(matches!(
            r10.command,
            DaemonCommand::GetPrivacyAccessLog { limit: Some(50) }
        ));
    }
}
