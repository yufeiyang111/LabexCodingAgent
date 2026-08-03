# 第 59 轮：删除不可达的第二套命令执行运行时

- 日期：2026-08-03
- 分支：`codex/agent-tool-reliability`
- 对应计划：架构收敛计划任务八“删除旧路径”
- 状态：完成

## 1. 审计起点

第 58 轮后继续审计 conversation-level 手动压缩与 task-level Provider compaction。当前 UI 的 `/compact` 已在 `CloudWorkspace.vue` 中被专门拦截，调用 `/agent/conversations/{conversationId}/compact`，由 `ManualCompactionTaskRunner` 创建 durable task、发布 compaction lifecycle event，并把 `COMPACTION_SUMMARY` 注入下一条普通消息的新 task 初始上下文。

因此，手动压缩不是“显示成功但完全无效”。它是 conversation memory compaction，与单个 Agent task 内的 Provider transcript compaction 作用域不同。

审计同时发现真正的并行旧路径：`CommandExecutor.java` 共 1119 行，内部另行实现 `/compact`、`/init`、`/review`、文件写入、build/test、依赖、索引、checkpoint 等 70 多个命令，但生产调用图中不存在任何 `commandExecutor.execute(...)`。

## 2. 真实生产路径

当前 slash command 路径是：

```text
CloudWorkspace
  -> POST /agent/commands
  -> AgentCommandService.runCommand
  -> CommandRegistry / CommandInfo.resolveTemplate
  -> 前端把解析后的模板作为普通 Agent 用户目标发送
  -> AgentLoopEngine
  -> ToolRegistry + permission/approval + durable task/transcript
```

`AgentCommandService` 虽注入 `CommandExecutor`，但从未读取该字段。`CommandExecutor` 的副作用实现不经过当前 Agent task、tool Part、审批和 SSE reducer，是一套完全不可达、却可能误导后续维护者重新接入的第二运行时。

## 3. 本轮目标

1. 删除 `CommandExecutor.java`；
2. 删除 `AgentCommandService` 中闲置 import、字段和构造参数；
3. 删除只测试不可达旧实现的 `CommandExecutorInitTest`；
4. 保持 `CommandRegistry`、`CommandInfo`、`POST /agent/commands` 和前端 slash command 契约不变；
5. 保持 `/compact` 继续走异步 durable manual compaction；
6. 用回归测试禁止未来重新引入第二套命令副作用运行时。

## 4. RED 设计

新增架构测试要求：

- `CommandExecutor.java` 不存在；
- `AgentCommandService` 不包含 `CommandExecutor`；
- 当前命令解析仍调用 `commandInfo.resolveTemplate(arguments)`。

先只修改测试运行：

```powershell
cd D:\LabexAgent\backend
mvn -q '-Dtest=AgentCommandArchitectureContractTest' test
```

预期在文件存在断言处失败，证明测试直接命中当前旧路径。

## 5. 验收计划

1. 取得 RED；
2. 删除不可达实现和闲置注入；
3. 补 `AgentCommandService.runCommand` 模板解析行为测试；
4. 验证前端 `/compact` 专用路径、普通 slash command、`/init` 模板和命令审批系统；
5. 运行后端全量、前端测试/构建、后端重启与 Chromium 验收；
6. 完成文档并创建独立本地提交。
## 6. RED 结果

聚焦架构测试按预期失败：

```text
Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
AgentCommandArchitectureContractTest.removesTheUnreachableSecondCommandExecutionRuntime
expected: <false> but was: <true>
```

失败发生在 `Files.exists(CommandExecutor.java)`，证明旧执行器仍真实存在，而不是文档误差。

## 7. 实施内容

### 7.1 删除不可达运行时

删除：

- `backend/src/main/java/com/labex/labexagent/command/CommandExecutor.java`：1119 行；
- `backend/src/test/java/com/labex/labexagent/command/CommandExecutorInitTest.java`：只覆盖旧执行器的测试。

全仓库生产调用图原本就不存在 `commandExecutor.execute(...)`，因此删除没有移除当前可达功能。

### 7.2 收敛 AgentCommandService

删除 `AgentCommandService` 中：

- `CommandExecutor` / `CommandResult` import；
- 闲置字段；
- 闲置构造参数和赋值。

当前服务只保留真实职责：项目归属、conversation event、`CommandRegistry` 模板解析、用户模型提示词优化。

### 7.3 保留并固定真实命令契约

新增行为回归验证：

- `/review src/App.vue` 通过 `CommandInfo.resolveTemplate` 得到 `Review this target: src/App.vue`；
- 用户命令和 `COMMAND` event 仍持久化；
- 实际 `CommandRegistry` 仍加载 72 个命令；
- `/init` 模板非空；
- `summarize` 别名仍解析到非空 compact 模板；
- `/compact` 的 durable `ManualCompactionTaskRunner` 与 command approval 回归通过。

### 7.4 删除错误的“完全复刻”表述

`CommandInfo`、`CommandRegistry`、`AgentCommandService` 的类注释不再宣称“完全复刻 OpenCode”。现在明确：

- command 只是元数据和模板；
- 文件、Git、测试、构建、系统命令等副作用统一交给 Agent Tool + permission/approval + durable task。

`AGENTS.md` 同步新增禁止重新引入并行 `CommandExecutor` 的强制规则。

## 8. 修改文件

- `AGENTS.md`
- 删除 `backend/src/main/java/com/labex/labexagent/command/CommandExecutor.java`
- `backend/src/main/java/com/labex/labexagent/command/CommandInfo.java`
- `backend/src/main/java/com/labex/labexagent/command/CommandRegistry.java`
- `backend/src/main/java/com/labex/labexagent/service/AgentCommandService.java`
- 删除 `backend/src/test/java/com/labex/labexagent/command/CommandExecutorInitTest.java`
- 新增 `backend/src/test/java/com/labex/labexagent/service/AgentCommandArchitectureContractTest.java`
- `backend/src/test/java/com/labex/labexagent/service/AgentCommandServicePromptOptimizationTest.java`
- `docs/iterations/2026-08-03-iteration-59-remove-dead-command-runtime.md`

## 9. GREEN 与真实验收

### 9.1 聚焦回归

```powershell
cd D:\LabexAgent\backend
mvn -q '-Dtest=AgentCommandArchitectureContractTest,AgentCommandServicePromptOptimizationTest,ManualCompactionTaskRunnerTest,StudentAgentControllerCommandApprovalTest' test
```

结果：通过。日志确认实际 `CommandRegistry` 注册 72 个命令，模板解析路径执行成功。

### 9.2 后端全量

```powershell
cd D:\LabexAgent\backend
mvn test
```

结果：

- tests：928；
- failures：0；
- errors：0；
- skipped：8；
- `BUILD SUCCESS`。

### 9.3 前端、验收单测与构建

```powershell
cd D:\LabexAgent\frontend
npm test
npm run test:acceptance:unit
npm run build
```

结果：

- 前端：196/196；
- acceptance unit：12/12；
- Vite production build：通过；
- `CloudWorkspace`：1,463,644 / 1,500,000 bytes；
- `index`：1,259,534 / 1,300,000 bytes；
- `TerminalPanel`：380,367 / 400,000 bytes。

### 9.4 后端重启系统验收

命令：

```powershell
.\scripts\acceptance\run-all.ps1 -BackendPort 18089 -FrontendPort 13009 -CdpPort 19231 -TimeoutSeconds 180 -RestartBrowserBackend
```

后端运行 ID：`5213fe631fea4fb899bf0f8e6577bde8`。

通过项包括 question/permission/command approve/command reject restart、manual compaction、Message/Part 投影、生命周期审计、正常/失败/取消/retry 权威事件、严格工具输入、completion evidence、隔离数据库和 cleanup。

### 9.5 Chromium 与 JVM handoff

浏览器运行 ID：`56e65b084d1240f38aba71642dbe1fd3`。

关键结果：

- 桌面布局、会话隔离、刷新回放去重：通过；
- question、permission、多工具审批批次：通过；
- durable compaction：epoch `1`，tokens `23744 -> 22024`，messages `7`；
- manual compaction + fork + 刷新：通过；
- interaction wait 和 compaction 后 JVM handoff：通过；
- Provider 流中断、model retry live projection：通过；
- recoverable wait 无伪终态：通过；
- environment retry 保持同一 task：通过；
- console errors：0；
- network errors：0。

## 10. 保留的原工作区改动

未暂存、未提交：

- `backend/src/main/resources/application-acceptance.yml`；
- `.codex-tmp/`；
- `docs/agent-context-provider-smoke-test.md`；
- `docs/coding-agent-engineering-roadmap.md`；
- `docs/coding-agent-industrialization/`；
- 既有未跟踪 `docs/superpowers/plans/` 与 `docs/superpowers/specs/`。

## 11. 结论与后续

第 59 轮删除了 1197 行只服务不可达命令运行时的生产/测试代码，保留了当前真实命令模板、Agent 工具、审批和 durable task 路径。

下一轮应继续审计 `CommandRegistry` 中各命令的契约类型：

1. 纯 prompt 命令可以继续模板化；
2. UI 会话动作（new/rename/fork/export）不应伪装成 Agent prompt；
3. 文件/Git/测试/部署等动作必须明确由 Agent Tool 执行并经过权限策略；
4. 不得因为旧执行器已删除，就让无实际能力的命令继续在 UI 中显示为“可直接执行”。