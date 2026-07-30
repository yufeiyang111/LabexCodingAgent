# Iteration 03 - Durable compaction epoch and finite overflow recovery

Date: 2026-07-30
Branch: `codex/agent-tool-reliability`
Baseline commit: `ffc309e`

## Objective

Move context compaction from an in-memory list rewrite to a durable, replayable projection. Preserve complete user turns and native assistant/tool-call protocol, persist each compaction epoch, and make provider overflow recovery finite and explainable.

## Audit findings before implementation

1. `ConversationCheckpointCompactor` protects `tailTurns * 2` messages rather than real user turns, so assistant tool batches and tool results can be split.
2. `TurnAwareContextPruner` recognizes synthetic `[Tool ... result]` user text only and estimates content only; native `assistant.tool_calls` / `role=tool` data, role/name fields and arguments are omitted.
3. `ContextUsageEstimator` omits role/name/tool-call metadata and output reserve from its message-category estimate.
4. Compaction rewrites `TranscriptMessageList` via `clear()` + overridden `addAll()`, which can append the compacted projection back into the durable transcript as duplicate facts.
5. Compaction state is saved only as conversation/SSE summary events. There is no durable epoch containing previous summary, compacted head, retained tail, source boundary, token budget, status and failure reason.
6. Restart restoration reads the complete durable transcript directly, so an in-memory compaction is lost after JVM restart.
7. Provider overflow retries are stopped only when an estimated token count does not decrease; the strategy attempts are not represented by a bounded state object or durable compaction failure.

## Planned changes

- Add `AgentRequestTokenEstimator` and structured `AgentContextOverflowException`.
- Add turn-aware `CompactionSelection` that never splits a complete user turn/tool batch.
- Add `t_agent_compaction_record`, `AgentCompactionRecord`, mapper and `AgentCompactionService`.
- Apply the latest completed compaction record during durable transcript projection and restart restoration.
- Make in-memory projection replacement non-appending so compaction never duplicates transcript facts.
- Persist running/completed/failed compaction states and expose epoch/boundary metadata in SSE events.
- Bound overflow recovery to an explicit strategy sequence; terminate with a structured reason when exhausted.

## Verification gates

1. Focused red/green tests for selection, request token estimation, persistence and restart projection.
2. Full backend test suite.
3. Full frontend acceptance suite and production build.
4. Real Spring Boot + MySQL run, browser acceptance, database inspection of transcript and compaction records.
5. Restart the verified JVM and prove the same task projection is reconstructed from durable state.
6. Inspect the loaded JVM classes and runtime logs; compilation alone is not acceptance.

## Results

## Implemented

### Durable context model

- Added `AgentRequestTokenEstimator` so the request budget covers the system prompt, tool schemas, native message metadata, tool-call arguments, tool results and reserved output capacity. Missing context-window configuration now enters an explicit blocked branch instead of using a permissive fake default.
- Added `CompactionSelection`, which selects complete user turns and keeps a native assistant/tool batch intact. The latest real user turn is never split.
- Added `t_agent_compaction_record`, `AgentCompactionRecord`, `AgentCompactionRecordMapper` and `AgentCompactionService`. Every compaction records execution epoch, compaction epoch, trigger, previous summary, compacted head, retained tail, transcript source boundary, token budget, status and failure reason.
- Added a safe task projection for compaction audit metadata. The HTTP projection intentionally excludes summaries, compacted transcript text and retained-tail payloads.
- Added terminal task projection `GET /student/projects/{projectId}/agent/tasks/{taskId}` so completed tasks can be inspected after refresh or JVM restart.

### Runtime and overflow recovery

- `TranscriptMessageList.replaceProjection` now replaces only the in-memory Provider projection and does not append a derived summary/tail back into durable transcript facts.
- Restart restoration applies the latest completed compaction and then appends only transcript facts after its persisted source sequence.
- Compaction uses the previous durable summary plus the selected head, persists `running/completed/failed`, and emits structured context-management events.
- Provider overflow recovery is finite: durable compaction, reduced core tool schemas, then an explicit `context_overflow_recovery_exhausted` terminal reason.
- Added frontend live/replay rendering for `CONTEXT_TOOL_SCHEMA_REDUCED`; the reducer treats it as a completed recovery strategy rather than guessing from the SSE connection lifecycle.

### Acceptance observability

- Extended the acceptance-only scripted Provider with a question -> large native tool call -> compaction -> final response scenario.
- Extended the browser harness to validate the persisted compaction epoch, token reduction, native tool-call identity, bounded Provider-message count and a real same-task JVM restart projection.
- Added an optional file handoff used only by the acceptance harness to pause after compaction while the verified backend process is restarted. The handoff contains task/conversation identifiers only and no token, cookie, password or API key.

## Failures found by real verification

1. **Spring startup failure:** `AgentCompactionService` was initially `final` while exposing `@Transactional` methods. Unit tests and compilation passed, but Spring CGLIB proxy creation failed during a real application start. A regression test now requires the service to remain proxyable.
2. **Mixed acceptance environment:** the existing Vite process on port `13000` still proxied `/api` to the old JVM on `8080`, while setup requests targeted `18080`. This produced a misleading old-runtime/new-data mixture. Verification was moved to an isolated pair: Vite `13001` -> backend `18080`; the user's `8080/13000` processes were left untouched.
3. **Acceptance sequencing race:** after model-switch reload, the browser could try to submit while the conversation/task projection was still reattaching. The harness now waits for the durable terminal projection before the next submission.
4. **Source encoding regression:** one newly added Chinese comment was written as ASCII question marks. `sourceEncoding.test.mjs` caught it; the source was rewritten as valid UTF-8 and the complete frontend suite was rerun.

## Verification results

### Focused red/green regression

- New context/persistence/protocol tests first failed for missing implementations, then passed after implementation.
- `mvn -Dtest=AgentCompactionServiceTest,AgentTaskEventControllerTest test`: **7 passed, 0 failed**.
- `mvn -Dtest=AcceptanceScriptedProviderTest test`: **10 passed, 0 failed**.
- Frontend reducer/timeline tests first failed for the missing tool-schema-reduction projection, then passed after both live and replay paths were updated.

### Complete automated gates

- `cd backend && mvn test`: **773 tests, 0 failures, 0 errors, 8 skipped**. Log: `backend/target/iteration03-full-backend-final-20260730.log`.
- `cd frontend && npm test`: **149 passed, 0 failed**. Log: `frontend/iteration03-full-frontend-final-20260730.log`.
- `cd frontend && npm run test:acceptance:unit`: **9 passed, 0 failed**. Log: `frontend/iteration03-acceptance-unit-final-20260730.log`.
- `cd frontend && npm run build`: production build and all configured chunk budgets passed. Log: `frontend/iteration03-frontend-build-final-20260730.log`.
- `cd frontend && node src/utils/sourceEncoding.test.mjs`: **1 passed, 0 failed** after the UTF-8 correction.
- Exact staged-index verification: exported the Git index into an isolated temporary tree, then ran the focused backend compaction/controller/provider suite and the frontend reducer/timeline suite against the files that will actually be committed; backend passed and frontend reported **26 passed, 0 failed**. This guards against mixed worktree hunks entering or breaking the commit.

### Real Spring Boot, MySQL and browser acceptance

The final browser run used the isolated pair `http://127.0.0.1:13001` -> `http://127.0.0.1:18080/api` with the `acceptance,local` backend profiles and the real MySQL schema migrator. It verified:

- desktop layout and non-mobile geometry;
- new/old conversation isolation;
- refresh replay deduplication and durable SSE cursors;
- visible user-question reply UI and same-task continuation;
- persisted Provider messages/parts and native `toolCallId`;
- completed durable compaction epoch `1`;
- estimated request reduction from **23,650** to **22,014** tokens;
- exactly **6** projected Provider messages after compaction, with no compaction re-append explosion;
- static context blocker details;
- verified completion evidence and blocked unverified completion;
- zero meaningful browser console or network errors.

Evidence log: `frontend/iteration03-browser-acceptance-20260730.log`.

### Real same-task JVM restart

The restart harness paused after task `642` persisted compaction epoch `1`. The backend process on `18080` was stopped, a new Spring Boot JVM was started and verified (`PID 8628`, startup time after the previous process), and the original browser process then queried the same task with its existing login state. The new JVM returned the same completed compaction epoch and the same six Provider transcript messages. `restartProjectionVerified` was `true`.

Evidence:

- `frontend/target/iteration03-restart-handoff-20260730-2241/ready.json`
- `frontend/target/iteration03-restart-handoff-20260730-2241/done.json`
- `frontend/iteration03-restart-browser.out.log`
- `backend/target/iteration03-restart-acceptance-18080.out.log`

## Remaining risks and next iteration

- This iteration makes compaction durable and replayable, but broader lifecycle convergence still has remaining work: approval/interaction ownership, transactional outbox guarantees across every producer, and removing legacy conversation/context compatibility paths.
- The compaction audit endpoint intentionally exposes metadata only. Deeper transcript inspection must remain an authenticated operator/debug capability rather than a normal frontend payload.
- The acceptance Provider is deterministic and profile-gated; it validates the production runtime/tool/projection path but does not prove any external LLM gateway's usage accounting.
