"""Tier 1 (F11) — Protected Package & Process/IPC Privilege Boundary happy-path E2E tests."""

from __future__ import annotations

import fcntl
import os
import signal
import socket
import stat
import time
from pathlib import Path

from tests.e2e.harness.registry import e2e_test
from tests.e2e.harness.silod_harness import PROTECTED_PACKAGES, SiloDaemonSandbox


@e2e_test(
    "T1-F11-01",
    tier=1,
    features=["F11"],
    description="silod creates state dir 0700 and AF_UNIX socket 0600 (with umask 0077) and authenticates same-UID SO_PEERCRED client",
)
def test_socket_permissions_and_peercred_auth() -> None:
    with SiloDaemonSandbox() as sb:
        dmode = stat.S_IMODE(os.stat(sb.state_dir).st_mode)
        smode = stat.S_IMODE(os.stat(sb.socket_path).st_mode)
        assert dmode == 0o700, f"Expected 0700 state dir, got {oct(dmode)}"
        assert smode == 0o600, f"Expected 0600 socket mode, got {oct(smode)}"

        resp, _ = sb.rpc("ping", req_id=555)
        assert resp["ok"] is True and resp["id"] == 555


@e2e_test(
    "T1-F11-02",
    tier=1,
    features=["F11"],
    description="silod holds exclusive flock on silod.lock for its lifetime and releases cleanly on SIGTERM",
)
def test_flock_singleton_lock_lifecycle() -> None:
    with SiloDaemonSandbox() as sb:
        assert sb.lock_path.is_file(), (
            f"Expected single-instance flock file at {sb.lock_path} (CB-07 / Contract §4)"
        )
        with open(sb.lock_path, "r+", encoding="utf-8") as lf:
            blocked = False
            try:
                fcntl.flock(lf.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                blocked = True
            else:
                fcntl.flock(lf.fileno(), fcntl.LOCK_UN)
            assert blocked, (
                f"Expected silod to hold exclusive flock on {sb.lock_path} while running!"
            )

        rc = sb.stop(sig=signal.SIGTERM, wait_secs=5.0)
        assert rc == 0, f"Expected clean SIGTERM exit 0, got {rc}"

        # Lock must now be acquirable and a fresh silod instance starts cleanly
        with open(sb.lock_path, "r+", encoding="utf-8") as lf:
            fcntl.flock(lf.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            fcntl.flock(lf.fileno(), fcntl.LOCK_UN)
        sb.start()
        assert sb.rpc_ok("ping")["pong"] is True


@e2e_test(
    "T1-F11-03",
    tier=1,
    features=["F11"],
    description="Firewall and Freezer RPCs reject ALL protected system/isolated/multi-user UIDs",
)
def test_protected_uids_rejected_across_firewall_and_freezer() -> None:
    protected_uids = [0, 1000, 1051, 2000, 9999, 90000, 99000, 100000, 101000, 199000]
    with SiloDaemonSandbox() as sb:
        for uid in protected_uids:
            sb.rpc_err(
                "set_app_firewall_rule",
                {
                    "uid": uid,
                    "rule": {
                        "package_name": "com.example.fake",
                        "allow_wifi": False,
                        "allow_cellular": False,
                        "allow_vpn": False,
                        "allow_lan": False,
                        "allow_screen_off": False,
                        "block_cleartext_http": True,
                        "block_localhost": True,
                        "is_vpn_app": False,
                    },
                },
            )
            sb.rpc_err("set_temp_unblock", {"uid": uid, "duration_minutes": 10})
            sb.rpc_err("kill_uid_sockets", {"uid": uid})
            sb.rpc_err(
                "set_freezer_entry",
                {
                    "uid": uid,
                    "entry": {
                        "package_name": "com.example.fake",
                        "mode": "deep_stop",
                        "freeze_on_screen_off": True,
                        "freeze_on_background": True,
                        "kill_sockets_on_freeze": True,
                    },
                },
            )
            sb.rpc_err("freeze_uid_now", {"uid": uid})

        cfg = sb.rpc_ok("get_config")
        assert cfg["firewall"]["rules"] == {}, f"Protected UID rules leaked into config: {cfg['firewall']['rules']}"
        assert cfg["freezer"]["entries"] == {}, f"Protected UID entries leaked into freezer: {cfg['freezer']['entries']}"


@e2e_test(
    "T1-F11-04",
    tier=1,
    features=["F11"],
    description="All 19 PROTECTED_PACKAGES are rejected across Firewall, Freezer, Permission, Guard, and Scope RPCs",
)
def test_all_19_protected_packages_rejected() -> None:
    with SiloDaemonSandbox() as sb:
        for idx, pkg in enumerate(PROTECTED_PACKAGES):
            uid = 10500 + idx
            sb.rpc_err(
                "set_app_firewall_rule",
                {
                    "uid": uid,
                    "rule": {
                        "package_name": pkg,
                        "allow_wifi": False,
                        "allow_cellular": False,
                        "allow_vpn": False,
                        "allow_lan": False,
                        "allow_screen_off": False,
                        "block_cleartext_http": False,
                        "block_localhost": False,
                        "is_vpn_app": False,
                    },
                },
            )
            sb.rpc_err(
                "set_freezer_entry",
                {
                    "uid": uid,
                    "entry": {
                        "package_name": pkg,
                        "mode": "deep_stop",
                        "freeze_on_screen_off": True,
                        "freeze_on_background": True,
                        "kill_sockets_on_freeze": True,
                    },
                },
            )
            sb.rpc_err(
                "set_app_permission_action",
                {"package_name": pkg, "category": "camera", "action": "revoke"},
            )
            sb.rpc_err(
                "set_app_perm_guard",
                {"package_name": pkg, "guard": {"auto_revoke_on_bg": [], "locked_deny": ["camera"]}},
            )
            sb.rpc_err("apply_hardened_scope", {"package_name": pkg})


@e2e_test(
    "T1-F11-05",
    tier=1,
    features=["F11"],
    description="Clean SIGTERM shutdown unlinks silod.sock ONLY if its (st_dev, st_ino) matches the bound socket (CB-07 / Contract §4)",
)
def test_socket_inode_verified_unlink_on_shutdown() -> None:
    with SiloDaemonSandbox() as sb:
        st1 = os.stat(sb.socket_path)
        ino_1 = st1.st_ino

        # Replace socket path with a brand-new AF_UNIX socket (different inode)
        os.unlink(sb.socket_path)
        replacement = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            replacement.bind(str(sb.socket_path))
            replacement.listen(1)
            ino_2 = os.stat(sb.socket_path).st_ino
            assert ino_2 != ino_1

            rc = sb.stop(sig=signal.SIGTERM, wait_secs=5.0)
            assert rc == 0, f"Expected clean SIGTERM exit 0, got {rc}"

            assert sb.socket_path.exists(), (
                "silod MUST NOT unlink a replaced socket file whose inode does not match its own bound socket (CB-07)!"
            )
            assert os.stat(sb.socket_path).st_ino == ino_2
        finally:
            replacement.close()


@e2e_test(
    "T1-F11-06",
    tier=1,
    features=["F11"],
    description="run_cmd process-group timeout kills grandchild holding stdout/stderr open without hanging silod (CB-17)",
)
def test_run_cmd_process_group_timeout_kills_grandchild() -> None:
    with SiloDaemonSandbox() as sb:
        gpid_file = sb.root / "grandchild.pid"
        sb.inject_fault(
            "dumpsys",
            argv_contains=["package"],
            exit_code=0,
            sleep_secs=25.0,
            spawn_grandchild_secs=25.0,
            grandchild_pid_file=str(gpid_file),
        )
        t0 = time.monotonic()
        # inspect_app_permissions runs dumpsys package (and cmd appops get); timeout in run_cmd should abort well before 25s!
        sb.rpc("inspect_app_permissions", {"package_name": "com.example.slowchild"}, timeout=20.0)
        elapsed = time.monotonic() - t0
        assert elapsed < 18.0, (
            f"RPC hung for {elapsed:.1f}s waiting on grandchild holding stdout pipe (CB-17 process-group kill missing!)"
        )
        assert gpid_file.is_file(), "Expected dumpsys fault shim to spawn grandchild and record its PID"
        gpid = int(gpid_file.read_text(encoding="utf-8").strip())
        try:
            # Verify grandchild process was actually killed by kill(-pid, SIGKILL)
            deadline = time.monotonic() + 1.0
            alive = True
            while time.monotonic() < deadline:
                try:
                    os.kill(gpid, 0)
                except ProcessLookupError:
                    alive = False
                    break
                time.sleep(0.05)
            assert not alive, (
                f"Grandchild PID {gpid} survived run_cmd timeout — setpgid(0,0) + kill(-pid, SIGKILL) missing (CB-17)!"
            )
        finally:
            try:
                os.kill(gpid, signal.SIGKILL)
            except OSError:
                pass
