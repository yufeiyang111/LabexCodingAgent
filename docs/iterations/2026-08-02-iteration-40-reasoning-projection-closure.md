# Iteration 40: Reasoning projection closure

- Date: 2026-08-02
- Goal: close the remaining paths that can project provider-internal `<think>` protocol text after the durable Message/Part state migration.
- Trigger: the user observed a raw `<think>` delimiter again after iteration 39.

## Confirmed root cause

Iteration 39 normalized the main Provider stream, legacy event replay, and final event persistence, but it did not cover every projection introduced by the durable transcript migration:

1. `agentRunPartState.js` copied durable `text` Part output and `assistant:final` Message content directly into `message.content` during task recovery. Historical dirty rows or a non-standard writer therefore bypassed the frontend reasoning boundary after refresh.
2. Durable Run Message/Part services accepted `FINAL` payload text without enforcing the reasoning boundary themselves. They relied on every caller already being clean.
3. Direct non-Agent Provider consumers did not share one output policy:
   - prompt optimization used one exact lowercase regular expression;
   - image understanding could promote `thinking` into a user-visible answer;
   - compaction accepted raw Provider content before parsing its structured checkpoint.

The recurrence is therefore an ownership problem, not merely another missing regular-expression variant: some new projections trusted upstream cleanup instead of enforcing the invariant at their own output boundary.

## Design

Use defense in depth without creating another transcript authority:

- Provider adapters and the Agent turn executor remain the earliest normalization point.
- Durable `FINAL` Message/Part writes normalize visible content before storage.
- Durable recovery projections normalize both final text and reasoning Part display before assigning frontend state.
- Direct Provider consumers call `InternalReasoningBoundary` explicitly:
  - user-visible outputs use `stripVisible` and never fall back to dedicated reasoning;
  - internal structured compaction strips hidden blocks before JSON parsing;
  - reasoning-only user-visible responses fail closed instead of exposing private reasoning.
- Existing dirty database rows are not rewritten destructively; replay sanitization makes them safe.

## Regression-first acceptance

1. A recovered `assistant:final` Message containing attributed/escaped reasoning renders only visible text.
2. A recovered durable `text` Part cannot overwrite the safe projection with raw reasoning protocol.
3. A durable `reasoning` Part displays reasoning text without protocol delimiters.
4. `AgentRunMessageService` and `AgentRunPartService` never persist raw reasoning delimiters for `FINAL`.
5. Prompt optimization removes non-trivial reasoning blocks through the shared boundary.
6. Image understanding never promotes reasoning-only output to a visible answer.
7. Compaction can parse valid structured output that follows a hidden reasoning block.
8. Full backend/frontend/build and real browser refresh recovery retain the invariant.

## Implementation record

### Durable state boundary

- `AgentRunMessageService` now sanitizes `FINAL` payload fields before writing both message content and metadata.
- `AgentRunPartService` applies the same rule before writing durable `text` Parts.
- Public history projection sanitizes legacy dirty final Messages and text Parts without rewriting the stored rows.
- Reasoning Messages/Parts retain their text but remove protocol delimiters.

### Frontend recovery boundary

- `agentRunPartState.js` no longer assigns durable Message/Part content directly to `message.content`.
- Run snapshot objects are copied and sanitized before being retained in UI state.
- Durable `text` Parts and `assistant:final` Messages remove complete reasoning blocks.
- Durable `reasoning` Parts remove delimiters while retaining the reasoning display text.

### Direct Provider consumers

- Prompt optimization replaced its exact lowercase regular expression with `InternalReasoningBoundary.stripVisible`.
- Image understanding no longer promotes the dedicated `thinking` field into a user-visible answer and fails closed when no visible content remains.
- Compaction strips hidden reasoning before parsing its structured JSON checkpoint.
- The acceptance Provider now emits an HTML-escaped attributed reasoning block split across the ordinary `content` channel as well as the dedicated reasoning channel.

## Regression evidence

Before implementation:

- frontend durable recovery regression failed because `assistant:final` content was copied unchanged;
- five backend regressions failed across durable Message persistence, durable Part persistence, prompt optimization, image understanding, and compaction parsing.

After implementation:

- durable recovery regression: 4/4 passed;
- focused backend boundary suite: 21/21 passed;
- acceptance Provider plus arbitrary-provider executor suite: 22/22 passed.

## Verification record

### Full source verification

- `cd backend; mvn test`
  - 861 tests, 0 failures, 0 errors, 8 skipped.
- `cd frontend; npm test`
  - 183 tests, 183 passed.
- `cd frontend; npm run build`
  - production build passed;
  - `CloudWorkspace` 1,459,020 / 1,500,000 bytes;
  - main index 1,259,534 / 1,300,000 bytes;
  - `TerminalPanel` 380,367 / 400,000 bytes.
- `git diff --check`
  - passed.

### Real backend, SSE, browser, refresh, and restart verification

Command:

```powershell
.\scripts\acceptance\run-all.ps1 -BackendPort 18080 -FrontendPort 13000 -CdpPort 19222 -TimeoutSeconds 180
```

Result:

- acceptance helper tests: 10/10 passed;
- backend restart run `e1406073b5314396b1f163c5d3334991` passed lifecycle, approval, RunMessage/RunPart projection, compaction, context-limit, completion-evidence, isolation, and cleanup checks;
- browser run `15e30cd411ff4ac999c36b03c3316ecc` passed live projection and refresh replay with `internalReasoningProtocolHidden=true`, zero console errors, and zero network errors.

A second browser run forced real backend restarts while the browser task remained active:

```powershell
.\scripts\acceptance\browser-runtime.ps1 -BackendPort 18080 -FrontendPort 13000 -CdpPort 19222 -TimeoutSeconds 180 -RestartBackendForAcceptance
```

- run `dc315fb714384995a19beea311c746d4`;
- `internalReasoningProtocolHidden=true`;
- `restartProjectionVerified=true`;
- `restartInteractionVerified=true`;
- console errors: 0;
- network errors: 0 after excluding the three expected restart transport interruptions;
- ports 18080, 13000, and 19222 were released after acceptance.

## Remaining risk

- Existing dirty rows remain in storage for audit and migration safety, but both backend public projection and frontend recovery sanitize them. A future UI must continue consuming the public projection rather than binding database entities directly.
- The policy intentionally treats `<think>` / `<thinking>` as internal protocol in assistant-generated content. A product feature that must display those literals should render an application-owned escaped example instead of bypassing this boundary.
