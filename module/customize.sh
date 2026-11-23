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
    */*)
      MODPATH="${0%/*}"
      [ -n "$MODPATH" ] || MODPATH="/"
      ;;
    *)
      MODPATH="."
      ;;
  esac
  MODPATH="$(CDPATH= cd -- "$MODPATH" 2>/dev/null && (pwd -P 2>/dev/null || pwd))" || abort "! Cannot determine MODPATH"
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
SILO_STATE_PATH="/data/adb/silo"
[ -n "${SILO_STATE_DIR:-}" ] && SILO_STATE_PATH="$SILO_STATE_DIR"
SILO_LIVE_MODPATH="/data/adb/modules/silo"
[ -n "${SILO_LIVE_MODDIR:-}" ] && SILO_LIVE_MODPATH="$SILO_LIVE_MODDIR"

mkdir -p "$SILO_STATE_PATH"
chmod 0700 "$SILO_STATE_PATH"
rm -f "$SILO_STATE_PATH/.boot_fail_count" "$SILO_STATE_PATH/.stop_supervisor" "$SILO_STATE_PATH/service_boot.pid"

# Clear any prior anti-bootloop disable marker on reinstall/upgrade (both live module dir and staged MODPATH)
rm -f "$SILO_LIVE_MODPATH/disable" "$MODPATH/disable"

ui_print "- Zero-mount stealth install complete."
ui_print "- Anti-Bootloop Dead-Man Switch armed (safe-first default on boot)."
ui_print "- Reboot to start silod, then grant root to Silo in KernelSU-Next."
