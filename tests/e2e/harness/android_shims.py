#!/usr/bin/env python3
"""Stateful flock-protected Android 15 / One UI 7 CLI responder & xtables-nft-multi shim.

This module acts BOTH as:
1. A library imported by `silod_harness.py` / `module_harness.py` to initialize,
   mutate, fault-inject, and inspect sandboxed Android CLI state (`state.json` +
   `cmd_log.jsonl`).
2. The executable shim dispatcher invoked directly by symlinked/wrapped commands
   in `<sandbox>/bin/{cmd,pm,am,dumpsys,settings,svc,getprop,xtables_shim}`.

CRITICAL RULE (verified empirically on Linux devbox):
In `xtables_shim` mode, stdin is ONLY read when `os.path.basename(sys.argv[0])`
ends with `-restore` (`iptables-restore`, `ip6tables-restore`), never on
`iptables`/`ip6tables` invocations such as `--version`, `--help`, `-C`, or `-S`.
"""

from __future__ import annotations

import fcntl
import json
import os
import re
import signal
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

ALL_SHIM_TOOLS: tuple[str, ...] = (
    "cmd",
    "pm",
    "am",
    "dumpsys",
    "settings",
    "svc",
    "getprop",
    "xtables_shim",
)


def default_android_state() -> dict[str, Any]:
    """Return a clean realistic Android 15 / One UI 7 sandbox state."""
    return {
        "props": {
            "sys.boot_completed": "1",
            "sys.user.0.ce_available": "true",
            "ro.product.cpu.abi": "arm64-v8a",
            "ro.product.cpu.abilist64": "arm64-v8a",
        },
        "settings_global": {
            "private_dns_mode": "opportunistic",
            "private_dns_specifier": "",
            "wifi_connected_mac_randomization_enabled": "1",
            "wifi_enhanced_mac_randomization": "0",
            "wifi_scan_always_enabled": "1",
            "ble_scan_always_enabled": "1",
            "captive_portal_mode": "1",
            "captive_portal_detection_enabled": "1",
            "adb_enabled": "0",
            "adb_wifi_enabled": "0",
            "wifi_on": "1",
            "bluetooth_on": "1",
        },
        "sensor_privacy": {
            "mic_muted": False,
            "camera_muted": False,
        },
        "dumpsys_sensor_privacy_raw": None,
        "clipboard": {
            "has_clip": True,
            "label_null": True,
            "timestamp": 1732280000000,
            "text": "secret-token-123",
        },
        "dumpsys_clipboard_raw": None,
        "power": {
            "wakefulness": "Awake",
            "display_state": "ON",
        },
        "trust": {
            "device_locked": False,
            "showing_lockscreen": False,
        },
        "bluetooth": {
            "connected_devices": "[]",
            "connection_state": "STATE_DISCONNECTED",
        },
        "ce_user_state": "RUNNING_UNLOCKED",
        # packages[pkg] = {
        #   "uid": int,
        #   "appops": { "<OP>": {"uid_mode": str|None, "pkg_mode": str|None, "time": str|None, "duration": str|None, "running": bool} },
        #   "runtime_perms": { "<PERM>": {"granted": bool, "flags": [str]} },
        #   "suspended_users": [int],
        #   "standby_bucket": str,
        # }
        "packages": {},
        # dumpsys_appops_raw[OP] = raw dumpsys text override (optional)
        "dumpsys_appops_raw": {},
        # iptables state: tables[table][chain] = [rule_args_str, ...]
        "ipt_v4": {"filter": {"INPUT": [], "OUTPUT": [], "FORWARD": []}, "mangle": {"POSTROUTING": []}},
        "ipt_v6": {"filter": {"INPUT": [], "OUTPUT": [], "FORWARD": []}, "mangle": {"POSTROUTING": []}},
        "xtables_caps": {
            "set": True,
            "recent": True,
            "nflog": True,
            "ttl": True,
            "hl": True,
        },
        # faults: list of dicts:
        #   {"tool": str, "argv_contains": [str], "stdin_contains": str|None,
        #    "exit_code": int, "stdout": str, "stderr": str, "once": bool,
        #    "sleep_secs": float, "spawn_grandchild_secs": float, "grandchild_pid_file": str|None}
        "faults": [],
    }


def install_shims(bin_dir: Path, state_dir: Path) -> None:
    """Create thin executable wrappers in `bin_dir` invoking this dispatcher."""
    bin_dir.mkdir(parents=True, exist_ok=True)
    state_dir.mkdir(parents=True, exist_ok=True)

    state_file = state_dir / "android_state.json"
    lock_file = state_dir / "android_state.lock"
    log_file = state_dir / "cmd_log.jsonl"

    if not state_file.exists():
        state_file.write_text(json.dumps(default_android_state(), indent=2), encoding="utf-8")
    if not log_file.exists():
        log_file.write_text("", encoding="utf-8")

    this_script = str(Path(__file__).resolve())
    python_bin = sys.executable or "/usr/bin/python3"

    for tool in ALL_SHIM_TOOLS:
        wrapper = bin_dir / tool
        # For xtables_shim, preserve $0 (e.g., /sbin/iptables-restore) via SILO_SHIM_ARGV0
        # because bind-mounting over /usr/sbin/xtables-nft-multi passes the symlink name as argv[0].
        if tool == "xtables_shim":
            content = (
                "#!/bin/sh\n"
                f'export SILO_SHIM_STATE_DIR="{state_dir}"\n'
                f'export SILO_SHIM_ARGV0="$0"\n'
                f'exec "{python_bin}" "{this_script}" "$@"\n'
            )
        else:
            content = (
                "#!/bin/sh\n"
                f'export SILO_SHIM_STATE_DIR="{state_dir}"\n'
                f'export SILO_SHIM_ARGV0="{tool}"\n'
                f'exec "{python_bin}" "{this_script}" "$@"\n'
            )
        wrapper.write_text(content, encoding="utf-8")
        wrapper.chmod(0o755)

    # Touch lock file with owner-only perms
    lock_file.touch(exist_ok=True)


def _with_state_lock(state_dir: Path, fn):
    lock_path = state_dir / "android_state.lock"
    state_path = state_dir / "android_state.json"
    with open(lock_path, "a+", encoding="utf-8") as lf:
        fcntl.flock(lf.fileno(), fcntl.LOCK_EX)
        try:
            if state_path.exists():
                state = json.loads(state_path.read_text(encoding="utf-8"))
            else:
                state = default_android_state()
            result, dirty = fn(state)
            if dirty:
                tmp = state_path.with_suffix(".json.tmp")
                tmp.write_text(json.dumps(state, indent=2), encoding="utf-8")
                os.replace(tmp, state_path)
            return result
        finally:
            fcntl.flock(lf.fileno(), fcntl.LOCK_UN)


def update_state(state_dir: Path, mutator) -> Any:
    """Thread/process-safe state mutation helper for test code."""
    def _runner(state: dict[str, Any]):
        res = mutator(state)
        return res, True
    return _with_state_lock(state_dir, _runner)


def read_state(state_dir: Path) -> dict[str, Any]:
    """Thread/process-safe state snapshot reader for test code."""
    return _with_state_lock(state_dir, lambda st: (st, False))


def _append_log(state_dir: Path, record: dict[str, Any]) -> None:
    lock_path = state_dir / "android_state.lock"
    log_path = state_dir / "cmd_log.jsonl"
    line = json.dumps(record, ensure_ascii=False) + "\n"
    with open(lock_path, "a+", encoding="utf-8") as lf:
        fcntl.flock(lf.fileno(), fcntl.LOCK_EX)
        try:
            with open(log_path, "a", encoding="utf-8") as out:
                out.write(line)
        finally:
            fcntl.flock(lf.fileno(), fcntl.LOCK_UN)


def read_cmd_log(state_dir: Path) -> list[dict[str, Any]]:
    """Read all logged shim invocations in chronological order."""
    log_path = state_dir / "cmd_log.jsonl"
    if not log_path.exists():
        return []
    entries: list[dict[str, Any]] = []
    for raw in log_path.read_text(encoding="utf-8").splitlines():
        raw = raw.strip()
        if not raw:
            continue
        entries.append(json.loads(raw))
    return entries


def _ensure_pkg(state: dict[str, Any], pkg: str) -> dict[str, Any]:
    pkgs = state.setdefault("packages", {})
    if pkg not in pkgs:
        pkgs[pkg] = {
            "uid": 10277,
            "appops": {},
            "runtime_perms": {},
            "suspended_users": [],
            "standby_bucket": "active",
        }
    return pkgs[pkg]


def _normalize_flag(flag: str) -> str:
    return flag.strip().upper().replace("-", "_")


def _check_fault(
    state: dict[str, Any],
    tool: str,
    args: list[str],
    stdin_text: str | None,
) -> tuple[dict[str, Any] | None, bool]:
    faults: list[dict[str, Any]] = state.get("faults", [])
    arg_str = " ".join(args)
    for idx, f in enumerate(faults):
        ftool = f.get("tool")
        if ftool and ftool != "*" and ftool != tool:
            continue
        need_args = f.get("argv_contains") or []
        if isinstance(need_args, str):
            need_args = [need_args]
        if any(needle not in arg_str and needle not in args for needle in need_args):
            continue
        need_stdin = f.get("stdin_contains")
        if need_stdin is not None:
            if stdin_text is None or need_stdin not in stdin_text:
                continue
        matched = dict(f)
        dirty = False
        if f.get("once"):
            faults.pop(idx)
            dirty = True
        return matched, dirty
    return None, False


def _handle_cmd(state: dict[str, Any], args: list[str]) -> tuple[int, str, str, bool]:
    if not args:
        return 1, "", "cmd: missing service\n", False

    service = args[0]
    rest = args[1:]

    if service == "appops":
        return _handle_appops(state, rest)

    if service == "package":
        return _handle_pm(state, rest)

    if service == "sensor_privacy":
        # cmd sensor_privacy <enable|disable> <user_id> <microphone|camera>
        if len(rest) >= 3:
            action = rest[0]
            sensor = rest[2]
            muted = action == "enable"
            sp = state.setdefault("sensor_privacy", {"mic_muted": False, "camera_muted": False})
            if sensor in ("microphone", "mic"):
                sp["mic_muted"] = muted
                return 0, "", "", True
            if sensor in ("camera", "cam"):
                sp["camera_muted"] = muted
                return 0, "", "", True
            return 1, "", f"Unknown sensor: {sensor}\n", False
        return 1, "", "Usage: cmd sensor_privacy <enable|disable> <user> <sensor>\n", False

    if service == "clipboard":
        clip = state.setdefault("clipboard", {})
        if rest and rest[0] in ("clear", "set-text"):
            clip["has_clip"] = False
            clip["text"] = ""
            clip["timestamp"] = int(time.time() * 1000)
            return 0, "", "", True
        return 0, "", "", False

    return 0, "", "", False


def _handle_appops(state: dict[str, Any], args: list[str]) -> tuple[int, str, str, bool]:
    if not args:
        return 1, "", "appops: missing subcommand\n", False
    sub = args[0]
    rest = args[1:]

    # Parse --user <id> and --uid flags
    user_id = 0
    is_uid = False
    pos: list[str] = []
    i = 0
    while i < len(rest):
        tok = rest[i]
        if tok == "--user" and i + 1 < len(rest):
            try:
                user_id = int(rest[i + 1])
            except ValueError:
                user_id = 0
            i += 2
            continue
        if tok == "--uid":
            is_uid = True
            i += 1
            continue
        pos.append(tok)
        i += 1
    _ = user_id

    if sub == "get":
        if not pos:
            return 1, "", "appops get: missing package\n", False
        pkg = pos[0]
        op_filter = pos[1] if len(pos) > 1 else None
        pinfo = state.get("packages", {}).get(pkg)
        if not pinfo:
            return 0, "No operations.\n", "", False
        appops: dict[str, dict[str, Any]] = pinfo.get("appops", {})
        lines: list[str] = []
        # Emit Uid mode lines first (matching One UI 7 / Android 15 ordering!)
        for op, entry in sorted(appops.items()):
            if op_filter and op != op_filter:
                continue
            uid_mode = entry.get("uid_mode")
            if uid_mode:
                lines.append(f"Uid mode: {op}: {uid_mode}")
        for op, entry in sorted(appops.items()):
            if op_filter and op != op_filter:
                continue
            pkg_mode = entry.get("pkg_mode")
            if pkg_mode is None and not entry.get("time") and not entry.get("running"):
                continue
            mode_str = pkg_mode or "allow"
            line = f"{op}: {mode_str}"
            if entry.get("time"):
                line += f"; time={entry['time']}"
            if entry.get("duration"):
                line += f"; duration={entry['duration']}"
            if entry.get("running"):
                line += " (running)"
            lines.append(line)
        if not lines:
            return 0, "No operations.\n", "", False
        return 0, "\n".join(lines) + "\n", "", False

    if sub == "set":
        if len(pos) < 3:
            return 1, "", "appops set: expected <pkg> <op> <mode>\n", False
        pkg, op, mode = pos[0], pos[1], pos[2]
        targets = [_ensure_pkg(state, pkg)]
        if is_uid and pkg.isdigit():
            uid_num = int(pkg)
            matched = [pinfo for pinfo in state.get("packages", {}).values() if pinfo.get("uid") == uid_num]
            if matched:
                targets = matched
        for pinfo in targets:
            entry = pinfo.setdefault("appops", {}).setdefault(op, {})
            if is_uid:
                entry["uid_mode"] = mode
            else:
                entry["pkg_mode"] = mode
        return 0, "", "", True

    if sub == "reset":
        if pos:
            pkg = pos[0]
            pinfo = _ensure_pkg(state, pkg)
            pinfo["appops"] = {}
            return 0, "", "", True
        return 0, "", "", False

    return 0, "", "", False


def _handle_pm(state: dict[str, Any], args: list[str]) -> tuple[int, str, str, bool]:
    if not args:
        return 1, "", "pm: missing subcommand\n", False
    sub = args[0]
    rest = args[1:]

    user_id = 0
    pos: list[str] = []
    i = 0
    while i < len(rest):
        if rest[i] == "--user" and i + 1 < len(rest):
            try:
                user_id = int(rest[i + 1])
            except ValueError:
                user_id = 0
            i += 2
            continue
        pos.append(rest[i])
        i += 1

    if sub == "revoke":
        if len(pos) < 2:
            return 1, "", "pm revoke: expected <pkg> <perm>\n", False
        pkg, perm = pos[0], pos[1]
        pinfo = _ensure_pkg(state, pkg)
        rperms = pinfo.setdefault("runtime_perms", {})
        pentry = rperms.setdefault(perm, {"granted": True, "flags": []})
        flags_upper = {_normalize_flag(f) for f in pentry.get("flags", [])}
        if "SYSTEM_FIXED" in flags_upper:
            return (
                1,
                "",
                f"java.lang.SecurityException: Permission {perm} for package {pkg} is SYSTEM_FIXED\n",
                False,
            )
        pentry["granted"] = False
        return 0, "", "", True

    if sub == "grant":
        if len(pos) < 2:
            return 1, "", "pm grant: expected <pkg> <perm>\n", False
        pkg, perm = pos[0], pos[1]
        pinfo = _ensure_pkg(state, pkg)
        rperms = pinfo.setdefault("runtime_perms", {})
        pentry = rperms.setdefault(perm, {"granted": False, "flags": []})
        pentry["granted"] = True
        return 0, "", "", True

    if sub == "set-permission-flags":
        if len(pos) < 3:
            return 1, "", "pm set-permission-flags: expected <pkg> <perm> <flags...>\n", False
        pkg, perm = pos[0], pos[1]
        new_flags = [_normalize_flag(f) for f in pos[2:]]
        pinfo = _ensure_pkg(state, pkg)
        rperms = pinfo.setdefault("runtime_perms", {})
        pentry = rperms.setdefault(perm, {"granted": False, "flags": []})
        cur = set(_normalize_flag(f) for f in pentry.get("flags", []))
        cur.update(new_flags)
        pentry["flags"] = sorted(cur)
        return 0, "", "", True

    if sub == "clear-permission-flags":
        if len(pos) < 3:
            return 1, "", "pm clear-permission-flags: expected <pkg> <perm> <flags...>\n", False
        pkg, perm = pos[0], pos[1]
        rm_flags = {_normalize_flag(f) for f in pos[2:]}
        pinfo = _ensure_pkg(state, pkg)
        rperms = pinfo.setdefault("runtime_perms", {})
        pentry = rperms.setdefault(perm, {"granted": False, "flags": []})
        cur = [f for f in (_normalize_flag(x) for x in pentry.get("flags", [])) if f not in rm_flags]
        pentry["flags"] = sorted(set(cur))
        return 0, "", "", True

    if sub == "suspend":
        if not pos:
            return 1, "", "pm suspend: missing package\n", False
        pkg = pos[0]
        pinfo = _ensure_pkg(state, pkg)
        susp = set(pinfo.get("suspended_users", []))
        susp.add(user_id)
        pinfo["suspended_users"] = sorted(susp)
        return 0, f"Package {pkg} new suspended state: true\n", "", True

    if sub == "unsuspend":
        if not pos:
            return 1, "", "pm unsuspend: missing package\n", False
        pkg = pos[0]
        pinfo = _ensure_pkg(state, pkg)
        susp = set(pinfo.get("suspended_users", []))
        susp.discard(user_id)
        pinfo["suspended_users"] = sorted(susp)
        return 0, f"Package {pkg} new suspended state: false\n", "", True

    if sub == "list" and pos and pos[0] == "packages":
        lines = [f"package:{pkg}" for pkg in sorted(state.get("packages", {}).keys())]
        return 0, ("\n".join(lines) + "\n") if lines else "", "", False

    return 0, "", "", False


def _handle_am(state: dict[str, Any], args: list[str]) -> tuple[int, str, str, bool]:
    if not args:
        return 1, "", "am: missing subcommand\n", False
    sub = args[0]
    rest = args[1:]
    if sub == "force-stop" and rest:
        return 0, "", "", False
    if sub == "set-standby-bucket":
        pos: list[str] = []
        i = 0
        while i < len(rest):
            if rest[i] == "--user" and i + 1 < len(rest):
                i += 2
                continue
            pos.append(rest[i])
            i += 1
        if len(pos) >= 2:
            pkg, bucket = pos[0], pos[1]
            pinfo = _ensure_pkg(state, pkg)
            pinfo["standby_bucket"] = bucket
            return 0, "", "", True
    return 0, "", "", False


def _format_dumpsys_package(pkg: str, pinfo: dict[str, Any]) -> str:
    uid = pinfo.get("uid", 10277)
    lines = [
        f"  Package [{pkg}] (deadbeef):",
        f"    userId={uid}",
        "    requested permissions:",
    ]
    rperms: dict[str, dict[str, Any]] = pinfo.get("runtime_perms", {})
    for perm in sorted(rperms.keys()):
        lines.append(f"      {perm}")
    lines.append("    runtime permissions:")
    for perm, pentry in sorted(rperms.items()):
        granted = "true" if pentry.get("granted") else "false"
        flags = "|".join(pentry.get("flags", []))
        lines.append(f"      {perm}: granted={granted}, flags=[ {flags} ]")
    susp_users = set(pinfo.get("suspended_users", []))
    users_to_emit = sorted(susp_users | {0, uid // 100_000})
    for u in users_to_emit:
        susp_str = "true" if u in susp_users else "false"
        lines.append(
            f"    User {u}: ceDataInode=12345 installed=true hidden=false suspended={susp_str} distractionFlags=0 stopped=false"
        )
    return "\n".join(lines) + "\n"


def _handle_dumpsys(state: dict[str, Any], args: list[str]) -> tuple[int, str, str, bool]:
    if not args:
        return 0, "", "", False
    svc = args[0]
    rest = args[1:]

    if svc == "appops":
        op = None
        for i, tok in enumerate(rest):
            if tok == "--op" and i + 1 < len(rest):
                op = rest[i + 1]
                break
        raw_map = state.get("dumpsys_appops_raw", {})
        if op and op in raw_map:
            return 0, raw_map[op], "", False
        # Synthesize realistic Android 15 dumpsys appops --op <OP> from state
        lines: list[str] = ["Current AppOps Service state:"]
        for pkg, pinfo in sorted(state.get("packages", {}).items()):
            appops = pinfo.get("appops", {})
            if op and op not in appops:
                continue
            uid = pinfo.get("uid", 10277)
            app_id = uid % 100_000
            user_id = uid // 100_000
            uid_label = f"u{user_id}a{app_id - 10000}" if app_id >= 10000 else str(uid)
            lines.append(f"  Uid {uid_label}:")
            lines.append(f"    Package {pkg}:")
            for op_name, entry in sorted(appops.items()):
                if op and op_name != op:
                    continue
                umode = entry.get("uid_mode") or "allow"
                pmode = entry.get("pkg_mode") or "allow"
                mode_paren = f"{umode}/{pmode}" if umode != pmode else umode
                lines.append(f"      {op_name} ({mode_paren}):")
                tstr = (entry.get("time") or "-2m15s100ms").strip()
                if tstr.endswith(" ago"):
                    tstr = tstr[:-4].strip()
                if tstr.startswith("+"):
                    tstr = "-" + tstr[1:]
                run_tag = " (running)" if entry.get("running") else ""
                lines.append(f"        Access: [top-s] {tstr} ago{run_tag}")
        return 0, "\n".join(lines) + "\n", "", False

    if svc == "package":
        if rest:
            pkg = rest[0]
            pinfo = state.get("packages", {}).get(pkg)
            if not pinfo:
                return 0, "", "", False
            return 0, _format_dumpsys_package(pkg, pinfo), "", False
        chunks = [
            _format_dumpsys_package(pkg, pinfo)
            for pkg, pinfo in sorted(state.get("packages", {}).items())
        ]
        return 0, "\n".join(chunks), "", False

    if svc == "sensor_privacy":
        raw = state.get("dumpsys_sensor_privacy_raw")
        if raw is not None:
            return 0, raw, "", False
        sp = state.get("sensor_privacy", {})
        mic_state = 1 if sp.get("mic_muted") else 2
        cam_state = 1 if sp.get("camera_muted") else 2
        out = (
            "users: {\n"
            "  user_id=0\n"
            "  toggles: {\n"
            "    sensor=1\n"
            "    toggle_type=1\n"
            f"    state_type={mic_state}\n"
            "  }\n"
            "  toggles: {\n"
            "    sensor=2\n"
            "    toggle_type=1\n"
            f"    state_type={cam_state}\n"
            "  }\n"
            "}\n"
        )
        return 0, out, "", False

    if svc == "clipboard":
        raw = state.get("dumpsys_clipboard_raw")
        if raw is not None:
            return 0, raw, "", False
        clip = state.get("clipboard", {})
        if not clip.get("has_clip"):
            return 0, "Primary clip: null\n", "", False
        label = "label=null" if clip.get("label_null", True) else 'label="clip"'
        ts = clip.get("timestamp", 1732280000000)
        out = (
            "Clipboard Service:\n"
            f"  Primary clip: ClipData {{ text/plain {label} }}\n"
            f"  ClipDescription {{ text/plain {label} }}\n"
            f"  mPrimaryClipTimestamp={ts}\n"
        )
        return 0, out, "", False

    if svc == "power":
        wake = state.get("power", {}).get("wakefulness", "Awake")
        return 0, f"POWER MANAGER (dumpsys power)\n  mWakefulness={wake}\n", "", False

    if svc == "display":
        dstate = state.get("power", {}).get("display_state", "ON")
        return 0, f"Display Power: state={dstate}\n  mState={dstate}\n", "", False

    if svc == "trust":
        locked = "1" if state.get("trust", {}).get("device_locked") else "0"
        return 0, f"User 0:\n  deviceLocked={locked}\n", "", False

    if svc == "window":
        showing = "true" if state.get("trust", {}).get("showing_lockscreen") else "false"
        return 0, f"  mShowingLockscreen={showing}\n", "", False

    if svc == "bluetooth_manager":
        bt = state.get("bluetooth", {})
        return (
            0,
            f"  Connected devices: {bt.get('connected_devices', '[]')}\n"
            f"  ConnectionState: {bt.get('connection_state', 'STATE_DISCONNECTED')}\n",
            "",
            False,
        )

    if svc == "user":
        ust = state.get("ce_user_state", "RUNNING_UNLOCKED")
        return (
            0,
            f"  UserInfo{{0:Owner:c13}} serialNo=0\n    State: {ust}\n  mUserStates:\n    0: {ust}\n",
            "",
            False,
        )

    return 0, "", "", False


def _handle_settings(state: dict[str, Any], args: list[str]) -> tuple[int, str, str, bool]:
    # settings get/put global <key> [value]
    if len(args) >= 3 and args[0] == "get" and args[1] == "global":
        val = state.get("settings_global", {}).get(args[2], "null")
        return 0, f"{val}\n", "", False
    if len(args) >= 4 and args[0] == "put" and args[1] == "global":
        state.setdefault("settings_global", {})[args[2]] = args[3]
        return 0, "", "", True
    return 0, "", "", False


def _handle_svc(state: dict[str, Any], args: list[str]) -> tuple[int, str, str, bool]:
    if len(args) >= 2 and args[0] == "wifi" and args[1] == "disable":
        state.setdefault("settings_global", {})["wifi_on"] = "0"
        return 0, "", "", True
    if len(args) >= 2 and args[0] == "bluetooth" and args[1] == "disable":
        state.setdefault("settings_global", {})["bluetooth_on"] = "0"
        return 0, "", "", True
    # svc power reboot / svc usb setFunctions ... safely recorded and exit 0!
    return 0, "", "", False


def _handle_getprop(state: dict[str, Any], args: list[str]) -> tuple[int, str, str, bool]:
    if not args:
        lines = [f"[{k}]: [{v}]" for k, v in sorted(state.get("props", {}).items())]
        return 0, "\n".join(lines) + "\n", "", False
    val = state.get("props", {}).get(args[0], "")
    return 0, f"{val}\n", "", False


def _apply_xtables_restore(
    state: dict[str, Any],
    state_dir: Path,
    is_v6: bool,
    script: str,
) -> tuple[int, str, str, bool]:
    fname = "last_ipt_v6.rules" if is_v6 else "last_ipt_v4.rules"
    (state_dir / fname).write_text(script, encoding="utf-8")

    key = "ipt_v6" if is_v6 else "ipt_v4"
    tables: dict[str, dict[str, list[str]]] = state.setdefault(key, {})
    cur_table: str | None = None
    for raw_line in script.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("*"):
            cur_table = line[1:].strip()
            tables.setdefault(cur_table, {})
            continue
        if line == "COMMIT":
            cur_table = None
            continue
        if cur_table is None:
            continue
        tbl = tables[cur_table]
        if line.startswith(":"):
            parts = line[1:].split()
            if parts:
                tbl.setdefault(parts[0], [])
            continue
        if line.startswith("-N "):
            ch = line[3:].strip()
            tbl.setdefault(ch, [])
            continue
        if line.startswith("-X "):
            ch = line[3:].strip()
            tbl.pop(ch, None)
            continue
        if line.startswith("-F "):
            ch = line[3:].strip()
            tbl[ch] = []
            continue
        if line.startswith("-D "):
            parts = line[3:].split(None, 1)
            if len(parts) == 2:
                ch, rule = parts[0], parts[1].strip()
                if ch in tbl and rule in tbl[ch]:
                    tbl[ch].remove(rule)
            continue
        if line.startswith("-A "):
            parts = line[3:].split(None, 1)
            if len(parts) == 2:
                ch, rule = parts[0], parts[1].strip()
                tbl.setdefault(ch, []).append(rule)
            continue
        if line.startswith("-I "):
            parts = line[3:].split(None, 1)
            if len(parts) == 2:
                ch, rule = parts[0], parts[1].strip()
                # Strip optional position number like "1 "
                m = re.match(r"^\d+\s+(.+)$", rule)
                if m:
                    rule = m.group(1).strip()
                tbl.setdefault(ch, []).insert(0, rule)
            continue
    return 0, "", "", True


def _handle_xtables(
    state: dict[str, Any],
    state_dir: Path,
    tool: str,
    args: list[str],
    stdin_text: str | None,
) -> tuple[int, str, str, bool]:
    is_v6 = tool.startswith("ip6tables")
    if tool.endswith("-restore"):
        return _apply_xtables_restore(state, state_dir, is_v6, stdin_text or "")

    caps = state.get("xtables_caps", {})
    if "--version" in args:
        return 0, f"{tool} v1.8.10 (nf_tables)\n", "", False
    if "--help" in args:
        if "set" in args:
            return (0, "--match-set setname flag\n", "", False) if caps.get("set", True) else (2, "", "no match set\n", False)
        if "recent" in args:
            return (0, "--rcheck --seconds --hitcount\n", "", False) if caps.get("recent", True) else (2, "", "no match recent\n", False)
        if "NFLOG" in args:
            return (0, "--nflog-group --nflog-prefix\n", "", False) if caps.get("nflog", True) else (2, "", "no target NFLOG\n", False)
        if "TTL" in args:
            return (0, "--ttl-set value\n", "", False) if caps.get("ttl", True) else (2, "", "no target TTL\n", False)
        if "HL" in args:
            return (0, "--hl-set value\n", "", False) if caps.get("hl", True) else (2, "", "no target HL\n", False)
        return 0, f"{tool} help\n", "", False

    # Parse -w, -t <table>, and action flag (-C, -I, -A, -D, -F, -X, -S, -N)
    table = "filter"
    cleaned: list[str] = []
    i = 0
    while i < len(args):
        tok = args[i]
        if tok == "-w" or tok.startswith("-w"):
            # Handle "-w" or "-w 5"
            if tok == "-w" and i + 1 < len(args) and args[i + 1].isdigit():
                i += 2
            else:
                i += 1
            continue
        if tok == "-t" and i + 1 < len(args):
            table = args[i + 1]
            i += 2
            continue
        cleaned.append(tok)
        i += 1

    if not cleaned:
        return 0, "", "", False

    key = "ipt_v6" if is_v6 else "ipt_v4"
    tbl = state.setdefault(key, {}).setdefault(table, {})
    op = cleaned[0]
    rest = cleaned[1:]

    if op == "-N" and rest:
        ch = rest[0]
        if ch in tbl:
            return 1, "", f"{tool}: Chain already exists.\n", False
        tbl[ch] = []
        return 0, "", "", True

    if op == "-F":
        if rest:
            ch = rest[0]
            if ch not in tbl:
                return 1, "", f"{tool}: No chain/target/match by that name.\n", False
            tbl[ch] = []
        else:
            for ch in list(tbl.keys()):
                tbl[ch] = []
        return 0, "", "", True

    if op == "-X" and rest:
        ch = rest[0]
        if ch not in tbl:
            return 1, "", f"{tool}: No chain/target/match by that name.\n", False
        del tbl[ch]
        return 0, "", "", True

    if op == "-S":
        if rest:
            ch = rest[0]
            if ch not in tbl:
                return 1, "", f"{tool}: No chain/target/match by that name.\n", False
            lines = [f"-N {ch}"] + [f"-A {ch} {r}" for r in tbl[ch]]
            return 0, "\n".join(lines) + "\n", "", False
        lines = []
        for ch, rules in tbl.items():
            lines.append(f"-N {ch}")
            for r in rules:
                lines.append(f"-A {ch} {r}")
        return 0, "\n".join(lines) + "\n", "", False

    if op in ("-C", "-D", "-I", "-A") and rest:
        ch = rest[0]
        rule_tokens = rest[1:]
        if op == "-I" and rule_tokens and rule_tokens[0].isdigit():
            rule_tokens = rule_tokens[1:]
        rule_str = " ".join(rule_tokens)
        if op == "-C":
            if ch in tbl and rule_str in tbl[ch]:
                return 0, "", "", False
            return 1, "", f"{tool}: Bad rule (does a matching rule exist in that chain?).\n", False
        if op == "-D":
            if ch in tbl and rule_str in tbl[ch]:
                tbl[ch].remove(rule_str)
                return 0, "", "", True
            return 1, "", f"{tool}: Bad rule (does a matching rule exist in that chain?).\n", False
        if op == "-I":
            tbl.setdefault(ch, []).insert(0, rule_str)
            return 0, "", "", True
        if op == "-A":
            tbl.setdefault(ch, []).append(rule_str)
            return 0, "", "", True

    return 0, "", "", False


def _main() -> int:
    state_dir_str = os.environ.get("SILO_SHIM_STATE_DIR")
    if not state_dir_str:
        sys.stderr.write("SILO_SHIM_STATE_DIR not set\n")
        return 2
    state_dir = Path(state_dir_str)
    argv0 = os.environ.get("SILO_SHIM_ARGV0") or sys.argv[0]
    tool = os.path.basename(argv0)
    args = sys.argv[1:]

    # CRITICAL: Only read stdin for iptables-restore / ip6tables-restore!
    stdin_text: str | None = None
    if tool.endswith("-restore"):
        stdin_text = sys.stdin.read()

    # Check fault injection under lock
    def _probe_fault(state: dict[str, Any]):
        return _check_fault(state, tool, args, stdin_text)

    fault = _with_state_lock(state_dir, _probe_fault)

    _append_log(
        state_dir,
        {
            "ts": time.time(),
            "tool": tool,
            "argv": args,
            "stdin": stdin_text,
            "fault_matched": bool(fault),
        },
    )

    if fault is not None:
        # Optional grandchild spawn for PGID timeout cleanup tests (T1-F11-06)
        gsecs = float(fault.get("spawn_grandchild_secs") or 0.0)
        if gsecs > 0:
            # Inherit stdout & stderr deliberately so parent read would hang if PGID isn't killed!
            proc = subprocess.Popen(
                ["/usr/bin/sleep", str(gsecs)],
                stdout=sys.stdout.fileno(),
                stderr=sys.stderr.fileno(),
                close_fds=False,
            )
            pid_file = fault.get("grandchild_pid_file")
            if pid_file:
                Path(pid_file).write_text(f"{proc.pid}\n", encoding="utf-8")
        sleep_secs = float(fault.get("sleep_secs") or 0.0)
        if sleep_secs > 0:
            time.sleep(sleep_secs)
        if fault.get("stdout"):
            sys.stdout.write(str(fault["stdout"]))
            sys.stdout.flush()
        if fault.get("stderr"):
            sys.stderr.write(str(fault["stderr"]))
            sys.stderr.flush()
        return int(fault.get("exit_code", 1))

    def _dispatch(state: dict[str, Any]):
        if tool == "cmd":
            rc, out, err, dirty = _handle_cmd(state, args)
        elif tool == "pm":
            rc, out, err, dirty = _handle_pm(state, args)
        elif tool == "am":
            rc, out, err, dirty = _handle_am(state, args)
        elif tool == "dumpsys":
            rc, out, err, dirty = _handle_dumpsys(state, args)
        elif tool == "settings":
            rc, out, err, dirty = _handle_settings(state, args)
        elif tool == "svc":
            rc, out, err, dirty = _handle_svc(state, args)
        elif tool == "getprop":
            rc, out, err, dirty = _handle_getprop(state, args)
        elif tool in ("iptables", "ip6tables", "iptables-restore", "ip6tables-restore", "xtables_shim", "xtables-nft-multi"):
            rc, out, err, dirty = _handle_xtables(state, state_dir, tool, args, stdin_text)
        else:
            rc, out, err, dirty = 0, "", "", False
        return (rc, out, err), dirty

    rc, out, err = _with_state_lock(state_dir, _dispatch)
    if out:
        sys.stdout.write(out)
        sys.stdout.flush()
    if err:
        sys.stderr.write(err)
        sys.stderr.flush()
    return rc


if __name__ == "__main__":
    signal.signal(signal.SIGPIPE, signal.SIG_DFL)
    sys.exit(_main())
