"""Tier 2 (F12) — KernelSU Module Lifecycle, Packaging & CI Security boundary & negative tests."""

from __future__ import annotations

import json
import os
import tempfile
import time
from pathlib import Path

from tests.e2e.harness import ci_harness, module_harness, packaging_harness
from tests.e2e.harness.registry import e2e_test
from tests.e2e.harness.silod_harness import REPO_ROOT, SiloDaemonSandbox


@e2e_test(
    "T2-F12-01",
    tier=2,
    features=["F12"],
    description="PID-reuse & corrupt PID safety: service.sh, action.sh, and uninstall.sh MUST NOT kill non-silod /usr/bin/sleep PIDs or crash on corrupt silod.pid (CB-07)",
)
def test_pid_reuse_and_corrupt_pid_safety_all_three_scripts() -> None:
    for script_name in ("service.sh", "action.sh", "uninstall.sh"):
        with module_harness.ModuleSandbox(silod_mode="native_stub") as sb:
            sb.state_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
            dummy = sb.spawn_non_silod_dummy(seconds=60)
            (sb.state_dir / "silod.pid").write_text(f"{dummy.pid}\n", encoding="utf-8")

            res = sb.run_script(script_name, timeout=20.0)
            assert res.returncode == 0, f"{script_name} failed with non-silod stale PID: {res}"

            # Real /usr/bin/sleep PID MUST still be alive!
            assert dummy.poll() is None, (
                f"CRITICAL PID-REUSE BUG (CB-07): {script_name} killed non-silod PID {dummy.pid} (/usr/bin/sleep)!"
            )

    # Corrupt PID values ("0", "abc", "", "99999999") must never kill process group or crash
    for bad_pid in ("0", "abc", "", "99999999"):
        with module_harness.ModuleSandbox(silod_mode="native_stub") as sb:
            sb.state_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
            (sb.state_dir / "silod.pid").write_text(f"{bad_pid}\n", encoding="utf-8")
            res = sb.run_script("uninstall.sh", timeout=15.0)
            assert res.returncode == 0, f"uninstall.sh failed on corrupt silod.pid={bad_pid!r}: {res}"


@e2e_test(
    "T2-F12-02",
    tier=2,
    features=["F12"],
    description="Hung verified native-ELF silod ignoring SIGTERM is waited on and escalated to SIGKILL by both service.sh and uninstall.sh (CB-07)",
)
def test_hung_verified_silod_sigkill_escalation() -> None:
    for script_name in ("service.sh", "uninstall.sh"):
        with module_harness.ModuleSandbox(
            silod_mode="native_stub",
            sleep_scale={"1": 0.05, "2": 0.05, "5": 0.05, "default": 0.03},
        ) as sb:
            sb.state_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
            hung = sb.spawn_verified_silod_stub(
                ignore_term=True,
                socket_path=sb.state_dir / "silod.sock",
            )
            (sb.state_dir / "silod.pid").write_text(f"{hung.pid}\n", encoding="utf-8")

            res = sb.run_script(script_name, timeout=20.0)
            assert res.returncode == 0, f"{script_name} failed on hung silod: {res}"

            time.sleep(0.15)
            assert hung.poll() is not None, (
                f"{script_name} failed to escalate SIGTERM -> SIGKILL against hung verified silod PID {hung.pid}"
            )


@e2e_test(
    "T2-F12-03",
    tier=2,
    features=["F12"],
    description="service.sh Dead-Man Switch (FAIL_COUNT >= 2 runs --emergency-flush + creates disable) AND 300s boot timeout (CB-18)",
)
def test_service_sh_dead_man_switch_and_300s_boot_timeout() -> None:
    # (a) Dead-Man Switch when .boot_fail_count >= 2
    with module_harness.ModuleSandbox(silod_mode="native_stub") as sb:
        sb.state_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
        (sb.state_dir / ".boot_fail_count").write_text("2\n", encoding="utf-8")
        (sb.state_dir / "silod.pid").write_text("99999999\n", encoding="utf-8")

        res = sb.run_script("service.sh", timeout=15.0)
        assert res.returncode == 0, f"service.sh dead-man switch failed: {res}"
        assert (sb.mod_dir / "disable").is_file(), "Dead-man switch MUST create $MODDIR/disable"
        assert not (sb.state_dir / ".boot_fail_count").exists()
        assert not (sb.state_dir / "silod.pid").exists()
        calls = sb.read_stub_log()
        assert any("--emergency-flush" in c.get("argv", []) for c in calls), (
            f"Dead-man switch MUST invoke silod --emergency-flush: {calls}"
        )
        # Must NOT have launched daemon mode
        daemon_calls = [
            c
            for c in calls
            if not any(
                f in c.get("argv", [])
                for f in ("--emergency-flush", "--version", "--status", "--uninstall-cleanup")
            )
        ]
        assert not daemon_calls, f"Dead-man switch must NOT launch silod daemon: {daemon_calls}"

    # (b) 300s boot wait timeout when sys.boot_completed stays "0"
    with module_harness.ModuleSandbox(
        silod_mode="native_stub",
        sleep_scale={"default": 0.0, "1": 0.0, "2": 0.0, "5": 0.0},
        getprop_map={"sys.boot_completed": "0"},
    ) as sb:
        res2 = sb.run_script("service.sh", timeout=20.0)
        assert res2.returncode == 0, f"service.sh boot timeout should exit 0 safely: {res2}"
        total_slept = sum(sb.read_sleep_durations())
        assert total_slept >= 300.0, (
            f"service.sh boot-completed wait MUST be >= 300s (CB-18), got {total_slept}s"
        )
        assert not (sb.state_dir / ".boot_fail_count").exists(), (
            "Boot timeout must NOT arm .boot_fail_count"
        )
        assert sb.read_stub_log() == [], "Boot timeout must NOT launch silod"


@e2e_test(
    "T2-F12-04",
    tier=2,
    features=["F12"],
    description="module/customize.sh aborts cleanly on x86_64, armeabi-v7a, empty ABI without ARCH, and missing bin/silod",
)
def test_customize_sh_rejection_boundaries() -> None:
    cases = [
        ({"ro.product.cpu.abi": "x86_64", "ro.product.cpu.abilist64": ""}, "x64", False),
        ({"ro.product.cpu.abi": "armeabi-v7a", "ro.product.cpu.abilist64": ""}, "arm", False),
        ({"ro.product.cpu.abi": "", "ro.product.cpu.abilist64": ""}, None, False),
        ({"ro.product.cpu.abi": "arm64-v8a"}, "arm64", True),
    ]
    for gp_map, arch_val, remove_bin in cases:
        with module_harness.ModuleSandbox(silod_mode="native_stub", getprop_map=gp_map) as sb:
            if remove_bin and sb.update_silod_path.exists():
                sb.update_silod_path.unlink()
            res = sb.run_customize(arch=arch_val)
            assert res.returncode != 0, (
                f"customize.sh MUST abort for case gp={gp_map}, arch={arch_val}, remove_bin={remove_bin}; "
                f"got rc=0 stdout={res.stdout!r}"
            )


@e2e_test(
    "T2-F12-05",
    tier=2,
    features=["F12"],
    description="Portable MODDIR resolution when $0 has no '/' (CB-28) AND graceful missing bin/silod behavior",
)
def test_portable_moddir_and_missing_silod_boundary() -> None:
    with module_harness.ModuleSandbox(silod_mode="native_stub") as sb:
        # Execute `sh action.sh` and `sh service.sh` from cwd=MODDIR with bare $0 (no slash!)
        r_act = sb.run_script("action.sh", bare_arg0=True, timeout=15.0)
        assert r_act.returncode == 0 and "silod 3.0.0" in r_act.stdout, (
            f"action.sh failed with bare $0 (portable MODDIR bug CB-28): {r_act}"
        )

        r_svc = sb.run_script("service.sh", bare_arg0=True, timeout=15.0)
        assert r_svc.returncode == 0, f"service.sh failed with bare $0 (portable MODDIR bug CB-28): {r_svc}"
        assert (sb.state_dir / "silod.pid").is_file()

    # Missing bin/silod behavior
    with module_harness.ModuleSandbox(silod_mode="none") as sb2:
        r_act2 = sb2.run_script("action.sh", timeout=15.0)
        assert r_act2.returncode == 1 and "[ERROR]" in (r_act2.stdout + r_act2.stderr), (
            f"action.sh must exit 1 with [ERROR] when bin/silod is missing: {r_act2}"
        )
        r_svc2 = sb2.run_script("service.sh", timeout=15.0)
        assert r_svc2.returncode == 0
        r_un2 = sb2.run_script("uninstall.sh", timeout=15.0)
        assert r_un2.returncode == 0


@e2e_test(
    "T2-F12-06",
    tier=2,
    features=["F12"],
    description="scripts/build_module.sh rejects all 8 invalid binary inputs (missing, empty, script, /bin/true, x86_64, arm32, arm64_dynamic with PT_DYNAMIC, ehdr_only)",
)
def test_build_module_sh_invalid_binary_rejection_matrix() -> None:
    with tempfile.TemporaryDirectory(prefix="silo_pkg_neg_") as td:
        td_p = Path(td)
        bad_inputs: list[tuple[str, Path]] = [
            ("nonexistent", td_p / "does_not_exist"),
            ("empty", packaging_harness.write_elf_fixture(td_p / "empty", "empty")),
            ("shell_script", packaging_harness.write_elf_fixture(td_p / "script.sh", "shell_script")),
            ("host_true", Path("/bin/true")),
            ("x86_64_static", packaging_harness.write_elf_fixture(td_p / "x86_64_static", "x86_64_static")),
            ("arm32_static", packaging_harness.write_elf_fixture(td_p / "arm32_static", "arm32_static")),
            ("arm64_dynamic", packaging_harness.write_elf_fixture(td_p / "arm64_dynamic", "arm64_dynamic")),
            ("arm64_ehdr_only", packaging_harness.write_elf_fixture(td_p / "arm64_ehdr_only", "arm64_ehdr_only")),
        ]
        for label, bin_p in bad_inputs:
            out_zip = td_p / f"out_{label}.zip"
            res = packaging_harness.run_build_module(bin_p, out_zip)
            assert res.returncode != 0, (
                f"build_module.sh MUST reject invalid binary fixture {label!r} ({bin_p}), but exited 0!\n"
                f"STDOUT: {res.stdout}\nSTDERR: {res.stderr}"
            )
            assert not out_zip.exists(), f"Rejected input {label!r} must NOT create output ZIP {out_zip}"


@e2e_test(
    "T2-F12-07",
    tier=2,
    features=["F12"],
    description="scripts/build_module.sh enforces 5-root zero-mount (system/vendor/product/system_ext/odm), omits stray files, overwrites existing ZIP, and handles spaces in path (CB-28)",
)
def test_build_module_sh_zero_mount_stray_files_and_path_spaces() -> None:
    script_src = (REPO_ROOT / "scripts" / "build_module.sh").read_text(encoding="utf-8")
    for root in packaging_harness.FORBIDDEN_MOUNT_ROOTS:
        assert root in script_src, (
            f"scripts/build_module.sh zero-mount guard MUST check partition root {root!r} (CB-28)"
        )

    with tempfile.TemporaryDirectory(prefix="silo_pkg_overlay_") as td:
        td_p = Path(td)
        elf = packaging_harness.write_elf_fixture(td_p / "silod_arm64", "arm64_static")

        # (a) Plant each forbidden overlay directory in an isolated workspace copy
        for root in packaging_harness.FORBIDDEN_MOUNT_ROOTS:
            ws_dir = td_p / f"ws_{root}"
            ws, script_copy = packaging_harness.create_temp_packaging_workspace(ws_dir)
            overlay_file = ws / "module" / root / "etc" / "evil.conf"
            overlay_file.parent.mkdir(parents=True, exist_ok=True)
            overlay_file.write_text("forbidden\n", encoding="utf-8")

            out_zip = ws_dir / "out.zip"
            res = packaging_harness.run_build_module(elf, out_zip, script_path=script_copy)
            if res.returncode == 0:
                audit = packaging_harness.inspect_module_zip(out_zip)
                assert not audit.forbidden_mount_entries, (
                    f"build_module.sh packaged forbidden overlay {root}/ into ZIP (CB-28): {audit.forbidden_mount_entries}"
                )

        # (b) Plant stray untracked file -> explicit copy must exclude it!
        ws_stray, script_stray = packaging_harness.create_temp_packaging_workspace(td_p / "ws_stray")
        (ws_stray / "module" / "untracked_junk.txt").write_text("junk\n", encoding="utf-8")
        out_stray = td_p / "stray_out.zip"
        res_s = packaging_harness.run_build_module(elf, out_stray, script_path=script_stray)
        assert res_s.returncode == 0, f"build_module.sh failed with stray file: {res_s}"
        audit_s = packaging_harness.inspect_module_zip(out_stray)
        assert "untracked_junk.txt" not in audit_s.entries, (
            f"build_module.sh must copy explicit allowlisted module files only (CB-28), got: {sorted(audit_s.entries)}"
        )

        # (c) Overwrite existing ZIP & output path containing spaces
        spaced_zip = td_p / "dir with spaces" / "silo mod.zip"
        spaced_zip.parent.mkdir(parents=True, exist_ok=True)
        spaced_zip.write_bytes(b"stale-zip-placeholder")
        res_sp = packaging_harness.run_build_module(elf, spaced_zip)
        assert res_sp.returncode == 0, f"build_module.sh failed on spaced output path: {res_sp}"
        packaging_harness.assert_valid_module_zip(spaced_zip, expected_silod_bin=elf)


@e2e_test(
    "T2-F12-08",
    tier=2,
    features=["F12"],
    description="CI YAML verifier catches synthetic tampered workflows (top-level contents:write, unescaped github.event.inputs in run:, missing odm) and validates real workflow",
)
def test_ci_yaml_verifier_tamper_detection_and_real_workflow() -> None:
    real_text = ci_harness.WORKFLOW_PATH.read_text(encoding="utf-8")
    real_doc = ci_harness.parse_workflow_yaml(real_text)
    real_violations = ci_harness.audit_workflow_security(real_doc)
    assert not real_violations, f"Real workflow failed CI security audit: {real_violations}"

    # Synthetic tamper 1: top-level `contents: write`
    tamper_perm = (
        "name: T\n"
        "permissions:\n"
        "  contents: write\n"
        "jobs:\n"
        "  daemon-and-module:\n"
        "    steps:\n"
        "      - run: echo ok\n"
        "  android-app:\n"
        "    steps:\n"
        "      - run: echo ok\n"
        "  release:\n"
        "    needs: [daemon-and-module, android-app]\n"
        "    permissions:\n"
        "      contents: write\n"
        "    steps:\n"
        "      - run: echo ok\n"
    )
    v1 = ci_harness.audit_workflow_security(ci_harness.parse_workflow_yaml(tamper_perm))
    assert any("Top-level workflow permission contents: write" in v for v in v1), (
        f"Parser failed to catch top-level contents: write tamper! violations={v1}"
    )

    # Synthetic tamper 2: unescaped `${{ github.event.inputs.release_tag }}` inside `run:`
    tamper_inj = (
        "name: T\n"
        "permissions:\n"
        "  contents: read\n"
        "jobs:\n"
        "  daemon-and-module:\n"
        "    steps:\n"
        "      - run: echo ok\n"
        "  android-app:\n"
        "    steps:\n"
        "      - run: echo ok\n"
        "  release:\n"
        "    needs: [daemon-and-module, android-app]\n"
        "    permissions:\n"
        "      contents: write\n"
        "    steps:\n"
        "      - name: BadStep\n"
        "        run: |\n"
        '          TAG="${{ github.event.inputs.release_tag }}"\n'
    )
    v2 = ci_harness.audit_workflow_security(ci_harness.parse_workflow_yaml(tamper_inj))
    assert any("github.event.inputs" in v for v in v2), (
        f"Parser failed to catch unescaped github.event.inputs injection in run:! violations={v2}"
    )

    # Synthetic tamper 3: missing `odm` in zero-mount regex
    tamper_odm = (
        "name: T\n"
        "permissions:\n"
        "  contents: read\n"
        "jobs:\n"
        "  daemon-and-module:\n"
        "    steps:\n"
        "      - run: |\n"
        "          cargo clippy --all-targets -- -D warnings\n"
        "          cargo test --verbose\n"
        "          sh -n module/service.sh\n"
        "          ./scripts/build_module.sh\n"
        "          grep -qE '(system|vendor|product|system_ext)/' list.txt\n"
        "  android-app:\n"
        "    steps:\n"
        "      - run: echo ok\n"
        "  release:\n"
        "    needs: [daemon-and-module, android-app]\n"
        "    permissions:\n"
        "      contents: write\n"
        "    steps:\n"
        "      - run: echo module.prop\n"
    )
    v3 = ci_harness.audit_workflow_security(ci_harness.parse_workflow_yaml(tamper_odm))
    assert any("odm" in v for v in v3), (
        f"Parser failed to catch missing 'odm' in zero-mount regex! violations={v3}"
    )


@e2e_test(
    "T2-F12-09",
    tier=2,
    features=["F12"],
    description="silod --uninstall-cleanup and --emergency-flush handle missing config, corrupt config, and skip injected protected packages safely",
)
def test_silod_cli_cleanup_defensive_boundaries() -> None:
    with SiloDaemonSandbox(auto_start=False, use_unshare=True) as sb:
        # 1) Nonexistent --config path
        r_missing = sb.run_cli(
            ["--uninstall-cleanup", "--config", str(sb.state_dir / "no_such_config.json")],
            use_unshare=True,
        )
        assert r_missing.returncode == 0, f"--uninstall-cleanup failed on missing config: {r_missing}"

        # 2) Corrupt --config path
        bad_cfg = sb.state_dir / "corrupt.json"
        bad_cfg.write_text("{broken json!!!", encoding="utf-8")
        r_corrupt = sb.run_cli(["--emergency-flush", "--config", str(bad_cfg)], use_unshare=True)
        assert r_corrupt.returncode == 0, f"--emergency-flush crashed on corrupt config: {r_corrupt}"

        # 3) Protected package manually injected into config.json must be skipped by --uninstall-cleanup
        inj_cfg = sb.state_dir / "prot_injected.json"
        inj_cfg.write_text(
            json.dumps(
                {
                    "schema_version": 3,
                    "scopes": {
                        "guards": {
                            "dev.silo.app": {"auto_revoke_on_bg": [], "locked_deny": ["camera"]},
                            "com.android.systemui": {"auto_revoke_on_bg": [], "locked_deny": ["camera"]},
                        }
                    },
                }
            ),
            encoding="utf-8",
        )
        log_before = len(sb.read_cmd_log())
        r_prot = sb.run_cli(["--uninstall-cleanup", "--config", str(inj_cfg)], use_unshare=True)
        assert r_prot.returncode == 0, f"--uninstall-cleanup failed on protected config: {r_prot}"
        new_logs = sb.read_cmd_log()[log_before:]
        touched_prot = [
            e
            for e in new_logs
            if any(p in e["argv"] for p in ("dev.silo.app", "com.android.systemui"))
        ]
        assert not touched_prot, (
            f"--uninstall-cleanup MUST skip protected packages even if manually present in config.json: {touched_prot}"
        )
