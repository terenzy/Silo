"""Tier 1 (F8) — Kernel Hardening & Security Presets happy-path E2E tests."""

from __future__ import annotations

import json
import tempfile
from pathlib import Path

from tests.e2e.harness import android_harness
from tests.e2e.harness.registry import e2e_test
from tests.e2e.harness.silod_harness import SiloDaemonSandbox


@e2e_test(
    "T1-F8-01",
    tier=1,
    features=["F8"],
    description="get_hardening_status reads real host /proc/sys hardening keys with >= 20 items and supported=true",
)
def test_hardening_status_reads_real_proc_sys() -> None:
    with SiloDaemonSandbox() as sb:
        hs = sb.rpc_ok("get_hardening_status")
        assert isinstance(hs["items"], list) and len(hs["items"]) >= 20, (
            f"Expected >= 20 sysctl hardening items, got {len(hs.get('items', []))}"
        )
        supported = [it for it in hs["items"] if it.get("supported")]
        assert len(supported) >= 10, f"Expected >= 10 supported host Linux sysctls, got {len(supported)}"
        for it in supported[:5]:
            assert it.get("current") is not None, f"Supported sysctl missing current value: {it}"


@e2e_test(
    "T1-F8-02",
    tier=1,
    features=["F8"],
    description="set_hardening_config(enabled=true) captures snapshot_saved=true and persists boot-ID-keyed sysctl_orig.json",
)
def test_hardening_enable_persists_boot_id_sysctl_orig() -> None:
    boot_id = Path("/proc/sys/kernel/random/boot_id").read_text(encoding="utf-8").strip()
    with SiloDaemonSandbox() as sb:
        hcfg = sb.rpc_ok("get_config")["hardening"]
        hcfg["enabled"] = True
        hs = sb.rpc_ok("set_hardening_config", {"config": hcfg})
        assert hs["enabled"] is True
        assert hs["snapshot_saved"] is True, f"Expected snapshot_saved=true: {hs}"

        assert sb.sysctl_orig_path.is_file(), (
            f"Expected boot-ID-keyed sysctl_orig.json next to --config at {sb.sysctl_orig_path} (D-23)"
        )
        snap = json.loads(sb.sysctl_orig_path.read_text(encoding="utf-8"))
        assert snap.get("boot_id") == boot_id, (
            f"sysctl_orig.json must store current kernel boot_id {boot_id!r}, got {snap!r}"
        )
        values = (
            snap.get("values")
            or snap.get("original_values")
            or snap.get("originals")
            or next((v for v in snap.values() if isinstance(v, dict)), {})
        )
        assert len(values) >= 5, f"Expected captured original sysctl values in sysctl_orig.json: {snap}"


@e2e_test(
    "T1-F8-03",
    tier=1,
    features=["F8"],
    description="Restarting silod reloads boot-ID-keyed sysctl_orig.json so snapshot_saved survives restart (D-23)",
)
def test_hardening_snapshot_survives_daemon_restart() -> None:
    with SiloDaemonSandbox() as sb:
        hcfg = sb.rpc_ok("get_config")["hardening"]
        hcfg["enabled"] = True
        hs1 = sb.rpc_ok("set_hardening_config", {"config": hcfg})
        assert hs1["snapshot_saved"] is True
        assert sb.sysctl_orig_path.is_file()

        sb.restart()
        hs2 = sb.rpc_ok("get_hardening_status")
        assert hs2["enabled"] is True
        assert hs2["snapshot_saved"] is True, (
            f"Hardening original snapshot MUST survive daemon restart via sysctl_orig.json (D-23): {hs2}"
        )


@e2e_test(
    "T1-F8-04",
    tier=1,
    features=["F8"],
    description="rollback_hardening disables hardening and returns clean HardeningStatus",
)
def test_hardening_rollback() -> None:
    with SiloDaemonSandbox() as sb:
        hcfg = sb.rpc_ok("get_config")["hardening"]
        hcfg["enabled"] = True
        sb.rpc_ok("set_hardening_config", {"config": hcfg})

        rb = sb.rpc_ok("rollback_hardening")
        assert rb["enabled"] is False
        cfg = sb.rpc_ok("get_config")
        assert cfg["hardening"]["enabled"] is False


@e2e_test(
    "T1-F8-05",
    tier=1,
    features=["F8"],
    description="apply_preset cycling paranoid -> hardened -> balanced -> safe resets ALL automation & hardening flags with zero Paranoid leftovers (D-21)",
)
def test_apply_preset_cycles_cleanly_without_leftovers() -> None:
    with SiloDaemonSandbox(use_unshare=True) as sb:
        p_par = sb.rpc_ok("apply_preset", {"preset": "paranoid"})
        assert p_par["firewall"]["mode"] == "default_deny"
        assert p_par["hardening"]["enabled"] is True
        assert p_par["automation"]["auto_reboot_enabled"] is False, (
            "apply_preset must NEVER auto-enable auto_reboot_enabled"
        )
        assert p_par["automation"]["usb_adb_lock_guard"] is True
        assert p_par["automation"]["sensors_off_on_lock"] is True

        p_hrd = sb.rpc_ok("apply_preset", {"preset": "hardened"})
        assert p_hrd["hardening"]["enabled"] is True
        assert p_hrd["automation"]["auto_reboot_enabled"] is False

        p_bal = sb.rpc_ok("apply_preset", {"preset": "balanced"})
        assert p_bal["firewall"]["mode"] == "default_allow"
        assert p_bal["automation"]["sensors_off_on_lock"] is False, (
            f"Balanced preset must reset Paranoid sensors_off_on_lock (D-21): {p_bal['automation']}"
        )
        assert p_bal["automation"]["wifi_auto_off_enabled"] is False, (
            f"Balanced preset must reset Paranoid wifi_auto_off_enabled (D-21): {p_bal['automation']}"
        )
        assert p_bal["automation"]["bluetooth_auto_off_enabled"] is False, (
            f"Balanced preset must reset Paranoid bluetooth_auto_off_enabled (D-21): {p_bal['automation']}"
        )
        assert p_bal["automation"]["auto_reboot_enabled"] is False

        p_safe = sb.rpc_ok("apply_preset", {"preset": "safe"})
        assert p_safe["firewall"]["mode"] == "default_allow"
        assert p_safe["hardening"]["enabled"] is False, (
            f"Safe preset must disable kernel hardening (D-21): {p_safe['hardening']}"
        )
        assert p_safe["scopes"]["auto_clear_clipboard"] is False, (
            f"Safe preset must reset auto_clear_clipboard (D-21): {p_safe['scopes']}"
        )
        for key in (
            "wifi_auto_off_enabled",
            "bluetooth_auto_off_enabled",
            "usb_adb_lock_guard",
            "sensors_off_on_lock",
            "auto_reboot_enabled",
        ):
            assert p_safe["automation"][key] is False, (
                f"Safe preset left automation.{key}=True after Paranoid (D-21): {p_safe['automation']}"
            )


@e2e_test(
    "T1-F8-06",
    tier=1,
    features=["F8"],
    description="Live silod apply_preset (all 4 presets) and get_hardening_status deserialize cleanly into compiled Kotlin SiloConfig & HardeningStatus",
)
def test_kotlin_presets_and_hardening_roundtrip() -> None:
    with SiloDaemonSandbox(use_unshare=True) as sb, tempfile.TemporaryDirectory(prefix="silo_su_") as td:
        su_dir = Path(td) / "bin"
        android_harness.install_su_bridge_wrapper(
            su_dir, silod_bin=sb.silod_bin, socket_path=sb.socket_path
        )
        for preset in ("safe", "balanced", "hardened", "paranoid"):
            pf = Path(td) / f"{preset}.json"
            pf.write_text(json.dumps({"preset": preset}), encoding="utf-8")
            out = android_harness.run_jvm_contract(
                ["silo_client_call", "apply_preset", str(pf), "15000", "SiloConfig"],
                extra_path_dir=su_dir,
            )
            assert out["model"] == "SiloConfig" and out["schema_version"] == 3

        hs = android_harness.run_jvm_contract(
            ["silo_client_call", "get_hardening_status", "null", "10000", "HardeningStatus"],
            extra_path_dir=su_dir,
        )
        assert hs["model"] == "HardeningStatus" and hs["items"] >= 20
