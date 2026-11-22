use crate::config::{is_protected_package, is_protected_uid, FreezeMode, FreezerConfig};
use crate::netlink::destroy_uid_sockets;
use crate::protocol::FreezerStatus;
use crate::sys::{boottime_secs, foreground_app_uids, resolve_bin, run_cmd};
use std::collections::{BTreeSet, HashMap};
use std::fs;
use std::path::{Path, PathBuf};

/// Manages kernel-level cgroup v2 app freezing with fast foreground auto-thaw
/// and strict protection for critical system UIDs.
#[derive(Debug, Default)]
pub struct FreezerEngine {
    /// UIDs currently frozen by Silo via cgroup v2 (`cgroup.freeze = 1`).
    frozen_uids: BTreeSet<u32>,
    /// Last monotonic boottime second each tracked UID was observed in foreground (`oom_score_adj <= 100`).
    last_fg_seen: HashMap<u32, u64>,
    /// UIDs temporarily exempted until next background transition after manual thaw.
    manual_thaw_until: HashMap<u32, u64>,
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

    pub fn status(&self, cfg: &FreezerConfig) -> FreezerStatus {
        FreezerStatus {
            enabled: cfg.enabled,
            cgroup_v2_available: Self::cgroup_v2_available(),
            currently_frozen_uids: self.currently_frozen(),
            configured_count: cfg.entries.len(),
        }
    }

    /// Freezes a single ordinary app UID immediately.
    pub fn freeze_uid_now(
        &mut self,
        uid: u32,
        package_name: &str,
        mode: FreezeMode,
        kill_sockets: bool,
        sock_destroy_avail: bool,
    ) -> Result<(), String> {
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
                let touched = write_uid_cgroup_freeze(uid, true)?;
                if touched > 0 {
                    self.frozen_uids.insert(uid);
                }
                Ok(())
            }
            FreezeMode::DeepStop => {
                // Ensure cgroup is thawed before force-stop so binder cleanup completes cleanly
                let _ = write_uid_cgroup_freeze(uid, false);
                self.frozen_uids.remove(&uid);
                if !package_name.is_empty() {
                    let am = resolve_bin("am");
                    let _ = run_cmd(&am, &["force-stop", package_name], None, 3000);
                    let _ = run_cmd(
                        &am,
                        &["set-standby-bucket", package_name, "restricted"],
                        None,
                        3000,
                    );
                }
                Ok(())
            }
        }
    }

    /// Immediately thaws a UID (<1ms kernel write) and grants a 60s grace window.
    pub fn thaw_uid_now(&mut self, uid: u32, package_name: Option<&str>) -> Result<(), String> {
        let _ = write_uid_cgroup_freeze(uid, false);
        self.frozen_uids.remove(&uid);
        let now = boottime_secs();
        self.last_fg_seen.insert(uid, now);
        self.manual_thaw_until.insert(uid, now.saturating_add(60));

        if let Some(pkg) = package_name {
            if !pkg.is_empty() && !is_protected_package(pkg) {
                let am = resolve_bin("am");
                let _ = run_cmd(&am, &["set-standby-bucket", pkg, "active"], None, 2000);
            }
        }
        Ok(())
    }

    /// Thaws EVERY UID frozen by Silo (invoked on disable, shutdown, or emergency recovery).
    pub fn thaw_all(&mut self) {
        let uids: Vec<u32> = self.frozen_uids.iter().copied().collect();
        for uid in uids {
            let _ = write_uid_cgroup_freeze(uid, false);
        }
        self.frozen_uids.clear();
    }

    /// Fast foreground check (~0.3ms via /proc/<pid>/oom_score_adj).
    /// Immediately thaws any soft-frozen UID that has become foreground/visible/perceptible!
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

    /// Periodic policy tick (runs every few seconds when freezer is enabled).
    pub fn policy_tick(
        &mut self,
        cfg: &FreezerConfig,
        screen_on: bool,
        sock_destroy_avail: bool,
    ) {
        if !cfg.enabled {
            if !self.frozen_uids.is_empty() {
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

        // Thaw any UID removed from the configured freezer list
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

            if should_freeze && !self.frozen_uids.contains(&uid) {
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

/// Discovers cgroup v2 `cgroup.freeze` control files for an Android UID under `/sys/fs/cgroup/uid_<uid>`.
/// Prefers per-PID `pid_*/cgroup.freeze` if the UID-level `cgroup.freeze` is absent, avoiding
/// any error when an app currently has no running processes.
fn write_uid_cgroup_freeze(uid: u32, freeze: bool) -> Result<usize, String> {
    let val = if freeze { "1" } else { "0" };
    let uid_dir = PathBuf::from(format!("/sys/fs/cgroup/uid_{}", uid));
    if !uid_dir.exists() {
        return Ok(0); // App has no active processes in cgroup v2 right now
    }

    let mut targets = Vec::new();
    let direct = uid_dir.join("cgroup.freeze");
    if direct.exists() {
        targets.push(direct);
    }

    if let Ok(entries) = fs::read_dir(&uid_dir) {
        for entry in entries.flatten() {
            let p = entry.path().join("cgroup.freeze");
            if p.exists() {
                targets.push(p);
            }
        }
    }

    let mut written = 0usize;
    let mut last_err = None;
    for path in targets {
        match fs::write(&path, val) {
            Ok(()) => written += 1,
            Err(e) => last_err = Some(format!("{}: {}", path.display(), e)),
        }
    }

    if written == 0 {
        if let Some(err) = last_err {
            return Err(err);
        }
    }
    Ok(written)
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
            .freeze_uid_now(1000, "android", FreezeMode::SoftCgroup, false, false)
            .is_err());
        assert!(engine
            .freeze_uid_now(10200, "dev.silo.app", FreezeMode::SoftCgroup, false, false)
            .is_err());
        assert!(engine
            .freeze_uid_now(10201, "com.rifsxd.ksunext", FreezeMode::SoftCgroup, false, false)
            .is_err());
    }
}
