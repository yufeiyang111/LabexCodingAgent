# 第 49 轮：可恢复等待态与终态事件边界

## 背景

架构收敛计划规定：`waiting_approval`、`waiting_user`、`waiting_workspace`、`waiting_environment`、`retry_backoff` 都是可恢复等待态，不能发送伪 `FINAL/DONE`。只有 `completed`、`failed`、`cancelled` 等真实终态才能结束运行语义；SSE 连接关闭只代表当前传输结束。

第 48 轮封闭内部推理标签后，继续审计等待态链路发现：工作区等待已经使用 `WORKSPACE_WAITING + TASK_PAUSED` 并只关闭 HTTP transport，但环境阻塞和上下文硬门禁仍沿用旧的“生成停止报告”路径。

## 当前证据与根因

### 1. `waiting_environment` 后仍写入终态命名事件

`AgentLoopEngine.stopForEnvironmentBlocker(...)` 当前顺序是：

1. `AgentTaskService.waitForEnvironment(...)` 把权威任务状态迁移为 `waiting_environment`；
2. 持久化 `ENVIRONMENT_BLOCKED` 和 checkpoint；
3. 调用 `streamFinal(...)` 持久化 `FINAL`；
4. 持久化 `DONE`；
5. 完成当前 `SseEmitter`。

`stopForContextLimit(...)` 使用同样的错误顺序。由于 `AgentSsePublisher.send(...)` 会通过 `AgentRunLifecycleService.appendEvent(...)` 写入权威事件，`FINAL/DONE` 不是临时 UI 文本，而是会被刷新重放的持久事件；事件当时的 task state 又仍是 `waiting_environment`，形成“事件叫终态、状态却可恢复”的自相矛盾协议。

### 2. 前端把旧伪终态当成正常最终输出

- 实时 reducer 在 `ENVIRONMENT_BLOCKED` 后设置阻塞正文，但随后的 `FINAL` 会再次覆盖正文；
- `DONE` 会执行普通完成清理，无法区分“transport paused”和“run terminal”；
- 历史 reducer 同样会在刷新时用旧 `FINAL` 覆盖阻塞投影；
- 恢复出的 assistant message 没有显式保存 task 的 `runState`，旧数据兼容判断缺少权威状态。

因此后端停止新增伪事件还不够，前端必须能安全读取已有数据库中的旧事件。

### 3. 环境恢复后的成功验证不能覆盖同命令历史失败

`RunTestsTool` 会为每次验证写入 `t_agent_verification`。当前 `RunCompletionEvidenceService` 把任务下所有历史失败都加入 `failedVerifications`。同一命令第一次因 DNS 失败、环境恢复后第二次成功时，第一次失败仍永久阻止完成。

正确语义应是：

- 所有验证记录继续保留，满足审计要求；
- 完成证据按规范化命令读取最新结果；
- 同一命令“失败 → 成功”以最新成功作为当前判定；
- 不同命令的失败不能被无关成功掩盖。

### 4. OpenCode 对照

`D:\opencode\opencode-dev\packages\opencode\src\session\status.ts` 把运行状态建模为 `busy / retry / idle`；`session/processor.ts` 在可重试错误时发布 `retry` 状态和重试事件，不创建 final text part 来假装本轮已经完成。LabexAgent 应遵循相同原则：等待/重试是状态事件，最终回答是终态产物，两者不能混用。

## 本轮计划

1. 先增加失败回归，锁定环境阻塞和上下文阻塞不得调用 `streamFinal` 或发送 `DONE`；
2. 增加实时与历史 reducer 回归，证明旧 `ENVIRONMENT_BLOCKED -> FINAL -> DONE` 不得覆盖等待态；
3. 增加完成证据回归，证明同一验证命令后续成功可 supersede 旧失败；
4. 统一三个可恢复等待出口，通过 `TASK_PAUSED` 结束当前 transport；
5. 为等待事件补齐 `taskStatus`、`reason`、`resumeAgentLoop`、`manualRetryRequired`；
6. 前端从任务快照和等待事件保存 `runState`，并忽略等待态中的旧伪 `FINAL/DONE`；
7. acceptance scripted Provider 增加确定性的“首次 DNS 失败、重试后成功”场景；
8. 真实浏览器验证首次等待无 `FINAL/DONE`、刷新仍显示重试 UI、恢复沿用原 task、最终只出现一组真实 `FINAL/DONE`；
9. 运行聚焦测试、全量后端、全量前端、构建、JAR 和真实浏览器系统验收；
10. 审查并创建聚焦本地 Git 提交。

## 验收标准

- 环境/DNS 阻塞与上下文硬门禁进入 `waiting_environment` 后不写入 `FINAL` 或 `DONE`；
- 当前 HTTP/SSE transport 通过 `TASK_PAUSED` 正常结束，不把连接生命周期当任务终态；
- `TASK_PAUSED` 明确携带 taskId、taskStatus、reason、是否自动恢复和是否需要手动重试；
- 实时 UI 和刷新回放都保留环境/上下文阻塞卡片，不被旧伪终态覆盖；
- 环境重试继续原 task、原 conversation 和原 transcript，不创建新任务；
- 同一验证命令失败后重跑成功时，历史失败仍留库，但当前 completion evidence 不再报告该失败；
- 不同命令的失败仍然阻止完成；
- 恢复成功后只产生一组真实 `FINAL/DONE`，且发生在 resume transition 和成功验证之后；
- 浏览器刷新、控制台、网络请求和任务事件序列均通过系统级检查。

## 实施与验收记录

### 1. 后端：可恢复等待态不再伪装成终态

修改 `AgentLoopEngine`：

- `stopForEnvironmentBlocker(...)` 改为持久化 `ENVIRONMENT_BLOCKED` 与 `TASK_PAUSED`，随后只关闭当前 `SseEmitter`；
- `stopForContextLimit(...)` 使用相同 transport pause 语义；
- 两类等待事件都显式携带 `taskId`、`taskStatus=waiting_environment`、`reason`、`resumeAgentLoop=false` 和 `manualRetryRequired=true`；
- 删除这两个出口中的 `streamFinal(...)` 和 `DONE`，避免刷新后重放出伪终态。

真实浏览器验收确认：首次环境阻塞的事件序列包含 `ENVIRONMENT_BLOCKED`、`TASK_PAUSED`，不包含 `FINAL` 或 `DONE`；手动恢复后仍使用原 task，最终只产生一组真实 `FINAL/DONE`。

### 2. 后端：阻塞前封闭 Provider tool-call 协议批次

第一次浏览器恢复测试暴露出新的协议错误：环境等待虽然正确暂停，但恢复时 durable transcript projector 报告 Provider tool call 不可恢复。根因是同一 assistant turn 已持久化 tool calls，环境阻塞分支却没有持久化对应 `role=tool` 结果。

修复方式：

- 当前阻塞工具先写入真实、压缩后的 tool result；
- 同一 assistant turn 中尚未执行的后续工具显式写入 skipped tool result；
- 所有 tool result 都保留原始 `toolCallId` 和工具名；
- 完整关闭协议批次后才迁移到 `waiting_environment`。

这样恢复 projector 不再需要猜测或制造孤儿结果，符合“一次 assistant tool batch 必须有确定终态”的架构不变量。

### 3. 后端：恢复后的成功验证覆盖同命令旧失败

`RunCompletionEvidenceService` 现在先按规范化命令选择最新 `AgentVerification`：

- 同一命令的后续成功覆盖旧失败，旧记录仍保留在数据库中用于审计；
- 不同命令分别判断，不能用无关成功掩盖失败；
- 当 `run_tests` 的历史失败工件已经被后续权威成功验证覆盖时，该工件继续保留，但不再永久阻塞 completion evidence；
- 其他工具失败仍然是未恢复风险并阻止任务声称完成。

浏览器系统验收验证了“第一次依赖解析失败、恢复后同一测试命令成功”的场景，最终 completion evidence 为 satisfied，且 failed verifications 为空。

### 4. 后端：一次性命令审批必须先落 transcript 再恢复

系统验收还发现审批 continuation 存在竞争窗口：审批已 consumed 不等于 Provider 可安全续跑；若 scheduler 先恢复，原始 tool call 的最终结果可能尚未进入持久化 transcript。

本轮收敛为：

1. `CommandApprovalOrchestrator` 先调用 `AgentRunTranscriptService.appendDeferredToolResult(...)`；
2. 再完成 durable tool-call journal；
3. `CommandApprovalResumeScheduler` 在领取 lease 前检查带有明确 resolution marker 的最终 tool result；
4. 未准备好时返回 `DEFERRED_TOOL_RESULT`，由 durable scheduler 后续重试，不抢跑；
5. 轮询查询通过 `CommandApprovalMapper` 联表限定为 `task.status=waiting_approval` 的已解决 `agent_shell` 审批，避免反复扫描所有历史记录；
6. 重复追加同一最终结果保持幂等，已有未标记占位结果会被原位替换，而不是产生第二个 tool result。

后端重启验收分别覆盖批准和拒绝路径，证明审批卡、原 `toolCallId`、原 task 与 continuation 在 JVM 重启后仍可恢复。

### 5. 前端：等待态恢复时重新进入活动投影

真实浏览器验收曾出现“后端 task 已 completed，但页面仍停在等待环境”的问题。根因不是后端没有最终答案，而是前端 `assistantMsg.runState` 一直保留 `waiting_environment`，于是兼容旧伪终态的保护逻辑把恢复后的真实 `FINAL` 也过滤掉了。

修复包括：

- 新增 `agentRunState.js`，统一终态和可恢复等待态判断；
- 实时 reducer 消费 `RUN_ENVIRONMENT_RESUME`、`RUN_WORKSPACE_RESUME`、活动 `RUN_STATE_*` 和交互恢复事件时，将旧等待态切回 active state，并清理对应 blocker；
- 实时与历史 reducer 在等待态中忽略旧数据库里的伪 `FINAL/DONE`，但恢复事件后允许真实终态更新；
- `useAgentTaskRuntime` 保存任务快照中的 `runState`；
- 若任务已经终态但订阅期间没有收到可用 `FINAL`，强制从 durable conversation history 重载，避免事件连接边缘竞态导致页面永久缺少最终答案；
- conversation ownership 和 generation guard 继续阻止旧会话异步结果污染新会话。

浏览器刷新、等待卡恢复、同 task 重试、最终回答和 completion evidence 均通过真实页面检查。

### 6. 系统验收基础设施：隔离数据库而不是复用开发实例

早期验收会受到本机旧 JVM、开发 MySQL 数据和残留任务影响，导致结果不可重复。两个 acceptance PowerShell 入口现在都会：

- 从本轮打包 JAR 中提取同版本 H2 runtime；
- 启动脚本独占的 H2 TCP server，使后端强制重启后仍使用同一持久数据库；
- 仅把 schema 中 MySQL 专用的前缀索引 `pattern(191)` 转换为 H2 可执行语法，不维护第二份 schema；
- 使用随机端口、随机 workspace 和精确 PID；
- finally 中只停止脚本拥有的进程并删除脚本拥有的 workspace；
- 不接管用户正在运行的开发端口或数据库。

这使“后端重启后审批/问题/回放仍可恢复”成为可重复系统验证，而不是依赖当前机器偶然状态。

## 实施过程中发现的连锁根因

1. 最初使用 `Unknown host` 制造环境失败时，被网络审批策略优先拦截，实际进入的是网络批准等待而不是 `waiting_environment`。验收场景改为确定性的 parent POM 依赖解析失败，既能触发环境分类器，又不会访问网络或落入网络审批。
2. 去掉伪 `FINAL/DONE` 后，恢复 projector 暴露了未闭合 tool batch；这说明旧终态事件此前只是掩盖了 transcript 协议缺口。
3. tool batch 修复后，任务仍无法完成，继续暴露 completion evidence 把历史失败永久化的问题。
4. completion evidence 修复后，后端已完成但前端仍不显示最终回答，最终定位到等待态没有在 resume 事件上清除。
5. 审批重启场景进一步暴露 consumed 状态与最终 tool result 之间的竞态，因此补上 transcript-first gate 和窄化恢复查询。

本轮没有用一个 fallback 掩盖所有错误，而是沿着权威状态、持久化 transcript、完成证据和前端投影逐层封闭协议。

## 验证证据

### 后端全量测试

命令：

```powershell
mvn -q -f backend/pom.xml test
```

结果：227 份 Surefire 报告，共 891 个测试；0 failures、0 errors、8 skipped。日志中的部分 ERROR/WARN 来自故意覆盖失败路径的测试，不是测试失败。

### 前端全量测试与 acceptance 单测

命令：

```powershell
cd frontend
npm.cmd test
npm.cmd run test:acceptance:unit
```

结果：

- 前端全量：192/192 passed；
- acceptance 单测：10/10 passed；
- source encoding 回归：1/1 passed，不存在用户可见的连续 ASCII 问号乱码。

### 后端真实重启系统验收

命令：

```powershell
& D:\LabexAgent\scripts\acceptance\agent-runtime.ps1 -BackendPort 18080 -TimeoutSeconds 180
```

结果：exit code 0。通过项包括：

- question restart；
- permission restart；
- command approve/reject restart；
- checkout contention；
- run message/part projection；
- tool part authority；
- manual compaction；
- static context blocked；
- context window unconfigured；
- completion evidence；
- unverified edit rejected；
- normal profile provider isolation；
- isolated database；
- script-owned cleanup。

### 真实浏览器系统验收

命令：

```powershell
& D:\LabexAgent\scripts\acceptance\browser-runtime.ps1 `
  -BackendPort 18080 -FrontendPort 13000 -CdpPort 19222 `
  -TimeoutSeconds 180 -RestartBackendForAcceptance
```

结果：exit code 0。关键证据：

- desktop layout、conversation isolation、refresh replay deduplication 通过；
- 内部 reasoning 协议标签未暴露；
- question reply 与 permission approval 刷新恢复通过；
- multi-tool permission batch 协议完整；
- durable/manual compaction 与 fork 刷新恢复通过；
- Provider stream interruption 恢复通过；
- 后端重启后的 projection、manual fork、interaction 恢复通过；
- 环境首次等待没有伪 `FINAL/DONE`；
- 环境恢复沿用原 task（task id 11），最终 `FINAL=1`、`DONE=1`；
- 环境恢复 completion evidence satisfied；
- console errors=0、network errors=0；
- 强制重启期间 3 个预期 transport errors 由验收策略明确识别，重连后没有遗留错误。

### 生产构建与 JAR

命令：

```powershell
cd frontend
npm.cmd run build
cd ..
mvn -q -f backend/pom.xml -DskipTests package
```

结果：

- Vite production build 通过；主要 chunk 均在仓库预算内；
- Rollup 只报告第三方 `@vueuse/core` PURE 注释位置提示，不影响构建；
- `backend/target/labex-agent-backend-1.0.0.jar` 成功生成；
- JAR 内确认包含 `AgentLoopEngine`、`RunCompletionEvidenceService` 和 `CommandApprovalResumeScheduler` 的本轮 class。

## 验收结论与边界

本轮验收标准全部满足：等待态、持久化 Provider transcript、审批恢复、完成证据和前端实时/刷新投影已经形成闭环，并由真实后端重启与真实浏览器流程证明。

验证边界：浏览器系统验收使用仓库内确定性的 acceptance Provider，能够证明 LabexAgent 自身的状态机、SSE、数据库、审批和 UI 协议，但不等于验证任意外部云模型服务的网络稳定性。外部 Provider 的 TLS、代理和上游 stream 行为仍应由独立 provider smoke 验证覆盖。