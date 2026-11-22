#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SILOD_BIN="${1:-$REPO_ROOT/daemon/target/aarch64-unknown-linux-musl/release/silod}"
OUT_ZIP="${2:-$REPO_ROOT/dist/silo-ksu-module.zip}"

if [ ! -f "$SILOD_BIN" ]; then
  echo "ERROR: silod ARM64 binary not found at $SILOD_BIN" >&2
  exit 1
fi

# Verify target binary is indeed an ARM64 static ELF executable
FILE_INFO="$(file "$SILOD_BIN")"
echo "Verifying daemon binary: $FILE_INFO"
if ! echo "$FILE_INFO" | grep -qiE 'ELF 64-bit.*ARM aarch64.*statically linked'; then
  echo "ERROR: $SILOD_BIN is not a statically linked ARM64 ELF binary!" >&2
  exit 1
fi

STAGE_DIR="$(mktemp -d)"
trap 'rm -rf "$STAGE_DIR"' EXIT

cp -r "$REPO_ROOT/module/"* "$STAGE_DIR/"
mkdir -p "$STAGE_DIR/bin"
cp "$SILOD_BIN" "$STAGE_DIR/bin/silod"
chmod 0755 "$STAGE_DIR/bin/silod" "$STAGE_DIR/service.sh" "$STAGE_DIR/action.sh" "$STAGE_DIR/uninstall.sh" "$STAGE_DIR/customize.sh"

# Strictly verify ZERO /system or /vendor overlay directory exists (zero-mount stealth invariant)
if [ -d "$STAGE_DIR/system" ] || [ -d "$STAGE_DIR/vendor" ]; then
  echo "ERROR: Stealth violation — module must not contain /system or /vendor overlays!" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUT_ZIP")"
rm -f "$OUT_ZIP"
(
  cd "$STAGE_DIR"
  zip -r9 "$OUT_ZIP" . >/dev/null
)

echo "Built KernelSU module successfully: $OUT_ZIP ($(stat -c%s "$OUT_ZIP") bytes)"
unzip -l "$OUT_ZIP"
