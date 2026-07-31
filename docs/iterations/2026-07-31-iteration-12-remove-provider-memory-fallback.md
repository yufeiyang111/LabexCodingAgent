# 第 12 轮：删除 Provider 内存 transcript 回退路径

- 日期：2026-07-31
- 分支：`codex/agent-tool-reliability`
- 对应计划：`docs/superpowers/plans/2026-07-29-agent-runtime-state-convergence.md` 的任务四、任务八、任务九
- 状态：完成

## 本轮计划

1. 用失败回归测试固定：Provider 预算/请求缺少 task 身份或 durable projector 时必须失败，不得返回内存 `msgs`。
2. 删除 `AgentTranscriptProjectionService` 中已经退出生产调用链的 memory shadow/fallback API 和 divergence 兼容异常。
3. `AgentLoopEngine` 恢复 transcript 时必须经过 durable projector；读取失败不得为新旧任务静默重建另一套历史。
4. interaction resume 必须直接使用 durable transcript service 生成结果，不保留 null fallback。
5. 运行聚焦后端测试、完整后端测试、前端门禁、真实 Chromium 场景和 compaction 后跨 JVM handoff。
6. 记录源码、测试和 live 证据后创建本地 Git 提交。

## 退出条件

- Provider 请求路径不存在 `projectForProvider(taskId, inMemoryMessages)`；
- Provider 预算路径不再接收或返回内存历史；
- `Source.MEMORY`、`shadowMismatch` 和只服务旧双写阶段的 divergence exception 被删除；
- transcript/projector 缺失或读取异常时任务明确失败，不会继续调用 Provider；
- durable compaction、审批恢复和普通浏览器验收没有回归。

## 红灯记录

先修改测试、不修改生产代码，执行：

```powershell
cd D:\LabexAgent\backend
mvn -q '-Dtest=AgentLoopEngineContextBudgetTest,AgentTranscriptProjectionServiceTest' test
```

得到 3 个预期失败：

1. `usesTheDurableProviderProjectionForBudgetAndAdmissionInputs`：`taskId=null` 时没有抛出异常，而是返回内存消息；
2. `refusesProviderBudgetProjectionWhenDurableProjectorIsUnavailable`：projector 缺失时没有抛出异常，而是返回内存消息；
3. `doesNotExposeTheRetiredMemoryFallbackProjectionApi`：反射仍找到 `project` 和 `projectForProvider`。

这证明测试捕获的是本轮要删除的真实兼容路径，而不是无关编译错误。

## 实施内容

### 1. 删除 memory shadow/fallback API

`AgentTranscriptProjectionService` 删除：

- `project(taskId, inMemoryMessages)`；
- `projectForProvider(taskId, inMemoryMessages)`；
- `Source.MEMORY` / `Source.DURABLE`；
- `shadowMismatch`；
- `AgentTranscriptProjectionDivergenceException`。

服务现在只暴露：

- `loadProviderMessages(taskId)`；
- `loadDurableProjection(taskId)`；
- `loadDurableProjectionForInteractionResume(taskId)`。

三者都只从 `AgentRunTranscriptService` 和最新 completed compaction epoch 读取。`taskId` 缺失或 transcript 为空时明确失败。

### 2. Provider 预算与请求使用同一 durable 投影

`AgentLoopEngine.providerMessagesForBudget` 删除 `inMemoryMessages` 参数。它现在：

1. 校验正数 `taskId`；
2. 要求 `AgentTranscriptProjectionService` 已注入；
3. 调用 `loadProviderMessages(taskId)`；
4. 不存在任何返回 `msgs` 的 fallback。

静态 admission、主动压缩前预算、裁剪后预算和最终 Provider request 都由同一 durable projector 产生。

### 3. transcript 恢复失败关闭

- `AgentRunTranscriptService` 和 `AgentTranscriptProjectionService` 从可选注入改为运行时必需注入；
- `TranscriptMessageList` 初始化 sequence 和 append 必须通过 durable transcript service；
- 新任务允许 durable transcript 为空，然后把初始上下文与用户请求追加为首批持久事实；
- durable projector 读取异常会终止当前运行并进入现有失败收敛，不再警告后用内存重新造历史；
- interaction resume 结果必须由 durable transcript service 生成。

`TranscriptMessageList` 暂时保留为本轮内的写入工作集和 compaction 投影容器，但不再是 Provider 的读取回退源。后续仍要把其 mutation/append 责任进一步迁移出 `AgentLoopEngine`。

### 4. 浏览器验收稳定性

真实验收第一次在刷新后切换 `acceptance-tiny` 时，发送静态上下文消息失败；页面无 console/network error，后端也没有收到新请求。

根因是 `sendMessage` 只检查“存在任意 `.ai-submit-btn`”，而恢复窗口内该按钮可能仍是停止按钮，或 composer 状态刚好正在切换。修复验收驱动后：

- textarea 必须启用；
- submit 必须存在且启用；
- 页面不能有 generating indicator；
- composer 状态需连续稳定 350ms；
- Enter 失败后重新校验 composer，再走真实 pointer/mouse/click 事件链。

这没有绕过 Vue 内部方法，仍然验证用户实际可操作的输入组件。

## 验证记录

### 聚焦后端回归

```powershell
cd D:\LabexAgent\backend
mvn -q '-Dtest=AgentLoopEngineContextBudgetTest,AgentTranscriptProjectionServiceTest,AgentLoopEngineStreamingContractTest,AgentLoopEngineDurableCompactionWiringTest' test
```

结果：通过。

随后包含 transcript、compaction 与 interaction 的扩展聚焦测试通过。

### 后端完整门禁

```powershell
cd D:\LabexAgent\backend
mvn -q test
```

第一次运行：`802` 个测试中仅旧源码 wiring 断言失败；它仍要求直接出现 `transcriptProjectionService.loadDurableProjection(...)`，与新的 required projector 局部变量不一致。

更新该架构断言，保持其真实不变量（必须经过 compaction-aware durable projector、projection replacement 不追加事实、compaction start/complete/fail 完整）后重跑：通过。

### 前端门禁

```powershell
cd D:\LabexAgent\frontend
npm.cmd test
npm.cmd run build
npm.cmd run test:acceptance:unit
```

结果：

- 前端测试：`160/160` 通过；
- Vite production build：通过；
- `CloudWorkspace`：`1,449,183 / 1,500,000` bytes；
- `index`：`1,259,534 / 1,300,000` bytes；
- `TerminalPanel`：`380,367 / 400,000` bytes；
- acceptance unit：`9/9` 通过。

### 普通真实 Chromium 系统验收

运行拓扑：

- Vite：`127.0.0.1:13002`；
- Spring Boot：`127.0.0.1:18081`；
- JVM PID：`51188`；
- JVM 启动时间：`2026-07-31 17:03:59`；
- classpath：`D:\LabexAgent\backend\target\classes`；
- Chromium viewport：`1440x900`。

第二次运行结果：

- `desktopLayout=true`；
- `conversationIsolation=true`；
- `refreshReplayDeduplicated=true`；
- `questionReplyComponent=true`；
- `permissionApprovalRefreshRecovery=true`；
- `multiToolPermissionBatchProtocolComplete=true`；
- `durableCompaction=true`；
- `compactionEpoch=1`；
- `compactionProviderMessages=7`；
- `providerStreamInterruptionHandled=true`；
- `staticContextBlockerCard=true`；
- `completionEvidenceCard=true`；
- `unverifiedCompletionBlocked=true`；
- console error `0`；
- network error `0`。

### compaction 后真实 JVM restart projection

验收目录：

`D:\LabexAgent\.codex-tmp\iteration12-restart-projection-20260731-1720`

持久身份：

- projectId：`168`；
- taskId：`964`；
- conversationId：`24f3ece2-241f-4bfe-9ed1-f7b2f4641b2b`；
- compactionEpoch：`1`。

过程：

1. 浏览器完成 durable compaction 并写入 `ready.json`；
2. 停止旧 JVM PID `51188`；
3. 启动新 JVM PID `24152`，启动时间 `2026-07-31 17:16:51`，classpath 指向 `D:\LabexAgent\backend\target\classes`；
4. 写入 `continue.signal`；
5. 浏览器从新 JVM 查询同一个 task projection；
6. compaction epoch 和 Provider message 数量与重启前一致；
7. 后续断流、静态上下文、完成证据场景继续完成。

最终 `done.json`：

- `restartProjectionVerified=true`；
- `durableCompaction=true`；
- `compactionProviderMessages=7`；
- `consoleErrors=0`；
- `networkErrors=0`。

外层 PowerShell wrapper 最后因为重定向后的 `Start-Process.ExitCode` 读取为空而报告非零，但浏览器 Node 进程已经正常生成完整 `done.json`，且 `browser.err.log` 为空。验收结论以 durable `done.json`、新 JVM PID 和页面场景结果为准；wrapper 本身不属于产品代码。

### 源码完整性

```powershell
cd D:\LabexAgent
git diff --check
```

结果：通过。

源代码搜索确认生产代码中不存在：

- `projectForProvider(`；
- `Source.MEMORY`；
- `shadowMismatch(`；
- `return inMemoryMessages`；
- `List<Map<String, Object>> inMemoryMessages`。

## 保留的原工作区改动

以下内容不属于本轮，不纳入提交：

- `backend/src/main/resources/application-acceptance.yml`；
- `.codex-tmp/`；
- `docs/agent-context-provider-smoke-test.md`；
- `docs/coding-agent-engineering-roadmap.md`；
- `docs/coding-agent-industrialization/`；
- 既有未跟踪的 `docs/superpowers/plans/` 和 `docs/superpowers/specs/`。

## 剩余风险与下一轮

1. `TranscriptMessageList` 仍兼具运行工作集和 durable append adapter 职责，应继续迁移到显式 transcript writer/turn transaction。
2. `AgentConversation.summary` 与 durable compaction record 仍是两套摘要写入，需要下一轮审计读写方向并删除旧 summary 权威路径。
3. 前端 assistant message 内仍保留兼容 `toolCalls` 数组，需确认所有创建/覆盖路径后降级为 reducer-only view model。
4. 混合版本 worker 仍缺少 runtime compatibility fencing；旧 acceptance JVM 抢任务的问题尚未做架构级隔离。
