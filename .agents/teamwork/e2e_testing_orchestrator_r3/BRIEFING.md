# BRIEFING.md — E2E Testing Track Sub-Orchestrator (Round 3)

## 🔒 My Identity
- **Conversation ID**: `f8a740c89ae8d5a8ab44ca85a23a342c`
- **Role**: DISPATCH-ONLY Sub-Orchestrator for the Independent E2E Testing Track (Round 3 resumption) of Silo v3.0.0.
- **Working Directory**: `/home/antigravity/terenzy/Silo`
- **State Folder**: `/home/antigravity/terenzy/Silo/.agents/teamwork/e2e_testing_orchestrator_r3`
- **Exclusive Owned Paths**: `tests/e2e/**`, `TEST_INFRA.md`, `TEST_READY.md` (publish `TEST_READY.md` ONLY after Gate PASS).

## 🔒 My Workflow
1. Record dispatch in `DISPATCH.md`, maintain `BRIEFING.md` and `progress.md`, read `ORIGINAL_REQUEST.md`, `PROJECT.md`, `TEST_INFRA.md`, and the 3 R1 E2E Explorer handoffs.
2. Iteration 1: SKIP Step 1a Explorers (R1 Explorer handoffs already complete). Run Step 1b (`teamwork_preview_test_writer` in `iter1_test_writer_r3`) to audit, fix if needed, and verify all 39 `tests/e2e/**` files and harness modules.
3. Strictly AFTER Step 1b completes, launch the 5-agent Parallel Verification Wave concurrently:
   - 2 `teamwork_preview_reviewer`s (1 primary full verification runner, 1 secondary contract/coverage/opaque-box quality reviewer)
   - 2 `teamwork_preview_challenger`s (adversarial anti-tautology mutation probes in `/tmp/teamwork_probe.*`, cleaned up before reporting)
   - 1 `teamwork_preview_auditor` (Forensic Auditor, labeled **intermediate track audit** explicitly scoped to `tests/e2e/`, `TEST_INFRA.md`, `TEST_READY.md`)
4. Enforce Step 1f Strict AND Gate: read every verifier's `handoff.md`, evaluate Auditor FIRST (binary veto), record in `GATE_STATUS.md`. Iterate up to 32 iterations on failure (with Oscillation/Regression/Integrity routing).
5. Strictly AFTER Gate PASS: publish `/home/antigravity/terenzy/Silo/TEST_READY.md`, write Hard Handoff in `handoff.md`, and send completion via `send_message` to Current Parent.

## 🔒 Key Constraints
- NEVER write/modify source code, test code, scripts, or CI files directly; delegate all coding/verification to subagents.
- NEVER run build, test, lint, or git commands directly.
- NEVER inspect source code directly; read only `.md` state/planning/report files.
- NEVER put code/probes/tests/data in `.agents/teamwork/` and NEVER name a file `AGENTS.md` or `GEMINI.md`.
- Wave 1 Parallel Build-Lock Rule: NEVER run `./gradlew`; NEVER write to `daemon/target/`; always isolate any `cargo` target dir to `CARGO_TARGET_DIR=<probe>/cargo_target` inside `/tmp/teamwork_probe.*` (cleaned up before reporting) and do not block on transient M1 `daemon/` compile state.
- Pass `/home/antigravity/terenzy/Silo/.agents/teamwork/ORIGINAL_REQUEST.md` verbatim in EVERY subagent dispatch.
- Fresh unique state subfolder for EVERY spawned subagent; never reuse a subagent after `handoff.md`.
- Include verbatim integrity warning in every Test Writer / Worker dispatch.

## Current Parent
- `058a70a41cdd8415cd242bd3f8eb9136` (Project Orchestrator)

## Team Roster
- Cumulative spawns: `1`
- Active subagents:
  - `f07ed398da6dbcd67ec84c0b15819e96` — `teamwork_preview_test_writer` (`E2E Test Writer R3`, state: `iter1_test_writer_r3`)
- Completed subagents: none yet
- Pre-existing R1 Explorer baselines (read-only inputs):
  - `e2e_explorer_daemon/handoff.md`
  - `e2e_explorer_app/handoff.md`
  - `e2e_explorer_module_ci/handoff.md`

## Succession Status
- Generation: 3 (Round 3 resumption)
- Succession needed: No (`1 / 20` spawns)
- Successor: None
