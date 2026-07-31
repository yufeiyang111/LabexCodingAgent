# 第 15 轮：删除 AgentTaskService 的状态机旁路与可选依赖

- 日期：2026-07-31
- 分支：`codex/agent-tool-reliability`
- 目标：让 `AgentTaskService` 不再通过 null lifecycle、直接 SQL 更新或可选 execution lease 维持旧兼容行为；运行状态、恢复 claim 和工作区分配全部使用强制构造注入的领域服务。

## 当前证据

第 14 轮已经删除了 scheduler/controller 的旧布尔恢复入口，但 `AgentTaskService` 仍保留以下旁路：

- 无 lifecycle 时直接更新 `t_agent_task.status`；
- `requestCancellation`、`finalizeCancellation`、等待外部条件时绕过 lifecycle；
- `executionLeaseService` 使用 `@Autowired(required = false)` 和 setter 注入；
- 生产服务保留没有 lifecycle 的测试/兼容构造器；
- background worktree service 没有进入 Spring 的主构造器。

这些路径会让源码测试对象和真实 Spring runtime 使用不同状态机，属于架构事实分叉。

## 本轮方案

1. 先增加契约测试，固定 `AgentTaskService` 必须只有强制 lifecycle、execution lease 和 background worktree 依赖，且不允许 null fallback。
2. 删除直接 SQL status fallback、可选 setter 和旧构造器。
3. 更新所有服务测试以显式注入 mock/fake 领域依赖。
4. 用真实 Spring 启动验证构造注入、状态机 bean 和 runtime classpath 一致。

## 验收

```powershell
cd D:\LabexAgent\backend
mvn -q "-Dtest=AgentTaskServiceFallbackContractTest,AgentTaskServiceTest,AgentTaskServiceTimingTest,AgentTaskServiceLifecycleTest" test
mvn -q test
```

真实验证：启动当前 classpath 的 acceptance JVM，确认 Spring context 完整启动并输出 `AgentRunState/AgentRunStateMachine linkage` 校验日志，然后停止该 JVM。

## 边界

本轮不改变数据库 schema、不重写 lifecycle transition 表，也不处理前端投影；只删除 AgentTaskService 的状态机旁路和依赖注入兼容层。

## 结果

### 实施

- `AgentTaskService` 现在通过唯一 Spring 构造器强制注入 `AgentRunLifecycleService`、`AgentRunExecutionLeaseService` 和 `BackgroundRunWorktreeService`。
- 删除无 lifecycle 的旧构造器、`@Autowired(required = false)` execution lease setter 以及所有 null 依赖分支。
- 删除 `updateTask` 直接更新 `AgentTask.status` 的第二写入路径；未知状态现在明确抛出 `IllegalArgumentException`。
- 创建任务、取消、等待外部条件、交互恢复和模型重试都只能经过 lifecycle/claim 服务。
- 相关测试全部改为显式注入 mock/fake 领域依赖，不再模拟缺失 runtime 服务的生产模式。

### 测试

- 契约测试先红：`AgentTaskServiceFallbackContractTest` 检出原有 null fallback 和直接状态写入。
- 聚焦回归：
  `mvn -q "-Dtest=AgentTaskServiceFallbackContractTest,AgentTaskServiceTest,AgentTaskServiceTimingTest,AgentTaskServiceLifecycleTest" test` —— 通过。
- 后端全量：`mvn -q test` —— 通过，退出码 0。

### Live

- 使用当前 `backend\target\classes` 启动 acceptance JVM，端口 `18084`，实际 JVM PID `43192`。
- Spring context 启动成功，日志包含 `Started LabexAgentApplication`。
- 启动期日志包含 `Verified AgentRunState/AgentRunStateMachine linkage`，证明强制构造注入没有破坏真实 runtime wiring。
- 验收完成后已通过 `taskkill /T /F` 清理测试 JVM 和 Maven launcher，没有留下 Labex backend 进程。

### 提交

- 本轮代码、测试和文档待完成最终 diff 审计后创建本地 Git 提交。