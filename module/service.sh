#!/system/bin/sh
# ==============================================================================
# Silo KernelSU Service Script — 3-Layer Anti-Bootloop Dead-Man Switch
#
# Safety invariants:
#   1. Runs strictly AFTER sys.boot_completed=1 (never blocks post-fs-data).
#   2. Never touches Download Mode, bootloader, partitions, USB gadget, or /system.
#   3. Increments /data/adb/silo/.boot_fail_count before launching silod.
#      silod clears the marker automatically after 45s of healthy operation.
#      If 2 consecutive boots fail stability, Silo auto-disables itself & flushes rules!
# ==============================================================================
MODDIR="${0%/*}"
SILOD="$MODDIR/bin/silod"
STATE_DIR="/data/adb/silo"
BOOT_MARKER="$STATE_DIR/.boot_fail_count"
SOCK_PATH="$STATE_DIR/silod.sock"
CFG_PATH="$STATE_DIR/config.json"
PID_FILE="$STATE_DIR/silod.pid"

# Wait up to 180s for Android boot completion
WAIT_SEC=0
while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; do
  sleep 2
  WAIT_SEC=$((WAIT_SEC + 2))
  if [ "$WAIT_SEC" -ge 180 ]; then
    exit 0
  fi
done

# Extra post-boot stabilization window so netd/SystemServer settle cleanly
sleep 5

mkdir -p "$STATE_DIR"
chmod 0700 "$STATE_DIR"

[ -x "$SILOD" ] || exit 0

# Read consecutive incomplete-boot counter
FAIL_COUNT=0
if [ -f "$BOOT_MARKER" ]; then
  RAW_CNT="$(cat "$BOOT_MARKER" 2>/dev/null | tr -cd '0-9')"
  [ -n "$RAW_CNT" ] && FAIL_COUNT="$RAW_CNT"
fi

# Layer 2 Dead-Man Switch: auto-disable & flush if 2 consecutive unstable boots occurred
if [ "$FAIL_COUNT" -ge 2 ]; then
  "$SILOD" --emergency-flush >/dev/null 2>&1 || true
  touch "$MODDIR/disable" 2>/dev/null || true
  rm -f "$BOOT_MARKER" "$SOCK_PATH" "$PID_FILE"
  exit 0
fi

# Arm boot-stability marker (cleared automatically by silod after 45s uptime)
echo $((FAIL_COUNT + 1)) > "$BOOT_MARKER"
chmod 0600 "$BOOT_MARKER"

# Stop any stale instance cleanly
if [ -f "$PID_FILE" ]; then
  OLD_PID="$(cat "$PID_FILE" 2>/dev/null | tr -cd '0-9')"
  if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
    kill -TERM "$OLD_PID" 2>/dev/null || true
    sleep 1
  fi
fi

rm -f "$SOCK_PATH"

"$SILOD" \
  --socket "$SOCK_PATH" \
  --config "$CFG_PATH" \
  --boot-marker "$BOOT_MARKER" \
  </dev/null >/dev/null 2>&1 &

echo "$!" > "$PID_FILE"
chmod 0600 "$PID_FILE"
