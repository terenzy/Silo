"""Tier 1 (F6) — App Freezer (`SoftCgroup` & `DeepStop`) happy-path E2E tests."""

from __future__ import annotations

import json
import tempfile
from pathlib import Path

from tests.e2e.harness import android_harness
from tests.e2e.harness.registry import e2e_test
from tests.e2e.harness.silod_harness import SiloDaemonSandbox


@e2e_test(
    "T1-F6-01",
    tier=1,
    features=["F6"],
    description="set_freezer_enabled persists config and get_freezer_status reports cgroup_v2_available=true",
)
def test_freezer_enable_and_status() -> None:
    with SiloDaemonSandbox() as sb:
        st = sb.rpc_ok("set_freezer_enabled", {"enabled": True, "background_grace_secs": 30})
        assert st["enabled"] is True
        assert st["cgroup_v2_available"] is True

        cfg = sb.rpc_ok("get_config")
        assert cfg["freezer"]["enabled"] is True
        assert cfg["freezer"]["background_grace_secs"] == 30

        st2 = sb.rpc_ok("get_freezer_status")
        assert st2["enabled"] is True and st2["cgroup_v2_available"] is True


@e2e_test(
    "T1-F6-02",
    tier=1,
    features=["F6"],
    description="DeepStop freeze_uid_now invokes am force-stop, restricted standby bucket, and pm suspend --user 0",
)
def test_deep_stop_freeze_invokes_am_and_pm_suspend() -> None:
    pkg = "com.example.heavy"
    with SiloDaemonSandbox() as sb:
        sb.rpc_ok("set_freezer_enabled", {"enabled": True, "background_grace_secs": 20})
        sb.rpc_ok(
            "set_freezer_entry",
            {
                "uid": 10277,
                "entry": {
                    "package_name": pkg,
                    "mode": "deep_stop",
                    "freeze_on_screen_off": True,
                    "freeze_on_background": True,
                    "kill_sockets_on_freeze": True,
                },
            },
        )
        st = sb.rpc_ok("freeze_uid_now", {"uid": 10277})
        assert pkg in st["suspended_packages"], f"Expected {pkg} in suspended_packages: {st}"

        logs = sb.read_cmd_log()
        assert any(e["tool"] == "am" and "force-stop" in e["argv"] and pkg in e["argv"] for e in logs)
        assert any(
            e["tool"] == "am" and "set-standby-bucket" in e["argv"] and pkg in e["argv"] and "restricted" in e["argv"]
            for e in logs
        )
        assert any(
            e["tool"] == "pm" and "suspend" in e["argv"] and "--user" in e["argv"] and "0" in e["argv"] and pkg in e["argv"]
            for e in logs
        ), f"Expected `pm suspend --user 0 {pkg}`: {logs}"


@e2e_test(
    "T1-F6-03",
    tier=1,
    features=["F6"],
    description="thaw_uid_now invokes pm unsuspend --user 0 and active standby bucket, removing package from suspended_packages",
)
def test_deep_stop_thaw_uid_unsuspends_cleanly() -> None:
    pkg = "com.example.thawme"
    with SiloDaemonSandbox() as sb:
        sb.rpc_ok(
            "set_freezer_entry",
            {
                "uid": 10278,
                "entry": {
                    "package_name": pkg,
                    "mode": "deep_stop",
                    "freeze_on_screen_off": True,
                    "freeze_on_background": True,
                    "kill_sockets_on_freeze": False,
                },
            },
        )
        sb.rpc_ok("freeze_uid_now", {"uid": 10278})
        st = sb.rpc_ok("thaw_uid_now", {"uid": 10278})
        assert pkg not in st["suspended_packages"], f"Expected {pkg} thawed: {st}"

        logs = sb.read_cmd_log()
        assert any(
            e["tool"] == "pm" and "unsuspend" in e["argv"] and "--user" in e["argv"] and "0" in e["argv"] and pkg in e["argv"]
            for e in logs
        )
        assert any(
            e["tool"] == "am" and "set-standby-bucket" in e["argv"] and pkg in e["argv"] and "active" in e["argv"]
            for e in logs
        )


@e2e_test(
    "T1-F6-04",
    tier=1,
    features=["F6"],
    description="thaw_all_now unsuspends packages recovered after daemon restart (D-12 / CB-14)",
)
def test_thaw_all_recovers_suspended_packages_across_restart() -> None:
    pkg = "com.example.survivesuspend"
    with SiloDaemonSandbox() as sb:
        sb.rpc_ok(
            "set_freezer_entry",
            {
                "uid": 10279,
                "entry": {
                    "package_name": pkg,
                    "mode": "deep_stop",
                    "freeze_on_screen_off": True,
                    "freeze_on_background": True,
                    "kill_sockets_on_freeze": False,
                },
            },
        )
        sb.rpc_ok("freeze_uid_now", {"uid": 10279})
        # Simulate daemon restart while Android still has pkg suspended
        sb.stop()
        sb.set_suspended(pkg, user_id=0, suspended=True, uid=10279)
        log_before = len(sb.read_cmd_log())
        sb.start()

        st = sb.rpc_ok("thaw_all_now")
        assert pkg not in st["suspended_packages"]
        new_logs = sb.read_cmd_log()[log_before:]
        assert any(
            e["tool"] == "pm" and "unsuspend" in e["argv"] and pkg in e["argv"]
            for e in new_logs
        ), f"thaw_all_now MUST unsuspend packages that were already suspended before daemon restart (D-12/CB-14): {new_logs}"


@e2e_test(
    "T1-F6-05",
    tier=1,
    features=["F6"],
    description="Multi-user Work Profile UID 1010277 (user 10) invokes pm suspend/unsuspend with --user 10",
)
def test_freezer_multi_user_work_profile_uid() -> None:
    pkg = "com.example.workapp"
    work_uid = 10 * 100_000 + 10277  # 1010277 -> user 10
    with SiloDaemonSandbox() as sb:
        sb.rpc_ok(
            "set_freezer_entry",
            {
                "uid": work_uid,
                "entry": {
                    "package_name": pkg,
                    "mode": "deep_stop",
                    "freeze_on_screen_off": True,
                    "freeze_on_background": True,
                    "kill_sockets_on_freeze": False,
                },
            },
        )
        sb.rpc_ok("freeze_uid_now", {"uid": work_uid})
        sb.rpc_ok("thaw_uid_now", {"uid": work_uid})

        logs = sb.read_cmd_log()
        assert any(
            e["tool"] == "pm" and "suspend" in e["argv"] and "--user" in e["argv"] and "10" in e["argv"] and pkg in e["argv"]
            for e in logs
        ), f"Expected `pm suspend --user 10 {pkg}` for Work Profile UID {work_uid} (CB-08): {logs}"
        assert any(
            e["tool"] == "pm" and "unsuspend" in e["argv"] and "--user" in e["argv"] and "10" in e["argv"] and pkg in e["argv"]
            for e in logs
        ), f"Expected `pm unsuspend --user 10 {pkg}` for Work Profile UID {work_uid} (CB-08): {logs}"


@e2e_test(
    "T1-F6-06",
    tier=1,
    features=["F6"],
    description="Live silod FreezerStatus & SiloConfig.freezer deserialize cleanly into compiled Kotlin FreezerStatus + FreezeMode enums",
)
def test_kotlin_freezer_status_and_enum_contract() -> None:
    with SiloDaemonSandbox() as sb, tempfile.TemporaryDirectory(prefix="silo_su_") as td:
        sb.rpc_ok("set_freezer_enabled", {"enabled": True, "background_grace_secs": 25})
        sb.rpc_ok(
            "set_freezer_entry",
            {
                "uid": 10301,
                "entry": {
                    "package_name": "com.example.soft",
                    "mode": "soft_cgroup",
                    "freeze_on_screen_off": True,
                    "freeze_on_background": True,
                    "kill_sockets_on_freeze": True,
                },
            },
        )
        sb.rpc_ok(
            "set_freezer_entry",
            {
                "uid": 10302,
                "entry": {
                    "package_name": "com.example.deep",
                    "mode": "deep_stop",
                    "freeze_on_screen_off": True,
                    "freeze_on_background": True,
                    "kill_sockets_on_freeze": True,
                },
            },
        )
        su_dir = Path(td) / "bin"
        android_harness.install_su_bridge_wrapper(
            su_dir, silod_bin=sb.silod_bin, socket_path=sb.socket_path
        )
        fs = android_harness.run_jvm_contract(
            ["silo_client_call", "get_freezer_status", "null", "10000", "FreezerStatus"],
            extra_path_dir=su_dir,
        )
        assert fs["model"] == "FreezerStatus" and fs["enabled"] is True and fs["cgroup_v2_available"] is True

        cfg = android_harness.run_jvm_contract(
            ["silo_client_call", "get_config", "null", "10000", "SiloConfig"],
            extra_path_dir=su_dir,
        )
        assert cfg["model"] == "SiloConfig" and cfg["freezer_entries"] == 2
