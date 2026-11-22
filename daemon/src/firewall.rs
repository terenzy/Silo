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
    "SILO_DENY_SCAN",
];

#[derive(Debug, Clone)]
pub struct FirewallApplyResult {
    pub ipset_entries_loaded: usize,
    pub ipset_backend: String,
    pub vpn_active: bool,
    pub active_interfaces: ActiveInterfaces,
    pub warning: Option<String>,
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
            .map(|o| {
                o.success()
                    && (o.stdout.contains("--match-set") || o.stderr.contains("--match-set"))
            })
            .unwrap_or(false)
    } else {
        false
    };

    let xt_recent_available = if iptables_available {
        run_cmd(&ipt, &["-m", "recent", "--help"], None, 2000)
            .map(|o| {
                o.success() && (o.stdout.contains("--rcheck") || o.stderr.contains("--rcheck"))
            })
            .unwrap_or(false)
    } else {
        false
    };

    let nflog_available = if iptables_available {
        run_cmd(&ipt, &["-j", "NFLOG", "--help"], None, 2000)
            .map(|o| {
                o.success()
                    && (o.stdout.contains("--nflog-group") || o.stderr.contains("--nflog-group"))
            })
            .unwrap_or(false)
    } else {
        false
    };

    let ttl_target_available = if iptables_available {
        run_cmd(&ipt, &["-t", "mangle", "-j", "TTL", "--help"], None, 2000)
            .map(|o| {
                o.success() && (o.stdout.contains("--ttl-set") || o.stderr.contains("--ttl-set"))
            })
            .unwrap_or(false)
    } else {
        false
    };

    let hl_target_available = if ip6tables_available {
        run_cmd(&ip6t, &["-t", "mangle", "-j", "HL", "--help"], None, 2000)
            .map(|o| {
                o.success() && (o.stdout.contains("--hl-set") || o.stderr.contains("--hl-set"))
            })
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
        xt_recent_available,
        nflog_available,
        ttl_target_available,
        hl_target_available,
        sock_destroy_available,
        cgroup_v2_freezer_available,
        available_tcp_congestion,
    }
}

/// Applies the firewall policy atomically using Android `netd`'s exact `iptables-restore --noflush -w`
/// flags, with graceful automatic degradation if an optional kernel match/target fails on an OEM build.
pub fn apply_firewall(
    cfg: &FirewallConfig,
    caps: &mut KernelCapabilities,
    screen_on: bool,
    temp_unblocked_uids: &HashSet<u32>,
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
            warning: None,
        });
    }

    if !caps.iptables_available {
        return Err("iptables binary is unavailable on this device".to_string());
    }

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

    // Apply IPv4 with automatic extension degradation if OEM iptables lacks NFLOG / TTL / xt_recent
    let mut warning: Option<String> = None;
    apply_table_with_fallback(
        false,
        cfg,
        caps,
        screen_on,
        vpn_active,
        use_ipset,
        &v4_cidrs,
        temp_unblocked_uids,
        &mut warning,
    )?;
    ensure_hook_jumps("iptables", cfg.normalize_ttl_hl && caps.ttl_target_available)?;

    // Apply IPv6 independently (an IPv6 OEM quirk must never disable IPv4 protection)
    if caps.ip6tables_available {
        match apply_table_with_fallback(
            true,
            cfg,
            caps,
            screen_on,
            vpn_active,
            use_ipset,
            &v6_cidrs,
            temp_unblocked_uids,
            &mut warning,
        ) {
            Ok(()) => {
                let _ = ensure_hook_jumps(
                    "ip6tables",
                    cfg.normalize_ttl_hl && caps.hl_target_available,
                );
            }
            Err(e) => {
                warning = Some(format!("IPv6 partial warning: {}", e));
            }
        }
    }

    if v4_cidrs.is_empty() {
        ipset_cleanup(IPSET_V4_NAME);
    }
    if v6_cidrs.is_empty() {
        ipset_cleanup(IPSET_V6_NAME);
    }

    // Tear down already-established TCP sockets via SOCK_DESTROY for newly blocked UIDs
    let currently_blocked: HashSet<u32> = cfg
        .rules
        .iter()
        .filter(|(&uid, r)| {
            !temp_unblocked_uids.contains(&uid)
                && ((!r.allow_wifi && !r.allow_cellular && !r.allow_vpn)
                    || (!screen_on && !r.allow_screen_off))
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
        warning,
    })
}

#[allow(clippy::too_many_arguments)]
fn apply_table_with_fallback(
    is_v6: bool,
    cfg: &FirewallConfig,
    caps: &mut KernelCapabilities,
    screen_on: bool,
    vpn_active: bool,
    use_ipset: bool,
    cidrs: &[CanonicalCidr],
    temp_unblocked_uids: &HashSet<u32>,
    warning: &mut Option<String>,
) -> Result<(), String> {
    let restore_name = if is_v6 {
        "ip6tables-restore"
    } else {
        "iptables-restore"
    };

    let script = build_restore_script(
        is_v6,
        cfg,
        caps,
        screen_on,
        vpn_active,
        use_ipset,
        cidrs,
        temp_unblocked_uids,
    );
    match apply_restore_atomic(restore_name, &script) {
        Ok(()) => Ok(()),
        Err(first_err) => {
            // Inspect error and degrade unsupported optional extensions cleanly instead of breaking the whole firewall
            let lower = first_err.to_lowercase();
            let mut degraded = false;
            if (lower.contains("nflog") || lower.contains("limit")) && caps.nflog_available {
                caps.nflog_available = false;
                degraded = true;
            }
            if lower.contains("recent") && caps.xt_recent_available {
                caps.xt_recent_available = false;
                degraded = true;
            }
            if lower.contains("ttl") && caps.ttl_target_available {
                caps.ttl_target_available = false;
                degraded = true;
            }
            if lower.contains("hl") && caps.hl_target_available {
                caps.hl_target_available = false;
                degraded = true;
            }
            if lower.contains("set") && use_ipset {
                caps.xt_set_available = false;
                degraded = true;
            }
            if !degraded {
                // Last-resort safe retry disabling optional targets so core per-UID firewall always works
                caps.nflog_available = false;
                caps.xt_recent_available = false;
                if is_v6 {
                    caps.hl_target_available = false;
                } else {
                    caps.ttl_target_available = false;
                }
            }
            *warning = Some(format!("Auto-degraded optional target after: {}", first_err));
            let retry_script = build_restore_script(
                is_v6,
                cfg,
                caps,
                screen_on,
                vpn_active,
                false,
                cidrs,
                temp_unblocked_uids,
            );
            apply_restore_atomic(restore_name, &retry_script)
        }
    }
}

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
    let check = run_cmd(bin, &["-w", "-t", table, "-C", parent, "-j", target], None, 3000)
        .map_err(|e| format!("{} check jump failed: {}", bin, e))?;
    if !check.success() {
        let ins = run_cmd(
            bin,
            &["-w", "-t", table, "-I", parent, "1", "-j", target],
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
        let ok = run_cmd(bin, &["-w", "-t", table, "-D", parent, "-j", target], None, 2000)
            .map(|o| o.success())
            .unwrap_or(false);
        if !ok {
            break;
        }
    }
}

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
            let _ = run_cmd(&bin, &["-w", "-t", "filter", "-F", ch], None, 2000);
        }
        for &ch in FILTER_CHAINS {
            let _ = run_cmd(&bin, &["-w", "-t", "filter", "-X", ch], None, 2000);
        }
        let _ = run_cmd(&bin, &["-w", "-t", "mangle", "-F", "SILO_MANGLE_POST"], None, 2000);
        let _ = run_cmd(&bin, &["-w", "-t", "mangle", "-X", "SILO_MANGLE_POST"], None, 2000);
    }
    ipset_cleanup(IPSET_V4_NAME);
    ipset_cleanup(IPSET_V6_NAME);
}

/// Uses Android `netd`'s exact `iptables-restore --noflush -w` invocation (no `--wait=5`!).
fn apply_restore_atomic(restore_bin_name: &str, script: &str) -> Result<(), String> {
    let bin = resolve_bin(restore_bin_name);
    let apply = run_cmd(&bin, &["--noflush", "-w"], Some(script), 8000)
        .map_err(|e| format!("{} spawn failed: {}", restore_bin_name, e))?;
    if !apply.success() {
        return Err(format!(
            "{} commit failed (code {}): {}",
            restore_bin_name,
            apply.status_code,
            apply.stderr.trim()
        ));
    }
    Ok(())
}

/// Pure, deterministic `iptables-restore --noflush` compiler.
#[allow(clippy::too_many_arguments)]
pub fn build_restore_script(
    is_v6: bool,
    cfg: &FirewallConfig,
    caps: &KernelCapabilities,
    screen_on: bool,
    vpn_active: bool,
    use_ipset: bool,
    cidrs: &[CanonicalCidr],
    temp_unblocked_uids: &HashSet<u32>,
) -> String {
    let mut s = String::with_capacity(8192);
    s.push_str("*filter\n");
    for &ch in FILTER_CHAINS {
        s.push_str(&format!(":{} - [0:0]\n", ch));
    }
    for &ch in FILTER_CHAINS {
        s.push_str(&format!("-F {}\n", ch));
    }

    let log_enabled = cfg.nflog_enabled && caps.nflog_available;
    let deny_specs = [
        ("SILO_DENY_UID", "SILO_UID"),
        ("SILO_DENY_LAN", "SILO_LAN"),
        ("SILO_DENY_DNS", "SILO_DNS"),
        ("SILO_DENY_HTTP", "SILO_HTTP"),
        ("SILO_DENY_IPSET", "SILO_IPSET"),
        ("SILO_DENY_V6LEAK", "SILO_V6LEAK"),
        ("SILO_DENY_SCAN", "SILO_SCAN"),
    ];
    for (chain, prefix) in deny_specs {
        if log_enabled {
            s.push_str(&format!(
                "-A {} -m limit --limit 30/sec --limit-burst 60 -j NFLOG --nflog-group {} --nflog-prefix \"{}\"\n",
                chain, SILO_NFLOG_GROUP, prefix
            ));
        }
        // Always silent DROP for inbound port-scanners so we don't reveal open/closed ports
        if chain == "SILO_DENY_SCAN" {
            s.push_str(&format!("-A {} -j DROP\n", chain));
            continue;
        }
        match cfg.block_action {
            BlockAction::Reject => {
                s.push_str(&format!(
                    "-A {} -p tcp -j REJECT --reject-with tcp-reset\n",
                    chain
                ));
                if is_v6 {
                    s.push_str(&format!(
                        "-A {} -j REJECT --reject-with icmp6-adm-prohibited\n",
                        chain
                    ));
                } else {
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

    // ---- SILO_IN (ingress anti-port-scan & ipset source protection) ----
    s.push_str("-A SILO_IN -i lo -j RETURN\n");
    s.push_str("-A SILO_IN -m conntrack --ctstate ESTABLISHED,RELATED -j RETURN\n");
    if cfg.anti_port_scan {
        s.push_str("-A SILO_IN -m conntrack --ctstate INVALID -j DROP\n");
        if caps.xt_recent_available {
            let list_name = if is_v6 { "SILO_SCAN6" } else { "SILO_SCAN4" };
            s.push_str(&format!(
                "-A SILO_IN -p tcp --syn -m recent --name {} --set\n",
                list_name
            ));
            s.push_str(&format!(
                "-A SILO_IN -p tcp --syn -m recent --name {} --rcheck --seconds 60 --hitcount 12 -j SILO_DENY_SCAN\n",
                list_name
            ));
        }
    }
    if !cidrs.is_empty() && use_ipset {
        let set_name = if is_v6 { IPSET_V6_NAME } else { IPSET_V4_NAME };
        s.push_str(&format!(
            "-A SILO_IN -m set --match-set {} src -j SILO_DENY_IPSET\n",
            set_name
        ));
    }
    s.push_str("-A SILO_IN -j RETURN\n");

    // ---- SILO_LAN_CHK ----
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

    // ---- SILO_BLK_IP (fallback CIDR chain) ----
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
        if rule.block_localhost && !temp_unblocked_uids.contains(&uid) {
            s.push_str(&format!(
                "-A SILO_OUT -o lo -m owner --uid-owner {} -j SILO_DENY_LAN\n",
                uid
            ));
        }
    }
    s.push_str("-A SILO_OUT -o lo -j RETURN\n");

    // 3. Exempt designated VPN provider app UIDs
    for (&uid, rule) in &cfg.rules {
        if rule.is_vpn_app {
            s.push_str(&format!(
                "-A SILO_OUT -m owner --uid-owner {} -j RETURN\n",
                uid
            ));
        }
    }

    // 4. Global IP/CIDR blocklist
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

    // 5. DNS Leak Guard
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

    // 6. IPv6 VPN Leak Guard
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

    // 7. LAN Isolation
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
            if rule.block_cleartext_http && !temp_unblocked_uids.contains(&uid) {
                s.push_str(&format!(
                    "-A SILO_OUT -p tcp --dport 80 -m owner --uid-owner {} -j SILO_DENY_HTTP\n",
                    uid
                ));
            }
        }
    }

    // 9. Temporary Unblock Grants (bypass per-UID restrictions until timer expires)
    for &uid in temp_unblocked_uids {
        s.push_str(&format!(
            "-A SILO_OUT -m owner --uid-owner {} -j RETURN\n",
            uid
        ));
    }

    // 10. Per-UID Screen-Off & Dual-Mode Interface Policy
    match cfg.mode {
        FirewallMode::DefaultAllow => {
            for (&uid, rule) in &cfg.rules {
                if temp_unblocked_uids.contains(&uid) {
                    continue;
                }
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
                    continue;
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
            s.push_str("-A SILO_OUT -m owner --uid-owner 10000-19999 -j SILO_DENY_UID\n");
            s.push_str("-A SILO_OUT -m owner --uid-owner 110000-119999 -j SILO_DENY_UID\n");
            s.push_str("-A SILO_OUT -j RETURN\n");
        }
    }
    s.push_str("COMMIT\n");

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
            xt_recent_available: true,
            nflog_available: true,
            ttl_target_available: true,
            hl_target_available: true,
            sock_destroy_available: true,
            cgroup_v2_freezer_available: true,
            available_tcp_congestion: vec!["cubic".into(), "bbr".into()],
        }
    }

    #[test]
    fn generates_anti_port_scan_and_temp_unblock_rules_cleanly() {
        let mut cfg = FirewallConfig {
            enabled: true,
            anti_port_scan: true,
            isolate_lan: true,
            dns_leak_guard: true,
            normalize_ttl_hl: true,
            ttl_value: 64,
            ..Default::default()
        };
        cfg.rules
            .insert(10277, AppFirewallRule::deny_all("com.shop.app".into()));
        let mut temp = HashSet::new();
        temp.insert(10277);

        let script = build_restore_script(false, &cfg, &mock_caps(), true, false, false, &[], &temp);
        assert!(script.contains("-m recent --name SILO_SCAN4 --rcheck --seconds 60 --hitcount 12 -j SILO_DENY_SCAN"));
        assert!(script.contains("-A SILO_OUT -m owner --uid-owner 10277 -j RETURN"));
        assert!(!script.contains("-A SILO_OUT -m owner --uid-owner 10277 -j SILO_DENY_UID"));
        assert!(!script.contains(":OUTPUT "));
        assert!(script.contains("-F SILO_OUT\n"));
    }

    #[test]
    fn default_deny_protects_kernel_packets() {
        let cfg = FirewallConfig {
            enabled: true,
            mode: FirewallMode::DefaultDeny,
            ..Default::default()
        };
        let script = build_restore_script(true, &cfg, &mock_caps(), true, true, false, &[], &HashSet::new());
        assert!(script.contains("-A SILO_OUT -m owner --uid-owner 10000-19999 -j SILO_DENY_UID"));
        assert!(script.ends_with("-A SILO_OUT -j RETURN\nCOMMIT\n"));
    }
}
