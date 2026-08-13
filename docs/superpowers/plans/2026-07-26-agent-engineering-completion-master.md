# LabexAgent Engineering Completion Master Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Turn the currently working Agent runtime into a repeatably verified, explicitly budgeted, maintainable system with durable completion evidence.

**Architecture:** Keep the existing Spring/MyBatis/Vue task-event architecture. Add opt-in acceptance harnesses around it, then move page/engine responsibilities behind narrow services without changing public APIs. Context admission and completion become explicit domain decisions rather than UI/model-text inference.

**Tech Stack:** Java 21, Spring Boot 3.2, MyBatis-Plus, MySQL, Vue 3, Vite 5, Node built-in test runner, PowerShell, Chrome DevTools Protocol.

## Global Constraints

- Do not modify Web Search, Exa/Parallel MCP, public fallback, or `web_fetch` behavior.
- Do not add runtime dependencies unless no existing or built-in mechanism can satisfy the requirement.
- Do not commit, stage, push, reset, clean, or delete unrelated worktree changes.
- Java source must remain BOM-free UTF-8; code comments use Chinese where comments are needed.
- Every behavior change starts with a failing focused test and ends with focused plus broader verification.
- A task is complete only after current-code tests and, where applicable, live runtime/browser evidence.
- Production must never register or route to the scripted acceptance provider.

## Delivery order

1. [P0 acceptance automation](2026-07-26-agent-acceptance-automation.md)
2. [P1 runtime decomposition](2026-07-26-agent-runtime-decomposition.md)
3. [P2 static context admission](2026-07-26-static-context-admission.md)
4. [P3 completion evidence](2026-07-26-run-completion-evidence.md)
5. [P4 integration and final audit](2026-07-26-agent-integration-audit.md)

## Acceptance matrix

| Requirement | Automated evidence | Live evidence |
|---|---|---|
| New conversation contains no prior DOM/state | frontend unit/source tests + browser CDP smoke | A -> new -> B browser run |
| SSE cursor survives refresh/reconnect | task runtime tests + browser smoke | no duplicate events |
| Question/permission/command continuation keeps task | backend focused tests + acceptance runner | same taskId after restart |
| Checkout contention is legal | state-machine tests + acceptance runner | WAITING_WORKSPACE observed |
| Manual compaction is durable | backend/service tests + acceptance runner | task reaches completed |
| Static prompt overflow is explicit | budget-decision tests | CONTEXT_LIMIT_BLOCKED card |
| Completion requires evidence | completion policy tests | task cannot complete without evidence |
| Normal runtime excludes fake provider | profile test | provider endpoint absent |

## Master completion gate

- [x] Every child plan checkbox is completed.
- [x] `mvn -q test` passes.
- [x] `mvn -q -DskipTests package` passes.
- [x] `npm test` passes.
- [x] `npm run build` passes and chunk budgets remain within limits.
- [x] Opt-in backend and browser acceptance runners pass and clean disposable data.
- [x] Normal-profile JAR is running and contains no runtime errors.
- [x] `git diff --check` passes.
- [x] Remaining unrelated files are listed without being modified.
