use crate::config::{
    AppFirewallRule, AppIsolationScope, AutomationConfig, FirewallConfig, FreezerEntry,
    HardeningConfig, SecurityPreset,
};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

pub const MAX_IPC_LINE_BYTES: usize = 512 * 1024; // 512 KiB hard cap per JSON-RPC message

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IpcRequest {
    pub id: u64,
    #[serde(flatten)]
    pub command: DaemonCommand,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "cmd", content = "params", rename_all = "snake_case")]
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
    TriggerPanicReboot {
        confirm_token: String,
    },
    RunSentinelAudit,
    ExportConfigJson,
    ImportConfigJson {
        json: String,
    },
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
    pub nflog_available: bool,
    pub ttl_target_available: bool,
    pub hl_target_available: bool,
    pub sock_destroy_available: bool,
    pub cgroup_v2_freezer_available: bool,
    pub available_tcp_congestion: Vec<String>,
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
    pub frozen_uids_count: usize,
    pub blocked_events_total: u64,
    pub auto_reboot_remaining_secs: Option<u64>,
    pub capabilities: KernelCapabilities,
    pub last_firewall_error: Option<String>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct ActiveInterfaces {
    pub wifi: Vec<String>,
    pub cellular: Vec<String>,
    pub vpn: Vec<String>,
    pub other: Vec<String>,
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
    pub configured_count: usize,
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ipc_request_roundtrip() {
        let req = IpcRequest {
            id: 42,
            command: DaemonCommand::ApplyPreset {
                preset: SecurityPreset::Hardened,
            },
        };
        let raw = serde_json::to_string(&req).unwrap();
        let parsed: IpcRequest = serde_json::from_str(&raw).unwrap();
        assert_eq!(parsed.id, 42);
        match parsed.command {
            DaemonCommand::ApplyPreset { preset } => assert_eq!(preset, SecurityPreset::Hardened),
            _ => panic!("wrong variant"),
        }
    }

    #[test]
    fn ipc_response_serialization() {
        let ok = IpcResponse::ok(7, serde_json::json!({"pong": true}));
        let s = serde_json::to_string(&ok).unwrap();
        assert!(s.contains("\"ok\":true"));
        assert!(!s.contains("\"error\""));

        let err = IpcResponse::err(8, "denied");
        let s2 = serde_json::to_string(&err).unwrap();
        assert!(s2.contains("\"ok\":false"));
        assert!(s2.contains("denied"));
    }
}
