# Incoming Parent Dispatch — 2026-11-23T00:03:44Z

- **Sender (Parent Orchestrator)**: `7edd17c10dd55bcbf5d01501e2a243f9`
- **Assigned Role**: Milestone M4 (`module-scripts-ci`) DISPATCH-ONLY Sub-Orchestrator (`sub_orch_m4_module_ci_r2`)
- **Working Directory**: `/home/antigravity/terenzy/Silo`
- **State Folder**: `/home/antigravity/terenzy/Silo/.agents/teamwork/sub_orch_m4_module_ci_r2`
- **Original Request**: `/home/antigravity/terenzy/Silo/.agents/teamwork/ORIGINAL_REQUEST.md`
- **Master Plan**: `/home/antigravity/terenzy/Silo/PROJECT.md`

## Exclusive File Ownership
- `module/service.sh`
- `module/customize.sh`
- `module/action.sh`
- `module/uninstall.sh`
- `module/module.prop` (read-only unless needed)
- `scripts/build_module.sh`
- `.github/workflows/build.yml`

(Do NOT touch `daemon/`, `app/`, or `tests/`.)

## Preserved Prior Exploration Artifacts (Read-Only)
- `/home/antigravity/terenzy/Silo/.agents/teamwork/explorer_survey_2/handoff.md`
- `/home/antigravity/terenzy/Silo/.agents/teamwork/explorer_survey_3/handoff.md`
- `/home/antigravity/terenzy/Silo/.agents/teamwork/sub_orch_m4_module_ci/iter1_explorer_2/handoff.md` (Survivability & Installer `CB-18`/`CB-28`)
- `/home/antigravity/terenzy/Silo/.agents/teamwork/sub_orch_m4_module_ci/iter1_explorer_3/handoff.md` (Packaging & CI `CB-28`/`CB-29`)

## Required Remediations (Feature F12: `CB-07`/`D-13`, `CB-18`, `CB-19`, `CB-28`, `CB-29`, `PROJECT.md § Interface Contracts #4`)
1. Eliminate stale PID-reuse random process kill & socket unlink race across `module/service.sh`, `module/action.sh`, `module/uninstall.sh`: verify `/proc/$PID/cmdline` (or `/proc/$PID/exe`) matches `silod` before `SIGTERM`/`SIGKILL`, wait up to 5s for exit before spawning a new daemon, and avoid clobbering an active daemon socket.
2. Daemon survivability (`service.sh`, `customize.sh`, `action.sh`): 300s boot-completed wait, bounded crash-restart supervisor watchdog (clean exit on exit code 0 / `SIGTERM` / uninstall / `disable` marker), clear `$MODDIR/disable` marker in `customize.sh` and `action.sh`.
3. Complete uninstall cleanup (`uninstall.sh`): invoke `$BIN --uninstall-cleanup --config "$CFG_PATH"` with fallback to `--emergency-flush --config "$CFG_PATH"` before removing `/data/adb/silo`.
4. Portable `MODDIR` resolution across all 4 `module/*.sh`, `$ARCH` / `ro.product.cpu.abilist64` fallback in `customize.sh`, explicit module file copy & expanded zero-mount check (`system|vendor|product|system_ext|odm`) in `scripts/build_module.sh`.
5. CI security & hygiene in `.github/workflows/build.yml`: least-privilege `permissions: contents: read` at workflow level (`contents: write` ONLY on `release` job), `env:` escaping for `${{ github.event.inputs.* }}`, fallback version from `module/module.prop`, expanded zero-mount ZIP verification.

## Execution Plan
- Mode: **2B Direct Iteration Loop** (resuming Step 1a with 1 missing Explorer `iter1_explorer_1_r2` to complement preserved `iter1_explorer_2` and `iter1_explorer_3`).
- Step 1a: Spawn `iter1_explorer_1_r2` (Module Lifecycle Explorer: `service.sh`, `action.sh`, `uninstall.sh`, `CB-07`/`D-13`/`CB-19`).
- Step 1b: Spawn 1 `teamwork_preview_worker` with full 3-explorer synthesis + verbatim integrity warning.
- Steps 1c–e: Parallel Verification Wave (2 Reviewers + 2 Challengers + 1 Forensic Auditor intermediate milestone audit).
- Step 1f: Strict AND Gate + Auditor binary veto. Iterate if FAIL; on PASS write Hard Handoff and report to parent `7edd17c10dd55bcbf5d01501e2a243f9`.
