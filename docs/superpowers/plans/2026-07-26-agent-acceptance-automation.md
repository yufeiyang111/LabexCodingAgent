# Agent Runtime Acceptance Automation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Make conversation isolation, durable interactions, compaction, checkout contention, restart recovery, and profile isolation repeatable with one opt-in command.

**Architecture:** Use the existing `acceptance & !prod` provider. A PowerShell orchestrator owns a disposable backend on an isolated port and exposes sanitized JSON evidence. A dependency-free Node CDP client drives the Vue UI through a Vite server pointed at that backend.

**Tech Stack:** PowerShell, Java/Spring Boot executable JAR, Node built-ins (`fetch`, `WebSocket`, `child_process`), Chrome DevTools Protocol, Vue/Vite.

## Global Constraints

- Acceptance uses generated users/projects and deletes projects/configs in `finally`.
- Never write tokens or passwords to disk or stdout.
- Never take over ports 8080/3000; default to 18080/13000 with overrides.
- Normal profile must be verified after the acceptance process exits.
- No Playwright/Selenium dependency unless built-in CDP proves insufficient.

### Task 1: Acceptance event parser and evidence model

**Files:**
- Create: `frontend/scripts/acceptance/agent-sse.mjs`
- Create: `frontend/scripts/acceptance/agent-sse.test.mjs`
- Create: `frontend/scripts/acceptance/evidence.mjs`
- Test: `frontend/scripts/acceptance/agent-sse.test.mjs`

**Interfaces:**
- Produces: `parseSse(text): AgentEvent[]`
- Produces: `assertEventSequence(events, requiredTypes): void`
- Produces: `redactEvidence(value): unknown`

- [x] Write failing parser tests for split data lines, event IDs, JSON envelopes, and malformed frames.
- [x] Run `node --test scripts/acceptance/agent-sse.test.mjs` and confirm RED.
- [x] Implement parser/evidence helpers with no third-party dependency.
- [x] Re-run focused tests and confirm GREEN.
- [x] Review diff; do not commit.

### Task 2: Isolated backend restart harness

**Files:**
- Create: `scripts/acceptance/agent-runtime.ps1`
- Create: `scripts/acceptance/README.md`
- Modify: `backend/src/main/java/com/labex/labexagent/llm/AcceptanceScriptedProvider.java`
- Test: `backend/src/test/java/com/labex/labexagent/llm/AcceptanceScriptedProviderTest.java`

**Interfaces:**
- Consumes: packaged JAR and acceptance provider.
- Produces: sanitized JSON with task IDs, state transitions, compaction status, cleanup status, and normal-profile provider result.

- [x] Add failing provider tests for permission approval, command approve/reject, delayed tool completion, and completion-evidence scenarios.
- [x] Run focused Maven test and confirm RED.
- [x] Extend scripted scenarios without network access or secret reads.
- [x] Implement PowerShell API/SSE helpers, generated credentials held only in memory, isolated ports, PID ownership checks, restart, polling, replay, and cleanup.
- [x] Verify script refuses an occupied port not owned by itself.
- [x] Run the harness and require same-task restart recovery, checkout contention, manual compaction completion, and normal-profile provider absence.
- [x] Review logs for secrets and remove any temporary state.

### Task 3: Dependency-free browser CDP harness

**Files:**
- Create: `frontend/scripts/acceptance/cdp-client.mjs`
- Create: `frontend/scripts/acceptance/cdp-client.test.mjs`
- Create: `frontend/scripts/acceptance/agent-browser.mjs`
- Modify: `frontend/vite.config.js`
- Modify: `frontend/package.json`

**Interfaces:**
- Produces: `CdpClient.connect(webSocketUrl)` and `evaluate(expression)`.
- Consumes: `VITE_API_TARGET`, `ACCEPTANCE_FRONTEND_PORT`, Chrome executable path.

- [x] Write failing CDP request-correlation and timeout tests.
- [x] Implement a minimal Node WebSocket CDP client.
- [x] Make Vite proxy target configurable while preserving `http://localhost:8080` as default.
- [x] Add `acceptance:browser` script without adding packages.
- [x] Automate register/login/project open, A message, new-session first-frame assertion, B message, console error assertion, refresh/reconnect cursor assertion, and cleanup.
- [x] Run focused Node tests, browser acceptance, full frontend tests, and build.

### Task 4: One-command acceptance entrypoint

**Files:**
- Create: `scripts/acceptance/run-all.ps1`
- Modify: `scripts/acceptance/README.md`

- [x] Compose package, backend harness, frontend harness, and final normal-profile verification.
- [x] Fail closed on missing Java/Node/Chrome/MySQL or occupied ports.
- [x] Emit only sanitized evidence and exact failed gate.
- [x] Verify cleanup after both success and an injected failure.
