# LabexAgent OpenCode-First 可用性改造验收清单（checklist.md）

**适用版本：** `2026-08-12-opencode-first-agent-usability`  
**仓库：** `D:\LabexAgent`  
**OpenCode 参考：** `D:\opencode\opencode-dev`  
**状态约定：** `[ ]` 未验证，`[x]` 已验证，`[~]` 部分验证，`[!]` 阻塞/失败  
**证据要求：** 每个 `[x]` 必须填写命令、时间、revision、日志/截图/响应路径；源码或单元测试通过不等于真实运行通过。

---

## 0. 发布标签与总判定

- [x] **Local Usable (Windows)**: Windows WSL/local execution、durable runtime、真实浏览器验收与全量测试门禁全部 verified（2026-08-13）。已知限制：交互式 WebSocket PTY 未恢复（REST managed terminal 是当前路径）；Docker Worker 未在真实 daemon 验证。证据：README「支持矩阵与发布状态」、本文件 T4.1/T4.2/T4.3 Evidence。
- [ ] **Linux Web Ready**：Linux Docker Worker、MySQL、HTTPS 反代、生产前端、SSE/WebSocket/托管终端、浏览器、重启和双项目隔离全部通过。
- [ ] **Public Multi-user Ready**：在 `Linux Web Ready` 之外，限流、配额、备份恢复、监控告警、滥用和升级回滚全部通过。

> 未满足下一层门槛时，不能使用下一层标签。只跑 `mvn test`、`npm test` 或 `npm run build`，不得标记任何 Web 发布标签。

## Iteration Evidence (2026-08-12)

- [x] Phase 1 Shell contract focused tests passed.
- [x] WSL fixture executed cd frontend&&npm install, frontend build, Maven fixture test, quoting, pipeline, redirection, variables, large-output artifact, failure, repair, and retry.
- [x] agent-runtime.ps1 passed in acceptance,local with approval/reject/cancel/restart/compaction/fork/outbox/lifecycle evidence.
- [x] Frontend: 231 tests passed; Vite production build and chunk budget passed.
- [~] Local usable is only a Windows WSL candidate because the real browser wrapper was not run.
- [!] Docker runtime is blocked: docker version cannot connect to dockerDesktopLinuxEngine.
- [ ] Linux Web Ready requires real Linux Docker, reverse proxy, browser, restart, and two-project isolation evidence.
- [ ] Public Multi-user Ready requires rate limits, quotas, backup, monitoring, abuse, and rollback evidence.

**Evidence files:**

- `D:/LabexAgent/docs/iterations/2026-08-12-iteration-82-opencode-shell-baseline.md`
- `C:/Users/35475/AppData/Local/Temp/labex-agent-acceptance-f5744fefaf604cec9d081bdee3495845`

## T2.1 Evidence (2026-08-13)

- [x] Backend focused: `ShellCommandFactoryTest,RunCommandToolTest,RunTestsToolTest,ProjectTerminalServiceWorkerTest,RunTestsToolWiringTest` PASS（28 tests）。
- [x] Frontend protocol: `node --test src/composables/terminalProtocol.test.mjs` PASS。
- [x] Real WSL single-case acceptance: `mvn -q '-Dlabex.wsl.smoke=true' '-Dtest=WslSandboxWorkerSmokeTest#executesRunTestsAndManagedTerminalThroughTheSameRealWslShell' test` PASS；`RunTestsTool` 真实执行失败 fixture（`shell=bash`，exit=1），managed terminal 真实执行 `npm install && npm run build` 且输出 `frontend build passed`。
- [x] Long-running terminal convergence regression tests added: succeeded/failed status, real `durationMs`/`outputChars`, stop→cancelled race guard, idle stop no-clobber（`ProjectTerminalServiceWorkerTest` 7 tests）。
- [x] Backend package: `mvn -q -DskipTests package` PASS。
- [x] Frontend full: `npm test` 231/231 PASS；`npm run build` PASS。
- [x] `git diff --check` clean；修改文件 UTF-8、无 `???`。
- [ ] WebSocket PTY 仍未恢复；REST managed terminal 是当前唯一可用终端路径（发布门禁必须标注）。
- [ ] Browser runtime / Docker / Linux Web 未验证，结论同 2026-08-12 记录。
- [x] PowerShell 坑已记录：未加引号的 `-Dlabex.wsl.smoke=true` 会被拆词，必须给每个 `-D` 参数加引号。

## T2.2 Evidence (2026-08-13)

- [x] Normalized derived index `agentRuntimeStore.js`（byTaskId/byToolCallId/byInteractionId/byPartKey/byMessageKey）与 `ensureAssistantMessage` 有单测覆盖。
- [x] TOOL_CALL 事件按 toolCallId 幂等合并（live + replay 两条 reducer 都改），snapshot+重放不重复建卡片（新增 3 个回归测试）。
- [x] Shell Part 状态词汇 pending/running/completed/error/timed_out/cancelled/waiting_approval 显式映射并有测试。
- [x] 结构化 executionStatus：后端 `sendObserve` 下发、`journalToolFinished` 映射 durable 状态（cancelled→interrupted、infrastructure_error→environment_blocked），前端 `applyStructuredExecutionStatus` 投影，不再解析文本猜测。
- [x] 身份校验（ownsConversation/ownsTaskIdentity/generation counter）与 cursor 单调去重已有测试覆盖（useAgentTaskRuntimeTest）。
- [x] permission card 只由持久 interaction 状态驱动（ToolCallCardQuestionReadiness 测试已钉住）。
- [~] `CloudWorkspace.vue` 模板拆分未完成（运行时状态已在 composables；模板 ~2900 行）。
- [ ] 浏览器真实验收（刷新/重连/结果卡片）未执行，属 T4.1。
- [x] Frontend full: `npm test` 244/244 PASS；`npm run build` PASS。
- [x] Backend focused: journal/part/progress/loop 契约测试 PASS；T2.1 聚焦集与 WSL 单一验收无回归。

## T2.3 Evidence (2026-08-13)

- [x] 每轮 Provider 从 durable projection 重建（`AgentTranscriptProjectionService.loadProviderMessages`；本地派生投影标注 derived_read_only 不写回）。
- [x] assistant tool_calls / role=tool 的 tool_call_id/name/arguments/status 协议对应；孤儿 tool result、缺失交互 tool call fail closed。
- [x] compaction 持久化 previous summary/head/tail/turn 数/token budget/epoch/source boundary；previous summary 复用。
- [x] prune 只清除内存投影中历史 completed tool result（durable Part 保留原文）；running/waiting part 永不被 prune。
- [x] context overflow 有限策略序列（COMPACT → REDUCE_TOOL_SCHEMA → 显式终态停止），不无限重试同一 prompt。
- [x] 重启后按 taskId 恢复 Provider 输入与 interaction waiting part（transcript/compaction 边界续读）。
- [x] `docs/coding-agent-industrialization/opencode-alignment-status.md` 已按当前源码修订（删除“本地 msgs 权威 / 压缩未收敛”过时表述）。
- [x] T2.3 聚焦测试 75+ 全 PASS（两批，见 iteration-82 T2.3 Update 节）。
- [x] 无生产代码改动；OpenCode 参考：session/prompt.ts、processor.ts、compaction.ts（design reference only，未复制源码）。

## T2.4 Evidence (2026-08-13)

- [x] 验证状态词汇：passed/failed/timed_out/cancelled/infrastructure_error（只有 exit 0 + SUCCEEDED 计 passed）。
- [x] 完成证据分离 environmentVerifications（超时/取消/基础设施）与 failedVerifications（代码失败），guidance 可行动区分。
- [x] RunTestsTool 对超时/取消/基础设施失败前缀 failure_code=VERIFICATION_TIMED_OUT/CANCELLED/INFRASTRUCTURE_ERROR + recovery_action。
- [x] 前端 CompletionEvidenceCard 增加"环境受阻验证"警示区块（environmentVerifications）。
- [x] RunCompletionEvidenceServiceTest 14 / AgentRunFinalizerTest 4 / AgentPostEditHookServiceTest 5 / RunCompletionPolicyTest 8 / AgentVerificationRecorderTest 6 全 PASS。
- [x] 受影响包回归 925 tests 0 failures；前端 244 PASS + build PASS。

## T4.1 Evidence (2026-08-13)

- [x] `run-all.ps1 -RestartBrowserBackend` 全绿：package/acceptanceUnitTests/backendRuntime/browserRuntime 全 true（powershell 5.1 实测 exit=0）。
- [x] 浏览器证据 40+ 项全 true：desktopLayout、slash 命令目录/帮助/复制/导出/新建隔离、conversationIsolation、refreshReplayDeduplicated、internalReasoningProtocolHidden、durable cache/plan/revision、question 组件、permission approval refresh recovery、multiToolPermissionBatch、commandApprovalStableToolIdentity、liveNetworkApprovalWithoutRefresh、serverOwnedExactNetworkRetry、durable history/messages/parts/cursors、durableCompaction（epoch 1、tokens 26225→23421）、manualCompactionForkRefresh、providerStreamInterruption、modelRetryLiveProjection、restartProjection/ManualFork/Interaction、staticContextBlockerCard、recoverableWaitNoPseudoTerminal、environmentRetrySameTask、completionEvidenceCard、unverifiedCompletionBlocked。
- [x] consoleErrors=0、networkErrors=0、expectedRestartTransportErrors=3（真实重启后的预期 SSE 传输错误）。
- [x] 真实验收发现并修复 5 个问题（见 tasks.md T4.1 修复记录）：ProjectTerminalService 构造器 @Autowired、H2 Shell 静默错误检测+重试、opencode 网络命令审批门、H2 绝对路径 URL、过时契约测试更新。
- [x] 前端 acceptance unit 16 PASS；后端受影响包 925 tests 0 failures。

## T4.2 Evidence (2026-08-13)

- [x] 后端 focused tests：修复后受影响集 213 tests 0 failures（tool.impl + commandsecurity + ProjectTerminalServiceWorkerTest + ProjectCommandSafetyTest）。
- [x] 后端 full `mvn test`：`Tests run: 1550, Failures: 0, Errors: 0, Skipped: 13`，BUILD SUCCESS（Skipped 为 Docker/WSL smoke 环境检测跳过）。
- [x] 前端 `npm test`：244 pass 0 fail；`npm run build`：成功（budget 内）。
- [x] Browser runtime：修复后重跑 `run-all.ps1 -RestartBrowserBackend` exit=0，`{package: true, acceptanceUnitTests: true, backendRuntime: true, browserRuntime: true}`。
- [x] Restart/replay/compaction/approval/cancellation evidence：backendRuntime 50+ 项与 browserRuntime 40+ 项证据全 true；`consoleErrors=0, networkErrors=0`。
- [ ] Linux Docker smoke / Windows WSL smoke：本机无 Docker/WSL，相关 smoke 测试按设计 skip；标记为 unverified on this machine。
- [x] 全量测试暴露并修复 3 类问题（见 tasks.md T4.2 修复记录）：networkRequested 语义错误（3 个调用点）、RunTestsTool 渲染 payload 绕过审批门、过期测试断言收敛。

## T4.3 Evidence (2026-08-13)

- [x] README 新增「支持矩阵与发布状态」：Windows local verified / Linux local partial / Linux Web unverified，逐项列出判定依据与已知限制。
- [x] `opencode-alignment-status.md` 写明参考版本（opencode 1.17.4）、文件（session/prompt.ts、processor.ts、compaction.ts）、适配差异（第 9 节）与未完成项（新增小节）。
- [x] `THIRD_PARTY_NOTICES.md`（新建）：OpenCode 标注 design reference only（MIT，未复制源码，附版本与参考文件）；若未来复制代码须引入 MIT 声明。
- [x] `architecture.md` 新增「Current Implementation Status (2026-08-13)」对照表，区分 verified / unverified。
- [x] checklist 第 0 节发布标签更新：`Local Usable (Windows)` [x]；Linux Web Ready / Public Multi-user Ready 未满足。
- [x] 发布标签只用三档（`Local Usable` / `Linux Web Ready` / `Public Multi-user Ready`），无模糊表述。
- [x] `git diff --check` 通过；未执行任何 commit / push / reset / clean。

## T3.1 Evidence (2026-08-14)

- [x] `ProductionStartupValidator` fail-fast：真实 production 启动一次性列出 6 项缺失（JWT/master-key/CORS/WebSocket/D: 盘符路径×2）后拒绝启动。
- [x] 拒绝清单覆盖：DB 凭据缺失、JWT 占位/不足 64 字节、master-key 缺失/非法 Base64、`full_access`、CORS `*`/http/未配置、WebSocket localhost 默认/`*`、Windows 盘符路径。
- [x] 资源限制配置化：`worker.resources.{cpu-millis,memory-megabytes,max-pids}`、`worker.docker.max-concurrent-containers`（Semaphore）、上传 100MB（既有）。
- [x] `SecurityConfig` 支持显式 CORS 来源列表；本地开发保持历史行为不受影响。
- [x] `application-production.yml`：Linux 路径 `/srv/labex-agent/workspaces`、`/srv/labex-agent/uploads`。
- [x] `deploy/linux/` 目录（`.env.production.example` + `README.md`）：拓扑、必填项、安全要点。
- [x] `ProductionConfigurationTest` 10 tests PASS；后端全量 1560 tests 0 failures；本地 local profile 重启 HTTP 200。




---

## 1. 变更基线与文档规则

- [ ] 记录 `git branch --show-current`。
- [ ] 记录 `git rev-parse HEAD`。
- [ ] 记录改造开始前 `git status --short`、`git diff --stat`。
- [ ] 未执行 `git reset`、`git clean`、广泛删除、覆盖用户无关改动。
- [ ] 每个迭代有独立记录：`D:\LabexAgent\docs\iterations\YYYY-MM-DD-iteration-<n>-*.md`。
- [ ] 每个修改模块都记录了对应 OpenCode 参考文件。
- [ ] 每个模块都写明：参考的不变量、LabexAgent 的适配差异、是否复制实质源码。
- [ ] 如复制 OpenCode 实质源码，已创建/更新 `D:\LabexAgent\THIRD_PARTY_NOTICES.md`，包含 `D:\opencode\opencode-dev\LICENSE` 中的 MIT 文本和版权声明。
- [ ] 未把 OpenCode 的 TypeScript/Bun/Effect 具体实现未经适配直接移入 Java/Spring/Vue。
- [ ] 没有新增第二套 transcript、task status、permission waiter 或前端事实状态。

**Evidence:**

```powershell
Set-Location D:\LabexAgent
git branch --show-current
git rev-parse HEAD
git status --short
git diff --stat
```

---

## 2. OpenCode 参考复刻检查

### 2.1 Shell

- [ ] 阅读 `D:\opencode\opencode-dev\packages\opencode\src\tool\shell.ts`。
- [ ] 阅读 `D:\opencode\opencode-dev\packages\opencode\src\tool\shell\prompt.ts`。
- [ ] 阅读 `D:\opencode\opencode-dev\packages\opencode\src\shell\shell.ts`。
- [ ] Tool 使用完整 `command` 字符串，不用自定义空格 tokenizer 模拟 Shell。
- [ ] `workdir` 是独立参数，并在 Worker 中进行 workspace 内路径校验。
- [ ] timeout、cancel、进程树终止和输出截断都在执行层处理。
- [ ] AST/解析只用于风险、外部路径和权限 pattern，不用于禁止正常 Shell 语言。

### 2.2 权限

- [ ] 阅读 `D:\opencode\opencode-dev\packages\opencode\src\agent\agent.ts`。
- [ ] 阅读 `D:\opencode\opencode-dev\packages\opencode\src\permission\index.ts`。
- [ ] 默认 `opencode` profile 对工作区读写、Shell、构建、测试、安装依赖放行。
- [ ] `.env`、外部目录、明显破坏性操作仍有明确 ASK/DENY 边界。
- [ ] 单个 Tool call 只做一次 permission evaluation。
- [ ] `allow_once`、`allow_always`、reject、expiry、resume 可持久化并幂等。
- [ ] `full_access` 只可本地显式启用，Linux production 拒绝。
- [ ] `safe` 是可选模式，不影响默认 Coding Agent 能力。

### 2.3 Context/Part/Compaction

- [ ] 阅读 `D:\opencode\opencode-dev\packages\opencode\src\session\prompt.ts`。
- [ ] 阅读 `D:\opencode\opencode-dev\packages\opencode\src\session\processor.ts`。
- [ ] 阅读 `D:\opencode\opencode-dev\packages\opencode\src\session\compaction.ts`。
- [ ] Provider 每轮从 durable Message/Part 投影构建消息。
- [ ] assistant tool call 与 tool result 的 `toolCallId/name/arguments` 一一对应。
- [ ] Tool Part 覆盖 pending/running/completed/error/cancelled 等明确状态。
- [ ] compaction 按 token budget 和完整 turn 保留 recent tail。
- [ ] previous summary、head/tail 边界、epoch、source sequence 持久化。
- [ ] 大工具输出截断给模型，但完整内容有 artifact 路径。
- [ ] 重启后能重建 Provider 输入和可恢复 interaction。

### 2.4 Frontend/PTY

- [ ] 阅读 `D:\opencode\opencode-dev\packages\app\src\context\global-sync\event-reducer.ts`。
- [ ] 阅读 `D:\opencode\opencode-dev\packages\opencode\src\pty-preparation.ts`。
- [ ] 阅读 `D:\opencode\opencode-dev\packages\opencode\src\server\routes\instance\httpapi\handlers\pty.ts`。
- [ ] 前端以 `taskId/messageId/partId/interactionId` normalized state 为唯一运行视图。
- [ ] snapshot/cursor/replay 事件可重复应用且幂等。
- [ ] 旧 task 的 event 不会写入新 conversation/session。
- [ ] PTY 如恢复，使用短期 ticket、Origin、project/session 授权与 Worker 生命周期清理。
- [ ] PTY 未恢复时，UI 不得宣称原生交互终端可用。

---

## 3. Shell Tool Contract

- [ ] `shell` schema 有 `command`（required）。
- [ ] `shell` schema 有 `workdir`、`timeout`、`description`（optional/按约定）。
- [ ] schema 顶层 `type=object`，不允许无关额外字段，参数错误有结构化提示。
- [ ] 兼容旧输入：`working_directory`、`timeout_seconds`、`bash`、`run_command`。
- [ ] 兼容转换只发生一次，并有删除条件和版本记录。
- [ ] `network` 不再让模型承担每次决策，读取运行 profile/Worker 配置。
- [ ] 系统提示词动态注入实际平台、Worker、Shell、workspace root、网络和权限 profile。
- [ ] 不再向模型宣称“不是通用 Shell”。
- [ ] 不再禁止 `&&`、`||`、`|`、`;`、引号、反斜杠、变量、子表达式。
- [ ] `cd frontend&&npm install` 能被真实 Shell 执行。
- [ ] `cd frontend && npm install` 能被真实 Shell 执行。
- [ ] `workdir=frontend` + `npm install` 能被真实 Shell 执行。
- [ ] 引号路径能执行。
- [ ] 管道能执行。
- [ ] 重定向能执行。
- [ ] 环境变量能执行。
- [ ] 条件/串联命令能执行。
- [ ] 命令失败返回非零 `exit_code` 和 `failed`，不是“工具成功但验证失败”。
- [ ] timeout 返回 `timed_out`。
- [ ] 用户停止返回 `cancelled`。
- [ ] Worker 不可用返回 `infrastructure_error`。
- [ ] 输出截断字段准确，完整 artifact 可追踪。
- [ ] Shell result 在下一轮 Provider transcript 中可见。

**Minimum command fixture:**

```bash
cd frontend&&npm install
cd frontend && npm run build
printf "hello world\n" > "file with spaces.txt"
printf "a\nb\n" | grep b
VALUE=world; printf "hello %s\n" "$VALUE"
npm run build > build.log 2>&1
```

---

## 4. 权限与边界

### Default `opencode`

- [ ] 普通文件读取允许。
- [ ] 工作区文件创建/写入/编辑允许。
- [ ] 工作区内 `npm install` 默认不弹审批。
- [ ] 工作区内 `npm run build` 默认不弹审批。
- [ ] 工作区内 `mvn test` 默认不弹审批。
- [ ] 工作区内 `git status`、`git diff`、本地提交默认不弹审批。
- [ ] 网络策略由 Worker/profile 控制，未启用时返回环境阻塞而不是命令语法阻塞。
- [ ] 失败命令不会因为相同 command 再次出现而死循环审批。

### 保留的最小边界

- [ ] `workspace` 路径逃逸被阻止。
- [ ] 项目 A 的用户不能读取项目 B 的 workspace。
- [ ] 项目 ownership 在 API、Tool、artifact、terminal 和 SSE replay 都检查。
- [ ] `.env`、私钥、凭据读取至少 ASK；公网不应把控制面 Secret 注入 Worker。
- [ ] 明确破坏性宿主机行为（例如 `rm -rf /`、磁盘/容器控制）至少 DENY 或 ASK。
- [ ] Worker 不能读取后端 DB password、JWT secret、Provider API key。
- [ ] `full_access` 不在 Linux production profile 可用。
- [ ] Docker/WSL 资源有 CPU、内存、PID、磁盘、超时和并发限制。
- [ ] cancel/timeout 会终止完整进程树或容器。

### `safe` 回归

- [ ] `safe` profile 仍能阻止明确的危险操作。
- [ ] `safe` profile 的拒绝信息可行动，不要求模型盲目重复。
- [ ] 切换 profile 不产生第二套 Tool 执行实现。

---

## 5. Windows 本地验收

- [ ] PowerShell 中 `java -version` 满足项目要求。
- [ ] PowerShell 中 `mvn -v` 可用。
- [ ] PowerShell 中 `node -v`、`npm -v` 可用。
- [ ] WSL distribution 可用。
- [ ] WSL 中 `bwrap` 可用。
- [ ] WSL 中 Bash、Git、Node/npm、Java/Maven 可用。
- [ ] `WslSandboxWorker` descriptor 表示 Bash + `/workspace`。
- [ ] WSL 只挂载当前项目 workspace。
- [ ] WSL 默认网络行为符合 `opencode` 配置。
- [ ] Agent 能修改真实项目文件。
- [ ] Agent 能执行 `npm install`。
- [ ] Agent 能执行前端 build。
- [ ] Agent 能执行后端测试。
- [ ] Agent 能看到失败 stdout/stderr 并修复。
- [ ] Agent 能在下一轮重跑并得到 PASS evidence。
- [ ] 停止 Agent 后 WSL/bwrap 子进程不存在。
- [ ] 检查监听端口和运行 JVM 的 PID/start time/classpath，避免验证旧进程。

**Evidence:**

```powershell
Set-Location D:\LabexAgent
wsl -d Debian -- bash -lc 'command -v bwrap; bash --version | head -n 1; git --version; node --version; npm --version; java -version 2>&1 | head -n 1; mvn -version | head -n 1'
pwsh -NoLogo -NoProfile -File .\scripts\acceptance\run-all.ps1 -SkipBrowser
```

---


### Iteration Windows WSL Result

- [x] WSL Shell descriptor and /workspace containment verified by real smoke.
- [x] &&, quoting, pipelines, redirection, and variables are not blocked by the direct tokenizer.
- [x] Result includes status/exit/duration/truncation/artifact; large output is recoverable from artifact.
- [x] Durable restart, approval, cancel, compaction, and fork acceptance passed.
- [~] Browser wrapper, live Shell result card, and reconnect remain unverified in this iteration.

---

## 6. Linux Web 部署验收

### Iteration Linux Web Status

- [!] Docker runtime blocked: Docker Desktop Linux daemon is not running; docker version cannot connect.
- [ ] Linux production VM/CI, MySQL, Docker Worker image, Nginx/Caddy, and real browser were not run.
- [ ] Two-user workspace isolation, container cleanup, resource limits, and Worker-secret isolation remain unverified.
- **Conclusion:** Linux Web Ready is unverified; Windows WSL smoke or Java unit tests cannot release it.


### 6.1 必须明确支持的部署形态

- [ ] Linux 单用户 local：可使用 Docker Worker；`unsafe-local` 仅显式开发用途。
- [ ] Linux 私有团队 Web：Spring Boot + MySQL + Docker Worker + HTTPS reverse proxy。
- [ ] Linux 公网多用户 Web：每个运行使用项目级隔离 Worker，不能跑宿主机共享 Shell。
- [ ] README 和部署文档明确说明以上三种形态。
- [ ] 文档明确当前状态是 `Local Usable`、`Linux Web Ready` 还是未达标。

### 6.2 配置

- [ ] `SPRING_PROFILES_ACTIVE=production`。
- [ ] `LABEX_AGENT_DB_URL` 指向持久化 MySQL。
- [ ] `LABEX_AGENT_DB_USERNAME`、`LABEX_AGENT_DB_PASSWORD` 已配置且未进 Worker 环境。
- [ ] `LABEX_AGENT_JWT_SECRET` 使用随机强密钥。
- [ ] `LABEX_AGENT_SECRET_STORE_MASTER_KEY` 已配置。
- [ ] `LABEX_AGENT_PROJECT_BASE_PATH=/srv/labex-agent/workspaces` 或等价 Linux 路径。
- [ ] `LABEX_AGENT_UPLOAD_PATH=/srv/labex-agent/uploads` 或等价 Linux 路径。
- [ ] `LABEX_AGENT_WORKER_DOCKER_IMAGE` 是固定非-smoke tag/digest。
- [ ] `LABEX_AGENT_PERMISSION_PROFILE=opencode`。
- [ ] production 拒绝 `full_access`。
- [ ] `LABEX_AGENT_WEBSOCKET_ALLOWED_ORIGINS` 为实际 HTTPS 前端域名。
- [ ] CORS 不使用 `*` + credentials。
- [ ] 前端/API 域名、代理路径、context path 一致。

### 6.3 Worker 容器

- [ ] 运行用户不是 root。
- [ ] workspace 仅挂载当前项目。
- [ ] 容器没有控制面 Secret。
- [ ] 网络关闭时 offline build/test 可用。
- [ ] `opencode` 网络配置开启时 `npm install` 可用。
- [ ] `--cap-drop ALL` 或等价能力限制已验证。
- [ ] `no-new-privileges` 已验证。
- [ ] read-only rootfs + 明确 writable tmp/cache/workspace 已验证。
- [ ] CPU、内存、PID、timeout、并发限制已验证。
- [ ] timeout/cancel 后容器已删除。
- [ ] 孤儿容器、孤儿进程和临时目录有清理策略。

**Evidence on Linux:**

```bash
docker build -t labex-agent-sandbox:opencode-2026-08-12 -f docker/sandbox/Dockerfile .
docker inspect labex-agent-sandbox:opencode-2026-08-12
docker run --rm --network none --read-only --tmpfs /tmp:rw,noexec,nosuid \
  --cap-drop ALL --security-opt no-new-privileges:true \
  -v "$PWD/scripts/acceptance/fixtures/opencode-shell-fixture:/workspace" \
  -w /workspace labex-agent-sandbox:opencode-2026-08-12 \
  bash -lc 'id; env | sort; npm test'
```

### 6.4 Reverse proxy/SSE/WebSocket

- [ ] Nginx/Caddy 提供 `frontend/dist`。
- [ ] SPA fallback 到 `index.html`。
- [ ] `/api/` 正确转发到 Spring Boot `/api/`。
- [ ] SSE 不缓冲，read timeout 足够长。
- [ ] WebSocket Upgrade 正常；如 PTY 未恢复，UI/文档明确使用 managed terminal。
- [ ] HTTPS 正常，HTTP 重定向或关闭。
- [ ] backend 8080 不直接暴露公网。
- [ ] 上传大小、请求超时、连接数有明确配置。
- [ ] 浏览器 console 无错误，Network 无未处理 4xx/5xx。

### 6.5 Linux 浏览器真实流程

- [ ] 浏览器注册。
- [ ] 浏览器登录。
- [ ] 创建项目/上传 fixture。
- [ ] 发起 Agent 任务。
- [ ] Agent 读取文件。
- [ ] Agent 修改文件。
- [ ] Agent 执行正常 Shell。
- [ ] Agent 执行依赖安装。
- [ ] Agent 运行 build/test。
- [ ] Agent 读取失败并自动修复。
- [ ] Agent 重跑成功。
- [ ] 页面显示 Tool Part 和 verification evidence。
- [ ] 页面刷新后 timeline 不重复。
- [ ] SSE 断线重连后 cursor 正确。
- [ ] 后端重启后 task/interaction/context 可以继续。
- [ ] 项目 A/B 并发时 workspace 不串。

---

## 7. Context/Recovery 验收

- [ ] 新 conversation 不读取旧 conversation 的 provider history。
- [ ] 新 session 不接收旧 session 的 SSE/event。
- [ ] 同一 assistant turn 的多个 tool calls 全部先持久化，再按稳定顺序执行。
- [ ] approval 等待不会发送伪终态 DONE。
- [ ] approval 批准只执行一次。
- [ ] approval 拒绝能让模型得到明确结果。
- [ ] approval 过期能进入明确终态。
- [ ] 用户刷新后从 durable task/event/part 继续。
- [ ] 后端重启后从 durable transcript 重新构建 provider 请求。
- [ ] compaction 后 tool-call/tool-result 协议仍合法。
- [ ] compaction 后 recent tail 从完整 turn/token budget 得出。
- [ ] context overflow 是有限重试和明确失败，不无限发送同一 prompt。
- [ ] 大工具输出不把 provider context 撑爆。
- [ ] 模型能看到上一次命令的真实 stderr/exit code。
- [ ] 最终回答不把 unavailable/skipped/cancelled 说成 PASS。

**Evidence:**

```powershell
Set-Location D:\LabexAgent\backend
mvn -q '-Dtest=AgentTranscriptProjectionServiceTest,AgentTranscriptProjectionContractTest,AgentRunPartServiceTest,AgentCompactionServiceTest,AgentRunStateMachineTest,AgentRunRecoveryServiceTest' test
```

另附真实 restart/browser 日志；focused test 不能替代 restart/browser evidence。

---

## 8. 回归验证命令

### Backend focused

```powershell
Set-Location D:\LabexAgent\backend
mvn -q '-Dtest=ShellToolContractTest,ShellCommandFactoryTest,PermissionServicePersistenceTest,PermissionServiceCancellationTest,LabexSystemPromptTest,RunCompletionEvidenceServiceTest,WslSandboxWorkerTest,DockerSandboxWorkerSmokeTest' test
```

### Backend full

```powershell
Set-Location D:\LabexAgent\backend
mvn test
```

### Frontend

```powershell
Set-Location D:\LabexAgent\frontend
npm install
npm test
npm run build
```

### Local runtime

```powershell
Set-Location D:\LabexAgent
pwsh -NoLogo -NoProfile -File .\scripts\acceptance\run-all.ps1 -SkipBrowser
```

### Browser runtime

```powershell
Set-Location D:\LabexAgent
pwsh -NoLogo -NoProfile -File .\scripts\acceptance\run-all.ps1
```

### Linux runtime

```bash
./deploy/linux/smoke.sh
./scripts/acceptance/linux-runtime.sh
```

### Git/document quality

```powershell
Set-Location D:\LabexAgent
git diff --check
```

- [ ] 所有命令实际运行过。
- [ ] 所有结果保存了 stdout/stderr 或结构化 evidence。
- [ ] 失败项没有被勾选为通过。
- [ ] 未验证项明确写原因和下一步。
- [ ] 运行中的 JVM/container revision 与源码 revision 对得上。

---

## 9. 最终交付报告模板

```text
老铁，OpenCode-first 可用性迭代状态：<Local Usable | Linux Web Ready | Public Multi-user Ready | 未达标>

规格：D:\LabexAgent\docs\superpowers\plans\2026-08-12-opencode-first-agent-usability\spec.md
任务：D:\LabexAgent\docs\superpowers\plans\2026-08-12-opencode-first-agent-usability\tasks.md
清单：D:\LabexAgent\docs\superpowers\plans\2026-08-12-opencode-first-agent-usability\checklist.md

已完成：
- ...

OpenCode 参考：
- D:\opencode\opencode-dev\packages\opencode\src\tool\shell.ts -> ...
- ...

修改文件：
- D:\LabexAgent\...

验证：
- 命令：...
- 结果：...
- 证据：...

未完成/风险：
- ...

下一任务：Tn.m
```
