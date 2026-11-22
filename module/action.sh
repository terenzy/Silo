#!/system/bin/sh
# ==============================================================================
# Silo KernelSU Action Button — Live Daemon Health & Recovery Trigger
# ==============================================================================
MODDIR="${0%/*}"
SILOD="$MODDIR/bin/silod"
STATE_DIR="/data/adb/silo"
SOCK_PATH="$STATE_DIR/silod.sock"
CFG_PATH="$STATE_DIR/config.json"
BOOT_MARKER="$STATE_DIR/.boot_fail_count"
PID_FILE="$STATE_DIR/silod.pid"

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
  echo "[INFO] silod is not currently running. Resetting Dead-Man marker & starting..."
  mkdir -p "$STATE_DIR"
  chmod 0700 "$STATE_DIR"
  rm -f "$BOOT_MARKER" "$SOCK_PATH"
  "$SILOD" \
    --socket "$SOCK_PATH" \
    --config "$CFG_PATH" \
    --boot-marker "$BOOT_MARKER" \
    </dev/null >/dev/null 2>&1 &
  echo "$!" > "$PID_FILE"
  chmod 0600 "$PID_FILE"
  sleep 2
  if "$SILOD" --socket "$SOCK_PATH" --status >/dev/null 2>&1; then
    echo "[OK] silod started successfully."
  else
    echo "[WARN] silod did not respond yet; check KernelSU module state."
  fi
fi
