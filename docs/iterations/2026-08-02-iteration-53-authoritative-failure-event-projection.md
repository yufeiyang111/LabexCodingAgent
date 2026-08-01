# 第 53 轮：失败终态的权威实时投影

- 日期：2026-08-02
- 状态：已完成
- 权威计划：`docs/superpowers/plans/2026-07-29-agent-runtime-state-convergence.md`
- 前置提交：`96d517a fix: project authoritative terminal events`

## 背景

第 52 轮消除了正常完成分支的重复 `RUN_STATE_COMPLETED`，但继续审计所有 AgentLoop 终态出口后发现：失败分支虽然只持久化一条 `RUN_STATE_FAILED`，当前 POST SSE 却完全不投影这条 lifecycle 事件。live 连接只收到 `ERROR`、可见停止说明和 `DONE`，刷新/重连后才从 durable events 看到 `failed`。

这造成同一 task 的 live 与 replay 语义不一致：

```text
live POST SSE: ERROR -> FINAL -> DONE
replay SSE:    ERROR -> FINAL -> RUN_STATE_FAILED -> DONE
```

失败事件已经由 `AgentTaskService.updateTask("failed") -> AgentRunLifecycleService.transition(...)` 提交，因此不能再创建第二条事件；正确做法是复用第 52 轮的 `sendPersistedEvent(...)` 投影同一 sequence/payload。

## 受影响出口

`AgentLoopEngine` 中共有六个直接失败出口：

1. non-progress / hard iteration fuse；
2. context overflow 策略耗尽；
3. model first-response timeout；
4. recoverable model error 重试耗尽后的 API failure；
5. runtime exception；
6. provider 未提供稳定 tool call identity。

scheduler、DiffService、manual compaction 等无当前 POST SSE 的后台路径继续只提交 lifecycle 事件，不需要引入实时投影依赖。

## 设计

新增私有边界 `failTaskAndProject(...)`：

1. 调用 `taskService.updateTask(..., "failed", ...)`；
2. 获取唯一权威 `AgentRunEvent`；
3. 调用 `sendPersistedEvent(...)` 投影到当前连接并保存 conversation 派生历史；
4. 不调用 `sendEvent("RUN_STATE_FAILED")`，因此不增加第二条持久事件。

所有 AgentLoop 失败出口只通过该边界提交并投影 failed 状态。

## RED 证据

### 结构回归

新增 `AgentLoopEngineFailureProjectionContractTest`，要求：

- `AgentLoopEngine` 不再直接调用 `taskService.updateTask(taskId, "failed", ...)`；
- 至少六个失败出口调用统一的 `failTaskAndProject(...)`。

旧实现执行结果：1 个测试、1 failure，明确检测到直接 failed 状态写入调用仍存在。

### 真实系统回归

后端 acceptance 的 unverified completion 场景新增：

- direct SSE 必须包含带 `transition` 和 event id 的 `RUN_STATE_FAILED`；
- durable failed 事件恰好一条；
- direct/durable event id 相同；
- previous/next/actor/reason/epoch 审计合法；
- failed 状态事件必须先于 `DONE`。

旧 JAR 稳定失败为：

```text
Direct failure stream did not project the authoritative persisted RUN_STATE_FAILED event.
```

## 实施内容

`AgentLoopEngine` 新增 `failTaskAndProject(...)`，内部只做两件事：

1. 通过 `AgentTaskService.updateTask("failed")` 提交 lifecycle 状态迁移；
2. 把返回的同一 `AgentRunEvent` 交给 `sendPersistedEvent(...)`。

六个失败出口全部切换到该边界。没有新增 `RUN_STATE_FAILED` append 路径，也没有让 scheduler 或后台服务依赖 SSE。

事件顺序保留各失败分支原本的业务语义：之前在状态迁移前已持久化的 `ERROR` / `FINAL` 仍保持原顺序，权威 `RUN_STATE_FAILED` 在 lifecycle 提交点投影，并始终先于 `DONE`。

`scripts/acceptance/agent-runtime.ps1` 新增 `authoritativeFailureProjection` 证据和唯一性/cursor/审计检查。

## 验收结果

### 聚焦与后端全量

- `AgentLoopEngineFailureProjectionContractTest` RED：1 个测试、1 failure；
- 聚焦 failure projection、task service、lifecycle、final policy 测试：通过；
- `mvn -q -f backend/pom.xml test`：228 份 Surefire 报告，895 个测试，0 failure，0 error，8 skipped；
- `mvn -q -f backend/pom.xml -DskipTests package`：通过，真实验收使用重新打包 JAR。

### 后端真实系统验收

命令：

```powershell
& D:\LabexAgent\scripts\acceptance\agent-runtime.ps1 -BackendPort 18080 -TimeoutSeconds 180
```

结果：通过，run id `b7a765632441451e80750803d520ac57`。新增与关联证据：

```json
{
  "lifecycleTransitionAudit": true,
  "singleAuthoritativeTerminalEvent": true,
  "authoritativeFailureProjection": true,
  "unverifiedEditRejected": true,
  "isolatedDatabase": true,
  "cleanup": true
}
```

既有审批批准/拒绝重启、问题回复、checkout 竞争、Tool Part authority、manual compaction、static context blocker、completion evidence 和 normal profile provider isolation 继续通过。

### 浏览器真实验收

命令：

```powershell
& D:\LabexAgent\scripts\acceptance\browser-runtime.ps1 `
  -BackendPort 18080 -FrontendPort 13000 -CdpPort 19222 `
  -TimeoutSeconds 180 -RestartBackendForAcceptance
```

结果：通过，运行环境 run id `8e3472b5a44744ad8bb0066056c5124d`，浏览器报告 run id `fb2f4985bd724e57bf4ff41e2d32182d`。关键证据：

- `refreshReplayDeduplicated = true`
- `restartProjectionVerified = true`
- `restartInteractionVerified = true`
- `recoverableWaitNoPseudoTerminal = true`
- `environmentRetrySameTask = true`
- `environmentFinalCount = 1`
- `environmentDoneCount = 1`
- `completionEvidenceCard = true`
- `unverifiedCompletionBlocked = true`
- `consoleErrors = 0`
- `networkErrors = 0`

### 前端、构建与编码

- `npm.cmd test`：192/192 通过；
- `npm.cmd run test:acceptance:unit`：10/10 通过；
- `npm.cmd run build`：通过；
- chunk 预算通过：CloudWorkspace 1,462,573 / 1,500,000，index 1,259,534 / 1,300,000，TerminalPanel 380,367 / 400,000；
- 构建仅有第三方 `@vueuse/core` PURE 注释位置警告；
- 源码编码测试：通过；
- `git diff --check`：待暂存后执行最终检查。

## 验收结论

AgentLoop 的正常完成和失败终态现在都遵循同一原则：lifecycle service 只提交一次权威状态事件，direct SSE、task subscription、durable replay 和 conversation 派生历史复用同一 sequence/payload。当前连接不再需要等刷新后才知道 task 已失败，也不会为实时反馈制造第二条状态事实。

## 剩余边界与下一轮

- 取消路径目前通过 `finalizeCancellation(...)` 返回 boolean，当前 POST SSE 发送 `INTERRUPTED` 和最终说明，但未显式复用 `RUN_CANCELLED` lifecycle event；需要下一轮审计其 live/replay 一致性和两步 `cancelling -> cancelled` 事件顺序；
- retry_backoff、waiting_environment、waiting_workspace 等可恢复态已经使用 `TASK_PAUSED`/阻塞事件并通过浏览器验收，但仍需在最终架构审计中确认它们的 direct cursor 与 lifecycle event 是否存在可解释的投影边界；
- 后台失败没有当前 direct SSE，不应为了“统一代码”错误引入 UI 依赖。