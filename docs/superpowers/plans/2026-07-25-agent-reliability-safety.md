# Agent Reliability and Safety Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent agent cancellation crashes, command-policy bypasses, false verification success, and shared-workspace concurrency conflicts.

**Architecture:** Model cancellation as a durable two-phase lifecycle transition, make execution-code capabilities subject to the same policy boundary as command tools, store trusted verification evidence instead of a global counter, and admit foreground tasks to a project-level workspace lease before model execution. Follow the OpenCode-style separation of session abort, permission events, and isolated worktrees for parallel execution.

**Tech Stack:** Spring Boot 3, Java 17, MyBatis-Plus, Vue 3, Maven, JUnit 5.

## Global Constraints

- Preserve existing task data and use additive schema changes only.
- Do not permit a tool to bypass server-owned approval or command policy through an interpreter subprocess.
- Do not report test/build success without a trusted process result with exit code zero.
- Preserve existing background worktree behavior; serialize only tasks sharing a checkout.
- Keep all lifecycle transitions CAS/idempotent.

---

### Task 1: Durable two-phase cancellation

**Files:**
- Modify: `backend/src/main/java/com/labex/labexagent/run/AgentRunLifecycleService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/service/AgentTaskService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/controller/StudentAgentController.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- Modify: `backend/src/main/java/com/labex/labexagent/diff/DiffService.java`
- Test: lifecycle/task/controller cancellation regression tests

- [ ] Add idempotent request-cancellation and finalize-cancellation lifecycle APIs.
- [ ] Route every normal cancellation through `CANCELLING` before `CANCELLED`.
- [ ] Make the interrupt path persist cancellation intent before signalling the in-memory token.
- [ ] Verify concurrent cancel/complete paths never perform `RUNNING -> CANCELLED`.

### Task 2: Unified execution policy and trusted verification evidence

**Files:**
- Modify: `backend/src/main/java/com/labex/labexagent/tool/impl/ExecuteCodeTool.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- Modify: `backend/src/main/java/com/labex/labexagent/service/AgentContextOrchestrator.java`
- Modify: `backend/src/main/java/com/labex/labexagent/tool/impl/CreatePlanTool.java`
- Test: execute-code policy and verification-plan regression tests

- [ ] Block or require approval for interpreter code that launches child processes or uses network execution facilities.
- [ ] Do not count arbitrary `execute_code` success as a build/test verification.
- [ ] Require trusted direct process evidence for test/build plan completion.
- [ ] Ensure nested Maven projects use `run_tests` and its resolved working directory.

### Task 3: Project checkout admission

**Files:**
- Modify: task admission/orchestration services and additive schema migrator.
- Modify: run recovery and background worktree orchestration.
- Test: two foreground tasks for one checkout; background worktree parallelism.

- [ ] Add a durable project-checkout lease at task admission.
- [ ] Queue or expose waiting-workspace status before any model call.
- [ ] Preserve background parallelism only through distinct worktrees.

### Task 4: Recoverable environment blockers

**Files:**
- Modify: run state model, lifecycle service, tool result classification, UI status handling.
- Test: DNS/package-registry failures become recoverable environment blockers.

- [ ] Classify DNS/network dependency failures without editing project dependency configuration.
- [ ] Persist diagnostics and allow retry after an environment recovery signal.
- [ ] Prevent environment-blocked runs from being reported as verified/completed.
