# 第 10 轮：Provider 断流、交互恢复与跨 JVM 审批投影

- 日期：2026-07-31
- 分支：`codex/agent-tool-reliability`
- 对应计划：`docs/superpowers/plans/2026-07-29-agent-runtime-state-convergence.md` 的任务七、任务九
- 状态：完成

## 本轮目标

1. Provider 流在没有 `done` / `error` 终端事件时不得被当作成功完成。
2. 提问或审批恢复期间，即使前端 session 尚未恢复，也不能请求 `active-task/undefined`。
3. `permission`、`network`、`question` 必须持久化成正确的等待状态和交互载荷。
4. 页面刷新和 JVM 重启后，审批卡片必须从 durable task / interaction / Tool Part 恢复，并继续原 task，而不是新建对话。
5. 用隔离后端、隔离 Vite 和真实 Chromium 验证，不以编译或 mock 代替系统验收。

## 实施内容

### 后端

- `AgentModelTurnExecutor` 检测 Provider 流无终端事件的 EOF，并返回明确的可恢复错误。
- `AgentLoopEngine` 将该错误纳入有限恢复策略；权限询问改为携带 durable interaction payload，由统一事件路径发送 `PERMISSION_ASK`。
- `AgentToolCallJournalService` 按交互类型写入：
  - `question -> waiting_user`
  - `permission/network -> waiting_approval`
- `AgentRunPartService` 在工具生命周期变化时同步更新 Provider `tool_call` Part，避免等待审批的 Provider Part 在 JVM 启动恢复阶段被误封为 `interrupted`。
- acceptance scripted Provider 新增 `[acceptance:stream-break]` 故障注入。

### 前端

- `useAgentTaskRuntime` 在 `conversationId` 暂时缺失时使用 durable `taskId` 查询兜底，校验 task / conversation / session 归属后再恢复订阅。
- session 事件和 assistant message 持续携带 `conversationId`，避免恢复入口丢失运行身份。
- reducer 按 `interactionType` 恢复提问、权限审批和网络审批卡片；旧观察事件不能把等待审批卡片覆盖成错误或提问卡片。
- 浏览器验收发送消息改走输入框真实 Enter 键路径，并修复 interaction restart handoff 错误引用普通 restart 目录的问题。

## 现场发现并修复的问题

### 1. `active-task/undefined`

前端恢复逻辑只从当前 UI session 读取 `conversationId`。刷新或交互恢复的短暂窗口内 session 为空，会向错误 URL 发请求。本轮改为优先使用 assistant message / interaction 中的 conversation，并按 taskId 兜底。

### 2. 权限审批被显示成用户提问

所有交互此前都被写成 `waiting_user + question`。本轮将 interaction 类型和任务等待状态一一对应，前端也按 durable payload 选择正确卡片。

### 3. Provider 流半截结束被误判成功

scripted Provider 在输出 partial delta 后直接结束。修复后任务进入有限 retry，最终为明确失败，浏览器验收确认没有显示成功完成。

### 4. 审批中 JVM 重启后 Provider Part 不可恢复

第一次真实 restart handoff 暴露错误：

```text
Provider tool call part is not recoverable: provider:0:tool-call:2:0:acceptance-permission-read-env
```

根因是 UI 工具 Part 已写成 `waiting_approval`，但 Provider `tool_call` Part 仍是 `pending`；JVM 启动恢复把它封为 `interrupted`，随后 Provider transcript 无法重建。本轮把两者的生命周期同步，第二次真实 JVM 重启验收通过。

### 5. 验收 harness 曾产生假阳性

`waitForRestartContinuation()` 原先只读取 `ACCEPTANCE_RESTART_HANDOFF_DIR`，interaction restart 分支虽然写了 `ready.json`，却没有真正等待 `continue.signal`。修复后先验证浏览器进程确实阻塞，再停止后端、启动新 JVM、写入 signal，最终 `restartInteractionVerified=true`。

## 验证记录

### 自动化门禁

```powershell
cd D:\LabexAgent\backend
mvn -q test
```

结果：通过。

```powershell
cd D:\LabexAgent\frontend
npm.cmd test
npm.cmd run build
npm.cmd run test:acceptance:unit
```

结果：

- 前端测试：158/158 通过；
- Vite 生产构建通过；
- bundle budget 通过；
- acceptance unit：9/9 通过；
- source encoding 检查包含在前端测试中并通过。

### 普通浏览器系统验收

隔离拓扑：

- Vite：`127.0.0.1:13002`
- Spring Boot：`127.0.0.1:18081`
- Chromium CDP：`19234`

结果：通过，覆盖：

- 桌面布局；
- 新旧会话隔离；
- 刷新回放去重；
- 提问回复组件；
- 权限审批与刷新恢复；
- durable Provider Message/Part；
- compaction epoch；
- Provider stream interruption；
- 静态上下文阻塞卡片；
- completion evidence；
- 未验证修改不得显示成功；
- console/network error 均为 0。

### 审批中真实 JVM 重启

验收目录：

`D:\LabexAgent\.codex-tmp\iteration10-interaction-restart-20260731-140517`

证据：

1. task `876` 进入 `waiting_approval` 并写出 `ready.json`；
2. 浏览器在没有 `continue.signal` 时保持阻塞，未提前生成 `done.json`；
3. 停止旧隔离 JVM；
4. 启动新 JVM PID `59492`，classpath 指向 `D:\LabexAgent\backend\target\classes`；
5. 写入 `continue.signal`；
6. 页面刷新后重新出现审批卡片；
7. 批准后继续原 task；
8. 最终 `restartInteractionVerified=true`，console/network error 均为 0。

## 保留的原工作区改动

以下内容不属于本轮，不纳入提交：

- `backend/src/main/resources/application-acceptance.yml`
- `.codex-tmp/`
- `docs/agent-context-provider-smoke-test.md`
- `docs/coding-agent-engineering-roadmap.md`
- `docs/coding-agent-industrialization/`
- 既有未跟踪的 `docs/superpowers/plans/`、`docs/superpowers/specs/` 文档。

## 剩余风险与后续

1. 继续做多 tool call 在交互暂停时的 companion tool result 协议验收，确保剩余 Part 都有确定终态和 Provider 对应结果。
2. 完成旧内存 `msgs`、重复 summary 和旧 UI toolCalls 权威路径的 shadow compare 与删除。
3. 补齐工具半截输出、命令未启动、compaction 失败、未知模型窗口、lease takeover 和 scheduler 重复领取故障注入。
4. 对现有测试做按风险分层和重复用例合并，不以测试数量代替系统证据。
