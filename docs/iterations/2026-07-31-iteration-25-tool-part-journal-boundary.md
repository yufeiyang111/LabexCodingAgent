# 第 25 轮：Tool Part 日志必需持久化

- 日期：2026-07-31
- 分支：`codex/agent-tool-reliability`
- 目标：将每个 tool call 的 `AgentRunPart` 作为必需的可恢复事实，禁止 Part 缺失、Part 写入失败或 lifecycle event 失败被静默吞掉。

## 先红的证据

`AgentToolCallJournalServiceTest` 新增契约测试，修改前执行：

```powershell
cd D:\LabexAgent\backend
mvn -q "-Dtest=AgentToolCallJournalServiceTest" test
```

结果：失败。原因包括：

- 两参构造函数把 `AgentRunPartService` 传成 null；
- `partService != null` 使 Part 变成可选记录；
- Part 写入和 lifecycle event 写入的 `RuntimeException` 被吞掉；
- 无 Part 时仍以 artifact key 发布事件，会造成工具实际执行与 transcript/replay 分叉。

## 实施

### AgentToolCallJournalService

- 删除两参旧构造函数；
- `AgentRunArtifactService`、`AgentRunLifecycleService`和 `AgentRunPartService` 都改为构造时必需依赖；
- `AgentRunPart` 先写入，之后才写兼容 artifact，最后写 lifecycle event；
- event key 统一为 `tool-call-state-part-{partId}-{status}`，事件同时带 `partId` 和 `partKey`；
- Part 持久化失败时不写 artifact 和 event，上层会获得明确失败，不再伪造可恢复状态；
- 仅保留解析旧 artifact 内容时的 `JsonParseException` 兼容处理，它不参与新事实写入。

### AgentLoopEngine

- `setToolCallJournalService(...)` 改为必需 dependency injection；
- 删除 pending、running、waiting approval、waiting user、blocked、completed/failed 路径中的 null guard；
- 同一 assistant turn 的 skipped tool call 也不允许由于 journal 缺失而静默漏记。

## 验收

### 聚焦测试

```powershell
cd D:\LabexAgent\backend
mvn -q "-Dtest=AgentToolCallJournalServiceTest,AgentLoopEngineWiringContractTest" test
```

结果：通过。

新增行为测试：Part store 抛出异常时，journal 直接失败，artifact 和 lifecycle event 均不会发生。

### 后端全量测试

```powershell
cd D:\LabexAgent\backend
mvn -q test
```

结果：通过，退出码 0。

### 真实 Spring JVM

使用本轮最新 JAR 启动 `local,acceptance` profile：

- JAR：`backend/target/labex-agent-backend-1.0.0.jar`
- 端口：`18095`
- JVM PID：`60976`
- 日志包含 `Started LabexAgentApplication`
- 日志包含 `Verified AgentRunState/AgentRunStateMachine linkage for 13 states`
- HTTP smoke 访问 `http://127.0.0.1:18095/api/error` 得到 HTTP 500，说明 listener 已建立
- 验收后已停止 PID `60976`，剩余匹配 backend JVM 为 0

## 边界

本轮的目标是确保工具生命周期不会在持久化失败时伪装为成功或可恢复状态。它没有改变工具执行策略、审批 UI 或兼容 artifact 读取接口。

## 提交

本轮只提交 Journal、AgentLoopEngine wiring、回归测试和本轮文档，不包含用户的 `application-acceptance.yml` 修改和其他未跟踪文件。
