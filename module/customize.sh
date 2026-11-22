#!/system/bin/sh
# ==============================================================================
# Silo KernelSU Module Installer (Zero-Mount, ARM64 Static Rust Daemon)
# ==============================================================================
SKIPUNZIP=0

ui_print "- Installing Silo Kernel Privacy & Security..."

ARCH="$(getprop ro.product.cpu.abi)"
case "$ARCH" in
  arm64-v8a*)
    ui_print "- Architecture verified: $ARCH"
    ;;
  *)
    abort "! Unsupported architecture: $ARCH (Silo requires arm64-v8a)"
    ;;
esac

if [ ! -f "$MODPATH/bin/silod" ]; then
  abort "! Missing silod binary inside module archive"
fi

chmod 0755 "$MODPATH/bin/silod"
chmod 0755 "$MODPATH/service.sh" "$MODPATH/action.sh" "$MODPATH/uninstall.sh"

# Prepare root-only runtime directory (0700) — never modifies /system or /vendor
mkdir -p /data/adb/silo
chmod 0700 /data/adb/silo
rm -f /data/adb/silo/.boot_fail_count

ui_print "- Zero-mount stealth install complete."
ui_print "- Anti-Bootloop Dead-Man Switch armed (safe-first default on boot)."
ui_print "- Reboot to start silod, then grant root to Silo in KernelSU-Next."
