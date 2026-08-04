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

## 13. 执行进度（截至 2026-08-04）

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
