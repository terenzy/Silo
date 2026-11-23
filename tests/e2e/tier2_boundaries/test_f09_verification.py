"""Tier 2 (F9) — Verification, Stealth Sentinel, Config Durability & App Discovery boundary & negative tests."""

from __future__ import annotations

import json
import os
import socket
import stat

from tests.e2e.harness import android_harness
from tests.e2e.harness.registry import e2e_test
from tests.e2e.harness.silod_harness import SiloDaemonSandbox


@e2e_test(
    "T2-F9-01",
    tier=2,
    features=["F9"],
    description="import_config_json rejects invalid JSON and strips unsafe CIDRs and protected UIDs/packages on import",
)
def test_import_config_sanitizes_unsafe_cidrs_and_protected_entries() -> None:
    with SiloDaemonSandbox() as sb:
        sb.rpc_err("import_config_json", {"json": "{not valid json"})

        cfg = sb.rpc_ok("get_config")
        cfg["firewall"]["ip_blocklist"] = ["127.0.0.1", "::1", "0.0.0.0/0", "198.51.100.7"]
        cfg["firewall"]["rules"] = {
            "0": {
                "package_name": "android",
                "allow_wifi": False,
                "allow_cellular": False,
                "allow_vpn": False,
                "allow_lan": False,
                "allow_screen_off": False,
                "block_cleartext_http": True,
                "block_localhost": True,
                "is_vpn_app": False,
            },
            "10555": {
                "package_name": "dev.silo.app",
                "allow_wifi": False,
                "allow_cellular": False,
                "allow_vpn": False,
                "allow_lan": False,
                "allow_screen_off": False,
                "block_cleartext_http": False,
                "block_localhost": False,
                "is_vpn_app": False,
            },
            "10556": {
                "package_name": "com.example.kept",
                "allow_wifi": False,
                "allow_cellular": True,
                "allow_vpn": True,
                "allow_lan": True,
                "allow_screen_off": True,
                "block_cleartext_http": False,
                "block_localhost": False,
                "is_vpn_app": False,
            },
        }
        cfg["scopes"]["guards"] = {
            "dev.silo.app": {"auto_revoke_on_bg": [], "locked_deny": ["camera"]},
            "com.example.kept": {"auto_revoke_on_bg": [], "locked_deny": ["camera"]},
        }

        imp = sb.rpc_ok("import_config_json", {"json": json.dumps(cfg)})
        assert imp["firewall"]["ip_blocklist"] == ["198.51.100.7"], (
            f"Unsafe CIDRs must be stripped on import: {imp['firewall']['ip_blocklist']}"
        )
        assert "0" not in imp["firewall"]["rules"] and "10555" not in imp["firewall"]["rules"], (
            f"Protected UID/package rules must be stripped on import: {imp['firewall']['rules']}"
        )
        assert "10556" in imp["firewall"]["rules"]
        assert "dev.silo.app" not in imp["scopes"]["guards"]
        assert "com.example.kept" in imp["scopes"]["guards"]


@e2e_test(
    "T2-F9-02",
    tier=2,
    features=["F9"],
    description="run_sentinel_audit does NOT count ephemeral outbound UDP sockets as app_listening_ports (CB-22)",
)
def test_sentinel_does_not_flag_ephemeral_udp_as_listening_port() -> None:
    with SiloDaemonSandbox(use_unshare=False) as sb:
        before = sb.rpc_ok("run_sentinel_audit")["app_listening_ports"]

        # Bind 5 ephemeral unconnected UDP sockets (mimicking Android DNS/QUIC outbound sockets)
        udp_socks = []
        try:
            for _ in range(5):
                u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                u.bind(("0.0.0.0", 0))
                udp_socks.append(u)
            after = sb.rpc_ok("run_sentinel_audit")["app_listening_ports"]
            assert after <= before, (
                f"Ephemeral outbound UDP sockets MUST NOT inflate app_listening_ports (CB-22): before={before}, after={after}"
            )
        finally:
            for u in udp_socks:
                u.close()


@e2e_test(
    "T2-F9-03",
    tier=2,
    features=["F9"],
    description="run_live_proof does NOT fail Hardware Sensors & Radio/ADB solely because adb_enabled_live=true while unlocked (D-27)",
)
def test_live_proof_unlocked_adb_and_appops_modes() -> None:
    with SiloDaemonSandbox(use_unshare=True) as sb:
        # Device is unlocked (device_locked=False), USB/ADB lock guard enabled, ADB currently ON while unlocked
        sb.set_trust_state(device_locked=False)
        sb.set_setting_global("adb_enabled", "1")
        auto = sb.rpc_ok("get_config")["automation"]
        auto["usb_adb_lock_guard"] = True
        sb.rpc_ok("set_automation_config", {"config": auto})

        # Set a package to foreground/deny AppOps so AppOps proof counts non-ignore restrictive modes too
        sb.set_package_appops("com.example.fgapp", "CAMERA", uid_mode="foreground", pkg_mode="foreground")
        sb.rpc_ok(
            "set_app_perm_guard",
            {"package_name": "com.example.fgapp", "guard": {"auto_revoke_on_bg": ["camera"], "locked_deny": []}},
        )

        proof = sb.rpc_ok("run_live_proof")
        sensor_items = [
            it for it in proof["items"] if "sensor" in it["subsystem"].lower() or "adb" in it["subsystem"].lower() or "radio" in it["subsystem"].lower()
        ]
        assert sensor_items, f"Missing Hardware Sensors / Radio / ADB LiveProofItem: {proof['items']}"
        assert sensor_items[0]["verified"] is True, (
            f"Live proof MUST NOT fail Sensors/Radio/ADB solely because adb_enabled_live=true while unlocked (D-27): {sensor_items[0]}"
        )


@e2e_test(
    "T2-F9-04",
    tier=2,
    features=["F9"],
    description="Missing config.json initializes cleanly with schema_version=3 and binary garbage config.json creates .corrupt.* backup",
)
def test_missing_and_binary_garbage_config_recovery() -> None:
    with SiloDaemonSandbox(initial_config=None) as sb:
        cfg = sb.rpc_ok("get_config")
        assert cfg["schema_version"] == 3

    with SiloDaemonSandbox(initial_config=b"\x00\xff\xfe\x01garbage\x80") as sb2:
        cfg2 = sb2.rpc_ok("get_config")
        assert cfg2["schema_version"] == 3
        backups = list(sb2.state_dir.glob("config.json.corrupt.*"))
        assert backups, "Binary garbage config.json must trigger config.json.corrupt.* backup (D-26)"


@e2e_test(
    "T2-F9-05",
    tier=2,
    features=["F9"],
    description="export_config_json emits clean v3 JSON and persisted config.json on disk enforces 0600 mode",
)
def test_config_file_permissions_0600_and_clean_export() -> None:
    with SiloDaemonSandbox() as sb:
        sb.rpc_ok("set_auto_clear_clipboard", {"enabled": True, "clear_secs": 35})
        assert sb.config_path.is_file()
        mode = stat.S_IMODE(os.stat(sb.config_path).st_mode)
        assert mode == 0o600, f"config.json must be written with 0600 permissions, got {oct(mode)}"

        raw = sb.rpc_ok("export_config_json")["json"]
        obj = json.loads(raw)
        assert obj["schema_version"] == 3
        assert obj["scopes"]["clipboard_clear_secs"] == 35


@e2e_test(
    "T2-F9-06",
    tier=2,
    features=["F9"],
    description="Compiled Kotlin AppInventory.isProtectedUid enforces exact multi-user UID protection boundaries",
)
def test_kotlin_app_inventory_multi_user_protected_uid_boundaries() -> None:
    protected = [0, 1000, 9999, 90000, 99999, 101000, 1501000]
    unprotected = [10000, 10277, 89999, 110000, 1010277, 9510277, 15010277]
    all_uids = [str(u) for u in protected + unprotected]
    res = android_harness.run_jvm_contract(["app_inventory_uid", *all_uids])
    for u in protected:
        assert res[str(u)] is True, f"UID {u} must be protected in AppInventory.isProtectedUid: {res}"
    for u in unprotected:
        assert res[str(u)] is False, f"Multi-user app UID {u} must NOT be marked protected: {res}"
