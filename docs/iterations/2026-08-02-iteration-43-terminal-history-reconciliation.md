# Iteration 43: Reconcile terminal history after approval refresh

- Date: 2026-08-02
- Status: completed
- Goal: close the refresh race where an interaction-resumed task becomes terminal between the conversation-history snapshot and the active-task lookup, leaving the final reply absent until another manual refresh.
- Governing plan: `docs/superpowers/plans/2026-07-29-agent-runtime-state-convergence.md`.

## Failure evidence

The real browser acceptance run failed after a permission approval:

- browser log root: `C:\Users\35475\AppData\Local\Temp\labex-agent-browser-runtime-598dea58829944cf9d827d7f90bd2438`
- task: `1728`
- the first history snapshot already contained `COMPLETION_EVIDENCE`, but not the later `FINAL`;
- the durable outbox subsequently emitted sequence `40 FINAL`, `41/42 RUN_STATE_COMPLETED`, and `43 DONE`;
- the refreshed page retained the approval/tool timeline and completion evidence, but never rendered the final reply.

The ordering was:

```text
browser refresh
  -> load conversation message snapshot (up to COMPLETION_EVIDENCE)
  -> query active-task
  -> task has already become terminal, so active-task returns no recoverable task
  -> frontend does not subscribe
  -> FINAL is committed after the first snapshot
  -> UI remains permanently one event behind
```

This is a snapshot/subscription handoff race. Delaying the test refresh would only hide it.

## Design

`useAgentTaskRuntime` now owns terminal history reconciliation:

1. active-task recovery still validates the current conversation and recovery generation;
2. when no active task remains, it checks whether the latest rendered task message lacks a durable terminal run state;
3. only for that incomplete projection, it invokes the injected conversation-history reloader;
4. after the second durable read, it reapplies task timing metadata;
5. stale conversation/session recovery remains rejected by the existing generation guards.

`CloudWorkspace` injects `loadConversationMessagesState` rather than duplicating API/reducer logic. The frontend still derives the timeline from durable conversation events; no local final message is invented.

## Regression-first record

Added `terminal recovery reconciles conversation history after the initial snapshot` to `useAgentTaskRuntime.test.mjs`.

RED result before implementation:

```text
node --test src/composables/useAgentTaskRuntime.test.mjs
15 tests, 1 failure
expected reconciliation calls: [conversation-a]
actual reconciliation calls: []
```

GREEN result after implementation:

```text
node --test src/composables/useAgentTaskRuntime.test.mjs
15 tests, 15 passed
```

## Real-system verification

Command:

```powershell
.\scripts\acceptance\browser-runtime.ps1 `
  -BackendPort 18080 -FrontendPort 13000 -CdpPort 19222 -TimeoutSeconds 240
```

Result:

- run ID: `8dd48c7c6e134bc2a00a7a0ed680139d`;
- permission approval refresh recovery: passed;
- multi-tool permission batch same-task completion: passed;
- manual compaction/fork refresh scenario: passed;
- internal reasoning protocol hidden: passed;
- browser console errors: `0`;
- browser network errors: `0`.

## Files

- `frontend/src/composables/useAgentTaskRuntime.js`
- `frontend/src/composables/useAgentTaskRuntime.test.mjs`
- `frontend/src/views/CloudWorkspace.vue`
- `docs/iterations/2026-08-02-iteration-43-terminal-history-reconciliation.md`

## Remaining risk

This reconciliation intentionally performs a second bounded history read only when the rendered latest task message has no terminal run state and the backend reports no active task. It does not replace SSE replay or make connection state authoritative. The broader restart path remains covered by the repository browser restart acceptance and will be rerun while completing Iteration 41.
