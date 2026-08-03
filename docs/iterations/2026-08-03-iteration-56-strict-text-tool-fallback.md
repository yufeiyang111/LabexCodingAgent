# 第 56 轮：严格文本工具调用恢复边界

- 日期：2026-08-03
- 状态：已完成
- 收敛依据：`docs/superpowers/plans/2026-07-29-agent-runtime-state-convergence.md`
- 基线提交：`d453c0f fix: preserve durable model retry projection`

## 1. 本轮目标

收敛 Provider 未使用原生 tool call、而把工具调用写进普通文本时的兼容恢复路径：

1. 原生结构化 tool call 保持第一优先级；
2. 文本兼容只接受明确、完整、唯一、独占整条模型输出的 `<tool_call>`、`<invoke>` 或 `tool_call` fenced envelope；
3. 普通自然语言、代码示例、中文描述、任意嵌入 JSON 和说明文字中的协议示例不得触发工具执行；
4. 解析结果必须区分 `NONE / VALID / INCOMPLETE / AMBIGUOUS / INVALID`；
5. 恢复出的工具必须属于本轮已暴露 schema，并通过模式、注册表、required、字段白名单和基础 JSON 类型校验；
6. 不完整、歧义、非法或 schema 不匹配的文本调用不得写入 pending Tool Part，更不得进入权限审批和工具执行；
7. 兼容恢复失败采用有限重试，不能依赖总迭代数无限循环；
8. 日志不得输出原始参数；显式工具信封不得作为 `FINAL_DELTA` 暴露给前端。

## 2. 发现的真实问题

### 2.1 普通文本可以被猜成副作用工具调用

旧 `ToolCallExtractor` 会扫描：

- 任意 `read_file(`、`shell(` 等字符串；
- 中文“调用工具”；
- 普通正文中的 `"tool"` JSON；
- 第一个看起来像 JSON object 的片段。

因此代码示例、解释文字或模型复述协议都可能被误判成真实工具调用。

### 2.2 非法参数会静默降级为 `{}`

旧路径先猜工具名，再由 `AgentLoopEngine.parseArgs(...)` 解析参数。解析失败时该方法返回空对象，主循环随后仍写入 `TOOL_CALL`、pending Tool Part 并尝试执行。错误没有在副作用边界前失败。

### 2.3 恢复路径绕过本轮 schema 门禁

虽然 `AgentToolTurnExecutor.resolve(...)` 已检查本轮工具选择、运行模式和注册表，但文本恢复路径没有校验 required 字段、未知字段和 JSON 基础类型。一个名称合法但参数结构错误的恢复调用仍可能进入工具实现。

### 2.4 原始参数进入 INFO 日志

旧代码使用 `log.info("... args={}", invArgs)` 输出模型生成的完整参数；命令、路径或其它敏感内容可能进入服务器日志。

### 2.5 工具协议标签会先作为答案流到前端

`AgentModelTurnExecutor` 原先在完整响应结束前把所有 `text_delta` 直接投影成 `FINAL_DELTA`。即使主循环最终识别出 `<tool_call>`，浏览器也可能先短暂显示内部协议标签。

## 3. RED 证据

1. 新增类型化解析器和 schema 校验测试后，首次聚焦测试在 test compile 阶段失败：缺少 `ToolArgumentSchemaValidator`；
2. 新增流式投影边界测试后，首次聚焦测试再次在 test compile 阶段失败：缺少 `TextToolCallStreamBoundary`；
3. 回归用例明确覆盖修复前会误执行的文本：`read_file(...)` 示例、中文“调用工具”、正文中的 `"tool"` JSON；
4. 回归用例覆盖截断信封、多候选信封、非法 JSON、非 object payload、未闭合 `<parameter>`、说明文字中嵌入的显式信封；
5. 回归用例要求 split `<tool_call>` 流中不得出现 `FINAL_DELTA`，旧模型流执行器不满足该契约；
6. 前缀精度回归首次出现 1 项失败：`<tool_calligraphy>` 和 `tool_call_example` 被错误隐藏，证明流边界必须检查协议名后的合法分隔符。

## 4. 实施内容

### 4.1 类型化严格解析器

`ToolCallExtractor` 改为返回 `Extraction`：

- `NONE`：没有显式协议；按普通最终文本处理；
- `VALID`：只有一个完整、独占整条输出的显式信封；
- `INCOMPLETE`：信封或参数标签未闭合；
- `AMBIGUOUS`：存在多个候选；
- `INVALID`：JSON、名称、字段或外围正文不符合协议。

解析器不再维护工具名白名单，也不再扫描 `toolName(`、中文短语或任意正文 JSON。

### 4.2 参数 schema 门禁

新增 `ToolArgumentSchemaValidator`，在恢复调用写入 Tool Part 之前验证：

- required 字段；
- 未声明字段；
- object / array / string / boolean / number / integer / null；
- 嵌套 object 和 array；
- enum。

`AgentToolTurnExecutor.resolveRecovered(...)` 复用现有“本轮是否暴露、模式是否允许、注册表是否存在”检查，再执行参数 schema 校验。拒绝结果不会调用工具。

### 4.3 有限恢复与清晰终态

`AgentLoopEngine` 只执行 `VALID` 且通过门禁的恢复调用。其它候选：

1. 写入不包含原始参数的运行日志和 checkpoint；
2. 向模型追加一次原生 structured tool call 纠正提示；
3. 记录无进展；
4. 连续两次失败后写入 `ERROR`、权威 `RUN_STATE_FAILED`、失败 checkpoint、最终说明和 `DONE`，防止无限循环。

旧的 `extractToolName(...)`、`extractToolArgs(...)` 和原始 args INFO 日志已删除。

### 4.4 流式协议边界

新增 `TextToolCallStreamBoundary`：

- 普通回答一旦排除显式协议前缀就立即透传，保留实时体验；
- 分片到达的 `<tool_call>`、`<invoke>`、MiniMax 前缀和 `tool_call` fenced envelope 被留在模型结果中供主循环解析；
- 工具信封不会被投影为用户可见 `FINAL_DELTA`；
- 类似 `<toolbox>`、`<tool_calligraphy>` 和 `tool_call_example` 的普通正文在判定没有合法协议分隔符后立即释放。

### 4.5 真实验收场景

`AcceptanceScriptedProvider` 新增 `[acceptance:text-tool-fallback]`：

1. 首轮只输出显式文本信封，不发送原生 tool call；
2. 正式主循环解析并执行 `list_files`；
3. 使用 `recovered:v1:*` 稳定 toolCallId；
4. 持久化完成态 Tool Part；
5. 最终返回正常总结；
6. 验收脚本同时断言信封没有进入 `FINAL_DELTA`。

验收脚本修正了新增断言中最初写错的 task snapshot 路径和公开 Part 字段名，并保留原文件 UTF-8 BOM，确保 Windows PowerShell 5 能正确解析中文。

## 5. 修改文件

### 运行时代码

- `backend/src/main/java/com/labex/labexagent/runtime/ToolCallExtractor.java`
- `backend/src/main/java/com/labex/labexagent/runtime/ToolArgumentSchemaValidator.java`
- `backend/src/main/java/com/labex/labexagent/runtime/TextToolCallStreamBoundary.java`
- `backend/src/main/java/com/labex/labexagent/runtime/AgentToolTurnExecutor.java`
- `backend/src/main/java/com/labex/labexagent/runtime/AgentModelTurnExecutor.java`
- `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- `backend/src/main/java/com/labex/labexagent/llm/AcceptanceScriptedProvider.java`

### 测试和验收

- `backend/src/test/java/com/labex/labexagent/runtime/ToolCallExtractorTest.java`
- `backend/src/test/java/com/labex/labexagent/runtime/ToolArgumentSchemaValidatorTest.java`
- `backend/src/test/java/com/labex/labexagent/runtime/TextToolCallStreamBoundaryTest.java`
- `backend/src/test/java/com/labex/labexagent/runtime/AgentToolTurnExecutorTest.java`
- `backend/src/test/java/com/labex/labexagent/runtime/AgentModelTurnExecutorTest.java`
- `backend/src/test/java/com/labex/labexagent/runtime/AgentLoopEngineStreamingContractTest.java`
- `backend/src/test/java/com/labex/labexagent/llm/AcceptanceScriptedProviderTest.java`
- `scripts/acceptance/agent-runtime.ps1`

## 6. GREEN 与真实验收

### 6.1 聚焦回归

```text
mvn -q '-Dtest=TextToolCallStreamBoundaryTest,AgentModelTurnExecutorTest,ToolCallExtractorTest,ToolArgumentSchemaValidatorTest,AgentToolTurnExecutorTest,AgentLoopEngineStreamingContractTest,AcceptanceScriptedProviderTest' test
54 tests, 0 failures, 0 errors, 0 skipped
```

### 6.2 后端全量

```text
cd D:\LabexAgent\backend
mvn test
918 tests, 0 failures, 0 errors, 8 skipped
BUILD SUCCESS
```

### 6.3 前端全量与生产构建

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

### 6.4 真实后端系统验收

```text
.\scripts\acceptance\agent-runtime.ps1 -TimeoutSeconds 150
runId: 77717ebcda1a4b17b6c121980f33957a
strictTextToolFallback: true
authoritativeModelRetryProjection: true
authoritativeFailureProjection: true
authoritativeCancellationProjection: true
cleanup: true
```

该场景实际启动隔离 H2 和打包后的 Spring Boot JAR，并验证：显式文本信封没有作为 `FINAL_DELTA` 暴露、`list_files` 真实执行、稳定 toolCallId、完成态持久化 Tool Part，以及既有审批/重启/压缩/失败/取消链路没有回归。

### 6.5 真实浏览器验收

```text
.\scripts\acceptance\browser-runtime.ps1 -RestartBackendForAcceptance -TimeoutSeconds 150
runId: 085570333a2e49c18d2c568a58cc7a87
conversationIsolation: true
refreshReplayDeduplicated: true
permissionApprovalRefreshRecovery: true
modelRetryLiveProjection: true
restartProjectionVerified: true
restartInteractionVerified: true
consoleErrors: 0
networkErrors: 0
```

## 7. 风险与后续

1. 本轮有意拒绝“工具信封外还带说明正文”的 Provider 输出；这可能让极少数依赖宽松文本协议的旧模型先收到一次纠正提示，第二次仍不合规则明确失败。该兼容性收紧是为了防止误执行。
2. 本轮严格校验只覆盖文本恢复路径。原生 tool call 参数目前仍通过旧 `parseArgs(...)` 进入主循环；下一轮应把原生调用也切换为类型化 JSON 解析和同一 schema 门禁，避免非法原生 arguments 静默降级为 `{}`。
3. 对“说明正文中嵌入显式信封”的非法响应，主循环会拒绝执行；由于正文并非以协议前缀开头，已经到达浏览器的说明文字不会回撤。安全边界已成立，但未来可以增加可撤销的 assistant draft projection，进一步优化异常 Provider 的视觉体验。
4. `backend/src/main/resources/application-acceptance.yml` 是本轮开始前已有的工作区状态，本轮未暂存、未提交。

## 8. 结论

文本工具调用不再是“从任意自然语言猜一个工具然后碰碰运气执行”，而成为一个明确、可分类、可校验、可熔断、可真实验收的兼容协议。普通文本不会触发工具，非法参数不会越过 Tool Part 边界，工具信封不会冒充前端答案，合法兼容调用仍能走完整的正式工具生命周期。
