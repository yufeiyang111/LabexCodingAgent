# 第 55 轮：模型重试权威事件与终态实时投影

- 日期：2026-08-03
- 状态：已完成
- 收敛依据：`docs/superpowers/plans/2026-07-29-agent-runtime-state-convergence.md`
- 基线提交：`bcf1a26 fix: project authoritative cancellation events`

## 1. 本轮目标

模型连接发生可恢复中断时，保证模型重试只有一条权威持久化事件链，并且浏览器在初始 POST 流结束后自动切换到同一任务的持久化订阅：

1. `RUN_MODEL_RETRY_SCHEDULED` / `RUN_MODEL_RETRY_STARTED` 必须来自 lifecycle 已持久化事件；
2. 不再额外写入兼容事件 `RETRY_SCHEDULED`；
3. 前端实时 reducer 与历史 reducer 必须理解同一组重试和终态事件；
4. 任务进入终态后，订阅必须排空紧随状态迁移提交的 `ERROR`、`FINAL`、`RUN_STATE_FAILED`、`DONE`；
5. 两次模型流中断耗尽重试后，失败正文必须无需刷新页面即可显示。

## 2. 发现的真实问题

### 2.1 重试事件存在双写

`AgentTaskService.scheduleModelRetry(...)` 已通过 `AgentRunLifecycleService` 持久化 `RUN_MODEL_RETRY_SCHEDULED`，但 `AgentLoopEngine` 随后又调用普通 `sendEvent(...)` 写入 `RETRY_SCHEDULED`。同一次状态迁移因此产生两种协议事件，实时路径和回放路径无法共享同一事实。

### 2.2 初始流结束后前端没有接管重试任务

前端 `useAgentEventTimeline` 原先不处理 `RUN_MODEL_RETRY_SCHEDULED`。初始 POST SSE 在调度重试后正常结束，但消息没有设置 `resumeTaskEventsAfterStream`，页面不会自动订阅同一 `taskId` 的持久事件。

### 2.3 订阅在终态迁移后过早关闭

真实浏览器验收显示，任务状态先变为 `failed`，transactional outbox 随后才广播同一事务附近追加的 `ERROR / FINAL / RUN_STATE_FAILED / DONE`。旧订阅代码看到任务终态就立即关闭观察者，导致最后几条事件只能刷新后回放。

日志证据中曾出现：订阅先移除，约 600ms 后才广播终态尾部事件。修复后日志为 `TASK_EVENT_TERMINAL_DRAIN_COMPLETE`，且 `terminalSequence` 与 `lastSequence` 都到达 58。

### 2.4 前端遗漏权威失败状态，终态对账覆盖了可见错误

后端已把 `ERROR` 投影到浏览器，但实时 reducer 和历史 reducer 都没有处理 `RUN_STATE_FAILED`：

1. `ERROR` 先写入错误正文；
2. `RUN_STATE_FAILED` 被忽略；
3. 不带 `taskStatus` 的 `DONE` 沿用旧的 `running`；
4. 终态历史对账认为消息仍未终止并重载历史；
5. 历史 reducer 只写 `message.error`，没有写模板实际渲染的 `message.content`，最终页面显示“已结束”但正文为空。

### 2.5 验收脚本存在异步短路误报

产品修复后，失败诊断页面已经出现 `Model API failed`，但验收脚本使用：

```js
bodyIncludes('中文') || bodyIncludes('English')
```

两个调用都返回 Promise。第一个 Promise 本身为真值，第二个候选永远不会执行，导致页面正确时仍超时。现改为一次浏览器表达式中的候选集合匹配，并增加脚本级回归测试。

### 2.6 Windows 外部管道破坏中文

迭代中一度通过 PowerShell 管道把含中文的 Python 脚本送入外部进程，Windows 代码页把少量新文本写成 `?`。已用显式 UTF-8 字节检查定位并修复；后续中文文档使用 .NET `UTF8Encoding(false)` 直接写入，不再经过外部文本管道。

## 3. 实施内容

### 3.1 Lifecycle 返回权威重试事件

- `AgentRunLifecycleService.scheduleModelRetryResult(...)` 返回完整 `TransitionResult`；
- 兼容方法 `scheduleModelRetry(...)` 继续保留，但只委托给新入口；
- `AgentTaskService.ModelRetrySchedule` 携带 lifecycle 创建的 `AgentRunEvent`；
- `AgentLoopEngine` 使用 `sendPersistedEvent(...)` 投影该事件，不再创建 `RETRY_SCHEDULED`。

### 3.2 Run Part 投影重试阶段

`AgentRunPartService` 将：

- `RUN_MODEL_RETRY_SCHEDULED` 投影为等待中的 retry Part；
- `RUN_MODEL_RETRY_STARTED` 投影为运行中的 retry Part。

这样任务快照、刷新回放与实时 UI 可以共享同一持久化重试证据。

### 3.3 前端实时与历史 reducer 同步

- 实时 reducer 在 `RUN_MODEL_RETRY_SCHEDULED` 时停止当前 POST 流的加载所有权并请求持久订阅接管；
- `RUN_MODEL_RETRY_STARTED` 恢复 active 状态；
- 实时和历史 reducer 都处理 `RUN_STATE_COMPLETED / FAILED / CANCELLED`；
- 历史 `ERROR` 同时写入 `error` 与可渲染 `content`；
- 历史 reducer 同步处理模型重试 scheduled/started，刷新后不再丢失阶段状态。

### 3.4 终态订阅排空窗口

`AgentTaskEventSubscriptionService` 增加 750ms 安静排空窗口：

- 首次观察终态只记录终态序号和时间，不立即关闭；
- catch-up 继续发送当前批次所有持久化事件；
- 只有游标在排空窗口内没有新事件时才 `complete()`；
- 新增可注入排空时间的包级构造器以进行确定性测试；
- 生产构造器显式添加 `@Autowired`，避免多构造器导致 Spring 无法选择依赖注入入口。

### 3.5 验收增强

后端系统验收新增 `authoritativeModelRetryProjection`，验证：

- scheduled/started 各两次；
- 不存在 `RETRY_SCHEDULED`；
- 首次 scheduled 的直接流事件与持久化回放是同一个序号和载荷；
- 任务最终进入 `failed`。

浏览器验收新增：

- 两轮重试事件数量与旧事件缺失检查；
- 无刷新终态失败正文检查；
- `modelRetryLiveProjection=true` 结果字段；
- 多候选正文匹配的脚本级测试。

## 4. RED 证据

1. 前端初始回归：`RUN_MODEL_RETRY_SCHEDULED` 被忽略，断言出现 `null !== 17`；
2. 后端契约回归：缺少 `scheduleModelRetryResult(...)` 和 `ModelRetrySchedule.event()` 时编译失败；
3. 修复前真实 JAR 系统验收：直接流未投影权威 `RUN_MODEL_RETRY_SCHEDULED`；
4. 第一次浏览器验收：后端任务已失败，但订阅在 outbox 广播终态尾部事件前移除；
5. 第二次浏览器验收：页面已经显示 `Model API failed`，但异步 `Promise || Promise` 验收谓词误报超时；
6. 新增 reducer 回归在修复前共 3 项失败：重试阶段未回放、历史失败仍为 `running`、实时失败仍为 `running`。

## 5. GREEN 与系统验收

### 5.1 聚焦回归

```text
node --test frontend/src/composables/useAgentEventTimeline.test.mjs frontend/src/composables/agentHistoryReducer.test.mjs
47 tests, 47 passed
```

### 5.2 后端全量

```text
cd D:\LabexAgent\backend
mvn test
897 tests, 0 failures, 0 errors, 8 skipped
BUILD SUCCESS
```

### 5.3 前端全量与生产构建

```text
cd D:\LabexAgent\frontend
npm test
196 tests, 196 passed

npm run test:acceptance:unit
12 tests, 12 passed

npm run build
build passed
CloudWorkspace: 1,463,644 / 1,500,000 bytes
index:          1,259,534 / 1,300,000 bytes
TerminalPanel:    380,367 /   400,000 bytes
```

### 5.4 真实后端系统验收

```text
.\scripts\acceptance\agent-runtime.ps1 -TimeoutSeconds 150
runId: 3f64eb732f2a41b7bc77268aab05eada
authoritativeModelRetryProjection: true
authoritativeFailureProjection: true
authoritativeCancellationProjection: true
cleanup: true
```

### 5.5 真实浏览器验收

```text
.\scripts\acceptance\browser-runtime.ps1 -RestartBackendForAcceptance -TimeoutSeconds 150
runId: d9c37eac07984a54aa0565f96b142d77
modelRetryLiveProjection: true
providerStreamInterruptionHandled: true
refreshReplayDeduplicated: true
restartProjectionVerified: true
restartInteractionVerified: true
consoleErrors: 0
networkErrors: 0
```

浏览器验收实际启动隔离 H2、打包后的 Spring Boot JAR、Vite 和无头 Chrome，并覆盖两次后端重启、审批恢复、压缩回放、模型流中断、静态上下文阻塞与环境恢复。

## 6. 风险与取舍

1. 终态订阅会最多延迟约 750ms 关闭，这是为了保证终态迁移之后紧随提交的持久事件可被同一观察者接收；执行状态本身不会延迟。
2. 浏览器重启场景仍会产生 3 条预期的旧连接传输错误，但验收只在真实后端重启已经验证后豁免这些错误；最终非预期控制台错误和网络错误均为 0。
3. `backend/src/main/resources/application-acceptance.yml` 是本轮开始前已有的工作区改动，本轮不暂存、不提交。
4. 本轮只收敛模型重试与终态投影，不宣称整个 transcript 读取路径已经完成唯一事实源切换。

## 7. 结论

本轮完成了从 lifecycle 状态迁移、持久化事件、Run Part、SSE 订阅、实时 reducer、历史 reducer到真实浏览器 UI 的一条完整垂直切片。模型流中断现在会按有限次数重试；重试耗尽后，同一任务的失败状态和失败正文无需刷新即可稳定显示，且实时路径与回放路径不再依赖重复的兼容事件。