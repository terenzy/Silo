"""Tier 1 (F2) — Permission Enforcer (`Kunci Blokir` & `Auto-Cabut`) happy-path E2E tests."""

from __future__ import annotations

import json
import tempfile
import time
from pathlib import Path

from tests.e2e.harness import android_harness
from tests.e2e.harness.registry import e2e_test
from tests.e2e.harness.silod_harness import SiloDaemonSandbox


@e2e_test(
    "T1-F2-01",
    tier=1,
    features=["F2"],
    description="set_app_perm_guard locked_deny persists config and immediately revokes + applies user-fixed permission flags + --uid ignore",
)
def test_locked_deny_persists_and_applies_user_fixed() -> None:
    pkg = "com.example.kunci"
    with SiloDaemonSandbox() as sb:
        sb.set_package_appops(pkg, "CAMERA", uid_mode="allow", pkg_mode="allow")
        sb.set_runtime_perm(pkg, "android.permission.CAMERA", granted=True)

        scopes = sb.rpc_ok(
            "set_app_perm_guard",
            {
                "package_name": pkg,
                "guard": {"auto_revoke_on_bg": [], "locked_deny": ["camera"]},
            },
        )
        assert "camera" in scopes["guards"][pkg]["locked_deny"]

        # Verify persisted in config.json on disk
        cfg = json.loads(sb.config_path.read_text(encoding="utf-8"))
        assert "camera" in cfg["scopes"]["guards"][pkg]["locked_deny"]

        # Verify immediate revoke + user-fixed flag + --uid AppOps ignore
        logs = sb.read_cmd_log()
        assert any(e["tool"] == "pm" and "revoke" in e["argv"] and pkg in e["argv"] for e in logs), (
            f"Expected immediate pm revoke on locked_deny: {logs}"
        )
        assert any(
            (e["tool"] in ("pm", "cmd"))
            and "set-permission-flags" in e["argv"]
            and pkg in e["argv"]
            and any("user-fixed" in a for a in e["argv"])
            for e in logs
        ), f"Expected proactive `set-permission-flags ... user-fixed` on locked_deny (PROJECT.md §2): {logs}"

        st = sb.read_android_state()["packages"][pkg]
        assert st["runtime_perms"]["android.permission.CAMERA"]["granted"] is False
        assert "USER_FIXED" in st["runtime_perms"]["android.permission.CAMERA"]["flags"]
        assert st["appops"]["CAMERA"].get("uid_mode") in ("ignore", "deny")


@e2e_test(
    "T1-F2-02",
    tier=1,
    features=["F2"],
    description="Removing a category from locked_deny invokes clear-permission-flags user-fixed",
)
def test_removing_locked_deny_clears_user_fixed() -> None:
    pkg = "com.example.unlockme"
    with SiloDaemonSandbox() as sb:
        sb.set_runtime_perm(pkg, "android.permission.CAMERA", granted=True)
        sb.rpc_ok(
            "set_app_perm_guard",
            {"package_name": pkg, "guard": {"auto_revoke_on_bg": [], "locked_deny": ["camera"]}},
        )

        # Unlock by clearing locked_deny
        scopes = sb.rpc_ok(
            "set_app_perm_guard",
            {"package_name": pkg, "guard": {"auto_revoke_on_bg": [], "locked_deny": []}},
        )
        assert pkg not in scopes["guards"], f"Empty guard should be pruned: {scopes['guards']}"

        logs = sb.read_cmd_log()
        assert any(
            (e["tool"] in ("pm", "cmd"))
            and "clear-permission-flags" in e["argv"]
            and pkg in e["argv"]
            and any("user-fixed" in a for a in e["argv"])
            for e in logs
        ), f"Expected `clear-permission-flags ... user-fixed` when unlocking locked_deny: {logs}"

        flags = sb.read_android_state()["packages"][pkg]["runtime_perms"]["android.permission.CAMERA"]["flags"]
        assert "USER_FIXED" not in flags, f"USER_FIXED should be cleared after unlock: {flags}"


@e2e_test(
    "T1-F2-03",
    tier=1,
    features=["F2"],
    description="Manual set_app_permission_action allow overrides and auto-removes category from locked_deny persistently",
)
def test_manual_allow_clears_locked_deny_conflict() -> None:
    pkg = "com.example.override"
    with SiloDaemonSandbox() as sb:
        sb.set_runtime_perm(pkg, "android.permission.CAMERA", granted=True)
        sb.set_runtime_perm(pkg, "android.permission.RECORD_AUDIO", granted=True)
        sb.rpc_ok(
            "set_app_perm_guard",
            {
                "package_name": pkg,
                "guard": {"auto_revoke_on_bg": [], "locked_deny": ["camera", "microphone"]},
            },
        )

        # Explicitly set camera back to allow
        sb.rpc_ok(
            "set_app_permission_action",
            {"package_name": pkg, "category": "camera", "action": "allow"},
        )

        cfg = sb.rpc_ok("get_config")
        locked = cfg["scopes"]["guards"].get(pkg, {}).get("locked_deny", [])
        assert "camera" not in locked, (
            f"Manual allow MUST auto-remove 'camera' from locked_deny so Kunci Blokir doesn't fight user: {locked}"
        )
        assert "microphone" in locked, f"Unrelated locked_deny category 'microphone' must stay intact: {locked}"

        # Confirm persisted on disk
        disk_cfg = json.loads(sb.config_path.read_text(encoding="utf-8"))
        assert "camera" not in disk_cfg["scopes"]["guards"][pkg]["locked_deny"]


@e2e_test(
    "T1-F2-04",
    tier=1,
    features=["F2"],
    description="Restarting silod with persisted locked_deny enforces lock proactively on startup within 4s (not waiting 15s)",
)
def test_locked_deny_enforced_on_daemon_startup() -> None:
    pkg = "com.example.bootlock"
    with SiloDaemonSandbox() as sb:
        sb.set_runtime_perm(pkg, "android.permission.RECORD_AUDIO", granted=True)
        sb.rpc_ok(
            "set_app_perm_guard",
            {"package_name": pkg, "guard": {"auto_revoke_on_bg": [], "locked_deny": ["microphone"]}},
        )

        # Simulate OS/user re-granting mic while daemon was stopped
        sb.stop()
        sb.set_runtime_perm(pkg, "android.permission.RECORD_AUDIO", granted=True, flags=[])
        sb.set_package_appops(pkg, "RECORD_AUDIO", uid_mode="allow", pkg_mode="allow")
        log_before = len(sb.read_cmd_log())

        t0 = time.monotonic()
        sb.start()

        # Poll up to 4.5s — remediated enforcer must enforce on startup rather than 15-20s sleep!
        enforced = False
        while time.monotonic() - t0 < 4.5:
            new_logs = sb.read_cmd_log()[log_before:]
            if any(
                e["tool"] == "pm" and "revoke" in e["argv"] and pkg in e["argv"]
                for e in new_logs
            ):
                enforced = True
                break
            time.sleep(0.15)
        assert enforced, (
            f"Expected startup enforcement of locked_deny within 4.5s (not 15s!); "
            f"new_logs={sb.read_cmd_log()[log_before:]}"
        )


@e2e_test(
    "T1-F2-05",
    tier=1,
    features=["F2"],
    description="set_app_perm_guard auto_revoke_on_bg updates DaemonStatus.enforcer_rules_count and survives restart",
)
def test_auto_revoke_guard_status_and_restart_persistence() -> None:
    pkg = "com.example.autocabut"
    with SiloDaemonSandbox() as sb:
        sb.rpc_ok(
            "set_app_perm_guard",
            {"package_name": pkg, "guard": {"auto_revoke_on_bg": ["location"], "locked_deny": []}},
        )
        st = sb.rpc_ok("get_status")
        assert st["enforcer_rules_count"] >= 1, f"Expected enforcer_rules_count >= 1, got {st}"

        sb.restart()
        cfg = sb.rpc_ok("get_config")
        assert cfg["scopes"]["guards"][pkg]["auto_revoke_on_bg"] == ["location"]
        st2 = sb.rpc_ok("get_status")
        assert st2["enforcer_rules_count"] >= 1


@e2e_test(
    "T1-F2-06",
    tier=1,
    features=["F2"],
    description="Live silod set_app_perm_guard deserializes cleanly into compiled Kotlin ScopesConfig and SiloStrings has honest pm-revoke copy",
)
def test_kotlin_scopes_config_and_honest_revoke_copy() -> None:
    pkg = "com.example.guardkt"
    with SiloDaemonSandbox() as sb, tempfile.TemporaryDirectory(prefix="silo_su_") as td:
        su_dir = Path(td) / "bin"
        android_harness.install_su_bridge_wrapper(
            su_dir, silod_bin=sb.silod_bin, socket_path=sb.socket_path
        )
        params_file = Path(td) / "params.json"
        params_file.write_text(
            json.dumps(
                {
                    "package_name": pkg,
                    "guard": {"auto_revoke_on_bg": ["location"], "locked_deny": ["camera"]},
                }
            ),
            encoding="utf-8",
        )
        out = android_harness.run_jvm_contract(
            ["silo_client_call", "set_app_perm_guard", str(params_file), "10000", "ScopesConfig"],
            extra_path_dir=su_dir,
        )
        assert out["model"] == "ScopesConfig"
        assert out["guards_count"] == 1

        # Verify compiled SiloStrings includes honest process-restart explanation for pm revoke (APP-M19)
        s_en = android_harness.run_jvm_contract(["silo_strings", "ENGLISH"])
        s_id = android_harness.run_jvm_contract(["silo_strings", "INDONESIAN"])
        assert "restart" in s_en["all_text"].lower() or "restarts" in s_en["all_text"].lower(), (
            "English SiloStrings should honestly explain Android pm revoke process-restart behavior (APP-M19)"
        )
        assert "restart" in s_id["all_text"].lower() or "ulang" in s_id["all_text"].lower(), (
            "Indonesian SiloStrings should honestly explain Android pm revoke process-restart behavior (APP-M19)"
        )
