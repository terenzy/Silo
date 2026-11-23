# Handoff — `iter1_explorer_2` (Survivability, Bounded Supervisor & Installer Design)

**Milestone**: M4 (`module-scripts-ci`)  
**Repository**: `/home/antigravity/terenzy/Silo`  
**Scope**: `module/service.sh`, `module/customize.sh`, `module/action.sh`, `module/uninstall.sh`, `module/module.prop` (read-only cross-checked against `daemon/src/main.rs`, `daemon/src/ipc.rs`, `daemon/src/config.rs`, `PROJECT.md § F12 & Interface Contracts § 4`)  
**Findings Addressed**: `CB-07`, `D-13`, `CB-18`, `CB-19` (teardown coordination), `CB-28`  
**Runtime Targets**: Android 15 One UI 7 (`SM-A146P`, `/system/bin/sh` = `mksh`, Toybox + KernelSU-Next BusyBox `ash`) AND Linux devbox/CI (`/bin/sh` = `dash` verified on this host).

---

## 1. Line-by-Line Verified Audit Findings

### 1.1 `daemon/src/main.rs` & `daemon/src/ipc.rs` (Verified Daemon Contract)
- **Signal Handling & Exit Codes (`daemon/src/main.rs:58-76, 1018-1027, 1173-1185, 1308`)**:
  - `install_signal_handlers()` traps `SIGTERM` and `SIGINT` into `GLOBAL_STOP: AtomicBool` (`SIGPIPE` is `SIG_IGN`).
  - The signal-poller thread checks `GLOBAL_STOP` every `100ms` and sets `stop`. `run_ipc_server` exits its `accept` loop within ~`40ms` (`daemon/src/ipc.rs:115, 141`), unlinks `socket_path` (`ipc.rs:149`; M1 adds `flock` on `/data/adb/silo/silod.lock` + `(st_dev, st_ino)` verification per `PROJECT.md § 4`), returns `Ok(()) => exit_code = 0`, calls `st.freezer.thaw_all()` (`main.rs:1183`), and exits via `std::process::exit(0)` (`main.rs:1308`).
  - **Consequence for Supervisor Exit Classification**:
    - `rc == 0`: Clean shutdown (normal exit OR caught `SIGTERM`/`SIGINT` graceful shutdown) → **MUST STOP supervisor (do NOT restart)**.
    - `rc == 143` (`128 + 15`, `SIGTERM`) or `rc == 130` (`128 + 2`, `SIGINT`): Explicit termination arriving before `install_signal_handlers()` ran (or in stub/mock daemons during sandbox testing) → **MUST STOP supervisor (do NOT restart)**.
    - `rc == 137` (`128 + 9`, `SIGKILL` — **Android LMKD OOM kill**), `rc == 134` (`SIGABRT` — Rust `panic = "abort"`), `rc == 139` (`SIGSEGV`), `rc == 132` (`SIGILL`), `rc == 1` (IPC bind / lock / runtime error), or any other non-zero code → **Unexpected crash → eligible for bounded restart** (subject to disable/stop-flag/sliding-window checks).
- **Boot-Guard Marker Clearing (`daemon/src/main.rs:56, 121, 129-131, 1093-1100`)**:
  - `BOOT_STABILITY_SECS = 45`.
  - On startup, `boot_guard_armed = boot_fail_marker.exists()`, `started_boottime = boottime_secs()`.
  - Every `5s` in the supervisor thread, once `boottime_secs().saturating_sub(started_boottime) >= 45`, `silod` deletes `boot_fail_marker` (`/data/adb/silo/.boot_fail_count`).
  - If `silod` restarts hours later (e.g. after an LMKD `SIGKILL`), `.boot_fail_count` no longer exists, so `boot_guard_armed` is cleanly `false`.
  - If `silod` crash-loops immediately after boot (<45s uptime), `.boot_fail_count` is NEVER cleared by `silod`; once the supervisor hits its 5-restarts-per-60s cap and exits, the marker remains armed for the next boot's Dead-Man Switch.
- **IMPORTANT Pre-M1 CLI Flag Footgun (`daemon/src/main.rs:1195, 1227`)**:
  - Currently `main.rs:1195` defaults `mode = "daemon"` and `main.rs:1227` has `_ => {}` (silently ignoring unknown CLI flags!). If `uninstall.sh` runs `"$SILOD" --uninstall-cleanup` against a **pre-M1** binary, the old binary would ignore `--uninstall-cleanup` and start running as a foreground daemon forever. Once M1 lands `--uninstall-cleanup [--config PATH]` (per `PROJECT.md § 4`), `uninstall.sh` will execute cleanly; do NOT test `uninstall.sh` with `--uninstall-cleanup` against an unpatched pre-M1 `silod` binary without a timeout or M1's patch.
- **Emergency Flush Config Path (`daemon/src/main.rs:1234-1274`)**:
  - `--emergency-flush` reads `config_path` (set via `--config`) at `main.rs:1256` to `pm unsuspend` Deep-Stopped packages. Passing `--config "$CFG_PATH"` explicitly from `service.sh` (Dead-Man Switch) and `uninstall.sh` ensures it works both on Android and when `STATE_DIR` is overridden in sandbox tests.

### 1.2 `module/service.sh` (Lines 1–76)
| Lines | Current Code / Behavior | Verified Defect(s) |
|---|---|---|
| L12 | `MODDIR="${0%/*}"` | Fails when `$0` has no `/` (e.g. invoked via `PATH` or `sh service.sh`); leaves `MODDIR` relative (`CB-28`). |
| L13–L18 | Path variables (`SILOD`, `STATE_DIR`, `BOOT_MARKER`, `SOCK_PATH`, `CFG_PATH`, `PID_FILE`) | No `SUP_PID_FILE` (`/data/adb/silo/supervisor.pid`), no `STOP_FLAG` (`/data/adb/silo/.stop_supervisor`), no `umask 077` before file creation, no `[ -f "$MODDIR/disable" ]` early exit check. |
| L20–L28 | `while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; do sleep 2; ... -ge 180` | 180s timeout exits silently on slow post-OTA Android 15 ART dexopt first boots (`CB-18`). Does not strip `\r\n ` from `getprop` or check `$MODDIR/disable` while waiting. |
| L39–L43, L54 | `RAW_CNT="$(cat "$BOOT_MARKER" \| tr -cd '0-9')"` → `echo $((FAIL_COUNT + 1))` | **Octal arithmetic crash**: if `.boot_fail_count` contains leading zeros (e.g. `"08"`, `"09"`), POSIX `sh` (`dash`/`mksh`) aborts with `Illegal number: 08`! Also unbounded digit length risks 64-bit overflow. |
| L46–L51 | Dead-Man Switch (`FAIL_COUNT -ge 2`) | Calls `"$SILOD" --emergency-flush` without `--config "$CFG_PATH"`; does not stop any existing supervisor/silod or clean `SUP_PID_FILE`/`STOP_FLAG`. |
| L58–L66 | Blind `kill -TERM "$OLD_PID"` + `sleep 1` + `rm -f "$SOCK_PATH"` | **CRITICAL (`CB-07`/`D-13`)**: `/data/adb/silo/silod.pid` persists across reboot; `OLD_PID` belongs to an unrelated Android service → kills random process on boot! Only sleeps `1s` without waiting for exit → old `silod`'s `ipc.rs:149` `remove_file(socket_path)` deletes the NEW `silod`'s socket! |
| L68–L75 | Bare `"$SILOD" ... &` + `echo "$!" > "$PID_FILE"` | **HIGH (`CB-18`)**: Zero crash-restart supervision (if LMKD kills `silod`, firewall/enforcer stay dead until reboot); no `setsid`/`HUP` immunity; brief pre-`chmod 0600` window on `PID_FILE`. |

### 1.3 `module/customize.sh` (Lines 1–34)
| Lines | Current Code / Behavior | Verified Defect(s) |
|---|---|---|
| L5 | `SKIPUNZIP=0` | Correct — MUST preserve so KernelSU/Magisk extracts archive before sourcing `customize.sh`. |
| L7, L15, L20 | Calls `ui_print` and `abort` directly | Works when sourced by KernelSU/Magisk, but crashes/doesn't abort if executed standalone in sandbox tests (`ui_print: not found`, `abort: not found`). |
| L9–L17 | `ARCH="$(getprop ro.product.cpu.abi)"` → `case "$ARCH" in arm64-v8a*)` | **BUG (`CB-28`)**: (1) Overwrites KernelSU/Magisk's pre-set `$ARCH` variable (polluting the caller's sourced shell environment!). (2) Fails falsely when `ro.product.cpu.abi` is empty/unusual even though `ro.product.cpu.abilist64` contains `arm64-v8a` or KernelSU set `$ARCH=arm64`. |
| L19–L21 | `if [ ! -f "$MODPATH/bin/silod" ]` | Fails in standalone run if `MODPATH` is unset. |
| L23–L24 | `chmod 0755 "$MODPATH/bin/silod"` & scripts | Good baseline; when KernelSU/Magisk `set_perm` is present, calling `set_perm` after `chmod 0755` also ensures `0:0` ownership & SELinux context. |
| L26–L29 | `mkdir -p /data/adb/silo`, `chmod 0700`, `rm -f /data/adb/silo/.boot_fail_count` | **HIGH (`CB-18`)**: Does NOT remove `/data/adb/modules/silo/disable` or `$MODPATH/disable`! During KSU/Magisk upgrade, `MODPATH` is `/data/adb/modules_update/silo`, and KSU preserves `/data/adb/modules/silo/disable` across updates — leaving a Dead-Man-disabled module permanently disabled even after flashing a fix! Also should remove stale `.stop_supervisor`. |

### 1.4 `module/action.sh` (Lines 1–46)
| Lines | Current Code / Behavior | Verified Defect(s) |
|---|---|---|
| L5 | `MODDIR="${0%/*}"` | Non-portable (`CB-28`). |
| L28–L38 | Recovery branch when `--status` fails | **HIGH (`CB-07`/`D-13`/`CB-18`)**: (1) Never removes `$MODDIR/disable`! (2) Never stops existing supervisor or hung `silod` before removing `$SOCK_PATH` and spawning a second `silod`! (3) Starts bare `silod` directly without the bounded supervisor (recovered daemon loses crash/LMKD resilience). |
| L39–L44 | Fixed `sleep 2` + single `--status` probe | Wastes 1.8s when startup is fast, false-warns if slow device takes 3–4s; should poll up to 5s breaking immediately on first success. |

### 1.5 `module/uninstall.sh` (Lines 1–27)
| Lines | Current Code / Behavior | Verified Defect(s) |
|---|---|---|
| L5 | `MODDIR="${0%/*}"` | Non-portable (`CB-28`). |
| L11–L18 | Blind `kill -TERM "$PID"` + `sleep 2` + `kill -KILL "$PID"` | **CRITICAL (`CB-07`/`D-13`)**: PID-reuse `SIGKILL` of unrelated Android process! Does NOT set `.stop_supervisor` or stop `supervisor.pid` FIRST, so supervisor would immediately respawn `silod` after `SIGKILL` (`rc=137`)! |
| L21–L23 | `"$SILOD" --emergency-flush` | **HIGH (`CB-19`)**: Missing `--config "$CFG_PATH"` and must call `--uninstall-cleanup --config "$CFG_PATH"` (per `PROJECT.md § 4`) before `rm -rf "$STATE_DIR"` so Silo-applied AppOps and permission flags are reset. |

### 1.6 `module/module.prop` (Lines 1–7)
- Verified consistent (`id=silo`, `version=v3.0.0`, `versionCode=30000`, `author=terenzy`). Matches `daemon/Cargo.toml`, `app/app/build.gradle.kts`, and `/data/adb/modules/silo` paths. **No edits needed.**

---

## 2. Concrete, Edge-Case-Free POSIX-sh Engineering Designs

> **Portability & Sandbox-Testability Convention (Applies Across All Module Scripts)**:
> - Strict POSIX `sh` verified against `/usr/bin/dash` (CI/devbox), compatible with Android `/system/bin/sh` (`mksh`) and KernelSU BusyBox `ash`.
> - Zero bashisms (no arrays, no `[[ ... ]]`, no `local` keyword unless omitted — using `_`-prefixed helper vars instead because `local` is outside strict POSIX, though widely supported, avoiding it guarantees 100% POSIX compliance).
> - Default paths remain exact Android paths (`/data/adb/silo`, `/data/adb/modules/silo`), with optional `SILO_*` environment overrides (`STATE_DIR="${SILO_STATE_DIR:-/data/adb/silo}"`, `BOOT_WAIT_MAX="${SILO_BOOT_WAIT_MAX:-300}"`, etc.) so automated sandbox tests in M4 and M5 E2E can exercise the **unmodified production scripts** in non-root temp directories at full speed without mutating `/data/adb`.

### 2.1 Shared POSIX Primitives (Used Consistently Across `service.sh`, `action.sh`, `uninstall.sh`)

#### A. Canonical `MODDIR` Resolution (`CB-28`)
```sh
case "$0" in
  */*) MODDIR="${0%/*}" ;;
  *) MODDIR="." ;;
esac
MODDIR="$(CDPATH= cd -- "$MODDIR" 2>/dev/null && pwd -P)" || exit 1
```
- Handles `$0 = "service.sh"`, `$0 = "module/service.sh"`, `$0 = "/data/adb/modules/silo/service.sh"`, symlinks, and spaces in paths. `CDPATH=` prevents environment `CDPATH` pollution.

#### B. Safe Decimal Integer & Leading-Zero Sanitizer (Prevents POSIX Octal Errors)
```sh
parse_uint() {
  _raw="$(printf '%s' "$1" | tr -cd '0-9')"
  _raw="${_raw#"${_raw%%[!0]*}"}"
  if [ -z "$_raw" ]; then
    printf '%s' "$2"
  elif [ "${#_raw}" -gt 9 ]; then
    printf '%s' "$2"
  else
    printf '%s' "$_raw"
  fi
}
```
- Verified in `dash`: `"08"` → `"8"`, `"000"` → fallback, `""` → fallback, `"999999999999"` → fallback (overflow-safe).
- For `.boot_fail_count` specifically: if stripped digits have length `> 3` (i.e. `>= 1000`), treat `FAIL_COUNT=2` (fail-safe Dead-Man trigger).

#### C. Deterministic Shell Path for Spawning Supervisor
```sh
resolve_sh() {
  if [ -x /system/bin/sh ]; then
    printf '%s' "/system/bin/sh"
  elif [ -x /bin/sh ]; then
    printf '%s' "/bin/sh"
  else
    printf '%s' "sh"
  fi
}
```
- Avoids BusyBox standalone-applet `command -v sh` returning bare `"sh"` (which fails `[ -x "$SH_BIN" ]`). Deterministically picks `/system/bin/sh` on Android and `/bin/sh` (`dash`) on Linux/CI.

#### D. Atomic `0600` PID File Writer
```sh
write_pid_file() {
  _target="$1"
  _val="$2"
  _tmp="${_target}.tmp.$$"
  if printf '%s\n' "$_val" > "$_tmp" 2>/dev/null && chmod 0600 "$_tmp" 2>/dev/null && mv -f "$_tmp" "$_target" 2>/dev/null; then
    return 0
  fi
  rm -f "$_tmp" 2>/dev/null || true
  printf '%s\n' "$_val" > "$_target"
  chmod 0600 "$_target"
}
```
- Combined with `umask 077` at script start: zero `0644` permission window and zero partial/truncated PID reads during concurrent restarts.

#### E. Verified Process Identification (`is_silod_pid` & `is_supervisor_pid`) (`CB-07`, `D-13`)
```sh
is_silod_pid() {
  _pid="$1"
  case "$_pid" in
    ''|*[!0-9]*) return 1 ;;
  esac
  [ "${#_pid}" -le 9 ] || return 1
  [ "$_pid" -gt 0 ] 2>/dev/null || return 1
  kill -0 "$_pid" 2>/dev/null || return 1

  _matched=0
  if [ -L "/proc/$_pid/exe" ]; then
    _exe="$(readlink "/proc/$_pid/exe" 2>/dev/null || true)"
    case "$_exe" in
      */silod|*/silod" (deleted)"|silod) _matched=1 ;;
    esac
  fi

  [ -r "/proc/$_pid/cmdline" ] || return 1
  # Zombies have 0-byte /proc/$PID/cmdline -> _has_cmd stays 0 -> returns 1 automatically!
  tr '\000' '\n' < "/proc/$_pid/cmdline" 2>/dev/null | {
    _idx=0
    _has_cmd=0
    _tok_match="$_matched"
    while IFS= read -r _tok || [ -n "$_tok" ]; do
      _has_cmd=1
      case "$_tok" in
        --bridge|--status|--once|--version|--emergency-flush|--uninstall-cleanup)
          exit 1
          ;;
      esac
      if [ "$_idx" -eq 0 ]; then
        case "$_tok" in
          */silod|silod) _tok_match=1 ;;
        esac
      elif [ "$_idx" -le 2 ]; then
        case "$_tok" in
          */silod) _tok_match=1 ;;
        esac
      fi
      _idx=$((_idx + 1))
    done
    [ "$_has_cmd" -eq 1 ] && [ "$_tok_match" -eq 1 ]
  }
}

is_supervisor_pid() {
  _pid="$1"
  case "$_pid" in
    ''|*[!0-9]*) return 1 ;;
  esac
  [ "${#_pid}" -le 9 ] || return 1
  [ "$_pid" -gt 0 ] 2>/dev/null || return 1
  kill -0 "$_pid" 2>/dev/null || return 1
  [ -r "/proc/$_pid/cmdline" ] || return 1

  tr '\000' '\n' < "/proc/$_pid/cmdline" 2>/dev/null | {
    _has_script=0
    _has_flag=0
    while IFS= read -r _tok || [ -n "$_tok" ]; do
      case "$_tok" in
        */service.sh|service.sh) _has_script=1 ;;
        --supervisor) _has_flag=1 ;;
      esac
    done
    [ "$_has_script" -eq 1 ] && [ "$_has_flag" -eq 1 ]
  }
}
```
**Why every edge case is solved**:
1. **Real ARM64 ELF `silod`**: matches BOTH `/proc/$PID/exe` (`*/silod` or `*/silod (deleted)`) AND `argv[0]`.
2. **Sandbox test mock scripts (`#!/bin/sh` or `#!/usr/bin/env python3` at `$MODDIR/bin/silod`)**: `/proc/$PID/exe` is `/usr/bin/dash`, so `argv[1]` (or `argv[2]` under `busybox sh`) matches `*/silod`.
3. **Zero false positives**: rejects `sleep`, `surfaceflinger`, `grep silod` (`argv[1]` lacks `/`), `cat /data/adb/silo/silod.pid`, AND `silod --bridge` / `--status` / `--once` / `--emergency-flush` / `--uninstall-cleanup`!
4. **Zombie immunity**: Linux clears `mm` on exit, making `/proc/$PID/cmdline` 0 bytes for zombies → `_has_cmd=0` → returns `1` immediately (avoiding the classic `kill -0 $ZOMBIE == 0` spin bug!).
5. **POSIX `tr '\000' '\n'`**: strictly POSIX octal escape, portable across GNU, Android Toybox, and BusyBox `tr`.
6. **`is_supervisor_pid`**: matches ONLY a process whose NUL-separated argv contains both `*/service.sh` (or `service.sh`) AND exact token `--supervisor`. Will never match unrelated processes or even a boot-waiting `service.sh` without `--supervisor`.

#### F. Verified Stop Helpers (`stop_supervisor` & `stop_silod`, 5s Graceful Wait + Verified `SIGKILL`)
```sh
# Portable 0.1s sleep step with integer-sleep fallback (max total ~5s for 50 ticks)
sleep_tenth() {
  if sleep 0.1 2>/dev/null; then
    return 0
  fi
  sleep 1
  return 1
}

stop_supervisor() {
  [ -f "$SUP_PID_FILE" ] || return 0
  _spid="$(parse_uint "$(cat "$SUP_PID_FILE" 2>/dev/null || true)" "")"
  if [ -n "$_spid" ] && [ "$_spid" != "$$" ] && is_supervisor_pid "$_spid"; then
    kill -TERM "$_spid" 2>/dev/null || true
    _i=0
    while [ "$_i" -lt 50 ]; do
      is_supervisor_pid "$_spid" || break
      if sleep_tenth; then _i=$((_i + 1)); else _i=$((_i + 10)); fi
    done
    # Re-verify PID identity right before SIGKILL to close TOCTOU PID-reuse window!
    if is_supervisor_pid "$_spid"; then
      kill -KILL "$_spid" 2>/dev/null || true
      _i=0
      while [ "$_i" -lt 10 ]; do
        is_supervisor_pid "$_spid" || break
        if sleep_tenth; then _i=$((_i + 1)); else _i=$((_i + 10)); fi
      done
    fi
  fi
  rm -f "$SUP_PID_FILE"
}

stop_silod() {
  [ -f "$PID_FILE" ] || return 0
  _dpid="$(parse_uint "$(cat "$PID_FILE" 2>/dev/null || true)" "")"
  if [ -n "$_dpid" ] && is_silod_pid "$_dpid"; then
    kill -TERM "$_dpid" 2>/dev/null || true
    _i=0
    while [ "$_i" -lt 50 ]; do
      is_silod_pid "$_dpid" || break
      if sleep_tenth; then _i=$((_i + 1)); else _i=$((_i + 10)); fi
    done
    if is_silod_pid "$_dpid"; then
      kill -KILL "$_dpid" 2>/dev/null || true
      _i=0
      while [ "$_i" -lt 10 ]; do
        is_silod_pid "$_dpid" || break
        if sleep_tenth; then _i=$((_i + 1)); else _i=$((_i + 10)); fi
      done
    fi
  fi
  rm -f "$PID_FILE"
}
```
**Teardown Invariant**: EVERY caller (`service.sh`, `action.sh`, `uninstall.sh`) MUST execute teardown in strict order:
1. `[ -d "$STATE_DIR" ] && : > "$STOP_FLAG" 2>/dev/null || true` (blocks any supervisor from respawning even if `supervisor.pid` was missing/corrupted).
2. `stop_supervisor` (stops supervisor first; supervisor forwards `SIGTERM` to `silod` and exits).
3. `stop_silod` (waits up to 5s for `silod` to complete `thaw_all()` & clean socket unlink, escalating to verified `SIGKILL` only if hung).
4. Only AFTER both are dead: `rm -f "$SOCK_PATH"` (or proceed to `--uninstall-cleanup` / fresh supervisor launch after `rm -f "$STOP_FLAG"`).

---

### 2.2 `module/service.sh` — Complete Architecture (`--supervisor` Mode + 300s Boot Wait + Dead-Man Switch)

#### Design Choice: Self-Invoked `service.sh --supervisor` (Instead of Inline Subshell)
Why `service.sh --supervisor` is strictly superior to `( ... ) &`:
1. **`$$` correctness**: In POSIX `sh`, `$$` inside a `( ... ) &` subshell is the ALREADY-EXITED parent shell's PID (classic POSIX footgun!). In a re-invoked `"$SH_BIN" "$MODDIR/service.sh" --supervisor`, **`$$` is guaranteed to be the supervisor's real PID**, even if `setsid` forks!
2. **Unambiguous `/proc/$PID/cmdline`**: Always contains `service.sh` AND `--supervisor`, enabling 100% false-positive-free `is_supervisor_pid` verification and never colliding with a boot-waiting `service.sh`.
3. **Zero duplication with `action.sh`**: `action.sh` launches the exact same `"$SH_BIN" "$MODDIR/service.sh" --supervisor` without waiting for `sys.boot_completed` or incrementing `.boot_fail_count`, so recovered `silod` gets identical crash/LMKD supervision!
4. **Clean `setsid` + stdio detachment**: Parent launches via `setsid "$SH_BIN" "$MODDIR/service.sh" --supervisor </dev/null >/dev/null 2>&1 &` (or without `setsid` if absent), waits up to 2s until `[ -s "$SUP_PID_FILE" ] && [ -s "$PID_FILE" ]` (normally 0–100ms), and returns `0` immediately so KernelSU boot and `action.sh` UI NEVER hang!

#### Complete Flow of `service.sh`
**Common header**:
```sh
#!/system/bin/sh
umask 077

case "$0" in
  */*) MODDIR="${0%/*}" ;;
  *) MODDIR="." ;;
esac
MODDIR="$(CDPATH= cd -- "$MODDIR" 2>/dev/null && pwd -P)" || exit 1

SILOD="$MODDIR/bin/silod"
STATE_DIR="${SILO_STATE_DIR:-/data/adb/silo}"
BOOT_MARKER="$STATE_DIR/.boot_fail_count"
SOCK_PATH="$STATE_DIR/silod.sock"
CFG_PATH="$STATE_DIR/config.json"
PID_FILE="$STATE_DIR/silod.pid"
SUP_PID_FILE="$STATE_DIR/supervisor.pid"
STOP_FLAG="$STATE_DIR/.stop_supervisor"
```

**Mode 1: `service.sh --supervisor` (Bounded Crash-Restart Supervisor)**
1. Parse sanitized tunable defaults:
   - `SUP_MAX_RESTARTS="$(parse_uint "${SILO_SUP_MAX_RESTARTS:-5}" 5)"` (default **5** restarts)
   - `SUP_WINDOW_SECS="$(parse_uint "${SILO_SUP_WINDOW_SECS:-60}" 60)"` (default **60s** sliding window)
   - `SUP_BASE_BACKOFF="$(parse_uint "${SILO_SUP_BASE_BACKOFF:-1}" 1)"` (default **1s**)
   - `SUP_MAX_BACKOFF="$(parse_uint "${SILO_SUP_MAX_BACKOFF:-8}" 8)"` (default **8s**; backoff sequence `1, 2, 4, 8, 8` sums to **`23s < 60s`**, mathematically guaranteeing a tight crash loop ALWAYS hits the 5-in-60s cap and never expires earlier timestamps!)
2. Ignore `SIGHUP`: `trap '' HUP`.
3. Best-effort OOM protection for the tiny supervisor shell itself: `printf '%s\n' -900 > "/proc/$$/oom_score_adj" 2>/dev/null || true`.
4. Precondition checks (exit `0` immediately if any fail):
   - `[ -f "$MODDIR/disable" ] && exit 0`
   - `[ -f "$STOP_FLAG" ] && exit 0`
   - `[ -d "$STATE_DIR" ] || exit 0`
   - `[ -x "$SILOD" ] || exit 0`
5. Duplicate-supervisor guard:
   - If `[ -f "$SUP_PID_FILE" ]`, read `_existing_sup`; if `[ -n "$_existing_sup" ] && [ "$_existing_sup" != "$$" ] && is_supervisor_pid "$_existing_sup"`, exit `0` cleanly (zero duplicate supervisors!).
   - Atomically write `$$` to `$SUP_PID_FILE` via `write_pid_file "$SUP_PID_FILE" "$$"`.
6. Signal trap setup:
   ```sh
   STOP_REQUESTED=0
   CHILD_PID=""
   SLEEP_PID=""

   on_stop_signal() {
     STOP_REQUESTED=1
     if [ -n "$SLEEP_PID" ]; then
       kill -TERM "$SLEEP_PID" 2>/dev/null || true
     fi
     if [ -n "$CHILD_PID" ] && is_silod_pid "$CHILD_PID"; then
       kill -TERM "$CHILD_PID" 2>/dev/null || true
     fi
   }
   trap on_stop_signal TERM INT
   ```
7. Main loop (`CRASH_TIMES=""`):
   ```sh
   while [ "$STOP_REQUESTED" -eq 0 ]; do
     [ -f "$MODDIR/disable" ] && break
     [ -f "$STOP_FLAG" ] && break
     [ -d "$STATE_DIR" ] || break
     [ -x "$SILOD" ] || break

     # Yield if a newer supervisor took ownership of SUP_PID_FILE
     if [ -f "$SUP_PID_FILE" ]; then
       _cur_sup="$(parse_uint "$(cat "$SUP_PID_FILE" 2>/dev/null || true)" "")"
       [ -n "$_cur_sup" ] && [ "$_cur_sup" != "$$" ] && break
     fi

     "$SILOD" \
       --socket "$SOCK_PATH" \
       --config "$CFG_PATH" \
       --boot-marker "$BOOT_MARKER" \
       </dev/null >/dev/null 2>&1 &
     CHILD_PID="$!"
     write_pid_file "$PID_FILE" "$CHILD_PID"

     # Guard microsecond window if SIGTERM arrived between spawn and wait
     if [ "$STOP_REQUESTED" -eq 0 ]; then
       RC=0
       wait "$CHILD_PID" || RC=$?
     else
       RC=143
     fi

     # Case A: Supervisor received SIGTERM / SIGINT
     if [ "$STOP_REQUESTED" -ne 0 ]; then
       if [ -n "$CHILD_PID" ] && is_silod_pid "$CHILD_PID"; then
         kill -TERM "$CHILD_PID" 2>/dev/null || true
         _w=0
         while [ "$_w" -lt 40 ]; do
           is_silod_pid "$CHILD_PID" || break
           if sleep_tenth; then _w=$((_w + 1)); else _w=$((_w + 10)); fi
         done
       fi
       # Only reap if actually exited/zombie — NEVER block indefinitely on a hung silod!
       if [ -n "$CHILD_PID" ] && ! is_silod_pid "$CHILD_PID"; then
         wait "$CHILD_PID" 2>/dev/null || true
       fi
       break
     fi

     # Clear PID_FILE only if it still points to our exited child
     if [ -f "$PID_FILE" ]; then
       _cur_pid="$(parse_uint "$(cat "$PID_FILE" 2>/dev/null || true)" "")"
       [ "$_cur_pid" = "$CHILD_PID" ] && rm -f "$PID_FILE"
     fi
     CHILD_PID=""

     # Case B: Clean exit (0) or explicit SIGTERM (143) / SIGINT (130) -> DO NOT restart
     case "$RC" in
       0|130|143) break ;;
     esac

     # Case C: Explicit stop / disable / uninstall occurred while child was dying -> DO NOT restart
     [ -f "$MODDIR/disable" ] && break
     [ -f "$STOP_FLAG" ] && break
     [ -d "$STATE_DIR" ] || break
     [ -x "$SILOD" ] || break

     # Case D: Unexpected exit (LMKD SIGKILL=137, SIGABRT=134, SIGSEGV=139, exit 1, etc.)
     # True sliding window using pure POSIX space-separated timestamps (zero bash arrays!)
     NOW="$(parse_uint "$(date +%s 2>/dev/null || true)" 0)"
     NEW_TIMES=""
     CRASH_COUNT=0
     for _t in $CRASH_TIMES; do
       if [ "$NOW" -ge "$_t" ] && [ $((NOW - _t)) -lt "$SUP_WINDOW_SECS" ]; then
         NEW_TIMES="${NEW_TIMES:+$NEW_TIMES }$_t"
         CRASH_COUNT=$((CRASH_COUNT + 1))
       fi
     done
     NEW_TIMES="${NEW_TIMES:+$NEW_TIMES }$NOW"
     CRASH_COUNT=$((CRASH_COUNT + 1))
     CRASH_TIMES="$NEW_TIMES"

     if [ "$CRASH_COUNT" -gt "$SUP_MAX_RESTARTS" ]; then
       break
     fi

     # Bounded exponential backoff: 1, 2, 4, 8, 8 (sum = 23s < 60s), interruptible by SIGTERM!
     BACKOFF="$SUP_BASE_BACKOFF"
     if [ "$BACKOFF" -gt 0 ]; then
       _shift=$((CRASH_COUNT - 1))
       _n=0
       while [ "$_n" -lt "$_shift" ] && [ "$BACKOFF" -lt "$SUP_MAX_BACKOFF" ]; do
         BACKOFF=$((BACKOFF * 2))
         _n=$((_n + 1))
       done
       [ "$BACKOFF" -gt "$SUP_MAX_BACKOFF" ] && BACKOFF="$SUP_MAX_BACKOFF"

       sleep "$BACKOFF" &
       SLEEP_PID="$!"
       if [ "$STOP_REQUESTED" -ne 0 ]; then
         kill -TERM "$SLEEP_PID" 2>/dev/null || true
       fi
       wait "$SLEEP_PID" 2>/dev/null || true
       SLEEP_PID=""
     fi
   done

   # Clean up PID files only if we still own them
   if [ -n "$CHILD_PID" ] && [ -f "$PID_FILE" ] && ! is_silod_pid "$CHILD_PID"; then
     _cur_pid="$(parse_uint "$(cat "$PID_FILE" 2>/dev/null || true)" "")"
     [ "$_cur_pid" = "$CHILD_PID" ] && rm -f "$PID_FILE"
   fi
   if [ -f "$SUP_PID_FILE" ]; then
     _cur_sup="$(parse_uint "$(cat "$SUP_PID_FILE" 2>/dev/null || true)" "")"
     [ "$_cur_sup" = "$$" ] && rm -f "$SUP_PID_FILE"
   fi
   exit 0
   ```

**Sliding-Window Edge-Case Verification Matrix** (tested on `dash`):
| Scenario | Behavior |
|---|---|
| Tight crash loop (`t=100,101,103,107,115,125`) | Initial launch + **5 restarts** (`1,2,4,8,8s`, total `23s < 60s`); 6th crash within 60s sees `CRASH_COUNT=6 > 5` → breaks & exits cleanly. |
| Runs smoothly for hours, then LMKD sends `SIGKILL` (`rc=137`) | Old timestamps pruned (`NOW - _t >= 60`); `CRASH_COUNT=1`, backoff resets to `1s`, restarts `silod`, updates `silod.pid` (`0600`). |
| Burst across boundary (`t=0,55,56,57,58,61,62`) | True sliding window: at `t=61` count is `5` (`55..61`); at `t=62` count is `6 > 5` → stops (no 9-in-10s tumbling-window bug!). |
| NTP/NITZ backward clock jump (`NOW < _t`) | `[ "$NOW" -ge "$_t" ]` prunes future timestamps safely (no negative arithmetic bug). |
| Broken `date` returning empty/`0` | `NOW=0`; `0 >= 0 && 0 - 0 < 60` is true → still bounded at 5 restarts (fail-safe, never infinite-loops!). |
| `SIGTERM` arrives during backoff `sleep 8` | `sleep "$BACKOFF" & SLEEP_PID=$!; wait "$SLEEP_PID"` wakes IMMEDIATELY, kills sleeper, cleans `supervisor.pid`, exits `0` in `<50ms`! |
| `SIGTERM` arrives while `silod` is hung | Forwards `SIGTERM` to verified `CHILD_PID`, waits at most 4s checking `is_silod_pid`, skips blocking `wait` if still hung, exits cleanly so `uninstall.sh`/`action.sh`'s `stop_silod` can escalate to `SIGKILL`. |
| Manual `kill -TERM $(cat silod.pid)` | `silod` catches `SIGTERM` and exits `0` (or `143` if killed early) → supervisor sees `0|130|143` → cleans PID files and exits `0` without restarting. |
| `silod` dies with `rc=137` during `uninstall.sh` | `uninstall.sh` already set `.stop_supervisor` AND stopped supervisor first → impossible to respawn. |

**Mode 2: `service.sh` (Normal Boot Entry — No `--supervisor` Arg)**
1. Early check: `[ -f "$MODDIR/disable" ] && exit 0`.
2. Sanitized boot wait tunables:
   - `BOOT_WAIT_MAX="$(parse_uint "${SILO_BOOT_WAIT_MAX:-300}" 300)"` (raised **180s → 300s**)
   - `BOOT_WAIT_STEP="$(parse_uint "${SILO_BOOT_WAIT_STEP:-2}" 2)"` (`[ "$BOOT_WAIT_STEP" -gt 0 ] || BOOT_WAIT_STEP=2`)
   - `BOOT_SETTLE_SECS="$(parse_uint "${SILO_BOOT_SETTLE_SECS:-5}" 5)"`
3. Boot completion polling:
   ```sh
   WAIT_SEC=0
   while [ "$(getprop sys.boot_completed 2>/dev/null | tr -d '\r\n\t ')" != "1" ]; do
     [ -f "$MODDIR/disable" ] && exit 0
     sleep "$BOOT_WAIT_STEP"
     WAIT_SEC=$((WAIT_SEC + BOOT_WAIT_STEP))
     if [ "$WAIT_SEC" -ge "$BOOT_WAIT_MAX" ]; then
       exit 0
     fi
   done
   [ "$BOOT_SETTLE_SECS" -gt 0 ] && sleep "$BOOT_SETTLE_SECS"
   [ -f "$MODDIR/disable" ] && exit 0
   ```
4. Prepare `$STATE_DIR` (`mkdir -p`, `chmod 0700`) and check `[ -x "$SILOD" ] || exit 0`.
5. Parse `.boot_fail_count` safely (no octal/overflow):
   ```sh
   FAIL_COUNT=0
   if [ -f "$BOOT_MARKER" ]; then
     IFS= read -r _raw_line < "$BOOT_MARKER" 2>/dev/null || true
     _raw_cnt="$(printf '%s' "$_raw_line" | tr -cd '0-9')"
     _raw_cnt="${_raw_cnt#"${_raw_cnt%%[!0]*}"}"
     if [ -n "$_raw_cnt" ]; then
       if [ "${#_raw_cnt}" -gt 3 ]; then
         FAIL_COUNT=2
       else
         FAIL_COUNT="$_raw_cnt"
       fi
     fi
   fi
   ```
6. **Layer 2 Dead-Man Switch (`FAIL_COUNT -ge 2`)**:
   ```sh
   if [ "$FAIL_COUNT" -ge 2 ]; then
     : > "$STOP_FLAG" 2>/dev/null || true
     stop_supervisor
     stop_silod
     "$SILOD" --emergency-flush --config "$CFG_PATH" >/dev/null 2>&1 || true
     touch "$MODDIR/disable" 2>/dev/null || true
     rm -f "$BOOT_MARKER" "$SOCK_PATH" "$PID_FILE" "$SUP_PID_FILE" "$STOP_FLAG"
     exit 0
   fi
   ```
7. Arm `.boot_fail_count` (`printf '%s\n' "$((FAIL_COUNT + 1))" > "$BOOT_MARKER"; chmod 0600 "$BOOT_MARKER"`).
8. Stop any stale/existing supervisor & `silod` cleanly (`: > "$STOP_FLAG"`, `stop_supervisor`, `stop_silod`), then `rm -f "$SOCK_PATH" "$STOP_FLAG"`.
9. Launch supervisor detached:
   ```sh
   SH_BIN="$(resolve_sh)"
   if command -v setsid >/dev/null 2>&1; then
     setsid "$SH_BIN" "$MODDIR/service.sh" --supervisor </dev/null >/dev/null 2>&1 &
   else
     "$SH_BIN" "$MODDIR/service.sh" --supervisor </dev/null >/dev/null 2>&1 &
   fi

   # Brief bounded wait (max ~2s, typically <100ms) so PID files exist when service.sh returns
   _w=0
   while [ "$_w" -lt 20 ]; do
     [ -s "$SUP_PID_FILE" ] && [ -s "$PID_FILE" ] && break
     if sleep_tenth; then _w=$((_w + 1)); else _w=$((_w + 10)); fi
   done
   ```

---

### 2.3 `module/action.sh` — Recovery & Supervisor Coordination Design

1. Resolve canonical `MODDIR`, set `STATE_DIR="${SILO_STATE_DIR:-/data/adb/silo}"`, `SILOD`, `SOCK_PATH`, `CFG_PATH`, `BOOT_MARKER`, `PID_FILE`, `SUP_PID_FILE`, `STOP_FLAG`.
2. If `[ -S "$SOCK_PATH" ] && STATUS_JSON="$("$SILOD" --socket "$SOCK_PATH" --status 2>/dev/null)"`:
   - Print `[OK] silod is running...` and `$STATUS_JSON`; do NOT disturb running supervisor or daemon.
3. Otherwise (recovery branch — daemon down or hung):
   - Print `[INFO] silod is not currently running. Clearing disable/Dead-Man markers & starting...`
   - `mkdir -p "$STATE_DIR"` and `chmod 0700 "$STATE_DIR"`.
   - **Remove disable & boot markers (`CB-18`)**: `rm -f "$MODDIR/disable" "$BOOT_MARKER"`.
   - **Stop any old supervisor + hung `silod` (`CB-07`/`D-13`)**:
     - `: > "$STOP_FLAG" 2>/dev/null || true`
     - `stop_supervisor`
     - `stop_silod`
     - `rm -f "$SOCK_PATH" "$STOP_FLAG"`
   - **Launch via bounded supervisor** (same `service.sh --supervisor`, detached stdio `</dev/null >/dev/null 2>&1` so KernelSU Manager's Action pipe reaches EOF immediately!):
     ```sh
     SH_BIN="$(resolve_sh)"
     if [ -f "$MODDIR/service.sh" ]; then
       if command -v setsid >/dev/null 2>&1; then
         setsid "$SH_BIN" "$MODDIR/service.sh" --supervisor </dev/null >/dev/null 2>&1 &
       else
         "$SH_BIN" "$MODDIR/service.sh" --supervisor </dev/null >/dev/null 2>&1 &
       fi
     else
       "$SILOD" --socket "$SOCK_PATH" --config "$CFG_PATH" --boot-marker "$BOOT_MARKER" \
         </dev/null >/dev/null 2>&1 &
       write_pid_file "$PID_FILE" "$!"
     fi
     ```
   - **Bounded readiness poll (up to 5s, breaking immediately on success)**:
     ```sh
     STARTED=0
     _w=0
     while [ "$_w" -lt 25 ]; do
       if [ -S "$SOCK_PATH" ] && "$SILOD" --socket "$SOCK_PATH" --status >/dev/null 2>&1; then
         STARTED=1
         break
       fi
       if sleep 0.2 2>/dev/null; then _w=$((_w + 1)); else sleep 1; _w=$((_w + 5)); fi
     done
     if [ "$STARTED" -eq 1 ]; then
       echo "[OK] silod started successfully (supervised)."
     else
       echo "[WARN] silod did not respond yet; check KernelSU module state."
     fi
     ```

---

### 2.4 `module/uninstall.sh` — Supervisor-Safe Clean Teardown Contract

Must execute in this exact sequence (aligning with `PROJECT.md § 4` & `CB-07`/`CB-19`):
1. Resolve canonical `MODDIR`, set `STATE_DIR="${SILO_STATE_DIR:-/data/adb/silo}"`, `SILOD`, `CFG_PATH`, `PID_FILE`, `SUP_PID_FILE`, `STOP_FLAG`.
2. If `[ -d "$STATE_DIR" ]`, `: > "$STOP_FLAG" 2>/dev/null || true`.
3. `stop_supervisor` (verified via `/proc/$PID/cmdline`).
4. `stop_silod` (verified via `/proc/$PID/exe` + `/proc/$PID/cmdline`, 5s wait + verified `SIGKILL`).
5. Run cleanup BEFORE removing `$STATE_DIR` (so `config.json` is still readable for AppOps + DeepStop unsuspend!):
   ```sh
   if [ -x "$SILOD" ]; then
     "$SILOD" --uninstall-cleanup --config "$CFG_PATH" >/dev/null 2>&1 || \
       "$SILOD" --emergency-flush --config "$CFG_PATH" >/dev/null 2>&1 || true
   fi
   ```
6. `rm -rf "$STATE_DIR"`.

---

### 2.5 `module/customize.sh` — Sourced & Standalone Safe Installer Design (`CB-18`, `CB-28`)

**Critical KernelSU/Magisk Sourcing Invariants**:
1. Keep `SKIPUNZIP=0`.
2. **NEVER** put `exit 0` at the end of `customize.sh` (it is sourced via `. "$MODPATH/customize.sh"`; `exit 0` would prematurely kill KernelSU's module installer!).
3. **NEVER** set `set -e` or `umask 077` globally in `customize.sh` (mutates the caller's installer shell!).
4. **NEVER** overwrite `$ARCH`, `$API`, `$IS64BIT`, `$MODPATH`, `$KSU`, or other KernelSU/Magisk globals — prefix all local variables with `SILO_`!

**Exact POSIX-sh Implementation Specification**:
```sh
#!/system/bin/sh
# ==============================================================================
# Silo KernelSU Module Installer (Zero-Mount, ARM64 Static Rust Daemon)
# ==============================================================================
SKIPUNZIP=0

# Standalone fallback helpers (only defined when not sourced by KernelSU/Magisk)
if ! command -v ui_print >/dev/null 2>&1; then
  ui_print() { printf '%s\n' "$1"; }
fi
if ! command -v abort >/dev/null 2>&1; then
  abort() { printf '%s\n' "$1" >&2; exit 1; }
fi

if [ -z "${MODPATH:-}" ]; then
  case "$0" in
    */*) MODPATH="${0%/*}" ;;
    *) MODPATH="." ;;
  esac
  MODPATH="$(CDPATH= cd -- "$MODPATH" 2>/dev/null && pwd -P)" || abort "! Cannot determine MODPATH"
fi

ui_print "- Installing Silo Kernel Privacy & Security..."

# Strict ARM64 check: primary ro.product.cpu.abi, with ro.product.cpu.abilist64 & KSU/Magisk $ARCH fallback.
# NOTE: Never clobber KernelSU/Magisk's $ARCH variable, and never let Houdini x86_64+arm64 abilist bypass primary x86_64!
SILO_ABI="$(getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r\n\t ' || true)"
SILO_ABILIST64="$(getprop ro.product.cpu.abilist64 2>/dev/null | tr -d '\r\n\t ' || true)"
SILO_KSU_ARCH="${ARCH:-}"
SILO_VERIFIED_ARCH=""

case "$SILO_ABI" in
  arm64|arm64-v8a*|aarch64*)
    SILO_VERIFIED_ARCH="$SILO_ABI"
    ;;
  armeabi*|x86*|mips*|riscv*)
    abort "! Unsupported architecture: $SILO_ABI (Silo requires arm64-v8a)"
    ;;
  *)
    case ",$SILO_ABILIST64," in
      *,x86_64,*|*,x86,*)
        abort "! Unsupported architecture: $SILO_ABILIST64 (Silo requires arm64-v8a)"
        ;;
      *,arm64,*|*,arm64-v8a,*|*,arm64-v8a-*,*|*,aarch64,*)
        SILO_VERIFIED_ARCH="$SILO_ABILIST64"
        ;;
      *)
        case "$SILO_KSU_ARCH" in
          arm64|arm64-v8a*|aarch64*)
            SILO_VERIFIED_ARCH="$SILO_KSU_ARCH"
            ;;
          *)
            SILO_FOUND="${SILO_ABI:-${SILO_ABILIST64:-${SILO_KSU_ARCH:-unknown}}}"
            abort "! Unsupported architecture: $SILO_FOUND (Silo requires arm64-v8a)"
            ;;
        esac
        ;;
    esac
    ;;
esac

ui_print "- Architecture verified: $SILO_VERIFIED_ARCH"

if [ ! -f "$MODPATH/bin/silod" ]; then
  abort "! Missing silod binary inside module archive"
fi

chmod 0755 "$MODPATH/bin/silod"
chmod 0755 "$MODPATH/service.sh" "$MODPATH/action.sh" "$MODPATH/uninstall.sh"
if command -v set_perm >/dev/null 2>&1; then
  set_perm "$MODPATH/bin/silod" 0 0 0755 2>/dev/null || true
  set_perm "$MODPATH/service.sh" 0 0 0755 2>/dev/null || true
  set_perm "$MODPATH/action.sh" 0 0 0755 2>/dev/null || true
  set_perm "$MODPATH/uninstall.sh" 0 0 0755 2>/dev/null || true
fi

# Prepare root-only runtime directory (0700) — never modifies /system, /vendor, /product, /system_ext, or /odm
SILO_STATE_PATH="${SILO_STATE_DIR:-/data/adb/silo}"
SILO_LIVE_MODDIR="${SILO_LIVE_MODDIR:-/data/adb/modules/silo}"
mkdir -p "$SILO_STATE_PATH"
chmod 0700 "$SILO_STATE_PATH"
rm -f "$SILO_STATE_PATH/.boot_fail_count" "$SILO_STATE_PATH/.stop_supervisor"

# Clear any prior anti-bootloop disable marker on reinstall/upgrade (both live module dir and staged MODPATH)
rm -f "$SILO_LIVE_MODDIR/disable" "$MODPATH/disable"

ui_print "- Zero-mount stealth install complete."
ui_print "- Anti-Bootloop Dead-Man Switch armed (safe-first default on boot)."
ui_print "- Reboot to start silod, then grant root to Silo in KernelSU-Next."
```

**Architecture Decision Verification Matrix**:
| `ro.product.cpu.abi` | `ro.product.cpu.abilist64` | `$ARCH` | Result |
|---|---|---|---|
| `arm64-v8a` | `arm64-v8a` | `arm64` | **PASS** (`arm64-v8a`) |
| `""` (empty) | `arm64-v8a` | `""` | **PASS** (`arm64-v8a`) |
| `""` (empty) | `""` | `arm64` | **PASS** (`arm64`) |
| `armeabi-v7a` | `""` | `arm` | **ABORT** (`armeabi-v7a`) |
| `x86` | `""` | `x86` | **ABORT** (`x86`) |
| `x86_64` | `x86_64` | `x64` | **ABORT** (`x86_64`) |
| `x86_64` | `x86_64,arm64-v8a` (Houdini) | `x64` | **ABORT** (`x86_64` — primary ABI rejected before fallback!) |
| `""` | `x86_64,arm64-v8a` | `""` | **ABORT** (`x86_64,arm64-v8a` — x86_64 branch matched first!) |
| `""` | `""` | `x86_64` / `x64` / `arm` | **ABORT** |
| `""` | `""` | `""` | **ABORT** (`unknown`) |

---

## 3. Recommended Verification Test Matrix for M4 Implementer / Verifier

All tests can run directly against the unmodified scripts on this devbox (`sh` = `/usr/bin/dash`) using temporary sandbox directories and stub `PATH` shims (`getprop`, `date`, mock `silod`):
1. **Syntax check**: `sh -n module/service.sh module/customize.sh module/action.sh module/uninstall.sh`.
2. **`service.sh` 300s boot wait**:
   - Stub `getprop` returning `0` with `SILO_BOOT_WAIT_MAX=4 SILO_BOOT_WAIT_STEP=2` → exits `0` without launching `silod` or creating `.boot_fail_count`.
   - Verify default constant in script is `300` (not `180`).
3. **`service.sh` Dead-Man Switch & octal safety**:
   - `.boot_fail_count` = `"08"` → triggers Dead-Man Switch without `Illegal number` error, runs `--emergency-flush --config "$CFG_PATH"`, creates `$MODDIR/disable`, deletes `.boot_fail_count`.
   - `.boot_fail_count` = `"0"` → increments to `"1"` (`0600`), launches supervisor + `silod`, writes `supervisor.pid` (`0600`) and `silod.pid` (`0600`, holding ACTUAL `silod` PID != supervisor PID!).
   - `$MODDIR/disable` present → `service.sh` exits `0` immediately without launching `silod`.
4. **Supervisor survivability & stop-edge cases**:
   - **LMKD `SIGKILL` (`137`)**: kill mock `silod` with `kill -9` → supervisor restarts `silod` and updates `silod.pid` (`0600`) to new child PID!
   - **Bounded 5-in-60s cap**: mock `silod` exits `1` (or `139`) immediately with `SILO_SUP_BASE_BACKOFF=0` → spawns initial + 5 restarts = **6 total starts**, then supervisor exits cleanly and cleans `supervisor.pid`!
   - **Sliding window reset after 60s**: stub `date +%s` jumping `+65s` after 3 crashes → counter resets to `1`, restart succeeds!
   - **Clean exit (`rc=0`)**: mock `silod` traps `SIGTERM` and exits `0` → supervisor does NOT restart and exits `0`.
   - **Explicit `SIGTERM` (`rc=143`)**: mock `silod` without trap killed with `kill -TERM` → supervisor does NOT restart and exits `0`.
   - **Supervisor `SIGTERM` during backoff sleep**: send `SIGTERM` to supervisor while sleeping → exits in `<0.5s` without respawning.
   - **Disable marker created while running**: touch `$MODDIR/disable` then `kill -9` `silod` → supervisor does NOT restart!
5. **PID-reuse safety (`CB-07`/`D-13`)**:
   - Write an innocent `sleep 30` PID into `silod.pid` and `supervisor.pid` → run `service.sh`, `action.sh`, `uninstall.sh` → innocent `sleep 30` MUST remain alive (`kill -0` succeeds)!
6. **`action.sh` recovery**:
   - With `$MODDIR/disable` + `.boot_fail_count` + hung mock `silod` + old supervisor → `action.sh` removes `$MODDIR/disable` & `.boot_fail_count`, stops old supervisor & hung `silod`, starts supervised `silod` cleanly, returns immediately (no hanging pipe FDs!).
7. **`uninstall.sh` race-free teardown**:
   - With active supervisor + `silod` → `uninstall.sh` stops supervisor first, stops `silod` (zero respawn!), runs `--uninstall-cleanup --config "$CFG_PATH"`, deletes `$STATE_DIR`.
8. **`customize.sh` sourced & standalone**:
   - Test both `. module/customize.sh` (with mock `ui_print`, `abort`, `set_perm`, `ARCH=arm64`, `MODPATH=...`) AND standalone `sh module/customize.sh`.
   - Verify all 10 architecture matrix cases, verify `$ARCH` is NOT modified when sourced, verify BOTH `$SILO_LIVE_MODDIR/disable` and `$MODPATH/disable` and `.boot_fail_count` are removed, verify `0700` state dir & `0755` bin/scripts.
