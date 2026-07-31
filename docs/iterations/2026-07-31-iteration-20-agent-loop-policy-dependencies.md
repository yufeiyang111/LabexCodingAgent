# 第 20 轮：AgentLoopEngine 策略、checkpoint 与配置依赖收敛

- 日期：2026-07-31
- 分支：`codex/agent-tool-reliability`
- 目标：删除 AgentLoopEngine 中 CommandClassifier、CheckpointStore、failure guard、token estimator 和配置属性的默认实例，让策略与恢复配置只有 Spring runtime 一套事实源。

## 当前证据

第 19 轮已经删除 processor 默认实例，但 AgentLoopEngine 仍保留：

- `new CommandClassifier()`；
- `new AgentCheckpointStore()`；
- `new CommandFailureGuard(1, 2)`；
- `new AgentRecoveryProperties()`；
- `new AgentLoopProperties()`；
- `new AgentRequestTokenEstimator()`。

其中 CommandClassifier、CheckpointStore、CommandNormalizer 尚未注册为 Spring bean，导致生产 runtime 仍然有一套不可观测的本地策略对象。

## 本轮方案

1. 先增加契约测试，固定上述策略/持久化/配置对象不得在 AgentLoopEngine 内部 `new`。
2. 将 CommandNormalizer、CommandClassifier、AgentCheckpointStore 注册为 Spring bean。
3. 通过 required setter 注入策略、checkpoint、failure guard、配置属性和 token estimator。
4. 更新测试 fixture（如测试直接触及这些字段），运行后端全量测试和真实 Spring acceptance JVM。

## 验收

```powershell
cd D:\LabexAgent\backend
mvn -q "-Dtest=AgentLoopEnginePolicyContractTest" test
mvn -q test
```

真实验证：启动当前 `target/classes` acceptance JVM，确认 Spring context 和 AgentRunState linkage 成功，然后清理进程。

## 边界

本轮不删除 AgentSsePublisher、AgentLoopGuard、ContextOverflowRecoveryPolicy 和 ContextWindowSupervisor 的局部执行对象；这些对象不持有跨任务事实，后续只在发现重复策略时再处理。

## 结果

### 实施

- `CommandNormalizer`、`CommandClassifier`、`AgentCheckpointStore` 已注册为 Spring bean。
- 删除 AgentLoopEngine 中 `CommandClassifier`、CheckpointStore、CommandFailureGuard、recovery/loop properties、request token estimator 的默认实例。
- 增加必需 runtime setter，并对缺失依赖直接抛出明确异常。
- `setContextCompactionServices` 现在同时强制校验 compaction service 和 token estimator。
- 测试 fixture 不再依赖上述默认策略对象。

### 测试

- 契约测试先红：`AgentLoopEnginePolicyContractTest` 检出默认策略/检查点实例。
- 聚焦回归：
  `mvn -q "-Dtest=AgentLoopEnginePolicyContractTest,AgentLoopEngineProcessorContractTest,AgentLoopEngineContextBudgetTest,AgentLoopEngineLanguageTest,AgentLoopEngineNextPreviewTest,AgentLoopEngineStartupFailureTest" test` —— 通过。
- 后端全量：`mvn -q test` —— 通过，退出码 0。

### Live

- 使用当前 `backend\target\classes` 启动 acceptance JVM，端口 `18089`，实际 JVM PID `19612`。
- Spring context 启动成功，日志包含 `Started LabexAgentApplication`。
- 启动期日志包含 `Verified AgentRunState/AgentRunStateMachine linkage`。
- 验收完成后已清理 Maven launcher 和 JVM，没有留下本轮测试进程。

### 提交

- 本轮代码、测试和文档待完成最终 diff 审计后创建本地 Git 提交。