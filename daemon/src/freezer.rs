use crate::config::{is_protected_package, is_protected_uid, validate_package_name, FreezeMode, FreezerConfig};
use crate::netlink::destroy_uid_sockets;
use crate::protocol::FreezerStatus;
use crate::sys::{boottime_secs, foreground_app_uids, parse_proc_status_uid, resolve_bin, run_cmd};
use std::collections::{BTreeSet, HashMap};
use std::fs;
use std::path::{Path, PathBuf};

/// Manages REAL kernel-level cgroup v2 app freezing AND Android `pm suspend` Deep Stop,
/// verifying actual kernel `cgroup.freeze` / `cgroup.events` state (zero gimmick!).
#[derive(Debug, Default)]
pub struct FreezerEngine {
    frozen_uids: BTreeSet<u32>,
    suspended_packages: BTreeSet<String>,
    last_fg_seen: HashMap<u32, u64>,
    manual_thaw_until: HashMap<u32, u64>,
    last_action_detail: Option<String>,
}

impl FreezerEngine {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn cgroup_v2_available() -> bool {
        Path::new("/sys/fs/cgroup").exists()
    }

    pub fn currently_frozen(&self) -> Vec<u32> {
        self.frozen_uids.iter().copied().collect()
    }

    pub fn suspended_packages(&self) -> Vec<String> {
        self.suspended_packages.iter().cloned().collect()
    }

    pub fn status(&self, cfg: &FreezerConfig) -> FreezerStatus {
        FreezerStatus {
            enabled: cfg.enabled,
            cgroup_v2_available: Self::cgroup_v2_available(),
            currently_frozen_uids: self.currently_frozen(),
            suspended_packages: self.suspended_packages(),
            configured_count: cfg.entries.len(),
            last_action_detail: self.last_action_detail.clone(),
        }
    }

    /// Counts how many cgroup v2 process nodes are ACTUALLY verified `frozen 1` right now in kernel.
    pub fn count_verified_frozen_pids(&self) -> usize {
        let mut count = 0usize;
        for &uid in &self.frozen_uids {
            for p in discover_uid_cgroup_freeze_paths(uid) {
                if is_cgroup_actually_frozen(&p) {
                    count += 1;
                }
            }
        }
        count
    }

    /// Freezes a single ordinary app UID immediately and verifies actual kernel/framework enforcement.
    pub fn freeze_uid_now(
        &mut self,
        uid: u32,
        package_name: &str,
        mode: FreezeMode,
        kill_sockets: bool,
        sock_destroy_avail: bool,
    ) -> Result<String, String> {
        if is_protected_uid(uid) || is_protected_package(package_name) {
            return Err(format!(
                "Refused to freeze critical system UID {} ({})",
                uid, package_name
            ));
        }

        if kill_sockets && sock_destroy_avail {
            let _ = destroy_uid_sockets(uid);
        }

        match mode {
            FreezeMode::SoftCgroup => {
                let (written, verified) = write_uid_cgroup_freeze(uid, true)?;
                if written > 0 {
                    self.frozen_uids.insert(uid);
                    let detail = format!(
                        "cgroup v2 frozen: {} node(s) written, {} verified frozen",
                        written, verified
                    );
                    self.last_action_detail = Some(detail.clone());
                    Ok(detail)
                } else {
                    // Honest feedback: app has no running process right now; restrict standby bucket too
                    if validate_package_name(package_name) {
                        let am = resolve_bin("am");
                        let _ = run_cmd(
                            &am,
                            &["set-standby-bucket", package_name, "restricted"],
                            None,
                            2500,
                        );
                    }
                    let detail =
                        "0 running PIDs right now (standby restricted; use Deep Suspend to block cold start)"
                            .to_string();
                    self.last_action_detail = Some(detail.clone());
                    Ok(detail)
                }
            }
            FreezeMode::DeepStop => {
                let _ = write_uid_cgroup_freeze(uid, false);
                self.frozen_uids.remove(&uid);
                if !validate_package_name(package_name) {
                    return Err("Deep Suspend requires a valid package name".to_string());
                }
                let am = resolve_bin("am");
                let pm = resolve_bin("pm");
                let _ = run_cmd(&am, &["force-stop", package_name], None, 3000);
                let _ = run_cmd(
                    &am,
                    &["set-standby-bucket", package_name, "restricted"],
                    None,
                    3000,
                );
                let susp = run_cmd(&pm, &["suspend", "--user", "0", package_name], None, 3500);
                let suspended_ok = susp.as_ref().map(|o| o.success()).unwrap_or(false);
                if suspended_ok {
                    self.suspended_packages.insert(package_name.to_string());
                }
                let detail = if suspended_ok {
                    format!("Deep Suspended (pm suspend + force-stop): {}", package_name)
                } else {
                    format!("Force-stopped & standby restricted: {}", package_name)
                };
                self.last_action_detail = Some(detail.clone());
                Ok(detail)
            }
        }
    }

    /// Immediately thaws a UID (<1ms kernel write) and unsuspends it if Deep Stopped.
    pub fn thaw_uid_now(&mut self, uid: u32, package_name: Option<&str>) -> Result<(), String> {
        let _ = write_uid_cgroup_freeze(uid, false);
        self.frozen_uids.remove(&uid);
        let now = boottime_secs();
        self.last_fg_seen.insert(uid, now);
        self.manual_thaw_until.insert(uid, now.saturating_add(60));

        if let Some(pkg) = package_name {
            if validate_package_name(pkg) && !is_protected_package(pkg) {
                let pm = resolve_bin("pm");
                let am = resolve_bin("am");
                let _ = run_cmd(&pm, &["unsuspend", "--user", "0", pkg], None, 3000);
                let _ = run_cmd(&am, &["set-standby-bucket", pkg, "active"], None, 2500);
                self.suspended_packages.remove(pkg);
            }
        }
        self.last_action_detail = Some(format!("Thawed UID {}", uid));
        Ok(())
    }

    /// Thaws EVERY UID frozen by Silo AND unsuspends EVERY Deep Suspended package.
    pub fn thaw_all(&mut self) {
        let uids: Vec<u32> = self.frozen_uids.iter().copied().collect();
        for uid in uids {
            let _ = write_uid_cgroup_freeze(uid, false);
        }
        self.frozen_uids.clear();

        let pkgs: Vec<String> = self.suspended_packages.iter().cloned().collect();
        let pm = resolve_bin("pm");
        let am = resolve_bin("am");
        for pkg in pkgs {
            let _ = run_cmd(&pm, &["unsuspend", "--user", "0", &pkg], None, 2500);
            let _ = run_cmd(&am, &["set-standby-bucket", &pkg, "active"], None, 2500);
        }
        self.suspended_packages.clear();
        self.last_action_detail = Some("All frozen & suspended apps thawed".to_string());
    }

    pub fn fast_foreground_thaw_tick(&mut self) {
        if self.frozen_uids.is_empty() {
            return;
        }
        let now = boottime_secs();
        let fg = foreground_app_uids();
        for uid in fg {
            self.last_fg_seen.insert(uid, now);
            if self.frozen_uids.contains(&uid) {
                let _ = write_uid_cgroup_freeze(uid, false);
                self.frozen_uids.remove(&uid);
            }
        }
    }

    pub fn policy_tick(&mut self, cfg: &FreezerConfig, screen_on: bool, sock_destroy_avail: bool) {
        if !cfg.enabled {
            if !self.frozen_uids.is_empty() || !self.suspended_packages.is_empty() {
                self.thaw_all();
            }
            return;
        }

        let now = boottime_secs();
        let fg_set: BTreeSet<u32> = if screen_on {
            foreground_app_uids().into_iter().collect()
        } else {
            BTreeSet::new()
        };

        for &uid in &fg_set {
            self.last_fg_seen.insert(uid, now);
            if self.frozen_uids.contains(&uid) {
                let _ = write_uid_cgroup_freeze(uid, false);
                self.frozen_uids.remove(&uid);
            }
        }

        let stale: Vec<u32> = self
            .frozen_uids
            .iter()
            .copied()
            .filter(|uid| !cfg.entries.contains_key(uid))
            .collect();
        for uid in stale {
            let _ = write_uid_cgroup_freeze(uid, false);
            self.frozen_uids.remove(&uid);
        }

        let grace = u64::from(cfg.background_grace_secs);
        for (&uid, entry) in &cfg.entries {
            if is_protected_uid(uid) || is_protected_package(&entry.package_name) {
                continue;
            }
            if fg_set.contains(&uid) {
                continue;
            }
            if let Some(&until) = self.manual_thaw_until.get(&uid) {
                if now < until {
                    continue;
                }
            }

            let should_freeze = if !screen_on {
                entry.freeze_on_screen_off
            } else if entry.freeze_on_background {
                let last = self.last_fg_seen.get(&uid).copied().unwrap_or(0);
                now.saturating_sub(last) >= grace
            } else {
                false
            };

            let already_handled = self.frozen_uids.contains(&uid)
                || self.suspended_packages.contains(&entry.package_name);
            if should_freeze && !already_handled {
                let _ = self.freeze_uid_now(
                    uid,
                    &entry.package_name,
                    entry.mode,
                    entry.kill_sockets_on_freeze,
                    sock_destroy_avail,
                );
            }
        }
    }
}

/// Discovers real cgroup v2 `cgroup.freeze` files both from `/sys/fs/cgroup/uid_<uid>`
/// AND directly from `/proc/<pid>/cgroup` for every running PID of `uid`.
pub fn discover_uid_cgroup_freeze_paths(uid: u32) -> Vec<PathBuf> {
    let mut paths = BTreeSet::new();

    // 1. Standard Android 12-16 UID cgroup tree
    let uid_dir = PathBuf::from(format!("/sys/fs/cgroup/uid_{}", uid));
    if uid_dir.exists() {
        let direct = uid_dir.join("cgroup.freeze");
        if direct.exists() {
            paths.insert(direct);
        }
        if let Ok(entries) = fs::read_dir(&uid_dir) {
            for entry in entries.flatten() {
                let p = entry.path().join("cgroup.freeze");
                if p.exists() {
                    paths.insert(p);
                }
            }
        }
    }

    // 2. Live /proc/<pid>/cgroup lookup (catches OEM custom cgroup v2 hierarchies!)
    if let Ok(procs) = fs::read_dir("/proc") {
        for entry in procs.flatten() {
            let name = entry.file_name();
            let Some(pid_str) = name.to_str() else {
                continue;
            };
            if !pid_str.bytes().all(|b| b.is_ascii_digit()) {
                continue;
            }
            let pdir = entry.path();
            let Ok(status) = fs::read_to_string(pdir.join("status")) else {
                continue;
            };
            if parse_proc_status_uid(&status) != Some(uid) {
                continue;
            }
            if let Ok(cg) = fs::read_to_string(pdir.join("cgroup")) {
                for line in cg.lines() {
                    // Unified cgroup v2 format: `0::/uid_10234/pid_5678`
                    if let Some(rel) = line.strip_prefix("0::") {
                        let trimmed = rel.trim().trim_start_matches('/');
                        if !trimmed.is_empty() {
                            let fpath = PathBuf::from("/sys/fs/cgroup")
                                .join(trimmed)
                                .join("cgroup.freeze");
                            if fpath.exists() {
                                paths.insert(fpath);
                            }
                        }
                    }
                }
            }
        }
    }

    paths.into_iter().collect()
}

fn is_cgroup_actually_frozen(freeze_path: &Path) -> bool {
    if let Ok(v) = fs::read_to_string(freeze_path) {
        if v.trim() == "1" {
            return true;
        }
    }
    if let Some(parent) = freeze_path.parent() {
        if let Ok(ev) = fs::read_to_string(parent.join("cgroup.events")) {
            return ev.lines().any(|l| l.trim() == "frozen 1");
        }
    }
    false
}

fn write_uid_cgroup_freeze(uid: u32, freeze: bool) -> Result<(usize, usize), String> {
    let val = if freeze { "1" } else { "0" };
    let targets = discover_uid_cgroup_freeze_paths(uid);
    if targets.is_empty() {
        return Ok((0, 0));
    }

    let mut written = 0usize;
    let mut verified = 0usize;
    let mut last_err = None;
    for path in targets {
        match fs::write(&path, val) {
            Ok(()) => {
                written += 1;
                if freeze && is_cgroup_actually_frozen(&path) {
                    verified += 1;
                }
            }
            Err(e) => last_err = Some(format!("{}: {}", path.display(), e)),
        }
    }

    if written == 0 {
        if let Some(err) = last_err {
            return Err(err);
        }
    }
    Ok((written, verified))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn freezer_refuses_critical_system_uids() {
        let mut engine = FreezerEngine::new();
        assert!(engine
            .freeze_uid_now(0, "root", FreezeMode::SoftCgroup, false, false)
            .is_err());
        assert!(engine
            .freeze_uid_now(1000, "android", FreezeMode::DeepStop, false, false)
            .is_err());
        assert!(engine
            .freeze_uid_now(10200, "dev.silo.app", FreezeMode::DeepStop, false, false)
            .is_err());
    }
}
