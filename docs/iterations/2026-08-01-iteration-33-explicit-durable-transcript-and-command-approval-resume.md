# Iteration 33: Explicit durable transcript and command-approval resume

- Date: 2026-08-01
- Branch: `codex/agent-tool-reliability`
- Previous commit: `b75a730 refactor: remove in-memory agent context transcript`
- Scope: durable Provider transcript writes, command-approval continuation after restart, and the durable frontend projection of resume states.

## Objective

Remove the remaining hidden persistence behavior from the Agent loop and make an approved command continue the original task/epoch after a backend restart. The browser must keep rendering the original approval card from durable events; approval must not look like a new chat turn.

## Failure evidence before this iteration

The initial restart acceptance evidence contained both of these failures:

```text
Agent run is already owned by another active worker
Durable SSE events require a bound agent run before they can be sent
```

The direct command-approval continuation called `AgentLoopEngine.resume(...)` while a lease written by the stopped JVM was still active. The new process could not acquire ownership, then attempted a durable SSE error before an Agent run had been bound. The task was left without a reliable handoff.

After lease dispatch was separated, the first rerun exposed a second, independent contract problem:

```text
RUN_COMMAND_APPROVAL_RESUME_QUEUED
RUN_STATE_RUNNING
COMMAND_APPROVAL_REQUIRED
TASK_PAUSED
```

The resumed Provider request said only that the approval was resolved. The acceptance provider did not receive a structured resolution marker, treated the original shell call as unapproved, and requested the same command again.

## Implementation

### Explicit Provider transcript writes

- Removed the `TranscriptMessageList` implicit-persistence collection from `AgentLoopEngine`.
- Replaced hidden `msgs.add(...)` persistence side effects with explicit `appendProviderMessage(...)` calls: durable transcript append first, then add the same defensive copy to the derived Provider request cache.
- Kept the request-time list as a cache only; it is no longer an independent durable history authority.

### Durable command-approval continuation

- Added `CommandApprovalResumeScheduler` as the only dispatcher for resolved `agent_shell` approvals.
- Before dispatch, it checks the task state and active execution lease. A foreign, unexpired lease keeps the task in `waiting_approval`; the scheduled retry claims only a legal handoff.
- `AgentTaskService.claimCommandApprovalResume(...)` makes the transition through the lifecycle service with a stable `AgentRunTransitionKey`.
- Dispatch receives the preclaimed lease, so the resumed Agent loop does not attempt a second acquisition.
- A rejected executor dispatch becomes an explicit failed task instead of a permanently recovering task.
- A pre-bind dispatch failure is reported as a transient SSE error only; it cannot write a false durable terminal event for an execution it does not own.
- The continuation is now a structured, status-aware protocol:

```text
Command approval decision: approved|rejected|expired
Resolution status: approved|rejected|expired
```

This tells both a real Provider and the acceptance provider that the one-time decision has already been resolved and that the command must not be replayed.

### Frontend durable projection

- Added reducer/timeline handling for `RUN_COMMAND_APPROVAL_RESUME_QUEUED` and `COMMAND_APPROVAL_RESUME_DEFERRED`.
- The existing command approval card transitions to `resuming` or `waiting_resume`; it retains the original `taskId` and does not create a synthetic new user/assistant turn.
- `CloudWorkspace.vue` maps the same durable events in the live SSE path and in history/replay recovery.

## Regression coverage

New/updated tests cover:

- active old lease defers command continuation;
- scheduler dispatch uses the preclaimed lease;
- approved continuation includes `Command approval decision: approved` and `Resolution status: approved`;
- failed executor dispatch becomes a durable failed task;
- scheduled retry takes over only after a stale lease expires;
- orchestrator no longer resumes the Agent loop directly;
- an unbound SSE publisher cannot be used as proof of a durable run failure;
- transcript wiring rejects reintroduction of `TranscriptMessageList`;
- history replay and live timeline render resume/deferred approval cards.

## Verification

### Backend

```powershell
cd D:\LabexAgent\backend
mvn -q -DforkCount=0 -Dtest=CommandApprovalResumeSchedulerTest test
mvn -q -DforkCount=0 test
mvn -q -DskipTests package
```

Results:

- focused scheduler regression: passed;
- full Maven suite: `221` Surefire reports, `838` tests, `0` failures, `0` errors;
- JAR packaging: passed.

### Frontend

```powershell
cd D:\LabexAgent\frontend
# run every *.test.mjs sequentially to avoid the Windows child-process EPERM issue
npm run build
```

Results:

- sequential frontend test files: `40` passed, `0` failed;
- source-encoding guard: passed;
- Vite production build and chunk-budget check: passed.

### Real restart acceptance

```powershell
D:\LabexAgent\scripts\acceptance\agent-runtime.ps1   -BackendPort 18135   -JarPath D:\LabexAgent\backend\target\labex-agent-backend-1.0.0.jar   -TimeoutSeconds 240
```

Result: passed all checks, including `commandApproveRestart`, `commandRejectRestart`, durable run message/part projection, manual compaction, context-window failure handling, completion evidence, and cleanup.

### Real browser acceptance

Two independent fresh-process runs passed after the restart handoffs:

```powershell
D:\LabexAgent\scripts\acceptance\browser-runtime.ps1   -BackendPort 18137 -FrontendPort 13025 -CdpPort 19251   -JarPath D:\LabexAgent\backend\target\labex-agent-backend-1.0.0.jar   -TimeoutSeconds 300 -RestartBackendForAcceptance

D:\LabexAgent\scripts\acceptance\browser-runtime.ps1   -BackendPort 18138 -FrontendPort 13026 -CdpPort 19252   -JarPath D:\LabexAgent\backend\target\labex-agent-backend-1.0.0.jar   -TimeoutSeconds 300 -RestartBackendForAcceptance
```

Both runs verified desktop layout, conversation isolation, refresh replay de-duplication, user-question reply, permission approval refresh recovery, multi-tool completion, durable compaction, Provider-stream interruption handling, backend restart projection, backend restart interaction recovery, static-context blocker card, completion evidence, and zero meaningful browser console/network errors.

One earlier browser run timed out while reopening the conversation menu after a restart transport transition. No production source changed between that run and the two later independent passing runs. It is recorded as an acceptance-harness/UI timing risk and must be watched in later fault-injection iterations; it is not treated as proof that the flow is permanently stable.

## Scope protection

The pre-existing modified file `backend/src/main/resources/application-acceptance.yml` and unrelated untracked plans/docs/temporary files are intentionally excluded from this iteration commit.

## Remaining convergence work

This iteration makes task recovery materially safer, but the architecture-convergence goal is still open:

1. finish deletion/audit of other legacy status, summary, and UI authority paths;
2. retain projector shadow comparison while proving no old transcript writes occur;
3. extend fault injection for provider interruption, duplicate/expired approval, compaction failure, unknown context-window, partial tool result, and scheduler duplicate delivery;
4. collect repeatable live evidence that all remaining legacy compatibility paths are read-only projections before removal.
