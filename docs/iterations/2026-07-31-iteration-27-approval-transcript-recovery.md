# 第 27 轮迭代：审批恢复与 durable transcript 闭环

- 日期：2026-07-31
- 范围：审批恢复、Provider transcript、真实验收入口、前端浏览器验收
- 对应架构计划：`docs/superpowers/plans/2026-07-29-agent-runtime-state-convergence.md` 的任务七、任务九
- Git 分支：`codex/agent-tool-reliability`

## 1. 本轮目标

完成审批和用户交互恢复的真实闭环：

1. 审批执行结束后，原始 `toolCallId` 必须有对应的持久化 `role=tool` 结果；
2. Provider transcript 重启或恢复时不能出现悬空 assistant tool call；
3. 审批失败不能触发同一个命令重新执行；
4. 前端真实浏览器验收覆盖审批、问题回答、刷新回放、SSE cursor 去重和桌面布局；
5. 每轮只提交本轮相关文件，保留已有工作区改动。

## 2. 失败复现与根因

### 2.1 既有 Vite 进程导致 500

第一次手工复用既有 `13000` Vite 进程时：

- 直接访问隔离后端的项目列表、项目详情和文件树接口都是 HTTP 200；
- 通过旧 Vite `/api` 代理访问项目列表返回 HTTP 500；
- 旧进程没有使用本轮隔离后端的 `VITE_API_TARGET`，仍代理到默认后端。

结论：这是验收环境复用旧 Vite 进程造成的代理目标不一致，不是项目详情或文件树接口自身的回归。后续使用 `scripts/acceptance/browser-runtime.ps1` 启动隔离 Vite并显式传入 `VITE_API_TARGET`。

### 2.2 命令审批后 transcript 不完整

真实后端重启验收稳定复现：

- 审批执行进入 `COMMAND_EXECUTION_FAILED`；
- 原始 tool call Part 仍无法在 Provider transcript 中重建；
- 恢复线程报 `Unable to restore durable Provider transcript`；
- 根因是审批执行链只更新工具状态，没有把外部执行结果追加为持久化 `role=tool` 消息。

同时，Tool Journal 通过可选 setter 注入，使这条持久化依赖可以静默缺失。

### 2.3 验收 Provider 没有将 failed 视为已解决

补上 transcript 后，验收 Provider 仍曾对 `Resolution status: failed` 重新发起 shell 审批。这是验收 Provider 状态识别不完整，不是生产 Agent 重复执行。

## 3. 本轮实现

### 3.1 审批编排器依赖收敛

修改：`backend/src/main/java/com/labex/labexagent/commandsecurity/CommandApprovalOrchestrator.java`

- `AgentToolCallJournalService` 改为构造器必需依赖；
- `AgentRunTranscriptService` 改为构造器必需依赖；
- 删除可选 Tool Journal setter，禁止运行时静默缺少持久化依赖；
- 审批执行结果先写入 durable transcript，再写兼容 Journal；
- 命令失败、拒绝、过期、编排中断均会生成可重建的工具结果。

### 3.2 增加 deferred tool result 持久化

修改：`backend/src/main/java/com/labex/labexagent/run/AgentRunTranscriptService.java`

新增 `appendDeferredToolResult`：

- 按 `taskId + toolCallId` 检查是否已有 `tool_result`，保证重复恢复幂等；
- 校验原始 tool call 存在，禁止孤立结果；
- 通过持久化任务的 execution epoch 和 transcript 下一个序号追加 Provider message；
- 写入 `role=tool`、`tool_call_id`、工具名和脱敏结果；
- 追加后更新匹配的 Tool Call Part，使 assistant tool call 与 tool result 成为完整协议批次。

### 3.3 验收 Provider 修复

修改：`backend/src/main/java/com/labex/labexagent/llm/AcceptanceScriptedProvider.java`

- 将 `Resolution status: failed` 纳入已解决交互状态；
- 不改变生产 Provider 行为。

### 3.4 编码回归修复

保留并提交上一轮真实前端门禁发现的两个 Java 注释编码修复：

- `backend/src/main/java/com/labex/labexagent/run/AgentToolCallJournalService.java`
- `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`

## 4. 回归测试

- `backend/src/test/java/com/labex/labexagent/commandsecurity/CommandApprovalOrchestratorTest.java`
  - 审批恢复必须调用 transcript deferred result；
  - Journal 只作为兼容投影写入已解决状态。
- `backend/src/test/java/com/labex/labexagent/run/AgentRunTranscriptServiceTest.java`
  - 外部审批结果写成 Provider `role=tool` message；
  - 原始 tool call Part 被更新为协议终态；
  - 保留 toolCallId、工具名、结果内容和序号。
- `backend/src/test/java/com/labex/labexagent/llm/AcceptanceScriptedProviderTest.java`
  - failed command approval continuation 不再重新发起审批。

## 5. 验收证据

### 后端 focused test

```text
mvn -q -Dtest=AgentRunTranscriptServiceTest,CommandApprovalOrchestratorTest,AcceptanceScriptedProviderTest test
结果：通过
```

### 后端完整测试

```text
cd D:\LabexAgent\backend
mvn -q test
结果：通过
```

### 后端重启/恢复系统验收

```text
D:\LabexAgent\scripts\acceptance\agent-runtime.ps1 -BackendPort 18101 -TimeoutSeconds 120
结果：通过
```

通过项包括：`questionRestart`、`permissionRestart`、`commandApproveRestart`、`commandRejectRestart`、`checkoutContention`、`runMessagePartProjection`、`manualCompaction`、`staticContextBlocked`、`completionEvidence`、`unverifiedEditRejected`、`normalProfileProviderIsolation`、`cleanup`。

### 完整隔离验收入口

```text
D:\LabexAgent\scripts\acceptance\run-all.ps1 -BackendPort 18102 -FrontendPort 13004 -CdpPort 19230 -TimeoutSeconds 120
结果：四项全部通过
```

- 后端 JAR 打包：通过；
- acceptance unit tests：9 项通过；
- 后端重启验收：通过；
- 真实 Chrome/CDP 浏览器验收：通过。

浏览器关键结果：

- 桌面布局：1440x900，左侧文件区、中间对话区、右侧 Agent 区均存在且横向排列；
- `conversationIsolation=true`；
- `refreshReplayDeduplicated=true`；
- `questionReplyComponent=true`；
- `permissionApprovalRefreshRecovery=true`；
- `multiToolPermissionBatchProtocolComplete=true`；
- `durableCompaction=true`；
- `providerStreamInterruptionHandled=true`；
- `staticContextBlockerCard=true`；
- `completionEvidenceCard=true`；
- `unverifiedCompletionBlocked=true`；
- `consoleErrors=0`；
- `networkErrors=0`。

本轮浏览器脚本没有传入独立的后端重启 handoff 目录，因此输出中的 `restartProjectionVerified=false` 不代表普通审批/刷新恢复失败；后端专用重启验收中的交互恢复指标已全部为 true。

### 前端测试和构建

```text
cd D:\LabexAgent\frontend
npm test
结果：通过

npm run build
结果：通过，chunk budget 通过
```

## 6. 未纳入本轮提交的工作区改动

以下内容保持原样，没有纳入本轮提交：

- `backend/src/main/resources/application-acceptance.yml` 的既有修改；
- `.codex-tmp/`；
- 用户已有的 `docs/agent-context-provider-smoke-test.md`；
- 用户已有的 `docs/coding-agent-engineering-roadmap.md`；
- `docs/coding-agent-industrialization/`；
- 其他未跟踪的历史计划和规格文档。

## 7. 本轮结论

第 27 轮解决了一个会直接阻断系统使用的真实问题：审批执行和 durable transcript 之前没有闭合，导致服务重启或恢复时无法继续原 task。本轮之后，审批结果以同一个 toolCallId 追加到持久化 Provider transcript，恢复线程可以重建完整的 assistant tool call + tool result 协议批次。

本轮完成的是“审批恢复与 transcript 闭环”，不等于整个架构收敛计划完成。后续仍需继续审计并删除旧的并列事实源、修复未知上下文窗口的宽松默认值，并完成更完整的故障注入和生产模型现场验证。
