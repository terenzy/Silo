use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};

pub const CONFIG_SCHEMA_VERSION: u32 = 1;
pub const MIN_AUTO_REBOOT_MINUTES: u32 = 15;
pub const MAX_AUTO_REBOOT_MINUTES: u32 = 10_080; // 7 days
pub const MAX_BLOCKLIST_ENTRIES: usize = 50_000;
pub const MIN_CLIPBOARD_CLEAR_SECS: u32 = 10;
pub const MAX_CLIPBOARD_CLEAR_SECS: u32 = 3_600;
pub const MIN_RADIO_OFF_MINUTES: u32 = 2;
pub const MAX_RADIO_OFF_MINUTES: u32 = 1_440;

pub const DEFAULT_STATE_DIR: &str = "/data/adb/silo";
pub const DEFAULT_SOCKET_PATH: &str = "/data/adb/silo/silod.sock";
pub const DEFAULT_CONFIG_PATH: &str = "/data/adb/silo/config.json";

/// Critical Android/Samsung/KSU packages that must NEVER be frozen, isolated, or blocked from core IPC.
pub const PROTECTED_PACKAGES: &[&str] = &[
    "android",
    "com.android.systemui",
    "com.android.phone",
    "com.android.settings",
    "com.android.networkstack",
    "com.android.networkstack.tethering",
    "com.android.providers.settings",
    "com.android.providers.telephony",
    "com.android.shell",
    "com.android.bluetooth",
    "com.android.se",
    "com.samsung.android.honeyboard",
    "com.google.android.inputmethod.latin",
    "dev.silo.app",
    "com.rifsxd.ksunext",
    "me.weishu.kernelsu",
    "com.sukisu.ultra",
    "com.resukisu.resukisu",
    "me.bmax.apatch",
];

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FirewallMode {
    /// Allow apps unless explicitly blocked (Safe-First default).
    #[default]
    DefaultAllow,
    /// Deny ordinary user apps unless explicitly allowed (GrapheneOS/AFWall lockdown).
    DefaultDeny,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum BlockAction {
    /// Instant ICMP/TCP rejection so apps fail immediately without stalling battery on retries.
    #[default]
    Reject,
    /// Silent packet drop.
    Drop,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct AppFirewallRule {
    pub package_name: String,
    pub allow_wifi: bool,
    pub allow_cellular: bool,
    pub allow_vpn: bool,
    pub allow_lan: bool,
    pub allow_screen_off: bool,
    pub block_cleartext_http: bool,
    pub block_localhost: bool,
    /// When true, this app is treated as a VPN provider UID (exempted from DNS leak block so tunnels work).
    pub is_vpn_app: bool,
}

impl Default for AppFirewallRule {
    fn default() -> Self {
        Self {
            package_name: String::new(),
            allow_wifi: true,
            allow_cellular: true,
            allow_vpn: true,
            allow_lan: false,
            allow_screen_off: true,
            block_cleartext_http: false,
            block_localhost: false,
            is_vpn_app: false,
        }
    }
}

impl AppFirewallRule {
    pub fn deny_all(package_name: String) -> Self {
        Self {
            package_name,
            allow_wifi: false,
            allow_cellular: false,
            allow_vpn: false,
            allow_lan: false,
            allow_screen_off: false,
            block_cleartext_http: true,
            block_localhost: false,
            is_vpn_app: false,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct FirewallConfig {
    pub enabled: bool,
    pub mode: FirewallMode,
    pub block_action: BlockAction,
    /// Automatically block newly installed apps when in DefaultAllow mode if enabled.
    pub block_new_apps: bool,
    /// Block RFC1918/link-local/multicast LAN egress for ordinary user apps unless `allow_lan` is set.
    pub isolate_lan: bool,
    /// Drop direct UDP/TCP port 53 bypass from user apps (keeps UID 0/1051 netd & VPN apps working).
    pub dns_leak_guard: bool,
    /// Drop all non-VPN IPv6 egress for user apps when a VPN interface (tun+/wg+) is active.
    pub ipv6_leak_guard: bool,
    /// Block cleartext HTTP (TCP port 80) globally for user apps (unless exempted).
    pub block_cleartext_http_global: bool,
    /// Normalize outgoing IPv4 TTL & IPv6 Hop Limit (anti-tethering/network fingerprinting).
    pub normalize_ttl_hl: bool,
    pub ttl_value: u8,
    /// Enable rate-limited NFLOG live telemetry of blocked packets.
    pub nflog_enabled: bool,
    /// Automatically tear down existing active TCP sockets via SOCK_DESTROY when blocking a UID.
    pub sock_destroy_on_block: bool,
    /// Per-UID rules keyed by numeric Android UID.
    pub rules: BTreeMap<u32, AppFirewallRule>,
    /// User-imported / custom IP & CIDR blocklist applied via kernel ipset (or fallback chain).
    pub ip_blocklist: Vec<String>,
}

impl Default for FirewallConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            mode: FirewallMode::DefaultAllow,
            block_action: BlockAction::Reject,
            block_new_apps: false,
            isolate_lan: false,
            dns_leak_guard: false,
            ipv6_leak_guard: false,
            block_cleartext_http_global: false,
            normalize_ttl_hl: false,
            ttl_value: 64,
            nflog_enabled: true,
            sock_destroy_on_block: true,
            rules: BTreeMap::new(),
            ip_blocklist: Vec::new(),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FreezeMode {
    /// Kernel cgroup v2 freezer (instant <5ms thaw when foregrounded).
    #[default]
    SoftCgroup,
    /// Deep stop (`am force-stop` + restricted standby bucket) until manually thawed.
    DeepStop,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct FreezerEntry {
    pub package_name: String,
    pub mode: FreezeMode,
    pub freeze_on_screen_off: bool,
    pub freeze_on_background: bool,
    pub kill_sockets_on_freeze: bool,
}

impl Default for FreezerEntry {
    fn default() -> Self {
        Self {
            package_name: String::new(),
            mode: FreezeMode::SoftCgroup,
            freeze_on_screen_off: true,
            freeze_on_background: true,
            kill_sockets_on_freeze: true,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct FreezerConfig {
    pub enabled: bool,
    /// Delay in seconds after an app leaves foreground before soft-freezing.
    pub background_grace_secs: u32,
    pub entries: BTreeMap<u32, FreezerEntry>,
}

impl Default for FreezerConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            background_grace_secs: 20,
            entries: BTreeMap::new(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct AppIsolationScope {
    pub isolate_contacts: bool,
    pub isolate_storage: bool,
    pub isolate_sms_calllog: bool,
    pub block_clipboard_read: bool,
    pub restrict_sensors: bool,
    pub restrict_background: bool,
    pub isolate_location: bool,
    pub block_camera_mic: bool,
}

impl AppIsolationScope {
    pub fn hardened_profile() -> Self {
        Self {
            isolate_contacts: true,
            isolate_storage: true,
            isolate_sms_calllog: true,
            block_clipboard_read: true,
            restrict_sensors: true,
            restrict_background: true,
            isolate_location: false,
            block_camera_mic: false,
        }
    }

    pub fn any_enabled(&self) -> bool {
        self.isolate_contacts
            || self.isolate_storage
            || self.isolate_sms_calllog
            || self.block_clipboard_read
            || self.restrict_sensors
            || self.restrict_background
            || self.isolate_location
            || self.block_camera_mic
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct ScopesConfig {
    pub apps: BTreeMap<String, AppIsolationScope>,
    pub auto_clear_clipboard: bool,
    pub clipboard_clear_secs: u32,
}

impl Default for ScopesConfig {
    fn default() -> Self {
        Self {
            apps: BTreeMap::new(),
            auto_clear_clipboard: false,
            clipboard_clear_secs: 45,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct HardeningConfig {
    pub enabled: bool,
    /// kernel.kptr_restrict = 2
    pub hide_kernel_pointers: bool,
    /// kernel.perf_event_paranoid = 3 (fallback 2)
    pub restrict_perf_events: bool,
    /// kernel.unprivileged_bpf_disabled = 2 (reversible on 5.13+, fallback 1)
    pub disable_unprivileged_bpf: bool,
    /// net.core.bpf_jit_harden = 2
    pub harden_bpf_jit: bool,
    /// fs.suid_dumpable = 0
    pub disable_suid_dumpable: bool,
    /// fs.protected_hardlinks/symlinks/fifos/regular
    pub protect_fs_links_fifos: bool,
    /// kernel.dmesg_restrict = 1
    pub restrict_dmesg: bool,
    /// net.ipv4.tcp_timestamps = 0 (anti-uptime fingerprinting)
    pub disable_tcp_timestamps: bool,
    /// Loose rp_filter=2 + disable ICMP redirects & source routes (VPN/dual-SIM safe)
    pub harden_network_stack: bool,
    /// net.ipv6.conf.{all,default}.use_tempaddr = 2
    pub ipv6_privacy_extensions: bool,
    /// Optional TCP congestion control override (empty = keep kernel default, e.g. "bbr" / "bbr3" / "cubic")
    pub tcp_congestion: String,
    /// Optional default qdisc override (empty = keep kernel default, e.g. "cake" / "fq" / "fq_codel")
    pub default_qdisc: String,
}

impl Default for HardeningConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            hide_kernel_pointers: true,
            restrict_perf_events: true,
            disable_unprivileged_bpf: true,
            harden_bpf_jit: true,
            disable_suid_dumpable: true,
            protect_fs_links_fifos: true,
            restrict_dmesg: true,
            disable_tcp_timestamps: true,
            harden_network_stack: true,
            ipv6_privacy_extensions: true,
            tcp_congestion: String::new(),
            default_qdisc: String::new(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct AutomationConfig {
    /// GrapheneOS-inspired Auto-Reboot to BFU (OFF by default, floor 15 mins).
    pub auto_reboot_enabled: bool,
    pub auto_reboot_minutes: u32,
    /// Auto-disable Wi-Fi when disconnected for N minutes.
    pub wifi_auto_off_enabled: bool,
    pub wifi_auto_off_minutes: u32,
    /// Auto-disable Bluetooth when disconnected for N minutes.
    pub bluetooth_auto_off_enabled: bool,
    pub bluetooth_auto_off_minutes: u32,
    /// Automatically enable global sensor_privacy when screen locks (restores on unlock).
    pub sensors_off_on_lock: bool,
}

impl Default for AutomationConfig {
    fn default() -> Self {
        Self {
            auto_reboot_enabled: false,
            auto_reboot_minutes: 720, // 12 hours
            wifi_auto_off_enabled: false,
            wifi_auto_off_minutes: 15,
            bluetooth_auto_off_enabled: false,
            bluetooth_auto_off_minutes: 15,
            sensors_off_on_lock: false,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SecurityPreset {
    Safe,
    Balanced,
    Hardened,
    Paranoid,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct SiloConfig {
    pub schema_version: u32,
    pub firewall: FirewallConfig,
    pub freezer: FreezerConfig,
    pub scopes: ScopesConfig,
    pub hardening: HardeningConfig,
    pub automation: AutomationConfig,
}

impl Default for SiloConfig {
    fn default() -> Self {
        Self {
            schema_version: CONFIG_SCHEMA_VERSION,
            firewall: FirewallConfig::default(),
            freezer: FreezerConfig::default(),
            scopes: ScopesConfig::default(),
            hardening: HardeningConfig::default(),
            automation: AutomationConfig::default(),
        }
    }
}

impl SiloConfig {
    /// Validate and sanitize all user-supplied fields to prevent misconfiguration or lockouts.
    pub fn sanitize(&mut self) -> Result<(), String> {
        self.schema_version = CONFIG_SCHEMA_VERSION;

        if self.firewall.ttl_value == 0 {
            self.firewall.ttl_value = 64;
        }

        // Remove any attempt to place critical system UIDs into restrictive firewall or freezer entries.
        self.firewall.rules.retain(|&uid, rule| {
            !is_protected_uid(uid) && !is_protected_package(&rule.package_name)
        });
        self.freezer.entries.retain(|&uid, entry| {
            !is_protected_uid(uid) && !is_protected_package(&entry.package_name)
        });
        self.scopes
            .apps
            .retain(|pkg, _| validate_package_name(pkg) && !is_protected_package(pkg));

        // Sanitize & deduplicate IP blocklist entries
        if self.firewall.ip_blocklist.len() > MAX_BLOCKLIST_ENTRIES {
            return Err(format!(
                "IP blocklist exceeds limit of {} entries",
                MAX_BLOCKLIST_ENTRIES
            ));
        }
        let mut clean_ips = BTreeSet::new();
        for raw in &self.firewall.ip_blocklist {
            let trimmed = raw.trim();
            if trimmed.is_empty() || trimmed.starts_with('#') {
                continue;
            }
            if let Some(cidr) = validate_cidr(trimmed) {
                clean_ips.insert(cidr.canonical);
            } else {
                return Err(format!("Invalid or unsafe IP/CIDR entry: {}", trimmed));
            }
        }
        self.firewall.ip_blocklist = clean_ips.into_iter().collect();

        // Clamp timing safety bounds
        self.freezer.background_grace_secs = self.freezer.background_grace_secs.clamp(5, 600);
        self.scopes.clipboard_clear_secs = self
            .scopes
            .clipboard_clear_secs
            .clamp(MIN_CLIPBOARD_CLEAR_SECS, MAX_CLIPBOARD_CLEAR_SECS);
        self.automation.auto_reboot_minutes = self
            .automation
            .auto_reboot_minutes
            .clamp(MIN_AUTO_REBOOT_MINUTES, MAX_AUTO_REBOOT_MINUTES);
        self.automation.wifi_auto_off_minutes = self
            .automation
            .wifi_auto_off_minutes
            .clamp(MIN_RADIO_OFF_MINUTES, MAX_RADIO_OFF_MINUTES);
        self.automation.bluetooth_auto_off_minutes = self
            .automation
            .bluetooth_auto_off_minutes
            .clamp(MIN_RADIO_OFF_MINUTES, MAX_RADIO_OFF_MINUTES);

        // Validate optional sysctl algorithm names (strict alphanumeric + underscore/hyphen)
        if !self.hardening.tcp_congestion.is_empty()
            && !is_safe_token(&self.hardening.tcp_congestion, 24)
        {
            return Err("Invalid tcp_congestion algorithm name".to_string());
        }
        if !self.hardening.default_qdisc.is_empty()
            && !is_safe_token(&self.hardening.default_qdisc, 24)
        {
            return Err("Invalid default_qdisc scheduler name".to_string());
        }

        Ok(())
    }

    /// Apply a curated security preset without overwriting per-app custom rules
    /// and without silently turning on Auto-Reboot BFU (always requires explicit opt-in).
    pub fn apply_preset(&mut self, preset: SecurityPreset) {
        match preset {
            SecurityPreset::Safe => {
                self.firewall.enabled = false;
                self.firewall.mode = FirewallMode::DefaultAllow;
                self.firewall.isolate_lan = false;
                self.firewall.dns_leak_guard = false;
                self.firewall.ipv6_leak_guard = false;
                self.firewall.block_cleartext_http_global = false;
                self.firewall.normalize_ttl_hl = false;
                self.firewall.block_new_apps = false;
                self.hardening.enabled = false;
                self.freezer.enabled = false;
                self.scopes.auto_clear_clipboard = false;
                self.automation.sensors_off_on_lock = false;
            }
            SecurityPreset::Balanced => {
                self.firewall.enabled = true;
                self.firewall.mode = FirewallMode::DefaultAllow;
                self.firewall.isolate_lan = true;
                self.firewall.dns_leak_guard = true;
                self.firewall.ipv6_leak_guard = true;
                self.firewall.block_cleartext_http_global = false;
                self.firewall.normalize_ttl_hl = false;
                self.firewall.block_new_apps = false;
                self.hardening.enabled = true;
                self.hardening.disable_tcp_timestamps = true;
                self.hardening.ipv6_privacy_extensions = true;
                self.scopes.auto_clear_clipboard = true;
                self.scopes.clipboard_clear_secs = 60;
            }
            SecurityPreset::Hardened => {
                self.firewall.enabled = true;
                self.firewall.mode = FirewallMode::DefaultAllow;
                self.firewall.isolate_lan = true;
                self.firewall.dns_leak_guard = true;
                self.firewall.ipv6_leak_guard = true;
                self.firewall.block_cleartext_http_global = true;
                self.firewall.normalize_ttl_hl = true;
                self.firewall.ttl_value = 64;
                self.firewall.block_new_apps = true;
                self.hardening.enabled = true;
                self.hardening.hide_kernel_pointers = true;
                self.hardening.restrict_perf_events = true;
                self.hardening.disable_unprivileged_bpf = true;
                self.hardening.harden_bpf_jit = true;
                self.hardening.disable_suid_dumpable = true;
                self.hardening.protect_fs_links_fifos = true;
                self.hardening.restrict_dmesg = true;
                self.hardening.disable_tcp_timestamps = true;
                self.hardening.harden_network_stack = true;
                self.hardening.ipv6_privacy_extensions = true;
                self.scopes.auto_clear_clipboard = true;
                self.scopes.clipboard_clear_secs = 45;
                self.automation.sensors_off_on_lock = true;
            }
            SecurityPreset::Paranoid => {
                self.apply_preset(SecurityPreset::Hardened);
                self.firewall.mode = FirewallMode::DefaultDeny;
                self.firewall.block_action = BlockAction::Reject;
                self.scopes.clipboard_clear_secs = 30;
                self.automation.wifi_auto_off_enabled = true;
                self.automation.bluetooth_auto_off_enabled = true;
            }
        }
    }

    pub fn load_or_default(path: &Path) -> Self {
        match fs::read_to_string(path) {
            Ok(raw) => match serde_json::from_str::<SiloConfig>(&raw) {
                Ok(mut cfg) => {
                    let _ = cfg.sanitize();
                    cfg
                }
                Err(_) => Self::default(),
            },
            Err(_) => Self::default(),
        }
    }

    /// Atomic root-only persistence (0600, fsync, atomic rename).
    pub fn save_atomic(&self, path: &Path) -> Result<(), String> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).map_err(|e| format!("create_dir_all failed: {}", e))?;
            let _ = fs::set_permissions(parent, fs::Permissions::from_mode(0o700));
        }

        let tmp_path = PathBuf::from(format!("{}.tmp.{}", path.display(), std::process::id()));
        let serialized = serde_json::to_string_pretty(self)
            .map_err(|e| format!("serialize config failed: {}", e))?;

        let mut file = OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(true)
            .mode(0o600)
            .open(&tmp_path)
            .map_err(|e| format!("open temp config failed: {}", e))?;

        file.write_all(serialized.as_bytes())
            .and_then(|_| file.sync_all())
            .map_err(|e| {
                let _ = fs::remove_file(&tmp_path);
                format!("write/sync config failed: {}", e)
            })?;

        fs::rename(&tmp_path, path).map_err(|e| {
            let _ = fs::remove_file(&tmp_path);
            format!("atomic rename config failed: {}", e)
        })?;
        let _ = fs::set_permissions(path, fs::Permissions::from_mode(0o600));
        Ok(())
    }
}

/// Hard-protect system/core Android UIDs across all users (userId * 100000 + appId).
/// Ordinary installed apps have appId in 10000..=19999.
pub fn is_protected_uid(uid: u32) -> bool {
    let app_id = uid % 100_000;
    !(10_000..90_000).contains(&app_id)
}

pub fn is_protected_package(pkg: &str) -> bool {
    let p = pkg.trim();
    if p.is_empty() {
        return false;
    }
    PROTECTED_PACKAGES.contains(&p)
}

pub fn validate_package_name(pkg: &str) -> bool {
    if pkg.len() < 3 || pkg.len() > 128 || !pkg.contains('.') {
        return false;
    }
    for segment in pkg.split('.') {
        if segment.is_empty() {
            return false;
        }
        let mut chars = segment.chars();
        let Some(first) = chars.next() else {
            return false;
        };
        if !(first.is_ascii_alphabetic() || first == '_') {
            return false;
        }
        if !chars.all(|c| c.is_ascii_alphanumeric() || c == '_') {
            return false;
        }
    }
    true
}

pub fn is_safe_token(s: &str, max_len: usize) -> bool {
    !s.is_empty()
        && s.len() <= max_len
        && s.chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CanonicalCidr {
    pub ip: IpAddr,
    pub prefix: u8,
    pub canonical: String,
}

/// Strictly validates IPv4/IPv6 addresses or CIDRs and rejects loopback / unspecified / prefix-0
/// so users cannot accidentally brick loopback or entire internet via a malformed blocklist line.
pub fn validate_cidr(input: &str) -> Option<CanonicalCidr> {
    let s = input.trim();
    if s.is_empty() || s.len() > 64 {
        return None;
    }

    let (ip_str, prefix_opt) = match s.split_once('/') {
        Some((a, b)) => (a, Some(b)),
        None => (s, None),
    };

    let ip: IpAddr = ip_str.parse().ok()?;
    match ip {
        IpAddr::V4(v4) => {
            let prefix = match prefix_opt {
                Some(p) => p.parse::<u8>().ok()?,
                None => 32,
            };
            if prefix == 0 || prefix > 32 {
                return None;
            }
            if v4.is_loopback() || v4.is_unspecified() {
                return None;
            }
            let masked = mask_ipv4(v4, prefix);
            // Reject 0.0.0.0/N or 127.0.0.0/8 overlapping loopback
            if masked.octets()[0] == 127 || (prefix <= 8 && masked.octets()[0] == 0) {
                return None;
            }
            let canonical = if prefix == 32 {
                masked.to_string()
            } else {
                format!("{}/{}", masked, prefix)
            };
            Some(CanonicalCidr {
                ip: IpAddr::V4(masked),
                prefix,
                canonical,
            })
        }
        IpAddr::V6(v6) => {
            let prefix = match prefix_opt {
                Some(p) => p.parse::<u8>().ok()?,
                None => 128,
            };
            if prefix == 0 || prefix > 128 {
                return None;
            }
            if v6.is_loopback() || v6.is_unspecified() {
                return None;
            }
            let masked = mask_ipv6(v6, prefix);
            if masked.is_loopback() || masked.is_unspecified() {
                return None;
            }
            let canonical = if prefix == 128 {
                masked.to_string()
            } else {
                format!("{}/{}", masked, prefix)
            };
            Some(CanonicalCidr {
                ip: IpAddr::V6(masked),
                prefix,
                canonical,
            })
        }
    }
}

fn mask_ipv4(ip: Ipv4Addr, prefix: u8) -> Ipv4Addr {
    let bits = u32::from(ip);
    let mask = if prefix == 0 {
        0
    } else {
        !0u32 << (32 - prefix)
    };
    Ipv4Addr::from(bits & mask)
}

fn mask_ipv6(ip: Ipv6Addr, prefix: u8) -> Ipv6Addr {
    let bits = u128::from(ip);
    let mask = if prefix == 0 {
        0
    } else {
        !0u128 << (128 - prefix)
    };
    Ipv6Addr::from(bits & mask)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn critical_uids_are_always_protected() {
        assert!(is_protected_uid(0));
        assert!(is_protected_uid(1000));
        assert!(is_protected_uid(1051));
        assert!(is_protected_uid(2000));
        assert!(is_protected_uid(101_000)); // multi-user system UID
        assert!(is_protected_uid(99_000)); // isolated process
        assert!(!is_protected_uid(10_000));
        assert!(!is_protected_uid(10_234));
        assert!(!is_protected_uid(110_234)); // work profile / private space app
    }

    #[test]
    fn package_name_validation_rejects_injection() {
        assert!(validate_package_name("com.example.app"));
        assert!(validate_package_name("dev.silo.app"));
        assert!(validate_package_name("org.frknkrc44.hma_oss"));
        assert!(!validate_package_name("com.example;rm -rf /"));
        assert!(!validate_package_name("../etc/passwd"));
        assert!(!validate_package_name("singleword"));
        assert!(!validate_package_name("com..double"));
        assert!(!validate_package_name("com.123bad"));
    }

    #[test]
    fn cidr_validation_normalizes_and_blocks_dangerous_entries() {
        let c = validate_cidr("192.168.1.42/24").unwrap();
        assert_eq!(c.canonical, "192.168.1.0/24");

        let single = validate_cidr("8.8.8.8").unwrap();
        assert_eq!(single.canonical, "8.8.8.8");

        let v6 = validate_cidr("2001:db8::1/64").unwrap();
        assert_eq!(v6.canonical, "2001:db8::/64");

        // Unsafe/malformed entries must be rejected
        assert!(validate_cidr("0.0.0.0/0").is_none());
        assert!(validate_cidr("127.0.0.1").is_none());
        assert!(validate_cidr("127.0.0.0/8").is_none());
        assert!(validate_cidr("::1").is_none());
        assert!(validate_cidr("::/0").is_none());
        assert!(validate_cidr("1.2.3.4/33").is_none());
        assert!(validate_cidr("1.2.3.4; iptables -F").is_none());
    }

    #[test]
    fn sanitize_enforces_safety_invariants() {
        let mut cfg = SiloConfig::default();
        cfg.automation.auto_reboot_minutes = 1; // dangerously low
        cfg.firewall
            .rules
            .insert(0, AppFirewallRule::deny_all("root".into()));
        cfg.firewall
            .rules
            .insert(10_200, AppFirewallRule::deny_all("com.test.app".into()));
        cfg.freezer
            .entries
            .insert(1000, FreezerEntry::default());
        cfg.scopes
            .apps
            .insert("com.android.systemui".into(), AppIsolationScope::hardened_profile());
        cfg.scopes
            .apps
            .insert("com.untrusted.app".into(), AppIsolationScope::hardened_profile());

        cfg.sanitize().unwrap();
        assert_eq!(cfg.automation.auto_reboot_minutes, MIN_AUTO_REBOOT_MINUTES);
        assert!(!cfg.firewall.rules.contains_key(&0));
        assert!(cfg.firewall.rules.contains_key(&10_200));
        assert!(!cfg.freezer.entries.contains_key(&1000));
        assert!(!cfg.scopes.apps.contains_key("com.android.systemui"));
        assert!(cfg.scopes.apps.contains_key("com.untrusted.app"));
    }

    #[test]
    fn presets_never_auto_enable_auto_reboot() {
        let mut cfg = SiloConfig::default();
        for p in [
            SecurityPreset::Safe,
            SecurityPreset::Balanced,
            SecurityPreset::Hardened,
            SecurityPreset::Paranoid,
        ] {
            cfg.apply_preset(p);
            assert!(
                !cfg.automation.auto_reboot_enabled,
                "Preset {:?} must never silently enable auto_reboot",
                p
            );
        }
    }

    #[test]
    fn atomic_save_and_reload_roundtrip() {
        let dir = std::env::temp_dir().join(format!("silo_cfg_test_{}", std::process::id()));
        let path = dir.join("config.json");
        let mut cfg = SiloConfig::default();
        cfg.apply_preset(SecurityPreset::Balanced);
        cfg.firewall.ip_blocklist = vec!["198.51.100.12/24".into()];
        cfg.sanitize().unwrap();
        cfg.save_atomic(&path).unwrap();

        let loaded = SiloConfig::load_or_default(&path);
        assert_eq!(loaded, cfg);
        assert_eq!(loaded.firewall.ip_blocklist, vec!["198.51.100.0/24".to_string()]);
        let _ = fs::remove_dir_all(&dir);
    }
}
