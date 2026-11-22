use crate::config::{validate_hostname, AutomationConfig, MIN_AUTO_REBOOT_MINUTES};
use crate::protocol::{ActiveInterfaces, PrivateDnsState, RadioPrivacyState, SensorPrivacyState};
use crate::sys::{boottime_secs, resolve_bin, run_cmd};

pub const PANIC_REBOOT_CONFIRM_TOKEN: &str = "CONFIRM_SILO_BFU_REBOOT";

#[derive(Debug, Default)]
pub struct AutomationEngine {
    locked_since_boottime: Option<u64>,
    wifi_idle_since: Option<u64>,
    bt_idle_since: Option<u64>,
    sensors_off_by_lock: bool,
    usb_adb_locked_applied: bool,
}

impl AutomationEngine {
    pub fn new() -> Self {
        Self::default()
    }

    /// Reads REAL hardware sensor_privacy state live from Android (`dumpsys sensor_privacy`).
    /// Never uses stale RAM flags that could lie after external permission/Quick-Settings changes.
    pub fn live_sensor_privacy(&self) -> SensorPrivacyState {
        probe_real_sensor_privacy()
    }

    /// Sets hardware microphone, camera, or both via `cmd sensor_privacy` and reads back live state.
    pub fn set_sensor_privacy(
        &mut self,
        sensor: &str,
        enabled: bool,
    ) -> Result<SensorPrivacyState, String> {
        apply_sensor_privacy_target(sensor, enabled)?;
        if !enabled {
            self.sensors_off_by_lock = false;
        }
        Ok(probe_real_sensor_privacy())
    }

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

    pub fn tick(
        &mut self,
        cfg: &AutomationConfig,
        ce_unlocked: bool,
        device_locked: bool,
        ifaces: &ActiveInterfaces,
    ) -> bool {
        let now = boottime_secs();

        // 1. Lockscreen Sensors Kill-Switch (tracks live state without clobbering manual user unblocks)
        if cfg.sensors_off_on_lock {
            if device_locked && !self.sensors_off_by_lock {
                let current = probe_real_sensor_privacy();
                if !(current.mic_muted && current.camera_muted)
                    && apply_sensor_privacy_target("all", true).is_ok()
                {
                    self.sensors_off_by_lock = true;
                }
            } else if !device_locked && self.sensors_off_by_lock {
                let _ = apply_sensor_privacy_target("all", false);
                self.sensors_off_by_lock = false;
            }
        } else if self.sensors_off_by_lock {
            let _ = apply_sensor_privacy_target("all", false);
            self.sensors_off_by_lock = false;
        }

        // 2. GrapheneOS-inspired safe framework USB & ADB Lockscreen Guard.
        // Strictly uses Android framework (`settings` + `svc usb`), NEVER touches hardware USB sysfs or Download Mode.
        // Per user strict choice: stays disabled on unlock until re-enabled manually in Developer Options.
        if cfg.usb_adb_lock_guard && ce_unlocked && device_locked {
            if !self.usb_adb_locked_applied {
                enforce_usb_adb_lockdown();
                self.usb_adb_locked_applied = true;
            }
        } else if !device_locked {
            self.usb_adb_locked_applied = false;
        }

        // 3. Wi-Fi Auto-Off when idle
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

        // 4. Bluetooth Auto-Off when idle
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

        // 5. GrapheneOS-style Auto-Reboot to BFU (CE-unlock aware, CLOCK_BOOTTIME, >=15m floor)
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

/// Safe framework-only USB/ADB lockdown when screen locks (charge-only + ADB off).
/// Never touches bootloader, Odin Download Mode, or kernel USB gadget sysfs.
pub fn enforce_usb_adb_lockdown() {
    let settings = resolve_bin("settings");
    let _ = run_cmd(&settings, &["put", "global", "adb_enabled", "0"], None, 2000);
    let svc = resolve_bin("svc");
    // Android 12-16 `svc usb setFunctions` with no extra args resets USB to charge-only (`none`).
    let res = run_cmd(&svc, &["usb", "setFunctions"], None, 2000);
    if !res.as_ref().map(|o| o.success()).unwrap_or(false) {
        let _ = run_cmd(&svc, &["usb", "setFunctions", "none"], None, 2000);
    }
}

pub fn disable_wifi_and_bt_now() -> (bool, bool) {
    let svc = resolve_bin("svc");
    let wifi_ok = run_cmd(&svc, &["wifi", "disable"], None, 2500)
        .map(|o| o.success())
        .unwrap_or(false);
    let bt_ok = run_cmd(&svc, &["bluetooth", "disable"], None, 2500)
        .map(|o| o.success())
        .unwrap_or(false);
    (wifi_ok, bt_ok)
}

/// Reads actual Android Private DNS (DoT) settings directly from Settings Global.
pub fn get_private_dns() -> PrivateDnsState {
    let mode = read_setting_global("private_dns_mode").unwrap_or_else(|| "opportunistic".into());
    let specifier = read_setting_global("private_dns_specifier").unwrap_or_default();
    PrivateDnsState { mode, specifier }
}

/// Sets Android's native system Private DNS (DoT) cleanly with strict hostname validation.
pub fn set_private_dns(mode: &str, specifier: &str) -> Result<PrivateDnsState, String> {
    let clean_mode = match mode.trim() {
        "off" => "off",
        "opportunistic" => "opportunistic",
        "hostname" => "hostname",
        other => return Err(format!("Unsupported Private DNS mode: {}", other)),
    };

    let settings = resolve_bin("settings");
    if clean_mode == "hostname" {
        let host = specifier.trim();
        if !validate_hostname(host) {
            return Err(format!("Invalid DoT hostname: {}", host));
        }
        let r1 = run_cmd(
            &settings,
            &["put", "global", "private_dns_specifier", host],
            None,
            2500,
        )
        .map_err(|e| format!("settings put specifier failed: {}", e))?;
        if !r1.success() {
            return Err(format!(
                "Failed to set private_dns_specifier: {}",
                r1.stderr.trim()
            ));
        }
    }

    let r2 = run_cmd(
        &settings,
        &["put", "global", "private_dns_mode", clean_mode],
        None,
        2500,
    )
    .map_err(|e| format!("settings put mode failed: {}", e))?;
    if !r2.success() {
        return Err(format!(
            "Failed to set private_dns_mode: {}",
            r2.stderr.trim()
        ));
    }

    Ok(get_private_dns())
}

/// Reads live GrapheneOS-aligned Android Network & Radio privacy settings from `settings get global`.
pub fn get_radio_privacy() -> RadioPrivacyState {
    let mac_conn = read_setting_global("wifi_connected_mac_randomization_enabled").as_deref() == Some("1");
    let mac_enh = read_setting_global("wifi_enhanced_mac_randomization").as_deref() == Some("1");
    let wifi_scan_off = read_setting_global("wifi_scan_always_enabled").as_deref() == Some("0");
    let ble_scan_off = read_setting_global("ble_scan_always_enabled").as_deref() == Some("0");
    let captive_off = read_setting_global("captive_portal_mode").as_deref() == Some("0");
    let adb_enabled_live = read_setting_global("adb_enabled").as_deref() == Some("1");

    RadioPrivacyState {
        enhanced_mac_randomization: mac_conn || mac_enh,
        disable_wifi_ble_scanning: wifi_scan_off && ble_scan_off,
        disable_captive_portal: captive_off,
        adb_enabled_live,
    }
}

/// Writes GrapheneOS-aligned Network & Radio privacy settings via `settings put global` and verifies read-back.
pub fn set_radio_privacy(
    enhanced_mac_randomization: Option<bool>,
    disable_wifi_ble_scanning: Option<bool>,
    disable_captive_portal: Option<bool>,
) -> Result<RadioPrivacyState, String> {
    let settings = resolve_bin("settings");

    if let Some(mac_on) = enhanced_mac_randomization {
        let v = if mac_on { "1" } else { "0" };
        let _ = run_cmd(
            &settings,
            &["put", "global", "wifi_connected_mac_randomization_enabled", v],
            None,
            2000,
        );
        let _ = run_cmd(
            &settings,
            &["put", "global", "wifi_enhanced_mac_randomization", v],
            None,
            2000,
        );
    }

    if let Some(scan_off) = disable_wifi_ble_scanning {
        let v = if scan_off { "0" } else { "1" };
        let _ = run_cmd(
            &settings,
            &["put", "global", "wifi_scan_always_enabled", v],
            None,
            2000,
        );
        let _ = run_cmd(
            &settings,
            &["put", "global", "ble_scan_always_enabled", v],
            None,
            2000,
        );
    }

    if let Some(cap_off) = disable_captive_portal {
        let v = if cap_off { "0" } else { "1" };
        let _ = run_cmd(
            &settings,
            &["put", "global", "captive_portal_mode", v],
            None,
            2000,
        );
        let _ = run_cmd(
            &settings,
            &["put", "global", "captive_portal_detection_enabled", v],
            None,
            2000,
        );
    }

    Ok(get_radio_privacy())
}

/// Official Android 12-16 `cmd sensor_privacy` toggle for microphone, camera, or both.
pub fn apply_sensor_privacy_target(sensor: &str, enable_mute: bool) -> Result<(), String> {
    let cmd_bin = resolve_bin("cmd");
    let action = if enable_mute { "enable" } else { "disable" };

    let targets: &[&str] = match sensor.trim().to_ascii_lowercase().as_str() {
        "microphone" | "mic" => &["microphone"],
        "camera" | "cam" => &["camera"],
        "all" | "both" => &["microphone", "camera"],
        other => return Err(format!("Unsupported sensor target: {}", other)),
    };

    let mut ok_any = false;
    let mut last_err = String::new();
    for &t in targets {
        match run_cmd(&cmd_bin, &["sensor_privacy", action, "0", t], None, 2500) {
            Ok(out) if out.success() => ok_any = true,
            Ok(out) => last_err = out.stderr.trim().to_string(),
            Err(e) => last_err = e,
        }
    }

    if ok_any {
        Ok(())
    } else {
        Err(format!("sensor_privacy failed: {}", last_err))
    }
}

/// Reads actual hardware microphone & camera privacy state live from `dumpsys sensor_privacy`.
pub fn probe_real_sensor_privacy() -> SensorPrivacyState {
    let dumpsys = resolve_bin("dumpsys");
    let Ok(out) = run_cmd(&dumpsys, &["sensor_privacy"], None, 1500) else {
        return SensorPrivacyState::default();
    };
    if !out.success() {
        return SensorPrivacyState::default();
    }
    parse_sensor_privacy_dump(&out.stdout)
}

/// Parses AOSP & One UI Android 12-16 `dumpsys sensor_privacy` text output for user 0.
pub fn parse_sensor_privacy_dump(dump: &str) -> SensorPrivacyState {
    let mut in_user_0 = true;
    let mut current_sensor: Option<u8> = None; // 1 = mic, 2 = camera
    let mut mic_muted = false;
    let mut cam_muted = false;

    for raw_line in dump.lines() {
        let t = raw_line.trim();
        if t.is_empty() {
            continue;
        }

        // Track user section cleanly (only read primary user 0)
        if let Some(rest) = t.strip_prefix("User:") {
            in_user_0 = rest.trim() == "0";
            current_sensor = None;
            continue;
        }
        if let Some(rest) = t.strip_prefix("user_id=") {
            in_user_0 = rest.trim().starts_with('0') && !rest.trim().chars().nth(1).map(|c| c.is_ascii_digit()).unwrap_or(false);
            current_sensor = None;
            continue;
        }
        if !in_user_0 {
            continue;
        }

        // Identify sensor header (Android Sensors.MICROPHONE=1, Sensors.CAMERA=2)
        if t == "sensor=1"
            || t.starts_with("sensor=1 ")
            || t.starts_with("sensor=1,")
            || t.contains("sensor=MICROPHONE")
            || t.eq_ignore_ascii_case("microphone:")
            || t.contains("sensor: 1")
        {
            current_sensor = Some(1);
        } else if t == "sensor=2"
            || t.starts_with("sensor=2 ")
            || t.starts_with("sensor=2,")
            || t.contains("sensor=CAMERA")
            || t.eq_ignore_ascii_case("camera:")
            || t.contains("sensor: 2")
        {
            current_sensor = Some(2);
        } else if t.starts_with("sensor=") {
            current_sensor = None;
        }

        // Check mute state inside current sensor block:
        // Android 13-16 StateTypes: 1 = ENABLED (muted), 2 = DISABLED (unmuted)
        let line_enabled = t.contains("state_type=1")
            || t.contains("state_type=ENABLED")
            || t.contains("is_enabled=true")
            || (t.contains("enabled=true") && !t.contains("is_enabled=false"));

        if line_enabled {
            match current_sensor {
                Some(1) => mic_muted = true,
                Some(2) => cam_muted = true,
                _ => {}
            }
        }
    }

    SensorPrivacyState {
        mic_muted,
        camera_muted: cam_muted,
    }
}

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

fn read_setting_global(key: &str) -> Option<String> {
    let settings = resolve_bin("settings");
    run_cmd(&settings, &["get", "global", key], None, 1500)
        .ok()
        .filter(|o| o.success())
        .map(|o| o.stdout.trim().to_string())
        .filter(|s| !s.is_empty() && s != "null")
}

fn is_wifi_radio_enabled() -> bool {
    read_setting_global("wifi_on").as_deref() == Some("1")
}

fn is_bt_enabled_and_idle() -> bool {
    if read_setting_global("bluetooth_on").as_deref() != Some("1") {
        return false;
    }
    let dumpsys = resolve_bin("dumpsys");
    if let Ok(out) = run_cmd(&dumpsys, &["bluetooth_manager"], None, 2000) {
        for line in out.stdout.lines() {
            let t = line.trim();
            if t.contains("ConnectionState: STATE_CONNECTED")
                || t.contains("mConnectionState=2")
                || (t.contains("Connected devices:") && !t.ends_with('0'))
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

        assert!(!eng.tick(&cfg, false, true, &ifaces));
        assert!(eng.auto_reboot_remaining_secs(&cfg, false, true).is_none());
        assert!(!eng.tick(&cfg, true, false, &ifaces));
        assert!(eng.auto_reboot_remaining_secs(&cfg, true, false).is_none());
        assert!(!eng.tick(&cfg, true, true, &ifaces));
        let rem = eng.auto_reboot_remaining_secs(&cfg, true, true).unwrap();
        assert!((895..=900).contains(&rem));
        assert!(!eng.tick(&cfg, true, false, &ifaces));
        assert!(eng.locked_since_boottime.is_none());
    }

    #[test]
    fn parses_android_15_sensor_privacy_dump_separately_for_mic_and_camera() {
        let dump_both_muted = "\
users: {
  user_id=0
  toggles: {
    sensor=1
    toggle_type=1
    state_type=1
  }
  toggles: {
    sensor=2
    toggle_type=1
    state_type=1
  }
}";
        let s1 = parse_sensor_privacy_dump(dump_both_muted);
        assert!(s1.mic_muted);
        assert!(s1.camera_muted);

        // User unblocked Microphone via system dialog -> state_type=2 while Camera remains state_type=1!
        let dump_mic_unblocked = "\
users: {
  user_id=0
  toggles: {
    sensor=1
    toggle_type=1
    state_type=2
  }
  toggles: {
    sensor=2
    toggle_type=1
    state_type=1
  }
  user_id=10
  toggles: {
    sensor=1
    toggle_type=1
    state_type=1
  }
}";
        let s2 = parse_sensor_privacy_dump(dump_mic_unblocked);
        assert!(!s2.mic_muted);
        assert!(s2.camera_muted);
    }
}
