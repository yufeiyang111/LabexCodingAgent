# 第 26 轮：AgentLoopEngine 必需持久化服务 wiring

- 日期：2026-07-31
- 分支：`codex/agent-tool-reliability`
- 目标：删除 AgentLoopEngine 在 interaction 恢复、transcript 投影、completion evidence、artifact 记录和事件 subscription 中的可选 service 旁路。这些依赖缺失时必须在 wiring 阶段失败，不能让运行似乎成功但丢失审批恢复或审计证据。

## 先红的回归证据

本轮先在 `AgentLoopEngineWiringContractTest` 增加了以下约束：

- `runInteractionService` 不能以 null 跳过审批恢复；
- `runFinalizer` 不能缺失时跳过 completion evidence；
- `AgentRunArtifactService` 不能缺失时静默停止 tool failure audit；
- transcript/projector/setter 必须使用统一的 runtime dependency 校验。

```powershell
cd D:\LabexAgent\backend
mvn -q "-Dtest=AgentLoopEngineWiringContractTest" test
```

修改前失败：检测到 `runInteractionService != null`、`runFinalizer == null` 和 `artifactService == null || context` 等旁路。

## 实施

### 必需 runtime setter

以下 setter 现在都通过 `requireRuntimeDependency(...)` 保证不会注入 null：

- `AgentTaskEventSubscriptionService`
- `AgentRunFinalizer`
- `AgentRunArtifactService`
- `AgentRunTranscriptService`
- `AgentTranscriptProjectionService`
- `AgentRunInteractionService`
- `AgentToolCallJournalService`（第 25 轮已强制）

### 删除运行时旁路

- interaction resume 只要带有 interaction id 就必须走持久化 interaction service；
- final response 只能在 completion evidence service 完成 assess 后结束；
- tool failure 只在 context 不完整时返回，不再把 artifact service 缺失当成正常情况；
- run loop 中的 durable subscription 依赖继续按必需 wiring 处理。

## 验收

### 聚焦测试

```powershell
cd D:\LabexAgent\backend
mvn -q "-Dtest=AgentLoopEngineWiringContractTest,AgentLoopEngineContextBudgetTest,AgentLoopEngineNextPreviewTest" test
```

结果：通过，退出码 0。

### 后端全量测试

```powershell
cd D:\LabexAgent\backend
mvn -q test
```

结果：通过，退出码 0。

全量测试包含审批决策、event replay、tool 文件操作和 Spring context 测试。

### 真实 Spring JVM

使用本轮最新 JAR 启动 `local,acceptance` profile：

- JAR：`backend/target/labex-agent-backend-1.0.0.jar`
- 端口：`18096`
- JVM PID：`17088`
- 日志包含 `Started LabexAgentApplication`
- 日志包含 `Verified AgentRunState/AgentRunStateMachine linkage for 13 states`
- HTTP smoke 访问 `http://127.0.0.1:18096/api/error` 得到 HTTP 500，说明 listener 已建立
- 验收后已停止 PID `17088`，剩余匹配 backend JVM 为 0

## 边界

本轮只收敛 AgentLoopEngine 的 service wiring 和调用旁路，没有改变任务状态迁移协议或前端 reducer。下一轮需要进入真实前端验收：审批后不刷新继续流、SSE cursor 回放、重复批准和任务终态。

## 提交

本轮只提交 AgentLoopEngine、契约测试和本轮文档，不包含用户已有配置修改。
