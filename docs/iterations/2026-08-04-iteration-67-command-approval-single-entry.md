# 第 67 轮：审批命令唯一入口与跨重启孤儿恢复

- 日期：2026-08-04
- 分支：`codex/agent-tool-reliability`
- 前置提交：`0033e65 fix: cancel approved command executions`
- 状态：已完成

## 1. 本轮目标

在第 66 轮已实现批准命令可中断的基础上，继续收敛审批执行架构：

1. 控制器只做身份验证和序列化，审批决策与执行只经过 `CommandApprovalOrchestrator`；
2. 删除控制器中会消费 approval 、直接启动 worker 、直接更新 task 的旧旁路；
3. 审计已消费的 approval 在 JVM 崩溃或重启时的状态，明确 orphan reconciliation 的归属和充足证据；
4. 不把同一 JVM 内的 ActiveRun 内存注册误当成重启后的持久执行状态。

## 2. 已确认问题

`StudentAgentController` 当前同时保留了两条路径：

```text
正常 Spring 路径 -> CommandApprovalOrchestrator
兼容旁路 -> controller consume -> approvedCommandExecutor -> taskService.updateTask
```

旁路绕过了 ActiveRun注册、取消 token传播、持久 transcript/Part、事件和恢复调度器，是一个已被第 66 轮证明不可保留的第二状态机。

另一个待核查点是：approval 被 `consume` 之后，执行进程之前如果 JVM 崩溃，数据库中可能只剩 `consumed` 状态。目前没有证据表明这条记录能在重启后被安全收敛，因此先做读路审计和防止不充分的自动执行。

## 3. 实施计划

1. 先以 Controller 回归测试固定“旁路不得执行”和“只委托 orchestrator”。
2. 删除 Controller 中的旧审批 service/executor/project 依赖、消费逻辑和 task 直写逻辑，简化构造器重载。
3. 读路追踪 `CommandApprovalService.consume`、`CommandApprovalOrchestrator.execute`、`AgentTask` 状态和重启恢复 scheduler；若缺少安全、可判断的归属，不盲目增加自动重试。
4. 若存在已有可复用的启动恢复框架，为 consumed approval 增加只读的 orphan 标记或可审计的不可自动重放终态；没有足够证据时本轮只完成审计和防重复契约。
5. 完成后运行后端、前端、独立运行时和浏览器系统验收，并创建本轮本地 Git 提交。

## 4. 验收门槛

- Controller 不再访问 `CommandApprovalService.consume`、`AgentApprovedCommandExecutor.execute` 或 `AgentTaskService.updateTask` 来处理审批执行。
- 决策和执行都由单一 `CommandApprovalOrchestrator` 拥有，控制器只序列化已持久化的结果。
- 已有审批回归测试和新增旁路禁用测试通过。
- 运行时没有将孤儿 approval 自动重放为新命令；已记录明确的恢复/人工处理路径或待办风险。


## 5. 实施记录

- `StudentAgentController` 移除 `CommandApprovalService`、`AgentApprovedCommandExecutor` 和 `StudentProjectService` 的直接依赖，不再在 Controller 中 consume approval、启动 worker、刷新元数据或直写 task。
- 审批决策和执行统一委托 `CommandApprovalOrchestrator`；如组件未注入，接口只返回明确的 `approvalUnavailable`，绝不启动任何兼容执行路径。
- 保留的旧构造器重载仅用于非 Spring 测试对象创建；没有 orchestrator 时请求不会降级为旧执行。
- `AgentRunRecoveryService` 增加重启后的审批执行不确定分类：当 task 仍是 `waiting_approval`、最新 agent approval 已是 `consumed` 且没有 durable tool result 时，发布幂等事件 `COMMAND_EXECUTION_RECOVERY_UNCERTAIN`，固定 `automaticReplay=false`，不自动重放命令。
- 为旧的 7 参数恢复服务测试保留兼容构造器，Spring 正式注入新增的 approval/transcript 依赖。
- 修复新增 Java 注释被 Windows 终端转码成 `four replacement markers` 的问题，前端源码编码回归测试已能检出并阻止该问题。

## 6. RED/GREEN 记录

- RED 1：新增的旁路禁止回归测试在旧 Controller 上返回 `completed`，证明旁路会真实执行。
- RED 2：修改测试使用唯一 orchestrator 构造器后，旧类型编译失败，证明 Controller 实际还依赖旧服务。
- RED 3：重启恢复回归测试在新构造器加入前编译失败，固定了重启后对 consumed approval 的处理契约。
- GREEN：上述测试均已转绿，新回归测试验证“标记不确定但不重放”。

## 7. 验收结果

验证日期：2026-08-04。

- 定向后端：`StudentAgentControllerCommandApprovalTest`、`AgentRunRecoveryServiceTest`、`CommandApprovalResumeSchedulerTest`、批准取消集成和相关 Controller 测试通过。
- 后端全量：`mvn -f backend/pom.xml test` —— `962 tests, 0 failures, 0 errors, 8 skipped`，`BUILD SUCCESS`。
- 前端全量：`npm test` —— `209 tests, 209 pass, 0 fail, 0 skipped`。
- 前端构建：`npm run build` —— `built in 45.27s`，包大小预算通过。
- 独立系统验收：`powershell -NoProfile -ExecutionPolicy Bypass -File scripts/acceptance/run-all.ps1 -BackendPort 18119 -FrontendPort 13039 -CdpPort 19261 -TimeoutSeconds 240 -RestartBrowserBackend`全部通过，包包含重新打包后的后端运行时和浏览器验收。
- 后端 runId：`abcfe1c66926411fb535511d46655aa0`；`approvedCommandCancellation=true`，`approvedCommandCancellationElapsedMs=353`，`compactionCancellationElapsedMs=494`。
- 浏览器 runId：`6c675c5460504829b2c2b7248a75eb82`；桌面布局、重启投影、审批恢复、上下文压缩和输入问题组件均通过。
- 浏览器 `consoleErrors=0`、`networkErrors=0`，三个验收端口不再监听。

## 8. 仍未完成的边界

1. `COMMAND_EXECUTION_RECOVERY_UNCERTAIN` 是跨 JVM 重启的安全分类和防重放证据，不是命令进程的杀死机制。当前 approval 没有持久进程 PID/进程组租约，重启后不应自动猜测并杀进程。
2. 下一轮需要将批准执行变成可持久的 execution record，在启动进程前记录可回收的进程身份和租约，才能在重启后安全地释放或终止。
3. 本轮没有改动生产数据或强制结束任何不确定命令；该路径会让前端可见事件并保留人工处理余地。
