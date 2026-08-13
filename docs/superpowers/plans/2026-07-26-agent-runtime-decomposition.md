# Agent Runtime Decomposition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Remove critical business ownership from `CloudWorkspace.vue` and `AgentLoopEngine.java` while preserving current APIs and behavior.

**Architecture:** Extract one responsibility at a time behind callback-based composables and package-private backend services. The state machine and lifecycle service remain the only durable run-state authority.

**Tech Stack:** Vue 3 Composition API, Node test runner, Java 21, Spring Boot, JUnit 5, Mockito.

## Global Constraints

- No broad page or engine rewrite.
- Each extraction must reduce source size and leave focused tests green before the next extraction.
- Extracted services may not update task tables directly; they call `AgentTaskService`/`AgentRunLifecycleService`.
- Public HTTP/SSE payloads remain backward compatible.

### Task 1: Move CloudWorkspace styles out of the SFC

**Files:**
- Create: `frontend/src/styles/cloud-workspace.scss`
- Modify: `frontend/src/views/CloudWorkspace.vue`
- Test: `frontend/src/views/agentStreamIntegration.test.mjs`

- [x] Add a source test requiring external style ownership and absence of a giant inline style block.
- [x] Move the existing SCSS byte-for-byte to the external file.
- [x] Use `<style lang="scss" src="@/styles/cloud-workspace.scss"></style>`.
- [x] Run frontend tests and build; visually smoke the workspace.

### Task 2: Extract Agent event timeline controller

**Files:**
- Create: `frontend/src/composables/useAgentEventTimeline.js`
- Create: `frontend/src/composables/useAgentEventTimeline.test.mjs`
- Modify: `frontend/src/views/CloudWorkspace.vue`
- Modify: `frontend/src/composables/agentHistoryReducer.js`

**Interfaces:**
- Produces: `handleAgentEvent(event, assistantMessage): void`
- Consumes: cursor recorder, context-management reducer, interaction reconciliation, token state, render scheduler.

- [x] Write failing tests for SESSION, deltas, tool lifecycle, question, command approval, compaction, ERROR, FINAL, and DONE.
- [x] Move event dispatch without moving rendering templates.
- [x] Ensure direct/replayed events use the same handler and cursor ownership.
- [x] Run focused tests, full frontend tests, build, and browser acceptance.

### Task 3: Extract extension configuration controller

**Files:**
- Create: `frontend/src/composables/useAgentExtensions.js`
- Create: `frontend/src/composables/useAgentExtensions.test.mjs`
- Modify: `frontend/src/views/CloudWorkspace.vue`

**Interfaces:**
- Owns: Skill/MCP loading, form state, upload parsing, save/test/toggle/delete.
- Does not own: extension panel rendering.

- [x] Add behavior tests for malformed skill metadata, upload boundaries, MCP test error, and stale request handling.
- [x] Move extension API/form logic.
- [x] Run focused/full tests and build.

### Task 4: Extract workspace files controller

**Files:**
- Create: `frontend/src/composables/useWorkspaceFiles.js`
- Create: `frontend/src/composables/useWorkspaceFiles.test.mjs`
- Modify: `frontend/src/views/CloudWorkspace.vue`

**Interfaces:**
- Owns: file tree paging, child loading, tabs, open/save/create/rename/delete, stale request guards.
- Does not own: Monaco rendering or dialogs.

- [x] Add failing tests for stale open responses, failed tree pages, dirty tab close, and rename/delete ownership.
- [x] Move workspace-file state and API calls.
- [x] Run focused/full tests, build, and browser file smoke.

### Task 5: Extract backend model-turn executor

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/runtime/AgentModelTurnExecutor.java`
- Create: `backend/src/test/java/com/labex/labexagent/runtime/AgentModelTurnExecutorTest.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`

**Interfaces:**
- Produces: `ModelTurnResult execute(ModelTurnRequest request)`.
- Owns: provider stream timeout/cancellation, chunk assembly, native tool-call identity, usage extraction, recoverable provider failure classification.
- Does not own: task state or tool execution.

- [x] Characterize current stream behavior with failing tests around the new interface.
- [x] Extract streaming and provider error helpers.
- [x] Keep SSE callback semantics unchanged.
- [x] Run focused tests and full Maven suite.

### Task 6: Extract backend tool-turn executor

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/runtime/AgentToolTurnExecutor.java`
- Create: `backend/src/test/java/com/labex/labexagent/runtime/AgentToolTurnExecutorTest.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`

**Interfaces:**
- Produces: `ToolTurnResult execute(ToolTurnRequest request)`.
- Owns: mode gate, command classification, permission evaluation, tool timeout, snapshots/diff, post-edit hook, metrics.
- Does not own: pause/final run transitions.

- [x] Add tests for deny/ask/allow, command approval, timeout, snapshots, and hook failure.
- [x] Extract one path at a time while retaining event ordering.
- [x] Run focused and full backend tests.

### Task 7: Extract interaction pauser and finalizer

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/runtime/AgentInteractionPauser.java`
- Create: `backend/src/main/java/com/labex/labexagent/runtime/AgentRunFinalizer.java`
- Create tests for both.
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`

- [x] Test question, permission, command approval, workspace/environment blocker pause payloads.
- [x] Test completed/failed/cancelled terminal event ordering and checkpoint cleanup.
- [x] Extract without allowing direct mapper writes.
- [x] Require `AgentLoopEngine.java` to become orchestration-focused and materially smaller.
