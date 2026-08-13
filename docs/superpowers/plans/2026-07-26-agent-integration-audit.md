# Agent Engineering Integration and Audit Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:verification-before-completion for the final audit. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Integrate P0-P3 safely, preserve unrelated work, and prove the final runtime against every requirement.

**Architecture:** Use a requirement-to-evidence matrix. Run focused gates after each task and broad gates only after all slices are integrated. Normal runtime, not the acceptance profile, is the final running state.

### Task 1: Worktree ownership audit

- [x] Record branch, recent commits, tracked/untracked files, and running PIDs before changes.
- [x] Classify every changed file by P0/P1/P2/P3/unrelated.
- [x] Preserve `frontend/test-output.txt` unless the user explicitly authorizes deletion.
- [x] Run `git diff --check` after every phase.

### Task 2: Focused verification matrix

- [x] P0: acceptance helper tests plus full backend/browser harness.
- [x] P1: composable/service focused tests plus size/responsibility assertions.
- [x] P2: budget and tool-selection tests plus undersized-model browser case.
- [x] P3: completion policy/service/finalizer tests plus unverified-edit acceptance case.

### Task 3: Broad gates

- [x] Run `mvn -q test` and aggregate Surefire failures/errors/skips.
- [x] Stop only the identified backend PID, then run `mvn -q -DskipTests package`.
- [x] Start current normal-profile JAR hidden and verify command line/class/profile.
- [x] Run `npm test`.
- [x] Run `npm run build` and record chunk budgets.
- [x] Run complete opt-in acceptance suite.

### Task 4: Runtime and security audit

- [x] Confirm normal provider list excludes `acceptance_scripted`.
- [x] Confirm no private/loopback URL policy was weakened.
- [x] Confirm Web Search/MCP excluded scope is unchanged.
- [x] Inspect logs for secrets, state-transition errors, scheduled-task errors, and stale classes.
- [x] Confirm disposable users/projects/configs/workspaces/scripts are removed where safe.

### Task 5: Final completion audit

- [x] Map every master-plan requirement to current source/test/runtime evidence.
- [x] Treat missing or indirect evidence as incomplete and continue work.
- [x] Report remaining large-file debt honestly.
- [x] Do not commit, push, reset, or clean without explicit user instruction.
