# Iteration 37: Durable prompt-cache telemetry projection

- Date: 2026-08-02
- Scope: distinguish real cache misses from absent telemetry, persist cache state, project it through durable token-usage events, and render it consistently before and after refresh.
- Architecture boundary: provider usage normalization -> persisted `AgentTokenUsage` -> durable `TOKEN_USAGE` event -> frontend reducer -> UI projection.

## Problem

The usage panel previously knew only total prompt and completion tokens. Missing provider cache fields were normalized to zero, so the UI could make an unsupported inference that a request had a 0% cache hit rate. Live events and history replay also discarded cache-read and cache-write fields.

This made several materially different situations look identical:

1. prompt cache routing was not enabled;
2. the provider omitted cache telemetry;
3. the provider explicitly reported a miss;
4. cache content was written but not yet read;
5. cached input tokens were actually read.

The result was misleading observability and made reports of "no recent cache hits" impossible to diagnose from persisted evidence.

## Plan

1. Characterize provider usage variants and field-presence semantics.
2. Introduce one cache telemetry classifier with explicit states.
3. Persist the state with each token-usage row and expose aggregate statistics.
4. include the state in durable `TOKEN_USAGE` events.
5. Use one frontend reducer for live events and replayed history.
6. Add a visible cache telemetry card and a deterministic browser acceptance fixture.
7. Run focused tests, full suites, build, backend runtime acceptance, and real browser acceptance.

## Changes

### Provider normalization

`OpenAiCompatibleProvider` now distinguishes an absent cache field from an explicitly reported zero. It recognizes supported OpenAI-compatible cache-read and cache-write shapes and emits `cache_usage_reported` alongside normalized token counts.

`CacheTelemetry` classifies each call as:

- `disabled`: no cache telemetry was reported and prompt-cache routing is disabled;
- `not_reported`: prompt-cache routing is enabled but the provider did not report cache fields;
- `miss`: the provider explicitly reported cache telemetry with no read or write tokens;
- `write_only`: cache content was created but no cached tokens were read;
- `hit`: cached input tokens were read.

Provider-reported telemetry is authoritative even when the local routing-key toggle is off, because some providers can perform automatic caching.

### Persistence and aggregation

- Added `cache_status` to `t_agent_token_usage` through both the canonical schema and additive migration path.
- Persisted normalized read tokens, write tokens, and cache status per usage record.
- Aggregated hit rate only over provider-reported calls; `disabled` and `not_reported` rows do not create a fake 0% rate.
- Exposed prompt, completion, cache-read, and cache-write totals in conversation/student summaries.
- Existing rows default to `not_reported`, preserving compatibility without rewriting historical evidence.

### Durable event projection

`AgentLoopEngine` now emits these fields on `TOKEN_USAGE`:

- `cachedTokens`
- `cacheWriteTokens`
- `cacheStatus`
- `cacheTelemetryReported`
- nullable `cacheHitRate`
- `estimated`

Estimated token records are explicitly `disabled` or `not_reported`; they are never treated as a measured cache miss.

### Frontend reducer and UI

- Added `cacheTelemetryStatus.js` as the single reducer/helper for live and replayed token usage.
- Reused the helper in `useAgentEventTimeline`, `useConversationState`, and historical event replay.
- Added a usage card that shows status, explanation, measured hit rate, cache-read tokens, and cache-write tokens.
- Entering the usage tab now explicitly loads the durable aggregate instead of relying on whichever conversation events happened to be in memory.
- Added a token-usage projection epoch. If a newer durable `TOKEN_USAGE` event arrives while an older aggregate request is in flight, the stale response is discarded and cannot overwrite the newer live projection.
- Corrected an intermediate file-encoding regression where newly added Chinese labels had become literal question marks; regression tests and browser assertions now require the intended visible labels.

### Acceptance fixture

The scripted provider recognizes `[acceptance:cache-telemetry]` and emits deterministic usage:

- prompt tokens: 200
- completion tokens: 20
- cached tokens: 50
- cache-write tokens: 10
- expected measured hit rate: 25.00%

The browser test verifies the card live, checks the persisted student summary API, refreshes the page, and verifies the same state is replayed.

During real browser acceptance, the final text was observed before token usage persistence completed. The first implementation allowed the usage tab's older aggregate response to overwrite the later live cache-hit event. This was reproduced as a `not_reported / 0 tokens` card, fixed with the projection epoch above, and then verified by rerunning the complete browser scenario. The conversation-menu helper was also hardened to wait for chat hydration and use native clicks, removing a separate synthetic-click race from the system test itself.

## Verification

### Focused backend tests

```powershell
cd D:\LabexAgent\backend
mvn -q -DforkCount=0 '-Dtest=AcceptanceScriptedProviderTest,CacheTelemetryTest,TokenTrackerCacheTelemetryTest,OpenAiCompatibleProviderUsageTest' test
```

Result: passed.

### Focused frontend tests

```powershell
cd D:\LabexAgent\frontend
node --test src/composables/cacheTelemetryStatus.test.mjs src/composables/useConversationState.test.mjs src/composables/useAgentEventTimeline.test.mjs src/views/agentStreamIntegration.test.mjs
npm run test:acceptance:unit
```

Result: 46/46 focused projection tests and 10/10 acceptance helper tests passed.

### Full frontend verification

```powershell
cd D:\LabexAgent\frontend
npm test
npm run build
```

Result: passed. The production bundle and chunk-budget checks passed.

### Full backend verification

```powershell
cd D:\LabexAgent\backend
mvn test
mvn clean package -DskipTests
```

Result: 850 tests across 224 suites, 0 failures, 0 errors, 8 skipped. Packaging completed successfully.

### Backend runtime acceptance

```powershell
cd D:\LabexAgent
.\scripts\acceptance\agent-runtime.ps1 -BackendPort 18172 -TimeoutSeconds 150
```

Result: passed. Question, permission, command approval/rejection restart recovery, checkout contention, durable Message/Part projection, manual compaction, context blockers, completion evidence, and normal-profile provider isolation were all `true`.

### Real browser acceptance

```powershell
cd D:\LabexAgent
.\scripts\acceptance\browser-runtime.ps1 -BackendPort 18173 -FrontendPort 13053 -CdpPort 19279 -TimeoutSeconds 180
```

Result: passed with an isolated 1440x900 desktop browser. Important evidence:

- `desktopLayout: true`
- `conversationIsolation: true`
- `refreshReplayDeduplicated: true`
- `internalReasoningProtocolHidden: true`
- `durableCacheTelemetryProjection: true`
- `questionReplyComponent: true`
- `permissionApprovalRefreshRecovery: true`
- `multiToolPermissionBatchProtocolComplete: true`
- `durableCompaction: true`
- `consoleErrors: 0`
- `networkErrors: 0`

## Risks and operational meaning

- `prompt_cache_key` is a routing hint, not a cache-hit guarantee. A later call still needs a reusable stable prefix and provider-side cache availability.
- Provider schemas vary. Unknown cache usage shapes remain `not_reported` until an explicit adapter mapping and test are added.
- A write-only call is not a failure; it can be the cache-creation phase before a later read.
- Prompt caching can reduce computation cost or latency, but cached tokens still consume model context capacity.
- The student summary is intentionally aggregate usage. Per-conversation detail remains available from the conversation summary and durable event history.
