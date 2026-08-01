# 第 51 轮：生命周期迁移审计信封

- 日期：2026-08-02
- 状态：已完成
- 权威计划：`docs/superpowers/plans/2026-07-29-agent-runtime-state-convergence.md`

## 背景

整体架构审计确认：Provider 请求已经通过 durable transcript projector 读取，运行状态写入也已集中到 `AgentRunLifecycleService`。但状态机强制不变量仍有一个明确缺口：`AgentRunEvent` 只持久化目标 `state`、事件类型和调用方自定义 payload，并不保证每次真实状态迁移都记录 `previousState`、`nextState`、`actor`、`reason` 和 `executionEpoch`。

当前 DEBUG 日志虽然打印了部分 previous/next/epoch，但日志不是可重放事实，重启后也不能由事件/outbox/API 查询恢复。因此不能把日志当作状态迁移审计。

## 设计

在 `AgentRunLifecycleService` 唯一写入口中，为所有真实状态变化注入统一、只读、向后兼容的 `transition` 信封：

```json
{
  "transition": {
    "previousState": "waiting_environment",
    "nextState": "queued",
    "actor": "agent_run_lifecycle",
    "reason": "RUN_ENVIRONMENT_RESUME",
    "executionEpoch": 4,
    "stateChanged": true
  }
}
```

规则：

1. 迁移信封只能由 lifecycle service 生成，调用方不能覆盖；
2. `reason` 优先使用调用方 payload 中非空的 `reason`，否则使用稳定事件类型；
3. `actor` 固定记录实际提交状态写入的 `agent_run_lifecycle`，用户、scheduler 或工具等业务触发方仍由原 payload/eventType 表达；
4. execution epoch 使用该事件提交后所属的 epoch；dispatch/recovery claim 使用刚领取的新 epoch；
5. 纯事件追加不伪造状态迁移信封；
6. 事件表、outbox 和 Run Part 投影必须接收同一份增强 payload；
7. 现有前端忽略未知字段，API 保持向后兼容。

## 计划

1. 先给普通 transition、dispatch claim、recovery claim 和纯 append event 增加失败回归；
2. 在 lifecycle service 中集中实现 payload 增强，禁止散落到调用方；
3. 修复 `AgentTranscriptProjectionService` 中已发现的单问号注释编码损伤，并增强源码编码回归；
4. 扩展后端 acceptance，从真实持久事件 API 检查至少一条状态迁移信封；
5. 运行聚焦测试、后端全量、前端编码/全量测试、生产构建、JAR 和后端系统验收；
6. 补充实施记录并创建本地 Git 提交。

## 实施内容

### 1. 先固定失败协议

在 `AgentRunLifecycleServiceTest` 和 `AgentRunDispatchClaimTest` 中新增回归，覆盖：

- 普通 `running -> waiting_environment` 迁移；
- dispatch claim 领取新 epoch；
- recovery claim 领取新 epoch；
- event、outbox、Run Part 三处 payload 一致；
- 同状态 `THINK` 事件不得伪造 `transition`。

首次执行聚焦测试时共 25 个测试，其中 3 个因 payload 中不存在 `transition` 而报错，证明测试命中了原始缺口，而不是实现后补写的“永远为绿”测试。

### 2. 生命周期唯一入口生成权威审计数据

`AgentRunLifecycleService` 新增集中式 `transitionPayload(...)`：

- 先清洗并复制调用方 payload；
- 对真实状态变化覆盖写入 canonical `transition`，调用方传入同名字段也不能伪造事实；
- `reason` 使用非空业务原因，否则回退到事件类型；
- dispatch/recovery 使用领取后的 epoch；
- common transition、dispatch claim、recovery claim 均把同一份 payload 写入 event、outbox 和 Run Part；
- 同状态 append event 仍保持普通事件语义。

同时把原先压缩在一行中的 recovery claim 展开为显式步骤，使锁定、epoch、lease、payload 和持久化顺序可审查。

### 3. 源码编码回归收紧

修复以下 JavaDoc/注释末尾被损坏成 ASCII `?` 的文本：

- `AgentTranscriptProjectionService`
- `AgentRunTranscriptService`
- `AgentRunLifecycleService`

`sourceEncoding.test.mjs` 除连续三个问号外，新增“中文源码注释以 ASCII 问号结束”的检测，并改为基于 `import.meta.url` 定位仓库。后者是在独立从仓库根目录执行门禁时发现的：旧测试错误依赖 `cwd`，会把后端目录解析为 `D:\backend\src`。修复后从仓库根目录和 `frontend` 目录执行都通过。

### 4. 系统验收读取持久化事实

`scripts/acceptance/agent-runtime.ps1` 新增 `lifecycleTransitionAudit` 证据，并从持久化事件 API 校验完成迁移包含：

- `previousState = running`
- `nextState = completed`
- `actor = agent_run_lifecycle`
- `reason = RUN_STATE_COMPLETED`
- `stateChanged = true`
- `executionEpoch >= 1`

验收过程中还暴露并修复两个脚本层问题：

1. 初始 HTTP 流中存在同名兼容投影，不能代替 durable lifecycle event；验收改为重新查询任务持久化事件，并选择带审计信封的权威迁移；
2. PowerShell 反序列化 JSON 后可能把任务 ID 格式化成 `3.0`，传给后端 `Long` 路径参数会得到 HTTP 400；脚本现在统一使用显式转换后的 `$completionTaskId`。

最终真实系统验收通过，run id 为 `00fca79a70a947719790a66bba8f800c`。该验收使用重新打包的 Spring Boot JAR、隔离的 H2 TCP 数据库，覆盖服务重启、审批恢复、交互恢复、checkout 竞争、transcript/part 投影、compaction、context blocker 和完成证据，不是 mock 或仅编译验证。

## 验收结果

### 后端回归与全量

- 聚焦生命周期/dispatch/recovery/scheduler 测试：通过；
- `mvn -q -f backend/pom.xml test`：227 份 Surefire 报告，893 个测试，0 failure，0 error，8 skipped；
- `mvn -q -f backend/pom.xml -DskipTests package`：通过；
- `backend/target/labex-agent-backend-1.0.0.jar` 已检查包含本轮 3 个关键运行时类。

### 后端真实系统验收

命令：

```powershell
& D:\LabexAgent\scripts\acceptance\agent-runtime.ps1 -BackendPort 18080 -TimeoutSeconds 180
```

结果：通过，关键证据全部为 `true`：

- `questionRestart`
- `permissionRestart`
- `commandApproveRestart`
- `commandRejectRestart`
- `checkoutContention`
- `runMessagePartProjection`
- `toolPartAuthority`
- `manualCompaction`
- `staticContextBlocked`
- `contextWindowUnconfigured`
- `completionEvidence`
- `unverifiedEditRejected`
- `normalProfileProviderIsolation`
- `lifecycleTransitionAudit`
- `isolatedDatabase`
- `cleanup`

### 前端与编码

- `npm.cmd test`：192/192 通过；
- `npm.cmd run test:acceptance:unit`：10/10 通过；
- `npm.cmd run build`：通过；
- 生产 chunk 预算通过：CloudWorkspace 1,462,573 / 1,500,000，index 1,259,534 / 1,300,000，TerminalPanel 380,367 / 400,000；
- 构建仅有第三方 `@vueuse/core` PURE 注释位置警告；
- 源码编码测试从仓库根目录和前端目录执行均为 1/1 通过；
- `git diff --check`：通过，仅存在 Git 的 LF/CRLF 工作区提示。

## 验收结论

本轮达到既定验收标准：真实状态迁移的 previous/next/actor/reason/epoch 已成为数据库事件、outbox 和 Run Part 可查询、可重放的持久化事实；同状态事件不会伪造迁移；dispatch/recovery epoch 与实际领取结果一致；完整后端、前端、JAR 和真实重启系统验收均通过。

## 已知边界与下一轮

当前仍存在两条同名 `RUN_STATE_COMPLETED` 持久事件：

1. lifecycle service 写入的权威状态迁移，包含 `transition`；
2. `AgentLoopEngine.sendEvent(...)` 在任务已经 completed 后追加的兼容实时投影，不包含 `transition`。

这不会再被误判为两次状态迁移，但事件名称重复会让实时流、历史回放和验收消费者难以区分权威状态事实与兼容投影。下一轮应在不破坏前端即时反馈的前提下，让 direct SSE 复用/发布 lifecycle service 已持久化的权威事件，停止持久化第二条同名状态事件，并补齐 live/replay 双路径回归与浏览器验收。