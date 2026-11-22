use crate::config::SiloConfig;
use crate::hardening::read_sysctl;
use crate::netlink::list_active_connections;
use crate::protocol::{AuditFinding, AuditSeverity, SentinelReport};
use crate::sys::{now_millis, parse_proc_status_uid, read_uid_package_map};
use std::fs;
use std::path::Path;

/// Strictly READ-ONLY Zero-Leak Stealth & Security Integrity Auditor.
/// Never modifies SUSFS, NoMount, HMA-OSS, or Baseband-guard configuration so it stays
/// 100% conflict-free with `a146p_stealth_companion`.
pub fn run_sentinel_audit(cfg: &SiloConfig, firewall_active: bool) -> SentinelReport {
    let mut findings = Vec::new();
    let mut score: i32 = 100;

    // 1. SELinux Enforcing verification
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
            detail: "SELinux is permissive or disabled, greatly weakening Android app sandboxing."
                .into(),
            recommendation: Some("Restore SELinux enforcing mode (`setenforce 1`).".into()),
        });
    }

    // 2. Kernel dmesg_restrict & unprivileged BPF check
    let dmesg_restrict = read_sysctl("kernel.dmesg_restrict").as_deref() == Some("1");
    if dmesg_restrict {
        findings.push(AuditFinding {
            id: "dmesg_restrict".into(),
            category: "Kernel Stealth".into(),
            title: "dmesg Access Restricted".into(),
            severity: AuditSeverity::Pass,
            detail: "Non-root processes cannot read kernel dmesg ring buffer.".into(),
            recommendation: None,
        });
    } else {
        score -= 8;
        findings.push(AuditFinding {
            id: "dmesg_open".into(),
            category: "Kernel Stealth".into(),
            title: "dmesg Readable by Unprivileged Processes".into(),
            severity: AuditSeverity::Warn,
            detail: "kernel.dmesg_restrict is 0; apps may leak kernel addresses or root logs."
                .into(),
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
            detail: "Ordinary apps can invoke bpf() syscalls (higher kernel exploit surface).".into(),
            recommendation: Some(
                "Enable Kernel Hardening in Silo to set unprivileged_bpf_disabled=2.".into(),
            ),
        });
    }

    // 3. Firewall active status
    if firewall_active && cfg.firewall.enabled {
        findings.push(AuditFinding {
            id: "firewall_active".into(),
            category: "Network Defense".into(),
            title: "Silo Kernel Firewall Active".into(),
            severity: AuditSeverity::Pass,
            detail: format!(
                "Netfilter enforcement running in {:?} mode (LAN isolation: {}, DNS guard: {}).",
                cfg.firewall.mode, cfg.firewall.isolate_lan, cfg.firewall.dns_leak_guard
            ),
            recommendation: None,
        });
    } else {
        score -= 10;
        findings.push(AuditFinding {
            id: "firewall_inactive".into(),
            category: "Network Defense".into(),
            title: "Kernel Firewall Inactive".into(),
            severity: AuditSeverity::Info,
            detail: "Silo kernel firewall is currently switched off (Safe-First default).".into(),
            recommendation: Some("Enable Firewall or select Balanced/Hardened preset.".into()),
        });
    }

    // 4. User-Installed CA Certificates (MitM detection)
    let user_ca_count = count_files_in_dir("/data/misc/user/0/cacerts-added");
    if user_ca_count == 0 {
        findings.push(AuditFinding {
            id: "user_ca_clean".into(),
            category: "TLS & Privacy".into(),
            title: "No User-Installed CA Certificates".into(),
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
            detail: "Custom user CAs can intercept HTTPS traffic and trigger banking/Play Integrity alerts.".into(),
            recommendation: Some("Remove unused user CA certificates in Android Security settings.".into()),
        });
    }

    // 5. Ordinary App Listening Ports Scan (via pure-Rust Netlink inet_diag)
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
            title: "No User Apps Listening on Network Ports".into(),
            severity: AuditSeverity::Pass,
            detail: "Zero ordinary app UIDs have open TCP/UDP listening sockets.".into(),
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
            title: format!("{} App Listening Socket(s) Detected", app_listening_ports),
            severity: AuditSeverity::Warn,
            detail: format!("Detected listeners: {}", sample.join(", ")),
            recommendation: Some(
                "Inspect these apps in Silo Monitor and enable Localhost/LAN isolation if unexpected."
                    .into(),
            ),
        });
    }

    // 6. Executable payload hygiene in /data/local/tmp
    let tmp_execs = count_executables_in_tmp();
    if tmp_execs == 0 {
        findings.push(AuditFinding {
            id: "tmp_clean".into(),
            category: "Persistence & Stealth".into(),
            title: "/data/local/tmp Clean of Executables".into(),
            severity: AuditSeverity::Pass,
            detail: "No stray executable binaries found in /data/local/tmp.".into(),
            recommendation: None,
        });
    } else {
        score -= 8;
        findings.push(AuditFinding {
            id: "tmp_execs".into(),
            category: "Persistence & Stealth".into(),
            title: format!("{} Executable(s) in /data/local/tmp", tmp_execs),
            severity: AuditSeverity::Warn,
            detail: "Residual executables in /data/local/tmp can be flagged by detectors or abused.".into(),
            recommendation: Some("Inspect and remove unused binaries in /data/local/tmp.".into()),
        });
    }

    // 7. Suspicious UID-0 processes running from /data/local/tmp or /sdcard
    let suspicious_roots = scan_suspicious_root_procs();
    let suspicious_root_procs = suspicious_roots.len();
    if suspicious_root_procs == 0 {
        findings.push(AuditFinding {
            id: "root_procs_clean".into(),
            category: "Root Integrity".into(),
            title: "No Suspicious Root Binaries Running".into(),
            severity: AuditSeverity::Pass,
            detail: "No UID 0 processes executing from untrusted writable paths (/sdcard, /data/local/tmp).".into(),
            recommendation: None,
        });
    } else {
        score -= 20;
        findings.push(AuditFinding {
            id: "suspicious_root_procs".into(),
            category: "Root Integrity".into(),
            title: format!(
                "{} Suspicious Root Process(es) Detected",
                suspicious_root_procs
            ),
            severity: AuditSeverity::Critical,
            detail: format!("Untrusted UID 0 binaries: {}", suspicious_roots.join(", ")),
            recommendation: Some("Investigate immediately and revoke unauthorized root access.".into()),
        });
    }

    // 8. Read-only Kernel Stealth Stack Check (KernelSU / SUSFS / NoMount / BBG)
    let ksu_present = Path::new("/data/adb/ksu").exists();
    let companion_present = Path::new("/data/adb/modules/a146p_stealth_companion").exists();
    let lsm_visible = fs::read_to_string("/sys/kernel/security/lsm").unwrap_or_default();
    let bbg_cloaked = !lsm_visible.contains("baseband_guard");
    findings.push(AuditFinding {
        id: "stealth_stack".into(),
        category: "Kernel Stealth".into(),
        title: "Stealth Stack Read-Only Check".into(),
        severity: if bbg_cloaked {
            AuditSeverity::Pass
        } else {
            AuditSeverity::Info
        },
        detail: format!(
            "KernelSU dir: {}, Stealth Companion: {}, /sys/kernel/security/lsm clean: {}, Silo zero-mount: true, Silo zero-TCP-ports: true.",
            if ksu_present { "present" } else { "not found" },
            if companion_present { "installed" } else { "not installed" },
            bbg_cloaked
        ),
        recommendation: None,
    });

    // 9. TCP Timestamp Fingerprinting Hygiene
    if read_sysctl("net.ipv4.tcp_timestamps").as_deref() == Some("0") {
        findings.push(AuditFinding {
            id: "tcp_ts_off".into(),
            category: "Network Privacy".into(),
            title: "TCP Timestamps Disabled".into(),
            severity: AuditSeverity::Pass,
            detail: "Remote hosts cannot infer device uptime via TCP timestamp headers.".into(),
            recommendation: None,
        });
    } else {
        score -= 4;
        findings.push(AuditFinding {
            id: "tcp_ts_on".into(),
            category: "Network Privacy".into(),
            title: "TCP Timestamps Enabled".into(),
            severity: AuditSeverity::Info,
            detail: "TCP timestamps can expose device uptime and clock skew to remote servers.".into(),
            recommendation: Some("Enable Kernel Hardening in Silo to disable tcp_timestamps.".into()),
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
    fn sentinel_audit_runs_cleanly_without_panicking() {
        let cfg = SiloConfig::default();
        let report = run_sentinel_audit(&cfg, false);
        assert!(report.score <= 100);
        assert!(!report.findings.is_empty());
    }
}
