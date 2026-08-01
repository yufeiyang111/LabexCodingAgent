# 第 29 轮迭代：收敛摘要事实源并删除旧内存裁剪路径

- 日期：2026-08-01
- 范围：持久化 conversation memory、AgentLoopEngine 上下文路径、回归测试、浏览器验收时序
- 对应约束：Provider 上下文必须由 durable transcript projector 生成；派生 summary 不能作为事实源；不得通过字符串裁剪破坏 tool-call 协议

## 1. 问题与证据

本轮审计发现两类残留：

1. `AgentConversationService.buildMemoryContext()` 在没有一等 `COMPACTION_SUMMARY` 事件时，仍会读取 `AgentConversation.summary` 并注入模型上下文。
2. `AgentLoopEngine` 中的 `trimMessagesIfNeeded`、`compactConversationCheckpoint`和 `aggressiveTrimMessages` 已无运行时调用点，且会把旧工具结果替换成 `role=user` 普通文本，存在破坏 `assistant tool_calls` 和 `role=tool` 关联的风险。

先加入回归测试后，未修复代码按预期失败：

```text
mvn -q -Dtest=AgentConversationServiceCompactionTest test
结果：1 个失败；过期 obsolete legacy summary 被恢复进上下文
```

第一次真实浏览器验收还暴露了验收脚本在收到 `FINAL` 文本后立即读取 task。后端日志证明 task 随后正常发出 `RUN_STATE_COMPLETED` 和 `DONE`，因此将验收改为等待 durable terminal state，而不是放宽状态检查。

失败日志：`C:/Users/35475/AppData/Local/Temp/labex-agent-browser-runtime-7925708642494e6cb79b30d91638c4f9/backend.out.log`

## 2. 实现

### 2.1 只从 durable compaction event 恢复历史摘要

修改：

- `backend/src/main/java/com/labex/labexagent/service/AgentConversationService.java`
- `backend/src/test/java/com/labex/labexagent/service/AgentConversationServiceCompactionTest.java`

没有 `COMPACTION_SUMMARY` 时，只使用 durable AgentMessage 近期事件；不再读取 `AgentConversation.summary` 作为模型上下文。旧 summary 写入暂时保留为兼容性投影，但不再是事实源。

新增回归测试 `recoveryDoesNotReadStaleConversationSummaryWithoutDurableCompactionEvent`。

### 2.2 删除未接入的旧内存裁剪方法

修改：

- `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- `backend/src/test/java/com/labex/labexagent/runtime/AgentLoopEngineContextBudgetTest.java`

删除三个无调用的旧方法及仅服务于它们的常量、import和反射测试；将保留的测试改为验证 durable Provider projection 中 tool call / tool result 结构不被改写。

### 2.3 修正浏览器验收终态时序

修改 `frontend/scripts/acceptance/agent-browser.mjs`：最终文本出现后，继续等待同一 task 进入 `completed`、`failed` 或 `cancelled`，再检查 task 、Tool Part 和结果顺序。

## 3. 验收结果

### 3.1 重点回归测试

```text
cd D:\LabexAgent\backend
mvn -q "-Dtest=AgentLoopEngineContextBudgetTest,AgentConversationServiceCompactionTest" test
结果：通过
```

### 3.2 后端全量测试

```text
cd D:\LabexAgent\backend
mvn -q test
结果：825 项测试，0 failures，0 errors，8 skipped
```

### 3.3 前端测试与构建

```text
cd D:\LabexAgent\frontend
npm test
结果：160 pass，0 fail

npm run build
结果：Vite 构建通过；chunk budget 通过
```

### 3.4 真实系统验收

```text
D:\LabexAgent\scripts\acceptance\browser-runtime.ps1 -BackendPort 18106 -FrontendPort 13007 -CdpPort 19233 -TimeoutSeconds 120
结果：通过

D:\LabexAgent\scripts\acceptance\run-all.ps1 -BackendPort 18107 -FrontendPort 13008 -CdpPort 19234 -TimeoutSeconds 120
结果：package、acceptance unit tests、backend restart acceptance、browser acceptance 全部通过
```

关键浏览器证据：conversationIsolation、refreshReplayDeduplicated、questionReplyComponent、permissionApprovalRefreshRecovery、multiToolPermissionBatchProtocolComplete、durableCompaction、providerStreamInterruptionHandled、staticContextBlockerCard、completionEvidenceCard、unverifiedCompletionBlocked 均为 true，consoleErrors=0，networkErrors=0。

## 4. 本轮未删除的兼容路径

- `AgentConversation.summary` 的写入；
- 旧 conversation message API 及其历史展示投影；
- durable compaction 仍在使用的 `ConversationCheckpointCompactor`；
- 与文件验证证据、命令失败保护相关的 `AgentRunArtifactService`。

它们不能作为 Provider transcript 或 task lifecycle 的权威事实源。下一轮继续审计前端 conversation 历史读取、旧 summary 写入监控以及 projector/旧路径 shadow compare，满足删除条件后再移除。

## 5. 结论

本轮完成了一个边界明确的架构收敛：模型上下文不再从过期的 conversation aggregate summary 读取，旧的字符串式内存裁剪路径也不再存在；Provider 输入继续由 durable transcript/projector 路径负责。
