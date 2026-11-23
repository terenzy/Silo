"""Tier 1 (F5) — Kernel Firewall, Panic Lockdown & IP Blocklist happy-path E2E tests."""

from __future__ import annotations

import re
import tempfile
from pathlib import Path

from tests.e2e.harness import android_harness
from tests.e2e.harness.registry import e2e_test
from tests.e2e.harness.silod_harness import SiloDaemonSandbox


@e2e_test(
    "T1-F5-01",
    tier=1,
    features=["F5"],
    description="set_firewall_config commits IPv4+IPv6 iptables-restore scripts with SILO_OUT/SILO_IN/LAN/DNS/SCAN/mangle TTL+HL chains",
)
def test_firewall_config_generates_complete_v4_v6_chains() -> None:
    with SiloDaemonSandbox(use_unshare=True) as sb:
        fw = sb.rpc_ok("get_config")["firewall"]
        fw.update(
            {
                "enabled": True,
                "mode": "default_allow",
                "isolate_lan": True,
                "dns_leak_guard": True,
                "anti_port_scan": True,
                "normalize_ttl_hl": True,
                "ttl_value": 64,
            }
        )
        sb.rpc_ok("set_firewall_config", {"config": fw})

        v4 = sb.last_iptables_script(v6=False)
        v6 = sb.last_iptables_script(v6=True)
        for token in (":SILO_OUT", ":SILO_IN", "SILO_LAN_CHK", "SILO_DENY_DNS", "SILO_DENY_SCAN", "*mangle", "SILO_MANGLE_POST", "--ttl-set 64"):
            assert token in v4, f"Missing expected IPv4 firewall token {token!r} in:\n{v4}"
        for token in (":SILO_OUT", ":SILO_IN", "*mangle", "SILO_MANGLE_POST", "--hl-set 64"):
            assert token in v6, f"Missing expected IPv6 firewall token {token!r} in:\n{v6}"

        st = sb.rpc_ok("get_status")
        assert st["firewall_active"] is True
        assert st["firewall_mode"] == "DefaultAllow"


@e2e_test(
    "T1-F5-02",
    tier=1,
    features=["F5"],
    description="set_app_firewall_rule emits modern Android/Samsung interfaces (xfrm+, ap+, v4-pdp+, wwan+, seth_lte+, eth+/rndis+/ncm+) and persists string UID key",
)
def test_per_app_firewall_rule_interfaces_and_persistence() -> None:
    with SiloDaemonSandbox(use_unshare=True) as sb:
        fw = sb.rpc_ok("get_config")["firewall"]
        fw["enabled"] = True
        fw["mode"] = "default_allow"
        sb.rpc_ok("set_firewall_config", {"config": fw})

        rule = {
            "package_name": "com.example.netapp",
            "allow_wifi": False,
            "allow_cellular": False,
            "allow_vpn": False,
            "allow_lan": False,
            "allow_screen_off": True,
            "block_cleartext_http": True,
            "block_localhost": True,
            "is_vpn_app": False,
        }
        sb.rpc_ok("set_app_firewall_rule", {"uid": 10277, "rule": rule})

        v4 = sb.last_iptables_script(v6=False)
        assert "--uid-owner 10277" in v4, f"Expected per-UID rule for 10277 in:\n{v4}"
        for iface in ("xfrm+", "ap+", "v4-pdp+", "wwan+", "seth_lte+"):
            assert iface in v4, f"Missing required modern/Samsung interface {iface!r} (D-09) in:\n{v4}"
        assert any(tok in v4 for tok in ("eth+", "rndis+", "ncm+")), (
            f"Expected wired/tether interfaces (eth+/rndis+/ncm+) in:\n{v4}"
        )

        cfg = sb.rpc_ok("get_config")
        assert "10277" in cfg["firewall"]["rules"], f"Expected string key '10277' in rules: {cfg['firewall']['rules']}"


@e2e_test(
    "T1-F5-03",
    tier=1,
    features=["F5"],
    description="set_temp_unblock inserts RETURN bypass in SILO_OUT and reports remaining seconds; clear_temp_unblock removes it",
)
def test_temp_unblock_add_and_clear() -> None:
    with SiloDaemonSandbox(use_unshare=True) as sb:
        fw = sb.rpc_ok("get_config")["firewall"]
        fw["enabled"] = True
        fw["mode"] = "default_deny"
        sb.rpc_ok("set_firewall_config", {"config": fw})

        res = sb.rpc_ok("set_temp_unblock", {"uid": 10277, "duration_minutes": 15})
        assert res["uid"] == 10277 and res["remaining_secs"] > 0

        v4 = sb.last_iptables_script(v6=False)
        assert re.search(r"-A SILO_OUT\s+-m owner --uid-owner 10277\s+-j RETURN", v4), (
            f"Expected SILO_OUT RETURN bypass for temp-unblocked UID 10277 in:\n{v4}"
        )
        st = sb.rpc_ok("get_status")
        assert int(st["temp_unblocks_remaining"].get("10277", 0)) > 0

        sb.rpc_ok("clear_temp_unblock", {"uid": 10277})
        v4_after = sb.last_iptables_script(v6=False)
        assert not re.search(r"-A SILO_OUT\s+-m owner --uid-owner 10277\s+-j RETURN", v4_after), (
            f"Temp-unblock RETURN rule must be removed after clear_temp_unblock:\n{v4_after}"
        )


@e2e_test(
    "T1-F5-04",
    tier=1,
    features=["F5"],
    description="replace_ip_blocklist canonicalizes IPv4/IPv6 CIDRs and places SILO_IN blocklist BEFORE ESTABLISHED,RELATED",
)
def test_ip_blocklist_canonicalization_and_inbound_ordering() -> None:
    with SiloDaemonSandbox(use_unshare=True) as sb:
        fw = sb.rpc_ok("get_config")["firewall"]
        fw["enabled"] = True
        sb.rpc_ok("set_firewall_config", {"config": fw})

        res = sb.rpc_ok(
            "replace_ip_blocklist",
            {"entries": ["198.51.100.42/24", "203.0.113.7/32", "2001:db8::1/128", "2001:db8:abcd::5/48"]},
        )
        assert res["loaded"] == 4 and res["rejected"] == 0, f"Unexpected blocklist result: {res}"

        cfg = sb.rpc_ok("get_config")
        bl = cfg["firewall"]["ip_blocklist"]
        assert "198.51.100.0/24" in bl and "203.0.113.7" in bl, f"Expected canonicalized IPv4 entries: {bl}"
        assert "2001:db8::1" in bl, f"Expected canonicalized IPv6 host entry: {bl}"

        v4 = sb.last_iptables_script(v6=False)
        # SILO_IN blocklist jump / match MUST appear BEFORE ESTABLISHED,RELATED RETURN (D-16)
        in_lines = [ln for ln in v4.splitlines() if ln.startswith("-A SILO_IN ")]
        est_idx = next((i for i, ln in enumerate(in_lines) if "ESTABLISHED,RELATED" in ln), -1)
        blk_idx = next(
            (i for i, ln in enumerate(in_lines) if ("SILO_BLK_IP_IN" in ln or "silo_blk_v4" in ln)),
            -1,
        )
        assert blk_idx != -1, f"Expected inbound IP blocklist rule in SILO_IN (D-16): {in_lines}"
        assert est_idx != -1 and blk_idx < est_idx, (
            f"SILO_IN IP blocklist (idx={blk_idx}) MUST precede ESTABLISHED,RELATED (idx={est_idx}) (D-16): {in_lines}"
        )


@e2e_test(
    "T1-F5-05",
    tier=1,
    features=["F5"],
    description="trigger_panic_lockdown sets panic_lockdown_active=true and blocks ALL non-protected UIDs despite prior DefaultAllow allow rules",
)
def test_panic_lockdown_blocks_all_user_uids_despite_allow_rules() -> None:
    with SiloDaemonSandbox(use_unshare=True) as sb:
        fw = sb.rpc_ok("get_config")["firewall"]
        fw["enabled"] = True
        fw["mode"] = "default_allow"
        sb.rpc_ok("set_firewall_config", {"config": fw})
        sb.rpc_ok(
            "set_app_firewall_rule",
            {
                "uid": 10277,
                "rule": {
                    "package_name": "com.example.allowed",
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

        rep = sb.rpc_ok("trigger_panic_lockdown")
        assert rep["firewall_locked"] is True

        st = sb.rpc_ok("get_status")
        assert st.get("panic_lockdown_active") is True, f"DaemonStatus.panic_lockdown_active must be true: {st}"
        cfg = sb.rpc_ok("get_config")
        assert cfg["firewall"].get("panic_lockdown_active") is True, (
            f"FirewallConfig.panic_lockdown_active must be true: {cfg['firewall']}"
        )

        v4 = sb.last_iptables_script(v6=False)
        # During panic lockdown, UID 10277 must NOT have a RETURN allow bypass before global user deny!
        assert not re.search(r"-A SILO_OUT\s+-m owner --uid-owner 10277\b.*-j RETURN", v4), (
            f"Panic lockdown MUST ignore prior DefaultAllow allow rules for UID 10277 (D-06):\n{v4}"
        )

        sb.rpc_ok("emergency_flush_firewall")
        st_after = sb.rpc_ok("get_status")
        assert st_after["firewall_active"] is False
        assert st_after.get("panic_lockdown_active") is False


@e2e_test(
    "T1-F5-06",
    tier=1,
    features=["F5"],
    description="Compiled Kotlin BlocklistParser.parseLines extracts IPv4, IPv6, CIDRs, comments, and multiple IPs per line cleanly",
)
def test_kotlin_blocklist_parser_happy_path() -> None:
    raw = (
        "# tracker feed\n"
        "198.51.100.1, 203.0.113.2 2001:db8::/48 ; inline comment\n"
        "198.51.100.1 # duplicate\n"
        "192.0.2.0/24\n"
        "2001:db8::42\n"
    )
    with tempfile.TemporaryDirectory(prefix="silo_bl_") as td:
        p = Path(td) / "list.txt"
        p.write_text(raw, encoding="utf-8")
        res = android_harness.run_jvm_contract(["blocklist_parser", str(p), "50000"])
        entries = res["entries"]
        expected = ["198.51.100.1", "203.0.113.2", "2001:db8::/48", "192.0.2.0/24", "2001:db8::42"]
        for tok in expected:
            assert tok in entries, f"BlocklistParser missed valid token {tok!r} (multi-IP line support APP-M07): got {entries}"
        assert len(entries) == len(expected), f"Expected deduplicated exact entries {expected}, got {entries}"
