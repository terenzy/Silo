"""Tier 1 (F9) — Verification, Stealth Sentinel, Config Durability & App Discovery happy-path E2E tests."""

from __future__ import annotations

import json
import tempfile
from pathlib import Path

from tests.e2e.harness import android_harness
from tests.e2e.harness.registry import e2e_test
from tests.e2e.harness.silod_harness import SiloDaemonSandbox


@e2e_test(
    "T1-F9-01",
    tier=1,
    features=["F9"],
    description="run_sentinel_audit returns valid SentinelReport with 0 <= score <= 100 and valid AuditSeverity findings",
)
def test_sentinel_audit_report_contract() -> None:
    with SiloDaemonSandbox() as sb:
        rep = sb.rpc_ok("run_sentinel_audit")
        assert 0 <= rep["score"] <= 100, f"Invalid sentinel score: {rep}"
        assert isinstance(rep["findings"], list) and len(rep["findings"]) > 0
        valid_sevs = {"pass", "info", "warn", "critical"}
        for f in rep["findings"]:
            assert f["severity"] in valid_sevs, f"Invalid AuditSeverity in finding: {f}"
            assert f.get("id") and f.get("title")


@e2e_test(
    "T1-F9-02",
    tier=1,
    features=["F9"],
    description="run_live_proof with firewall enabled returns 8 LiveProofItems verifying IPv4/IPv6 hooks and rules",
)
def test_live_proof_verifies_active_firewall_hooks() -> None:
    with SiloDaemonSandbox(use_unshare=True) as sb:
        fw = sb.rpc_ok("get_config")["firewall"]
        fw["enabled"] = True
        fw["mode"] = "default_allow"
        sb.rpc_ok("set_firewall_config", {"config": fw})

        proof = sb.rpc_ok("run_live_proof")
        assert proof["ipv4_hook_attached"] is True, f"Expected ipv4_hook_attached=true: {proof}"
        assert proof["ipv4_silo_rules_count"] > 0, f"Expected ipv4_silo_rules_count > 0: {proof}"
        assert proof["ipv6_hook_attached"] is True, f"Expected ipv6_hook_attached=true: {proof}"
        assert isinstance(proof["items"], list) and len(proof["items"]) >= 8, (
            f"Expected >= 8 LiveProofItem entries: {proof}"
        )


@e2e_test(
    "T1-F9-03",
    tier=1,
    features=["F9"],
    description="export_config_json and import_config_json round-trip full v3 config losslessly",
)
def test_export_import_config_roundtrip() -> None:
    with SiloDaemonSandbox(use_unshare=True) as sb:
        sb.rpc_ok(
            "set_app_firewall_rule",
            {
                "uid": 10277,
                "rule": {
                    "package_name": "com.example.exportme",
                    "allow_wifi": False,
                    "allow_cellular": True,
                    "allow_vpn": True,
                    "allow_lan": False,
                    "allow_screen_off": False,
                    "block_cleartext_http": True,
                    "block_localhost": False,
                    "is_vpn_app": False,
                },
            },
        )
        sb.rpc_ok(
            "set_app_perm_guard",
            {
                "package_name": "com.example.exportme",
                "guard": {"auto_revoke_on_bg": ["location"], "locked_deny": ["camera"]},
            },
        )
        sb.rpc_ok("set_auto_clear_clipboard", {"enabled": True, "clear_secs": 40})

        exported = sb.rpc_ok("export_config_json")
        exported_json = exported["json"]
        parsed = json.loads(exported_json)
        assert parsed["schema_version"] == 3
        assert "10277" in parsed["firewall"]["rules"]

        # Reset via safe preset, then import exported snapshot back
        sb.rpc_ok("apply_preset", {"preset": "safe"})
        sb.rpc_ok("remove_app_firewall_rule", {"uid": 10277})

        imported = sb.rpc_ok("import_config_json", {"json": exported_json})
        assert "10277" in imported["firewall"]["rules"]
        assert imported["firewall"]["rules"]["10277"]["allow_wifi"] is False
        assert imported["scopes"]["guards"]["com.example.exportme"]["locked_deny"] == ["camera"]
        assert imported["scopes"]["clipboard_clear_secs"] == 40


@e2e_test(
    "T1-F9-04",
    tier=1,
    features=["F9"],
    description="Corrupt config.json on startup creates automatic config.json.corrupt.* backup and falls back cleanly (D-26)",
)
def test_corrupt_config_creates_backup_on_startup() -> None:
    corrupt_payload = '{"schema_version": 3, "firewall": { BROKEN JSON !!!'
    with SiloDaemonSandbox(initial_config=corrupt_payload) as sb:
        cfg = sb.rpc_ok("get_config")
        assert cfg["schema_version"] == 3

        backups = list(sb.state_dir.glob("config.json.corrupt.*"))
        assert backups, (
            f"Corrupt config.json MUST be backed up to config.json.corrupt.<ts> before overwriting with defaults (D-26); "
            f"files={list(sb.state_dir.iterdir())}"
        )
        assert backups[0].read_text(encoding="utf-8") == corrupt_payload


@e2e_test(
    "T1-F9-05",
    tier=1,
    features=["F9"],
    description="Legacy v2 scopes.apps entries do NOT silently overwrite v3 scopes.guards categories on startup or import (CB-10)",
)
def test_legacy_v2_scopes_apps_do_not_override_v3_guards() -> None:
    pkg = "com.example.migrated"
    legacy_cfg = {
        "schema_version": 2,
        "scopes": {
            "apps": {
                pkg: {
                    "isolate_contacts": True,
                    "isolate_storage": False,
                    "isolate_sms_calllog": False,
                    "block_clipboard_read": True,
                    "restrict_sensors": False,
                    "restrict_background": False,
                    "isolate_location": False,
                    "block_camera_mic": True,
                }
            },
            "guards": {
                pkg: {
                    "auto_revoke_on_bg": ["location"],
                    "locked_deny": ["microphone"],
                }
            },
            "auto_clear_clipboard": False,
            "clipboard_clear_secs": 45,
        },
    }
    with SiloDaemonSandbox(initial_config=legacy_cfg) as sb:
        cfg = sb.rpc_ok("get_config")
        guard = cfg["scopes"]["guards"].get(pkg, {})
        assert "microphone" in guard.get("locked_deny", []), (
            f"v3 guards.locked_deny must be preserved: {cfg['scopes']}"
        )
        assert "location" in guard.get("auto_revoke_on_bg", []), (
            f"v3 guards.auto_revoke_on_bg must be preserved: {cfg['scopes']}"
        )
        # Ensure legacy scopes.apps did not silently stomp over or conflict with v3 guards
        assert not (set(guard.get("auto_revoke_on_bg", [])) & set(guard.get("locked_deny", [])))


@e2e_test(
    "T1-F9-06",
    tier=1,
    features=["F9"],
    description="Live silod SentinelReport & LiveProofReport deserialize in Kotlin AND AppInventory detects Android 14+ media & Activity/Provider trackers",
)
def test_kotlin_sentinel_liveproof_and_app_inventory_trackers() -> None:
    with SiloDaemonSandbox(use_unshare=True) as sb, tempfile.TemporaryDirectory(prefix="silo_su_") as td:
        su_dir = Path(td) / "bin"
        android_harness.install_su_bridge_wrapper(
            su_dir, silod_bin=sb.silod_bin, socket_path=sb.socket_path
        )
        sr = android_harness.run_jvm_contract(
            ["silo_client_call", "run_sentinel_audit", "null", "15000", "SentinelReport"],
            extra_path_dir=su_dir,
        )
        assert sr["model"] == "SentinelReport" and 0 <= sr["score"] <= 100 and sr["findings"] > 0

        lp = android_harness.run_jvm_contract(
            ["silo_client_call", "run_live_proof", "null", "15000", "LiveProofReport"],
            extra_path_dir=su_dir,
        )
        assert lp["model"] == "LiveProofReport" and lp["items"] >= 8

        # Verify AppInventory.evaluateOfflineRisk maps Android 14+ READ_MEDIA_VISUAL_USER_SELECTED -> "media"
        # and detects Activity/Provider tracker SDK components (APP-H08 / APP-M08)
        risk = android_harness.run_jvm_contract(
            [
                "app_inventory_risk",
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED,android.permission.CAMERA",
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED,android.permission.CAMERA",
                "com.facebook.ads.AudienceNetworkActivity,com.appsflyer.SingleInstallBroadcastReceiver",
            ]
        )
        assert "media" in risk["granted"], (
            f"Android 14+ READ_MEDIA_VISUAL_USER_SELECTED must map to 'media' (APP-H08): {risk}"
        )
        assert "camera" in risk["granted"]
        assert len(risk["trackers"]) >= 2, (
            f"Expected >= 2 detected trackers (including Activity tracker), got {risk}"
        )
