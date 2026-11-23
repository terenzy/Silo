"""Tier 1 (F4) — App <-> Daemon IPC Bridge, Concurrency & UI Responsiveness happy-path E2E tests."""

from __future__ import annotations

import concurrent.futures
import json
import os
import stat
import tempfile
import threading
import time
from pathlib import Path

from tests.e2e.harness import android_harness
from tests.e2e.harness.registry import e2e_test
from tests.e2e.harness.silod_harness import SiloDaemonSandbox, UnixRpcClient


@e2e_test(
    "T1-F4-01",
    tier=1,
    features=["F4"],
    description="Raw AF_UNIX JSON-RPC ping echoes exact request id, returns version 3.0.0, and enforces 0600 socket mode",
)
def test_raw_socket_ping_id_echo_and_permissions() -> None:
    with SiloDaemonSandbox() as sb:
        sock_mode = stat.S_IMODE(os.stat(sb.socket_path).st_mode)
        assert sock_mode == 0o600, f"Socket permissions must be 0600, got {oct(sock_mode)}"
        dir_mode = stat.S_IMODE(os.stat(sb.state_dir).st_mode)
        assert dir_mode == 0o700, f"State dir permissions must be 0700, got {oct(dir_mode)}"

        client = sb.client()
        for rid in (1, 42, 987654321):
            resp, _ = client.call("ping", req_id=rid)
            assert resp["id"] == rid, f"Expected echoed id={rid}, got {resp}"
            assert resp["ok"] is True
            assert resp["data"]["pong"] is True
            assert resp["data"]["version"] == "3.0.0"


@e2e_test(
    "T1-F4-02",
    tier=1,
    features=["F4"],
    description="get_status returns in < 50ms with ZERO subprocesses even while a slow dumpsys RPC is in flight",
)
def test_get_status_cached_sub_50ms_and_nonblocking() -> None:
    with SiloDaemonSandbox() as sb:
        # Warm status once so any lazy cache initialization is complete
        st0 = sb.rpc_ok("get_status")
        assert st0["version"] == "3.0.0"

        # Inject a 1.5s delay on a single `dumpsys appops --op CAMERA` call so get_privacy_access_log stays in flight
        sb.inject_fault(
            "dumpsys",
            argv_contains=["appops", "CAMERA"],
            sleep_secs=1.5,
            exit_code=0,
            stdout="",
            once=True,
        )

        slow_done = threading.Event()
        slow_err: list[BaseException] = []

        def _slow_call() -> None:
            try:
                UnixRpcClient(sb.socket_path, timeout=15.0).call("get_privacy_access_log", req_id=100)
            except BaseException as exc:
                slow_err.append(exc)
            finally:
                slow_done.set()

        t = threading.Thread(target=_slow_call, daemon=True)
        t.start()
        time.sleep(0.25)  # ensure slow RPC is actively blocked in dumpsys

        logs_before = len(sb.read_cmd_log())
        resp, elapsed_ms = UnixRpcClient(sb.socket_path, timeout=5.0).call("get_status", req_id=200)
        logs_after = len(sb.read_cmd_log())

        assert resp["ok"] is True and resp["id"] == 200
        assert elapsed_ms < 50.0, (
            f"get_status must execute from CachedSystemState in <50ms without blocking on slow RPCs (D-04); took {elapsed_ms:.1f}ms"
        )
        # Allow 0 new subprocesses spawned by get_status itself (check no sensor_privacy/settings/getprop ran)
        new_tools = [
            e for e in sb.read_cmd_log()[logs_before:logs_after]
            if e["tool"] in ("settings", "getprop", "cmd") or (e["tool"] == "dumpsys" and "sensor_privacy" in e["argv"])
        ]
        assert not new_tools, (
            f"get_status MUST NOT spawn external subprocesses (D-04/CB-04), but observed: {new_tools}"
        )
        t.join(timeout=10.0)
        assert not slow_err, f"Background slow RPC failed unexpectedly: {slow_err}"


@e2e_test(
    "T1-F4-03",
    tier=1,
    features=["F4"],
    description="silod --bridge handles sequential RPCs over a single persistent stdio pipe with matching ids",
)
def test_bridge_sequential_rpcs() -> None:
    with SiloDaemonSandbox() as sb, sb.open_bridge() as br:
        r1 = br.call("ping", req_id=11)
        r2 = br.call("get_status", req_id=22)
        r3 = br.call("get_config", req_id=33)
        assert r1["id"] == 11 and r1["ok"] is True and r1["data"]["pong"] is True
        assert r2["id"] == 22 and r2["ok"] is True and r2["data"]["version"] == "3.0.0"
        assert r3["id"] == 33 and r3["ok"] is True and r3["data"]["schema_version"] == 3


@e2e_test(
    "T1-F4-04",
    tier=1,
    features=["F4"],
    description="silod --bridge transparently reconnects and succeeds on next RPC after silod is killed and restarted",
)
def test_bridge_transparent_reconnect_after_daemon_restart() -> None:
    with SiloDaemonSandbox() as sb, sb.open_bridge() as br:
        r1 = br.call("ping", req_id=1)
        assert r1["ok"] is True

        # Kill & restart silod on the exact same --socket while bridge stays open
        sb.restart()

        r2 = br.call("ping", req_id=2, timeout=15.0)
        assert r2["id"] == 2 and r2["ok"] is True and r2["data"]["pong"] is True, (
            f"silod --bridge must transparently reconnect+retry once when silod restarts (PROJECT.md §1): got {r2}"
        )


@e2e_test(
    "T1-F4-05",
    tier=1,
    features=["F4"],
    description="silod --once and --status exit 0 with valid JSON, and 8 concurrent AF_UNIX clients all succeed",
)
def test_cli_once_status_and_concurrent_clients() -> None:
    with SiloDaemonSandbox() as sb:
        once_res = sb.run_cli(
            ["--once", '{"id":7,"cmd":"ping"}', "--socket", str(sb.socket_path)],
            use_unshare=False,
        )
        assert once_res.returncode == 0, f"--once failed: {once_res}"
        once_json = json.loads(once_res.stdout.strip())
        assert once_json["id"] == 7 and once_json["ok"] is True

        st_res = sb.run_cli(["--status", "--socket", str(sb.socket_path)], use_unshare=False)
        assert st_res.returncode == 0, f"--status failed: {st_res}"
        st_json = json.loads(st_res.stdout.strip())
        assert st_json["ok"] is True and st_json["data"]["version"] == "3.0.0"

        def _worker(idx: int) -> dict:
            resp, _ = UnixRpcClient(sb.socket_path, timeout=10.0).call("ping", req_id=1000 + idx)
            return resp

        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            futs = [pool.submit(_worker, i) for i in range(8)]
            results = [f.result(timeout=15.0) for f in futs]
        for idx, resp in enumerate(results):
            assert resp["ok"] is True and resp["id"] == 1000 + idx


@e2e_test(
    "T1-F4-06",
    tier=1,
    features=["F4"],
    description="Real compiled Kotlin SiloClient talks end-to-end through real silod --bridge to live silod and bytecode uses getErrorStream + destroyForcibly",
)
def test_real_kotlin_silo_client_e2e_and_bytecode() -> None:
    with SiloDaemonSandbox() as sb, tempfile.TemporaryDirectory(prefix="silo_su_") as td:
        su_dir = Path(td) / "bin"
        android_harness.install_su_bridge_wrapper(
            su_dir, silod_bin=sb.silod_bin, socket_path=sb.socket_path
        )
        st = android_harness.run_jvm_contract(
            ["silo_client_call", "get_status", "null", "10000", "DaemonStatus"],
            extra_path_dir=su_dir,
        )
        assert st["model"] == "DaemonStatus" and st["version"] == "3.0.0"

        cfg = android_harness.run_jvm_contract(
            ["silo_client_call", "get_config", "null", "10000", "SiloConfig"],
            extra_path_dir=su_dir,
        )
        assert cfg["model"] == "SiloConfig" and cfg["schema_version"] == 3

        # Verify SiloClient compiled bytecode drains stderr and uses destroyForcibly (APP-H02 / APP-M11)
        disasm = android_harness.javap_disassemble_classes(
            android_harness.KOTLIN_DEBUG_CLASSES, "dev/silo/app/ipc/SiloClient"
        )
        assert "java/lang/Process.getErrorStream:()Ljava/io/InputStream;" in disasm, (
            "SiloClient bytecode MUST drain Process.getErrorStream() on background thread (APP-H02)"
        )
        assert "java/lang/Process.destroyForcibly:()Ljava/lang/Process;" in disasm, (
            "SiloClient bytecode MUST invoke Process.destroyForcibly() on teardown (APP-M11)"
        )
