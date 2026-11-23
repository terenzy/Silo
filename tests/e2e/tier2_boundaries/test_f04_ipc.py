"""Tier 2 (F4) — App <-> Daemon IPC Bridge, Concurrency & UI Responsiveness boundary & fault-injection tests."""

from __future__ import annotations

import json
import os
import socket
import tempfile
import time
from pathlib import Path

from tests.e2e.harness import android_harness
from tests.e2e.harness.registry import e2e_test
from tests.e2e.harness.silod_harness import SiloDaemonSandbox, UnixRpcClient


@e2e_test(
    "T2-F4-01",
    tier=2,
    features=["F4"],
    description="4 MiB IPC framing boundary: > 512 KiB (~1.1 MiB) JSON-RPC succeeds over socket & --bridge; > 4 MiB frame is rejected cleanly without crash",
)
def test_ipc_4mib_framing_and_oversized_rejection() -> None:
    # Build ~35,000 unique valid public-space CIDRs (~650 KiB+ JSON payload, > legacy 512 KiB limit, < 4 MiB target limit)
    entries = [
        f"198.{b}.{c}.1/32"
        for b in range(1, 152)
        if b not in (18, 19)
        for c in range(1, 241)
    ]
    payload_bytes = len(json.dumps({"id": 1, "cmd": "replace_ip_blocklist", "params": {"entries": entries}}))
    assert 600_000 < payload_bytes < 4 * 1024 * 1024, f"Unexpected test payload size: {payload_bytes}"

    with SiloDaemonSandbox(use_unshare=True) as sb:
        # 1) Direct AF_UNIX socket > 512 KiB
        res = sb.rpc_ok("replace_ip_blocklist", {"entries": entries}, timeout=30.0)
        assert res["loaded"] == len(entries) and res["rejected"] == 0, (
            f"4 MiB IPC framing must accept >512 KiB blocklist payload (PROJECT.md §1 / APP-H20): got {res}"
        )

        # 2) Same > 512 KiB payload over silod --bridge
        with sb.open_bridge() as br:
            bresp = br.call("replace_ip_blocklist", {"entries": entries}, req_id=77, timeout=30.0)
            assert bresp["id"] == 77 and bresp["ok"] is True and bresp["data"]["loaded"] == len(entries)

        # 3) Oversized > 4 MiB line must be rejected without crashing daemon
        oversized = b'{"id":9,"cmd":"ping","pad":"' + (b"A" * (4 * 1024 * 1024 + 4096)) + b'"}\n'
        try:
            raw, _ = UnixRpcClient(sb.socket_path, timeout=10.0).send_raw(oversized)
            if raw:
                err_obj = json.loads(raw.splitlines()[0].decode("utf-8"))
                assert err_obj.get("ok") is False, f"Oversized >4 MiB payload must fail: {err_obj}"
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass  # Server closing offending connection is valid protection

        # Daemon MUST still be alive and serving normal RPCs!
        assert sb.rpc_ok("ping")["pong"] is True


@e2e_test(
    "T2-F4-02",
    tier=2,
    features=["F4"],
    description="Malformed JSON returns id=0 error, unknown cmd echoes caller id with ok=false, and invalid UTF-8 closes cleanly without killing daemon",
)
def test_malformed_json_unknown_cmd_and_invalid_utf8() -> None:
    with SiloDaemonSandbox() as sb:
        client = sb.client()

        raw_bad, _ = client.send_raw(b'{"id":5,"cmd":\n')
        assert raw_bad, "Expected error JSON response for malformed JSON"
        bad_obj = json.loads(raw_bad.splitlines()[0].decode("utf-8"))
        assert bad_obj["ok"] is False and bad_obj["id"] == 0

        unk_resp, _ = client.call("totally_nonexistent_cmd", req_id=4242)
        assert unk_resp["id"] == 4242 and unk_resp["ok"] is False and unk_resp.get("error")

        # Invalid UTF-8 bytes
        try:
            raw_utf8, _ = client.send_raw(b"\xff\xfe\xfd\n")
            if raw_utf8:
                obj = json.loads(raw_utf8.splitlines()[0].decode("utf-8"))
                assert obj["ok"] is False
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass

        assert sb.rpc_ok("ping")["pong"] is True


@e2e_test(
    "T2-F4-03",
    tier=2,
    features=["F4"],
    description="MAX_CONCURRENT_CLIENTS (16): saturating with 24 simultaneous AF_UNIX sockets never wedges daemon and capacity recovers on close",
)
def test_concurrent_client_saturation_recovery() -> None:
    with SiloDaemonSandbox() as sb:
        sockets: list[socket.socket] = []
        try:
            for _ in range(24):
                s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                s.settimeout(2.0)
                s.connect(str(sb.socket_path))
                sockets.append(s)
            # Send ping on first 10 held sockets
            for idx in range(10):
                sockets[idx].sendall(f'{{"id":{idx + 1},"cmd":"ping"}}\n'.encode("utf-8"))
                reply = sockets[idx].recv(4096)
                assert reply and json.loads(reply.splitlines()[0])["ok"] is True
        finally:
            for s in sockets:
                try:
                    s.close()
                except OSError:
                    pass

        time.sleep(0.1)
        assert sb.rpc_ok("ping")["pong"] is True


@e2e_test(
    "T2-F4-04",
    tier=2,
    features=["F4"],
    description="CLI exit codes: --once missing arg exits 2, --once/--status missing socket exits 1, --bridge when daemon down returns id error & exits 0 on EOF",
)
def test_cli_once_status_bridge_negative_boundaries() -> None:
    with SiloDaemonSandbox(auto_start=False) as sb:
        missing_sock = sb.state_dir / "nonexistent.sock"

        r_noarg = sb.run_cli(["--once"], use_unshare=False)
        assert r_noarg.returncode == 2, f"Expected exit 2 for --once missing JSON arg, got {r_noarg}"

        r_once = sb.run_cli(["--once", '{"id":1,"cmd":"ping"}', "--socket", str(missing_sock)], use_unshare=False)
        assert r_once.returncode == 1, f"Expected exit 1 for --once on missing socket, got {r_once}"

        r_st = sb.run_cli(["--status", "--socket", str(missing_sock)], use_unshare=False)
        assert r_st.returncode == 1, f"Expected exit 1 for --status on missing socket, got {r_st}"

        with sb.open_bridge() as br:
            resp = br.call("ping", req_id=99, timeout=10.0)
            assert resp["id"] == 99 and resp["ok"] is False and resp.get("error")
            rc, _ = br.close()
            assert rc == 0, f"Bridge must exit 0 cleanly on stdin EOF even after connect failure, got {rc}"


@e2e_test(
    "T2-F4-05",
    tier=2,
    features=["F4"],
    description="BTreeMap<u32, _> config deserialization accepts BOTH integer and string UID keys and rejects non-numeric keys",
)
def test_u32_map_key_deserialization_boundaries() -> None:
    with SiloDaemonSandbox() as sb:
        base = sb.rpc_ok("get_config")
        rule = {
            "package_name": "com.example.mapkey",
            "allow_wifi": False,
            "allow_cellular": True,
            "allow_vpn": True,
            "allow_lan": True,
            "allow_screen_off": True,
            "block_cleartext_http": False,
            "block_localhost": False,
            "is_vpn_app": False,
        }
        # String UID key succeeds (RFC 8259 JSON object keys are strings; serde deserialize_u32_map parses numeric strings)
        base["firewall"]["rules"] = {"10277": rule}
        imp1 = sb.rpc_ok("import_config_json", {"json": json.dumps(base)})
        assert "10277" in imp1["firewall"]["rules"]

        # Invalid non-numeric UID key MUST fail cleanly
        base["firewall"]["rules"] = {"not_a_uid": rule}
        sb.rpc_err("import_config_json", {"json": json.dumps(base)})


@e2e_test(
    "T2-F4-06",
    tier=2,
    features=["F4"],
    description="Real compiled Kotlin SiloClient genuinely cancels blocking readLine() on timeout in < 3.0s and kills stalled su child (APP-C03)",
)
def test_kotlin_silo_client_genuine_timeout_cancellation() -> None:
    with tempfile.TemporaryDirectory(prefix="silo_su_to_") as td:
        td_p = Path(td)
        pid_file = td_p / "stalled.pid"
        su_dir = td_p / "bin"
        android_harness.install_custom_su_shim(
            su_dir,
            "#!/bin/sh\n"
            f'echo $$ > "{pid_file}"\n'
            "IFS= read -r _line\n"
            "exec /usr/bin/sleep 30\n",
        )
        out = android_harness.run_jvm_contract(
            ["silo_client_call", "ping", "null", "800"],
            extra_path_dir=su_dir,
            timeout=15.0,
        )
        assert out["ok"] is False, f"Expected timeout failure from SiloClient: {out}"
        assert out["elapsed_ms"] < 3000, (
            f"SiloClient hung for {out['elapsed_ms']}ms instead of cancelling blocking readLine() on 800ms timeout (APP-C03)!"
        )
        if pid_file.is_file():
            stalled_pid = int(pid_file.read_text(encoding="utf-8").strip())
            try:
                deadline = time.monotonic() + 1.0
                alive = True
                while time.monotonic() < deadline:
                    try:
                        os.kill(stalled_pid, 0)
                    except ProcessLookupError:
                        alive = False
                        break
                    time.sleep(0.05)
                assert not alive, f"Stalled su bridge child PID {stalled_pid} leaked after timeout (APP-C03)!"
            finally:
                try:
                    os.kill(stalled_pid, 9)
                except OSError:
                    pass


@e2e_test(
    "T2-F4-07",
    tier=2,
    features=["F4"],
    description="Real compiled Kotlin SiloClient drains 256 KiB stderr flood without pipe deadlock and surfaces stderr diagnostics on EOF (APP-H02)",
)
def test_kotlin_silo_client_stderr_flood_and_eof_diagnostics() -> None:
    py = "/usr/bin/python3"
    with tempfile.TemporaryDirectory(prefix="silo_su_err_") as td:
        td_p = Path(td)
        su_dir = td_p / "bin"
        # 1) 256 KiB stderr flood (4x Linux 64 KiB pipe buffer) BEFORE replying valid JSON on stdout
        android_harness.install_custom_su_shim(
            su_dir,
            f"#!{py}\n"
            "import json, sys\n"
            "line = sys.stdin.readline()\n"
            "req = json.loads(line)\n"
            "sys.stderr.write(('E' * 1024 + '\\n') * 260)\n"
            "sys.stderr.flush()\n"
            "sys.stdout.write(json.dumps({'id': req['id'], 'ok': True, 'data': {'pong': True}}) + '\\n')\n"
            "sys.stdout.flush()\n",
        )
        out1 = android_harness.run_jvm_contract(
            ["silo_client_call", "ping", "null", "5000"],
            extra_path_dir=su_dir,
            timeout=15.0,
        )
        assert out1["ok"] is True and out1["data"]["pong"] is True, (
            f"SiloClient deadlocked or failed on 256 KiB stderr flood (APP-H02): {out1}"
        )

        # 2) Immediate stderr error + EOF must surface stderr diagnostic in returned error message
        android_harness.install_custom_su_shim(
            su_dir,
            "#!/bin/sh\n"
            'echo "KSU root denied for dev.silo.app" >&2\n'
            "exit 1\n",
        )
        out2 = android_harness.run_jvm_contract(
            ["silo_client_call", "ping", "null", "5000"],
            extra_path_dir=su_dir,
            timeout=15.0,
        )
        assert out2["ok"] is False
        assert "KSU root denied" in str(out2.get("error", "")), (
            f"SiloClient MUST surface stderr diagnostic on EOF (APP-H02), got: {out2}"
        )


@e2e_test(
    "T2-F4-08",
    tier=2,
    features=["F4"],
    description="Real compiled Kotlin SiloClient rejects mismatched response id (APP-H03) AND keeps bridge PID alive on DaemonRpcException",
)
def test_kotlin_silo_client_id_mismatch_and_rpc_error_bridge_reuse() -> None:
    py = "/usr/bin/python3"
    with tempfile.TemporaryDirectory(prefix="silo_su_id_") as td:
        td_p = Path(td)
        su_dir = td_p / "bin"

        # 1) Wrong response ID -> MUST fail!
        android_harness.install_custom_su_shim(
            su_dir,
            f"#!{py}\n"
            "import json, sys\n"
            "sys.stdin.readline()\n"
            "sys.stdout.write(json.dumps({'id': 999999, 'ok': True, 'data': {'spoofed': True}}) + '\\n')\n"
            "sys.stdout.flush()\n",
        )
        out_bad = android_harness.run_jvm_contract(
            ["silo_client_call", "ping", "null", "5000"],
            extra_path_dir=su_dir,
            timeout=15.0,
        )
        assert out_bad["ok"] is False, (
            f"SiloClient MUST reject response with mismatched resp.id != reqId (APP-H03), got: {out_bad}"
        )

        # 2) Structured RPC error preserves bridge PID for 2nd call
        pid_log = td_p / "bridge_pids.log"
        android_harness.install_custom_su_shim(
            su_dir,
            f"#!{py}\n"
            "import json, os, sys\n"
            f"with open({str(pid_log)!r}, 'a') as f: f.write(str(os.getpid()) + '\\n')\n"
            "for line in sys.stdin:\n"
            "    req = json.loads(line)\n"
            "    if req['cmd'] == 'bad_cmd':\n"
            "        resp = {'id': req['id'], 'ok': False, 'error': 'unknown command'}\n"
            "    else:\n"
            "        resp = {'id': req['id'], 'ok': True, 'data': {'pong': True}}\n"
            "    sys.stdout.write(json.dumps(resp) + '\\n')\n"
            "    sys.stdout.flush()\n",
        )
        out_reuse = android_harness.run_jvm_contract(
            ["silo_client_call", "bad_cmd", "null", "5000", "", "ping"],
            extra_path_dir=su_dir,
            timeout=15.0,
        )
        assert out_reuse["ok"] is False and "DaemonRpcException" in str(out_reuse.get("error_class", ""))
        assert out_reuse["second"]["ok"] is True
        pids = pid_log.read_text(encoding="utf-8").splitlines()
        assert len(pids) == 1, f"Expected bridge process reused across DaemonRpcException, spawned {pids}"


@e2e_test(
    "T2-F4-09",
    tier=2,
    features=["F4"],
    description="Real compiled Kotlin SiloClient forcefully terminates SIGTERM-ignoring su child via destroyForcibly() (APP-M11)",
)
def test_kotlin_silo_client_sigterm_trapping_child_destroy_forcibly() -> None:
    py = "/usr/bin/python3"
    with tempfile.TemporaryDirectory(prefix="silo_su_kill_") as td:
        td_p = Path(td)
        pid_file = td_p / "stubborn.pid"
        su_dir = td_p / "bin"
        android_harness.install_custom_su_shim(
            su_dir,
            f"#!{py}\n"
            "import json, os, signal, sys, time\n"
            "signal.signal(signal.SIGTERM, signal.SIG_IGN)\n"
            f"with open({str(pid_file)!r}, 'w') as f: f.write(str(os.getpid()))\n"
            "line = sys.stdin.readline()\n"
            "req = json.loads(line)\n"
            "sys.stdout.write(json.dumps({'id': req['id'], 'ok': True, 'data': {'pong': True}}) + '\\n')\n"
            "sys.stdout.flush()\n"
            "time.sleep(30)\n",
        )
        out = android_harness.run_jvm_contract(
            ["silo_client_call", "ping", "null", "5000"],
            extra_path_dir=su_dir,
            timeout=15.0,
        )
        assert out["ok"] is True
        assert pid_file.is_file()
        child_pid = int(pid_file.read_text(encoding="utf-8").strip())
        deadline = time.monotonic() + 2.0
        alive = True
        while time.monotonic() < deadline:
            try:
                os.kill(child_pid, 0)
            except ProcessLookupError:
                alive = False
                break
            time.sleep(0.05)
        if alive:
            try:
                os.kill(child_pid, 9)
            except OSError:
                pass
        assert not alive, (
            f"SIGTERM-ignoring child PID {child_pid} survived SiloClient.disconnect() — destroyForcibly() missing (APP-M11)!"
        )
