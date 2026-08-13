# Agent Self-Configuration Development Tasks

> **Document type:** `tasks.md`
> **Status:** Ready for implementation handoff
> **Version:** 1.0
> **Date:** 2026-08-08
> **Specification:** [spec.md](spec.md)
> **Acceptance checklist:** [checklist.md](checklist.md)

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Every task uses checkbox tracking and must pass its verification gate before the next dependent task starts, subject to the task type rules below.

**Goal:** Add a project-scoped, Git-visible Agent configuration control plane that can safely select real Windows/WSL execution environments, capabilities, extensions and bounded self-repair while preserving durable lifecycle, approval, secret and recovery invariants.

**Architecture:** `.labex-agent/project/**` is the user-visible non-secret configuration source. Backend services validate and version it, persist proposals/audit/runtime snapshots, and use one immutable `AgentCapabilityPolicy` per execution epoch. `AgentTask`/lifecycle owns run state and fencing; interaction is only a durable wait projection for project proposals; Worker adapters own execution boundaries; Vue components consume API and durable event projections without creating a second Agent store.

**Tech Stack:** Spring Boot 3, Java 17, MyBatis-Plus, MySQL/MariaDB additive schema migration, Gson/Jackson already present in the backend, WSL2/bubblewrap and Docker Workers, Vue 3, Axios, Node `node:test`, Vite.

---

## Planning Handoff and Scope

This file is the global coordination artifact for implementation agents. The planning coordinator owns architecture consistency, dependency ordering, task boundaries and acceptance evidence; it does not implement production code. Each coding agent receives exactly one numbered task, works test-first inside that task's file boundary, and returns evidence to the coordinator before another task that shares files begins.

The current branch is `codex/agent-tool-reliability` and the shared worktree already contains substantial unrelated backend, frontend and acceptance changes. In particular, `AgentStreamRequest`, `AgentRunContinuationRequestFactory`, `AgentRunInteractionService`, `AgentRunResumeScheduler`, `AgentLoopEngine`, `AgentRunInteractionMapper`, `application.yml` and several matching tests are already modified. These edits are an input baseline, not disposable scaffolding.

### Multi-Agent Coordination Contract

- Use one implementation agent per numbered task. Do not give one agent an entire phase unless that phase has been decomposed into the numbered tasks below.
- Every numbered task is `IMPLEMENTATION` unless its section explicitly declares `CHARACTERIZATION` or `LIVE_ACCEPTANCE`. `CHARACTERIZATION` records the secure target and the current RED evidence, then hands off to its dependent implementation task; it is complete only when the RED failure is intentional and documented. `IMPLEMENTATION` tasks require RED -> GREEN -> focused regression. `LIVE_ACCEPTANCE` tasks are coordinator-owned evidence gates and are never run by an ordinary implementation Agent.
- Do not run implementation agents in parallel when their file lists overlap, when both alter schema/migration, or when either changes lifecycle, interaction, transcript, capability or Worker contracts. Shared-file tasks are serialized and reviewed between handoffs.
- Parallel work is allowed only after the coordinator proves disjoint file ownership and stable upstream contracts. The default for this plan is serial execution.
- Before editing, every implementation agent records `git status --short`, inspects `git diff -- <owned-file>` for each pre-modified file, and states how it will preserve those changes. If ownership is ambiguous, return `NEEDS_CONTEXT` instead of replacing the file.
- Do not create a clean worktree from `HEAD` and assume it contains the shared uncommitted baseline. An isolated worktree may be used only after the coordinator explicitly identifies which baseline commit or reviewed patch contains the required existing changes.
- Use RED -> verify the intended failure -> GREEN -> focused regression -> phase gate for `IMPLEMENTATION` tasks. A characterization task may stop after the intended RED result; its final assertion must encode the secure target behavior, and it must explicitly return `CHARACTERIZATION_RED` rather than pretending to pass. Never retain an assertion that blesses the vulnerability.
- No agent commits, stages, pushes, rebases, resets, cleans, or reverts unless the user separately requests it. The coordinator reviews diffs and updates plan status.
- Real Windows helper execution, production database operations and real `wsl --shutdown` are never part of ordinary implementation-agent verification. They require the explicit coordinator-owned live acceptance gates in Phase 5 and Phase 7; the default live scripts must run with host shutdown disabled.

### Mandatory Implementation Agent Prompt

The coordinator must send the following prompt together with this complete `tasks.md` file and one exact numbered task section. This fixed prompt plus the selected task section is the complete self-contained Agent prompt required by the repository development protocol.

```text
Role: You are the implementation Agent for exactly one numbered task in the Agent Self-Configuration feature.

Repository: D:\LabexAgent
Backend: Spring Boot 3, Java 17, Maven, MyBatis-Plus, MySQL/MariaDB with H2 tests.
Frontend: Vue 3, Vite, Axios, Node node:test.

Goal: Implement only the numbered task named by the coordinator and satisfy its linked spec requirements.

Read first:
1. docs/superpowers/agent-self-configuration/spec.md
2. docs/superpowers/agent-self-configuration/tasks.md global execution rules
3. The selected task section, its listed production files and existing tests
4. AGENTS.md and docs/coding-agent-engineering-roadmap.md

Allowed changes: Only files listed in the selected task. A new focused file is allowed only when the task explicitly permits creation and the responsibility cannot fit an existing listed module. Stop with NEEDS_CONTEXT before touching any additional file.

Required workflow:
1. Record git status and inspect pre-existing diffs for every owned file.
2. For `IMPLEMENTATION`, write the secure target test first and run it to observe the intended RED failure. For `CHARACTERIZATION`, write the secure target test and record the current RED result without implementing production code. For `LIVE_ACCEPTANCE`, use only the approved acceptance script and evidence packet.
3. Implement the smallest code that makes the target behavior pass.
4. Run the exact focused command and relevant regressions from the selected task.
5. Review the diff for authority duplication, authorization bypass, secret exposure, stale-fence writes and unrelated changes.
6. Run git diff --check for owned files.

Forbidden:
- Do not reset, clean, checkout, revert, stage, commit, push, amend or rewrite history.
- Do not read or print .env files, credentials, private keys, tokens or SecretStore values.
- Do not change task status outside AgentRunLifecycleService or create a second transcript, status map, approval waiter, Provider history or command executor.
- Do not weaken tests, security gates, ownership checks, validation, sandbox requirements or CI/build gates.
- Do not run destructive migrations, production database commands, real wsl --shutdown or an unverified Windows helper.
- Do not use unsafe-local as a sandbox fallback.

Verification: Run exactly the command(s) in the selected task and report exit code plus tests/failures/errors/skips. A compile-only result is not live runtime evidence.

Return one status: DONE, DONE_WITH_CONCERNS, CHARACTERIZATION_RED, NEEDS_CONTEXT or BLOCKED. Include task ID, task type, files changed, RED evidence when applicable, GREEN evidence when applicable, regression/live evidence, diff-check result, preserved pre-existing changes, remaining risks and confirmation that no secrets or unrelated files were touched.
```

### Current and Target Behavior Matrix

| Surface | Current behavior to characterize | Required target behavior |
|---|---|---|
| Public stream resume | Internal resume IDs can be bound from public JSON | Public DTO rejects recovery fields before service/runtime dispatch |
| Interaction resume | Interaction lookup and task dispatch are split across calls | One owner/project/latest/TTL-aware transactional claim returns one lease |
| Executor writes | Some write paths trust stale task/epoch context | Every executor write requires active owner + epoch + lease fence |
| Model/mode recovery | Resume may read mutable default; `plan_exit` can remain in memory | Exact model and mode are durable and rebuild runtime projections |
| Lease recovery | Recovery is primarily startup-driven | Bounded periodic reconciler reclaims only expired non-terminal work |
| Project config | No Git-visible project authority or immutable run snapshot | Validated config revision plus immutable task-epoch snapshot |
| Proposal/secret | No unified owner-only config CAS and write-only secret path | Durable proposal/audit/interaction projection and scoped secret lease |
| Tool capability | Schema and execution have parallel allowlists/dynamic registries | One snapshot-backed capability policy and epoch-local tool catalog |
| Worker selection | Spring profile selects one Worker implementation | One router selects explicit strict/integrated profiles from immutable spec |
| Environment repair | Failures can be handled as ad hoc command retries | Durable bounded environment operation with typed probes and recovery |
| WSL host maintenance | Global shutdown is not a durable control-plane workflow | Composite approval + global lease + drain + adapter + health recovery |
| Frontend | No project config center or dedicated durable projection | Modular UI consumes API/history/replay without second Agent store |

### Required Agent Return Packet

Every coding agent returns one of `DONE`, `DONE_WITH_CONCERNS`, `NEEDS_CONTEXT` or `BLOCKED`, plus:

1. The numbered task and exact requirements completed.
2. Files created or modified, including pre-existing edits preserved.
3. RED command and expected failure observed.
4. GREEN and focused regression commands with pass/fail/error/skip counts.
5. `git diff --check` result for owned files and a concise diff-risk review.
6. Remaining assumptions, compatibility writes/reads, feature flags and deletion conditions.
7. Explicit confirmation that no secrets, unrelated files, real host shutdown or unsafe fallback were introduced. A live acceptance packet must also record approval scope, isolated user/project IDs, JVM/browser identity and evidence paths.

The coordinator performs spec-compliance review first, code-quality/security review second, then runs the task verification command. A task is not complete while either review has an open finding.

### Dependency Waves

| Wave | Tasks | Entry gate | Coordination rule | Exit artifact |
|---|---|---|---|---|
| 0 | 0.0-0.8 | Reviewed dirty-worktree inventory | Fully serial; runtime files overlap | Phase 0 security suite and full backend suite |
| 1 | 1.1-1.4 | Wave 0 green | Serial until config schema, revision and snapshot contracts freeze | Deterministic revision and immutable task-epoch snapshot |
| 2 | 2.1-2.4 | Wave 1 green | Serial; proposal, interaction, controller and secret boundaries overlap | Owner-only CAS proposal flow and secret sentinel evidence |
| 3 | 3.1-3.4 | Waves 1-2 green | Serial around `ToolRegistry`, `AgentLoopEngine` and resource resolution | One epoch-scoped capability/tool authority |
| 4 | 4.1-4.4 | Waves 0, 1 and 3 green | Serial; Worker/LSP/MCP/terminal wiring overlaps | Integrated WSL and durable environment operation evidence |
| 5 | 5.1-5.3 | Wave 4 green and native helper toolchain available | Separate native helper, Java client and live acceptance review | Versioned helper contract plus live Windows security report |
| 6 | 6.1-6.3 | Backend API/event contracts from Waves 2-4 frozen | Frontend tasks serial around reducers and `CloudWorkspace.vue` | Refresh/replay-safe configuration center |
| 7 | 7.1-7.2 | All enabled waves green | No parallel mutation during live acceptance | Compatibility exit metrics and complete live evidence |

`integrated-windows` may remain unavailable without blocking Waves 0-4 or 6. Phase 5 is a release gate for that profile, not permission to substitute `unsafe-local`.

### Dispatch Order

Use the following dependency order. A comma inside brackets means the tasks may be considered together for review, but they still run serially when their file lists overlap.

```text
0.0 -> 0.1[CHARACTERIZATION_RED] -> 0.2 -> 0.3 -> 0.4 -> 0.5 -> 0.6 -> 0.7 -> 0.8
0.8 -> 1.1 -> 1.2 -> 1.3 -> 1.4
1.4 -> 2.1 -> 2.2 -> 2.3 -> 2.4
[1.4, 2.4] -> 3.1 -> 3.2 -> 3.3 -> 3.4
[0.8, 1.4, 3.4] -> 4.1 -> 4.2 -> 4.3 -> 4.4
4.4 -> 5.1 -> 5.2 -> 5.3            # optional profile release gate
[2.4, 4.3] -> 6.1 -> 6.2
[2.3, 6.1, 6.2] -> 6.3
[0.8, 1.4, 2.4, 3.4, 4.4, 6.3] -> 7.1 -> 7.2
```

The coordinator may defer Phase 5 when no native helper implementation is available. It may not bypass any other dependency by enabling a feature flag early.

### Task Dependency and Requirement Matrix

| Task | Depends on | Spec requirements |
|---|---|---|
| 0.0 | None | FR-001, FR-002, NFR-009 |
| 0.1 | 0.0 | FR-012, NFR-001-NFR-005 |
| 0.2 | 0.1 | FR-012, NFR-001, NFR-005 |
| 0.3 | 0.2 | FR-005, FR-012, NFR-001, NFR-002, NFR-004 |
| 0.4 | 0.3 | FR-004, FR-012, NFR-002-NFR-005 |
| 0.5 | 0.4 | FR-004, FR-012, NFR-002-NFR-005 |
| 0.6 | 0.5 | FR-004, FR-012, NFR-002-NFR-005 |
| 0.7 | 0.6 | FR-004, FR-007, FR-012, NFR-004, NFR-005 |
| 0.8 | 0.7 | FR-012, NFR-002, NFR-004, NFR-005 |
| 1.1 | 0.8 | FR-001, FR-002, NFR-003, NFR-005 |
| 1.2 | 1.1 | FR-003, FR-004, NFR-002, NFR-006 |
| 1.3 | 1.2 | FR-002, FR-003, FR-005, NFR-001, NFR-002 |
| 1.4 | 1.3 | FR-004, FR-008, NFR-003-NFR-005 |
| 2.1 | 1.4 | FR-003, FR-005, NFR-002, NFR-006, NFR-008 |
| 2.2 | 2.1 | FR-003, FR-005, NFR-001, NFR-002, NFR-008 |
| 2.3 | 2.2 | FR-005, FR-012, FR-013, NFR-002, NFR-004, NFR-010 |
| 2.4 | 2.3 | FR-006, NFR-001-NFR-003, NFR-008 |
| 3.1 | 2.4 | FR-007, NFR-001, NFR-005, NFR-010 |
| 3.2 | 3.1 | FR-004, FR-006, FR-008, NFR-001, NFR-003-NFR-005 |
| 3.3 | 3.2 | FR-007, FR-008, NFR-001, NFR-004, NFR-010 |
| 3.4 | 3.3 | FR-007, FR-008, NFR-003, NFR-005 |
| 4.1 | 3.4 | FR-009, NFR-003, NFR-005 |
| 4.2 | 4.1 | FR-006, FR-009, NFR-003, NFR-005 |
| 4.3 | 4.2 | FR-010, FR-012, NFR-002-NFR-005, NFR-008 |
| 4.4 | 4.3 | FR-010, FR-011, NFR-002-NFR-005, NFR-008 |
| 5.1 | 4.4 | FR-009, NFR-003, NFR-005 |
| 5.2 | 5.1 | FR-009, NFR-003, NFR-005 |
| 5.3 | 5.2 | FR-009, AC-001-AC-003, AC-009, AC-010 |
| 6.1 | 2.4, 4.3 | FR-005, FR-006, FR-010, FR-013, NFR-004, NFR-007 |
| 6.2 | 6.1 | FR-005, FR-006, FR-009, FR-010, FR-013, NFR-007 |
| 6.3 | 2.3, 6.2 | FR-005, FR-012, FR-013, NFR-004, NFR-010 |
| 7.1 | 0.8, 1.4, 2.4, 3.4, 4.4, 6.3 | FR-014, NFR-004-NFR-006, NFR-008 |
| 7.2 | 7.1 | FR-001-FR-014, NFR-001-NFR-010, AC-001-AC-010 |

## Execution Rules

- Read [spec.md](spec.md) before every implementation task.
- The worktree is already dirty. Never run `git reset`, `git checkout`, `git clean`, broad deletion, database reset, or destructive migration. Do not modify unrelated existing changes.
- Do not read or print `.env`, credentials, private keys, tokens or secret store values.
- Follow RED → verify failure → GREEN → verify pass → refactor for every behavior change. No production code is written before its failing test.
- `AgentRunLifecycleService` remains the only task-status write authority. `AgentRunMessage`/`AgentRunPart` remain the transcript/Part authority. `AgentRunEvent`/outbox remain durable event and delivery projection facts.
- Every new database change is additive in `schema.sql` and `AdditiveSchemaMigrator`; no `DROP`, `TRUNCATE`, table rebuild or data reset.
- Do not make the project config a secret store. Config files, proposals, snapshots, interaction payloads, events, outbox rows, transcript, SSE and logs contain aliases/digests only.
- Do not commit automatically. Keep each task reviewable as a diff; commit only after an explicit user request.
- Do not claim live safety from unit tests. Windows helper, WSL shutdown and browser/runtime checks require separately recorded live evidence.

## Baseline and Shared Commands

Run from `D:\LabexAgent` unless the command specifies another directory:

```powershell
mvn -f backend/pom.xml '-Dtest=AgentRunStateMachineTest,AgentRunInteractionDispatchClaimTest,AgentRunResumeSchedulerTest,AgentRunExecutionLeaseServiceTest,AgentRunRecoveryServiceTest,AgentRunContinuationRequestFactoryTest' test
```

Expected result: the command completes with the current worktree's actual pass/fail/error/skip counts recorded in [checklist.md](checklist.md). Do not replace a failure with an assertion change without identifying its behavior owner.

```powershell
npm --prefix frontend test
npm --prefix frontend run build
```

Expected result: existing Node tests and the production Vite build complete. The repository has no frontend lint or format script.

## File Ownership Map

The following files are the planned ownership boundaries. Add a focused file when a responsibility does not fit one of these boundaries; do not extend `CloudWorkspace.vue`, `StudentAgentController`, `AgentContext` or `AgentLoopEngine` with unrelated control-plane responsibilities.

- Runtime safety: `backend/src/main/java/com/labex/labexagent/run/`, `backend/src/main/java/com/labex/labexagent/dto/`, focused runtime/controller tests.
- Project configuration: new `backend/src/main/java/com/labex/labexagent/projectconfig/` package, `com.labex.entity`, `com.labex.mapper`, a focused controller and service tests.
- Schema: `backend/src/main/resources/sql/schema.sql`, `backend/src/main/java/com/labex/config/AdditiveSchemaMigrator.java`.
- Capability: new `backend/src/main/java/com/labex/labexagent/capability/`, existing `tool/ToolRegistry`, `ToolSelectionPolicy`, permission and extension services.
- Secrets: existing `labexagent/secret/` plus a project-scoped binding service; no generic interaction response for secret values.
- Workers: existing `backend/src/main/java/com/labex/labexagent/worker/`; host maintenance is a separate adapter and operation coordinator.
- Frontend API/state: `frontend/src/api/index.js`, new `frontend/src/composables/useProjectAgentConfig.js`, new `frontend/src/composables/agentProjectConfigProjection.js`.
- Frontend UI: new `frontend/src/components/cloud/agent-config/`; `CloudWorkspace.vue` only adds a narrow composition entry point.
- Acceptance: `scripts/acceptance/agent-runtime.ps1`, `scripts/acceptance/browser-runtime.ps1`, `frontend/scripts/acceptance/agent-browser.mjs`, README and architecture docs after behavior lands.
- Repository visibility and coordination: `.gitignore`, [spec.md](spec.md), this file and [checklist.md](checklist.md). Runtime or secret directories beneath `.labex-agent/` remain ignored.

## Phase 0: Runtime Safety and Recovery Prerequisites

### Task 0.0: Establish the Shared Baseline and Git-Visible Boundaries

**Files:**
- Modify: `.gitignore`
- Verify: `docs/superpowers/agent-self-configuration/spec.md`
- Verify: `docs/superpowers/agent-self-configuration/tasks.md`
- Verify: `docs/superpowers/agent-self-configuration/checklist.md`

- [ ] Capture the current branch, `git status --short`, relevant per-file diffs and focused baseline test counts before another Agent edits runtime code. Preserve the inventory with the implementation handoff; do not stage unrelated files.
- [ ] Replace the broad `docs/` behavior with a narrow exception that makes `docs/superpowers/agent-self-configuration/**` version-visible while keeping personal/private documentation ignored.
- [ ] Replace the effective broad `.labex-agent/` ignore for project configuration with explicit exceptions for only `.labex-agent/project/agent.json`, `agents/**`, `tools/**`, `mcp/**`, `skills/**` and `environment.json`. Keep runtime, leases, caches, credentials, generated state and every non-project subtree ignored.
- [ ] Keep `/sandbox/windows-helper/build/` and generated native binaries ignored; only helper source, contract and test fixtures may become version-visible.
- [ ] Run explicit `git check-ignore` assertions against `spec.md`, `tasks.md`, `checklist.md`, every allowed project-config path and representative denied runtime/secret paths. A visible path is successful only when `git check-ignore -q` exits `1`; an intentionally ignored path is successful only when it exits `0`.
- [ ] Run the baseline commands in this file and record existing failures without changing assertions. If the baseline cannot compile, stop the wave and return `BLOCKED` with the first owned/unowned failure classification.
- [ ] Run:

```powershell
git rev-parse HEAD
git branch --show-current
git status --short
$visible = @(
  'docs/superpowers/agent-self-configuration/spec.md',
  'docs/superpowers/agent-self-configuration/tasks.md',
  'docs/superpowers/agent-self-configuration/checklist.md',
  '.labex-agent/project/agent.json',
  '.labex-agent/project/agents/example.json',
  '.labex-agent/project/tools/example.json',
  '.labex-agent/project/mcp/example.json',
  '.labex-agent/project/skills/example.md',
  '.labex-agent/project/environment.json'
)
$ignored = @(
  '.labex-agent/runtime/state.json',
  '.labex-agent/leases/current.json',
  '.labex-agent/cache/index.json',
  '.labex-agent/credentials/provider.json',
  'sandbox/windows-helper/build/helper.exe'
)
foreach ($path in $visible) { git check-ignore -q -- $path; if ($LASTEXITCODE -eq 0) { throw "Expected Git-visible path is ignored: $path" } }
foreach ($path in $ignored) { git check-ignore -q -- $path; if ($LASTEXITCODE -ne 0) { throw "Expected ignored path is visible: $path" } }
mvn -f backend/pom.xml '-Dtest=AgentRunStateMachineTest,AgentRunInteractionDispatchClaimTest,AgentRunResumeSchedulerTest,AgentRunExecutionLeaseServiceTest,AgentRunRecoveryServiceTest,AgentRunContinuationRequestFactoryTest' test
npm --prefix frontend test
npm --prefix frontend run build
```

Expected result: implementation agents share one reviewed dirty baseline, planning artifacts are trackable, non-secret project config can be committed, and local runtime/secret artifacts remain excluded.

### Task 0.1: Capture Characterization and Negative Security Tests

**Task type:** `CHARACTERIZATION`

**Files:**
- Create: `backend/src/test/java/com/labex/labexagent/controller/StudentAgentControllerStreamSecurityTest.java`
- Create: `backend/src/test/java/com/labex/labexagent/run/AgentRunExecutionFenceTest.java`
- Create: `backend/src/test/java/com/labex/labexagent/run/AgentRunModelSelectionServiceTest.java`
- Create: `backend/src/test/java/com/labex/labexagent/run/AgentRunLeaseReconcilerTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/run/AgentRunInteractionDispatchClaimTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/run/AgentRunStateMachineTest.java`

- [ ] Add tests whose final assertions require the public stream to reject recovery fields before service dispatch, stale owners/epochs/leases to reject all writes, resumed tasks to retain their exact model, `plan_exit` to survive restart, and expired leases to be reconciled after startup. Run them against the current code and record the observed RED behavior; do not make insecure behavior a passing assertion.
- [ ] Add negative cases for another student/project, expired interaction, resolved non-latest interaction, duplicate dispatch, old epoch, old owner, and expired lease.
- [ ] Record the expected RED failures by test name without changing assertions to accept insecure behavior.
- [ ] Run:

```powershell
mvn -f backend/pom.xml '-Dtest=StudentAgentControllerStreamSecurityTest,AgentRunExecutionFenceTest,AgentRunModelSelectionServiceTest,AgentRunLeaseReconcilerTest,AgentRunInteractionDispatchClaimTest,AgentRunStateMachineTest' test
```

Expected result: return `CHARACTERIZATION_RED` with the named secure-target failures caused by missing implementation, while existing tests gain no unrelated failure. This result unlocks Task 0.2; it does not mark the Phase 0 security gate green.

### Task 0.2: Remove Public Direct-Resume Inputs

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/dto/AgentStreamHttpRequest.java`
- Modify: `backend/src/main/java/com/labex/labexagent/dto/AgentStreamRequest.java`
- Modify: `backend/src/main/java/com/labex/labexagent/controller/StudentAgentController.java:177-182`
- Modify: `backend/src/test/java/com/labex/labexagent/controller/StudentAgentControllerStreamSecurityTest.java`

- [ ] Define the public DTO with only `sessionId`, `conversationId`, `mode`, `message`, `displayMessage`, `activePath`, `modelConfigId`, `backgroundRun` and `submittedAt`. Enforce unknown-field rejection on this DTO so `resumeTaskId` and `resumeInteractionId` produce HTTP 4xx even when the application's default ObjectMapper ignores unknown properties. Do not enable global unknown-field rejection without auditing every existing API DTO.
- [ ] Add an explicit conversion method from the HTTP DTO to the internal `AgentStreamRequest`; only scheduler/continuation code may populate `resumeTaskId`, `resumeInteractionId` and `resumeNote`.
- [ ] Change `StudentAgentController.stream` to bind the public DTO and convert it before calling `prepareAgentStreamRequest` and `AgentLoopEngine.start`.
- [ ] Rewrite the two Task 0.1 stream-security tests so they bind the new public `AgentStreamHttpRequest` (the internal DTO keeps recovery fields for scheduler-only use) and expect HTTP 4xx. Preserve the `AgentLoopEngine.start` never-called invariant and keep a factory test proving scheduler-generated continuation requests still contain the recovery identifiers.
- [ ] Run:

```powershell
mvn -f backend/pom.xml '-Dtest=StudentAgentControllerStreamSecurityTest,AgentRunContinuationRequestFactoryTest,StudentAgentControllerPermissionOwnershipTest' test
```

Expected result: all three test classes pass and the public DTO cannot deserialize recovery identifiers.

### Task 0.3: Make Interaction Resume a Single Transactional Claim

**Files:**
- Modify: `backend/src/main/resources/sql/schema.sql:t_agent_run_interaction`
- Modify: `backend/src/main/java/com/labex/entity/AgentRunInteraction.java`
- Modify: `backend/src/main/java/com/labex/mapper/AgentRunInteractionMapper.java`
- Modify: `backend/src/main/java/com/labex/labexagent/run/AgentRunInteractionService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/run/AgentRunLifecycleService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/run/AgentRunResumeScheduler.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- Modify: `backend/src/main/java/com/labex/labexagent/service/AgentTaskService.java`
- Modify: `backend/src/main/java/com/labex/config/AdditiveSchemaMigrator.java`
- Modify: `backend/src/test/java/com/labex/labexagent/run/AgentRunInteractionDispatchClaimTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/run/AgentRunInteractionMapperDatabaseTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/run/AgentRunResumeSchedulerTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/service/AgentTaskServiceTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/service/AgentTaskServiceLifecycleTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/runtime/AgentLoopEngineStreamingContractTest.java`

- [ ] Add additive claim metadata: `resume_claim_id`, `resume_claim_epoch`, `resume_claimed_at` and `resume_consumed_at`, with indexes for waiting/resolved task scans. Apply the same definitions in `schema.sql` and `REQUIRED_COLUMNS` or table creation logic.
- [ ] Add a mapper query that locks the owned task and latest compatible interaction with `FOR UPDATE` semantics supported by the project database. The predicate must include student, project, task, latest waiting state, resolved status, expiration and unconsumed claim.
- [ ] Add one transactional lifecycle entry point, `claimResolvedInteractionDispatch(...)`, that validates task state, interaction state and idempotency, records the claim, changes the task to `recovering`, increments the execution epoch through the existing lifecycle/lease authority, writes event/outbox, and returns one server-owned dispatch claim.
- [ ] Remove direct `findById` interaction injection from both `AgentTaskService` and `AgentLoopEngine` resume paths. `AgentLoopEngine` may consume only a preclaimed internal continuation carrying the validated interaction and lease; no controller, task service or loop fallback may reconstruct a claim from a user-controlled ID.
- [ ] Assert two concurrent claims yield exactly one claim, cross-owner/project claims fail as not found, expired/latest-invalid interactions do not resume, and duplicate decision/dispatch returns the first durable result.
- [ ] Run:

```powershell
mvn -f backend/pom.xml '-Dtest=AgentRunInteractionDispatchClaimTest,AgentRunInteractionMapperDatabaseTest,AgentRunResumeSchedulerTest,AgentRunLifecycleServiceTest,AgentTaskServiceTest,AgentTaskServiceLifecycleTest,AgentLoopEngineStreamingContractTest' test
```

Expected result: one concurrent claimant succeeds, all invalid ownership/status cases fail closed, and scheduler recovery remains durable.

### Task 0.4: Add and Propagate ExecutionFence

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/run/ExecutionFence.java`
- Modify: `backend/src/main/java/com/labex/labexagent/run/AgentRunExecutionLeaseService.java`
- Modify: `backend/src/test/java/com/labex/labexagent/run/AgentRunExecutionFenceTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/run/AgentRunExecutionLeaseServiceTest.java`

- [ ] Define `ExecutionFence` as an immutable record containing `taskId`, `owner` and `epoch`. Add `requireActiveFence(ExecutionFence, now)` to `AgentRunExecutionLeaseService`; it must verify task owner, exact epoch and lease expiry through a database predicate.
- [ ] Define the database predicate and typed failure contract before changing any durable write service. Control-plane lifecycle APIs remain separately named and validate their own ownership/CAS contract; they are not an unfenced escape hatch for executor writes.
- [ ] Run the characterization test from Task 0.1 and verify it fails for the missing fence contract rather than for a test wiring error.
- [ ] Run:

```powershell
mvn -f backend/pom.xml '-Dtest=AgentRunExecutionFenceTest,AgentRunExecutionLeaseServiceTest' test
```

Expected result: the immutable fence and active-lease predicate pass focused contract tests, with stale owner/epoch/expired-lease cases returning a typed failure.

### Task 0.5: Fence Durable Fact Writers

**Files:**
- Modify: `backend/src/main/java/com/labex/labexagent/run/AgentRunTranscriptService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/run/AgentRunMessageService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/run/AgentRunPartService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/run/AgentRunLifecycleService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/run/AgentRunArtifactService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/run/AgentRunPlanService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/run/RunCompletionEvidenceService.java`
- Modify: `backend/src/test/java/com/labex/labexagent/run/AgentRunTranscriptServiceTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/run/AgentRunMessageServiceTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/run/AgentRunPartServiceTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/run/AgentRunLifecycleServiceTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/run/AgentRunArtifactServiceTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/run/AgentRunPlanServiceTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/run/RunCompletionEvidenceServiceTest.java`

- [ ] Require `ExecutionFence` for executor-originated message, Part, lifecycle event, artifact, plan and completion writes. Each update must include task ID, owner, epoch and active lease in its SQL predicate and return a typed stale-fence failure when zero rows update.
- [ ] Keep queue initialization, transactional dispatch claims, interaction expiry, cancellation and scheduler takeover on their explicitly named control-plane APIs; validate their own state/ownership/CAS contract and do not accept an executor fence as a substitute.
- [ ] Add stale owner, stale epoch and expired lease tests for every durable fact family. Assert that no message, Part, event/outbox, artifact, plan or completion evidence is written.
- [ ] Add a secret sentinel test through a failed write path and assert absence from transcript, Part, event/outbox, artifact, SSE and error payloads.
- [ ] Run:

```powershell
mvn -f backend/pom.xml '-Dtest=AgentRunTranscriptServiceTest,AgentRunMessageServiceTest,AgentRunPartServiceTest,AgentRunLifecycleServiceTest,AgentRunArtifactServiceTest,AgentRunPlanServiceTest,RunCompletionEvidenceServiceTest' test
```

Expected result: all current-epoch writer tests pass and every stale-fence case is rejected without partial persistence.

### Task 0.6: Propagate ExecutionFence Through Executor Call Sites

**Files:**
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentContext.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentToolTurnExecutor.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentRunFinalizer.java`
- Modify: `backend/src/main/java/com/labex/labexagent/service/AgentPostEditHookService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/tool/impl/CreatePlanTool.java`
- Modify: `backend/src/main/java/com/labex/labexagent/tool/impl/PlanExitTool.java`
- Modify: `backend/src/main/java/com/labex/labexagent/tool/impl/TodoWriteTool.java`
- Modify: `backend/src/main/java/com/labex/labexagent/run/AgentToolCallJournalService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/run/CommandFailureGuard.java`
- Modify: `backend/src/main/java/com/labex/labexagent/run/AgentVerificationRecorder.java`
- Modify: `backend/src/main/java/com/labex/labexagent/tool/impl/RunTestsTool.java`
- Modify: `backend/src/test/java/com/labex/labexagent/runtime/AgentToolTurnExecutorTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/runtime/AgentRunFinalizerTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/runtime/AgentLoopEngineWiringContractTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/service/AgentPostEditHookServiceTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/tool/impl/CreatePlanToolVerificationTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/tool/impl/TodoWriteToolDurabilityTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/run/AgentToolCallJournalServiceTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/run/CommandFailureGuardTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/run/AgentVerificationRecorderTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/tool/impl/RunTestsToolTest.java`

- [ ] Put the fence on `AgentContext` only after the worker owns the task. Do not derive it from request JSON or a stale `AgentTask` object.
- [ ] Thread the fence through loop, tool-turn, finalizer, post-edit verification and plan call sites. No executor-originated durable write may silently create a fence from mutable context.
- [ ] Preserve the original `toolCallId`, task ID and epoch when plan/artifact/completion writes fail with stale-fence; mark the remaining batch according to existing interrupted/skipped rules.
- [ ] Run:

```powershell
mvn -f backend/pom.xml '-Dtest=AgentToolTurnExecutorTest,AgentRunFinalizerTest,AgentLoopEngineWiringContractTest,AgentPostEditHookServiceTest,CreatePlanToolVerificationTest' test
```

Expected result: executor call sites cannot write without the active fence, and stale-fence failures produce durable safe failure projection rather than partial completion.

### Task 0.7: Freeze Transitional Model Selection and Mode

**Files:**
- Modify: `backend/src/main/resources/sql/schema.sql:t_agent_task`
- Modify: `backend/src/main/java/com/labex/entity/AgentTask.java`
- Modify: `backend/src/main/java/com/labex/config/AdditiveSchemaMigrator.java`
- Modify: `backend/src/main/java/com/labex/labexagent/service/AgentTaskService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/run/AgentRunContinuationRequestFactory.java`
- Modify: `backend/src/main/java/com/labex/service/AgentModelConfigService.java`
- Create: `backend/src/main/java/com/labex/labexagent/run/AgentRunModeService.java`
- Create: `backend/src/main/java/com/labex/labexagent/run/AgentRunConfigurationException.java`
- Modify: `backend/src/main/java/com/labex/labexagent/tool/impl/PlanExitTool.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- Modify: `backend/src/main/java/com/labex/labexagent/run/AgentRunStateMachine.java`
- Modify: `backend/src/test/java/com/labex/labexagent/run/AgentRunContinuationRequestFactoryTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/run/AgentRunModelSelectionServiceTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/run/AgentRunStateMachineTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/run/AgentRunInteractionDispatchClaimTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/run/AgentRunInteractionMapperDatabaseTest.java`
- Create: `backend/src/test/java/com/labex/labexagent/run/AgentRunModeServiceTest.java`
- Create: `backend/src/test/java/com/labex/labexagent/tool/impl/PlanExitToolTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/runtime/AgentToolTurnExecutorTest.java`

- [ ] Add nullable `model_config_id` to `t_agent_task` and `AgentTask`; new tasks persist the exact selected config. Existing tasks with null values must fail closed during migration/recovery rather than selecting the new default.
- [ ] Change resume preparation to load the task first, acquire/validate the fence, then resolve the exact owned and enabled model config. A deleted, disabled or missing config returns a structured configuration failure and never calls `resolveForStudent` fallback.
- [ ] Add fenced `AgentRunModeService.transitionPlanToBuild(taskId, fence, idempotencyKey)` that CAS-updates task mode, emits `RUN_MODE_CHANGED`, and only then updates in-memory context.
- [ ] Make `PlanExitTool` call that service. Extract prompt/tool schema construction behind a rebuildable runtime projection. The next Provider call after plan exit must recompute mode policy, selected tools, system prompt and prompt-cache key.
- [ ] Add `RECOVERING -> PREPARING` and `PREPARING -> WAITING_ENVIRONMENT` transitions. Replace the direct recovering-to-running update in `AgentLoopEngine` with lifecycle transitions.
- [ ] Run:

```powershell
mvn -f backend/pom.xml '-Dtest=AgentRunContinuationRequestFactoryTest,AgentRunModelSelectionServiceTest,AgentRunModeServiceTest,PlanExitToolTest,AgentToolTurnExecutorTest,ToolSelectionPolicyTest,AgentRunStateMachineTest' test
```

Expected result: model selection is stable across resume, plan mode survives restart, build tools appear only after the durable mode transition, and illegal terminal transitions remain rejected.

### Task 0.8: Add Periodic Expired-Lease Reconciliation

**Files:**
- Modify: `backend/src/main/java/com/labex/labexagent/run/AgentRunRecoveryService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/run/AgentRunTakeoverScheduler.java`
- Modify: `backend/src/main/java/com/labex/mapper/AgentTaskMapper.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/java/com/labex/labexagent/run/AgentRunStateMachine.java`
- Modify: `backend/src/test/java/com/labex/labexagent/run/AgentRunRecoveryServiceTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/run/AgentRunTakeoverSchedulerTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/run/AgentRunLeaseReconcilerTest.java`

- [ ] Add a bounded scheduled `reconcileExpiredExecutionLeases()` query for `queued`, `preparing`, `running`, `recovering`, `waiting_workspace` and `waiting_environment` rows whose lease expired. Use a configurable interval and batch size with conservative defaults.
- [ ] Reclaim only non-terminal tasks with no active lease. Preserve waiting reasons; do not mark a waiting task completed or enqueue a task whose interaction is unresolved.
- [ ] Make `AgentRunTakeoverScheduler` claim one new epoch through the lifecycle/lease authority and emit one idempotent dispatch event. A dead `recovering` worker must be recoverable; a live owner must remain untouched.
- [ ] Test two scheduler instances, lease expiry, an active lease, stranded recovering state, unresolved interaction, terminal state and duplicate enqueue. The dedicated reconciler test must prove the scheduled path runs after startup rather than only through `ApplicationReadyEvent`; align the Task 0.1 characterization test's hook point with the implementation-chosen periodic pass (`AgentRunLeaseHeartbeatService.heartbeatScheduled()` or the new reconciler pass).
- [ ] Run:

```powershell
mvn -f backend/pom.xml '-Dtest=AgentRunRecoveryServiceTest,AgentRunTakeoverSchedulerTest,AgentRunLeaseReconcilerTest,AgentRunStateMachineTest,AgentRunLeaseHeartbeatServiceTest' test
mvn -f backend/pom.xml test
```

Expected result: Phase 0 tests and the full backend suite pass with no new failures. Record any existing platform/environment skip separately.

## Phase 1: Project Configuration Source, Revision and Runtime Snapshot

### Task 1.1: Implement Protected Config Reader and Canonical Schema

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/projectconfig/AgentProjectConfigDocument.java`
- Create: `backend/src/main/java/com/labex/labexagent/projectconfig/AgentProjectConfigReader.java`
- Create: `backend/src/main/java/com/labex/labexagent/projectconfig/AgentProjectConfigValidator.java`
- Create: `backend/src/main/java/com/labex/labexagent/projectconfig/AgentProjectConfigCanonicalizer.java`
- Create: `backend/src/main/java/com/labex/labexagent/projectconfig/ProtectedProjectConfigPath.java`
- Create: `backend/src/test/java/com/labex/labexagent/projectconfig/AgentProjectConfigReaderTest.java`
- Create: `backend/src/test/java/com/labex/labexagent/projectconfig/AgentProjectConfigValidatorTest.java`
- Create: `backend/src/test/java/com/labex/labexagent/projectconfig/AgentProjectConfigCanonicalizerTest.java`

- [ ] Read only `.labex-agent/project/agent.json` and its declared child files under the owned workspace. Reject symlinks, traversal, absolute paths, files outside the project and files exceeding configured size limits.
- [ ] Parse JSON with explicit `schemaVersion`; reject unknown keys, duplicate semantic definitions, unsupported runtime profiles, hard-denied capabilities, secret-looking keys and invalid model/MCP/Skill references.
- [ ] Canonicalize object key order, normalize line endings and produce SHA-256 digest. Formatting-only changes must retain the same digest; semantic changes must change it.
- [ ] Return structured validation errors with file path, JSON path, reason code and no raw secret value.
- [ ] Run:

```powershell
mvn -f backend/pom.xml '-Dtest=AgentProjectConfigReaderTest,AgentProjectConfigValidatorTest,AgentProjectConfigCanonicalizerTest' test
```

Expected result: traversal, symlink, unknown-key, secret-key, malformed JSON and ceiling violations fail closed; equivalent formatting has one digest.

### Task 1.2: Add Revision, Proposal-Supporting Schema and Additive Migration

**Files:**
- Modify: `backend/src/main/resources/sql/schema.sql`
- Modify: `backend/src/main/java/com/labex/config/AdditiveSchemaMigrator.java`
- Create: `backend/src/main/java/com/labex/entity/AgentProjectConfigRevision.java`
- Create: `backend/src/main/java/com/labex/mapper/AgentProjectConfigRevisionMapper.java`
- Create: `backend/src/main/java/com/labex/entity/AgentRunConfigSnapshot.java`
- Create: `backend/src/main/java/com/labex/mapper/AgentRunConfigSnapshotMapper.java`
- Create: `backend/src/main/java/com/labex/entity/AgentProjectConfigExternalChange.java`
- Create: `backend/src/main/java/com/labex/mapper/AgentProjectConfigExternalChangeMapper.java`
- Create: `backend/src/test/java/com/labex/labexagent/projectconfig/AgentProjectConfigSchemaTest.java`
- Create: `backend/src/test/java/com/labex/labexagent/projectconfig/AgentProjectConfigRevisionMapperDatabaseTest.java`
- Create: `backend/src/test/java/com/labex/labexagent/projectconfig/AgentRunConfigSnapshotMapperDatabaseTest.java`
- Create: `backend/src/test/java/com/labex/labexagent/projectconfig/AgentProjectConfigExternalChangeMapperDatabaseTest.java`

- [ ] Add `t_agent_project_config_revision` with owner/project/revision/digest/tree reference/schema version/normalized config/source actor/timestamps and a unique `(project_id, revision)` constraint.
- [ ] Add `t_agent_run_config_snapshot` with `task_id`, `execution_epoch`, project revision, effective config JSON/digest, model fingerprint, capability/resource digests, runtime/network/verification policy, secret aliases and timestamps; enforce unique `(task_id, execution_epoch)`.
- [ ] Add `t_agent_project_config_external_change` with one pending record per project/base revision/tree digest, redacted changed-path summary, status and materialized proposal ID. It is an observation/review fact, not a proposal decision authority.
- [ ] Add owner/project/revision, project/digest, task/epoch and task/project indexes. Never store secret values or raw provider credentials.
- [ ] Add table creation and duplicate-table handling to `AdditiveSchemaMigrator`; add any nullable legacy columns as additive only.
- [ ] Test fresh schema and existing-schema migration paths using the repository's database test pattern. Assert a second migration is idempotent and no destructive SQL is emitted.
- [ ] Run:

```powershell
mvn -f backend/pom.xml '-Dtest=AgentProjectConfigSchemaTest,AgentProjectConfigRevisionMapperDatabaseTest,AgentRunConfigSnapshotMapperDatabaseTest,AgentProjectConfigExternalChangeMapperDatabaseTest,AgentRunSchemaTest' test
```

Expected result: schema tests pass on the supported test database and repeated migration does not alter existing data.

### Task 1.3: Implement Revision Service, Ownership and External-Change Detection

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/projectconfig/AgentProjectConfigRevisionService.java`
- Create: `backend/src/main/java/com/labex/labexagent/projectconfig/AgentProjectConfigOwnership.java`
- Create: `backend/src/main/java/com/labex/labexagent/projectconfig/AgentProjectConfigExternalChangeService.java`
- Create: `backend/src/main/java/com/labex/labexagent/projectconfig/AgentProjectConfigFileWriter.java`
- Create: `backend/src/main/java/com/labex/labexagent/controller/AgentProjectConfigController.java`
- Create: `backend/src/test/java/com/labex/labexagent/projectconfig/AgentProjectConfigRevisionServiceTest.java`
- Create: `backend/src/test/java/com/labex/labexagent/projectconfig/AgentProjectConfigExternalChangeServiceTest.java`
- Create: `backend/src/test/java/com/labex/labexagent/projectconfig/AgentProjectConfigFileWriterTest.java`
- Create: `backend/src/test/java/com/labex/labexagent/controller/AgentProjectConfigControllerTest.java`

- [ ] Require `StudentProjectService.getOwnedProject(studentId, projectId)` before every read/write. Ignore client-supplied student IDs, project ownership and task epochs.
- [ ] On first load, validate the project config tree and create revision zero or the first accepted revision with digest and audit metadata. On subsequent loads, compare the file tree digest with the accepted revision.
- [ ] If an external editor changed the protected tree, persist an `external_change_pending` marker with a structured diff summary and block task execution until Task 2.2 materializes it as an `external_change` proposal through the owner-only decision flow. Do not write the proposal table from this revision reader and do not silently create a new effective revision.
- [ ] Implement `AgentProjectConfigFileWriter` with a project-root lock, temp-file write, fsync where the filesystem supports it, atomic move or fail-closed replacement, post-write tree digest verification and cleanup on failure. It may write only validated paths beneath `.labex-agent/project/`.
- [ ] Add `GET /student/projects/{projectId}/agent/config` returning redacted document, revision, digest, validation status, trust/runtime status, enabled resource references and environment status.
- [ ] Return HTTP 409 for stale revision conflicts; do not claim that `Result.error(409, ...)` changes the HTTP status unless the controller explicitly sets it.
- [ ] Test cross-project ownership, first load, equivalent formatting, external edit, malformed config, stale revision and secret redaction.
- [ ] Run:

```powershell
mvn -f backend/pom.xml '-Dtest=AgentProjectConfigRevisionServiceTest,AgentProjectConfigExternalChangeServiceTest,AgentProjectConfigFileWriterTest,AgentProjectConfigControllerTest,StudentProjectServicePathTest' test
```

Expected result: owned projects can be read, foreign projects are indistinguishable from not found, and external/config-invalid states fail closed.

### Task 1.4: Create Effective Task-Epoch Snapshots

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/projectconfig/AgentEffectiveProjectConfigService.java`
- Create: `backend/src/main/java/com/labex/labexagent/projectconfig/AgentRunConfigSnapshotService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/service/AgentTaskService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- Create: `backend/src/test/java/com/labex/labexagent/projectconfig/AgentEffectiveProjectConfigServiceTest.java`
- Create: `backend/src/test/java/com/labex/labexagent/projectconfig/AgentRunConfigSnapshotServiceTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/runtime/AgentRunProcessorWiringTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/service/AgentTaskServiceTest.java`

- [ ] Resolve project config, owner-owned catalog references, platform ceiling and request defaults into one complete non-secret effective document before task queueing.
- [ ] Insert `t_agent_run_config_snapshot` before the new task enters `queued`; include model/Skill/MCP/tool/resource fingerprints and secret aliases only.
- [ ] On resume or takeover, copy the same effective revision into the new epoch only after the fence is claimed. Do not mutate or overwrite the prior epoch snapshot.
- [ ] For existing active tasks without a snapshot, read only the exact persisted model reference once, create a migration snapshot, and fail closed if any reference cannot be resolved. Do not use current default fallback.
- [ ] Test new-task snapshot creation, immutable epoch copy, changed catalog row, changed project digest, missing resource and secret-value absence.
- [ ] Run:

```powershell
mvn -f backend/pom.xml '-Dtest=AgentEffectiveProjectConfigServiceTest,AgentRunConfigSnapshotServiceTest,AgentRunProcessorWiringTest,AgentRunContinuationRequestFactoryTest' test
```

Expected result: all model-facing runtime inputs are reconstructible from the durable snapshot for the current epoch.

## Phase 2: Proposals, Approval, Audit and Secret Input

### Task 2.1: Add Proposal and Audit Persistence

**Files:**
- Modify: `backend/src/main/resources/sql/schema.sql`
- Modify: `backend/src/main/java/com/labex/config/AdditiveSchemaMigrator.java`
- Create: `backend/src/main/java/com/labex/entity/AgentProjectConfigProposal.java`
- Create: `backend/src/main/java/com/labex/mapper/AgentProjectConfigProposalMapper.java`
- Create: `backend/src/main/java/com/labex/entity/AgentProjectConfigAuditEvent.java`
- Create: `backend/src/main/java/com/labex/mapper/AgentProjectConfigAuditEventMapper.java`
- Create: `backend/src/test/java/com/labex/labexagent/projectconfig/AgentProjectConfigProposalMapperDatabaseTest.java`
- Create: `backend/src/test/java/com/labex/labexagent/projectconfig/AgentProjectConfigAuditEventMapperDatabaseTest.java`

- [ ] Add proposal fields from the spec: base revision, candidate digest/patch reference, origin task/epoch/tool call, status, expiry, decision idempotency key, actor/time and applied revision. Set normal proposal expiry to exactly one minute unless a stricter platform policy supplies a shorter deadline.
- [ ] Add append-only audit fields: actor, reason, previous/next status, before/after digest, changed paths, task/epoch and idempotency key. Do not add raw config or secret columns to audit.
- [ ] Add unique idempotency constraints and project/status/time indexes. Use immutable rows for audit; never update or delete audit history.
- [ ] Test duplicate proposal key, duplicate decision key, stale base revision, expiry boundary and append-only audit rows.
- [ ] Run:

```powershell
mvn -f backend/pom.xml '-Dtest=AgentProjectConfigProposalMapperDatabaseTest,AgentProjectConfigAuditEventMapperDatabaseTest,AgentRunSchemaTest' test
```

Expected result: proposal state and audit persistence are transaction-safe and idempotent.

### Task 2.2: Implement Proposal Service and Decision API

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/projectconfig/AgentProjectConfigProposalService.java`
- Create: `backend/src/main/java/com/labex/labexagent/projectconfig/AgentProjectConfigChangeEvidenceService.java`
- Create: `backend/src/main/java/com/labex/labexagent/projectconfig/AgentProjectConfigApplyRecoveryService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/projectconfig/AgentProjectConfigExternalChangeService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/projectconfig/AgentProjectConfigFileWriter.java`
- Modify: `backend/src/main/java/com/labex/labexagent/controller/AgentProjectConfigController.java`
- Create: `backend/src/test/java/com/labex/labexagent/projectconfig/AgentProjectConfigProposalServiceTest.java`
- Create: `backend/src/test/java/com/labex/labexagent/projectconfig/AgentProjectConfigChangeEvidenceServiceTest.java`
- Create: `backend/src/test/java/com/labex/labexagent/projectconfig/AgentProjectConfigApplyRecoveryServiceTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/controller/AgentProjectConfigControllerTest.java`

- [ ] Add `POST /student/projects/{projectId}/agent/config/proposals` accepting `expectedRevision`, complete candidate document/patch, reason and idempotency key. Derive student/project/actor from authentication and route ownership.
- [ ] Add list/detail endpoints that return redacted proposal data, changed paths, digest, state, expiry and audit references.
- [ ] Add `POST /student/projects/{projectId}/agent/config/proposals/{proposalId}/decision` accepting only `approve` or `reject`, expected revision and decision idempotency key.
- [ ] Materialize an `external_change_pending` marker from Task 1.3 as an `external_change` proposal through the same owner/project/CAS authority. The revision reader never creates or approves that proposal itself.
- [ ] Implement approval as a recoverable state machine rather than claiming one database transaction can roll back the filesystem: lock the current head, CAS `pending -> applying`, validate references/capabilities, write a staged protected tree, record Git/change evidence, atomically publish the verified tree, then insert revision, CAS `applying -> applied`, append audit and durable event/outbox. New task snapshots remain blocked while a proposal is `applying`.
- [ ] Add `AgentProjectConfigApplyRecoveryService` to reconcile crash points before/after staging, publish, evidence and revision insert. Reconciliation must either finish the same idempotent apply or mark a typed recoverable/failed state; it must never expose a false applied revision or silently discard an externally visible file tree.
- [ ] Return the first durable result for repeated identical decision keys; return 409 for stale revision and a safe 4xx for foreign/stale proposal IDs.
- [ ] Test approve, reject, expired, stale, conflict, duplicate, cross-user, changed file tree, invalid capability and each staged apply crash point.
- [ ] Run:

```powershell
mvn -f backend/pom.xml '-Dtest=AgentProjectConfigProposalServiceTest,AgentProjectConfigChangeEvidenceServiceTest,AgentProjectConfigApplyRecoveryServiceTest,AgentProjectConfigControllerTest,AgentRunLifecycleServiceTest' test
```

Expected result: no proposal can apply without owner approval, matching digest and current revision, and every filesystem/database crash point is durably reconcilable without a false applied head.

### Task 2.3: Connect Blocking Proposals to Durable Interaction and Events

**Files:**
- Modify: `backend/src/main/resources/sql/schema.sql:t_agent_run_interaction`
- Modify: `backend/src/main/java/com/labex/entity/AgentRunInteraction.java`
- Modify: `backend/src/main/java/com/labex/labexagent/run/AgentRunInteractionService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/run/AgentRunPartService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/run/AgentRunLifecycleService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/run/AgentRunEventReplayService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/projectconfig/AgentProjectConfigProposalService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/controller/AgentProjectConfigController.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentToolTurnExecutor.java`
- Modify: `backend/src/main/java/com/labex/labexagent/run/AgentRunInteractionTimeoutService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- Create: `backend/src/main/java/com/labex/labexagent/tool/impl/ProposeProjectConfigTool.java`
- Create: `backend/src/test/java/com/labex/labexagent/run/AgentRunConfigProposalInteractionTest.java`
- Create: `backend/src/test/java/com/labex/labexagent/tool/impl/ProposeProjectConfigToolTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/runtime/AgentToolTurnExecutorTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/run/AgentRunInteractionTimeoutServiceTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/runtime/AgentLoopEngineWiringContractTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/runtime/AgentLoopEngineStreamingContractTest.java`

- [ ] Add interaction type `config_proposal` with allowed statuses `approved` and `rejected`, but keep proposal table as the authority. Interaction payload contains only proposal ID, digest, paths and expiry.
- [ ] Create a Tool Part linked to the originating `toolCallId` before task wait. Mark remaining same-turn Parts `skipped` or `interrupted` according to existing batch rules.
- [ ] Emit `CONFIG_PROPOSAL_CREATED`, `CONFIG_PROPOSAL_DECIDED`, `CONFIG_REVISION_APPLIED` and `CONFIG_PROPOSAL_FAILED` task projections with IDs/digests/status only.
- [ ] Resolve interaction and proposal in one decision path owned by `AgentProjectConfigProposalService` plus the transactional interaction claim. A resolved interaction without a successful proposal decision must never resume the task.
- [ ] Add `propose_project_config` as the only Agent-side configuration mutation entry. It creates a proposal through the service, carries task/epoch/toolCall provenance from `AgentContext`, and has no apply, secret rotation or host-maintenance capability.
- [ ] Test refresh/replay, duplicate decision, interaction expiration, proposal conflict, toolCallId correlation and resumed epoch.
- [ ] Run:

```powershell
mvn -f backend/pom.xml '-Dtest=AgentRunConfigProposalInteractionTest,ProposeProjectConfigToolTest,AgentRunPartServiceTest,AgentRunEventReplayServiceTest,AgentToolTurnExecutorTest' test
```

Expected result: frontend can reconstruct proposal waits from durable facts, and an SSE connection is not required for correctness.

### Task 2.4: Add Write-Only Secret Input and Scoped Worker Lease

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/secret/ProjectSecretBindingService.java`
- Create: `backend/src/main/java/com/labex/labexagent/secret/ScopedSecretLeaseService.java`
- Create: `backend/src/main/java/com/labex/entity/AgentProjectSecretBinding.java`
- Create: `backend/src/main/java/com/labex/mapper/AgentProjectSecretBindingMapper.java`
- Modify: `backend/src/main/java/com/labex/labexagent/controller/AgentProjectConfigController.java`
- Modify: `backend/src/main/java/com/labex/labexagent/secret/SecretStore.java`
- Modify: `backend/src/main/resources/sql/schema.sql`
- Modify: `backend/src/main/java/com/labex/config/AdditiveSchemaMigrator.java`
- Create: `backend/src/test/java/com/labex/labexagent/secret/ProjectSecretBindingServiceTest.java`
- Create: `backend/src/test/java/com/labex/labexagent/secret/ScopedSecretLeaseServiceTest.java`
- Create: `backend/src/test/java/com/labex/labexagent/secret/AgentProjectSecretBindingMapperDatabaseTest.java`

- [ ] Add project/owner/alias binding metadata and encrypted SecretStore scope. The value endpoint must accept one input ID and value, encrypt immediately, and return only alias/configured status.
- [ ] Add `POST /student/projects/{projectId}/agent/config/proposals/{proposalId}/secret-input` with owner, proposal, field ID, TTL and one-time idempotency checks. Never serialize its request into an interaction response or transcript.
- [ ] Define `ScopedSecretLease` with task, epoch, worker run ID, allowed names, expiry and one-time cleanup. Deny reserved control-plane variables and command-line injection.
- [ ] This task issues and persists only encrypted binding metadata plus a lease reference. Worker/terminal/LSP/MCP injection is wired in Task 4.2 and Task 3.2; do not add a second secret injection path here.
- [ ] Add request/log redaction tests using a sentinel; assert secret text is absent from all serialized domain objects and error paths.
- [ ] Run:

```powershell
mvn -f backend/pom.xml '-Dtest=ProjectSecretBindingServiceTest,ScopedSecretLeaseServiceTest,AgentProjectSecretBindingMapperDatabaseTest,LocalEnvelopeSecretStoreTest,AgentModelConfigSecretTest,AgentMcpServerSecretTest' test
```

Expected result: secret values are encrypted and injected only through a bounded lease; no ordinary interaction or config snapshot contains them.

## Phase 3: Unified Capability and Extension Runtime

### Task 3.1: Introduce AgentCapabilityPolicy as the Single Gate

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/capability/AgentCapabilityPolicy.java`
- Create: `backend/src/main/java/com/labex/labexagent/capability/AgentCapabilityPolicyResolver.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentContext.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- Modify: `backend/src/main/java/com/labex/labexagent/tool/ToolRegistry.java`
- Modify: `backend/src/main/java/com/labex/labexagent/tool/ToolSelectionPolicy.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentToolTurnExecutor.java`
- Modify: `backend/src/main/java/com/labex/labexagent/permission/DefaultPermissionRuleset.java`
- Modify: `backend/src/main/java/com/labex/labexagent/permission/PermissionService.java`
- Create: `backend/src/test/java/com/labex/labexagent/capability/AgentCapabilityPolicyTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/tool/ToolRegistryModeTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/tool/ToolSelectionPolicyTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/runtime/AgentToolTurnExecutorTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/runtime/AgentLoopEnginePolicyContractTest.java`

- [ ] Define policy input as the immutable epoch snapshot plus platform ceiling and project trust state; define output as tool schema selection, execution decision, network class, file scope and approval requirement.
- [ ] Resolve one immutable policy decision into `AgentContext`. Make `AgentLoopEngine` schema generation and `AgentToolTurnExecutor` execution checks consume that same decision. Remove independent `MODE_ALLOWED_TOOLS`, selected-name checks and project permission append-order behavior once shadow comparison is green.
- [ ] Hard-code platform denies for secrets, metadata, cross-user data and protected config. Project rules may only add capability within the ceiling.
- [ ] Test schema/execution parity, unknown tool, plan/build transition, project override within ceiling, ceiling violation, remembered permission mismatch and stale epoch.
- [ ] Run:

```powershell
mvn -f backend/pom.xml '-Dtest=AgentCapabilityPolicyTest,ToolRegistryModeTest,ToolSelectionPolicyTest,AgentToolTurnExecutorTest,AgentLoopEnginePolicyContractTest,PermissionServicePersistenceTest' test
```

Expected result: a tool is exposed and executable only when the same policy permits it for the current epoch.

### Task 3.2: Resolve Model, MCP and Skill from the Epoch Snapshot

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/capability/AgentEffectiveResourceResolver.java`
- Modify: `backend/src/main/java/com/labex/service/AgentModelConfigService.java`
- Modify: `backend/src/main/java/com/labex/service/AgentMcpServerService.java`
- Modify: `backend/src/main/java/com/labex/service/AgentSkillService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/mcp/McpManager.java`
- Modify: `backend/src/main/java/com/labex/labexagent/tool/impl/McpCallTool.java`
- Modify: `backend/src/main/java/com/labex/labexagent/tool/impl/SkillTool.java`
- Create: `backend/src/test/java/com/labex/labexagent/capability/AgentEffectiveResourceResolverTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/mcp/McpToolAdapterWorkerTest.java`

- [ ] Resolve catalog resources once while creating the epoch snapshot. Store stable IDs/slugs, enabled state and config digest; do not re-read a mutable user default during a run.
- [ ] Enforce owner and project-effective allowlist in MCP and Skill tools; a user-owned resource not selected by the project config is unavailable to the task.
- [ ] Ensure MCP auth uses `ScopedSecretLease`; redact endpoint arguments, headers and errors.
- [ ] Test resource deletion/disable after snapshot, owner mismatch, project allowlist, digest mismatch and reconnect after a new epoch.
- [ ] Run:

```powershell
mvn -f backend/pom.xml '-Dtest=AgentEffectiveResourceResolverTest,McpToolAdapterWorkerTest,McpClientWorkerTest,AgentModelConfigCapabilitiesTest,AgentMcpServerSecretTest' test
```

Expected result: resource changes affect only a newly created snapshot/epoch and never silently alter a suspended run.

### Task 3.3: Resolve Named Agents and Epoch-Scoped Tool Catalogs

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/capability/AgentDefinitionResolver.java`
- Create: `backend/src/main/java/com/labex/labexagent/capability/AgentEpochToolCatalog.java`
- Modify: `backend/src/main/java/com/labex/labexagent/projectconfig/AgentEffectiveProjectConfigService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentContext.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentToolTurnExecutor.java`
- Create: `backend/src/test/java/com/labex/labexagent/capability/AgentDefinitionResolverTest.java`
- Create: `backend/src/test/java/com/labex/labexagent/capability/AgentEpochToolCatalogTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/runtime/AgentToolTurnExecutorTest.java`

- [ ] Resolve `agent.json`'s default Agent and declared `agents/*.json` definitions into the effective snapshot. A named Agent may select only its declared non-secret model/resource/capability references within the project policy; it cannot change task ownership, platform ceiling, host maintenance or secret scope.
- [ ] Build `AgentEpochToolCatalog` from the fixed snapshot, store it on `AgentContext`, and use the same catalog for model schema and tool execution. It owns the epoch-local view of built-in definitions, project script definitions and project-allowed MCP definitions.
- [ ] Stop consuming `ToolRegistry.dynamicTools` as a cross-project MCP authority. Task 3.1 leaves `ToolRegistry` as the built-in directory; this task overlays allowed dynamic entries in the epoch catalog without mutating the registry or sharing entries with another project/task.
- [ ] Test two projects using same-named MCP tools, two epochs with different named Agent definitions, a deleted Agent file after snapshot, an undeclared Agent selection and catalog isolation under concurrent tasks.
- [ ] Run:

```powershell
mvn -f backend/pom.xml '-Dtest=AgentDefinitionResolverTest,AgentEpochToolCatalogTest,AgentEffectiveResourceResolverTest,ToolRegistryModeTest,AgentToolTurnExecutorTest' test
```

Expected result: Agent, MCP and dynamic tool selection are immutable within an epoch and cannot leak across projects.

### Task 3.4: Add Project Sandbox Script Tools

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/tool/project/ProjectScriptToolDefinition.java`
- Create: `backend/src/main/java/com/labex/labexagent/tool/project/ProjectScriptToolLoader.java`
- Create: `backend/src/main/java/com/labex/labexagent/tool/project/ProjectScriptToolExecutor.java`
- Modify: `backend/src/main/java/com/labex/labexagent/capability/AgentEpochToolCatalog.java`
- Create: `backend/src/test/java/com/labex/labexagent/tool/project/ProjectScriptToolLoaderTest.java`
- Create: `backend/src/test/java/com/labex/labexagent/tool/project/ProjectScriptToolExecutorTest.java`

- [ ] Load only declared `.labex-agent/project/tools/*.json` definitions whose script path stays inside a project-owned tools directory. Reject symlinks, executable extensions outside the allowlist, unbounded output and undeclared environment access.
- [ ] Use a fixed stdin/stdout protocol: request JSON on stdin, one bounded JSON envelope on stdout, structured non-secret stderr, exit code and timeout. Do not let scripts write directly to control-plane DB or emit arbitrary tool schemas at runtime.
- [ ] Add validated script definitions only to the current `AgentEpochToolCatalog`. Execute scripts only through the current Worker and `AgentCapabilityPolicy`; project tool definitions cannot mutate `ToolRegistry` or bypass command/network approval and platform denies.
- [ ] Reload definitions only at a new execution epoch after proposal approval. Current tool batches use their original schema.
- [ ] Test malformed definitions, traversal, schema mismatch, output limit, timeout, cancellation, network denial and epoch reload.
- [ ] Run:

```powershell
mvn -f backend/pom.xml '-Dtest=ProjectScriptToolLoaderTest,ProjectScriptToolExecutorTest,ToolArgumentSchemaValidatorTest,CommandToolWorkerTest' test
```

Expected result: a valid project script behaves like a normal sandboxed tool, and invalid scripts fail before execution.

## Phase 4: Integrated Workers and Environment Operations

### Task 4.1: Define Runtime Profiles and the Worker Router

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/worker/AgentRuntimeProfile.java`
- Create: `backend/src/main/java/com/labex/labexagent/worker/AgentEnvironmentPolicy.java`
- Create: `backend/src/main/java/com/labex/labexagent/worker/SandboxWorkerConfiguration.java`
- Create: `backend/src/main/java/com/labex/labexagent/worker/SandboxWorkerRouter.java`
- Modify: `backend/src/main/java/com/labex/labexagent/worker/WorkerRunSpec.java`
- Modify: `backend/src/main/java/com/labex/labexagent/worker/WorkerPolicy.java`
- Create: `backend/src/test/java/com/labex/labexagent/worker/AgentRuntimeProfileTest.java`
- Create: `backend/src/test/java/com/labex/labexagent/worker/SandboxWorkerRouterTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/worker/SandboxWorkerContractTest.java`

- [ ] Define `strict`, `integrated-wsl`, `integrated-windows` and explicit `full-access` with platform ceiling checks. `unsafe-local` remains diagnostic-only and cannot be selected by project config. `full-access` requires a user-originated high-risk per-task decision recorded in the epoch snapshot; no project file or Agent proposal can enable it alone.
- [ ] Add runtime target, distribution, network class, writable roots, safe environment names, secret lease reference and verification policy to `WorkerRunSpec`; do not pass arbitrary host environment maps.
- [ ] Add `SandboxWorkerConfiguration` with one `@Primary SandboxWorkerRouter` for existing constructor injection sites. The router owns named delegate slots but does not silently select an unavailable delegate.
- [ ] Make `SandboxWorkerRouter` choose only from immutable `WorkerRunSpec`. If an integrated delegate is missing or unhealthy, return a typed infrastructure failure; only a separately policy-selected `strict` spec may use the strict delegate.
- [ ] Test profile serialization, explicit strict selection, unsafe-local rejection, missing delegate failure and platform ceiling enforcement.
- [ ] Run:

```powershell
mvn -f backend/pom.xml '-Dtest=AgentRuntimeProfileTest,SandboxWorkerRouterTest,SandboxWorkerContractTest' test
```

Expected result: profile and router contracts are explicit, and an unavailable integrated profile does not silently become strict or local.

### Task 4.2: Wire Worker Delegates and Existing Entry Points

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/worker/IntegratedWslWorker.java`
- Modify: `backend/src/main/java/com/labex/labexagent/worker/SandboxWorkerConfiguration.java`
- Modify: `backend/src/main/java/com/labex/labexagent/worker/SandboxWorkerRouter.java`
- Modify: `backend/src/main/java/com/labex/labexagent/worker/SandboxWorker.java`
- Modify: `backend/src/main/java/com/labex/labexagent/worker/WslSandboxWorker.java`
- Modify: `backend/src/main/java/com/labex/labexagent/worker/DockerSandboxWorker.java`
- Modify: `backend/src/main/java/com/labex/labexagent/worker/LocalDevelopmentWorker.java`
- Modify: `backend/src/main/java/com/labex/labexagent/tool/impl/BashTool.java`
- Modify: `backend/src/main/java/com/labex/labexagent/tool/impl/ExecuteCodeTool.java`
- Modify: `backend/src/main/java/com/labex/labexagent/tool/impl/RunCommandTool.java`
- Modify: `backend/src/main/java/com/labex/labexagent/tool/impl/RunTestsTool.java`
- Modify: `backend/src/main/java/com/labex/labexagent/commandsecurity/AgentApprovedCommandExecutor.java`
- Modify: `backend/src/main/java/com/labex/labexagent/mcp/McpToolAdapter.java`
- Modify: `backend/src/main/java/com/labex/service/ProjectTerminalService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/lsp/LspSessionManager.java`
- Modify: `backend/src/main/java/com/labex/labexagent/terminal/TerminalWebSocketHandler.java`
- Create: `backend/src/test/java/com/labex/labexagent/worker/IntegratedWslWorkerTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/worker/SandboxWorkerContractTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/worker/WslSandboxWorkerTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/tool/impl/CommandToolWorkerTest.java`
- Modify: `backend/src/test/java/com/labex/service/ProjectTerminalServiceWorkerTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/lsp/LspSessionManagerWorkerTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/mcp/McpToolAdapterWorkerTest.java`

- [ ] Keep `WslSandboxWorker` as the strict bubblewrap implementation. `IntegratedWslWorker` runs in the selected WSL distro against the same `/mnt/d` project workspace without the strict bwrap mount model, but still uses clear environment construction, scoped secret leases, process-tree supervision, workspace validation and network policy.
- [ ] Update every listed `WorkerRunSpec.forWorkspace(...)` creator to obtain profile/policy from the current task snapshot or explicit managed-terminal project policy. Terminal, LSP and MCP runs without a task default to an explicitly constructed strict spec and cannot borrow a privileged Agent task profile.
- [ ] Preserve Worker contract methods (`prepare`, `execute`, `startProcess`, `openTerminal`, `readFile`, `applyChange`, `collectArtifacts`, `terminate`) and make profile behavior explicit in each delegate.
- [ ] An unavailable integrated profile returns a typed infrastructure failure. It must never automatically switch to strict; strict is used only when the caller's immutable spec explicitly selected strict.
- [ ] Test workspace-only writes, network policy, secret absence, process-tree cleanup and profile propagation through command, terminal, LSP and MCP entry points.
- [ ] Run:

```powershell
mvn -f backend/pom.xml '-Dtest=IntegratedWslWorkerTest,SandboxWorkerContractTest,WslSandboxWorkerTest,DockerSandboxWorkerSmokeTest,CommandToolWorkerTest,ProjectTerminalServiceWorkerTest,LspSessionManagerWorkerTest,McpToolAdapterWorkerTest' test
```

Expected result: every execution entry point consumes the immutable profile, and integrated-worker failure is visible rather than a hidden fallback.

### Task 4.3: Add Durable Environment Operation Coordinator

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/environment/AgentEnvironmentOperation.java`
- Create: `backend/src/main/java/com/labex/labexagent/environment/AgentEnvironmentOperationService.java`
- Create: `backend/src/main/java/com/labex/labexagent/environment/AgentEnvironmentProbeService.java`
- Create: `backend/src/main/java/com/labex/labexagent/environment/AgentDependencyRepairService.java`
- Create: `backend/src/main/java/com/labex/labexagent/controller/AgentEnvironmentOperationController.java`
- Create: `backend/src/main/java/com/labex/labexagent/dto/AgentEnvironmentOperationRequest.java`
- Modify: `backend/src/main/resources/sql/schema.sql`
- Modify: `backend/src/main/java/com/labex/config/AdditiveSchemaMigrator.java`
- Create: `backend/src/main/java/com/labex/entity/AgentEnvironmentOperationEntity.java`
- Create: `backend/src/main/java/com/labex/mapper/AgentEnvironmentOperationMapper.java`
- Create: `backend/src/test/java/com/labex/labexagent/environment/AgentEnvironmentOperationServiceTest.java`
- Create: `backend/src/test/java/com/labex/labexagent/environment/AgentEnvironmentProbeServiceTest.java`
- Create: `backend/src/test/java/com/labex/labexagent/environment/AgentEnvironmentOperationControllerTest.java`

- [ ] Add the operation table and state machine from the spec. Persist operation type, scope, attempt, deadline, requested digest, result digest, failure category and idempotency key.
- [ ] Implement deterministic probes for project toolchain, package manager, network reachability, CA/proxy, workspace write and localhost binding. Probe results must be bounded and secret-free.
- [ ] Repair only project/user-level dependencies by default. System package managers, PATH, certificates, services and drivers create a higher-risk proposal and never execute from an arbitrary shell string.
- [ ] Add owner/project-authorized `POST /student/projects/{projectId}/agent/environment/operations` and `GET /student/projects/{projectId}/agent/environment/operations/{operationId}`. Derive actor/project from authentication and route ownership; the controller never accepts task owner, epoch or capability ceiling from the request body.
- [ ] On retryable failure, transition task to `waiting_environment`; on success, health-check then create a new epoch snapshot for a resumed task. On non-retryable failure, persist failure and stop.
- [ ] Test duplicate operation, bounded attempts, timeout, probe failure classification, cancellation, task wait/resume and no fake terminal event.
- [ ] Run:

```powershell
mvn -f backend/pom.xml '-Dtest=AgentEnvironmentOperationServiceTest,AgentEnvironmentProbeServiceTest,AgentEnvironmentOperationControllerTest,EnvironmentBlockerClassifierTest,CommandFailureGuardTest' test
```

Expected result: environment repair is durable, bounded and distinguishable from code failure.

### Task 4.4: Add Structured WSL Config Validation and Guarded Host Adapter

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/environment/WslConfigSchema.java`
- Create: `backend/src/main/java/com/labex/labexagent/environment/WslConfigValidator.java`
- Create: `backend/src/main/java/com/labex/labexagent/environment/WslHostMaintenanceAdapter.java`
- Create: `backend/src/main/java/com/labex/labexagent/environment/GlobalMaintenanceLeaseService.java`
- Create: `backend/src/main/java/com/labex/labexagent/environment/EnvironmentDrainCoordinator.java`
- Modify: `backend/src/main/java/com/labex/labexagent/environment/AgentEnvironmentOperationService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/worker/SandboxWorkerRouter.java`
- Modify: `backend/src/main/java/com/labex/labexagent/service/AgentTaskService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/terminal/TerminalWebSocketHandler.java`
- Modify: `backend/src/main/java/com/labex/labexagent/lsp/LspSessionManager.java`
- Modify: `backend/src/main/java/com/labex/labexagent/mcp/McpManager.java`
- Create: `backend/src/test/java/com/labex/labexagent/environment/WslConfigValidatorTest.java`
- Create: `backend/src/test/java/com/labex/labexagent/environment/GlobalMaintenanceLeaseServiceTest.java`
- Create: `backend/src/test/java/com/labex/labexagent/environment/EnvironmentDrainCoordinatorTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/environment/AgentEnvironmentOperationServiceTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/worker/SandboxWorkerRouterTest.java`

- [ ] Represent every supported official `.wslconfig` key as a typed field with range/enum validation. Reject unknown keys and full raw-text replacement.
- [ ] Define `GlobalMaintenanceLease` with operation ID, owner, host identity, drain deadline, affected resource counts and status. New WSL task/terminal/LSP/MCP work must reject or queue while draining through the task router and each managed resource entry point.
- [ ] Drain existing resources using their durable/session ownership APIs. A drain deadline with any non-stoppable resource transitions operation to failed and never calls shutdown.
- [ ] Keep the host adapter behind one method such as `executeShutdown(maintenanceLease, idempotencyKey)`. It must be called by the control-plane coordinator only after the composite owner approval and successful drain; no model/tool receives a shutdown command.
- [ ] Use bounded process execution, host identity and output digest. Do not log raw host output that can contain secrets.
- [ ] Test unknown key, invalid range, duplicate operation, active terminal/LSP/MCP resource, drain timeout, successful adapter invocation and adapter failure using a fake adapter. Do not invoke real `wsl --shutdown` in unit tests.
- [ ] Run:

```powershell
mvn -f backend/pom.xml '-Dtest=WslConfigValidatorTest,GlobalMaintenanceLeaseServiceTest,EnvironmentDrainCoordinatorTest,AgentEnvironmentOperationServiceTest,SandboxWorkerRouterTest,LspSessionManagerWorkerTest,McpClientWorkerTest' test
```

Expected result: automatic shutdown is possible only through a durable approved host operation after a complete drain.

## Phase 5: Windows Native Sandbox Gate

### Task 5.1: Implement the Native Windows Helper Boundary

**Files:**
- Create: `sandbox/windows-helper/CMakeLists.txt`
- Create: `sandbox/windows-helper/contract.json`
- Create: `sandbox/windows-helper/src/main.cpp`
- Create: `sandbox/windows-helper/src/protocol.hpp`
- Create: `sandbox/windows-helper/src/sandbox_boundary.hpp`
- Create: `sandbox/windows-helper/src/sandbox_boundary.cpp`
- Create: `sandbox/windows-helper/tests/helper_protocol_test.cpp`
- Create: `sandbox/windows-helper/README.md`

- [ ] Use a pinned C++17/MSVC toolchain and CMake project for the native boundary. If the required toolchain is unavailable, return `BLOCKED` with the exact prerequisite; do not replace the helper with PowerShell, `LocalDevelopmentWorker` or `unsafe-local`.
- [ ] Implement versioned stdin/stdout JSON handling for start, execute, terminal, terminate, health and cleanup. Requests contain workspace path, argv, bounded environment allowlist, network class, resource limits and secret lease handle; they never contain raw secrets or arbitrary shell text.
- [ ] Create the low-privilege process boundary, Windows Job Object/process-tree cleanup, workspace ACL boundary and explicitly tested network policy. Keep all control-plane output bounded and secret-free.
- [ ] Add native fixtures for workspace write, outside-workspace write, child-process escape, network class, secret cleanup, timeout and helper crash. Tests must not require an existing user's workspace or credentials.
- [ ] Run:

```powershell
cmake -S sandbox/windows-helper -B sandbox/windows-helper/build -G "Visual Studio 17 2022"
cmake --build sandbox/windows-helper/build --config Release
ctest --test-dir sandbox/windows-helper/build -C Release --output-on-failure
```

Expected result: a versioned native helper binary and isolated contract/security tests exist, or the task returns `BLOCKED` without enabling `integrated-windows`.

### Task 5.2: Define and Test the Java Helper Contract

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/worker/WindowsSandboxWorker.java`
- Create: `backend/src/main/java/com/labex/labexagent/worker/WindowsSandboxHelperClient.java`
- Create: `backend/src/test/java/com/labex/labexagent/worker/WindowsSandboxHelperClientTest.java`
- Modify: `sandbox/windows-helper/README.md`
- Modify: `sandbox/windows-helper/contract.json`
- Modify: `backend/src/main/java/com/labex/labexagent/worker/SandboxWorkerConfiguration.java`
- Modify: `backend/src/main/java/com/labex/labexagent/worker/SandboxWorkerRouter.java`

- [ ] Verify the Java contract against the native helper version and fixtures from Task 5.1. Keep the helper separate from the Java control plane.
- [ ] Implement client timeout, protocol validation, helper version check, kill/cleanup and typed `WORKER_UNAVAILABLE` failure for an explicitly requested integrated profile. Do not automatically switch that request to strict or route it to `LocalDevelopmentWorker`; a separately created strict spec may select the strict delegate.
- [ ] Add contract fixtures for workspace write, outside-workspace write, child-process escape, network class, secret cleanup, timeout and helper crash. Use a fake helper in Java unit tests.
- [ ] Run:

```powershell
mvn -f backend/pom.xml '-Dtest=WindowsSandboxHelperClientTest,AgentRuntimeProfileTest,SandboxWorkerContractTest' test
```

Expected result: `integrated-windows` remains disabled unless the native helper contract and security evidence exist. An unavailable integrated request fails explicitly; no implicit strict/local fallback occurs.

### Task 5.3: Perform Live Windows Helper Acceptance

**Task type:** `LIVE_ACCEPTANCE`

**Files:**
- Modify: `sandbox/windows-helper/README.md`
- Modify: `scripts/acceptance/agent-runtime.ps1`
- Create: `scripts/acceptance/windows-sandbox-runtime.ps1`

- [ ] Build the helper with a pinned toolchain and record binary hash, version, PID, parent PID and startup time.
- [ ] Run a fresh isolated project through Maven/npm/Vite/Spring Boot probes and confirm browser-accessible localhost where allowed.
- [ ] Attempt writes outside workspace, metadata access, LAN access, child-process persistence and secret sentinel extraction; record deny/approval outcomes.
- [ ] Stop the helper and prove process tree, temporary secret lease and runtime files are cleaned.
- [ ] Do not mark the profile production-ready from unit tests; attach live evidence and keep `integrated-windows` disabled until all checks pass. An unavailable integrated request remains an explicit failure.
- [ ] Run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/acceptance/windows-sandbox-runtime.ps1
```

Expected result: a separate coordinator-owned acceptance report distinguishes source tests, native helper tests and live Windows evidence. Ordinary implementation Agents do not run this task.

## Phase 6: Frontend Project Configuration Center

### Task 6.1: Add Centralized API and Pure Projection State

**Files:**
- Modify: `frontend/src/api/index.js`
- Create: `frontend/src/composables/useProjectAgentConfig.js`
- Create: `frontend/src/composables/agentProjectConfigProjection.js`
- Create: `frontend/src/composables/agentProjectConfigProjection.test.mjs`

- [ ] Add `projectAgentConfigApi` methods for `get`, `listProposals`, `getProposal`, `createProposal`, `decideProposal`, `submitSecretInput`, `getAudit` and `getEnvironment` using the existing `request` wrapper.
- [ ] Generate decision idempotency keys client-side only as retry keys; the server remains authoritative for actor/project/revision.
- [ ] Define pure projection state keyed by `projectId + revision + proposalId + operationId`. It must represent loading, validation error, stale conflict, pending, applied, rejected, expired and environment maintenance without inferring state from connection lifecycle.
- [ ] Test duplicate API response, stale revision, proposal status transition, secret submission response without value, environment drain status and project switching isolation.
- [ ] Run:

```powershell
node --test frontend/src/composables/agentProjectConfigProjection.test.mjs
```

Expected result: the new pure projection tests pass and no Pinia store is added.

### Task 6.2: Build Modular Config Components

**Files:**
- Create: `frontend/src/components/cloud/agent-config/AgentConfigPanel.vue`
- Create: `frontend/src/components/cloud/agent-config/AgentConfigEditor.vue`
- Create: `frontend/src/components/cloud/agent-config/ConfigProposalCard.vue`
- Create: `frontend/src/components/cloud/agent-config/ConfigDiffViewer.vue`
- Create: `frontend/src/components/cloud/agent-config/TrustRuntimeCard.vue`
- Create: `frontend/src/components/cloud/agent-config/SecureSecretInputCard.vue`
- Create: `frontend/src/components/cloud/agent-config/EnvironmentOperationStatus.vue`
- Create: `frontend/src/components/cloud/agent-config/agentConfigComponents.test.mjs`
- Modify: `frontend/src/views/CloudWorkspace.vue` only at the existing project settings/model/extensions composition entry point.

- [ ] Keep `AgentConfigPanel` orchestration-only: load composable state, pass props, handle emitted save/decision/secret/runtime events. It must not parse JSON, call raw Axios, or own task event reducers.
- [ ] Render editable non-secret project config with explicit validation errors and a complete proposal diff before submission. `ConfigDiffViewer` consumes only the redacted unified diff returned by the proposal API and may reuse `DiffViewer` line parsing behavior; it does not call change-set apply/reject/undo endpoints or become a second change-set authority.
- [ ] Render trust/runtime profile as a user decision; display the explicit strict option and integrated profile risks without implying automatic fallback or allowing project config to self-elevate.
- [ ] Render secure secret input as a write-only field. On success clear the input and show only configured/alias status.
- [ ] Render environment operation status including waiting approval, draining, executing, health check, failed and recovered states. Never show shutdown as completed before durable health check.
- [ ] Keep all controls keyboard-accessible and labels associated with fields. Do not put the whole configuration view inside nested decorative cards.
- [ ] Add component source-contract tests for API ownership, secret clearing, proposal decision disabling and project switch reset.
- [ ] Run:

```powershell
npm --prefix frontend test
npm --prefix frontend run build
```

Expected result: all existing frontend tests and build pass, and `CloudWorkspace.vue` remains an orchestrator rather than a new business-logic container.

### Task 6.3: Project Durable Event and Interaction Projection

**Files:**
- Modify: `frontend/src/composables/agentHistoryReducer.js`
- Modify: `frontend/src/composables/agentRunPartState.js`
- Modify: `frontend/src/composables/agentInteractionProjection.js`
- Modify: `frontend/src/composables/useAgentEventTimeline.js`
- Modify: `frontend/src/composables/useAgentTaskRuntime.js`
- Modify: `frontend/src/components/cloud/ToolCallCard.vue`
- Create: `frontend/src/composables/agentConfigEventProjection.test.mjs`
- Modify: `frontend/src/views/agentStreamIntegration.test.mjs`
- Modify: `frontend/src/views/agentRefreshRecovery.test.mjs`

- [ ] Add projection cases for `CONFIG_PROPOSAL_CREATED`, `CONFIG_PROPOSAL_DECIDED`, `CONFIG_REVISION_APPLIED`, `CONFIG_PROPOSAL_FAILED`, `ENVIRONMENT_OPERATION_STATUS` and `RUN_MODE_CHANGED` using IDs/digests only.
- [ ] Correlate a blocking proposal with `taskId`, `executionEpoch`, `interactionId`, `toolCallId` and `proposalId`. Do not attach an approval card to the last tool call by position.
- [ ] Ensure initial HTTP history, SSE replay, refresh recovery and duplicate cursor all enter the same reducer and preserve the proposal's terminal state.
- [ ] Test new conversation isolation, refresh while draining, SSE disconnect before decision, duplicate event, rejected proposal and resumed epoch.
- [ ] Run:

```powershell
npm --prefix frontend test
npm --prefix frontend run build
```

Expected result: UI state remains correct with SSE disconnected and after a page refresh.

## Phase 7: Compatibility Exit and Live End-to-End Acceptance

### Task 7.1: Add Compatibility Gates and Documentation

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/java/com/labex/config/AdditiveSchemaMigrator.java`
- Modify: `backend/src/main/java/com/labex/labexagent/migration/AgentLegacyMigrationReadinessService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/migration/AgentLegacyMigrationGateService.java`
- Create: `backend/src/main/java/com/labex/labexagent/projectconfig/AgentProjectConfigMigrationReadinessContributor.java`
- Create: `backend/src/main/java/com/labex/labexagent/projectconfig/AgentProjectConfigFeatureGate.java`
- Modify: `backend/src/main/java/com/labex/labexagent/projectconfig/AgentProjectConfigReader.java`
- Modify: `backend/src/main/java/com/labex/labexagent/projectconfig/AgentProjectConfigProposalService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/environment/AgentEnvironmentOperationService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/worker/SandboxWorkerRouter.java`
- Modify: `backend/src/main/java/com/labex/labexagent/service/AgentTaskService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/controller/AgentProjectConfigController.java`
- Create: `backend/src/test/java/com/labex/labexagent/projectconfig/AgentProjectConfigFeatureGateTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/migration/AgentLegacyMigrationReadinessServiceTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/migration/AgentLegacyMigrationGateServiceTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/projectconfig/AgentProjectConfigReaderTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/projectconfig/AgentProjectConfigProposalServiceTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/environment/AgentEnvironmentOperationServiceTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/worker/SandboxWorkerRouterTest.java`
- Modify: `README.md`

- [ ] Add explicit feature flags for project config read, proposal apply, integrated Worker and host maintenance. Default production to fail-closed until each gate is enabled and verified.
- [ ] Extend the existing `AgentLegacyMigrationReadinessService`/`AgentLegacyMigrationGateService` authority with a project-config readiness contributor. Track unsnapshotted tasks, legacy model fallback reads, plaintext `.labex/runtime.env` writes, in-memory permission approvals and old resource reads with durable migration readiness counters; do not create a second readiness owner.
- [ ] Enforce each flag at the real boundary: reader/config API, proposal apply service, `SandboxWorkerRouter`, environment operation service and task creation/resume. A flag must not exist only in `application.yml` or frontend UI.
- [ ] Define removal conditions: no active unsnapshotted tasks, zero new plaintext writes, zero fallback reads for the configured observation window, and a successful production-profile verification command.
- [ ] Update README with project config layout, secret alias behavior, profile selection, host maintenance impact and exact live verification commands. Do not document `unsafe-local` as a normal safe profile.
- [ ] Run:

```powershell
mvn -f backend/pom.xml '-Dtest=AgentRuntimeConvergenceContractTest,AgentRunSchemaTest,AgentProjectConfigFeatureGateTest,AgentLegacyMigrationReadinessServiceTest,AgentLegacyMigrationGateServiceTest' test
npm --prefix frontend test
npm --prefix frontend run build
```

Expected result: compatibility behavior is observable and has a concrete exit condition.

### Task 7.2: Run Full Durable and Browser Acceptance

**Task type:** `LIVE_ACCEPTANCE`

**Files:**
- Modify: `scripts/acceptance/agent-runtime.ps1`
- Modify: `scripts/acceptance/browser-runtime.ps1`
- Modify: `frontend/scripts/acceptance/agent-browser.mjs`
- Create: `scripts/acceptance/project-agent-config-runtime.ps1`
- Create: `frontend/scripts/acceptance/project-agent-config-browser.mjs`

- [ ] Create an isolated test user/project and record project ID, task ID, conversation ID, session ID, execution epoch, event cursor, JVM PID/start time/classpath and browser URL. Do not use an existing user's project or secrets.
- [ ] Extend `browser-runtime.ps1` with a relative `-BrowserScript` scenario parameter that defaults to the existing browser smoke script, validates the selected script beneath `frontend/scripts/acceptance/`, and always owns startup/cleanup of H2, JVM, Vite and browser processes.
- [ ] Verify first-open trust decision, explicit strict selection, integrated WSL toolchain detection, project config proposal, diff, approval, revision/digest, new epoch snapshot and model/MCP/Skill/tool policy.
- [ ] Verify secure secret input never appears in transcript, event replay, browser network payload display, logs or Worker output.
- [ ] Verify public direct-resume is rejected; approved interaction resumes once; duplicate decision/cursor is idempotent; stale epoch cannot write.
- [ ] Verify Maven/npm/Vite/Spring Boot real commands, public dependency access, localhost access, workspace-only writes, LAN/metadata denial and verification evidence.
- [ ] Verify automatic WSL maintenance: composite proposal approval, global drain of task/terminal/LSP/MCP, failed drain cancels shutdown, successful shutdown health-checks, and affected tasks resume on new epochs. Only run the real shutdown in an explicitly approved local acceptance environment after recording that it will interrupt all WSL workloads.
- [ ] Keep the default runtime command non-destructive with WSL shutdown disabled. The coordinator may run the separately approved shutdown scenario only with an explicit `-AllowWslShutdown` switch after recording the affected workload scope; ordinary implementation Agents must never run that command.
- [ ] Run:

```powershell
mvn -f backend/pom.xml test
npm --prefix frontend test
npm --prefix frontend run build
powershell -ExecutionPolicy Bypass -File scripts/acceptance/project-agent-config-runtime.ps1
powershell -ExecutionPolicy Bypass -File scripts/acceptance/browser-runtime.ps1 -BrowserScript project-agent-config-browser.mjs
```

Expected result: source tests/build pass with known skips clearly separated, then the wrapper starts and cleans the isolated JVM/H2/Vite/browser processes and the selected browser scenario produces evidence for each runtime/security assertion. A live failure is reported as a failed gate, not hidden by excluding the test.

The real host-maintenance command is a separate approval-gated action and is never part of the default command above:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/acceptance/project-agent-config-runtime.ps1 -AllowWslShutdown
```

Run that command only in an explicitly approved local acceptance environment after recording that it will interrupt all WSL workloads.
