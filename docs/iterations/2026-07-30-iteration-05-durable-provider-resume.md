# Iteration 05 - Durable Provider Resume Protocol

- Date: 2026-07-30
- Status: Completed
- Scope: resume after question/permission/network interaction; strict durable Provider projection; compaction-aware resume

## 1. Problem reproduced

The first real browser run after enabling strict durable transcript projection failed after answering the question interaction. The task stopped with:

`Invalid Provider transcript: tool call has no result: acceptance-question`

This was not a frontend-only problem. The resumed task restored an assistant `tool_calls` message whose tool was paused for interaction, but the resume path did not yet add a protocol-correct `role=tool` message. The former memory fallback hid this state mismatch and allowed the architecture violation to continue.

## 2. Changes

1. `AgentStreamRequest` now carries `resumeInteractionId`.
2. `AgentRunResumeScheduler` propagates the exact durable interaction ID into the same-task continuation.
3. `AgentRunInteractionService.findById` reads the persisted interaction used by the resume path.
4. `AgentRunTranscriptService` adds a resume-only projection that preserves the open assistant tool batch, derives the unmatched `tool_call_id`, and creates one durable `role=tool` result from the persisted interaction response.
5. `AgentLoopEngine` appends that result before the continuation user message and fails closed on durable transcript restore errors during resume.
6. `AgentProviderMessageProjector` and `AgentCompactionService` support a copy-only intermediate resume projection. Strict protocol validation runs after the result is appended, not before.
7. Normal Provider requests remain strict: durable and in-memory projections must match; silent memory fallback is not restored.
8. The protocol validator rejects duplicate tool IDs, missing function/name/arguments, orphan tool results, and unresolved calls.

## 3. Verification

### Focused backend regression tests

- `AgentRunTranscriptServiceTest`
- `AgentRunResumeSchedulerTest`
- `AgentTranscriptProjectionServiceTest`
- `AgentCompactionServiceTest`
- `AgentProviderProtocolValidatorTest`
- `AgentLoopEngineStreamingContractTest`

Result: passed.

### Full backend

Command:

```powershell
cd D:\LabexAgent\backend
mvn -q test
```

Result: passed, zero failures and zero errors. The test suite includes Spring integration/controller/runtime coverage, not only pure unit tests.

### Frontend

```powershell
cd D:\LabexAgent\frontend
npm test
npm run test:acceptance:unit
npm run build
```

Results:

- 153 frontend tests passed.
- 9 acceptance unit tests passed.
- Production build passed.
- Chunk budgets passed: CloudWorkspace 1,446,057 / 1,500,000; index 1,259,442 / 1,300,000; TerminalPanel 380,367 / 400,000 bytes.

### Real browser/system acceptance

Isolated runtime:

- Backend: `http://127.0.0.1:18080`
- Vite: `http://127.0.0.1:13001`
- Browser run: `084d42e8ab0b4df8aad9cd467957b40e`
- Acceptance project: `130`

Result: passed with 0 console errors and 0 network errors. Verified:

- desktop three-column layout
- conversation isolation
- refresh replay deduplication
- question reply component
- permission approval refresh recovery
- durable Provider messages and Parts
- durable compaction epoch and reduced token estimate
- static context blocker card
- completion evidence card
- unverified completion remains blocked

`restartProjectionVerified` remains false in the current acceptance harness. This is reported as an unverified boundary, not claimed as proven. The browser acceptance did prove same-task interaction resume and durable replay in a live restarted backend process, but a second JVM restart between pause and resume still needs a dedicated harness scenario.

## 4. Remaining risk

The architecture is not yet fully OpenCode-equivalent. Remaining work is primarily Provider input fully switching away from `AgentLoopEngine`'s compatibility `msgs` cache, lifecycle/state ownership cleanup, explicit multi-JVM restart proof, and deletion of obsolete compatibility paths. Those are separate convergence tasks and must not be hidden by this iteration.
