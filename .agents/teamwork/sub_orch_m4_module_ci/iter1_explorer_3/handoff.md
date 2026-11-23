# M4 Packaging, CI Security & Behavioral Verification Handoff (`iter1_explorer_3`)

> **Scope**: Line-by-line read-only audit and concrete remediation specifications for `scripts/build_module.sh` (`CB-28`), `.github/workflows/build.yml` (`CB-29`), `module/module.prop` + cross-repo version/contract alignment, and a complete self-contained Behavioral Sandbox Verification Plan covering all M4 deliverables (`module/{customize,service,action,uninstall}.sh`, `scripts/build_module.sh`, `.github/workflows/build.yml`).

---

## 1. Cross-Check Alignment (`module/module.prop`, `README.md`, `app/app/build.gradle.kts`, `daemon/`)

All version, path, and artifact-naming contracts across the repository were verified read-only:

| File & Lines | Field / Contract | Verified Value | Alignment Status |
|---|---|---|---|
| `module/module.prop:1-6` | `id`, `version`, `versionCode` | `id=silo`, `version=v3.0.0`, `versionCode=30000` | Canonical source for KSU module metadata & CI fallback version |
| `app/app/build.gradle.kts:16-17` | `versionCode`, `versionName` | `30000`, `"3.0.0"` | **ALIGNED** with `module/module.prop` |
| `daemon/Cargo.toml:2-3` | `name`, `version` | `"silod"`, `"3.0.0"` | **ALIGNED** (`Cargo.toml:6` stale v2 description is owned by M1) |
| `daemon/.cargo/config.toml:1-7` | `aarch64-unknown-linux-musl` flags | `linker = "rust-lld"`, `+crt-static`, `relocation-model=static` | Produces static ARM64 LSB `ET_EXEC` (`statically linked`); without `relocation-model=static`, produces `ET_DYN`+`DF_1_PIE` (`static-pie linked`) |
| `README.md:54,70` | Release filenames & local build cmd | `silo-ksu-module-<tag>.zip`, `Silo-<tag>.apk`, `./scripts/build_module.sh` (0-arg) | **MUST PRESERVE** 0-arg default paths (`daemon/target/aarch64-unknown-linux-musl/release/silod` → `dist/silo-ksu-module.zip`) and release artifact names |

---

## 2. Deep Line-by-Line Audit & Remediation Design: `scripts/build_module.sh` (`CB-28`)

### 2.1 Current File Defects (`scripts/build_module.sh:1-46`)

| Lines | Current Code | Defect / Edge Case | Severity |
|---|---|---|---|
| `6-12` | `SILOD_BIN="${1:-...}"`, `OUT_ZIP="${2:-...}"`, `[ ! -f "$SILOD_BIN" ]` | (1) Silently ignores extra positional args (`$# > 2`) and lacks `-h`/`--help` usage. (2) Does not reject `OUT_ZIP` being an existing directory or ending with `/`. (3) Does not pass `--` to `file`/`cp`/`rm`/`dirname`/`basename`/`stat`/`unzip` (option injection if paths start with `-`). (4) Does not check `file`/`zip`/`unzip` tool presence or `[ -r "$SILOD_BIN" ]`. | Medium |
| `15-20` | `FILE_INFO="$(file "$SILOD_BIN")"` + `grep -qiE 'ELF 64-bit.*ARM aarch64.*statically linked'` | **(1) Rejects valid `static-pie linked` ARM64 musl binaries**: empirically verified on devbox `file-5.45` (same version as `ubuntu-latest`), an ARM64 static-PIE ELF prints `ELF 64-bit LSB pie executable, ARM aarch64, version 1 (SYSV), static-pie linked` — failing `statically linked`! **(2) Filename spoofing**: without `-b` (`--brief`), `file` prefixes `<path>: `, so `/tmp/ELF 64-bit ARM aarch64 statically linked/fake.txt` bypasses the check! **(3) Symlink failure**: without `-L` (`--dereference`), passing a symlink to a valid `silod` fails with `symbolic link to ...`. **(4) False accepts**: accepts ARM64 shared objects (`ELF 64-bit LSB shared object, ARM aarch64, ..., statically linked`) and big-endian (`MSB`) ELF binaries! | High |
| `22-23` | `STAGE_DIR="$(mktemp -d)"` + `trap 'rm -rf "$STAGE_DIR"' EXIT` | Clean, but staging and temp ZIP should live under a single `WORK_DIR="$(mktemp -d)"` with a `cleanup()` function so `$TMP_ZIP` is also guaranteed removed on error/signal and `$OUT_ZIP` is never left half-written or stealth-violating if post-ZIP verification fails. | Medium |
| `25` | `cp -r "$REPO_ROOT/module/"* "$STAGE_DIR/"` | **Indiscriminate recursive wildcard copy (`CB-28`)**: copies ANY stray scratch files, editor backups (`service.sh~`, `.swp`), or rogue directories from `module/` into the KSU module ZIP. Also silently succeeds if a required file is missing until `chmod` fails cryptically on line 28. | High |
| `27-28` | `cp "$SILOD_BIN" "$STAGE_DIR/bin/silod"` + `chmod 0755 ...` | (1) `module.prop` permission is NEVER explicitly normalized to `0644` (inherits host umask/checkout mode). (2) `bin/` directory permission not explicitly normalized to `0755`. (3) `cp` without `-L --` could fail on `-`-prefixed paths or preserve symlink if aliased. | Medium |
| `31-34` | `if [ -d "$STAGE_DIR/system" ] \|\| [ -d "$STAGE_DIR/vendor" ]` | **Incomplete zero-mount check (`CB-28`)**: (1) Misses `product`, `system_ext`, and `odm` partitions! (2) Only checks `-d` (misses symlinks or regular files named `system`/`odm`, etc.). (3) Does NOT check `$MODULE_DIR` before explicit copy (with explicit copy, a stray `module/product/` would be skipped silently instead of failing the build as a stealth violation!). (4) Does NOT inspect the generated ZIP archive itself (`unzip -Z1`)! | High |
| `36-42` | `rm -f "$OUT_ZIP"` then `( cd "$STAGE_DIR"; zip -r9 "$OUT_ZIP" . >/dev/null )` | (1) Writes directly to `$OUT_ZIP` before post-ZIP verification (a failed post-ZIP check would leave a bad archive at `$OUT_ZIP`). (2) Zipping `.` can accidentally include unexpected hidden entries in `$STAGE_DIR`; zipping the explicit top-level entries (`module.prop customize.sh service.sh action.sh uninstall.sh bin`) is strictly cleaner. | Medium |
| `44` | `stat -c%s "$OUT_ZIP"` | GNU-only `stat -c%s`; using `wc -c < "$OUT_ZIP" | tr -d '[:space:]'` is POSIX-portable across Linux/BSD/macOS. | Low |

### 2.2 Empirically Verified `file-5.45` Outputs (Devbox x86_64 Linux)

Tested directly via `python3 ... | file -b -` on synthetic minimal ELF binaries (zero temporary files created):

| Synthetic Binary Type | Exact `file -b` Output (`file-5.45`) | Expected Gate Result |
|---|---|---|
| ARM64 LSB static `ET_EXEC` (`+crt-static`, `relocation-model=static`) | `ELF 64-bit LSB executable, ARM aarch64, version 1 (SYSV), statically linked, no section header` | **ACCEPT** |
| ARM64 LSB static-PIE `ET_DYN` + `DT_FLAGS_1=DF_1_PIE` | `ELF 64-bit LSB pie executable, ARM aarch64, version 1 (SYSV), static-pie linked, no section header` | **ACCEPT** |
| ARM64 LSB dynamic `ET_DYN` + `PT_INTERP=/system/bin/linker64` | `ELF 64-bit LSB shared object, ARM aarch64, version 1 (SYSV), dynamically linked, interpreter /system/bin/linker64, no section header` | **REJECT** |
| ARM64 LSB shared lib `ET_DYN` (no `DF_1_PIE`, no `PT_INTERP`) | `ELF 64-bit LSB shared object, ARM aarch64, version 1 (SYSV), statically linked, no section header` | **REJECT** |
| ARM64 MSB (big-endian) static `ET_EXEC` | `ELF 64-bit MSB executable, ARM aarch64, version 1 (SYSV), statically linked, no section header` | **REJECT** |
| x86-64 LSB static `ET_EXEC` | `ELF 64-bit LSB executable, x86-64, version 1 (SYSV), statically linked, no section header` | **REJECT** |

**Exact safe `file` gate**:
```bash
FILE_INFO="$(file -bL -- "$SILOD_BIN")"
echo "Verifying daemon binary: $FILE_INFO"
if ! printf '%s\n' "$FILE_INFO" | grep -qE '^ELF 64-bit LSB (pie )?executable, ARM aarch64.*(statically linked|static-pie linked)' \
   || printf '%s\n' "$FILE_INFO" | grep -qiE 'dynamically linked|interpreter'; then
  echo "ERROR: $SILOD_BIN is not a statically linked ARM64 (aarch64 LSB) ELF executable!" >&2
  exit 1
fi
```

### 2.3 Concrete Remediation Design for `scripts/build_module.sh`

Complete, edge-case-free target design (preserves 0/1/2-arg CLI contract, passes `bash -n`, enforces 3-layer zero-mount defense):

```bash
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

# Replace atomically/cleanly so stale entries from an existing ZIP never linger
rm -f -- "$OUT_ZIP"
mv -f -- "$TMP_ZIP" "$OUT_ZIP"
chmod 0644 -- "$OUT_ZIP"

ZIP_SIZE="$(wc -c < "$OUT_ZIP" | tr -d '[:space:]')"
echo "Built KernelSU module successfully: $OUT_ZIP (${ZIP_SIZE} bytes)"
unzip -l -- "$OUT_ZIP"
```

**Why this 3-layer design handles every edge case**:
1. Benign stray files in `module/` (e.g. `module/notes.tmp`, `module/service.sh~`, `module/.DS_Store`) are **excluded** silently by explicit whitelist copy without breaking builds.
2. Forbidden overlay paths (`system`, `vendor`, `product`, `system_ext`, `odm` — directories, files, OR symlinks) in `module/`, `$STAGE_DIR`, OR `$TMP_ZIP` **fail the build loudly** and leave **no** output ZIP behind.
3. Pre-existing `$OUT_ZIP` with stale entries is cleanly replaced (never incrementally updated by `zip`).
4. Both `statically linked` and `static-pie linked` ARM64 LSB executables pass; dynamic ARM64, x86-64, `.so`, big-endian `MSB`, text, and filename-spoofed files all fail.
5. `WORK_DIR` cleanup runs automatically on success, error, and exit.

---

## 3. Deep Line-by-Line Audit & Remediation Design: `.github/workflows/build.yml` (`CB-29`)

### 3.1 Complete `${{ ... }}` Expression Security Audit (Every Occurrence in Lines 1–211)

| Line(s) | Expression | Context | Risk | Action Required |
|---|---|---|---|---|
| `48` | `${{ runner.os }}`, `${{ hashFiles('daemon/Cargo.toml', 'daemon/Cargo.lock') }}` | `with.key:` (`actions/cache@v4`) | Safe (YAML action input) | Keep as-is |
| `50` | `${{ runner.os }}` | `with.restore-keys:` (`actions/cache@v4`) | Safe (YAML action input) | Keep as-is |
| `112` | `${{ secrets.SILO_KEYSTORE_BASE64 }}` | `env:` | Safe (`env:` indirection) | Keep as-is |
| `125-127` | `${{ secrets.SILO_KEYSTORE_* }}` | `env:` | Safe (`env:` indirection) | Keep as-is |
| `158` | `startsWith(github.ref, 'refs/tags/v') \|\| (github.event_name == 'workflow_dispatch' && github.event.inputs.create_release == 'true')` | Job `if:` | Safe (GHA expression engine, not shell) | Keep as-is |
| **`174-175`** | **`${{ github.event.inputs.release_tag }}`** | **DIRECT inside `run:` bash!** | **HIGH — GHA Script Injection + Path Traversal + `GITHUB_OUTPUT` Injection!** | **MUST MOVE to `env: INPUT_RELEASE_TAG` + strict tag regex validation!** |
| `192` | `${{ github.token }}` | `env:` | Safe (`env:` indirection) | Keep as-is |
| **`194`** | **`${{ steps.meta.outputs.tag }}`** | **DIRECT inside `run:` bash!** | **MEDIUM — Direct `${{` in `run:`** | **MUST MOVE to `env: RELEASE_TAG`** |
| **`196`** | **`${{ github.event.inputs.prerelease }}`** | **DIRECT inside `run:` bash!** | **MEDIUM — Direct `${{` in `run:`** | **MUST MOVE to `env: INPUT_PRERELEASE`** |

After remediation: **ZERO `${{ ... }}` expressions remain inside ANY `run:` block in the entire workflow!**

### 3.2 Complete Line-by-Line Findings in `.github/workflows/build.yml`

1. **Over-broad Top-Level Permissions (`lines 3-4`, `CB-29`)**:
   - Currently top-level `permissions: contents: write` gives write permission to `daemon-and-module` and `android-app` (neither needs write access: they only run `checkout@v5`, toolchains, `cache@v4`, `setup-gradle@v4`, and `upload-artifact@v4`).
   - **Fix**: Set top-level `permissions: contents: read`. Add `permissions: contents: write` **strictly on the `release` job** (line 159).
2. **Missing `bash -n scripts/build_module.sh` (`lines 65-75`)**:
   - Currently runs `sh -n` on the 4 `module/*.sh` scripts, but omits `bash -n scripts/build_module.sh` (required by `PROJECT.md:20`).
   - **Fix**: Add `bash -n scripts/build_module.sh` before invoking `./scripts/build_module.sh`.
3. **Incomplete & Fragile Zero-Mount CI Gate (`lines 76-83`, `CB-28`)**:
   - Currently: `if unzip -l dist/silo-ksu-module.zip | grep -qE '[[:space:]](system|vendor|product)/'; then`
   - **Defects**:
     - Missing `system_ext` and `odm`!
     - `unzip -l` includes archive filename headers, column titles, comments, and totals — can false-positive on path/comments and miss bare files named `system`/`odm` or `./system/...` paths!
     - Error message only mentions `/system or /vendor`.
   - **Fix**:
     ```yaml
      - name: Verify Zero-Mount Stealth Invariant on Module ZIP
        run: |
          ZIP_ENTRIES="$(unzip -Z1 dist/silo-ksu-module.zip)"
          if printf '%s\n' "$ZIP_ENTRIES" | grep -qiE '^(\./)?(system|vendor|product|system_ext|odm)(/|$)'; then
            echo "::error::Stealth invariant violated: forbidden overlay (system/vendor/product/system_ext/odm) found in module ZIP!"
            exit 1
          fi
          for req in module.prop customize.sh service.sh action.sh uninstall.sh bin/silod; do
            if ! printf '%s\n' "$ZIP_ENTRIES" | grep -qxF "$req"; then
              echo "::error::Required module entry missing from ZIP: $req"
              exit 1
            fi
          done
          echo "[OK] Zero-mount stealth invariant & required module entries verified."
     ```
4. **Minor Hardening in `android-app` Job (`lines 114-116, 139-145`)**:
   - Line 115: Use `printf '%s' "$SILO_KEYSTORE_BASE64" | tr -d '[:space:]' | base64 -d > "${RUNNER_TEMP}/silo-release.jks"` and `chmod 0600 "${RUNNER_TEMP}/silo-release.jks"`.
   - Line 139: Assert `test -x "$AAPT2" || { echo "::error::aapt2 binary not found under ANDROID_HOME!"; exit 1; }`.
   - Line 142: Use `grep -Fq 'android.permission.INTERNET'` (literal match, no regex `.` wildcard).
5. **Release Tag Derivation, Injection Elimination & Hardcoded `v3.0.0` Fix (`lines 169-189`, `CB-29`)**:
   - Move `github.event.inputs.release_tag` to `env: INPUT_RELEASE_TAG`.
   - Replace hardcoded `v3.0.0` fallback (line 177) with dynamic extraction from `module/module.prop`:
     ```bash
     BASE_VERSION="$(grep -m1 '^version=' module/module.prop | cut -d= -f2- | tr -d '\r\n[:space:]')"
     ```
     (Fail cleanly if empty; prefix with `v` if not already prefixed with `v`.)
   - Validate final `TAG` strictly before using in filenames or writing to `$GITHUB_OUTPUT`:
     - Must match `^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$` AND must NOT contain `..`!
     - Blocks shell metacharacters, newlines (`GITHUB_OUTPUT` injection), slashes (path traversal in `"release_out/Silo-${TAG}.apk"`), and leading `-` (CLI flag injection into `gh release`).
   - Exact replacement for step `Prepare versioned release files & SHA256SUMS`:
     ```yaml
      - name: Prepare versioned release files & SHA256SUMS
        id: meta
        env:
          INPUT_RELEASE_TAG: ${{ github.event.inputs.release_tag }}
        run: |
          if [[ "${GITHUB_REF}" == refs/tags/v* ]]; then
            TAG="${GITHUB_REF#refs/tags/}"
          elif [ -n "$INPUT_RELEASE_TAG" ]; then
            TAG="$INPUT_RELEASE_TAG"
          else
            BASE_VERSION="$(grep -m1 '^version=' module/module.prop | cut -d= -f2- | tr -d '\r\n[:space:]')"
            if [ -z "$BASE_VERSION" ]; then
              echo "::error::Failed to derive version from module/module.prop"
              exit 1
            fi
            case "$BASE_VERSION" in
              v*) ;;
              *) BASE_VERSION="v${BASE_VERSION}" ;;
            esac
            TAG="${BASE_VERSION}-$(date -u +%Y%m%d-%H%M)"
          fi

          if [[ "$TAG" == *".."* ]] || ! printf '%s\n' "$TAG" | grep -qE '^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$'; then
            echo "::error::Invalid or unsafe release tag format: ${TAG}"
            exit 1
          fi

          echo "tag=${TAG}" >> "$GITHUB_OUTPUT"

          mkdir -p release_out
          cp -- release_staging/silo-apk/Silo-release.apk "release_out/Silo-${TAG}.apk"
          cp -- release_staging/silo-ksu-module/silo-ksu-module.zip "release_out/silo-ksu-module-${TAG}.zip"
          (
            cd release_out
            sha256sum -- "Silo-${TAG}.apk" "silo-ksu-module-${TAG}.zip" > SHA256SUMS.txt
          )
          cat release_out/SHA256SUMS.txt
     ```
6. **Create GitHub Release Step Hardening (`lines 190-211`, `CB-29`)**:
   - Move `steps.meta.outputs.tag` and `github.event.inputs.prerelease` to `env:` (`RELEASE_TAG`, `INPUT_PRERELEASE`).
   - Replace unquoted `$PRE_FLAG` word splitting with a proper bash array `GH_FLAGS=()`.
   - When `[[ "${GITHUB_REF}" != refs/tags/* ]]`, add `--target "$GITHUB_SHA"` to `GH_FLAGS` so `workflow_dispatch` pins any new tag to the exact built commit.
   - Use explicit `if gh release view "$RELEASE_TAG" >/dev/null 2>&1; then gh release upload ... --clobber; else gh release create ...; fi` so genuine `gh release create` failures are never masked by a fallback upload error.
   - Exact replacement for step `Create GitHub Release`:
     ```yaml
      - name: Create GitHub Release
        env:
          GH_TOKEN: ${{ github.token }}
          RELEASE_TAG: ${{ steps.meta.outputs.tag }}
          INPUT_PRERELEASE: ${{ github.event.inputs.prerelease }}
        run: |
          GH_FLAGS=()
          if [ "$INPUT_PRERELEASE" = "true" ]; then
            GH_FLAGS+=(--prerelease)
          fi
          if [[ "${GITHUB_REF}" != refs/tags/* ]]; then
            GH_FLAGS+=(--target "$GITHUB_SHA")
          fi

          if gh release view "$RELEASE_TAG" >/dev/null 2>&1; then
            gh release upload "$RELEASE_TAG" \
              "release_out/Silo-${RELEASE_TAG}.apk" \
              "release_out/silo-ksu-module-${RELEASE_TAG}.zip" \
              "release_out/SHA256SUMS.txt" \
              --clobber
          else
            gh release create "$RELEASE_TAG" \
              "release_out/Silo-${RELEASE_TAG}.apk" \
              "release_out/silo-ksu-module-${RELEASE_TAG}.zip" \
              "release_out/SHA256SUMS.txt" \
              --title "Silo ${RELEASE_TAG}" \
              --notes "Silo ${RELEASE_TAG} — Zero-panic, zero-gimmick Linux 6.x kernel privacy & security suite (static ARM64 Rust \`silod\` KernelSU Module + strict offline-first Kotlin/Compose APK with verified zero \`INTERNET\` permission). Note: update BOTH the KernelSU module ZIP (and reboot) and the APK." \
              "${GH_FLAGS[@]}"
          fi
     ```
   - *(Note: double-check backtick escaping inside double-quoted bash `--notes "..."`! In bash, `\`silod\`` inside `"..."` outputs literal `` `silod` ``, which works cleanly and matches the original line 204.)*

---

## 4. Behavioral Sandbox Verification Plan (All M4 Deliverables)

### 4.1 Verified Local Devbox Toolchain & Critical Gotchas

Probed read-only on this devbox:

| Tool | Exact Path | Version / Status | Critical Note |
|---|---|---|---|
| `sh` / `dash` | `/usr/bin/sh` → `dash` | POSIX `dash` | Strict POSIX sh; catches bashisms immediately (great proxy for Android `mksh`) |
| `bash` | `/usr/bin/bash` | GNU bash 5.2.21 | For `scripts/build_module.sh` |
| `file` | `/usr/bin/file` | `file-5.45` | Matches `ubuntu-latest`; supports `-bL --` |
| `zip` / `unzip` | `/usr/bin/zip`, `/usr/bin/unzip` | Zip 3.0, UnZip 6.00 | Supports `unzip -Z1` (filenames-only mode, verified) |
| **`/usr/bin/python3`** | `/usr/bin/python3` | Python 3.12.3 + **`PyYAML 6.0.1`** | **USE `/usr/bin/python3` for YAML checks!** |
| `python3` (PATH) | `/opt/pyenv/shims/python3` | Python 3.12.16 (**NO PyYAML!**) | **GOTCHA**: bare `python3 -c 'import yaml'` fails with `ModuleNotFoundError`! |
| `ruby` | `/usr/bin/ruby` | Stdlib `yaml` available | Alternative YAML validator: `ruby -ryaml -e 'YAML.load_file(...)'` |
| `gcc` | Installed | C compiler available | Enables compiling tiny ELF dummy `silod` in sandbox to exercise real `/proc/$PID/exe` checks! |
| `shellcheck` / `actionlint` | MISSING | Not installed | Use `sh -n`, `dash -n`, `bash -n`, and custom YAML/AST verification script |

### 4.2 End-to-End Isolated Sandbox Test Matrix

All behavioral tests MUST execute inside `SANDBOX="$(mktemp -d)"` with `trap 'rm -rf -- "$SANDBOX"' EXIT` (plus killing any background test children in the trap!) so **zero** repository files are modified and **zero** stray processes/temp dirs remain.

> **Sandbox Path Isolation Pattern for `module/*.sh`**:
> Because production `module/*.sh` scripts reference `/data/adb/silo` and `/data/adb/modules/silo` (unwritable for non-root user on devbox), sandbox tests copy each script into `$SANDBOX/moddir/` and rewrite ONLY the root path literals (`/data/adb/silo` → `$SANDBOX/state`, `/data/adb/modules/silo` → `$SANDBOX/moddir`) via `sed`, then prepend `$SANDBOX/shims` (containing mock `getprop` and optional fast-clock `sleep` shim) to `PATH`. (If the implemented scripts support clean internal constants/overrides, the same rewrite or env injection works seamlessly.)
>
> **CRITICAL `/proc/$PID/exe` vs `/proc/$PID/cmdline` Testing Pattern**:
> - A `#!/bin/sh` dummy `$MODDIR/bin/silod` tests `/proc/$PID/cmdline` matching (on Linux, `/proc/$PID/exe` of a shell script points to `/usr/bin/dash`, NOT `.../silod`).
> - A tiny 12-line C dummy compiled with `gcc -o "$SANDBOX/moddir/bin/silod"` tests REAL Linux `/proc/$PID/exe` ending in `/silod`!
> - Both MUST be tested so neither `/proc/$PID/exe` nor `/proc/$PID/cmdline` verification has blind spots!

#### Suite T1 — Syntax & Static Lint Gate (All Scripts)
```bash
sh -n module/customize.sh module/service.sh module/action.sh module/uninstall.sh
dash -n module/customize.sh module/service.sh module/action.sh module/uninstall.sh
bash -n scripts/build_module.sh
```
- **Pass criteria**: Exit `0` with zero stderr warnings.

#### Suite T2 — Stale Non-`silod` PID Safety (`CB-07`) across `service.sh`, `action.sh`, `uninstall.sh`
1. Spawn innocent process: `sleep 60 & INNOCENT_PID=$!` (real Linux process: `/proc/$INNOCENT_PID/exe` = `/usr/bin/sleep`, `/proc/$INNOCENT_PID/cmdline` = `sleep\060\0`).
2. Write `$INNOCENT_PID` into `$SANDBOX/state/silod.pid`.
3. Run sandboxed `service.sh`, then repeat with `action.sh`, then repeat with `uninstall.sh`.
4. **Assert**: `kill -0 "$INNOCENT_PID"` succeeds AFTER EVERY ONE of the 3 scripts completes! (And clean up `kill "$INNOCENT_PID"` in test teardown.)

#### Suite T3 — Real Dummy `silod` Stop, Wait, `SIGKILL` Escalation & Socket-Unlink Safety (`CB-07`)
- **T3a (Graceful `SIGTERM` wait + zero socket-unlink race)**:
  1. Start old dummy `silod` (both shell script variant AND `gcc`-compiled ELF variant) that traps `SIGTERM`, sleeps `2s`, unlinks `$SANDBOX/state/silod.sock` (simulating old `silod`'s exit cleanup!), then exits `0`.
  2. Write its PID to `silod.pid` and create initial `$SANDBOX/state/silod.sock`.
  3. Run sandboxed `service.sh` (with new dummy `silod` that creates a marker/socket at `$SANDBOX/state/silod.sock` containing its own PID).
  4. **Assert**:
     - Old `silod` is dead (`! kill -0 "$OLD_PID"`).
     - Old `silod` exited BEFORE new `silod` started (verified via monotonic timestamps in `$SANDBOX/events.log`: `old_exit <= new_start`).
     - `$SANDBOX/state/silod.sock` STILL EXISTS and belongs to new `silod` (NOT unlinked by old `silod`!).
- **T3b (Hung `silod` `SIGTERM` → poll wait → `SIGKILL` escalation)**:
  1. Start dummy `silod` ignoring `SIGTERM` (`signal(SIGTERM, SIG_IGN)` in C / `trap '' TERM` in sh).
  2. Write its PID to `silod.pid`.
  3. Run sandboxed `service.sh`, `action.sh`, and `uninstall.sh` (separately).
  4. **Assert**:
     - Stop waits for the bounded grace period (~3–5s, not 0s instant kill),
     - Escalates to `SIGKILL`,
     - `! kill -0 "$HUNG_PID"` BEFORE starting new `silod` or running `--uninstall-cleanup`.

#### Suite T4 — `service.sh` Supervisor, Dead-Man Switch, 300s Boot Wait & Portable `MODDIR` (`CB-18`, `CB-28`)
- **T4a (Bounded crash-restart watchdog)**: Dummy `silod` appends timestamp to `$SANDBOX/starts.log` and immediately `exit 1`. Run sandboxed `service.sh` (with short/shimmed backoff if needed, or observe actual bounded window).
  - **Assert**: Restart count is strictly > 1 AND strictly bounded (e.g., `<= 6` total starts = initial + max 5 restarts), and supervisor exits cleanly (does NOT loop infinitely).
- **T4b (Clean exit `0` — NO restart)**: Dummy `silod` logs start and `exit 0`.
  - **Assert**: `wc -l < starts.log == 1`; supervisor exits without respawning.
- **T4c (`$MODDIR/disable` marker stops supervisor)**: Dummy `silod` creates `$SANDBOX/moddir/disable` then `exit 1`.
  - **Assert**: `wc -l < starts.log == 1`; supervisor notices `disable` and exits cleanly.
- **T4d (`SIGTERM` to supervisor / `silod`)**: Dummy `silod` sleeps cleanly; send `SIGTERM` to supervisor (and in a separate run, `SIGTERM` directly to child `silod` so exit status is `143`).
  - **Assert**: Neither case triggers respawn; both supervisor and child terminate cleanly.
- **T4e (Dead-Man Switch `.boot_fail_count >= 2`)**: Seed `$SANDBOX/state/.boot_fail_count` with `2`. Run sandboxed `service.sh`.
  - **Assert**: Dummy `silod` called with `--emergency-flush` (recorded in `args.log`), `$SANDBOX/moddir/disable` created, `.boot_fail_count` removed, daemon NOT started.
- **T4f (300s boot-wait timeout)**: Mock `getprop` always returns `""`; mock `sleep` appends `$1` to `$SANDBOX/slept.log` and returns immediately without real delay. Run sandboxed `service.sh`.
  - **Assert**: Sum of `slept.log` is `>= 300` (proving timeout raised from 180s to 300s!), exit code is `0`, `silod` NOT started, `.boot_fail_count` NOT incremented.
- **T4g (Portable `MODDIR` without `/` in `$0`)**: `( cd "$SANDBOX/moddir" && sh service.sh )` with `getprop` returning `1`.
  - **Assert**: Script resolves `MODDIR` to `$SANDBOX/moddir` (NOT literal `service.sh`), locates `$MODDIR/bin/silod`, and starts cleanly.

#### Suite T5 — `customize.sh` Architecture Fallback, Validation & `disable` Cleanup (`CB-18`, `CB-28`)
Run sandboxed `customize.sh` via `sh -c 'MODPATH="..."; ui_print() { echo "$*"; }; abort() { echo "ABORT: $*" >&2; exit 1; }; . "$SCRIPT"'`:
- **T5a**: `getprop ro.product.cpu.abi` → `arm64-v8a` → **PASS** (exit 0).
- **T5b**: `getprop ro.product.cpu.abi` → `""`, `getprop ro.product.cpu.abilist64` → `arm64-v8a` → **PASS** (exit 0).
- **T5c**: Both `getprop` empty, env `ARCH=arm64` → **PASS** (exit 0).
- **T5d**: `ro.product.cpu.abi=armeabi-v7a`, `abilist64=""`, `ARCH=arm` → **ABORT** (non-zero exit).
- **T5e**: Missing `$MODPATH/bin/silod` → **ABORT** (non-zero exit).
- **T5f**: Pre-create `$SANDBOX/moddir/disable` AND `$SANDBOX/state/.boot_fail_count`; run valid install → **ASSERT**:
  - `$SANDBOX/moddir/disable` removed,
  - `$SANDBOX/state/.boot_fail_count` removed,
  - `stat -c%a "$SANDBOX/state"` == `700`,
  - `stat -c%a` on `bin/silod`, `service.sh`, `action.sh`, `uninstall.sh` == `755`.

#### Suite T6 — `action.sh` Live Status, Recovery & `disable` Removal (`CB-07`, `CB-18`, `CB-28`)
- **T6a (Already healthy)**: Create real Unix domain socket at `$SANDBOX/state/silod.sock` (via Python `socket.AF_UNIX`), mock `silod --status` returns `0` + `{"ok":true}`. Run `action.sh`.
  - **Assert**: Prints `[OK]`, prints status JSON, does NOT kill/spawn `silod`.
- **T6b (Dead/hung recovery + `disable` cleanup)**: Pre-create `$SANDBOX/moddir/disable`, `.boot_fail_count`, and a hung dummy `silod` (plus separately an innocent non-silod PID). Mock `silod --status` fails before launch, succeeds after launch. Run `action.sh`.
  - **Assert**: `$SANDBOX/moddir/disable` removed, `.boot_fail_count` removed, hung `silod` terminated before restart (while innocent PID survives untouched), new `silod` started and responding `[OK]`.
- **T6c (Portable `MODDIR`)**: `( cd "$SANDBOX/moddir" && sh action.sh )` resolves `MODDIR` cleanly.

#### Suite T7 — `uninstall.sh` Cleanup Contract & Fallback (`CB-07`, `CB-19`, `CB-28`)
- **T7a (Primary `--uninstall-cleanup` path)**: Pre-create `$SANDBOX/state/config.json`. Dummy `silod` verifies `config.json` STILL EXISTS when invoked, logs its argv to `$SANDBOX/uninstall_args.log`, and exits `0` for `--uninstall-cleanup`. Run sandboxed `uninstall.sh`.
  - **Assert**: `uninstall_args.log` contains `--uninstall-cleanup` and `--config` pointing to `config.json`, `config.json` existed during the call, AND `$SANDBOX/state` is completely removed AFTER `silod` exits!
- **T7b (Fallback `--emergency-flush` path)**: Dummy `silod` exits `2` on `--uninstall-cleanup` (simulating older binary or unrecognized flag) and exits `0` on `--emergency-flush`. Run sandboxed `uninstall.sh`.
  - **Assert**: `uninstall_args.log` shows `--uninstall-cleanup` attempted first, followed by `--emergency-flush` fallback, followed by `rm -rf "$STATE_DIR"`.
- **T7c (PID safety + portable `MODDIR`)**: Innocent PID survives; genuine dummy `silod` (both responsive and hung) is terminated BEFORE cleanup; `( cd "$SANDBOX/moddir" && sh uninstall.sh )` works without `/` in `$0`.

#### Suite T8 — `scripts/build_module.sh` Exhaustive Packaging & Stealth Tests (`CB-28`)
Copy `scripts/build_module.sh` and `module/` into `$SANDBOX/repo/`, generate synthetic ELF binaries with Python `struct.pack` (same verified headers from §2.2), and run ALL 13 test cases with `TMPDIR="$SANDBOX/tmp_check"` (asserting `$SANDBOX/tmp_check` is empty after every run):

1. **T8a — Static ARM64 `ET_EXEC` (`statically linked`)**: SUCCEEDS; `unzip -Z1` returns exact expected set `{action.sh, bin/, bin/silod, customize.sh, module.prop, service.sh, uninstall.sh}`; `unzip -Z` shows `-rwxr-xr-x` on scripts & `bin/silod` and `-rw-r--r--` on `module.prop`.
2. **T8b — Static-PIE ARM64 `ET_DYN+DF_1_PIE` (`static-pie linked`)**: SUCCEEDS.
3. **T8c — Dynamic ARM64 `ET_DYN+PT_INTERP`**: FAILS (non-zero exit), no output ZIP created.
4. **T8d — Static x86-64 `ET_EXEC`**: FAILS.
5. **T8e — ARM64 shared library `.so` (`ET_DYN` without `DF_1_PIE`), ARM64 big-endian `MSB`, plain shell script**: ALL 3 FAIL.
6. **T8f — Filename-spoofed non-ELF** (`$SANDBOX/ELF 64-bit LSB executable, ARM aarch64, statically linked/fake`): FAILS.
7. **T8g — Symlink to valid static ARM64 ELF**: SUCCEEDS; extracted `bin/silod` is a regular ELF file, not a symlink.
8. **T8h — Stray files in `module/`** (`module/stray.txt`, `module/service.sh~`, `module/.hidden_note`): SUCCEEDS; `unzip -Z1` confirms NONE of those 3 files are in the ZIP!
9. **T8i — Missing required file** (delete `module/action.sh` in sandbox copy): FAILS with error naming `action.sh`.
10. **T8j — Expanded zero-mount rejection (ALL 5 partitions)**: For each `ov` in `system vendor product system_ext odm`:
    - Create `module/$ov/testfile` → FAILS with stealth violation, no output ZIP created.
    - Replace with bare file `module/$ov` → FAILS with stealth violation, no output ZIP created.
11. **T8k — Clean overwrite of pre-existing ZIP**: Create `$SANDBOX/out.zip` containing `stale_entry.txt`; run `build_module.sh` → SUCCEEDS and `unzip -Z1` confirms `stale_entry.txt` is NOT present!
12. **T8l — Nested output dir creation & directory-path rejection**:
    - `$SANDBOX/a/b/c/mod.zip` → parent dirs created, SUCCEEDS.
    - `$SANDBOX/a/b/c/` or existing dir `$SANDBOX/a/b/c` → FAILS cleanly.
13. **T8m — Zero temp-dir leak**: After BOTH passing and failing invocations with `TMPDIR="$SANDBOX/tmp_check"`, `[ -z "$(ls -A "$SANDBOX/tmp_check")" ]` holds true!

#### Suite T9 — `.github/workflows/build.yml` AST, Security & Behavioral Checks (`CB-29`)
Execute via `/usr/bin/python3` (which has `PyYAML 6.0.1` — also cross-check with `ruby -ryaml`):

```bash
/usr/bin/python3 - <<'PY'
import re, yaml

with open(".github/workflows/build.yml", "r", encoding="utf-8") as f:
    wf = yaml.safe_load(f)

# 1. Least-privilege permissions
assert wf.get("permissions") == {"contents": "read"}, f"Bad top-level permissions: {wf.get('permissions')}"
jobs = wf["jobs"]
assert "permissions" not in jobs["daemon-and-module"], "daemon-and-module must not elevate permissions"
assert "permissions" not in jobs["android-app"], "android-app must not elevate permissions"
assert jobs["release"].get("permissions") == {"contents": "write"}, f"Bad release permissions: {jobs['release'].get('permissions')}"

# 2. ZERO ${{ ... }} direct interpolation in ANY run: block across ALL jobs
for job_name, job in jobs.items():
    for i, step in enumerate(job.get("steps", [])):
        run_block = step.get("run")
        if run_block:
            assert "${{" not in run_block, (
                f"Direct GHA expression found in run: of {job_name} step #{i} ({step.get('name')}): {run_block}"
            )

# 3. daemon-and-module runs bash -n scripts/build_module.sh and expanded zero-mount unzip -Z1 check
dm_runs = "\n".join(s.get("run", "") for s in jobs["daemon-and-module"]["steps"])
assert "bash -n scripts/build_module.sh" in dm_runs, "Missing bash -n scripts/build_module.sh in CI"
assert "unzip -Z1" in dm_runs, "Zero-mount CI check should use unzip -Z1"
for ov in ("system", "vendor", "product", "system_ext", "odm"):
    assert ov in dm_runs, f"Missing overlay '{ov}' in CI zero-mount check"

# 4. release job derives fallback version from module/module.prop (no hardcoded v3.0.0 tag)
rel_runs = "\n".join(s.get("run", "") for s in jobs["release"]["steps"])
assert "module/module.prop" in rel_runs, "Release job must derive fallback version from module/module.prop"
assert 'TAG="v3.0.0-' not in rel_runs, "Hardcoded v3.0.0 fallback tag still present!"

print("[PASS] All .github/workflows/build.yml AST & security assertions passed!")
PY
ruby -ryaml -e 'YAML.load_file(".github/workflows/build.yml"); puts "[PASS] ruby YAML cross-check OK"'
```

Plus behavioral shell checks on the release tag derivation & validation logic:
- Extract/run the tag derivation block against:
  - Real `module/module.prop` with empty `GITHUB_REF` & empty `INPUT_RELEASE_TAG` → produces `v3.0.0-YYYYMMDD-HHMM` (exit 0).
  - Sandbox `module.prop` with `version=v3.4.5` → produces `v3.4.5-YYYYMMDD-HHMM` (exit 0).
  - Sandbox `module.prop` with `version=4.0.0` (no `v`) → produces `v4.0.0-YYYYMMDD-HHMM` (exit 0).
  - Injection attempt `INPUT_RELEASE_TAG='v3.0.0"; touch /tmp/should_not_exist; #'` → FAILS (non-zero), `/tmp/should_not_exist` NOT created!
  - Traversal attempt `INPUT_RELEASE_TAG='../../evil'` → FAILS!
  - Flag injection `INPUT_RELEASE_TAG='--clobber'` → FAILS!
  - Valid custom tag `INPUT_RELEASE_TAG='v3.0.1-rc.1'` → PASSES!

---

## 5. Caveats & Notes for Worker / Reviewers / Challengers / Auditor

1. **Zero-Diff Outside M4 Ownership**: M4 owns ONLY `module/**`, `scripts/**`, `.github/workflows/build.yml`. Do NOT edit `daemon/Cargo.toml` (`CB-30` is M1) or `app/**` (M3).
2. **`module/module.prop` untouched**: `module/module.prop` already has accurate `id=silo`, `version=v3.0.0`, `versionCode=30000` aligned with `app/app/build.gradle.kts` and `daemon/Cargo.toml`. No edit required to `module/module.prop`.
3. **PyYAML devbox caveat**: Always invoke `/usr/bin/python3` (NOT `python3`, which points to pyenv without `PyYAML`) or `ruby -ryaml` when parsing `.github/workflows/build.yml`.
4. **Cross-Explorer Alignment**: Coordinate `scripts/build_module.sh` required file list (`module.prop`, `customize.sh`, `service.sh`, `action.sh`, `uninstall.sh`, `bin/silod`) with `iter1_explorer_1` and `iter1_explorer_2` — if no extra helper file is introduced under `module/`, this exact 5-file whitelist stays 100% consistent (if a shared helper were ever added, both `build_module.sh` and `build.yml` required lists would need to include it, though keeping each KSU script self-contained with zero extra files is preferred for simplicity and `customize.sh` standalone sourcing!).
