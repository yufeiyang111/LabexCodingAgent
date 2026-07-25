# Agent Task Timing Design

## Goal
Persist reliable duration metrics for every Agent task from the server-side receipt of a user stream request through the end of a terminal Agent response.

## Scope
- Use the existing `t_agent_task` row as the per-user-message execution record.
- Persist both end-to-end elapsed time and active execution time.
- Preserve timing across pause/resume flows for permissions and user questions.
- Finalize durations for completed, failed, and cancelled tasks.

## Data model
Add these nullable/additive fields to `t_agent_task`:

- `submitted_at DATETIME(3)`: server receipt time for the initial stream request.
- `started_at DATETIME(3)`: first time an Agent worker begins executing the task.
- `active_segment_started_at DATETIME(3)`: start of the currently active execution segment; cleared when paused or terminal.
- `finished_at DATETIME(3)`: time after the terminal output sequence is emitted.
- `elapsed_ms BIGINT`: end-to-end duration from `submitted_at` to `finished_at`, including queueing and human wait time.
- `active_elapsed_ms BIGINT`: accumulated active Agent duration, excluding waiting for approval or user input.

Existing records remain valid with null timing values. New columns are added both to `schema.sql` and `AdditiveSchemaMigrator` for upgrades.

## Runtime lifecycle
1. `AgentLoopEngine.start(...)` overwrites an internal request timestamp using server time before queue submission.
2. A new task stores that timestamp as `submitted_at`; a resumed task retains its existing submission timestamp.
3. After a task is available to the worker, `AgentTaskService.startTiming(...)` sets `started_at` once and opens an active segment.
4. `AgentTaskService.updateTask(...)` closes the active segment before transitions to `waiting_approval` or `waiting_user`.
5. The `runLoop(...)` finally block calls `finishTimingIfTerminal(...)`, after terminal SSE output has been emitted. It only seals a task whose persisted state is `completed`, `failed`, or `cancelled`, so pause/resume tasks remain open.

## API and presentation
`GET /student/projects/{projectId}/agent/tasks` already serializes `AgentTask`, so the new fields become available without a new endpoint. This change stores and exposes the measurements; adding a dedicated UI duration badge is intentionally deferred until there is a task-history surface that consumes this API.

## Verification
- Unit tests cover initial timing creation, idempotent start, pause accumulation, and terminal finalization.
- Schema migrator test coverage verifies additive definitions for the new columns.
- Run focused Maven tests and the frontend production build only if frontend files change.
