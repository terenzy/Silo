#!/system/bin/sh
# ==============================================================================
# Silo KernelSU Service Script — 3-Layer Anti-Bootloop Dead-Man Switch
# & Bounded Crash-Restart Supervisor
#
# Safety invariants:
#   1. Runs strictly AFTER sys.boot_completed=1 (never blocks post-fs-data).
#   2. Never touches Download Mode, bootloader, partitions, USB gadget, or /system.
#   3. Increments /data/adb/silo/.boot_fail_count before launching silod.
#      silod clears the marker automatically after 45s of healthy operation.
#      If 2 consecutive boots fail stability, Silo auto-disables itself & flushes rules!
#   4. Verifies /proc/<pid>/exe and /proc/<pid>/cmdline before signaling any PID.
#   5. Supervises silod with bounded restarts (max 5 in 60s sliding window).
# ==============================================================================
umask 077

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

SILOD="$MODDIR/bin/silod"
STATE_DIR="/data/adb/silo"
[ -n "${SILO_STATE_DIR:-}" ] && STATE_DIR="$SILO_STATE_DIR"
BOOT_MARKER="$STATE_DIR/.boot_fail_count"
SOCK_PATH="$STATE_DIR/silod.sock"
CFG_PATH="$STATE_DIR/config.json"
PID_FILE="$STATE_DIR/silod.pid"
SUP_PID_FILE="$STATE_DIR/supervisor.pid"
BOOT_PID_FILE="$STATE_DIR/service_boot.pid"
STOP_FLAG="$STATE_DIR/.stop_supervisor"

# Sanitizes $1 to a base-10 non-negative integer (<= 9 digits); returns $2 if empty, negative, or overflow.
parse_uint() {
  case "$1" in
    *-*)
      printf '%s' "$2"
      return 0
      ;;
  esac
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

resolve_sh() {
  if [ -x /system/bin/sh ]; then
    printf '%s' "/system/bin/sh"
  elif [ -x /bin/sh ]; then
    printf '%s' "/bin/sh"
  else
    printf '%s' "sh"
  fi
}

write_pid_file() {
  _wpf_target="$1"
  _wpf_val="$2"
  _wpf_tmp="${_wpf_target}.tmp.$$"
  if printf '%s\n' "$_wpf_val" 2>/dev/null > "$_wpf_tmp" \
     && chmod 0600 "$_wpf_tmp" 2>/dev/null \
     && mv -f "$_wpf_tmp" "$_wpf_target" 2>/dev/null; then
    return 0
  fi
  rm -f "$_wpf_tmp" 2>/dev/null || true
  printf '%s\n' "$_wpf_val" > "$_wpf_target"
  chmod 0600 "$_wpf_target"
}

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
    */sh|*/dash|*/ash|*/mksh|*/bash|*/python|*/python[0-9]*|*/env)
      _isp_exe_mode="interp"
      ;;
    */busybox|*/toybox)
      _isp_exe_mode="multicall"
      ;;
  esac

  tr '\000' '\n' 2>/dev/null < "/proc/$_isp_pid/cmdline" | (
    _idx=0
    _has_cmd=0
    _matched=0
    _rejected=0
    _interp_ok=0
    _expect_mc_applet=0
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
            sh|*/sh|dash|*/dash|ash|*/ash|mksh|*/mksh|bash|*/bash|python|python[0-9]*|*/python|*/python[0-9]*|env|*/env)
              _interp_ok=1
              ;;
            busybox|*/busybox|toybox|*/toybox)
              _expect_mc_applet=1
              ;;
          esac
        elif [ "$_isp_exe_mode" = "multicall" ]; then
          case "$_tok" in
            sh|*/sh|dash|*/dash|ash|*/ash|mksh|*/mksh|bash|*/bash|env|*/env)
              _interp_ok=1
              ;;
            busybox|*/busybox|toybox|*/toybox)
              _expect_mc_applet=1
              ;;
          esac
        elif [ "$_isp_exe_mode" = "other" ]; then
          # Allow symlinked native ELF stub only when invoked directly as exact $SILOD
          [ -n "${SILOD:-}" ] && [ "$_tok" = "$SILOD" ] && _matched=1
        fi
      elif [ "$_idx" -eq 1 ] && [ "$_expect_mc_applet" -eq 1 ]; then
        _expect_mc_applet=0
        case "$_tok" in
          sh|dash|ash|mksh|bash|env)
            _interp_ok=1
            ;;
        esac
      elif [ "$_interp_ok" -eq 1 ] && [ "$_idx" -le 2 ]; then
        if [ "$_idx" -eq 1 ]; then
          case "$_tok" in
            */*) ;;
            -*c*) _saw_dash_c=1 ;;
          esac
        fi
        if [ "$_saw_dash_c" -eq 0 ]; then
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

# Scopes orphan detection strictly to THIS instance's $SOCK_PATH / $SILOD
is_silod_for_instance() {
  _ifi_pid="$1"
  is_silod_pid "$_ifi_pid" || return 1
  tr '\000' '\n' 2>/dev/null < "/proc/$_ifi_pid/cmdline" | (
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

  tr '\000' '\n' 2>/dev/null < "/proc/$_isup_pid/cmdline" | (
    _idx=0
    _ok_argv0=0
    _expect_mc_applet=0
    _saw_dash_c=0
    _has_script=0
    _has_flag=0
    while IFS= read -r _tok || [ -n "$_tok" ]; do
      if [ "$_idx" -eq 0 ]; then
        case "$_tok" in
          sh|*/sh|dash|*/dash|ash|*/ash|mksh|*/mksh|bash|*/bash|*/service.sh|service.sh)
            _ok_argv0=1
            ;;
          busybox|*/busybox|toybox|*/toybox)
            _expect_mc_applet=1
            ;;
        esac
      elif [ "$_idx" -eq 1 ] && [ "$_expect_mc_applet" -eq 1 ]; then
        _expect_mc_applet=0
        case "$_tok" in
          sh|dash|ash|mksh|bash) _ok_argv0=1 ;;
        esac
      elif [ "$_idx" -eq 1 ] && [ "$_ok_argv0" -eq 1 ]; then
        case "$_tok" in
          */*) ;;
          -*c*) _saw_dash_c=1 ;;
        esac
      fi
      case "$_tok" in
        */service.sh|service.sh) _has_script=1 ;;
        --supervisor) _has_flag=1 ;;
      esac
      _idx=$((_idx + 1))
    done
    [ "$_ok_argv0" -eq 1 ] && [ "$_saw_dash_c" -eq 0 ] && [ "$_has_script" -eq 1 ] && [ "$_has_flag" -eq 1 ]
  )
}

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

stop_orphan_silod() {
  for _sos_c in /proc/[0-9]*/comm; do
    [ -r "$_sos_c" ] || continue
    IFS= read -r _sos_nm 2>/dev/null < "$_sos_c" || continue
    [ "$_sos_nm" = "silod" ] || continue
    _sos_pid="${_sos_c#/proc/}"
    _sos_pid="${_sos_pid%/comm}"
    if is_silod_for_instance "$_sos_pid"; then
      stop_silod_pid "$_sos_pid"
    fi
  done
}

extract_status_pid() {
  _esp_json="$1"
  _esp_pat='"pid":'
  case "$_esp_json" in
    *"$_esp_pat"*)
      _esp_rest="${_esp_json#*"$_esp_pat"}"
      _esp_rest="${_esp_rest#"${_esp_rest%%[! ]*}"}"
      _esp_num="${_esp_rest%%[!0-9]*}"
      parse_uint "$_esp_num" ""
      ;;
  esac
}

# Never blindly unlink $SOCK_PATH while an active healthy daemon owns it.
# Returns 0 if stale socket removed/absent (safe to spawn); returns 1 only if an
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

cleanup_boot_pid() {
  if [ -f "$BOOT_PID_FILE" ]; then
    _cbp_pid="$(parse_uint "$(cat "$BOOT_PID_FILE" 2>/dev/null || true)" "")"
    [ "$_cbp_pid" = "$$" ] && rm -f "$BOOT_PID_FILE"
  fi
}

# ==============================================================================
# Mode 1: Bounded Crash-Restart Supervisor (--supervisor)
# ==============================================================================
if [ "${1:-}" = "--supervisor" ]; then
  SUP_MAX_RESTARTS="$(parse_uint "${SILO_SUP_MAX_RESTARTS:-5}" 5)"
  SUP_WINDOW_SECS="$(parse_uint "${SILO_SUP_WINDOW_SECS:-60}" 60)"
  SUP_BASE_BACKOFF="$(parse_uint "${SILO_SUP_BASE_BACKOFF:-1}" 1)"
  SUP_MAX_BACKOFF="$(parse_uint "${SILO_SUP_MAX_BACKOFF:-8}" 8)"
  [ "$SUP_WINDOW_SECS" -gt 0 ] || SUP_WINDOW_SECS=60

  trap '' HUP
  printf '%s\n' -900 2>/dev/null > "/proc/$$/oom_score_adj" || true

  if [ ! -d "$MODDIR" ] || [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ] \
     || [ -f "$STOP_FLAG" ] || [ ! -d "$STATE_DIR" ] || [ ! -x "$SILOD" ]; then
    exit 0
  fi

  # Duplicate-supervisor guard
  if [ -f "$SUP_PID_FILE" ]; then
    _existing_sup="$(parse_uint "$(cat "$SUP_PID_FILE" 2>/dev/null || true)" "")"
    if [ -n "$_existing_sup" ] && [ "$_existing_sup" != "$$" ] && is_supervisor_pid "$_existing_sup"; then
      exit 0
    fi
  fi
  write_pid_file "$SUP_PID_FILE" "$$"

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

  CRASH_TIMES=""

  while [ "$STOP_REQUESTED" -eq 0 ]; do
    if [ ! -d "$MODDIR" ] || [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ] \
       || [ -f "$STOP_FLAG" ] || [ ! -d "$STATE_DIR" ] || [ ! -x "$SILOD" ]; then
      break
    fi

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
        # Short reap window if STOP_FLAG is asserted (caller's stop_silod owns full 5s + SIGKILL wait)
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

    # Clear PID_FILE only if it still points to our exited child
    if [ -f "$PID_FILE" ]; then
      _cur_pid="$(parse_uint "$(cat "$PID_FILE" 2>/dev/null || true)" "")"
      [ "$_cur_pid" = "$CHILD_PID" ] && rm -f "$PID_FILE"
    fi
    CHILD_PID=""

    # Case B: Clean exit (0) or explicit SIGTERM/SIGINT -> DO NOT restart
    case "$RC" in
      0|130|143|258|271) break ;;
    esac

    # Case C: Explicit stop / disable / remove / uninstall occurred while child was dying
    if [ ! -d "$MODDIR" ] || [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ] \
       || [ -f "$STOP_FLAG" ] || [ ! -d "$STATE_DIR" ] || [ ! -x "$SILOD" ]; then
      break
    fi

    # Case D: Unexpected exit (LMKD SIGKILL=137, SIGABRT=134, SIGSEGV=139, exit 1, etc.)
    # True sliding window using pure POSIX space-separated timestamps
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

    # Bounded exponential backoff: 1, 2, 4, 8, 8 (sum = 23s < 60s), interruptible by SIGTERM
    BACKOFF="$SUP_BASE_BACKOFF"
    if [ "$BACKOFF" -gt 0 ] && [ "$SUP_MAX_BACKOFF" -gt 0 ]; then
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

  # Clean up PID files only if we still own them (leave PID_FILE intact if child is still hung for caller's stop_silod)
  if [ -n "$CHILD_PID" ] && [ -f "$PID_FILE" ] && ! is_silod_pid "$CHILD_PID"; then
    _cur_pid="$(parse_uint "$(cat "$PID_FILE" 2>/dev/null || true)" "")"
    [ "$_cur_pid" = "$CHILD_PID" ] && rm -f "$PID_FILE"
  fi
  if [ -f "$SUP_PID_FILE" ]; then
    _cur_sup="$(parse_uint "$(cat "$SUP_PID_FILE" 2>/dev/null || true)" "")"
    [ "$_cur_sup" = "$$" ] && rm -f "$SUP_PID_FILE"
  fi
  exit 0
fi

# ==============================================================================
# Mode 2: Normal Boot Entry (KernelSU late_start service)
# ==============================================================================
if [ ! -d "$MODDIR" ] || [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ] || [ ! -x "$SILOD" ]; then
  exit 0
fi

BOOT_WAIT_MAX="$(parse_uint "${SILO_BOOT_WAIT_MAX:-300}" 300)"
BOOT_WAIT_STEP="$(parse_uint "${SILO_BOOT_WAIT_STEP:-2}" 2)"
[ "$BOOT_WAIT_STEP" -gt 0 ] || BOOT_WAIT_STEP=2
BOOT_SETTLE_SECS="$(parse_uint "${SILO_BOOT_SETTLE_SECS:-5}" 5)"

if [ -d "$STATE_DIR" ]; then
  write_pid_file "$BOOT_PID_FILE" "$$"
fi
trap cleanup_boot_pid EXIT

# Wait up to 300s (default) for Android boot completion
WAIT_SEC=0
while [ "$(getprop sys.boot_completed 2>/dev/null | tr -d '\r\n\t ')" != "1" ]; do
  if [ ! -d "$MODDIR" ] || [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ] || [ ! -x "$SILOD" ]; then
    exit 0
  fi
  if [ "$BOOT_WAIT_MAX" -eq 0 ]; then
    exit 0
  fi
  sleep "$BOOT_WAIT_STEP"
  WAIT_SEC=$((WAIT_SEC + BOOT_WAIT_STEP))
  if [ "$WAIT_SEC" -ge "$BOOT_WAIT_MAX" ]; then
    exit 0
  fi
done

# Extra post-boot stabilization window so netd/SystemServer settle cleanly
if [ "$BOOT_SETTLE_SECS" -gt 0 ]; then
  sleep "$BOOT_SETTLE_SECS"
fi

# Re-verify module state & binary BEFORE creating STATE_DIR so uninstall races never recreate /data/adb/silo
if [ ! -d "$MODDIR" ] || [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ] || [ ! -x "$SILOD" ]; then
  exit 0
fi

mkdir -p "$STATE_DIR"
chmod 0700 "$STATE_DIR"

# Read consecutive incomplete-boot counter (octal-safe & overflow-safe)
FAIL_COUNT=0
if [ -f "$BOOT_MARKER" ]; then
  _bm_cnt="$(tr -cd '0-9' 2>/dev/null < "$BOOT_MARKER" || true)"
  _bm_cnt="${_bm_cnt#"${_bm_cnt%%[!0]*}"}"
  if [ -n "$_bm_cnt" ]; then
    if [ "${#_bm_cnt}" -gt 3 ]; then
      FAIL_COUNT=2
    else
      FAIL_COUNT="$_bm_cnt"
    fi
  fi
fi

# Layer 2 Dead-Man Switch: auto-disable & flush if 2 consecutive unstable boots occurred
if [ "$FAIL_COUNT" -ge 2 ]; then
  ( : > "$STOP_FLAG" ) 2>/dev/null || true
  stop_supervisor
  stop_silod
  stop_orphan_silod
  "$SILOD" --emergency-flush --config "$CFG_PATH" >/dev/null 2>&1 || true
  touch "$MODDIR/disable" 2>/dev/null || true
  rm -f "$BOOT_MARKER" "$SOCK_PATH" "$PID_FILE" "$SUP_PID_FILE" "$BOOT_PID_FILE" "$STOP_FLAG"
  exit 0
fi

# Arm boot-stability marker (cleared automatically by silod after 45s uptime)
printf '%s\n' "$((FAIL_COUNT + 1))" > "$BOOT_MARKER"
chmod 0600 "$BOOT_MARKER"

# Stop any stale/existing supervisor, tracked silod, or orphan silod cleanly
( : > "$STOP_FLAG" ) 2>/dev/null || true
stop_supervisor
stop_silod
stop_orphan_silod
if ! safe_cleanup_socket; then
  rm -f "$STOP_FLAG"
  exit 0
fi
rm -f "$STOP_FLAG"

cleanup_boot_pid

SH_BIN="$(resolve_sh)"
if command -v setsid >/dev/null 2>&1; then
  setsid "$SH_BIN" "$MODDIR/service.sh" --supervisor </dev/null >/dev/null 2>&1 &
else
  "$SH_BIN" "$MODDIR/service.sh" --supervisor </dev/null >/dev/null 2>&1 &
fi

# Bounded readiness wait (~2s max, typically <100ms) so supervisor & silod PID files exist
_w=0
while [ "$_w" -lt 20 ]; do
  [ -s "$SUP_PID_FILE" ] && [ -s "$PID_FILE" ] && break
  if sleep_tenth; then _w=$((_w + 1)); else _w=$((_w + 10)); fi
done
