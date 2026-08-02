# Iteration 39: Internal reasoning boundary regression

- Date: 2026-08-02
- Goal: prevent raw internal-reasoning protocol delimiters from reaching durable transcript, SSE projections, or rendered history.
- Trigger: the user observed a visible `<think>` delimiter after iterations 35 and 36.

## Confirmed gap

The previous repair protected the common streaming forms of `<think>` and `<thinking>`, but the boundary was not uniform:

1. `OpenAiCompatibleProvider.parseResponse` returned non-streaming `content` and `reasoning_content` without normalization.
2. `AgentLoopEngine.cleanModelOutput` only removed exact lowercase `<think>...</think>` blocks.
3. Stream parsers only recognized exact tags without attributes or HTML-escaped delimiters.
4. The frontend replay defense had the same exact-tag limitation, so old or provider-escaped records could display delimiters after refresh.

A streaming fallback, provider retry, alternate OpenAI-compatible response, or historical record could therefore bypass the previous fix.

## Design

Use one semantic contract at every boundary:

- **Visible assistant output**: remove complete internal-reasoning blocks, orphan closing delimiters, and incomplete opening tails.
- **Dedicated reasoning output**: retain reasoning text but remove protocol delimiters.
- **Provider parser**: normalize both streaming and non-streaming responses before returning a provider result.
- **Runtime/persistence boundary**: sanitize final output before SSE emission and durable `FINAL` persistence.
- **Frontend/replay boundary**: repeat the same defensive normalization for historical dirty data; it remains a projection guard, not the source of truth.

Supported protocol forms are case-insensitive `<think>` / `<thinking>`, optional tag whitespace or attributes, and common HTML-escaped forms. The repair does not persist hidden reasoning as final visible content.

## Regression-first acceptance

1. Non-streaming OpenAI-compatible responses cannot return raw tags.
2. Streaming tags split across chunks with attributes cannot leak.
3. Escaped and attributed tags are removed during frontend history rendering.
4. Refresh/replay produces the same safe visible projection as the live stream.
5. Existing ordinary visible text around a reasoning block is preserved.

## Implementation record

### Backend protocol authority

- Added `InternalReasoningBoundary` as the shared raw/escaped reasoning protocol scanner.
- Replaced the two private exact-tag parsers in `OpenAiCompatibleProvider` with the shared stream filters.
- Normalized non-streaming `content` and `reasoning_content` before constructing provider results.
- Removed the unsafe fallback that promoted a reasoning-only response into visible `content`.
- Applied the same stream boundary in `AgentModelTurnExecutor`, so a future or scripted `LlmProvider` cannot bypass normalization merely by emitting raw chunks.
- Sanitized `FINAL`/`FINAL_DELTA` and durable `THINK*` event payloads before `AgentMessage` persistence.
- Sanitized runtime final aggregates before SSE/durable final projection.
- Routed the direct final-summary Provider stream through the same stateful filters instead of trusting provider-local cleanup.

### Frontend replay defense

- Replaced exact-string matching with a stateful scanner that recognizes raw, HTML-escaped, mixed-case, attributed, and split delimiters.
- Kept two distinct semantics:
  - reasoning channel: remove delimiters and retain reasoning text;
  - final answer: remove the entire reasoning block.
- Kept rendering-time sanitization so historical dirty events remain safe after refresh.

### System fixture

The acceptance-only provider now emits an HTML-escaped, attributed delimiter split across three chunks. This makes the browser scenario exercise the regression rather than the old easy case.

## Regression evidence

Before the implementation:

- `OpenAiCompatibleProviderContractTest`: 2 failures; both non-streaming and attributed split-stream output retained raw `<THINK ...>` text.
- `frontend/src/utils/agentMarkdown.test.mjs`: 2 failures; attributed and escaped blocks remained visible.
- `AgentModelTurnExecutorTest#enforcesReasoningBoundaryForEveryProviderImplementation`: failed because a non-OpenAI provider could bypass the provider-local filter.
- `AgentConversationServiceCompactionTest#finalEventPersistsOnlyTheVisibleReasoningProjection`: failed because raw reasoning was stored in both `content` and `eventData`.
- `OpenAiCompatibleProviderContractTest#neverPromotesReasoningOnlyResponseIntoVisibleContent`: failed because `private plan` was returned as visible content.

## Verification record

### Focused verification

- `mvn -Dtest=AcceptanceScriptedProviderTest,AgentModelTurnExecutorTest,OpenAiCompatibleProviderContractTest,AgentConversationServiceCompactionTest test`
  - 42 tests, 0 failures, 0 errors.
- `node --test src/utils/agentMarkdown.test.mjs`
  - 9 tests, 9 passed.

### Full source verification

- `cd backend; mvn test`
  - 854 tests, 0 failures, 0 errors, 8 skipped.
- `cd frontend; npm test`
  - 182 tests, 182 passed.
- `cd frontend; npm run build`
  - production build passed;
  - `CloudWorkspace` 1,458,592 / 1,500,000 bytes;
  - main index 1,259,534 / 1,300,000 bytes;
  - `TerminalPanel` 380,367 / 400,000 bytes.

### Real runtime and browser verification

Command:

```powershell
.\scripts\acceptance\run-all.ps1 -BackendPort 18080 -FrontendPort 13000 -CdpPort 19222 -TimeoutSeconds 180
```

Result:

- package: passed;
- acceptance helper tests: 10/10 passed;
- backend restart acceptance run `c6434357703f47e0bc1362b8e243052e`: passed all lifecycle, approval, projection, compaction, context and cleanup checks;
- browser acceptance run `0b1f647ba6ac469babfb4f44678dda28`:
  - `internalReasoningProtocolHidden=true` for live projection and refresh replay;
  - `conversationIsolation=true`;
  - `refreshReplayDeduplicated=true`;
  - console errors: 0;
  - network errors: 0.
- Ports 18080, 13000 and 19222 were released after acceptance.

## Remaining risk

- The scanner intentionally treats `<think>` and `<thinking>` in assistant output as provider protocol even if a model intended to show them as a literal example. This is the safer boundary for an agent UI; literal examples should be escaped by trusted application code rather than passed through the model protocol unchanged.
- Existing database rows are not destructively rewritten. They are sanitized during replay/rendering, while all new `FINAL` and `THINK*` writes pass through the persistence boundary.
