# Iteration 38: Tool Part authority and legacy artifact retirement

- Date: 2026-08-02
- Scope: durable tool-call state, task snapshot projection, approval completion, frontend refresh recovery
- Parent plan: `docs/superpowers/plans/2026-07-29-agent-runtime-state-convergence.md`

## Problem evidence

The runtime already writes tool lifecycle state to `AgentRunPart`, but two legacy reads remain:

1. `AgentTaskEventController.publicTask` builds the compatibility `toolCalls` field through `AgentToolCallJournalService.latestForTask`, which reads `tool_call_state` artifacts.
2. `AgentToolCallJournalService.recordExisting` reads the artifact history before it updates an existing Tool Part.
3. `useAgentTaskRuntime` applies durable `parts` and then applies `task.toolCalls`, so a stale compatibility snapshot can overwrite the newer Part state after refresh.

This violates the convergence invariant that `AgentRunPart` is the unique durable tool-state authority and that compatibility DTOs are read-only projections.

## Design

### Authority

- `AgentRunPart(partType=tool, toolCallId=...)` is the only readable tool execution lifecycle fact.
- `AgentRunPartService.publicToolCalls(taskId)` provides the temporary legacy DTO by projecting durable Tool Parts.
- `AgentToolCallJournalService` writes Tool Parts and durable events only. It no longer reads or writes `tool_call_state` artifacts.
- Existing artifact rows remain untouched in the database, but no runtime path consumes them.

### Frontend projection

- Refresh recovery continues to consume `runMessages` and `parts` through `applyRunMessageSnapshot` and `applyRunPartSnapshot`.
- The compatibility `task.toolCalls` field is ignored by the current frontend, preventing a stale derived field from overriding a durable Part.
- `pendingInteraction` and `commandApproval` remain separate durable projections attached after Part replay.

### Compatibility and exit condition

- The HTTP `toolCalls` key remains for older clients, but its content is derived from `AgentRunPart`.
- Removal of the DTO key itself is out of scope until external-client compatibility is explicitly ended.
- The `tool_call_state` artifact type has no new writes after this iteration and can be migrated or deleted in a later database cleanup without affecting runtime recovery.

## Regression tests

1. A task snapshot returns `toolCalls` projected from Tool Parts even when no journal/artifact source exists.
2. The compatibility projection includes only `partType=tool` rows and preserves `toolCallId`, tool name, arguments, status, iteration, detail, and Part identity.
3. Completing an approved command updates the existing Tool Part directly and emits one idempotent `TOOL_CALL_STATE` event.
4. Journal source contains no `AgentRunArtifactService` or `tool_call_state` dependency.
5. Frontend refresh recovery prefers a durable Part when a stale compatibility `toolCalls` entry conflicts with it.

## Verification plan

- Focused backend tests:
  - `AgentRunPartServiceTest`
  - `AgentToolCallJournalServiceTest`
  - `AgentTaskEventControllerTest`
  - `CommandApprovalOrchestratorTest`
- Focused frontend tests:
  - `agentRunPartState.test.mjs`
  - `useAgentTaskRuntime.test.mjs`
- Full backend `mvn test` and package build.
- Full frontend tests and production build.
- Real runtime acceptance and browser refresh/approval acceptance.

## Implementation record

### Backend

- Added `AgentRunPartService.publicToolCalls(taskId)` as the compatibility DTO projector.
- Kept the DTO shape (`toolCallId`, tool, arguments, status, iteration, detail, Part identity) while sourcing it only from `partType=tool` rows.
- Removed `AgentRunArtifactService`, artifact history parsing, `latestForTask`, and `tool_call_state` writes from `AgentToolCallJournalService`.
- Changed delayed approval completion to resolve the existing Tool Part first and publish the event from the same Part projection, preserving original arguments.
- Changed `AgentTaskEventController` so `toolCalls` is derived from `AgentRunPartService`.

### Frontend

- Removed the `task.toolCalls` replay path from `useAgentTaskRuntime`.
- Refresh recovery now replays `runMessages` and `parts`, then attaches durable interaction and command-approval projections.
- Added a conflict regression where a stale `toolCalls=waiting_approval` snapshot cannot override a durable `Part=environment_blocked` state.

### System acceptance

- Extended `scripts/acceptance/agent-runtime.ps1` with `toolPartAuthority`.
- After approved and rejected command restart flows, the script compares every compatibility tool call against its durable Tool Part by call ID, status, tool name, and result.

## TDD evidence

### RED

Backend focused compilation failed as expected because `publicToolCalls` and the Part-only journal constructor did not exist.

Frontend focused recovery failed as expected:

- expected durable visual status: `warning`
- actual stale compatibility status: `waiting_approval`

This proved that the old DTO could overwrite the durable Part after refresh.

### GREEN

Focused backend:

```powershell
cd D:\LabexAgent\backend
mvn -q -DforkCount=0 '-Dtest=AgentRunPartServiceTest,AgentToolCallJournalServiceTest,AgentTaskEventControllerTest,CommandApprovalOrchestratorTest' test
```

Result: 18 tests passed, 0 failures, 0 errors.

Focused frontend:

```powershell
cd D:\LabexAgent\frontend
node --test src/composables/agentRunPartState.test.mjs src/composables/useAgentTaskRuntime.test.mjs
```

Result: 17 tests passed, 0 failures.

## Full verification

### Backend

```powershell
cd D:\LabexAgent\backend
mvn test
mvn clean package -DskipTests
```

Results:

- 849 tests executed.
- 0 failures, 0 errors, 8 skipped.
- Production JAR built at `backend/target/labex-agent-backend-1.0.0.jar`.

### Frontend

```powershell
cd D:\LabexAgent\frontend
npm test
npm run test:acceptance:unit
npm run build
```

Results:

- 180 frontend tests passed.
- 10 acceptance infrastructure tests passed.
- Production build passed.
- `CloudWorkspace` 1,458,026 bytes <= 1,500,000.
- `index` 1,259,534 bytes <= 1,300,000.
- `TerminalPanel` 380,367 bytes <= 400,000.

## Real runtime acceptance

Command:

```powershell
cd D:\LabexAgent
.\scripts\acceptance\agent-runtime.ps1 -BackendPort 18176 -TimeoutSeconds 180
```

Evidence:

- run ID: `a22f975419684cbbb9b4359637456e43`
- acceptance JVM PID: `19844`
- normal-profile isolation restart PID: `536`
- URL: `http://127.0.0.1:18176/api`
- `questionRestart=true`
- `permissionRestart=true`
- `commandApproveRestart=true`
- `commandRejectRestart=true`
- `runMessagePartProjection=true`
- `toolPartAuthority=true`
- `manualCompaction=true`
- `cleanup=true`

The first version of the added PowerShell assertion failed at parse time because Windows PowerShell 5.1 did not accept `-or` at the beginning of continuation lines. No backend process was started by that failed attempt. The condition was rewritten into explicit booleans, the script parser passed, and the complete runtime acceptance then passed.

## Real browser acceptance

Command:

```powershell
cd D:\LabexAgent
.\scripts\acceptance\browser-runtime.ps1 -BackendPort 18175 -FrontendPort 13054 -CdpPort 19280 -TimeoutSeconds 210
```

Evidence:

- browser run ID: `89ddaa7651014032aac8ddc68ba3d247`
- browser-runtime log ID: `61c2fa99982f4424994bb264cc685d3e`
- backend JVM PID: `46440`
- backend URL: `http://127.0.0.1:18175/api`
- frontend URL: `http://127.0.0.1:13054/`
- viewport: 1440 x 900
- `conversationIsolation=true`
- `refreshReplayDeduplicated=true`
- `permissionApprovalRefreshRecovery=true`
- `multiToolPermissionBatchProtocolComplete=true`
- `durableProviderMessages=3`
- `durableProviderParts=1`
- `durableCompaction=true`
- `internalReasoningProtocolHidden=true`
- `consoleErrors=0`
- `networkErrors=0`

The browser run did not enable the optional process-restart handoff (`restartProjectionVerified=false`, `restartInteractionVerified=false`). Those restart cases were verified by the dedicated runtime acceptance above.

## Final authority audit

- Production source has no `tool_call_state` read or write path; the only remaining occurrence is explanatory documentation.
- `AgentTaskEventController` no longer depends on `AgentToolCallJournalService`.
- `useAgentTaskRuntime` no longer consumes `task.toolCalls`.
- Existing database artifact rows are preserved but are no longer read or appended.
- The compatibility HTTP field remains a read-only projection from durable Tool Parts.

## Remaining risk and follow-up

- The compatibility `toolCalls` HTTP key still duplicates data already available under `parts`; it remains only for older clients.
- A later API-version cleanup may remove that key after external clients are audited.
- Historical `tool_call_state` rows may be deleted by a separate additive cleanup migration only after retention requirements are decided.
