# Iteration 09 - Full durable projection recovery after JVM restart

## Objective

Close the verification gap left by Iteration 08. The previous browser run verified cross-JVM approval recovery, but `restartProjectionVerified` was false because it used the interaction handoff mode. This iteration runs the separate full projection handoff mode: compact a task, stop the backend JVM, start a fresh JVM, and verify that the same completed compaction epoch and Provider transcript projection are reconstructed from durable storage.

## Plan

1. Start the acceptance backend from the current compiled source.
2. Run the browser acceptance with `ACCEPTANCE_RESTART_HANDOFF_DIR`.
3. Stop the exact backend process after the compaction task writes `ready.json`.
4. Start a new acceptance backend JVM and signal continuation.
5. Verify the same task, compaction epoch, and Provider message count after restart.
6. Run the complete backend suite and frontend test/build gates.
7. Record evidence without mixing pre-existing worktree changes into the commit.

## Implementation changes

No production code was changed in this iteration. The existing acceptance harness already contained the full projection handoff path, but it had not been executed successfully against the current durable transcript and context-budget implementation. This iteration adds an evidence record and closes that verification gap.

The separate interaction handoff mode remains available and was not changed.

## Real cross-JVM acceptance evidence

Handoff directory:

`D:/LabexAgent/.codex-tmp/iteration09-restart-projection`

Acceptance command:

`cd D:/LabexAgent/frontend && npm.cmd run acceptance:browser`

Backend JVMs:

- first JVM PID: `31892`
- restarted JVM PID: `61512`
- port: `18081`
- local host restart timestamps: `2026-07-31T06:44:21.553+08:00` and `2026-07-31T06:46:01.533+08:00`
- UI: `http://127.0.0.1:13001`
- browser viewport: 1440x900 with `mobile: false`

The local host log date is July 31, 2026. The iteration plan date is July 30, 2026; the later host timestamp is recorded explicitly rather than being treated as a change to the plan date.

Acceptance run:

- runId: `5cbcbd6c1d5e40f6919b8c89997f546a`
- projectId: `141`
- compaction task prepared before restart: `802`
- desktop layout: true
- new/old conversation isolation: true
- refresh replay deduplication: true
- question reply component: true
- permission approval refresh recovery: true
- durable Provider messages: 3
- durable Provider parts: 1
- durable compaction: true
- compaction epoch: 1
- compaction Provider messages: 7
- `restartProjectionVerified`: true
- `restartInteractionVerified`: false because this run intentionally exercised the separate projection handoff mode
- static context blocker card: true
- completion evidence card: true
- unverified completion blocked: true
- browser console errors: 0
- network errors: 0

The acceptance script did not trust the browser alone: after the restart it queried the same task API and required the completed compaction epoch and the same number of durable Provider messages. The restarted backend was a new JVM process using the same compiled application classes and database.

## Automated verification

- `cd D:/LabexAgent/backend && mvn -q test`: passed; the existing backend suite completed with zero failures and zero errors.
- `cd D:/LabexAgent/frontend && npm test`: passed, 156/156.
- `cd D:/LabexAgent/frontend && npm run build`: passed, including the existing chunk-budget check.

## Result

This iteration proves that the durable compaction record and the Provider transcript projection survive a real backend JVM restart. It removes the previous `restartProjectionVerified: false` evidence gap.

## Remaining work

1. The old in-memory transcript compatibility path still needs shadow comparison, write monitoring, and deletion-condition verification before removal.
2. Fault-injection acceptance is still required for Provider stream interruption, tool timeout, compaction failure, missing compaction window, duplicate scheduler execution, and lease takeover.
3. The test suite still needs an inventory and deduplication pass. New fixes should continue to add small regression tests plus one system scenario rather than large numbers of repetitive unit tests.
4. No remote push was performed.

## Commit scope

Only this iteration document belongs to the commit. The existing modified acceptance configuration, `.codex-tmp` evidence directories, and other untracked planning documents were intentionally left untouched.\n