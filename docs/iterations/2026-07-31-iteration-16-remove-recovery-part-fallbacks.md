# 第 16 轮：删除恢复与 Run Part 的可选依赖旁路

- 日期：2026-07-31
- 分支：`codex/agent-tool-reliability`
- 目标：让启动恢复和 durable Run Part 写入不再因为 optional setter、旧构造器或 null message service 而退化成半持久化运行。

## 当前证据

第 15 轮收紧了 `AgentTaskService`，但全仓库审计发现：

- `AgentRunRecoveryService` 仍使用多个 `@Autowired(required = false)` setter；
- 启动恢复在缺少 lease/takeover/Part/message service 时会静默跳过关键恢复动作；
- `AgentRunPartService` 保留没有 `AgentRunMessageService` 的旧构造器，并允许工具 Part 没有关联 durable assistant message；
- 这会使真实 Spring runtime 与测试构造对象拥有不同的 transcript/recovery 事实。

## 本轮方案

1. 先增加契约测试，要求 Recovery/Part 服务只保留强制依赖构造器，不允许 optional setter 和 null message fallback。
2. 删除 Recovery 的 optional setter，强制注入 lease、takeover、Part、Message 服务。
3. 删除 Part 的旧构造器和 message null 分支，所有工具 Part 都先关联 durable assistant turn。
4. 更新恢复/Part 测试，运行后端全量测试，并用真实 acceptance JVM 验证启动恢复 wiring。

## 验收

```powershell
cd D:\LabexAgent\backend
mvn -q "-Dtest=AgentRecoveryPartFallbackContractTest,AgentRunRecoveryServiceTest,AgentRunPartServiceTest" test
mvn -q test
```

真实验证：启动当前 `target/classes` 的 acceptance JVM，确认 Spring context 成功启动和状态机 linkage 校验，然后清理测试进程。

## 边界

本轮不改变 recovery 状态迁移协议和数据库 schema；只删除恢复/transcript Part 的依赖注入兼容层，确保缺少关键服务时启动直接失败而不是静默降级。

## 结果

### 实施

- `AgentRunRecoveryService` 现在通过唯一构造器强制注入 execution lease、takeover scheduler、Run Part 和 Run Message 服务。
- 删除启动恢复的 optional setter 和关键恢复动作的 null 跳过分支。
- `AgentRunPartService` 删除无 `AgentRunMessageService` 的旧构造器；工具 Part、事件 Part 和审批终态更新都必须关联 durable assistant message。
- 测试改为显式提供恢复/消息/Part mock，不再覆盖“缺依赖也静默运行”的生产模式。

### 测试

- 契约测试先红：`AgentRecoveryPartFallbackContractTest` 检出了 Recovery/Part 的 optional fallback。
- 聚焦回归：
  `mvn -q "-Dtest=AgentRecoveryPartFallbackContractTest,AgentRunRecoveryServiceTest,AgentRunPartServiceTest" test` —— 通过。
- 后端全量：`mvn -q test` —— 通过，退出码 0。

### Live

- 使用当前 `backend\target\classes` 启动 acceptance JVM，端口 `18085`，实际 JVM PID `57444`。
- Spring context 启动成功，日志包含 `Started LabexAgentApplication`。
- 启动期日志包含 `Verified AgentRunState/AgentRunStateMachine linkage`，证明强制恢复/Part 依赖注入已被真实 runtime 加载。
- 验收完成后已清理 Maven launcher 和 JVM，没有留下本轮测试进程。

### 提交

- 本轮代码、测试和文档待完成最终 diff 审计后创建本地 Git 提交。