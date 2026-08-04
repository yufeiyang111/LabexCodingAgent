# 第 72 轮：持久化分叉边界与会话 Memory 读路径切换

- 日期：2026-08-04
- 分支：`codex/agent-tool-reliability`
- 基线提交：`13bba1f feat: persist conversation compaction authority`
- 状态：已完成
- 上位计划：`docs/superpowers/plans/2026-07-29-agent-runtime-state-convergence.md`

## 1. 问题陈述

第 71 轮已经把会话压缩的权威事实迁移到：

```text
AgentTask.request_payload + AgentRunMessage(assistant:final)
  -> AgentConversationTranscriptProjectionService
  -> AgentCompactionRecord(scope=conversation)
```

但当前分叉仍只复制 `t_agent_message`。这会产生一个不可接受的双重事实：

- 前端打开子会话时能看到复制后的旧事件；
- Provider memory 读取新的 durable transcript 时，子会话没有任何 `AgentTask` / `AgentRunMessage`，因此历史为空；
- 父会话在分叉后继续运行时，子会话又没有不可变边界，无法解释自己继承到哪一个 task；
- 如果直接克隆 `AgentTask`，调度器、租约和任务列表会把历史副本误判成真实执行任务。

本轮必须先补齐 durable fork graph，再切换 Provider memory；不能通过继续复制更多旧消息表数据掩盖问题。

## 2. OpenCode 对齐结论

参考：

- `D:/opencode/opencode-dev/packages/opencode/src/session/session.ts`
- `D:/opencode/opencode-dev/packages/opencode/src/session/message-v2.ts`
- `D:/opencode/opencode-dev/packages/opencode/src/session/compaction.ts`

OpenCode 分叉会复制完整 message/part graph，并重写 message ID、parent ID 和 compaction tail 引用。LabexAgent 的执行任务同时承担 scheduler/lease 身份，不能原样克隆 task。因此采用等价但适合当前数据模型的方案：

```text
child AgentConversation
  parent_conversation_id = source conversation
  forked_from_task_id = source 的不可变稳定 task 边界
```

Provider projector 递归读取父图到该边界，再追加子会话自己的 durable task。旧 `AgentMessage` 只继续作为 UI 兼容投影，不再决定 Provider history。

## 3. 架构决定

### 3.1 唯一分叉边界

新增 `t_agent_conversation.forked_from_task_id`：

- 默认分叉点为源会话“连续终态前缀”的最后一个 task；
- running / waiting / retry 中的 task 及其后的 task 不得进入分叉；
- API 可显式提交 `taskId`；必须属于同一用户、项目、源会话，且不得越过稳定边界；
- 旧 `messageId` 只做兼容映射：优先读取事件中的 `taskId`，否则按受限时间边界映射；
- 分叉创建与边界确定在事务中完成；
- 子会话永远读取固定边界，父会话后续新增 task 不得泄漏进入子会话。

### 3.2 Durable memory projector

新增单一 `AgentConversationMemoryProjectionService`：

1. 优先读取当前会话在请求边界前最新的 completed conversation compaction；
2. 从 compaction summary + retained tail 恢复，再追加 sourceMaxTaskId 后的 durable task；
3. 当前会话没有可用 compaction 时，递归读取父会话到 `forked_from_task_id`；
4. 追加当前会话自己的 `AgentTask.request_payload` 与 `AgentRunMessage(assistant:final)`；
5. 检测循环 parent、超过最大 lineage 深度、超过有界 task 批次数时明确失败，不能静默截断；
6. 输出 Provider-safe message list 和有界字符串 context；不读取 `AgentMessage`。

### 3.3 读路径迁移模式

`AgentConversationService.buildMemoryContext(...)` 保留为调用门面，配置支持：

- `legacy`：只读旧 `AgentMessage`，用于紧急回滚；
- `shadow`：同时构建新旧投影、记录无原文的计数/指纹比较，但返回旧投影；
- `durable`：只读 durable projector，不再查询旧消息表。

本轮在 RED/定向/全量/live gate 全部通过后，将默认值切到 `durable`。`shadow` 只保留为迁移诊断模式，并写明退出条件。

### 3.4 Compaction 与 fork 统一

`AgentConversationCompactionService` 必须从同一个 durable memory projector 取输入。这样对子会话执行压缩时，压缩 head 会包含继承的父历史，而不是只看到子会话自己的 task。

旧 `AgentConversationService.compactConversation(...)` 已无生产调用，本轮删除，避免继续维护第二套基于 `AgentMessage` 的 compaction。

## 4. TDD 失败用例

修改实现前先增加并执行以下回归：

1. fork 持久化最新稳定 task 边界，不包含 running task；
2. 显式 taskId 越权、跨会话、越过稳定边界时拒绝；
3. 旧 messageId 能映射到不晚于该消息的 durable task；
4. 子会话投影只继承父会话到固定边界，父会话分叉后的新 task 不泄漏；
5. nested fork 能递归恢复祖先、父、子三段历史；
6. parent cycle / 过深 lineage 明确失败；
7. 父会话已有 compaction 时，子会话使用边界前可用 checkpoint 和尾部，而不是重新依赖旧消息；
8. 子会话自己的 completed compaction 成为新起点，并追加后续 task；
9. durable 模式不调用 `AgentMessageMapper`；shadow 模式比较两条路径但仍返回 legacy；
10. 手动压缩子会话时输入包含继承历史；
11. schema/entity/migrator 同步包含 `forked_from_task_id`；
12. controller 同时接受 `taskId` 与旧 `messageId`。

RED 证据必须记录具体失败断言，不能只写“测试未通过”。

## 5. 实现文件范围

预计修改：

- `backend/src/main/java/com/labex/entity/AgentConversation.java`
- `backend/src/main/java/com/labex/mapper/AgentTaskMapper.java`
- `backend/src/main/java/com/labex/labexagent/service/AgentConversationService.java`
- `backend/src/main/java/com/labex/labexagent/service/AgentConversationTranscriptProjectionService.java`
- `backend/src/main/java/com/labex/labexagent/service/AgentConversationMemoryProjectionService.java`（新增）
- `backend/src/main/java/com/labex/labexagent/service/AgentConversationCompactionService.java`
- `backend/src/main/java/com/labex/labexagent/controller/StudentAgentController.java`
- `backend/src/main/java/com/labex/config/AdditiveSchemaMigrator.java`
- `backend/src/main/resources/sql/schema.sql`
- `backend/src/main/resources/application.yml`
- 对应后端测试、验收脚本与本迭代文档

不修改：

- task lifecycle / lease 的终态语义；
- `AgentRunMessage` / `AgentRunPart` 的现有 ID；
- 前端 reducer 的权威事件协议；
- 用户原有未提交文档和 acceptance 配置改动。

## 6. 验证顺序

```powershell
cd D:\LabexAgent\backend
mvn '-Dtest=AgentConversationForkDurableBoundaryTest,AgentConversationMemoryProjectionServiceTest,AgentConversationMemoryModeTest,AgentConversationCompactionServiceTest,AdditiveSchemaMigratorTimingTest' test
mvn test
mvn -DskipTests package

cd D:\LabexAgent\frontend
npm run build

cd D:\LabexAgent
.\scripts\acceptance\agent-runtime.ps1 -JarPath .\backend\target\labex-agent-backend-1.0.0.jar -BackendPort 18080 -TimeoutSeconds 120
```

真实系统验收必须新增：

- 父会话完成至少两个 durable task；
- 创建 fork 并记录 `forkedFromTaskId`；
- 父会话继续新增 task；
- 重启 JVM；
- 子会话 memory/context preview 仍包含边界内历史，不包含边界后历史；
- 子会话执行压缩后重启，仍从自己的 completed compaction 恢复；
- 数据库检查 Provider memory 的来源是 task/run-message/compaction，而非复制的 `AgentMessage`。

## 7. 本轮停止边界

本轮完成后停止在以下边界：

- Provider conversation memory 已切到 durable read；
- fork 有不可变 task graph 边界；
- fork 后 compaction/restart 可恢复；
- 旧 `AgentMessage` 仅剩 UI history 兼容投影；
- 旧 `AgentConversationService.compactConversation(...)` 已删除；
- 每项都有自动测试和真实 JVM/数据库/HTTP 证据；
- 创建一个独立本地 Git 提交，不推送。

本轮**不宣称整个架构收敛完成**。后续仍需单独完成前端历史列表完全改读 durable Message/Part、停止复制旧 AgentMessage，以及删除 shadow/legacy 回滚代码；这些属于下一轮，不能夹带进本轮。

## 8. 执行记录

### 8.1 RED 与根因证据

实现前增加 fork boundary、durable memory、compaction input、schema/controller 和 read-mode 测试，首次运行得到以下有意义的失败：

- `AgentConversation` 缺少 `forkedFromTaskId`；
- controller/service 缺少同时接受 `messageId + taskId` 的 fork API；
- `AgentConversationMemoryProjectionService` 尚不存在；
- conversation compaction 无法按 fork task 边界选择 checkpoint；
- ownership 回归证明 boundary service 不能只信查询结果，必须再次校验 student/project/conversation；
- durable 模式测试证明不能再触发 `AgentMessageMapper`。

定向 RED 不是通过 mock 字符串判断：测试先在编译期和具体断言处失败，随后才实现字段、服务和 projector。

真实验收复跑还暴露了一个既有 fixture 竞态：`python3 -m http.server 0` 在 Windows 上首先命中 `WindowsApps/python3.exe` 占位程序，进程可能在测试读取 durable PID 前退出。回归测试先改为期待稳定 Node fixture 并失败，再将场景改为：

```text
setup 通过正式文件 API 创建 .labex-acceptance-command-hold.cjs
  -> approved command: node .labex-acceptance-command-hold.cjs
  -> CommandClassifier = REQUIRE_APPROVAL
  -> Node timer 保持真实子进程 40 秒
```

同时将 PowerShell 验收脚本保存为 UTF-8 BOM，Windows PowerShell 5.1 `ParseFile` 已可直接解析含中文脚本。

### 8.2 实现结果

1. **不可变 fork authority**
   - `t_agent_conversation.forked_from_task_id` 已进入 entity、schema、additive migrator 和索引；
   - `AgentConversationForkBoundaryService` 成为唯一边界解析器；
   - 默认取源会话连续终态前缀，显式 `taskId` 必须属于稳定前缀；
   - 旧 `messageId` 只做一次性 task 映射/回填；
   - fork 创建使用源会话行锁，并把 UI 兼容事件复制限制在 task 完成时间之前。

2. **durable conversation graph projection**
   - 新增 `AgentConversationMemoryProjectionService`；
   - 递归读取 parent conversation 到固定 `forkedFromTaskId`，再追加子会话自己的 task/run-message；
   - 支持 parent/child 自身 completed conversation compaction；
   - parent cycle、超过 32 层 lineage、超过有界 transcript scan 或分页不前进都会明确失败；
   - Provider memory 全程不读取 `AgentMessage`。

3. **read path 切换与回滚**
   - `labex-agent.memory.projection-mode` 支持 `legacy | shadow | durable`；
   - 默认值已切到 `durable`；
   - `shadow` 只记录长度和截断 SHA-256 指纹，不记录原始上下文；
   - `legacy` 保留为本轮紧急回滚入口。

4. **compaction 统一**
   - `AgentConversationCompactionService` 只从 durable memory projector 读取输入；
   - 子会话压缩的 head 已包含继承的父历史，tail 保留子会话最近 turn；
   - `AgentCompactionService` 可按 `sourceMaxTaskId` 选择不晚于 fork 边界的 completed checkpoint；
   - 删除旧的 `AgentConversationService.compactConversation(...)` 第二套压缩实现。

5. **协议与 API**
   - transcript projector 使用 501 行探测、每批最多 500 个稳定 task，并暴露 `hasMore`；
   - controller 和前端集中 API 同时支持 `messageId` 与 `taskId`；
   - 验收脚本新增 durable fork boundary、child compaction、JVM restart 三项证据。

### 8.3 验证结果

后端定向回归：

```powershell
mvn '-Dtest=AgentConversationForkBoundaryServiceTest,AgentConversationForkDurableBoundaryTest,AgentConversationMemoryProjectionServiceTest,AgentConversationMemoryModeTest,AgentConversationCompactionServiceTest,AgentConversationServiceCompactionTest,AgentConversationTranscriptProjectionServiceTest,AgentCompactionConversationScopeTest,AdditiveSchemaMigratorTimingTest,AgentRunSchemaTest,StudentAgentControllerForkTest' test
```

结果：`40 tests`，`0 failures`，`0 errors`，`0 skipped`。

验收 fixture 回归：

```powershell
mvn '-Dtest=AcceptanceScriptedProviderTest#emitsALongCommandWithoutCreatingAnUnverifiedWorkspaceEdit,CommandSecurityTest#requiresApprovalForOrdinaryDirectMutations' test
```

结果：`2 tests`，全部通过；同时保留了先失败后修复的 RED 记录。

后端全量：

```powershell
cd D:\LabexAgent\backend
mvn test
```

结果：`1006 tests`，`0 failures`，`0 errors`，`8 skipped`，`BUILD SUCCESS`。

后端可执行包：

```powershell
mvn -DskipTests package
```

结果：`D:\LabexAgent\backend\target\labex-agent-backend-1.0.0.jar` 生成成功。

前端生产构建：

```powershell
cd D:\LabexAgent\frontend
npm run build
```

结果：Vite 构建和 chunk budget 均通过；关键预算：

- `CloudWorkspace`: `1,462,842 / 1,500,000 bytes`；
- `index`: `1,259,625 / 1,300,000 bytes`；
- `TerminalPanel`: `380,367 / 400,000 bytes`。

真实系统验收：

```powershell
cd D:\LabexAgent
.\scripts\acceptance\agent-runtime.ps1 `
  -JarPath .\backend\target\labex-agent-backend-1.0.0.jar `
  -BackendPort 18080 `
  -TimeoutSeconds 120
```

最终成功 run：`bf81c769398a42529ccaa8845c54990a`。该脚本真实启动隔离 H2、Spring Boot JAR、HTTP/SSE、命令子进程并多次重启 JVM；关键证据：

- `durableForkBoundary = true`，边界 task `21`；
- `durableForkCompaction = true`，子会话 source max task `25`；
- `durableForkRestart = true`；
- 删除子会话全部旧 `AgentMessage` 兼容副本后，context preview 仍包含 parent task 1-3；
- parent task 4-5 在 fork 后完成，但子会话 preview、compaction 和重启恢复均不包含它们；
- 子会话 compaction 的 `compacted_head` 含继承历史，`retained_tail` 含 child turn；
- `approvedCommandRestartRecovery = true`，稳定 Node fixture 的 durable PID 为 `46756`；
- 其余审批、取消、outbox、Part authority、compaction epoch 和 normal-profile 隔离证据也全部为 `true`；
- `cleanup = true`。

在 fixture 修复前曾有一次完整 run `18d1a6e89b2543aaaac95e511ca94c73` 通过；随后直接复跑主动暴露了 `WindowsApps/python3.exe` 竞态，修复后以上最终 run 再次通过，因此没有把偶然通过当成稳定结论。

### 8.4 本轮风险与退出条件

仍未完成、且不在本轮提交中夹带：

- 前端历史列表和 `getMemoryStats` 仍读取 `AgentMessage` 兼容投影；
- fork 仍复制旧 UI 事件，下一轮切完 durable history 后才能停止复制；
- `legacy` / `shadow` 回滚路径尚未删除；删除条件是前端 durable projection、历史回放和迁移监控均稳定；
- 本轮真实 Provider 使用 acceptance scripted provider，未声称外部云模型连接已验证；
- 本轮未做浏览器视觉回归，前端改动仅为集中 API 参数扩展并已通过生产构建；
- 单个 lineage 节点超过 10,000 个 task 或总投影超过 20 个批次时会明确要求先压缩，不会静默截断。

本轮停止边界已满足：Provider memory、fork boundary、child compaction 与 restart 都以 durable authority 工作；下一轮应单独迁移前端历史与统计读取，随后删除旧事件复制和 legacy/shadow 代码。
