"""Tier 1 (F7) — Privacy Automation, Sensors, Clipboard, Radio & DNS happy-path E2E tests."""

from __future__ import annotations

import json
import tempfile
from pathlib import Path

from tests.e2e.harness import android_harness
from tests.e2e.harness.registry import e2e_test
from tests.e2e.harness.silod_harness import SiloDaemonSandbox


@e2e_test(
    "T1-F7-01",
    tier=1,
    features=["F7"],
    description="set_sensor_privacy toggles microphone & camera independently via cmd sensor_privacy and reads separated state from dumpsys",
)
def test_sensor_privacy_independent_mic_and_camera() -> None:
    with SiloDaemonSandbox() as sb:
        st1 = sb.rpc_ok("set_sensor_privacy", {"sensor": "microphone", "enabled": True})
        assert st1["mic_muted"] is True and st1["camera_muted"] is False, f"Expected only mic muted: {st1}"

        st2 = sb.rpc_ok("set_sensor_privacy", {"sensor": "camera", "enabled": True})
        assert st2["mic_muted"] is True and st2["camera_muted"] is True, f"Expected both muted: {st2}"

        st3 = sb.rpc_ok("set_sensor_privacy", {"sensor": "microphone", "enabled": False})
        assert st3["mic_muted"] is False and st3["camera_muted"] is True, f"Expected only cam muted: {st3}"


@e2e_test(
    "T1-F7-02",
    tier=1,
    features=["F7"],
    description="set_private_dns hostname mode writes settings global private_dns_mode/specifier and reads back accurately",
)
def test_private_dns_hostname_and_readback() -> None:
    with SiloDaemonSandbox() as sb:
        dns = sb.rpc_ok("set_private_dns", {"mode": "hostname", "specifier": "dns.quad9.net"})
        assert dns["mode"] == "hostname" and dns["specifier"] == "dns.quad9.net"

        readback = sb.rpc_ok("get_private_dns")
        assert readback["mode"] == "hostname" and readback["specifier"] == "dns.quad9.net", (
            f"Unexpected Private DNS readback: {readback}"
        )

        off = sb.rpc_ok("set_private_dns", {"mode": "off", "specifier": ""})
        assert off["mode"] == "off"


@e2e_test(
    "T1-F7-03",
    tier=1,
    features=["F7"],
    description="set_radio_privacy configures enhanced MAC, scan disable, captive portal; disabling enhanced MAC does NOT disable baseline MAC randomization",
)
def test_radio_privacy_enhanced_mac_preserves_baseline_randomization() -> None:
    with SiloDaemonSandbox() as sb:
        rp = sb.rpc_ok(
            "set_radio_privacy",
            {
                "enhanced_mac_randomization": True,
                "disable_wifi_ble_scanning": True,
                "disable_captive_portal": True,
            },
        )
        assert rp["enhanced_mac_randomization"] is True
        assert rp["disable_wifi_ble_scanning"] is True
        assert rp["disable_captive_portal"] is True

        # Turn enhanced MAC randomization OFF -> must NOT write wifi_connected_mac_randomization_enabled 0 (D-22)!
        log_before = len(sb.read_cmd_log())
        rp2 = sb.rpc_ok("set_radio_privacy", {"enhanced_mac_randomization": False})
        assert rp2["enhanced_mac_randomization"] is False

        new_logs = sb.read_cmd_log()[log_before:]
        bad_writes = [
            e
            for e in new_logs
            if e["tool"] == "settings"
            and "wifi_connected_mac_randomization_enabled" in e["argv"]
            and "0" in e["argv"]
        ]
        assert not bad_writes, (
            f"Disabling enhanced_mac_randomization MUST NOT disable baseline Android MAC randomization (D-22): {bad_writes}"
        )
        assert sb.read_android_state()["settings_global"]["wifi_connected_mac_randomization_enabled"] == "1"


@e2e_test(
    "T1-F7-04",
    tier=1,
    features=["F7"],
    description="set_auto_clear_clipboard persists config and clear_clipboard_now invokes cmd clipboard clear",
)
def test_clipboard_auto_clear_config_and_clear_now() -> None:
    with SiloDaemonSandbox() as sb:
        sc = sb.rpc_ok("set_auto_clear_clipboard", {"enabled": True, "clear_secs": 30})
        assert sc["auto_clear_clipboard"] is True and sc["clipboard_clear_secs"] == 30

        res = sb.rpc_ok("clear_clipboard_now")
        assert res.get("done") is True

        assert sb.read_android_state()["clipboard"]["has_clip"] is False
        logs = sb.read_cmd_log()
        assert any(e["tool"] == "cmd" and "clipboard" in e["argv"] for e in logs)


@e2e_test(
    "T1-F7-05",
    tier=1,
    features=["F7"],
    description="set_automation_config persists all automation fields and trigger_panic_reboot with exact confirm token invokes svc power reboot",
)
def test_automation_config_and_panic_reboot() -> None:
    with SiloDaemonSandbox() as sb:
        auto = {
            "auto_reboot_enabled": True,
            "auto_reboot_minutes": 30,
            "wifi_auto_off_enabled": True,
            "wifi_auto_off_minutes": 10,
            "bluetooth_auto_off_enabled": True,
            "bluetooth_auto_off_minutes": 15,
            "sensors_off_on_lock": True,
            "usb_adb_lock_guard": True,
        }
        saved = sb.rpc_ok("set_automation_config", {"config": auto})
        for k, v in auto.items():
            assert saved.get(k) == v, f"AutomationConfig field {k!r} mismatch: expected {v!r}, got {saved!r}"

        rep = sb.rpc_ok("trigger_panic_reboot", {"confirm_token": "CONFIRM_SILO_BFU_REBOOT"})
        assert rep.get("done") is True

        logs = sb.read_cmd_log()
        assert any(
            e["tool"] == "svc" and e["argv"][:2] == ["power", "reboot"]
            for e in logs
        ), f"Expected sandboxed `svc power reboot` call: {logs}"


@e2e_test(
    "T1-F7-06",
    tier=1,
    features=["F7"],
    description="Live silod PrivateDns, RadioPrivacy, SensorPrivacy, and AutomationConfig deserialize cleanly into compiled Kotlin DTOs",
)
def test_kotlin_f7_privacy_dtos_roundtrip() -> None:
    with SiloDaemonSandbox() as sb, tempfile.TemporaryDirectory(prefix="silo_su_") as td:
        su_dir = Path(td) / "bin"
        android_harness.install_su_bridge_wrapper(
            su_dir, silod_bin=sb.silod_bin, socket_path=sb.socket_path
        )

        p_dns = Path(td) / "dns.json"
        p_dns.write_text(json.dumps({"mode": "hostname", "specifier": "dns.quad9.net"}), encoding="utf-8")
        r_dns = android_harness.run_jvm_contract(
            ["silo_client_call", "set_private_dns", str(p_dns), "10000", "PrivateDnsState"],
            extra_path_dir=su_dir,
        )
        assert r_dns["mode"] == "hostname" and r_dns["specifier"] == "dns.quad9.net"

        p_rad = Path(td) / "rad.json"
        p_rad.write_text(json.dumps({"enhanced_mac_randomization": True}), encoding="utf-8")
        r_rad = android_harness.run_jvm_contract(
            ["silo_client_call", "set_radio_privacy", str(p_rad), "10000", "RadioPrivacyState"],
            extra_path_dir=su_dir,
        )
        assert r_rad["enhanced_mac"] is True

        p_sp = Path(td) / "sp.json"
        p_sp.write_text(json.dumps({"sensor": "camera", "enabled": True}), encoding="utf-8")
        r_sp = android_harness.run_jvm_contract(
            ["silo_client_call", "set_sensor_privacy", str(p_sp), "10000", "SensorPrivacyState"],
            extra_path_dir=su_dir,
        )
        assert r_sp["camera_muted"] is True

        auto = sb.rpc_ok("get_config")["automation"]
        auto["sensors_off_on_lock"] = True
        p_au = Path(td) / "au.json"
        p_au.write_text(json.dumps({"config": auto}), encoding="utf-8")
        r_au = android_harness.run_jvm_contract(
            ["silo_client_call", "set_automation_config", str(p_au), "10000", "AutomationConfig"],
            extra_path_dir=su_dir,
        )
        assert r_au["sensors_off_on_lock"] is True
