# Parent Dispatch Log — `sub_orch_m4_module_ci`

## Dispatch #1 — 2026-11-22T16:12:04Z
- **From**: Top-Level Project Orchestrator (`5ee4b475a7063ff35a048e6a278d18c3`)
- **Role**: Milestone M4 Sub-Orchestrator (`module-scripts-ci`)
- **Working Directory**: `/home/antigravity/terenzy/Silo`
- **State Folder**: `/home/antigravity/terenzy/Silo/.agents/teamwork/sub_orch_m4_module_ci`
- **Exclusive File Ownership**:
  - `module/service.sh`
  - `module/customize.sh`
  - `module/action.sh`
  - `module/uninstall.sh`
  - `module/module.prop` (read-only unless needed)
  - `scripts/build_module.sh`
  - `.github/workflows/build.yml`
- **Required Remediations (F12 — `CB-07`/`D-13`, `CB-18`, `CB-19`, `CB-28`, `CB-29`)**:
  1. Eliminate stale PID-reuse random process kill & socket unlink race (`service.sh`, `uninstall.sh`, `action.sh`).
  2. Daemon survivability (300s boot wait, bounded crash-restart supervisor, clean exit on `0`/`SIGTERM`/`uninstall`/`disable`), disable marker removal in `customize.sh` & `action.sh`.
  3. Complete uninstall cleanup (`--uninstall-cleanup --config "$CFG_PATH"` with fallback to `--emergency-flush --config "$CFG_PATH"`).
  4. Portable `MODDIR` across all 4 `module/*.sh`, `$ARCH`/`abilist64` fallback in `customize.sh`, explicit module file copy & expanded zero-mount check (`system|vendor|product|system_ext|odm`) in `scripts/build_module.sh`.
  5. CI security & hygiene in `.github/workflows/build.yml`: least-privilege `contents: read` top-level / `contents: write` only on `release`, `env:` escaping for `${{ github.event.inputs.* }}`, fallback version from `module/module.prop`, expanded zero-mount ZIP check.
- **Verification Requirements**:
  - `sh -n` on all 4 `module/*.sh`
  - `bash -n` on `scripts/build_module.sh`
  - YAML validity + GHA expression injection check on `.github/workflows/build.yml`
  - Isolated behavioral sandbox tests (temp dirs cleaned up after run)
  - Intermediate milestone Forensic Audit scoped to `module/`, `scripts/`, `.github/workflows/build.yml`.
