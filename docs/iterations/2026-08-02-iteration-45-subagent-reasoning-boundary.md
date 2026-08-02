# Iteration 45：关闭子 Agent 的内部推理旁路

## 状态

已完成源码修复与边界验收（2026-08-02）；完整重启场景暴露了独立的交互租约竞态，转入下一轮修复。

## 现象

用户再次看到 `<think>` 协议标签。此前主 Agent 的 Provider、SSE、持久化事件与前端回放都已增加清洗，但泄漏仍可复发。

## 根因证据

全仓库审计 `LlmProvider.chatStream(...)` 调用点后发现：

- 主 Agent 模型回合通过 `AgentModelTurnExecutor`，会将 `thinking_delta` 与 `text_delta` 分离，并对嵌入在正文中的推理块做跨 chunk 状态化过滤；
- 简单提示流也在 `AgentLoopEngine` 内使用同一边界；
- `LlmSubagentExecutor` 是唯一旁路：它不区分 Provider 事件类型，把每个 `chunk.content()` 都追加到最终输出并持久化为 `DELTA`；
- 因此 `thinking_delta`、带 `<think>` 的正文、`done` 聚合内容和未知事件都可能混入子 Agent 的可见结果，随后进入父任务摘要。

这不是前端 Markdown 样式问题，而是 Provider 事件在后端进入权威 transcript 前就被错误合流。

## 本轮计划

1. 先增加子 Agent 流协议回归测试，证明当前实现会泄漏推理并重复拼接 `done`；
2. 让 `LlmSubagentExecutor` 按 `ProviderEventType` 消费流：
   - 仅 `TEXT_DELTA` 进入可见输出；
   - `THINKING_DELTA` 不进入可见输出或持久化结果；
   - 正文中嵌入的 `<think>...</think>` 使用跨 chunk 状态机剥离；
   - `DONE` 只负责封口，不重复追加聚合正文；
   - `ERROR`、`CANCELLED` 和缺失终态进入明确失败；
3. 在 `SubagentResultSummaryService` 再做一次最终可见投影，防止其他 `SubagentExecutor` 实现绕过边界；
4. 运行聚焦测试、完整后端测试、前端测试/构建和真实浏览器验收；
5. 记录 RED/GREEN 与验收结果，创建聚焦本地 Git 提交。

## 验收标准

- 子 Agent 的 `thinking_delta` 不出现在返回值、`DELTA` 事件或父任务 `SUBAGENT_SUMMARY`；
- 跨 chunk 的 `<think>` / `<thinking>` 块不会出现在可见文本；
- `done` 中的聚合正文不会造成重复；
- Provider 错误、取消或无终态不会伪装成成功；
- 现有主 Agent 实时投影、刷新回放、审批恢复与重启验收不回归。

## RED / GREEN / 系统验收

### RED

命令：

```powershell
cd D:\LabexAgent\backend
mvn '-Dtest=LlmSubagentExecutorTest,SubagentResultSummaryServiceTest' test
```

有效 RED 结果：3 个断言均失败。

- `LlmSubagentExecutor` 实际返回了 `thinking_delta`、正文中的 `<THINK>` 块以及重复的 `done` 聚合正文；
- Provider `error` 被当作普通 `DELTA` 成功返回；
- `SubagentResultSummaryService` 原样保存并投影 `<think>private chain</think>`。

### GREEN 与扩大回归

- 聚焦回归：3/3 通过；
- Provider、主模型回合、RunMessage/RunPart、SSE、回放和子 Agent 组合回归：63/63 通过；
- 后端全量：870 个测试，0 失败，0 错误，8 跳过；
- 前端全量测试：通过；
- 前端生产构建：通过；
- bundle 预算：`CloudWorkspace` 1,459,986 / 1,500,000，`index` 1,259,534 / 1,300,000，`TerminalPanel` 380,367 / 400,000。

### 真实浏览器边界验收

命令：

```powershell
.\scripts\acceptance\browser-runtime.ps1 `
  -BackendPort 18080 `
  -FrontendPort 13000 `
  -CdpPort 19222 `
  -TimeoutSeconds 180 `
  -RestartBackendForAcceptance
```

日志目录：

`C:\Users\35475\AppData\Local\Temp\labex-agent-browser-runtime-4ccabe6350f0420eb2d101d52d361458`

结果分层：

- 真实 Spring Boot、Vite、Chromium 已启动；
- 脚本通过了前置的实时 reasoning-boundary 与刷新回放断言，说明本轮 `<think>` 可见边界在真实页面上未泄漏；
- 随后的完整场景在手工 compaction 问题回复后失败，失败点不是 reasoning 渲染；
- 后端日志证明回答已持久化：`AGENT_QUESTION_REPLY_RESULT ... answered=true`；
- 紧接着恢复 claim 被拒绝：`AGENT_INTERACTION_RESUME_CLAIM_REJECTED ... taskStatus=waiting_user`；
- 任务 `1766` 此后一直停留在 `waiting_user`。

因此本轮可以确认 `<think>` 旁路已关闭，但不能把整套重启验收宣称为通过。该独立租约交接竞态必须在下一轮修复后重新跑完整浏览器验收。

## 实现记录

1. `LlmSubagentExecutor` 不再盲目拼接 `chunk.content()`，而是按 `ProviderEventType` 分流；
2. 仅 `TEXT_DELTA` 进入可见输出，并通过 `VisibleStreamFilter` 跨 chunk 删除嵌入推理块；
3. `THINKING_DELTA` 与 usage 不进入可见 transcript；
4. `DONE` 仅封口，不重复追加聚合内容；
5. Provider 错误、取消、意外工具调用和缺失终态进入明确失败；
6. `SubagentResultSummaryService` 在写入子 Agent 终态和父任务摘要前再次执行最终可见投影。

## 剩余风险与下一步

- 本轮只修复了子 Agent Provider 流旁路；主 Agent 的既有边界仍由原测试和真实页面回放覆盖；
- 下一轮必须修复“已回答交互遇到尚未释放的旧 execution lease 后永久卡住”的恢复竞态；
- 修复后重新运行完整强制重启浏览器验收，并确认端口与临时进程清理。
