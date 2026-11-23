"""Tier 4 — All 7 Real-World Multi-Feature Application Scenarios (T4-S01..T4-S07 from TEST_INFRA.md § 4)."""

from __future__ import annotations

import json
import os
import re
import socket
import subprocess
import tempfile
import threading
import time
from pathlib import Path

from tests.e2e.harness import (
    android_harness,
    ci_harness,
    module_harness,
    packaging_harness,
)
from tests.e2e.harness.registry import e2e_test
from tests.e2e.harness.silod_harness import SiloDaemonSandbox, UnixRpcClient


@e2e_test(
    "T4-S01",
    tier=4,
    features=["F1", "F2", "F3", "F4"],
    description="One UI 7 Samsung Camera permission revoke, Kunci Blokir, manual override, Monitor strict parsing & <50ms concurrent get_status",
)
def test_scenario_s01_one_ui_samsung_camera_lifecycle() -> None:
    pkg = "com.sec.android.app.camera"
    with SiloDaemonSandbox() as sb:
        # 1. Seed conflicting One UI 7 state: Uid mode=ignore + historical pkg=allow + runtime granted=true
        sb.set_package_appops(
            pkg,
            "CAMERA",
            uid_mode="ignore",
            pkg_mode="allow",
            time_str="+3m12s ago",
            duration="+5s",
            running=False,
            uid=10277,
        )
        sb.set_runtime_perm(pkg, "android.permission.CAMERA", granted=True, uid=10277)

        # 2. Inspect via live silod and verify One UI 7 Uid mode wins + all 4 additive fields
        inspected = sb.rpc_ok("inspect_app_permissions", {"package_name": pkg})
        cam0 = {c["category"]: c for c in inspected["categories"]}["camera"]
        assert cam0["appops_mode"] == "ignore"
        assert cam0["runtime_granted"] is True
        assert cam0["has_runtime_perms"] is True
        assert cam0["supports_foreground"] is True
        assert cam0["system_fixed"] is False

        # 3. Revoke camera
        revoked = sb.rpc_ok(
            "set_app_permission_action",
            {"package_name": pkg, "category": "camera", "action": "revoke"},
        )
        cam1 = {c["category"]: c for c in revoked["categories"]}["camera"]
        assert cam1["appops_mode"] in ("ignore", "deny") and cam1["runtime_granted"] is False

        # 4. Enable Kunci Blokir with conflicting auto_revoke_on_bg -> verify mutual exclusion + user-fixed flag
        scopes = sb.rpc_ok(
            "set_app_perm_guard",
            {
                "package_name": pkg,
                "guard": {"auto_revoke_on_bg": ["camera", "location"], "locked_deny": ["camera"]},
            },
        )
        assert scopes["guards"][pkg]["locked_deny"] == ["camera"]
        assert scopes["guards"][pkg]["auto_revoke_on_bg"] == ["location"]
        cam_flags = sb.read_android_state()["packages"][pkg]["runtime_perms"]["android.permission.CAMERA"]["flags"]
        assert "USER_FIXED" in cam_flags

        # 5. Monitor get_privacy_access_log reports strict mode & excludes protected packages
        sb.set_dumpsys_appops_raw(
            "CAMERA",
            "Current AppOps Service state:\n"
            "  Uid u0a101:\n"
            "    Package dev.silo.app:\n"
            "      CAMERA (allow):\n"
            "        Access: [top-s] -2s ago\n"
            "  Uid u0a277:\n"
            f"    Package {pkg}:\n"
            "      CAMERA (allow/ignore):\n"
            "        Access: [top-s] -5m ago\n"
            "        Access: [fg-s] -10s ago (running)\n",
        )
        events = sb.rpc_ok("get_privacy_access_log", {"limit": 50})
        pkgs_seen = {e["package_name"] for e in events}
        assert "dev.silo.app" not in pkgs_seen
        cam_evs = [e for e in events if e["package_name"] == pkg]
        assert len(cam_evs) == 1 and cam_evs[0]["mode"] == "ignore" and cam_evs[0]["running_now"] is True

        # 6. Concurrent get_status returns < 50ms while a slow dumpsys call is in flight
        sb.rpc_ok("get_status")
        sb.inject_fault("dumpsys", argv_contains=["appops"], sleep_secs=1.5, exit_code=0, stdout="", once=True)
        t = threading.Thread(
            target=lambda: UnixRpcClient(sb.socket_path, timeout=15.0).call("get_privacy_access_log"),
            daemon=True,
        )
        t.start()
        time.sleep(0.2)
        st_resp, elapsed_ms = UnixRpcClient(sb.socket_path, timeout=5.0).call("get_status", req_id=901)
        assert st_resp["ok"] is True and elapsed_ms < 50.0, f"Concurrent get_status took {elapsed_ms:.1f}ms"
        t.join(timeout=10.0)
        sb.clear_faults()

        # 7. Manual override back to allow -> auto-clears locked_deny, clears user-fixed, calls pm grant
        restored = sb.rpc_ok(
            "set_app_permission_action",
            {"package_name": pkg, "category": "camera", "action": "allow"},
        )
        cam2 = {c["category"]: c for c in restored["categories"]}["camera"]
        assert cam2["appops_mode"] in ("default", "allow") and cam2["runtime_granted"] is True
        cfg_after = sb.rpc_ok("get_config")
        assert "camera" not in cfg_after["scopes"]["guards"].get(pkg, {}).get("locked_deny", [])
        cam_flags_after = sb.read_android_state()["packages"][pkg]["runtime_perms"]["android.permission.CAMERA"]["flags"]
        assert "USER_FIXED" not in cam_flags_after


@e2e_test(
    "T4-S02",
    tier=4,
    features=["F5", "F4", "F7", "F11"],
    description="Panic Lockdown with prior DefaultAllow custom rules, multi-user profiles (u10/u95/u150) & xfrm+ VPN, then cleared cleanly by Balanced preset",
)
def test_scenario_s02_panic_lockdown_multi_user_and_recovery() -> None:
    user_uids = [10277, 1_010_050, 9_510_050, 15_010_050]
    with SiloDaemonSandbox(use_unshare=True) as sb:
        fw = sb.rpc_ok("get_config")["firewall"]
        fw["enabled"] = True
        fw["mode"] = "default_allow"
        sb.rpc_ok("set_firewall_config", {"config": fw})

        for uid in user_uids:
            sb.rpc_ok(
                "set_app_firewall_rule",
                {
                    "uid": uid,
                    "rule": {
                        "package_name": f"com.example.profile{uid}",
                        "allow_wifi": True,
                        "allow_cellular": True,
                        "allow_vpn": True,
                        "allow_lan": False,
                        "allow_screen_off": True,
                        "block_cleartext_http": False,
                        "block_localhost": False,
                        "is_vpn_app": False,
                    },
                },
            )
        # Verify xfrm+ appears in per-app VPN/interface rules when restricted
        sb.rpc_ok(
            "set_app_firewall_rule",
            {
                "uid": 10290,
                "rule": {
                    "package_name": "com.example.novpn",
                    "allow_wifi": False,
                    "allow_cellular": False,
                    "allow_vpn": False,
                    "allow_lan": False,
                    "allow_screen_off": False,
                    "block_cleartext_http": True,
                    "block_localhost": False,
                    "is_vpn_app": False,
                },
            },
        )
        v4_pre = sb.last_iptables_script(v6=False)
        assert "xfrm+" in v4_pre, f"Expected xfrm+ interface rule in:\n{v4_pre}"

        # Trigger Panic Lockdown
        rep = sb.rpc_ok("trigger_panic_lockdown")
        assert rep["firewall_locked"] is True
        assert rep["mic_muted"] is True and rep["camera_muted"] is True
        assert rep["wifi_disabled"] is True and rep["bluetooth_disabled"] is True
        assert rep["clipboard_cleared"] is True

        st = sb.rpc_ok("get_status")
        assert st.get("panic_lockdown_active") is True

        v4_lock = sb.last_iptables_script(v6=False)
        v6_lock = sb.last_iptables_script(v6=True)
        for uid in user_uids:
            assert not re.search(rf"-A SILO_OUT\s+-m owner --uid-owner {uid}\b.*-j RETURN", v4_lock), (
                f"Panic Lockdown leaked prior DefaultAllow RETURN rule for UID {uid}:\n{v4_lock}"
            )
        assert "--uid-owner 0-9999 -j RETURN" in v4_lock
        assert ":SILO_OUT" in v6_lock

        # Protected UIDs remain rejected even during lockdown (with full valid AppFirewallRule payload)
        sb.rpc_err(
            "set_app_firewall_rule",
            {
                "uid": 1000,
                "rule": {
                    "package_name": "com.example.x",
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

        # Clear Panic Lockdown via Balanced preset
        cfg_bal = sb.rpc_ok("apply_preset", {"preset": "balanced"})
        assert cfg_bal["firewall"].get("panic_lockdown_active") is False
        assert cfg_bal["firewall"]["mode"] == "default_allow"
        assert sb.rpc_ok("get_status").get("panic_lockdown_active") is False


@e2e_test(
    "T4-S03",
    tier=4,
    features=["F4", "F5", "F9"],
    description="Large IP/CIDR blocklist (>512 KiB / ~28,000 CIDRs + invalid tokens) over --bridge + export/import + corrupt config recovery",
)
def test_scenario_s03_large_blocklist_bridge_and_config_durability() -> None:
    v4_entries = [f"198.{b}.{c}.0/24" for b in range(1, 115) for c in range(1, 246)]
    v6_entries = [f"2001:db8:{i:x}::/48" for i in range(1, 501)]
    bad_entries = ["127.0.0.1", "::1", "0.0.0.0/0", "not_valid_ip", "198.51.100.0/33"]
    all_entries = v4_entries + v6_entries + bad_entries
    assert len(all_entries) > 28_000
    assert len(json.dumps({"entries": all_entries})) > 550_000

    with SiloDaemonSandbox(use_unshare=True) as sb:
        fw = sb.rpc_ok("get_config")["firewall"]
        fw["enabled"] = True
        sb.rpc_ok("set_firewall_config", {"config": fw})

        with sb.open_bridge() as br:
            resp = br.call("replace_ip_blocklist", {"entries": all_entries}, req_id=3001, timeout=40.0)
            assert resp["id"] == 3001 and resp["ok"] is True, f"Large --bridge blocklist failed: {resp}"
            assert resp["data"]["loaded"] == len(v4_entries) + len(v6_entries)
            assert resp["data"]["rejected"] == len(bad_entries)

            exp = br.call("export_config_json", req_id=3002, timeout=20.0)
            assert exp["ok"] is True
            exported_json = exp["data"]["json"]

        # Corrupt config.json on disk, restart, verify .corrupt.* backup created
        sb.stop()
        sb.config_path.write_text('{"schema_version":3,"truncated":', encoding="utf-8")
        sb.start()

        backups = list(sb.state_dir.glob("config.json.corrupt.*"))
        assert backups, "Expected automatic config.json.corrupt.* backup after corrupt restart"

        # Import previously exported config cleanly over --bridge
        with sb.open_bridge() as br2:
            imp = br2.call("import_config_json", {"json": exported_json}, req_id=3003, timeout=30.0)
            assert imp["ok"] is True
            assert len(imp["data"]["firewall"]["ip_blocklist"]) == len(v4_entries) + len(v6_entries)


@e2e_test(
    "T4-S04",
    tier=4,
    features=["F4", "F6", "F8", "F11", "F12"],
    description="Daemon crash, flock singleton, --bridge transparent reconnect, non-silod PID safety in service.sh/uninstall.sh, and real --uninstall-cleanup",
)
def test_scenario_s04_crash_restart_bridge_reconnect_and_uninstall_lifecycle() -> None:
    pkg_f = "com.example.s04frozen"
    pkg_g = "com.example.s04guard"
    with SiloDaemonSandbox(use_unshare=True) as sb:
        # Configure active firewall + freezer + guards + hardening
        sb.rpc_ok("apply_preset", {"preset": "hardened"})
        sb.set_runtime_perm(pkg_g, "android.permission.CAMERA", granted=True)
        sb.rpc_ok(
            "set_app_perm_guard",
            {"package_name": pkg_g, "guard": {"auto_revoke_on_bg": [], "locked_deny": ["camera"]}},
        )
        sb.rpc_ok(
            "set_freezer_entry",
            {
                "uid": 10444,
                "entry": {
                    "package_name": pkg_f,
                    "mode": "deep_stop",
                    "freeze_on_screen_off": True,
                    "freeze_on_background": True,
                    "kill_sockets_on_freeze": False,
                },
            },
        )
        sb.rpc_ok("freeze_uid_now", {"uid": 10444})

        # 1. Verify silod.lock blocks duplicate daemon instance
        dup = subprocess.Popen(
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
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        assert dup.wait(timeout=8.0) != 0, "Duplicate silod must be blocked by silod.lock"

        # 2. Keep a persistent silod --bridge open across silod crash (SIGKILL) & restart
        with sb.open_bridge() as br:
            assert br.call("ping", req_id=1)["ok"] is True
            sb.stop(sig=9)
            sb.start()
            rec = br.call("get_status", req_id=2, timeout=15.0)
            assert rec["id"] == 2 and rec["ok"] is True and rec["data"]["version"] == "3.0.0", (
                f"Bridge failed transparent reconnect after crash/restart: {rec}"
            )

        # 3. Stop daemon and execute real silod --uninstall-cleanup verifying full flush + unsuspend + AppOps/flag reset
        sb.stop()
        log_before = len(sb.read_cmd_log())
        u_res = sb.run_cli(["--uninstall-cleanup", "--config", str(sb.config_path)], use_unshare=True)
        assert u_res.returncode == 0, f"--uninstall-cleanup failed: {u_res}"
        new_logs = sb.read_cmd_log()[log_before:]
        assert any(e["tool"] == "pm" and "unsuspend" in e["argv"] and pkg_f in e["argv"] for e in new_logs)
        assert any(e["tool"] == "cmd" and "appops" in e["argv"] and "default" in e["argv"] and pkg_g in e["argv"] for e in new_logs)
        assert any(
            (e["tool"] in ("pm", "cmd")) and "clear-permission-flags" in e["argv"] and pkg_g in e["argv"]
            for e in new_logs
        )

    # 4. Verify non-silod stale PID protection in module/service.sh and module/uninstall.sh
    for script in ("service.sh", "uninstall.sh"):
        with module_harness.ModuleSandbox(silod_mode="native_stub") as msb:
            msb.state_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
            dummy = msb.spawn_non_silod_dummy(60)
            (msb.state_dir / "silod.pid").write_text(f"{dummy.pid}\n", encoding="utf-8")
            res = msb.run_script(script, timeout=15.0)
            assert res.returncode == 0 and dummy.poll() is None, (
                f"{script} killed non-silod stale PID {dummy.pid}!"
            )


@e2e_test(
    "T4-S05",
    tier=4,
    features=["F7", "F8", "F9"],
    description="Preset switching (Paranoid -> Balanced -> Safe -> Hardened) + sensors/ADB/clipboard + hardening rollback + Live Proof & Sentinel",
)
def test_scenario_s05_preset_switching_automation_rollback_and_verification() -> None:
    with SiloDaemonSandbox(use_unshare=True) as sb:
        # Cycle all 4 presets verifying zero leftovers
        p1 = sb.rpc_ok("apply_preset", {"preset": "paranoid"})
        assert p1["firewall"]["mode"] == "default_deny" and p1["automation"]["sensors_off_on_lock"] is True

        p2 = sb.rpc_ok("apply_preset", {"preset": "balanced"})
        assert p2["firewall"]["mode"] == "default_allow" and p2["automation"]["sensors_off_on_lock"] is False
        assert p2["automation"]["wifi_auto_off_enabled"] is False
        assert p2["automation"]["bluetooth_auto_off_enabled"] is False

        p3 = sb.rpc_ok("apply_preset", {"preset": "safe"})
        assert p3["hardening"]["enabled"] is False
        assert p3["scopes"]["auto_clear_clipboard"] is False
        assert p3["automation"]["usb_adb_lock_guard"] is False

        p4 = sb.rpc_ok("apply_preset", {"preset": "hardened"})
        assert p4["hardening"]["enabled"] is True and p4["firewall"]["enabled"] is True

        # Sensors, clipboard clear (with label=null clip active), private DNS, radio privacy
        sp = sb.rpc_ok("set_sensor_privacy", {"sensor": "all", "enabled": True})
        assert sp["mic_muted"] is True and sp["camera_muted"] is True
        sb.rpc_ok("clear_clipboard_now")
        assert sb.read_android_state()["clipboard"]["has_clip"] is False
        dns = sb.rpc_ok("set_private_dns", {"mode": "hostname", "specifier": "dns.quad9.net"})
        assert dns["mode"] == "hostname"

        # Live Proof & Sentinel Audit
        proof = sb.rpc_ok("run_live_proof")
        assert proof["ipv4_hook_attached"] is True and len(proof["items"]) >= 8
        sent = sb.rpc_ok("run_sentinel_audit")
        assert 0 <= sent["score"] <= 100 and len(sent["findings"]) > 0

        # Rollback hardening
        rb = sb.rpc_ok("rollback_hardening")
        assert rb["enabled"] is False


@e2e_test(
    "T4-S06",
    tier=4,
    features=["F10", "F12"],
    description="End-to-End Release Artifact Security Gate: Android APK (zero-INTERNET/disguise/QS tiles/R8), KernelSU zero-mount ZIP, and CI workflow audit",
)
def test_scenario_s06_release_artifact_security_gate() -> None:
    # 1. Real Android unit tests (JUnit XML 100% pass)
    xml_dir = android_harness.ensure_unit_tests_run()
    junit = android_harness.parse_junit_xml_dir(xml_dir)
    assert junit.total_tests >= 5 and junit.failures == 0 and junit.errors == 0 and junit.skipped == 0

    # 2. Real Release APK verification (signature, zero-INTERNET, zero-POST_NOTIFICATIONS, disguise, QS tiles, R8 enums)
    apk = android_harness.ensure_release_apk_built()
    android_harness.apksigner_verify(apk)
    perms = set(android_harness.aapt2_permissions(apk))
    assert "android.permission.INTERNET" not in perms
    assert "android.permission.POST_NOTIFICATIONS" not in perms
    assert "android.permission.QUERY_ALL_PACKAGES" in perms

    tree = android_harness.aapt2_manifest_tree(apk)
    main_act = next(
        n
        for n in tree.find_all("activity")
        if android_harness.normalize_component_fqcn(str(n.attrs.get("name", ""))) == "dev.silo.app.MainActivity"
    )
    assert main_act.attrs.get("exported") is False
    assert tree.find_all("application")[0].attrs.get("allowBackup") is False

    aliases = {
        android_harness.normalize_component_fqcn(str(n.attrs.get("name", ""))): n
        for n in tree.find_all("activity-alias")
    }
    assert aliases["dev.silo.app.LauncherDefault"].attrs.get("enabled") is True
    assert aliases["dev.silo.app.LauncherDisguise"].attrs.get("enabled") is False

    assert not ci_harness.audit_proguard_rules()
    descriptors, _ = android_harness.dexdump_inspect_apk(apk)
    for enum_desc in (
        "Ldev/silo/app/ipc/FirewallMode;",
        "Ldev/silo/app/ipc/BlockAction;",
        "Ldev/silo/app/ipc/FreezeMode;",
        "Ldev/silo/app/ipc/SecurityPreset;",
        "Ldev/silo/app/ipc/AuditSeverity;",
    ):
        assert enum_desc in descriptors, f"Missing enum {enum_desc} in Release APK DEX"

    for tile_rel in ("dev/silo/app/tiles/LockdownTileService", "dev/silo/app/tiles/SensorsOffTileService"):
        disasm = android_harness.javap_disassemble_classes(android_harness.KOTLIN_DEBUG_CLASSES, tile_rel)
        assert "isLocked:()Z" in disasm and "unlockAndRun:(Ljava/lang/Runnable;)V" in disasm

    # 3. KernelSU Module ZIP packaging (honors SILOD_ARM64_BIN when set, else verified 136B static ARM64 ELF)
    with tempfile.TemporaryDirectory(prefix="silo_s06_pkg_") as td:
        td_p = Path(td)
        arm64_bin, _is_real = packaging_harness.resolve_arm64_binary_or_fixture(td_p)
        out_zip = td_p / "silo-ksu-module.zip"
        res = packaging_harness.run_build_module(arm64_bin, out_zip)
        assert res.returncode == 0, f"build_module.sh failed: {res}"
        packaging_harness.assert_valid_module_zip(out_zip, expected_silod_bin=arm64_bin)

    # 4. Shell syntax + CI workflow + Cargo metadata
    for path_key, (rc, out) in module_harness.check_shell_syntax().items():
        assert rc == 0, f"Syntax check failed for {path_key}: {out}"
    assert not ci_harness.audit_workflow_security(ci_harness.load_workflow())
    assert not ci_harness.audit_cargo_metadata()


@e2e_test(
    "T4-S07",
    tier=4,
    features=["F6", "F11", "F5"],
    description="App Freezer (SoftCgroup & DeepStop + multi-user) lifecycle + protected boundary defense + restart durability",
)
def test_scenario_s07_freezer_lifecycle_and_protected_boundary_defense() -> None:
    work_uid = 1_010_277  # User 10 Work Profile
    with SiloDaemonSandbox(use_unshare=True) as sb:
        sb.rpc_ok("set_freezer_enabled", {"enabled": True, "background_grace_secs": 30})
        # Mixed SoftCgroup + DeepStop + Work Profile entries
        sb.rpc_ok(
            "set_freezer_entry",
            {
                "uid": 10380,
                "entry": {
                    "package_name": "com.example.softapp",
                    "mode": "soft_cgroup",
                    "freeze_on_screen_off": True,
                    "freeze_on_background": True,
                    "kill_sockets_on_freeze": True,
                },
            },
        )
        sb.rpc_ok(
            "set_freezer_entry",
            {
                "uid": 10381,
                "entry": {
                    "package_name": "com.example.deepapp",
                    "mode": "deep_stop",
                    "freeze_on_screen_off": True,
                    "freeze_on_background": True,
                    "kill_sockets_on_freeze": True,
                },
            },
        )
        sb.rpc_ok(
            "set_freezer_entry",
            {
                "uid": work_uid,
                "entry": {
                    "package_name": "com.example.workdeep",
                    "mode": "deep_stop",
                    "freeze_on_screen_off": True,
                    "freeze_on_background": True,
                    "kill_sockets_on_freeze": False,
                },
            },
        )

        # Freeze both DeepStop apps and SoftCgroup app
        st_d1 = sb.rpc_ok("freeze_uid_now", {"uid": 10381})
        assert "com.example.deepapp" in st_d1["suspended_packages"]
        st_w = sb.rpc_ok("freeze_uid_now", {"uid": work_uid})
        assert "com.example.workdeep" in st_w["suspended_packages"]
        sb.rpc_ok("freeze_uid_now", {"uid": 10380})

        # Protected UID & spoofed-package bypass attempts MUST ALL be rejected
        for uid, pkg in (
            (0, "com.example.fake"),
            (1000, ""),
            (1001000, "com.example.fake"),
            (10555, "dev.silo.app"),
            (10556, "com.rifsxd.ksunext"),
        ):
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
        sb.rpc_err("freeze_uid_now", {"uid": 0})
        sb.rpc_err("freeze_uid_now", {"uid": 1000})

        # Verify LISTEN socket immune to kill_connection
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as srv:
            srv.bind(("127.0.0.1", 0))
            srv.listen(1)
            port = srv.getsockname()[1]
            resp, _ = sb.rpc(
                "kill_connection",
                {
                    "family": 4,
                    "local_ip": "127.0.0.1",
                    "local_port": port,
                    "remote_ip": "0.0.0.0",
                    "remote_port": 0,
                },
            )
            if resp.get("ok"):
                assert resp["data"]["killed"] == 0

        # Restart durability: entries persist & thaw_all_now unsuspends recovered packages
        sb.restart()
        cfg_after = sb.rpc_ok("get_config")
        assert set(cfg_after["freezer"]["entries"].keys()) == {"10380", "10381", str(work_uid)}

        st_thaw = sb.rpc_ok("thaw_all_now")
        assert st_thaw["suspended_packages"] == []
