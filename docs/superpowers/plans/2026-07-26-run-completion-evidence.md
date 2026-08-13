# Agent Run Completion Evidence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Make `COMPLETED` a server-validated claim backed by persisted changed-file, verification, risk, and criteria evidence.

**Architecture:** Build evidence from existing pending changes, `t_agent_verification`, and run artifacts. Persist a bounded JSON artifact of type `completion_evidence`. A pure policy decides whether completion is allowed; the model cannot set the boolean itself.

**Tech Stack:** Java records, MyBatis-Plus, existing `AgentRunArtifactService`, Gson, SSE timeline rendering.

### Task 1: Evidence contract and policy

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/run/RunCompletionEvidence.java`
- Create: `backend/src/main/java/com/labex/labexagent/run/RunCompletionPolicy.java`
- Create: `backend/src/test/java/com/labex/labexagent/run/RunCompletionPolicyTest.java`

**Interfaces:**
- Evidence fields: changedFiles, successfulVerifications, failedVerifications, unresolvedRisks, criteria, satisfied.
- Policy inputs are server-owned records only.

- [x] Write failing tests for no-change informational tasks, changed-but-unverified tasks, failed verification, manual file verification, cancelled runs, and environment blockers.
- [x] Implement deny-by-default policy.
- [x] Bound every list and public string.

### Task 2: Evidence collection and persistence

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/run/RunCompletionEvidenceService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/run/AgentRunArtifactService.java`
- Create: `backend/src/test/java/com/labex/labexagent/run/RunCompletionEvidenceServiceTest.java`

- [x] Collect owned pending changes and verification records by task/project/student.
- [x] Persist deterministic JSON plus SHA-256 as `completion_evidence`.
- [x] Make repeated finalization idempotent for the same run version.
- [x] Never include command secrets or unbounded output.

### Task 3: Terminal completion gate

**Files:**
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentRunFinalizer.java` or `AgentLoopEngine.java` until extraction lands.
- Modify: `backend/src/main/java/com/labex/labexagent/service/AgentTaskService.java`
- Modify lifecycle/finalization tests.

- [x] Add failing test that a model final answer cannot complete an evidence-unsatisfied task.
- [x] Emit `COMPLETION_EVIDENCE` before `RUN_STATE_COMPLETED`.
- [x] Continue the run with actionable verification guidance when fixable.
- [x] Fail/pause explicitly when evidence cannot be satisfied.

### Task 4: API and timeline presentation

**Files:**
- Modify: `backend/src/main/java/com/labex/labexagent/controller/StudentAgentController.java`
- Modify: `frontend/src/composables/agentHistoryReducer.js`
- Create/modify completion evidence UI component and tests.

- [x] Expose owned task completion evidence without sensitive output.
- [x] Render completed, missing, failed, and risk sections in the timeline.
- [x] Ensure historical replay reconstructs the same card.
- [x] Add acceptance scenario proving an unverified edit cannot display successful completion.
