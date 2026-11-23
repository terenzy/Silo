"""Tier 2 (F8) — Kernel Hardening & Security Presets boundary, corner & negative tests."""

from __future__ import annotations

import json

from tests.e2e.harness.registry import e2e_test
from tests.e2e.harness.silod_harness import SiloDaemonSandbox


@e2e_test(
    "T2-F8-01",
    tier=2,
    features=["F8"],
    description="set_hardening_config rejects unsafe tcp_congestion and default_qdisc injection/oversized values",
)
def test_hardening_rejects_unsafe_tcp_cc_and_qdisc() -> None:
    with SiloDaemonSandbox() as sb:
        base = sb.rpc_ok("get_config")["hardening"]

        for bad_cc in ("bbr;id", "../evil", "a" * 30, "bbr\ncubic"):
            cfg = dict(base)
            cfg["tcp_congestion"] = bad_cc
            sb.rpc_err("set_hardening_config", {"config": cfg})

        for bad_qd in ("fq\ncake", "cake;id", "../qdisc", "q" * 30):
            cfg = dict(base)
            cfg["default_qdisc"] = bad_qd
            sb.rpc_err("set_hardening_config", {"config": cfg})


@e2e_test(
    "T2-F8-02",
    tier=2,
    features=["F8"],
    description="Stale sysctl_orig.json with mismatched boot_id is NOT loaded as current boot's original values (D-23)",
)
def test_stale_boot_id_sysctl_orig_ignored() -> None:
    with SiloDaemonSandbox() as sb:
        # 1. Let silod itself write a schema-valid sysctl_orig.json for the current boot
        hcfg = sb.rpc_ok("get_config")["hardening"]
        hcfg["enabled"] = True
        hs1 = sb.rpc_ok("set_hardening_config", {"config": hcfg})
        assert hs1["snapshot_saved"] is True
        assert sb.sysctl_orig_path.is_file(), (
            f"Expected silod to persist sysctl_orig.json at {sb.sysctl_orig_path} (D-23)"
        )

        # 2. Disable hardening cleanly without wiping the snapshot file, stop silod, and mutate ONLY boot_id
        real_snap = json.loads(sb.sysctl_orig_path.read_text(encoding="utf-8"))
        assert "boot_id" in real_snap, f"sysctl_orig.json missing boot_id key: {real_snap}"
        sb.stop()

        cfg_disk = json.loads(sb.config_path.read_text(encoding="utf-8"))
        cfg_disk["hardening"]["enabled"] = False
        sb.config_path.write_text(json.dumps(cfg_disk, indent=2), encoding="utf-8")

        real_snap["boot_id"] = "00000000-0000-0000-0000-000000000000"
        sb.sysctl_orig_path.write_text(json.dumps(real_snap, indent=2), encoding="utf-8")

        # 3. Restart silod — stale boot_id MUST be ignored (snapshot_saved == False)
        sb.start()
        hs2 = sb.rpc_ok("get_hardening_status")
        assert hs2["snapshot_saved"] is False, (
            f"Stale sysctl_orig.json with mismatched boot_id MUST be ignored on startup (D-23): {hs2}"
        )


@e2e_test(
    "T2-F8-03",
    tier=2,
    features=["F8"],
    description="Clearing tcp_congestion/default_qdisc back to empty string succeeds cleanly while preserving snapshot durability (D-23/CB-26)",
)
def test_clearing_tcp_cc_and_qdisc_restores_cleanly() -> None:
    with SiloDaemonSandbox() as sb:
        hcfg = sb.rpc_ok("get_config")["hardening"]
        hcfg["enabled"] = True
        hcfg["tcp_congestion"] = "cubic"
        hcfg["default_qdisc"] = "fq_codel"
        hs1 = sb.rpc_ok("set_hardening_config", {"config": hcfg})
        assert hs1["snapshot_saved"] is True

        # Clear tcp_congestion & default_qdisc back to "" (Default)
        hcfg["tcp_congestion"] = ""
        hcfg["default_qdisc"] = ""
        hs2 = sb.rpc_ok("set_hardening_config", {"config": hcfg})
        assert hs2["enabled"] is True and hs2["snapshot_saved"] is True

        cfg = sb.rpc_ok("get_config")
        assert cfg["hardening"]["tcp_congestion"] == ""
        assert cfg["hardening"]["default_qdisc"] == ""


@e2e_test(
    "T2-F8-04",
    tier=2,
    features=["F8"],
    description="rollback_hardening when hardening was never enabled returns ok=true safely with enabled=false",
)
def test_rollback_hardening_when_never_enabled() -> None:
    with SiloDaemonSandbox() as sb:
        rb = sb.rpc_ok("rollback_hardening")
        assert rb["enabled"] is False


@e2e_test(
    "T2-F8-05",
    tier=2,
    features=["F8"],
    description="apply_preset with invalid preset name returns ok=false and preserves existing config unchanged",
)
def test_apply_preset_rejects_unknown_name_preserving_config() -> None:
    with SiloDaemonSandbox(use_unshare=True) as sb:
        before = sb.rpc_ok("apply_preset", {"preset": "balanced"})
        sb.rpc_err("apply_preset", {"preset": "ultra_mega_invalid"})
        after = sb.rpc_ok("get_config")
        assert after["firewall"]["mode"] == before["firewall"]["mode"]
        assert after["hardening"]["enabled"] == before["hardening"]["enabled"]
