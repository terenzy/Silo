# Briefing — `sub_orch_m3_android_app`

## 🔒 My Identity
- **Agent Role**: Milestone M3 Sub-Orchestrator (`android-app`, DISPATCH-ONLY under the Project Pattern)
- **Conversation ID**: `61dc89a1bcda9db55cf5a47a3576d5ea`
- **State Folder**: `/home/antigravity/terenzy/Silo/.agents/teamwork/sub_orch_m3_android_app`
- **Project Root**: `/home/antigravity/terenzy/Silo`

## 🔒 My Workflow
- Single 2B Iteration Loop (Max 32 iterations) covering all 53 `APP-*` defects in `app/**`:
  - **a. Explore**: 3 parallel `teamwork_preview_explorer` subagents covering all `app/**` modules and contracts.
  - **b. Implement**: 1 `teamwork_preview_worker` subagent with exclusive ownership of `app/**`, mandatory verbatim integrity warning, canonical build/test & `aapt2` checks.
  - **c, d, e. Parallel Verification Wave** (strictly AFTER Worker's final edit):
    - 2 × `teamwork_preview_reviewer` (Primary: full `./gradlew testDebugUnitTest assembleRelease --no-daemon` + `aapt2` zero-INTERNET gate; Secondary: target-scoped unit tests + code/contract review)
    - 2 × `teamwork_preview_challenger` (empirical adversarial probes in `mktemp -d -- "${TMPDIR:-/tmp}/teamwork_probe.XXXXXX"` with `chmod 700`, cleaned up before reporting)
    - 1 × `teamwork_preview_auditor` (Forensic Integrity Auditor — **intermediate milestone audit** with sub-scope `app/`)
  - **f. Gate**: Read every verifier's `handoff.md` directly. Auditor evaluated FIRST (INTEGRITY VIOLATION = unconditional FAIL). All 4 strict AND criteria required to PASS.

## 🔒 Key Constraints
- DISPATCH-ONLY: NEVER edit source/test code, NEVER run build/test commands, NEVER inspect source code directly.
- Exclusive ownership: `app/**` ONLY. Never modify `daemon/`, `module/`, `scripts/`, `.github/`, or `tests/`.
- Always pass `/home/antigravity/terenzy/Silo/.agents/teamwork/ORIGINAL_REQUEST.md` verbatim to EVERY subagent.
- Never reuse a subagent after it has delivered its handoff; always spawn fresh.
- Never put source code, tests, or data under `.agents/teamwork/`.

## Current Parent
- Top-Level Project Orchestrator: `5ee4b475a7063ff35a048e6a278d18c3`

## Team Roster
- `iter1_explorer_1` (App Explorer: Perms & UI): `ef236840533a5a291f25f0c3fd79eea3` (DONE)
- `iter1_explorer_2` (App Explorer: IPC, VM & Monitor): `311c17232bff7bd85f75eeb4e1ed32dc` (RUNNING)
- `iter1_explorer_3` (App Explorer: Tiles, Home, System): `f8810027457052572e82e4c87d0c3c57` (DONE)

## Succession Status
- Active (Iteration 1 — Explore phase)

## Active Timers
- None (`schedule` tool not exposed in this environment; using reactive subagent wakeup + `manage_subagents(Action="list")` checks on wakeup)

## Artifact Index
- Original Request: `/home/antigravity/terenzy/Silo/.agents/teamwork/ORIGINAL_REQUEST.md`
- User Screenshot: `/home/antigravity/Screenshot_20261122_223732_Silo.jpg`
- Project Plan & Interface Contracts: `/home/antigravity/terenzy/Silo/PROJECT.md`
- Step 0 Survey 1 (53 `APP-*` defects): `/home/antigravity/terenzy/Silo/.agents/teamwork/explorer_survey_1/handoff.md`
- Step 0 Survey 3 (Cross-boundary & toolchain): `/home/antigravity/terenzy/Silo/.agents/teamwork/explorer_survey_3/handoff.md`
- Dispatch Record: `/home/antigravity/terenzy/Silo/.agents/teamwork/sub_orch_m3_android_app/DISPATCH.md`
- Progress Tracker: `/home/antigravity/terenzy/Silo/.agents/teamwork/sub_orch_m3_android_app/progress.md`
- Gate Status: `/home/antigravity/terenzy/Silo/.agents/teamwork/sub_orch_m3_android_app/GATE_STATUS.md`
