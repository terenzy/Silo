"""Tier 2 (F2) — Permission Enforcer (`Kunci Blokir` & `Auto-Cabut`) boundary, corner & negative tests."""

from __future__ import annotations

import time

from tests.e2e.harness import android_harness
from tests.e2e.harness.registry import e2e_test
from tests.e2e.harness.silod_harness import SiloDaemonSandbox


@e2e_test(
    "T2-F2-01",
    tier=2,
    features=["F2"],
    description="Mutually exclusive sanitization: sending same category in both auto_revoke_on_bg and locked_deny strips it from auto_revoke_on_bg (locked_deny wins)",
)
def test_guard_mutual_exclusion_sanitization() -> None:
    pkg = "com.example.mutex"
    with SiloDaemonSandbox() as sb:
        scopes = sb.rpc_ok(
            "set_app_perm_guard",
            {
                "package_name": pkg,
                "guard": {
                    "auto_revoke_on_bg": ["camera", "location"],
                    "locked_deny": ["camera"],
                },
            },
        )
        g = scopes["guards"][pkg]
        assert g["locked_deny"] == ["camera"]
        assert "camera" not in g["auto_revoke_on_bg"], (
            f"locked_deny must strip duplicates from auto_revoke_on_bg (PROJECT.md §2): {g}"
        )
        assert g["auto_revoke_on_bg"] == ["location"]


@e2e_test(
    "T2-F2-02",
    tier=2,
    features=["F2"],
    description="Unknown and duplicate categories in AppPermGuard are stripped/deduplicated and empty guard prunes the package entry",
)
def test_guard_sanitizes_duplicates_unknowns_and_prunes_empty() -> None:
    pkg = "com.example.dedup"
    with SiloDaemonSandbox() as sb:
        scopes = sb.rpc_ok(
            "set_app_perm_guard",
            {
                "package_name": pkg,
                "guard": {
                    "auto_revoke_on_bg": ["location", "location", "not_a_cat"],
                    "locked_deny": ["camera", "camera", "fake_cat"],
                },
            },
        )
        g = scopes["guards"][pkg]
        assert g["auto_revoke_on_bg"] == ["location"], f"Expected deduplicated/sanitized list: {g}"
        assert g["locked_deny"] == ["camera"], f"Expected deduplicated/sanitized list: {g}"

        # Sending only invalid/empty categories removes the package from guards map
        scopes2 = sb.rpc_ok(
            "set_app_perm_guard",
            {
                "package_name": pkg,
                "guard": {"auto_revoke_on_bg": ["bogus"], "locked_deny": []},
            },
        )
        assert pkg not in scopes2["guards"], f"Empty guard must be pruned from map: {scopes2['guards']}"


@e2e_test(
    "T2-F2-03",
    tier=2,
    features=["F2"],
    description="set_app_perm_guard rejects protected packages and invalid package names",
)
def test_guard_rejects_protected_and_invalid_packages() -> None:
    with SiloDaemonSandbox() as sb:
        for bad_pkg in ("dev.silo.app", "com.android.systemui", "com.rifsxd.ksunext", "bad pkg", "noperiod", ""):
            sb.rpc_err(
                "set_app_perm_guard",
                {
                    "package_name": bad_pkg,
                    "guard": {"auto_revoke_on_bg": [], "locked_deny": ["camera"]},
                },
            )


@e2e_test(
    "T2-F2-04",
    tier=2,
    features=["F2"],
    description="Adding auto_revoke_on_bg on a package that already has locked_deny does NOT redundantly re-revoke unchanged locked_deny categories (D-05)",
)
def test_adding_auto_revoke_does_not_re_revoke_existing_locked_deny() -> None:
    pkg = "com.example.norevoke"
    with SiloDaemonSandbox() as sb:
        sb.set_runtime_perm(pkg, "android.permission.CAMERA", granted=True)
        sb.rpc_ok(
            "set_app_perm_guard",
            {"package_name": pkg, "guard": {"auto_revoke_on_bg": [], "locked_deny": ["camera"]}},
        )
        # Wait briefly for any async settle, then snapshot cmd_log
        time.sleep(0.2)
        log_before = len(sb.read_cmd_log())

        # Now add auto_revoke_on_bg=["location"] keeping locked_deny=["camera"] unchanged
        sb.rpc_ok(
            "set_app_perm_guard",
            {"package_name": pkg, "guard": {"auto_revoke_on_bg": ["location"], "locked_deny": ["camera"]}},
        )
        new_logs = sb.read_cmd_log()[log_before:]
        cam_revokes = [
            e
            for e in new_logs
            if e["tool"] == "pm"
            and "revoke" in e["argv"]
            and pkg in e["argv"]
            and "android.permission.CAMERA" in e["argv"]
        ]
        assert not cam_revokes, (
            f"Unchanged locked_deny=['camera'] must NOT be redundantly pm-revoked when editing auto_revoke_on_bg (D-05): {cam_revokes}"
        )


@e2e_test(
    "T2-F2-05",
    tier=2,
    features=["F2"],
    description="Kunci Blokir does NOT enter a false-positive revoke loop when Uid mode=ignore + historical pkg=allow and runtime_granted=false (D-01/D-05)",
)
def test_kunci_blokir_no_false_positive_revoke_loop() -> None:
    pkg = "com.example.noloop"
    with SiloDaemonSandbox() as sb:
        # Simulate already-revoked One UI 7 state: Uid mode=ignore, historical pkg line=allow, runtime granted=false
        sb.set_package_appops(pkg, "CAMERA", uid_mode="ignore", pkg_mode="allow", time_str="+1m ago")
        sb.set_runtime_perm(pkg, "android.permission.CAMERA", granted=False, flags=["USER_SET", "USER_FIXED"])

        sb.rpc_ok(
            "set_app_perm_guard",
            {"package_name": pkg, "guard": {"auto_revoke_on_bg": [], "locked_deny": ["camera"]}},
        )
        time.sleep(0.3)
        log_baseline = len(sb.read_cmd_log())
        # Observe enforcer over 2.5s (covering multiple 1s active-guard ticks)
        time.sleep(2.5)
        new_logs = sb.read_cmd_log()[log_baseline:]
        spurious_revokes = [
            e for e in new_logs if e["tool"] == "pm" and "revoke" in e["argv"] and pkg in e["argv"]
        ]
        assert not spurious_revokes, (
            f"Kunci Blokir triggered false-positive revoke loop on already-ignored One UI 7 Uid mode (D-01/D-05): {spurious_revokes}"
        )


@e2e_test(
    "T2-F2-06",
    tier=2,
    features=["F2"],
    description="Compiled Kotlin AppPermGuard.totalCount() deduplicates overlapping autoRevokeOnBg & lockedDeny categories (APP-M19)",
)
def test_kotlin_app_perm_guard_total_count_no_double_count() -> None:
    res = android_harness.run_jvm_contract(["perm_guard_total", "camera,location", "camera"])
    assert res["total_count"] == 2, (
        f"AppPermGuard(auto=['camera','location'], locked=['camera']).totalCount() must be 2, NOT 3 (APP-M19): got {res}"
    )
