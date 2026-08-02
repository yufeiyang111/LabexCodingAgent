# Iteration 35: Internal reasoning tag output boundary

- Date: 2026-08-02
- Scope: prevent provider protocol delimiters from becoming visible Agent text while preserving the durable-event replay model.
- Related architecture rule: provider output is untrusted input; the frontend is a projection and must not infer or expose protocol-only content.

## Problem and evidence

A provider can emit reasoning in a dedicated field or wrap it in protocol delimiters such as `<think>` and `<thinking>`. The previous stream parser only recognized lowercase `<think>` and the frontend appended `THINK_DELTA` and `FINAL_DELTA` directly. Therefore mixed-case, alternate delimiter names, split delimiters, and old replayed content could be rendered as user-visible text.

This is an output-boundary defect, not a new transcript authority. The durable event and part owners are unchanged.

## Plan

1. Make the OpenAI-compatible provider recognize case-insensitive `<think>` and `<thinking>` delimiters across SSE chunks.
2. Keep protocol delimiters out of both the thinking and visible-text callbacks.
3. Add projection-side defenses for live SSE and persisted-history replay.
4. Make final-answer filtering stateful across `FINAL_DELTA` fragments so an incomplete private block cannot flash before the close delimiter arrives.
5. Verify provider parsing, frontend reducers, production build, packaged JAR, and the existing isolated runtime acceptance suite.

## Delivered changes

- `OpenAiCompatibleProvider.ThinkTagStreamParser` now matches both delimiter forms case-insensitively and keeps incomplete delimiter prefixes buffered between SSE chunks.
- The provider contract test sends `<TH` in one SSE frame and `INKING>...` in the next. It proves that delimiters are not returned in any callback, while the text is separated into the expected channels.
- `agentMarkdown.js` contains two projection safeguards:
  - tag cleanup for thinking and old persisted records;
  - a per-message stateful final-output filter for split internal reasoning blocks.
- Both the live event timeline reducer and history reducer use the safeguards. `FINAL` remains authoritative for completed output and resets transient filtering state after projection.
- The source-level stream integration contract requires final text to pass through the output boundary instead of directly appending provider data.

## Acceptance evidence

### Focused regression tests

```powershell
cd D:/LabexAgent/backend
mvn -q -DforkCount=0 -Dtest=OpenAiCompatibleProviderContractTest test

cd ../frontend
node --test src/utils/agentMarkdown.test.mjs src/composables/agentHistoryReducer.test.mjs src/composables/useAgentEventTimeline.test.mjs
```

Result:

- Provider contract passed with a local HTTP SSE server, including mixed-case and cross-frame delimiter parsing.
- 42 frontend focused tests passed. They include live and replayed `FINAL_DELTA` sequences where the internal block spans three fragments.

### Broader checks

```powershell
cd D:/LabexAgent/frontend
npm test
npm run build

cd ../backend
mvn -q -DskipTests package
```

Result:

- Full frontend suite passed: 172 tests, 0 failures.
- Vite production build and chunk-budget check passed.
- Backend JAR package completed successfully.

### Isolated backend runtime acceptance

```powershell
D:/LabexAgent/scripts/acceptance/agent-runtime.ps1 `
  -BackendPort 18148 `
  -JarPath D:/LabexAgent/backend/target/labex-agent-backend-1.0.0.jar `
  -TimeoutSeconds 240
```

Result: all reported checks were `true`: question/permission/command approval continuation across restart, checkout contention, durable run message/part projection, manual compaction, context admission safeguards, completion evidence, unverified-edit rejection, normal-profile isolation, and cleanup.

### Browser runtime acceptance

```powershell
D:/LabexAgent/scripts/acceptance/browser-runtime.ps1 `
  -BackendPort 18150 `
  -FrontendPort 13030 `
  -CdpPort 19256 `
  -JarPath D:/LabexAgent/backend/target/labex-agent-backend-1.0.0.jar `
  -TimeoutSeconds 300 `
  -RestartBackendForAcceptance
```

Result: browser-level acceptance passed with the latest frontend build: desktop layout, conversation isolation, refresh replay deduplication, question reply component, permission approval recovery, durable provider message/part projection, cursor persistence, durable compaction, restart projection/interaction recovery, static context blocker card, completion evidence card, unverified completion blocker, `expectedRestartTransportErrors = 0`, `consoleErrors = 0`, and `networkErrors = 0`.

## Limits and follow-up

- This protects LabexAgent provider output and UI projection. It cannot alter a separate desktop chat client rendering its own internal channel.
- Existing persisted records are sanitized at projection time. They are not rewritten, preserving transcript evidence and avoiding a second persistence migration.
- Continue with the previously opened prompt-cache diagnosis after this user-visible leak boundary is committed.
