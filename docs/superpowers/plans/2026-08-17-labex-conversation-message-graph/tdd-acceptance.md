# LabexAgent 会话消息图 TDD 验收清单

**对应规格：** `D:\LabexAgent\docs\superpowers\specs\2026-08-17-labex-conversation-message-graph-design.md`  
**对应实施计划：** `D:\LabexAgent\docs\superpowers\plans\2026-08-17-labex-conversation-message-graph\implementation-plan.md`  
**日期：** 2026-08-17  
**方法：** 每一个编号严格执行 Red → Green；测试只使用本文确认的公共 seam，不能通过检查私有字段、mock 内部调用次数或抓取日志文本替代行为断言。

---

## 1. 已确认的测试 seam

| Seam | 观察行为 | 不测什么 |
|---|---|---|
| `AgentConversationMessageGraphService` | 写入后 graph 的 sequence、parent、ownership、task anchor、Part 生命周期 | 私有 SQL 拼接、内部 helper 调用顺序 |
| `AgentConversationMessageGraphProjector` | graph -> protocol-safe Provider messages、compaction filter、隔离 | 某个 JSON 库/Map 实现细节 |
| `AgentConversationExecutionLeaseService` | 同会话单 runner、heartbeat、过期接管和跨会话并发 | JVM 线程调度细节 |
| native `AgentLoopEngine` 路由 | graph version 的请求读取、system layer 分离、legacy 不变 | Loop 内部局部变量或 prompt 字符串实现 |
| `LabexNativeToolBatchExecutor` + Part 服务 | 同一 Part 的状态演进、取消/审批/恢复后的副作用幂等 | 工具本身的业务实现细节 |
| event replay / 前端 reducer | Message/Part/Task 的刷新、重连、重复事件回放 | 组件 DOM 的像素级样式 |

所有测试 fixture 必须使用明确的固定 ID、固定 Message/Part 内容和独立期望值。测试替身只允许存在于 `backend/src/test` 或前端测试文件，生产路径禁止 fake provider/history。

---

## 2. Red-Green 切片 G1：加法字段与图读取基础

### CG-DB-01：Conversation 图字段可用且 legacy 空值安全

- **测试文件：** 新增 `backend/src/test/java/com/labex/labexagent/run/AgentConversationMessageGraphSchemaTest.java`
- **Red：** 断言实体可以承载 conversation sequence、parent、tail anchor、task origin 与 lease 字段；现有实体没有字段或 mapper 不能映射时红。
- **Green：** 修改 schema、AdditiveSchemaMigrator、实体和 mapper；legacy records 的新增字段为 null/默认值时，原 Task-only 查询仍工作。
- **断言：**
  - 图版本 Conversation 的 `nextMessageSequence` 初始/递增可表达；
  - `AgentTask.originMessageId` 可为空以兼容旧 Task；
  - `AgentRunMessage.parentMessageId/conversationSequence` 可为空以兼容旧 Message；
  - `AgentRunPart.tailStartMessageId` 可为空以兼容非 compaction Part。

### CG-DB-02：Conversation sequence 唯一，Message 内 Part 顺序稳定

- **测试文件：** 同上或新增 mapper integration test（按项目现有数据库测试模式决定）。
- **Red：** 两个 native Message 不能表达相同 `(conversationId, conversationSequence)`，或 Part 重读顺序不稳定。
- **Green：** 增加约束/索引和按明确字段排序的 mapper 查询。
- **断言：** 读取不依赖 `task_id` 或全局自增 id 的偶然顺序。

**Focused 命令：**

```powershell
cd D:\LabexAgent\backend
mvn -q -Dtest=AgentConversationMessageGraphSchemaTest test
```

---

## 3. Red-Green 切片 G2：graph writer、parent、幂等和 lease

### CG-GRAPH-01：真实 user Message 成为 Task origin anchor

- **测试文件：** 新增 `AgentConversationMessageGraphServiceTest.java`
- **Red：** `appendUserRequest(...)` 后无法返回 durable Message id，或 Task 没有绑定它。
- **Green：** 在一个事务中写 User Message、conversation sequence、Task origin anchor。
- **固定场景：** Conversation `conv-A`、Task `1001`、User 内容 `实现删除 Skill`。
- **断言：**
  - Message role=`user`，sequence=1；
  - task `originMessageId` 等于这个 Message id；
  - 第二次使用同一 idempotency key 重试不产生第二条 User Message；
  - `conv-B` 无法读取 `conv-A` 的 Message。

### CG-GRAPH-02：assistant Message parent 指向 latest real user

- **Red：** 第二个 assistant turn 可能 parent 到前一个 assistant、Task 或伪 system/user injection。
- **Green：** `beginAssistantStep(...)` 从 graph latest 选择真实 User Message，并写 `parent_message_id`。
- **固定场景：** `U1 -> A1(tool part) -> A2`，随后 `U2 -> A3`。
- **断言：** `A1/A2.parent=U1`、`A3.parent=U2`；compaction synthetic user 不计入 latest real user 的业务判断，除 compaction 流程显式要求。

### CG-GRAPH-03：Conversation lease 单 writer、过期接管安全

- **测试文件：** 新增 `AgentConversationExecutionLeaseServiceTest.java`
- **Red：** 两个 owner 可以同时 claim `conv-A`，或旧 owner heartbeat 覆盖新 epoch。
- **Green：** claim/heartbeat/release 使用 owner + epoch + expiry 条件更新。
- **断言：**
  - `runner-a` claim 成功；`runner-b` 未过期时失败/等待；
  - 过期后 `runner-b` 接管 epoch 增加；
  - `runner-a` 的旧 heartbeat/release 不能破坏 `runner-b` lease；
  - `conv-B` 可由另一个 owner 并行 claim。

**Focused 命令：**

```powershell
cd D:\LabexAgent\backend
mvn -q -Dtest=AgentConversationMessageGraphServiceTest,AgentConversationExecutionLeaseServiceTest test
```

---

## 4. Red-Green 切片 G3：图到 Provider 协议投影

### CG-PROJECT-01：跨 Task 连续图，不使用 final 摘要前缀

- **测试文件：** 新增 `AgentConversationMessageGraphProjectorTest.java`
- **Red：** 当前 `AgentTranscriptProjectionService.loadProviderMessages(taskId)` 只能得到 task-local + flattened memory prefix，无法保留历史 Tool Part。
- **Green：** graph projector 读取 `conversation_id + conversation_sequence` 的完整图。
- **固定场景：**

```text
Task 1001 -> U1("先分析并给出方案")
          -> A1("方案一")
          -> ToolPart(read_file, completed, "pom.xml")
          -> A2("结论：采用方案一")
Task 1002 -> U2("按刚刚的方案开始实现")
```

- **断言：** Provider messages 按 `U1, A1+tool_call, tool result, A2, U2` 排列；不出现“历史任务摘要”“稳定历史 Task 的 user/final”等 N4 拼接文本；Tool result 带原始 `tool_call_id`。

### CG-PROJECT-02：同一 Tool Part 生成 call/result 对

- **Red：** native 持久化独立 `role=tool` Message 或 call/result 不能依据同一 Part 恢复。
- **Green：** 一个 assistant Message 的 Tool Part 具备 input、status、output 后，projector 生成完整 assistant tool call + `role=tool` wire message。
- **断言：**
  - 一个 `tool_call_id` 只出现一对 call/result；
  - 输出为 failed 时 tool wire content 包含失败事实、不会被伪装为 completed；
  - pending/running Part 在正常 invocation mode fail closed，不生成假 result；interaction resume mode 可保留可恢复状态。

### CG-PROJECT-03：严格 tenant / Conversation 隔离

- **Red：** 同 project 的另一个 Conversation 或不同 student/project 可能被 sequence 扫描混入。
- **Green：** projector 所有查询都有 student、project、conversation ownership 限制。
- **断言：** `conv-B`、陌生 student 或 project 的 Message/Part 从不进入 `conv-A` 的 Provider messages。

### CG-PROJECT-04：单 Task shadow compare

- **测试文件：** 扩展 `AgentTranscriptProjectionServiceTest.java`
- **Red：** 对一个无历史、无 compaction 的单 Task fixture，旧 durable projection 和新 graph projection 不能稳定比较。
- **Green：** 两者输出 wire messages 完全相等，作为读路径切换前的最小可信对照。
- **禁止断言：** 不把跨 Task memory prefix 或旧 task compaction 的输出与新 graph 进行假等价比较。

**Focused 命令：**

```powershell
cd D:\LabexAgent\backend
mvn -q -Dtest=AgentConversationMessageGraphProjectorTest,AgentTranscriptProjectionServiceTest,AgentTranscriptProjectionServiceWiringTest test
```

---

## 5. Red-Green 切片 G4：native loop 路由与 system 层分离

### CG-LOOP-01：conversation_graph_v1 必走 graph projector

- **测试文件：** 新增 `AgentLoopEngineConversationGraphRoutingTest.java` 或扩展现有 `AgentLoopEngine*Test`。
- **Red：** `labex-native` 的 graph version 请求仍调用 `AgentConversationMemoryProjectionService` 或 task-only `loadDurableProjection`。
- **Green：** 由 runtime profile + history projection version 同时决定路由。
- **断言：** 新 graph 会话的所有 provider/budget/compaction 读取共享 graph projector；legacy profile 或旧 projection version 仍走旧路径。

### CG-LOOP-02：native request 不再注入伪 user message

- **Red：** Provider request 可见 `initialContextMessage`、`<agent_runtime_projection`、历史 memory prefix。
- **Green：** 引入/扩展 request assembler，分别暴露 `systemLayers` 与 `conversationMessages`。
- **断言：**
  - graph messages 只有真实/compaction Message 与 Provider 投影的 tool message；
  - system/environment/按需能力信息仅出现在 system 层；
  - latest user 与 compaction user 计算不受 system progress 污染。

### CG-LOOP-03：跨 Task 首轮即可看到真实上文

- **测试文件：** 结合 scripted test provider 的 acceptance fixture，仅在测试 profile 显式启用。
- **Red：** Task 1002 的首个 request 看不到 Task 1001 的完整图。
- **Green：** Task 1002 在新图中直接加载 Conversation history。
- **断言：** fixture 看到固定的 U1/A1/ToolPart/A2/U2 顺序；不以模型自然语言“我记得”作为断言。

**Focused 命令：**

```powershell
cd D:\LabexAgent\backend
mvn -q -Dtest=AgentLoopEngineConversationGraphRoutingTest,AgentTranscriptProjectionServiceWiringTest,AgentLoopEngineContextBudgetTest test
```

---

## 6. Red-Green 切片 G5：Tool Part、审批、取消与恢复

### CG-TOOL-01：一轮多个工具先落 Part，再执行

- **测试文件：** 扩展 `LabexNativeToolBatchDurableDatabaseTest.java` 与 `LabexNativeToolBatchExecutorTest.java`。
- **Red：** 第一个工具开始后第二个工具才出现，或 interruption 后无明确 Part 终态。
- **Green：** 同一 assistant Message 的全部 tool calls 先写 pending Part，再按确定性顺序执行。
- **断言：** approval 阻塞后的后续未执行 Part=skipped；cancel 后未开始 Part=interrupted；已完成 Part 不重跑。

### CG-TOOL-02：失败结果保持失败事实

- **测试文件：** 扩展 `AgentRunPartServiceTest.java`、`AgentConversationMessageGraphProjectorTest.java`。
- **固定失败：** shell exit=1、超时、权限拒绝、工具抛异常。
- **断言：** Part status/metadata/Provider tool output 均保留真实失败分类；不能被 completion 或 verification 投影成 success。
- **边界：** 最终用户答复的全局真实性 gate 不在本切片改变，但必须能从图中读取失败证据。

### CG-RECOVERY-01：审批与重启从同一 Part 恢复

- **测试文件：** 扩展 `AgentRunRecoveryServiceTest.java`、`AgentTranscriptProjectionServiceWiringTest.java`。
- **断言：** interaction 的原始 tool call id 对应同一 Tool Part；重复审批/恢复不会重复副作用；恢复后 Provider graph 合法。

**Focused 命令：**

```powershell
cd D:\LabexAgent\backend
mvn -q -Dtest=LabexNativeToolBatchDurableDatabaseTest,LabexNativeToolBatchExecutorTest,AgentRunPartServiceTest,AgentRunRecoveryServiceTest test
```

---

## 7. Red-Green 切片 G6：图内 compaction 与 context 恢复

### CG-COMPACT-01：Compaction 是图节点，不是 task summary 前缀

- **测试文件：** 新增 `AgentConversationMessageGraphCompactionTest.java`。
- **Red：** compaction 只能通过 `t_agent_compaction_record` 的 task-local summary 读取，无法表达 tail Message id。
- **Green：** 创建 compaction user Message + Part + summary assistant Message，并写 `tail_start_message_id`。
- **断言：** projector filter 后保留合法 summary/tail；被裁剪的历史 Tool Part 不会与其结果分离。

### CG-COMPACT-02：重启、interaction resume 与 overflow 不会伪完成

- **测试文件：** 扩展 `AgentLoopEngineCompactionTerminalityTest.java`、`AgentLoopEngineContextBudgetTest.java`。
- **断言：**
  - newest valid compaction 正确恢复；
  - 未完成 Part 不被 summary 覆盖；
  - context overflow 有结构化原因和有限终态，不能无限请求相同 graph；
  - task terminal status 仍由 lifecycle service 决定。

**Focused 命令：**

```powershell
cd D:\LabexAgent\backend
mvn -q -Dtest=AgentConversationMessageGraphCompactionTest,AgentLoopEngineCompactionTerminalityTest,AgentLoopEngineContextBudgetTest test
```

---

## 8. Red-Green 切片 G7：事件回放和前端验收

### CG-EVENT-01：native graph event 可重放

- **后端测试：** 扩展 `AgentRunEventReplayService` 对应测试或新增 `AgentConversationGraphEventReplayTest.java`。
- **断言：** Message/Part 更新包含稳定 conversation/message/part identity；同一事件重复投递不会生成重复气泡或改变已完成 Part。

### CG-UI-01：刷新、断线和恢复不割裂 Task 状态

- **前端测试文件：** 扩展 `frontend/src/composables/agentHistoryReducer.test.mjs`。
- **固定事件序列：** user message -> assistant streaming -> Tool Part running -> SSE 断线 -> 重放 running -> completed -> final -> Task terminal。
- **断言：** UI 只根据 durable event/Part reducer 更新；不会因 EventSource 关闭显示“用户主动取消”；最终回答 event 在历史中可见。

### CG-UI-02：不展示内部 system / lease / prompt 实现信息

- **前端测试 / 手动验收：** native history renderer 不把 system layer、runtime projection、lease 字段显示成用户消息。
- **断言：** 用户只看到真实对话、允许展示的思考/工具/计划/验证投影和安全错误。

**Focused 命令：**

```powershell
cd D:\LabexAgent\frontend
node --test src/composables/agentHistoryReducer.test.mjs
npm run build
```

---

## 9. 全量回归与真实验收

每个 Green 切片结束后先跑 focused tests；G4、G6、G7 合并后再跑：

```powershell
cd D:\LabexAgent\backend
mvn -q test

cd D:\LabexAgent\frontend
npm run build

cd D:\LabexAgent
git diff --check
```

真实浏览器/Provider 验收使用一条全新 `conversation_graph_v1` native 会话，并逐项确认：

1. 首次请求：“分析当前代码并给出删除 Skill 的实施方案”；
2. 第二个 Task：“按刚刚确认的方案开始实现”；检查第二次的**首个 Provider request** 与 UI transcript，都具有完整上文而不是只看到一段历史 final；
3. 让模型进行一个 read tool 和一个失败 shell；刷新页面、重连 SSE，检查 Tool Part 与失败事实未变化；
4. 触发 approval，刷新后批准，确认同一 Tool Part 恢复、无重复副作用；
5. 触发可控 context budget / compaction，确认 summary + tail 结构可回放；
6. 新建另一个 Conversation，发送相似指令，确认完全无跨会话串历史；
7. 检查真实启动的 JVM PID、启动时间和 classpath，确认浏览器命中的是包含修复的实例；
8. 将日志、Provider request 审计和截图做脱敏后保存为验收证据。

---

## 10. 旁路删除的最终验收门

只有同时满足以下条件，才可在 native 路径删除 N4 和其它过渡读取：

- `AgentConversationMemoryProjectionService` 不再被 `conversation_graph_v1` 的 Provider projector 引用；
- `AgentTranscriptProjectionService.loadProviderMessages(taskId)` 不再在 native 拼接 Task history prefix；
- native `AgentLoopEngine` 不再追加 `initialContextMessage` 或 `agent_runtime_projection` 的 `role=user`；
- native compaction 不再读取 task-local `t_agent_compaction_record` 作为 Provider history；
- CG-01 至 CG-UI-02、全量后端测试、前端 build、真实 smoke 均通过；
- legacy reader 的观察窗口满足现有 `AgentLegacyMigrationGate` 删除条件。

达到门槛前，所有兼容代码必须明确标注仅 legacy 路径可达；不得因“测试暂时通过”提前删除恢复所需的旧路径。
