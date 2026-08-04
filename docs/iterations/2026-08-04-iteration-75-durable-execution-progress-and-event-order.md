# 第 75 轮：持久化执行进度投影与通用事件顺序

- **日期：** 2026-08-04
- **状态：** 已完成实现与真实验收
- **上游计划：** `docs/superpowers/plans/2026-07-29-agent-runtime-state-convergence.md`
- **本轮边界：** 只收敛 checkpoint 剩余执行辅助状态与通用 durable-event 实时投影顺序；不重写整个 `AgentLoopEngine`，不做无关 UI 改版。

## 1. 当前问题与证据

第 74 轮已经把计划从文件 checkpoint 迁移为 `t_agent_run_plan_item` 权威事实，但以下状态仍由 `AgentContext` 内存字段更新，再复制到 `.labex/agent-checkpoints/*.json`：

- `stage`
- `writeCount`
- `verificationCount`
- `trustedVerificationSources`
- `unverifiedChangeTargets`
- `lastTool` / `lastResult`
- `runLog`

这使文件 checkpoint 仍可能在 JVM 重启后覆盖数据库 transcript 已经表达的工具事实。与此同时，`AgentRunPart` 已经持久化工具名称、参数、状态和结果，`AgentRunEvent(SESSION)` 已经持久化 run-log 路径；继续把同一事实写进 checkpoint 会形成第二事实源。

第 74 轮还发现：某个工具事务先提交领域事件，随后外围又调用 `sse.send(...)` 追加更大 sequence，若较小 sequence 的领域事件晚投影到当前连接，前端单调游标会将其丢弃。仅为 `PLAN_UPDATE` 添加特例不能覆盖其他领域事件。

## 2. OpenCode 对照结论

本轮参考：

- `D:/opencode/opencode-dev/packages/core/src/v1/session.ts`
- `D:/opencode/opencode-dev/packages/opencode/src/session/processor.ts`
- `D:/opencode/opencode-dev/packages/opencode/src/session/message-v2.ts`

OpenCode 将 tool pending/running/completed/error、step-start、step-finish、snapshot 和 patch 作为持久化 Message Part；恢复时从 Part 重建，而不是从另一个工作区 JSON 恢复工具结果计数。LabexAgent 因此采用同一原则：

1. Tool Part / Run Event 是执行事实；
2. 工程阶段、写入计数、验证计数和未验证目标是可重建投影；
3. `AgentContext` 只缓存该投影；
4. 文件 checkpoint 只保留为 v1/v2 只读迁移输入，不再写入新事实。

## 3. 设计

### 3.1 执行进度 reducer

新增纯 reducer，按持久化 Tool Part 顺序重放：

- 成功写工具：增加 `writeCount`，记录目标，进入 `implement`；
- 成功可信验证工具：增加 `verificationCount`，记录来源，清除已验证目标，进入 `verify`；
- 对带 SHA 标记、且命中待验证目标的 `read_file`：按现有契约视为手工验证；
- 明确失败：进入 `repair`；
- `pending`、`running`、`waiting_approval`、`waiting_user`、`skipped`、`interrupted` 不伪造成功或失败进度；
- plan/read-only 工具只按现有阶段规则推进。

运行时增量更新和重启重放必须复用同一个 reducer，不能各写一套判断。

### 3.2 Durable progress projector

新增 `AgentRunProgressProjectionService`：

- 校验当前 `AgentTask.execution_epoch`；
- 从 `AgentRunPart(part_type=tool)` 按稳定顺序重建执行进度；
- 从最新 Tool Part 投影 last tool/result；
- 从 `AgentRunEvent(SESSION)` 投影 run-log 路径；
- 终态任务由 `AgentTask.status` 覆盖最终阶段；
- 将结果应用到 `AgentContext`，并生成 Provider resume prompt。

对于只有 v1/v2 checkpoint、没有 durable Tool Part 的旧活动任务，checkpoint 只能生成一次性 legacy seed；seed 必须先写成带稳定幂等键的 `RUN_PROGRESS_MIGRATED` durable event，再参与投影。数据库事实存在时必须忽略陈旧 checkpoint。

### 3.3 文件 checkpoint 退出写路径

- `AgentCheckpointStore` 改为只读 legacy reader；
- 删除新 checkpoint 写入和 `writeAgentCheckpoint(...)` 调用；
- Provider resume prompt 改读 durable progress projector；
- 原文件不删除，避免破坏仍需迁移的活动任务；达到迁移退出条件后再删除 reader。

### 3.4 通用 durable-event 顺序

`AgentSsePublisher` 持有当前连接已经投影的 sequence 游标：

- bind 时记录当前数据库游标作为基线；
- 每次追加新 SSE durable event 后，先从数据库补齐基线与新 event 之间的所有已提交事件，再按 sequence 发送；
- `sendPersisted(...)` 同样补齐缺口，并忽略已经投影的重复 sequence；
- SSE 断开只关闭观察者，不回滚数据库事件。

这样工具内部提交的任何领域事件都不会被后续更大 sequence 越过，`PLAN_UPDATE` 不再需要专用顺序特例。

## 4. RED 测试

1. reducer：写入、可信验证、手工 read 校验、失败和等待态语义；
2. projector：只从 Tool Part/Event 重建，数据库事实优先于 checkpoint seed；
3. migration：v1/v2 seed 幂等写为 durable event，过期 epoch 被拒绝；
4. checkpoint：源码不再提供新写入，v1/v2 仍可读取迁移 seed；
5. SSE：sequence 41 在工具事务中先落库、sequence 42 由后续 `send` 创建时，当前连接必须按 41、42 顺序发送；重复 41 不得再次发送；
6. loop contract：启动恢复与 prompt 使用 progress projector，不再调用 checkpoint `restoreInto` 或 `save`。

## 5. 验收计划

### 小范围

- 新 reducer/projector/checkpoint/SSE 测试；
- `AgentLoopEngine` 相关 contract/streaming tests；
- schema 与 Spring wiring tests。

### 全量

- `backend/mvn -q test`
- `frontend/npm.cmd test`
- `frontend/npm.cmd run build`

### 真实系统验收

- 使用隔离 H2、真实 Spring Boot JVM、HTTP/SSE 和重启；
- 完成一次写工具后进入可恢复等待态；
- JVM 重启后删除或篡改旧 checkpoint，确认 Provider 仍看到由 Tool Part 重建的写入计数、阶段、待验证目标、last tool 和 run-log pointer；
- 完成可信验证并确认最终投影；
- 捕获当前连接的事件 ID，证明工具事务事件和外围事件严格单调；
- 浏览器刷新后无 console/network error，历史 Part 与实时 reducer 一致。

## 6. 实现与验收结果

已完成：

- 新增 `AgentRunExecutionProgressReducer` 与 `AgentRunProgressProjectionService`；运行时增量更新和 JVM 重启重放使用同一个 reducer，`AgentContext` 不再提供写入计数/验证信任状态的旁路 mutator。
- `AgentCheckpointStore` 已降级为 v1/v2 legacy reader；新任务不再写 `.labex/agent-checkpoints/*.json`，数据库中存在 Tool Part 时不会消费旧 checkpoint。
- Provider 调用、硬 admission、overflow recovery 使用同一份 `providerMessagesForInvocation(...)`，在调用边界注入不落库的只读运行时投影；自动 compaction 仍只裁剪 durable transcript，避免派生投影污染 turn 边界。
- `AgentSsePublisher` 对所有 durable event 做数据库缺口补齐，不再只为 `PLAN_UPDATE` 做特例；重复 sequence 会被连接游标抑制。
- Acceptance scripted provider 新增“写入 → 用户等待 → JVM 重启 → 由 Tool Part 重建进度 → SHA read_file 验证”的真实链路。

真实验收证据：

- 后端全量：`mvn -q test`，1,033 项测试，0 failure，0 error，8 skipped。
- 前端全量：`npm.cmd test`，213/213 通过；验收单元：`npm.cmd run test:acceptance:unit`，15/15 通过。
- 前端构建：`npm.cmd run build` 通过，chunk budget 检查通过。
- 后端真实 JVM/H2/HTTP/SSE/restart：acceptance run `fe05cdb9627448cba97935db233356cf`；执行进度重建、checkpoint 退役、直接 SSE 与 durable replay 顺序均通过。
- 浏览器真实验收：browser run `99621f04b5734dba8c90d568ee5f0cf3`；桌面布局、刷新回放、提问/审批恢复、内部思考隐藏、模型重试、compaction/fork/restart 均通过，console/network errors 均为 0。

验收中发现并修复的回归：动态运行时投影最初被自动 compaction 计入 soft budget，导致既有 compaction epoch 验收多触发一次；修复为 soft compaction 只依据 durable transcript，硬 admission 与 Provider 调用仍计入动态投影，最终系统验收通过。 全量后端复跑还发现 Windows PID 文件存在截断读竞态；测试夹具改为等待非空内容后再解析，最终全量回归通过。
## 7. 停止边界

本轮完成后停止于：

- checkpoint 剩余执行辅助状态已由 durable Part/Event 重建；
- checkpoint 已降级为只读迁移源；
- 当前 SSE 连接通用补齐持久化事件缺口；
- 真实重启和浏览器刷新验收通过；
- 完成本地 Git 提交。

本轮不宣称整体架构收敛完成。之后仍需按总计划第 12 节逐项完成最终审计，并确认所有 legacy/shadow 开关及旧 `t_agent_message` 迁移源达到删除条件。