"""Tier 1 (F3) — Real-Time Privacy Monitor & Live Traffic happy-path E2E tests."""

from __future__ import annotations

import json
import socket
import tempfile
from pathlib import Path

from tests.e2e.harness import android_harness
from tests.e2e.harness.registry import e2e_test
from tests.e2e.harness.silod_harness import SiloDaemonSandbox


@e2e_test(
    "T1-F3-01",
    tier=1,
    features=["F3"],
    description="get_privacy_access_log parses (allow/ignore) strict tuple as 'ignore' instead of 'allow'",
)
def test_monitor_strict_tuple_mode_parsing() -> None:
    raw_cam = (
        "Current AppOps Service state:\n"
        "  Uid u0a277:\n"
        "    Package com.example.camspy:\n"
        "      CAMERA (allow/ignore):\n"
        "        Access: [top-s] -1m20s100ms ago\n"
    )
    with SiloDaemonSandbox() as sb:
        sb.set_dumpsys_appops_raw("CAMERA", raw_cam)
        events = sb.rpc_ok("get_privacy_access_log", {"limit": 50})
        matches = [e for e in events if e["package_name"] == "com.example.camspy" and e["op"] == "CAMERA"]
        assert len(matches) == 1, f"Expected 1 CAMERA event for com.example.camspy, got: {events}"
        assert matches[0]["mode"] == "ignore", (
            f"(allow/ignore) tuple must resolve to strict mode 'ignore' (D-11/CB-05), got {matches[0]}"
        )


@e2e_test(
    "T1-F3-02",
    tier=1,
    features=["F3"],
    description="get_privacy_access_log scans ALL Access: lines per op and surfaces fresher / (running) entry",
)
def test_monitor_scans_all_access_lines_and_running_entry() -> None:
    raw_mic = (
        "Current AppOps Service state:\n"
        "  Uid u0a288:\n"
        "    Package com.example.voicerecorder:\n"
        "      RECORD_AUDIO (allow):\n"
        "        Access: [top-s] -4d3h10m ago\n"
        "        Access: [fg-s] -12s50ms ago (running)\n"
    )
    with SiloDaemonSandbox() as sb:
        sb.set_dumpsys_appops_raw("RECORD_AUDIO", raw_mic)
        events = sb.rpc_ok("get_privacy_access_log", {"limit": 50})
        matches = [e for e in events if e["package_name"] == "com.example.voicerecorder"]
        assert len(matches) == 1, f"Expected 1 RECORD_AUDIO event, got {events}"
        ev = matches[0]
        assert ev["running_now"] is True, (
            f"Monitor must scan ALL Access: lines and detect trailing (running) [fg-s] entry (D-11): {ev}"
        )
        assert ev["age_secs"] <= 60, f"Expected fresher age_secs <= 60 (not 4d stale entry), got {ev}"


@e2e_test(
    "T1-F3-03",
    tier=1,
    features=["F3"],
    description="get_privacy_access_log excludes protected packages and system UIDs < 10000",
)
def test_monitor_excludes_protected_packages_and_system_uids() -> None:
    raw_clip = (
        "Current AppOps Service state:\n"
        "  Uid 1000:\n"
        "    Package com.android.settings:\n"
        "      READ_CLIPBOARD (allow):\n"
        "        Access: [top-s] -5s ago\n"
        "  Uid u0a101:\n"
        "    Package dev.silo.app:\n"
        "      READ_CLIPBOARD (allow):\n"
        "        Access: [top-s] -5s ago\n"
        "  Uid u0a102:\n"
        "    Package com.samsung.android.honeyboard:\n"
        "      READ_CLIPBOARD (allow):\n"
        "        Access: [top-s] -5s ago\n"
        "  Uid u0a103:\n"
        "    Package com.rifsxd.ksunext:\n"
        "      READ_CLIPBOARD (allow):\n"
        "        Access: [top-s] -5s ago\n"
        "  Uid u0a299:\n"
        "    Package com.example.clipreader:\n"
        "      READ_CLIPBOARD (allow):\n"
        "        Access: [top-s] -10s ago\n"
    )
    with SiloDaemonSandbox() as sb:
        sb.set_dumpsys_appops_raw("READ_CLIPBOARD", raw_clip)
        events = sb.rpc_ok("get_privacy_access_log", {"limit": 50})
        pkgs = {e["package_name"] for e in events}
        for forbidden in ("com.android.settings", "dev.silo.app", "com.samsung.android.honeyboard", "com.rifsxd.ksunext"):
            assert forbidden not in pkgs, f"Protected package {forbidden!r} leaked into Monitor log: {events}"
        assert "com.example.clipreader" in pkgs, f"Expected ordinary user app in Monitor log: {events}"


@e2e_test(
    "T1-F3-04",
    tier=1,
    features=["F3"],
    description="get_live_connections queries real host NETLINK_SOCK_DIAG and sees an active test TCP listener",
)
def test_get_live_connections_sees_real_host_tcp_socket() -> None:
    with SiloDaemonSandbox(use_unshare=False) as sb, socket.socket(socket.AF_INET, socket.SOCK_STREAM) as srv:
        srv.bind(("127.0.0.1", 0))
        srv.listen(1)
        bound_port = srv.getsockname()[1]

        conns = sb.rpc_ok("get_live_connections")
        assert isinstance(conns, list) and len(conns) > 0, "Expected non-empty live socket list from NETLINK_SOCK_DIAG"
        hit = [
            c
            for c in conns
            if c.get("protocol") == "TCP"
            and c.get("local_port") == bound_port
            and c.get("family") == 4
        ]
        assert hit, f"Expected bound TCP listener port {bound_port} in get_live_connections (sample={conns[:5]})"
        assert hit[0]["is_listening"] is True, f"Expected is_listening=True for LISTEN socket: {hit[0]}"


@e2e_test(
    "T1-F3-05",
    tier=1,
    features=["F3"],
    description="get_telemetry and clear_telemetry return valid TelemetrySnapshot schema and reset cleanly",
)
def test_telemetry_snapshot_and_clear() -> None:
    with SiloDaemonSandbox() as sb:
        snap = sb.rpc_ok("get_telemetry", {"limit": 50})
        assert isinstance(snap["total_blocked"], int)
        assert isinstance(snap["per_uid_blocked"], dict)
        assert isinstance(snap["events"], list)

        cleared = sb.rpc_ok("clear_telemetry")
        assert cleared.get("done") is True
        snap2 = sb.rpc_ok("get_telemetry")
        assert snap2["total_blocked"] == 0
        assert snap2["events"] == []


@e2e_test(
    "T1-F3-06",
    tier=1,
    features=["F3"],
    description="Live silod Monitor RPCs deserialize cleanly into compiled Kotlin PrivacyAccessEvent, ActiveConnection, and TelemetrySnapshot",
)
def test_live_monitor_rpcs_kotlin_deserialization() -> None:
    raw_cam = (
        "Current AppOps Service state:\n"
        "  Uid u0a277:\n"
        "    Package com.example.camkt:\n"
        "      CAMERA (allow/ignore):\n"
        "        Access: [top-s] -30s ago\n"
    )
    with SiloDaemonSandbox(use_unshare=False) as sb, tempfile.TemporaryDirectory(prefix="silo_su_") as td:
        sb.set_dumpsys_appops_raw("CAMERA", raw_cam)
        su_dir = Path(td) / "bin"
        android_harness.install_su_bridge_wrapper(
            su_dir, silod_bin=sb.silod_bin, socket_path=sb.socket_path
        )
        p_ev = android_harness.run_jvm_contract(
            ["silo_client_call", "get_privacy_access_log", "null", "10000", "PrivacyAccessEventList"],
            extra_path_dir=su_dir,
        )
        assert p_ev["model"] == "PrivacyAccessEventList" and p_ev["count"] >= 1

        p_conn = android_harness.run_jvm_contract(
            ["silo_client_call", "get_live_connections", "null", "10000", "ActiveConnectionList"],
            extra_path_dir=su_dir,
        )
        assert p_conn["model"] == "ActiveConnectionList"

        p_tel = android_harness.run_jvm_contract(
            ["silo_client_call", "get_telemetry", "null", "10000", "TelemetrySnapshot"],
            extra_path_dir=su_dir,
        )
        assert p_tel["model"] == "TelemetrySnapshot"
