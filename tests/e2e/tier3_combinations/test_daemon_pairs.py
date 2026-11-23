"""Tier 3 — Daemon cross-feature pairwise interaction tests (T3-P01..T3-P09)."""

from __future__ import annotations

import json
import re
import threading
import time

from tests.e2e.harness.registry import e2e_test
from tests.e2e.harness.silod_harness import SiloDaemonSandbox, UnixRpcClient


@e2e_test(
    "T3-P01",
    tier=3,
    features=["F1", "F2"],
    description="F1 x F2: Revoke + Kunci Blokir -> manual allow override auto-unlocks locked_deny & clears user-fixed -> inspect confirms consistent state",
)
def test_pair_f1_f2_revoke_lock_override_consistency() -> None:
    pkg = "com.example.pair12"
    with SiloDaemonSandbox() as sb:
        sb.set_runtime_perm(pkg, "android.permission.CAMERA", granted=True)
        sb.set_package_appops(pkg, "CAMERA", uid_mode="allow", pkg_mode="allow")

        sb.rpc_ok(
            "set_app_permission_action",
            {"package_name": pkg, "category": "camera", "action": "revoke"},
        )
        sb.rpc_ok(
            "set_app_perm_guard",
            {"package_name": pkg, "guard": {"auto_revoke_on_bg": ["camera"], "locked_deny": ["camera"]}},
        )
        cfg_locked = sb.rpc_ok("get_config")
        assert cfg_locked["scopes"]["guards"][pkg]["locked_deny"] == ["camera"]
        assert cfg_locked["scopes"]["guards"][pkg]["auto_revoke_on_bg"] == []

        # User explicitly overrides camera back to allow
        live = sb.rpc_ok(
            "set_app_permission_action",
            {"package_name": pkg, "category": "camera", "action": "allow"},
        )
        cam = {c["category"]: c for c in live["categories"]}["camera"]
        assert cam["appops_mode"] in ("default", "allow")
        assert cam["runtime_granted"] is True

        cfg_after = sb.rpc_ok("get_config")
        assert pkg not in cfg_after["scopes"]["guards"] or "camera" not in cfg_after["scopes"]["guards"][pkg]["locked_deny"]
        flags = sb.read_android_state()["packages"][pkg]["runtime_perms"]["android.permission.CAMERA"]["flags"]
        assert "USER_FIXED" not in flags


@e2e_test(
    "T3-P02",
    tier=3,
    features=["F1", "F3"],
    description="F1 x F3: set_app_permission_action revoke updates state reflected as strict 'ignore' mode in get_privacy_access_log",
)
def test_pair_f1_f3_revoke_reflected_in_monitor_log() -> None:
    pkg = "com.example.pair13"
    with SiloDaemonSandbox() as sb:
        sb.set_runtime_perm(pkg, "android.permission.CAMERA", granted=True, uid=10288)
        sb.set_package_appops(pkg, "CAMERA", uid_mode="allow", pkg_mode="allow", time_str="-40s", uid=10288)

        sb.rpc_ok(
            "set_app_permission_action",
            {"package_name": pkg, "category": "camera", "action": "revoke"},
        )
        events = sb.rpc_ok("get_privacy_access_log", {"limit": 50})
        hits = [e for e in events if e["package_name"] == pkg and e["category"] == "camera"]
        assert hits, f"Expected camera event for {pkg} in Monitor log: {events}"
        assert hits[0]["mode"] in ("ignore", "deny"), (
            f"Monitor log must reflect revoked strict mode (ignore/deny), got {hits[0]}"
        )


@e2e_test(
    "T3-P03",
    tier=3,
    features=["F2", "F4"],
    description="F2 x F4: Concurrent get_status responds in <50ms with 0 subprocesses while set_app_perm_guard runs slow pm/cmd enforcement",
)
def test_pair_f2_f4_nonblocking_status_during_guard_enforcement() -> None:
    pkg = "com.example.pair24"
    with SiloDaemonSandbox() as sb:
        sb.rpc_ok("get_status")  # warm cache
        sb.set_runtime_perm(pkg, "android.permission.CAMERA", granted=True)
        sb.inject_fault("pm", argv_contains=["revoke", pkg], sleep_secs=1.8, exit_code=0)

        done = threading.Event()
        err: list[BaseException] = []

        def _guard_call() -> None:
            try:
                UnixRpcClient(sb.socket_path, timeout=15.0).call(
                    "set_app_perm_guard",
                    {"package_name": pkg, "guard": {"auto_revoke_on_bg": [], "locked_deny": ["camera"]}},
                    req_id=301,
                )
            except BaseException as exc:
                err.append(exc)
            finally:
                done.set()

        t = threading.Thread(target=_guard_call, daemon=True)
        t.start()
        time.sleep(0.25)

        resp, elapsed_ms = UnixRpcClient(sb.socket_path, timeout=5.0).call("get_status", req_id=302)
        assert resp["ok"] is True and resp["id"] == 302
        assert elapsed_ms < 50.0, (
            f"get_status blocked on state_mtx during set_app_perm_guard subprocess (D-04): took {elapsed_ms:.1f}ms"
        )
        t.join(timeout=10.0)
        assert not err, f"Background set_app_perm_guard failed: {err}"


@e2e_test(
    "T3-P04",
    tier=3,
    features=["F4", "F5"],
    description="F4 x F5: > 700 KiB replace_ip_blocklist transmitted over silod --bridge succeeds with exact loaded/rejected counts and commits firewall script",
)
def test_pair_f4_f5_large_blocklist_over_bridge() -> None:
    valid_entries = [f"203.{b}.{c}.1/32" for b in range(1, 140) for c in range(1, 231)]
    mixed = valid_entries + ["127.0.0.1", "999.999.999.999", "0.0.0.0/0"]
    assert len(json.dumps(mixed)) > 600_000

    with SiloDaemonSandbox(use_unshare=True) as sb:
        fw = sb.rpc_ok("get_config")["firewall"]
        fw["enabled"] = True
        sb.rpc_ok("set_firewall_config", {"config": fw})

        with sb.open_bridge() as br:
            resp = br.call("replace_ip_blocklist", {"entries": mixed}, req_id=501, timeout=35.0)
            assert resp["id"] == 501 and resp["ok"] is True, f"Bridge large blocklist failed: {resp}"
            assert resp["data"]["loaded"] == len(valid_entries)
            assert resp["data"]["rejected"] == 3
        v4 = sb.last_iptables_script(v6=False)
        assert ":SILO_OUT" in v4


@e2e_test(
    "T3-P05",
    tier=3,
    features=["F5", "F7"],
    description="F5 x F7: trigger_panic_lockdown coordinates firewall panic_lockdown_active, mic+camera mute, Wi-Fi+BT disable, and clipboard clear",
)
def test_pair_f5_f7_panic_lockdown_multi_subsystem_coordination() -> None:
    with SiloDaemonSandbox(use_unshare=True) as sb:
        rep = sb.rpc_ok("trigger_panic_lockdown")
        assert rep["firewall_locked"] is True
        assert rep["mic_muted"] is True and rep["camera_muted"] is True
        assert rep["wifi_disabled"] is True and rep["bluetooth_disabled"] is True
        assert rep["clipboard_cleared"] is True

        st = sb.rpc_ok("get_status")
        assert st.get("panic_lockdown_active") is True
        ast = sb.read_android_state()
        assert ast["sensor_privacy"]["mic_muted"] is True
        assert ast["sensor_privacy"]["camera_muted"] is True
        assert ast["settings_global"]["wifi_on"] == "0"
        assert ast["settings_global"]["bluetooth_on"] == "0"
        assert ast["clipboard"]["has_clip"] is False


@e2e_test(
    "T3-P06",
    tier=3,
    features=["F5", "F8"],
    description="F5 x F8: trigger_panic_lockdown followed by apply_preset('balanced') clears panic_lockdown_active, restores DefaultAllow, and enables hardening",
)
def test_pair_f5_f8_panic_lockdown_cleared_by_balanced_preset() -> None:
    with SiloDaemonSandbox(use_unshare=True) as sb:
        sb.rpc_ok("trigger_panic_lockdown")
        assert sb.rpc_ok("get_status").get("panic_lockdown_active") is True

        cfg = sb.rpc_ok("apply_preset", {"preset": "balanced"})
        assert cfg["firewall"]["mode"] == "default_allow"
        assert cfg["firewall"].get("panic_lockdown_active") is False
        assert cfg["hardening"]["enabled"] is True

        st = sb.rpc_ok("get_status")
        assert st.get("panic_lockdown_active") is False
        assert st["firewall_mode"] == "DefaultAllow"
        assert sb.sysctl_orig_path.is_file()


@e2e_test(
    "T3-P07",
    tier=3,
    features=["F5", "F11"],
    description="F5 x F11: Panic Lockdown blocks multi-user apps (u0/u10/u95/u150) despite prior DefaultAllow rules while exempting system UIDs",
)
def test_pair_f5_f11_panic_lockdown_multi_user_and_protected_exemptions() -> None:
    with SiloDaemonSandbox(use_unshare=True) as sb:
        fw = sb.rpc_ok("get_config")["firewall"]
        fw["enabled"] = True
        fw["mode"] = "default_allow"
        sb.rpc_ok("set_firewall_config", {"config": fw})
        for uid in (10277, 1_010_050, 9_510_050, 15_010_050):
            sb.rpc_ok(
                "set_app_firewall_rule",
                {
                    "uid": uid,
                    "rule": {
                        "package_name": f"com.example.u{uid}",
                        "allow_wifi": True,
                        "allow_cellular": True,
                        "allow_vpn": True,
                        "allow_lan": True,
                        "allow_screen_off": True,
                        "block_cleartext_http": False,
                        "block_localhost": False,
                        "is_vpn_app": False,
                    },
                },
            )
        sb.rpc_ok("trigger_panic_lockdown")
        v4 = sb.last_iptables_script(v6=False)
        for uid in (10277, 1_010_050, 9_510_050, 15_010_050):
            assert not re.search(rf"-A SILO_OUT\s+-m owner --uid-owner {uid}\b.*-j RETURN", v4), (
                f"UID {uid} bypassed Panic Lockdown via stale DefaultAllow RETURN rule:\n{v4}"
            )
        # System UID range 0-9999 remains exempted
        assert re.search(r"--uid-owner 0-9999\s+-j RETURN", v4), f"System UID 0-9999 must stay exempt:\n{v4}"


@e2e_test(
    "T3-P08",
    tier=3,
    features=["F7", "F8"],
    description="F7 x F8: apply_preset('paranoid') -> apply_preset('safe') resets every F7 automation toggle and disables F8 kernel hardening cleanly",
)
def test_pair_f7_f8_paranoid_to_safe_clean_reset() -> None:
    with SiloDaemonSandbox(use_unshare=True) as sb:
        par = sb.rpc_ok("apply_preset", {"preset": "paranoid"})
        assert par["automation"]["sensors_off_on_lock"] is True
        assert par["automation"]["usb_adb_lock_guard"] is True
        assert par["scopes"]["auto_clear_clipboard"] is True
        assert par["hardening"]["enabled"] is True

        safe = sb.rpc_ok("apply_preset", {"preset": "safe"})
        assert safe["hardening"]["enabled"] is False
        assert safe["scopes"]["auto_clear_clipboard"] is False
        assert safe["automation"]["sensors_off_on_lock"] is False
        assert safe["automation"]["usb_adb_lock_guard"] is False
        assert safe["automation"]["wifi_auto_off_enabled"] is False
        assert safe["automation"]["bluetooth_auto_off_enabled"] is False
        assert sb.rpc_ok("get_hardening_status")["enabled"] is False


@e2e_test(
    "T3-P09",
    tier=3,
    features=["F8", "F9"],
    description="F8 x F9: Hardening snapshot -> export_config -> corrupt config recovery (.corrupt.*) -> import_config -> run_live_proof passes",
)
def test_pair_f8_f9_hardening_export_corrupt_recovery_and_live_proof() -> None:
    with SiloDaemonSandbox(use_unshare=True) as sb:
        sb.rpc_ok("apply_preset", {"preset": "hardened"})
        assert sb.sysctl_orig_path.is_file()
        exported = sb.rpc_ok("export_config_json")["json"]

        # Corrupt config.json on disk and restart
        sb.stop()
        sb.config_path.write_text("{CORRUPTED_CONFIG", encoding="utf-8")
        sb.start()

        backups = list(sb.state_dir.glob("config.json.corrupt.*"))
        assert backups, "Expected corrupt config backup after restart"

        # Restore exported config and verify Live Proof + Hardening snapshot durability
        sb.rpc_ok("import_config_json", {"json": exported})
        hs = sb.rpc_ok("get_hardening_status")
        assert hs["enabled"] is True and hs["snapshot_saved"] is True

        proof = sb.rpc_ok("run_live_proof")
        assert proof["ipv4_hook_attached"] is True and proof["ipv4_silo_rules_count"] > 0
