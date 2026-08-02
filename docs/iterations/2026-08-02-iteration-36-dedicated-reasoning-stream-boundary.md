# Iteration 36: Dedicated reasoning-stream output boundary

- Date: 2026-08-02
- Scope: prevent `<think>` and `<thinking>` protocol delimiters from leaking through dedicated provider reasoning channels, durable replay, or final rendering.
- Commit: `c699f6b fix: close dedicated reasoning tag leaks`

## Problem

Iteration 35 protected ordinary assistant `content`, but dedicated provider reasoning fields such as `reasoning_content` could still carry raw protocol delimiters. Those delimiters could be split across SSE chunks, persisted as `THINK_DELTA` events, replayed after refresh, and rendered visibly.

A representative failure was a chunk sequence equivalent to:

```text
<thi
nk>private plan</THINK
ING>
```

A per-chunk string replacement cannot safely remove that sequence because no individual chunk contains a complete tag.

## Architecture decision

The provider adapter is the primary trust boundary for provider protocol text. The durable event store remains authoritative for replay, while reducers and final rendering apply defense in depth. The fix does not create a second transcript or frontend-owned task state.

## Changes

### Provider boundary

- Added a stateful protocol-tag stream filter for dedicated reasoning output.
- Buffered possible partial tag prefixes across SSE chunks.
- Removed complete `<think>` and `<thinking>` delimiters case-insensitively.
- Flushed only safe visible reasoning text before the stream terminates.

### Durable event projection

- Applied the same boundary while reducing live `THINK_DELTA` events.
- Applied it again when replaying persisted reasoning events after refresh.
- Kept reasoning content visible while hiding only provider protocol markers.

### Final render boundary

- Sanitized assistant reasoning immediately before Markdown rendering.
- Preserved this as a last-resort boundary for older persisted events that may already contain delimiters.

### Acceptance fixture

- Added `[acceptance:reasoning-boundary]` to the scripted provider.
- Emitted deliberately split mixed-case protocol tags through the dedicated reasoning channel.
- Extended browser acceptance to verify both live rendering and refresh replay.

## Verification

### Focused backend tests

```powershell
cd D:\LabexAgent\backend
mvn -q -DforkCount=0 '-Dtest=AcceptanceScriptedProviderTest#emitsSplitReasoningProtocolFixtureForBrowserBoundaryAcceptance,OpenAiCompatibleProviderContractTest#stripsSplitProtocolTagsFromDedicatedReasoningChannel' test
```

Result: passed. The provider emitted visible reasoning text without protocol delimiters.

### Focused frontend tests

```powershell
cd D:\LabexAgent\frontend
node --test src/utils/agentMarkdown.test.mjs src/composables/useAgentEventTimeline.test.mjs src/composables/agentHistoryReducer.test.mjs
node --test src/views/agentStreamIntegration.test.mjs
```

Result: passed.

### Full verification

```powershell
cd D:\LabexAgent\frontend
npm test
npm run build

cd D:\LabexAgent\backend
mvn test
mvn clean package -DskipTests
```

Result: all commands passed, including frontend chunk-budget checks.

### Runtime and browser acceptance

The acceptance suite started isolated backend, frontend, and browser processes instead of reusing a developer server. It verified:

- `internalReasoningProtocolHidden: true`
- `refreshReplayDeduplicated: true`
- question reply and permission approval recovery
- durable transcript and compaction projection
- zero browser console errors
- zero browser network errors

The browser DOM contained the visible reasoning payload but no `<think>` or `<thinking>` fragments before or after refresh.

## Risks and follow-up

- Unknown provider-specific delimiters still require an explicit adapter contract; broad removal of arbitrary angle-bracket text would corrupt valid user-visible content.
- Older rows are protected at render time, but a future migration could optionally normalize historical reasoning parts if required.
- Runtime verification must continue to use the packaged JAR and isolated browser process so stale JVM or Vite code cannot produce a false pass.
