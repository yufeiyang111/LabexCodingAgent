# LabexAgent 会话消息图实施计划

**前置规格：** `D:\LabexAgent\docs\superpowers\specs\2026-08-17-labex-conversation-message-graph-design.md`  
**状态：** 已获规格确认；本计划用于后续逐切片 TDD 实现。  
**日期：** 2026-08-17  
**实施策略：** 小步加法迁移、每切片先红测、可 shadow compare、按新 Conversation 切换；禁止全量重写 `AgentLoopEngine`。  
**参考实现：** 本地 `D:\opencode\opencode-dev`，版本 `1.17.4`。仅复刻语义，不复制实质代码。

---

## 0. 总体执行纪律

1. 本计划只切换 `labex-native + history_projection_version=conversation_graph_v1` 的新会话；其他会话保持现状，严禁自动折叠历史。
2. 每个切片先提交行为级红测，再写最小生产实现，再跑该切片 focused tests；不得一次堆出所有测试或一次性重写 Loop。
3. `AgentRunLifecycleService` 仍是 Task 状态唯一写入者；Conversation graph 不直接改 Task 终态。
4. `AgentRunMessage`、`AgentRunPart` 是 native transcript 唯一事实；数据库 event、SSE、UI、Provider wire message 都是投影。
5. 当前工作区存在其他未提交改动；本计划不得 `git reset`、`git checkout`、覆盖或顺手清理无关文件。
6. 使用现有 `AdditiveSchemaMigrator` 和 `schema.sql` 做可重入加法迁移；不得执行破坏性迁移、数据库 reset 或历史数据批量改写。

---

## 1. 切片顺序总览

| 切片 | 目标 | 读取路径变化 | 删除边界 | 主要验收 |
|---|---|---|---|---|
| G1 | 补齐 Conversation 图的物理字段与索引 | 不切换 | 无 | schema/实体/mapper 可用且旧读写不变 |
| G2 | 建立图 writer 与 Conversation lease | 仅新图写入 | 无 | 顺序、parent、幂等、单 runner 都可重放 |
| G3 | 建立 Message/Part -> Provider graph projector | shadow compare，不切换真实 native 请求 | 无 | Tool Part 协议、跨 Task 连续性、隔离 |
| G4 | 原生单回合接入图与 system assembler | `conversation_graph_v1` 切换 | native 停止读历史前缀 | 请求无伪 user injection，Task 只作执行边界 |
| G5 | Tool Part 生命周期收敛 | 新 native 实际工具回合 | native 停止 canonical `role=tool` Message 写入 | 同 Part 更新、approval/cancel/recovery 安全 |
| G6 | 图内 compaction 与恢复 | 新 native 读取图内 compaction | native 停止从 task compaction record 读 Provider history | tail anchor、重启、context overflow 有限终态 |
| G7 | Event/SSE/UI 回放与 legacy 删除门 | 前端 native 回放 | 删除 N4 native 分支 | 刷新/重连/跨 Task UI 一致、live smoke |

每个切片完成后都要记录：参考的本地 OpenCode 文件和行区间、Labex 必要适配、是否有实质代码复制（预期全部为否）、focused 命令、全量回归结果和尚未验证的风险。

---

## 2. G1：加法持久化模型与迁移护栏

### 2.1 修改文件

| 文件 | 修改 |
|---|---|
| `backend/src/main/resources/sql/schema.sql` | 为 conversation/message/part/task 增加图字段和索引定义，不修改既有键或删除字段。 |
| `backend/src/main/java/com/labex/config/AdditiveSchemaMigrator.java` | 将相同字段/索引加入可重入启动迁移，处理已有部署数据库。 |
| `backend/src/main/java/com/labex/entity/AgentConversation.java` | 映射 message sequence 和 conversation lease 字段。 |
| `backend/src/main/java/com/labex/entity/AgentTask.java` | 映射 `origin_message_id`。 |
| `backend/src/main/java/com/labex/entity/AgentRunMessage.java` | 映射 `parent_message_id`、`conversation_sequence`。 |
| `backend/src/main/java/com/labex/entity/AgentRunPart.java` | 映射 `tail_start_message_id`。 |
| `backend/src/main/java/com/labex/mapper/AgentConversationMapper.java` | 增加受 ownership 限制的 `FOR UPDATE`、sequence/lease 原子更新方法。 |
| `backend/src/main/java/com/labex/mapper/AgentRunMessageMapper.java` | 增加按 Conversation sequence、parent 批量读取的显式查询。 |
| `backend/src/main/java/com/labex/mapper/AgentRunPartMapper.java` | 增加按 Message id、Part sequence 读取的显式查询。 |

### 2.2 字段目标

```text
t_agent_conversation
  next_message_sequence
  execution_owner
  execution_epoch
  execution_lease_expires_at
  execution_heartbeat_at

t_agent_task
  origin_message_id

t_agent_run_message
  parent_message_id
  conversation_sequence

t_agent_run_part
  tail_start_message_id
```

新增索引的最终名称、数据库方言细节和唯一性约束先在红测中固定，再根据现有 `AdditiveSchemaMigrator` 的幂等模式实现。至少需要保证：

- `(conversation_id, conversation_sequence)` 在 native 图中唯一；
- assistant/summary 的 parent 可按 `conversation_id + parent_message_id` 查询；
- 同一 Message 内 Part 顺序稳定；
- 所有 sequence 和 lease 更新都受 student/project/conversation ownership 限制。

### 2.3 完成条件

- 新字段能在空库 schema 和已有库 additive migrator 两种路径中出现；
- 旧 Task-only transcript 查询不改变结果；
- 不回填旧数据，不因 null 新字段影响 legacy。

---

## 3. G2：Conversation graph writer 与持久化单 runner

### 3.1 新增深模块

| 模块 | 位置 | 职责 |
|---|---|---|
| `AgentConversationMessageGraphService` | `backend/src/main/java/com/labex/labexagent/run/` | 统一追加真实 user/assistant Message、追加/更新 Part、分配 conversation sequence、校验 parent/owner/task anchor。 |
| `AgentConversationExecutionLeaseService` | `backend/src/main/java/com/labex/labexagent/run/` | Conversation 范围 claim、heartbeat、release、expired takeover；语义对齐本地 OpenCode `SessionRunState.ensureRunning`，实现形式适配多 JVM Web。 |
| `AgentConversationMessageGraphSnapshot`（record 或等价 DTO） | 同域 | 只读图快照：消息、Part、tail/compaction 信息；不保存第二份可变历史。 |

新模块必须复用现有 `AgentRunMessageMapper`、`AgentRunPartMapper`、`AgentConversationMapper`、`AgentTaskMapper` 和既有 task execution fence；不得新建平行 transcript 表或全局 Map。

### 3.2 写入时序

1. 控制器/任务服务接收到 native 用户请求；
2. 在事务中校验 Conversation owner，创建真实 user Message，分配 `conversation_sequence`；
3. 创建 `AgentTask` 并写入 `origin_message_id`；
4. native loop 开始前 claim Conversation lease，Task 自身 epoch/lease 仍由现有 lifecycle 管理；
5. 每次模型步骤先创建 assistant Message，`parent_message_id=latestUserMessageId`；
6. 流式文本、tool call 和 tool result 只更新本轮 assistant Message 或其 Part；
7. 终态/等待态结束时 release 或延续 lease，事件经现有 outbox 发送。

### 3.3 并发规则

- 同一 Conversation 不能有两个 native loop writer；
- 新 user request 在 active lease 期间可以安全落库，但对应 Task 只能由协调器在前一 runner 离开后启动；
- 过期接管必须比较 owner、epoch 和过期时间，且记录明确的 actor/reason；
- 浏览器断开不释放 lease，只有 lifecycle 终态、明确取消或有效过期接管才会改变它。

### 3.4 完成条件

- 通过公共 writer 写入的图可按 sequence 和 parent 重读；
- 幂等重试不复制 user Message、assistant Message 或 Tool Part；
- 同 Conversation 并发 claim 只有一个成功；不同 Conversation 可并行；
- 旧 Task execution lease 语义和状态机回归不受影响。

---

## 4. G3：Message/Part graph Provider projector

### 4.1 新增与调整

| 文件 / 模块 | 修改 |
|---|---|
| `AgentConversationMessageGraphProjector`（新增，`runtime/`） | 从 Conversation graph 读取、执行 compaction filter、生成 Provider messages。 |
| `AgentProviderMessageProjector.java` | 保持协议校验职责，接收 graph projector 的纯传输输出；不再承担历史拼接。 |
| `AgentTranscriptProjectionService.java` | 增加 profile-aware 路由：图版本 native 委托 graph projector；legacy 保留 Task projection；暂不删除 N4。 |
| `AgentRunTranscriptService.java` | 仅保留 legacy/过渡 Task transcript 行为；抽取可复用 Message/Part 编解码而非再次维护第二个模型历史。 |
| `AgentConversationMemoryProjectionService.java` | native Provider 路径不再引用；暂不删除其 legacy/界面用途。 |
| `AgentConversationTranscriptProjectionService.java` | native Provider 路径不再引用；暂不删除其 legacy/fork 兼容用途。 |

### 4.2 投影算法

1. 精确按 student、project、conversation id 读取 graph rows；
2. 按 `conversation_sequence` 取 Message，按 Message 内 Part sequence 取 Part；
3. 找到最近一条完整、合法的 Compaction Part，采用 summary + tail anchor 构造 visible graph；
4. 对每条 assistant Message：文本和 Tool Part 共同重建 assistant provider message；
5. 对每条完成/失败/已解析的 Tool Part：按原 `tool_call_id` 生成 Provider `role=tool` 输出；
6. 对 pending/running/waiting Tool Part：进入 interaction/recovery 专用 mode，保持未完成状态并禁止伪造成功；
7. 交给现有 `AgentProviderProtocolValidator` 做最终协议验证。

### 4.3 Shadow compare 边界

只对“单 Task、无历史前缀、无旧 compaction”的同语义数据执行 shadow compare：旧 durable transcript projector 和新 graph projector 的 Provider wire output 必须一致。跨 Task 历史与 compaction 属于旧路径不具备的能力，不得强行以字符串 equality 作为等价标准。

### 4.4 完成条件

- 不同 Conversation/用户/项目严格隔离；
- 两个 Task 的真实 user/assistant/Tool Part 顺序连续；
- Tool result 由同一 Part 生成 Provider role=tool，call id 一一对应；
- provider graph output 不含 `AgentConversationMemoryProjectionService` 的扁平 final prefix。

---

## 5. G4：在现有 AgentLoopEngine 内切换 native 单回合

### 5.1 修改文件

| 文件 | 修改 |
|---|---|
| `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java` | 保留入口和 lifecycle 分派；对 `conversation_graph_v1` 只调用 graph projector/turn coordinator，删除 native 的 `initialContextMessage` 和 `agent_runtime_projection` user 注入。 |
| `backend/src/main/java/com/labex/labexagent/runtime/profile/AgentRuntimeProfileResolver.java` | 增加 history projection version 的安全路由，不允许单个 Task 临时越过 Conversation 固定版本。 |
| `backend/src/main/java/com/labex/labexagent/service/AgentTaskService.java` | 创建 native Task 时写入 user origin anchor；恢复时从 durable anchor 重建。 |
| 相关 Controller/DTO | 只在现有 request 中传递 profile；不暴露外部参考项目名称，不让前端临时决定内部读取路径。 |
| `AgentProviderRequestAssembler`（新增或由现有 prompt/provider 组件扩展） | 明确分出 system layers、graph messages 和动态 tools。 |

### 5.2 请求装配不变量

```text
systemLayers = provider/agent instruction + workspace environment + 按需 Skill/extension instruction
conversationMessages = AgentConversationMessageGraphProjector 的输出
tools = 当前 model + Conversation + assistant Message + capability 条件下解析的集合
```

禁止：

- native 把 system layers、lean initial context、plan/progress、memory prefix 追加成 `role=user`；
- native 在 `AgentLoopEngine` 持有跨模型步骤可变 `msgs` 历史；
- native 以 `Task.summary` 或最终文本代替 Message/Part 图。

### 5.3 完成条件

- 新 graph 会话跨 Task 的第二轮第一次 LLM 请求即可看到第一轮真实图；
- legacy 会话保持既有调用路径；
- Provider request 的 durable messages 和预算读取同一 projection；
- 应用重启后 native 由 Conversation 图恢复，不依赖 JVM 内存。

---

## 6. G5：native Tool Part 生命周期收敛

### 6.1 修改文件

| 文件 | 修改 |
|---|---|
| `AgentRunTranscriptService.java` | native 不再把 tool output 持久化为独立 canonical tool Message；保留旧逻辑供 legacy 读取。 |
| `AgentRunPartService.java` | 为 Tool Part 提供受 fence 保护的 pending/running/waiting/completed/failed/skipped/interrupted 更新。 |
| `LabexNativeToolBatchExecutor.java` | 一轮全部 tool call 先持久化为 assistant Message 的 Part，再按确定性顺序执行和更新。 |
| native turn coordinator / interaction services | approval、question、取消和异常时将未开始 Part 标记为 skipped/interrupted，恢复时使用同一 Part。 |
| `AgentProviderProtocolValidator.java` | 继续严查 provider wire projection 的 call/result 对。 |

### 6.2 完成条件

- 一个 `tool_call_id` 在同一 Conversation/assistant message/epoch 范围内只对应一个 Tool Part；
- 非零退出、超时、权限拒绝和工具异常写入 failed 证据，不能在投影中变成 completed；
- approval 之后、刷新之后、恢复之后均不会重复副作用；
- 该切片只建设真实状态链，不提前改“最终回答真实性”政策。

---

## 7. G6：graph 内 compaction、恢复与 token 预算

### 7.1 修改文件

| 文件 | 修改 |
|---|---|
| `AgentCompactionService.java` | native 改为接受 graph snapshot 和 Message id tail anchor；legacy task compaction 留兼容。 |
| `AgentConversationCompactionService.java` | 不再为 native Provider 拼接摘要文本；若仍保留，限为历史兼容/审计投影。 |
| `AgentConversationMessageGraphService.java` | 持久化 Compaction User Message、Compaction Part、summary Assistant Message。 |
| `AgentConversationMessageGraphProjector.java` | 实现与 OpenCode `filterCompacted` 等价的合法图筛选。 |
| `AgentRunRecoveryService.java`、interaction/replay 服务 | 从 graph 的 latest/compaction/Part 状态恢复。 |
| 预算/usage 服务 | graph 和 request system/tool schema 使用同一 token 输入，context overflow 进入有限终态。 |

### 7.2 完成条件

- compaction 后 assistant tool call 与 result 不被分裂；
- summary、tail anchor、token 参数、epoch/status 都可解释；
- context overflow 不会无限以相同输入重试；
- 恢复时未完成 Tool Part 不会被 summary 遮蔽或变成功。

---

## 8. G7：事件回放、前端消费和旁路删除

### 8.1 修改文件

| 文件 | 修改 |
|---|---|
| `AgentRunEventReplayService.java`、相关 DTO/API | 确保图 Message/Part 更新带 conversation/message/part identity，Task event 继续由 lifecycle 解释。 |
| `frontend/src/composables/agentHistoryReducer.js` | 从 durable Message/Part event 归并 native transcript，不通过 EventSource 断开猜终态。 |
| `frontend/src/composables/useAgentEventTimeline.js`、`agentRunState.js` | 以 event cursor + graph identity 处理刷新、重复事件和恢复。 |
| `frontend/src/components/cloud/chat/CenterAiWorkspace.vue` | 按现有 Labex UI 显示连续会话，不展示内部 system、lease 或原始实现信息。 |
| `AgentTranscriptProjectionService.java` 等 | 达到删除门槛后移除 native N4 memory prefix、native task-local compaction provider read 和伪 user injection。 |

### 8.2 完成条件

- 刷新、SSE 断线、重复 cursor、恢复之后前端 Message/Part/Task 显示一致；
- 不会把模型最终答复已写入但 SSE 未显示误判为“用户取消”；
- UI 保留用户需要的计划、工具、用量、审查信息，但不直接泄漏 raw system/内部执行指令；
- legacy 观察窗口达标后才删除 native 旁路。

---

## 9. 参考记录与验证命令

### 9.1 本地源码参考记录

- `D:\opencode\opencode-dev\packages\opencode\src\session\prompt.ts:1105-1124, 1141-1149, 1239-1293, 1404-1408`
- `D:\opencode\opencode-dev\packages\opencode\src\session\message-v2.ts:142-425, 480-500, 533-587`
- `D:\opencode\opencode-dev\packages\opencode\src\session\processor.ts:187-226`
- `D:\opencode\opencode-dev\packages\opencode\src\session\compaction.ts:97-112, 299-442`
- `D:\opencode\opencode-dev\packages\opencode\src\session\llm\request.ts:56-112`
- `D:\opencode\opencode-dev\packages\opencode\src\session\run-state.ts:35-94`
- `D:\opencode\opencode-dev\packages\opencode\src\effect\runner.ts:115-138`

### 9.2 每切片的最低验证

```powershell
cd D:\LabexAgent\backend
mvn -q -Dtest=<本切片测试类列表> test
mvn -q test

cd D:\LabexAgent\frontend
npm run build

cd D:\LabexAgent
git diff --check
```

完整后端、前端构建通过不等于真实运行已加载修复。最后的 live smoke 必须核对启动 JVM 的 PID、启动时间、classpath，之后在浏览器完成同 Conversation 跨 Task、tool result、刷新/恢复和 compaction 场景验收。
