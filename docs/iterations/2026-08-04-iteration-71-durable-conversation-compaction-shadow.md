# 第 71 轮：会话级持久化压缩双写与 shadow 边界

- 日期：2026-08-04
- 分支：`codex/agent-tool-reliability`
- 基线提交：`b4d23bf feat: fence agent event outbox sequences`
- 状态：已完成
- 上位计划：`docs/superpowers/plans/2026-07-29-agent-runtime-state-convergence.md`

## 1. 本轮目标

将“手动会话压缩”从 `t_agent_message` 的历史事件拼接迁移到可审计的持久化事实链：

```text
AgentTask.request_payload + AgentRunMessage(assistant:final)
  -> AgentConversationTranscriptProjectionService
  -> AgentCompactionRecord(scope=conversation)
  -> 完成后的 authority record
  -> t_agent_message.COMPACTION_SUMMARY 只读兼容投影
```

本轮只建立新权威事实、稳定边界、双写和 shadow 验证，**不直接切换** `AgentLoopEngine.buildMemoryContext(...)` 的读取路径。原因是当前 fork 仍复制旧 `AgentMessage` 历史；此时强切读取会让旧 fork 会话丢失上下文。

## 2. 修改前确认的问题

1. 当前 task 内 Provider transcript 已由 `AgentRunMessage` / `AgentRunPart` 持久化并投影。
2. 跨 task 的 conversation memory 仍由 `AgentConversationService.buildMemoryContext(...)` 读取 `t_agent_message`。
3. 手动压缩仍调用 `AgentConversationService.compactConversation(...)`，其输入、摘要和完成事实都落在旧消息表。
4. `AgentCompactionRecord` 只有 task 作用域，无法表示跨 task 的会话压缩边界。
5. 直接改读新路径会破坏仍依赖 `AgentMessage` 的 fork，因此必须先双写和 shadow compare。

## 3. OpenCode 对齐原则

参考 `D:/opencode/opencode-dev/packages/opencode/src/session/compaction.ts` 与 `message-v2.ts#filterCompacted` 后，本轮采用以下原则：

- compaction 是 durable message graph 的事实，不是 UI 临时摘要；
- summary、retained tail 和稳定边界 ID 必须一起持久化；
- 只有 `completed` 记录可以被后续投影消费；
- UI/旧历史表只能是兼容投影，不能反向覆盖权威记录；
- 并发压缩必须有 epoch、锁和上一版本围栏，不能靠“最后写入者获胜”。

## 4. 实际实现

### 4.1 Durable conversation transcript projector

新增 `AgentConversationTranscriptProjectionService`：

- 从 `AgentTask.request_payload.message` 恢复 user turn；
- 从 `AgentRunMessage(message_key=assistant:final)` 恢复 assistant turn；
- 明确排除 `mode=compact` 的内部任务；
- 只推进到连续终态 task 前缀，遇到 running/waiting task 立即停止；
- 使用 task ID 作为稳定增量边界；
- 对历史 `<think>` 内容和常见 secret 形式做清洗；
- 老 task 缺少 `request_payload` 时只退化读取 durable `AgentTask.title`，不重新依赖 `AgentMessage`；
- 单批最多读取 500 个最早未压缩 task，避免无界查询，并允许后续 epoch 继续推进。

### 4.2 Conversation-scope compaction record

扩展 `AgentCompactionRecord`：

- `scope`：`task` / `conversation`；
- `sourceMaxTaskId`：conversation 压缩覆盖的最后稳定 task ID。

扩展 `AgentCompactionService`：

- task 压缩读取显式限定 `scope=task`；
- 新增 conversation start/latest/project API；
- conversation start 对所属 `AgentConversation` 执行 `SELECT ... FOR UPDATE`；
- 请求携带 `expectedPreviousCompactionId`，在锁内与最新 completed 记录比较；
- 若已有 running conversation compaction 或快照已过期，拒绝并发写入；
- task 详情的安全审计投影保留所有与该 task 关联的 compaction scope，但不暴露 summary/head/tail 原文。

### 4.3 手动压缩改走新权威路径

新增 `AgentConversationCompactionService`，并让 `ManualCompactionTaskRunner` 改为调用它：

1. 校验 conversation 与 compaction task 所有权；
2. 读取上一 completed conversation compaction；
3. 从 durable task/run-message 投影增量消息；
4. 用 `CompactionSelection` 按真实 user turn 选择 head/tail；
5. 在调用模型前持久化 `scope=conversation,status=running` 记录；
6. 模型 checkpoint 必须满足 `estimatedTokensAfter < estimatedTokensBefore`；模型结果不缩小时切换 deterministic fallback，fallback 仍不缩小时将权威记录明确终结为 `failed`；
7. 有效摘要生成后，先将权威记录 CAS 到 `completed`；
8. 再写旧 `COMPACTION_SUMMARY`，并标注：
   - `authority=agent_compaction_record`
   - `projectionOnly=true`
   - `compactionId`
   - `taskId`
   - `sourceMaxTaskId`
9. 兼容投影失败时不回滚已完成的权威记录，但手动 task 会明确失败，不能伪报成功。

### 4.4 执行租约与恢复

手动压缩 task 现在与普通 Agent task 一样：

- 先获取 `AgentRunExecutionLeaseService` 的 execution epoch/owner/expiry，再注册内存 cancellation token，避免 lease 竞争者替换真实 owner；
- 将 lease epoch 写入 conversation compaction record；
- 由 `AgentRunLeaseHeartbeatService` 续租；
- finally 中取消 heartbeat 并释放 lease；
- 获取不到 lease 时视为另一 worker 已拥有该 task，本 worker 直接退出，不把 task 错误改成 failed，也不触碰 owner 的 cancellation token；
- `AgentConversationCompactionService.compact(...)` 成功返回即越过 durable commit point；之后到达的取消不得把已完成 authority/task 反转为 cancelled；
- JVM 重启后，现有 `AgentRunRecoveryService` 可以依据 task epoch + lease 正确关闭失联的 running record。

### 4.5 Schema 与验收脚本

- `schema.sql` 新增 `scope`、`source_max_task_id` 和 conversation lookup index；
- `AdditiveSchemaMigrator` 为已有数据库做 additive migration；
- `scripts/acceptance/agent-runtime.ps1` 显式固定保留最近 2 轮，并以 5 轮真实 conversation 验证压缩确实缩小、数据库 authority、task execution epoch、legacy mirror 和 task audit 投影；失败时输出完整 durable task/part/compaction 审计信息。

## 5. TDD 过程

### RED

首次运行：

```powershell
cd D:\LabexAgent\backend
mvn '-Dtest=AgentConversationTranscriptProjectionServiceTest,AgentCompactionConversationScopeTest,AgentConversationCompactionServiceTest,ManualCompactionTaskRunnerTest' test
```

结果：`testCompile` 失败，明确缺少 conversation projector、conversation-scope API 与新 runner 依赖。

后续审查中追加并先观察失败的回归：

- stale previous compaction ID 必须拒绝；
- legacy projection 失败不能静默返回成功；
- execution lease 被其他 worker 持有时不能把 task 改成 failed；
- 模型 checkpoint 不缩小时必须切换 deterministic fallback，fallback 仍不缩小时必须失败，不能持久化伪压缩成功；
- lease 竞争者不得替换并取消真实 worker 已注册的 cancellation token；
- durable compaction 已提交后，迟到的取消不得反转 task 终态。

### GREEN

定向回归最终结果：

```text
42 tests, 0 failures, 0 errors
```

覆盖 projector、scope/epoch、恢复、迁移、双写顺序、fallback、租约和 runner 生命周期。

## 6. 广域验证

### 6.1 后端全量

```powershell
cd D:\LabexAgent\backend
mvn test
```

结果：

```text
Tests run: 993, Failures: 0, Errors: 0, Skipped: 8
BUILD SUCCESS
```

### 6.2 前端生产构建

```powershell
cd D:\LabexAgent\frontend
npm run build
```

结果：成功；chunk budget 检查通过：

- `CloudWorkspace`: 1,462,842 / 1,500,000 bytes
- `index`: 1,259,608 / 1,300,000 bytes
- `TerminalPanel`: 380,367 / 400,000 bytes

### 6.3 真实系统验收

构建真实 Spring Boot JAR 后执行：

```powershell
cd D:\LabexAgent
.\scripts\acceptance\agent-runtime.ps1 `
  -JarPath .\backend\target\labex-agent-backend-1.0.0.jar `
  -BackendPort 18080 `
  -TimeoutSeconds 120
```

验收过程真实发现并修正了三类问题，而不是只追求脚本绿灯：

1. 首轮新增 SQL 错把旧消息表字段写成 `metadata`，实际列名是 `event_data`；脚本正确失败。排查时还通过 `git diff` 发现一次宽泛文本替换把 `scope` 意外加入其他建表语句，提交前已删除越界 schema 改动。
2. 加入“压缩必须实际缩小”保护后，原三轮夹具再次失败。durable 审计显示 `estimatedTokensBefore=459`、`retainedTurns=2`：只剩一轮 head，摘要加两轮 tail 后无法缩小。这证明保护逻辑生效，而不是租约、数据库或 Provider 故障。
3. 验收夹具随后显式配置 `compactionTailTurns=2`，改为五轮 durable transcript，并保留失败时的完整 task/part/compaction JSON 诊断。最终验收通过。

最终关键证据：

```json
{
  "manualCompaction": true,
  "manualCompactionAuthority": true,
  "manualCompactionSourceMaxTaskId": 23,
  "manualCompactionExecutionEpoch": 1,
  "compactionEpochRecovery": true,
  "compactionCancellationTerminal": true,
  "questionRestart": true,
  "permissionRestart": true,
  "commandApproveRestart": true,
  "commandRejectRestart": true,
  "outboxSequenceFence": true,
  "normalProfileProviderIsolation": true,
  "isolatedDatabase": true,
  "cleanup": true
}
```

最终验收使用隔离 H2 TCP 数据库、当前源码重新打包得到的真实 Spring JVM、真实 HTTP/SSE、真实 task lease/heartbeat、JVM restart 和数据库查询，不是 mock 或仅编译检查。

## 7. 修改文件

### 生产代码

- `backend/src/main/java/com/labex/mapper/AgentConversationMapper.java`
- `backend/src/main/java/com/labex/labexagent/context/AgentCompactionRecord.java`
- `backend/src/main/java/com/labex/labexagent/context/AgentCompactionService.java`
- `backend/src/main/java/com/labex/labexagent/service/AgentConversationTranscriptProjectionService.java`
- `backend/src/main/java/com/labex/labexagent/service/AgentConversationCompactionService.java`
- `backend/src/main/java/com/labex/labexagent/service/ManualCompactionTaskRunner.java`
- `backend/src/main/java/com/labex/config/AdditiveSchemaMigrator.java`
- `backend/src/main/resources/sql/schema.sql`
- `scripts/acceptance/agent-runtime.ps1`

### 测试

- `backend/src/test/java/com/labex/labexagent/context/AgentCompactionConversationScopeTest.java`
- `backend/src/test/java/com/labex/labexagent/service/AgentConversationTranscriptProjectionServiceTest.java`
- `backend/src/test/java/com/labex/labexagent/service/AgentConversationCompactionServiceTest.java`
- `backend/src/test/java/com/labex/labexagent/service/ManualCompactionTaskRunnerTest.java`
- `backend/src/test/java/com/labex/config/AdditiveSchemaMigratorTimingTest.java`

## 8. 停止边界与剩余工作

本轮停止在“新权威事实已建立、旧读取仍是兼容路径”的明确边界。以下内容**未在第 71 轮执行**，留给第 72 轮：

1. 让 fork 同时复制/引用 durable conversation checkpoint；
2. 将 `buildMemoryContext` 的 Provider 读取切换到 conversation compaction projector；
3. 对新旧 memory projection 做 shadow compare 并记录差异；
4. 证明 fork、旧会话、连续多次手动压缩和 500-task 分批推进无差异；
5. 达到退出条件后，停止把 `COMPACTION_SUMMARY` 作为 Provider 事实读取；
6. 最终删除 `AgentConversationService.compactConversation(...)` 的旧权威实现。

因此，本轮可以声明“会话压缩权威记录、并发围栏、执行租约和兼容双写已真实验证”，但**不能**声明“整个 conversation memory 已完全切到新路径”或“架构收敛全部完成”。
