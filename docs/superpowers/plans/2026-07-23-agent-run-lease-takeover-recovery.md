# Agent Run Lease Takeover and Durable Cancellation Plan

> **For agentic workers:** Implement this plan in order. Preserve unrelated working-tree changes. Check off an item only after its focused verification passes.

**Goal:** Convert expired Agent execution leases from a conservative failure path into a fenced, checkpoint-based recovery path; allow users to cancel persisted retrying tasks; and make upgrade-time lookup indexes additive for existing databases.

**Architecture:** Add an observable `RECOVERING` state. The recovery service claims an expired/no-owner task through the existing execution lease epoch, records an event, and resumes the task through `AgentLoopEngine.resume(...)` using the durable conversation and task metadata. A durable cancellation service handles `RETRYING` even without an in-memory session. The additive migrator creates missing task lookup indexes idempotently.

**Tech Stack:** Java 17, Spring Boot 3, MyBatis-Plus, MySQL, JUnit 5, Mockito.

## Global Constraints

- Keep all schema changes additive; add columns and indexes to both `schema.sql` and `AdditiveSchemaMigrator`.
- Use task owner, project, and execution epoch as server-side authority. Never trust a client-supplied owner or epoch.
- A task can only be resumed after an expired/no-owner lease is claimed by the recovering instance.
- Do not resume waiting interactions automatically; their existing interaction workflow remains authoritative.
- Do not alter unrelated authentication or model-provider behavior.

---

### Task 1: Persist recovery state and indexes

**Files:**
- Modify: `backend/src/main/java/com/labex/labexagent/run/AgentRunState.java`
- Modify: `backend/src/main/java/com/labex/labexagent/run/AgentRunStateMachine.java`
- Modify: `backend/src/main/resources/sql/schema.sql`
- Modify: `backend/src/main/java/com/labex/config/AdditiveSchemaMigrator.java`

- [x] Add `RECOVERING` and only legal transitions for takeover/retry/cancellation.
- [x] Add idempotent upgrade indexes for retry scans and lease-expiry scans.
- [x] Cover state transitions and migration behavior with tests.

### Task 2: Claim expired tasks and resume from durable context

**Files:**
- Modify: `backend/src/main/java/com/labex/labexagent/run/AgentRunExecutionLeaseService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/run/AgentRunRecoveryService.java`
- New: `backend/src/main/java/com/labex/labexagent/run/AgentRunTakeoverScheduler.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java` only if a resume guard is needed.

- [x] Add a CAS claim for expired/no-owner leases that increments the fencing epoch.
- [x] Move claimed tasks into `RECOVERING`, append a durable takeover event, and resume through `AgentLoopEngine.resume(...)`.
- [x] Permit only known-safe task states (`queued`, `preparing`, `running`) to enter automatic takeover.
- [x] Keep waiting and retrying paths on their dedicated schedulers.
- [x] Fail a takeover only after resume enqueue or durable-context validation fails.

### Task 3: Persisted cancellation for retrying tasks

**Files:**
- Modify: `backend/src/main/java/com/labex/labexagent/service/AgentTaskService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/controller/StudentAgentController.java`
- Modify: `backend/src/main/java/com/labex/labexagent/run/AgentRunLifecycleService.java` if the existing conditional transition API is insufficient.

- [x] Add an ownership-checked `cancelScheduledRetry(...)` operation.
- [x] Clear `next_retry_at` atomically when `RETRYING -> CANCELLED` succeeds.
- [x] Use the persisted path only when no active in-memory session can receive the interrupt.
- [x] Preserve existing active-session cancellation behavior.

### Task 4: Tests and verification

**Files:**
- Add/modify tests under `backend/src/test/java/com/labex/labexagent/run/`, `service/`, `controller/`, and `config/`.

- [x] Test takeover refusal for an unexpired foreign lease.
- [x] Test expiry claim increments epoch and schedules exactly one resume.
- [x] Test restart recovery does not fail a task that has been safely claimed for takeover.
- [x] Test a retrying task can be cancelled without an active session.
- [x] Run focused tests, `mvn -q test`, and `mvn -q package -DskipTests`.
