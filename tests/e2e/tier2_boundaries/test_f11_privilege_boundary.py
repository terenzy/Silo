"""Tier 2 (F11) — Protected Package & Process/IPC Privilege Boundary negative & corner tests."""

from __future__ import annotations

import os
import subprocess
import time

from tests.e2e.harness.registry import e2e_test
from tests.e2e.harness.silod_harness import SiloDaemonSandbox, run_cargo_unit_test


@e2e_test(
    "T2-F11-01",
    tier=2,
    features=["F11"],
    description="Duplicate silod contention: spawning 2 additional silod processes against active --socket results in 2 clean non-zero exits without breaking primary",
)
def test_duplicate_silod_contention_rejected_by_flock() -> None:
    with SiloDaemonSandbox() as sb:
        primary_ino = os.stat(sb.socket_path).st_ino
        dups: list[subprocess.Popen[bytes]] = []
        try:
            for _ in range(2):
                p = subprocess.Popen(
                    [
                        str(sb.silod_bin),
                        "--socket",
                        str(sb.socket_path),
                        "--config",
                        str(sb.config_path),
                        "--boot-marker",
                        str(sb.boot_marker_path),
                    ],
                    env=sb.build_env(),
                    stdin=subprocess.DEVNULL,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                )
                dups.append(p)
            for p in dups:
                rc = p.wait(timeout=8.0)
                assert rc != 0, (
                    f"Duplicate silod targeting locked socket MUST exit non-zero via flock (CB-07 / Contract §4), got rc={rc}"
                )
        finally:
            for p in dups:
                if p.poll() is None:
                    p.kill()
                    p.wait(timeout=2.0)

        assert sb.socket_path.exists()
        assert os.stat(sb.socket_path).st_ino == primary_ino, (
            "Failed duplicate silod MUST NOT unlink primary daemon's socket!"
        )
        assert sb.rpc_ok("ping")["pong"] is True


@e2e_test(
    "T2-F11-02",
    tier=2,
    features=["F11"],
    description="Protected UID boundary precision (9999/90000/99999/109999/190000 rejected; 10000/89999/110000/189999 accepted) & spoofed package_name defense",
)
def test_protected_uid_exact_boundaries_and_spoofed_pkg_defense() -> None:
    rejected_uids = [9999, 90000, 99999, 109999, 190000]
    accepted_uids = [10000, 89999, 110000, 189999]

    def _rule(pkg: str) -> dict:
        return {
            "package_name": pkg,
            "allow_wifi": False,
            "allow_cellular": True,
            "allow_vpn": True,
            "allow_lan": True,
            "allow_screen_off": True,
            "block_cleartext_http": False,
            "block_localhost": False,
            "is_vpn_app": False,
        }

    with SiloDaemonSandbox() as sb:
        # Protected UIDs with spoofed benign or empty package_name MUST fail
        for uid in rejected_uids:
            sb.rpc_err("set_app_firewall_rule", {"uid": uid, "rule": _rule("com.example.spoof")})
            sb.rpc_err("set_app_firewall_rule", {"uid": uid, "rule": _rule("")})

        # Protected package name paired with arbitrary non-protected UID MUST fail
        sb.rpc_err("set_app_firewall_rule", {"uid": 10555, "rule": _rule("dev.silo.app")})
        sb.rpc_err("set_app_firewall_rule", {"uid": 10556, "rule": _rule("com.rifsxd.ksunext")})

        # Exact accepted boundary UIDs MUST succeed
        for uid in accepted_uids:
            sb.rpc_ok("set_app_firewall_rule", {"uid": uid, "rule": _rule(f"com.example.u{uid}")})

        rules = sb.rpc_ok("get_config")["firewall"]["rules"]
        for uid in accepted_uids:
            assert str(uid) in rules
        for uid in rejected_uids:
            assert str(uid) not in rules


@e2e_test(
    "T2-F11-03",
    tier=2,
    features=["F11"],
    description="Shared-UID /data/system/packages.list index (narrow cargo test fallback): uid_to_pkgs blocks spoofed package_name bypass (D-10/CB-12)",
)
def test_packages_list_uid_to_pkgs_shared_uid_protection_cargo_fallback() -> None:
    run_cargo_unit_test("sys::", require_min_tests=1)


@e2e_test(
    "T2-F11-04",
    tier=2,
    features=["F11"],
    description="Malformed & hostile package_name inputs (shell metacharacters, path traversal, digit-start, empty segment, hyphen, 300 chars) rejected across all RPCs",
)
def test_hostile_package_names_rejected_across_all_rpcs() -> None:
    hostile = [
        "",
        "../etc/passwd",
        "com.a;id",
        "com.a\nrm",
        "com.a$(id)",
        "123.digit.start",
        "com..empty",
        "com.bad-dash",
        "com." + ("a" * 300),
    ]
    with SiloDaemonSandbox() as sb:
        for bad in hostile:
            sb.rpc_err(
                "set_app_permission_action",
                {"package_name": bad, "category": "camera", "action": "revoke"},
            )
            sb.rpc_err(
                "set_app_perm_guard",
                {"package_name": bad, "guard": {"auto_revoke_on_bg": [], "locked_deny": ["camera"]}},
            )
            sb.rpc_err(
                "set_freezer_entry",
                {
                    "uid": 10400,
                    "entry": {
                        "package_name": bad,
                        "mode": "deep_stop",
                        "freeze_on_screen_off": True,
                        "freeze_on_background": True,
                        "kill_sockets_on_freeze": False,
                    },
                },
            )
            sb.rpc_err("apply_hardened_scope", {"package_name": bad})
        assert sb.rpc_ok("ping")["pong"] is True


@e2e_test(
    "T2-F11-05",
    tier=2,
    features=["F11"],
    description="kill_uid_sockets refuses all protected system/root/isolated UIDs",
)
def test_kill_uid_sockets_refuses_protected_uids() -> None:
    with SiloDaemonSandbox() as sb:
        for uid in (0, 1000, 1051, 2000, 99000, 100000):
            sb.rpc_err("kill_uid_sockets", {"uid": uid})


@e2e_test(
    "T2-F11-06",
    tier=2,
    features=["F11"],
    description="Legacy scope RPCs (set_app_scope, apply_hardened_scope, clear_app_scope) reject protected packages and shell metacharacters",
)
def test_scope_rpcs_reject_protected_and_injection_inputs() -> None:
    with SiloDaemonSandbox() as sb:
        for bad in ("dev.silo.app", "com.android.systemui", "com.bad;id", "bad pkg"):
            sb.rpc_err("apply_hardened_scope", {"package_name": bad})
            sb.rpc_err("clear_app_scope", {"package_name": bad})
            sb.rpc_err(
                "set_app_scope",
                {
                    "package_name": bad,
                    "scope": {
                        "isolate_contacts": True,
                        "isolate_storage": False,
                        "isolate_sms_calllog": False,
                        "block_clipboard_read": True,
                        "restrict_sensors": False,
                        "restrict_background": False,
                        "isolate_location": False,
                        "block_camera_mic": False,
                    },
                },
            )
