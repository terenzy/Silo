#!/system/bin/sh
# ==============================================================================
# Silo KernelSU Action Button — Live Daemon Health & Recovery Trigger
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
SOCK_PATH="$STATE_DIR/silod.sock"
CFG_PATH="$STATE_DIR/config.json"
BOOT_MARKER="$STATE_DIR/.boot_fail_count"
PID_FILE="$STATE_DIR/silod.pid"
SUP_PID_FILE="$STATE_DIR/supervisor.pid"
STOP_FLAG="$STATE_DIR/.stop_supervisor"

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

echo "============================================"
echo " Silo Kernel Privacy & Security — Status"
echo "============================================"

if [ ! -x "$SILOD" ]; then
  echo "[ERROR] silod binary missing at $SILOD"
  exit 1
fi

echo "Binary: $("$SILOD" --version 2>/dev/null || echo unknown)"

if [ -S "$SOCK_PATH" ] && STATUS_JSON="$("$SILOD" --socket "$SOCK_PATH" --status 2>/dev/null)"; then
  echo "[OK] silod is running and responding on root-only Unix socket."
  echo "$STATUS_JSON"
else
  echo "[INFO] silod is not currently running. Clearing disable/Dead-Man markers & starting..."
  mkdir -p "$STATE_DIR"
  chmod 0700 "$STATE_DIR"
  rm -f "$MODDIR/disable" "$BOOT_MARKER"

  ( : > "$STOP_FLAG" ) 2>/dev/null || true
  stop_supervisor
  stop_silod
  stop_orphan_silod
  if ! safe_cleanup_socket; then
    rm -f "$STOP_FLAG"
    echo "[OK] silod is running and responding on root-only Unix socket."
    exit 0
  fi
  rm -f "$STOP_FLAG"

  LAUNCH_MODE="direct"
  SH_BIN="$(resolve_sh)"
  if [ -f "$MODDIR/service.sh" ]; then
    LAUNCH_MODE="supervised"
    if command -v setsid >/dev/null 2>&1; then
      setsid "$SH_BIN" "$MODDIR/service.sh" --supervisor </dev/null >/dev/null 2>&1 &
    else
      "$SH_BIN" "$MODDIR/service.sh" --supervisor </dev/null >/dev/null 2>&1 &
    fi
  else
    (
      trap '' HUP
      exec "$SILOD" \
        --socket "$SOCK_PATH" \
        --config "$CFG_PATH" \
        --boot-marker "$BOOT_MARKER"
    ) </dev/null >/dev/null 2>&1 &
    write_pid_file "$PID_FILE" "$!"
  fi

  STARTED=0
  _w=0
  while [ "$_w" -lt 25 ]; do
    if [ -S "$SOCK_PATH" ] && "$SILOD" --socket "$SOCK_PATH" --status >/dev/null 2>&1; then
      STARTED=1
      break
    fi
    if sleep 0.2 2>/dev/null; then
      _w=$((_w + 1))
    else
      sleep 1
      _w=$((_w + 5))
    fi
  done

  if [ "$STARTED" -eq 1 ]; then
    if [ "$LAUNCH_MODE" = "supervised" ]; then
      echo "[OK] silod started successfully (supervised)."
    else
      echo "[OK] silod started successfully."
    fi
  else
    echo "[WARN] silod did not respond yet; check KernelSU module state."
  fi
fi
