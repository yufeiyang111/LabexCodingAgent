# Iteration 06 - Provider durable transcript boundary

## Objective

Make the Provider request path read from the persisted `AgentRunMessage` / `AgentRunPart` projection. The normal path must not use an in-memory message list as a long-lived source of truth. Frontend interaction recovery must preserve the original `interactionId` / `requestId`, and completed tasks must not leave stale waiting interactions visible.

## Changes

- `backend/src/main/java/com/labex/labexagent/runtime/AgentTranscriptProjectionService.java`
  - Added `loadProviderMessages(taskId)`.
  - Missing durable projection fails closed instead of silently rebuilding Provider input from memory.
- `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
  - Model requests now use the durable transcript projector.
  - The durable-projector path no longer uses the in-memory `TurnAwareContextPruner` compatibility read path.
- `frontend/src/composables/useAgentInteraction.js`
  - Normalized `requestId` and `interactionId` recovery identity.
  - Marks a successful submission as `resuming` before the continuation arrives.
- `frontend/src/composables/useAgentTaskRuntime.js`
  - Avoids reattaching the same waiting interaction while it is resuming.
- `frontend/src/composables/useAgentEventTimeline.js`
  - Clears residual waiting interactions on `RUN_STATE_COMPLETED` / `DONE`.

## Verification

### Backend

- `mvn -q "-Dtest=AgentTranscriptProjectionServiceTest,AgentLoopEngineStreamingContractTest" test`: passed.
- `mvn -q test`: passed.

### Frontend

- `npm test`: 156/156 passed.
- `npm run build`: passed, including the chunk budget check.
- `node --check scripts/acceptance/agent-browser.mjs`: passed.

### Browser acceptance

Run ID: `2b584c4cb87942349dd6f60409e26a1b`.

Verified:

- Desktop layout.
- New and old conversation isolation.
- Replay deduplication after refresh.
- Question reply component.
- Permission approval and same-task refresh recovery.
- Durable Provider messages and parts.
- Durable compaction epoch and token change.
- Context blocker, completion evidence, and unverified-completion blocking.
- Zero browser console errors and zero network errors.

This run did not enable the JVM restart handoff, so `restartProjectionVerified` remained false. Cross-JVM waiting-interaction verification was added in Iteration 07.

## Remaining risk

1. Verify that a waiting interaction survives a backend JVM restart with the same task and interaction identity.
2. Make stale retries using an already persisted transition key idempotent after the task has advanced.
3. Remove the old in-memory transcript compatibility path only after shadow comparison and live verification.
