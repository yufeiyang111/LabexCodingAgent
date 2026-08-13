# Agent Stability Boundaries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repair conversation isolation, task-scoped recovery, strict resume queue handling, fail-closed Agent modes, and user-visible encoding corruption.

**Architecture:** Conversation selection uses an explicit generation guard and transport detachment. Runtime checkpoint state is persisted as task-scoped JSON and restored into `AgentContext`; new turns no longer import arbitrary project run logs. Mode validation and queue failure handling are enforced at multiple layers.

**Tech Stack:** Vue 3 composables, Node test runner, Spring Boot 3, Java 17+, MyBatis-Plus, Gson, JUnit 5, Mockito.

---

### Task 1: Frontend conversation ownership

**Files:**
- Create: `frontend/src/composables/conversationSelectionGuard.js`
- Create: `frontend/src/composables/conversationSelectionGuard.test.mjs`
- Modify: `frontend/src/composables/useAgentStream.js`
- Modify: `frontend/src/views/CloudWorkspace.vue`
- Modify: `frontend/src/views/agentStreamIntegration.test.mjs`

- [x] Write a failing unit test proving an invalidated startup selection cannot apply.
- [x] Add a generation guard with `capture()`, `invalidate()`, and `isCurrent()`.
- [x] Add a transport-only `disconnect()` method that aborts fetch/subscription without calling interrupt.
- [x] Invalidate startup auto-selection on new/explicit conversation intent.
- [x] Clear context snapshot, prediction, dialog state, task recovery generations, and detach old transport when creating a new session.
- [x] Run `node --test src/composables/conversationSelectionGuard.test.mjs src/views/agentStreamIntegration.test.mjs`.

### Task 2: Task-scoped structured checkpoint

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/runtime/AgentCheckpointStore.java`
- Create: `backend/src/test/java/com/labex/labexagent/runtime/AgentCheckpointStoreTest.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentContext.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`

- [x] Write failing tests proving checkpoints are isolated by conversation/task and restore plan/verification state.
- [x] Add explicit execution-state restoration methods to `AgentContext`.
- [x] Persist checkpoint JSON under `.labex/agent-checkpoints/<conversation>/<task>.json`.
- [x] Load/inject checkpoint only for `resumeTaskId`; do not inject arbitrary latest run logs into normal turns or previews.
- [x] Restore structured state before the resumed model call.
- [x] Run `mvn -q '-Dtest=AgentCheckpointStoreTest,AgentLoopEngineNextPreviewTest,AgentLoopEngineContextBudgetTest' test`.

### Task 3: Strict continuation enqueue

**Files:**
- Modify: `backend/src/main/java/com/labex/labexagent/run/AgentRunResumeScheduler.java`
- Modify: `backend/src/main/java/com/labex/labexagent/run/AgentRunRetryScheduler.java`
- Modify: `backend/src/test/java/com/labex/labexagent/run/AgentRunResumeSchedulerTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/run/AgentRunRetrySchedulerTest.java`

- [x] Write failing tests requiring the strict `resume(..., true)` overload and terminal failure on queue rejection.
- [x] Use strict enqueue for interaction and retry continuation.
- [x] Persist an explicit failed state when interaction continuation cannot be queued.
- [x] Run the two scheduler test classes.

### Task 4: Fail-closed Agent mode

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/runtime/AgentMode.java`
- Create: `backend/src/test/java/com/labex/labexagent/runtime/AgentModeTest.java`
- Create: `backend/src/test/java/com/labex/labexagent/tool/ToolRegistryModeTest.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- Modify: `backend/src/main/java/com/labex/labexagent/tool/ToolRegistry.java`
- Modify: `backend/src/main/java/com/labex/labexagent/permission/DefaultPermissionRuleset.java`

- [x] Write failing tests for mixed-case normalization and unknown-mode rejection/denial.
- [x] Normalize mode once at runtime entry and preview entry.
- [x] Make unknown modes expose no tools and receive a default DENY rule.
- [x] Run mode and permission-focused tests.

### Task 5: Encoding regression gate

**Files:**
- Create: `frontend/src/utils/sourceEncoding.test.mjs`
- Modify: affected production Java/Vue/JS files containing literal `???` user-facing strings.

- [x] Write a failing source scan for three consecutive ASCII question marks in production source.
- [x] Replace corrupted user-visible strings with correct Chinese text without changing protocol identifiers.
- [x] Run the encoding test and full frontend test suite.

### Task 6: Asynchronous manual compaction ownership

**Files:**
- Modify: rontend/src/composables/agentHistoryReducer.js
- Modify: rontend/src/views/CloudWorkspace.vue
- Modify: rontend/src/composables/agentHistoryReducer.test.mjs
- Modify: rontend/src/composables/useConversationState.test.mjs
- Modify: rontend/src/views/contextManagementPresentation.test.mjs

- [x] Treat the compact endpoint response as a queued task, not a completed compaction.
- [x] Subscribe to the returned task ID and let durable events complete or fail the timeline item.
- [x] Correlate compaction lifecycle events by task ID so concurrent tasks cannot update each other.
- [x] Run the focused compaction and context-management tests.

### Task 7: Full verification

- [x] Run `npm test` from `frontend`.
- [x] Run `npm run build` from `frontend`.
- [x] Run `mvn -q test` from `backend`.
- [x] Run `mvn -q -DskipTests compile` from `backend`.
- [x] Run `git diff --check` and inspect scoped diffs.
- [x] Report unrelated pre-existing modified/untracked files separately; do not commit or clean them.