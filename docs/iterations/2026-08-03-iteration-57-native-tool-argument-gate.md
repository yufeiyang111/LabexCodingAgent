# 第 57 轮：原生工具调用参数门禁与批次终态

- 日期：2026-08-03
- 状态：已完成
- 收敛依据：`docs/superpowers/plans/2026-07-29-agent-runtime-state-convergence.md`
- 基线提交：`d9fe0ff fix: harden text tool call recovery`

## 1. 本轮目标

收敛 OpenAI-compatible 原生 `tool_calls` 的参数解析和执行边界：

1. `function.arguments` 必须被分类为合法 object、缺失、非法 JSON 或非 object，禁止解析失败静默降级为 `{}`；
2. 原生调用与文本兼容调用共享本轮工具暴露、运行模式、注册表和 input schema 门禁；
3. assistant 原始 `tool_calls` 作为 Provider transcript 事实完整持久化，原始 `toolCallId` 和批次顺序不得改写；
4. 非法调用不得进入 loop guard、权限审批和真实工具执行，但必须产生与原始 `toolCallId` 一一对应的失败 Tool Part 和 `role=tool` 结果；
5. 同一批次中的其它合法调用继续按顺序执行，所有 Part 都必须进入明确终态；
6. 连续非法原生调用采用有限恢复，不依赖无限总迭代；
7. 日志、SSE 和失败详情不得回显无法解析的原始 arguments。

## 2. 已确认的根因

`AgentLoopEngine.parseArgs(...)` 捕获所有异常并返回空对象。原生批次在 schema 校验之前就用该空对象发布 `TOOL_CALL`、创建 pending Tool Part，之后又把同一空对象传给 loop guard 和真实工具。结果是：

- Provider 的截断/非法 JSON 与真实 `{}` 无法区分；
- 必填参数丢失可能延迟到工具内部才失败，甚至被某些宽松工具接受；
- 持久化 Tool Part 无法解释模型到底给了合法空对象还是损坏参数；
- 非法调用可能进入审批或产生副作用；
- 批次剩余调用的终态依赖中途分支，缺少统一 admission 结果。

## 3. 计划与验收

1. 先增加 typed arguments parser 和 native admission 的失败回归测试；
2. 让 `AgentToolTurnExecutor` 成为原生/文本参数 schema 门禁的唯一执行入口；
3. 主循环先对整批调用做 admission，再一次性写入 pending 或 error Tool Part；
4. 执行阶段对非法调用只写失败 tool result，对合法调用维持原顺序执行；
5. 增加真实 acceptance Provider 场景：同一 assistant turn 包含一个非法调用和一个合法调用；
6. 验证非法调用没有执行事件，合法调用成功，两个原始 ID 均有终态 Part，任务最终完成；
7. 运行聚焦回归、后端全量、前端全量/构建、真实后端系统验收和真实浏览器验收。

## 4. RED 与调试证据

1. 首次聚焦测试在 test compile 阶段失败：缺少 `ToolCallArgumentsParser`；
2. 同一次 RED 还明确缺少 `AgentToolTurnExecutor.resolveNative(...)`，证明原生调用没有类型化 admission API；
3. 回归输入覆盖空白 arguments、截断 JSON、JSON array、缺少 required 字段、未暴露工具和合法 object；
4. 实施过程中一次未命中的 Windows 正则替换把 `AgentToolTurnExecutor.java` 临时写成 0 字节；已立即从基线提交精确恢复该单文件，并改为“先计算、校验长度、再一次写入”的替换方式，未触碰其它工作区内容；
5. 首次真实后端验收失败于验收脚本读取不存在的 `sequenceNumber` DTO 字段；核对当前投影后改用真实公开的 `partId` 验证持久化插入顺序；
6. 首次浏览器验收暴露旧 compaction fixture 使用未声明 `padding` 字段。新门禁正确以 `unknown_field` 拒绝，但 fixture 因恢复标记被压缩而再次提问；
7. 把 fixture 改成合法但超大的 `path` 后，路径不存在导致 `list_files` 失败，完成证据门禁继续拒绝最终回答，Provider transcript 从 9 条增长到 13 条；日志证明这不是重启重复，而是任务尚未终止；
8. 最终使用 15,000 个 `./` 组成约 30 KB 的合法 `path`：参数能触发 compaction，又会被 `normalizeRelativePath(...)` 规范化到工作区根目录并真实执行成功；浏览器验收同时新增“比较重启快照前必须等待任务持久化终态”的约束。

## 5. 实施内容

### 5.1 类型化 arguments parser

新增 `ToolCallArgumentsParser`：

- `VALID`：完整 JSON object；
- `MISSING`：null、空串或纯空白；
- `INVALID_JSON`：截断或非法 JSON；
- `NON_OBJECT`：合法 JSON，但顶层不是 object。

失败结果只返回空的公开参数对象和结构化 reason code，不保留到日志、SSE 或 Tool Part input。Provider 原始 assistant `tool_calls` 仍原样持久化，因为它是协议事实而非可执行输入。

### 5.2 原生与文本共享 schema 门禁

`AgentToolTurnExecutor` 新增 `resolveNative(...)` 和统一的 `resolveInput(...)`：

1. 检查工具是否在本轮模型请求中暴露；
2. 检查当前 Agent mode 是否允许；
3. 检查工具是否存在于注册表；
4. 检查 required、未知字段、基础类型、嵌套 object/array 和 enum；
5. 返回携带规范化参数、工具、拒绝结果和 reason code 的 `ToolInputResolution`。

文本兼容调用的 `resolveRecovered(...)` 改为复用同一入口，不再维护第二套 schema 逻辑。

### 5.3 整批 admission 与确定性终态

`AgentLoopEngine` 在执行任何原生调用之前：

1. 持久化完整 assistant `tool_calls` Provider message；
2. 对同一 turn 的全部调用生成 `NativeToolAdmission`；
3. 合法调用先写 pending Tool Part；
4. 非法调用直接写 error Tool Part；
5. 执行循环遇到非法 admission 时，只写 `OBSERVE` 和原始 `toolCallId` 对应的 `role=tool` 失败结果，然后继续后续合法调用；
6. loop guard、审批和 `execTool(...)` 只接收已通过 admission 的参数；
7. 环境阻塞、循环保护或缺少计划导致批次提前停止时，合法剩余调用写 skipped，已 error 的调用不被 skipped 覆盖；Provider tool result 同样按 admission 的真实结果投影；
8. 连续两轮含非法原生输入时进入明确失败终态，reason code 为 `native_tool_input_recovery_exhausted`。

### 5.4 验收 fixture 与浏览器竞态修复

- `AcceptanceScriptedProvider` 新增 `[acceptance:native-tool-input]`：同一批次先发截断 JSON，再发合法 `list_files`；
- 系统验收断言非法 ID 没有 `TOOL_EXECUTION_STARTED`，合法 ID 有执行事件，两个 Tool Part 分别为 `error` / `completed`，且 `partId` 顺序与 `toolCallIndex` 一致；
- compaction 大参数 fixture 不再依赖 schema 外字段；
- 浏览器验收在记录 Provider message 基线前等待 compaction task 进入持久化终态，避免把“最终文本已显示”误当作“任务已完成”。

## 6. 修改文件

### 运行时代码

- `backend/src/main/java/com/labex/labexagent/runtime/ToolCallArgumentsParser.java`
- `backend/src/main/java/com/labex/labexagent/runtime/AgentToolTurnExecutor.java`
- `backend/src/main/java/com/labex/labexagent/runtime/ToolArgumentSchemaValidator.java`
- `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- `backend/src/main/java/com/labex/labexagent/llm/AcceptanceScriptedProvider.java`

### 回归测试和验收

- `backend/src/test/java/com/labex/labexagent/runtime/ToolCallArgumentsParserTest.java`
- `backend/src/test/java/com/labex/labexagent/runtime/AgentToolTurnExecutorTest.java`
- `backend/src/test/java/com/labex/labexagent/runtime/AgentLoopEngineWorkspacePauseContractTest.java`
- `backend/src/test/java/com/labex/labexagent/llm/AcceptanceScriptedProviderTest.java`
- `scripts/acceptance/agent-runtime.ps1`
- `frontend/scripts/acceptance/agent-browser.mjs`

## 7. GREEN 与真实验收

### 7.1 后端全量

```text
cd D:\LabexAgent\backend
mvn test
924 tests, 0 failures, 0 errors, 8 skipped
BUILD SUCCESS
```

### 7.2 前端全量、acceptance 单测和生产构建

```text
cd D:\LabexAgent\frontend
npm test
196 tests, 196 passed

npm run test:acceptance:unit
12 tests, 12 passed

npm run build
build passed
CloudWorkspace: 1,463,644 / 1,500,000 bytes
index:          1,259,534 / 1,300,000 bytes
TerminalPanel:    380,367 /   400,000 bytes
```

### 7.3 真实后端系统验收

```text
.\scripts\acceptance\agent-runtime.ps1 -TimeoutSeconds 150
runId: 1549b19319fe43628a0ee32c5a9f70b6
nativeToolInputGate: true
strictTextToolFallback: true
authoritativeModelRetryProjection: true
authoritativeFailureProjection: true
authoritativeCancellationProjection: true
cleanup: true
```

脚本实际启动隔离 H2 和打包后的 Spring Boot JAR。新场景证明：截断 arguments 没有进入工具执行，同批合法调用真实执行，两个原始 `toolCallId` 都有正确持久化终态，任务最终完成；既有审批、重启、压缩、失败、取消和终态投影均未回归。

### 7.4 真实浏览器验收

```text
.\scripts\acceptance\browser-runtime.ps1 -RestartBackendForAcceptance -TimeoutSeconds 150
runId: 215623d262ff4db4bbb3b5a5998ae605
conversationIsolation: true
refreshReplayDeduplicated: true
permissionApprovalRefreshRecovery: true
multiToolPermissionBatchProtocolComplete: true
durableCompaction: true
restartProjectionVerified: true
restartInteractionVerified: true
consoleErrors: 0
networkErrors: 0
```

该验收真实启动 Vite、浏览器、隔离数据库和两次后端重启，确认本轮协议收紧没有破坏 SSE 实时投影、刷新恢复、审批组件、问题回复、compaction 重放和前端错误策略。

## 8. 风险与后续

1. 当前 `ToolArgumentSchemaValidator` 实现的是项目所需的确定性 JSON Schema 子集；尚未覆盖 `oneOf`、`anyOf`、`pattern`、数值范围和字符串长度等高级约束。后续如果 MCP 或 Provider schema 使用这些关键字，应扩展同一个 validator，而不是在工具内部再建第二套校验；
2. 连续非法轮次按“一个 assistant turn 至少含一个非法调用”计数。混合批次中的合法调用仍会执行，但如果模型连续两轮继续夹带非法调用，任务会停止。这是有意的安全策略；
3. 原始非法 arguments 仍保存在 Provider assistant transcript 中，用于审计和协议重放；公开 Tool Part、SSE、运行日志和失败详情只保留安全参数与 reason code；
4. `backend/src/main/resources/application-acceptance.yml` 是本轮开始前已有的工作区状态，本轮未暂存、未提交。

## 9. 结论

原生 structured tool call 不再是“解析失败就当 `{}` 继续执行”的宽松入口。Provider 协议事实、可执行参数、持久化 Tool Part 和前端投影现在有清晰边界：非法调用可审计但不可执行，合法同批调用不被误伤，恢复有上限，重启后仍能按原始 ID 和顺序解释整批结果。