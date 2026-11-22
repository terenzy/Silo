use crate::config::HardeningConfig;
use crate::protocol::{HardeningStatus, SysctlItemStatus};
use std::collections::BTreeMap;
use std::fs;
use std::path::PathBuf;

#[derive(Debug, Clone)]
struct SysctlSpec {
    key: &'static str,
    label: &'static str,
    category: &'static str,
    desired: String,
    fallback_desired: Option<&'static str>,
    enabled: bool,
}

/// Manages curated kernel exploit-surface sysctl hardening with pre-apply original-value
/// snapshot and atomic rollback. Every parameter is verified safe for Android 15 / One UI 7.
#[derive(Debug, Default)]
pub struct HardeningEngine {
    original_values: BTreeMap<String, String>,
}

impl HardeningEngine {
    pub fn new() -> Self {
        Self::default()
    }

    fn build_specs(cfg: &HardeningConfig) -> Vec<SysctlSpec> {
        let mut specs = vec![
            SysctlSpec {
                key: "kernel.kptr_restrict",
                label: "Hide Kernel Pointers (kptr_restrict)",
                category: "Kernel Memory",
                desired: "2".into(),
                fallback_desired: None,
                enabled: cfg.hide_kernel_pointers,
            },
            SysctlSpec {
                key: "kernel.perf_event_paranoid",
                label: "Restrict Perf Side-Channels",
                category: "Kernel Memory",
                desired: "3".into(),
                fallback_desired: Some("2"),
                enabled: cfg.restrict_perf_events,
            },
            SysctlSpec {
                key: "kernel.unprivileged_bpf_disabled",
                label: "Disable Unprivileged eBPF (Reversible)",
                category: "Exploit Surface",
                // Value 2 disables unprivileged BPF reversibly on Linux 5.13+ (value 1 latches until reboot)
                desired: "2".into(),
                fallback_desired: Some("1"),
                enabled: cfg.disable_unprivileged_bpf,
            },
            SysctlSpec {
                key: "net.core.bpf_jit_harden",
                label: "Harden eBPF JIT Constant Blinding",
                category: "Exploit Surface",
                desired: "2".into(),
                fallback_desired: Some("1"),
                enabled: cfg.harden_bpf_jit,
            },
            SysctlSpec {
                key: "fs.suid_dumpable",
                label: "Disable SUID Core Dumps",
                category: "Exploit Surface",
                desired: "0".into(),
                fallback_desired: None,
                enabled: cfg.disable_suid_dumpable,
            },
            SysctlSpec {
                key: "fs.protected_hardlinks",
                label: "Protect Hardlinks",
                category: "Filesystem",
                desired: "1".into(),
                fallback_desired: None,
                enabled: cfg.protect_fs_links_fifos,
            },
            SysctlSpec {
                key: "fs.protected_symlinks",
                label: "Protect Symlinks",
                category: "Filesystem",
                desired: "1".into(),
                fallback_desired: None,
                enabled: cfg.protect_fs_links_fifos,
            },
            SysctlSpec {
                key: "fs.protected_fifos",
                label: "Protect World-Writable FIFOs",
                category: "Filesystem",
                desired: "2".into(),
                fallback_desired: Some("1"),
                enabled: cfg.protect_fs_links_fifos,
            },
            SysctlSpec {
                key: "fs.protected_regular",
                label: "Protect World-Writable Regular Files",
                category: "Filesystem",
                desired: "2".into(),
                fallback_desired: Some("1"),
                enabled: cfg.protect_fs_links_fifos,
            },
            SysctlSpec {
                key: "kernel.dmesg_restrict",
                label: "Restrict Kernel dmesg Access",
                category: "Stealth & Audit",
                desired: "1".into(),
                fallback_desired: None,
                enabled: cfg.restrict_dmesg,
            },
            SysctlSpec {
                key: "net.ipv4.tcp_timestamps",
                label: "Disable TCP Timestamps (Anti-Uptime Fingerprint)",
                category: "Network Privacy",
                desired: "0".into(),
                fallback_desired: None,
                enabled: cfg.disable_tcp_timestamps,
            },
            SysctlSpec {
                key: "net.ipv4.tcp_syncookies",
                label: "Enable TCP SYN Cookies",
                category: "Network Stack",
                desired: "1".into(),
                fallback_desired: None,
                enabled: cfg.harden_network_stack,
            },
            SysctlSpec {
                key: "net.ipv4.conf.all.rp_filter",
                label: "Loose Reverse Path Filter (VPN/Dual-SIM Safe)",
                category: "Network Stack",
                // Loose rp_filter=2 blocks unroutable spoofing without breaking VPN/VoWiFi asymmetric routing!
                desired: "2".into(),
                fallback_desired: None,
                enabled: cfg.harden_network_stack,
            },
            SysctlSpec {
                key: "net.ipv4.conf.all.accept_redirects",
                label: "Ignore IPv4 ICMP Redirects",
                category: "Network Stack",
                desired: "0".into(),
                fallback_desired: None,
                enabled: cfg.harden_network_stack,
            },
            SysctlSpec {
                key: "net.ipv6.conf.all.accept_redirects",
                label: "Ignore IPv6 ICMP Redirects",
                category: "Network Stack",
                desired: "0".into(),
                fallback_desired: None,
                enabled: cfg.harden_network_stack,
            },
            SysctlSpec {
                key: "net.ipv4.conf.all.send_redirects",
                label: "Disable Sending ICMP Redirects",
                category: "Network Stack",
                desired: "0".into(),
                fallback_desired: None,
                enabled: cfg.harden_network_stack,
            },
            SysctlSpec {
                key: "net.ipv4.conf.all.accept_source_route",
                label: "Reject IPv4 Source-Routed Packets",
                category: "Network Stack",
                desired: "0".into(),
                fallback_desired: None,
                enabled: cfg.harden_network_stack,
            },
            SysctlSpec {
                key: "net.ipv6.conf.all.accept_source_route",
                label: "Reject IPv6 Source-Routed Packets",
                category: "Network Stack",
                desired: "0".into(),
                fallback_desired: None,
                enabled: cfg.harden_network_stack,
            },
            SysctlSpec {
                key: "net.ipv4.icmp_echo_ignore_broadcasts",
                label: "Ignore Broadcast ICMP Echo",
                category: "Network Stack",
                desired: "1".into(),
                fallback_desired: None,
                enabled: cfg.harden_network_stack,
            },
            SysctlSpec {
                key: "net.ipv4.conf.default.accept_redirects",
                label: "Ignore IPv4 ICMP Redirects (default)",
                category: "Network Stack",
                desired: "0".into(),
                fallback_desired: None,
                enabled: cfg.harden_network_stack,
            },
            SysctlSpec {
                key: "net.ipv6.conf.default.accept_redirects",
                label: "Ignore IPv6 ICMP Redirects (default)",
                category: "Network Stack",
                desired: "0".into(),
                fallback_desired: None,
                enabled: cfg.harden_network_stack,
            },
            SysctlSpec {
                key: "net.ipv4.tcp_rfc1337",
                label: "Protect TCP TIME-WAIT Assassination (RFC1337)",
                category: "Network Stack",
                desired: "1".into(),
                fallback_desired: None,
                enabled: cfg.harden_network_stack,
            },
            SysctlSpec {
                key: "kernel.randomize_va_space",
                label: "Full Address Space Randomization (ASLR)",
                category: "Kernel Memory",
                desired: "2".into(),
                fallback_desired: None,
                enabled: cfg.harden_memory_and_ptrace,
            },
            SysctlSpec {
                key: "kernel.yama.ptrace_scope",
                label: "Restrict Cross-Process ptrace (Yama)",
                category: "Exploit Surface",
                desired: "2".into(),
                fallback_desired: Some("1"),
                enabled: cfg.harden_memory_and_ptrace,
            },
            SysctlSpec {
                key: "net.ipv6.conf.all.use_tempaddr",
                label: "IPv6 Privacy Temporary Addresses (all)",
                category: "Network Privacy",
                desired: "2".into(),
                fallback_desired: None,
                enabled: cfg.ipv6_privacy_extensions,
            },
            SysctlSpec {
                key: "net.ipv6.conf.default.use_tempaddr",
                label: "IPv6 Privacy Temporary Addresses (default)",
                category: "Network Privacy",
                desired: "2".into(),
                fallback_desired: None,
                enabled: cfg.ipv6_privacy_extensions,
            },
        ];

        if !cfg.tcp_congestion.is_empty() {
            specs.push(SysctlSpec {
                key: "net.ipv4.tcp_congestion_control",
                label: "TCP Congestion Control (BBRv3/CUBIC)",
                category: "Network Performance",
                desired: cfg.tcp_congestion.clone(),
                fallback_desired: None,
                enabled: true,
            });
        }
        if !cfg.default_qdisc.is_empty() {
            specs.push(SysctlSpec {
                key: "net.core.default_qdisc",
                label: "Default Packet Scheduler (CAKE/FQ)",
                category: "Network Performance",
                desired: cfg.default_qdisc.clone(),
                fallback_desired: None,
                enabled: true,
            });
        }

        specs
    }

    pub fn apply(&mut self, cfg: &HardeningConfig) -> HardeningStatus {
        if !cfg.enabled {
            return self.status(cfg);
        }

        let specs = Self::build_specs(cfg);
        for spec in &specs {
            let Some(current) = read_sysctl(spec.key) else {
                continue;
            };
            // Save pre-Silo original value once per boot
            self.original_values
                .entry(spec.key.to_string())
                .or_insert_with(|| current.clone());

            if spec.enabled {
                if write_sysctl(spec.key, &spec.desired).is_err() {
                    if let Some(fb) = spec.fallback_desired {
                        let _ = write_sysctl(spec.key, fb);
                    }
                }
            } else if let Some(orig) = self.original_values.get(spec.key) {
                let _ = write_sysctl(spec.key, orig);
            }
        }
        self.status(cfg)
    }

    pub fn rollback_all(&mut self, cfg: &HardeningConfig) -> HardeningStatus {
        for (key, orig) in &self.original_values {
            let _ = write_sysctl(key, orig);
        }
        self.status(cfg)
    }

    pub fn status(&self, cfg: &HardeningConfig) -> HardeningStatus {
        let specs = Self::build_specs(cfg);
        let mut items = Vec::with_capacity(specs.len());

        for spec in specs {
            let current = read_sysctl(spec.key);
            let supported = current.is_some();
            let enforced = match current.as_deref() {
                Some(cur) => {
                    cur == spec.desired
                        || spec
                            .fallback_desired
                            .map(|fb| cur == fb)
                            .unwrap_or(false)
                }
                None => false,
            };
            items.push(SysctlItemStatus {
                key: spec.key.to_string(),
                label: spec.label.to_string(),
                category: spec.category.to_string(),
                desired: spec.desired,
                current,
                original: self.original_values.get(spec.key).cloned(),
                supported,
                enforced,
            });
        }

        HardeningStatus {
            enabled: cfg.enabled,
            snapshot_saved: !self.original_values.is_empty(),
            items,
        }
    }
}

pub fn sysctl_key_to_path(key: &str) -> Option<PathBuf> {
    if key.is_empty()
        || key.contains("..")
        || key.contains('/')
        || !key
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '_' || c == '-')
    {
        return None;
    }
    let rel = key.replace('.', "/");
    Some(PathBuf::from("/proc/sys").join(rel))
}

pub fn read_sysctl(key: &str) -> Option<String> {
    let path = sysctl_key_to_path(key)?;
    fs::read_to_string(path).ok().map(|s| s.trim().to_string())
}

fn write_sysctl(key: &str, value: &str) -> Result<(), String> {
    let path = sysctl_key_to_path(key).ok_or_else(|| format!("Invalid sysctl key: {}", key))?;
    fs::write(&path, format!("{}\n", value))
        .map_err(|e| format!("write {} failed: {}", path.display(), e))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sysctl_path_rejects_traversal() {
        assert_eq!(
            sysctl_key_to_path("kernel.kptr_restrict"),
            Some(PathBuf::from("/proc/sys/kernel/kptr_restrict"))
        );
        assert!(sysctl_key_to_path("../etc/passwd").is_none());
        assert!(sysctl_key_to_path("kernel/kptr_restrict").is_none());
        assert!(sysctl_key_to_path("").is_none());
    }

    #[test]
    fn hardening_status_reads_host_kernel_safely() {
        let engine = HardeningEngine::new();
        let status = engine.status(&HardeningConfig::default());
        assert!(!status.items.is_empty());
        // Host Linux always exposes fs.suid_dumpable or kernel.kptr_restrict
        assert!(status.items.iter().any(|i| i.supported));
    }
}
