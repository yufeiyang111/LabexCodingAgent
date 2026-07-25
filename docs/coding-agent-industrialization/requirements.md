# LabexAgent Industrial Coding Agent Requirements

**Status:** Approved for phased implementation  
**Date:** 2026-07-10  
**Owners:** LabexAgent engineering  
**Related documents:** [Architecture](./architecture.md), [Implementation plan](../superpowers/plans/2026-07-10-coding-agent-industrialization.md)

## 1. Purpose

LabexAgent already provides an end-to-end coding workspace with file tools, an interactive terminal, LSP, MCP, streaming conversations, plans, checkpoints, diffs, and token accounting. The next stage is to turn that feature-rich prototype into a trustworthy coding agent platform that can safely run for multiple users and can be operated on a server.

The central product requirement is not “more tools.” It is that every reported result is backed by an observable execution result, every run is isolated and recoverable, and every code change can be reviewed or reverted without overwriting concurrent user work.

## 2. Problem Statement

The current product has five user-visible failure modes:

1. A command can exit with a non-zero status while the Agent records it as successful verification.
2. Stop actions do not promptly cancel model requests, approval waits, or child processes.
3. Repository discovery repeatedly scans source files and becomes slow or irrelevant on larger projects.
4. Changes, checkpoints, and concurrent user edits do not share a single version-control contract.
5. The cloud terminal and command tools run on the application host instead of an isolated execution environment.

These failures make the Agent feel inconsistent even when the selected model is capable.

## 3. Product Goals

### G1. Trustworthy completion

- A verification command is successful only when it completes within its deadline and exits with code `0`.
- The final answer must distinguish passed, failed, skipped, and unavailable verification.
- Tool results must preserve exit code, duration, truncation, timeout, and cancellation metadata.
- A failed command that modified files must still produce a recorded change set.

### G2. Safe multi-user execution

- Every terminal, command, LSP, MCP stdio process, and test runs inside a project-scoped sandbox worker.
- A user can access only projects they own or projects explicitly shared with them.
- The client cannot choose an arbitrary host working directory.
- Worker processes do not inherit control-plane secrets.
- Filesystem, CPU, memory, process count, disk, network, and execution-time limits are enforced outside the model.

### G3. Durable and controllable runs

- A run has a persistent state and append-only event history.
- Runs can wait for approval without occupying an application request thread.
- Stop requests propagate to the model request, active tool process, worker, and client stream.
- The UI can reconnect and replay events after a network interruption.
- Retried steps are idempotent and do not duplicate writes or tool side effects.

### G4. Relevant repository context

- Repository indexing is incremental and invalidated by file changes.
- Retrieval combines paths, symbols, lexical matches, recent changes, diagnostics, and optional semantic search.
- Exact source files remain the source of truth before editing.
- Context assembly follows a measurable token budget and records why each item was selected.

### G5. Safe code changes

- Each run receives an isolated Git branch or worktree and a single-writer lease.
- A file write uses compare-and-swap against the version the Agent read.
- Diffs use Git-compatible algorithms instead of line-position comparison.
- Conversation rewind and code restore are separate operations.
- User edits and Agent edits never silently overwrite each other.

### G6. Provider-independent model execution

- The runtime supports multiple native provider adapters behind one normalized event protocol.
- Provider capabilities are negotiated rather than assumed.
- Multiple streamed tool calls are preserved by call ID and index.
- Retry policy distinguishes rate limits, temporary transport errors, invalid requests, and model output errors.
- Usage, latency, cancellation, and cache information are recorded consistently.

### G7. Operable and measurable platform

- Every run has correlated logs, traces, tool timings, token usage, and sandbox resource metrics.
- A fixed evaluation suite gates prompt, model, tool, and runtime changes.
- Product metrics include task success, verification pass rate, human correction rate, cancellation latency, cost, and time to accepted change.
- Security-sensitive actions and policy decisions are auditable.

## 4. User Roles

| Role | Required capabilities |
|---|---|
| Developer | Create projects, run interactive Agent tasks, approve actions, inspect logs and diffs, restore checkpoints |
| Project owner | Configure repository instructions, environment templates, network policy, and shared access |
| Organization administrator | Enforce models, sandboxes, secrets, retention, budgets, and audit policy |
| Platform operator | Monitor workers, retry infrastructure failures, rotate secrets, and investigate run traces without exposing tenant data |

## 5. Functional Requirements

### FR-EXEC: Execution contract

- **FR-EXEC-01:** Exit code `0` maps to success; every other exit code maps to failure.
- **FR-EXEC-02:** Timeout and user cancellation are distinct terminal states.
- **FR-EXEC-03:** Standard output and error are consumed while the process runs so a full pipe cannot deadlock the process.
- **FR-EXEC-04:** Cancellation terminates the full child-process tree.
- **FR-EXEC-05:** Output is bounded for model context while full logs are retained as artifacts under the configured retention policy.

### FR-RUN: Durable run lifecycle

- **FR-RUN-01:** Valid run states are `queued`, `preparing`, `running`, `waiting_approval`, `waiting_user`, `cancelling`, `cancelled`, `failed`, and `completed`.
- **FR-RUN-02:** Every state transition is persisted with a monotonically increasing sequence number.
- **FR-RUN-03:** The API supports run creation, status lookup, event replay, approval, answer, cancellation, and retry.
- **FR-RUN-04:** A restarted control-plane instance can continue or safely fail an in-progress run without reporting success.

### FR-SBX: Sandbox

- **FR-SBX-01:** A task executes in an OCI container initially; the worker interface must permit a later gVisor or micro-VM backend.
- **FR-SBX-02:** The workspace is the only default writable source directory.
- **FR-SBX-03:** Outbound network access is deny-by-default with explicit domain policy.
- **FR-SBX-04:** Cloud metadata endpoints, loopback, link-local, private ranges, and DNS rebinding targets are blocked for user-controlled URLs.
- **FR-SBX-05:** Runtime secrets are injected only for approved tools and must be redacted from logs and model-visible output.

### FR-WS: Workspace and changes

- **FR-WS-01:** The Git remote and run branch are the source of truth for server deployments.
- **FR-WS-02:** Active checkouts live on worker-local disk or a mounted volume, not directly in object storage.
- **FR-WS-03:** Object storage contains artifacts, compressed snapshots, logs, screenshots, videos, and build outputs.
- **FR-WS-04:** Applying a staged edit fails with a conflict if the current content hash differs from the expected hash.
- **FR-WS-05:** One project/worktree has at most one mutating run unless runs use separate branches and worktrees.

### FR-CTX: Context

- **FR-CTX-01:** File metadata and symbols are updated from file-system events or run change events.
- **FR-CTX-02:** Retrieval results contain source, score, version, and selection reason.
- **FR-CTX-03:** Generated summaries are versioned and invalidated when their source files change.
- **FR-CTX-04:** The runtime reserves token budgets for instructions, conversation, repository context, tool results, and completion.

### FR-SEC: Security and secrets

- **FR-SEC-01:** API keys and MCP authorization material are encrypted with envelope encryption or stored as secret-manager references.
- **FR-SEC-02:** Terminal WebSocket authorization binds `userId`, `projectId`, and `runId` server-side.
- **FR-SEC-03:** Allowed web origins are configured explicitly per environment.
- **FR-SEC-04:** File resolution rejects workspace escapes through `..`, absolute paths, junctions, and symbolic links.
- **FR-SEC-05:** Every outbound fetch validates the resolved address before connecting and after redirects.

### FR-EVAL: Evaluation

- **FR-EVAL-01:** The repository contains deterministic unit and integration tests for runtime state, tool results, cancellation, paths, diffs, and provider streaming.
- **FR-EVAL-02:** A versioned task corpus covers bug fixing, feature work, refactoring, frontend behavior, and security-negative cases.
- **FR-EVAL-03:** A runtime or prompt release cannot regress the agreed success-rate and safety thresholds.

## 6. Non-Functional Requirements

| Area | Requirement |
|---|---|
| Availability | Control-plane APIs target 99.9% monthly availability after the durable runtime milestone |
| Cancellation | P95 stop-to-process-termination latency is below 2 seconds |
| Recovery | An interrupted run is visible and replayable within 10 seconds of reconnect |
| Isolation | No worker process can read control-plane environment variables or another tenant workspace |
| Performance | Unchanged repositories do not require a full source scan for each Agent iteration |
| Audit | Security decisions and secret access are attributable to user, run, tool, and timestamp |
| Compatibility | Existing Spring Boot 3, Java 17, Vue 3, MySQL, SSE, and WebSocket clients migrate incrementally |
| Maintainability | Runtime orchestration and the main workspace view are decomposed into focused units with contract tests |

## 7. Out of Scope for the First Release

- Production deployment and database migration execution by the Agent.
- Unrestricted access to a customer VPC.
- Cross-repository atomic edits in one run.
- Training or fine-tuning foundation models.
- Replacing Git with an object-storage-native source-control system.
- Building a custom container scheduler before the Docker worker contract is proven.

## 8. Release Acceptance

### Phase P0 acceptance

- Non-zero commands can no longer mark changes verified.
- Terminal and tool processes no longer execute in the control-plane process namespace.
- Stop propagates to active model and tool execution.
- Path, SSRF, secret storage, change CAS, and project write-lock tests pass.
- Existing login, project, editor, terminal resize, and Agent conversation flows remain functional.

### Phase P1 acceptance

- Runs survive API instance restarts and support event replay.
- Approval waits consume no Agent executor thread.
- Provider streaming preserves multiple tool calls.
- Repository context is incrementally updated and benchmarked against the current scanner.

### Phase P2 acceptance

- Background runs use isolated branches/worktrees and can create reviewable pull requests.
- Browser verification artifacts can be attached to a run.
- Subagents have independent context, tools, budgets, permissions, and persisted lifecycle.
- The evaluation suite is a required CI gate.

## 9. Success Metrics

The primary quality metric is **accepted verified change rate**: the percentage of runs whose changes are accepted by the user and whose declared verification actually passed. Supporting metrics are first-pass success rate, human correction rate, rollback rate, cancellation latency, context-retrieval latency, model cost, run duration, and infrastructure failure rate.

