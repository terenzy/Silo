"""Tier 3 — Module lifecycle & privilege boundary pairwise tests (T3-P10..T3-P13)."""

from __future__ import annotations

import json
import os
import time

from tests.e2e.harness import module_harness
from tests.e2e.harness.registry import e2e_test
from tests.e2e.harness.silod_harness import (
    BridgeClient,
    SiloDaemonSandbox,
    UnixRpcClient,
    run_silod_cli,
)


@e2e_test(
    "T3-P10",
    tier=3,
    features=["F11", "F12"],
    description="F11 x F12: Re-running service.sh over live real silod cleanly replaces PID_1 with PID_2 without flock or socket-unlink race",
)
def test_pair_f11_f12_service_sh_reexec_over_live_silod() -> None:
    with module_harness.ModuleSandbox(silod_mode="real") as sb:
        r1 = sb.run_script("service.sh", timeout=20.0)
        assert r1.returncode == 0
        pid1 = int((sb.state_dir / "silod.pid").read_text(encoding="utf-8").strip())
        sock = sb.state_dir / "silod.sock"
        deadline = time.monotonic() + 6.0
        while time.monotonic() < deadline and not sock.exists():
            time.sleep(0.05)
        assert sock.exists()

        # Re-run service.sh while PID_1 is running
        r2 = sb.run_script("service.sh", timeout=20.0)
        assert r2.returncode == 0, f"2nd service.sh run failed: {r2}"
        pid2 = int((sb.state_dir / "silod.pid").read_text(encoding="utf-8").strip())
        assert pid2 != pid1, f"Expected replacement daemon PID_2 != PID_1 ({pid1})"

        time.sleep(0.2)
        old_alive = True
        try:
            os.kill(pid1, 0)
        except ProcessLookupError:
            old_alive = False
        assert not old_alive, f"Old silod PID_1={pid1} was not stopped before spawning PID_2={pid2}"

        deadline = time.monotonic() + 6.0
        while time.monotonic() < deadline:
            st_res = run_silod_cli(["--socket", str(sock), "--status"], silod_bin=sb.silod_path)
            if st_res.returncode == 0:
                st_json = json.loads(st_res.stdout.strip())
                assert st_json["ok"] is True and st_json["data"]["pid"] == pid2
                return
            time.sleep(0.1)
        raise AssertionError(f"Replacement silod PID_2={pid2} never responded on {sock}")


@e2e_test(
    "T3-P11",
    tier=3,
    features=["F12", "F1", "F2"],
    description="F12 x F1 x F2: Live silod with Kunci Blokir + AppOps -> sandboxed uninstall.sh runs --uninstall-cleanup resetting AppOps & flags BEFORE removing STATE_DIR",
)
def test_pair_f12_f1_f2_guards_cleaned_up_by_uninstall_sh() -> None:
    pkg = "com.example.uninstallperm"
    with module_harness.ModuleSandbox(silod_mode="real") as sb:
        r_start = sb.run_script("service.sh", timeout=20.0)
        assert r_start.returncode == 0
        sock = sb.state_dir / "silod.sock"
        deadline = time.monotonic() + 6.0
        while time.monotonic() < deadline and not sock.exists():
            time.sleep(0.05)
        assert sock.exists()

        cli = UnixRpcClient(sock, timeout=10.0)
        resp, _ = cli.call(
            "set_app_perm_guard",
            {"package_name": pkg, "guard": {"auto_revoke_on_bg": [], "locked_deny": ["camera"]}},
        )
        assert resp["ok"] is True, f"set_app_perm_guard failed: {resp}"

        # Execute uninstall.sh -> stops silod, runs --uninstall-cleanup --config, removes STATE_DIR!
        # Wait: STATE_DIR holds cmd_log.jsonl in ModuleSandbox when silod_mode="real", so read stub/cmd behavior via wrapper or move log?
        # Redirect cmd_log path check: uninstall.sh removes STATE_DIR; let's inspect uninstall.sh behavior by checking returncode, process death, and STATE_DIR deletion,
        # AND we verify exact cmd/pm reset commands via a dedicated pre-uninstall log copy or verifying --uninstall-cleanup directly!
        pid_before = int((sb.state_dir / "silod.pid").read_text(encoding="utf-8").strip())
        # Copy config.json to logs_dir so we can also assert config had locked_deny before uninstall
        cfg_before = json.loads((sb.state_dir / "config.json").read_text(encoding="utf-8"))
        assert "camera" in cfg_before["scopes"]["guards"][pkg]["locked_deny"]

        # Create a sidecar logger wrapper in bin_dir around cmd/pm to record uninstall cleanup calls OUTSIDE state_dir!
        sidecar_log = sb.logs_dir / "uninstall_android_calls.log"
        for tool in ("cmd", "pm"):
            orig = sb.bin_dir / tool
            orig_bak = sb.bin_dir / f"{tool}.orig"
            orig.rename(orig_bak)
            orig.write_text(
                "#!/bin/sh\n"
                f'printf "%s %s\\n" "{tool}" "$*" >> "{sidecar_log}"\n'
                f'exec "{orig_bak}" "$@"\n',
                encoding="utf-8",
            )
            orig.chmod(0o755)

        r_un = sb.run_script("uninstall.sh", timeout=20.0)
        assert r_un.returncode == 0, f"uninstall.sh failed: {r_un}"
        time.sleep(0.15)
        try:
            os.kill(pid_before, 0)
            raise AssertionError(f"uninstall.sh left silod PID {pid_before} alive!")
        except ProcessLookupError:
            pass
        assert not sb.state_dir.exists(), "uninstall.sh must remove STATE_DIR"

        calls_txt = sidecar_log.read_text(encoding="utf-8") if sidecar_log.is_file() else ""
        assert pkg in calls_txt and "default" in calls_txt, (
            f"uninstall.sh -> --uninstall-cleanup MUST reset AppOps to default for {pkg} before deleting STATE_DIR; calls:\n{calls_txt}"
        )
        assert "clear-permission-flags" in calls_txt, (
            f"uninstall.sh -> --uninstall-cleanup MUST clear user-fixed/revoked-compat flags for {pkg}; calls:\n{calls_txt}"
        )


@e2e_test(
    "T3-P12",
    tier=3,
    features=["F12", "F4"],
    description="F12 x F4: Dead-Man Switch auto-disable -> action.sh recovery removes disable -> live silod --bridge RPC succeeds",
)
def test_pair_f12_f4_dead_man_switch_recovery_and_bridge_rpc() -> None:
    with module_harness.ModuleSandbox(silod_mode="real") as sb:
        sb.state_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
        (sb.state_dir / ".boot_fail_count").write_text("2\n", encoding="utf-8")

        r_dms = sb.run_script("service.sh", timeout=20.0)
        assert r_dms.returncode == 0
        assert (sb.mod_dir / "disable").is_file(), "Dead-Man Switch must create $MODDIR/disable"

        r_rec = sb.run_script("action.sh", timeout=20.0)
        assert r_rec.returncode == 0, f"action.sh recovery failed: {r_rec}"
        assert not (sb.mod_dir / "disable").exists(), "action.sh must remove $MODDIR/disable"

        sock = sb.state_dir / "silod.sock"
        with BridgeClient(sock, silod_bin=sb.silod_path, env=sb.build_env()) as br:
            p = br.call("ping", req_id=404)
            assert p["id"] == 404 and p["ok"] is True and p["data"]["pong"] is True
            st = br.call("get_status", req_id=405)
            assert st["id"] == 405 and st["ok"] is True and st["data"]["version"] == "3.0.0"


@e2e_test(
    "T3-P13",
    tier=3,
    features=["F6", "F11", "F5"],
    description="F6 x F11 x F5: Under Panic Lockdown, protected UIDs/packages stay immune across Firewall + Freezer while ordinary user apps are governed normally",
)
def test_pair_f6_f11_f5_protected_boundary_under_panic_lockdown() -> None:
    with SiloDaemonSandbox(use_unshare=True) as sb:
        sb.rpc_ok("trigger_panic_lockdown")
        assert sb.rpc_ok("get_status").get("panic_lockdown_active") is True

        # Protected UIDs & packages must remain immune across firewall & freezer
        for uid, pkg in ((0, "android"), (1000, "com.android.systemui"), (10555, "dev.silo.app")):
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

        # Ordinary user app can still be frozen & thawed normally
        sb.rpc_ok(
            "set_freezer_entry",
            {
                "uid": 10377,
                "entry": {
                    "package_name": "com.example.normalapp",
                    "mode": "deep_stop",
                    "freeze_on_screen_off": True,
                    "freeze_on_background": True,
                    "kill_sockets_on_freeze": False,
                },
            },
        )
        st_frz = sb.rpc_ok("freeze_uid_now", {"uid": 10377})
        assert "com.example.normalapp" in st_frz["suspended_packages"]
        st_thw = sb.rpc_ok("thaw_uid_now", {"uid": 10377})
        assert "com.example.normalapp" not in st_thw["suspended_packages"]
