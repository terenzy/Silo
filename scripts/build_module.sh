#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 [SILOD_ARM64_BIN] [OUT_ZIP]" >&2
}

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  usage
  exit 0
fi

if [ "$#" -gt 2 ]; then
  echo "ERROR: Expected at most 2 arguments, got $#" >&2
  usage
  exit 1
fi

for tool in file zip unzip; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "ERROR: Required tool '$tool' not found in PATH" >&2
    exit 1
  fi
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MODULE_DIR="$REPO_ROOT/module"
SILOD_BIN="${1:-$REPO_ROOT/daemon/target/aarch64-unknown-linux-musl/release/silod}"
OUT_ZIP="${2:-$REPO_ROOT/dist/silo-ksu-module.zip}"

if [ ! -f "$SILOD_BIN" ] || [ ! -r "$SILOD_BIN" ]; then
  echo "ERROR: silod ARM64 binary not found or not readable at $SILOD_BIN" >&2
  exit 1
fi

case "$OUT_ZIP" in
  */)
    echo "ERROR: Output ZIP path must be a file path, not a directory: $OUT_ZIP" >&2
    exit 1
    ;;
esac
if [ -d "$OUT_ZIP" ]; then
  echo "ERROR: Output ZIP path points to an existing directory: $OUT_ZIP" >&2
  exit 1
fi

# Verify target binary is a statically linked ARM64 (aarch64 LSB) ELF executable
# Uses -b (brief: no filename prefix, preventing filename-spoofing) and -L (follow symlinks)
FILE_INFO="$(file -bL -- "$SILOD_BIN")"
echo "Verifying daemon binary: $FILE_INFO"
if ! printf '%s\n' "$FILE_INFO" | grep -qE '^ELF 64-bit LSB (pie )?executable, ARM aarch64.*(statically linked|static-pie linked)' \
   || printf '%s\n' "$FILE_INFO" | grep -qiE 'dynamically linked|interpreter'; then
  echo "ERROR: $SILOD_BIN is not a statically linked ARM64 (aarch64 LSB) ELF executable!" >&2
  exit 1
fi

FORBIDDEN_OVERLAYS=(system vendor product system_ext odm)
REQUIRED_MODULE_FILES=(module.prop customize.sh service.sh action.sh uninstall.sh)

# Layer 1 zero-mount check: fail loudly if forbidden overlay entries exist in module/
for ov in "${FORBIDDEN_OVERLAYS[@]}"; do
  if [ -e "$MODULE_DIR/$ov" ] || [ -L "$MODULE_DIR/$ov" ]; then
    echo "ERROR: Stealth violation — forbidden overlay entry '$ov' found in $MODULE_DIR!" >&2
    exit 1
  fi
done

WORK_DIR="$(mktemp -d)"
cleanup() {
  rm -rf -- "${WORK_DIR:-}"
}
trap cleanup EXIT

STAGE_DIR="$WORK_DIR/stage"
TMP_ZIP="$WORK_DIR/module.zip"
mkdir -p "$STAGE_DIR/bin"

# Explicit whitelist copy: NEVER copy stray scratch, editor backup, or hidden files from module/
for f in "${REQUIRED_MODULE_FILES[@]}"; do
  src="$MODULE_DIR/$f"
  if [ ! -f "$src" ] || [ -L "$src" ] || [ ! -r "$src" ]; then
    echo "ERROR: Required module file missing or invalid: $src" >&2
    exit 1
  fi
  cp -- "$src" "$STAGE_DIR/$f"
done

cp -L -- "$SILOD_BIN" "$STAGE_DIR/bin/silod"

chmod 0755 "$STAGE_DIR/bin" "$STAGE_DIR/bin/silod" \
  "$STAGE_DIR/customize.sh" "$STAGE_DIR/service.sh" \
  "$STAGE_DIR/action.sh" "$STAGE_DIR/uninstall.sh"
chmod 0644 "$STAGE_DIR/module.prop"

# Layer 2 zero-mount check: verify staging directory has zero overlay dirs/entries
for ov in "${FORBIDDEN_OVERLAYS[@]}"; do
  if [ -e "$STAGE_DIR/$ov" ] || [ -L "$STAGE_DIR/$ov" ]; then
    echo "ERROR: Stealth violation — forbidden overlay entry '$ov' found in staging directory!" >&2
    exit 1
  fi
done

(
  cd "$STAGE_DIR"
  zip -r9 "$TMP_ZIP" "${REQUIRED_MODULE_FILES[@]}" bin >/dev/null
)

# Layer 3 zero-mount & completeness check on the generated ZIP before publishing OUT_ZIP
ZIP_ENTRIES="$(unzip -Z1 -- "$TMP_ZIP")"
if printf '%s\n' "$ZIP_ENTRIES" | grep -qiE '^(\./)?(system|vendor|product|system_ext|odm)(/|$)'; then
  echo "ERROR: Stealth violation — forbidden overlay path found in generated module ZIP!" >&2
  exit 1
fi
for req in "${REQUIRED_MODULE_FILES[@]}" "bin/silod"; do
  if ! printf '%s\n' "$ZIP_ENTRIES" | grep -qxF "$req"; then
    echo "ERROR: Required entry '$req' missing from generated module ZIP!" >&2
    exit 1
  fi
done

OUT_DIR="$(dirname -- "$OUT_ZIP")"
OUT_NAME="$(basename -- "$OUT_ZIP")"
mkdir -p -- "$OUT_DIR"
OUT_ZIP="$(cd -- "$OUT_DIR" && pwd)/$OUT_NAME"

# Replace cleanly so stale entries from an existing ZIP never linger
rm -f -- "$OUT_ZIP"
mv -f -- "$TMP_ZIP" "$OUT_ZIP"
chmod 0644 -- "$OUT_ZIP"

ZIP_SIZE="$(wc -c < "$OUT_ZIP" | tr -d '[:space:]')"
echo "Built KernelSU module successfully: $OUT_ZIP (${ZIP_SIZE} bytes)"
unzip -l -- "$OUT_ZIP"
