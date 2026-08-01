# 第 52 轮：终态事件的权威实时投影

- 日期：2026-08-02
- 状态：已完成
- 权威计划：`docs/superpowers/plans/2026-07-29-agent-runtime-state-convergence.md`
- 前置提交：`74641e6 fix: persist lifecycle transition audit metadata`

## 问题现象

真实系统验收发现，一个正常完成的 Agent task 在 `AgentRunEvent` 中会出现两条同名 `RUN_STATE_COMPLETED`：

1. `AgentTaskService.updateTask(..., "completed", ...)` 调用 `AgentRunLifecycleService.transition(...)` 持久化的权威状态迁移；
2. `AgentLoopEngine` 为了让当前 POST SSE 立即看到完成态，又调用 `sendEvent(..., "RUN_STATE_COMPLETED", ...)`，而 `sendEvent` 最终进入 `AgentSsePublisher.send(...) -> lifecycle.appendEvent(...)`，持久化了第二条同名普通事件。

第一条包含第 51 轮加入的 `transition` 审计信封；第二条不包含。历史回放因此无法仅凭事件名区分权威状态事实和兼容实时投影。

## 根因链路

```text
AgentLoopEngine final branch
  -> taskService.updateTask("completed")
      -> lifecycle.transition(...)
          -> AgentRunEvent #N: RUN_STATE_COMPLETED + transition
          -> outbox / Run Part
  -> sendEvent("RUN_STATE_COMPLETED")
      -> AgentSsePublisher.send(...)
          -> lifecycle.appendEvent(...)
              -> AgentRunEvent #N+1: RUN_STATE_COMPLETED without transition
          -> current POST SSE frame
```

根因不是 reducer 重复执行，也不是 outbox 至少一次投递，而是执行引擎把“向当前连接投影已经持久化的事件”错误实现成了“再次创建并持久化一个事件”。

## 已有正确模式

- `AgentRunLifecycleService.transition(...)` 返回包含已持久化 `AgentRunEvent` 的 `TransitionResult`；
- `AgentSsePublisher.sendPersisted(sequence, type, payload)` 能发送已有事件，不会再次调用 `appendEvent`；
- task subscription 和 replay controller 都以持久化 event sequence 为 SSE event id；
- 前端 cursor store 以 event id 去重和恢复，因此 direct SSE 也应发送同一权威 sequence，而不是生成新的 sequence。

## 修复假设

如果 `AgentTaskService.updateTask(...)` 把 lifecycle transition 产生的权威 `AgentRunEvent` 返回给调用方，并由 `AgentLoopEngine` 使用 `sendPersisted(...)` 把该事件投影到当前 POST SSE，同时继续写入 conversation 派生历史，那么：

- 数据库只保留一条 `RUN_STATE_COMPLETED`；
- direct SSE 立即收到同一条事件及其 `transition`；
- direct SSE、task subscription、断线回放共享同一个 event id；
- 前端现有 reducer 无需新增第二套状态；
- `DONE` 仍作为完成后的流结束事件单独持久化。

## 计划

1. 先增加失败回归，要求 task service 返回 lifecycle event，系统验收要求 durable 完成事件恰好一条且 direct SSE 收到审计信封；
2. 让 `AgentTaskService.updateTask(...)` 返回权威 lifecycle event，其他忽略返回值的调用点保持兼容；
3. 在 `AgentLoopEngine` 增加“投影已有持久事件”辅助方法，解析 event payload、调用 `sendPersisted` 并保存 conversation 派生事件；
4. 删除完成分支中第二次 `sendEvent("RUN_STATE_COMPLETED")`；
5. 运行聚焦测试、完整后端测试、前端测试、JAR、真实后端系统验收和浏览器验收；
6. 记录结果并创建独立本地 Git 提交。

## 实施内容

### 1. RED：固定返回值和真实重复事件

新增 `AgentTaskServiceLifecycleTest.returnsTheAuthoritativeLifecycleEventForLiveProjection`。测试通过反射调用现有五参数 `updateTask`，让 lifecycle mock 返回一条已持久化事件，并要求 task service 返回同一对象。

首次执行结果：

- 测试数：11；
- failure：1；
- error：0；
- 失败原因：期望权威 `AgentRunEvent`，实际返回 `null`。

系统验收新增两个要求：

- 非审批完成场景的 direct POST SSE 必须包含带 `transition` 和 event id 的 `RUN_STATE_COMPLETED`；
- durable event API 中 `RUN_STATE_COMPLETED` 数量必须严格等于 1，且 direct/durable event id 必须相等。

修正 PowerShell 严格模式下的缺失属性读取后，旧实现稳定失败为：

```text
Direct completion stream did not project the authoritative persisted RUN_STATE_COMPLETED event.
```

### 2. task service 暴露生命周期权威事件

`AgentTaskService.updateTask` 的两个重载由 `void` 改为返回 `AgentRunEvent`：

- 普通重载返回带 occurrence idempotency key 的内部重载结果；
- 内部重载保存 `lifecycle.transition(...)` 的 `TransitionResult`，完成 timing 更新后返回 `transition.event()`；
- 其他调用方可以继续忽略返回值，Java 调用语义保持兼容；
- 对 Mockito 默认 `null` 返回保留窄范围兼容，真实 lifecycle 仍保持“返回结果或抛错”的契约；
- `AgentLoopEngine` 若拿不到权威事件会明确抛出错误，不会创建伪终态事件。

### 3. direct SSE 投影已有事件

`AgentLoopEngine` 完成分支现在按以下顺序执行：

1. 持久化并发送 `FINAL`；
2. `taskService.updateTask("completed")` 提交权威生命周期事件；
3. 写入完成 checkpoint；
4. `sendPersistedEvent(...)` 使用权威 sequence/type/payload 投影到当前 POST SSE；
5. 保存同一 payload 到 conversation 派生历史；
6. 发送 `DONE` 并结束 emitter。

原有第二次 `sendEvent("RUN_STATE_COMPLETED")` 已删除。浏览器断开时，已有终态不会因为 live projection 的 `IOException` 被重新解释成执行失败；conversation 派生历史仍继续保存。

### 4. 验收脚本强化

`scripts/acceptance/agent-runtime.ps1` 新增 `singleAuthoritativeTerminalEvent` 证据，并校验：

- direct completion event 存在 canonical transition；
- direct completion event 有持久化 event id；
- durable completion event 恰好一条；
- direct/durable event id 相同；
- lifecycle previous/next/actor/reason/epoch 审计仍正确。

## 调试证据：旧 JAR 与新源码边界

实现后第一次系统 GREEN 仍报告 direct 事件没有 transition。没有继续猜代码，而是核对脚本、产物和加载边界：

- 验收脚本只启动 `backend/target/labex-agent-backend-1.0.0.jar`，不会自动执行 Maven package；
- 聚焦 Maven test 已更新 `target/classes`；
- JAR 时间戳仍早于新 class，说明验收实际加载旧代码。

重新执行 `mvn -q -f backend/pom.xml -DskipTests package` 后，同一系统验收通过。主机时钟显示为 2026-08-03，但项目本轮权威日期按 2026-08-02 记录。这个过程再次证明“源码/测试编译成功”不能替代“运行 JVM 已加载新产物”的验证。

## 验收结果

### 聚焦与后端全量

- `AgentTaskServiceLifecycleTest` RED：11 个测试、1 failure，命中返回 `null`；
- 聚焦 lifecycle/task service/SSE/final policy 测试：通过；
- `mvn -q -f backend/pom.xml test`：227 份 Surefire 报告，894 个测试，0 failure，0 error，8 skipped；
- `mvn -q -f backend/pom.xml -DskipTests package`：通过，系统验收使用重新打包的 JAR。

### 后端真实系统验收

命令：

```powershell
& D:\LabexAgent\scripts\acceptance\agent-runtime.ps1 -BackendPort 18080 -TimeoutSeconds 180
```

结果：通过，run id `7e258a0d63c7406a87dfb137390e977b`。除既有重启、审批、交互、checkout、transcript、Tool Part、compaction、context blocker、完成证据和隔离数据库证据外，本轮新增：

```json
{
  "lifecycleTransitionAudit": true,
  "singleAuthoritativeTerminalEvent": true
}
```

### 浏览器真实验收

命令：

```powershell
& D:\LabexAgent\scripts\acceptance\browser-runtime.ps1 `
  -BackendPort 18080 -FrontendPort 13000 -CdpPort 19222 `
  -TimeoutSeconds 180 -RestartBackendForAcceptance
```

结果：通过，运行环境 run id `81c78aa0daf7450db17859562e8eb5e1`，浏览器报告 run id `d99f22fe74a64d0c968c9faaace2ad34`。关键证据：

- `refreshReplayDeduplicated = true`
- `restartProjectionVerified = true`
- `restartInteractionVerified = true`
- `questionReplyComponent = true`
- `permissionApprovalRefreshRecovery = true`
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
- 源码编码测试：1/1 通过；
- `git diff --check`：通过，仅有工作区 LF/CRLF 提示。

## 验收结论

正常完成任务现在只有一条权威 `RUN_STATE_COMPLETED`。该事件由 lifecycle service 一次性提交，event、outbox、Run Part、direct SSE、task subscription 和 replay 共享同一 sequence 和 payload。实时 UI 不再依靠第二条同名持久事件获得终态，刷新后历史也不再面对同名真假事件歧义。

## 剩余边界与下一轮

- 本轮只收敛了正常完成分支。失败、取消、retry exhaustion 等终态/等待态仍需继续审计，确认执行引擎是否存在“先 lifecycle transition、后用 `sendEvent` 再持久化同名状态事件”的同类模式；
- `DONE` 仍是独立的流结束事件，不是状态事实。后续应继续确保等待态不发送 DONE，终态消费者以 `RUN_STATE_*` 和 task status 为权威；
- direct SSE 的权威事件投影目前是 `AgentLoopEngine` 私有方法。若后续在多个终态分支复用，应先通过回归确认共同语义，再提取到单一投影边界，不能提前新增第二套事件服务。