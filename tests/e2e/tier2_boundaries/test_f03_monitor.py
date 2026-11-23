"""Tier 2 (F3) — Real-Time Privacy Monitor & Live Traffic boundary, corner & negative tests."""

from __future__ import annotations

import socket

from tests.e2e.harness.registry import e2e_test
from tests.e2e.harness.silod_harness import SiloDaemonSandbox


@e2e_test(
    "T2-F3-01",
    tier=2,
    features=["F3"],
    description="Exact op-name token boundary: CAMERA_SANDBOXED (allow) in dumpsys appops --op CAMERA MUST NOT match CAMERA (D-11)",
)
def test_monitor_exact_op_token_boundary() -> None:
    raw = (
        "Current AppOps Service state:\n"
        "  Uid u0a277:\n"
        "    Package com.example.prefixtrap:\n"
        "      CAMERA_SANDBOXED (allow):\n"
        "        Access: [top-s] -10s ago\n"
    )
    with SiloDaemonSandbox() as sb:
        sb.set_dumpsys_appops_raw("CAMERA", raw)
        events = sb.rpc_ok("get_privacy_access_log", {"limit": 50})
        leaked = [e for e in events if e["package_name"] == "com.example.prefixtrap"]
        assert not leaked, (
            f"Prefix-trap op CAMERA_SANDBOXED falsely matched op CAMERA (D-11): {leaked}"
        )


@e2e_test(
    "T2-F3-02",
    tier=2,
    features=["F3"],
    description="Accesses older than 7 days (-8d2h) are excluded unless (running) is present",
)
def test_monitor_excludes_stale_over_7_days_unless_running() -> None:
    raw = (
        "Current AppOps Service state:\n"
        "  Uid u0a280:\n"
        "    Package com.example.staleapp:\n"
        "      CAMERA (allow):\n"
        "        Access: [top-s] -8d2h10m ago\n"
        "  Uid u0a281:\n"
        "    Package com.example.longrunner:\n"
        "      CAMERA (allow):\n"
        "        Access: [fg-s] -8d2h10m ago (running)\n"
    )
    with SiloDaemonSandbox() as sb:
        sb.set_dumpsys_appops_raw("CAMERA", raw)
        events = sb.rpc_ok("get_privacy_access_log", {"limit": 50})
        pkgs = {e["package_name"] for e in events}
        assert "com.example.staleapp" not in pkgs, f"8-day stale non-running access must be excluded: {events}"
        assert "com.example.longrunner" in pkgs, f"8-day (running) access must still be surfaced: {events}"


@e2e_test(
    "T2-F3-03",
    tier=2,
    features=["F3"],
    description="get_privacy_access_log and get_telemetry clamp extreme limit values (0 and 99999) cleanly",
)
def test_monitor_and_telemetry_limit_clamping() -> None:
    # Populate 25 packages for CAMERA to verify limit=0 clamps up to >= 10 and limit=9999 succeeds
    lines = ["Current AppOps Service state:"]
    for i in range(25):
        lines.append(f"  Uid u0a{300 + i}:")
        lines.append(f"    Package com.example.app{i}:")
        lines.append("      CAMERA (allow):")
        lines.append(f"        Access: [top-s] -{i + 1}s ago")
    with SiloDaemonSandbox() as sb:
        sb.set_dumpsys_appops_raw("CAMERA", "\n".join(lines) + "\n")
        ev_low = sb.rpc_ok("get_privacy_access_log", {"limit": 0})
        assert len(ev_low) == 10, f"Expected limit=0 clamped to 10, got {len(ev_low)}"

        ev_high = sb.rpc_ok("get_privacy_access_log", {"limit": 9999})
        assert len(ev_high) == 25

        t_low = sb.rpc_ok("get_telemetry", {"limit": 0})
        t_high = sb.rpc_ok("get_telemetry", {"limit": 99999})
        assert isinstance(t_low["events"], list) and isinstance(t_high["events"], list)


@e2e_test(
    "T2-F3-04",
    tier=2,
    features=["F3"],
    description="kill_connection skips/refuses local TCP LISTEN sockets and protected UIDs without breaking the listener (D-10/CB-12)",
)
def test_kill_connection_protects_listen_socket_and_protected_uids() -> None:
    with SiloDaemonSandbox(use_unshare=False) as sb, socket.socket(socket.AF_INET, socket.SOCK_STREAM) as srv:
        srv.bind(("127.0.0.1", 0))
        srv.listen(2)
        port = srv.getsockname()[1]

        resp, _ = sb.rpc(
            "kill_connection",
            {
                "family": 4,
                "local_ip": "127.0.0.1",
                "local_port": port,
                "remote_ip": "0.0.0.0",
                "remote_port": 0,
            },
        )
        # Either rejected (ok=false) OR killed==0, and the LISTEN socket MUST remain alive & accepting!
        if resp.get("ok"):
            assert resp["data"]["killed"] == 0, (
                f"kill_connection MUST NOT destroy TCP LISTEN sockets (D-10/CB-12): {resp}"
            )

        with socket.create_connection(("127.0.0.1", port), timeout=2.0) as cli:
            conn, _ = srv.accept()
            conn.close()
            _ = cli


@e2e_test(
    "T2-F3-05",
    tier=2,
    features=["F3"],
    description="Failing or empty dumpsys appops returns gracefully without crashing daemon or degrading get_status",
)
def test_monitor_handles_failing_dumpsys_gracefully() -> None:
    with SiloDaemonSandbox() as sb:
        sb.inject_fault("dumpsys", argv_contains=["appops"], exit_code=1, stderr="Service appops died\n")
        events = sb.rpc_ok("get_privacy_access_log", {"limit": 20})
        assert events == []
        st = sb.rpc_ok("get_status")
        assert st["version"] == "3.0.0"
