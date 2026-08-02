# Iteration 42: Canonical event reasoning boundary

- Date: 2026-08-02
- Status: completed
- Scope: close the remaining `<think>` / `<thinking>` delimiter leak in the authoritative run-event and SSE delivery path.

## Problem statement

Iterations 36-40 normalized provider output, conversation events, durable run messages/parts, recovery projections, and final Markdown rendering. The authoritative delivery path still had two gaps:

1. `AgentRunLifecycleService` serialized the original event payload into `AgentRunEvent` and the transactional outbox before the downstream part projection sanitized it.
2. `AgentSsePublisher` persisted an event and then sent the original caller-owned payload to the live browser; replay and task-subscription paths also passed decoded legacy payloads directly to the SSE frame builder.

Therefore a caller that supplied a raw reasoning delimiter could bypass the already-correct `AgentRunMessage` / `AgentRunPart` projections. Old durable events could reproduce the leak after refresh even after new writes were fixed.

## Architectural decision

`InternalReasoningBoundary` remains the single protocol-normalization utility. The boundary must be enforced at all authoritative event edges:

- before an `AgentRunEvent` or outbox envelope is persisted;
- before any live transient or durable SSE frame is serialized;
- while projecting legacy persisted events for replay, without rewriting historical rows;
- downstream message/part/frontend filters remain defense in depth, not the primary authority.

Visible `FINAL` / `FINAL_DELTA` payloads drop complete internal reasoning blocks. `THINK*` payloads retain reasoning text but remove protocol delimiters. Unrelated tool/file payloads are not globally rewritten because they may legitimately contain source code with the same literal text.

## Regression plan

1. Prove a durable `FINAL` event currently persists raw reasoning content into both `AgentRunEvent` and outbox.
2. Prove live, transient, and persisted SSE frames currently serialize raw delimiters.
3. Prove legacy replay currently returns raw persisted payloads.
4. Implement the shared event-payload boundary and make all three tests green.
5. Run focused tests, the full backend suite, frontend tests/build, and the real browser/runtime acceptance scenario including refresh and backend restart.

## Acceptance criteria

- No raw, case-variant, attributed, HTML-escaped, or numeric-escaped internal reasoning delimiter reaches a final or reasoning SSE frame.
- Newly persisted authoritative events and outbox envelopes contain only normalized payloads.
- Existing dirty rows are normalized on replay without destructive migration.
- Refresh/reconnect and forced backend restart preserve visible output and never display protocol tags.


## Implementation record

### Shared event boundary

- Added `InternalReasoningBoundary.sanitizeEventPayload(...)` and a shared event-type classifier.
- `FINAL` / `FINAL_DELTA` remove complete internal reasoning blocks from `content` and `delta`.
- `THINK*` projections keep reasoning text while removing protocol delimiters.
- Non-reasoning tool/file payloads remain byte-for-byte outside this boundary so source code containing the same literal text is not corrupted.

### Authoritative persistence and delivery

- All three `AgentRunLifecycleService` event creation paths now persist the normalized payload to both `AgentRunEvent` and the transactional outbox, then project the same normalized object into Run Parts.
- `AgentSsePublisher.send`, `sendTransient`, and `sendPersisted` normalize immediately before the final browser frame, so the primary connection, secondary task subscribers, and replay endpoints share the same output boundary.
- `AgentRunEventReplayService` normalizes matching legacy rows in the read projection only. Historical database rows are not destructively rewritten.

### Acceptance harness stabilization

The forced-restart browser scenario exposed two pre-handoff races unrelated to the product fix:

1. the cache card was inspected after final text appeared but before the Agent reached terminal state;
2. after reload, the asynchronously mounted conversation menu could miss a one-shot click.

The harness now waits for the cache run terminal state and uses a bounded click/mount retry for the conversation menu. Assertions were not weakened.

## Red/green evidence

### RED

Command:

```powershell
mvn '-Dtest=AgentRunLifecycleServiceTest,AgentRunEventReplayServiceTest,AgentSsePublisherDurabilityTest' test
```

Result before implementation: 34 tests, 4 failures. The failures proved raw reasoning content was present in:

- the authoritative `AgentRunEvent` payload;
- the transactional outbox envelope;
- live/transient SSE data;
- legacy replay payloads.

### GREEN focused

Commands and results:

- focused authority tests: 34 tests, 0 failures, 0 errors;
- expanded reasoning/compaction tests: 74 tests, 0 failures, 0 errors.

## Final verification

### Backend

```powershell
mvn test
```

- Exit code: 0
- Tests: 868
- Failures: 0
- Errors: 0
- Skipped: 8
- Maven result: `BUILD SUCCESS`

### Frontend

```powershell
npm test
```

- Tests: 183
- Passed: 183
- Failed: 0
- The source-encoding guard also caught and then verified the repair of two corrupted comments in the still-uncommitted Iteration 41 entity change.

```powershell
npm run build
```

- Exit code: 0
- Vite transformed 3,787 modules.
- Chunk budgets passed:
  - `CloudWorkspace`: 1,459,020 / 1,500,000 bytes
  - `index`: 1,259,534 / 1,300,000 bytes
  - `TerminalPanel`: 380,367 / 400,000 bytes

### Real runtime and browser acceptance

```powershell
.\scripts\acceptance\run-all.ps1 -BackendPort 18080 -FrontendPort 13000 -CdpPort 19222 -TimeoutSeconds 180
```

- Exit code: 0
- Backend runtime run ID: `0a44ebbb700244c2b176270d0b0e7486`
- Browser run ID: `955f2e9dc0e34d26887457a9317d43d5`
- `internalReasoningProtocolHidden=true`
- `refreshReplayDeduplicated=true`
- approval/question recovery, durable Provider transcript, compaction, completion evidence, and cleanup all passed.
- Browser console errors: 0
- Browser network errors: 0

```powershell
.\scripts\acceptance\browser-runtime.ps1 -BackendPort 18080 -FrontendPort 13000 -CdpPort 19222 -TimeoutSeconds 180 -RestartBackendForAcceptance
```

- Exit code: 0
- Browser run ID: `6a5f7d8c90b0457ab568e6139eeb7c04`
- `internalReasoningProtocolHidden=true`
- `restartInteractionVerified=true`
- `restartProjectionVerified=true`
- `expectedRestartTransportErrors=0`
- Browser console errors: 0
- Browser network errors: 0

All acceptance-owned ports (`18080`, `13000`, `19222`) were released after verification.

## Files changed in this iteration

- `backend/src/main/java/com/labex/labexagent/llm/InternalReasoningBoundary.java`
- `backend/src/main/java/com/labex/labexagent/run/AgentRunEventReplayService.java`
- `backend/src/main/java/com/labex/labexagent/run/AgentRunLifecycleService.java`
- `backend/src/main/java/com/labex/labexagent/runtime/AgentSsePublisher.java`
- `backend/src/test/java/com/labex/labexagent/run/AgentRunEventReplayServiceTest.java`
- `backend/src/test/java/com/labex/labexagent/run/AgentRunLifecycleServiceTest.java`
- `backend/src/test/java/com/labex/labexagent/runtime/AgentSsePublisherDurabilityTest.java`
- `frontend/scripts/acceptance/agent-browser.mjs`
- `docs/iterations/2026-08-02-iteration-42-canonical-event-reasoning-boundary.md`

## Remaining risk

- Historical rows can still contain the old raw payload at rest; all supported replay/SSE projections now normalize them. A destructive data migration is intentionally deferred because it is unnecessary for user-visible correctness and would increase migration risk.
- This boundary intentionally targets `FINAL*` and `THINK*` events. Tool/file outputs are not globally stripped because a repository may legitimately contain those literal strings.
