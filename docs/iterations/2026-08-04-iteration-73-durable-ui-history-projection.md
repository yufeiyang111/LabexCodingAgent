# 第 73 轮：Durable UI 历史、刷新恢复与旧会话迁移

- 日期：2026-08-04
- 分支：`codex/agent-tool-reliability`
- 基线提交：`cc76a79 feat: project durable fork memory`
- 状态：完成
- 上位计划：`docs/superpowers/plans/2026-07-29-agent-runtime-state-convergence.md`

## 1. 问题陈述

第 72 轮已把 Provider memory、conversation compaction 与 fork 边界切到 durable graph，但浏览器历史仍存在两套互相竞争的事实源：

```text
首次打开/翻页
  -> GET /conversations/{id}/messages
  -> t_agent_message
  -> replayHistoryEvent(...)

刷新后的活跃任务恢复
  -> AgentTask + AgentRunMessage + AgentRunPart + AgentRunEvent SSE replay
  -> applyRunMessageSnapshot/applyRunPartSnapshot + live reducer
```

这导致：

1. 相同任务在首次加载与刷新恢复时可能呈现不同内容、工具状态和审批状态；
2. 终态确认后前端还要重新读取旧 history 来“补 FINAL”，说明终态与可见回答没有同一投影；
3. fork 必须复制旧 UI 事件才能显示父历史，与第 72 轮的 immutable task boundary 冲突；
4. `getMemoryStats(...)` 统计旧消息表，和 Provider 实际读取的 durable memory 不一致；
5. 停止旧写入后，旧会话如果没有迁移会直接变空。

## 2. OpenCode 对齐结论

参考：

- `D:/opencode/opencode-dev/packages/opencode/src/session/message-v2.ts`
- `D:/opencode/opencode-dev/packages/opencode/src/session/session.ts`
- `D:/opencode/opencode-dev/packages/opencode/src/acp/service.ts`

OpenCode 的稳定边界是：

- HTTP 分页返回持久化 `Message + Parts`；
- page cursor 由稳定 message identity 与创建时间组成，不按 UI 临时数组下标分页；
- 实时增量使用 `message.updated`、`message.part.updated`、`message.part.delta`；
- 初始恢复与实时事件更新同一组持久 message/part identity；
- fork 复制的是权威 message/part graph，不是仅供 UI 展示的事件缓存。

LabexAgent 的 `AgentTask` 同时承担 scheduler/lease 身份，不能为了 fork 克隆执行任务。因此沿用第 72 轮已验证的等价模型：父任务只通过 `parent_conversation_id + forked_from_task_id` 继承；UI projector 递归读取该 immutable boundary，不复制任务，也不复制旧 UI 事件。

## 3. 方案比较

### 方案 A：继续返回 `AgentMessage`

优点是改动小；缺点是保留第二事实源、fork 复制和终态补刷，违反上位计划。拒绝。

### 方案 B：前端先拉 task list，再逐 task 拉 detail/events

可以复用现有 API，但会产生 N+1、分页竞态、跨 fork graph 排序困难，且不同请求之间无法获得稳定页面。拒绝。

### 方案 C：会话级 durable history projector（采用）

后端一次返回稳定 task-turn 页面：

```text
Conversation lineage segment
  -> AgentTask.request_payload              (user turn)
  -> AgentRunEvent                          (可重放顺序与状态变化)
  -> AgentRunMessage + AgentRunPart         (可更新快照与修复投影)
  -> versioned ConversationHistoryPage DTO
```

前端对每个 task 创建一个稳定 assistant message：先用与实时 SSE 相同的 event reducer 回放 `AgentRunEvent`，再用 `RunMessage/RunPart` snapshot 修复最终文本、reasoning、tool/interaction 状态。连接断开或组件卸载不参与状态推断。

## 4. API 与投影契约

### 4.1 Endpoint

保留 URL 兼容但改变权威响应：

```text
GET /student/projects/{projectId}/agent/conversations/{conversationId}/messages
  ?beforeTaskId={exclusive cursor}
  &limit={task page size}
```

响应 `projectionVersion=durable-task-history-v1`，包含：

- `conversationId`
- `turns[]`
- `hasMore`
- `nextBeforeTaskId`
- `legacyMigrated`

旧 `beforeMessageId` 只在过渡期返回明确参数错误或映射为一次性 migration cursor，前端不再发送。

### 4.2 Turn DTO

每个 turn 至少包含：

- task identity：`taskId / conversationId / sourceConversationId / inherited / sessionId`
- task state：`mode / status / currentStep / summary / lastEventSequence`
- user projection：优先从 `request_payload.displayMessage` 读取用户原始输入，旧任务回退 `message`，损坏 payload 才回退 task title；Provider transcript 始终读取 `message`
- ordered `events`：`eventId / sequence / state / eventType / data / createdAt`
- ordered `runMessages`
- ordered `parts`
- task timing：`submittedAt / startedAt / finishedAt / activeElapsedMs`

分页以全局单调 `taskId` 为 exclusive cursor。每个 lineage segment 最多查询 `limit + 1` 个候选，合并后再选最新页面；不得静默截断 task。单 task 的事件、Message、Part 必须全部返回，超过显式安全上限时返回结构化错误，而不是半个 tool batch。

### 4.3 Fork graph

投影递归读取：

```text
grandparent <= grandchild.parent fork boundary
  + parent <= child.parent fork boundary
  + child own tasks
```

检测 parent cycle、越权 parent、超过 32 层 lineage并明确失败。`sourceConversationId` 标记事实来源，`conversationId` 保留当前打开会话；前端不创建复制 identity。

## 5. 旧会话一次性迁移

`t_agent_message` 从本轮开始只允许作为 migration source，不再是 UI/Provider/statistics 读路径，也不再接受新运行写入。

迁移规则：

1. 仅当会话存在 legacy rows 且 durable task/event projection 不完整时执行；
2. 按 `USER` 边界分组；优先把分组绑定到同会话、同用户、同项目、时间顺序一致的现有 task；缺失 task 时创建 `mode=legacy_import` 的 terminal task；
3. user 文本写入 task request payload；非 USER row 以稳定幂等键 `legacy-message:{messageId}` 写入 `AgentRunEvent`；
4. `FINAL/THINK/tool/lifecycle/context` 继续投影到 `AgentRunMessage/AgentRunPart`；
5. 同一 migration 重复执行不得创建重复 task/event/part；
6. 迁移完成后 GET 只重新读取 durable projector；不把 legacy DTO直接返回前端；
7. migration 不复活旧等待态，不覆盖已有 execution epoch/lease，不复制 secret 或内部 reasoning 原文。

退出条件：系统测试证明旧会话首次读取完成迁移，删除 legacy rows 后第二次读取仍完全一致。

## 6. Memory stats 契约

`getMemoryStats(...)` 改为 durable projector 统计：

- `messageCount`：投影后的 user + assistant 可见消息数，而不是旧事件行数；
- `estimatedTokens`：使用与 durable context 相同的安全估算，不用 summary 字符数冒充 token；
- `needsCompact`：依据 durable context 预算或最新 completed conversation compaction；
- `maxTokens`：使用当前明确的 context budget 配置；未知时进入保守分支。

统计必须包含 fork 继承边界内历史，并排除边界后的父任务。

## 7. TDD RED 用例

实现前先增加并执行：

1. history page 完全不查询 `AgentMessageMapper`（无迁移数据时）；
2. 页面按 task cursor 分页，事件/Message/Part 顺序稳定且无跨 task 混入；
3. child history 继承 parent 到固定 fork task，parent 后续 task 不泄漏；
4. nested fork、cycle、越权 parent、深度上限均有明确行为；
5. old AgentMessage 会话首次读取完成一次性 durable migration，第二次读取不重复；
6. 删除 legacy rows 后 migrated history 仍一致；
7. statistics 不读取 legacy table，并与 durable fork projection一致；
8. frontend 使用 `beforeTaskId`，不再解析 `eventData`/`messageId` 作为历史事实；
9. frontend 初始加载与实时 SSE 对同一 event sequence 得到相同 reducer 结果；
10. RunMessage/Part snapshot 能在缺失 FINAL SSE、刷新等待审批、tool running->terminal 时修复视图；
11. stale history response 不能覆盖用户新选中的 conversation；
12. terminal recovery 不再依赖重新读取旧 AgentMessage 来补 FINAL。

## 8. 实际实现范围

本轮修改/新增：

- backend service：conversation history projector、legacy history migrator、durable stats
- backend controller/DTO：versioned history page
- backend runtime：停止 `saveUserMessage/saveEvent` 旧写入
- fork service：停止复制旧 UI rows
- frontend API/state/reducer：task cursor、turn hydration、snapshot repair
- system acceptance：旧会话 migration、fork refresh、SSE disconnect/reload、审批卡恢复、浏览器可视化
- docs：本轮文档、上位收敛计划、必要的 API 行为说明

## 9. 验证门槛

1. backend 定向 RED/GREEN；
2. frontend node tests；
3. backend `mvn test`；
4. frontend `npm run build`；
5. 隔离 H2/Spring JVM：创建新会话、旧会话 migration、fork、等待审批、批准、断开 SSE、刷新、终态；
6. 真实浏览器：无需刷新即可看到审批卡；手工刷新后用户消息、思考摘要、工具终态、最终回答与 pending interaction 一致；
7. 删除旧 `AgentMessage` fixture 后再次刷新，结果不变；
8. 检查进程 PID、JVM 启动时间、实际 HTTP 响应和浏览器 DOM，不以编译成功替代运行验收。

## 10. 回滚与停止边界

回滚只允许切换 API 路由到上一个本地 Git 提交；不再保留运行时 `legacy/shadow` 配置分支。旧表保留只读 migration source，直到迁移监控证明不再有未迁移会话；不得恢复双写。

本轮停止边界：上述 durable history、旧会话 migration、统计切读、停止旧写入/fork复制、真实浏览器刷新验收全部完成并形成一个独立本地提交。完成前不得宣称整体 Agent 架构收敛完成。

## 11. 执行记录

### 11.1 RED 与根因

浏览器恢复验收首先暴露出 slash command 的真实分层错误：前端请求同时携带展开后的 Provider 输入 `message` 和用户原始输入 `displayMessage`，但 `AgentLoopEngine -> AgentTaskService` 只把 `message` 写入 `AgentTask.request_payload`。因此首次提交时 UI 显示 `/review src/App.vue`，刷新后 durable history 却显示内部展开模板。

先增加公共 history / Provider transcript 双边界回归：

```powershell
cd D:\LabexAgent\backend
mvn "-Dtest=AgentConversationHistoryProjectionServiceTest,AgentConversationTranscriptProjectionServiceTest" test
```

RED 结果：`9` 个测试中 `1` 个失败；失败值明确是公共历史返回 `Expanded provider review prompt` 而不是 `/review src/App.vue`。同一轮 Provider transcript 测试通过，证明修复不能简单交换两个字段。

### 11.2 实现结果

1. `AgentConversationHistoryProjectionService` 以 `AgentTask + AgentRunEvent + AgentRunMessage + AgentRunPart` 投影 versioned task-turn 页面，按 `beforeTaskId` 稳定分页并递归读取 immutable fork boundary。
2. `AgentLegacyConversationHistoryMigrationService` 将旧 `t_agent_message` 一次性迁移为 terminal task + 幂等 run event，写入 `durable-v1` marker；后续读路径不再回退旧表。
3. `AgentConversationService`、fork 和 compaction 停止写入/复制旧 UI 消息；memory stats 和会话 memory 读取 durable graph。
4. 前端 `useConversationState` 使用与实时 SSE 相同的 reducer 回放持久事件，再用 RunMessage/Part snapshot 修复最终文本、reasoning、tool 与 interaction 状态；分页 cursor 改为 task identity。
5. `AgentTaskService` 同时持久化 `message` 与 `displayMessage`：task title 和公共历史使用用户可见输入，Provider transcript 保持使用展开后的模型输入。
6. 验收脚本新增真实旧会话迁移场景：直接向隔离 H2 写入 legacy rows，首次 GET 触发迁移，重复 GET 验证幂等，删除全部 legacy rows 并重启 JVM 后再次验证 task/event 历史不变。

### 11.3 验证证据

针对性 GREEN：

```powershell
cd D:\LabexAgent\backend
mvn "-Dtest=AgentTaskServiceTest,AgentConversationHistoryProjectionServiceTest,AgentConversationTranscriptProjectionServiceTest" test
```

结果：`12` 个测试，`0` failure，`0` error。

后端全量：

```powershell
cd D:\LabexAgent\backend
mvn test
```

结果：`1011` 个测试，`0` failure，`0` error，`8` 个既有 skip；`BUILD SUCCESS`。

前端全量与生产构建：

```powershell
cd D:\LabexAgent\frontend
npm.cmd test
npm.cmd run build
```

结果：两条命令均 exit `0`。Vite chunk budget 通过：

- `CloudWorkspace`: `1,464,867 / 1,500,000 bytes`；
- `index`: `1,259,636 / 1,300,000 bytes`；
- `TerminalPanel`: `380,367 / 400,000 bytes`。

完整隔离 JVM/SSE/浏览器验收：

```powershell
cd D:\LabexAgent
.\scripts\acceptance\run-all.ps1 -RestartBrowserBackend -TimeoutSeconds 180
```

结果：`package=true`、`acceptanceUnitTests=true`、`backendRuntime=true`、`browserRuntime=true`。

- 后端 runId：`9d0d4ce22f1a43398ff71122a47cbb79`；审批批准/拒绝、问题回复、命令取消与重启恢复、outbox、Part authority、compaction epoch、fork boundary/compaction/restart 全部通过。
- 浏览器 runId：`2d1c2ec78a5848a2b102e103e2ddc16d`；`slashAgentPromptDisplayStable=true`、`durableHistoryProjection=durable-task-history-v1`、`refreshReplayDeduplicated=true`、`permissionApprovalRefreshRecovery=true`、`restartProjectionVerified=true`、`consoleErrors=0`、`networkErrors=0`。

补充真实 legacy migration 验收：

```powershell
cd D:\LabexAgent
.\scripts\acceptance\agent-runtime.ps1 -BackendPort 18080 -TimeoutSeconds 180
```

成功 runId：`1ff3bab6c3954b5697911bed8773ecc6`。证据：

- `legacyHistoryMigration=true`；
- `legacyHistoryMigrationTaskId=27`；
- 重复读取没有增加 task/event；
- 删除 `t_agent_message` fixture 后重启 JVM，`legacyHistoryMigrationRestart=true`；
- 用户可见输入与最终回答仍存在，内部 reasoning 原文未泄漏；
- `cleanup=true`。

### 11.4 原有改动与停止边界

以下原有工作区内容没有纳入本轮提交，也没有 reset/clean：

- `backend/src/main/resources/application-acceptance.yml`（工作区状态/换行差异，无 tracked content diff）；
- `.codex-tmp/`；
- `docs/agent-context-provider-smoke-test.md`；
- `docs/coding-agent-engineering-roadmap.md`；
- `docs/coding-agent-industrialization/`；
- 既有未跟踪 `docs/superpowers/plans/*` 与 `docs/superpowers/specs/`。

本轮停止于 durable UI history、旧会话迁移、slash 可见输入、fork/compaction 历史和真实刷新恢复全部通过；这不等于上位 Agent Runtime 架构收敛目标已经全部完成。
