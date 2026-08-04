# 第 74 轮：持久化任务计划与 Todo 投影

- 日期：2026-08-04
- 分支：`codex/agent-tool-reliability`
- 基线提交：`09325dc feat: project durable conversation history`
- 状态：实现与真实验收完成，随本轮本地提交交付

> **执行方法：** 先按 TDD 写失败回归测试，再完成最小纵向实现；验证必须覆盖真实数据库、JVM 重启、同一 task/epoch 恢复和浏览器刷新。完成后只提交本轮文件。

**目标：** 将 `create_plan` / `todo_write` 的计划状态从 `AgentContext` 与文件 checkpoint 的并行权威，收敛到按 `AgentTask` 持久化、受 execution epoch 保护、可重放到前端的数据库事实源。

**架构：** 新增 `t_agent_run_plan_item`，按 `task_id + position` 保存完整有序计划；每次变更在任务行锁内整体替换，并以 `execution_epoch` 拒绝过期 worker。计划变更和 `PLAN_UPDATE` 事件在同一事务中提交，`AgentContext` 仅保存当前 DB 投影缓存。checkpoint 升级为 v2 后不再写入/恢复计划，只为旧 v1 checkpoint 提供一次性迁移读取。

**参考实现：** `D:/opencode/opencode-dev/packages/opencode/src/session/todo.ts`。采用其“数据库有序 Todo + 事务整体替换 + 更新事件”的原则；不复制 OpenCode 的 Effect/Drizzle 技术栈，也不引入独立于现有 `AgentTask`、execution epoch、`AgentRunEvent` 的第二套会话状态机。

**技术栈：** Java 17、Spring Boot 3、MyBatis-Plus、MySQL/H2、Vue 3、SSE durable replay、PowerShell acceptance harness。

---

## 一、当前失败事实

1. `CreatePlanTool` 只调用 `AgentContext.setPlan()`；进程退出后没有数据库事实。
2. `TodoWriteTool` 只返回输入文本，不更新任何计划状态。
3. `AgentCheckpointStore` v1 把 plan/currentPlanIndex 写入工作区 JSON，并在恢复时覆盖 `AgentContext`；文件 checkpoint 因此成为计划权威。
4. checkpoint 不受数据库事务、task 行锁或 execution epoch 约束；旧 worker 或旧文件可能恢复过期计划。
5. `PLAN_UPDATE` 由主循环在工具执行后另行发送；计划变更与可重放事件没有同一提交边界。
6. 完成门禁读取 `AgentContext` 缓存；若恢复缓存丢失，Agent 可能错误地认为没有未完成计划。

## 二、唯一事实源与不变量

| 事实 | 本轮唯一所有者 | 派生视图 |
|---|---|---|
| 计划项目、顺序、状态、修订号 | `t_agent_run_plan_item` + `AgentRunPlanService` | `AgentContext.plan/currentPlanIndex` |
| 写入资格 | `AgentTask.execution_epoch`（任务行锁内校验） | 工具调用上下文中的 epoch |
| 前端可重放更新 | 与计划变更同事务提交的 `AgentRunEvent(PLAN_UPDATE)` | 当前 SSE 连接、前端 reducer、PlanDisplay |
| 旧计划恢复 | 仅 v1 checkpoint 的一次性迁移输入 | v2 checkpoint 不再保存 plan |

必须满足：

- 每个 task 最多 30 个有序项目；状态只允许 `pending`、`in_progress`、`completed`。
- 每次 create/update/complete/todo_write 都整体写入一个完整快照并递增 `plan_revision`。
- 同一快照最多一个 `in_progress`；若存在未完成项，最前面的未完成项为 `in_progress`。
- 过期 execution epoch 的写入必须失败，不能污染新 worker 的计划。
- DB 变更与 `PLAN_UPDATE` 的 event/outbox 必须同事务；崩溃时不能只成功一半。
- 主循环只能投影已持久化事件，不能再次追加重复的 `PLAN_UPDATE`。
- checkpoint v2 不包含计划；恢复时先恢复非计划执行辅助信息，再从数据库投影计划。
- 只有数据库没有计划且读取到 v1 非空计划时，才允许一次性迁移；迁移后覆盖成 v2 checkpoint。
- 最终完成门禁在判断前重新读取数据库投影，不能把 `AgentContext` 当权威。

## 三、文件与职责

### 新增

- `backend/src/main/java/com/labex/entity/AgentRunPlanItem.java`
  - MyBatis 实体；保存 task、position、title、description、status、execution epoch、revision、时间。
- `backend/src/main/java/com/labex/mapper/AgentRunPlanItemMapper.java`
  - 按 task/position 有序读取和删除。
- `backend/src/main/java/com/labex/labexagent/run/AgentRunPlanService.java`
  - 任务行锁、epoch fence、事务整体替换、修订号、事件原子提交、上下文投影。
- `backend/src/test/java/com/labex/labexagent/run/AgentRunPlanServiceTest.java`
  - 服务级状态/epoch/事件测试。
- `backend/src/test/java/com/labex/labexagent/run/AgentRunPlanMapperDatabaseTest.java`
  - H2 MySQL 模式真实 SQL 顺序与唯一约束测试。
- `backend/src/test/java/com/labex/labexagent/tool/impl/TodoWriteToolDurabilityTest.java`
  - Markdown Todo 解析和持久化回归测试。

### 修改

- `backend/src/main/resources/sql/schema.sql`
  - 增加 `t_agent_run_plan_item`。
- `backend/src/main/java/com/labex/labexagent/runtime/AgentContext.java`
  - 增加 execution epoch/plan revision 派生字段和唯一计划投影入口；删除公开 `setPlan/setCurrentPlanIndex` 写入口。
- `backend/src/main/java/com/labex/labexagent/runtime/AgentCheckpointStore.java`
  - checkpoint v2 不再写/恢复计划；兼容读取 v1 迁移数据。
- `backend/src/main/java/com/labex/labexagent/tool/impl/CreatePlanTool.java`
  - create/update/complete 全部通过 `AgentRunPlanService`。
- `backend/src/main/java/com/labex/labexagent/tool/impl/TodoWriteTool.java`
  - 将短 Markdown/plain todo 解析为完整计划快照并持久化。
- `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
  - 绑定当前 lease epoch；启动/恢复时加载或迁移计划；只发送已提交 `PLAN_UPDATE`；完成门禁前刷新 DB 投影。
- `backend/src/main/java/com/labex/labexagent/llm/AcceptanceScriptedProvider.java`
  - 增加持久化计划和浏览器计划脚本场景。
- `backend/src/test/java/com/labex/labexagent/tool/impl/CreatePlanToolVerificationTest.java`
  - 保留验证任务门禁，同时证明状态写入服务而非只改 context。
- `backend/src/test/java/com/labex/labexagent/runtime/AgentCheckpointStoreTest.java`
  - 验证 v2 不保存/恢复 plan，并验证 v1 迁移输入可读。
- `backend/src/test/java/com/labex/labexagent/run/AgentRunSchemaTest.java`
  - 固定新表及关键索引契约。
- `scripts/acceptance/agent-runtime.ps1`
  - 真实 MySQL/JVM 重启：创建计划 → 等待用户 → 确认 v2 checkpoint 无 plan → 重启 → 同 task 恢复 → 完成计划；验证 epoch/revision/event。
- `frontend/scripts/acceptance/agent-browser.mjs`
  - 真实浏览器：显示计划 → 刷新 → 从 durable history 重放同一计划卡片。
- `docs/superpowers/plans/2026-07-29-agent-runtime-state-convergence.md`
  - 记录第 73、74 轮完成证据与剩余边界。

## 四、TDD 与实现步骤

### Task 1：固定数据库计划契约（RED）

- [x] 新增 schema 断言：表、唯一键、task/status 索引、execution epoch、revision。
- [x] 新增 H2 Mapper 测试：按 position 排序；同 task/position 重复插入失败。
- [x] 新增 Service 测试：首次替换 revision=1；第二次变更 revision=2；过期 epoch 拒绝；计划事件使用稳定幂等键。
- [x] 只运行上述测试，确认在生产类/表不存在时失败。

### Task 2：实现数据库唯一事实源（GREEN）

- [x] 新增实体、Mapper、SQL 表。
- [x] 实现 `AgentRunPlanService`：锁定 `AgentTask`、校验 epoch/终态、读取完整快照、规范化状态、整体替换、追加 durable `PLAN_UPDATE`。
- [x] 事件 payload 保持现有前端协议：`taskId`、`plan`、`summary`、`planJson`，并新增 `executionEpoch`、`planRevision`、`source`。
- [x] 运行 Service/Mapper/schema 测试并确认通过。

### Task 3：工具只通过持久化服务写计划（RED → GREEN）

- [x] 修改 `CreatePlanToolVerificationTest`，先证明缺少持久化调用时失败。
- [x] 新增 `TodoWriteToolDurabilityTest`：支持 `- [x]`、`- [ ]`、普通列表；空列表和超过 30 项失败。
- [x] `CreatePlanTool` 的 create/update/complete 从 DB 读取和写入，成功后只刷新 context 投影。
- [x] `TodoWriteTool` 解析后调用同一 replace API，禁止形成第二套 Todo 存储。
- [x] 运行两类工具测试确认通过。

### Task 4：checkpoint 降级和运行时恢复（RED → GREEN）

- [x] checkpoint v2 测试先证明：保存文件不含 plan，restore 不覆盖 context plan。
- [x] 手写 v1 JSON fixture，证明仍能读取一次性 legacy plan seed。
- [x] `AgentLoopEngine` 使用当前 lease epoch 填充 context；恢复时 DB 优先，只有 DB 空且 v1 seed 非空才迁移。
- [x] create_plan/todo_write 在工具 delegate 返回后、任何后续工具生命周期事件之前，以原始 sequence 调用 `sendPersisted` 投影；删除重复 `sendEvent(PLAN_UPDATE)`。
- [x] 完成门禁前刷新 DB 计划，防止缓存丢失绕过未完成任务。
- [x] 运行 checkpoint、loop contract、工具批次/恢复相关测试。

### Task 5：真实系统与浏览器验收

- [x] scripted provider 创建两步计划并进入 `waiting_user`。
- [x] 检查 MySQL 计划行、revision=1、PLAN_UPDATE=1；检查 checkpoint v2 不含 plan。
- [x] 停止并重启 JVM，回复原 interaction；确认同 task、execution epoch 增长、计划从 DB 恢复并完成、最终 revision=3、无重复位置/事件。
- [x] 通过 history API 验证三个 PLAN_UPDATE 可重放且最终全部 completed。
- [x] 浏览器场景显示 PlanDisplay；刷新页面后从 durable history 仍显示相同计划和完成状态；控制台/网络错误为 0。

### Task 6：完整验证、文档和提交

- [x] 后端定向测试。
- [x] `mvn test` 全量后端测试。
- [x] 前端现有完整测试脚本。
- [x] `npm run build`。
- [x] `scripts/acceptance/run-all.ps1`，包含 package、acceptance unit、后端重启、浏览器运行时。
- [x] 把命令、数量、runId、失败修复过程写回本文档和总收敛计划。
- [x] 检查 `git diff`，只暂存本轮文件，创建本地 Git 提交，不 push。

## 五、明确停止边界

本轮完成后停止，不顺带迁移以下仍在 checkpoint 中的辅助字段：

- `writeCount` / `verificationCount`；
- `trustedVerificationSources` / `unverifiedChangeTargets`；
- `stage`、last tool/result、run log pointer；
- 其他 runtime 大类重写或前端视觉改版。

这些字段是否继续持久化到数据库，需要下一轮单独做所有权审计和迁移，不能和计划状态混在一次提交中。

## 六、实际改动与验收记录

### 6.1 实际改动

1. 新增 `AgentRunPlanItem`、`AgentRunPlanItemMapper` 和 `AgentRunPlanService`：以 task 行锁、execution epoch fence、完整快照替换和单调 revision 管理计划。
2. `create_plan` 的 create/update/complete 与 `todo_write` 全部写入同一数据库事实源；`AgentContext` 只接收服务返回的派生投影，并删除公开 `setPlan/setCurrentPlanIndex` 写入口。
3. `PLAN_UPDATE` 与计划行在同一事务提交，并使用 `plan-update:{taskId}:{revision}` 稳定幂等键；主循环不再追加第二个同名事件。
4. checkpoint 升级为 v2：不再保存或恢复 plan/currentPlanIndex；v1 只提供一次性 legacy seed，且数据库已有计划时绝不覆盖。
5. `AgentLoopEngine` 从 execution lease 绑定当前 epoch，启动/恢复时数据库优先加载计划，最终完成门禁前重新读取数据库。
6. scripted provider 新增 `durable-plan` 与 `browser-plan` 场景；后端验收覆盖 JVM 重启、删除 checkpoint、同 task 恢复，浏览器验收覆盖实时 PlanDisplay 与刷新回放。
7. 系统验收额外暴露并修复两个运行时竞态：
   - execution lease 已取得后，必须在暴露 `SESSION/preparing` 前注册取消令牌，避免中断请求落入“无 active run 且 preparing 不可兜底取消”的窗口；
   - 计划事务提交 sequence 后，必须在 `TOOL_PHASE_CHANGED/TOOL_EXECUTION_COMPLETED` 等更大 sequence 前投影，避免前端单调游标丢弃迟到的计划事件。
8. 恢复 `scripts/acceptance/agent-runtime.ps1` 的 UTF-8 BOM；Windows PowerShell 5.1 对含中文的 BOM-less UTF-8 脚本会发生整文件词法解析错乱。
9. 将 approved-command 重启 fixture 从“固定 15 秒租约并依赖 JVM 足够快”改为数据库时钟驱动：先显式推进 lease 验证启动保留，再显式过期验证 poller 回收。

### 6.2 RED 证据

1. 首轮数据库/服务测试在生产类型尚不存在时编译失败，缺少 `AgentRunPlanItem`、Mapper、`AgentRunPlanService` 与目标 API。
2. checkpoint 恢复测试在 `restoreOrMigrate(...)` 尚不存在时失败；scripted provider 场景在未产生计划工具调用时以 `NoSuchElementException` 失败。
3. 首次后端系统验收在取消场景失败：`Direct cancellation stream did not project the authoritative persisted RUN_CANCELLED event.`。日志证明任务已进入 `preparing`，但取消令牌尚未注册，interrupt 的 inactive fallback 又拒绝 preparing，任务随后正常完成。
4. 首次浏览器计划验收失败：`Timed out waiting for live durable plan display`。数据库/outbox 已有 task 5 的 `PLAN_UPDATE` sequence `17/39/61`，但工具外围先发送了更大的 sequence，前端游标因此拒绝迟到计划事件。
5. 针对该顺序新增 `AgentLoopEngineStreamingContractTest.projectsTransactionallyPersistedPlanEventBeforeLaterToolLifecycleEvents`；实现移动前测试按预期失败，随后才修改生产调用点。
6. 最终 `run-all` 曾在 approved-command active-lease fixture 失败：固定 lease 为 15 秒，而受负载影响的 JVM 启动约 18.6 秒；恢复器在真实过期后回收进程是正确行为，错误的是验收把启动速度当作业务不变量。改为显式未来/过去数据库租约后，独立后端验收和最终组合门禁均通过。

### 6.3 定向 GREEN

后端核心定向验证：

```powershell
cd D:\LabexAgent\backend
mvn -q "-Dtest=AgentRunPlanServiceTest,AgentRunPlanMapperDatabaseTest,CreatePlanToolVerificationTest,TodoWriteToolDurabilityTest,AgentCheckpointStoreTest,AgentRunSchemaTest" test
mvn -q "-Dtest=AgentRunPlanServiceTest,AgentRunPlanMapperDatabaseTest,CreatePlanToolVerificationTest,TodoWriteToolDurabilityTest,AgentCheckpointStoreTest,AgentRunSchemaTest,AcceptanceScriptedProviderTest,AgentLoopEngine*Test" test
mvn -q "-Dtest=StudentAgentControllerCancellationTest,AgentCancellationRegistryTest,AgentLoopEngineCancellationTest,AgentTaskServiceLifecycleTest" test
mvn -q "-Dtest=AgentLoopEngineStreamingContractTest,AgentRunPlanServiceTest,AgentRunPlanMapperDatabaseTest,CreatePlanToolVerificationTest,TodoWriteToolDurabilityTest,AcceptanceScriptedProviderTest" test
```

以上命令均 exit `0`。数据库测试使用 H2 MySQL 模式真实执行 SQL，而不是只检查字符串。

### 6.4 完整验证

后端全量：

```powershell
cd D:\LabexAgent\backend
mvn -q test
```

结果：`1022` tests，`0` failure，`0` error，`8` skipped；共 `253` 个 surefire suite，exit `0`。

前端全量与生产构建：

```powershell
cd D:\LabexAgent\frontend
npm.cmd test
npm.cmd run build
```

结果：前端 `213/213` tests 通过；生产构建 exit `0`。chunk budget：

- `CloudWorkspace`: `1,464,867 / 1,500,000 bytes`；
- `index`: `1,259,636 / 1,300,000 bytes`；
- `TerminalPanel`: `380,367 / 400,000 bytes`。

独立后端重启验收：

```powershell
cd D:\LabexAgent
.\scripts\acceptance\agent-runtime.ps1 -BackendPort 18080 -TimeoutSeconds 120
```

成功 runId：`38b011765fbf40a7b47d10df919bb4b6`。关键证据：

- `durablePlanRestart=true`；
- `durablePlanCheckpointIndependent=true`；
- `durablePlanTaskId=8`；
- `durablePlanRevision=3`；
- `authoritativeCancellationProjection=true`；
- `cleanup=true`。

独立浏览器验收：

```powershell
cd D:\LabexAgent
.\scripts\acceptance\browser-runtime.ps1 -BackendPort 18080 -FrontendPort 13000 -CdpPort 19222 -TimeoutSeconds 120
```

成功 runId：`e0711719e8134f458af05c5528b94de5`。关键证据：

- `durablePlanRefreshReplay=true`；
- `durablePlanTaskId=5`；
- `durablePlanRevision=3`；
- `consoleErrors=0`；
- `networkErrors=0`。

最终组合门禁：

```powershell
cd D:\LabexAgent
.\scripts\acceptance\run-all.ps1 -BackendPort 18080 -FrontendPort 13000 -CdpPort 19222 -TimeoutSeconds 120
```

结果：`package=true`、`acceptanceUnitTests=true`、`backendRuntime=true`、`browserRuntime=true`；acceptance unit `15/15` 通过。

- 后端最终 runId：`49e79c33267840c69788d688271a5cda`；计划重启/epoch/revision/checkpoint 独立性、审批恢复、取消、outbox、compaction、fork 与 legacy history migration 全部通过。
- 浏览器最终 runId：`27df320213c745af86e434e8d70f98f6`；实时计划、刷新回放、会话隔离、审批/问题组件、durable history、compaction 与环境恢复全部通过，控制台/网络错误为 0。

### 6.5 风险、原有改动与停止边界

本轮只证明“任务计划”已经是数据库权威，不宣称所有 checkpoint 辅助状态均完成迁移。以下内容按既定边界留到独立后续轮次：

- `writeCount` / `verificationCount`；
- `trustedVerificationSources` / `unverifiedChangeTargets`；
- `stage`、last tool/result、run log pointer；
- 对所有“工具事务内先持久化领域事件、外围随后发送其他事件”的通用顺序审计；本轮只为计划事件建立了明确契约。

以下原有工作区内容未纳入本轮、未 reset/clean：

- `backend/src/main/resources/application-acceptance.yml`；
- `.codex-tmp/`；
- `docs/agent-context-provider-smoke-test.md`；
- `docs/coding-agent-engineering-roadmap.md`；
- `docs/coding-agent-industrialization/`；
- 既有未跟踪 `docs/superpowers/plans/*` 与 `docs/superpowers/specs/`。

本轮完成后停止，不继续做 checkpoint 其余字段迁移或运行时大类重写；这不等于上位 Agent Runtime 架构收敛目标已经全部完成。