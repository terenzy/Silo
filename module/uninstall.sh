#!/system/bin/sh
# ==============================================================================
# Silo Clean Uninstaller — Zero-Trace Removal
# ==============================================================================
MODDIR="${0%/*}"
SILOD="$MODDIR/bin/silod"
STATE_DIR="/data/adb/silo"
PID_FILE="$STATE_DIR/silod.pid"

# 1. Gracefully stop silod (automatically thaws all cgroup-frozen UIDs & unsuspends Deep-Stopped apps)
if [ -f "$PID_FILE" ]; then
  PID="$(cat "$PID_FILE" 2>/dev/null | tr -cd '0-9')"
  if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
    kill -TERM "$PID" 2>/dev/null || true
    sleep 2
    kill -KILL "$PID" 2>/dev/null || true
  fi
fi

# 2. Cleanly remove all SILO_* iptables/ip6tables chains, ipsets, unfreeze cgroups, and unsuspend packages
if [ -x "$SILOD" ]; then
  "$SILOD" --emergency-flush >/dev/null 2>&1 || true
fi

# 3. Remove root-only state directory
rm -rf "$STATE_DIR"
