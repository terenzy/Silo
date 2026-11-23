"""Tier 1 (F12) — KernelSU Module Lifecycle, Packaging & CI Security happy-path E2E tests."""

from __future__ import annotations

import json
import os
import stat
import tempfile
import time
from pathlib import Path

from tests.e2e.harness import ci_harness, module_harness, packaging_harness
from tests.e2e.harness.registry import e2e_test
from tests.e2e.harness.silod_harness import REPO_ROOT, SiloDaemonSandbox, run_silod_cli


@e2e_test(
    "T1-F12-01",
    tier=1,
    features=["F12"],
    description="sh -n passes on all 4 module/*.sh scripts, bash -n passes on scripts/build_module.sh, and module.prop matches Cargo.toml",
)
def test_shell_syntax_and_module_prop_parity() -> None:
    syntax = module_harness.check_shell_syntax()
    for path_key, (rc, out) in syntax.items():
        assert rc == 0, f"Shell syntax check failed for {path_key} (rc={rc}): {out}"

    prop_text = (REPO_ROOT / "module" / "module.prop").read_text(encoding="utf-8")
    props = packaging_harness.parse_module_prop_text(prop_text)
    cargo = ci_harness.load_cargo_toml()

    assert props.get("id") == "silo"
    assert props.get("version") == f"v{cargo['package']['version']}"
    assert props.get("versionCode") == "30000"
    assert props.get("author") == "terenzy"


@e2e_test(
    "T1-F12-02",
    tier=1,
    features=["F12"],
    description="module/customize.sh accepts arm64-v8a & ARCH=arm64 fallback, sets modes 0755/0700, and clears stale .boot_fail_count & disable marker",
)
def test_customize_sh_happy_path_and_disable_cleanup() -> None:
    # (a) getprop arm64-v8a + stale .boot_fail_count & /data/adb/modules/silo/disable
    with module_harness.ModuleSandbox(
        silod_mode="native_stub",
        getprop_map={"ro.product.cpu.abi": "arm64-v8a"},
    ) as sb:
        sb.state_dir.mkdir(parents=True, exist_ok=True)
        (sb.state_dir / ".boot_fail_count").write_text("2\n", encoding="utf-8")
        disable_marker = sb.mod_dir / "disable"
        disable_marker.write_text("", encoding="utf-8")
        # Reset modes to verify customize.sh fixes them
        sb.update_silod_path.chmod(0o644)
        (sb.mod_update_dir / "service.sh").chmod(0o644)

        res = sb.run_customize(arch=None)
        assert res.returncode == 0, f"customize.sh failed (rc={res.returncode}):\n{res.stdout}\n{res.stderr}"
        assert stat.S_IMODE(os.stat(sb.update_silod_path).st_mode) == 0o755
        assert stat.S_IMODE(os.stat(sb.mod_update_dir / "service.sh").st_mode) == 0o755
        assert stat.S_IMODE(os.stat(sb.state_dir).st_mode) == 0o700
        assert not (sb.state_dir / ".boot_fail_count").exists(), "Stale .boot_fail_count must be deleted"
        assert not disable_marker.exists(), (
            "customize.sh MUST remove stale /data/adb/modules/silo/disable from prior boot-guard trigger (CB-18 / Contract §4)"
        )

    # (b) empty getprop with pre-set ARCH=arm64 fallback (CB-28)
    with module_harness.ModuleSandbox(
        silod_mode="native_stub",
        getprop_map={"ro.product.cpu.abi": "", "ro.product.cpu.abilist64": ""},
    ) as sb:
        res2 = sb.run_customize(arch="arm64")
        assert res2.returncode == 0, (
            f"customize.sh must honor pre-set ARCH=arm64 fallback when getprop is empty (CB-28):\n{res2.stdout}\n{res2.stderr}"
        )


@e2e_test(
    "T1-F12-03",
    tier=1,
    features=["F12"],
    description="module/service.sh polls boot_completed, arms .boot_fail_count=1 (0600), launches real silod, and responds to --status",
)
def test_service_sh_launches_real_silod_cleanly() -> None:
    with module_harness.ModuleSandbox(
        silod_mode="real",
        getprop_map={"sys.boot_completed": ["0", "1"]},
    ) as sb:
        res = sb.run_script("service.sh", timeout=20.0)
        assert res.returncode == 0, f"service.sh failed: {res}"

        assert stat.S_IMODE(os.stat(sb.state_dir).st_mode) == 0o700
        boot_marker = sb.state_dir / ".boot_fail_count"
        assert boot_marker.is_file() and boot_marker.read_text(encoding="utf-8").strip() == "1"
        assert stat.S_IMODE(os.stat(boot_marker).st_mode) == 0o600

        pid_file = sb.state_dir / "silod.pid"
        assert pid_file.is_file()
        assert stat.S_IMODE(os.stat(pid_file).st_mode) == 0o600
        pid = int(pid_file.read_text(encoding="utf-8").strip())
        os.kill(pid, 0)  # Verify live process

        # Poll for socket readiness and run real --status
        sock_path = sb.state_dir / "silod.sock"
        deadline = time.monotonic() + 6.0
        while time.monotonic() < deadline and not sock_path.exists():
            time.sleep(0.05)
        assert sock_path.exists(), "service.sh did not bring up silod.sock"

        st_res = run_silod_cli(
            ["--socket", str(sock_path), "--status"],
            silod_bin=sb.silod_path,
        )
        assert st_res.returncode == 0, f"silod --status failed: {st_res}"
        st_json = json.loads(st_res.stdout.strip())
        assert st_json["ok"] is True and st_json["data"]["version"] == "3.0.0"


@e2e_test(
    "T1-F12-04",
    tier=1,
    features=["F12"],
    description="module/action.sh clears disable + .boot_fail_count, recovers real silod, and preserves running PID on healthy 2nd run",
)
def test_action_sh_recovery_and_healthy_idempotency() -> None:
    with module_harness.ModuleSandbox(silod_mode="real") as sb:
        sb.state_dir.mkdir(parents=True, exist_ok=True)
        disable_marker = sb.mod_dir / "disable"
        disable_marker.write_text("", encoding="utf-8")
        boot_marker = sb.state_dir / ".boot_fail_count"
        boot_marker.write_text("2\n", encoding="utf-8")

        res1 = sb.run_script("action.sh", timeout=20.0)
        assert res1.returncode == 0, f"action.sh recovery failed:\n{res1.stdout}\n{res1.stderr}"
        assert "silod 3.0.0" in res1.stdout
        assert "[OK]" in res1.stdout
        assert not disable_marker.exists(), (
            "action.sh MUST remove $MODDIR/disable during manual recovery (CB-18 / Contract §4)"
        )
        assert not boot_marker.exists(), "action.sh must remove stale .boot_fail_count"

        pid1 = int((sb.state_dir / "silod.pid").read_text(encoding="utf-8").strip())
        os.kill(pid1, 0)

        # Healthy 2nd run must keep the same silod PID running!
        res2 = sb.run_script("action.sh", timeout=20.0)
        assert res2.returncode == 0, f"action.sh healthy check failed:\n{res2.stdout}\n{res2.stderr}"
        assert "[OK]" in res2.stdout
        pid2 = int((sb.state_dir / "silod.pid").read_text(encoding="utf-8").strip())
        assert pid2 == pid1, f"Healthy action.sh must NOT restart running silod (pid1={pid1}, pid2={pid2})"


@e2e_test(
    "T1-F12-05",
    tier=1,
    features=["F12"],
    description="module/uninstall.sh stops verified silod PID, runs --uninstall-cleanup --config WHILE config.json still exists, and removes STATE_DIR",
)
def test_uninstall_sh_stops_silod_and_runs_uninstall_cleanup_before_rm() -> None:
    with module_harness.ModuleSandbox(silod_mode="native_stub") as sb:
        sb.state_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
        cfg_path = sb.state_dir / "config.json"
        cfg_path.write_text('{"schema_version": 3}\n', encoding="utf-8")

        proc = sb.spawn_verified_silod_stub(socket_path=sb.state_dir / "silod.sock")
        (sb.state_dir / "silod.pid").write_text(f"{proc.pid}\n", encoding="utf-8")

        res = sb.run_script("uninstall.sh", timeout=20.0)
        assert res.returncode == 0, f"uninstall.sh failed:\n{res.stdout}\n{res.stderr}"

        # Verified silod process must be dead
        time.sleep(0.1)
        assert proc.poll() is not None, f"uninstall.sh failed to terminate verified silod PID {proc.pid}"

        # Stub log must confirm --uninstall-cleanup ran with --config while config.json still existed!
        stub_calls = sb.read_stub_log()
        cleanup_calls = [c for c in stub_calls if "--uninstall-cleanup" in c.get("argv", [])]
        assert cleanup_calls, (
            f"uninstall.sh MUST invoke `silod --uninstall-cleanup --config <cfg>` (CB-19 / Contract §4); calls={stub_calls}"
        )
        assert cleanup_calls[0].get("cfg_exists") is True, (
            f"--uninstall-cleanup must run BEFORE rm -rf STATE_DIR so config.json is readable! call={cleanup_calls[0]}"
        )
        assert not sb.state_dir.exists(), f"uninstall.sh must remove STATE_DIR {sb.state_dir}"


@e2e_test(
    "T1-F12-06",
    tier=1,
    features=["F12"],
    description="Real silod CLI --version, --emergency-flush, and --uninstall-cleanup reset firewall, freezer suspensions, AppOps & permission flags",
)
def test_real_silod_cli_version_flush_and_uninstall_cleanup() -> None:
    pkg_freeze = "com.example.frozenapp"
    pkg_guard = "com.example.guardedapp"
    with SiloDaemonSandbox(auto_start=False, use_unshare=True) as sb:
        cfg_data = {
            "schema_version": 3,
            "freezer": {
                "enabled": True,
                "background_grace_secs": 20,
                "entries": {
                    "10277": {
                        "package_name": pkg_freeze,
                        "mode": "deep_stop",
                        "freeze_on_screen_off": True,
                        "freeze_on_background": True,
                        "kill_sockets_on_freeze": True,
                    }
                },
            },
            "scopes": {
                "apps": {
                    pkg_guard: {
                        "isolate_contacts": True,
                        "block_camera_mic": True,
                    }
                },
                "guards": {
                    pkg_guard: {
                        "auto_revoke_on_bg": [],
                        "locked_deny": ["camera"],
                    }
                },
                "auto_clear_clipboard": False,
                "clipboard_clear_secs": 45,
            },
        }
        sb.config_path.write_text(json.dumps(cfg_data), encoding="utf-8")
        sb.set_suspended(pkg_freeze, user_id=0, suspended=True, uid=10277)
        sb.set_runtime_perm(
            pkg_guard,
            "android.permission.CAMERA",
            granted=False,
            flags=["USER_SET", "USER_FIXED", "REVOKED_COMPAT"],
        )
        sb.set_package_appops(pkg_guard, "CAMERA", uid_mode="ignore", pkg_mode="ignore")

        v_res = sb.run_cli(["--version"], use_unshare=False)
        assert v_res.returncode == 0 and v_res.stdout.strip() == "silod 3.0.0"

        f_res = sb.run_cli(["--emergency-flush", "--config", str(sb.config_path)], use_unshare=True)
        assert f_res.returncode == 0, f"--emergency-flush failed: {f_res}"
        assert any(
            e["tool"] == "pm" and "unsuspend" in e["argv"] and pkg_freeze in e["argv"]
            for e in sb.read_cmd_log()
        )

        # Now run --uninstall-cleanup (Contract §4 / CB-19)
        sb.set_suspended(pkg_freeze, user_id=0, suspended=True, uid=10277)
        log_before = len(sb.read_cmd_log())
        u_res = sb.run_cli(["--uninstall-cleanup", "--config", str(sb.config_path)], use_unshare=True)
        assert u_res.returncode == 0, f"--uninstall-cleanup failed (rc={u_res.returncode}): {u_res}"

        new_logs = sb.read_cmd_log()[log_before:]
        assert any(
            e["tool"] == "pm" and "unsuspend" in e["argv"] and pkg_freeze in e["argv"]
            for e in new_logs
        ), f"--uninstall-cleanup must unsuspend freezer entries: {new_logs}"
        assert any(
            e["tool"] == "cmd" and "appops" in e["argv"] and "default" in e["argv"] and pkg_guard in e["argv"]
            for e in new_logs
        ), f"--uninstall-cleanup MUST reset Silo-configured AppOps to default (CB-19): {new_logs}"
        assert any(
            (e["tool"] in ("pm", "cmd"))
            and "clear-permission-flags" in e["argv"]
            and pkg_guard in e["argv"]
            for e in new_logs
        ), f"--uninstall-cleanup MUST clear user-fixed/revoked-compat flags (CB-19): {new_logs}"


@e2e_test(
    "T1-F12-07",
    tier=1,
    features=["F12"],
    description="Real scripts/build_module.sh packages valid static ARM64 ELF into exact 6-file zero-mount ZIP with 0755 permissions",
)
def test_build_module_sh_valid_arm64_static_zip() -> None:
    with tempfile.TemporaryDirectory(prefix="silo_pkg_e2e_") as td:
        td_p = Path(td)
        elf_bin = packaging_harness.write_elf_fixture(td_p / "silod_arm64", "arm64_static")
        out_zip = td_p / "silo-ksu-module.zip"

        res = packaging_harness.run_build_module(elf_bin, out_zip)
        assert res.returncode == 0, f"build_module.sh failed:\n{res.stdout}\n{res.stderr}"
        packaging_harness.assert_valid_module_zip(out_zip, expected_silod_bin=elf_bin)


@e2e_test(
    "T1-F12-08",
    tier=1,
    features=["F12"],
    description=".github/workflows/build.yml, daemon/Cargo.toml, and .cargo/config.toml satisfy least-privilege CI, safe env inputs, 5-root zero-mount, and v3 metadata",
)
def test_ci_workflow_and_cargo_metadata_security() -> None:
    doc = ci_harness.load_workflow()
    wf_violations = ci_harness.audit_workflow_security(doc)
    assert not wf_violations, (
        "GitHub Actions CI workflow security/gate violations:\n- " + "\n- ".join(wf_violations)
    )

    cargo_violations = ci_harness.audit_cargo_metadata()
    assert not cargo_violations, (
        "Cargo metadata/static-musl violations:\n- " + "\n- ".join(cargo_violations)
    )
