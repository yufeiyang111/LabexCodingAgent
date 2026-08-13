# 第 82 轮：OpenCode-first 真实 Shell 基线与可用性收敛

## 1. 本轮目标

本轮根据 `docs/superpowers/plans/2026-08-12-opencode-first-agent-usability/` 三件套开始实施，第一停止边界是打通真实 Shell Tool 的协议：模型可以把完整 Shell 字符串交给 Worker，在正确的 `workdir` 中执行，并保留可验证的进程结果。

本轮不重写 `AgentLoopEngine`、不清理历史工作树、不删除既有权限/审批持久化实现；先用一个垂直切片证明 `cd frontend&&npm install`、引号、空格路径、管道、重定向和变量不会被 direct-command tokenizer 错误拆解或拒绝。对应模块实现前必须参考本地 OpenCode 源码，并在此记录复刻点。

## 2. 工作树基线（2026-08-12）

- 分支：`codex/agent-tool-reliability`
- HEAD：`aae859717afe67b87a22e7e88ffdffe089b0be94`
- 工作树类型：普通 checkout；`git rev-parse --git-dir` 与 common dir 均为 `D:/LabexAgent/.git`，本轮没有创建新的 worktree。
- `git status --short` 基线：217 项（其中已跟踪改动 135 项、未跟踪项 82 项；未用 `git clean` 或 reset 清理）。仓库中另有被忽略的 workspace/runtime 产物，不作为本轮改动范围。
- `git diff --stat` 基线：134 个已跟踪文件，约 8928 行新增、810 行删除；这些既存改动全部保留。
- 既存命令实现仍以 `DirectCommandTokenizer` 为执行前提；`cd frontend&&npm install`、管道、重定向、引号和变量在进入 Worker 前会被拒绝或错误转换。
- 当前 focused 基线命令已通过：

```powershell
Set-Location D:\LabexAgent\backend
mvn -q '-Dtest=RunCommandToolTest,RunCommandToolWorkingDirectoryTest,CommandSecurityTest,DirectCommandTokenizerTest,DirectCommandWorkingDirectoryTest,ToolArgumentSchemaValidatorTest,WslSandboxWorkerTest,DockerSandboxWorkerSmokeTest' test
```

- 当前尚未验证：真实 Agent Loop → WSL Shell → fixture 修复 → SSE/浏览器闭环；Linux Web 多用户 Docker 部署也不因本轮源码测试而自动视为已验证。

## 3. OpenCode 参考与复刻边界

本轮已阅读：

- `D:\opencode\opencode-dev\packages\opencode\src\tool\shell.ts`
- `D:\opencode\opencode-dev\packages\opencode\src\tool\shell\prompt.ts`
- `D:\opencode\opencode-dev\packages\opencode\src\shell\shell.ts`

复刻点：

1. Tool 参数以完整 `command` 字符串为核心，不把 Shell 语法预先拆成 argv。
2. `workdir`、超时和简短 `description` 是独立参数；命令交给实际 Shell 的单个 payload。
3. Windows 根据可用 Shell 选择 PowerShell/Git Bash/CMD；WSL/Docker 使用 Linux Bash descriptor。
4. 权限匹配与执行解耦，权限应在接近执行的位置做一次决策，不能在 Tool 内创建未持久化审批。
5. 输出截断与完整 artifact 的设计参考 `packages/opencode/src/tool/truncate.ts`，后续 T1.4 单独实施。

许可证：本地参考快照 `D:\opencode\opencode-dev\LICENSE` 为 MIT。若后续复制实质代码而非仅复刻设计，必须保留相应版权和许可证声明；本轮优先采用独立 Java 实现并保留上述参考记录。

## 4. Initial Planning Status (superseded by final update)

| 阶段 | 状态 | 证据 |
|---|---|---|
| T0.1 基线记录 | verified | 本文第 2 节及 focused 基线命令 |
| T0.2 Shell fixture | in_progress | fixture 文件已建立；初始测试应明确失败 |
| T1.1 Shell Tool 红灯契约 | in_progress | `ShellToolContractTest` 已先于生产实现写入 |
| T1.2 Worker descriptor/factory | pending | 尚未修改生产 Worker |
| T1.3 真实 ShellTool | pending | 尚未切换 `RunCommandTool` |
| T1.4 结构化结果/artifact | pending | 不在本轮首个红绿循环内 |
| T1.5 单一 Permission Gate | pending | 仍需在真实 Shell 契约稳定后实施 |
| T1.6 动态系统提示词 | pending | 仍需接入 Worker descriptor |
| T1.7 WSL smoke | pending | 必须在生产实现变绿后执行 |

## 5. 风险与停止边界

- 本轮不会把 `full_access` 能力直接开放给 Linux 宿主机；Linux Web 仍必须走 Docker Worker，Windows 本地优先走 WSL Worker。
- 旧审批恢复、durable transcript、SSE 回放等既存改动不是本轮重构对象；若 focused 回归暴露冲突，记录为阻塞并按最小兼容方式处理。
- 任何测试通过只证明源码/测试路径；只有 WSL/Docker 真实进程和浏览器 acceptance 通过，才能声称“可实际运行”。

## Final Update (2026-08-12)

**Status: Phase 1 verified; Windows WSL execution layer is a local candidate; Linux Web is unverified.**

### Completed scope

| Task | Status | Evidence |
|---|---|---|
| T0.1 baseline | verified | Branch/HEAD/dirty-worktree baseline recorded; no reset/clean/delete of unrelated work |
| T0.2 fixture | verified for WSL | Fixture begins with a failing Node test, then passes after real source repair/retry |
| T1.1 Shell contract | verified | command/workdir/timeout/description schema and no-tokenizer contract tests |
| T1.2 descriptor/factory | verified | WSL/Docker/Local descriptors and complete payload factory |
| T1.3 real Shell tool | verified | shell Tool executes complete command through Worker; legacy aliases map once |
| T1.4 result/artifact | verified | structured status/exit/duration/truncation/artifact with durable index |
| T1.5 permission gate | verified | ordinary workspace commands allowed; destructive boundary still asks/denies |
| T1.6 dynamic prompt | verified | actual Shell/workspace/network/profile is injected |
| T1.7 WSL smoke | verified | real dependency install/build/Maven test/repair/retry/large artifact/cancel coverage |

### OpenCode design references

- D:/opencode/opencode-dev/packages/opencode/src/tool/shell.ts
- D:/opencode/opencode-dev/packages/opencode/src/tool/shell/prompt.ts
- D:/opencode/opencode-dev/packages/opencode/src/shell/shell.ts
- D:/opencode/opencode-dev/packages/opencode/src/tool/truncate.ts
- D:/opencode/opencode-dev/packages/opencode/src/agent/agent.ts
- D:/opencode/opencode-dev/packages/opencode/src/permission/index.ts
- D:/opencode/opencode-dev/packages/opencode/src/session/prompt.ts
- D:/opencode/opencode-dev/packages/opencode/src/session/processor.ts
- D:/opencode/opencode-dev/packages/opencode/src/session/compaction.ts
- D:/opencode/opencode-dev/packages/app/src/context/global-sync/event-reducer.ts

The implementation follows invariants (complete Shell payload, ordered permission decision, durable Parts/events, retained context/artifact evidence) and does not copy substantive OpenCode source. The local OpenCode snapshot is MIT-licensed.

### Verification evidence

1. **Backend focused:** Shell/tool/permission/artifact/context/acceptance tests passed.
2. **WSL real fixture:** executed `cd frontend&&npm install`, frontend build, Maven test, quote/pipeline/redirection/variable semantics, large output artifact, initial failure, source repair, and retry success.
3. **Agent runtime:** `powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File ./scripts/acceptance/agent-runtime.ps1 -TimeoutSeconds 180` passed with run id `f5744fefaf604cec9d081bdee3495845`.
4. **Frontend:** 231/231 tests passed; Vite production build and chunk budget passed.
5. **Docker/Linux/browser:** not verified. `docker version` failed to connect to `dockerDesktopLinuxEngine`; no Linux production or browser acceptance was run.

### Next task

1. T2.1: route RunTestsTool, managed terminal, and post-edit verification through the shared Shell executor/result model.
2. T2.2: complete normalized frontend event/result/artifact rendering and refresh/SSE replay evidence.
3. T2.3/T2.4: reconcile durable transcript/compaction/completion evidence documentation and focused gates.
4. T3: perform real Linux Docker production smoke before any Linux Web Ready claim.

## T2.1 Update (2026-08-13)

**Status: verified for the shared Shell execution slice; PTY WebSocket still disabled; browser/Docker/Linux unverified.**

### What was reviewed and closed

T2.1 的实现路径已在上一轮落地，本轮完成审查、补缺口和真实 WSL 复验：

1. **RunTestsTool** 只通过 `TestCommandResolver` 解析服务端验证策略（Maven → `mvn test`、Node → `npm run build`、Python → `python -m pytest`），随后经 `ShellCommandFactory.renderArguments(...)` 转为单一 Shell payload，由 `WorkerShellExecutor` 执行；模型不能自定义 run_tests command，schema 没有 `command` 字段。
2. **REST managed terminal**（`ProjectTerminalService`）默认 profile 与 Agent shell 复用同一 `WorkerShellExecutor`；`safe` profile 才走 `DirectCommandTokenizer` 兼容路径。
3. **Controller**（`StudentProjectController`）终端命令 classification 使用与执行一致的 shell 名称（`WorkerShellExecutor.descriptor(...).shellName()`，safe profile 为 `direct`），approval 记录保存同一 shell；execute 时使用保存的 canonical command / workdir。
4. **前端** `terminalRunSession` 接受 `{ path, timeoutSeconds, longRunning }`；`normalizeManagedTerminalResult` 识别 `running/succeeded/failed/cancelled`；WebSocket helper 保持禁用。

### Fixed in this iteration

`D:\LabexAgent\backend\src\main\java\com\labex\service\ProjectTerminalService.java`：

- long-running 进程收敛时 `TerminalExecution` 现在携带真实 `durationMs` 与 `outputChars`（此前保持 0）。
- `stop()` 先写 `cancelled` 状态再 `terminate()`，消除 reader 线程收敛与 stop 的竞态覆盖；空闲会话 stop 不再把上次成功结果的 exitCode 清成 -1。
- `running/exitCode/lastExecution/outputCharCount` 标记为 `volatile`，保证 reader 线程与请求线程可见性。

`D:\LabexAgent\backend\src\test\java\com\labex\service\ProjectTerminalServiceWorkerTest.java` 新增 4 个回归测试：

- `longRunningTerminalConvergesToSucceededWithDurationAndOutputChars`
- `longRunningTerminalConvergesToFailedWithNonZeroExitCode`
- `stopKeepsCancelledStatusEvenAfterTheOutputReaderConverges`
- `stopOnIdleSessionDoesNotClobberThePreviousResult`

### Verification evidence (2026-08-13)

1. **Backend focused:** `ShellCommandFactoryTest,RunCommandToolTest,RunTestsToolTest,ProjectTerminalServiceWorkerTest,RunTestsToolWiringTest` 全 PASS（其中 ProjectTerminalServiceWorkerTest 7 tests）。
2. **Frontend protocol:** `node --test src/composables/terminalProtocol.test.mjs` PASS。
3. **Real WSL single-case acceptance:** `mvn -q '-Dlabex.wsl.smoke=true' '-Dtest=WslSandboxWorkerSmokeTest#executesRunTestsAndManagedTerminalThroughTheSameRealWslShell' test` PASS —— `RunTestsTool` 真实执行失败的 `'npm' 'test'`（`shell=bash`，exit=1），REST managed terminal 真实执行 `npm install && npm run build` 并输出 `frontend build passed`。
4. **Backend package:** `mvn -q -DskipTests package` PASS（jar 2026-08-13 02:10 生成）。
5. **Frontend full:** `npm test` 231/231 PASS；`npm run build` PASS（1m 4s）。
6. **质量检查:** `git diff --check` 无 whitespace error；修改文件 UTF-8、无 `???`。

### PowerShell note (root cause of the previous "full-suite" accident)

PowerShell 5.1 会把未加引号的 `-Dlabex.wsl.smoke=true` 错误拆词，导致 Maven 收到 `.wsl.smoke=true` 这个伪 lifecycle phase 或把 `-Dtest=...` 吞进前一个 property。正确写法是给每个 `-D` 参数加引号：

```powershell
mvn -q '-Dlabex.wsl.smoke=true' '-Dtest=WslSandboxWorkerSmokeTest#executesRunTestsAndManagedTerminalThroughTheSameRealWslShell' test
```

### OpenCode references for T2.1

- `D:\opencode\opencode-dev\packages\opencode\src\tool\shell.ts` —— 完整 command payload、workdir、timeout、取消、输出截断。
- `D:\opencode\opencode-dev\packages\opencode\src\pty-preparation.ts` —— shell/cwd/env 明确化与 PTY 生命周期（本轮只复刻 REST managed terminal 的结构化结果语义，未实现 PTY）。
- `D:\opencode\opencode-dev\packages\opencode\src\server\routes\instance\httpapi\handlers\pty.ts` —— 短期票据、Origin、session 校验（本轮未恢复 WebSocket PTY，仅作为后续设计依据）。

复刻为不变量：Shell 方言由 Worker descriptor 唯一描述；命令保持单一 payload；结构化状态收敛（running → succeeded/failed/cancelled）。未复制实质 TypeScript 源码，License 记录同上。

### T2.1 remaining gaps

- 交互式 WebSocket PTY 仍禁用，REST managed terminal 是当前唯一可用终端路径；发布前必须明确标注。
- 浏览器真实验收未执行。
- Linux Docker / production / reverse proxy 未验证（Docker daemon 不可用）。

### Next task

T2.2: 前端 normalized event reducer 与 Shell 结果卡片（含 refresh / SSE replay 证据）。

## T2.2 Update (2026-08-13)

**Status: verified for the normalized reducer/store slice; browser E2E remains open (T4.1).**

### OpenCode reference and replicated invariants

T2.2 实现前阅读了 `D:\opencode\opencode-dev\packages\app\src\context\global-sync\event-reducer.ts`，复刻以下不变量：

1. **稳定 ID 查找 + 幂等合并**：OpenCode 用 `Binary.search` 按 message.id / part.id / permission.id / question.id 查找，found 时 `reconcile` 就地合并，否则按序插入。LabexAgent 前端按 `taskId/toolCallId/interactionId/partKey/messageKey` 建立派生索引，`TOOL_CALL` 事件改为按 `toolCallId` 幂等合并，重复 event 不再重复建卡片。
2. **结构化状态优先于文本解析**：OpenCode 的 Part 携带结构化 `state.status`，前端不解析输出文本猜测状态。LabexAgent 后端 `sendObserve` 现在随 OBSERVE 事件下发 `executionStatus/executionExitCode/executionDurationMs`；`journalToolFinished` 按结构化状态写入 durable Part（`cancelled→interrupted`、`infrastructure_error→environment_blocked`、`timed_out→error`+文本，与 OpenCode 的 error+metadata/text 语义一致）。前端 `applyStructuredExecutionStatus` 投影 `timed_out→error(执行超时)`、`cancelled→interrupted(已取消)`、`infrastructure_error→warning`。
3. **replied 交互从等待集合收敛**：OpenCode 的 `permission.replied`/`question.replied` 会把请求从 waiting store 移除；LabexAgent 的 `resolveDurableInteraction`/`removeDuplicateCandidates` 保持既有等价语义（卡片收敛为非可操作状态），permission 卡片只由持久 interaction 状态驱动，不由 EventSource 连接状态推断（已有 ToolCallCardQuestionReadiness 测试钉住）。

未复制实质 TypeScript 源码（design reference only）；快照为 MIT License。

### Changed files

- 新增 `D:\LabexAgent\frontend\src\composables\agentRuntimeStore.js`：messages 数组的派生归一化索引（byTaskId/byToolCallId/byInteractionId/byPartKey/byMessageKey + `ensureAssistantMessage`），只读视图，不是第二事实源。
- `D:\LabexAgent\frontend\src\composables\agentHistoryReducer.js` / `useAgentEventTimeline.js`：TOOL_CALL 幂等合并；OBSERVE 应用结构化 executionStatus。
- `D:\LabexAgent\frontend\src\composables\agentToolCallState.js`：新增 `applyStructuredExecutionStatus`；`visibleToolCallStatus` 显式覆盖 timed_out/failed/cancelled。
- `D:\LabexAgent\frontend\src\components\cloud\ToolCallCard.vue`：executionText 区分“执行超时/已取消/已跳过/已中断”。
- `D:\LabexAgent\frontend\src\composables\useAgentTaskRuntime.js`：assistantMessageForTask 改走归一化索引（latest-wins 语义不变）。
- `D:\LabexAgent\backend\src\main\java\com\labex\labexagent\run\AgentToolCallJournalService.java`：新增 executor-fenced `interrupted(...)`。
- `D:\LabexAgent\backend\src\main\java\com\labex\labexagent\runtime\AgentLoopEngine.java`：`journalToolFinished` 按结构化 executionStatus 映射 durable 状态；`sendObserve` 下发 executionStatus/exitCode/durationMs。

### Verification evidence (2026-08-13)

1. **前端聚焦**：agentRuntimeStore/agentHistoryReducer/useAgentEventTimeline/agentToolCallState/agentRunPartState/useAgentTaskRuntime 88 tests PASS（修复了索引 interactionId 字符串处理 bug 后）。
2. **后端聚焦**：`AgentToolCallJournalServiceTest(9) + AgentRunPartServiceTest(16) + AgentRunExecutionProgressReducerTest(4) + AgentLoopEngineWorkspacePauseContractTest(4) + AgentRuntimeConvergenceContractTest(2) + AgentRunProgressProjectionServiceTest(4)` 全 PASS。
3. **T2.1 聚焦回归**：ShellCommandFactoryTest/RunCommandToolTest/RunTestsToolTest/ProjectTerminalServiceWorkerTest/RunTestsToolWiringTest 全 PASS。
4. **WSL 单一验收**：`WslSandboxWorkerSmokeTest#executesRunTestsAndManagedTerminalThroughTheSameRealWslShell` PASS。
5. **前端全量**：`npm test` 244/244 PASS；`npm run build` PASS。
6. **后端 package**：`mvn -q -DskipTests package` PASS。

### T2.2 remaining gaps

- `CloudWorkspace.vue` 模板仍约 2900 行，运行时状态已收敛在 composables；模板级拆分留待后续低风险迭代。
- 浏览器真实验收（刷新、SSE 断线重连、结果卡片）未执行，属 T4.1。
- durable Part 无独立 metadata 落库：timed_out 在重启回放时收敛为 `error`（结果文本仍含 `status=timed_out`），live 路径已结构化。若后续需要重启后保留超时区分，应扩展 Part 状态词汇或 metadata 列。

### Next task

T2.3（durable transcript / Tool Part / compaction 复核）或 T4.1（浏览器真实验收，需真实运行环境）。

## T2.3 Update (2026-08-13)

**Status: verified (reconciliation + documentation pass; no production code change needed).**

### OpenCode references and replicated invariants

复核前阅读了 `D:\opencode\opencode-dev\packages\opencode\src\session\prompt.ts`、`processor.ts`、`compaction.ts`，对照结论：

1. **每轮从持久化消息重建 Provider 输入**：OpenCode 的 prompt.ts 每轮从持久化消息重建请求；LabexAgent 的 `AgentTranscriptProjectionService.loadProviderMessages(taskId)` 是唯一读取入口，`AgentLoopEngine.providerMessagesForInvocation` 附加的 runtime projection 标注 `derived_read_only` 且不写回 transcript。
2. **compaction 选择**：OpenCode 用真实 user turn 计算 tail（排除 compaction 消息）、默认 `tail_turns=2`、`preserve_recent_tokens=clamp(25% usable, 2000..8000)`、按预算倒序保留 turn、budget 不足时 splitTurn 边界回退；LabexAgent 的 `ContextWindowPolicy`（默认值一致）与 `CompactionSelection.select`（整 turn 粒度回退，无 splitTurn 微调）等价实现。
3. **prune 只清理安全历史**：OpenCode 只 prune `completed` tool part、保护最近 2 turn、受保护工具除外、标记 `compacted` 而非删除；LabexAgent 的 `TurnAwareContextPruner` 只重写内存投影中的历史 completed tool result（durable Part 保留原文），保护 write/plan/test/question/permission 工具。
4. **overflow 有限策略**：OpenCode 的 compaction 结果若仍 "compact" 则记录 ContextOverflowError 并 stop；LabexAgent 的 `ContextOverflowRecoveryPolicy` 按 COMPACT → REDUCE_TOOL_SCHEMA → exhausted 显式终态停止，且用前后 token 估算验证恢复进度，不无限重试同一 prompt。
5. **协议 fail closed**：孤儿 role=tool、缺失交互 tool call、非终态 companion tool call 均抛错或截断（`AgentRunTranscriptService`）；tool call/result 由 epoch+sequence 稳定 key 幂等持久化。

### Verification evidence (2026-08-13)

- T2.3 聚焦测试集两批共 75+ tests 全 PASS（transcript/compaction/pruner/context-budget/terminality/wiring/progress-projection/conversation-compaction 各测试类）。
- 无生产代码改动；文档改动：`docs/coding-agent-industrialization/opencode-alignment-status.md`（第 1/7/8/9 节按当前源码重写，删除“本地 msgs 仍是权威”“压缩尚未收敛”等过时表述）。

### Next task

T4.1（真实浏览器验收）需要运行环境；在此之前可继续 T2.4（completion evidence 与失败分级复核）。

## T2.4 + T4.1 Update (2026-08-13)

**Status: verified.** T2.4（completion evidence 与失败分级）实现完成；T4.1（浏览器真实验收）`run-all.ps1 -RestartBrowserBackend` 全绿。

### T2.4 交付

1. 验证状态词汇收敛：`AgentVerificationRecorder` 写入 `passed/failed/timed_out/cancelled/infrastructure_error`（`ToolResult.executionStatus` 结构化映射；只有真实 exit 0 + SUCCEEDED 计 passed）。
2. 完成证据分离环境受阻与代码失败：`RunCompletionEvidence.environmentVerifications` 新列表 + `RunCompletionPolicy` 新准则 `environment_verifications`；`AgentRunFinalizer` 对两类失败给出可行动的不同 guidance。
3. `RunTestsTool` 对超时/取消/基础设施失败前缀 `failure_code=VERIFICATION_TIMED_OUT/CANCELLED/INFRASTRUCTURE_ERROR` + recovery_action；`AgentLoopEngine.annotateCommandRecovery` 对已有 failure_code 不再叠加第二份前缀。
4. 前端 `CompletionEvidenceCard` 增加"环境受阻验证"警示区块。

### T4.1 交付（真实验收发现并修复 5 个问题）

1. **ProjectTerminalService 启动失败**：多构造器缺 `@Autowired`（Spring 6.1 无默认构造器）→ JAR 启动即挂。修复：双参构造器加 `@Autowired`（与 RunCommandTool 同模式）。
2. **H2 Shell 静默吞 SQL 错误**：`org.h2.tools.Shell` 对 SQL 错误（含 lock timeout）仍返回 exit 0、只打印 `Error:` 行 → 验收脚本把失败的 DELETE 当成功，legacy 清理断言偶发拿到旧行。修复：`Invoke-AcceptanceSql` 扫描 `Error:` 行 + 锁超时重试 6 次 + legacy DELETE 用 Update count 验证（前后 COUNT 核对）。
3. **opencode 网络命令审批门缺失**：`mvn validate` 在 opencode profile 下被 classifyRealShell 直接 ALLOW → 绕过"命令审批 → 离线执行 → 一次性网络重试"的收敛设计（浏览器 network-retry 场景因此挂）。修复：real-shell 路径对网络可执行程序/显式网络请求/网络能力构建工具（mvn/npm/npx/pip/gradle 等）→ REQUIRE_APPROVAL NETWORK_COMMAND；browser 证据 `liveNetworkApprovalWithoutRefresh`、`serverOwnedExactNetworkRetry` 随之全绿。
4. **H2 相对路径 URL 偶发漂移**：`jdbc:h2:tcp://host/./labex-agent` 重启后 legacy 段偶发 rows=3（marker/行像被"回退"）。修复：agent-runtime/browser-runtime 改用绝对路径 URL `.../$databaseRoot/labex-agent`。改后连续多次 run-all 全绿（绝对路径消除 `./` 解析不确定性）。
5. **过时契约测试**：`AgentLoopEngineStreamingContractTest` 断言旧 `networkRequested` 形态 → 更新为当前收敛形态（opencodeShell 守卫 + offline-retry grant 消费语义不变）。

### 验收证据

- `run-all.ps1 -RestartBrowserBackend`（powershell 5.1，无 pwsh 环境）：exit=0，summary `{package: true, acceptanceUnitTests: true, backendRuntime: true, browserRuntime: true}`。
- 浏览器证据 40+ 项全 true；consoleErrors=0、networkErrors=0；2 次真实后端重启 handoff。
- 后端受影响包回归 925 tests 0 failures；前端 244 PASS + build PASS；acceptance unit 16 PASS。

### Next task

T4.2（后端/前端全量验证）或 T4.3（文档与发布判定）。

## T4.2 Update (2026-08-13)

**Status: verified for Windows.** 后端全量 1550 tests 0 failures；前端 244 pass + build PASS；修复后重跑浏览器验收全绿。Linux Docker/WSL smoke 因本机无环境未执行（对应 smoke 测试按设计 skip）。

### 全量测试暴露并修复 3 类问题

1. **`networkRequested` 语义错误（3 个调用点）**：`RunCommandTool`、`RunTestsTool`、`StudentProjectController` 把 Worker 网络能力（`run.policy().networkEnabled()`，默认 `network-default-enabled: true`）传给 `CommandRequest.networkRequested`，导致 opencode profile 下所有命令被判 REQUIRE_APPROVAL，工具直连路径全部返回 `command_approval_not_persisted`（15 个契约测试红）。修复：`RunCommandTool` 网络标记只读 args 显式 `network` 字段；`RunTestsTool`/`StudentProjectController`/safe 分支传 `false`——服务端策略命令不携带模型网络意图，网络命令仍由 `NETWORK_EXECUTABLES`/`NETWORK_CAPABLE_BUILD_TOOLS` 分类触发审批（审批通过后由 `AgentApprovedCommandExecutor` 执行，不经工具）。
2. **`RunTestsTool` 渲染 payload 绕过审批门**：分类基于 PowerShell/Bash 渲染后的完整 payload（`& 'mvn' 'test'` / `'mvn' 'test'`），`firstToken` 变成 `&` 或带引号 token，构建工具审批门失效。修复：分类改用真实命令身份（未渲染 argv `String.join(" ", command)`），渲染只影响执行层。
3. **过期测试断言收敛**：`CommandToolWorkerTest` 两个测试断言 run_tests 未经审批的工具直连被拒绝（worker 调用 3 次）；`ShellToolContractTest` 的 npm/mvn 载体换成非网络命令（python 超时策略断言 180s）；`RunTestsToolTest` 六个 mvn 测试显式启用 `acceptanceAutoApproveVerification` 模拟验收 profile。

### 验收证据

- 后端 full `mvn test`：`Tests run: 1550, Failures: 0, Errors: 0, Skipped: 13`（BUILD SUCCESS；Skipped 为 Docker/WSL smoke 环境检测）。
- 后端 focused（受影响集）：213 tests 0 failures。
- 前端 `npm test` 244/244 PASS；`npm run build` PASS。
- 修复后重跑 `run-all.ps1 -RestartBrowserBackend`：exit=0，`{package: true, acceptanceUnitTests: true, backendRuntime: true, browserRuntime: true}`，`consoleErrors=0, networkErrors=0`（修复前残留后端进程锁 jar 导致 repackage 失败，已终止 T4.1 残留进程后重跑）。
- Linux Docker smoke / Windows WSL smoke：本机无 Docker/WSL，标记 unverified on this machine。

### Next task

T4.3（更新文档与发布判定）：README 支持矩阵、alignment status、THIRD_PARTY_NOTICES、checklist 证据路径、发布标签（Windows 侧证据已支持 `Local Usable` 判定）。

## T4.3 Update (2026-08-13)

**Status: verified.** 发布判定 **`Local Usable`**（Windows）；`Linux Web Ready` / `Public Multi-user Ready` 未满足。

### 文档交付

1. `README.md`：新增「支持矩阵与发布状态」——Windows local verified / Linux local partial / Linux Web unverified，发布判定与依据（后端全量 1550、前端 244 + build、浏览器四段全绿）、已知限制（PTY 未恢复、Docker/Linux 未验收）。
2. `opencode-alignment-status.md`：补参考版本（opencode 1.17.4）、文件列表、design reference only 声明，新增「未完成项」小节。
3. `THIRD_PARTY_NOTICES.md`（新建）：OpenCode 标注 design reference only（MIT、未复制源码）；将来复制代码须引入 MIT 声明。
4. `architecture.md`：新增「Current Implementation Status (2026-08-13)」对照表。
5. `checklist.md`：第 0 节 `Local Usable (Windows)` [x]；新增 T4.3 Evidence。
6. `tasks.md`：T4.3 全部 checkbox 勾选 + 实测记录。

### 验证

- `git diff --check`：无空白错误（仅既有 CRLF 提示）。
- 未执行任何 commit / push / reset / clean。

### Next task

- T3.1–T3.4（Linux Web 生产部署）需要真实 Linux 环境；
- `Public Multi-user Ready` 需要限流/配额/监控/备份等工业化建设；
- 交互式 WebSocket PTY 恢复（可后置）。

## T3.1 Update (2026-08-14)

**Status: verified.** 生产配置固化与 fail-fast 校验落地（纯 Windows 可完成的 T3 部分）。

### 交付

1. `ProductionStartupValidator`（新，`@Profile({"prod","production"})`）：启动前一次性汇总全部缺失项——DB 凭据、JWT ≥64 字节（拒绝本地占位）、Secret Store 主密钥（Base64 32 字节）、拒绝 `full_access`、CORS/WebSocket 仅显式 HTTPS 来源（拒绝 `*` 与 localhost 默认）、拒绝 Windows 盘符路径。
2. Worker 资源限制显式配置：`labex-agent.worker.resources.{cpu-millis,memory-megabytes,max-pids}` + `WorkerResourceDefaults` 静态提供默认 `WorkerPolicy`（无 Spring 上下文时回退硬编码）；`DockerSandboxWorker` 新增 `max-concurrent-containers` Semaphore 并发闸。
3. 生产 CORS：`labex-agent.cors.allowed-origins` 非空时 `SecurityConfig` 用显式来源列表（本地保持历史放开行为）。
4. `application-production.yml`（新）：`/srv/labex-agent/workspaces`、`/srv/labex-agent/uploads` Linux 路径默认值。
5. `deploy/linux/.env.production.example` + `deploy/linux/README.md`（新）：生产拓扑、必填项、安全要点、验收状态。
6. 文档：`.env.example` 补生产注释；README 补「本地可 unsafe-local，Linux Web 必须 Docker Worker」执行后端规则。

### 验证

- `ProductionConfigurationTest` 10 tests 全 PASS（完整配置加载 + 9 类失败场景 + 多问题一次性汇总）。
- 后端全量 `mvn test`：1560 tests 0 failures（较 T4.2 新增 10 个）。
- 真实 `mvn spring-boot:run -Dspring-boot.run.profiles=production`：启动失败，错误消息列出全部 6 项问题（验证 fail-fast 在真实上下文生效）。
- 本地 local profile 重启正常，后端/前端 HTTP 200（浏览器正在使用新代码订阅事件）。

### Next task

- T3.2（Docker Worker image）需 Linux + Docker 环境（WSL2 内原生 Docker 或启动 Docker Desktop）。
