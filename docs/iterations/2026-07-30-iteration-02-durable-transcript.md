# Iteration 02 — Durable Provider Transcript

- 日期：2026-07-30
- 分支：`codex/agent-tool-reliability`
- 基线提交：`49fea77 feat: enforce provider transcript protocol boundary`
- 范围：`AgentRunMessage` / `AgentRunPart` 可重建 transcript、AgentLoop 双写与恢复投影、真实审批恢复验收

## 1. 本轮目标

1. 不再只把 `AgentLoopEngine.msgs` 当作 Provider transcript 的唯一来源。
2. 将每条 Provider message 以稳定 `taskId + executionEpoch + sequence` 写入 `t_agent_run_message`。
3. 将 assistant 原生 `tool_calls` 和 tool result 写入 `t_agent_run_part`，保留 `toolCallId`、工具名、arguments 和结果归属。
4. 恢复时从数据库重建协议安全的完整 turn；未完成的 tool batch 不能产生孤立 tool result 或缺失 result。
5. 内存消息与 durable transcript 做 shadow compare；一致时由 durable 投影驱动 Provider，请求不一致时记录诊断并暂时回退。
6. 使用真实 Spring Boot、MySQL、浏览器、审批恢复流程验证，而不是只验证编译和 mock。

## 2. 实现内容

### 2.1 `AgentRunTranscriptService`

新增运行 transcript 持久化服务：

- `appendMessage(taskId, executionEpoch, sequence, providerMessage)`：按稳定 key 幂等写入；
- user / assistant 文本写入 Run Message；
- assistant `tool_calls` 写入稳定 `tool_call` Part；
- `role=tool` 同时写入 Tool Message 和 `tool_result` Part；
- tool result 到达后，匹配的 tool-call Part 转为 completed；
- `loadProjectableTranscript(taskId)` 从 Message/Part 重建 OpenAI-compatible 消息；
- `nextSequence(taskId)` 允许 JVM 重启后继续追加而不覆盖旧 turn；
- durable part key 对外部 tool call id 做字符规整和长度限制。

### 2.2 协议安全恢复

审批等待或进程中断可能留下：

```text
assistant(tool_calls=[A, B])
tool(A result)
<等待审批或进程中断，B 没有 result>
user(批准后继续)
```

恢复投影不能把这个半批次发送给 Provider。本轮实现的规则是：

- 完整的 assistant tool batch + 全部 tool result 原样保留；
- 未完成批次整体从 Provider 投影中省略；
- 批次之后的新 user/assistant turn 仍保留；
- 原始未完成 Part 仍留在数据库，后续生命周期收敛负责将其标记为 interrupted/skipped，而不是读路径篡改事实。

### 2.3 `AgentTranscriptProjectionService`

新增独立、可测试的 shadow compare 服务：

- 先校验内存投影；
- 加载并校验 durable 投影；
- 两者完全相等时返回 `Source.DURABLE`；
- 不相等时返回内存投影并标记 `shadowMismatch=true`；
- 不允许静默改变 Provider prompt。

### 2.4 `AgentLoopEngine` 接入

- 将原 `ArrayList<Map<String,Object>> msgs` 替换为兼容现有调用点的 `TranscriptMessageList`；
- 每次 `add` 先深拷贝，然后追加到内存并写入 durable transcript；
- 新任务写入初始上下文和用户请求；
- 恢复任务优先从 durable transcript 恢复完整 turn，再追加新的用户回复；
- Provider 调用改为经过 `AgentTranscriptProjectionService`；
- shadow mismatch 和投影失败均有结构化日志。

### 2.5 浏览器验收增强

`frontend/scripts/acceptance/agent-browser.mjs` 新增真实 API 断言：

- 在 `waiting_user` 状态读取 active task；
- `runMessages` 中至少存在 3 条 `provider:*` 消息；
- `parts` 中至少存在一个有 `toolCallId` 的 `provider:*` tool-call Part；
- 审批回复后继续订阅同一个 durable task。

## 3. 迭代中发现并修复的现场问题

### 3.1 UTF-8 注释被写成 `????`

第一次前端全量测试结果：147 passed / 1 failed。

`sourceEncoding.test.mjs` 发现自动插入的中文注释被终端编码链写成字面量问号。已使用 BOM-free UTF-8 和 Unicode-safe 替换修复；随后源编码测试和前端全量测试通过。

### 3.2 Spring 真实启动失败

第一次真实启动失败：

```text
AgentTranscriptProjectionService: No default constructor found
```

原因：类存在两个构造器但生产构造器未显式标记。修复方式：为依赖 `AgentRunTranscriptService` 的构造器增加 `@Autowired`，并新增 `ApplicationContextRunner` wiring regression test。

### 3.3 审批恢复出现 shadow mismatch

第一次真实审批恢复日志：

```text
AGENT_TRANSCRIPT_SHADOW_MISMATCH taskId=577 detail=durable=2,memory=3
```

原因：旧实现遇到未完成 tool batch 时直接截断整个后续 transcript，导致批准后的 durable user reply 也被丢弃。修复后改为“省略未完成批次，但继续投影后续 turn”。最终真实验收日志中没有 `AGENT_TRANSCRIPT_*` 错误或 mismatch。

### 3.4 暂存区快照暴露基线中的旧编码损坏

从 Git 暂存区导出的独立快照执行前端全量测试时，发现基线 `AgentLoopEngine` 的循环保护中文提示仍是字面量 `????`。当前工作区此前已经修复该行，但修复属于原有未提交改动。

为了保证本轮提交自身通过源编码门槛，本轮只吸收这一条用户可见字符串修复：

```text
连续 N 次没有取得进展，已触发循环保护。
```

除该单行必要修复外，其他原有 `AgentLoopEngine`、审批、网络和前端改动仍保持未提交。
## 4. 验证记录

### 4.1 Focused backend tests

```powershell
cd D:\LabexAgent\backend
mvn '-Dtest=AgentRunTranscriptServiceTest,AgentTranscriptProjectionServiceTest,AgentTranscriptProjectionServiceWiringTest,AgentProviderMessageProjectorTest' test
```

结果：10 tests，0 failures，0 errors。

覆盖：

- assistant tool batch 持久化；
- toolCallId/name/arguments 重建；
- malformed tool message 拒绝；
- 未完成批次省略且保留后续 user turn；
- durable/memory shadow match 与 mismatch；
- Spring 构造器真实 wiring。

### 4.2 Backend full suite

```powershell
cd D:\LabexAgent\backend
mvn test
```

结果：756 tests，0 failures，0 errors，8 skipped，BUILD SUCCESS。

日志：`backend/target/iteration02-full-backend-final-20260730.log`

### 4.3 Frontend full suite

```powershell
cd D:\LabexAgent\frontend
npm test
```

结果：148 passed，0 failed。

日志：`frontend/iteration02-full-frontend-final-20260730.log`

### 4.4 真实后端与数据库

启动命令：

```powershell
cd D:\LabexAgent\backend
mvn -Dspring-boot.run.profiles=acceptance,local -Dspring-boot.run.arguments=--server.port=8080 spring-boot:run
```

验收事实：

- active profiles：`acceptance`, `local`；
- MySQL Hikari connection：成功；
- additive schema migration：成功；
- Spring Boot：成功启动；
- AgentRunState startup verifier：13 states；
- 最终 JVM PID：`62896`，启动时间 `2026-07-30 20:36:54 +08:00`；
- `AgentRunTranscriptService`、`AgentTranscriptProjectionService`、`AgentLoopEngine` 均由实际 JVM 加载。

### 4.5 真实浏览器与审批恢复

```powershell
cd D:\LabexAgent\frontend
$env:ACCEPTANCE_API_BASE='http://127.0.0.1:8080/api'
$env:ACCEPTANCE_UI_BASE='http://127.0.0.1:13000'
node scripts/acceptance/agent-browser.mjs
```

最终结果（runId `38acba3d03fc48a7aa1604cd072a7f10`）：

```text
desktopLayout=true
conversationIsolation=true
refreshReplayDeduplicated=true
questionReplyComponent=true
durableProviderMessages=3
durableProviderParts=1
staticContextBlockerCard=true
completionEvidenceCard=true
unverifiedCompletionBlocked=true
consoleErrors=0
networkErrors=0
```

并检查最终后端日志：没有 `AGENT_TRANSCRIPT_SHADOW_MISMATCH` 或 `AGENT_TRANSCRIPT_PROJECTION_FAILED`。

### 4.6 Git 暂存区独立快照验证

为了确认提交不依赖其他未提交工作区改动，将 Git index 导出到 `%TEMP%` 独立目录，并复用只读 `node_modules` junction 执行验证。

结果：

- staged backend full suite：744 tests，0 failures，0 errors，8 skipped；
- staged frontend full suite：144 passed，0 failed；
- 修复基线单行编码后 staged backend compile：BUILD SUCCESS。

暂存快照测试数量低于工作区测试数量，是因为原有未提交测试文件没有纳入本轮 index；这正是该验证需要区分的边界。
## 5. 本轮边界与后续工作

本轮证明了 durable transcript 已进入真实写入、恢复和 Provider 请求选择路径，但不能宣称整个架构收敛完成：

1. `set/remove` 型上下文裁剪仍只修改内存列表；需要 compaction epoch 和持久化 head/tail 后才能完全移除 fallback。
2. execution epoch 当前编码在稳定 key 和 metadata 中，尚未变成 Message/Part 的独立数据库列。
3. 未完成 Tool Part 由投影安全省略，但生命周期服务仍需负责持久化 `interrupted/skipped` 终态。
4. 旧 `AgentConversation.summary`、checkpoint 和 workspace memory 仍是并行派生数据，需要在后续 compaction 迭代明确作用域。
5. Provider 读取只有在 shadow match 时切到 durable；完成 compaction/state migration 后才能删除内存 `msgs` 事实路径。

因此本轮完成定义是：**durable Provider transcript 双写与审批恢复投影完成**，整体 Agent Runtime 收敛目标仍保持进行中。