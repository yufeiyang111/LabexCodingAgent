# Iteration 01：Provider transcript 协议安全边界

- **日期：** 2026-07-30
- **目标：** 在不重写 AgentLoopEngine 的前提下，先阻止上下文裁剪/恢复破坏原生 tool-call 协议，并让 Provider 发送前存在明确的协议投影与校验边界。
- **基线分支：** `codex/agent-tool-reliability`
- **基线 HEAD：** `5d2598b chore: checkpoint agent reliability iteration`
- **既有工作区改动：** 本轮开始前已有大量 Java/Vue/测试/README 改动；本轮不重置、不覆盖、不纳入本轮提交。

## 本轮计划

1. 新增 Provider transcript validator，校验 assistant `tool_calls` 和 `role=tool` 的 `tool_call_id`、工具名及配对关系。
2. 新增 Provider message projector，深复制 Provider 输入，防止发送过程意外修改 AgentLoop 的工作列表。
3. 将 AgentLoop 的 Provider 请求改为通过 projector 生成。
4. 修复历史 tool result 裁剪时丢失 `tool_call_id`、`name`、metadata 等字段的问题。
5. 增加协议、多工具调用、孤立工具结果、未闭合工具调用和 metadata 保留回归测试。
6. 执行 focused test、后端全量、前端全量、真实 acceptance 系统验收。
7. 仅提交本轮文件，并记录 commit SHA。

## 已完成实现

- 新增 `AgentProviderProtocolValidator`。
- 新增 `AgentProviderMessageProjector`。
- `AgentLoopEngine` 在构造 `ModelTurnRequest` 前通过 projector 复制并校验 `msgs`。
- `TurnAwareContextPruner` 现在复制原消息字段，只替换 `content`，不会再丢失工具协议身份。
- 新增 `AgentProviderMessageProjectorTest`。
- 为 `TurnAwareContextPrunerTest` 增加协议 metadata 保留回归测试。

## 当前验收证据

### 已通过

```text
mvn -Dtest="AgentProviderMessageProjectorTest,TurnAwareContextPrunerTest,AgentToolCallBatchProtocolTest,AgentLoopEngineStreamingContractTest" test

14 tests
0 failures
0 errors
0 skipped
BUILD SUCCESS
```

### 待完成

- 后端全量 `mvn test`；
- 前端全量 `npm test`；
- acceptance profile 后端真实 HTTP/SSE 链路；
- 浏览器 acceptance：会话隔离、刷新回放、审批/问题交互、事件 cursor；
- 检查 Provider projector 在真实启动 JVM 中已加载；
- 通过后创建本轮 Git commit。

## 验收结论

当前只能确认协议投影和裁剪回归测试通过，不能宣称本轮整体系统验收完成。只有后端进程、前端进程和浏览器 acceptance 全部通过后，才能将本轮标记为通过。
## 真实系统验收过程与结果

### 第一次启动尝试：失败并定位到验收命令配置

命令只启用了 `acceptance` profile：

```text
-Dspring-boot.run.profiles=acceptance
```

后端启动失败：

```text
No qualifying bean of type com.labex.labexagent.worker.SandboxWorker available
```

原因是 `application.yml` 的 `spring.profiles.default=local` 在显式指定 profile 后不会自动补回 `local`，而 `TerminalWebSocketHandler` 与 Agent 工具依赖 `SandboxWorker`。这不是 Provider projector 引入的错误，而是 acceptance 启动方式没有激活既有 local worker。

没有保留无效的 `application-acceptance.yml` 修改；最终使用显式 profile 组合：

```text
-Dspring-boot.run.profiles=acceptance,local
```

### 第二次启动：真实后端通过

- Backend port `18080`：PID `51620`
- Backend port `8080`：PID `15864`
- Active profiles：`acceptance`, `local`
- MySQL：真实 Hikari 连接成功
- Additive schema migration：完成
- Spring Boot：`Started LabexAgentApplication`
- AgentRunState startup verifier：验证 13 个状态

### 浏览器系统验收

执行：

```powershell
cd D:\LabexAgent\frontend
$env:ACCEPTANCE_API_BASE='http://127.0.0.1:18080/api'
$env:ACCEPTANCE_UI_BASE='http://127.0.0.1:13000'
node scripts/acceptance/agent-browser.mjs
```

结果：

```text
ACCEPTANCE_EXIT=0
conversationIsolation=true
refreshReplayDeduplicated=true
questionReplyComponent=true
staticContextBlockerCard=true
completionEvidenceCard=true
unverifiedCompletionBlocked=true
consoleErrors=0
networkErrors=0
```

同时真实验证了：

- 桌面三栏布局；
- 新会话与旧会话隔离；
- 刷新后 durable task replay 不重复；
- 问题回复组件存在并可继续任务；
- task event cursor 持久化；
- 静态上下文阻塞卡片；
- 完成证据卡片；
- 未验证改动不能伪装完成；
- 浏览器控制台错误数为 0；
- 网络错误数为 0。

### 运行时加载证据

对实际运行中的 JVM `PID 15864` 执行：

```powershell
jcmd 15864 GC.class_histogram | Select-String 'AgentProviderMessageProjector|AgentProviderProtocolValidator|AgentLoopEngine'
```

确认实际 JVM 已加载：

```text
com.labex.labexagent.runtime.AgentProviderMessageProjector
com.labex.labexagent.runtime.AgentProviderProtocolValidator
com.labex.labexagent.runtime.AgentLoopEngine
```

## 本轮最终验收结论

本轮协议安全改动已通过：

- focused backend tests：14 passed；
- backend full suite：749 tests，0 failures，0 errors，8 skipped；
- frontend full suite：148 passed，0 failed；
- real Spring Boot + MySQL acceptance runtime：通过；
- real browser acceptance：通过；
- actual JVM class-load check：通过。

本轮未完成的架构项：

- `AgentRunMessage` / `AgentRunPart` 尚未成为 Provider transcript 的数据库唯一读取来源；
- compaction epoch 和 durable summary/head/tail 尚未完成；
- 状态机旁路写入的全面清理尚未完成；
- 本轮 Git commit 将仅包含本报告列出的 7 个文件，并保留其他既有工作区改动。

因此本轮只可以标记为“Provider 协议边界迭代完成”，不能标记整个架构收敛目标完成。
