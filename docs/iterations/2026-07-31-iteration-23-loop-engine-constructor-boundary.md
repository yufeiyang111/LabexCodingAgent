# 第 23 轮：删除 AgentLoopEngine 旧构造函数旁路

- 日期：2026-07-31
- 分支：`codex/agent-tool-reliability`
- 目标：不再保留可以用一组 null 创建未完整运行时 wiring 的 AgentLoopEngine 旧构造函数，统一使用完整的 Spring 构造函数。

## 先红的回归证据

在修改实现前，`AgentLoopEngineWiringContractTest` 新增了构造函数数量检查：

```powershell
cd D:\LabexAgent\backend
mvn -q "-Dtest=AgentLoopEngineWiringContractTest" test
```

失败：

```text
legacyConstructorsMustNotCreateAnUnwiredRuntime
expected: <1> but was: <3>
```

当时存在 3 个 public 构造函数，其中 2 个通过转发并注入 null 来创建运行时对象。

## 实施

### 修改代码

- 删除 `AgentLoopEngine` 中两个旧的简化构造函数；
- 保留唯一带 `AgentRunLifecycleService`、`CommandApprovalService`、`ContextUsageEstimator`、`ContextUsageRegistry` 和 `CompactionAgent` 的完整构造函数；
- 把两个只测试私有方法的单元测试改为调用完整构造函数，不再使用过时签名；
- 保留完整的 wiring contract 测试。

### 涉及文件

- `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- `backend/src/test/java/com/labex/labexagent/runtime/AgentLoopEngineWiringContractTest.java`
- `backend/src/test/java/com/labex/labexagent/runtime/AgentLoopEngineLanguageTest.java`
- `backend/src/test/java/com/labex/labexagent/runtime/AgentLoopEngineStartupFailureTest.java`

## 验收

### 聚焦测试

```powershell
cd D:\LabexAgent\backend
mvn -q "-Dtest=AgentLoopEngineWiringContractTest,AgentLoopEngineLanguageTest,AgentLoopEngineStartupFailureTest" test
```

结果：通过，退出码 0。

### 后端全量测试

```powershell
cd D:\LabexAgent\backend
mvn -q test
```

结果：通过，退出码 0。

### 真实 JVM 验证

使用本轮最新 JAR 启动 `local,acceptance` profile：

- JAR：`backend/target/labex-agent-backend-1.0.0.jar`
- 端口：`18092`
- JVM PID：`41488`
- 日志包含 `Started LabexAgentApplication`
- 日志包含 `Verified AgentRunState/AgentRunStateMachine linkage for 13 states`
- HTTP smoke 访问 `http://127.0.0.1:18092/api/error` 得到 HTTP 500，说明 listener 已建立
- 验收后已停止 PID `41488`，剩余匹配 backend JVM 为 0

## 边界

本轮只删除不能完整注入的构造函数旁路，没有改变生产任务状态、事件、transcript 或前端协议。下一轮将继续处理 AgentLoopEngine 中与持久化 compaction 有关的 null fallback。

## 提交

本轮只提交上述 4 个代码文件和本轮文档，没有包含用户的 `application-acceptance.yml` 修改和其他未跟踪文件。
