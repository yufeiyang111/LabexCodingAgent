# Agent Runtime State Convergence Plan

> **For implementation:** Follow this plan in order. Do not begin a later task until the preceding task has a regression test and a verification record.

- **日期：** 2026-07-29
- **范围：** `backend/src/main/java/com/labex/labexagent/**`、`backend/src/test/java/com/labex/labexagent/**`、`frontend/src/**`、相关数据库 schema 与架构文档
- **目标：** 将当前并列的 `AgentTask`、内存 `msgs`、运行 Message/Part、事件/outbox、summary/checkpoint、前端 SSE 状态收敛成一个可持久化、可恢复、可解释的 Agent 运行状态机。
- **参考：** `D:\opencode\opencode-dev\packages\opencode\src\session`

## 1. 设计结论

当前项目不是 LangGraph 式的显式有向图，也不是可靠的 LangChain 链式调用；它是一个带持久化任务、工具循环、交互等待和 SSE 投影的自定义运行时。问题不在于“缺一个框架”，而在于同一个事实被多个状态源重复表示：

```text
AgentTask / lifecycle
        ↓
AgentRunMessage + AgentRunPart  ← 唯一运行 transcript 与 Tool Part
        ↓
AgentProviderMessageProjector
        ↓
Provider request
        ↓
AgentRunEvent + transactional outbox
        ↓
SSE / history replay / frontend reducer
```

必须坚持以下边界：

1. `AgentTask` 只负责运行身份、状态、epoch、租约和恢复目标。
2. `AgentRunMessage` / `AgentRunPart` 负责模型回合、工具调用、工具结果、思考可见片段、交互和恢复 Part。
3. `AgentRunEvent` / outbox 负责事件传输和断线回放，不反向成为状态数据库。
4. Provider 请求由持久化 transcript projector 派生，内存列表只能作为短期缓存。
5. 前端只能消费 durable history + event reducer，不能用 EventSource 是否打开推断任务状态。
6. summary、checkpoint、workspace memory 必须分别命名、分别归属，并能解释自己的作用域和生命周期。

## 2. 全局约束

- 不进行一次性重写，不引入 LangGraph/LangChain 作为新的并列运行时。
- 不删除用户现有工作区改动，不修改密钥或 `.env`，不做数据库 reset/破坏性迁移。
- 所有新增代码注释使用中文；公共 API、数据库列、事件类型先写契约再实现。
- 兼容迁移遵循：双写 → shadow compare → 切换读取 → 监控旧写入 → 删除旧路径。
- 每一项完成必须提供：回归测试、命令结果、若有 live 验证则记录进程/PID/URL/游标证据。

## 3. 任务一：固定当前真实失败边界

### 目标
先把“审批后断流、重复恢复、工具不停止、context overflow、刷新后状态错误、tool_call 对不起来”等问题变成可重复的测试，而不是凭日志猜测。

### 修改/新增

- 新增或整理：`backend/src/test/java/com/labex/labexagent/run/AgentRunStateCharacterizationTest.java`
- 新增或整理：`backend/src/test/java/com/labex/labexagent/runtime/AgentProviderProtocolCharacterizationTest.java`
- 新增：`frontend/src/composables/agentRunRecovery.integration.test.mjs`
- 更新：`docs/coding-agent-industrialization/opencode-alignment-status.md`

### 必须覆盖

1. 同一 `taskId` 的审批批准只能生成一次恢复执行；重复请求返回同一结果，不得触发第二次工具副作用。
2. SSE 首次连接关闭后，持久事件订阅能从 cursor 继续；断线不能把任务标成完成。
3. 一个 assistant turn 返回多个 tool call 时，所有 call 都入库，顺序和 `toolCallId` 稳定。
4. `role=tool` 必须关联正确的 `tool_call_id` 和工具名。
5. compaction/裁剪后仍能构造合法 Provider 消息。
6. context overflow 必须在有限次数内进入明确失败或恢复态，不得无限重试。
7. 重启后开放 Part 只能进入明确的 `interrupted` 或合法恢复态。

### 验证

```powershell
cd D:\LabexAgent\backend
mvn -Dtest=AgentRunStateCharacterizationTest,AgentProviderProtocolCharacterizationTest test
cd D:\LabexAgent\frontend
npm run build
```

## 4. 任务二：建立 Provider transcript projector 与协议校验

### 新增

- `backend/src/main/java/com/labex/labexagent/runtime/AgentProviderMessage.java`
- `backend/src/main/java/com/labex/labexagent/runtime/AgentProviderMessageProjector.java`
- `backend/src/main/java/com/labex/labexagent/runtime/AgentProviderProtocolValidator.java`
- `backend/src/main/java/com/labex/labexagent/runtime/AgentProviderProtocolException.java`
- 对应单测：`AgentProviderMessageProjectorTest.java`、`AgentProviderProtocolValidatorTest.java`

### 规则

- projector 的输入是持久化的 Run Message/Part 和 compaction epoch。
- projector 输出唯一的 OpenAI-compatible message 结构。
- 不允许通过 `Map.of("role", ..., "content", ...)` 重建 tool message，因为这会丢失 `tool_call_id`、`name` 和 tool calls。
- validator 必须拒绝孤立 tool result、重复 tool id、顺序错误、缺失工具名、缺失 arguments 等协议错误。
- 在该任务完成前，保留 `AgentLoopEngine` 的旧路径但只能做 shadow compare；不能悄悄改变旧路径的行为。

### 验证

```powershell
cd D:\LabexAgent\backend
mvn -Dtest=AgentProviderMessageProjectorTest,AgentProviderProtocolValidatorTest,AgentLoopEngineStreamingContractTest test
```

## 5. 任务三：把 Run Message/Part 变成可重建 transcript

### 修改

- `backend/src/main/resources/sql/schema.sql`
- `backend/src/main/java/com/labex/labexagent/entity/AgentRunMessage.java`
- `backend/src/main/java/com/labex/labexagent/entity/AgentRunPart.java`
- `backend/src/main/java/com/labex/labexagent/service/AgentRunMessageService.java`
- `backend/src/main/java/com/labex/labexagent/service/AgentRunPartService.java`
- 新增 `backend/src/main/java/com/labex/labexagent/service/AgentRunTranscriptService.java`
- 对应 mapper、DTO、服务测试

### 建议字段

按现有命名风格做 additive migration，不重建表：

- `turn_index`
- `part_index`
- `role`
- `provider_message_id`
- `tool_call_id`
- `tool_name`
- `tool_arguments_json`
- `content_json`
- `status`
- `compaction_epoch`
- `created_at`、`updated_at`

### 关键约束

- 稳定唯一键：`task_id + epoch + turn_index + part_index`；tool call 另外约束 `task_id + epoch + tool_call_id`。
- 写入必须批量且事务化；先写完整 assistant tool batch，再执行工具。
- transcript service 提供 `appendUserTurn`、`appendAssistantTurn`、`appendToolResult`、`markPartTerminal`、`loadProjectableTranscript`。
- 将现有 `AgentToolCallJournalService` 变成该服务的适配层，不再拥有第二套 Part 事实。

## 6. 任务四：双写并切换 AgentLoopEngine 的输入来源

### 修改

- `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- `backend/src/main/java/com/labex/labexagent/service/AgentTranscriptProjectionService.java`
- `backend/src/test/java/com/labex/labexagent/runtime/AgentLoopEngineTranscriptRecoveryTest.java`

### 执行顺序

1. 每轮先把 user/assistant/tool 消息写入 durable transcript。
2. 旧 `msgs` 与 projector 输出做 canonical JSON shadow compare；差异必须进入测试失败或诊断事件。
3. 默认改为 projector 输出驱动 Provider 请求。
4. 保留旧结构只用于 debug，不允许修改后再次成为事实源。
5. 重启恢复时只从 task + transcript + compaction epoch 重建，不读取旧进程内存。

## 7. 任务五：实现有 epoch 的 compaction

### 新增

- `backend/src/main/java/com/labex/labexagent/context/AgentCompactionService.java`
- `backend/src/main/java/com/labex/labexagent/context/CompactionSelection.java`
- `backend/src/main/java/com/labex/labexagent/context/AgentCompactionRecord.java`
- `backend/src/main/java/com/labex/labexagent/context/AgentContextOverflowException.java`
- `backend/src/main/java/com/labex/labexagent/context/AgentRequestTokenEstimator.java`
- 对应单测与故障注入测试

### 行为

- 以真实 user turn 分组，包含完整的 assistant tool batch 和所有 tool result。
- 一个 compaction record 必须保存：previous summary、compacted head、保留 tail、epoch、估算 token、模型窗口、状态、失败原因。
- summary 只压缩 head；recent tail 不重复注入，也不重复 summary-of-summary。
- tool call、tool result、媒体、长工具输出按协议安全规则处理，不得简单删半段 JSON。
- token 预算覆盖 system prompt、tool schema、消息 role/name、tool arguments、tool results 和 output reserve。
- 未知模型窗口走安全保守配置；禁止 1,000,000 默认值。
- overflow 使用结构化异常，最多执行有限次策略切换：压缩、减少工具 schema、请求用户或终止；不能无限 retry。

### 验证

```powershell
cd D:\LabexAgent\backend
mvn -Dtest=AgentCompactionServiceTest,AgentRequestTokenEstimatorTest,AgentProviderMessageProjectorTest test
```

## 8. 任务六：收敛生命周期状态机

### 新增/修改

- `backend/src/main/java/com/labex/labexagent/run/AgentRunState.java`
- `backend/src/main/java/com/labex/labexagent/run/AgentRunStateMachine.java`
- `backend/src/main/java/com/labex/labexagent/run/AgentRunLifecycleService.java`
- `backend/src/main/java/com/labex/labexagent/run/AgentRunInteractionService.java`
- `backend/src/main/java/com/labex/labexagent/run/AgentRunResumeScheduler.java`
- 所有直接写 `AgentTask.status` 的调用点和对应测试

### 规则

- 只有 lifecycle service 能提交状态迁移。
- 每次迁移记录 previous/next/actor/reason/epoch/idempotency key。
- `waiting_approval`、`waiting_user`、`waiting_workspace`、`retry_backoff` 都是持久等待态。
- `completed`、`failed`、`cancelled` 不可回退；重新执行必须新建 epoch。
- 恢复接口必须基于稳定 transition key 去重，不能把批准动作当成新对话。
- scheduler 只领取合法过期租约，不重放已完成 transition。

## 9. 任务七：前端改为 durable projection

### 修改

- `frontend/src/composables/agentHistoryReducer.js`
- `frontend/src/composables/useAgentEventTimeline.js`
- `frontend/src/composables/useAgentTaskRuntime.js`
- `frontend/src/composables/useAgentInteraction.js`
- `frontend/src/components/cloud/ToolCallCard.vue`
- `frontend/src/views/CloudWorkspace.vue`
- `frontend/src/api/index.js`

### 规则

- 初始 HTTP、SSE、持久事件订阅和恢复响应全部进入同一个 reducer。
- reducer 以 `conversationId + taskId + sessionId + cursor + partKey` 去重，不以消息到达时序猜测状态。
- 审批卡片直接从 durable interaction/Tool Part 渲染；批准后保留原卡片并转为 running/completed，不新建“下一次对话”。
- 页面刷新先加载 active-task/history，再连接 SSE；连接断开仍保留 durable 状态。
- 新会话必须清空旧 task 的派生 UI 状态，但不能删除旧历史。
- 删除“仅在 EventSource open 时显示运行”的条件渲染。

### 验证

```powershell
cd D:\LabexAgent\frontend
npm run build
node --test src/composables/agentHistoryReducer.test.mjs src/composables/useAgentTaskRuntime.test.mjs src/composables/useAgentInteraction.test.mjs
```

## 10. 任务八：删除旧路径，而不是继续兼容堆叠

满足以下条件后才删除：

1. projector 与旧 `msgs` 连续 shadow compare 无差异；
2. 重启、断线、审批、compaction、overflow 的回归测试全部稳定；
3. live smoke 已证明同一个 task 不会重复执行；
4. 监控显示旧路径没有新的写入；
5. 文档、DTO、前端 reducer 已切到新路径。

删除对象包括：重复的 conversation summary 事实、仅用于维持旧 UI 的 toolCalls 权威路径、直接改 status 的旁路服务、以字符串编码状态的恢复分支，以及无限重试 fallback。兼容 DTO 可保留，但必须标注为只读投影。

## 11. 任务九：故障注入与现场验收

新增故障注入场景：

- Provider 流中断；
- SSE 在 approval 前后断开；
- 审批重复提交、过期提交、服务重启后提交；
- 工具进程超时、返回半截结果、命令未启动；
- compaction 失败、token estimator 缺模型窗口；
- JVM 重启、旧 lease 过期、scheduler 重复领取。

每个场景必须检查：数据库状态、事件顺序、Tool Part 终态、前端回放、是否发生重复副作用、是否能继续或明确失败。源码测试通过但未启动实际后端/浏览器时，只能报告为“测试通过”，不能报告为“系统已修复”。

## 12. 完成定义

只有全部满足才可以宣称架构收敛完成：

- Provider 请求完全由 durable transcript projector 产生；
- `AgentTask` 是运行状态唯一权威，所有状态迁移可审计、幂等、可恢复；
- compaction 有 epoch、head/tail、summary 和状态记录；
- `tool_call_id`、tool name、arguments、result 在裁剪/重启后仍完整；
- 审批不是新对话，而是原 task/epoch 的一次幂等恢复；
- SSE/浏览器刷新只影响连接，不影响任务事实；
- 前端只渲染 durable projection；
- 旧并列路径已删除或明确降级为只读兼容投影；
- 后端测试、前端测试、构建和 live fault-injection 全部有证据；
- 文档不再把“已有部分落地”写成“已完成 OpenCode 等价对齐”。

## 13. 执行进度（截至 2026-08-05）

### 第 71 轮进度：conversation compaction authority shadow 已完成

已完成：

- conversation transcript 可以只从 `AgentTask.request_payload` 与 `AgentRunMessage(assistant:final)` 投影；
- `AgentCompactionRecord` 已区分 `scope=task` 与 `scope=conversation`；
- conversation compaction 已持久化 head、tail、summary、sourceMaxTaskId、epoch 和 execution epoch，并拒绝不降低估算 token 的伪压缩；
- conversation 行锁、`expectedPreviousCompactionId` 和 running-record 检查已阻止并发旧快照写入；
- 手动压缩 task 已接入 execution lease 与 heartbeat，并以 lease 获取作为 cancellation token 注册和 durable commit 的终态围栏；
- 旧 `COMPACTION_SUMMARY` 已降级为带 `authority=agent_compaction_record`、`projectionOnly=true` 的兼容投影；
- 后端 993 项测试、前端生产构建和隔离 H2/Spring JVM/HTTP/SSE/restart 系统验收已通过。

证据文档：`docs/iterations/2026-08-04-iteration-71-durable-conversation-compaction-shadow.md`。

尚未完成，因此本计划仍不能标记为全部完成：

- fork 仍以 `AgentMessage` 历史为兼容事实；
- `AgentLoopEngine.buildMemoryContext(...)` 尚未切到 conversation compaction projector；
- 新旧 conversation memory 尚未完成 shadow compare 与读路径切换；
- 旧 `AgentConversationService.compactConversation(...)` 尚未删除。

上述停止边界已于第 72 轮完成，证据如下。

### 第 72 轮进度：durable fork graph 与 Provider memory read 已完成

已完成：

- `t_agent_conversation.forked_from_task_id` 已成为不可变 fork task 边界；
- `AgentConversationForkBoundaryService` 已统一新 fork、显式 task、旧 messageId 映射和 lazy backfill；
- `AgentConversationMemoryProjectionService` 已递归投影 parent graph、当前会话 task/run-message 和 completed conversation compaction；
- Provider conversation memory 默认读模式已切到 `durable`，`legacy` / `shadow` 仅保留为回滚与迁移诊断；
- child compaction 已从同一个 durable projector 取完整 parent + child 输入；
- 旧 `AgentConversationService.compactConversation(...)` 已删除；
- fork API 已支持稳定 `taskId`，旧 `messageId` 继续兼容；
- transcript projector 已支持有界分页，不再在 500 个 task 后静默丢失；
- 后端 1006 项测试、前端生产构建和隔离 H2/Spring JVM/HTTP/SSE/命令进程/restart 验收已通过；
- 真实验收在删除 child 的旧 `AgentMessage` 副本后仍恢复 parent 边界内历史，并证明 parent 边界后 task 不泄漏；
- 验收长驻进程 fixture 已从不稳定的 Windows `python3` alias 改为受正式审批的项目内 Node 脚本。

证据文档：`docs/iterations/2026-08-04-iteration-72-durable-fork-memory-read.md`。

尚未完成，因此本计划仍不能标记为全部完成：

- 前端 history/reducer 的历史加载仍以 `AgentMessage` 兼容事件为主；
- `AgentConversationService.getMemoryStats(...)` 仍从旧消息表统计；
- fork 仍复制旧 UI 事件，尚未停止旧路径写入；
- `legacy` / `shadow` 回滚模式尚未达到删除条件；
- 需要在 durable history API/reducer 切换后补浏览器刷新、旧会话迁移和可视化回放验收。

下一轮停止边界：前端历史、统计和刷新恢复全部改读 durable Message/Part/Event projection；在真实浏览器回放通过后，停止 fork 的旧事件复制并删除 legacy/shadow memory 代码。不得在这一步完成前宣称整个 Agent 架构收敛完成。
### 第 73 轮进度：durable UI history 与旧会话迁移已完成

已完成：

- `AgentConversationHistoryProjectionService` 已以 `AgentTask + AgentRunEvent + AgentRunMessage + AgentRunPart` 投影版本化 task-turn 页面，并使用稳定 task cursor 分页；
- 前端历史加载与实时 SSE 已复用同一 reducer/Part projector，刷新不再回读旧 `AgentMessage` 作为事实源；
- slash command 同时持久化 Provider 输入和用户可见 `displayMessage`，刷新后不再暴露内部展开模板；
- fork/compaction 停止复制旧 UI 事件，memory stats 已切读 durable graph；
- `AgentLegacyConversationHistoryMigrationService` 将旧会话一次性迁移为 terminal task + durable events，后续不再双写；
- 后端 `1011` 项测试、前端生产构建、隔离 H2/JVM/HTTP/SSE/浏览器刷新与 legacy migration 重启验收全部通过；
- 本轮已形成本地提交 `09325dc feat: project durable conversation history`。

证据文档：`docs/iterations/2026-08-04-iteration-73-durable-ui-history-projection.md`。

保留边界：旧 `t_agent_message` 仅作为只读 migration source 保留，不能恢复为运行时回退或双写路径；整体 Agent Runtime 仍需继续审计其他 checkpoint/派生状态。

### 第 74 轮进度：durable task plan authority 已完成

已完成：

- 新增 `t_agent_run_plan_item` 与 `AgentRunPlanService`，按 task 行锁、execution epoch、position 和单调 revision 保存完整有序计划；
- `create_plan` / `todo_write` 已统一写入该事实源，`AgentContext` 只保存可重建投影；
- 计划行与 `PLAN_UPDATE` 在同一事务提交，使用稳定幂等键，主循环只发送已持久化 sequence；
- checkpoint v2 已删除 plan/currentPlanIndex 权威，v1 仅可在数据库为空时提供一次性迁移 seed；
- 启动、恢复和最终完成门禁均重新读取数据库计划；过期 execution epoch 写入被拒绝；
- 修复 `preparing` 先于取消令牌注册的中断竞态，以及计划事件 sequence 晚于工具生命周期事件投影而被前端游标丢弃的竞态；
- 后端 `1022` 项测试（`0` failure、`0` error、`8` skipped）、前端 `213/213` 测试和生产构建全部通过；
- 最终 `run-all` 四段门禁全部通过：后端 runId `49e79c33267840c69788d688271a5cda`，浏览器 runId `27df320213c745af86e434e8d70f98f6`，实时计划与刷新回放 revision `3`，控制台/网络错误为 `0`。

证据文档：`docs/iterations/2026-08-04-iteration-74-durable-task-plan-projection.md`。

尚未完成，因此本计划仍不能标记为全部完成：

- checkpoint 中的 `writeCount`、`verificationCount`、trusted/unverified verification state、stage、last tool/result 和 run-log pointer 尚未完成所有权审计与数据库迁移；
- 仍需审计所有“工具事务内先提交领域事件、外围再发送其他 durable event”的通用顺序，避免计划之外出现同类游标倒序；
- 需要基于本计划第 12 节逐项做最终架构收敛审计，确认所有旧兼容路径均已删除或明确降级为只读迁移源后，才能关闭总目标。

下一轮停止边界：只审计并迁移 checkpoint 的剩余执行辅助状态与通用 durable-event 投影顺序；不得顺带重写整个 `AgentLoopEngine` 或进行无关前端视觉改版。

### 第 75 轮进度：durable execution progress projection 已完成

已完成：

- `AgentRunExecutionProgressReducer` 成为运行时增量和重启重放共用的唯一进度规则；stage、write/verification count、trusted/unverified targets、last tool/result 和 run-log pointer 均由 `AgentRunPart` / `AgentRunEvent(SESSION)` 投影。
- `AgentRunProgressProjectionService` 校验 execution epoch，数据库 Tool Part/Event 优先；旧 v1/v2 checkpoint 只允许一次幂等 `RUN_PROGRESS_MIGRATED` 迁移，不能覆盖已有 durable 事实。
- `AgentCheckpointStore` 已退役为只读 legacy reader，`AgentLoopEngine` 删除新 checkpoint 写入路径；Provider 调用边界使用不落库的只读运行时投影，避免派生状态成为 transcript 第二事实源。
- `AgentSsePublisher` 已对所有 durable event 做 sequence 缺口补齐；前端单调 cursor 不会因为工具事务事件晚于外围 event 而跳过已提交事实。
- 后端 1,033 项测试、前端 213 项测试、15 项验收单元测试和生产构建通过；后端真实 H2/Spring JVM/HTTP/SSE/restart acceptance run `fe05cdb9627448cba97935db233356cf` 通过；浏览器 restart/refresh acceptance run `99621f04b5734dba8c90d568ee5f0cf3` 通过，console/network errors 均为 0。

证据文档：`docs/iterations/2026-08-04-iteration-75-durable-execution-progress-and-event-order.md`。

尚未完成，因此本计划仍不能标记为全部完成：

- 仍需按第 12 节逐项审计所有 Provider transcript、legacy `t_agent_message`、checkpoint reader、兼容 projection 和 shadow 开关的删除条件；本轮只完成 execution progress 这一条边界。
- 仍需确认所有状态迁移、compaction、approval、question、tool batch 和 frontend reducer 的旧兼容路径均已停止写入，并为每个删除动作补迁移/回滚证据。
- 仍需在最终架构审计中核对启动 JVM 的 PID、启动时间、classpath 与实际加载 class，避免把源码验证误报成运行时验证。

下一轮停止边界：只做第 12 节最终审计与遗留兼容路径收口；不得重新引入第二套状态、transcript、checkpoint 或前端实时事实源，也不得以本轮 acceptance 通过宣称整体 OpenCode 等价已经完成。
### 第 76 轮计划：收口旧 memory 构造入口与 fork durable 边界

本轮只处理最终审计发现的兼容入口，不扩大到 AgentLoopEngine 重写或前端视觉改版：

1. 删除 `AgentConversationService` 中携带 `AgentMessageMapper`、`AgentTaskMapper` 和 `legacy/shadow` 字符串的旧构造器；生产服务只接受 durable memory/history/fork projector。
2. 更新已有回归测试，使测试直接装配生产构造路径，并保留“memory 不读取旧消息表”的回归断言。
3. 为 fork 增加 durable task 优先的回归覆盖：显式 `taskId` 时不得触碰 legacy message mapper；只有旧 `messageId` 兼容请求才允许进入 fork boundary 的一次性 legacy 映射。
4. 对代码、测试和文档做最终审计，明确保留的两类迁移 reader：`t_agent_message` 历史迁移与 v1/v2 checkpoint 一次性迁移；二者都不得成为 Provider/UI/runtime 的常规事实源。
5. 运行定向后端测试、完整后端测试、前端测试/构建和现有系统 acceptance；若 acceptance 环境不可用，必须记录具体阻断原因，不以编译替代真实验收。

本轮停止边界：不删除 `AgentMessage` 表、`AgentLegacyConversationHistoryMigrationService` 或 `AgentCheckpointStore`，因为它们仍需覆盖未迁移存量；不改变数据库 schema；不宣称总计划第 12 节已全部完成，除非最终 JVM/浏览器/迁移退出条件均有独立证据。
### 第 76 轮进度：durable memory 构造入口与 WSL 审批取消边界已完成

已完成：

- `AgentConversationService` 只保留生产五参数 durable 构造器；携带 `AgentMessageMapper`、`AgentTaskMapper` 或 `legacy/shadow` 字符串的旧构造入口已删除。
- Provider memory、UI history、memory stats 和显式 task fork 均从 durable projector/boundary 进入；稳定 `taskId` fork 的回归测试证明不会访问旧 `AgentMessageMapper`。
- `AgentLegacyConversationHistoryMigrationService` 与 `AgentCheckpointStore` 已重新审计并明确为只读迁移入口：前者只读旧消息后创建 durable graph，后者只读 v1/v2 seed；两者都不是常规 Provider/UI/runtime 事实源。
- 新增 package-private `WslCommandSupervisor`，非交互 WSL 命令取消/超时先通过 workspace 临时控制文件终止 Linux 进程组，2.5 秒后才允许 Windows wrapper 强制兜底；`WslSandboxWorker` 保持轻量编排，终端路径不变。
- 后端全量 1,033 项测试、前端 213 项测试、15 项验收单元测试和生产构建通过；真实 WSL execute/cancel/next-command/toolchain/terminal smoke 通过。
- 后端真实 restart acceptance run `631419761ea245a2a010485a4b91f1b4` 通过；审批命令取消耗时 `2903ms`，进程身份、active lease、孤儿回收、durable history/compaction/fork/plan/progress/SSE 顺序全部通过。
- 浏览器真实 acceptance run `3dddca4eaad14b60aeacbfbcedf286f5` 通过；审批/提问刷新恢复、durable history、模型重试、compaction/fork/restart 和内部思考隐藏通过，console/network errors 均为 0。

证据文档：`docs/iterations/2026-08-05-iteration-76-durable-memory-entry-and-wsl-cancellation.md`。

尚未完成，因此本计划仍不能标记为全部完成：

- `t_agent_message` 与 v1/v2 checkpoint reader 仍需服务未迁移存量；必须先建立存量归零、读取命中和删除版本的可观测退出条件，不能直接删表或删 reader。
- deterministic acceptance 使用 scripted Provider；真实外部云 Provider 的代理/TLS/stream interruption 仍需具备本地凭据后的独立 smoke。
- 最终关闭总目标前仍需逐项核对第 12 节，并确认兼容 reader 达到删除条件，而不是仅凭本轮 JVM/浏览器验收宣称 OpenCode 等价完成。

下一轮停止边界：只建立 legacy history/checkpoint reader 的迁移命中遥测、存量归零报告和删除条件；不得新增平行 transcript、状态机或前端本地事实源，也不得顺带进行无关 UI 改版。

### 第 77 轮计划：legacy migration 读取遥测与删除门槛

本轮只建立两个只读兼容 reader 的可删除证据，不扩大为新的 transcript、checkpoint 或状态机：

1. 为 legacy history/checkpoint reader 建立事务化命中计数、最近命中、pending inventory、零存量起点、观察窗口和目标删除版本。
2. checkpoint 首次 resumed inspection 后写稳定幂等 `LEGACY_CHECKPOINT_INSPECTED` durable event；后续恢复必须跳过旧文件读取。
3. 提供 `ROLE_ADMIN` 限制的全局聚合报告，不向普通用户泄露跨用户项目或历史统计。
4. 报告必须区分 pending source、durable-covered archive source、invalid/unowned source；只有 pending 为 0 且连续 14 天无 reader hit 时才允许目标版本 `1.1.0` 删除 reader。
5. 运行定向测试、完整后端/前端门槛和真实 JVM/restart/browser acceptance，并记录 hit count 在重复恢复后不增长的数据库证据。

本轮停止边界：不删除旧表、旧文件或 reader；不新增前端本地状态；不把 migration gate 写入 Provider prompt；不以一次零存量快照代替连续观察窗口。
### 第 77 轮进度：legacy migration 删除门槛与可见终态错误已完成

已完成：

- 新增事务化 `t_agent_legacy_migration_gate`，为 legacy history/checkpoint reader 保存读取命中、pending inventory、零存量起点、14 天观察窗口和目标删除版本 `1.1.0`。
- history reader 只在真实旧消息迁移时命中；checkpoint reader 首次 resumed inspection 后写幂等 `LEGACY_CHECKPOINT_INSPECTED` durable event，重复恢复和 JVM 重启均跳过旧文件读取。
- checkpoint inventory 已增加规范路径、身份归属、文件大小、扫描深度、条目上限和符号链接边界；损坏、未归属或归属不匹配来源继续阻断删除。
- 新增 ADMIN-only 聚合接口 `/admin/agent/runtime/legacy-migration-readiness`；普通用户真实请求返回 403。
- 系统验收发现并修复“Provider 已输出部分正文后终态失败不可见”的实时/历史投影缺陷；正文与错误现在幂等合并，刷新前后行为一致。
- 后端 `1043` 项测试（`0` failure、`0` error、`9` skipped）、前端 `215/215` 测试、`16/16` 验收单元测试和生产构建全部通过。
- 后端真实 JVM/H2/HTTP/SSE/restart acceptance run `f1685da75d9a42d9bd9f366f6b19b073` 通过；浏览器 acceptance run `af6451e436cb4d66bf4fda72b0006719` 通过，legacy marker 未显示为消息文本，Provider 中断终态实时可见，console/network errors 均为 0。

证据文档：`docs/iterations/2026-08-05-iteration-77-legacy-migration-removal-gates.md`。

本轮停止边界已经到达：

- 不物理删除 `t_agent_message`、旧 checkpoint 文件或兼容 reader；必须等待真实运行形成连续 14 天零存量且无新命中的证据。
- 不把 deterministic scripted Provider 验收误报为真实外部云 Provider/代理/TLS smoke。
- 不继续扩大 AgentLoopEngine、状态机、前端 UI 或其他架构重构；下一步仅由用户进行常用浏览器和现有数据的手工验收，或在观察窗口满足后另开独立删除迭代。

### 第 78 轮计划：上下文来源与有效压缩线可视化修正

本轮只修正上下文预算派生投影和用户可见解释，不改变 compaction 决策、阈值默认值或状态机：

1. 将 conversation memory、run recovery context 和真实 compaction summary 拆分为独立分类。
2. 为新快照增加分类版本，旧 `compactedContext` 只作为 v1 兼容投影显示。
3. 前端直接消费后端 `softLimitTokens`，同时显示总窗口占比、有效线占比、距触发距离和本轮 trim state。
4. 模型配置按后端公式预览输入容量、百分比线、安全缓冲线与最终有效线；后端继续拥有配置校验权威。
5. 通过失败回归测试、完整后端/前端门槛和真实浏览器组件渲染检查验收。

本轮停止边界：不调整运行时压缩阈值，不迁移旧事件，不伪造 compaction before/after，不重写 AgentLoopEngine，不顺带处理无关 UI 或兼容 reader 删除。

### 第 78 轮进度：上下文预算来源与有效压缩线可视化已完成

已完成：

- `ContextUsageEstimator` 已拆分 `conversationMemory`、`runRecoveryContext` 和 `compactionSummary`，并识别 durable `<conversation-checkpoint>` 消息；
- 新 `CONTEXT_STATUS` / preview payload 标记 `context-budget-v2`，旧事件恢复为 `context-budget-v1`；
- 前端旧 `compactedContext` 改显示为“旧版恢复上下文”，不再把普通恢复记忆宣称为已压缩摘要；
- 上下文弹窗和环形指示器已展示有效软上限、当前占有效线比例、距触发距离、本轮 trim state 和总窗口比例；
- 模型配置实时预览与后端 `ContextWindowPolicy` 公式一致；204800 / 51200 / 90% / 默认缓冲的有效线显示为 153600，而不是误导性的 184320；
- 后端干净重编译与全量 `1044` 项测试通过，前端当前共享工作区 `223/223`、acceptance 辅助 `16/16` 和生产构建通过；
- 真实浏览器挂载生产 Vue 组件验证 39.2K 快照显示为总窗口 19%、有效线 25.5%、距触发 114.4K、本轮未执行压缩，v1 快照显示“旧版恢复上下文”。

证据文档：`docs/iterations/2026-08-05-iteration-78-context-budget-visualization.md`。

本轮停止边界已经到达：

- 不继续修改 compaction engine、Provider transcript、状态机或审批恢复；
- 旧事件保持只读兼容，不批量重写数据库；
- 实际 8080 JVM 需要重启并产生一次新请求后才会出现 v2 分类；
- 最终验收由用户使用自己的账号、项目和常用浏览器执行。
