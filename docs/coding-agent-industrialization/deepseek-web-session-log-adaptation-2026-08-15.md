# DeepSeek Harness Web Session-Log Adaptation (2026-08-15)

## Scope

This iteration uses the current public DeepSeek Harness Web architecture as the primary reference, with the local OpenCode v1.17.4 snapshot as the secondary reference for durable tool-loop detection. No source code was copied from either project.

## References inspected

- DeepSeek Harness public `master` source, retrieved on 2026-08-15:
  - `packages/core/agent-loop/src/agent.ts:245-400`: the driver appends `turn/start`, `step/start`, user messages, assistant chunks, the assembled assistant message, and `turn/end`; each model request is built from `session.deriveMessages()`.
  - `packages/core/agent-loop/src/tool-calls.ts:40-289`: tool calls and model-ordered results are appended to the session log; cancellation emits a paired synthetic result for each unstarted call so replay remains protocol-valid.
  - `packages/client/runtime/src/client/sessions/projection-store.ts:134-176`: a projection only accepts a greater sequence number; baseline replay cannot overwrite a newer live frame, and reconnect truncates client-only rows beyond the durable cursor.
  - `docs/architecture.md`: sessions are append-only event streams, and the Web client reconstructs state from durable events plus ordered projections.
- OpenCode local snapshot `D:\opencode\opencode-dev` (package version 1.17.4):
  - `packages/opencode/src/session/prompt.ts:1134-1149`: each loop reads durable compacted messages again.
  - `packages/opencode/src/session/processor.ts:519-545`: repeated tool/input suffixes are detected from durable Parts before asking for a doom-loop decision.

## LabexAgent adaptation

DeepSeek can append directly from a single session owner. LabexAgent is a multi-user Web control plane, so each executor-originated durable event must additionally prove that its execution lease is still current.

The live `AgentSsePublisher` now accepts the active `ExecutionFence` at run binding. When the fence is available, `send(...)` calls the fenced `AgentRunLifecycleService.appendEvent(...)` overload. The ordinary two-argument binding remains only as a compatibility path for callers that do not own an executor lease (for example focused publisher tests).

`AgentLoopEngine` binds the publisher only after acquiring the current lease and creating the fence. Therefore durable events such as `THINK`, `LOOP_GUARD`, `FINAL`, and `DONE` cannot be appended by a stale worker after the task moved to another owner or epoch.

## Invariants preserved

1. Durable event persistence happens before its live SSE projection.
2. Reconnect/replay still uses the existing ordered event cursor; no new frontend state source is introduced.
3. Event payload sanitization and transient token-delta behavior are unchanged.
4. A lost execution fence fails the write instead of silently producing an old-epoch event.
5. The earlier current-epoch terminal Tool Part suffix replay remains the authority for loop-guard reconstruction; this change only fences the corresponding durable event stream.

## Files changed by this adaptation

- `backend/src/main/java/com/labex/labexagent/runtime/AgentSsePublisher.java`
- `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- `backend/src/test/java/com/labex/labexagent/runtime/AgentSsePublisherDurabilityTest.java`

## Follow-up: durable non-progress budget

OpenCode evaluates every loop iteration from durable message/part state. LabexAgent already restored the current-epoch terminal Tool Part suffix, but the separate `nonProgressIterations` fuse was previously process-local. This iteration adds an immutable `LOOP_GUARD_PROGRESS` lifecycle event carrying the current epoch, reason, iteration, optional tool name, and absolute non-progress count.

The existing event-to-Part projector maps the event to one stable `loop_guard_progress` Part. A resumed `AgentLoopGuard` loads the latest Part for its current execution epoch before calling `beforeIteration`. Both model-only no-progress outcomes and successful/failed tool outcomes project the updated absolute count, so a restart or worker takeover cannot reset the non-progress budget silently.

This is a LabexAgent adaptation, not copied DeepSeek or OpenCode code. The event remains fenced by the active executor lease, and the Part remains a rebuildable projection of the immutable lifecycle event.

## Follow-up: durable model-step boundaries

DeepSeek Harness records explicit turn and step boundaries around the model-driver path. LabexAgent now adopts the smallest compatible subset without changing the database schema or replacing the existing task lifecycle: `AgentTask + executionEpoch` remains the authoritative run boundary, while one actual model-invocation attempt is represented as one durable `model_step` Part.

`AgentLoopEngine` emits the following fenced lifecycle events around the existing `AgentModelTurnExecutor` invocation:

- `MODEL_STEP_STARTED` before the context-admission gate attempts the request;
- `MODEL_STEP_COMPLETED` after a model result is returned, with its result type;
- `MODEL_STEP_BLOCKED` when context admission declines the request;
- `MODEL_STEP_INTERRUPTED` when cancellation wins the race;
- `MODEL_STEP_FAILED` when invocation throws, with only the exception class as the diagnostic reason.

The event projector maps all five events to the stable key `model-step:<iteration>`. Thus the same Part moves from `running` to `completed`, `blocked`, `interrupted`, or `error`, instead of creating an ambiguous collection of one-off rows. It carries the task id, execution epoch, iteration, and only safe outcome metadata.

Existing recovery already seals every `pending`, `running`, and `streaming` Part as `interrupted` before redispatch. A process that dies after `MODEL_STEP_STARTED` therefore leaves a durable, queryable interrupted model-step boundary rather than an implicit in-memory gap. No new recovery owner, mutable transcript list, browser-only state, or second status field was introduced.

This is intentionally narrower than DeepSeek Harness's generic turn/step event model. In this phase a `model_step` means the provider invocation only; tool execution, approval waits, and lifecycle transitions keep their existing durable Part types. The next convergence phase can generalize a full turn projection only after the provider transcript and event projector have one shared authority path.

Files additionally changed by this follow-up:

- `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- `backend/src/main/java/com/labex/labexagent/run/AgentRunPartService.java`
- `backend/src/test/java/com/labex/labexagent/runtime/AgentLoopEngineLoopPolicyTest.java`
- `backend/src/test/java/com/labex/labexagent/run/AgentRunPartServiceTest.java`

## Follow-up: Web projection for durable model steps

The frontend already rejects duplicate or older SSE frames at the task cursor boundary. The new backend `model_step` Part still needed explicit client projection, otherwise its recovery value would exist only in the database and not in the Web runtime.

The Web runtime now projects `MODEL_STEP_STARTED`, `MODEL_STEP_COMPLETED`, `MODEL_STEP_FAILED`, `MODEL_STEP_BLOCKED`, and `MODEL_STEP_INTERRUPTED` into one derived `modelSteps` entry keyed by `model-step:<iteration>`. The same projection is used by durable history replay, live task-event SSE, and `runParts` snapshot hydration. Every update carries the durable task event sequence. A lower sequence cannot overwrite a later state, so a delayed `running` replay or snapshot cannot replace an already completed model invocation.

`modelSteps` is intentionally a derived display/recovery projection on the existing assistant-message object. It is not a new source of truth, is not sent back to the backend, and does not alter the existing conversation/task ownership model. No layout was changed in this phase; the goal is correctness across reconnect and history reconstruction before introducing any new visualization.

Reference adaptation: this mirrors the DeepSeek Web projection-store rule that only newer session events advance a projection and that durable replay is the baseline for reconnect. OpenCode remains the reference for stable Message/Part identities, while LabexAgent keeps its task-scoped durable sequence and execution-fence requirements.

Files additionally changed by this follow-up:

- `frontend/src/composables/agentRunPartState.js`
- `frontend/src/composables/agentHistoryReducer.js`
- `frontend/src/composables/useAgentEventTimeline.js`
- `frontend/src/composables/useConversationState.js`
- `frontend/src/composables/useAgentTaskRuntime.js`
- `frontend/src/composables/agentRunPartState.test.mjs`
- `frontend/src/composables/agentHistoryReducer.test.mjs`
- `frontend/src/composables/useAgentEventTimeline.test.mjs`
