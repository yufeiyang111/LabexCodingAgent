# 第 61 轮：命令审批稳定身份、协议闭合与终态刷新投影收敛

- 日期：2026-08-03
- 分支：`codex/agent-tool-reliability`
- 前置提交：`6a283a6 fix: converge slash command dispatch`
- 状态：已完成

## 1. 本轮目标

把一次性命令审批从“按最后一个运行中工具猜卡片”的临时逻辑，收敛为由持久化 `toolCallId` 和终态任务快照驱动的稳定投影：

1. 首次 `COMMAND_APPROVAL_REQUIRED` 必须携带创建审批时已经持久化的原始 `toolCallId`；
2. 实时事件、历史回放和 active-task/terminal-task 刷新恢复使用同一套审批投影规则；
3. 多工具批次中，审批只绑定对应的 `toolCallId`，不能绑定最后一个工具；
4. 审批事件早于工具 Part 到达时，先创建带稳定 ID 的占位卡，后续 durable `TOOL_CALL_STATE` 合并而不重复；
5. 历史旧事件缺少 `toolCallId` 时，只创建由 `approvalId` 派生的确定性兼容卡，禁止猜测任意现有工具；
6. 审批暂停同一批次剩余工具时，为所有 skipped tool call 补齐 Provider `tool_result`，保持协议一一对应；
7. 用户拒绝审批并刷新后，继续原 task，不创建第二个 task；最终回答和审批终态无需再次刷新即可显示。

## 2. 架构归属

- 审批与原始工具调用的权威绑定：`CommandApproval.toolCallId`；
- 工具生命周期与 Provider 协议事实：`AgentRunPart`；
- 最终模型输出：`AgentRunMessage(messageKey=assistant:final)`；
- 可重放运行事件：`AgentRunEvent`；
- 旧会话消息表：兼容投影，不得覆盖 task transcript 的权威性；
- 前端：事件、Message、Part 和审批快照的派生视图，不得自行发明绑定关系。

本轮未改变命令安全分类、批准策略或状态机合法迁移集合。`AgentRunLifecycleService.recordEventPartBestEffort` 的非原子投影问题仍留作第 62 轮架构收敛候选。

## 3. 根因与实际失败链

### 3.1 审批身份丢失

改造前：

1. `CommandApprovalService` 创建记录时已经强制保存非空 `toolCallId`；
2. `AgentLoopEngine.stopForCommandApproval()` 发送首次 `COMMAND_APPROVAL_REQUIRED` 时却没有传出该 ID；
3. `agentHistoryReducer.js` 和 `CloudWorkspace.vue` 各自维护一套审批卡绑定逻辑；
4. 两套逻辑都可能使用最后一个 `toolCalls` 项或工具名猜测；
5. 乱序、刷新和多工具批次下会错绑、重复或无法更新。

### 3.2 多工具批次 Provider 协议未闭合

首个工具进入命令审批等待后，后续工具被标成 skipped，但旧实现只写工具生命周期，没有为这些 assistant `tool_calls` 追加对应 Provider `tool_result`。恢复后 transcript 可能出现孤立 tool call，导致 Provider 拒绝或上下文重建不合法。

### 3.3 task API 快照缺少审批 toolCallId

后端实体已经保存 `toolCallId`，但 `/agent/tasks/{taskId}` 的 `commandApproval` 公共投影没有返回该字段。刷新恢复仍无法使用权威身份。

### 3.4 刷新后“已结束但没有最终回答”

真实浏览器验收进一步发现两层问题：

1. 历史 reducer 原先不处理 `RUN_STATE_RECOVERING/RUNNING`，当完整运行事件存在时会把 `FINAL` 当成等待态伪终态而忽略；
2. 更关键的是，旧 `AgentMessage` 兼容历史只包含 `TASK_PAUSED(waiting_approval)`，恢复期 `RUN_STATE_RECOVERING/RUNNING` 只存在于 `AgentRunEvent`，没有镜像进该历史；历史接口已经返回完整 `FINAL`，但 reducer 仍因旧等待态忽略它。

最终修复不是继续猜状态，而是：会话加载发现 active task 已消失但历史中仍有 taskId 时，读取 `/agent/tasks/{taskId}` 的终态快照，以 `AgentRunMessage/AgentRunPart` 补齐最终回答和工具状态，并用持久化 `commandApproval` 关闭审批操作态。

## 4. 实施内容

### 4.1 后端

- `AgentLoopEngine`
  - 把当前 `toolCallId` 传给命令审批暂停路径；
  - 首次审批事件使用稳定 ID，并拒绝空 ID；
  - 审批阻塞后显式记录剩余调用 skipped；
  - 为 skipped companion calls 追加 Provider tool results，闭合整批协议。
- `AgentTaskEventController`
  - task 快照的 `commandApproval` 返回 `toolCallId`。
- `AcceptanceScriptedProvider`
  - `[acceptance:approval]` 生成固定双工具批次：首个 shell 审批、第二个 list_files 用于验证 skipped 与协议闭合。
- 对应测试锁定首次事件身份、task DTO 和 scripted provider 双工具契约。

### 4.2 前端

- 新增 `agentCommandApprovalState.js`
  - 先按 `approvalId`、再按稳定 `toolCallId` 查找；
  - 乱序时创建稳定占位卡；
  - legacy 事件使用 `legacy-command-approval:<approvalId>`；
  - 统一批准、拒绝、过期、执行和恢复状态更新。
- `agentHistoryReducer.js` 与 `CloudWorkspace.vue`
  - 删除重复的“最后一个工具”猜测逻辑；
  - 共用审批投影模块；
  - 历史 reducer 补齐运行态事件处理。
- `useAgentTaskRuntime.js`
  - completed 但最终内容为空时仍执行终态历史对账；
  - active task 为空时按历史 taskId 拉取终态 task 快照；
  - 从 `runMessages/parts` 恢复最终输出和工具状态；
  - 从 `commandApproval` 恢复拒绝/执行终态，避免已结束后仍显示可点击按钮。
- `ToolCallCard.vue`
  - 暴露只读 `data-tool-call-id`，用于诊断和系统验收，不作为状态源。
- 浏览器验收
  - 验证首个 shell 卡拥有审批、第二个 skipped 卡没有审批；
  - 验证 task DTO 的稳定 `toolCallId`；
  - 验证 skipped companion 有 Provider tool result；
  - 刷新后仍绑定同一卡；
  - 拒绝后继续同一 task；
  - 最终回答可见且审批按钮不可再操作；
  - 失败诊断只记录事件类型、状态和内容长度，不输出完整上下文。

## 5. RED→GREEN 证据

### 5.1 单元/回归 RED

- 多工具审批事件原先会绑定最后一个工具；
- completed 历史快照缺少 FINAL 时原先不会触发二次对账；
- 旧历史继续缺 FINAL 时原先不会读取权威终态 task transcript。

新增测试均先观察到预期失败，再做最小实现修复。

### 5.2 真实浏览器失败证据

1. 首次系统验收发现 task projection 缺 `commandApproval.toolCallId`；
2. 第二次发现验收误把 Provider Part 的 `completed` 当作工具执行成功，随后改为检查权威 `toolCalls` 生命周期中的 `skipped`；
3. `labex-agent-browser-runtime-d3a1aa63156e496e8238dff60c6be848`：后端已持久化 FINAL，但刷新后页面仍停在等待态；
4. `labex-agent-browser-runtime-a3b9226e13a949609646362712b78171`：诊断确认 `/messages` 中 messageId 131 已有完整 FINAL，问题确定在前端历史/终态投影；
5. 引入终态 task transcript hydration 后，真实浏览器流程通过。

这些失败没有通过放宽断言规避，而是逐层定位 DTO、协议层、兼容历史和权威 task projection 的差异。

## 6. 最终验证

### 6.1 后端全量测试

命令：

```powershell
cd D:\LabexAgent\backend
mvn test
```

结果：`934` tests，`0` failures，`0` errors，`8` skipped，`BUILD SUCCESS`。

### 6.2 前端全量测试

命令：

```powershell
cd D:\LabexAgent\frontend
npm test
```

结果：`209/209` 通过，包含审批身份、终态 task hydration、刷新恢复和源码编码守卫。

### 6.3 前端生产构建

命令：

```powershell
cd D:\LabexAgent\frontend
npm run build
```

结果：构建成功；分包预算全部通过：

- `CloudWorkspace-D-M8hXon.js`：`1,462,276 / 1,500,000` bytes；
- `index-CRNg7UPw.js`：`1,259,608 / 1,300,000` bytes；
- `TerminalPanel-ChGYrHNr.js`：`380,367 / 400,000` bytes。

### 6.4 完整系统验收

命令：

```powershell
cd D:\LabexAgent
.\scripts\acceptance\run-all.ps1 `
  -BackendPort 18108 `
  -FrontendPort 13028 `
  -CdpPort 19250 `
  -TimeoutSeconds 240 `
  -RestartBrowserBackend
```

结果：

- acceptance unit：`14/14`；
- backend restart acceptance runId：`4f740449af13416e8ed91faf93ff4764`；
- browser acceptance runId：`65cbd16706ea42688ff85ff80dd6c205`；
- `commandApprovalStableToolIdentity: true`；
- `multiToolPermissionBatchProtocolComplete: true`；
- `permissionApprovalRefreshRecovery: true`；
- `restartInteractionVerified: true`；
- `restartProjectionVerified: true`；
- `consoleErrors: 0`；
- `networkErrors: 0`；
- 最终汇总：`package=true`、`acceptanceUnitTests=true`、`backendRuntime=true`、`browserRuntime=true`。

另一次聚焦浏览器通过证据：runtime 目录 `labex-agent-browser-runtime-5b1e680ac03840f691e345fbbe9b95fa`，browser runId `f2a7b530dc2c4d9c81dd9c2476f5c681`。

## 7. 文件范围

### 后端

- `backend/src/main/java/com/labex/labexagent/controller/AgentTaskEventController.java`
- `backend/src/main/java/com/labex/labexagent/llm/AcceptanceScriptedProvider.java`
- `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- `backend/src/test/java/com/labex/labexagent/controller/AgentTaskEventControllerTest.java`
- `backend/src/test/java/com/labex/labexagent/llm/AcceptanceScriptedProviderTest.java`
- `backend/src/test/java/com/labex/labexagent/runtime/AgentLoopEngineCommandIdentityTest.java`

### 前端与验收

- `frontend/src/composables/agentCommandApprovalState.js`
- `frontend/src/composables/agentCommandApprovalState.test.mjs`
- `frontend/src/composables/agentHistoryReducer.js`
- `frontend/src/composables/agentHistoryReducer.test.mjs`
- `frontend/src/composables/useAgentTaskRuntime.js`
- `frontend/src/composables/useAgentTaskRuntime.test.mjs`
- `frontend/src/views/CloudWorkspace.vue`
- `frontend/src/views/agentRefreshRecovery.test.mjs`
- `frontend/src/components/cloud/ToolCallCard.vue`
- `frontend/scripts/acceptance/agent-browser.mjs`

### 文档

- `docs/iterations/2026-08-03-iteration-61-command-approval-identity-convergence.md`

## 8. 未纳入本轮的原有工作区改动

以下内容保持原样，不暂存、不提交：

- `backend/src/main/resources/application-acceptance.yml`；
- `.codex-tmp/`；
- `docs/agent-context-provider-smoke-test.md`；
- `docs/coding-agent-engineering-roadmap.md`；
- `docs/coding-agent-industrialization/`；
- 未跟踪的 `docs/superpowers/plans/*` 与 `docs/superpowers/specs/`。

## 9. 后续

第 62 轮优先审计 `AgentRunLifecycleService.recordEventPartBestEffort`：当前运行事件和 transcript Message/Part 的投影失败可能被吞掉，存在“实时 AgentRunEvent 可见，但刷新或 Provider 重建缺失 transcript”的风险。下一轮应先写失败注入测试，再决定事务原子化、可重放 repair 或显式 projection-failed 状态。