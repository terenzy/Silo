# BRIEFING — `sub_orch_m4_module_ci`

## 🔒 My Identity
- **Agent**: `sub_orch_m4_module_ci` (Milestone M4 Sub-Orchestrator: `module-scripts-ci`)
- **Conversation ID**: `15cf6e299e01b46011e1dd0d1dc1fcc5`
- **State Folder**: `/home/antigravity/terenzy/Silo/.agents/teamwork/sub_orch_m4_module_ci`
- **Repository**: `/home/antigravity/terenzy/Silo`
- **Scope**: Feature F12 (`CB-07`/`D-13`, `CB-18`, `CB-19`, `CB-28`, `CB-29`) strictly within `module/service.sh`, `module/customize.sh`, `module/action.sh`, `module/uninstall.sh`, `module/module.prop` (read-only unless needed), `scripts/build_module.sh`, `.github/workflows/build.yml`.

## 🔒 My Workflow
- Dispatch-only Project Pattern 2B Iteration Loop (Max 32 iterations):
  1. **Explore**: 3 `teamwork_preview_explorer`s in parallel -> synthesize findings & concrete implementation plan.
  2. **Implement**: 1 `teamwork_preview_worker` with exclusive M4 ownership & mandatory integrity warning -> wait for completion.
  3. **Verify (Parallel Wave)**: Strictly after Worker finishes, dispatch 2 `teamwork_preview_reviewer`s + 2 `teamwork_preview_challenger`s + 1 `teamwork_preview_auditor` (intermediate milestone audit scoped to `module/`, `scripts/`, `.github/workflows/build.yml`).
  4. **Gate**: Read all 5 `handoff.md` files directly, evaluate Forensic Auditor FIRST (binary veto), then strict AND across independent tests + 2x APPROVE + 2x Challenger confirm + Auditor CLEAN -> record in `GATE_STATUS.md`.
  5. **Complete**: Cancel any timers, write self-contained `handoff.md`, notify parent via `send_message`.

## 🔒 Key Constraints
- NEVER edit source/test files or run builds/tests directly; NEVER inspect source code directly (delegate to Explorers/Worker/Verifiers).
- Edit ONLY `.md` state files in `/home/antigravity/terenzy/Silo/.agents/teamwork/sub_orch_m4_module_ci/`.
- `run_command` ONLY for `mkdir -p` under `.agents/teamwork/`.
- Never reuse subagents after handoff — always spawn fresh.
- Include `/home/antigravity/terenzy/Silo/.agents/teamwork/ORIGINAL_REQUEST.md` verbatim in EVERY subagent dispatch.
- Forensic Auditor is non-skippable and has an unconditional binary veto on INTEGRITY VIOLATION / CHEATING DETECTED.

## Current Parent
- **Parent Conversation ID**: `5ee4b475a7063ff35a048e6a278d18c3` (Top-Level Project Orchestrator)

## Team Roster
- **Iteration 1 Explorers (Active)**:
  - `iter1_explorer_1` (Module Lifecycle Explorer) — `15c3c7c99dbfc94e41d3653fe3fbdc5e` — `/home/antigravity/terenzy/Silo/.agents/teamwork/sub_orch_m4_module_ci/iter1_explorer_1/handoff.md`
  - `iter1_explorer_2` (Survivability & Installer Explorer) — `4f80e3f44996eb9e3556ea0c681632dc` — `/home/antigravity/terenzy/Silo/.agents/teamwork/sub_orch_m4_module_ci/iter1_explorer_2/handoff.md`
  - `iter1_explorer_3` (Packaging & CI Explorer) — `b977be1e296bf20e35061bb37f3048f9` — `/home/antigravity/terenzy/Silo/.agents/teamwork/sub_orch_m4_module_ci/iter1_explorer_3/handoff.md`

## Succession Status
- Active (Iteration 1 — Explore phase)

## Active Timers
- None (`schedule` tool is not exposed in this runtime environment; relying on reactive wakeup + `manage_subagents(Action="list")` liveness checks).

## Artifact Index
- Original Request: `/home/antigravity/terenzy/Silo/.agents/teamwork/ORIGINAL_REQUEST.md`
- Project Plan: `/home/antigravity/terenzy/Silo/PROJECT.md`
- Survey 2: `/home/antigravity/terenzy/Silo/.agents/teamwork/explorer_survey_2/handoff.md`
- Survey 3: `/home/antigravity/terenzy/Silo/.agents/teamwork/explorer_survey_3/handoff.md`
- Dispatch Log: `/home/antigravity/terenzy/Silo/.agents/teamwork/sub_orch_m4_module_ci/DISPATCH.md`
- Progress: `/home/antigravity/terenzy/Silo/.agents/teamwork/sub_orch_m4_module_ci/progress.md`
