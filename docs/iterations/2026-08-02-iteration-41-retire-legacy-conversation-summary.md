# Iteration 41: Retire the legacy conversation summary aggregate

- Date: 2026-08-02
- Status: completed
- Goal: remove `AgentConversation.summary` from the runtime write/fork/compaction path so durable `COMPACTION_SUMMARY` messages remain the only conversation-summary fact.
- Governing plan: `docs/superpowers/plans/2026-07-29-agent-runtime-state-convergence.md`.

## Confirmed residual architecture

Iterations 29 and 30 stopped reading `AgentConversation.summary` for Provider context and memory statistics, but retained its writes as a temporary compatibility projection. The current source still has four competing behaviors:

1. every USER and selected event appends text into the aggregate column;
2. a hidden character-threshold auto-compactor rewrites that aggregate without emitting a durable compaction record;
3. manual/model compaction writes both a durable `COMPACTION_SUMMARY` message and the aggregate column;
4. conversation fork copies the aggregate even though it already copies the durable message history.

This is a second mutable fact source. It can drift from the durable transcript, cannot explain its own compaction history, and can be copied into a fork even when the selected fork cutoff excludes the corresponding durable event.

## Design

- `AgentMessage(eventType=COMPACTION_SUMMARY)` is the sole conversation-summary fact used for recovery, memory statistics, fork history, and context projection.
- `AgentConversation.summary` remains in the entity/schema only for non-destructive compatibility with existing databases. It is hidden from JSON, receives no new runtime writes, and existing values are left untouched.
- New and forked conversations do not initialize or copy the legacy aggregate.
- USER/FINAL/tool/lifecycle event persistence updates only the durable message and conversation `update_time`.
- Manual deterministic fallback still builds a bounded summary from durable messages, but persists it only through `saveCompactionSummary`.
- `compacted_at` remains an aggregate metadata projection written only after the durable compaction message is inserted.
- The hidden aggregate-length auto-compactor is deleted. Automatic compaction is owned by the explicit context/compaction runtime and its durable audit records.

## Regression-first acceptance

1. saving ordinary user and agent events does not mutate a pre-existing legacy summary;
2. saving a compaction summary inserts a first-class durable event and leaves the legacy value unchanged;
3. deterministic manual fallback produces and persists a durable checkpoint without writing the aggregate;
4. fork copies durable messages up to the cutoff but does not copy the legacy aggregate;
5. source search finds no production read/write of `AgentConversation.summary` outside the compatibility entity;
6. full backend/frontend/build verification passes;
7. real manual compaction, fork/replay, browser refresh, and backend restart acceptance pass.

## Implementation record

1. `AgentConversation.summary` remains mapped only for non-destructive database compatibility. Its accessor is hidden from JSON and marked deprecated; equality, hash code, and string rendering no longer treat it as runtime state.
2. New conversations no longer initialize the legacy aggregate, and forks no longer copy it.
3. `saveUserMessage` and `saveEvent` now insert durable `AgentMessage` rows and update only conversation time metadata.
4. `saveCompactionSummary` inserts `COMPACTION_SUMMARY` first, then updates `compacted_at`; it never writes the legacy column.
5. The character-count aggregate auto-compactor and its `persistSummary` writer were removed. Automatic context compaction remains owned by the explicit runtime compaction state and audit records.
6. Deterministic manual fallback now only builds a bounded, redacted checkpoint; persistence still flows through `saveCompactionSummary`.
7. The browser acceptance now executes manual compaction, waits for its durable task terminal state, finds the newly inserted summary message, forks at that exact message ID, verifies the child memory is rebuilt from the copied durable message, reloads the browser, and repeats the checks after a real backend restart.

## Verification record

### Focused backend regression

```text
mvn -Dtest=AgentConversationServiceCompactionTest,ManualCompactionTaskRunnerTest test
11 tests, 0 failures, 0 errors
BUILD SUCCESS
```

The conversation compaction suite itself contains 9 tests covering durable recovery, ordinary writes, model compaction, deterministic fallback, cancellation/config rejection, and fork behavior.

### Static authority audit

Production-source search found no `AgentConversation::getSummary`, `AgentConversation::setSummary`, `conversation.getSummary()`, `conversation.setSummary(...)`, `source.getSummary()`, or `child.setSummary(...)` call outside the compatibility entity.

### Full backend verification

```text
mvn test
868 tests, 0 failures, 0 errors, 8 skipped
BUILD SUCCESS
```

### Frontend verification

```text
npm test
184 tests, 184 passed

npm run build
BUILD SUCCESS
CloudWorkspace: 1,459,986 / 1,500,000 bytes
index: 1,259,534 / 1,300,000 bytes
TerminalPanel: 380,367 / 400,000 bytes
```

### Real browser verification without backend restart

Run ID: `8dd48c7c6e134bc2a00a7a0ed680139d`

- `manualCompactionForkRefreshVerified=true`
- permission/question recovery passed
- internal reasoning protocol hidden
- console errors: `0`
- network errors: `0`

### Real browser verification with two backend restarts

Run ID: `202bf6fd737549a6859fc15772e3954b`

- `manualCompactionForkRefreshVerified=true`
- `restartManualForkVerified=true`
- `restartProjectionVerified=true`
- `restartInteractionVerified=true`
- manual compaction task: `1755`
- the child conversation retained the copied durable `COMPACTION_SUMMARY` after restart
- expected restart-window transport errors: `3`, all classified only after restart verification
- final console errors: `0`
- final network errors: `0`

The restart run uses actual Spring Boot process termination/start, MySQL-backed task/message state, Vite, and a headless Chromium session. It is not a mocked browser test.

## Remaining risk

- Historical non-null values in `t_agent_conversation.summary` remain at rest. Runtime code neither reads nor rewrites them; removing the column requires a separate additive/deprecation migration window.
- The `/memory` response retains legacy field names (`estimatedTokens`, `needsCompact`) even though it is now calculated from the latest durable summary message. Renaming that public response is an API cleanup, not a second summary authority.
- Existing conversation rows may still contain stale aggregate data visible to direct database operators, but the API hides it and all recovery/fork/context paths use durable messages.
