# 第 17 轮：强制 Provider transcript projection 与 compaction 依赖

- 日期：2026-07-31
- 分支：`codex/agent-tool-reliability`
- 目标：让 Provider 请求投影只从强制注入的 durable transcript projector 和 compaction service 产生，禁止缺少 compaction 时静默退化、缺少 projector 时临时 new 默认实例。

## 当前证据

第 16 轮已经删除 Run Message/Part 的可选依赖，但 `AgentTranscriptProjectionService` 仍存在：

- `@Autowired(required = false)` compaction setter；
- 只注入 transcript service 的 Spring 构造器；
- 默认 `new AgentProviderMessageProjector()`；
- compaction 缺失时直接从未压缩 transcript 继续投影。

这会让真实运行时出现两套 Provider 输入策略，无法证明模型请求使用了统一的 compaction epoch 和协议 projector。

## 本轮方案

1. 先增加契约测试，固定 projection service 必须强制持有 compaction service 和 provider projector。
2. 将 `AgentProviderMessageProjector` 注册为 Spring bean，并通过构造器注入。
3. 删除 optional compaction setter、默认 projector 和旧的单参数构造器。
4. 更新 projection 测试，覆盖 compaction projection 和无压缩记录时的明确行为。
5. 运行后端全量测试，并使用真实 acceptance JVM 验证 Spring wiring。

## 验收

```powershell
cd D:\LabexAgent\backend
mvn -q "-Dtest=AgentTranscriptProjectionContractTest,AgentTranscriptProjectionServiceTest" test
mvn -q test
```

真实验证：启动当前 `target/classes` 的 acceptance JVM，确认 `Started LabexAgentApplication` 与 AgentRunState linkage 日志，并清理测试 JVM。

## 边界

本轮不重写 compaction 算法、不调整数据库 schema，也不改 AgentLoopEngine 的全部 optional wiring；只收紧 Provider transcript projection 的事实入口。

## 结果

### 实施

- `AgentProviderMessageProjector` 已注册为 Spring bean。
- `AgentTranscriptProjectionService` 现在强制注入 `AgentRunTranscriptService`、`AgentProviderMessageProjector` 和 `AgentCompactionService`。
- 删除 optional compaction setter、默认 projector 和旧的单参数/双参数构造器。
- Provider projection 始终先经过 compaction service 的 durable projection 查询；没有已完成压缩记录时由 compaction service 返回空 projection，再回到 durable transcript，而不是因为依赖缺失而静默降级。
- 更新 `AgentTranscriptProjectionServiceWiringTest`，显式注册新的 runtime 依赖，固定真实构造契约。

### 测试

- 契约测试先红：检出 optional compaction 和默认 projector。
- 聚焦回归：
  `mvn -q "-Dtest=AgentTranscriptProjectionContractTest,AgentTranscriptProjectionServiceTest,AgentTranscriptProjectionServiceWiringTest" test` —— 通过。
- 第一次后端全量测试暴露已有 wiring test 没有提供新增的 projector bean；补齐测试上下文后重新运行。
- 后端全量：`mvn -q test` —— 通过，退出码 0。

### Live

- 使用当前 `backend\target\classes` 启动 acceptance JVM，端口 `18086`，实际 JVM PID `55112`。
- Spring context 启动成功，日志包含 `Started LabexAgentApplication`。
- 启动期日志包含 `Verified AgentRunState/AgentRunStateMachine linkage`。
- 验收完成后已清理 Maven launcher 和 JVM，没有留下本轮测试进程。

### 提交

- 本轮代码、测试和文档待完成最终 diff 审计后创建本地 Git 提交。