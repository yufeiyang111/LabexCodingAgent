# 第 24 轮：强制 durable compaction 依赖

- 日期：2026-07-31
- 分支：`codex/agent-tool-reliability`
- 目标：删除 AgentLoopEngine 在 compaction 和 context 预处理中以 null 表示的旧内存 fallback，保证超限上下文只能从持久化 transcript 和 compaction record 恢复。

## 先红的回归证据

在修改实现前，`AgentLoopEngineDurableCompactionWiringTest` 新增检查：

- 不允许 `compactionService` 缺失时静默跳过持久化；
- 不允许 `transcriptProjectionService != null` 决定是否走旧的内存 prune；
- 不允许 `compactionAgent == null` 被当成可恢复策略。

```powershell
cd D:\LabexAgent\backend
mvn -q "-Dtest=AgentLoopEngineDurableCompactionWiringTest" test
```

失败：原实现命中 `this.compactionService == null` 旁路，新增契约测试不通过。

## 实施

### AgentLoopEngine 上下文管理

- 在进入 context management 时强制校验 durable projector；
- 取消基于 `transcriptProjectionService != null` 的旧 tool-result 内存 prune 分支；
- context 超限后统一进入可持久 compaction，不再把运行副本当成实时事实。

### durable compaction 依赖

- 新增 `requireCompactionService()`，缺失时明确失败；
- compaction 必须同时具有 task、project 和 execution context；
- `previousSummary` 和 `sourceMaxSequence` 必须来自持久化 compaction/transcript service；
- compaction agent 是必需 runtime dependency，只有模型压缩返回失败时才允许进入明确的 deterministic checkpoint strategy；
- compaction record 必须先 start，并在 success/failure 时更新终态。

## 验收

### 聚焦测试

```powershell
cd D:\LabexAgent\backend
mvn -q "-Dtest=AgentLoopEngineDurableCompactionWiringTest,AgentLoopEngineContextBudgetTest,ContextWindowSupervisorTest" test
```

结果：通过，退出码 0。

### 后端全量测试

```powershell
cd D:\LabexAgent\backend
mvn -q test
```

结果：通过，退出码 0。

### 真实 Spring JVM

使用本轮最新 JAR 启动 `local,acceptance` profile：

- JAR：`backend/target/labex-agent-backend-1.0.0.jar`
- 端口：`18093`
- JVM PID：`30028`
- 日志包含 `Started LabexAgentApplication`
- 日志包含 `Verified AgentRunState/AgentRunStateMachine linkage for 13 states`
- HTTP smoke 访问 `http://127.0.0.1:18093/api/error` 得到 HTTP 500，说明 HTTP listener 已建立
- 验收后已停止 PID `30028`，剩余匹配 backend JVM 为 0

## 边界

本轮保留了模型 compaction 失败后的 deterministic checkpoint fallback，因为它是明确的策略切换，不是依赖缺失时的静默旁路。

下一轮继续处理 ContextWindowSupervisor 中剩余的旧 PRUNE 语义、AgentLoopEngine 中的余下 null service 保护分支，以及真实浏览器的审批后实时回放。

## 提交

本轮只提交 AgentLoopEngine、回归测试和本轮文档，不包含用户已有配置修改。
