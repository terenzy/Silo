"""Tier 2 (F7) — Privacy Automation, Sensors, Clipboard, Radio & DNS boundary & negative tests."""

from __future__ import annotations

from tests.e2e.harness.registry import e2e_test
from tests.e2e.harness.silod_harness import SiloDaemonSandbox


@e2e_test(
    "T2-F7-01",
    tier=2,
    features=["F7"],
    description="set_private_dns rejects unsupported mode, blank hostname, shell injection, single-label localhost, and leading dash",
)
def test_private_dns_rejects_invalid_modes_and_hostnames() -> None:
    with SiloDaemonSandbox() as sb:
        bad_cases = [
            {"mode": "invalid_mode", "specifier": ""},
            {"mode": "hostname", "specifier": ""},
            {"mode": "hostname", "specifier": "   "},
            {"mode": "hostname", "specifier": "dns.quad9.net; rm -rf /"},
            {"mode": "hostname", "specifier": "localhost"},
            {"mode": "hostname", "specifier": "-bad.example.com"},
            {"mode": "hostname", "specifier": "bad-.example.com"},
        ]
        for params in bad_cases:
            sb.rpc_err("set_private_dns", params)


@e2e_test(
    "T2-F7-02",
    tier=2,
    features=["F7"],
    description="set_sensor_privacy rejects unknown sensor 'gps' AND 'all' fails when only 1 of 2 sensors succeeds (D-22/CB-20)",
)
def test_sensor_privacy_rejects_unknown_and_partial_all_failure() -> None:
    with SiloDaemonSandbox() as sb:
        sb.rpc_err("set_sensor_privacy", {"sensor": "gps", "enabled": True})

        # Inject failure ONLY on camera while microphone succeeds -> "all" MUST report error (CB-20)!
        sb.inject_fault(
            "cmd",
            argv_contains=["sensor_privacy", "camera"],
            exit_code=1,
            stderr="Error: camera sensor_privacy failed\n",
        )
        sb.rpc_err("set_sensor_privacy", {"sensor": "all", "enabled": True})


@e2e_test(
    "T2-F7-03",
    tier=2,
    features=["F7"],
    description="dumpsys sensor_privacy brace-tracking: trailing listeners { enabled=true } and user_id=10 muted MUST NOT falsely mark user 0 camera_muted (CB-20)",
)
def test_dumpsys_sensor_privacy_brace_tracking_ignores_listeners_and_other_users() -> None:
    tricky_dump = (
        "users: {\n"
        "  user_id=0\n"
        "  toggles: {\n"
        "    sensor=1\n"
        "    toggle_type=1\n"
        "    state_type=2\n"
        "  }\n"
        "  toggles: {\n"
        "    sensor=2\n"
        "    toggle_type=1\n"
        "    state_type=2\n"
        "  }\n"
        "  listeners: {\n"
        "    enabled=true\n"
        "  }\n"
        "}\n"
        "users: {\n"
        "  user_id=10\n"
        "  toggles: {\n"
        "    sensor=2\n"
        "    toggle_type=1\n"
        "    state_type=1\n"
        "  }\n"
        "}\n"
    )
    with SiloDaemonSandbox() as sb:
        sb.set_dumpsys_sensor_privacy_raw(tricky_dump)
        # Trigger live read via set_sensor_privacy(microphone, false) which returns parsed SensorPrivacyState
        st = sb.rpc_ok("set_sensor_privacy", {"sensor": "microphone", "enabled": False})
        assert st["mic_muted"] is False, f"Expected user 0 mic_muted=false: {st}"
        assert st["camera_muted"] is False, (
            f"Trailing listeners {{ enabled=true }} or user_id=10 MUST NOT falsely report user 0 camera_muted=true (CB-20): {st}"
        )


@e2e_test(
    "T2-F7-04",
    tier=2,
    features=["F7"],
    description="trigger_panic_reboot rejects bad confirm_token without calling svc; automation & clipboard timers clamp safely",
)
def test_panic_reboot_token_guard_and_timer_clamping() -> None:
    with SiloDaemonSandbox() as sb:
        for bad_tok in ("", "YES", "CONFIRM_REBOOT", "confirm_silo_bfu_reboot"):
            sb.rpc_err("trigger_panic_reboot", {"confirm_token": bad_tok})

        reboots = [e for e in sb.read_cmd_log() if e["tool"] == "svc" and e["argv"][:2] == ["power", "reboot"]]
        assert not reboots, f"Invalid confirm_token MUST NOT invoke svc power reboot: {reboots}"

        auto = sb.rpc_ok("get_config")["automation"]
        auto.update(
            {
                "auto_reboot_minutes": 1,
                "wifi_auto_off_minutes": 0,
                "bluetooth_auto_off_minutes": 99999,
            }
        )
        saved = sb.rpc_ok("set_automation_config", {"config": auto})
        assert saved["auto_reboot_minutes"] == 15
        assert saved["wifi_auto_off_minutes"] == 2
        assert saved["bluetooth_auto_off_minutes"] == 1440

        sc_low = sb.rpc_ok("set_auto_clear_clipboard", {"enabled": True, "clear_secs": 1})
        assert sc_low["clipboard_clear_secs"] == 10
        sc_high = sb.rpc_ok("set_auto_clear_clipboard", {"enabled": True, "clear_secs": 99999})
        assert sc_high["clipboard_clear_secs"] == 3600


@e2e_test(
    "T2-F7-05",
    tier=2,
    features=["F7"],
    description="is_ce_unlocked fails safe (false) when getprop sys.user.0.ce_available is empty/unavailable and dumpsys user is not unlocked (D-20)",
)
def test_ce_unlocked_fails_safe_when_props_unavailable() -> None:
    with SiloDaemonSandbox(auto_start=False) as sb:
        sb.set_prop("sys.user.0.ce_available", "")
        sb.update_android_state(lambda st: st.__setitem__("ce_user_state", "RUNNING_LOCKED"))
        sb.start()

        st = sb.rpc_ok("get_status")
        assert st["ce_unlocked"] is False, (
            f"is_ce_unlocked MUST fail safe (false) when CE status is unavailable/locked (D-20): {st}"
        )
