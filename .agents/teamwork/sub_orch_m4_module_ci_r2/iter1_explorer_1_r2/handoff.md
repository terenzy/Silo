# Handoff — `iter1_explorer_1_r2` (Module Lifecycle, PID-Reuse Safety, Uninstall Cleanup & Peer Synthesis)

**Milestone**: M4 (`module-scripts-ci`)  
**Role**: Module Lifecycle Explorer (`CB-07` / `D-13`, `CB-19`, plus adversarial cross-verification & harmonization of `iter1_explorer_2` and `iter1_explorer_3` across `module/{service,action,uninstall,customize}.sh`, `module/module.prop`, `scripts/build_module.sh`, and `.github/workflows/build.yml`)  
**Repository**: `/home/antigravity/terenzy/Silo` (READ-ONLY audit; zero source/git modifications)  
**Target Runtimes**: Android 15 / One UI 7 (`SM-A146P`, `/system/bin/sh` = `mksh` + Toybox + KernelSU-Next BusyBox `ash`) AND Linux devbox/CI (`/bin/sh` = `dash`)

---

## Executive Summary — Critical Refinements Found Beyond Explorers 2 & 3

Both preserved peer handoffs (`iter1_explorer_2/handoff.md` and `iter1_explorer_3/handoff.md`) are architecturally strong, and **no fundamental contradiction exists** between them (the self-contained 5-file module whitelist `module.prop`, `customize.sh`, `service.sh`, `action.sh`, `uninstall.sh` is 100% aligned with `scripts/build_module.sh`, `.github/workflows/build.yml`, and E2E `packaging_harness.py`).

However, deep line-by-line stress-testing and empirical POSIX `dash`/Linux `/proc` probing uncovered **9 concrete bugs, races, and cross-boundary hazards** in `iter1_explorer_2/handoff.md` (plus 1 minor polish in `iter1_explorer_3/handoff.md`) that MUST be fixed by the M4 Worker on the first pass:

| # | Severity | Location / Affected Proposal | Verified Bug / Hazard | Mandatory Fix for M4 Worker |
|---|---|---|---|---|
| **R1** | **CRITICAL** | `iter1_explorer_2` `is_silod_pid` (`handoff.md:159-182`) | Uses `exit 1` inside a pipeline `{ ... }` block. Under POSIX shells that execute the final pipeline command in the current shell (`ksh93`, `zsh`, `bash -O lastpipe`), `exit 1` **terminates the entire calling script** (`service.sh`/`action.sh`/`uninstall.sh`) immediately! Also leaks loop variables. | Wrap pipeline consumer in an explicit subshell `( ... )` AND replace `exit 1` with `_rejected=1; break`, checking `[ "$_rejected" -eq 0 ]` at subshell end. |
| **R2** | **CRITICAL** | `iter1_explorer_2` `parse_uint` (`handoff.md:93-103`) | Stripping leading zeros (`_raw="${_raw#"${_raw%%[!0]*}"}"`) turns `"0"` and `"000"` into `""`, then `if [ -z "$_raw" ]` returns the **fallback `$2`** instead of `"0"`! Breaks `SILO_SUP_BASE_BACKOFF=0` (returns `1`), `SILO_BOOT_SETTLE_SECS=0` (returns `5`), and `SILO_SUP_MAX_RESTARTS=0` (returns `5`)! | Check `if [ -z "$_pu_raw" ]; then printf '%s' "$2"; return 0; fi` **BEFORE** stripping leading zeros; if empty *after* stripping zeros, print `"0"`. |
| **R3** | **CRITICAL** | `iter1_explorer_2` `STATE_DIR="${SILO_STATE_DIR:-/data/adb/silo}"` & `SILO_LIVE_MODDIR="${SILO_LIVE_MODDIR:-/data/adb/modules/silo}"` (`handoff.md:302,698-699`) | **Cross-boundary E2E regex breakage (empirically proven)**: `e2e_explorer_module_ci/handoff.md:133` rewrites `/data/adb` using `(?<![A-Za-z0-9_./-])(/data/adb)`. Because `-` immediately precedes `/data/adb` in `:-/data/adb`, the regex **fails to match**, causing E2E sandbox tests to hit real `/data/adb/silo` and fail with `Permission denied`! | NEVER put `/data/adb` immediately after `:-`. Always assign the literal on its own quoted line (`STATE_DIR="/data/adb/silo"; [ -n "${SILO_STATE_DIR:-}" ] && STATE_DIR="$SILO_STATE_DIR"`). Works 100% with BOTH `SILO_*` env overrides AND E2E regex rewriting! |
| **R4** | **HIGH** | `iter1_explorer_2` `is_silod_pid` (`handoff.md:174-178`) & `is_supervisor_pid` (`handoff.md:195-205`) | Unconditional `elif [ "$_idx" -le 2 ]` matching `*/silod` causes **false positives** on innocent processes (`cat .../bin/silod`, `sha256sum .../bin/silod`, `ls -l .../bin/silod`, `sh -c "... .../bin/silod"`), and `is_supervisor_pid` falsely matches `grep -- --supervisor .../service.sh`. Also `exec -a silod /usr/bin/sleep` falsely matched `argv[0]`. | Gate `argv[1..2]` `*/silod` matching STRICTLY on `_exe` (or `argv[0]` when `readlink` unavailable) being a known script interpreter AND `argv[1] != "-c"`. Reject spoofed `exec -a silod /usr/bin/sleep` when `_exe` is readable and neither `*/silod*` nor interpreter (allowing exact `argv[0] == "$SILOD"` for symlinked ELF). Gate `is_supervisor_pid` on `argv[0]` being a shell or `service.sh`. |
| **R5** | **HIGH** | `iter1_explorer_2` socket clobber & orphan daemon (`handoff.md:524, 557`) | If `silod.pid` is missing/corrupted/stale while a live orphan `silod` owns `$SOCK_PATH` and holds `flock(/data/adb/silo/silod.lock)`, blindly calling `rm -f "$SOCK_PATH"` **destroys the active socket**, then the new `silod` fails `flock` and crash-loops — bricking Silo until reboot! | Add (1) fast pure-shell `/proc/[0-9]*/comm` (`~11ms`, 0 forks for non-`silod`) orphan discovery scoped strictly to THIS `$SOCK_PATH`/`$SILOD` (`is_silod_for_instance`), AND (2) `safe_cleanup_socket` probing `"$SILOD" --socket "$SOCK_PATH" --status` before unlinking (extracts `"pid"`, stops verified orphan, refuses `rm -f` if an unstoppable healthy daemon owns `$SOCK_PATH`). |
| **R6** | **HIGH** | `iter1_explorer_2` `service.sh` boot-waiter vs `uninstall.sh` (`handoff.md:491-495, 595-607`) | Two races: (1) `service.sh` executes `mkdir -p "$STATE_DIR"` **before** `[ -x "$SILOD" ] || exit 0` and never checks `$MODDIR/remove`, so a boot-waiting `service.sh` waking up after uninstall **re-creates `/data/adb/silo`** (or relaunches `silod`!). (2) `uninstall.sh` has no mechanism to cancel a sleeping boot-waiting `service.sh`. | (1) In `service.sh`, check `[ ! -d "$MODDIR" ] || [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ] || [ ! -x "$SILOD" ]` at entry, inside boot-wait loop, after settle sleep, and **BEFORE** `mkdir -p "$STATE_DIR"` (and check `remove` in `--supervisor`). (2) Record boot-waiter `$$` in `$STATE_DIR/service_boot.pid` (when `$STATE_DIR` exists) and stop verified boot-waiter in `uninstall.sh` BEFORE `stop_supervisor`. |
| **R7** | **HIGH** | `iter1_explorer_2` supervisor Case A wait vs `stop_supervisor` (`handoff.md:233, 379`) | On `SIGTERM` to supervisor while `silod` is hung, supervisor waits `_w -lt 40` ticks (`4.0s` + ~1s fork overhead on ARM64 = **~5s**), racing `stop_supervisor`'s 50-tick (5s) `SIGKILL` timeout AND causing `stop_silod` to wait **another 5s** (~9–10s total!). | In Case A (`STOP_REQUESTED != 0`), forward `SIGTERM` to verified `CHILD_PID`; if `[ -f "$STOP_FLAG" ]` (orchestrated teardown — `stop_silod` runs next!), wait at most 5 ticks (~0.5s) to reap quick exits and exit cleanly leaving `PID_FILE` intact for `stop_silod`'s 5s wait + `SIGKILL`. Only wait 30 ticks (~3s) on standalone manual `kill -TERM $SUP_PID` (no `$STOP_FLAG`). |
| **R8** | **MEDIUM** | `iter1_explorer_2` trap variable clobbering, `MODDIR` at `/`, `uninstall.sh` `rm -rf` guard, `action.sh` fallback `HUP` | (1) `on_stop_signal` calls `is_silod_pid` asynchronously without `local` — shared `_`-prefixed vars risk clobbering if trap fires mid-helper. (2) `MODDIR="${0%/*}"` produces `""` if `$0="/service.sh"`. (3) `rm -rf "$STATE_DIR"` lacks guard against empty/root `SILO_STATE_DIR`. (4) `action.sh` fallback direct-spawn lacks `HUP` immunity. | (1) Give every helper distinct variable prefixes (`_isp_*`, `_isup_*`, `_pu_*`, `_esp_*`, `_ifi_*`, `_sbp_*`) + subshell `( ... )` pipelines. (2) `[ -n "$MODDIR" ] || MODDIR="/"`. (3) Guard `rm -rf -- "$STATE_DIR"` against `""|"/"|"/data"|"/data/adb"|"/system"|"/vendor"`. (4) Wrap `action.sh` fallback spawn in `( trap '' HUP; exec "$SILOD" ... ) </dev/null >/dev/null 2>&1 &`. |
| **R9** | **LOW** | `iter1_explorer_2` `parse_uint` / `is_*_pid` octal check, `kill --`, `mksh`/`ksh93` signal codes | Empirically verified on devbox `dash`: `kill -0 -- "$PID"` FAILS (`Illegal number: --`). Rejecting `0*` in `case "$_pid" in ''|0*|*[!0-9]*)` removes octal ambiguity completely without `[ "$_pid" -gt 0 ]`. | NEVER pass `--` to `kill`; validate `''|0*|*[!0-9]*` + `${#_pid} -le 9`; accept `0|130|143|258|271` as clean/explicit stops in supervisor. |

---

## 1. Line-by-Line Audit of Existing Repository Files (`CB-07` / `D-13`, `CB-19`, `CB-18`, `CB-28`)

### 1.1 Exact Current PID, Signal, Wait & Socket-Unlink Hazards

| File & Exact Lines | Existing Code | Verified Defect(s) |
|---|---|---|
| `module/service.sh:12` | `MODDIR="${0%/*}"` | Fails when `$0` has no `/` (e.g. `sh service.sh`); fails if `$0="/service.sh"` (`""`). |
| `module/service.sh:21-28` | `while [ "$(getprop sys.boot_completed ...)" != "1" ] ... -ge 180` | 180s too short for Android 15 ART dexopt post-OTA boot (`CB-18`, raise to 300s); does not strip `\r\n\t `; does not check `$MODDIR/disable` or `$MODDIR/remove`. |
| `module/service.sh:33-36` | `mkdir -p "$STATE_DIR"; chmod 0700 "$STATE_DIR"; [ -x "$SILOD" ] \|\| exit 0` | Creates `$STATE_DIR` **before** checking `[ -x "$SILOD" ]` (and without checking `$MODDIR/disable`/`$MODDIR/remove`), risking recreating `/data/adb/silo` after uninstall! |
| `module/service.sh:39-43, 54` | `RAW_CNT="$(cat "$BOOT_MARKER" \| tr -cd '0-9')"` → `echo $((FAIL_COUNT + 1))` | Leading zeros (`"08"`, `"09"`) crash POSIX `dash`/`mksh` arithmetic (`Illegal number: 08`); unbounded digit string overflows 64-bit integer. |
| `module/service.sh:46-51` | `"$SILOD" --emergency-flush >/dev/null 2>&1 \|\| true` | Missing `--config "$CFG_PATH"` (needed by `daemon/src/main.rs:1256` to `pm unsuspend` DeepStop entries); does not stop an already-running supervisor/`silod`. |
| **`module/service.sh:58-66`** | `OLD_PID="$(cat "$PID_FILE" \| tr -cd '0-9')"` → `kill -TERM "$OLD_PID"` → `sleep 1` → `rm -f "$SOCK_PATH"` | **CRITICAL (`CB-07`/`D-13`)**: (1) `/data/adb/silo/silod.pid` survives reboot; `OLD_PID` belongs to a random Android service -> **kills unrelated process on boot!** (2) Fixed `sleep 1` does not wait for old `silod` to finish `thaw_all()` & socket unlink -> old `silod` (`daemon/src/ipc.rs:149`) unlinks NEW `silod`'s socket! (3) Blind `rm -f "$SOCK_PATH"` clobbers socket of an active orphan `silod` if `silod.pid` was missing/stale! |
| `module/service.sh:68-75` | Bare `"$SILOD" ... &` + `echo "$!" > "$PID_FILE"` + `chmod 0600` | No crash/LMKD-restart supervisor (`CB-18`); no `setsid`/`HUP` protection; brief `0644` window on `PID_FILE` (missing `umask 077` + atomic write). |
| **`module/action.sh:24-44`** | `[ -S "$SOCK_PATH" ] && --status` else `rm -f "$BOOT_MARKER" "$SOCK_PATH"` + bare `"$SILOD" ... &` + `sleep 2` | **CRITICAL (`CB-07`/`CB-18`)**: (1) Never removes `$MODDIR/disable`! (2) Never stops existing supervisor or hung `silod` before unlinking `$SOCK_PATH` and spawning duplicate daemon! (3) Loses crash-restart supervision on recovery; fixed `sleep 2` instead of bounded readiness poll. |
| **`module/uninstall.sh:11-26`** | Blind `kill -TERM "$PID"` + `sleep 2` + `kill -KILL "$PID"` → `"$SILOD" --emergency-flush` → `rm -rf "$STATE_DIR"` | **CRITICAL (`CB-07`/`CB-19`)**: (1) PID-reuse `SIGKILL` of unrelated Android process! (2) Does not stop boot-waiting `service.sh` or supervisor before stopping `silod` (supervisor would respawn on `SIGKILL`!). (3) Calls `--emergency-flush` without `--config`, never calls `--uninstall-cleanup --config "$CFG_PATH"` -> leaks AppOps (`ignore`/`deny`) and `user-fixed`/`revoked-compat` flags forever! |
| `module/customize.sh:9-29` | `ARCH="$(getprop ro.product.cpu.abi)"`, no `$MODDIR/disable` cleanup | Overwrites KernelSU's `$ARCH`; lacks `abilist64` / `$ARCH` fallback (`CB-28`); never deletes `/data/adb/modules/silo/disable` or `$MODPATH/disable` (`CB-18`). |
| `module/module.prop:1-7` | `id=silo`, `version=v3.0.0`, `versionCode=30000`, `author=terenzy` | Verified consistent with `daemon/Cargo.toml` and `app/app/build.gradle.kts`. **Keep untouched.** |

### 1.2 Cross-Reference with `daemon/src/main.rs` & `daemon/src/ipc.rs` (M1 Contract Coordination)

Verified directly in `daemon/src/main.rs:58-76, 1007-1308`, `daemon/src/ipc.rs:90-270`, `daemon/src/protocol.rs:640-645`, and `PROJECT.md § Interface Contracts #4`:
1. **Signal Handling & Shutdown Sequence**:
   - `SIGTERM`/`SIGINT` sets `GLOBAL_STOP` (`main.rs:60-76`). Signal thread wakes within `100ms` (`main.rs:1025`), `run_ipc_server` exits `accept` loop within `40ms` (`ipc.rs:141`), unlinks `socket_path` (`ipc.rs:149` — M1 adds `(st_dev, st_ino)` ownership check before unlinking!), calls `st.freezer.thaw_all()` (`main.rs:1183`), releases `flock` FD on exit, and exits `0` (`main.rs:1308`).
   - Because `thaw_all()` executes `pm unsuspend` subprocesses (each up to 2.5s timeout), clean shutdown can legitimately take **1–4 seconds** — confirming why a **5-second wait loop** (polling exit, not fixed `sleep 1`) is essential BEFORE spawning a new daemon!
2. **Single-Instance `flock` (`/data/adb/silo/silod.lock`)**:
   - M1 acquires `flock(LOCK_EX | LOCK_NB)` on `/data/adb/silo/silod.lock` for `silod`'s lifetime and exits non-zero immediately if another `silod` holds the lock.
   - **Critical Shell Coordination Invariant**: If `service.sh` or `action.sh` fails to wait until the old `silod` exits (or fails to stop an orphan `silod` when `silod.pid` is missing/stale), the new `silod` will fail `flock` with exit `1`, triggering supervisor retries! Waiting until `is_silod_pid` returns false guarantees the kernel has closed the old `silod`'s file descriptors and **released `silod.lock`** before the new daemon starts.
   - Because `silod` also guards `unlink(socket_path)` with `(st_dev, st_ino)` (M1) AND our shell scripts wait for full exit + never clobber an active socket (`safe_cleanup_socket`), both layers reinforce each other (defense in depth).
3. **Live PID Discovery via `--status`**:
   - `DaemonStatus` (`daemon/src/protocol.rs:640-642`) includes `"pid": <u32>`.
   - `silod --socket "$SOCK_PATH" --status` returns immediate `ECONNREFUSED` (`<1ms`, non-zero exit) on a stale socket file, and returns `{"id":1,"ok":true,"data":{...,"pid":<N>,...}}` (`<5ms` post-M1) on a live healthy daemon.
4. **CLI Flags Contract**:
   - Daemon mode: `--socket <PATH> --config <PATH> --boot-marker <PATH>` (all other modes are short-lived clients/utilities: `--bridge`, `--status`, `--once <JSON>`, `--version`, `--emergency-flush`, `--uninstall-cleanup`).
   - **Pre-M1 Binary Caution**: Pre-M1 `daemon/src/main.rs:1227` has `_ => {}`, so an unpatched pre-M1 `silod` ignores unknown `--uninstall-cleanup` and enters foreground daemon mode. M1 implements `--uninstall-cleanup [--config PATH]` (and rejects unknown flags). In M4 standalone verification prior to M1 merge, ALWAYS test `uninstall.sh` with a native-ELF stub implementing `--uninstall-cleanup` (and returning exit `2` in the fallback test).

---

## 2. Hardened POSIX-sh Primitives & Verification Proof (`CB-07` / `D-13`)

> **Hard Compatibility Rule (Verified Against `e2e_explorer_module_ci/handoff.md:133`)**:
> Write every `/data/adb/...` default path on its own double-quoted assignment line — **NEVER** inside `${VAR:-/data/adb/...}`!
> ```sh
> STATE_DIR="/data/adb/silo"
> [ -n "${SILO_STATE_DIR:-}" ] && STATE_DIR="$SILO_STATE_DIR"
> ```
> Empirically verified: `re.sub(r'(?<![A-Za-z0-9_./-])(/data/adb)(?=/|["\'\s;]|$)', ...)` skips `:-/data/adb` because `-` is excluded in the negative lookbehind, whereas `STATE_DIR="/data/adb/silo"` rewrites cleanly AND still honors `SILO_STATE_DIR`!

### 2.1 Canonical `MODDIR` Resolution (Handles No `/`, Spaces, Symlinks, Root `/`)
```sh
case "$0" in
  */*)
    MODDIR="${0%/*}"
    [ -n "$MODDIR" ] || MODDIR="/"
    ;;
  *)
    MODDIR="."
    ;;
esac
MODDIR="$(CDPATH= cd -- "$MODDIR" 2>/dev/null && (pwd -P 2>/dev/null || pwd))" || exit 1
```

### 2.2 Fixed `parse_uint` (Fixes Explorer 2's `"0"` Bug & Octal/Overflow Hazards)
```sh
# Sanitizes $1 to a base-10 non-negative integer (<= 9 digits); returns $2 if no digits or overflow.
parse_uint() {
  _pu_raw="$(printf '%s' "$1" | tr -cd '0-9')"
  if [ -z "$_pu_raw" ]; then
    printf '%s' "$2"
    return 0
  fi
  _pu_raw="${_pu_raw#"${_pu_raw%%[!0]*}"}"
  if [ -z "$_pu_raw" ]; then
    printf '%s' "0"
  elif [ "${#_pu_raw}" -gt 9 ]; then
    printf '%s' "$2"
  else
    printf '%s' "$_pu_raw"
  fi
}
```
Empirically verified on `dash`:
- `""` → fallback; `"abc"` → fallback; `"0"` → `"0"`; `"000"` → `"0"`; `"08"` → `"8"`; `"0042"` → `"42"`; `"999999999999"` → fallback; `"00000000001"` → `"1"`.

### 2.3 Hardened `is_silod_pid`, `is_silod_for_instance`, `is_supervisor_pid`, `is_boot_waiter_pid`

**Portability & Security Properties (ALL empirically verified on `dash` + Linux `/proc`)**:
- Rejects `''|0*|*[!0-9]*` and `${#_pid} -gt 9` (no octal ambiguity, no negative/zero PIDs, no `--` needed for `kill`, since `dash` rejects `kill -0 --`).
- Uses `readlink "/proc/$_pid/exe"` **WITHOUT `-f`** (preserves ` (deleted)` suffix cleanly when binary is upgraded while running; gracefully falls back to `cmdline` when `readlink` is absent or `exe` unreadable).
- Uses POSIX `tr '\000' '\n'` (verified portable across GNU, Android Toybox, and KernelSU BusyBox `tr`).
- Pipeline consumer runs in explicit subshell `( ... )` with `_rejected=1; break` (ZERO `exit 1` in `{ ... }` hazard on `lastpipe`/Korn shells; ZERO variable leakage!).
- Function-unique variable prefixes (`_isp_*`, `_ifi_*`, `_isup_*`, `_ibw_*`) eliminate signal-trap variable clobbering without non-POSIX `local`.
- Zombie processes have 0-byte `/proc/$PID/cmdline` → `_has_cmd` stays `0` → returns `1` immediately (no `kill -0 $ZOMBIE` spin bug).

```sh
is_silod_pid() {
  _isp_pid="$1"
  case "$_isp_pid" in
    ''|0*|*[!0-9]*) return 1 ;;
  esac
  [ "${#_isp_pid}" -le 9 ] || return 1
  kill -0 "$_isp_pid" 2>/dev/null || return 1
  [ -r "/proc/$_isp_pid/cmdline" ] || return 1

  _isp_exe=""
  if [ -L "/proc/$_isp_pid/exe" ]; then
    _isp_exe="$(readlink "/proc/$_isp_pid/exe" 2>/dev/null || true)"
  fi

  _isp_exe_mode="other"
  case "$_isp_exe" in
    "")
      _isp_exe_mode="unknown"
      ;;
    */silod|*/silod" (deleted)"|silod)
      _isp_exe_mode="silod"
      ;;
    */sh|*/dash|*/ash|*/mksh|*/bash|*/busybox|*/toybox|*/python|*/python[0-9]*|*/env)
      _isp_exe_mode="interp"
      ;;
  esac

  tr '\000' '\n' < "/proc/$_isp_pid/cmdline" 2>/dev/null | (
    _idx=0
    _has_cmd=0
    _matched=0
    _rejected=0
    _interp_ok=0
    _saw_dash_c=0
    _skip_val=0

    [ "$_isp_exe_mode" = "silod" ] && _matched=1
    [ "$_isp_exe_mode" = "interp" ] && _interp_ok=1

    while IFS= read -r _tok || [ -n "$_tok" ]; do
      _has_cmd=1
      if [ "$_skip_val" -eq 1 ]; then
        _skip_val=0
        _idx=$((_idx + 1))
        continue
      fi
      case "$_tok" in
        --socket|--config|--boot-marker)
          _skip_val=1
          ;;
        --bridge|--status|--once|--version|--emergency-flush|--uninstall-cleanup|--help|-h|-V)
          _rejected=1
          break
          ;;
      esac
      if [ "$_idx" -eq 0 ]; then
        if [ "$_isp_exe_mode" = "unknown" ]; then
          case "$_tok" in
            */silod|silod) _matched=1 ;;
            sh|*/sh|dash|*/dash|ash|*/ash|mksh|*/mksh|bash|*/bash|busybox|*/busybox|toybox|*/toybox|python|python[0-9]*|*/python|*/python[0-9]*|env|*/env)
              _interp_ok=1
              ;;
          esac
        elif [ "$_isp_exe_mode" = "other" ]; then
          # Allow symlinked native ELF stub ONLY when invoked directly as exact $SILOD
          [ -n "${SILOD:-}" ] && [ "$_tok" = "$SILOD" ] && _matched=1
        fi
      elif [ "$_interp_ok" -eq 1 ] && [ "$_idx" -le 2 ]; then
        if [ "$_idx" -eq 1 ] && [ "$_tok" = "-c" ]; then
          _saw_dash_c=1
        elif [ "$_saw_dash_c" -eq 0 ]; then
          case "$_tok" in
            */silod) _matched=1 ;;
          esac
        fi
      fi
      _idx=$((_idx + 1))
    done
    [ "$_has_cmd" -eq 1 ] && [ "$_matched" -eq 1 ] && [ "$_rejected" -eq 0 ]
  )
}

# Scopes orphan detection STRICTLY to THIS instance's $SOCK_PATH / $SILOD (safe for parallel E2E sandboxes)
is_silod_for_instance() {
  _ifi_pid="$1"
  is_silod_pid "$_ifi_pid" || return 1
  tr '\000' '\n' < "/proc/$_ifi_pid/cmdline" 2>/dev/null | (
    _saw_sock_flag=0
    _expect_sock=0
    _sock_matched=0
    _bin_matched=0
    _idx=0
    while IFS= read -r _tok || [ -n "$_tok" ]; do
      if [ "$_expect_sock" -eq 1 ]; then
        _expect_sock=0
        [ "$_tok" = "$SOCK_PATH" ] && _sock_matched=1
      elif [ "$_tok" = "--socket" ]; then
        _saw_sock_flag=1
        _expect_sock=1
      elif [ "$_idx" -le 2 ] && [ "$_tok" = "$SILOD" ]; then
        _bin_matched=1
      fi
      _idx=$((_idx + 1))
    done
    if [ "$_saw_sock_flag" -eq 1 ]; then
      [ "$_sock_matched" -eq 1 ]
    else
      [ "$_bin_matched" -eq 1 ]
    fi
  )
}

is_supervisor_pid() {
  _isup_pid="$1"
  case "$_isup_pid" in
    ''|0*|*[!0-9]*) return 1 ;;
  esac
  [ "${#_isup_pid}" -le 9 ] || return 1
  kill -0 "$_isup_pid" 2>/dev/null || return 1
  [ -r "/proc/$_isup_pid/cmdline" ] || return 1

  tr '\000' '\n' < "/proc/$_isup_pid/cmdline" 2>/dev/null | (
    _idx=0
    _ok_argv0=0
    _has_script=0
    _has_flag=0
    while IFS= read -r _tok || [ -n "$_tok" ]; do
      if [ "$_idx" -eq 0 ]; then
        case "$_tok" in
          sh|*/sh|dash|*/dash|ash|*/ash|mksh|*/mksh|bash|*/bash|busybox|*/busybox|toybox|*/toybox|*/service.sh|service.sh)
            _ok_argv0=1
            ;;
        esac
      fi
      case "$_tok" in
        */service.sh|service.sh) _has_script=1 ;;
        --supervisor) _has_flag=1 ;;
      esac
      _idx=$((_idx + 1))
    done
    [ "$_ok_argv0" -eq 1 ] && [ "$_has_script" -eq 1 ] && [ "$_has_flag" -eq 1 ]
  )
}

is_boot_waiter_pid() {
  _ibw_pid="$1"
  case "$_ibw_pid" in
    ''|0*|*[!0-9]*) return 1 ;;
  esac
  [ "${#_ibw_pid}" -le 9 ] || return 1
  [ "$_ibw_pid" != "$$" ] || return 1
  kill -0 "$_ibw_pid" 2>/dev/null || return 1
  [ -r "/proc/$_ibw_pid/cmdline" ] || return 1

  tr '\000' '\n' < "/proc/$_ibw_pid/cmdline" 2>/dev/null | (
    _idx=0
    _ok_argv0=0
    _has_script=0
    _has_sup=0
    while IFS= read -r _tok || [ -n "$_tok" ]; do
      if [ "$_idx" -eq 0 ]; then
        case "$_tok" in
          sh|*/sh|dash|*/dash|ash|*/ash|mksh|*/mksh|bash|*/bash|busybox|*/busybox|toybox|*/toybox|*/service.sh|service.sh)
            _ok_argv0=1
            ;;
        esac
      fi
      case "$_tok" in
        */service.sh|service.sh) _has_script=1 ;;
        --supervisor) _has_sup=1 ;;
      esac
      _idx=$((_idx + 1))
    done
    [ "$_ok_argv0" -eq 1 ] && [ "$_has_script" -eq 1 ] && [ "$_has_sup" -eq 0 ]
  )
}
```

**Empirically Verified Process Matrix (24/24 Cases Passed on `dash` + Linux `/proc`)**:
- **MATCHED (`0`)**: real native ELF `silod`, deleted/upgraded ELF `.../silod (deleted)`, symlinked ELF at exact `$SILOD`, `#!/bin/sh` script at `.../bin/silod`, `#!/usr/bin/env python3` script at `.../bin/silod`, missing `readlink` fallback, real `sh .../service.sh --supervisor`.
- **REJECTED (`1`)**: ALL 6 CLI flags (`--bridge`, `--status`, `--once`, `--version`, `--emergency-flush`, `--uninstall-cleanup`), `/usr/bin/sleep 30`, `grep silod`, `cat .../silod`, `sh -c "... .../silod"`, spoofed `exec -a silod /usr/bin/sleep`, real zombie `silod`, empty/`0`/`08`/`abc`/`99999999`/`-1`/10-digit PIDs, boot-waiting `service.sh` (by `is_supervisor_pid`), `grep -- --supervisor .../service.sh`.

### 2.4 Verified Stop Helpers, Orphan Sweep & Safe Socket Cleanup

```sh
sleep_tenth() {
  if sleep 0.1 2>/dev/null; then
    return 0
  fi
  sleep 1
  return 1
}

stop_silod_pid() {
  _ssp_pid="$1"
  [ -n "$_ssp_pid" ] || return 0
  is_silod_pid "$_ssp_pid" || return 0
  kill -TERM "$_ssp_pid" 2>/dev/null || true
  _ssp_i=0
  while [ "$_ssp_i" -lt 50 ]; do
    is_silod_pid "$_ssp_pid" || break
    if sleep_tenth; then _ssp_i=$((_ssp_i + 1)); else _ssp_i=$((_ssp_i + 10)); fi
  done
  # Re-verify PID identity immediately before SIGKILL to close TOCTOU PID-reuse window
  if is_silod_pid "$_ssp_pid"; then
    kill -KILL "$_ssp_pid" 2>/dev/null || true
    _ssp_i=0
    while [ "$_ssp_i" -lt 10 ]; do
      is_silod_pid "$_ssp_pid" || break
      if sleep_tenth; then _ssp_i=$((_ssp_i + 1)); else _ssp_i=$((_ssp_i + 10)); fi
    done
  fi
}

stop_silod() {
  [ -f "$PID_FILE" ] || return 0
  _ss_pid="$(parse_uint "$(cat "$PID_FILE" 2>/dev/null || true)" "")"
  stop_silod_pid "$_ss_pid"
  rm -f "$PID_FILE"
}

stop_supervisor() {
  [ -f "$SUP_PID_FILE" ] || return 0
  _sup_pid="$(parse_uint "$(cat "$SUP_PID_FILE" 2>/dev/null || true)" "")"
  if [ -n "$_sup_pid" ] && [ "$_sup_pid" != "$$" ] && is_supervisor_pid "$_sup_pid"; then
    kill -TERM "$_sup_pid" 2>/dev/null || true
    _sup_i=0
    while [ "$_sup_i" -lt 50 ]; do
      is_supervisor_pid "$_sup_pid" || break
      if sleep_tenth; then _sup_i=$((_sup_i + 1)); else _sup_i=$((_sup_i + 10)); fi
    done
    if is_supervisor_pid "$_sup_pid"; then
      kill -KILL "$_sup_pid" 2>/dev/null || true
      _sup_i=0
      while [ "$_sup_i" -lt 10 ]; do
        is_supervisor_pid "$_sup_pid" || break
        if sleep_tenth; then _sup_i=$((_sup_i + 1)); else _sup_i=$((_sup_i + 10)); fi
      done
    fi
  fi
  rm -f "$SUP_PID_FILE"
}

stop_boot_waiter() {
  [ -f "$BOOT_PID_FILE" ] || return 0
  _sbw_pid="$(parse_uint "$(cat "$BOOT_PID_FILE" 2>/dev/null || true)" "")"
  if [ -n "$_sbw_pid" ] && is_boot_waiter_pid "$_sbw_pid"; then
    kill -TERM "$_sbw_pid" 2>/dev/null || true
    _sbw_i=0
    while [ "$_sbw_i" -lt 20 ]; do
      is_boot_waiter_pid "$_sbw_pid" || break
      if sleep_tenth; then _sbw_i=$((_sbw_i + 1)); else _sbw_i=$((_sbw_i + 10)); fi
    done
    if is_boot_waiter_pid "$_sbw_pid"; then
      kill -KILL "$_sbw_pid" 2>/dev/null || true
    fi
  fi
  rm -f "$BOOT_PID_FILE"
}

# Fast pure-shell /proc comm scan (~11ms empirically, 0 forks for non-silod PIDs)
stop_orphan_silod() {
  for _sos_c in /proc/[0-9]*/comm; do
    [ -r "$_sos_c" ] || continue
    IFS= read -r _sos_nm < "$_sos_c" 2>/dev/null || continue
    [ "$_sos_nm" = "silod" ] || continue
    _sos_pid="${_sos_c#/proc/}"
    _sos_pid="${_sos_pid%/comm}"
    if is_silod_for_instance "$_sos_pid"; then
      stop_silod_pid "$_sos_pid"
    fi
  done
}

# Pure-POSIX JSON "pid" extractor (zero sed/awk/jq forks)
extract_status_pid() {
  _esp_json="$1"
  case "$_esp_json" in
    *'"pid":'*)
      _esp_rest="${_esp_json#*\"pid\":}"
      _esp_rest="${_esp_rest#"${_esp_rest%%[! ]*}"}"
      _esp_num="${_esp_rest%%[!0-9]*}"
      parse_uint "$_esp_num" ""
      ;;
  esac
}

# Never blindly unlink $SOCK_PATH while an active healthy daemon owns it!
# Returns 0 if stale socket removed/absent (safe to spawn); returns 1 ONLY if an
# unstoppable live daemon is still responding on $SOCK_PATH.
safe_cleanup_socket() {
  if [ -S "$SOCK_PATH" ] && [ -x "$SILOD" ]; then
    _scs_json=""
    if _scs_json="$("$SILOD" --socket "$SOCK_PATH" --status 2>/dev/null)"; then
      _scs_pid="$(extract_status_pid "$_scs_json")"
      if [ -n "$_scs_pid" ] && is_silod_pid "$_scs_pid"; then
        stop_silod_pid "$_scs_pid"
      fi
      if [ -S "$SOCK_PATH" ] && "$SILOD" --socket "$SOCK_PATH" --status >/dev/null 2>&1; then
        return 1
      fi
    fi
  fi
  rm -f "$SOCK_PATH"
  return 0
}
```

---

## 3. Exact Refinements to Each Module Script (Reconciled With Explorers 2 & 3)

> **Module File Whitelist Lock (Harmonized With `iter1_explorer_3` & E2E `T1-F12-07`)**:
> Keep `module/` to the EXACT 5 files: `module.prop`, `customize.sh`, `service.sh`, `action.sh`, `uninstall.sh`. Do **NOT** introduce any extra shared `.sh` file in `module/` (E2E `packaging_harness.py` strictly asserts the ZIP entry set is `{"module.prop", "customize.sh", "service.sh", "action.sh", "uninstall.sh", "bin/silod"}`).

### 3.1 `module/service.sh` — Deltas on Top of `iter1_explorer_2/handoff.md § 2.2`
Apply all Explorer 2 designs (`--supervisor` self-invocation, 300s boot wait, sliding-window 5-in-60s bounded restart with `1,2,4,8,8s` backoff, `umask 077`, atomic `0600` PID writes, Dead-Man Switch with `--emergency-flush --config "$CFG_PATH"`), **WITH these exact refinements**:
1. **E2E-safe `STATE_DIR` assignment**:
   ```sh
   STATE_DIR="/data/adb/silo"
   [ -n "${SILO_STATE_DIR:-}" ] && STATE_DIR="$SILO_STATE_DIR"
   ```
   Add `BOOT_PID_FILE="$STATE_DIR/service_boot.pid"`.
2. **Use hardened `MODDIR` (§2.1), fixed `parse_uint` (§2.2), hardened `is_silod_pid`/`is_silod_for_instance`/`is_supervisor_pid` (§2.3), and `stop_supervisor`/`stop_silod`/`stop_orphan_silod`/`safe_cleanup_socket` (§2.4)**.
3. **In `--supervisor` mode**:
   - Check `[ ! -d "$MODDIR" ] || [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ] || [ -f "$STOP_FLAG" ] || [ ! -d "$STATE_DIR" ] || [ ! -x "$SILOD" ]` both at entry and in the loop.
   - In **Case A (`STOP_REQUESTED != 0`)**:
     ```sh
     if [ "$STOP_REQUESTED" -ne 0 ]; then
       if [ -n "$CHILD_PID" ] && is_silod_pid "$CHILD_PID"; then
         kill -TERM "$CHILD_PID" 2>/dev/null || true
         # Short reap window if STOP_FLAG is asserted (caller's stop_silod owns the full 5s + SIGKILL wait!)
         _w_max=30
         [ -f "$STOP_FLAG" ] && _w_max=5
         _w=0
         while [ "$_w" -lt "$_w_max" ]; do
           is_silod_pid "$CHILD_PID" || break
           if sleep_tenth; then _w=$((_w + 1)); else _w=$((_w + 10)); fi
         done
       fi
       if [ -n "$CHILD_PID" ] && ! is_silod_pid "$CHILD_PID"; then
         wait "$CHILD_PID" 2>/dev/null || true
       fi
       break
     fi
     ```
   - In **Case B (clean/explicit exit)**: match `0|130|143|258|271) break ;;` (covers `dash`, `mksh`, BusyBox `ash`, and `ksh93`, while `SIGKILL=137`, `SIGABRT=134`, `SIGSEGV=139`, `rc=1` all restart cleanly within 5-in-60s).
4. **In Mode 2 (normal boot entry)**:
   - Check `[ ! -d "$MODDIR" ] || [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ] || [ ! -x "$SILOD" ] && exit 0` at entry!
   - If `[ -d "$STATE_DIR" ]`, write `$$` to `$BOOT_PID_FILE` (`write_pid_file "$BOOT_PID_FILE" "$$"`) before the boot-wait loop, and clean up `$BOOT_PID_FILE` (only if it still holds `$$`) on exit/handoff.
   - Inside boot-wait loop, re-check `[ ! -d "$MODDIR" ] || [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ] || [ ! -x "$SILOD" ]` on every iteration (and after `BOOT_SETTLE_SECS`).
   - **Check `[ -x "$SILOD" ] || exit 0` BEFORE `mkdir -p "$STATE_DIR"`** so an uninstalled module NEVER re-creates `/data/adb/silo`!
   - Parse `.boot_fail_count` via `tr -cd '0-9' < "$BOOT_MARKER" 2>/dev/null` + zero-strip + length `> 3` → `FAIL_COUNT=2` (handles leading blank lines and octal `"08"`).
   - Dead-Man Switch (`FAIL_COUNT -ge 2`): assert `$STOP_FLAG`, run `stop_supervisor`, `stop_silod`, `stop_orphan_silod`, run `"$SILOD" --emergency-flush --config "$CFG_PATH" >/dev/null 2>&1 || true`, `touch "$MODDIR/disable" 2>/dev/null || true`, `rm -f "$BOOT_MARKER" "$SOCK_PATH" "$PID_FILE" "$SUP_PID_FILE" "$BOOT_PID_FILE" "$STOP_FLAG"`, `exit 0`.
   - Before spawning supervisor:
     ```sh
     : > "$STOP_FLAG" 2>/dev/null || true
     stop_supervisor
     stop_silod
     stop_orphan_silod
     if ! safe_cleanup_socket; then
       rm -f "$STOP_FLAG"
       exit 0
     fi
     rm -f "$STOP_FLAG"
     ```
   - Clean `$BOOT_PID_FILE` (if `$$`), then launch `setsid "$SH_BIN" "$MODDIR/service.sh" --supervisor </dev/null >/dev/null 2>&1 &` (or without `setsid`), and wait up to `20` tenths (~2s, breaking as soon as `[ -s "$SUP_PID_FILE" ] && [ -s "$PID_FILE" ]`).

### 3.2 `module/action.sh` — Deltas on Top of `iter1_explorer_2/handoff.md § 2.3`
1. Use `umask 077`, hardened `MODDIR` (§2.1), E2E-safe two-line `STATE_DIR="/data/adb/silo"`, fixed `parse_uint` (§2.2), and hardened `is_*`/`stop_*`/`safe_cleanup_socket` (§2.3–2.4).
2. Preserve exact output markers checked by UI/tests: `Binary: $("$SILOD" --version ...)`, `[ERROR]` + `exit 1` if `[ ! -x "$SILOD" ]`, `[OK]` + `$STATUS_JSON` when healthy (do NOT kill/restart healthy daemon!).
3. In recovery branch (when `[ -S "$SOCK_PATH" ] && --status` failed):
   - `mkdir -p "$STATE_DIR"` + `chmod 0700 "$STATE_DIR"`.
   - `rm -f "$MODDIR/disable" "$BOOT_MARKER"` (`CB-18`).
   - `: > "$STOP_FLAG" 2>/dev/null || true` → `stop_supervisor` → `stop_silod` → `stop_orphan_silod` → `if ! safe_cleanup_socket; then rm -f "$STOP_FLAG"; echo "[OK] silod is running and responding on root-only Unix socket."; exit 0; fi` → `rm -f "$STOP_FLAG"`.
   - Launch detached `service.sh --supervisor` with `</dev/null >/dev/null 2>&1 &` (and `setsid` if present) so KernelSU Manager's Action UI stdout/stderr pipes hit EOF immediately!
   - Fallback branch (only if `$MODDIR/service.sh` missing): use `( trap '' HUP; exec "$SILOD" --socket "$SOCK_PATH" --config "$CFG_PATH" --boot-marker "$BOOT_MARKER" ) </dev/null >/dev/null 2>&1 &` + `write_pid_file "$PID_FILE" "$!"` (preserves exact `silod` PID in `$!` while adding `SIGHUP` immunity!).
   - Poll up to 5s (`_w -lt 25`, `sleep 0.2` with `sleep 1; _w=$((_w + 5))` fallback) checking `[ -S "$SOCK_PATH" ] && "$SILOD" --socket "$SOCK_PATH" --status >/dev/null 2>&1`.

### 3.3 `module/uninstall.sh` — Complete Edge-Case-Hardened Specification (`CB-07`, `CB-19`, `CB-28`)
Execute in **this exact order**:
1. `umask 077`, hardened `MODDIR` (§2.1), E2E-safe two-line `STATE_DIR="/data/adb/silo"`, `SILOD="$MODDIR/bin/silod"`, `SOCK_PATH="$STATE_DIR/silod.sock"`, `CFG_PATH="$STATE_DIR/config.json"`, `PID_FILE="$STATE_DIR/silod.pid"`, `SUP_PID_FILE="$STATE_DIR/supervisor.pid"`, `BOOT_PID_FILE="$STATE_DIR/service_boot.pid"`, `STOP_FLAG="$STATE_DIR/.stop_supervisor"`.
2. **Assert stop-flag** (blocks any supervisor respawn immediately):
   `[ -d "$STATE_DIR" ] && : > "$STOP_FLAG" 2>/dev/null || true`
3. **Stop any sleeping boot-waiting `service.sh`**:
   `stop_boot_waiter` (verifies `service.sh` without `--supervisor`, `SIGTERM` -> up to 2s -> verified `SIGKILL`).
4. **Stop supervisor FIRST** (so it cannot respawn `silod`):
   `stop_supervisor` (verifies `service.sh` + `--supervisor`, `SIGTERM` -> up to 5s -> verified `SIGKILL`).
5. **Stop `silod` cleanly** (verified PID, up to 5s wait + re-verified `SIGKILL`, plus orphan cleanup):
   - `stop_silod`
   - `stop_orphan_silod`
   - If `[ -S "$SOCK_PATH" ] && [ -x "$SILOD" ]`, probe `_u_json="$("$SILOD" --socket "$SOCK_PATH" --status 2>/dev/null || true)"`, extract `"pid"`, and call `stop_silod_pid` (catches even non-`comm=silod` mocks orphaned without `silod.pid`!).
6. **Execute full uninstall cleanup BEFORE deleting `$STATE_DIR`** (so `$CFG_PATH` is still readable for resetting Silo-applied AppOps & `user-fixed`/`revoked-compat` flags, AND iptables/ipset/cgroups are flushed even when `$CFG_PATH` does not exist!):
   ```sh
   if [ -x "$SILOD" ]; then
     "$SILOD" --uninstall-cleanup --config "$CFG_PATH" >/dev/null 2>&1 || \
       "$SILOD" --emergency-flush --config "$CFG_PATH" >/dev/null 2>&1 || true
   fi
   ```
   - Missing/non-executable `$SILOD`: skipped gracefully, proceeds to step 7.
   - Missing `$CFG_PATH`: still runs `--uninstall-cleanup` (flushes kernel rules/ipsets/cgroups; skips config-only steps).
   - Non-zero exit from `--uninstall-cleanup` (older binary / partial failure): cleanly falls back to `--emergency-flush --config "$CFG_PATH"`, and `|| true` guarantees step 7 ALWAYS runs.
7. **Safety-guarded complete removal of `$STATE_DIR`** (never leaves `/data/adb/silo` behind, never risks catastrophic `rm -rf /` on bad env var):
   ```sh
   case "$STATE_DIR" in
     ""|"/"|"/data"|"/data/adb"|"/system"|"/vendor")
       ;;
     *)
       rm -rf -- "$STATE_DIR" 2>/dev/null || rm -rf "$STATE_DIR" 2>/dev/null || true
       ;;
   esac
   exit 0
   ```

### 3.4 `module/customize.sh` — Delta on Top of `iter1_explorer_2/handoff.md § 2.5`
Explorer 2's `customize.sh` design (sourced-safe without `exit 0` / `set -e` / `umask`, `SILO_*`-prefixed vars preserving KSU's `$ARCH`, strict 3-tier ABI check rejecting x86_64/Houdini/arm32, `set_perm` when available, removing BOTH live `/data/adb/modules/silo/disable` and `$MODPATH/disable` + `.boot_fail_count` + `.stop_supervisor`) is verified solid — **with ONE mandatory E2E-regex fix**:
- Replace `iter1_explorer_2/handoff.md:698-699` (`:-/data/adb`) with:
  ```sh
  SILO_STATE_PATH="/data/adb/silo"
  [ -n "${SILO_STATE_DIR:-}" ] && SILO_STATE_PATH="$SILO_STATE_DIR"
  if [ -z "${SILO_LIVE_MODDIR:-}" ]; then
    SILO_LIVE_MODDIR="/data/adb/modules/silo"
  fi
  ```
- Also add `[ -n "$MODPATH" ] || MODPATH="/"` in the standalone `MODPATH` fallback (`handoff.md:637`) and remove stale `"$SILO_STATE_PATH/service_boot.pid"` alongside `.boot_fail_count` & `.stop_supervisor`.

### 3.5 `scripts/build_module.sh` & `.github/workflows/build.yml` — Verification of `iter1_explorer_3/handoff.md`
Cross-verified `iter1_explorer_3/handoff.md` against `PROJECT.md` and `e2e_explorer_module_ci/handoff.md`:
- **`scripts/build_module.sh`**: All 3 zero-mount defense layers (`system vendor product system_ext odm`), explicit 5-file whitelist (`module.prop customize.sh service.sh action.sh uninstall.sh`), `file -bL --` accepting both `statically linked` and `static-pie linked` ARM64 LSB executables while rejecting `dynamically linked|interpreter`/`.so`/`MSB`/non-ARM64/filename-spoofing, mode normalization (`0755` scripts/`bin`/`bin/silod`, `0644` `module.prop`), and temp cleanup are **100% ready for implementation as specified**.
- **`.github/workflows/build.yml`**: Top-level `permissions: contents: read`, job-scoped `permissions: contents: write` ONLY on `release`, ZERO `${{ ... }}` inside ANY `run:` block, `bash -n scripts/build_module.sh`, `unzip -Z1` 5-partition zero-mount gate, `module/module.prop` dynamic fallback version + strict tag regex, `GH_FLAGS=()` array + `gh release view` branch are **100% ready for implementation as specified**.
- **Important devbox testing reminder**: Always use `/usr/bin/python3` (has PyYAML 6.0.1) or `ruby -ryaml` for YAML AST checks — NOT `python3` in `PATH` (`/opt/pyenv/shims/python3` lacks PyYAML).

---

## 4. M4 Worker Execution Checklist (Concise Actionable Synthesis)

1. Edit ONLY M4-owned files: `module/service.sh`, `module/action.sh`, `module/uninstall.sh`, `module/customize.sh`, `scripts/build_module.sh`, `.github/workflows/build.yml`. Leave `module/module.prop`, `daemon/**`, `app/**`, `tests/**` untouched.
2. Apply `iter1_explorer_2/handoff.md` + **ALL 9 refinements (R1–R9)** from this handoff to `module/{service,action,uninstall,customize}.sh`.
3. Apply `iter1_explorer_3/handoff.md` cleanly to `scripts/build_module.sh` and `.github/workflows/build.yml`.
4. Run full sandbox verification (in `mktemp -d` with cleanup trap) covering:
   - `sh -n module/*.sh`, `dash -n module/*.sh`, `bash -n scripts/build_module.sh`
   - All Suites T1–T9 from `iter1_explorer_3/handoff.md` + Explorer 2's matrix + **5 extra regression tests for R1–R7**:
     1. `SILO_SUP_BASE_BACKOFF=0` & `SILO_BOOT_SETTLE_SECS=0` actually resolve to `0` (`R2`).
     2. Innocent `cat .../bin/silod`, `grep -- --supervisor .../service.sh`, and spoofed `exec -a silod /usr/bin/sleep` ALL survive `service.sh`, `action.sh`, `uninstall.sh` (`R4`).
     3. Orphan live `silod` with deleted `silod.pid` is discovered & cleanly stopped (and its live socket is NEVER blindly unlinked) (`R5`).
     4. Boot-waiting `service.sh` cancelled cleanly by `uninstall.sh` AND does NOT recreate `$STATE_DIR` if `$SILOD` is removed / `$MODDIR/remove` exists (`R6`).
     5. E2E regex `(?<![A-Za-z0-9_./-])(/data/adb)` rewrites EVERY `/data/adb` path in all 4 `module/*.sh` scripts (`R3`).
