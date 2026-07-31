# 第 21 轮：AgentLoopGuard 配置所有权与局部状态边界

- 日期：2026-07-31
- 分支：`codex/agent-tool-reliability`
- 目标：保留 `AgentLoopGuard` 每个 task/epoch 独立的循环检测状态，同时删除它在缺少配置时自行创建 `AgentLoopProperties` 的第二事实源。

## 当前证据

第 20 轮已经让 AgentLoopEngine 强制注入 `AgentLoopProperties`，但 `AgentLoopGuard` 仍然执行：

```java
this.properties = properties == null ? new AgentLoopProperties() : properties;
```

这会造成：

- 运行时配置缺失时被静默替换；
- hard max、non-progress、strategy switch 阈值可能与 Spring 配置不一致；
- guard 的局部状态和配置所有权边界不清晰。

## 本轮方案

1. 先增加契约测试，固定 null 配置必须失败，禁止 guard 内部 `new AgentLoopProperties()`。
2. 保留 `AgentLoopGuard` 作为每次运行独立的局部状态对象；不把 `recentToolSignatures`、challenge 状态注册成 Spring singleton。
3. 更新文档和测试，验证 AgentLoopEngine 仍然把注入的 `loopProperties` 传给每次新建的 guard。
4. 运行后端聚焦/全量测试和真实 Spring acceptance JVM。

## 验收

```powershell
cd D:\LabexAgent\backend
mvn -q "-Dtest=AgentLoopGuardConfigContractTest,AgentLoopGuardTest,AgentLoopEngineLoopPolicyTest" test
mvn -q test
```

真实验证：启动当前 `target/classes` acceptance JVM，确认 Spring context 和 AgentRunState linkage 成功，然后清理进程。

## 边界

`AgentLoopGuard` 的每任务局部可变状态不是持久化事实，不在本轮改成 Spring singleton；循环触发结果仍必须通过 AgentTask/lifecycle/transcript 记录。

## 结果

### 实施

- `AgentLoopGuard` 保留每个 task/epoch 独立的 `recentToolSignatures`、challenge pattern 和 non-progress 计数，不改成 Spring singleton。
- 删除 `properties == null ? new AgentLoopProperties() : properties` fallback；缺少循环配置现在立即抛出 `IllegalArgumentException`。
- AgentLoopEngine 继续把 Spring 注入的 `loopProperties` 传给每次新建的 guard，配置所有权仍在 runtime wiring。

### 测试

- 契约测试先红：`AgentLoopGuardConfigContractTest` 检出 guard 内部默认配置实例。
- 聚焦回归：
  `mvn -q "-Dtest=AgentLoopGuardConfigContractTest,AgentLoopGuardTest,AgentLoopEngineLoopPolicyTest" test` —— 通过。
- 后端全量：`mvn -q test` —— 通过，退出码 0。

### Live

- 使用当前 `backend\target\classes` 启动 acceptance JVM，端口 `18090`，实际 JVM PID `26636`。
- Spring context 启动成功，日志包含 `Started LabexAgentApplication`。
- 启动期日志包含 `Verified AgentRunState/AgentRunStateMachine linkage`。
- 验收完成后已清理 Maven launcher 和 JVM，没有留下本轮测试进程。

### 提交

- 本轮代码、测试和文档待完成最终 diff 审计后创建本地 Git 提交。