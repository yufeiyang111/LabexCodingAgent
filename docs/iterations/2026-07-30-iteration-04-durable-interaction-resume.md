# Iteration 04 - Durable interaction resolution and same-task resume

- **Date:** 2026-07-30
- **Status:** Completed
- **Baseline commit:** `98d9596 feat: persist context compaction epochs`
- **Scope:** approval/question/network interaction CAS, stable lifecycle transition keys, same-task resume scheduling, SSE replay ownership, frontend durable-task reattachment.

## Architecture ownership

1. `AgentRunInteraction` is the authority for a pending user decision and its terminal resolution.
2. `AgentTask` plus `AgentRunLifecycleService` is the authority for waiting/recovering/running state and execution epoch.
3. `AgentRunEvent` plus outbox is the replay transport; an SSE connection never owns task completion.
4. The frontend reconnects to the existing task and cursor after a decision; it must not create a new conversation turn to simulate resume.
5. In-memory permission waiters are compatibility accelerators only and cannot override the durable interaction row.

## Baseline evidence

Focused backend lifecycle/interaction tests and frontend interaction/task-runtime tests passed before new characterization work. The baseline does not yet prove late, contradictory or concurrent decisions are safe.

## Audit findings

### Critical: contradictory decisions are silently accepted

`AgentRunInteractionService.respond` returns any already-resolved row without checking whether the replayed decision matches the persisted terminal status. An `approved` row can therefore be returned to a later `rejected` request as though that request succeeded. The caller may render or schedule based on the new request while the database still contains the old fact.

### Critical: expired waiting decisions can win a race against the expiry scheduler

The response CAS currently checks only `status = waiting`. If `expires_time` is already in the past but `claimExpired` has not run yet, a late approval can still update the row. Expiration must be enforced in the decision transaction itself, not only by a background poller.

### High: generic lifecycle updates still hide occurrence ownership

The compatibility `AgentTaskService.updateTask` overload creates a random occurrence key. This avoids accidental key reuse exceptions but is not a retry-stable domain key. This iteration will keep compatibility callers intact while requiring approval/resume paths to use stable interaction-derived keys and documenting remaining migration call sites.

## Planned regression gates

- duplicate same decision is idempotent and does not write twice;
- contradictory second decision is rejected;
- expired waiting interaction cannot be approved;
- invalid terminal status is rejected at the persistence boundary;
- approval/rejection/cancel response schedules at most one same-task recovery;
- refresh/SSE reconnect replays the durable waiting or recovering state without requiring a new chat request;
- real browser fault injection covers decision submission followed by immediate stream disconnect and refresh.

## Verification record

The initial baseline was intentionally incomplete; the implementation and live acceptance are recorded below.


## Implemented convergence

### Durable interaction decision boundary

- `AgentRunInteractionService.respond` now validates terminal status compatibility, enforces expiration in the compare-and-set update, and treats an identical repeated decision as an idempotent replay.
- Question, permission and network decisions resume the same persisted task through `AgentRunResumeScheduler`; they do not create a replacement chat turn.
- `AgentTaskService.beginInteractionResume` emits `RUN_INTERACTION_RESUME_QUEUED` with the original `interactionId` and a stable interaction-derived transition key.
- The acceptance provider recognizes the persisted `Resolution status: approved/rejected` continuation markers so the system test does not incorrectly reissue a resolved permission request.

### Single frontend interaction projector

- `agentInteractionProjection.js` is the shared owner for attaching, de-duplicating and resolving question, permission and network cards.
- Live SSE, history replay and active-task snapshot recovery now use the same projection rules.
- `RUN_INTERACTION_RESUME_QUEUED` resolves every duplicate card for the same durable request while preserving unrelated interactions.

### Recovery snapshot race correction

A real browser run exposed one remaining race after the first implementation: the decision API could return after the task entered `recovering` while the interaction row still temporarily reported `waiting`. The frontend then reconciled that stale snapshot by re-attaching the old question card and started from a cursor that had already passed `RUN_INTERACTION_RESUME_QUEUED`.

`useAgentTaskRuntime.reconcileRecoveredToolCalls` now treats `AgentTask.status` as authoritative. When the task is no longer `waiting_user` or `waiting_approval`, it resolves an existing matching interaction projection as `resuming` instead of re-attaching a new waiting card. A regression test covers refresh/recovery during this transition, and the browser acceptance test confirms the question card disappears after submission while the same task continues.

### Multi-instance SSE correctness

The browser failure was reproduced with two backend JVMs sharing one database. The global outbox row could be claimed by the JVM that did not own the browser connection. Because the default sink publishes only into that JVM's in-memory application event bus, the connected JVM permanently missed events such as `COMPACTION_COMPLETED`. This also explains intermittent missing approval components and UI updates that appeared only after refresh.

`AgentTaskEventSubscriptionService` now:

1. replays from the durable `AgentRunEvent` cursor when a subscriber connects;
2. polls the durable event log for every local subscriber (`labex.agent.task-event-poll-ms`, default 250 ms);
3. treats outbox delivery as a low-latency hint rather than the correctness boundary;
4. detects sequence gaps and catches up from the database before delivering a newer event;
5. advances the subscriber cursor only after the SSE send succeeds.

## Regression evidence

- Red test: durable polling API was absent, so a subscriber could not recover events whose outbox notification was consumed by another JVM.
- Green backend tests: `AgentTaskEventSubscriptionServiceTest` covers cross-instance outbox loss and gap-before-newer-event ordering.
- Green frontend tests cover duplicate interaction cards in history replay, live resume and active-task recovery.
- Real Chromium acceptance on the isolated UI/backend verified desktop layout, conversation isolation, refresh replay, question reply, permission approval plus refresh recovery, durable compaction, static context blocker and completion evidence with zero browser console or network errors.

## Final verification

All commands below were executed against the current worktree after the final source changes:

- Backend: `cd D:\LabexAgent\backend && mvn test` — **782 tests, 0 failures, 0 errors, 8 skipped; BUILD SUCCESS**.
- Frontend: `cd D:\LabexAgent\frontend && npm test` — **153 tests, 0 failures**.
- Frontend build: `npm run build` — **success**; chunk budgets passed (`CloudWorkspace` 1,446,057 / 1,500,000 bytes, `index` 1,259,442 / 1,300,000 bytes, `TerminalPanel` 380,367 / 400,000 bytes).
- Acceptance unit gate: `npm run test:acceptance:unit` — **9 tests, 0 failures**.
- Source encoding gate: `node --test src/utils/sourceEncoding.test.mjs` — **1 test, 0 failures**.
- Real browser system acceptance: `npm run acceptance:browser` against isolated Vite `http://127.0.0.1:13001` and backend `http://127.0.0.1:18080` — **passed** with run id `380d6e4db57a4aabafa2dc5961ad85e3`; desktop layout, conversation isolation, refresh replay deduplication, question reply, permission refresh recovery, durable provider transcript, durable compaction, static context blocker, completion evidence and unverified-completion blocking all passed; browser console errors **0**, network errors **0**.
- The browser run reported `restartProjectionVerified: false`; this iteration proves immediate reply/reconnect and refresh replay, but does not claim a second backend-process handoff in the same run.
- `git diff --check` passed.

The Maven/Spring test process printed `2026-07-31` timestamps because the local Java process clock is ahead of the task date; the iteration date remains **2026-07-30** and this timestamp discrepancy is not used as product evidence.

## Commit boundary

This iteration is ready for a focused local Git commit. Temporary `.codex-tmp/` artifacts and unrelated pre-existing worktree files are intentionally not part of the iteration commit and must remain reported separately.
