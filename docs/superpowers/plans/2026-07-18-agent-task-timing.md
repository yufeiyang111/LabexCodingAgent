# Agent Task Timing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Persist end-to-end and active Agent execution durations per `t_agent_task` from request receipt through terminal output.

**Architecture:** Extend the existing Agent task entity and additive schema migration. Keep time accounting in `AgentTaskService`, use server-owned request receipt time in `AgentLoopEngine`, pause active timing on durable user waits, and close timing in the loop's finalization path only for terminal task states.

**Tech Stack:** Java 17, Spring Boot 3, MyBatis-Plus, MySQL, JUnit 5, Mockito.

## Global Constraints

- Do not overwrite or revert unrelated existing working-tree changes.
- Use server timestamps; never accept a client-provided duration as authoritative.
- Keep schema changes additive and add every new column to `AdditiveSchemaMigrator`.
- Do not add dependencies or alter public authentication behavior.

---

### Task 1: Persist task timing fields

**Files:**
- Modify: `backend/src/main/resources/sql/schema.sql`
- Modify: `backend/src/main/java/com/labex/config/AdditiveSchemaMigrator.java`
- Modify: `backend/src/main/java/com/labex/entity/AgentTask.java`

**Interfaces:**
- Produces `AgentTask` accessors for `submittedAt`, `startedAt`, `activeSegmentStartedAt`, `finishedAt`, `elapsedMs`, and `activeElapsedMs`.

- [x] Add nullable millisecond-precision timing columns to the task table definition.
- [x] Register the same fields as additive migrations for existing databases.
- [x] Map all fields in the MyBatis-Plus entity.

### Task 2: Centralize timing accounting

**Files:**
- Modify: `backend/src/main/java/com/labex/labexagent/service/AgentTaskService.java`
- Test: `backend/src/test/java/com/labex/labexagent/service/AgentTaskServiceTimingTest.java`

**Interfaces:**
- Consumes `AgentTask` timing fields.
- Produces `createTask(..., LocalDateTime submittedAt)`, `startTiming(Long taskId)`, `pauseTiming(Long taskId)`, and `finishTimingIfTerminal(Long taskId)`.

- [x] Write unit tests for initial submission time, active-segment accumulation, pause idempotency, and terminal-only finalization.
- [x] Extend task creation with a server-provided submission timestamp while preserving existing overloads.
- [x] Implement idempotent active-segment start/pause/finalization helpers.
- [x] Pause timing before user-wait state transitions.
- [x] Run focused timing tests.

### Task 3: Wire timing into the Agent execution lifecycle

**Files:**
- Modify: `backend/src/main/java/com/labex/labexagent/dto/AgentStreamRequest.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- Test: `backend/src/test/java/com/labex/labexagent/runtime/AgentLoopEngineTimingTest.java` if constructor/method seams permit; otherwise validate service-level behavior and compile the runtime path.

**Interfaces:**
- `AgentLoopEngine.start(...)` owns and overwrites `request.submittedAt`.
- New tasks receive that time; resumed tasks retain their original timing baseline.

- [x] Capture server request receipt time before queue submission.
- [x] Pass the timestamp when creating a task.
- [x] Open an active segment after new or resumed task resolution.
- [x] In `runLoop(...)` finalization, seal timing only after a terminal status has been persisted and output paths have returned.
- [x] Run timing and runtime-focused tests.

### Task 4: Verify upgrade and build safety

**Files:**
- Modify: `docs/superpowers/specs/2026-07-18-agent-task-timing-design.md` only if behavior changes during implementation.

- [x] Run Maven tests for the service, lifecycle, and schema migration coverage.
- [x] Run `mvn test` if focused tests pass and existing suite duration is acceptable.
- [x] Run `mvn -DskipTests compile` to validate production compilation.
- [x] Inspect the targeted diff and report unrelated working-tree state separately.
