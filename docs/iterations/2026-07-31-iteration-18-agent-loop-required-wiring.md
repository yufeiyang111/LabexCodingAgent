# 第 18 轮：AgentLoopEngine 核心 runtime 依赖必需 wiring

- 日期：2026-07-31
- 分支：`codex/agent-tool-reliability`
- 目标：禁止 `AgentLoopEngine` 在真实 Spring runtime 中因核心服务缺失而静默降级；本轮先把所有核心 runtime setter 从 optional autowiring 收紧为必需 wiring。

## 当前证据

第 17 轮已经收紧 Provider transcript projection，但 `AgentLoopEngine` 仍有多组 `@Autowired(required = false)`，涉及执行器、租约、交互、压缩、完成证据、工具审计、网络审批和配置属性。

这会造成：

- Spring context 可以在缺少关键能力时继续启动；
- AgentLoop 运行到中途才因 null 或默认对象产生部分持久化；
- 测试构造路径和真实生产 wiring 不一致；
- 用户看到任务“运行了”，但 transcript、lease、artifact 或 tool journal 可能没有记录。

## 本轮方案

1. 先增加契约测试，固定 `AgentLoopEngine` 不得声明 optional autowiring。
2. 将核心 runtime setter 改为必需 `@Autowired`，不改变本轮公开 API 和数据库 schema。
3. 保留默认对象和旧测试构造路径作为下一轮待删除兼容层，并在文档中明确边界，避免把本轮误报成完全无 fallback。
4. 运行后端聚焦/全量测试及真实 Spring acceptance JVM 启动。

## 验收

```powershell
cd D:\LabexAgent\backend
mvn -q "-Dtest=AgentLoopEngineWiringContractTest" test
mvn -q test
```

真实验证：启动当前 `target/classes` 的 acceptance JVM，确认 context 成功启动并输出 AgentRunState linkage 日志，然后清理进程。

## 边界

本轮只收紧 Spring runtime 的依赖准入，不删除 AgentLoopEngine 中已经存在的默认 helper 实例、null 检查和测试兼容构造器；这些是下一轮的明确清理对象。

## 结果

### 实施

- `AgentLoopEngine` 的 13 个 `@Autowired(required = false)` 注入点已全部改为必需 `@Autowired`。
- 收紧了执行器、事件订阅、finalizer、artifact、tool journal、transcript、交互、compaction、租约、网络审批和配置属性的 Spring wiring。
- 本轮暂未删除默认 helper 实例和手工测试构造器，避免把 wiring 收敛与大规模构造重写混成一个不可审计变更；这些兼容层已在下一轮清单中明确。

### 测试

- 契约测试先红：`AgentLoopEngineWiringContractTest` 检出 optional autowiring。
- 聚焦回归：
  `mvn -q "-Dtest=AgentLoopEngineWiringContractTest,AgentLoopEngineLanguageTest,AgentLoopEngineNextPreviewTest,AgentLoopEngineStartupFailureTest" test` —— 通过。
- 后端全量：`mvn -q test` —— 通过，退出码 0。

### Live

- 使用当前 `backend\target\classes` 启动 acceptance JVM，端口 `18087`，实际 JVM PID `12876`。
- Spring context 启动成功，日志包含 `Started LabexAgentApplication`。
- 启动期日志包含 `Verified AgentRunState/AgentRunStateMachine linkage`。
- 验收完成后已清理 Maven launcher 和 JVM，没有留下本轮测试进程。

### 提交

- 本轮代码、测试和文档待完成最终 diff 审计后创建本地 Git 提交。