# Phase 0 Core Tool Test Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Dispatch one fresh implementer per task; after implementation, run spec-compliance review first and code-quality review second. Do not start the next task until both reviews pass.

**Goal:** Add dedicated regression tests for five high-risk coding-agent classes without changing production behavior.

**Architecture:** Each target class receives one focused JUnit 5 test class using the repository's existing plain-construction + Mockito style. Tests execute real argument parsing, path resolution and safety classification while mocking only side-effect boundaries (`DiffService` or `SandboxWorker`). Known defects are captured as characterization tests and explicitly linked to later engineering phases.

**Tech Stack:** Java 17, Maven, JUnit 5, Mockito, Gson, Spring Boot test dependencies already present in `backend/pom.xml`.

**Approved design:** `docs/superpowers/specs/2026-07-17-phase0-tool-test-coverage-design.md`

**Roadmap:** `docs/coding-agent-engineering-roadmap.md`

---

## Non-Negotiable Execution Rules

1. **Tests only:** do not edit any file under `backend/src/main/`.
2. **Preserve user work:** the repository currently contains extensive uncommitted changes. Do not run checkout/restore/reset/clean/stash, do not reformat unrelated files, and do not commit unless the user explicitly asks.
3. **Characterization, not correction:** if a test reveals production behavior that looks wrong, encode the approved current behavior and report it. Do not repair it in this phase.
4. **Read before writing:** every implementer must read the production class and the listed reference tests before creating its test file.
5. **No Spring context:** do not use `@SpringBootTest`, `@Mock`, `@InjectMocks`, or `MockitoExtension`; follow the repository's direct-construction style.
6. **No shared fixture abstraction:** each test class keeps small local helpers. Do not create a common test base or production test seam.
7. **Verification truthfulness:** targeted tests must pass. The current full suite baseline has a Windows temp-directory cleanup error in `SandboxWorkerContractTest.localDevelopmentWorkerProvidesManagedBidirectionalProcessStreams`; if it recurs, report the exact test/error and prove no additional failures were introduced. Never call that result “all tests pass.”
8. **Stop on contradiction:** if current code differs from this plan, stop and return `NEEDS_CONTEXT` with file/line evidence. Do not guess or silently rewrite expectations.

## File Map

| Task | Create | Must not modify |
|---|---|---|
| 1 | `backend/src/test/java/com/labex/labexagent/tool/impl/EditFileToolTest.java` | all production files |
| 2 | `backend/src/test/java/com/labex/labexagent/tool/impl/WriteFileToolTest.java` | all production files |
| 3 | `backend/src/test/java/com/labex/controller/student/ProjectCommandSafetyTest.java` | all production files |
| 4 | `backend/src/test/java/com/labex/labexagent/tool/impl/BashToolTest.java` | all production files |
| 5 | `backend/src/test/java/com/labex/labexagent/tool/impl/ApplyPatchToolTest.java` | all production files |
| 6 | no new source file; verification/reporting only | all repository files except this plan's checkbox state if tracking is desired |

## Standard Agent Return Contract

Every implementer and reviewer must return exactly these fixed, mechanically parseable sections:

```text
STATUS: DONE | DONE_WITH_CONCERNS | NEEDS_CONTEXT | BLOCKED
FILES: created/modified paths, or "none"
TESTS: each command, exit code, tests run/failures/errors/skips
EVIDENCE: concise behavior/file-line observations
CONCERNS: known defects, environment limitations, or "none"
```

---

## Baseline Gate (Must Run Before Task 1)

Before creating any Phase 0 test file, record:

```bash
cd backend
mvn test
```

Save the exact `HEAD` SHA, `git status --short`, Maven exit code, total tests, failures, errors, skips, and every non-pass test name/reason. This establishes the only valid comparison baseline. Do not classify a Windows temp-directory error or symlink/smoke skip as accepted unless this fresh run actually reproduces it.

---

## Task 1: EditFileTool Characterization Tests

**Files:**
- Create: `backend/src/test/java/com/labex/labexagent/tool/impl/EditFileToolTest.java`
- Read: `backend/src/main/java/com/labex/labexagent/tool/impl/EditFileTool.java`
- Read: `backend/src/main/java/com/labex/labexagent/tool/ToolSupport.java`
- Read: `backend/src/main/java/com/labex/labexagent/workspace/SecureWorkspacePath.java`
- Reference: `backend/src/test/java/com/labex/labexagent/tool/impl/FileToolChangeTypeTest.java`
- Reference: `backend/src/test/java/com/labex/labexagent/tool/ToolSupportPathTest.java`

- [ ] **Step 1: Dispatch the implementer with this complete prompt**

```text
You are the implementation agent for Phase 0 Task 1 in d:\LabexAgent.

GOAL
Create a dedicated JUnit 5 characterization test class for EditFileTool. This task adds tests only and must not change production behavior.

ALLOWED WRITE
- backend/src/test/java/com/labex/labexagent/tool/impl/EditFileToolTest.java

FORBIDDEN
- Do not modify anything under backend/src/main/.
- Do not modify existing tests.
- Do not add dependencies, Spring test annotations, shared fixture classes, or production test seams.
- Do not commit, stash, restore, reset, clean, or reformat unrelated code.

READ FIRST
1. backend/src/main/java/com/labex/labexagent/tool/impl/EditFileTool.java
2. backend/src/main/java/com/labex/labexagent/tool/ToolSupport.java
3. backend/src/main/java/com/labex/labexagent/workspace/SecureWorkspacePath.java
4. backend/src/test/java/com/labex/labexagent/tool/impl/FileToolChangeTypeTest.java
5. backend/src/test/java/com/labex/labexagent/tool/ToolSupportPathTest.java
6. docs/superpowers/specs/2026-07-17-phase0-tool-test-coverage-design.md

STYLE
- package com.labex.labexagent.tool.impl
- JUnit 5 and direct Mockito mock(X.class), when(...), verify(...), eq(...)
- @TempDir Path workspace
- private local helpers for context(), args(), and pendingChange()
- use AgentContext.create with StudentProject.workspacePath set to workspace.toString()
- use new EditFileTool(diffService, mock(StudentProjectService.class))

REQUIRED BEHAVIORS
A. missing/empty file_path => failure content exactly "file_path is required"
B. missing/empty old_string => failure content exactly "old_string is required"
C. absolute path or ../ traversal => failure content exactly "Unsafe file path" and DiffService has no interactions
D. valid but nonexistent path => failure content exactly "Unsafe file path". Add a comment explaining that resolveExisting throws before EditFileTool's later Files.exists branch, making the "文件不存在" branch unreachable under the current resolver.
E. existing file where old_string is absent => failure content exactly "未找到要替换的内容", no DiffService call
F. old_string equals new_string => same no-change failure, no DiffService call
G. successful replacement with at least two occurrences of old_string => success, diff="diff", pendingChangeId="change"; verify stageAndApply receives exact student/project identity/conversation/task plus cleaned path, exact before, complete after with every literal match replaced, and "modify"
H. aliases => at least one parameterized or compact test proving path/filePath, oldString/old_text and newString/new_text aliases work. Keep the test readable; do not duplicate every success assertion excessively.

PROVEN APIs
- Success-path `stageAndApply` verification must use `eq(7)`, `same(project)`, `eq("conversation")`, `eq(1L)`, then exact path/before/after/type matchers. Preserve the same `StudentProject` instance in the fixture. Use `isNull()` only for an intentionally null taskId case.
- PendingChange ctor: new PendingChange("change", 7, 12, "conversation", 1L, 99L, path, "modify", before, after, "diff", "applied")
- ToolResult accessors: isSuccess, getContent, getDiff, getPendingChangeId

VERIFICATION
From d:\LabexAgent\backend run:
  mvn -Dtest=EditFileToolTest,FileToolChangeTypeTest,ToolSupportPathTest test
Expected: exit 0; all selected tests pass.
Then run:
  git diff -- backend/src/main/java
This may show pre-existing user changes. Verify your task added no new production-file changes; do not alter the existing diff.

RETURN
Use the standard STATUS/FILES/TESTS/EVIDENCE/CONCERNS format. If any expected behavior contradicts current code, stop and return NEEDS_CONTEXT with file:line evidence rather than editing production code.
```

- [ ] **Step 2: Independently verify Task 1**

Run from `backend/`:

```bash
mvn -Dtest=EditFileToolTest,FileToolChangeTypeTest,ToolSupportPathTest test
```

Expected: selected tests pass. Inspect the diff and confirm only `EditFileToolTest.java` was created by this task.

- [ ] **Step 3: Spec-compliance review prompt**

```text
Review Phase 0 Task 1 for specification compliance only.

Read:
- docs/superpowers/specs/2026-07-17-phase0-tool-test-coverage-design.md
- docs/superpowers/plans/2026-07-17-phase0-tool-test-foundation.md (Task 1)
- backend/src/main/java/com/labex/labexagent/tool/impl/EditFileTool.java
- backend/src/test/java/com/labex/labexagent/tool/impl/EditFileToolTest.java

Check every A-H required behavior, exact messages, no-interaction assertions, alias coverage, unreachable missing-file branch comment, and the hard constraint that no production file was changed for this task. Do not review style yet. Return PASS or FAIL with a numbered list of exact missing/extra behavior and file:line references. If FAIL, the implementer must fix and you must re-review.
```

- [ ] **Step 4: Code-quality review prompt**

```text
Review EditFileToolTest.java for code quality after spec compliance has passed. Look for brittle assertions, tests that only verify mocks instead of real tool behavior, duplicated setup, platform-dependent paths, weak test names, over-mocking, and accidental production changes. Confirm the tests use the repository's existing JUnit/Mockito style. Classify issues as Critical/Important/Minor and approve only when no Critical or Important issue remains.
```

---

## Task 2: WriteFileTool Characterization Tests

**Files:**
- Create: `backend/src/test/java/com/labex/labexagent/tool/impl/WriteFileToolTest.java`
- Read: `backend/src/main/java/com/labex/labexagent/tool/impl/WriteFileTool.java`
- References: same path/support/change-type tests as Task 1

- [ ] **Step 1: Dispatch the implementer with this complete prompt**

```text
You are the implementation agent for Phase 0 Task 2 in d:\LabexAgent.

GOAL
Create dedicated JUnit 5 characterization tests for WriteFileTool, tests only.

ALLOWED WRITE
- backend/src/test/java/com/labex/labexagent/tool/impl/WriteFileToolTest.java

FORBIDDEN
No production edits, existing-test edits, dependencies, Spring context, common fixture base, commits, resets/restores/clean/stash, or unrelated formatting.

READ FIRST
- backend/src/main/java/com/labex/labexagent/tool/impl/WriteFileTool.java
- backend/src/main/java/com/labex/labexagent/tool/ToolSupport.java
- backend/src/main/java/com/labex/labexagent/workspace/SecureWorkspacePath.java
- backend/src/test/java/com/labex/labexagent/tool/impl/FileToolChangeTypeTest.java
- backend/src/test/java/com/labex/labexagent/tool/ToolSupportPathTest.java
- docs/superpowers/specs/2026-07-17-phase0-tool-test-coverage-design.md

STYLE/SETUP
Match FileToolChangeTypeTest: @TempDir, StudentProject + AgentContext.create, direct Mockito construction, local helpers. Construct WriteFileTool with mocked DiffService and mocked StudentProjectService.

REQUIRED BEHAVIORS
A. missing/empty file_path => failure "file_path is required"
B. absolute path and ../ traversal => failure "Unsafe file path", no DiffService interaction
C. existing nonempty file => stageAndApply beforeContent equals real content and type="modify"
D. nonexistent file => stageAndApply beforeContent="" and type="create"
E. omitted content => current behavior submits afterContent="" and succeeds; mark as characterization behavior, not a recommendation
F. aliases => prove path/filePath and file_content/fileContent resolve correctly without excessive duplicate tests
G. successful results expose diff and pendingChangeId from PendingChange

Use exact 8-argument stageAndApply verification. Do not assume stageAndApply itself writes the mocked file; this test verifies the tool-to-DiffService contract.

VERIFICATION
From backend/:
  mvn -Dtest=WriteFileToolTest,FileToolChangeTypeTest,ToolSupportPathTest test
Expected exit 0.
Confirm only the new test file was introduced by this task.

RETURN
Standard STATUS/FILES/TESTS/EVIDENCE/CONCERNS. Stop with NEEDS_CONTEXT on code/spec contradiction.
```

- [ ] **Step 2: Independently verify Task 2**

```bash
mvn -Dtest=WriteFileToolTest,FileToolChangeTypeTest,ToolSupportPathTest test
```

- [ ] **Step 3: Spec-compliance review prompt**

```text
Review Task 2 only against the approved design and plan. Verify A-G, especially real existing-file content, create-vs-modify type, omitted-content characterization, alias coverage, and exact stageAndApply parameters. Confirm no production files changed for this task. Return PASS/FAIL with file:line evidence; style is out of scope for this review.
```

- [ ] **Step 4: Code-quality review prompt**

```text
Review WriteFileToolTest.java for maintainable tests: meaningful names, real filesystem setup via @TempDir, minimal mocks, no assertion duplication, no hidden order dependence, and no false claim that mocked DiffService wrote files. Report Critical/Important/Minor; approve only with no Critical/Important findings.
```

---

## Task 3: ProjectCommandSafety Characterization Tests

**Files:**
- Create: `backend/src/test/java/com/labex/controller/student/ProjectCommandSafetyTest.java`
- Read: `backend/src/main/java/com/labex/controller/student/ProjectCommandSafety.java`

- [ ] **Step 1: Dispatch the implementer with this complete prompt**

```text
You are the implementation agent for Phase 0 Task 3 in d:\LabexAgent.

GOAL
Create direct characterization tests for ProjectCommandSafety.check(command, approved). Do not strengthen the policy yet; Phase 1 will change behavior deliberately.

ALLOWED WRITE
- backend/src/test/java/com/labex/controller/student/ProjectCommandSafetyTest.java

FORBIDDEN
No production edits, no command execution, no shell invocation, no exploit payload beyond inert strings passed to the pure Java check method, no dependencies, no commits or destructive git commands.

READ FIRST
- backend/src/main/java/com/labex/controller/student/ProjectCommandSafety.java
- docs/superpowers/specs/2026-07-17-phase0-tool-test-coverage-design.md
- docs/coding-agent-engineering-roadmap.md Phase 1

REQUIRED BEHAVIORS
A. "ignore previous ..." => blocked, no approval, matchedRule="prompt_injection"
B. a HARD_BLOCKED command such as "shutdown now" => blocked, no approval, level="blocked", keyword="shutdown"
C. NEEDS_APPROVAL "rm -rf foo" approved=false => blocked pending approval, level="approval_required", keyword="rm -rf"
D. the same command approved=true => current behavior allowed; assert allowed=true, approvalRequired=false, riskLevel="normal", matchedRule="". Add a comment that this characterization does not authorize model self-approval.
E. safe prefix "npm test" => allowed level="safe"
F. chained safe pattern, e.g. "echo ready && npm test", only if current SAFE_PATTERNS logic really classifies it safe; otherwise encode actual behavior and explain
G. normal "echo hi" => allowed level="normal"
H. null => no exception, allowed level="normal"
I. inert base64 string `echo cm0gLXJmIC8=|base64 -d|sh` => current policy allows normal. In Java source use bare `|` characters; do not copy Markdown table escaping. Never execute this string. Name the test with `knownDefect` and assert allowed=true, approvalRequired=false, riskLevel="normal", matchedRule=""; comment points to docs/coding-agent-engineering-roadmap.md Phase 1.
J. inert quote string `r'm' -rf /` => its own known-defect test with the same exact normal/empty-rule assertions. Never execute.
K. inert IFS string `rm${IFS}-rf${IFS}/` => a separate known-defect test with the same assertions. Never execute.
L. case-insensitive matching for one blocked/risky rule

These are pure method tests; no Mockito and no @TempDir are needed. Prefer small helper assertions only if they make risk-level checks clearer.

VERIFICATION
From backend/:
  mvn -Dtest=ProjectCommandSafetyTest test
Expected exit 0. Do not run any tested command.

RETURN
Standard STATUS/FILES/TESTS/EVIDENCE/CONCERNS. Include the exact current classifications observed for D, F, I and J.
```

- [ ] **Step 2: Independently verify Task 3**

```bash
mvn -Dtest=ProjectCommandSafetyTest test
```

Expected: pass; no process execution occurred.

- [ ] **Step 3: Spec-compliance review prompt**

```text
Review ProjectCommandSafetyTest against Phase 0 Task 3. Confirm it is pure classification testing, covers A-L, never executes command strings, accurately records approved=true and each bypass classification, and clearly links known weaknesses to Phase 1. Return PASS/FAIL with exact gaps. Do not demand policy fixes in this phase.
```

- [ ] **Step 4: Code-quality review prompt**

```text
Review ProjectCommandSafetyTest for security-test quality. Reject tests that normalize away the bypass, accidentally invoke a shell, use vague names, or portray current allowed behavior as safe/recommended. Check assertions cover allowed, approvalRequired, riskLevel and matchedRule where relevant. Approve only with no Critical/Important issues.
```

---

## Task 4: BashTool Characterization Tests

**Files:**
- Create: `backend/src/test/java/com/labex/labexagent/tool/impl/BashToolTest.java`
- Read: `BashTool.java`, `SandboxWorker.java`, `WorkerRunSpec.java`, process records, cancellation interface
- Reference: `CommandToolWorkerTest.java`

- [ ] **Step 1: Dispatch the implementer with this complete prompt**

```text
You are the implementation agent for Phase 0 Task 4 in d:\LabexAgent.

GOAL
Create focused BashTool unit/characterization tests. Mock SandboxWorker; never execute an OS command.

ALLOWED WRITE
- backend/src/test/java/com/labex/labexagent/tool/impl/BashToolTest.java

FORBIDDEN
No production edits; no real shell/process execution; no LocalDevelopmentWorker/Docker invocation; no mutation of global os.name; no dependencies; no commits or destructive git operations.

READ FIRST
- backend/src/main/java/com/labex/labexagent/tool/impl/BashTool.java
- backend/src/main/java/com/labex/controller/student/ProjectCommandSafety.java
- backend/src/main/java/com/labex/labexagent/worker/SandboxWorker.java
- backend/src/main/java/com/labex/labexagent/worker/WorkerRunSpec.java
- backend/src/main/java/com/labex/labexagent/execution/ProcessExecutionRequest.java
- backend/src/main/java/com/labex/labexagent/execution/ProcessExecutionResult.java
- backend/src/main/java/com/labex/labexagent/execution/ExecutionStatus.java
- backend/src/main/java/com/labex/labexagent/runtime/CancellationToken.java
- backend/src/test/java/com/labex/labexagent/tool/impl/CommandToolWorkerTest.java
- docs/superpowers/specs/2026-07-17-phase0-tool-test-coverage-design.md

SETUP
Use @TempDir workspace and direct AgentContext constructor as CommandToolWorkerTest does. Mock SandboxWorker.execute(any(), any(), any()) to return SUCCEEDED/0. Use ArgumentCaptor for WorkerRunSpec, ProcessExecutionRequest and CancellationToken.

REQUIRED BEHAVIORS
A. blank command => failure "command is required", worker never called
B. HARD_BLOCKED command => failure, no approval, worker never called
C. HARD_BLOCKED remains blocked with allow_dangerous=true
D. NEEDS_APPROVAL without approval => isApprovalRequired true, approval command exact, worker never called
E. NEEDS_APPROVAL with allow_dangerous=true => current behavior calls worker and returns success. Mark this as characterization that Phase 1 will remove as an approval source.
F. timeout default=60 seconds
G. timeout 0 and negative clamp to 1 second (one test may cover both cleanly)
H. timeout >600 clamps to 600 seconds
I. usesLinuxShell=true => calculate the unique expected executable from `Files.exists(Path.of("/bin/bash"))`, then assert the full command equals `[expectedShell, "-lc", originalCommand]`
J. cross-platform default shell with usesLinuxShell=false and without changing os.name: Windows must equal `["cmd.exe", "/c", command]`; non-Windows must equal the same precisely calculated Unix triple. Do not skip.
K. taskId non-null => captured WorkerRunSpec.runId="task-<id>"
L. taskId null => runId="agent-<sessionId>"
M. active CancellationToken object is passed by identity to worker
N. failed ProcessExecutionResult maps to unsuccessful ToolResult with status/exit fields (test the integration boundary, not every ToolResult factory branch)

IMPORTANT
- Mockito mock of an interface default method typically returns false unless configured. Explicitly stub usesLinuxShell in tests that depend on it.
- Never execute dangerous string fixtures; they are passed only to BashTool with a mocked worker, and blocked cases must verify no worker interaction.

VERIFICATION
From backend/:
  mvn -Dtest=BashToolTest,CommandToolWorkerTest test
Expected exit 0 and no real process execution from BashToolTest.

RETURN
Standard STATUS/FILES/TESTS/EVIDENCE/CONCERNS. Report shell expectations used on the current platform.
```

- [ ] **Step 2: Independently verify Task 4**

```bash
mvn -Dtest=BashToolTest,CommandToolWorkerTest test
```

- [ ] **Step 3: Spec-compliance review prompt**

```text
Review BashToolTest for A-N compliance. Confirm blocked/approval cases prove zero worker calls, allowed cases capture exact request/run/token, timeout boundaries are checked as Duration values, runId branches are covered, and no real command executes. Confirm no global os.name mutation and no production changes. Return PASS/FAIL with file:line evidence.
```

- [ ] **Step 4: Code-quality review prompt**

```text
Review BashToolTest for cross-platform reliability, Mockito correctness, captor clarity, state isolation, and security-test hygiene. Reject hardcoded /bin/sh assumptions that fail on hosts with /bin/bash, shared mocks whose invocation counts leak between tests, or tests that only assert ToolResult without checking worker contract. Approve only after Critical/Important issues are fixed and re-reviewed.
```

---

## Task 5: ApplyPatchTool Characterization Tests

**Files:**
- Create: `backend/src/test/java/com/labex/labexagent/tool/impl/ApplyPatchToolTest.java`
- Read: `ApplyPatchTool.java`, `ToolSupport.java`, `SecureWorkspacePath.java`, `PendingChange.java`

- [ ] **Step 1: Dispatch the implementer with this complete prompt**

```text
You are the implementation agent for Phase 0 Task 5 in d:\LabexAgent.

GOAL
Create comprehensive characterization tests for ApplyPatchTool's batch semantics without changing production code.

ALLOWED WRITE
- backend/src/test/java/com/labex/labexagent/tool/impl/ApplyPatchToolTest.java

FORBIDDEN
No production edits, no “atomicity fix,” no change to ApplyPatchTool, DiffService or PendingChange, no shared test base, dependencies, commits, reset/restore/clean/stash.

READ FIRST
- backend/src/main/java/com/labex/labexagent/tool/impl/ApplyPatchTool.java
- backend/src/main/java/com/labex/labexagent/tool/ToolSupport.java
- backend/src/main/java/com/labex/labexagent/workspace/SecureWorkspacePath.java
- backend/src/main/java/com/labex/labexagent/diff/PendingChange.java
- backend/src/test/java/com/labex/labexagent/tool/impl/FileToolChangeTypeTest.java
- docs/superpowers/specs/2026-07-17-phase0-tool-test-coverage-design.md

SETUP
@TempDir workspace, StudentProject + AgentContext.create, mocked DiffService, local helpers to construct change JsonObjects and unique PendingChange objects. Use in-order verification or captors where order/first ID matters.

REQUIRED BEHAVIORS
A. missing changes => failure "changes 参数必须是数组"
B. non-array changes => same failure
C. empty array => failure "changes 不能为空"
D. non-object elements are skipped while a later valid create succeeds
E. unknown operation with an already existing safe path is skipped; pair it with a later valid create and prove no DiffService call is made for the unknown item while the valid item succeeds. Also document that unknown operations with nonexistent/unsafe paths fail "Unsafe file path" before reaching the switch.
F. valid create => before="", after=content, type="create"
G. existing file + create => current behavior still submits type="create" and does not read old content; label as characterization difference
H. valid replace on existing file => exact before/after, type="modify"
I. replace whose old_string is absent => current behavior submits no-op before==after; label known defect/difference from EditFileTool
J. valid delete existing file => exact before, after="", type="delete"
K. replace/delete nonexistent path => current behavior fails "Unsafe file path" before DiffService, because resolveExisting requires the target. Do not encode the previously mistaken assumption that empty delete is submitted.
L. batch [valid create, invalid path] => the first stageAndApply call occurs, then execute returns failure "Unsafe file path". Label this as a tool control-flow characterization only: the mocked DiffService test does not prove real disk partial application or real rollback behavior.
M. mixed valid create/replace/delete => prepare real existing targets for replace/delete before execution because mocked DiffService does not write files; verify exactly three calls in input order, result content contains "已自动应用 3 个文件变更", combined diff exactly `diff1\ndiff2\ndiff3\n`, and pendingChangeId equals the first successful change id
N. first array element skipped then valid element => pendingChangeId is the first successfully processed change id, not array position zero

PATH INPUTS
Use `/outside.txt` or `../outside.txt` for unsafe paths; SecureWorkspacePath rejects absolute paths and paths escaping workspace. For nonexistent replace/delete, use a legal relative path that has not been created and assert "Unsafe file path".

MOCKING
Return unique PendingChange ids/diffs for multi-change cases. Stub exact calls or use thenReturn(change1, change2, change3) when appropriate. Verify no more interactions after failure points.

VERIFICATION
From backend/:
  mvn -Dtest=ApplyPatchToolTest,FileToolChangeTypeTest,ToolSupportPathTest test
Expected exit 0.

RETURN
Standard STATUS/FILES/TESTS/EVIDENCE/CONCERNS. Enumerate each known defect locked by characterization tests.
```

- [ ] **Step 2: Independently verify Task 5**

```bash
mvn -Dtest=ApplyPatchToolTest,FileToolChangeTypeTest,ToolSupportPathTest test
```

- [ ] **Step 3: Spec-compliance review prompt**

```text
Review ApplyPatchToolTest against requirements A-N. Focus on accurate resolveExisting behavior, partial-application evidence, no-op replace evidence, create-on-existing behavior, call order/diff order/first successful ID, and exact messages. Ensure the reviewer does not request production fixes in Phase 0. Return PASS/FAIL with file:line references.
```

- [ ] **Step 4: Code-quality review prompt**

```text
Review ApplyPatchToolTest for readable batch fixtures, independent tests, precise Mockito verification, meaningful unique PendingChange values, and avoidance of overfitting to implementation details beyond approved behavior. Reject a giant single test, unclear JSON construction, or mock stubbing that can pass without proving call order/arguments. Approve only with no Critical/Important findings.
```

---

## Task 6: Integrated Verification and Phase Report

**Files:**
- No production or test code should be added in this task.
- Create only if requested by the user: a verification record under `docs/coding-agent-industrialization/`; otherwise report in conversation and update roadmap status later.

- [ ] **Step 1: Dispatch the verification agent with this complete prompt**

```text
You are the final verification agent for Phase 0 in d:\LabexAgent. Do not edit code.

VERIFY SCOPE
Expected new files:
- EditFileToolTest.java
- WriteFileToolTest.java
- ProjectCommandSafetyTest.java
- BashToolTest.java
- ApplyPatchToolTest.java

STEP 0 — CAPTURE BASELINE BEFORE IMPLEMENTATION
Before Task 1 changes are created, run `mvn test` and save exact totals plus every failure/error/skip test name and reason. This baseline is mandatory for later comparison. Do not pre-classify a test as an accepted environment failure unless the fresh baseline reproduces it.

STEP A — TARGETED SUITE
From backend/ run:
  mvn -Dtest=EditFileToolTest,WriteFileToolTest,ProjectCommandSafetyTest,BashToolTest,ApplyPatchToolTest,FileToolChangeTypeTest,CommandToolWorkerTest,ToolSupportPathTest test
Record exit code and tests/failures/errors/skips.

STEP B — PRODUCTION-DIFF GUARD
Run git diff --name-only and git status --short. Determine which files were introduced by Phase 0 versus pre-existing user changes. Confirm none of the five tasks modified backend/src/main. Do not revert anything.

STEP C — FULL SUITE
Run from backend/:
  mvn test
Record exact exit code and totals. Compare every failure/error/skip with the pre-implementation baseline. Any new failure/error must be fixed. Any new skip must be removed or individually justified as an intentional platform/capability condition. An existing failure/error can be called an unchanged environment baseline only when test name, exception type, and cause match the captured baseline; never assume this in advance.

STEP D — SUPPLEMENTAL SUITE IF AN UNCHANGED BASELINE ERROR RECURS
Only if Step C exactly reproduces a captured baseline failure/error, run a supplemental full suite excluding only that exact test class. Inspect the Maven/Surefire syntax supported by this project first, then record the narrowest command and totals. This supplemental pass does not turn Step C into a pass and must not exclude any Phase 0 test.

STEP E — REQUIREMENT AUDIT
Map every approved design behavior to a test method. Flag missing, duplicate-only, or misleading coverage. Confirm known defect tests are labeled as characterization and no dangerous command fixture was executed.

RETURN
STATUS: DONE only if targeted suite passes, no Phase 0 production edits exist, and the full suite has no new failure/error; any new skip is removed or individually justified against the captured baseline.
FILES: none
TESTS: full command/evidence table
EVIDENCE: requirement-to-test map summary
CONCERNS: known baseline failure/skips and any residual risk
```

- [ ] **Step 2: Final spec-compliance review prompt**

```text
Review the entire Phase 0 output against the approved design. Read all five new test classes and verification evidence. Confirm every completion target and non-goal. Reject completion if any production file changed due to Phase 0, targeted tests fail, known defects are presented as recommendations, or full-suite failures are hidden. Return a final PASS/FAIL checklist.
```

- [ ] **Step 3: Final code-quality review prompt**

```text
Review all five new test classes as a coherent test foundation. Look for duplicated helpers that are acceptable locally versus harmful copy-paste, inconsistent naming, platform flakes, mock-only tests, command execution risk, missing negative assertions, and maintainability when Phase 1 deliberately changes behavior. Report Critical/Important/Minor and require fixes/re-review for Critical or Important findings.
```

- [ ] **Step 4: Update phase status only with fresh evidence**

After reviews pass, update `docs/coding-agent-engineering-roadmap.md` Phase 0 status with:

- completion date;
- exact targeted test count/result;
- exact full suite result;
- known baseline error/skips if present;
- links to this plan and the approved spec.

Do not write “all tests pass” unless `mvn test` actually exits 0 with zero failures/errors.

---

## Plan Self-Review Record

- [x] Every approved design behavior maps to one task requirement.
- [x] No plan step requests a production behavior fix.
- [x] Nonexistent EditFileTool and ApplyPatchTool replace/delete behavior reflects `resolveExisting` reality.
- [x] All five implementer prompts are self-contained.
- [x] Every task has targeted verification, spec review, and quality review.
- [x] Cross-platform shell assertions do not hardcode a single executable.
- [x] No step asks agents to commit or alter the user's existing uncommitted work.
- [x] Full-suite baseline failure is reported honestly and never normalized away.
- [x] Placeholder scan completed; no implementation placeholders remain.

## Execution Handoff

Use **Subagent-Driven Development** as approved:

1. Fresh implementer for Task 1.
2. Independent spec review, fix/re-review until pass.
3. Independent quality review, fix/re-review until pass.
4. Repeat sequentially for Tasks 2-5.
5. Run Task 6 integrated verification and final reviews.

Do not dispatch implementation tasks in parallel because they share the same working tree and test suite. Investigation/review agents may run independently only when they do not edit files.
