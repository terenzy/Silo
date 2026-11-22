use crate::config::{validate_cidr, BlockAction, CanonicalCidr, FirewallConfig, FirewallMode};
use crate::netlink::{
    classify_active_interfaces, destroy_uid_sockets, ipset_atomic_sync, ipset_cleanup,
    probe_ipset_netlink, probe_sock_destroy_supported, SILO_NFLOG_GROUP,
};
use crate::protocol::{ActiveInterfaces, KernelCapabilities};
use crate::sys::{kernel_release, resolve_bin, run_cmd};
use std::collections::HashSet;
use std::fs;
use std::net::IpAddr;
use std::path::Path;

pub const IPSET_V4_NAME: &str = "silo_blk_v4";
pub const IPSET_V6_NAME: &str = "silo_blk_v6";
const FALLBACK_CHAIN_MAX_CIDRS: usize = 2000;

const WIFI_IFACES: &[&str] = &["wlan+", "swlan+"];
const CELL_IFACES: &[&str] = &[
    "ccmni+",
    "v4-ccmni+",
    "rmnet+",
    "v4-rmnet+",
    "pdp+",
    "clat+",
];
const VPN_IFACES: &[&str] = &["tun+", "wg+", "ppp+", "ipsec+"];

const LAN_V4_CIDRS: &[&str] = &[
    "10.0.0.0/8",
    "172.16.0.0/12",
    "192.168.0.0/16",
    "169.254.0.0/16",
    "224.0.0.0/4",
];

const LAN_V6_CIDRS: &[&str] = &["fc00::/7", "fe80::/10", "ff00::/8"];

const FILTER_CHAINS: &[&str] = &[
    "SILO_OUT",
    "SILO_IN",
    "SILO_LAN_CHK",
    "SILO_BLK_IP",
    "SILO_DENY_UID",
    "SILO_DENY_LAN",
    "SILO_DENY_DNS",
    "SILO_DENY_HTTP",
    "SILO_DENY_IPSET",
    "SILO_DENY_V6LEAK",
];

#[derive(Debug, Clone)]
pub struct FirewallApplyResult {
    pub ipset_entries_loaded: usize,
    pub ipset_backend: String,
    pub vpn_active: bool,
    pub active_interfaces: ActiveInterfaces,
}

pub fn probe_capabilities() -> KernelCapabilities {
    let ipt = resolve_bin("iptables");
    let ip6t = resolve_bin("ip6tables");

    let iptables_available = run_cmd(&ipt, &["--version"], None, 2000)
        .map(|o| o.success())
        .unwrap_or(false);
    let ip6tables_available = run_cmd(&ip6t, &["--version"], None, 2000)
        .map(|o| o.success())
        .unwrap_or(false);

    let xt_set_available = if iptables_available {
        run_cmd(&ipt, &["-m", "set", "--help"], None, 2000)
            .map(|o| o.success() && (o.stdout.contains("--match-set") || o.stderr.contains("--match-set")))
            .unwrap_or(false)
    } else {
        false
    };

    let nflog_available = if iptables_available {
        run_cmd(&ipt, &["-j", "NFLOG", "--help"], None, 2000)
            .map(|o| o.success() && (o.stdout.contains("--nflog-group") || o.stderr.contains("--nflog-group")))
            .unwrap_or(false)
    } else {
        false
    };

    let ttl_target_available = if iptables_available {
        run_cmd(&ipt, &["-t", "mangle", "-j", "TTL", "--help"], None, 2000)
            .map(|o| o.success() && (o.stdout.contains("--ttl-set") || o.stderr.contains("--ttl-set")))
            .unwrap_or(false)
    } else {
        false
    };

    let hl_target_available = if ip6tables_available {
        run_cmd(&ip6t, &["-t", "mangle", "-j", "HL", "--help"], None, 2000)
            .map(|o| o.success() && (o.stdout.contains("--hl-set") || o.stderr.contains("--hl-set")))
            .unwrap_or(false)
    } else {
        false
    };

    let ipset_netlink_available = probe_ipset_netlink().is_some();
    let sock_destroy_available = probe_sock_destroy_supported();
    let cgroup_v2_freezer_available = Path::new("/sys/fs/cgroup").exists()
        && fs::read_to_string("/proc/mounts")
            .map(|m| m.contains("cgroup2"))
            .unwrap_or(false);

    let available_tcp_congestion =
        fs::read_to_string("/proc/sys/net/ipv4/tcp_available_congestion_control")
            .map(|s| s.split_whitespace().map(String::from).collect())
            .unwrap_or_default();

    KernelCapabilities {
        kernel_release: kernel_release(),
        iptables_available,
        ip6tables_available,
        ipset_netlink_available,
        xt_set_available,
        nflog_available,
        ttl_target_available,
        hl_target_available,
        sock_destroy_available,
        cgroup_v2_freezer_available,
        available_tcp_congestion,
    }
}

/// Applies the complete firewall policy atomically (dry-run `--test --noflush` first, then live apply)
/// without ever flushing Android `netd`'s chains.
pub fn apply_firewall(
    cfg: &FirewallConfig,
    caps: &KernelCapabilities,
    screen_on: bool,
    previously_blocked_uids: &mut HashSet<u32>,
) -> Result<FirewallApplyResult, String> {
    let ifaces = classify_active_interfaces();
    let vpn_active = !ifaces.vpn.is_empty();

    if !cfg.enabled {
        flush_firewall(caps);
        previously_blocked_uids.clear();
        return Ok(FirewallApplyResult {
            ipset_entries_loaded: 0,
            ipset_backend: "disabled".to_string(),
            vpn_active,
            active_interfaces: ifaces,
        });
    }

    if !caps.iptables_available {
        return Err("iptables binary is unavailable on this device".to_string());
    }

    // Parse & split IP blocklist into IPv4 / IPv6 canonical CIDRs
    let mut v4_cidrs = Vec::new();
    let mut v6_cidrs = Vec::new();
    for entry in &cfg.ip_blocklist {
        if let Some(c) = validate_cidr(entry) {
            match c.ip {
                IpAddr::V4(_) => v4_cidrs.push(c),
                IpAddr::V6(_) => v6_cidrs.push(c),
            }
        }
    }

    // Use native Netlink ipset O(1) lookup when BOTH kernel ipset netlink AND iptables xt_set are present;
    // otherwise gracefully degrade to bounded iptables chain.
    let mut use_ipset = false;
    let mut ipset_loaded = 0usize;
    let ipset_backend: String;

    if v4_cidrs.is_empty() && v6_cidrs.is_empty() {
        ipset_backend = if caps.ipset_netlink_available && caps.xt_set_available {
            "ipset_netlink_ready".to_string()
        } else {
            "iptables_chain_fallback".to_string()
        };
    } else if caps.ipset_netlink_available && caps.xt_set_available {
        let mut ok_all = true;
        if !v4_cidrs.is_empty() {
            match ipset_atomic_sync(IPSET_V4_NAME, libc::AF_INET as u8, &v4_cidrs) {
                Ok(n) => ipset_loaded += n,
                Err(_) => ok_all = false,
            }
        }
        if !v6_cidrs.is_empty() && caps.ip6tables_available {
            match ipset_atomic_sync(IPSET_V6_NAME, libc::AF_INET6 as u8, &v6_cidrs) {
                Ok(n) => ipset_loaded += n,
                Err(_) => ok_all = false,
            }
        }
        if ok_all {
            use_ipset = true;
            ipset_backend = "ipset_netlink_o1".to_string();
        } else {
            ipset_loaded = (v4_cidrs.len() + v6_cidrs.len()).min(FALLBACK_CHAIN_MAX_CIDRS);
            ipset_backend = "iptables_chain_fallback".to_string();
        }
    } else {
        ipset_loaded = (v4_cidrs.len() + v6_cidrs.len()).min(FALLBACK_CHAIN_MAX_CIDRS);
        ipset_backend = "iptables_chain_fallback".to_string();
    }

    let v4_script = build_restore_script(
        false,
        cfg,
        caps,
        screen_on,
        vpn_active,
        use_ipset,
        &v4_cidrs,
    );
    apply_restore_atomic("iptables-restore", &v4_script)?;
    ensure_hook_jumps("iptables", cfg.normalize_ttl_hl && caps.ttl_target_available)?;

    if caps.ip6tables_available {
        let v6_script = build_restore_script(
            true,
            cfg,
            caps,
            screen_on,
            vpn_active,
            use_ipset,
            &v6_cidrs,
        );
        apply_restore_atomic("ip6tables-restore", &v6_script)?;
        ensure_hook_jumps("ip6tables", cfg.normalize_ttl_hl && caps.hl_target_available)?;
    }

    // Reclaim unused kernel ipset memory once iptables references are detached
    if v4_cidrs.is_empty() {
        ipset_cleanup(IPSET_V4_NAME);
    }
    if v6_cidrs.is_empty() {
        ipset_cleanup(IPSET_V6_NAME);
    }

    // Tear down already-established TCP sockets via SOCK_DESTROY for newly fully-blocked UIDs
    let currently_blocked: HashSet<u32> = cfg
        .rules
        .iter()
        .filter(|(_, r)| {
            (!r.allow_wifi && !r.allow_cellular && !r.allow_vpn)
                || (!screen_on && !r.allow_screen_off)
        })
        .map(|(&uid, _)| uid)
        .collect();

    if cfg.sock_destroy_on_block && caps.sock_destroy_available {
        for &uid in &currently_blocked {
            if !previously_blocked_uids.contains(&uid) {
                let _ = destroy_uid_sockets(uid);
            }
        }
    }
    *previously_blocked_uids = currently_blocked;

    Ok(FirewallApplyResult {
        ipset_entries_loaded: ipset_loaded,
        ipset_backend,
        vpn_active,
        active_interfaces: ifaces,
    })
}

/// Verifies that our top-level jump rules (`OUTPUT -> SILO_OUT`, `INPUT -> SILO_IN`)
/// are still intact in case Android `netd` reloaded rules.
pub fn ensure_hook_jumps(ipt_name: &str, mangle_ttl_enabled: bool) -> Result<(), String> {
    let bin = resolve_bin(ipt_name);

    ensure_single_jump(&bin, "filter", "OUTPUT", "SILO_OUT")?;
    ensure_single_jump(&bin, "filter", "INPUT", "SILO_IN")?;

    if mangle_ttl_enabled {
        let _ = ensure_single_jump(&bin, "mangle", "POSTROUTING", "SILO_MANGLE_POST");
    } else {
        remove_all_jumps(&bin, "mangle", "POSTROUTING", "SILO_MANGLE_POST");
    }
    Ok(())
}

fn ensure_single_jump(bin: &str, table: &str, parent: &str, target: &str) -> Result<(), String> {
    let check = run_cmd(bin, &["-w", "5", "-t", table, "-C", parent, "-j", target], None, 3000)
        .map_err(|e| format!("{} check jump failed: {}", bin, e))?;
    if !check.success() {
        let ins = run_cmd(
            bin,
            &["-w", "5", "-t", table, "-I", parent, "1", "-j", target],
            None,
            3000,
        )
        .map_err(|e| format!("{} insert jump failed: {}", bin, e))?;
        if !ins.success() {
            return Err(format!(
                "{} failed to insert {} -> {}: {}",
                bin,
                parent,
                target,
                ins.stderr.trim()
            ));
        }
    }
    Ok(())
}

fn remove_all_jumps(bin: &str, table: &str, parent: &str, target: &str) {
    for _ in 0..8 {
        let ok = run_cmd(
            bin,
            &["-w", "5", "-t", table, "-D", parent, "-j", target],
            None,
            2000,
        )
        .map(|o| o.success())
        .unwrap_or(false);
        if !ok {
            break;
        }
    }
}

/// Cleanly detaches and removes ALL Silo chains without touching any Android `netd` rules.
pub fn flush_firewall(caps: &KernelCapabilities) {
    let tools: &[(&str, bool)] = &[
        ("iptables", caps.iptables_available),
        ("ip6tables", caps.ip6tables_available),
    ];
    for &(tool, avail) in tools {
        if !avail {
            continue;
        }
        let bin = resolve_bin(tool);
        remove_all_jumps(&bin, "filter", "OUTPUT", "SILO_OUT");
        remove_all_jumps(&bin, "filter", "INPUT", "SILO_IN");
        remove_all_jumps(&bin, "mangle", "POSTROUTING", "SILO_MANGLE_POST");

        for &ch in FILTER_CHAINS {
            let _ = run_cmd(&bin, &["-w", "5", "-t", "filter", "-F", ch], None, 2000);
        }
        for &ch in FILTER_CHAINS {
            let _ = run_cmd(&bin, &["-w", "5", "-t", "filter", "-X", ch], None, 2000);
        }
        let _ = run_cmd(&bin, &["-w", "5", "-t", "mangle", "-F", "SILO_MANGLE_POST"], None, 2000);
        let _ = run_cmd(&bin, &["-w", "5", "-t", "mangle", "-X", "SILO_MANGLE_POST"], None, 2000);
    }
    ipset_cleanup(IPSET_V4_NAME);
    ipset_cleanup(IPSET_V6_NAME);
}

fn apply_restore_atomic(restore_bin_name: &str, script: &str) -> Result<(), String> {
    let bin = resolve_bin(restore_bin_name);

    // Step 1: Dry-run syntax verification (`--test --noflush`) so malformed rules never touch kernel tables
    let test = run_cmd(&bin, &["--noflush", "--test"], Some(script), 5000)
        .map_err(|e| format!("{} dry-run spawn failed: {}", restore_bin_name, e))?;
    if !test.success() {
        return Err(format!(
            "{} dry-run validation failed: {}",
            restore_bin_name,
            test.stderr.trim()
        ));
    }

    // Step 2: Atomic commit (`--noflush --wait=5`) preserving ALL Android netd chains
    let apply = run_cmd(&bin, &["--noflush", "--wait=5"], Some(script), 8000)
        .map_err(|e| format!("{} apply spawn failed: {}", restore_bin_name, e))?;
    if !apply.success() {
        return Err(format!(
            "{} commit failed: {}",
            restore_bin_name,
            apply.stderr.trim()
        ));
    }
    Ok(())
}

/// Pure, deterministic `iptables-restore --noflush` / `ip6tables-restore --noflush` compiler.
/// Completely testable offline without root!
pub fn build_restore_script(
    is_v6: bool,
    cfg: &FirewallConfig,
    caps: &KernelCapabilities,
    screen_on: bool,
    vpn_active: bool,
    use_ipset: bool,
    cidrs: &[CanonicalCidr],
) -> String {
    let mut s = String::with_capacity(8192);
    s.push_str("*filter\n");
    for &ch in FILTER_CHAINS {
        s.push_str(&format!(":{} - [0:0]\n", ch));
    }
    // Atomic per-chain flush inside --noflush transaction: prevents rule duplication on reloads
    // while NEVER flushing built-in chains or Android netd chains!
    for &ch in FILTER_CHAINS {
        s.push_str(&format!("-F {}\n", ch));
    }

    // Build terminal deny action chains with rate-limited NFLOG + REJECT/DROP
    let log_enabled = cfg.nflog_enabled && caps.nflog_available;
    let deny_specs = [
        ("SILO_DENY_UID", "SILO_UID"),
        ("SILO_DENY_LAN", "SILO_LAN"),
        ("SILO_DENY_DNS", "SILO_DNS"),
        ("SILO_DENY_HTTP", "SILO_HTTP"),
        ("SILO_DENY_IPSET", "SILO_IPSET"),
        ("SILO_DENY_V6LEAK", "SILO_V6LEAK"),
    ];
    for (chain, prefix) in deny_specs {
        if log_enabled {
            s.push_str(&format!(
                "-A {} -m limit --limit 30/sec --limit-burst 60 -j NFLOG --nflog-group {} --nflog-prefix \"{}\"\n",
                chain, SILO_NFLOG_GROUP, prefix
            ));
        }
        match cfg.block_action {
            BlockAction::Reject => {
                if is_v6 {
                    s.push_str(&format!(
                        "-A {} -p tcp -j REJECT --reject-with tcp-reset\n",
                        chain
                    ));
                    s.push_str(&format!(
                        "-A {} -j REJECT --reject-with icmp6-adm-prohibited\n",
                        chain
                    ));
                } else {
                    s.push_str(&format!(
                        "-A {} -p tcp -j REJECT --reject-with tcp-reset\n",
                        chain
                    ));
                    s.push_str(&format!(
                        "-A {} -j REJECT --reject-with icmp-port-unreachable\n",
                        chain
                    ));
                }
            }
            BlockAction::Drop => {
                s.push_str(&format!("-A {} -j DROP\n", chain));
            }
        }
    }

    // ---- SILO_IN (ingress protection without breaking normal outbound replies) ----
    s.push_str("-A SILO_IN -i lo -j RETURN\n");
    s.push_str("-A SILO_IN -m conntrack --ctstate ESTABLISHED,RELATED -j RETURN\n");
    if !cidrs.is_empty() && use_ipset {
        let set_name = if is_v6 { IPSET_V6_NAME } else { IPSET_V4_NAME };
        s.push_str(&format!(
            "-A SILO_IN -m set --match-set {} src -j SILO_DENY_IPSET\n",
            set_name
        ));
    }
    s.push_str("-A SILO_IN -j RETURN\n");

    // ---- SILO_LAN_CHK (per-app LAN allowlist before blocking RFC1918/multicast) ----
    for (&uid, rule) in &cfg.rules {
        if rule.allow_lan || rule.is_vpn_app {
            s.push_str(&format!(
                "-A SILO_LAN_CHK -m owner --uid-owner {} -j RETURN\n",
                uid
            ));
        }
    }
    s.push_str("-A SILO_LAN_CHK -m owner --uid-owner 10000-19999 -j SILO_DENY_LAN\n");
    s.push_str("-A SILO_LAN_CHK -m owner --uid-owner 110000-119999 -j SILO_DENY_LAN\n");
    s.push_str("-A SILO_LAN_CHK -j RETURN\n");

    // ---- SILO_BLK_IP (fallback CIDR chain when xt_set is unavailable in stock iptables) ----
    if !cidrs.is_empty() && !use_ipset {
        for c in cidrs.iter().take(FALLBACK_CHAIN_MAX_CIDRS) {
            s.push_str(&format!(
                "-A SILO_BLK_IP -d {} -j SILO_DENY_IPSET\n",
                c.canonical
            ));
        }
    }
    s.push_str("-A SILO_BLK_IP -j RETURN\n");

    // ---- SILO_OUT (main egress pipeline) ----
    // 1. Always exempt critical Android core system UIDs & isolated/multi-user system UIDs
    s.push_str("-A SILO_OUT -m owner --uid-owner 0-9999 -j RETURN\n");
    s.push_str("-A SILO_OUT -m owner --uid-owner 90000-109999 -j RETURN\n");

    // 2. Per-app opt-in localhost isolation, then allow normal loopback IPC
    for (&uid, rule) in &cfg.rules {
        if rule.block_localhost {
            s.push_str(&format!(
                "-A SILO_OUT -o lo -m owner --uid-owner {} -j SILO_DENY_LAN\n",
                uid
            ));
        }
    }
    s.push_str("-A SILO_OUT -o lo -j RETURN\n");

    // 3. Exempt designated VPN provider app UIDs so encrypted tunnels never deadlock
    for (&uid, rule) in &cfg.rules {
        if rule.is_vpn_app {
            s.push_str(&format!(
                "-A SILO_OUT -m owner --uid-owner {} -j RETURN\n",
                uid
            ));
        }
    }

    // 4. Global tracker / malware IP blocklist (applies to ordinary user apps)
    if !cidrs.is_empty() {
        if use_ipset {
            let set_name = if is_v6 { IPSET_V6_NAME } else { IPSET_V4_NAME };
            s.push_str(&format!(
                "-A SILO_OUT -m set --match-set {} dst -j SILO_DENY_IPSET\n",
                set_name
            ));
        } else {
            s.push_str("-A SILO_OUT -j SILO_BLK_IP\n");
        }
    }

    // 5. DNS Leak Guard: block plain port 53 bypass on Wi-Fi/Cellular from user apps
    //    (Android netd UID 1051 & VPN apps already returned cleanly in steps 1 & 3)
    if cfg.dns_leak_guard {
        for iface in WIFI_IFACES.iter().chain(CELL_IFACES.iter()) {
            for proto in ["udp", "tcp"] {
                s.push_str(&format!(
                    "-A SILO_OUT -o {} -p {} --dport 53 -m owner --uid-owner 10000-19999 -j SILO_DENY_DNS\n",
                    iface, proto
                ));
                s.push_str(&format!(
                    "-A SILO_OUT -o {} -p {} --dport 53 -m owner --uid-owner 110000-119999 -j SILO_DENY_DNS\n",
                    iface, proto
                ));
            }
        }
    }

    // 6. IPv6 VPN Leak Guard: block non-VPN IPv6 egress for user apps while VPN is active
    if is_v6 && cfg.ipv6_leak_guard && vpn_active {
        for iface in WIFI_IFACES.iter().chain(CELL_IFACES.iter()) {
            s.push_str(&format!(
                "-A SILO_OUT -o {} -m owner --uid-owner 10000-19999 -j SILO_DENY_V6LEAK\n",
                iface
            ));
            s.push_str(&format!(
                "-A SILO_OUT -o {} -m owner --uid-owner 110000-119999 -j SILO_DENY_V6LEAK\n",
                iface
            ));
        }
    }

    // 7. LAN Isolation (RFC1918 + link-local + multicast)
    if cfg.isolate_lan {
        let lan_nets = if is_v6 { LAN_V6_CIDRS } else { LAN_V4_CIDRS };
        for net in lan_nets {
            s.push_str(&format!("-A SILO_OUT -d {} -j SILO_LAN_CHK\n", net));
        }
    }

    // 8. Cleartext HTTP (TCP port 80) Guard
    if cfg.block_cleartext_http_global {
        s.push_str(
            "-A SILO_OUT -p tcp --dport 80 -m owner --uid-owner 10000-19999 -j SILO_DENY_HTTP\n",
        );
        s.push_str(
            "-A SILO_OUT -p tcp --dport 80 -m owner --uid-owner 110000-119999 -j SILO_DENY_HTTP\n",
        );
    } else {
        for (&uid, rule) in &cfg.rules {
            if rule.block_cleartext_http {
                s.push_str(&format!(
                    "-A SILO_OUT -p tcp --dport 80 -m owner --uid-owner {} -j SILO_DENY_HTTP\n",
                    uid
                ));
            }
        }
    }

    // 9. Per-UID Screen-Off & Dual-Mode Interface Policy
    match cfg.mode {
        FirewallMode::DefaultAllow => {
            for (&uid, rule) in &cfg.rules {
                if !screen_on && !rule.allow_screen_off {
                    s.push_str(&format!(
                        "-A SILO_OUT -m owner --uid-owner {} -j SILO_DENY_UID\n",
                        uid
                    ));
                    continue;
                }
                if !rule.allow_wifi && !rule.allow_cellular && !rule.allow_vpn {
                    s.push_str(&format!(
                        "-A SILO_OUT -m owner --uid-owner {} -j SILO_DENY_UID\n",
                        uid
                    ));
                    continue;
                }
                if !rule.allow_wifi {
                    for iface in WIFI_IFACES {
                        s.push_str(&format!(
                            "-A SILO_OUT -o {} -m owner --uid-owner {} -j SILO_DENY_UID\n",
                            iface, uid
                        ));
                    }
                }
                if !rule.allow_cellular {
                    for iface in CELL_IFACES {
                        s.push_str(&format!(
                            "-A SILO_OUT -o {} -m owner --uid-owner {} -j SILO_DENY_UID\n",
                            iface, uid
                        ));
                    }
                }
                if !rule.allow_vpn {
                    for iface in VPN_IFACES {
                        s.push_str(&format!(
                            "-A SILO_OUT -o {} -m owner --uid-owner {} -j SILO_DENY_UID\n",
                            iface, uid
                        ));
                    }
                }
            }
            s.push_str("-A SILO_OUT -j RETURN\n");
        }
        FirewallMode::DefaultDeny => {
            for (&uid, rule) in &cfg.rules {
                if !screen_on && !rule.allow_screen_off {
                    continue; // falls through to SILO_DENY_UID
                }
                if rule.allow_wifi {
                    for iface in WIFI_IFACES {
                        s.push_str(&format!(
                            "-A SILO_OUT -o {} -m owner --uid-owner {} -j RETURN\n",
                            iface, uid
                        ));
                    }
                }
                if rule.allow_cellular {
                    for iface in CELL_IFACES {
                        s.push_str(&format!(
                            "-A SILO_OUT -o {} -m owner --uid-owner {} -j RETURN\n",
                            iface, uid
                        ));
                    }
                }
                if rule.allow_vpn {
                    for iface in VPN_IFACES {
                        s.push_str(&format!(
                            "-A SILO_OUT -o {} -m owner --uid-owner {} -j RETURN\n",
                            iface, uid
                        ));
                    }
                }
            }
            // Only deny ordinary user app UIDs; kernel-originated packets (no owner) & system UIDs still RETURN safely
            s.push_str("-A SILO_OUT -m owner --uid-owner 10000-19999 -j SILO_DENY_UID\n");
            s.push_str("-A SILO_OUT -m owner --uid-owner 110000-119999 -j SILO_DENY_UID\n");
            s.push_str("-A SILO_OUT -j RETURN\n");
        }
    }
    s.push_str("COMMIT\n");

    // ---- Optional Mangle Table: TTL (IPv4) / Hop Limit (IPv6) Normalization ----
    let ttl_ok = if is_v6 {
        cfg.normalize_ttl_hl && caps.hl_target_available
    } else {
        cfg.normalize_ttl_hl && caps.ttl_target_available
    };
    if ttl_ok {
        let val = cfg.ttl_value.max(1);
        s.push_str("*mangle\n");
        s.push_str(":SILO_MANGLE_POST - [0:0]\n");
        s.push_str("-F SILO_MANGLE_POST\n");
        for iface in WIFI_IFACES.iter().chain(CELL_IFACES.iter()) {
            if is_v6 {
                s.push_str(&format!(
                    "-A SILO_MANGLE_POST -o {} -j HL --hl-set {}\n",
                    iface, val
                ));
            } else {
                s.push_str(&format!(
                    "-A SILO_MANGLE_POST -o {} -j TTL --ttl-set {}\n",
                    iface, val
                ));
            }
        }
        s.push_str("-A SILO_MANGLE_POST -j RETURN\n");
        s.push_str("COMMIT\n");
    }

    s
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::AppFirewallRule;

    fn mock_caps() -> KernelCapabilities {
        KernelCapabilities {
            kernel_release: "6.6.127-android15-8".into(),
            iptables_available: true,
            ip6tables_available: true,
            ipset_netlink_available: true,
            xt_set_available: true,
            nflog_available: true,
            ttl_target_available: true,
            hl_target_available: true,
            sock_destroy_available: true,
            cgroup_v2_freezer_available: true,
            available_tcp_congestion: vec!["cubic".into(), "bbr".into()],
        }
    }

    #[test]
    fn default_allow_script_never_flushes_builtin_chains_and_protects_system_uids() {
        let mut cfg = FirewallConfig {
            enabled: true,
            isolate_lan: true,
            dns_leak_guard: true,
            normalize_ttl_hl: true,
            ttl_value: 64,
            ..Default::default()
        };
        cfg.rules.insert(
            10234,
            AppFirewallRule {
                package_name: "com.shady.app".into(),
                allow_wifi: true,
                allow_cellular: false,
                allow_vpn: true,
                allow_lan: false,
                allow_screen_off: false,
                block_cleartext_http: true,
                block_localhost: true,
                is_vpn_app: false,
            },
        );
        let c = validate_cidr("198.51.100.0/24").unwrap();
        let script = build_restore_script(false, &cfg, &mock_caps(), true, false, true, &[c]);

        // Must never declare or flush built-in OUTPUT/INPUT/FORWARD policies (which would wipe netd!)
        assert!(!script.contains(":OUTPUT "));
        assert!(!script.contains(":INPUT "));
        assert!(!script.contains(":FORWARD "));
        assert!(!script.contains("-F OUTPUT"));
        // Must atomically flush Silo's own chains inside --noflush to prevent rule duplication
        assert!(script.contains("-F SILO_OUT\n"));
        assert!(script.contains("-F SILO_MANGLE_POST\n"));
        // Must protect system UIDs 0-9999 first
        assert!(script.contains("-A SILO_OUT -m owner --uid-owner 0-9999 -j RETURN"));
        // Must block MediaTek cellular ccmni+ for UID 10234
        assert!(script.contains("-A SILO_OUT -o ccmni+ -m owner --uid-owner 10234 -j SILO_DENY_UID"));
        // Must use ipset O(1) match
        assert!(script.contains("-m set --match-set silo_blk_v4 dst -j SILO_DENY_IPSET"));
        // Must normalize TTL on mangle
        assert!(script.contains("-A SILO_MANGLE_POST -o ccmni+ -j TTL --ttl-set 64"));
        // Must use rate-limited NFLOG
        assert!(script.contains("--nflog-group 42 --nflog-prefix \"SILO_UID\""));
    }

    #[test]
    fn default_deny_only_blocks_ordinary_app_uids_not_kernel_packets() {
        let mut cfg = FirewallConfig {
            enabled: true,
            mode: FirewallMode::DefaultDeny,
            ..Default::default()
        };
        cfg.rules.insert(
            10300,
            AppFirewallRule {
                package_name: "org.signal.app".into(),
                allow_wifi: true,
                allow_cellular: true,
                allow_vpn: true,
                allow_lan: false,
                allow_screen_off: true,
                block_cleartext_http: false,
                block_localhost: false,
                is_vpn_app: false,
            },
        );
        let script = build_restore_script(true, &cfg, &mock_caps(), true, true, false, &[]);
        assert!(script.contains("-A SILO_OUT -o wlan+ -m owner --uid-owner 10300 -j RETURN"));
        assert!(script.contains("-A SILO_OUT -m owner --uid-owner 10000-19999 -j SILO_DENY_UID"));
        assert!(script.ends_with("-A SILO_OUT -j RETURN\nCOMMIT\n"));
        // Must NOT have an unconditional bare DROP/REJECT at end of SILO_OUT
        assert!(!script.contains("-A SILO_OUT -j SILO_DENY_UID"));
    }

    #[test]
    fn screen_off_blocks_apps_marked_disallow_screen_off() {
        let mut cfg = FirewallConfig {
            enabled: true,
            ..Default::default()
        };
        cfg.rules.insert(
            10400,
            AppFirewallRule {
                package_name: "com.bg.tracker".into(),
                allow_wifi: true,
                allow_cellular: true,
                allow_vpn: true,
                allow_lan: false,
                allow_screen_off: false,
                block_cleartext_http: false,
                block_localhost: false,
                is_vpn_app: false,
            },
        );
        let script = build_restore_script(false, &cfg, &mock_caps(), false, false, false, &[]);
        assert!(script.contains("-A SILO_OUT -m owner --uid-owner 10400 -j SILO_DENY_UID"));
    }
}
