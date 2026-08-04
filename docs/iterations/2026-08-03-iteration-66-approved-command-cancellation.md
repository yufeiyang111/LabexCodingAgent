# 第 66 轮：批准后命令执行的取消传播与持久化终态

- 日期：2026-08-03
- 分支：`codex/agent-tool-reliability`
- 前置提交：`46b65af fix: cancel hanging provider requests`
- 状态：进行中

## 1. 本轮目标

修复命令经过用户批准后脱离 Agent task cancellation 链的问题，确保 `mvn test`、`npm test`、Gradle、pytest 等长命令在用户点击中断后能够真实停止进程树，并将任务、事件、Provider transcript 和 Tool Part 收敛到同一个可恢复终态：

1. 批准后的命令执行必须注册与原 `sessionId/studentId/projectId/taskId` 相同的 active run；
2. `AgentApprovedCommandExecutor` 必须把该 token 原样传给 `SandboxWorker`；
3. `/interrupt` 必须先持久化 `WAITING_APPROVAL -> CANCELLING`，再唤醒同一 token；
4. `LocalProcessExecutor` 返回 `ExecutionStatus.CANCELLED` 后，编排器必须停止网络重试、环境分类和 Agent resume；
5. tool result 必须以协议安全文本持久化，原 Tool Part 必须进入 `interrupted`；
6. task 必须从 `CANCELLING` 进入 `CANCELLED`，不能永久卡住；
7. HTTP 执行响应、刷新后的 task snapshot 与可重放事件都必须显示 cancelled/interrupted，而不是 failed 或 running；
8. 系统验收必须启动真实长进程并证明中断后在严格时间预算内退出。

## 2. 已确认根因

当前链路：

```text
waiting_approval
  -> 用户 approve
  -> POST /command-approvals/{id}/execute
  -> CommandApprovalOrchestrator.execute
  -> AgentApprovedCommandExecutor.execute
  -> SandboxWorker.execute(..., CancellationToken.none())
```

存在四个直接缺口：

- 原 Agent loop 在进入 `waiting_approval` 后结束并从 `AgentCancellationRegistry` 注销；
- 批准后的执行没有重新注册 active run，`/interrupt` 查不到正在运行的命令；
- `cancelInactiveRun` 只允许 `retrying/waiting_workspace/waiting_environment`，不会取消 `waiting_approval`；
- 即使底层未来返回 `CANCELLED`，现有 orchestrator 仍按 failed 路径记录并尝试 resume，且没有执行 `CANCELLING -> CANCELLED`。

因此前端“中断成功”的 HTTP 返回不等于 Maven/npm 子进程已停止，任务也可能长期停在等待或取消中状态。

## 3. OpenCode 对照

只迁移不变量，不照搬 TypeScript/Bun 实现：

- `D:/opencode/opencode-dev/packages/opencode/src/session/tools.ts` 把同一 `AbortSignal` 放进每个 Tool Context；
- `D:/opencode/opencode-dev/packages/opencode/src/tool/shell.ts` 在 abort/timeout 竞争中主动调用 process handle 的 kill；
- `D:/opencode/opencode-dev/packages/opencode/src/shell/shell.ts` 在 Windows 使用 `taskkill /f /t`，在 POSIX 先终止进程组再强杀。

LabexAgent 已有等价底层能力：`LocalProcessExecutor` 每 50ms 检查 `CancellationToken`，并终止父进程和 descendants。缺失的是批准后执行路径没有传入 task token，而不是需要再造第二套 process registry。

## 4. 架构归属

| 事实/动作 | 唯一归属 |
|---|---|
| durable cancellation intent | `AgentTask` + `AgentRunLifecycleService` |
| 当前可中断执行身份 | `AgentCancellationRegistry.ActiveRun` |
| 审批命令编排与 active-run 作用域 | `CommandApprovalOrchestrator` |
| argv/网络 grant/worker 请求构造 | `AgentApprovedCommandExecutor` |
| 进程树终止 | `SandboxWorker` / `LocalProcessExecutor` |
| Provider tool result | `AgentRunTranscriptService` |
| Tool Part 终态与事件 | `AgentRunPartService` / `AgentToolCallJournalService` |

禁止新增静态 PID Map、controller 线程状态、前端本地取消计时器或命令专用第二状态机。

## 5. 设计

### 5.1 active-run 作用域

`CommandApprovalOrchestrator` 在 capability 成功 consume 后、发布 `COMMAND_EXECUTION_STARTED` 前：

1. 使用 approval 上的稳定 session/task 身份注册 `ActiveRun`；
2. 把该对象作为 `CancellationToken` 传给 executor；
3. 无论成功、失败、取消或异常，最终都按对象身份调用 `complete(activeRun)`；
4. 不使用仅按 sessionId 的 `reset`，避免误删后继 epoch 的 active run。

### 5.2 执行结果分支

- `SUCCEEDED/FAILED/TIMED_OUT/INFRASTRUCTURE_ERROR` 保留既有结果、重试和 resume 语义；
- `CANCELLED` 单独处理：
  - 记录非敏感 `EXECUTION_INTERRUPTED` audit；
  - 发送 `COMMAND_EXECUTION_CANCELLED` durable event；
  - 写入 `status=interrupted` 的 tool result；
  - 将 UI/兼容 Tool Part 和 Provider `tool_call` Part 标记 `interrupted`；
  - 调用 `AgentTaskService.finalizeCancellation`；
  - 禁止 network retry、environment blocker、failure guard 和 resume scheduler。

### 5.3 Part 选择修正

`AgentRunPartService.resolveExistingToolCall` 不能按 `toolCallId LIMIT 1` 任意命中 `tool_result`。本轮将：

- 先同步同 ID 的 Provider `tool_call` Part；
- 优先更新 UI/兼容 `partType=tool`；
- 仅为兼容旧数据在缺失 UI Tool Part 时回退到 `partType=tool_call`；
- 永不把 `tool_result` 当成待更新 Tool Call。

### 5.4 HTTP 投影

命令执行接口按 `ExecutionStatus` 输出 `completed/failed/timed_out/cancelled/infrastructure_error`，不能把取消伪装为 failed；`resumeAgentLoop` 只在 orchestrator 返回 `resuming` 时为真。

## 6. RED 场景

1. executor 的 cancellation-aware overload 必须把完全相同的 token 传给 worker；当前实现失败；
2. orchestrator 必须以 approval 身份注册 active run，并把它传给 executor；当前实现失败；
3. `CANCELLED` 结果必须 finalize task、标记 Tool Part interrupted 且不 resume；当前实现失败；
4. Tool Part 终态更新不能误命中同 ID 的 `tool_result`；当前查询无 part type 约束，应失败；
5. 真实 Java 长进程在批准后执行，registry cancel 后必须在 3 秒内返回 `CANCELLED`；当前 executor 使用 none，应超时或失败；
6. 系统 acceptance 的 30 秒命令必须在 interrupt 后 5 秒内结束，task 为 cancelled，Tool Part 为 interrupted。

## 7. 预计修改范围

- `backend/src/main/java/com/labex/labexagent/commandsecurity/AgentApprovedCommandExecutor.java`
- `backend/src/main/java/com/labex/labexagent/commandsecurity/CommandApprovalOrchestrator.java`
- `backend/src/main/java/com/labex/labexagent/run/AgentRunPartService.java`
- `backend/src/main/java/com/labex/labexagent/run/AgentToolCallJournalService.java`
- `backend/src/main/java/com/labex/labexagent/controller/StudentAgentController.java`
- `backend/src/main/java/com/labex/labexagent/llm/AcceptanceScriptedProvider.java`
- 对应后端测试
- `scripts/acceptance/agent-runtime.ps1`
- 本文档

## 8. 验收门槛

- RED/GREEN 单元与本地真实进程回归；
- command approval、cancellation、transcript/Part 相关定向测试；
- 后端全量测试；
- 前端全量测试与构建；
- 重新打包后的独立 runtime acceptance；
- 完整 `run-all.ps1` 浏览器验收；
- 单独本地 Git 提交，并继续排除原工作区改动。

## 9. 实施记录

本轮已完成以下实现：

- `AgentApprovedCommandExecutor` 保留旧的两参数兼容入口，同时新增带 `CancellationToken` 的执行入口；批准命令不再调用 `CancellationToken.none()`，而是把 `CommandApprovalOrchestrator` 注册的同一 ActiveRun token 传给 worker。
- `CommandApprovalOrchestrator` 在消费审批能力后、发布 `COMMAND_EXECUTION_STARTED` 前注册 ActiveRun，并在所有结果路径 finally 中按对象身份清理；不使用仅按 session 的 reset，避免误删后续运行。
- `ExecutionStatus.CANCELLED` 进入独立的取消终态分支：记录中断审计、写入 `status=interrupted` 的 tool result、标记 Provider `tool_call` 与 UI/兼容 Tool Part、发送 `COMMAND_EXECUTION_CANCELLED`，调用 `AgentTaskService.finalizeCancellation`，且不触发网络重试、环境阻塞、失败保护或 Agent resume。
- 取消投影采用 best-effort 但终态优先：transcript、Tool Part、事件或元数据刷新任一步失败，仍继续执行 `finalizeCancellation`，不会由外层异常兜底把已经取消的任务误转为 `FAILED`；最终只能返回 `cancelled` 或明确的 `cancelling`。
- `AgentRunPartService.resolveExistingToolCall` 先同步 Provider `tool_call`，再优先选择 `partType=tool`，兼容回退到 `tool_call`，不再把 `tool_result` 当成待更新的 Tool Call。
- `StudentAgentController` 按真实 `ExecutionStatus` 输出 `cancelled`、`timed_out`、`infrastructure_error` 等状态，不把取消伪装成失败。
- `AcceptanceScriptedProvider` 增加真实工作区相对路径的 30 秒 Java 长进程 fixture；`scripts/acceptance/agent-runtime.ps1` 新增批准后执行、实时 interrupt、持久化 Part/事件/任务终态检查。
- 新增/更新审批编排、真实本地进程取消、Part 权威性、Controller 状态投影和 acceptance provider 回归测试。

## 10. 验收结果

验证日期：2026-08-04（本轮工作从 2026-08-03 开始）。所有命令均在当前工作区执行，且未把原有工作区改动纳入本轮提交。

### 10.1 RED/GREEN

- RED：新增三参数 executor 入口前，真实取消集成测试因 `NoSuchMethodException` 失败；取消投影失败回归测试先观察到 `ExecutionResult.available=false`，证明外层异常路径会绕过取消终态。
- GREEN：实现 token 传播、ActiveRun 注册、独立 `CANCELLED` 分支和投影降级后，定向测试全部通过。

定向后端测试：

```text
mvn -q -f backend/pom.xml -Dtest=AgentApprovedCommandCancellationIntegrationTest,AgentApprovedCommandExecutorTest,CommandApprovalOrchestratorTest,CommandApprovalDatabaseConcurrencyTest,AgentRunPartServiceTest,AgentRunTranscriptServiceTest,StudentAgentControllerCommandApprovalTest,StudentAgentControllerCancellationTest,AcceptanceScriptedProviderTest test
通过
```

### 10.2 全量代码验证

- 后端：`mvn -f backend/pom.xml test` —— `960 tests, 0 failures, 0 errors, 8 skipped`，`BUILD SUCCESS`。
- 前端：`npm test` —— `209 tests, 209 pass, 0 fail, 0 skipped`。
- 前端：`npm run build` —— `built in 59.89s`，CloudWorkspace、index、TerminalPanel 三个 bundle 均在既有预算内。
- PowerShell acceptance 脚本解析：`PS_PARSE_OK`。
- `git diff --check`：通过。

### 10.3 独立运行时与系统验收

执行：

```text
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/acceptance/run-all.ps1 -BackendPort 18118 -FrontendPort 13038 -CdpPort 19260 -TimeoutSeconds 240 -RestartBrowserBackend
```

结果：`package=true`、`acceptanceUnitTests=true`、`backendRuntime=true`、`browserRuntime=true`。

- 后端运行时 runId：`62f549b5ef704678bcccfd32d47e102b`。
- 批准命令取消：`approvedCommandCancellation=true`，`approvedCommandCancellationElapsedMs=357`；任务、Provider `tool_call`、兼容 Tool Part、`tool_result` 和取消事件均通过持久化断言。
- 压缩取消：`compactionCancellationTerminal=true`，`compactionCancellationElapsedMs=494`。
- 其余既有审批恢复、命令拒绝、checkout contention、transcript/Part projection、context blocker、completion evidence、outbox repair 等后端门禁均为 `true`。
- 浏览器 runId：`1cc36c621e8e4f3bad833e84c04c1a0c`。
- 浏览器桌面布局、审批刷新恢复、问题回复组件、命令稳定 Tool identity、持久化上下文压缩、重启投影、模型重试和环境重试等门禁均为 `true`。
- 浏览器 `consoleErrors=0`、`networkErrors=0`；桌面 viewport 为 `1440x900`，不是移动端布局。
- 验收结束后检查端口 `18118/13038/19260`，未发现残留监听；仅确认与本轮验收相关的进程已退出。

## 11. 当前边界与后续工作

本轮目标已完成，但以下内容刻意没有伪装成已解决：

1. acceptance 使用隔离 H2、脚本 Provider 和本地工作区，证明的是 LabexAgent 运行时/持久化协议，不等价于真实云 Provider、生产 MySQL 或真实 Maven 项目网络环境。
2. `StudentAgentController` 中仍保留一个仅在 `CommandApprovalOrchestrator` 为空时触发的旧直执行兼容分支；正常 Spring 运行使用 orchestrator，但该旁路仍不满足唯一编排入口原则，下一轮应删除或迁移后彻底禁止。
3. 已消费审批在 JVM 崩溃瞬间的跨重启 orphan reconciliation 尚未完成；本轮覆盖的是同一 JVM 内的用户 interrupt、进程树终止和 durable cancellation projection。
4. 批准执行接口仍是同步 HTTP 请求；取消已经能真实终止命令，但若要完全对齐 durable worker 模型，还应把执行请求本身改为可查询的持久化后台作业。
5. 工作区中原有的 `backend/src/main/resources/application-acceptance.yml`、`.codex-tmp/` 及其他未纳入本轮范围的文档/计划改动均保留，未被本轮提交覆盖。
