"""Tier 2 (F6) — App Freezer (`SoftCgroup` & `DeepStop`) boundary, corner & negative tests."""

from __future__ import annotations

from tests.e2e.harness.registry import e2e_test
from tests.e2e.harness.silod_harness import SiloDaemonSandbox, run_cargo_unit_test


@e2e_test(
    "T2-F6-01",
    tier=2,
    features=["F6"],
    description="set_freezer_entry / freeze_uid_now with DeepStop and empty or invalid package_name returns clean error",
)
def test_deep_stop_rejects_empty_and_invalid_package_name() -> None:
    with SiloDaemonSandbox() as sb:
        for bad_pkg in ("", "bad pkg", "com.bad;id"):
            sb.rpc_err(
                "set_freezer_entry",
                {
                    "uid": 10333,
                    "entry": {
                        "package_name": bad_pkg,
                        "mode": "deep_stop",
                        "freeze_on_screen_off": True,
                        "freeze_on_background": True,
                        "kill_sockets_on_freeze": False,
                    },
                },
            )
        # Unconfigured UID freeze_uid_now
        sb.rpc_err("freeze_uid_now", {"uid": 10999})


@e2e_test(
    "T2-F6-02",
    tier=2,
    features=["F6"],
    description="set_freezer_enabled clamps background_grace_secs into [5, 600]",
)
def test_freezer_grace_secs_clamping() -> None:
    with SiloDaemonSandbox() as sb:
        sb.rpc_ok("set_freezer_enabled", {"enabled": True, "background_grace_secs": 1})
        assert sb.rpc_ok("get_config")["freezer"]["background_grace_secs"] == 5

        sb.rpc_ok("set_freezer_enabled", {"enabled": True, "background_grace_secs": 9999})
        assert sb.rpc_ok("get_config")["freezer"]["background_grace_secs"] == 600


@e2e_test(
    "T2-F6-03",
    tier=2,
    features=["F6"],
    description="SoftCgroup freeze_uid_now with 0 running PIDs degrades honestly to restricted standby bucket without falsely claiming frozen UIDs",
)
def test_soft_cgroup_zero_pids_degrades_honestly() -> None:
    pkg = "com.example.idleapp"
    with SiloDaemonSandbox() as sb:
        sb.rpc_ok("set_freezer_enabled", {"enabled": True, "background_grace_secs": 20})
        sb.rpc_ok(
            "set_freezer_entry",
            {
                "uid": 10345,
                "entry": {
                    "package_name": pkg,
                    "mode": "soft_cgroup",
                    "freeze_on_screen_off": True,
                    "freeze_on_background": True,
                    "kill_sockets_on_freeze": False,
                },
            },
        )
        st = sb.rpc_ok("freeze_uid_now", {"uid": 10345})
        assert 10345 not in st["currently_frozen_uids"], (
            f"SoftCgroup with 0 PIDs / no uid_10345 cgroup must NOT falsely claim UID frozen: {st}"
        )
        logs = sb.read_cmd_log()
        assert any(
            e["tool"] == "am" and "set-standby-bucket" in e["argv"] and pkg in e["argv"] and "restricted" in e["argv"]
            for e in logs
        ), f"Expected honest fallback to `am set-standby-bucket {pkg} restricted`: {logs}"


@e2e_test(
    "T2-F6-04",
    tier=2,
    features=["F6"],
    description="remove_freezer_entry on a currently suspended UID immediately unsuspends it and removes it from config",
)
def test_remove_freezer_entry_unsuspends_and_prunes() -> None:
    pkg = "com.example.removefrozen"
    with SiloDaemonSandbox() as sb:
        sb.rpc_ok(
            "set_freezer_entry",
            {
                "uid": 10346,
                "entry": {
                    "package_name": pkg,
                    "mode": "deep_stop",
                    "freeze_on_screen_off": True,
                    "freeze_on_background": True,
                    "kill_sockets_on_freeze": False,
                },
            },
        )
        st1 = sb.rpc_ok("freeze_uid_now", {"uid": 10346})
        assert pkg in st1["suspended_packages"]

        st2 = sb.rpc_ok("remove_freezer_entry", {"uid": 10346})
        assert pkg not in st2["suspended_packages"], f"remove_freezer_entry must thaw/unsuspend {pkg}: {st2}"
        assert "10346" not in sb.rpc_ok("get_config")["freezer"]["entries"]
        assert any(
            e["tool"] == "pm" and "unsuspend" in e["argv"] and pkg in e["argv"]
            for e in sb.read_cmd_log()
        )


@e2e_test(
    "T2-F6-05",
    tier=2,
    features=["F6"],
    description="Kernel cgroup path safety (narrow cargo test fallback): freezer MUST reject shared cgroup paths lacking exact uid_<uid> segment (D-12)",
)
def test_cgroup_uid_segment_safety_cargo_fallback() -> None:
    run_cargo_unit_test("freezer::", require_min_tests=1)
