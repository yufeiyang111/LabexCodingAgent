# LabexAgent Industrial Coding Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor LabexAgent from a host-executed prototype into a trustworthy, isolated, durable, and measurable coding Agent platform.

**Architecture:** Keep Spring Boot as a modular control plane while extracting untrusted execution behind a sandbox-worker contract. Convert the Agent loop into a persistent event-driven state machine, use Git worktrees and compare-and-swap changes for source integrity, and replace repeated repository scans with a versioned context service.

**Tech Stack:** Java 17, Spring Boot 3.2, MyBatis-Plus, MySQL, Vue 3, Vite, xterm.js, Docker/OCI worker, Redis Streams or RabbitMQ, S3/MinIO, KMS/Vault, JUnit 5.

## Global Constraints

- Preserve existing authentication and project ownership behavior while tightening authorization.
- Do not expose or migrate real secrets through source code, logs, tests, or documentation.
- Do not run untrusted repository code in the control-plane process after the sandbox milestone.
- Keep API changes backward compatible until the matching frontend migration is complete.
- Implement behavioral changes test-first and run the smallest test before the full backend/frontend verification.
- Do not commit or push unless explicitly requested.
- Existing uncommitted user changes must be preserved and reviewed before every edit.

---

## File and Module Map

| Area | Responsibility |
|---|---|
| `labexagent/tool/ToolResult.java` | Canonical success/failure result used by runtime verification |
| `labexagent/execution/` | Process request/result contracts, output draining, timeout, cancellation |
| `labexagent/worker/` | Sandbox worker port and local/container implementations |
| `labexagent/runtime/` | Pure run state machine, step orchestration, cancellation propagation |
| `labexagent/provider/` | Normalized provider events and capability-aware adapters |
| `labexagent/workspace/` | Leases, worktrees, file versions, compare-and-swap writes |
| `labexagent/context/` | Incremental metadata, symbols, retrieval, and token budgeting |
| `frontend/src/composables/agent/` | Run stream, event reduction, cancellation, approvals |
| `frontend/src/views/CloudWorkspace.vue` | Composition shell after logic extraction |

## Milestone P0: Trust and Containment

### Task 1: Correct Process Exit Semantics

**Files:**
- Create: `backend/src/test/java/com/labex/labexagent/tool/ToolResultTest.java`
- Modify: `backend/src/main/java/com/labex/labexagent/tool/ToolResult.java`
- Modify: `backend/src/main/java/com/labex/labexagent/tool/impl/RunCommandTool.java`
- Modify: `backend/src/main/java/com/labex/labexagent/tool/impl/RunTestsTool.java`
- Modify: `backend/src/main/java/com/labex/labexagent/tool/impl/ExecuteCodeTool.java`
- Modify: `backend/src/main/java/com/labex/labexagent/tool/impl/BashTool.java`

**Interfaces:**
- Produces: `ToolResult.fromProcessExit(int exitCode, String output)`.
- Invariant: only exit code `0` produces `success=true`.

- [x] Write tests proving exit code `0` succeeds and exit code `137` fails while preserving `exit=<code>` and output.
- [x] Run `mvn -Dtest=ToolResultTest test` and confirm compilation fails because `fromProcessExit` does not exist.
- [x] Add the factory method and switch all four process tools to use it.
- [x] Run `mvn -Dtest=ToolResultTest test` and confirm all cases pass.
- [x] Run `mvn test` and confirm no regression.

### Task 2: Introduce a Process Execution Contract

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/execution/ExecutionStatus.java`
- Create: `backend/src/main/java/com/labex/labexagent/execution/ProcessExecutionRequest.java`
- Create: `backend/src/main/java/com/labex/labexagent/execution/ProcessExecutionResult.java`
- Create: `backend/src/main/java/com/labex/labexagent/execution/ProcessExecutor.java`
- Create: `backend/src/main/java/com/labex/labexagent/execution/LocalProcessExecutor.java`
- Create: `backend/src/main/java/com/labex/labexagent/runtime/CancellationToken.java`
- Create: `backend/src/test/java/com/labex/labexagent/execution/LocalProcessExecutorTest.java`
- Modify: the four process tools from Task 1.

**Interfaces:**
- `ProcessExecutor.execute(ProcessExecutionRequest, CancellationToken)` returns `ProcessExecutionResult`.
- `ProcessExecutionResult` contains status, exit code, duration, bounded output, and truncation.

- [x] Write a cross-platform helper-process fixture that emits output larger than the native pipe buffer.
- [x] Prove the new contract is absent before implementation and cover the previous wait-before-read failure mode with a 250,000-character process fixture.
- [x] Implement concurrent output draining, a bounded model buffer, and a full-output listener callback.
- [x] Add timeout and cancellation tests that assert descendant termination.
- [x] Migrate command tools without changing their public schemas.
- [x] Run targeted execution tests and `mvn test`.

### Task 3: Secure Terminal Authorization

**Files:**
- Create: `backend/src/test/java/com/labex/labexagent/terminal/TerminalWebSocketAuthorizationTest.java`
- Modify: `backend/src/main/java/com/labex/labexagent/terminal/TerminalWebSocketHandler.java`
- Modify: `backend/src/main/java/com/labex/labexagent/terminal/TerminalSession.java`
- Modify: `frontend/src/composables/useTerminal.js`
- Modify: `frontend/src/composables/terminalProtocol.js`

**Interfaces:**
- Terminal creation consumes an authenticated `projectId` and workspace-relative `cwd`.
- The backend resolves the owned project and supplies the absolute workspace path.

- [x] Add negative tests for a missing project, another user's project, absolute `cwd`, and `..` traversal.
- [x] Bind WebSocket session metadata to authenticated user and authorized project.
- [x] Remove arbitrary client host paths and construct the terminal root server-side.
- [x] Replace inherited `System.getenv()` with an explicit safe environment allowlist.
- [x] Validate WebSocket origin and stop using wildcard production origins.
- [x] Run terminal backend tests and the three frontend terminal regression tests.

### Task 4: Put Execution Behind a Worker Port

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/worker/SandboxWorker.java`
- Create: `backend/src/main/java/com/labex/labexagent/worker/WorkerRunSpec.java`
- Create: `backend/src/main/java/com/labex/labexagent/worker/WorkerPolicy.java`
- Create: `backend/src/main/java/com/labex/labexagent/worker/LocalDevelopmentWorker.java`
- Create: `backend/src/main/java/com/labex/labexagent/worker/DockerSandboxWorker.java`
- Create: `backend/src/test/java/com/labex/labexagent/worker/SandboxWorkerContractTest.java`
- Create: `backend/src/test/java/com/labex/labexagent/worker/DockerSandboxWorkerSmokeTest.java`
- Modify: process tools, terminal, LSP startup, and stdio MCP startup to use the port.

**Interfaces:**
- `prepare`, `execute`, `readFile`, `applyChange`, `collectArtifacts`, and `terminate` match the architecture document.

- [x] Write contract tests that run against every enabled worker implementation.
- [x] Add a development-only local worker guarded by an explicit profile.
- [x] Implement a Docker worker with workspace-only mounts, resource limits, no inherited secrets, and network disabled by default.
- [x] Route terminal and non-interactive commands through the same worker identity.
- [x] Fail application startup in production when no isolated worker is configured.
- [x] Run worker contract tests and a smoke test inside a disposable container. On 2026-07-15, `DockerSandboxWorkerSmokeTest` invoked Docker Engine with `labex-agent-sandbox:smoke`, a D-drive build context and D-drive workspace; it produced `smoke-ok` and left no `labex-agent-*` containers. The offline image archive is retained at `D:/LabexAgent/.labex/docker-smoke/labex-agent-sandbox-smoke.tar`.

### Task 5: Secure Paths, URLs, and Secrets

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/workspace/SecureWorkspacePath.java`
- Create: `backend/src/test/java/com/labex/labexagent/workspace/SecureWorkspacePathTest.java`
- Create: `backend/src/main/java/com/labex/labexagent/network/OutboundUrlPolicy.java`
- Create: `backend/src/test/java/com/labex/labexagent/network/OutboundUrlPolicyTest.java`
- Modify: file tools, `StudentProjectServiceImpl`, `ProjectTerminalService`, web tools, repository clone, and MCP HTTP calls.
- Create: `backend/src/main/java/com/labex/labexagent/secret/SecretStore.java`
- Modify: model and MCP configuration persistence.

**Interfaces:**
- `SecureWorkspacePath.resolveExisting` and `resolveForCreate` reject links and escapes.
- `OutboundUrlPolicy.validate` returns a validated destination or a typed rejection.
- `SecretStore` returns short-lived values by reference and never exposes them in API DTOs.

- [x] Add traversal, symbolic-link, junction, redirect, DNS rebinding, metadata-IP, and private-range negative tests.
- [x] Replace lexical `startsWith` path checks with the shared secure resolver.
- [x] Apply outbound policy before connection and after every redirect.
- [x] Introduce envelope encryption or a Vault/KMS adapter with a development provider.
- [x] Migrate stored credentials with an additive schema change and rollback instructions.
- [x] Run security tests and full backend tests.

### Task 6: Change Compare-and-Swap and Workspace Lease

**Files:**
- Create: `backend/src/main/java/com/labex/labexagent/workspace/WorkspaceLeaseService.java`
- Create: `backend/src/test/java/com/labex/labexagent/workspace/WorkspaceLeaseServiceTest.java`
- Modify: `backend/src/main/java/com/labex/labexagent/diff/DiffService.java`
- Modify: write/edit/apply-patch tools.

**Interfaces:**
- A mutating run owns a lease keyed by checkout ID.
- `applyChange` requires the expected SHA-256 and returns conflict instead of overwriting.

- [x] Add tests for stale file hashes and simultaneous mutating runs.
- [x] Enforce the stored `beforeHash` before applying a change.
- [x] Generate diffs with Git and preserve rename metadata.
- [x] Record file changes even when a shell command exits non-zero.
- [x] Run diff, concurrency, and full backend tests.

### Task 7: End-to-End Cancellation

**Files:**
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/CancellationToken.java`
- Modify: Agent runtime, provider gateway, process executor, permission service, interaction service, and controller.
- Modify: `frontend/src/composables/useAgentStream.js`
- Modify: `frontend/src/views/CloudWorkspace.vue`
- Create: backend cancellation integration tests and frontend reducer tests.

**Interfaces:**
- One run token fans out to active provider and worker operations.
- Cancellation endpoint is idempotent.

- [x] Add a test with a blocking provider and long-running process.
- [x] Propagate cancellation and assert both stop within the configured deadline.
- [x] Persist `cancelling` and `cancelled` states.
- [x] Consolidate frontend streaming onto the existing `AbortController` composable.
- [x] Verify reconnect, stop, and repeated-stop behavior.

## Milestone P1: Durable Runtime and Context

### Task 8: Persisted Run Event State Machine

**Files:**
- Add additive run-event and approval schema migrations.
- Create runtime state, event, repository, outbox, and step-handler classes.
- Split orchestration responsibilities out of `AgentLoopEngine`.

- [x] Add transition-table tests for every legal and illegal state change.
- [x] Persist events and publish queue messages through a transactional outbox.
- [x] Replace blocking question and approval futures with resumable records. Questions and approvals now suspend the active run slice after persisting the interaction; an owned, persisted response schedules a continuation without retaining an in-process future.
- [x] Add event replay and Last-Event-ID support to the stream API.
- [x] Add restart recovery and duplicate-delivery tests.

### Task 9: Provider Gateway

**Files:**
- Create normalized provider event and capability types.
- Split provider-specific adapters from the runtime.
- Add streaming fixtures for multiple interleaved tool calls.

- [x] Write contract fixtures for text, reasoning, usage, errors, and two concurrent tool calls.
- [x] Implement capability negotiation and call-ID/index accumulation.
- [x] Add connection reuse headers, configurable connection/read deadlines, cancellation, and typed bounded retry policy.
- [x] Keep OpenAI-compatible behavior as one adapter; defer native adapters until a provider requires a non-compatible protocol.

### Task 10: Incremental Context Service

**Files:**
- Create context metadata, symbol, lexical index, retrieval, and token-budget modules.
- Replace repeated scans in `ProjectIndexService`, `ProjectCodeMapService`, and retrieval tools.

- [x] Create a fixed retrieval benchmark with identifier, natural-language, route, test, and recent-change queries.
- [x] Index file hashes and update only changed files.
- [x] Add Tree-sitter or LSP symbol extraction and BM25 lexical search. BM25 lexical search is default; LSP document-symbol enrichment is opt-in and falls back safely when unavailable.
- [x] Add optional embeddings behind a feature flag. A local hashed-vector embedding score is disabled by default and adds a bounded cosine score only when enabled.
- [x] Record retrieval reasons and compare latency/relevance with the current scanner. The benchmark records cold vs cached elapsed time, top-hit paths, and overlap for each fixed query.

### Task 11: Frontend Runtime Decomposition

**Files:**
- Create focused composables for run stream, event reducer, approvals, conversation state, and change state.
- Split workspace panels and dialogs from `CloudWorkspace.vue`.

- [x] Add reducer tests before moving event-handling logic.
- [x] Migrate one concern at a time while preserving rendered behavior.
- [x] Add a frontend test script and CI command.
- [x] Code-split Monaco, terminal, charts, model settings, and conversation history.
- [x] Require production build without a workspace chunk exceeding the agreed budget.

## Milestone P2: Cloud Delivery and Evaluation

### Task 12: Background Branch and Pull Request Workflow

- [x] Create one branch/worktree per background run.
- [x] Add commit, push, and pull-request policy gates. Push/PR remain disabled until a remote provider is explicitly configured.
- [x] Attach verification logs and artifacts to the run and pull request.
- [x] Add CI follow-up with a bounded retry count and human-commit protection.

### Task 13: Real Subagents

- [x] Persist subagent identity, parent run, budget, permissions, tools, and status.
- [x] Give each subagent an independent context window and event stream.
- [x] Support foreground/background execution, cancellation, and result summarization.
- [x] Prevent nested or parallel fan-out beyond configured limits.

### Task 14: Evaluation and Release Gates

- [x] Create a versioned 30-50 task corpus with deterministic validators.
- [x] Record pass rate, accepted-change rate, human correction, cost, latency, and safety violations.
- [x] Compare model/prompt/runtime candidates against the same baseline.
- [x] Add CI thresholds that block statistically meaningful regressions.

## Verification Matrix

| Change | Required verification |
|---|---|
| Java behavior | Targeted JUnit test, then `mvn test` |
| Frontend logic | Targeted Node/Vue test, then `npm run build` |
| Terminal protocol | Backend authorization tests plus all terminal `.test.mjs` files |
| Sandbox | Worker contract tests plus disposable-container smoke test |
| Database | Additive migration test against clean and upgraded schemas |
| Security | Negative path, ownership, secret, SSRF, and cancellation tests |
| Agent runtime | Transition, replay, restart, idempotency, and evaluation corpus |

## Current Execution Status

- [x] Requirements document written.
- [x] Architecture document written.
- [x] Implementation plan written.
- [x] P0 Task 1: Correct process exit semantics. Verified with 2 targeted tests and 14 full backend tests on 2026-07-10.
- [x] P0 Task 2: Introduce a process execution contract. Verified with 9 targeted contract tests and 21 full backend tests on 2026-07-10.
- [x] P0 Task 3: Secure terminal authorization. Verified with 6 targeted terminal tests, 3 frontend terminal regression tests, 30 full backend tests, and a production frontend build on 2026-07-10.
- [x] P0 Task 4: Worker port implementation and contract tests are complete. On 2026-07-15, 6 contract tests plus the opt-in real `DockerSandboxWorkerSmokeTest` passed with a D-drive workspace/image archive; output was `smoke-ok` and no disposable worker container remained.
- [x] P0 Task 5: Secure paths, outbound URLs, credentials, image sources, and model endpoint validation. Verified with 74 full backend tests on 2026-07-11; 4 link-oriented tests are skipped on this Windows account because it cannot create symbolic links.
- [x] P0 Task 6: Change compare-and-swap and workspace lease. Verified with 9 focused tests and 83 full backend tests on 2026-07-11; 4 link-oriented tests are skipped on this Windows account because it cannot create symbolic links.
- [x] P0 Task 7: End-to-end cancellation. Verified with 16 focused backend tests, 6 frontend regression tests, 93 full backend tests, and a production frontend build on 2026-07-11; 4 link-oriented backend tests are skipped on this Windows account because it cannot create symbolic links.
- [x] P1 Task 8: Persisted run event state machine complete. State transition, transactional event/outbox, replay, persisted interactions, restart recovery, and durable question/approval continuations are verified with 113 targeted backend tests and 218 full backend tests on 2026-07-14; 4 link-oriented tests remain skipped on this Windows account because it cannot create symbolic links.
- [x] P1 Task 9: Provider Gateway complete. Verified with 14 targeted provider/runtime tests and 222 full backend tests on 2026-07-14; 4 link-oriented tests remain skipped on this Windows account because it cannot create symbolic links.
- [x] P1 Task 10: Incremental context service complete. Fixed retrieval benchmark, cold-vs-cached latency/top-hit comparison, in-memory file-hash reuse, BM25 lexical ranking, optional LSP symbols, opt-in hashed-vector embedding score, retrieval reasons/latency, and integration with project index, code map, and retrieve_context are verified with 6 targeted tests and 226 full backend tests on 2026-07-14; 4 link-oriented tests remain skipped on this Windows account because it cannot create symbolic links.
- [x] P1 Task 11: Frontend runtime decomposition complete. Stream lifecycle, persisted-history reducer, conversation, approval/question, and change-set state are separated into focused composables; Monaco, terminal, charts, conversation history, and model settings are lazy-loaded. Verified with 37 frontend tests and a production build including executable chunk budgets on 2026-07-14.
- [x] P2 Task 12: Background branch/worktree delivery flow, artifact capture, verification gate, and bounded retry policy complete; remote push/PR stays deny-by-default until an explicit provider is configured.
- [x] P2 Task 13: Durable, budgeted foreground/background subagents with isolated context/events, cancellation, fan-out limits, and parent summaries complete.
- [x] P2 Task 14: Versioned deterministic evaluation corpus, persisted metrics, release regression gates, and the `evaluation-gate` Maven profile complete.
- [x] Final regression snapshot on 2026-07-15: the full backend suite passed (264 tests, 0 failures, 0 errors; 5 environment-gated skips).


