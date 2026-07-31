# 第 19 轮：删除 AgentLoopEngine processor 默认实例

- 日期：2026-07-31
- 分支：`codex/agent-tool-reliability`
- 目标：让 AgentLoopEngine 使用 Spring 注入的执行器、协议、叙述器和上下文准入组件，删除同一职责的 `new` 默认实例，避免测试/生产使用不同 runtime 对象。

## 当前证据

第 18 轮已将 AgentLoopEngine 的 Spring optional wiring 改为必需，但类内部仍有并行默认对象：

- `new AgentModelTurnExecutor(...)`
- `new AgentToolTurnExecutor(toolRegistry)`
- `new AgentToolCallBatchProtocol()`
- `new AgentProviderMessageProjector()`
- `new AgentToolNarrator()`
- `new ToolSelectionPolicy()`
- `new ContextAdmissionService()`
- `new ContextAdmissionGate()`
- `new AgentInteractionPauser(taskService)`

这些对象会遮蔽 Spring bean 的唯一性，尤其会让工具超时、Provider 协议校验和上下文准入使用不可观测的实例。

## 本轮方案

1. 先增加契约测试，禁止上述 processor/helper 默认实例。
2. 扩展必需 `setRunProcessors` 注入边界，包含 ToolTurnExecutor 和 ProviderMessageProjector。
3. 删除构造器中的 `new` 初始化，测试 fixture 显式注入 processor 实例。
4. 运行聚焦、后端全量和真实 Spring acceptance JVM 验证。

## 验收

```powershell
cd D:\LabexAgent\backend
mvn -q "-Dtest=AgentLoopEngineProcessorContractTest,AgentLoopEngineLanguageTest,AgentLoopEngineNextPreviewTest,AgentLoopEngineStartupFailureTest" test
mvn -q test
```

真实验证：启动当前 `target/classes` 的 acceptance JVM，确认 Spring context 和 AgentRunState linkage 成功，然后清理进程。

## 边界

本轮不删除 CommandClassifier、AgentCheckpointStore、配置属性默认对象，也不重写 AgentLoopEngine 构造器；这些默认事实源在后续切片处理。

## 结果

### 实施

- 删除 AgentLoopEngine 中 processor/helper 的默认实例：模型执行器、工具执行器、批处理协议、Provider projector、工具叙述器、工具选择策略、上下文准入服务和交互暂停器。
- `setRunProcessors` 现在显式接收全部 processor 依赖，并对 null 依赖直接失败。
- 更新 context budget 和 preview 测试 fixture，显式模拟 Spring 注入的 processor 集合。
- 保留 CommandClassifier、AgentCheckpointStore、配置属性等非本轮对象，作为后续独立切片。

### 测试

- 契约测试先红：`AgentLoopEngineProcessorContractTest` 检出默认 processor 实例。
- 聚焦回归：
  `mvn -q "-Dtest=AgentLoopEngineProcessorContractTest,AgentLoopEngineContextBudgetTest,AgentLoopEngineLanguageTest,AgentLoopEngineNextPreviewTest,AgentLoopEngineStartupFailureTest" test` —— 通过。
- 全量测试第一次真实暴露 `AgentLoopEngineContextBudgetTest` 仍依赖旧默认 `ContextAdmissionService`；更新测试 fixture 后重新运行。
- 后端全量：`mvn -q test` —— 通过，退出码 0。

### Live

- 使用当前 `backend\target\classes` 启动 acceptance JVM，端口 `18088`，实际 JVM PID `18988`。
- Spring context 启动成功，日志包含 `Started LabexAgentApplication`。
- 启动期日志包含 `Verified AgentRunState/AgentRunStateMachine linkage`。
- 验收完成后已清理 Maven launcher 和 JVM，没有留下本轮测试进程。

### 提交

- 本轮代码、测试和文档待完成最终 diff 审计后创建本地 Git 提交。