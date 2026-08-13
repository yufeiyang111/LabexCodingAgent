# OpenCode-inspired Agent runtime completion plan

## Task 1: Reference study and boundary freeze
- [x] Read OpenCode session run-state, status, compaction, permission/question and frontend session cache implementations.
- [x] Document mechanisms to adopt and framework-specific mechanisms not to copy.

## Task 2: Extract frontend task runtime
- [x] Add tests for stale recovery rejection, project/task-scoped cursors and explicit invalidation.
- [x] Create `frontend/src/composables/useAgentTaskRuntime.js`.
- [x] Move recovery, subscription, cursor and timing logic out of `CloudWorkspace.vue`.
- [x] Keep rendering/event reduction callbacks in the page.
- [x] Run focused and full frontend tests/build.

## Task 3: Extract context-management controller
- [x] Add tests for enqueue-only semantics and task-ID event ownership.
- [x] Move manual compaction orchestration and labels out of `CloudWorkspace.vue`.
- [x] Keep timeline rendering declarative.

## Task 4: Harden backend checkpoints
- [x] Add failing tests for atomic replacement, bounded payloads and checkpoint tag neutralization.
- [x] Implement safe temporary write plus atomic/fallback move.
- [x] Bound checkpoint note/result fields and render them without allowing structural tag injection.
- [x] Run checkpoint and loop regression tests.

## Task 5: Runtime restart and live acceptance
- [x] Stop only the identified stale backend PID.
- [x] Package and start the current JAR hidden.
- [x] Verify health/auth with a generated test account.
- [x] Create a disposable project and exercise conversation isolation and compaction task events.
- [x] Verify process/runtime version evidence and clean disposable data where safe.

## Task 6: Final gates
- [x] Run frontend full tests and build.
- [x] Run backend focused and full tests plus package.
- [x] Run `git diff --check`.
- [x] Audit remaining large-file and ownership risks without claiming unverified behavior.

## Live acceptance evidence
- Browser acceptance confirmed that direct SSE events render without console errors and durable cursors cross the extracted runtime boundary.
- New-session ownership changes hard-reset the rendered timeline, so the previous conversation is absent from the first rendered frame.
- Question and command-approval continuations survived a backend restart, retained their original task IDs, and completed under real checkout contention.
- The normal runtime profile does not register the acceptance-only scripted provider.
