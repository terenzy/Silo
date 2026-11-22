package dev.silo.app.ipc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
enum class FirewallMode {
    @SerialName("default_allow")
    DEFAULT_ALLOW,
    @SerialName("default_deny")
    DEFAULT_DENY,
}

@Serializable
enum class BlockAction {
    @SerialName("reject")
    REJECT,
    @SerialName("drop")
    DROP,
}

@Serializable
enum class FreezeMode {
    @SerialName("soft_cgroup")
    SOFT_CGROUP,
    @SerialName("deep_stop")
    DEEP_STOP,
}

@Serializable
enum class SecurityPreset {
    @SerialName("safe")
    SAFE,
    @SerialName("balanced")
    BALANCED,
    @SerialName("hardened")
    HARDENED,
    @SerialName("paranoid")
    PARANOID,
}

@Serializable
enum class AuditSeverity {
    @SerialName("pass")
    PASS,
    @SerialName("info")
    INFO,
    @SerialName("warn")
    WARN,
    @SerialName("critical")
    CRITICAL,
}

@Serializable
data class AppFirewallRule(
    @SerialName("package_name") val packageName: String = "",
    @SerialName("allow_wifi") val allowWifi: Boolean = true,
    @SerialName("allow_cellular") val allowCellular: Boolean = true,
    @SerialName("allow_vpn") val allowVpn: Boolean = true,
    @SerialName("allow_lan") val allowLan: Boolean = false,
    @SerialName("allow_screen_off") val allowScreenOff: Boolean = true,
    @SerialName("block_cleartext_http") val blockCleartextHttp: Boolean = false,
    @SerialName("block_localhost") val blockLocalhost: Boolean = false,
    @SerialName("is_vpn_app") val isVpnApp: Boolean = false,
)

@Serializable
data class FirewallConfig(
    val enabled: Boolean = false,
    val mode: FirewallMode = FirewallMode.DEFAULT_ALLOW,
    @SerialName("block_action") val blockAction: BlockAction = BlockAction.REJECT,
    @SerialName("block_new_apps") val blockNewApps: Boolean = false,
    @SerialName("isolate_lan") val isolateLan: Boolean = false,
    @SerialName("dns_leak_guard") val dnsLeakGuard: Boolean = false,
    @SerialName("ipv6_leak_guard") val ipv6LeakGuard: Boolean = false,
    @SerialName("block_cleartext_http_global") val blockCleartextHttpGlobal: Boolean = false,
    @SerialName("anti_port_scan") val antiPortScan: Boolean = false,
    @SerialName("normalize_ttl_hl") val normalizeTtlHl: Boolean = false,
    @SerialName("ttl_value") val ttlValue: Int = 64,
    @SerialName("nflog_enabled") val nflogEnabled: Boolean = true,
    @SerialName("sock_destroy_on_block") val sockDestroyOnBlock: Boolean = true,
    val rules: Map<Int, AppFirewallRule> = emptyMap(),
    @SerialName("ip_blocklist") val ipBlocklist: List<String> = emptyList(),
)

@Serializable
data class FreezerEntry(
    @SerialName("package_name") val packageName: String = "",
    val mode: FreezeMode = FreezeMode.SOFT_CGROUP,
    @SerialName("freeze_on_screen_off") val freezeOnScreenOff: Boolean = true,
    @SerialName("freeze_on_background") val freezeOnBackground: Boolean = true,
    @SerialName("kill_sockets_on_freeze") val killSocketsOnFreeze: Boolean = true,
)

@Serializable
data class FreezerConfig(
    val enabled: Boolean = false,
    @SerialName("background_grace_secs") val backgroundGraceSecs: Int = 20,
    val entries: Map<Int, FreezerEntry> = emptyMap(),
)

@Serializable
data class AppIsolationScope(
    @SerialName("isolate_contacts") val isolateContacts: Boolean = false,
    @SerialName("isolate_storage") val isolateStorage: Boolean = false,
    @SerialName("isolate_sms_calllog") val isolateSmsCalllog: Boolean = false,
    @SerialName("block_clipboard_read") val blockClipboardRead: Boolean = false,
    @SerialName("restrict_sensors") val restrictSensors: Boolean = false,
    @SerialName("restrict_background") val restrictBackground: Boolean = false,
    @SerialName("isolate_location") val isolateLocation: Boolean = false,
    @SerialName("block_camera_mic") val blockCameraMic: Boolean = false,
) {
    fun activeCount(): Int = listOf(
        isolateContacts,
        isolateStorage,
        isolateSmsCalllog,
        blockClipboardRead,
        restrictSensors,
        restrictBackground,
        isolateLocation,
        blockCameraMic,
    ).count { it }
}

@Serializable
data class AppPermGuard(
    @SerialName("auto_revoke_on_bg") val autoRevokeOnBg: List<String> = emptyList(),
    @SerialName("locked_deny") val lockedDeny: List<String> = emptyList(),
) {
    fun totalCount(): Int = autoRevokeOnBg.size + lockedDeny.size
}

@Serializable
data class ScopesConfig(
    val apps: Map<String, AppIsolationScope> = emptyMap(),
    val guards: Map<String, AppPermGuard> = emptyMap(),
    @SerialName("auto_clear_clipboard") val autoClearClipboard: Boolean = false,
    @SerialName("clipboard_clear_secs") val clipboardClearSecs: Int = 45,
)

@Serializable
data class HardeningConfig(
    val enabled: Boolean = false,
    @SerialName("hide_kernel_pointers") val hideKernelPointers: Boolean = true,
    @SerialName("restrict_perf_events") val restrictPerfEvents: Boolean = true,
    @SerialName("disable_unprivileged_bpf") val disableUnprivilegedBpf: Boolean = true,
    @SerialName("harden_bpf_jit") val hardenBpfJit: Boolean = true,
    @SerialName("disable_suid_dumpable") val disableSuidDumpable: Boolean = true,
    @SerialName("protect_fs_links_fifos") val protectFsLinksFifos: Boolean = true,
    @SerialName("restrict_dmesg") val restrictDmesg: Boolean = true,
    @SerialName("disable_tcp_timestamps") val disableTcpTimestamps: Boolean = true,
    @SerialName("harden_network_stack") val hardenNetworkStack: Boolean = true,
    @SerialName("harden_memory_and_ptrace") val hardenMemoryAndPtrace: Boolean = true,
    @SerialName("ipv6_privacy_extensions") val ipv6PrivacyExtensions: Boolean = true,
    @SerialName("tcp_congestion") val tcpCongestion: String = "",
    @SerialName("default_qdisc") val defaultQdisc: String = "",
)

@Serializable
data class AutomationConfig(
    @SerialName("auto_reboot_enabled") val autoRebootEnabled: Boolean = false,
    @SerialName("auto_reboot_minutes") val autoRebootMinutes: Int = 720,
    @SerialName("wifi_auto_off_enabled") val wifiAutoOffEnabled: Boolean = false,
    @SerialName("wifi_auto_off_minutes") val wifiAutoOffMinutes: Int = 15,
    @SerialName("bluetooth_auto_off_enabled") val bluetoothAutoOffEnabled: Boolean = false,
    @SerialName("bluetooth_auto_off_minutes") val bluetoothAutoOffMinutes: Int = 15,
    @SerialName("sensors_off_on_lock") val sensorsOffOnLock: Boolean = false,
    @SerialName("usb_adb_lock_guard") val usbAdbLockGuard: Boolean = false,
)

@Serializable
data class SiloConfig(
    @SerialName("schema_version") val schemaVersion: Int = 3,
    val firewall: FirewallConfig = FirewallConfig(),
    val freezer: FreezerConfig = FreezerConfig(),
    val scopes: ScopesConfig = ScopesConfig(),
    val hardening: HardeningConfig = HardeningConfig(),
    val automation: AutomationConfig = AutomationConfig(),
)

@Serializable
data class KernelCapabilities(
    @SerialName("kernel_release") val kernelRelease: String = "unknown",
    @SerialName("iptables_available") val iptablesAvailable: Boolean = false,
    @SerialName("ip6tables_available") val ip6tablesAvailable: Boolean = false,
    @SerialName("ipset_netlink_available") val ipsetNetlinkAvailable: Boolean = false,
    @SerialName("xt_set_available") val xtSetAvailable: Boolean = false,
    @SerialName("xt_recent_available") val xtRecentAvailable: Boolean = false,
    @SerialName("nflog_available") val nflogAvailable: Boolean = false,
    @SerialName("ttl_target_available") val ttlTargetAvailable: Boolean = false,
    @SerialName("hl_target_available") val hlTargetAvailable: Boolean = false,
    @SerialName("sock_destroy_available") val sockDestroyAvailable: Boolean = false,
    @SerialName("cgroup_v2_freezer_available") val cgroupV2FreezerAvailable: Boolean = false,
    @SerialName("available_tcp_congestion") val availableTcpCongestion: List<String> = emptyList(),
)

@Serializable
data class ActiveInterfaces(
    val wifi: List<String> = emptyList(),
    val cellular: List<String> = emptyList(),
    val vpn: List<String> = emptyList(),
    val other: List<String> = emptyList(),
)

@Serializable
data class PrivateDnsState(
    val mode: String = "opportunistic",
    val specifier: String = "",
)

@Serializable
data class SensorPrivacyState(
    @SerialName("mic_muted") val micMuted: Boolean = false,
    @SerialName("camera_muted") val cameraMuted: Boolean = false,
)

@Serializable
data class RadioPrivacyState(
    @SerialName("enhanced_mac_randomization") val enhancedMacRandomization: Boolean = false,
    @SerialName("disable_wifi_ble_scanning") val disableWifiBleScanning: Boolean = false,
    @SerialName("disable_captive_portal") val disableCaptivePortal: Boolean = false,
    @SerialName("adb_enabled_live") val adbEnabledLive: Boolean = false,
)

@Serializable
data class CategoryPermLiveState(
    val category: String = "",
    @SerialName("appops_mode") val appopsMode: String = "default",
    @SerialName("last_access") val lastAccess: String? = null,
    @SerialName("running_now") val runningNow: Boolean = false,
)

@Serializable
data class AppLivePermissions(
    @SerialName("package_name") val packageName: String = "",
    val categories: List<CategoryPermLiveState> = emptyList(),
)

@Serializable
data class PrivacyAccessEvent(
    val uid: Int = 0,
    @SerialName("package_name") val packageName: String = "",
    val category: String = "",
    val op: String = "",
    val mode: String = "allow",
    @SerialName("last_access_label") val lastAccessLabel: String = "",
    @SerialName("age_secs") val ageSecs: Long = 0,
    @SerialName("running_now") val runningNow: Boolean = false,
)

@Serializable
data class PanicLockdownReport(
    @SerialName("firewall_locked") val firewallLocked: Boolean = false,
    @SerialName("sockets_destroyed") val socketsDestroyed: Int = 0,
    @SerialName("mic_muted") val micMuted: Boolean = false,
    @SerialName("camera_muted") val cameraMuted: Boolean = false,
    @SerialName("wifi_disabled") val wifiDisabled: Boolean = false,
    @SerialName("bluetooth_disabled") val bluetoothDisabled: Boolean = false,
    @SerialName("clipboard_cleared") val clipboardCleared: Boolean = false,
)

@Serializable
data class DaemonStatus(
    val version: String = "0.0.0",
    val pid: Int = 0,
    @SerialName("uptime_secs") val uptimeSecs: Long = 0,
    @SerialName("boot_guard_armed") val bootGuardArmed: Boolean = false,
    @SerialName("boot_guard_cleared") val bootGuardCleared: Boolean = false,
    @SerialName("firewall_active") val firewallActive: Boolean = false,
    @SerialName("firewall_mode") val firewallMode: String = "DefaultAllow",
    @SerialName("ipset_entries_loaded") val ipsetEntriesLoaded: Int = 0,
    @SerialName("ipset_backend") val ipsetBackend: String = "idle",
    @SerialName("vpn_active") val vpnActive: Boolean = false,
    @SerialName("active_interfaces") val activeInterfaces: ActiveInterfaces = ActiveInterfaces(),
    @SerialName("screen_on") val screenOn: Boolean = true,
    @SerialName("device_locked") val deviceLocked: Boolean = false,
    @SerialName("ce_unlocked") val ceUnlocked: Boolean = true,
    @SerialName("sensors_off_active") val sensorsOffActive: Boolean = false,
    @SerialName("mic_muted") val micMuted: Boolean = false,
    @SerialName("camera_muted") val cameraMuted: Boolean = false,
    @SerialName("frozen_uids_count") val frozenUidsCount: Int = 0,
    @SerialName("suspended_pkgs_count") val suspendedPkgsCount: Int = 0,
    @SerialName("blocked_events_total") val blockedEventsTotal: Long = 0,
    @SerialName("auto_reboot_remaining_secs") val autoRebootRemainingSecs: Long? = null,
    @SerialName("private_dns") val privateDns: PrivateDnsState = PrivateDnsState(),
    @SerialName("radio_privacy") val radioPrivacy: RadioPrivacyState = RadioPrivacyState(),
    @SerialName("enforcer_rules_count") val enforcerRulesCount: Int = 0,
    @SerialName("enforcer_actions_count") val enforcerActionsCount: Long = 0,
    @SerialName("last_enforcer_event") val lastEnforcerEvent: String? = null,
    @SerialName("temp_unblocks_remaining") val tempUnblocksRemaining: Map<Int, Long> = emptyMap(),
    val capabilities: KernelCapabilities = KernelCapabilities(),
    @SerialName("last_firewall_error") val lastFirewallError: String? = null,
)

@Serializable
data class BlockedPacketEvent(
    @SerialName("timestamp_ms") val timestampMs: Long = 0,
    val uid: Int? = null,
    @SerialName("package_name") val packageName: String? = null,
    val family: Int = 4,
    val protocol: String = "TCP",
    @SerialName("src_ip") val srcIp: String = "",
    @SerialName("src_port") val srcPort: Int = 0,
    @SerialName("dst_ip") val dstIp: String = "",
    @SerialName("dst_port") val dstPort: Int = 0,
    val reason: String = "",
)

@Serializable
data class ActiveConnection(
    val uid: Int = 0,
    @SerialName("package_name") val packageName: String? = null,
    val family: Int = 4,
    val protocol: String = "TCP",
    val state: String = "",
    @SerialName("local_ip") val localIp: String = "",
    @SerialName("local_port") val localPort: Int = 0,
    @SerialName("remote_ip") val remoteIp: String = "",
    @SerialName("remote_port") val remotePort: Int = 0,
    @SerialName("is_listening") val isListening: Boolean = false,
)

@Serializable
data class TelemetrySnapshot(
    @SerialName("total_blocked") val totalBlocked: Long = 0,
    @SerialName("per_uid_blocked") val perUidBlocked: Map<Int, Long> = emptyMap(),
    val events: List<BlockedPacketEvent> = emptyList(),
)

@Serializable
data class SysctlItemStatus(
    val key: String = "",
    val label: String = "",
    val category: String = "",
    val desired: String = "",
    val current: String? = null,
    val original: String? = null,
    val supported: Boolean = false,
    val enforced: Boolean = false,
)

@Serializable
data class HardeningStatus(
    val enabled: Boolean = false,
    @SerialName("snapshot_saved") val snapshotSaved: Boolean = false,
    val items: List<SysctlItemStatus> = emptyList(),
)

@Serializable
data class FreezerStatus(
    val enabled: Boolean = false,
    @SerialName("cgroup_v2_available") val cgroupV2Available: Boolean = false,
    @SerialName("currently_frozen_uids") val currentlyFrozenUids: List<Int> = emptyList(),
    @SerialName("suspended_packages") val suspendedPackages: List<String> = emptyList(),
    @SerialName("configured_count") val configuredCount: Int = 0,
    @SerialName("last_action_detail") val lastActionDetail: String? = null,
)

@Serializable
data class AuditFinding(
    val id: String = "",
    val category: String = "",
    val title: String = "",
    val severity: AuditSeverity = AuditSeverity.INFO,
    val detail: String = "",
    val recommendation: String? = null,
)

@Serializable
data class SentinelReport(
    val score: Int = 0,
    @SerialName("timestamp_ms") val timestampMs: Long = 0,
    @SerialName("selinux_enforcing") val selinuxEnforcing: Boolean = true,
    @SerialName("user_ca_count") val userCaCount: Int = 0,
    @SerialName("app_listening_ports") val appListeningPorts: Int = 0,
    @SerialName("suspicious_root_procs") val suspiciousRootProcs: Int = 0,
    val findings: List<AuditFinding> = emptyList(),
)

@Serializable
data class LiveProofItem(
    val subsystem: String = "",
    val verified: Boolean = false,
    val evidence: String = "",
)

@Serializable
data class LiveProofReport(
    @SerialName("timestamp_ms") val timestampMs: Long = 0,
    @SerialName("ipv4_hook_attached") val ipv4HookAttached: Boolean = false,
    @SerialName("ipv4_silo_rules_count") val ipv4SiloRulesCount: Int = 0,
    @SerialName("ipv6_hook_attached") val ipv6HookAttached: Boolean = false,
    @SerialName("ipv6_silo_rules_count") val ipv6SiloRulesCount: Int = 0,
    @SerialName("sysctl_enforced_count") val sysctlEnforcedCount: Int = 0,
    @SerialName("sysctl_total_supported") val sysctlTotalSupported: Int = 0,
    @SerialName("verified_frozen_pids") val verifiedFrozenPids: Int = 0,
    @SerialName("suspended_packages_count") val suspendedPackagesCount: Int = 0,
    val items: List<LiveProofItem> = emptyList(),
)

@Serializable
data class IpcEnvelopeRequest(
    val id: Long,
    val cmd: String,
    val params: JsonElement? = null,
)

@Serializable
data class IpcEnvelopeResponse(
    val id: Long = 0,
    val ok: Boolean = false,
    val data: JsonElement? = null,
    val error: String? = null,
)
