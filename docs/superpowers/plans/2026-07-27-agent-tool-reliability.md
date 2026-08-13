# Agent Tool Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 LabexAgent 的工具执行、文件编辑、验证和最终完成判断形成可追踪、可恢复、不可夸大的闭环。

**Architecture:** 保留现有安全边界，不把受限 direct-command 误包装成真正 Shell；通过结构化失败代码和可操作提示让 Agent 能自行恢复。文件编辑增加读取版本指纹，拒绝基于过期内容的静默修改。最终完成状态只接受真实验证证据，并将工具失败、LSP 不可用和跨文件编译错误纳入完成阻断条件。

**Tech Stack:** Spring Boot 3、Java 17、JUnit 5、Mockito、Vue 3/Vite、现有 SSE 事件流、现有 MyBatis-Plus 持久化和 SandboxWorker。

---

## 现状边界

当前工作区已经存在完成证据相关代码：

- `backend/src/main/java/com/labex/labexagent/run/RunCompletionEvidence.java`
- `backend/src/main/java/com/labex/labexagent/run/RunCompletionEvidenceService.java`
- `backend/src/main/java/com/labex/labexagent/run/RunCompletionPolicy.java`
- `backend/src/main/java/com/labex/labexagent/runtime/AgentRunFinalizer.java`

本计划不重写这些机制，而是把工具失败、诊断不可用、实际编译/测试结果接入已有证据链。

本计划不修改以下范围：

- Web Search、Exa/Parallel MCP、`web_fetch`。
- 认证、数据库 schema、生产部署。
- 当前未请求的 UI 全面重构。
- 不初始化、重置、提交或清理当前工作区。

---

### Task 1: 固化 shell 工具契约，避免“名叫 Shell、实际不是 Shell”

**Files:**
- Modify: `backend/src/main/java/com/labex/labexagent/tool/impl/RunCommandTool.java:24-73`
- Modify: `backend/src/main/java/com/labex/labexagent/tool/impl/BashTool.java:23-61`
- Modify: `backend/src/main/java/com/labex/labexagent/commandsecurity/CommandClassifier.java:46-124`
- Modify: `backend/src/main/java/com/labex/labexagent/commandsecurity/CommandClassification.java`
- Create: `backend/src/main/java/com/labex/labexagent/commandsecurity/CommandPolicyMessage.java`
- Test: `backend/src/test/java/com/labex/labexagent/commandsecurity/CommandSecurityTest.java`
- Test: `backend/src/test/java/com/labex/labexagent/tool/impl/BashToolTest.java`
- Test: `backend/src/test/java/com/labex/labexagent/tool/impl/CommandToolWorkerTest.java`

- [ ] **Step 1: 写失败测试，明确 direct-command 合同**

增加测试，确认以下行为：

```java
assertBlockedWithHint("echo one | cat", CommandReasonCode.SHELL_OPERATOR,
        "请拆分为多个独立命令");
assertBlockedWithHint("mvn test && npm run build", CommandReasonCode.SHELL_OPERATOR,
        "不支持管道、重定向或多命令串联");
```

同时确认 `git status` 仍然允许，`rm -rf unsafe` 仍然只返回审批，不会绕过安全策略直接执行。

- [ ] **Step 2: 增加策略消息映射，不暴露用户命令内容**

创建 `CommandPolicyMessage`，根据 `CommandReasonCode` 返回稳定的客户端安全提示：

```java
public final class CommandPolicyMessage {
    private CommandPolicyMessage() {}

    public static String forReason(CommandReasonCode reason) {
        return switch (reason) {
            case SHELL_OPERATOR, REDIRECTION, COMMAND_SUBSTITUTION,
                 VARIABLE_EXPANSION, WINDOWS_VARIABLE_EXPANSION ->
                    "当前 shell 工具只接受一条受限直接命令，不支持管道、重定向、变量、命令替换或多命令串联；请拆分为多个独立工具调用。";
            case NETWORK_COMMAND, NETWORK_URL ->
                    "该命令涉及网络访问，当前工作区策略禁止网络命令。";
            case SHELL_COMMAND_STRING, POWERSHELL_COMMAND,
                 POWERSHELL_ENCODED_COMMAND, ENCODED_EXECUTION ->
                    "不允许通过 Shell/Powershell 字符串或编码内容间接执行命令。";
            default -> "命令不符合当前工作区的受限直接命令策略。";
        };
    }
}
```

- [ ] **Step 3: 修改两个命令工具的描述和失败结果**

把工具描述从“执行 shell 命令”改成明确的 direct-command 合同：

```text
执行一条受限直接命令；不支持管道、重定向、变量、命令替换、引号和多命令串联。危险的单条命令需要用户审批。
```

失败结果统一包含机器可读字段和人类提示：

```text
command blocked by restricted command policy
reason=shell_operator
hint=当前 shell 工具只接受一条受限直接命令，请拆分为多个独立工具调用
```

不把原始命令拼入普通失败消息，继续使用现有审批脱敏规则。

- [ ] **Step 4: 确认分类顺序不被破坏**

保留当前顺序：控制字符、提示注入、Shell 操作符、重定向、命令替换、变量、编码执行、Shell 字符串、引号转义、网络命令、硬阻断命令、审批命令、允许命令。新增提示只能改变错误解释，不能放宽策略。

- [ ] **Step 5: 运行 focused tests**

```powershell
mvn -q '-Dtest=CommandSecurityTest,BashToolTest,CommandToolWorkerTest' test
```

Expected: 所有测试通过，新增 shell operator 提示测试通过，worker 对被拦截命令调用次数仍为 0。

---

### Task 2: 给 edit_file 增加文件版本指纹和冲突语义

**Files:**
- Modify: `backend/src/main/java/com/labex/labexagent/tool/impl/ReadFileTool.java:23-63`
- Modify: `backend/src/main/java/com/labex/labexagent/tool/impl/EditFileTool.java:26-75`
- Modify: `backend/src/main/java/com/labex/labexagent/tool/ToolSupport.java`
- Create: `backend/src/main/java/com/labex/labexagent/tool/FileContentFingerprint.java`
- Modify: `backend/src/main/java/com/labex/labexagent/tool/ToolResult.java` only if structured error fields are required by the existing SSE payload contract
- Test: `backend/src/test/java/com/labex/labexagent/tool/impl/EditFileToolTest.java`
- Test: `backend/src/test/java/com/labex/labexagent/tool/ToolSupportPathTest.java`

- [ ] **Step 1: 写失败测试，覆盖 stale edit**

增加以下测试场景：

1. `read_file` 返回完整 SHA-256。
2. 文件内容发生变化后，携带旧 SHA 调用 `edit_file`，返回 `FILE_CHANGED_SINCE_READ`。
3. 版本冲突时不调用 `DiffService`，不产生 change record。
4. 没有传 `expected_sha256` 时保持兼容，但仍执行当前精确匹配和唯一匹配校验。
5. `old_string` 不存在时仍返回“未找到原文”，但附带“请重新读取文件”的修复建议。

- [ ] **Step 2: 提取统一的完整指纹实现**

创建 `FileContentFingerprint`，以 UTF-8 内容计算完整 64 位十六进制 SHA-256：

```java
public final class FileContentFingerprint {
    private FileContentFingerprint() {}

    public static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((content == null ? "" : content)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                out.append(String.format("%02x", value));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
```

- [ ] **Step 3: 修改 read_file 输出完整指纹**

将当前只输出前 16 个十六进制字符的短 hash 改为完整 hash：

```text
[read_file path=... lines=... sha256=<64 hex chars>]
```

保留字段名 `sha256`，避免破坏已有模型识别逻辑。

- [ ] **Step 4: 扩展 edit_file 参数并先做版本检查**

在 `EditFileTool.definition()` 增加可选参数：

```text
expected_sha256: 上一次 read_file 返回的完整文件 SHA-256；如果文件已经变化，编辑会以冲突失败
```

执行顺序改为：

1. 读取当前文件。
2. 如果传入 `expected_sha256`，比较当前完整 hash。
3. 不一致时返回：

```text
code=FILE_CHANGED_SINCE_READ
message=文件自上次读取后已发生变化，请重新读取后再编辑
expected_sha256=...
actual_sha256=...
```

4. 版本一致后，继续当前 `old_string` 精确一次匹配。
5. 仍然禁止模糊匹配、自动猜测、自动替换多处。

- [ ] **Step 5: 保留安全写入和 DiffService 冲突检查**

`EditFileTool` 仍然通过 `DiffService.stageAndApplyDeferred(...)` 写入，不能绕过现有 workspace lease、before hash 校验和变更记录。版本检查是更早、更清晰的 Agent 协议层冲突提示，不替代 DiffService 的最终一致性检查。

- [ ] **Step 6: 运行 focused tests**

```powershell
mvn -q '-Dtest=EditFileToolTest,ToolSupportPathTest' test
```

Expected: 旧有 11 个编辑测试继续通过，并新增 stale edit 测试通过。

---

### Task 3: 把工具失败和 LSP unavailable 接入完成证据

**Files:**
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentToolTurnExecutor.java`
- Modify: `backend/src/main/java/com/labex/labexagent/service/AgentPostEditHookService.java:32-123`
- Modify: `backend/src/main/java/com/labex/labexagent/run/RunCompletionEvidenceService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/run/RunCompletionPolicy.java`
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentRunFinalizer.java`
- Test: `backend/src/test/java/com/labex/labexagent/runtime/AgentToolTurnExecutorTest.java`
- Test: `backend/src/test/java/com/labex/labexagent/service/AgentPostEditHookServiceTest.java` or the existing service test file if the class already has coverage
- Test: `backend/src/test/java/com/labex/labexagent/run/RunCompletionPolicyTest.java`
- Test: `backend/src/test/java/com/labex/labexagent/run/RunCompletionEvidenceServiceTest.java`

- [ ] **Step 1: 写失败测试，区分四种验证状态**

验证状态必须明确区分：

```text
PASS          有明确成功输出或结构化成功结果
FAIL          命令实际执行并失败，或 LSP 报出错误
UNAVAILABLE   LSP/验证器没有响应，不能当成通过
SKIPPED       文件类型或项目配置不支持该验证
```

测试必须断言：

- `UNAVAILABLE` 不计入 successfulVerifications。
- `UNAVAILABLE` 计入 unresolvedRisks。
- `FAIL` 计入 failedVerifications。
- 存在修改文件且没有 PASS 级验证时，`RunCompletionEvidence.satisfied()` 为 false。

- [ ] **Step 2: 为 Post-edit Hook 输出结构化状态**

在现有 `HookReport` 中增加状态信息，保持现有 `content` 文本兼容：

```java
public enum VerificationStatus {
    PASS, FAIL, UNAVAILABLE, SKIPPED
}
```

当前 LSP 不可用时输出：

```text
status=UNAVAILABLE
reason=lsp_unavailable
message=未获得诊断结果，不能视为无错误
suggested_verification=mvn -q -DskipTests compile
```

当前 LSP 报错误时输出：

```text
status=FAIL
reason=lsp_diagnostics
```

- [ ] **Step 3: 记录每次工具失败**

在 `AgentToolTurnExecutor` 中，任何 `ToolResult.isSuccess() == false` 且不是纯审批等待的结果，都要记录为当前 task 的 unresolved risk 或 failed tool event。至少记录：

```text
tool_name
failure_code 或失败文本
file_path/command 的安全摘要
iteration/task id
```

命令内容继续使用现有 `CommandRedactor`，不记录 token、密码、授权头或完整敏感命令。

- [ ] **Step 4: 收紧最终完成规则**

修改 `RunCompletionPolicy`：

- 有文件修改，但只有 `read_file`/字符串存在性检查，没有 PASS 级编译或测试证据：禁止完成。
- 存在 `UNAVAILABLE` 验证：禁止声称“验证通过”，只能输出“验证未完成”。
- 存在工具失败且未出现后续成功恢复：禁止完成。
- 存在失败验证：禁止完成。
- 只有明确的编译、测试、运行时 smoke 或结构化诊断 PASS，才计入 successfulVerifications。

- [ ] **Step 5: 修改 finalizer 的用户可见指导**

`AgentRunFinalizer` 的指导语句必须能告诉 Agent 下一步：

```text
当前不能结束：文件已修改，但没有通过编译/测试证据。请先执行项目实际存在的验证命令。
当前不能结束：LSP 不可用。请执行编译或测试；LSP unavailable 不能视为无错误。
当前不能结束：工具调用失败。请先根据失败原因恢复，再重新验证。
```

- [ ] **Step 6: 运行完成证据测试**

```powershell
mvn -q '-Dtest=AgentToolTurnExecutorTest,RunCompletionPolicyTest,RunCompletionEvidenceServiceTest' test
```

Expected: Agent 无法通过“文件存在 + 字符串 includes”伪造完成证据。

---

### Task 4: 增加真实的项目验证命令解析，禁止硬编码不存在的脚本

**Files:**
- Modify: `backend/src/main/java/com/labex/labexagent/service/AgentPostEditHookService.java:144-155`
- Create: `backend/src/main/java/com/labex/labexagent/verification/ProjectVerificationCommandResolver.java`
- Create: `backend/src/main/java/com/labex/labexagent/verification/VerificationCommand.java`
- Test: `backend/src/test/java/com/labex/labexagent/verification/ProjectVerificationCommandResolverTest.java`
- Test: `backend/src/test/java/com/labex/labexagent/service/AgentPostEditHookServiceTest.java`

- [ ] **Step 1: 写失败测试，覆盖真实 package.json/pom.xml**

测试项目配置：

1. 只有 `package.json` 的项目，存在 `build` 脚本但不存在 `test` 脚本，不能建议 `npm test`。
2. `package.json` 存在 `test` 脚本时才建议 `npm test`。
3. Vue/TypeScript 修改且只有 `build` 脚本时建议 `npm run build`。
4. Java 项目存在 `pom.xml` 时建议 `mvn -q -DskipTests compile`；需要测试时再建议 `mvn -q test`。
5. 不存在项目配置时返回“无法自动推断验证命令”，不能伪造命令。

- [ ] **Step 2: 实现项目脚本读取**

`ProjectVerificationCommandResolver` 只读取：

- `package.json` 的 `scripts` 字段。
- `pom.xml` 是否存在。
- 当前修改文件扩展名。

不读取 `.env`、密钥文件或用户凭据。

- [ ] **Step 3: 将建议命令接入 post-edit hook 和完成证据**

每个建议命令必须带来源和可执行性：

```text
source=package.json
command=npm run build
available=true
```

命令执行结果必须由 `shell`/`run_tests` 真实返回后，才能成为 PASS 证据。

- [ ] **Step 4: 运行测试**

```powershell
mvn -q '-Dtest=ProjectVerificationCommandResolverTest,AgentPostEditHookServiceTest' test
```

---

### Task 5: 增加跨文件契约回归测试，覆盖本次日志中的真实错误模式

**Files:**
- Create: `backend/src/test/java/com/labex/labexagent/runtime/AgentVerificationAcceptanceTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/runtime/AgentLoopEngineFinalReplyPolicyTest.java`
- Modify: `backend/src/test/java/com/labex/labexagent/runtime/AgentLoopEngineStreamingContractTest.java`
- Create: `frontend/src/views/agentToolFailurePresentation.test.mjs` if the current frontend test organization requires a user-visible failure regression test

- [ ] **Step 1: 构造“方法签名不一致”场景**

测试输入包含：

```text
AuthService.getCurrentUser()
AuthController 调用 getCurrentUser(authentication.getName())
```

验证 Agent 不能通过字符串存在性检查完成任务，必须执行 Java 编译或报告未验证。

- [ ] **Step 2: 构造 shell_operator 失败后恢复场景**

第一轮返回：

```text
mvn test && npm run build
```

断言工具返回结构化 `SHELL_OPERATOR` 失败；下一轮只有在 Agent 改为可执行的独立命令并成功后，才允许继续完成。

- [ ] **Step 3: 构造 stale edit 场景**

流程：

1. read 文件得到 hash A。
2. 外部修改文件为 hash B。
3. edit 携带 hash A。
4. 断言返回 `FILE_CHANGED_SINCE_READ`。
5. Agent 重新 read 后使用 hash B 成功编辑。

- [ ] **Step 4: 构造 LSP unavailable 场景**

LSP mock 返回 unavailable，断言最终事件不是“验证通过”，而是“验证未完成”，且 finalizer 阻止完成。

- [ ] **Step 5: 运行回归测试**

```powershell
mvn -q '-Dtest=AgentVerificationAcceptanceTest,AgentLoopEngineFinalReplyPolicyTest,AgentLoopEngineStreamingContractTest' test
```

---

### Task 6: 前端展示结构化失败和验证状态

**Files:**
- Inspect/Modify: `frontend/src/composables/agentHistoryReducer.js`
- Inspect/Modify: `frontend/src/composables/useAgentTaskRuntime.js`
- Inspect/Modify: `frontend/src/views/CloudWorkspace.vue`
- Inspect/Modify: `frontend/src/components/cloud/CompletionEvidenceCard.vue`
- Create/Modify: `frontend/src/components/cloud/ToolFailureCard.vue`
- Test: `frontend/src/composables/agentHistoryReducer.test.mjs`
- Test: `frontend/src/views/agentStreamIntegration.test.mjs`

- [ ] **Step 1: 定义前端事件映射**

后端工具结果中的以下字段必须保留：

```text
status
code
reason
hint
suggested_verification
```

前端不能把 `UNAVAILABLE` 渲染成绿色成功，也不能把审批等待渲染成普通失败。

- [ ] **Step 2: 增加工具失败卡片**

至少显示：

- 工具名称。
- 失败原因。
- 可执行恢复建议。
- 是否需要重新读取文件。
- 是否需要用户审批。
- 当前任务是否因此被阻止完成。

- [ ] **Step 3: 增加验证状态颜色和文字约束**

```text
PASS        验证通过
FAIL        验证失败
UNAVAILABLE 验证未完成
SKIPPED     未执行该验证
```

禁止使用“无错误”描述 `UNAVAILABLE`。

- [ ] **Step 4: 运行前端测试和构建**

```powershell
npm run test -- --run
npm run build
```

如果当前 `package.json` 没有 test script，则只运行仓库实际存在的测试脚本和：

```powershell
npm run build
```

---

### Task 7: 分阶段验收和交付门槛

**Files:**
- Inspect: `backend/pom.xml`
- Inspect: `frontend/package.json`
- Inspect: `README.md`
- Inspect: `git status --short`
- Modify: `docs/` 中与 Agent 工具协议和验证行为相关的文档

- [ ] **Step 1: 后端 focused gate**

```powershell
mvn -q '-Dtest=CommandSecurityTest,EditFileToolTest,BashToolTest,AgentToolTurnExecutorTest,RunCompletionPolicyTest,RunCompletionEvidenceServiceTest' test
```

- [ ] **Step 2: 后端完整 gate**

```powershell
mvn test
```

如果 Windows 文件锁导致临时目录或 JAR 重命名失败，必须定位占用 PID，不能把锁错误误判为代码失败，也不能删除 target。

- [ ] **Step 3: 后端编译/打包 gate**

```powershell
mvn -q -DskipTests compile
mvn -q -DskipTests package
```

- [ ] **Step 4: 前端 gate**

```powershell
npm run build
```

- [ ] **Step 5: Live smoke gate**

在真实启动的后端和前端上验证：

1. shell 传入 `git status | findstr x`，页面展示“受限 direct-command，需拆分”，后端不启动进程。
2. read 文件获得完整 hash，外部修改后 edit 返回 stale conflict。
3. 修改 Java 文件后 LSP unavailable，任务不能显示“验证通过”。
4. 修改 Java 文件并执行真实 `mvn -q -DskipTests compile` 成功后，完成证据允许进入最终总结。
5. 构造方法签名不一致的 Java 修改，编译失败后任务不能完成。

- [ ] **Step 6: 交付前检查**

```powershell
git status --short
git diff --stat
git diff -- backend/src/main/java frontend/src/main/java frontend/src/components frontend/src/composables
```

只报告本次改动，保留用户已有未提交文件，不提交、不推送、不重置。

---

## 完成判定

只有同时满足以下条件，才可以对用户说“已修复”：

1. shell 工具契约和描述一致，策略失败有可恢复提示。
2. edit_file 能识别读取版本与当前版本冲突，并且冲突不写文件。
3. LSP unavailable 不再被当成无错误。
4. 工具失败、验证失败和未完成验证会阻止 final completion。
5. 实际存在的 Maven/npm 验证命令被真实执行并记录结果。
6. 跨文件方法签名错误能被编译 gate 捕获。
7. focused tests、后端完整测试、编译/打包、前端构建和 live smoke 均有实际输出证据。
8. 最终回复区分“通过”“失败”“未执行”“不可用”，不使用模糊的“验证通过”。

## 实施顺序

严格按以下顺序执行：

```text
Task 1 shell contract
    ↓
Task 2 edit version conflict
    ↓
Task 3 completion evidence
    ↓
Task 4 project verification resolver
    ↓
Task 5 historical-log regression tests
    ↓
Task 6 frontend presentation
    ↓
Task 7 full verification and live smoke
```

Task 1 和 Task 2 完成后先停一次，确认工具协议没有破坏既有安全测试，再进入完成证据改造。不要先修改前端展示来掩盖后端状态错误。
