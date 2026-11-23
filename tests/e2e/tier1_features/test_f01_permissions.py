"""Tier 1 (F1) — Per-App Permission & AppOps Control happy-path E2E tests."""

from __future__ import annotations

import json
import tempfile
from pathlib import Path

from tests.e2e.harness import android_harness
from tests.e2e.harness.registry import e2e_test
from tests.e2e.harness.silod_harness import (
    ALL_PERM_CATEGORIES,
    APPOPS_ONLY_CATEGORIES,
    FOREGROUND_SUPPORTED_CATEGORIES,
    SiloDaemonSandbox,
)


def _cat_map(live_perms: dict) -> dict[str, dict]:
    return {c["category"]: c for c in live_perms.get("categories", [])}


@e2e_test(
    "T1-F1-01",
    tier=1,
    features=["F1"],
    description="inspect_app_permissions parses One UI 7 Uid mode vs historical pkg mode and returns all 4 additive contract fields",
)
def test_inspect_one_ui_uid_mode_and_additive_fields() -> None:
    pkg = "com.sec.android.app.camera"
    with SiloDaemonSandbox() as sb:
        # Realistic One UI 7 conflict: Uid mode is ignore, historical package line says allow
        sb.set_package_appops(
            pkg,
            "CAMERA",
            uid_mode="ignore",
            pkg_mode="allow",
            time_str="+3m12s ago",
            duration="+5s",
            running=False,
        )
        sb.set_runtime_perm(pkg, "android.permission.CAMERA", granted=False, flags=["USER_SET"])
        sb.set_runtime_perm(pkg, "android.permission.RECORD_AUDIO", granted=True, flags=[])

        data = sb.rpc_ok("inspect_app_permissions", {"package_name": pkg})
        assert data["package_name"] == pkg
        cats = _cat_map(data)
        assert set(cats.keys()) == set(ALL_PERM_CATEGORIES), f"Unexpected categories: {sorted(cats)}"

        cam = cats["camera"]
        assert cam["appops_mode"] == "ignore", (
            f"Uid mode 'ignore' must NOT be overwritten by historical pkg 'allow' (D-01): got {cam}"
        )
        assert cam["running_now"] is False
        assert cam.get("last_access") or cam.get("last_access_ago"), f"Missing timestamp in {cam}"

        # Assert all 4 frozen additive contract fields across all 10 categories (PROJECT.md §2)
        for name in ALL_PERM_CATEGORIES:
            entry = cats[name]
            for field in ("runtime_granted", "has_runtime_perms", "supports_foreground", "system_fixed"):
                assert field in entry, f"Missing target additive field {field!r} on category {name!r}: {entry}"
            assert entry["has_runtime_perms"] is (name not in APPOPS_ONLY_CATEGORIES), (
                f"has_runtime_perms mismatch for {name}: {entry}"
            )
            assert entry["supports_foreground"] is (name in FOREGROUND_SUPPORTED_CATEGORIES), (
                f"supports_foreground mismatch for {name}: {entry}"
            )
            assert entry["system_fixed"] is False

        assert cam["runtime_granted"] is False, f"Camera runtime_granted should be False: {cam}"
        assert cats["microphone"]["runtime_granted"] is True, f"Mic runtime_granted should be True: {cats['microphone']}"
        assert cats["clipboard"]["runtime_granted"] is None, f"Clipboard is AppOps-only (runtime_granted must be null): {cats['clipboard']}"
        assert cats["background"]["runtime_granted"] is None, f"Background is AppOps-only: {cats['background']}"


@e2e_test(
    "T1-F1-02",
    tier=1,
    features=["F1"],
    description="set_app_permission_action revoke runs pm revoke + --uid AppOps ignore and reads back revoked state",
)
def test_action_revoke_runtime_and_uid_appops() -> None:
    pkg = "com.example.camapp"
    with SiloDaemonSandbox() as sb:
        sb.set_package_appops(pkg, "CAMERA", uid_mode="allow", pkg_mode="allow")
        sb.set_runtime_perm(pkg, "android.permission.CAMERA", granted=True)

        res = sb.rpc_ok(
            "set_app_permission_action",
            {"package_name": pkg, "category": "camera", "action": "revoke"},
        )
        cam = _cat_map(res)["camera"]
        assert cam["appops_mode"] in ("ignore", "deny"), f"Expected restrictive appops_mode after revoke: {cam}"
        assert cam.get("runtime_granted") is False, f"Expected runtime_granted=False after revoke: {cam}"

        logs = sb.read_cmd_log()
        pm_revokes = [e for e in logs if e["tool"] == "pm" and "revoke" in e["argv"] and pkg in e["argv"]]
        assert pm_revokes, f"Expected `pm revoke` invocation for {pkg}; logs={logs}"
        uid_sets = [
            e
            for e in logs
            if e["tool"] == "cmd"
            and "appops" in e["argv"]
            and "set" in e["argv"]
            and "--uid" in e["argv"]
            and pkg in e["argv"]
        ]
        assert uid_sets, f"Expected `cmd appops set --uid` invocation (D-02); logs={logs}"


@e2e_test(
    "T1-F1-03",
    tier=1,
    features=["F1"],
    description="set_app_permission_action ignore (Palsukan) sets --uid AppOps ignore WITHOUT calling pm revoke",
)
def test_action_ignore_silent_without_pm_revoke() -> None:
    pkg = "com.example.social"
    with SiloDaemonSandbox() as sb:
        sb.set_package_appops(pkg, "CAMERA", uid_mode="allow", pkg_mode="allow")
        sb.set_runtime_perm(pkg, "android.permission.CAMERA", granted=True)

        res = sb.rpc_ok(
            "set_app_permission_action",
            {"package_name": pkg, "category": "camera", "action": "ignore"},
        )
        cam = _cat_map(res)["camera"]
        assert cam["appops_mode"] == "ignore", f"Expected ignore appops_mode: {cam}"
        assert cam.get("runtime_granted") is True, f"Palsukan (ignore) must preserve runtime grant: {cam}"

        logs = sb.read_cmd_log()
        pm_revokes = [e for e in logs if e["tool"] == "pm" and "revoke" in e["argv"] and pkg in e["argv"]]
        assert not pm_revokes, f"action=ignore MUST NOT invoke pm revoke (would kill app process): {pm_revokes}"


@e2e_test(
    "T1-F1-04",
    tier=1,
    features=["F1"],
    description="set_app_permission_action allow (Normal) clears user-fixed/revoked-compat, resets AppOps to default, and invokes pm grant",
)
def test_action_allow_restores_flags_appops_and_grant() -> None:
    pkg = "com.example.restoreme"
    with SiloDaemonSandbox() as sb:
        sb.set_package_appops(pkg, "CAMERA", uid_mode="ignore", pkg_mode="ignore")
        sb.set_runtime_perm(
            pkg,
            "android.permission.CAMERA",
            granted=False,
            flags=["USER_SET", "USER_FIXED", "REVOKED_COMPAT"],
        )

        res = sb.rpc_ok(
            "set_app_permission_action",
            {"package_name": pkg, "category": "camera", "action": "allow"},
        )
        cam = _cat_map(res)["camera"]
        assert cam["appops_mode"] in ("default", "allow"), f"Expected default/allow after Normal: {cam}"
        assert cam.get("runtime_granted") is True, f"Expected pm grant to restore runtime_granted=True: {cam}"

        logs = sb.read_cmd_log()
        grants = [e for e in logs if e["tool"] == "pm" and "grant" in e["argv"] and pkg in e["argv"]]
        assert grants, f"Expected `pm grant` when restoring revoked runtime permission via allow: {logs}"

        st = sb.read_android_state()["packages"][pkg]["runtime_perms"]["android.permission.CAMERA"]
        assert "USER_FIXED" not in st["flags"], f"user-fixed flag must be cleared on allow: {st}"
        assert "REVOKED_COMPAT" not in st["flags"], f"revoked-compat flag must be cleared on allow: {st}"


@e2e_test(
    "T1-F1-05",
    tier=1,
    features=["F1"],
    description="set_app_permission_action foreground on location succeeds and bulk_revoke_category media revokes READ_MEDIA_VISUAL_USER_SELECTED",
)
def test_foreground_location_and_bulk_revoke_media_android14() -> None:
    p1 = "com.example.gallery1"
    p2 = "com.example.gallery2"
    with SiloDaemonSandbox() as sb:
        sb.set_package_appops(p1, "FINE_LOCATION", uid_mode="ignore", pkg_mode="ignore")
        sb.set_runtime_perm(p1, "android.permission.ACCESS_FINE_LOCATION", granted=True)
        fg_res = sb.rpc_ok(
            "set_app_permission_action",
            {"package_name": p1, "category": "location", "action": "foreground"},
        )
        loc = _cat_map(fg_res)["location"]
        assert loc["appops_mode"] == "foreground", f"Expected foreground mode on location: {loc}"
        assert loc["supports_foreground"] is True

        for pkg in (p1, p2):
            sb.set_runtime_perm(pkg, "android.permission.READ_MEDIA_IMAGES", granted=True)
            sb.set_runtime_perm(pkg, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED", granted=True)
            sb.set_package_appops(pkg, "READ_MEDIA_IMAGES", uid_mode="allow", pkg_mode="allow")

        bulk = sb.rpc_ok("bulk_revoke_category", {"category": "media", "packages": [p1, p2]})
        assert bulk["category"] == "media"
        assert bulk["revoked_count"] == 2, f"Expected revoked_count=2, got {bulk}"

        logs = sb.read_cmd_log()
        visual_revokes = [
            e
            for e in logs
            if e["tool"] == "pm"
            and "revoke" in e["argv"]
            and "android.permission.READ_MEDIA_VISUAL_USER_SELECTED" in e["argv"]
        ]
        assert len(visual_revokes) >= 2, (
            f"Android 14+ READ_MEDIA_VISUAL_USER_SELECTED must be revoked for both packages: {visual_revokes}"
        )


@e2e_test(
    "T1-F1-06",
    tier=1,
    features=["F1"],
    description="Live silod inspect_app_permissions deserializes cleanly into compiled Kotlin AppLivePermissions via real SiloClient",
)
def test_live_silod_to_kotlin_app_live_permissions_contract() -> None:
    pkg = "com.sec.android.app.camera"
    with SiloDaemonSandbox() as sb, tempfile.TemporaryDirectory(prefix="silo_su_") as td:
        sb.set_package_appops(
            pkg,
            "CAMERA",
            uid_mode="ignore",
            pkg_mode="allow",
            time_str="+3m12s ago",
        )
        sb.set_runtime_perm(pkg, "android.permission.CAMERA", granted=False)
        su_dir = Path(td) / "bin"
        android_harness.install_su_bridge_wrapper(
            su_dir, silod_bin=sb.silod_bin, socket_path=sb.socket_path
        )
        params_file = Path(td) / "params.json"
        params_file.write_text(json.dumps({"package_name": pkg}), encoding="utf-8")

        out = android_harness.run_jvm_contract(
            ["silo_client_call", "inspect_app_permissions", str(params_file), "10000", "AppLivePermissions"],
            extra_path_dir=su_dir,
        )
        assert out["model"] == "AppLivePermissions"
        assert out["package_name"] == pkg
        cats = {c["category"]: c for c in out["categories"]}
        assert cats["camera"]["appops_mode"] == "ignore"
        assert cats["camera"]["has_runtime_perms"] is True
        assert cats["camera"]["supports_foreground"] is True
        assert cats["clipboard"]["has_runtime_perms"] is False
        assert cats["clipboard"]["supports_foreground"] is False


@e2e_test(
    "T1-F1-07",
    tier=1,
    features=["F1"],
    description="Real Android JVM unit test suite (:app:testDebugUnitTest) executes with 0 failures, 0 errors, 0 skipped",
)
def test_android_junit_suite_passes_cleanly() -> None:
    xml_dir = android_harness.ensure_unit_tests_run()
    summary = android_harness.parse_junit_xml_dir(xml_dir)
    assert summary.total_tests >= 5, f"Expected >= 5 JVM unit tests, got {summary.total_tests}"
    assert summary.failures == 0 and summary.errors == 0 and summary.skipped == 0, (
        f"Android unit tests had failures/errors/skips: {summary}"
    )
