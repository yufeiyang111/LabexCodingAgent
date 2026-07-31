# 第 22 轮：SSE durable/transient 事件边界收敛

- 日期：2026-07-31
- 分支：`codex/agent-tool-reliability`
- 目标：禁止运行期 durable SSE 事件在未绑定 task/epoch 时绕过 `AgentRunLifecycleService` 直接写入浏览器，明确区分预任务 transient 错误、运行期 transient delta 和已持久化 replay 事件。

## 背景与失败边界

第 21 轮之后，`AgentSsePublisher.send(String, Object)` 仍有一条隐含旁路：如果没有先调用 `bindRun(...)`，它会退化为没有 sequence 的裸 SSE 写入。这会让普通运行事件绕过 `AgentRunEvent`，浏览器看到了事件，但刷新、断线或跨 JVM 恢复时无法重放。

本轮先在 `AgentSsePublisherDurabilityTest` 增加回归测试，要求未绑定 run 的 durable send 必须失败，且不得向 emitter 写入事件。

### 先红的证据

```powershell
cd D:\LabexAgent\backend
mvn -q "-Dtest=AgentSsePublisherDurabilityTest" test
```

结果：测试编译通过后，新增测试失败：

```text
rejectsDurableSendBeforeTheRunIsBound
Expected java.lang.IllegalStateException to be thrown, but nothing was thrown.
```

这证明测试捕获的是现有真实旁路，而不是伪造的预期失败。

## 实施内容

### 1. AgentSsePublisher

文件：

- `backend/src/main/java/com/labex/labexagent/runtime/AgentSsePublisher.java`

修改：

- `send(String, Object)` 在没有绑定 lifecycle/task 时立即抛出 `IllegalStateException`；
- 只有绑定 task 后，才允许先调用 `AgentRunLifecycleService.appendEvent(...)`，再发送带 sequence 的 SSE；
- 将低层 replay API 显式命名为 `sendPersisted(...)`；
- 将实际 emitter 写入收敛到内部 `sendFrame(...)`；
- `sendTransient(...)` 继续用于不要求持久回放的 delta 和预任务错误。

### 2. AgentLoopEngine

文件：

- `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`

修改：

- 队列拒绝属于尚未创建 durable task 的 transient 错误，改用 `sendTransient(...)`；
- 运行期 transient subscriber 依赖改为必需 wiring，不再用 null 条件表达可选状态；
- 创建 task 后无条件绑定 `runLifecycleService`，避免第一条 durable 事件在未绑定时发送。

### 3. 订阅与 replay 调用点

文件：

- `backend/src/main/java/com/labex/labexagent/run/AgentTaskEventSubscriptionService.java`
- `backend/src/main/java/com/labex/labexagent/controller/StudentAgentController.java`

修改：

- transient 订阅广播通过 `sendTransient(...)`；
- 已持久化事件 replay 通过 `sendPersisted(...)`；
- 调用名称直接表达事件的事实来源，避免后续新增调用点误用裸 `send(sequence, ...)`。

### 4. 回归测试

文件：

- `backend/src/test/java/com/labex/labexagent/runtime/AgentSsePublisherDurabilityTest.java`

覆盖：

- 未绑定 run 的 durable send 被拒绝；
- 已绑定事件先落 durable lifecycle，再写 SSE；
- durable 持久化失败时不写 emitter；
- 客户端断开不会抹掉 durable run；
- transient delta 不会为每个 chunk 写 durable event；
- 客户端断开后 transient subscriber 仍能接收后续 live delta。

## 验收结果

### 聚焦回归与 SSE 订阅测试

```powershell
cd D:\LabexAgent\backend
mvn -q "-Dtest=AgentSsePublisherDurabilityTest,AgentLoopEngineStreamingContractTest,AgentTaskEventSubscriptionServiceTest,AgentTaskEventControllerTest" test
```

结果：通过，退出码 0。

### 后端全量测试

```powershell
cd D:\LabexAgent\backend
mvn -q test
```

结果：通过，退出码 0。

日志中同时出现了审批决策、task event replay、outbox broadcast、terminal state subscriber removal 等系统测试路径，没有只验证简单纯函数。

### 真实 Spring JVM

先构建本轮最新代码：

```powershell
cd D:\LabexAgent\backend
mvn -q -DskipTests package
```

首次使用 `acceptance` 单 profile 启动时，应用因该 profile 没有提供 `SandboxWorker` bean 而失败：

```text
Parameter 2 of constructor in TerminalWebSocketHandler required a bean of type SandboxWorker that could not be found.
```

`application-acceptance.yml` 是用户原有工作区改动，本轮没有修改。为完成不改变该文件的真实启动验证，使用 `local,acceptance` 双 profile 启动最新 JAR：

- JAR：`backend/target/labex-agent-backend-1.0.0.jar`
- 端口：`18091`
- JVM PID：`29052`
- Spring context：启动成功
- Tomcat：`18091`，context path `/api`
- 启动日志：`Started LabexAgentApplication`
- 状态机校验：`Verified AgentRunState/AgentRunStateMachine linkage for 13 states`
- HTTP smoke：访问 `http://127.0.0.1:18091/api/error` 获得 HTTP 500，而不是连接失败，证明 HTTP listener 已真实建立
- 验收结束后已停止 PID `29052`，剩余匹配 backend JVM 数量为 0

## 本轮边界

本轮没有声称已经完成整个前端浏览器端到端审批恢复。这里修复的是事件发布端的事实边界：

- durable 事件必须先进入 lifecycle/event store；
- transient 事件不能伪装成 durable 事件；
- replay 事件必须显式走 persisted API；
- SSE 断线不能把任务事实降级成连接内存状态。

下一轮继续审计 AgentLoopEngine 的其他局部瞬时对象和残余事实源，并安排真实浏览器审批后不断流、断线 cursor 回放和刷新恢复验收。

## 提交

本轮只提交上述代码、测试和本轮文档；未包含用户已有修改或其他未跟踪文件。
