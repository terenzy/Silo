# Briefing — `e2e_testing_orchestrator`

## 🔒 My Identity
- **Role**: Independent E2E Testing Track Orchestrator (`e2e_testing_orchestrator`, DISPATCH-ONLY Sub-Orchestrator under Project Pattern)
- **Conversation ID**: `042425bf98efe04f6055f145dfece762`
- **State Folder**: `/home/antigravity/terenzy/Silo/.agents/teamwork/e2e_testing_orchestrator`
- **Working Directory**: `/home/antigravity/terenzy/Silo`

## 🔒 My Workflow
1. Create `/home/antigravity/terenzy/Silo/TEST_INFRA.md` defining all 12 features (`F1`–`F12`), opaque-box test architecture, runner CLI (`python3 tests/e2e/run_e2e.py --tier <1|2|3|4|all>`), pass/fail semantics, Tier 4 scenarios, and coverage thresholds (`>= 60` T1, `>= 60` T2, `>= 12` T3, `>= 6` T4; `>= 138` total).
2. Run Project Pattern 2B Iteration Loop (max 32 iterations):
   - **a. Explore**: Spawn 3 parallel `teamwork_preview_explorer`s to inspect exact public CLI/IPC/script/Gradle/CI contracts and devbox execution mechanics (without touching code).
   - **b. Implement**: Spawn 1 `teamwork_preview_test_writer` with exclusive ownership of `tests/e2e/**` and verbatim MANDATORY INTEGRITY WARNING.
   - **c, d, e. Parallel Verification Wave**: Spawn 2 `teamwork_preview_reviewer`s + 2 `teamwork_preview_challenger`s + 1 `teamwork_preview_auditor` (intermediate track audit scoped to `tests/e2e/`, `TEST_INFRA.md`, `TEST_READY.md`) concurrently strictly AFTER test writer completes.
   - **f. Gate**: Evaluate Auditor FIRST (binary veto on INTEGRITY VIOLATION), then strict AND across independent post-edit run, both Reviewers APPROVE, both Challengers confirm correctness, and Auditor CLEAN.
3. On Gate PASS: Publish `/home/antigravity/terenzy/Silo/TEST_READY.md`, update `progress.md` and `GATE_STATUS.md`, write self-contained `handoff.md`, and send completion report to `Current Parent`.

## 🔒 Key Constraints
- DISPATCH-ONLY: NEVER write/edit source or test code directly; NEVER run builds/tests/code exploration directly (`run_command` ONLY for `mkdir -p` under `.agents/teamwork/`).
- ONLY edit `.md` state files in own state folder + `TEST_INFRA.md` / `TEST_READY.md` at project root.
- EXCLUSIVE OWNERSHIP: `TEST_INFRA.md`, `TEST_READY.md`, `tests/e2e/**`. NEVER touch `app/`, `daemon/`, `module/`, `scripts/`, or `.github/`.
- OPAQUE-BOX REQUIREMENT-DRIVEN: Derive strictly from `ORIGINAL_REQUEST.md` + `PROJECT.md § Feature Inventory` + `PROJECT.md § Interface Contracts`. Do NOT weaken tests to pass against buggy pre-fix code.
- Pass `/home/antigravity/terenzy/Silo/.agents/teamwork/ORIGINAL_REQUEST.md` verbatim to EVERY subagent.
- Never reuse a subagent after handoff — always spawn fresh.
- Forensic Auditor is NON-SKIPPABLE and holds unconditional binary veto.

## Current Parent
- Conversation ID: `5ee4b475a7063ff35a048e6a278d18c3` (Project Orchestrator)

## Team Roster
- `e2e_explorer_daemon` (`teamwork_preview_explorer`, conv `63ec234dde2eb36c8c8907ab48f69707`) — RUNNING (Iteration 1a)
- `e2e_explorer_app` (`teamwork_preview_explorer`, conv `a6c2bcdd1585ac10b1d175a63cc281eb`) — RUNNING (Iteration 1a)
- `e2e_explorer_module_ci` (`teamwork_preview_explorer`, conv `c24aa721df8a731ac5a62eca2f26985c`) — RUNNING (Iteration 1a)

## Succession Status
- Active (Iteration 1). No succession triggered.

## Active Timers
- None (`schedule` tool not exposed in this runtime; relying on platform reactive wakeup + `manage_subagents(Action="list")` monitoring on every wakeup).

## Artifact Index
- `/home/antigravity/terenzy/Silo/.agents/teamwork/ORIGINAL_REQUEST.md`
- `/home/antigravity/terenzy/Silo/PROJECT.md`
- `/home/antigravity/terenzy/Silo/TEST_INFRA.md`
- `/home/antigravity/terenzy/Silo/.agents/teamwork/e2e_testing_orchestrator/DISPATCH.md`
- `/home/antigravity/terenzy/Silo/.agents/teamwork/e2e_testing_orchestrator/BRIEFING.md`
- `/home/antigravity/terenzy/Silo/.agents/teamwork/e2e_testing_orchestrator/progress.md`
- `/home/antigravity/terenzy/Silo/.agents/teamwork/explorer_survey_1/handoff.md`
- `/home/antigravity/terenzy/Silo/.agents/teamwork/explorer_survey_2/handoff.md`
- `/home/antigravity/terenzy/Silo/.agents/teamwork/explorer_survey_3/handoff.md`
