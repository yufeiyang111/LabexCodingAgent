# Iteration 08 - Align context budget with the durable Provider projection

## Objective

Make context admission, token budgeting, context status events, and the final Provider request use the same durable transcript projection. Before this change, the loop could estimate the in-memory `msgs` list while the Provider read `AgentTranscriptProjectionService.loadProviderMessages(taskId)`. After compaction, replay, or a resume, those two inputs could diverge and produce an incorrect admission decision or misleading UI status.

## Plan

1. Locate every context-budget and Provider-input call in `AgentLoopEngine`.
2. Add one narrow helper that obtains the durable Provider projection for a task.
3. Route pre-management admission, post-management admission, context status, proactive context management estimates, and the Provider request through that projection.
4. Add one regression test for the helper and update the existing Provider source-contract test to assert the invariant rather than one exact call-site spelling.
5. Run focused tests, the complete backend suite, frontend tests/build, and a real browser acceptance with durable compaction and cross-JVM approval recovery.
6. Record evidence and commit only this iteration's intended files.

## Changes

### Runtime alignment

File: `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`

Added `providerMessagesForBudget(taskId, inMemoryMessages)`. Production task execution reads the durable Provider projection from `AgentTranscriptProjectionService`; the in-memory list remains a compatibility fallback only when no task or projector exists, which is needed by isolated non-Provider unit construction.

The following paths now use the same `providerMessages` value:

- pre-context-management static admission;
- post-context-management admission;
- context status/token-budget event generation;
- the `AgentModelTurnExecutor.ModelTurnRequest` sent to the Provider;
- proactive context-management token estimation and post-prune estimation.

The old compatibility transcript path was not removed in this iteration because the convergence plan requires shadow comparison and live failure-injection evidence before deletion.

### Regression coverage

Files:

- `backend/src/test/java/com/labex/labexagent/runtime/AgentLoopEngineContextBudgetTest.java`
- `backend/src/test/java/com/labex/labexagent/runtime/AgentLoopEngineStreamingContractTest.java`

Added one focused test proving that a task uses the durable projection for budget inputs and that the no-task test-only fallback remains local. The existing source contract test now checks the durable projection invariant and the final Provider request shape instead of depending on one exact direct invocation string.

## Verification

### Automated verification

- `cd D:/LabexAgent/backend && mvn -q "-Dtest=AgentLoopEngineContextBudgetTest,AgentTranscriptProjectionServiceTest,AgentCompactionServiceTest" test`: passed.
- `cd D:/LabexAgent/backend && mvn -q "-Dtest=AgentLoopEngineStreamingContractTest,AgentLoopEngineContextBudgetTest" test`: passed.
- `cd D:/LabexAgent/backend && mvn -q test`: passed. The existing suite reported 791 tests with zero failures and zero errors; 8 were skipped by the existing suite configuration.
- `cd D:/LabexAgent/frontend && npm test`: passed, 156/156.
- `cd D:/LabexAgent/frontend && npm run build`: passed, including the existing chunk-budget check.
- `git diff --check`: passed; Git only reported the repository's existing LF-to-CRLF normalization warnings.

### Real browser and runtime verification

Acceptance command:

`cd D:/LabexAgent/frontend && npm.cmd run acceptance:browser`

Backend acceptance JVM:

- port: `18081`
- PID: `26700`
- local host start timestamp: `2026-07-31T06:37:01+08:00`
- compiled `AgentLoopEngine.class` timestamp: `2026-07-31 06:28:16`
- UI: `http://127.0.0.1:13001`
- browser viewport: 1440x900 with `mobile: false`

Acceptance result:

- runId: `5766503a09af45a187a59e9e9215aa7e`
- desktop layout: true
- new/old conversation isolation: true
- refresh replay deduplication: true
- question reply component: true
- permission approval recovery after refresh: true
- durable Provider messages: 3
- durable Provider parts: 1
- durable compaction: true
- compaction epoch: 1
- compaction Provider messages: 7
- cross-JVM interaction recovery: true
- `restartProjectionVerified`: false because this run exercised the interaction handoff mode, not the separate full projection handoff mode
- static context blocker card: true
- completion evidence card: true
- unverified completion blocked: true
- browser console errors: 0
- network errors: 0

The acceptance run is real browser execution against the rebuilt Spring Boot process and the Vite frontend. It proves the existing durable compaction and approval-recovery flow remains healthy after this alignment change. It does not yet prove tool-timeout, Provider-stream-break, compaction-failure, or lease-takeover fault injection.

## Remaining risks and next iteration

1. The old in-memory transcript compatibility path remains and must be shadow-compared, monitored, and removed only after the convergence plan's deletion conditions are met.
2. The current acceptance harness still needs explicit fault-injection scenarios for tool timeout, Provider stream interruption, compaction failure, missing compaction window, duplicate scheduler execution, and lease takeover.
3. The current change aligns budgeting and the request projection; it does not by itself redesign the full context-selection algorithm.
4. The acceptance backend emitted expected failure-state events for scripted negative scenarios; the browser script still exited successfully and reported zero browser/network errors.
5. No remote push was performed.

## Commit scope

Only the following files belong to this iteration:

- `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- `backend/src/test/java/com/labex/labexagent/runtime/AgentLoopEngineContextBudgetTest.java`
- `backend/src/test/java/com/labex/labexagent/runtime/AgentLoopEngineStreamingContractTest.java`
- this iteration document

Pre-existing modified and untracked files were intentionally left untouched.
