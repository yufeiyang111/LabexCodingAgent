# Static Context Admission Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Reject or reduce impossible model requests before provider invocation and show users exactly which context categories are reducible.

**Architecture:** A pure budget analyzer calculates immutable/static and reducible context separately. A deterministic admission decision chooses proceed, prune, compact, or block. Tool schemas are selected by mode and configured capabilities before token estimation.

**Tech Stack:** Java records/services, existing token estimator, Spring SSE, Vue context components.

### Task 1: Static/reducible budget model

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/runtime/ContextBudgetBreakdown.java`
- Create: `backend/src/main/java/com/labex/labexagent/runtime/ContextAdmissionDecision.java`
- Create: `backend/src/main/java/com/labex/labexagent/runtime/ContextAdmissionService.java`
- Create: `backend/src/test/java/com/labex/labexagent/runtime/ContextAdmissionServiceTest.java`

**Interfaces:**
- Categories: system prompt, tool schemas, workspace/project context, checkpoints, history, reserved output.
- Produces actions: `PROCEED`, `PRUNE`, `COMPACT`, `BLOCK_STATIC_OVERFLOW`.

- [x] Write table-driven failing tests for every decision boundary.
- [x] Implement pure calculation with no provider calls.
- [x] Verify static overflow cannot be mislabeled as compactable history.

### Task 2: Mode/capability-aware tool schema selection

**Files:**
- Modify: `backend/src/main/java/com/labex/labexagent/tool/ToolRegistry.java`
- Create: `backend/src/main/java/com/labex/labexagent/tool/ToolSelectionPolicy.java`
- Create: `backend/src/test/java/com/labex/labexagent/tool/ToolSelectionPolicyTest.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`

- [x] Test exact tool names exposed for build/plan/explore.
- [x] Exclude MCP/image/web capability schemas when unavailable, without changing enabled Web Search behavior.
- [x] Use one selected tool list for prompt text, schemas, permissions, and context estimation.
- [x] Verify no executable tool can be called unless its schema was selected.

### Task 3: Admission enforcement and SSE contract

**Files:**
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/ContextUsageSnapshot.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/ContextUsageEstimator.java`
- Modify related tests.

- [x] Add failing test proving provider is not invoked for static overflow.
- [x] Emit `CONTEXT_LIMIT_BLOCKED` with category breakdown and safe remediation.
- [x] Persist task as an environment/configuration blocker rather than code failure.
- [x] Keep prune/compact lifecycle events unchanged for reducible history.

### Task 4: Frontend context visualization

**Files:**
- Modify: `frontend/src/components/cloud/ContextUsageDialog.vue`
- Modify: `frontend/src/components/cloud/ContextUsageIndicator.vue`
- Modify: `frontend/src/composables/agentHistoryReducer.js`
- Add/update tests.

- [x] Render static vs reducible categories and reserved output.
- [x] Render a prominent blocked card with larger-model/fewer-tools remediation.
- [x] Ensure numberless ring accessibility remains intact.
- [x] Run browser acceptance with an intentionally undersized acceptance model.
