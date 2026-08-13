# Structured Conversation Checkpoint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve actionable task state during AgentLoopEngine context-overflow compaction without making another LLM call.

**Architecture:** Add a pure runtime compactor that converts historical message pairs into a bounded structured checkpoint. `AgentLoopEngine` invokes it when the provider reports context overflow and retains its existing aggressive-trim fallback. The compactor receives the original request and `AgentContext` so task and plan state never depend on heuristic extraction.

**Tech Stack:** Java 17, Spring Boot runtime package, JUnit 5.

## Global Constraints

- Do not change database schema or durable workspace-memory behavior.
- Do not add dependencies or make provider/LLM calls during compaction.
- Preserve the most recent three turns exactly.
- Keep the change limited to checkpoint compaction and focused tests.
- Do not commit or push without explicit user request.

---

### Task 1: Define the checkpoint compactor with tests

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/runtime/ConversationCheckpointCompactor.java`
- Create: `backend/src/test/java/com/labex/labexagent/runtime/ConversationCheckpointCompactorTest.java`

**Interfaces:**
- Consumes: mutable provider message list, original user request, `AgentContext`.
- Produces: `compact(List<Map<String,Object>>, String, AgentContext)` mutates the list only when historical turns can be replaced by a shorter checkpoint.

- [ ] Write failing tests for task/plan, files, verification, failures, prior checkpoint merge, recent-turn preservation, and size reduction.
- [ ] Run the focused test and confirm it fails because the class is absent.
- [ ] Implement a bounded deterministic compactor with categorized, deduplicated bullets.
- [ ] Re-run the focused test.

### Task 2: Wire overflow recovery to the compactor

**Files:**
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- Test: `backend/src/test/java/com/labex/labexagent/runtime/AgentLoopEngineContextBudgetTest.java`

**Interfaces:**
- Consumes: the compactor from Task 1 and existing `request.getMessage()`/`ctx` runtime state.
- Produces: `CHECKPOINT_COMPACTION` event using a structured checkpoint, with unchanged aggressive-trim fallback.

- [ ] Replace the misleading inline summary method/call with the compactor.
- [ ] Keep context-progress detection and fallback behavior intact.
- [ ] Run existing context-budget regression coverage.

### Task 3: Verify the scoped change

**Files:**
- Verify: Task 1/2 files only.

- [ ] Run focused Maven tests.
- [ ] Run `mvn -f backend/pom.xml -DskipTests compile`.
- [ ] Run `git diff --check` against the files touched in this work.
- [ ] Inspect the scoped diff and report existing unrelated workspace changes separately.
