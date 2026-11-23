"""Tier 2 (F1) — Per-App Permission & AppOps Control boundary, corner & negative error tests."""

from __future__ import annotations

from tests.e2e.harness import android_harness
from tests.e2e.harness.registry import e2e_test
from tests.e2e.harness.silod_harness import SiloDaemonSandbox


def _cat_map(live_perms: dict) -> dict[str, dict]:
    return {c["category"]: c for c in live_perms.get("categories", [])}


@e2e_test(
    "T2-F1-01",
    tier=2,
    features=["F1"],
    description="set_app_permission_action rejects 'foreground' on all 6 unsupported categories",
)
def test_foreground_rejected_on_unsupported_categories() -> None:
    pkg = "com.example.nofg"
    unsupported = ("clipboard", "contacts", "media", "sms_calllog", "background", "background_location")
    with SiloDaemonSandbox() as sb:
        for cat in unsupported:
            err = sb.rpc_err(
                "set_app_permission_action",
                {"package_name": pkg, "category": cat, "action": "foreground"},
            )
            assert err, f"Expected descriptive error rejecting foreground on {cat}"


@e2e_test(
    "T2-F1-02",
    tier=2,
    features=["F1"],
    description="Multi-op fail-closed aggregation: in media, READ_MEDIA_IMAGES=allow + MANAGE_EXTERNAL_STORAGE=ignore MUST report 'allow' (D-03)",
)
def test_multi_op_fail_closed_aggregation() -> None:
    pkg = "com.example.multiop"
    with SiloDaemonSandbox() as sb:
        sb.set_package_appops(pkg, "READ_MEDIA_IMAGES", uid_mode="allow", pkg_mode="allow")
        sb.set_package_appops(pkg, "READ_MEDIA_VIDEO", uid_mode="ignore", pkg_mode="ignore")
        sb.set_package_appops(pkg, "MANAGE_EXTERNAL_STORAGE", uid_mode="ignore", pkg_mode="ignore")

        data = sb.rpc_ok("inspect_app_permissions", {"package_name": pkg})
        media = _cat_map(data)["media"]
        assert media["appops_mode"] == "allow", (
            f"Multi-op aggregation must fail closed (allow > foreground > ignore > default) (D-03): got {media}"
        )


@e2e_test(
    "T2-F1-03",
    tier=2,
    features=["F1"],
    description="Stdout-only error from cmd appops set (empty stderr, exit 1) MUST fail set_app_permission_action instead of silent ok=true (D-02)",
)
def test_stdout_only_appops_failure_is_not_silently_ignored() -> None:
    pkg = "com.example.failstdout"
    with SiloDaemonSandbox() as sb:
        sb.inject_fault(
            "cmd",
            argv_contains=["appops", "set", pkg],
            exit_code=1,
            stdout="Error: Operation not permitted for package\n",
            stderr="",
        )
        sb.inject_fault(
            "pm",
            argv_contains=["set-permission-flags", pkg],
            exit_code=1,
            stdout="Error: cannot set flags\n",
            stderr="",
        )
        err = sb.rpc_err(
            "set_app_permission_action",
            {"package_name": pkg, "category": "clipboard", "action": "ignore"},
        )
        assert err, "Expected non-empty error message when cmd appops set fails on stdout only (D-02)"


@e2e_test(
    "T2-F1-04",
    tier=2,
    features=["F1"],
    description="SYSTEM_FIXED runtime permission is reported honestly with system_fixed=true when dumpsys package flags SYSTEM_FIXED",
)
def test_system_fixed_permission_reported_honestly() -> None:
    pkg = "com.example.sysfixed"
    with SiloDaemonSandbox() as sb:
        sb.set_runtime_perm(
            pkg,
            "android.permission.CAMERA",
            granted=True,
            flags=["SYSTEM_FIXED", "GRANTED_BY_DEFAULT"],
        )
        sb.set_package_appops(pkg, "CAMERA", uid_mode="allow", pkg_mode="allow")

        data = sb.rpc_ok("inspect_app_permissions", {"package_name": pkg})
        cam = _cat_map(data)["camera"]
        assert cam.get("system_fixed") is True, (
            f"Expected system_fixed=true when dumpsys package marks SYSTEM_FIXED (PROJECT.md §2): {cam}"
        )
        assert cam.get("runtime_granted") is True


@e2e_test(
    "T2-F1-05",
    tier=2,
    features=["F1"],
    description="Invalid package names, command injection payloads, unknown categories, unknown actions, and protected packages are rejected",
)
def test_permission_rpc_input_validation_and_injection_rejection() -> None:
    with SiloDaemonSandbox() as sb:
        bad_cases = [
            {"package_name": "com.bad;id", "category": "camera", "action": "revoke"},
            {"package_name": "../etc/passwd", "category": "camera", "action": "revoke"},
            {"package_name": "dev.silo.app", "category": "camera", "action": "revoke"},
            {"package_name": "com.example.ok", "category": "root_magic", "action": "revoke"},
            {"package_name": "com.example.ok", "category": "camera", "action": "hack_mode"},
            {"package_name": "a", "category": "camera", "action": "revoke"},
        ]
        for params in bad_cases:
            sb.rpc_err("set_app_permission_action", params)


@e2e_test(
    "T2-F1-06",
    tier=2,
    features=["F1"],
    description="Compiled AppInventory.evaluateOfflineRisk does NOT miscount special AppOps (SYSTEM_ALERT_WINDOW / MANAGE_EXTERNAL_STORAGE) as revocable runtime grants (APP-H08/CB-09)",
)
def test_kotlin_app_inventory_excludes_special_appops_from_runtime_grants() -> None:
    specials = "android.permission.SYSTEM_ALERT_WINDOW,android.permission.MANAGE_EXTERNAL_STORAGE"
    risk = android_harness.run_jvm_contract(["app_inventory_risk", specials, specials, ""])
    assert risk["granted"] == [], (
        f"Special AppOps SYSTEM_ALERT_WINDOW & MANAGE_EXTERNAL_STORAGE must NOT be counted as revocable runtime grantedCategories (APP-H08/CB-09): {risk}"
    )
    assert risk["level"] == "LOW", f"Expected LOW risk when only non-runtime special AppOps exist: {risk}"
