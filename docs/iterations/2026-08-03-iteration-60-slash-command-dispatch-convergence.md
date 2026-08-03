# 第 60 轮：Slash Command 所有权与分发收敛

- 日期：2026-08-03
- 分支：`codex/agent-tool-reliability`
- 前置提交：`d167b5e refactor: remove dead command runtime`
- 状态：已完成（代码、测试与本文档同一提交）

## 1. 本轮目标

把 Slash Command 从“前端看到 `/` 就把任意字符串改写后发给模型”的 demo 路径，收敛为显式、可验证、可恢复的三类分发协议：

1. `AGENT_PROMPT`：只负责把服务端模板解析成 Provider 有效提示词，随后进入唯一 Agent durable runtime；文件、Git、测试、构建等副作用仍必须由 Agent Tool + permission/approval 执行。
2. `CLIENT_ACTION`：只调用前端已有 UI 或专用 API，不创建 Agent task，不污染 conversation transcript，不发送给模型。
3. `UNAVAILABLE`：明确拒绝且不出现在可用命令目录，禁止静默回退成普通 prompt。

同时解决一个此前没有被建模的问题：用户应该在会话中看到原始 Slash Command，而 Provider transcript 必须保存服务端解析后的有效提示词。两者不能互相覆盖，也不能由客户端伪造不一致语义。

## 2. 文档与参考实现

本轮先阅读仓库架构约束和现有命令调用链，再对照本机 OpenCode 实现：

- `D:/opencode/opencode-dev/packages/app/src/context/command.tsx`
- `D:/opencode/opencode-dev/packages/app/src/pages/session/use-session-commands.tsx`

采用的原则不是复制 OpenCode UI，而是学习其所有权划分：命令目录是声明式元数据；会话、模型、主题等客户端动作有明确 handler；需要 Agent 能力的命令进入统一 session/runtime；未知动作失败关闭，不用一个兜底字符串执行器伪装成功。

## 3. 原始问题证据

改造前存在以下架构和体验问题：

1. 前端维护一份 72 项硬编码命令表，后端又维护另一份注册表，两者会漂移。
2. 除 `/compact`、`/summarize` 外，大多数 Slash Command 都会调用命令端点并最终作为 prompt 发送给模型；`/new`、`/themes`、`/models` 等 UI 动作没有真实所有者。
3. 命令解析端点会建立/取得 conversation 并写消息或事件，单纯“解析命令”会污染 durable transcript。
4. 不可用命令可能退化成普通 prompt，用户会看到“似乎执行了”，实际只是模型收到一段文字。
5. `/review src/App.vue` 的模板没有 `$ARGUMENTS`，参数在解析时丢失。
6. 若直接把模板发送给 Agent，刷新后用户消息会显示整段内部模板，而不是原始 `/review src/App.vue`。
7. 客户端动作完成后命令面板生命周期没有明确定义；初版实现中 `/help` 打开面板后又被统一关闭，仍属于假接通。

## 4. 设计与实现

### 4.1 后端命令协议

`CommandInfo` 新增显式协议字段：

- `CommandDispatch`：`AGENT_PROMPT`、`CLIENT_ACTION`、`UNAVAILABLE`；
- `ClientAction`：受控枚举，不接受任意 JavaScript 方法名或字符串执行器；
- `aliases`：稳定字符串列表；
- `unavailableReason`：不可用诊断。

canonical constructor 同时强制不变量：禁止 null dispatch；`AGENT_PROMPT` 必须有非空模板；`CLIENT_ACTION` 必须有受控 action；`UNAVAILABLE` 必须有明确原因；互相矛盾的模板/action/reason 组合直接拒绝，不能默认为 Agent prompt。

`CommandRegistry` 对全部内置命令显式分类：

- 会话、模型、主题、面板、帮助和退出等映射为 `CLIENT_ACTION`；
- review、分析、Git、测试、构建、部署等仍为 `AGENT_PROMPT`，副作用必须回到 Tool 权限链；
- 尚无真实能力的 rename、share、timeline、editor 等映射为 `UNAVAILABLE`。

### 4.2 纯解析 API

- 新增 `GET /student/projects/{projectId}/agent/commands`，向前端提供当前可用 typed catalog。
- 保留 `POST /student/projects/{projectId}/agent/commands` 作为纯 resolver：只验证项目归属、解析 canonical command/alias、返回 dispatch/action/template；不创建 conversation、不保存用户消息、不发送 `COMMAND` event。
- 日志只记录 canonical command、dispatch 和参数长度，不记录原始参数内容。

### 4.3 用户可见输入与 Provider transcript 分离

`AgentStreamRequest` 新增 `displayMessage`：

- `message`：服务端模板解析后的 Provider 有效提示词；
- `displayMessage`：用户原始输入，例如 `/review src/App.vue`。

公开 stream 入口在启动 Agent 前调用 `AgentCommandService.prepareAgentStreamRequest()`：

1. 重新从 `displayMessage` 解析 canonical command 和参数；
2. 只允许 `AGENT_PROMPT` 进入 Agent runtime；
3. 服务端重新解析模板；
4. 要求客户端提交的 `message` 与服务端结果完全一致，不一致直接拒绝；
5. 对请求做规范化后再进入 `AgentLoopEngine`。

`AgentLoopEngine` 的写入边界：

- 会话标题、用户可见 `AgentMessage` 和运行日志使用 `displayMessage`；
- durable `AgentRunMessage` Provider transcript、上下文预算、恢复 objective 使用有效 `message`；
- 因此刷新后仍显示原始 Slash Command，而重启恢复和 Provider 重放仍使用真实模板语义。

### 4.4 前端 typed dispatcher

新增 `slashCommandRuntime.js`：

- 解析命令与参数；
- 标准化服务端目录；
- 仅按服务端 `dispatch` 分发；
- `CLIENT_ACTION` 必须存在已注册 handler；
- `AGENT_PROMPT` 必须返回非空模板；
- 未知、不可用、缺少 handler、目录加载失败全部 fail closed，禁止回退成原始 prompt。

`CloudWorkspace.vue` 删除硬编码命令目录，启动时并行加载服务端 catalog。已真实接通：

- 会话列表、新会话、压缩、fork、复制、Markdown 导出；
- 时间戳/思考过程切换；
- 模型、主题、Changes、Skills、MCP、Usage、Context 面板；
- 项目状态、帮助面板、退出工作区。

`/help` 的面板时序修复为：先关闭输入 `/help` 时产生的旧面板，再执行客户端 handler；handler 可按需重新打开并保持目标面板。

### 4.5 `/review` 参数修复

`templates/command/review.txt` 现在显式包含 `$ARGUMENTS`，并对空参数给出默认 review scope，保证 `/review src/App.vue` 不丢目标。

### 4.6 验收运行时稳定性

真实浏览器验收过程中发现 Java HotSpot native OOM：

- 崩溃文件：`hs_err_pid47976.log`；
- 错误：`Native memory allocation (malloc) failed`，发生于 C2 CompilerThread；
- 主机物理内存约 16 GB，但当时可提交虚拟内存约 2.5 GB；
- 验收 JVM 没有任何内存边界，HotSpot ergonomics 给出约 4 GB 最大堆，即使实际 Java heap 使用很低，仍可能在 native/code-cache 提交时崩溃。

只对 `scripts/acceptance/browser-runtime.ps1` 的隔离验收后端增加：

- `-Xms64m`
- `-Xmx512m`
- `-XX:MaxMetaspaceSize=256m`
- `-XX:ReservedCodeCacheSize=128m`

没有改生产启动参数。

第一次保存 `.ps1` 时意外移除了 UTF-8 BOM，Windows PowerShell 5 将中文脚本按本地代码页解析并报语法错误。随后补充字节级 BOM 回归并恢复 BOM，防止再次发生。

### 4.7 提问卡 durable identity readiness

最终 `run-all` 曾在 compaction question 卡死：task 持续处于 `waiting_user`，前端没有发出回答 API。日志证明 `TOOL_CALL` 先让提问卡可见，而携带 `requestId` / `interactionId` 的 durable `USER_QUESTION` 事件稍后到达。用户若在这段窗口点击提交，`useAgentInteraction()` 返回 `request_missing`，旧 UI 会静默忽略，表现为“按钮点了没有反应”。

修复后：

- `ToolCallCard` 只有在 durable request identity 到达后才启用发送/取消按钮；
- 未就绪时显示“正在同步可恢复提问请求”；
- `emitQuestion()` 也在组件边界防止绕过 disabled 状态；
- `CloudWorkspace` 对 `request_missing` 给出明确提示，不再静默；
- 浏览器验收等待 `.tc-approval-btn.primary:not(:disabled)`，验证的是可提交状态而不只是 DOM 卡片存在。

## 5. TDD / 回归记录

### 5.1 `/review` 参数

- RED：`AgentCommandDispatchContractTest` 3 项中 1 项失败，模板未包含 `src/App.vue`。
- GREEN：模板接入 `$ARGUMENTS` 后聚焦测试通过。

### 5.2 可见输入 / Provider prompt

- RED：契约测试因缺少 `displayMessage`、`userVisibleMessage()` 和 stream 准备校验而编译失败。
- GREEN：加入双轨请求和服务端一致性校验后，原始命令与有效模板分别进入正确事实源；伪造不一致 prompt 被拒绝。

### 5.3 验收 JVM 内存边界

- RED：`runtime-config.test.mjs` 期望四个 JVM 限制，13 项中 1 项失败。
- GREEN：验收脚本加入限制后 13/13 通过。

### 5.4 Windows PowerShell BOM

- RED：新增字节级断言后，实际文件头为 `[91, 67, 109]`，不是 UTF-8 BOM `[239, 187, 191]`。
- GREEN：恢复 BOM 后验收单测 14/14，通过 PowerShell 实际启动。

### 5.5 `/help` 面板生命周期

- RED：结构回归证明 `closeCommandPalette()` 位于 `resolveSlashCommand()` 之后，`/help` 会被二次关闭。
- GREEN：调整为先关旧面板、后执行 handler；聚焦测试 4/4；浏览器验收返回 `slashHelpPalette: true`。

### 5.6 typed command 元数据不变量

- RED：构造 `dispatch=null`、缺 action、空模板或空不可用原因时没有抛错，聚焦测试 5 项中 1 项失败。
- GREEN：canonical constructor 严格验证后 5/5 通过，72 个内置命令正常注册；后端全量增至 933 项并通过。

### 5.7 提问 request identity 竞态

- RED：新增 readiness 契约 2/2 失败，证明按钮未禁用、无同步提示、`request_missing` 被静默忽略、浏览器未等待可提交状态。
- 真实失败：仓库级 browser acceptance 在 task 8 持续 `waiting_user`，后端日志无回答请求，handoff 记录 `Timed out waiting for question durable decision`。
- GREEN：组件、workspace handler 和浏览器等待条件修复后聚焦 2/2、前端全量 203/203；目标浏览器 runId=`115714fc61e542b799567d6ea12a8f73` 和最终仓库级 browser runId=`cec44638a48344c1ac9f236f53144052` 均通过。

## 6. 修改文件

### 后端

- `backend/src/main/java/com/labex/labexagent/command/CommandInfo.java`
- `backend/src/main/java/com/labex/labexagent/command/CommandRegistry.java`
- `backend/src/main/java/com/labex/labexagent/controller/StudentAgentController.java`
- `backend/src/main/java/com/labex/labexagent/dto/AgentStreamRequest.java`
- `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- `backend/src/main/java/com/labex/labexagent/service/AgentCommandService.java`
- `backend/src/main/resources/templates/command/review.txt`
- `backend/src/test/java/com/labex/labexagent/service/AgentCommandArchitectureContractTest.java`
- `backend/src/test/java/com/labex/labexagent/service/AgentCommandDispatchContractTest.java`
- `backend/src/test/java/com/labex/labexagent/service/AgentCommandServicePromptOptimizationTest.java`

### 前端与验收

- `frontend/src/api/index.js`
- `frontend/src/components/cloud/ToolCallCard.vue`
- `frontend/src/components/cloud/ToolCallCardQuestionReadiness.test.mjs`
- `frontend/src/composables/slashCommandRuntime.js`
- `frontend/src/composables/slashCommandRuntime.test.mjs`
- `frontend/src/views/CloudWorkspace.vue`
- `frontend/src/views/contextManagementPresentation.test.mjs`
- `frontend/scripts/acceptance/agent-browser.mjs`
- `frontend/scripts/acceptance/runtime-config.test.mjs`
- `frontend/package.json`
- `scripts/acceptance/browser-runtime.ps1`

### 文档

- `docs/iterations/2026-08-03-iteration-60-slash-command-dispatch-convergence.md`

## 7. 验收结果

### 7.1 源码与构建门槛

1. `cd backend && mvn test`
   - `Tests run: 933, Failures: 0, Errors: 0, Skipped: 8`
   - `BUILD SUCCESS`
2. `cd frontend && npm test`
   - `tests 203, pass 203, fail 0`
3. `cd frontend && npm run build`
   - Vite production build 成功；
   - `CloudWorkspace-0EtCDlh6.js`：1,462,008 / 1,500,000 bytes；
   - `index-DQjVlW7s.js`：1,259,608 / 1,300,000 bytes；
   - `TerminalPanel-BotMVej6.js`：380,367 / 400,000 bytes。
4. `cd frontend && npm run test:acceptance:unit`
   - `tests 14, pass 14, fail 0`

### 7.2 仓库级系统验收

命令：

```powershell
./scripts/acceptance/run-all.ps1 `
  -BackendPort 18108 `
  -FrontendPort 13028 `
  -CdpPort 19250 `
  -TimeoutSeconds 240 `
  -RestartBrowserBackend
```

结果：

- package：通过；
- acceptance unit tests：通过；
- backend restart acceptance：通过，runId=`176c1a8f901645f090e5435962cc848d`；
- browser runtime：通过，runId=`cec44638a48344c1ac9f236f53144052`；
- 汇总：`package=true`、`acceptanceUnitTests=true`、`backendRuntime=true`、`browserRuntime=true`。

### 7.3 提交前审查与提问竞态修复后的浏览器验收

`/help` 面板修复后先由 runId=`d2fce4caf9eb457d9af21d5a87a1c74e` 验证。随后一次仓库级验收暴露“提问卡可见早于 durable request identity”的真实竞态；修复后目标浏览器 runId=`115714fc61e542b799567d6ea12a8f73` 通过，最终仓库级浏览器 runId=`cec44638a48344c1ac9f236f53144052` 再次通过：

- `typedSlashCommandCatalog=true`；
- `slashPromptResolutionPure=true`；
- `slashAgentPromptDisplayStable=true`；
- `slashClientUiActions=true`；
- `slashHelpPalette=true`；
- `slashConversationCopy=true`；
- `slashConversationExport=true`；
- `slashNewConversationIsolation=true`；
- `slashManualCompaction=true`；
- `slashConversationFork=true`；
- 提问回复、审批刷新恢复、多工具批次、durable Provider Message/Part、compaction、Provider 中断、环境恢复、完成证据均通过；
- 两次后端重启投影/交互恢复通过；
- `consoleErrors=0`、`networkErrors=0`。

真实系统断言还确认：

1. `/review src/App.vue` 在 UI 和 conversation history 中保持原始用户输入；
2. 同一 task 的 durable Provider user message 包含服务端展开后的 `Requested review target` 和 `src/App.vue`；
3. 页面刷新后仍显示原始 Slash Command，不把内部模板伪装成用户消息；
4. 命令纯解析前后 conversation 数量不变；
5. `/rename` 明确返回 `UNAVAILABLE`，没有进入 Agent；
6. copy 写入真实剪贴板，export 生成并读取真实 Markdown 下载文件；
7. 受控内存验收后端完成两次重启和完整链路，没有再次生成 HotSpot OOM；
8. 提问卡只有在 durable request identity 到达后才可提交，compaction question 不再因早点击永久停在 `waiting_user`。

## 8. 风险与后续

1. rename、share、timeline、editor 等命令仍明确 `UNAVAILABLE`；本轮选择诚实隐藏，而不是用 prompt 假装实现。
2. `CloudWorkspace.vue` 仍然偏大；本轮只加入薄 typed dispatcher，没有在组件内创建第二套运行状态。后续可按既有收敛计划继续抽离 UI action handlers，但不得改变服务端 dispatch 权威性。
3. Agent task 的 recovery objective 保留 Provider 有效提示词，这是为了在 transcript 尚未完整写入前仍可安全恢复；用户可见事实仍以 conversation `AgentMessage` 的原始命令为准。该双轨语义需要后续迁移保持。
4. 命令目录加载失败时前端会安全禁用 Slash Command；这是故意的 fail-closed 行为，不应改回本地硬编码 fallback。
5. 本轮只限制验收后端 JVM，不代表生产 JVM 已完成容量规划；生产参数应结合真实负载、容器/主机限制单独配置。

## 9. 原工作区改动保留

本轮未修改、未暂存以下原有/无关内容：

- `backend/src/main/resources/application-acceptance.yml`
- `.codex-tmp/`
- `docs/agent-context-provider-smoke-test.md`
- `docs/coding-agent-engineering-roadmap.md`
- `docs/coding-agent-industrialization/`
- 既有未跟踪 `docs/superpowers/plans/*`
- `docs/superpowers/specs/`
