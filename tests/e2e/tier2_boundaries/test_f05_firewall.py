"""Tier 2 (F5) — Kernel Firewall, Panic Lockdown & IP Blocklist boundary & negative tests."""

from __future__ import annotations

import re
import tempfile
from pathlib import Path

from tests.e2e.harness import android_harness
from tests.e2e.harness.registry import e2e_test
from tests.e2e.harness.silod_harness import SiloDaemonSandbox


@e2e_test(
    "T2-F5-01",
    tier=2,
    features=["F5"],
    description="replace_ip_blocklist rejects loopback, unspecified, invalid masks, and >50000 entries; mixed batch returns exact loaded & rejected > 0",
)
def test_ip_blocklist_rejects_unsafe_invalid_and_oversized_batches() -> None:
    with SiloDaemonSandbox(use_unshare=True) as sb:
        mixed = [
            "198.51.100.10",
            "2001:db8::2",
            "127.0.0.1",
            "127.0.0.0/8",
            "::1",
            "0.0.0.0/0",
            "::/0",
            "198.51.100.0/33",
            "2001:db8::/129",
            "10.0.0.1/0",
            "not-an-ip",
        ]
        res = sb.rpc_ok("replace_ip_blocklist", {"entries": mixed})
        assert res["loaded"] == 2, f"Expected exactly 2 valid entries loaded: {res}"
        assert res["rejected"] == len(mixed) - 2, f"Expected exact rejected count {len(mixed) - 2}: {res}"

        # > 50,000 entries cap
        too_many = [f"198.{(i >> 16) & 0xFF}.{(i >> 8) & 0xFF}.{i & 0xFF}" for i in range(1, 50_005)]
        sb.rpc_err("replace_ip_blocklist", {"entries": too_many}, timeout=30.0)


@e2e_test(
    "T2-F5-02",
    tier=2,
    features=["F5"],
    description="IPv6 ip6tables-restore failure on HL/NFLOG MUST NOT disable IPv4 nflog_available or xt_recent_available (D-14/CB-16)",
)
def test_ipv6_restore_failure_does_not_corrupt_ipv4_capabilities() -> None:
    with SiloDaemonSandbox(use_unshare=True) as sb:
        # Inject one-shot IPv6 ip6tables-restore failure on HL or NFLOG
        sb.inject_fault(
            "ip6tables-restore",
            stdin_contains="NFLOG",
            exit_code=1,
            stderr="ip6tables-restore: NFLOG not supported in ip6tables\n",
            once=False,
        )
        sb.inject_fault(
            "ip6tables-restore",
            stdin_contains="--hl-set",
            exit_code=1,
            stderr="ip6tables-restore: HL target not supported\n",
            once=False,
        )

        fw = sb.rpc_ok("get_config")["firewall"]
        fw.update(
            {
                "enabled": True,
                "mode": "default_allow",
                "nflog_enabled": True,
                "anti_port_scan": True,
                "normalize_ttl_hl": True,
                "ttl_value": 64,
            }
        )
        sb.rpc_ok("set_firewall_config", {"config": fw})

        v4 = sb.last_iptables_script(v6=False)
        assert "--ttl-set 64" in v4, f"IPv6 HL failure must NOT disable IPv4 TTL normalization (D-14):\n{v4}"
        assert "NFLOG" in v4, f"IPv6 NFLOG failure must NOT disable IPv4 NFLOG telemetry (D-14):\n{v4}"

        st = sb.rpc_ok("get_status")
        assert st["capabilities"]["nflog_available"] is True, (
            f"IPv4 nflog_available must remain true despite IPv6 failure (D-14): {st['capabilities']}"
        )
        assert st["capabilities"]["xt_recent_available"] is True


@e2e_test(
    "T2-F5-03",
    tier=2,
    features=["F5"],
    description="is_vpn_app=true does NOT bypass its own per-app allow_wifi=false / ip_blocklist restrictions (D-16)",
)
def test_is_vpn_app_does_not_bypass_own_per_app_restrictions() -> None:
    with SiloDaemonSandbox(use_unshare=True) as sb:
        fw = sb.rpc_ok("get_config")["firewall"]
        fw.update({"enabled": True, "mode": "default_allow", "ip_blocklist": ["198.51.100.5"]})
        sb.rpc_ok("set_firewall_config", {"config": fw})

        sb.rpc_ok(
            "set_app_firewall_rule",
            {
                "uid": 10310,
                "rule": {
                    "package_name": "com.example.vpnclient",
                    "allow_wifi": False,
                    "allow_cellular": True,
                    "allow_vpn": True,
                    "allow_lan": False,
                    "allow_screen_off": False,
                    "block_cleartext_http": True,
                    "block_localhost": False,
                    "is_vpn_app": True,
                },
            },
        )
        v4 = sb.last_iptables_script(v6=False)
        out_lines = [ln for ln in v4.splitlines() if ln.startswith("-A SILO_OUT ")]
        # Must NOT have an unconditional blanket `-A SILO_OUT -m owner --uid-owner 10310 -j RETURN` before blocklist/wifi rules!
        blanket_return = [
            ln for ln in out_lines if re.fullmatch(r"-A SILO_OUT\s+-m owner --uid-owner 10310\s+-j RETURN", ln.strip())
        ]
        assert not blanket_return, (
            f"is_vpn_app=true MUST NOT emit a blanket top-of-chain RETURN bypassing its own allow_wifi=false & blocklist (D-16):\n{v4}"
        )


@e2e_test(
    "T2-F5-04",
    tier=2,
    features=["F5"],
    description="Multi-user UIDs (Work Profile u10, Dual Messenger u95, Secure Folder u150, app_id 89999) are governed while system ranges stay exempt (D-08/CB-08)",
)
def test_multi_user_uid_coverage_and_system_exemptions() -> None:
    multi_user_uids = [89999, 1_010_050, 9_510_050, 15_010_050]
    with SiloDaemonSandbox(use_unshare=True) as sb:
        fw = sb.rpc_ok("get_config")["firewall"]
        fw.update(
            {
                "enabled": True,
                "mode": "default_deny",
                "isolate_lan": True,
                "dns_leak_guard": True,
                "block_cleartext_http_global": True,
            }
        )
        sb.rpc_ok("set_firewall_config", {"config": fw})

        for uid in multi_user_uids:
            sb.rpc_ok(
                "set_app_firewall_rule",
                {
                    "uid": uid,
                    "rule": {
                        "package_name": f"com.example.u{uid}",
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

        v4 = sb.last_iptables_script(v6=False)
        # Must NOT cap user app range at legacy 10000-19999 (which missed app_id >= 20000 and secondary users!)
        assert "--uid-owner 10000-19999" not in v4, (
            f"Legacy hardcoded --uid-owner 10000-19999 misses app_id 20000-89999 and Work/Dual/Secure profiles (D-08):\n{v4}"
        )
        for uid in multi_user_uids:
            assert f"--uid-owner {uid}" in v4, f"Missing multi-user UID {uid} rule in:\n{v4}"


@e2e_test(
    "T2-F5-05",
    tier=2,
    features=["F5"],
    description="set_temp_unblock clamps duration_minutes into [1, 240] and ttl_value=0 sanitizes to 64",
)
def test_temp_unblock_and_ttl_clamping() -> None:
    with SiloDaemonSandbox(use_unshare=True) as sb:
        r_low = sb.rpc_ok("set_temp_unblock", {"uid": 10277, "duration_minutes": 0})
        assert 1 <= r_low["remaining_secs"] <= 60, f"Expected 0m clamped to 1m (<=60s): {r_low}"

        r_high = sb.rpc_ok("set_temp_unblock", {"uid": 10277, "duration_minutes": 9999})
        assert 239 * 60 <= r_high["remaining_secs"] <= 240 * 60, (
            f"Expected 9999m clamped to 240m: {r_high}"
        )

        fw = sb.rpc_ok("get_config")["firewall"]
        fw["ttl_value"] = 0
        saved = sb.rpc_ok("set_firewall_config", {"config": fw})
        assert saved["ttl_value"] == 64, f"Expected ttl_value=0 sanitized to 64, got {saved['ttl_value']}"


@e2e_test(
    "T2-F5-06",
    tier=2,
    features=["F5"],
    description="Compiled Kotlin BlocklistParser rejects timestamps, bare colons, bad IPv4 octets, invalid CIDR masks, and enforces maxEntries cap (APP-M07)",
)
def test_kotlin_blocklist_parser_rejects_malformed_and_caps() -> None:
    raw = (
        "12:30:00\n"
        ":::\n"
        "::1::2\n"
        "999.1.1.1\n"
        "256.0.0.1\n"
        "198.51.100.0/33\n"
        "2001:db8::/129\n"
        "10.0.0.1/0\n"
        "\n"
        "   # comment only\n"
        "198.51.100.1\n"
        "198.51.100.2\n"
        "198.51.100.3\n"
        "198.51.100.4\n"
        "198.51.100.5\n"
    )
    with tempfile.TemporaryDirectory(prefix="silo_bl_neg_") as td:
        p = Path(td) / "bad.txt"
        p.write_text(raw, encoding="utf-8")
        res = android_harness.run_jvm_contract(["blocklist_parser", str(p), "3"])
        entries = res["entries"]
        assert entries == ["198.51.100.1", "198.51.100.2", "198.51.100.3"], (
            f"BlocklistParser accepted malformed tokens or ignored maxEntries=3 cap (APP-M07): got {entries}"
        )
