# Iteration 07 - Cross-JVM interaction recovery and idempotent transition replay

## Objective

Verify that a waiting approval interaction can be recovered from persisted task, interaction, event, and transcript data after a backend JVM restart and browser refresh. Also fix the stale retry failure:

`Agent run idempotency key was already used while the task is in a different state`

## Plan

1. Pause the browser acceptance flow after the task enters `waiting_approval`.
2. Stop the old backend JVM and start a new acceptance-profile JVM.
3. Read the same task and interaction from the API and confirm that they remain in the persisted waiting state.
4. Refresh the browser and complete the approval recovery flow.
5. Add a regression test proving that a matching historical idempotency event is a side-effect-free replay even after the task advances. A different event type or target state must still be rejected.
6. Run backend tests, frontend static checks, and the real browser handoff acceptance.

## Changes

### Real acceptance handoff

File: `frontend/scripts/acceptance/agent-browser.mjs`

Added `ACCEPTANCE_INTERACTION_RESTART_HANDOFF_DIR` support:

- Writes `ready.json` after the waiting interaction is created.
- Waits for `continue.signal` after the external backend restart.
- Reads the same task and verifies `waiting_approval` and the same interaction in `waiting` status.
- Reloads the browser and verifies that the approval card is reconstructed from the durable projection.
- Writes `restartInteractionVerified` into the acceptance result.

### Lifecycle idempotency

File: `backend/src/main/java/com/labex/labexagent/run/AgentRunLifecycleService.java`

When a key already maps to an event with the same target state and event type:

- `transitionIfCurrent` still returns false when the current state no longer matches the expected state, so a stale caller cannot claim ownership.
- `transition` returns the historical `TransitionResult` without updating the task or inserting another event/outbox row.
- A different target state or event type is still rejected by `validateIdempotentReplay`.
- A stale request cannot resurrect a completed, failed, or cancelled task.

Regression test: `backend/src/test/java/com/labex/labexagent/run/AgentRunLifecycleServiceTest.java`.

## Real cross-JVM acceptance evidence

Handoff directory:

`D:/LabexAgent/.codex-tmp/iteration07-interaction-restart-20260731-052151`

The `20260731` directory suffix is the local runtime host timestamp. It does not change the iteration record date of 2026-07-30.

Acceptance entities:

- runId: `2e851d4898944a6cadeba7191bfb108a`
- projectId: `139`
- taskId: `782`
- conversationId: `8e9cd67e-8379-4c39-91da-77be81be8393`
- interactionId: `47745943-1bb5-4747-bb57-5ea6c4e8e2bf`

Result:

- `restartInteractionVerified: true`
- `permissionApprovalRefreshRecovery: true`
- `durableProviderMessages: 3`
- `durableProviderParts: 1`
- `durableCompaction: true`
- `consoleErrors: 0`
- `networkErrors: 0`

The first restart orchestration attempt failed because PowerShell/Maven argument quoting split the profile argument into an invalid lifecycle phase. The backend was restored. The second run used a temporary `.ps1` launcher and HTTP readiness checks and passed. This was an orchestration failure, not a business-code failure.

## Verification completed

- `mvn -q "-Dtest=AgentRunLifecycleServiceTest" test`: passed.
- `mvn -q test`: passed.
- `npm test`: 156/156 passed.
- `npm run build`: passed, including the chunk budget check.
- `node --check frontend/scripts/acceptance/agent-browser.mjs`: passed.
- Real cross-JVM waiting-interaction handoff after rebuilding and restarting the backend: passed.

## Not yet completed

- The full backend test suite and a complete browser regression after the lifecycle change still need to run.
- The old in-memory transcript compatibility path still needs shadow comparison, write monitoring, and deletion-condition verification.
- Context overflow, tool timeout, compaction failure, and lease takeover still need fault-injection acceptance.
- This iteration creates a local commit only and does not push remotely.
