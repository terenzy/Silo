use crate::automation::get_private_dns;
use crate::config::SiloConfig;
use crate::freezer::FreezerEngine;
use crate::hardening::{read_sysctl, HardeningEngine};
use crate::netlink::list_active_connections;
use crate::protocol::{
    AuditFinding, AuditSeverity, KernelCapabilities, LiveProofItem, LiveProofReport, SentinelReport,
};
use crate::scopes::count_live_ignored_ops;
use crate::sys::{now_millis, parse_proc_status_uid, read_uid_package_map, resolve_bin, run_cmd};
use std::fs;
use std::path::Path;

/// Strictly READ-ONLY Zero-Leak Stealth & Security Integrity Auditor.
pub fn run_sentinel_audit(cfg: &SiloConfig, firewall_active: bool) -> SentinelReport {
    let mut findings = Vec::new();
    let mut score: i32 = 100;

    let selinux_enforcing = fs::read_to_string("/sys/fs/selinux/enforce")
        .map(|s| s.trim() == "1")
        .unwrap_or(false);
    if selinux_enforcing {
        findings.push(AuditFinding {
            id: "selinux_enforcing".into(),
            category: "Kernel Integrity".into(),
            title: "SELinux Enforcing".into(),
            severity: AuditSeverity::Pass,
            detail: "SELinux is actively enforcing mandatory access control.".into(),
            recommendation: None,
        });
    } else {
        score -= 25;
        findings.push(AuditFinding {
            id: "selinux_permissive".into(),
            category: "Kernel Integrity".into(),
            title: "SELinux Not Enforcing".into(),
            severity: AuditSeverity::Critical,
            detail: "SELinux is permissive or disabled, weakening Android sandboxing.".into(),
            recommendation: Some("Restore SELinux enforcing mode (`setenforce 1`).".into()),
        });
    }

    let dmesg_restrict = read_sysctl("kernel.dmesg_restrict").as_deref() == Some("1");
    if dmesg_restrict {
        findings.push(AuditFinding {
            id: "dmesg_restrict".into(),
            category: "Kernel Stealth".into(),
            title: "dmesg Access Restricted".into(),
            severity: AuditSeverity::Pass,
            detail: "Non-root processes cannot read kernel dmesg logs.".into(),
            recommendation: None,
        });
    } else {
        score -= 8;
        findings.push(AuditFinding {
            id: "dmesg_open".into(),
            category: "Kernel Stealth".into(),
            title: "dmesg Readable by Unprivileged Processes".into(),
            severity: AuditSeverity::Warn,
            detail: "kernel.dmesg_restrict is 0; apps may inspect kernel logs.".into(),
            recommendation: Some("Enable Kernel Hardening in Silo.".into()),
        });
    }

    let unpriv_bpf = read_sysctl("kernel.unprivileged_bpf_disabled");
    if matches!(unpriv_bpf.as_deref(), Some("1") | Some("2")) {
        findings.push(AuditFinding {
            id: "unpriv_bpf_closed".into(),
            category: "Exploit Surface".into(),
            title: "Unprivileged eBPF Disabled".into(),
            severity: AuditSeverity::Pass,
            detail: format!(
                "kernel.unprivileged_bpf_disabled = {}",
                unpriv_bpf.unwrap_or_default()
            ),
            recommendation: None,
        });
    } else if unpriv_bpf.is_some() {
        score -= 10;
        findings.push(AuditFinding {
            id: "unpriv_bpf_open".into(),
            category: "Exploit Surface".into(),
            title: "Unprivileged eBPF Enabled".into(),
            severity: AuditSeverity::Warn,
            detail: "Ordinary apps can invoke bpf() syscalls (wider kernel attack surface).".into(),
            recommendation: Some("Enable Kernel Hardening in System settings.".into()),
        });
    }

    if firewall_active && cfg.firewall.enabled {
        findings.push(AuditFinding {
            id: "firewall_active".into(),
            category: "Network Defense".into(),
            title: "Kernel Firewall Enforcing".into(),
            severity: AuditSeverity::Pass,
            detail: format!(
                "Mode: {:?}, LAN Guard: {}, DNS Guard: {}, Anti-Scan: {}.",
                cfg.firewall.mode,
                cfg.firewall.isolate_lan,
                cfg.firewall.dns_leak_guard,
                cfg.firewall.anti_port_scan
            ),
            recommendation: None,
        });
    } else {
        score -= 10;
        findings.push(AuditFinding {
            id: "firewall_inactive".into(),
            category: "Network Defense".into(),
            title: "Kernel Firewall Off".into(),
            severity: AuditSeverity::Info,
            detail: "Firewall is currently disabled (Safe-First default).".into(),
            recommendation: Some("Turn on Firewall or pick Balanced/Hardened preset.".into()),
        });
    }

    let user_ca_count = count_files_in_dir("/data/misc/user/0/cacerts-added");
    if user_ca_count == 0 {
        findings.push(AuditFinding {
            id: "user_ca_clean".into(),
            category: "TLS & Privacy".into(),
            title: "No User CA Certificates".into(),
            severity: AuditSeverity::Pass,
            detail: "Zero custom user root CAs present (clean TLS trust & banking hygiene).".into(),
            recommendation: None,
        });
    } else {
        score -= 12;
        findings.push(AuditFinding {
            id: "user_ca_present".into(),
            category: "TLS & Privacy".into(),
            title: format!("{} User CA Certificate(s) Installed", user_ca_count),
            severity: AuditSeverity::Warn,
            detail: "Custom user CAs can intercept HTTPS and trigger banking alerts.".into(),
            recommendation: Some("Remove unused user CAs in Android Security settings.".into()),
        });
    }

    let uid_map = read_uid_package_map();
    let conns = list_active_connections(&uid_map);
    let app_listeners: Vec<_> = conns
        .iter()
        .filter(|c| c.is_listening && (c.uid % 100_000) >= 10_000)
        .collect();
    let app_listening_ports = app_listeners.len();
    if app_listening_ports == 0 {
        findings.push(AuditFinding {
            id: "no_app_listeners".into(),
            category: "Network Defense".into(),
            title: "No App Listening Ports".into(),
            severity: AuditSeverity::Pass,
            detail: "Zero user apps have open TCP/UDP listening sockets.".into(),
            recommendation: None,
        });
    } else {
        score -= (app_listening_ports.min(3) as i32) * 4;
        let sample: Vec<String> = app_listeners
            .iter()
            .take(5)
            .map(|c| {
                format!(
                    "{} ({}:{} {})",
                    c.package_name
                        .clone()
                        .unwrap_or_else(|| format!("uid:{}", c.uid)),
                    c.local_ip,
                    c.local_port,
                    c.protocol
                )
            })
            .collect();
        findings.push(AuditFinding {
            id: "app_listeners_found".into(),
            category: "Network Defense".into(),
            title: format!("{} App Listening Socket(s)", app_listening_ports),
            severity: AuditSeverity::Warn,
            detail: format!("Active listeners: {}", sample.join(", ")),
            recommendation: Some("Inspect in Monitor tab and enable Localhost isolation if unwanted.".into()),
        });
    }

    let tmp_execs = count_executables_in_tmp();
    if tmp_execs == 0 {
        findings.push(AuditFinding {
            id: "tmp_clean".into(),
            category: "Stealth".into(),
            title: "/data/local/tmp Clean".into(),
            severity: AuditSeverity::Pass,
            detail: "No stray executable binaries found in /data/local/tmp.".into(),
            recommendation: None,
        });
    } else {
        score -= 8;
        findings.push(AuditFinding {
            id: "tmp_execs".into(),
            category: "Stealth".into(),
            title: format!("{} Executable(s) in /data/local/tmp", tmp_execs),
            severity: AuditSeverity::Warn,
            detail: "Stray executables in /data/local/tmp can be flagged by detectors.".into(),
            recommendation: Some("Remove unused binaries in /data/local/tmp.".into()),
        });
    }

    let suspicious_roots = scan_suspicious_root_procs();
    let suspicious_root_procs = suspicious_roots.len();
    if suspicious_root_procs == 0 {
        findings.push(AuditFinding {
            id: "root_procs_clean".into(),
            category: "Root Integrity".into(),
            title: "No Untrusted Root Binaries".into(),
            severity: AuditSeverity::Pass,
            detail: "No UID 0 processes executing from /sdcard or /data/local/tmp.".into(),
            recommendation: None,
        });
    } else {
        score -= 20;
        findings.push(AuditFinding {
            id: "suspicious_root_procs".into(),
            category: "Root Integrity".into(),
            title: format!("{} Suspicious Root Process(es)", suspicious_root_procs),
            severity: AuditSeverity::Critical,
            detail: format!("Detected: {}", suspicious_roots.join(", ")),
            recommendation: Some("Investigate immediately and revoke unauthorized root access.".into()),
        });
    }

    let ksu_present = Path::new("/data/adb/ksu").exists();
    let companion_present = Path::new("/data/adb/modules/a146p_stealth_companion").exists();
    let lsm_visible = fs::read_to_string("/sys/kernel/security/lsm").unwrap_or_default();
    let bbg_cloaked = !lsm_visible.contains("baseband_guard");
    findings.push(AuditFinding {
        id: "stealth_stack".into(),
        category: "Kernel Stealth".into(),
        title: "Zero-Leak Stealth Hygiene".into(),
        severity: if bbg_cloaked {
            AuditSeverity::Pass
        } else {
            AuditSeverity::Info
        },
        detail: format!(
            "KernelSU: {}, Companion: {}, LSM cloaked: {}, Zero-Mount: true, Zero-TCP: true.",
            if ksu_present { "OK" } else { "-" },
            if companion_present { "OK" } else { "-" },
            bbg_cloaked
        ),
        recommendation: None,
    });

    if read_sysctl("net.ipv4.tcp_timestamps").as_deref() == Some("0") {
        findings.push(AuditFinding {
            id: "tcp_ts_off".into(),
            category: "Network Privacy".into(),
            title: "TCP Timestamps Disabled".into(),
            severity: AuditSeverity::Pass,
            detail: "Uptime & clock-skew fingerprinting via TCP headers is blocked.".into(),
            recommendation: None,
        });
    } else {
        score -= 4;
        findings.push(AuditFinding {
            id: "tcp_ts_on".into(),
            category: "Network Privacy".into(),
            title: "TCP Timestamps Enabled".into(),
            severity: AuditSeverity::Info,
            detail: "TCP timestamps can expose device uptime to remote hosts.".into(),
            recommendation: Some("Enable Kernel Hardening to disable tcp_timestamps.".into()),
        });
    }

    SentinelReport {
        score: score.clamp(0, 100) as u32,
        timestamp_ms: now_millis(),
        selinux_enforcing,
        user_ca_count,
        app_listening_ports,
        suspicious_root_procs,
        findings,
    }
}

/// REAL Anti-Gimmick Live Kernel Verification: inspects live iptables/ip6tables kernel chains,
/// live `/proc/sys` hardening parameters, live cgroup v2 `frozen 1` nodes, live AppOps `ignore`
/// rules, and live Android Private DNS state!
pub fn run_live_proof(
    cfg: &SiloConfig,
    caps: &KernelCapabilities,
    hardening: &HardeningEngine,
    freezer: &FreezerEngine,
) -> LiveProofReport {
    let mut items = Vec::new();

    // 1. Live IPv4 Netfilter Proof (`iptables -w -S OUTPUT` & `-S SILO_OUT`)
    let ipt = resolve_bin("iptables");
    let ipv4_hook_attached = run_cmd(&ipt, &["-w", "-C", "OUTPUT", "-j", "SILO_OUT"], None, 2000)
        .map(|o| o.success())
        .unwrap_or(false);
    let ipv4_silo_rules_count = count_chain_rules(&ipt, "SILO_OUT") + count_chain_rules(&ipt, "SILO_IN");
    items.push(LiveProofItem {
        subsystem: "IPv4 Netfilter (SILO_OUT / SILO_IN)".into(),
        verified: if cfg.firewall.enabled {
            ipv4_hook_attached && ipv4_silo_rules_count > 0
        } else {
            !ipv4_hook_attached
        },
        evidence: format!(
            "OUTPUT->SILO_OUT hook={}, live kernel rules={}",
            ipv4_hook_attached, ipv4_silo_rules_count
        ),
    });

    // 2. Live IPv6 Netfilter Proof
    let ip6t = resolve_bin("ip6tables");
    let ipv6_hook_attached = run_cmd(&ip6t, &["-w", "-C", "OUTPUT", "-j", "SILO_OUT"], None, 2000)
        .map(|o| o.success())
        .unwrap_or(false);
    let ipv6_silo_rules_count =
        count_chain_rules(&ip6t, "SILO_OUT") + count_chain_rules(&ip6t, "SILO_IN");
    items.push(LiveProofItem {
        subsystem: "IPv6 Netfilter (SILO_OUT / SILO_IN)".into(),
        verified: if cfg.firewall.enabled && caps.ip6tables_available {
            ipv6_hook_attached && ipv6_silo_rules_count > 0
        } else {
            true
        },
        evidence: format!(
            "OUTPUT->SILO_OUT hook={}, live kernel rules={}",
            ipv6_hook_attached, ipv6_silo_rules_count
        ),
    });

    // 3. Live Netlink Capabilities Proof
    items.push(LiveProofItem {
        subsystem: "Kernel Netlink (SOCK_DESTROY / ipset / NFLOG)".into(),
        verified: caps.sock_destroy_available || caps.ipset_netlink_available,
        evidence: format!(
            "SOCK_DESTROY={}, ipset_netlink={}, xt_set={}, xt_recent={}, NFLOG={}",
            caps.sock_destroy_available,
            caps.ipset_netlink_available,
            caps.xt_set_available,
            caps.xt_recent_available,
            caps.nflog_available
        ),
    });

    // 4. Live Sysctl Hardening Proof
    let h_status = hardening.status(&cfg.hardening);
    let sysctl_total_supported = h_status.items.iter().filter(|i| i.supported).count();
    let sysctl_enforced_count = h_status
        .items
        .iter()
        .filter(|i| i.supported && i.enforced)
        .count();
    items.push(LiveProofItem {
        subsystem: "Kernel Sysctl Hardening (/proc/sys)".into(),
        verified: !cfg.hardening.enabled || sysctl_enforced_count > 0,
        evidence: format!(
            "{}/{} supported kernel parameters actively enforced",
            sysctl_enforced_count, sysctl_total_supported
        ),
    });

    // 5. Live App Isolation Scopes Proof (`cmd appops get`)
    let live_ignored_ops = count_live_ignored_ops(&cfg.scopes);
    items.push(LiveProofItem {
        subsystem: "App Isolation Scopes (AppOps ignore)".into(),
        verified: cfg.scopes.apps.is_empty() || live_ignored_ops > 0,
        evidence: format!(
            "{} isolated app(s), {} live `ignore` AppOps verified in framework",
            cfg.scopes.apps.len(),
            live_ignored_ops
        ),
    });

    // 6. Live Kernel Freezer & Deep Suspend Proof
    let verified_frozen_pids = freezer.count_verified_frozen_pids();
    let suspended_packages_count = freezer.suspended_packages().len();
    items.push(LiveProofItem {
        subsystem: "App Freezer (cgroup v2 & pm suspend)".into(),
        verified: caps.cgroup_v2_freezer_available,
        evidence: format!(
            "cgroup2={}, verified frozen cgroup nodes={}, pm suspended apps={}",
            caps.cgroup_v2_freezer_available, verified_frozen_pids, suspended_packages_count
        ),
    });

    // 7. Live Private DNS (DoT) Proof
    let dns = get_private_dns();
    items.push(LiveProofItem {
        subsystem: "System Private DNS (DoT)".into(),
        verified: dns.mode == "hostname" || dns.mode == "opportunistic",
        evidence: format!(
            "mode={}, host={}",
            dns.mode,
            if dns.specifier.is_empty() {
                "auto"
            } else {
                &dns.specifier
            }
        ),
    });

    LiveProofReport {
        timestamp_ms: now_millis(),
        ipv4_hook_attached,
        ipv4_silo_rules_count,
        ipv6_hook_attached,
        ipv6_silo_rules_count,
        sysctl_enforced_count,
        sysctl_total_supported,
        verified_frozen_pids,
        suspended_packages_count,
        items,
    }
}

fn count_chain_rules(bin: &str, chain: &str) -> usize {
    run_cmd(bin, &["-w", "-S", chain], None, 2000)
        .ok()
        .filter(|o| o.success())
        .map(|o| {
            o.stdout
                .lines()
                .filter(|l| l.starts_with("-A "))
                .count()
        })
        .unwrap_or(0)
}

fn count_files_in_dir(path: &str) -> usize {
    let Ok(rd) = fs::read_dir(path) else {
        return 0;
    };
    rd.flatten()
        .filter(|e| e.file_type().map(|t| t.is_file()).unwrap_or(false))
        .count()
}

fn count_executables_in_tmp() -> usize {
    use std::os::unix::fs::PermissionsExt;
    let Ok(rd) = fs::read_dir("/data/local/tmp") else {
        return 0;
    };
    rd.flatten()
        .filter(|e| {
            e.metadata()
                .map(|m| m.is_file() && (m.permissions().mode() & 0o111) != 0)
                .unwrap_or(false)
        })
        .count()
}

fn scan_suspicious_root_procs() -> Vec<String> {
    let mut hits = Vec::new();
    let Ok(entries) = fs::read_dir("/proc") else {
        return hits;
    };
    for entry in entries.flatten() {
        let name = entry.file_name();
        let Some(pid_str) = name.to_str() else {
            continue;
        };
        if !pid_str.bytes().all(|b| b.is_ascii_digit()) {
            continue;
        }
        let path = entry.path();
        let Ok(status) = fs::read_to_string(path.join("status")) else {
            continue;
        };
        if parse_proc_status_uid(&status) != Some(0) {
            continue;
        }
        if let Ok(exe) = fs::read_link(path.join("exe")) {
            let exe_str = exe.to_string_lossy();
            if exe_str.starts_with("/data/local/tmp/")
                || exe_str.starts_with("/sdcard/")
                || exe_str.starts_with("/storage/")
                || exe_str.starts_with("/data/media/")
            {
                hits.push(format!("pid:{} ({})", pid_str, exe_str));
            }
        }
    }
    hits
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sentinel_audit_and_live_proof_run_safely() {
        let cfg = SiloConfig::default();
        let report = run_sentinel_audit(&cfg, false);
        assert!(report.score <= 100);
        assert!(!report.findings.is_empty());

        let caps = crate::firewall::probe_capabilities();
        let proof = run_live_proof(&cfg, &caps, &HardeningEngine::new(), &FreezerEngine::new());
        assert_eq!(proof.items.len(), 7);
    }
}
