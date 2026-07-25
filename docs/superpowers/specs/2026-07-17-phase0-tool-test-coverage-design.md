# Phase 0: 核心工具测试覆盖 — 设计文档

## 目标

给 5 个当前缺乏专属测试文件的核心类建立回归测试网，**只锁定现状行为，不修复任何已发现的缺陷**。这层测试是后续 `ProjectCommandSafety` / 命令审批语义重构的直接安全前提，也为文件工具后续行为变更提供回归基线。Outbox 与 `ToolCallExtractor` 阶段应复用各自现有测试并按 TDD 另补故障/解析样本，不把本阶段误称为它们的共同前置测试基座。

范围内的 5 个类：

| 类 | 当前测试状态 | 新建测试文件 |
|---|---|---|
| `EditFileTool` | 仅 `FileToolChangeTypeTest` 覆盖 changeType 单一分支 | `EditFileToolTest.java` |
| `WriteFileTool` | 仅 `FileToolChangeTypeTest` 覆盖"已存在空文件"边角 | `WriteFileToolTest.java` |
| `BashTool` | 仅 `CommandToolWorkerTest` 覆盖 worker 转发 + linux shell | `BashToolTest.java` |
| `ApplyPatchTool` | 无 | `ApplyPatchToolTest.java` |
| `ProjectCommandSafety` | 无 | `ProjectCommandSafetyTest.java` |

## 核心原则

**这是回归测试，不是修复。** 测试断言的是代码**当前实际做了什么**，即使那个行为本身是 bug。凡是锁定已知缺陷的用例，测试方法名、参数化 case 名或相邻注释里要明确标注“characterization / 锁定现状 / 已知缺陷”，并指向现有 [工程路线图](../../coding-agent-engineering-roadmap.md) 的对应后续阶段。除非后续阶段有意变更语义，不得因为当前行为不理想而删除、跳过或改弱这些断言。

不改任何 `main/` 下的生产代码。只新增 `test/` 下的文件。

## 文件放置

- `backend/src/test/java/com/labex/labexagent/tool/impl/EditFileToolTest.java`
- `backend/src/test/java/com/labex/labexagent/tool/impl/WriteFileToolTest.java`
- `backend/src/test/java/com/labex/labexagent/tool/impl/BashToolTest.java`
- `backend/src/test/java/com/labex/labexagent/tool/impl/ApplyPatchToolTest.java`
- `backend/src/test/java/com/labex/controller/student/ProjectCommandSafetyTest.java`
  （**注意**：`ProjectCommandSafety` 在 `com.labex.controller.student` 包下，不在 `labexagent` 下，测试要放对应目录）

## 测试约定（必须遵循现有代码库风格）

参照 `FileToolChangeTypeTest` 和 `CommandToolWorkerTest`：

- 纯 JUnit 5 + Mockito，**不使用** `@SpringBootTest`（整个测试套件目前 0 个 Spring 容器测试，保持一致）。
- `@TempDir Path workspace` 作为沙箱根目录，`Files.writeString(...)` 写测试文件。
- Mockito 用 `mock(X.class)` + `when(...).thenReturn(...)`，**不用** `@Mock`/`@InjectMocks`/`MockitoExtension` 注解风格。
- 断言用 `org.junit.jupiter.api.Assertions.*`（`assertTrue`/`assertFalse`/`assertEquals`）。
- 每个测试类内用私有 helper 方法构造 `AgentContext`/`JsonObject`/`PendingChange`，不抽公共基类。
- 成功路径校验 `stageAndApply(...)` 时，对上下文参数使用 `eq(7)`、`same(project)`、`eq("conversation")`、`eq(1L)`，再精确匹配 path / before / after / changeType；只有专门构造 `taskId == null` 的场景才对该位置使用 `isNull()`。
- 校验传参用 `ArgumentCaptor` 或 `verify(...)` + 精确 matcher。

### 已验证的真实 API（写测试时照抄，不要凭记忆）

**AgentContext 两种构造方式：**

```java
// 方式 A：工厂方法，从 project.workspacePath 推导 workspaceRoot（Edit/Write/ApplyPatch 用这个）
StudentProject project = new StudentProject();
project.setProjectId(12);
project.setStudentId(7);
project.setWorkspacePath(workspace.toString());
AgentContext ctx = AgentContext.create("session", 7, project, "conversation", 1L);

// 方式 B：全参构造，project 传 null，直接给 workspace Path（BashTool 用这个，因为不碰 project）
AgentContext ctx = new AgentContext(
        "session-7", 1, null, "conversation-7", 1L, workspace,
        new ArrayList<>(), new ArrayList<>(), 0);
```

**DiffService.stageAndApply —— 8 个参数（已核对真实签名）：**

```java
stageAndApply(Integer studentId, StudentProject project, String conversationId,
              Long taskId, String path, String beforeContent, String afterContent, String changeType)
```

成功路径不要沿用宽松的前四个 `any()`；应精确验证 context 透传：
```java
verify(diffService).stageAndApply(eq(7), same(project), eq("conversation"), eq(1L),
        eq("Main.java"), eq("placeholder"), eq("updated"), eq("modify"));
```
测试 helper 应保留同一个 `StudentProject project` 实例供 `same(project)` 校验。

**PendingChange 构造（12 参）——`getId()` 返回第 1 个 String，`getDiff()` 返回 "diff" 位：**
```java
new PendingChange("change", 7, 12, "conversation", 1L, 99L,
        "Main.java", "modify", "", "updated", "diff", "applied");
// getId() -> "change"，getDiff() -> "diff"
```

**SandboxWorker mock（BashTool 依赖）：**
```java
SandboxWorker worker = mock(SandboxWorker.class);
when(worker.execute(any(), any(), any())).thenReturn(
        new ProcessExecutionResult(ExecutionStatus.SUCCEEDED, 0, 1, "ok", false));
// worker.usesLinuxShell() 默认返回 false（interface default），需要时 when(...).thenReturn(true)
```

**ProcessExecutionResult record：** `(ExecutionStatus status, Integer exitCode, long durationMs, String output, boolean truncated)`；`succeeded()` 要求 `status==SUCCEEDED && exitCode==0`。

**ProcessExecutionRequest：** `new ProcessExecutionRequest(List<String> command, Path workingDir, Duration timeout, int maxOutputChars)`；`request.command()` 取回命令 List。

**ToolResult 访问器：** `isSuccess()`、`isApprovalRequired()`、`getApprovalCommand()`、`getContent()`、`getDiff()`、`getPendingChangeId()`。

---

## 各类测试用例清单

### 1. EditFileToolTest

构造：`new EditFileTool(mock(DiffService.class), mock(StudentProjectService.class))`（`studentProjectService` 字段被注入但 `execute()` 从不使用，随便 mock 空的即可）。

| 用例 | 输入 | 断言现状 |
|---|---|---|
| 空 file_path | `{}` 或空 path | `failed`，content = "file_path is required" |
| 空 old_string | 有 path 无 old_string | `failed`，content = "old_string is required" |
| 路径穿越 | path 让 `ToolSupport.resolve` 抛 `IllegalArgumentException` | `failed`，content = "Unsafe file path" |
| **文件不存在（死分支已验证）** | 合法 path 但文件未创建 | `failed`，content = "Unsafe file path"（**不是** "文件不存在"）。`ToolSupport.resolve` → `resolveExisting` 对不存在的 path 先抛 `IllegalArgumentException`，EditFileTool 捕获后返回 "Unsafe file path"，所以源码第 53-55 行的 "文件不存在: " 分支**永远走不到**。测试锁定真实行为并在注释标注该死分支 |
| old_string 不在文件中（无变化）| old_string 文件里没有 | `failed`，content = "未找到要替换的内容" |
| old_string == new_string | 两者相同（replace 后无变化）| `failed`，content = "未找到要替换的内容"（命中同一分支）|
| 正常替换成功 | old 在文件中出现至少两次、new 不同 | `isSuccess()==true`，精确验证 context/path/changeType/before，并断言完整 afterContent 中所有字面匹配项都被替换（锁定当前 `String.replace` 的全量替换语义） |
| 字段别名 | 用 `path`/`filePath`、`oldString`/`old_text`、`newString`/`new_text` | 别名能正确命中并成功 |

### 2. WriteFileToolTest

构造同上（`studentProjectService` 同样是死依赖）。

| 用例 | 输入 | 断言现状 |
|---|---|---|
| 空 file_path | 空 path | `failed`，"file_path is required" |
| 路径穿越 | `resolveForCreate` 抛异常 | `failed`，"Unsafe file path" |
| 覆盖已存在非空文件 | 文件已有内容 | 成功，`verify` stageAndApply 的 `changeType="modify"` 且 beforeContent 是真实旧内容 |
| 创建新文件 | 文件不存在 | 成功，`changeType="create"` 且 beforeContent="" |
| content 缺省 | 不传 content | 默认写空串 "" 且不报错（锁定现状：content 默认值是 ""）|
| 字段别名 | `file_content`/`fileContent`、`path`/`filePath` | 别名命中并成功 |

### 3. BashToolTest（逻辑最复杂，重点类）

构造：`new BashTool(mock(SandboxWorker.class))`。注意 `@Component` 已注释，但 `new` 出来照样能测（`CommandToolWorkerTest` 已经在这么用）。

| 用例 | 输入 | 断言现状 |
|---|---|---|
| 空命令 | command 为 blank | `failed`，"command is required" |
| HARD_BLOCKED | command 含 "shutdown" 等硬阻断词 | `failed` 且 `isApprovalRequired()==false`（不可审批放行）|
| HARD_BLOCKED 即使 allow_dangerous=true | 硬阻断词 + `allow_dangerous:true` | 仍然 `failed`（allow_dangerous 绕不过硬阻断）|
| NEEDS_APPROVAL 未批准 | command 含 "rm -rf" 无 allow_dangerous | `isApprovalRequired()==true`，`getApprovalCommand()==command` |
| NEEDS_APPROVAL 已批准放行 | "rm -rf" + `allow_dangerous:true` | 放行执行，`verify` worker.execute 被调用（证明 approved 传进了 safety check）|
| timeout clamp 下界 | `timeout_seconds:0` 或负 | `ArgumentCaptor<ProcessExecutionRequest>` 断言 timeout clamp 到 1 秒 |
| timeout clamp 上界 | `timeout_seconds:9999` | clamp 到 600 秒 |
| timeout 默认 | 不传 | 60 秒 |
| usesLinuxShell()==true | mock 返回 true | 根据 `Files.exists(Path.of("/bin/bash"))` 精确断言完整命令为 `[/bin/bash,-lc,command]` 或 `[/bin/sh,-lc,command]`，不是宽松“二选一” |
| 宿主 shell 分支 | mock `usesLinuxShell()==false` | Windows 精确断言 `[cmd.exe,/c,command]`；非 Windows 按 `/bin/bash` 是否存在精确断言 Unix 三元命令。按运行时条件计算期望，不 skip、不修改 `os.name` |
| taskId==null 的 runId | context.taskId 为 null | 不崩溃、正常执行（runId 前缀走 "agent-" 分支）|
| cancellationToken 转发 | 设置 context 的 token | `ArgumentCaptor<CancellationToken>` 断言传给 worker 的就是同一个 token |

> 宿主分支不通过修改全局 `os.name` 来强行覆盖另一平台；测试按当前 OS 计算唯一精确期望，因此 Windows 与 Linux CI 都执行而不 skip。

### 4. ApplyPatchToolTest（批量操作，重点类）

构造：`new ApplyPatchTool(mock(DiffService.class))`。

| 用例 | 输入 | 断言现状 |
|---|---|---|
| changes 缺失 | 无 changes 字段 | `failed`，"changes 参数必须是数组" |
| changes 非数组 | changes 是字符串/对象 | `failed`，"changes 参数必须是数组" |
| changes 空数组 | `changes:[]` | `failed`，"changes 不能为空" |
| 非 JsonObject 元素被跳过 | 数组含字符串元素 | 该元素跳过，其他正常处理 |
| 未知 operation 被跳过 | 先创建 path 对应的现有普通文件，再传 `operation:"rename"`，后面跟一个合法 change | `rename` 项在路径解析成功后进入 default 并被跳过，不调用 DiffService，后续合法项成功。若未知 operation 指向不存在或越界 path，会在 switch 前返回 "Unsafe file path"，不会被跳过 |
| 全部无效 | 数组全是非法元素 | `failed`，"没有有效的变更" |
| **首项已提交给 DiffService，后续坏路径令整体失败（已知控制流缺陷）** | 数组 = [合法create, 坏路径] | 断言第 1 项已调用一次 `stageAndApply`，最终 `ToolResult` 为 `failed("Unsafe file path")`。纯 mock 单测只证明该调用先发生且工具层没有补偿调用；**不得声称它验证了真实磁盘部分写入或 DiffService 实际无回滚** |
| create 不检查已存在（差异点）| create 一个已存在的文件 | 不报冲突、照样 stageAndApply，`changeType="create"`（对比 EditFileTool 有存在性检查）|
| **replace 找不到 old_string 仍提交（已知差异）** | replace 但 old_string 不在文件 | 断言 stageAndApply **被调用**（after==before 的"空变更"照样提交）——锁定与 EditFileTool "未找到要替换的内容→failed" 的行为差异 |
| delete 文件存在 | delete 已存在文件 | beforeContent = 真实内容，`changeType="delete"` |
| **delete 文件不存在（真实行为已验证）** | delete 不存在文件 | `failed`，content = "Unsafe file path"。`delete`/`replace` 走 `ToolSupport.resolve`（要求存在），文件不存在时先抛异常 → 返回 "Unsafe file path"，**不会**走到"beforeContent 取空串、提交空 delete change"那步。只有 `create` 走 `resolveForCreate` 允许不存在。测试锁定真实行为 |
| 多 change 混合 + firstChangeId | [create, replace, delete] 全合法；replace/delete 目标必须在调用前真实存在，不能依赖 mocked DiffService 把前面的 create 落盘 | 断言 `verify(times(3))`，用 captor/InOrder 校验处理顺序；`result.getContent()` 含“已自动应用 3 个文件变更”，`result.getDiff()` 精确等于 `diff1\ndiff2\ndiff3\n`，pendingChangeId 是首个成功项 ID。首元素被跳过场景单独断言 ID 来自后续第一个有效项 |

### 5. ProjectCommandSafetyTest

静态方法 `ProjectCommandSafety.check(String command, boolean approved)`，返回 `SafetyCheck`。无需 mock，直接传命令字符串触发分支。

| 用例 | 输入 | 断言现状 |
|---|---|---|
| prompt injection | "ignore previous ..." | `allowed()==false`，`riskLevel()=="blocked"`，matchedRule="prompt_injection" |
| HARD_BLOCKED 命中 | "shutdown now" | `allowed()==false`，`approvalRequired()==false`，level="blocked" |
| NEEDS_APPROVAL 命中未批准 | "rm -rf foo", approved=false | `allowed()==false`，`approvalRequired()==true`，level="approval_required" |
| NEEDS_APPROVAL 已批准消音 | "rm -rf foo", approved=true | **`allowed()==true`**（锁定现状：approved=true 时整个词根本不命中，风险等级降为 safe/normal，不留任何审批痕迹）|
| SAFE_PATTERNS 前缀命中 | "npm test" | `allowed()==true`，level="safe" |
| 普通命令 | "echo hi" | `allowed()==true`，level="normal" |
| **base64 绕过（已知漏洞）** | Java 测试字符串使用裸管道：`echo cm0gLXJmIC8=\|base64 -d\|sh`（表格中的 `\|` 仅为 Markdown 转义，Java 源码里不能带反斜杠） | 精确断言 `allowed()==true`、`approvalRequired()==false`、`riskLevel()=="normal"`、`matchedRule()==""`；测试名明确含 `knownDefect`，注释指向现有路线图 Phase 1，不得描述为安全通过 |
| **引号拆词绕过（已知漏洞）** | `r'm' -rf /` | 独立测试，精确断言当前 normal/空 matchedRule，并标记 Phase 1 |
| **`$IFS` 绕过（已知漏洞）** | `rm${IFS}-rf${IFS}/` | 独立测试，精确断言当前 normal/空 matchedRule，并标记 Phase 1 |
| null 命令 | `check(null, false)` | 不抛异常，走空串路径，`allowed()==true` normal |

## 验收标准

- 5 个测试文件全部新建完成。
- 执行前先保存一次完整 `mvn test` 基线：总数、failures、errors、skips、具体测试名和原因。
- 目标测试不得有 failure/error，预期执行的目标用例不得 skip。
- 新增后完整 `mvn test` 逐项与基线比较：任何新增 failure/error 必须修复；任何新增 skip 必须消除，或逐项证明为预期的平台/能力条件并记录。既有 failure/error 只有在测试名、异常类型和失败原因均未变化时，才能明确列为未变化环境基线，不能笼统写“已知环境问题”。
- 按行为分支和参数化 invocation 验收，不设“测试方法数量”KPI；五个目标类各有独立测试类，适用行为均有可识别覆盖。
- 所有"已知缺陷/差异"用例在参数化 case 名、方法名或相邻注释里明确标注，并指向现有路线图对应阶段。
- 不修改任何 `main/` 下生产代码。

## 不在范围内（留给后续 spec）

- 修复 `ProjectCommandSafety` 黑名单可绕过问题 → 「安全边界重构」spec
- 修复 `ApplyPatchTool` 首个 `stageAndApply` 调用后遇到坏路径仍整体失败的控制流，或验证真实磁盘回滚语义 → 归入后续可靠性 spec；本阶段纯 mock 测试不对磁盘状态作结论
- 修复 `ApplyPatchTool` replace 空变更提交 → 同上
- outbox 生产实现、`ToolCallExtractor` 加固 → 各自独立 spec
- 引入 `@SpringBootTest` 集成测试 → 明确不做，保持现有纯单测风格
