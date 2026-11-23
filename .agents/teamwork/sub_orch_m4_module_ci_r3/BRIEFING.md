# Briefing — Milestone M4 (`module-scripts-ci`, Round 3 Resumption)

## 🔒 My Identity
- **Role**: DISPATCH-ONLY Sub-Orchestrator for Milestone M4 (`module-scripts-ci`, Round 3 resumption)
- **Conversation ID**: `168b53c44acee7813323a1f5dd623b11`
- **State Folder**: `/home/antigravity/terenzy/Silo/.agents/teamwork/sub_orch_m4_module_ci_r3`
- **Repository**: `/home/antigravity/terenzy/Silo`
- **Exclusive Owned Files**:
  - `module/service.sh`
  - `module/action.sh`
  - `module/uninstall.sh`
  - `module/customize.sh`
  - `scripts/build_module.sh`
  - `.github/workflows/build.yml`

## 🔒 My Workflow
1. Project Pattern 2B Iteration Loop (max 32 iterations):
   - Iteration 1: Skip Step 1a Explorers per parent instruction (3 completed R1/R2 Explorer handoffs + survey 3 supplied); synthesize them and proceed directly to Step 1b (`iter1_worker_r3`).
   - Step b: Spawn 1 fresh `teamwork_preview_worker` with `ORIGINAL_REQUEST.md`, `PROJECT.md`, all 3 Explorer handoffs, reference survey, R2 worker progress, exclusive ownership, verbatim integrity warning, and full verification matrix requirement. WAIT for completion.
   - Steps c–e (Parallel Verification Wave strictly AFTER Step b's final edit): Launch all 5 verifiers concurrently in ONE wave:
     - Primary `teamwork_preview_reviewer`: full syntax/YAML/behavioral verification matrix + full contract review.
     - Secondary `teamwork_preview_reviewer`: target-scoped checks + deep contract/security/edge-case review.
     - 2 `teamwork_preview_challenger`s: adversarial shell/packaging/CI edge-case probes in isolated `/tmp/teamwork_probe.*` (`chmod 700`, cleaned up before reporting).
     - 1 `teamwork_preview_auditor` (**intermediate milestone audit**, scoped strictly to `module/`, `scripts/`, `.github/workflows/build.yml`).
   - Step f (Strict AND Gate): Read every verifier's `handoff.md` directly. Evaluate Auditor FIRST (binary veto). Require independent post-edit passing checks + ALL Reviewers `APPROVE` + ALL Challengers `APPROVE` + Auditor `CLEAN`. Update `GATE_STATUS.md`.
   - On failure: check Oscillation Guard, Regression Guard, Integrity Violation, or unexplored module flag -> route to Step a (3 fresh Explorers; with FULL UNREDACTED Auditor report on integrity violation) or directly to Step b (fresh Worker).
   - On PASS: write self-contained Hard Handoff to `handoff.md`, update `progress.md`, and report via `send_message` to `Current Parent`.

## 🔒 Key Constraints
- DISPATCH-ONLY: NEVER write/edit source, test, script, or CI files directly; NEVER run build/test/lint/git commands directly; NEVER inspect source files directly.
- Edit ONLY `.md` files inside `/home/antigravity/terenzy/Silo/.agents/teamwork/sub_orch_m4_module_ci_r3`.
- Include `/home/antigravity/terenzy/Silo/.agents/teamwork/ORIGINAL_REQUEST.md` verbatim in EVERY subagent dispatch.
- Fresh unique state folder per spawned subagent; never reuse a subagent after `handoff.md`.
- Never place source code, probes, tests, or data files in `.agents/teamwork/`; never name any file `AGENTS.md` or `GEMINI.md`.
- Never touch `app/`, `daemon/`, or `tests/`.
- Succession threshold: cumulative spawns >= 20 (once all non-orchestrator subagents have reported).

## Current Parent
- **Conversation ID**: `058a70a41cdd8415cd242bd3f8eb9136` (Parent Project Orchestrator)

## Team Roster
*(Cumulative spawns: 6)*
- `iter1_worker_r3` (`teamwork_preview_worker`, `c8f67e09c800075978c23eaa7c092ce3`) — Step 1b Worker (R3 resumption): COMPLETED (`sub_orch_m4_module_ci_r3/iter1_worker_r3/handoff.md`)
- `iter1_reviewer_1` (`teamwork_preview_reviewer`, `f867e933da5951c312eb47f44189716f`) — Step 1c Primary Reviewer (Full Matrix): ACTIVE (`sub_orch_m4_module_ci_r3/iter1_reviewer_1`)
- `iter1_reviewer_2` (`teamwork_preview_reviewer`, `dae6b07d235a65ff06b021ee5b7d30cc`) — Step 1c Secondary Reviewer (Contract & Security): ACTIVE (`sub_orch_m4_module_ci_r3/iter1_reviewer_2`)
- `iter1_challenger_1` (`teamwork_preview_challenger`, `2ce304bfd26eb172a8605afa771a24b5`) — Step 1d Challenger 1 (Shell & Races): ACTIVE (`sub_orch_m4_module_ci_r3/iter1_challenger_1`)
- `iter1_challenger_2` (`teamwork_preview_challenger`, `77017a9a88492fd8671496045d55ede2`) — Step 1d Challenger 2 (Packaging & CI): ACTIVE (`sub_orch_m4_module_ci_r3/iter1_challenger_2`)
- `iter1_auditor` (`teamwork_preview_auditor`, `71322d35e0311a5b7ff1559a11fa0b39`) — Step 1e Forensic Auditor (Intermediate Milestone Audit): ACTIVE (`sub_orch_m4_module_ci_r3/iter1_auditor`)

## Succession Status
- **Generation**: Round 3 (initial generation for `sub_orch_m4_module_ci_r3`)
- **Cumulative Spawns**: 6 / 20
- **Successor**: None
