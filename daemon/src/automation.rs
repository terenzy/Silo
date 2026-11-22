use crate::config::{AutomationConfig, MIN_AUTO_REBOOT_MINUTES};
use crate::protocol::ActiveInterfaces;
use crate::sys::{boottime_secs, resolve_bin, run_cmd};

pub const PANIC_REBOOT_CONFIRM_TOKEN: &str = "CONFIRM_SILO_BFU_REBOOT";

/// Manages GrapheneOS-inspired runtime privacy automation:
/// 1. Auto-Reboot to BFU (CLOCK_BOOTTIME monotonic lock timer, CE-unlock aware, >=15 min safety floor)
/// 2. Wi-Fi & Bluetooth idle auto-off
/// 3. Global Camera/Mic Hardware Sensors Kill-Switch (`cmd sensor_privacy`)
#[derive(Debug, Default)]
pub struct AutomationEngine {
    locked_since_boottime: Option<u64>,
    wifi_idle_since: Option<u64>,
    bt_idle_since: Option<u64>,
    sensors_off_manual: bool,
    sensors_off_by_lock: bool,
}

impl AutomationEngine {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn sensors_off_active(&self) -> bool {
        self.sensors_off_manual || self.sensors_off_by_lock
    }

    pub fn set_sensors_off_manual(&mut self, enabled: bool) -> Result<(), String> {
        apply_sensor_privacy(enabled)?;
        self.sensors_off_manual = enabled;
        if !enabled {
            self.sensors_off_by_lock = false;
        }
        Ok(())
    }

    /// Computes remaining seconds before BFU auto-reboot, if armed.
    pub fn auto_reboot_remaining_secs(
        &self,
        cfg: &AutomationConfig,
        ce_unlocked: bool,
        device_locked: bool,
    ) -> Option<u64> {
        if !cfg.auto_reboot_enabled || !ce_unlocked || !device_locked {
            return None;
        }
        let locked_since = self.locked_since_boottime?;
        let threshold_secs = u64::from(cfg.auto_reboot_minutes.max(MIN_AUTO_REBOOT_MINUTES)) * 60;
        let elapsed = boottime_secs().saturating_sub(locked_since);
        Some(threshold_secs.saturating_sub(elapsed))
    }

    /// Periodic tick called by daemon supervisor. Returns `true` if BFU auto-reboot threshold was reached.
    pub fn tick(
        &mut self,
        cfg: &AutomationConfig,
        ce_unlocked: bool,
        device_locked: bool,
        ifaces: &ActiveInterfaces,
    ) -> bool {
        let now = boottime_secs();

        // 1. Lockscreen Sensors Kill-Switch
        if cfg.sensors_off_on_lock {
            if device_locked && !self.sensors_off_by_lock && !self.sensors_off_manual {
                if apply_sensor_privacy(true).is_ok() {
                    self.sensors_off_by_lock = true;
                }
            } else if !device_locked && self.sensors_off_by_lock {
                if !self.sensors_off_manual {
                    let _ = apply_sensor_privacy(false);
                }
                self.sensors_off_by_lock = false;
            }
        } else if self.sensors_off_by_lock {
            if !self.sensors_off_manual {
                let _ = apply_sensor_privacy(false);
            }
            self.sensors_off_by_lock = false;
        }

        // 2. Wi-Fi Auto-Off when enabled but disconnected
        if cfg.wifi_auto_off_enabled {
            if ifaces.wifi.is_empty() && is_wifi_radio_enabled() {
                let since = *self.wifi_idle_since.get_or_insert(now);
                if now.saturating_sub(since) >= u64::from(cfg.wifi_auto_off_minutes) * 60 {
                    let svc = resolve_bin("svc");
                    let _ = run_cmd(&svc, &["wifi", "disable"], None, 3000);
                    self.wifi_idle_since = None;
                }
            } else {
                self.wifi_idle_since = None;
            }
        } else {
            self.wifi_idle_since = None;
        }

        // 3. Bluetooth Auto-Off when enabled but no active profile connection
        if cfg.bluetooth_auto_off_enabled {
            if is_bt_enabled_and_idle() {
                let since = *self.bt_idle_since.get_or_insert(now);
                if now.saturating_sub(since) >= u64::from(cfg.bluetooth_auto_off_minutes) * 60 {
                    let svc = resolve_bin("svc");
                    let _ = run_cmd(&svc, &["bluetooth", "disable"], None, 3000);
                    self.bt_idle_since = None;
                }
            } else {
                self.bt_idle_since = None;
            }
        } else {
            self.bt_idle_since = None;
        }

        // 4. GrapheneOS-inspired Auto-Reboot to BFU Timer
        // CRITICAL INVARIANT: Only arm when CE storage is unlocked (prevents rebooting while already in BFU!)
        // and device is currently locked, with hard floor >= 15 minutes.
        if cfg.auto_reboot_enabled && ce_unlocked && device_locked {
            let locked_since = *self.locked_since_boottime.get_or_insert(now);
            let threshold_secs =
                u64::from(cfg.auto_reboot_minutes.max(MIN_AUTO_REBOOT_MINUTES)) * 60;
            if now.saturating_sub(locked_since) >= threshold_secs {
                return true;
            }
        } else {
            self.locked_since_boottime = None;
        }

        false
    }
}

/// Official Android 12-16 `cmd sensor_privacy` toggle for global hardware microphone & camera mute.
/// Never touches undocumented settings keys.
pub fn apply_sensor_privacy(enable_privacy_off: bool) -> Result<(), String> {
    let cmd_bin = resolve_bin("cmd");
    let action = if enable_privacy_off {
        "enable"
    } else {
        "disable"
    };
    let r_mic = run_cmd(&cmd_bin, &["sensor_privacy", action, "0", "microphone"], None, 2500);
    let r_cam = run_cmd(&cmd_bin, &["sensor_privacy", action, "0", "camera"], None, 2500);

    match (r_mic, r_cam) {
        (Ok(m), Ok(c)) if m.success() || c.success() => Ok(()),
        (Err(e), _) => Err(format!("sensor_privacy exec error: {}", e)),
        (_, Err(e)) => Err(format!("sensor_privacy exec error: {}", e)),
        (Ok(m), Ok(c)) => Err(format!(
            "sensor_privacy failed: mic={} cam={}",
            m.stderr.trim(),
            c.stderr.trim()
        )),
    }
}

/// Executes a clean Android reboot back into BFU (Before First Unlock) encrypted state.
pub fn execute_clean_bfu_reboot() -> Result<(), String> {
    let svc = resolve_bin("svc");
    if let Ok(out) = run_cmd(&svc, &["power", "reboot"], None, 5000) {
        if out.success() {
            return Ok(());
        }
    }
    let reboot = resolve_bin("reboot");
    let out = run_cmd(&reboot, &[], None, 5000)
        .map_err(|e| format!("reboot spawn failed: {}", e))?;
    if out.success() {
        Ok(())
    } else {
        Err(format!("reboot failed: {}", out.stderr.trim()))
    }
}

fn is_wifi_radio_enabled() -> bool {
    let settings = resolve_bin("settings");
    run_cmd(&settings, &["get", "global", "wifi_on"], None, 1500)
        .map(|o| o.success() && o.stdout.trim() == "1")
        .unwrap_or(false)
}

fn is_bt_enabled_and_idle() -> bool {
    let settings = resolve_bin("settings");
    let on = run_cmd(&settings, &["get", "global", "bluetooth_on"], None, 1500)
        .map(|o| o.success() && o.stdout.trim() == "1")
        .unwrap_or(false);
    if !on {
        return false;
    }
    let dumpsys = resolve_bin("dumpsys");
    if let Ok(out) = run_cmd(&dumpsys, &["bluetooth_manager"], None, 2000) {
        for line in out.stdout.lines() {
            let t = line.trim();
            if t.contains("ConnectionState: STATE_CONNECTED")
                || t.contains("mConnectionState=2")
                || t.contains("Connected devices:") && !t.ends_with("0")
            {
                return false;
            }
        }
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bfu_timer_never_arms_when_already_in_bfu_or_unlocked() {
        let mut eng = AutomationEngine::new();
        let cfg = AutomationConfig {
            auto_reboot_enabled: true,
            auto_reboot_minutes: 15,
            ..Default::default()
        };
        let ifaces = ActiveInterfaces::default();

        // Already in BFU (ce_unlocked = false): must NOT arm!
        assert!(!eng.tick(&cfg, false, true, &ifaces));
        assert!(eng.auto_reboot_remaining_secs(&cfg, false, true).is_none());

        // Unlocked (device_locked = false): must NOT arm!
        assert!(!eng.tick(&cfg, true, false, &ifaces));
        assert!(eng.auto_reboot_remaining_secs(&cfg, true, false).is_none());

        // Locked after CE unlock: arms with ~900s remaining
        assert!(!eng.tick(&cfg, true, true, &ifaces));
        let rem = eng.auto_reboot_remaining_secs(&cfg, true, true).unwrap();
        assert!((895..=900).contains(&rem));

        // Unlocking immediately clears countdown
        assert!(!eng.tick(&cfg, true, false, &ifaces));
        assert!(eng.locked_since_boottime.is_none());
    }
}
