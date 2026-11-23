"""Tier 3 — Cross-boundary Android App <-> Live Daemon pairwise tests (T3-P14..T3-P16)."""

from __future__ import annotations

import json
import tempfile
from pathlib import Path

from tests.e2e.harness import android_harness
from tests.e2e.harness.registry import e2e_test
from tests.e2e.harness.silod_harness import SiloDaemonSandbox


@e2e_test(
    "T3-P14",
    tier=3,
    features=["F4", "F5", "F9"],
    description="F4 x F5 x F9: Compiled Kotlin BlocklistParser parses >600 KiB feed -> pushed via real SiloClient -> silod --bridge -> live silod + rejected check",
)
def test_pair_app_daemon_large_blocklist_via_kotlin_parser_and_silo_client() -> None:
    lines = ["# FireHOL-style synthetic feed"]
    for b in range(1, 140):
        # Multi-IP lines + inline comments + bogus timestamp lines
        lines.append(f"198.{b}.1.1, 198.{b}.2.1 198.{b}.3.1 # row {b}")
        for c in range(4, 240):
            lines.append(f"198.{b}.{c}.1")
    lines.append("12:30:00 invalid timestamp")
    lines.append("999.1.1.1 invalid octet")
    raw_text = "\n".join(lines) + "\n"
    assert len(raw_text.encode("utf-8")) > 550_000

    with SiloDaemonSandbox(use_unshare=True) as sb, tempfile.TemporaryDirectory(prefix="silo_t3_bl_") as td:
        td_p = Path(td)
        feed_file = td_p / "feed.txt"
        feed_file.write_text(raw_text, encoding="utf-8")

        parsed = android_harness.run_jvm_contract(["blocklist_parser", str(feed_file), "50000"])
        entries = parsed["entries"]
        assert len(entries) > 30_000, f"Expected >30,000 parsed valid CIDRs, got {len(entries)}"
        assert "12:30:00" not in entries and "999.1.1.1" not in entries

        su_dir = td_p / "bin"
        android_harness.install_su_bridge_wrapper(
            su_dir, silod_bin=sb.silod_bin, socket_path=sb.socket_path
        )
        params_file = td_p / "replace_params.json"
        params_file.write_text(json.dumps({"entries": entries}), encoding="utf-8")

        res = android_harness.run_jvm_contract(
            ["silo_client_call", "replace_ip_blocklist", str(params_file), "35000"],
            extra_path_dir=su_dir,
            timeout=45.0,
        )
        assert res["ok"] is True, f"SiloClient large replace_ip_blocklist failed: {res}"
        assert res["data"]["loaded"] == len(entries) and res["data"]["rejected"] == 0

        # Also verify direct invalid entry surfaces rejected == 1
        bad_params = td_p / "bad_params.json"
        bad_params.write_text(json.dumps({"entries": ["198.51.100.1", "127.0.0.1"]}), encoding="utf-8")
        res_bad = android_harness.run_jvm_contract(
            ["silo_client_call", "replace_ip_blocklist", str(bad_params), "15000"],
            extra_path_dir=su_dir,
        )
        assert res_bad["ok"] is True and res_bad["data"]["loaded"] == 1 and res_bad["data"]["rejected"] == 1


@e2e_test(
    "T3-P15",
    tier=3,
    features=["F5", "F10"],
    description="F5 x F10: Panic Lockdown via real SiloClient -> live silod deserializes panicLockdownActive=true in Kotlin + LockdownTileService bytecode guards unlockAndRun",
)
def test_pair_f5_f10_panic_lockdown_wire_contract_and_lockscreen_tile() -> None:
    with SiloDaemonSandbox(use_unshare=True) as sb, tempfile.TemporaryDirectory(prefix="silo_t3_p15_") as td:
        su_dir = Path(td) / "bin"
        android_harness.install_su_bridge_wrapper(
            su_dir, silod_bin=sb.silod_bin, socket_path=sb.socket_path
        )
        rep = android_harness.run_jvm_contract(
            ["silo_client_call", "trigger_panic_lockdown", "null", "15000", "PanicLockdownReport"],
            extra_path_dir=su_dir,
        )
        assert rep["firewall_locked"] is True and rep["mic_muted"] is True and rep["camera_muted"] is True

        st = android_harness.run_jvm_contract(
            ["silo_client_call", "get_status", "null", "10000", "DaemonStatus"],
            extra_path_dir=su_dir,
        )
        assert st["panic_lockdown_active"] is True

        cfg = android_harness.run_jvm_contract(
            ["silo_client_call", "get_config", "null", "10000", "SiloConfig"],
            extra_path_dir=su_dir,
        )
        assert cfg["panic_lockdown_active"] is True

        disasm = android_harness.javap_disassemble_classes(
            android_harness.KOTLIN_DEBUG_CLASSES, "dev/silo/app/tiles/LockdownTileService"
        )
        assert "isLocked:()Z" in disasm and "unlockAndRun:(Ljava/lang/Runnable;)V" in disasm


@e2e_test(
    "T3-P16",
    tier=3,
    features=["F7", "F10"],
    description="F7 x F10: Sensor privacy mute/unmute via real SiloClient -> live silod deserializes SensorPrivacyState in Kotlin + SensorsOffTileService guards unlockAndRun",
)
def test_pair_f7_f10_sensor_privacy_wire_contract_and_lockscreen_tile() -> None:
    with SiloDaemonSandbox() as sb, tempfile.TemporaryDirectory(prefix="silo_t3_p16_") as td:
        td_p = Path(td)
        su_dir = td_p / "bin"
        android_harness.install_su_bridge_wrapper(
            su_dir, silod_bin=sb.silod_bin, socket_path=sb.socket_path
        )

        p_on = td_p / "on.json"
        p_on.write_text(json.dumps({"sensor": "all", "enabled": True}), encoding="utf-8")
        r_on = android_harness.run_jvm_contract(
            ["silo_client_call", "set_sensor_privacy", str(p_on), "10000", "SensorPrivacyState"],
            extra_path_dir=su_dir,
        )
        assert r_on["mic_muted"] is True and r_on["camera_muted"] is True

        p_off = td_p / "off.json"
        p_off.write_text(json.dumps({"sensor": "microphone", "enabled": False}), encoding="utf-8")
        r_off = android_harness.run_jvm_contract(
            ["silo_client_call", "set_sensor_privacy", str(p_off), "10000", "SensorPrivacyState"],
            extra_path_dir=su_dir,
        )
        assert r_off["mic_muted"] is False and r_off["camera_muted"] is True

        disasm = android_harness.javap_disassemble_classes(
            android_harness.KOTLIN_DEBUG_CLASSES, "dev/silo/app/tiles/SensorsOffTileService"
        )
        assert "isLocked:()Z" in disasm and "unlockAndRun:(Ljava/lang/Runnable;)V" in disasm
