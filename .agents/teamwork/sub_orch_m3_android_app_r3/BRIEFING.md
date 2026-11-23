# BRIEFING.md — M3 (`android-app`, Round 3 Resumption)

## 🔒 My Identity
- **Role**: DISPATCH-ONLY Sub-Orchestrator for Milestone M3 (`android-app`, Round 3 resumption) of the Silo v3.0.0 project.
- **Conversation ID**: `515b14d491c04e3348a65d4cfc35e02c`
- **State Folder**: `/home/antigravity/terenzy/Silo/.agents/teamwork/sub_orch_m3_android_app_r3`
- **Working Directory**: `/home/antigravity/terenzy/Silo`
- **Owned Scope**: `app/**` ONLY (Kotlin sources, JVM unit tests, `AndroidManifest.xml`, `proguard-rules.pro`, Gradle/resources).

## 🔒 My Workflow
- Project Pattern 2B (max 32 iterations).
- Iteration 1 skips Step 1a Explorers (explicitly supplied completed R1 Explorer handoffs: `iter1_explorer_1`, `iter1_explorer_2`, `iter1_explorer_3`, plus `explorer_survey_1`) and proceeds directly to Step 1b (`iter1_worker_r3`).
- Strictly AFTER Step b completes, launch 5-verifier Parallel Verification Wave concurrently: Primary `teamwork_preview_reviewer`, Secondary `teamwork_preview_reviewer`, 2 `teamwork_preview_challenger`s, 1 `teamwork_preview_auditor` (**intermediate milestone audit** explicitly scoped to `app/`).
- Enforce Step f Strict AND Gate: read every verifier's `handoff.md` directly, evaluate Auditor FIRST (binary veto on `INTEGRITY VIOLATION` / `CHEATING DETECTED`), require independent post-edit build/test pass + all Reviewers `APPROVE` + all Challengers confirm correctness + Auditor `CLEAN`.
- Record gate verdicts in `GATE_STATUS.md`, oscillation dead ends in `DEAD_ENDS.md`, and write self-contained Hard Handoff in `handoff.md` upon PASS before reporting to `Current Parent`.

## 🔒 Key Constraints
- NEVER write, modify, or create source code, test code, scripts, or CI files directly.
- NEVER run build, test, lint, or git commands directly.
- NEVER explore source code directly; read only `.md` reports/state/planning files.
- Edit ONLY `.md` state files in `/home/antigravity/terenzy/Silo/.agents/teamwork/sub_orch_m3_android_app_r3`.
- Include `/home/antigravity/terenzy/Silo/.agents/teamwork/ORIGINAL_REQUEST.md` verbatim in EVERY subagent dispatch.
- Include verbatim integrity warning in every Worker dispatch.
- Fresh unique state folder per subagent; never share folders or reuse completed subagents.
- Never place source code, probes, tests, or data files in `.agents/teamwork/`.
- Exclusive ownership: `app/**` ONLY; never touch `daemon/`, `module/`, `scripts/`, `.github/`, or `tests/`.

## Current Parent
- **Conversation ID**: `058a70a41cdd8415cd242bd3f8eb9136` (Parent Project Orchestrator)

## Team Roster
- **Baseline R1 Explorers (Completed, reused by parent instruction)**:
  - `iter1_explorer_1`: `/home/antigravity/terenzy/Silo/.agents/teamwork/sub_orch_m3_android_app/iter1_explorer_1/handoff.md`
  - `iter1_explorer_2`: `/home/antigravity/terenzy/Silo/.agents/teamwork/sub_orch_m3_android_app/iter1_explorer_2/handoff.md`
  - `iter1_explorer_3`: `/home/antigravity/terenzy/Silo/.agents/teamwork/sub_orch_m3_android_app/iter1_explorer_3/handoff.md`
  - `explorer_survey_1`: `/home/antigravity/terenzy/Silo/.agents/teamwork/explorer_survey_1/handoff.md`
- **Cumulative Spawns (this sub-orchestrator)**: 1 (limit 20 before succession)
- **Active Subagents**:
  - `iter1_worker_r3` (`teamwork_preview_worker`, `b5ed343f8289703e870f456fb9d57c00`) — state: `/home/antigravity/terenzy/Silo/.agents/teamwork/sub_orch_m3_android_app_r3/iter1_worker_r3` (Step 1b)

## Succession Status
- Generation: R3 (fresh sub-orchestrator spawned by Parent)
- Successor: None
