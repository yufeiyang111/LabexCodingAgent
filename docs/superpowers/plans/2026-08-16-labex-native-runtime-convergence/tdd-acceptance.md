# LabexAgent 原生运行时 TDD 测试与验收文档

> 状态：用户已于 2026-08-16 确认 S1 至 S6 public seam；已开始阶段 A 的 Red -> Green 垂直切片。
>
> 对应实施计划：`D:/LabexAgent/docs/superpowers/plans/2026-08-16-labex-native-runtime-convergence/spec.md`。
>
> 方法：严格采用 Red -> Green 的垂直切片，不先批量写测试，也不先进行大范围重构。

## 1. TDD 约束

1. 每条测试验证一个用户或调用方可观察到的行为，而不是 private method、内部 Map 或临时日志。
2. 每次只选择一个 public seam：先写失败用例，确认红灯，再做最小实现让它变绿，再进入下一条。
3. 预期值必须来自已确认的协议、持久化事实或用户可见结果，不能在断言里重新实现被测逻辑。
4. 外部 Provider、网络、进程和 MCP 可以在测试中使用明确声明的 fake 或 fixture，但业务路径不能留下 mock、假成功或隐式 test profile。
5. 失败的 tool、批准等待、恢复、连接中断均需通过公开事件、API 或持久化 projection 观察，不能只断言内部方法是否被调用。
6. 代码重构不和红绿切片混做；每完成一组稳定切片后再单独审查和重构。

## 2. 已确认的测试 seam

以下 seam 是开始写测试源码前必须确认的公共观察边界。它们与当前架构一致，避免测试耦合到内部实现。

| 编号 | Public seam | 可观察结果 | 首个 TDD 切片 |
|---|---|---|---|
| S1 | 对话创建与流式请求 API | profile 被保存、冲突被拒绝、task snapshot 稳定 | 新旧 conversation profile 隔离 |
| S2 | `AgentLoopEngine` 的 start、resume 与 durable Run API | task、Part、Event 的顺序和状态可重建 | native 单回合多 tool call |
| S3 | `AgentTool` 通过 `ToolRegistry` 的公开执行契约 | tool result 含状态、目标身份、证据、幂等归属 | shell 失败与删除真实落盘 |
| S4 | workspace 与 verification 的服务边界 | 文件系统、Git/change-set、verification 事实一致 | 删除后文件树与验证一致 |
| S5 | SSE event 到前端 reducer 的边界 | 刷新、重连后 final 与计划正确重放 | final 已生成但未显示修复 |
| S6 | provider schema 生成边界 | 当前回合只有许可且可用的核心与扩展工具 | 按需 exposure snapshot |

确认记录：用户已于 2026-08-16 确认上述六个 seam 是本轮测试唯一的 public 观察边界。若某个 seam 的接口需要调整，应先更新本表和实施计划，不借测试窥探内部实现。

## 3. 关键验收场景与红绿顺序

### 切片 1：Profile 隔离

**Red**：创建 legacy 与 native 两个 conversation 后，分别启动 task；断言各 task 使用创建时 snapshot，向已有 conversation 传入不同 profile 得到明确冲突。

**Green**：只实现 enum、字段迁移、service snapshot 与 API 校验，不提前实现 native processor。

**验收**：两个 conversation 可在同一项目并存；历史空 profile 仍稳定路由 legacy。

### 切片 2：Native durable tool batch

**Red**：使用受控 Provider fixture 返回两个 tool call。通过 Run Message、Part 和公开事件断言两条 call 都先持久化、结果同 ID 配对、按确定性顺序完成。

**Green**：只引入 native turn processor 的最小分派与 durable batch 逻辑，复用现有 lifecycle 与 tool executor。

**验收**：中断或审批发生时未执行的第二条 call 显示 `interrupted` 或 `skipped`，重试不会重复执行第一条。

**2026-08-16 实施证据**：`LabexNativeToolBatchExecutorTest` 先覆盖组件协议；`LabexNativeToolBatchDurableDatabaseTest` 通过受控 streaming Provider、真实 H2/MyBatis `AgentTask`/Run Message/Part/Event/outbox，验证正常双工具 turn 在第一条 delegate 前已持久化全部 `tool_call` Part，且结果按 `toolCallId` 配对；另验证 approval 后 task 未被 batch 伪终结、第一条 Part 为 `waiting_approval`、第二条为 `skipped`。此为 S2 的 durable batch 子切片，不替代后续 `AgentLoopEngine` public start/resume、断线和浏览器 replay 验收。

### 切片 3：真实 shell 失败不被说成成功

**Red**：通过公开 shell tool seam 执行一个确定性非零命令；断言 Part 记录非零 exit、verification 不是成功、final projection 不产生成功完成事实。

**Green**：只补齐结构化 shell result 和 completion evidence 消费规则。

**验收**：模型可以产生解释文本，但 UI 与 durable verification 必须同时显示真实失败。

### 切片 4：删除声明与真实文件系统一致

**Red**：先创建 fixture 文件，再由 tool 删除；断言文件真实不存在、target identity 指向正确 worktree、`WORKSPACE_CHANGED` 产生且 verification 记录变更。再构造删除失败，断言模型没有可被 accepted 为完成的虚假证据。

**Green**：只接入 target identity、mutation evidence、workspace event 与 failure propagation。

**验收**：复现历史 Skill 删除事故时，系统能明确区分未执行、执行失败、已删除三种状态。

### 切片 C1：工作区 target identity（批准命令的最小垂直切片）

**Public seam**：已持久化的 `WORKSPACE_CHANGED` 事件 payload。第一轮只覆盖真实执行的批准命令：事件必须带有可重放的 `workspaceIdentity`，其中包含 task/conversation/project/epoch、受控相对 workdir、变更相对路径与稳定 fingerprint；不得向前端、模型或普通事件泄露宿主绝对工作区路径。

**Red**：构造嵌套 workdir 的批准命令和真实删除证据；现有事件只有裸 `workingDirectory`/`changedPaths`，没有稳定 target identity，且无法证明它属于哪个 execution epoch。

**Green**：引入单一 `WorkspaceOperationIdentity` 值对象，使用现有 `SecureWorkspacePath` 解析路径、以 workspace 根指纹代替绝对路径；`CommandApprovalOrchestrator` 用该值对象投影 `WORKSPACE_CHANGED`。不改变执行、审批、snapshot 或前端刷新语义。

**验收**：同一项目的不同 task/epoch 不会生成相同 operation fingerprint；同一 identity 在持久化 event 中可重放；payload 不包含宿主 workspace 绝对路径。后续切片再将相同值对象接到 direct shell、write/apply-patch、Tool Part metadata 与 verification，不能先复制另一套路径/身份逻辑。

**本地参考与适配**：参考 `D:\opencode\opencode-dev\packages\opencode\src\snapshot\index.ts:47-52` 的 snapshot/diff 接口，以及 `D:\opencode\opencode-dev\packages\opencode\src\session\processor.ts:114,694,731,848` 的 turn 前后快照与 patch 投影不变量。LabexAgent 使用 durable Event/Part 和多用户 project ownership，不复制单机实现代码。

### 切片 5：Final durable replay

**Red**：写入已 accepted 的 final 后模拟 SSE 断线、页面刷新、重新载入 conversation；断言 reducer 仍显示最终答复。构造 rejected 与 interrupted，断言可见原因。

**Green**：只实现 native completion projector 与 reducer event handling，不改无关 UI 样式。

**验收**：不再出现日志有最终总结、前端对话区没有最终消息的截断现象。

### 切片 6：计划是投影而非完成门槛

**Red**：native run 无 `create_plan`、`plan_exit`、`todo_write` 调用时完成一个只读问题或简单改动；断言 final accepted 不受计划工具影响。另断言已有 Plan Part 仍能渲染。

**Green**：只移除 native completion 对控制工具的依赖，保留 legacy replay。

**验收**：模型不被工作流式计划强制束缚，侧边栏仍展示可用执行进度。

### 切片 7：按需扩展暴露

**Red**：在相同 conversation 上，以不同 capability、附件、LSP client、Skill、MCP 连接状态建立请求；断言 schema 与 snapshot 只包含符合条件的工具。

**Green**：只实现 `ToolExposurePlanner` 和 snapshot 持久化，不改变底层工具实际行为。

**验收**：恢复同一 request 使用原 snapshot；MCP 断线、图片缺失、LSP 不可用都有明确而有限的失败。

**2026-08-16 实施证据（子切片）**：`ToolExposurePlannerTest` 先证明 native schema 不得从全局 `ToolRegistry.dynamicTools` 泄漏其他用户 MCP，再验证当前 student 的 MCP 仅作为 scoped binding 随本次 request 暴露；`AgentToolExposureSnapshotServiceTest` 验证恢复只接受同 profile/mode 的最新 durable snapshot，不重新发现工具；`AgentRunLifecycleServiceTest#preservesNullableToolExposureSchemaInDurableEventAndOutbox` 固定 JSON Schema 中显式 `null` 不能在 Event/outbox 落库时消失。实现复刻每次 request 解析工具集合、并把真实 schema 绑定到本次 session 的不变量；LabexAgent 适配为 task/student scoped bindings + durable Event/Part，未复制外部实质源码。真实 MCP 断线、LSP language client、图片/Web capability 的现场验收仍未关闭。

### 切片 8：恢复、压缩与循环保护

**Red**：构造审批等待、断线、上下文压缩、重复无进展 tool call 四类中断；从公共 resume seam 断言 epoch、Part 状态、停止原因和 final 可见状态一致。

**Green**：只在 lifecycle、processor 或 projector 需要处加最小恢复逻辑。

**验收**：不会把 waiting state 伪造成终态，也不会把同一 prompt 无限重试。

## 4. 测试层级与数据边界

| 层级 | 使用场景 | 允许的替身 | 禁止事项 |
|---|---|---|---|
| 后端纯单测 | profile、schema filter、event reducer、identity 归一化 | 明确 fixture、fake Provider response | mock private method 或断言内部 Map |
| 后端服务集成测 | conversation/task、Part/Event、permission、tool result | 测试数据库、受控 workspace | 用日志文本代替 durable state |
| 工具集成测 | shell、读写、删除、Git/change-set、verification | 临时测试 workspace 与确定性命令 | 操作真实用户 workspace 或 host 外路径 |
| 前端 reducer 测 | SSE event、replay、final、workspace refresh | 固定 durable event fixture | 根据连接关闭猜状态 |
| 浏览器 smoke | conversation 创建、流式执行、刷新、文件树 | 专用 acceptance 项目 | 依赖 mock 页面数据 |

测试 fixture 必须创建在受控临时 workspace，测试结束后只删除其自身显式创建的目录。不得读取、打印或写入 `.env`、凭据和用户真实项目文件。

## 5. 验收矩阵

| 能力 | 正向证据 | 负向证据 | 完成条件 |
|---|---|---|---|
| Profile | task 与 conversation profile 一致 | 冲突 profile 被拒绝 | 历史与新会话并存 |
| Transcript | tool call/result 配对且可重放 | 缺 ID、重复执行、半批状态可见 | 多 call、resume 通过 |
| Shell | exit 0 与验证证据一致 | exit 非零不标成功 | 真实失败可见 |
| 删除 | 文件、diff、Part、tree 一致 | 删除失败不会自述成功 | 历史事故复现通过 |
| Final | accepted 在刷新后显示 | rejected/interrupted 有理由 | 对话不截断 |
| 计划 | UI 可显示 progress | 无 plan tool 仍可完成 | 无 completion gate |
| 工作区刷新 | 当前 task 变更刷新 tree | 其他 task 不误刷新 | project guard 通过 |
| 扩展工具 | 条件满足才暴露 | 不可用时有限失败 | snapshot 可恢复 |
| 安全 | 正常工程命令可运行 | destructive/越界有保护 | 不阻碍常规开发 |
| 循环保护 | 无进展有限停止 | 不无限重试 | 停止原因可解释 |

## 6. 每条测试的最小验收记录

每个红绿切片需要在测试名称或临近文档记录以下内容：公共 seam、用户可观察行为、失败时的独立预期、最小实现文件、执行命令、红灯证据、绿灯证据、未覆盖风险。不得用一次全量构建成功代替行为验收。

## 7. 执行顺序与命令原则

1. 先运行最小相关后端测试，再扩大到相关 package，最后运行完整后端测试。
2. 前端使用仓库实际存在的测试执行方式；当前没有 package 脚本时，实施前先确认 `.test.mjs` 的实际 Node runner 命令，不新增测试框架或脚本来凑验收。
3. UI 改动在 reducer 测试通过后进行浏览器 smoke；浏览器连接状态不能作为唯一证据。
4. 每个阶段结束检查 `git diff`，确认没有混入无关重构、mock 业务数据或未受控配置常量。

## 8. 已确认事项与阶段 A 执行记录

用户已于 2026-08-16 确认：S1 至 S6 是本轮唯一允许的 public seam。实施从 profile 隔离开始，仍按逐条 Red -> Green 执行，不一次性重写 `AgentLoopEngine`。

| 切片 | Public seam | Red 证据 | 最小 Green 实现 | 绿灯验证 | 未覆盖风险 |
|---|---|---|---|---|---|
| A1：conversation/task profile | S1 | 新 profile 字段与服务 snapshot 尚不存在时，创建/冲突/任务 snapshot 的回归无法满足 | `AgentRuntimeProfile`、conversation/task 字段、additive schema、请求映射、service snapshot | `AgentConversationRuntimeProfileTest`、`AgentTaskServiceTest`、`AgentStreamRuntimeProfileRequestTest`、`StudentAgentControllerStreamSecurityTest` | background/approval 的完整恢复链尚未做黑盒验收 |
| A2：恢复 profile | S2（durable continuation request） | `AgentRunContinuationRequestFactoryTest` 断言期望 `labex-native`，实际为 `null` | continuation request 固定从 `AgentTask.runtimeProfile` 重建 | `mvn -q -Dtest=AgentRunContinuationRequestFactoryTest test` | 尚未进入 native turn processor |
| A3：fork profile | S1 | `AgentConversationRuntimeProfileTest` 期望 fork 继承 `labex-native`，实际为 `null` | fork 复制 source conversation 的 profile snapshot | `mvn -q -Dtest=AgentConversationRuntimeProfileTest test` | 后台分支与 interaction 恢复待覆盖 |
| A4：执行 profile 权威来源 | S2（任务执行准备边界） | `AgentRuntimeProfileResolverTest` 在 resolver 尚不存在时测试编译失败 | 新增 resolver；`AgentLoopEngine` 在 task 已确定后将 request profile 归一为 task snapshot，并将其投影到 SESSION 元数据 | `mvn -q -Dtest=AgentRuntimeProfileResolverTest test`；`mvn -q -DskipTests compile` | 当前仅建立 durable 路由边界；native 处理器尚未替换 legacy turn loop |

| A5：产品化执行 profile 名称 | S6（Provider 请求配置） | `AgentExecutionPropertiesTest` 将默认值和生产降级预期改为 `labex-standard` 后，旧实现仍返回旧外部名称 | 统一执行 profile 默认值、生产校验、prompt 默认值和配置文档 | `mvn -q -Dtest=AgentExecutionPropertiesTest test`；相关配置测试 | 仅完成执行 profile 命名收敛，不改变 destructive-operation 边界 |
| A6：native Provider 投影 | S6（system prompt + tool schema） | native prompt/schema overload 尚不存在，`LabexSystemPromptTest` 与 `ToolSelectionPolicyTest` 编译失败 | native profile 注入直接、证据优先提示词；按 profile 的 schema 过滤隐藏 `todo_write` 与 `plan_exit`，Engine 从 task snapshot 投影 prompt/schema | `mvn -q -Dtest=LabexSystemPromptTest,ToolSelectionPolicyTest test`；`mvn -q -DskipTests compile` | 尚未抽出完整 native turn processor；Part/Event batch 仍使用现有 durable loop |

### 切片 B1：Native durable tool batch bridge（已完成，尚未关闭 S2）

**Red**：新增 `LabexNativeToolBatchExecutorTest` 后，类与协议不存在导致 test compile 失败；新增 Engine routing contract 后，native profile 尚未调用 batch executor 导致断言失败。

**Green**：新增 `AgentProviderTranscriptAppender` 与 `LabexNativeToolBatchExecutor`。同一模型 turn 先追加 assistant `tool_calls`、再将所有可执行 call 记为 `pending`，随后通过现有 Engine 工具包装串行执行；每个结果按原始 `toolCallId` 追加 role=`tool`。approval 会让后续可执行 call 变为 `skipped`，取消会让尚未开始的 call 变为 `interrupted`。`labex-native` 已从现有大循环显式分派到该 bridge；legacy 不变。

**绿测**：

```text
mvn -q -Dtest=AgentProviderTranscriptAppenderTest,LabexNativeToolBatchExecutorTest,AgentLoopEngineNativeToolBatchWiringTest test
mvn -q -Dtest=LabexNativeToolBatchDurableDatabaseTest test
mvn -q -Dtest=AgentProviderTranscriptAppenderTest,LabexNativeToolBatchExecutorTest,LabexNativeToolBatchDurableDatabaseTest,AgentLoopEngineNativeToolBatchWiringTest,AgentToolCallBatchProtocolTest,AgentToolTurnExecutorTest,AgentRunTranscriptServiceTest,AgentToolCallJournalServiceTest,AgentLoopEngineProcessorContractTest,AgentLoopEngineParallelToolCallTest,AgentLoopEngineLoopPolicyTest,AgentLoopEngineFinalReplyPolicyTest,AgentLoopEngineCompactionTerminalityTest,AgentLoopEngineNextPreviewTest,AgentLoopEngineWiringContractTest,AgentLoopEngineContextBudgetTest,AgentRuntimeProfileResolverTest,AgentRunContinuationRequestFactoryTest,ToolSelectionPolicyTest,LabexSystemPromptTest,ProductionConfigurationTest test
```

**边界**：受控 Provider + H2 黑盒已关闭“同批先落库、正常结果按 ID 配对、approval 后 Part 状态”这一 durable batch 子切片；`AgentLoopEngine` public start/resume、用户交互、cancellation 的真实 mapper、compaction/overflow 与刷新 replay 仍未关闭，不能将本结果宣称为完整 S2 或端到端验收。

### 切片 C1 后续：直接工具的工作区证据（2026-08-16）

**范围**：在不新增另一套 workspace 路径逻辑的前提下，将 `WorkspaceOperationIdentity` 接到实际 direct shell、`write_file`、`apply_patch` 的公开 `ToolResult`，再经 native journal 写入 durable Tool Part 和 `TOOL_CALL_STATE` 事件投影。

**不变量**：

- 所有 target identity 都由同一个 `WorkspaceOperationIdentity` 生成：包含 task、conversation、project、epoch、受控相对 workdir/路径、workspace 根指纹和 operation fingerprint；不写入宿主绝对路径。
- `shell` 的 Tool Part metadata 保留结构化执行状态（status、exitCode、duration、截断、shell）和 workspace identity；非零退出仍是“已观察到的命令结果”，不会被误报为 worker 故障。
- `write_file` 与 `apply_patch` 只有在 `DiffService.stageAndApply*Deferred` 已返回实际写入的 `PendingChange` 后，才标记 `workspaceMutation.state=applied`，并保存可回链 changeId 列表；不会仅根据模型文本宣称变更已落盘。
- native batch journal 传递原始 `ToolResult`，不把结构化执行/变更事实压扁成字符串；旧 String journal overload 保留给兼容路径。

**本地参考与适配**：参考 `D:\opencode\opencode-dev\packages\opencode\src\snapshot\index.ts:47-52` 的 snapshot/diff 生命周期接口，以及 `D:\opencode\opencode-dev\packages\opencode\src\tool\edit.ts:101-111,145-194` 的“写入后生成 diff/metadata/诊断投影”顺序。LabexAgent 使用多用户 durable Part/Event、现有 DiffService 和 change-set，不复制外部实质代码，也不把单机 snapshot 假设直接搬入 Web 控制面。

**Red → Green 证据**：

```text
# Red：shell result 缺失 identity；journal 只能接收文本；文件 mutation 缺少 durable evidence
mvn -q -Dtest=RunCommandToolTest#projectsSafeWorkspaceIdentityIntoTheNativeShellResult test
mvn -q -Dtest=AgentToolCallJournalServiceTest#persistsStructuredWorkspaceIdentityWithTheCompletedToolPart test
mvn -q -Dtest=WriteFileToolTest#projectsAppliedWriteAsWorkspaceChangeEvidence test
mvn -q -Dtest=ApplyPatchToolTest#batchesPreparedCreateReplaceAndDeleteInInputOrder test

# Green：C1/C1 后续聚焦回归
mvn -q -Dtest=WorkspaceOperationIdentityTest,CommandApprovalOrchestratorTest#workspaceChangedBindsDeletionEvidenceToASafeTaskEpochIdentity,AgentRunPartServiceTest#persistsStructuredWorkspaceIdentityAlongsideTheToolPart,RunCommandToolTest#projectsSafeWorkspaceIdentityIntoTheNativeShellResult,AgentToolCallJournalServiceTest#persistsStructuredWorkspaceIdentityWithTheCompletedToolPart,LabexNativeToolBatchExecutorTest#persistsEveryCallBeforeSerialExecutionAndPairsEachToolResultWithItsProviderId,WriteFileToolTest#projectsAppliedWriteAsWorkspaceChangeEvidence,ApplyPatchToolTest#batchesPreparedCreateReplaceAndDeleteInInputOrder test
```

**尚未关闭**：verification 表自身还没有 identity metadata 字段；下一阶段应通过既有 verification/change-set/workspace event 的一致性黑盒测试关闭“删除失败不可被 final 误报完成”和“恢复不重复执行副作用”，不能把本记录当成阶段 C 全部完成。

### 切片 C2：直接文件 mutation 的写后验证与 legacy durable 回放（2026-08-16）

**公共 seam**：S3 的 `AgentTool` 公开执行结果、S4 的临时 workspace/change-set 事实，以及 legacy/native 共用的 durable Tool Part 与 `WORKSPACE_CHANGED` 事件投影。

**Red**：真实临时 workspace 删除 `skills/SKILL.md` 后，`apply_patch` 的 `ToolResult` 只有 mutation/changeId，缺少“目标确实已不存在”的结构化证据；同时 legacy `AgentLoopEngine` 在 terminal journal 分支把 `ToolResult` 压成 `String detail`，导致 target identity、mutation 与 verification 无法持久化回放。

**Green**：`DiffService` 在同一 workspace lease 内完成写入后，按目标类型验证后置条件：删除必须实际不存在；写入必须为普通文件、字节数与 SHA-256 都与目标内容一致。验证失败会走现有 batch rollback/failed 路径，不能返回 `workspaceMutation.state=applied`。成功验证经 `ApplyTelemetry` 投影为 `workspaceVerification`，由 `write_file` / `apply_patch` 附着到 `ToolResult`，再写入 Tool Part、`TOOL_CALL_STATE`、`OBSERVE` 与 `WORKSPACE_CHANGED`。legacy terminal journal 也改为传递原始 `ToolResult`，不再退化为字符串。

**本地参考与适配**：参考 `D:\opencode\opencode-dev\packages\opencode\src\tool\edit.ts:101-115,155-194` 与 `D:\opencode\opencode-dev\packages\opencode\src\tool\write.ts:46-72,92-100` 的真实写入完成后再发布文件事件、生成 metadata/diagnostics 的顺序；同时参考 `D:\opencode\opencode-dev\packages\opencode\src\snapshot\index.ts:47-52` 的 snapshot/diff 事实边界。LabexAgent 将其适配为多用户 durable Part/Event 与现有 `DiffService` 事务/lease，未复制外部实质代码。

**Red → Green 证据**：

```text
# Red：真实删除没有 workspaceVerification；legacy terminal journal 丢失 ToolResult 结构化事实
mvn -q -Dtest=ApplyPatchToolTest#deleteMutatesTheRealWorkspaceBeforeReportingDurableMutationEvidence test
mvn -q -Dtest=AgentLoopEngineStreamingContractTest#preservesStructuredToolEvidenceWhenLegacyTurnsReachTerminalPartStates test

# Green：直接文件写后事实、Tool Part/Event 回放、重复批准不重复执行
mvn -q -Dtest=DiffServiceCasTest,ApplyPatchToolTest,WriteFileToolTest,AgentToolCallJournalServiceTest,AgentLoopEngineStreamingContractTest,CommandApprovalOrchestratorTest test
mvn -q -DskipTests compile
```

**已覆盖的可观察结果**：删除结果同时满足真实文件已不存在、`AgentFileChange` 为 `delete/applied`、ToolResult 携带安全的 `workspaceIdentity`、`workspaceMutation` 和 `workspaceVerification`；同一 approval 第二次执行时 consume 原子条件失败，命令与 resume 不会重复发生。

**仍未关闭的风险**：尚未对可控文件系统 race 注入写后 mismatch，也尚未完成浏览器侧删除/刷新/重连现场验收；`AgentVerification` 表的 schema 仍未独立保存 identity metadata。因此本切片只关闭 direct file mutation 的最小事实链，不宣称阶段 C、恢复或 UI 端到端全部完成。

### 切片 C3：审批 shell mutation 的 durable 证据回写（2026-08-16）

**Public seam**：S3 的已批准 `shell` 命令恢复结果、S4 的 change-set/真实 workspace 后置状态，以及 durable Tool Part / `TOOL_CALL_STATE` / `WORKSPACE_CHANGED` 回放。

**Red**：`CommandApprovalOrchestrator` 对批准后的命令只把 `detail` 字符串回写到 transcript 与既有 Tool Part；即使 snapshot 已记录删除，恢复后的模型与 UI 仍拿不到 `workspaceIdentity`、`workspaceMutation`、`workspaceVerification`。新增的审批删除回归和外部 resolved ToolResult journal 回归在新增 overload 前无法编译，明确暴露缺失的 durable 契约。

**Green**：批准命令在 before/after snapshot diff 后，对已进入 change-set 的安全相对 target 做真实文件系统后置检查：删除必须仍为 absent，其它变更必须为 regular_file。`CommandApprovalOrchestrator` 将脱敏后的进程 result、workspace identity、change ID 与验证结果作为同一个 `ToolResult` 回写；`AgentToolCallJournalService`/`AgentRunPartService` 为控制面延后结束的 Tool Part 合并安全 metadata，保留原 Part 的 sequence、partType、status 与 executionEpoch。`WORKSPACE_CHANGED` 同步投影 mutation/verification，且在 workspace 无法检查时不伪造 verification metadata。

**本地参考与适配**：已阅读 `D:\opencode\opencode-dev\packages\opencode\src\tool\shell.ts:482-539` 的 shell 执行期间增量 metadata 更新，以及 `D:\opencode\opencode-dev\packages\opencode\src\snapshot\index.ts:47-52` 的 snapshot/diff/revert 事实边界。LabexAgent 没有复制外部代码：它将同一“命令输出与结构化 metadata 一起成为可回放事实”的不变量适配为 Spring Boot 多用户审批恢复、transactional outbox 与已有 change-set。

**Red → Green 证据**：

```text
# Red：控制面已有 ToolResult 无法写回 metadata，批准删除没有 durable verification 载荷
mvn -q -Dtest=AgentToolCallJournalServiceTest#externallyResolvedToolResultPreservesStructuredWorkspaceEvidence,CommandApprovalOrchestratorTest#approvedDeletionProjectsVerifiedWorkspaceEvidenceIntoTheDeferredToolResult test

# Green：真实临时 workspace 删除、审批回写、Part metadata 合并与既有审批恢复回归
mvn -q -Dtest=CommandApprovalOrchestratorTest,AgentToolCallJournalServiceTest,AgentRunPartServiceTest test
```

**已覆盖的可观察结果**：受控临时 workspace 中 `skills/SKILL.md` 被实际删除后，审批恢复回写的同一 ToolResult 同时含有无宿主绝对路径的 workspace identity、`change-71` mutation 与 `{state: verified, expectedState: absent, observedState: absent}`；该 metadata 继续进入既有 Tool Part 与 `TOOL_CALL_STATE`。没有 snapshot/工作区证据时不会被伪装为 verified。

**仍未关闭的风险**：这是 approval shell 的最小事实链，尚未把任意 shell 文本的“意图”解析为目标契约，也尚未做真实 worker 容器 + 浏览器刷新/重连端到端验收；代码改动仍需要适当的 build/test/LSP 验证，不能把文件存在性当作功能正确性。

### 切片 C4：原生文件 mutation 工具契约收敛（2026-08-16）

**Public seam**：S6 的 native Provider schema / durable `ToolExposureSnapshot`，以及 S3 的 `write_file`、`apply_patch` 公开 `AgentTool` 执行结果。

**Red**：native build 的工具 schema 同时暴露 `edit_file` 与 `write_file`，但没有 `apply_patch`；模型面对两种精确编辑工具会出现选择摇摆。与此同时 `apply_patch` 仍接受 `create` 和全文件替换，与 `write_file` 的职责重叠。以下红灯精确固定了“新 native 不出现 `edit_file`、新文件必须走 `write_file`、整文件替换必须走 `write_file`”三项可观察契约。

**Green**：`ToolSelectionPolicy` 保留 legacy mode 的原 schema，但 native build 排除 legacy-only `edit_file` 并增加 `apply_patch`。`write_file` 收敛为单文件创建或有意的整文件替换；`apply_patch` 收敛为已有文件的唯一上下文文本替换或删除，拒绝 `create` 和整文件 replace。历史 `ToolExposureSnapshot` 不重新按新 policy 推导，而是按持久化的原始 definition 回放，因此旧 native task 的 `edit_file` schema 不会在恢复时被静默改写。

**本地参考与适配**：已阅读 `D:\opencode\opencode-dev\packages\opencode\src\tool\edit.ts:47-56,90-95`（精确替换与整文件替换应转向 write）、`write.ts:20-39`（完整文件写入）及 `apply_patch.ts:18-22,307-310`、`apply_patch.txt:1-25`（结构化 patch 的独立契约）。OpenCode 同时保留 edit/write/apply_patch 三种工具；LabexAgent 基于用户确认的“新 native 不保留重复精确编辑入口”要求，选择将 exact-edit 语义统一至 `apply_patch`，并把 `edit_file` 仅保留给 legacy/durable replay。未复制外部实质代码。

**Red → Green 证据**：

```text
# Red：native schema 仍暴露 edit_file；create 和整文件 patch 未被收敛
mvn -q -Dtest=ToolSelectionPolicyTest,ToolExposurePlannerTest,ApplyPatchToolContractTest,ApplyPatchToolTest test

# Green：native selection、历史 snapshot 恢复、文件 mutation 边界与写后证据共同通过
mvn -q -Dtest=ToolSelectionPolicyTest,ToolExposurePlannerTest,ApplyPatchToolContractTest,ApplyPatchToolTest,WriteFileToolTest test
```

**已覆盖的可观察结果**：新 native build 的 schema 包含 `write_file` 与 `apply_patch`，不包含 `edit_file`；`apply_patch(create)` 在进入 workspace 前返回明确的 `write_file` 引导；`apply_patch(replace)` 若尝试覆盖完整已有文件也返回明确引导；旧 exposure snapshot 的 `edit_file` definition 恢复后仍原样可见。`write_file` 与 `apply_patch` 的 ToolResult 继续沿用既有 identity/change-set/verification 事实链。

**仍未关闭的风险**：此切片没有完成 native shell 的唯一实现、真实 worker/worktree 多路径现场验收、循环保护与断线恢复；也没有把工具删除扩展到所有 legacy 工具。`apply_patch` 的历史未完成调用若包含 create，仍必须依靠对应的持久化 exposure / task 兼容策略进行后续回放验收，不能仅凭本次单元测试宣称全量历史迁移完成。

### 切片 C5：循环保护区分模型停滞与不同工具失败（2026-08-16）

**Public seam**：`AgentLoopGuard` 的 iteration/tool-call 决策；它是 `AgentLoopEngine` 在每轮执行和断线恢复时读取、并通过 `LOOP_GUARD_PROGRESS` durable Part/Event 投影的唯一循环预算入口。

**问题与假设排序**：

1. **已证实**：此前每个失败的工具结果都会增加 `nonProgressIterations`。预测是：即便四次调用使用不同参数、没有重复模式，在阈值为 3 时也会停止；新增回归红灯得到 `nonProgressIterations=4`，证实该问题。
2. 同签名失败已经由 `failedToolSignatures` 与 canonical signature 单独检测；去掉全局累计不应放宽重复调用的阻断。既有 `escalatesRepeatedFailedCommandIdentityEvenWhenOtherStrategiesAreInterleaved` 与聚焦回归覆盖该预测。
3. 真正的模型停滞（空回复、无效 native 输入、伪 final、完成证据被拒绝）仍经 `recordModelNoProgress` 累计，因此不会把循环保护整体删除。

**本地参考与适配**：已阅读 `D:\opencode\opencode-dev\packages\opencode\src\session\processor.ts:35,519-546`。该实现只对最近三条相同 tool/input 触发 doom-loop permission，而每个错误 tool result 仍被持久化为 error Part（`229-246,549-570`），并不以不同失败工具的数量作为固定 stop 条件。LabexAgent 复刻“精确重复模式与错误事实分离”的原则；考虑到多用户 durable resume，仍保留已有 `LOOP_GUARD_PROGRESS` / epoch 机制。未复制外部实质代码。

**Red → Green 证据**：

```text
# Red：四次不同 read_file 失败在阈值 3 时被误判为连续无进展
mvn -q -Dtest=AgentLoopGuardTest test

# Green：不同失败可继续探索；同签名失败、模型无进展和 Engine durable projection 的既有回归仍通过
mvn -q -Dtest=AgentLoopGuardTest,AgentLoopEngineLoopPolicyTest,AgentLoopEngineStreamingContractTest,AgentLoopEngineFinalReplyPolicyTest,AgentLoopEngineNextPreviewTest,AgentRunPartServiceTest test
```

**Green**：`recordToolResult(false)` 仅记录该 canonical signature 的失败次数，交由已有重复失败 / 重复模式策略处理；它不再调用 `recordModelNoProgress()`。成功工具结果仍可将真实模型停滞预算归零；空回复、无效模型工具输入、拒绝的 final 和完成证据恢复等显式模型停滞路径仍调用 `recordModelNoProgress()`。

**已覆盖的可观察结果**：多次不同 target 的失败读操作不会再以“连续无进展”停止；同一个失败工具调用重复达到阈值仍会要求切换策略或请求用户；任务恢复继续从 durable `LOOP_GUARD_PROGRESS` 读取计数，而不会把普通工具错误变成跨恢复的伪无进展债务。

**仍未关闭的风险**：尚未通过真实 Provider + worker 重演用户历史日志中的所有失败序列，也未完成 overflow / compaction / browser reconnect 的联合验收。不同失败策略没有总数硬上限，仍受上下文、取消、超时和同签名循环保护约束；后续需要用真实验收任务观察模型是否会在大量“不同但无价值”的命令间漂移。

### 切片 C6：shell 非零退出码不得伪装为循环进展（2026-08-16）

**Public seam**：S3 的结构化 `ToolResult` 和 S2 的 `AgentLoopGuard` tool-result 入口。用户仍能看到 shell 命令已经执行及其完整非零输出，但循环策略必须把它识别为未成功的执行 outcome。

**Red**：`RunCommandTool` 有意把“Worker 成功观察到命令非零 exit”表示为 `ToolResult.isSuccess()==true`，以便模型读取 stdout/stderr；但 `AgentLoopEngine` 将同一个布尔值直接传给 loop guard。结果是相同的失败构建命令每次都会清空失败计数，重复失败保护永远不触发。新增测试先固定“工具 transport 成功但 execution outcome 失败”的双重语义，并要求 guard 将连续三次相同 non-zero shell 调用切换策略。

**Green**：`ToolResult` 新增 `isSuccessfulExecutionOutcome()`：无执行元数据的普通成功工具仍为成功 outcome；有执行元数据时仅 `status=succeeded` 且 exit 为 0/缺失才成功。`AgentLoopGuard` 接收结构化 ToolResult，`AgentLoopEngine` 不再传入裸 `isSuccess()`，而是把 outcome 用于 durable `tool_success` / `tool_failure` 投影和同签名失败计数。前端/模型的命令输出语义保持不变。

**本地参考与适配**：已阅读 `D:\opencode\opencode-dev\packages\opencode\src\session\processor.ts:229-246,549-570`：工具错误会被持久化为 error Part；以及 `519-546`：重复完全相同的 tool/input 才触发 doom-loop permission。LabexAgent 适配为“可完成的 shell transport + 失败的命令 outcome”二元结构，以保持现有 Web UI 的完整输出可见性和多用户 durable Part 事实。未复制外部实质代码。

**Red → Green 证据**：

```text
# Red：ToolResult 缺少 outcome 语义，AgentLoopGuard 只接收裸 boolean
mvn -q -Dtest=ToolResultTest,AgentLoopGuardTest test

# Green：非零 shell 输出保留、重复失败拦截、Engine 调用点和既有 shell 契约共同通过
mvn -q -Dtest=ToolResultTest,AgentLoopGuardTest,RunCommandToolTest,ShellToolContractTest,AgentLoopEngineLoopPolicyTest,AgentLoopEngineStreamingContractTest,AgentLoopEngineFinalReplyPolicyTest,AgentLoopEngineNextPreviewTest,AgentRunPartServiceTest test
```

**已覆盖的可观察结果**：非零 shell 命令仍作为已完成工具调用向模型展示 `exit/status/output`，但其 execution outcome 为失败；相同 command/workdir 的第三次 non-zero 调用会触发切换策略，而不再被前两次“transport 成功”清零。不同失败命令仍遵循 C5 的规则，不会仅因数量累积触发固定轮数 stop。

**仍未关闭的风险**：尚未对真实 Provider 长会话的“失败命令后模型如何选择下一条策略”做浏览器验收；部分非 shell 工具若自行写入非标准 executionStatus，需要遵循此 outcome 契约。完成证据、verification 和 UI 的终态显示仍要在后续端到端验收中一起确认。

### 状态验证 C7：native command schema 已收敛为单一 shell 入口（2026-08-16）

**Public seam**：S6 的 `ToolSelectionPolicy.select(..., LABEX_NATIVE)` 返回的 Provider schema。该项是对既有实现的 characterization/验收，不新增第二个执行器。

**验证**：`RunCommandTool` 是注册的 `shell` 实现；旧 `BashTool` 已非 Spring Component，`run_tests`、`execute_code` 等旧专用入口仍可服务历史 replay 或 legacy，但不在 native build 白名单中。新增 `nativeBuildExposesOnlyTheUnifiedShellEntryPoint` 将 `bash`、`run_command`、`run_tests`、`execute_code` 全部放入 fixture，固定它们不得重新泄漏进 native schema。

**本地参考与适配**：已参考 `D:\opencode\opencode-dev\packages\opencode\src\tool\shell.ts:482-539` 的单一 shell tool 事实与增量 metadata 设计。LabexAgent 沿用一个 `shell` 模型入口，适配为 Worker + approval + durable Tool Part；未复制外部实质代码。

**验收命令**：

```text
mvn -q -Dtest=ToolSelectionPolicyTest test
```

**边界**：这只证明新 native schema 收敛，不表示 legacy 专用工具已经删除，也不替代真实 Worker 下 install/build/test/git 命令的浏览器现场验收。

### 切片 C8：工具失败分类与安全执行目标 metadata（2026-08-16）

**Public seam**：`ToolResult.durableResultMetadata()` 是所有 Agent Tool Result 写入 `AgentToolCallJournalService`、durable Tool Part 与 `TOOL_CALL_STATE` Event 的公开 metadata 边界。

**Red**：已完成的 shell transport 遇到非零退出码时，模型可读到文本 `outcome=non_zero_exit`，但 durable metadata 只有 `execution.status/exitCode`，没有稳定失败分类，也丢失安全的相对 `workdir` 与 artifact path。恢复和 UI 只能猜测“已完成”是否等同于命令成功，不能依据结构化事实区分非零退出、超时、取消与基础设施失败。

**Green**：`ToolResult` 在所有普通失败上提供安全默认 `failureClass=tool_error`；真实进程结果按确定性规则投影为 `non_zero_exit`、`timed_out`、`cancelled`、`infrastructure_error` 或 `execution_failed`。非零 shell exit 仍保留为完成的 transport（模型可读输出），但 durable metadata 明确标出 `failureClass=non_zero_exit`。`execution` metadata 同时持久化安全的相对 `workdir` 和 artifact `outputPath`；approval/question 等可恢复控制态清除普通失败分类，避免把等待用户误投影为执行错误。Journal 无需第二套转换，继续原样写入 Tool Part/Event。

**本地参考与适配**：参考 `D:\opencode\opencode-dev\packages\opencode\src\session\processor.ts:203-245` 的 completed 与 error Tool Part 状态分离，以及 `session/message-v2.ts:336-370` 的持久化 Tool Part 到模型输出错误投影。LabexAgent 未复制 TypeScript 实现；其适配是保留现有“非零 shell exit 作为模型可观察结果”的契约，并额外以 Spring durable metadata 明确实际执行失败分类与安全目标。

**Red → Green 证据**：

```text
# Red：非零 exit 的 durable metadata 没有 failureClass，也没有 execution target
mvn -q -Dtest=ToolResultTest test

# Green：metadata、journal 透传、shell 非零结果和 loop guard outcome 契约共同通过
mvn -q -Dtest=ToolResultTest,AgentToolCallJournalServiceTest,AgentLoopGuardTest,RunCommandToolTest,ShellToolContractTest test
```

**已覆盖的可观察结果**：`ToolResult.fromObservedProcessExecution(FAILED, exit=2, ...)` 的 transport 仍为 completed 供模型读取，但 Part/Event metadata 包含 `failureClass=non_zero_exit`、`execution.status=failed`、`exitCode=2`、相对 `workdir` 与 `outputPath`。老的 `fromProcessExit(137, ...)` 也映射为 `non_zero_exit`；通用失败仍至少有 `tool_error`，不再只有非结构化错误文本。

**仍未关闭的风险**：此切片没有改变 Provider transcript 的文本协议，也还没有完成真实 worker、恢复后 UI 或浏览器对 `failureClass` 的渲染验收。失败分类是 durable 事实增强，不能替代最终完成策略、工作区写后验证或断线恢复验收。

### 切片 C9：非零 shell exit 的 durable UI 回放（2026-08-16）

**Public seam**：前端 `applyRunPartSnapshot(...)` / `upsertDurableToolCallState(...)` 将 durable Tool Part 与 live `TOOL_CALL_STATE` 投影为用户可见的同一张工具卡。

**Red**：一个 `status=completed` 的 `shell` Tool Part 带有 `{failureClass: "non_zero_exit", execution: {status: "failed", exitCode: 2}}` 时，旧 reducer 忽略 `metadata`，按 transport 状态把卡片投影为绿色 `completed`。刷新或重新进入会话后，用户重新看到“工具完成”，这会掩盖命令真实失败并放大模型“已完成”的错觉。

**Green**：RunPart snapshot 解析持久化的 metadata 并交给统一 tool-call reducer；reducer 在 `completed` transport 上根据稳定 `failureClass` 映射真实可见状态（非零退出/超时/普通失败为 error，取消为 interrupted，基础设施失败为 warning），同时保留原始 `durableStatus=completed` 供审计。安全相对 execution target 作为 `executionResult` 留在同一模型；live `TOOL_CALL_STATE` 已复用该 reducer，因此不建立第二个流式状态路径。`ToolCallCard` 识别原生 `shell`，显示命令及“命令失败（退出码 N）”摘要。

**本地参考与适配**：参考 `D:\opencode\opencode-dev\packages\opencode\src\session\message-v2.ts:336-370` 将持久化 Tool Part 的 completed/error 状态转换为模型可用输出、而非根据连接状态猜测；同时参考 `packages\app\src\context\global-sync\event-reducer.ts` 的 event-driven reducer 边界。LabexAgent 未复制实质源码，适配为 Vue 现有历史快照与 SSE `TOOL_CALL_STATE` 共用的 reducer。

**Red → Green 证据**：

```text
# Red：完成 transport 的非零 exit 在刷新回放后仍显示 completed
cd frontend && node --test src/composables/agentRunPartState.test.mjs

# Green：Part snapshot、tool state 与 live event timeline 聚焦回归
cd frontend && node --test src/composables/agentRunPartState.test.mjs src/composables/agentToolCallState.test.mjs src/composables/useAgentEventTimeline.test.mjs

# Build：Vue SFC 编译与 bundle budget
cd frontend && npm run build
```

**已覆盖的可观察结果**：带 `failureClass=non_zero_exit` 的 completed shell Part 在刷新回放后保持 `durableStatus=completed`、但用户可见 `status=error`；失败分类、exit code、相对 workdir 与 artifact path 都保留在 card state。既有正常 completed Tool Part 继续显示 completed；没有 failureClass 的历史 Part 不会被猜测性改写。

**仍未关闭的风险**：本切片不代替真实浏览器中触发 shell、刷新页面和 reconnect 的现场验收；旧历史记录没有 failureClass 时只能保持旧的状态投影。最终回答是否允许仍由后端 completion evidence 决定，前端卡片不应也不能自行决定任务状态。
### 切片 C10：实时 OBSERVE 不覆盖 durable shell failure（2026-08-16）

**Public seam**：实时 SSE `OBSERVE` 与持久化 `TOOL_CALL_STATE` / 历史 event 共同投影同一个 `toolCallId` 工具卡。

**Red**：先收到一个带 `failureClass=non_zero_exit` 的完成态 shell Tool Part，卡片已正确显示 error；随后旧 `OBSERVE` 只根据 transport `success=true` 重算为 completed，实时页面会从错误回跳到绿色完成。该行为与刷新后的 durable replay 不一致，仍会误导用户和后续验收。

**Green**：统一 `applyStructuredExecutionOutcome(...)` 只消费后端结构化事实：保留安全 `execution` 目标、失败分类与原始 durable status；`execution.status=failed` 和 `failureClass` 都能投影错误。`OBSERVE` 现在携带由 `ToolResult.durableResultMetadata()` 产生的同一份 `execution` 与 `failureClass`，因此事件先后顺序不会再制造第二套 UI 事实。正常 transport 完成不受影响；非零 exit 仍可作为模型可读输出，但用户卡片保持错误。

**本地参考与适配**：参考 `D:\opencode\opencode-dev\packages\opencode\src\session\processor.ts:203-245` 以单一 Tool Part 状态完成/失败更新与 settle；参考 `packages\app\src\context\global-sync\event-reducer.ts:228-253` 按 Part ID 幂等更新 UI store；参考 `packages\opencode\src\session\message-v2.ts:336-370` 把 Tool Part 的 error/pending 状态投影为确定的协议输出。LabexAgent 未复制实质源码；因其是 Spring Boot 多用户 SSE + durable transcript，改为 `ToolResult` metadata 经 `TOOL_CALL_STATE` 和 `OBSERVE` 双投影到既有 Vue reducer。

**Red → Green 证据**：

```text
# Red：实时 OBSERVE 将已分类的非零 exit 从 error 覆盖回 completed
cd frontend && node --test src/composables/useAgentEventTimeline.test.mjs

# Green：durable snapshot、history replay、实时 timeline 与统一工具卡状态
cd frontend && node --test src/composables/agentRunPartState.test.mjs src/composables/agentToolCallState.test.mjs src/composables/agentHistoryReducer.test.mjs src/composables/useAgentEventTimeline.test.mjs

# Green：OBSERVE 后端字段透传、ToolResult/loop guard/shell 契约
cd backend && mvn -q -Dtest=ToolResultTest,AgentToolCallJournalServiceTest,AgentLoopGuardTest,RunCommandToolTest,ShellToolContractTest,AgentLoopEngineStreamingContractTest test

# Build：Vue SFC 编译与 bundle budget
cd frontend && npm run build
```

**已覆盖的可观察结果**：一条 `status=completed` 的 shell Part 只要携带 `failureClass=non_zero_exit` 或 `execution.status=failed`，无论随后收到的是 durable snapshot、历史 `OBSERVE` 还是实时 `OBSERVE`，用户可见卡片均保持 `status=error`；`durableStatus=completed` 保留作 transport 审计，`exitCode`、相对 workdir 和 artifact 路径不会被实时事件丢失。

**仍未关闭的风险**：尚未完成真实浏览器中执行命令、刷新、SSE reconnect 的现场 smoke；本切片也不决定 Agent 是否允许给最终答复，完成/失败仍必须由后端 verification 与 completion evidence 裁决。
### 切片 C11：把真实 execution outcome 从 model transport 中分离（2026-08-16）

**Public seam**：工具执行后的工程进度、环境阻塞判定、workspace memory、metrics，以及最终模型恢复提示。

**Red**：`ToolResult.fromObservedProcessExecution(FAILED, exit=2, ...)` 依既有协议仍是 `success=true`，因此旧代码把它当成 `completed`：进度停在 intake 而不是 repair，shell 的 DNS/依赖失败没有环境阻塞分类，跨会话 workspace memory 把失败的 `npm run build` 写成 `PASS` 且没有 failure event。此类错误会同时污染下一轮 agent 判断、熔断/恢复策略和用户验收记录。

**Green**：保留 `isSuccess()` 只表示“tool result transport 已完成、模型可读取输出”；工程事实统一改用 `isSuccessfulExecutionOutcome()`。`AgentContextOrchestrator` 将非零 exit 投影为 repair，`EnvironmentBlockerClassifier` 可阻断 DNS/依赖重试，`AgentLoopEngine` 记录命令失败、添加 recovery guidance 并在日志中同时显示 execution 与 transport，workspace memory/metrics 将失败写为 FAIL 和 `executionSuccess=false`。`sendObserve` / journal 仍使用 transport success 与 completed Tool Part，避免破坏 provider tool-result 协议。

**本地参考与适配**：参考 `D:\opencode\opencode-dev\packages\opencode\src\tool\shell.ts:489-605`：shell 运行保留 process exit、输出截断和 artifact metadata 后返回 tool output，而非仅以异常替代输出；参考 `packages\opencode\src\session\processor.ts:203-245` 的 Tool Part completed/error 持久化边界。LabexAgent 未复制实质代码；因现有 OpenAI-compatible transcript 需要对非零 exit 保留工具输出，采用显式双语义 API（transport vs execution outcome）而不是改变 `ToolResult.isSuccess()` 的既有协议。

**Red → Green 证据**：

```text
# Red：transport completed 的非零 exit 被当成工程成功
cd backend && mvn -q -Dtest=AgentContextOrchestratorVerificationTrustTest,EnvironmentBlockerClassifierTest,AgentWorkspaceMemoryServiceTest test

# Green：真实 outcome、environment blocker、memory/metrics、loop/journal/shell 契约
cd backend && mvn -q -Dtest=ToolResultTest,AgentContextOrchestratorVerificationTrustTest,EnvironmentBlockerClassifierTest,AgentWorkspaceMemoryServiceTest,AgentMetricsServiceTest,AgentToolCallJournalServiceTest,AgentLoopGuardTest,RunCommandToolTest,ShellToolContractTest,AgentLoopEngineStreamingContractTest test
```

**已覆盖的可观察结果**：非零 shell exit 仍交给模型阅读完整输出，但任务进度进入 repair、同类 DNS/依赖命令可进入环境阻塞处理、workspace memory 明确记录 FAIL 与 failure event、metrics 同时记录 `transportSuccess=true` / `executionSuccess=false`。这使后续模型不会从“PASS”记忆或成功遥测中得到相反信号。

**仍未关闭的风险**：本切片没有完成整个跨会话记忆的噪声治理，也不替代真实 browser/worker 的现场验收；当前仅消除了失败 outcome 被误写成成功事实的路径。全局记忆的可插拔设计与 Hermes 对比仍按主计划在 harness 核心完成后单独调研。
### 切片 C12：durable progress replay 不把非零 exit 重放成成功（2026-08-16）

**Public seam**：`AgentRunProgressProjectionService.load(...)` 由 durable Tool Part 重建 task stage、verification、last tool status，并在恢复后的 prompt 中作为工程事实。

**Red**：C11 修正了当前 JVM 的增量 `afterTool(...)`，但 durable projection 仍只把 `AgentRunPart.status` 传给 reducer。非零 shell exit 按协议持久化为 `status=completed` 且 metadata 为 `{failureClass: non_zero_exit, execution.status: failed}`；旧恢复路径忽略 metadata，重启后会把最后状态重建成 completed，丢掉 repair 语义。

**Green**：`AgentRunExecutionProgressReducer` 增加纯 `effectiveToolStatus(...)`：只在 transport `completed` 时读取结构化 `failureClass` 和 `execution`，将 cancelled / infrastructure / non-zero / timeout 映射为各自工程状态；既有无 metadata 的历史 Part 保持兼容。`AgentRunProgressProjectionService` 用这份 effective status 更新 reducer 和 `lastStatus`，使 live、恢复和 prompt projection 共享同一实际结果。

**本地参考与适配**：参考 `D:\opencode\opencode-dev\packages\app\src\context\global-sync\event-reducer.ts:228-253` 按 durable Part ID 进行幂等状态重放，以及 `D:\opencode\opencode-dev\packages\opencode\src\session\message-v2.ts:326-370` 在保留 tool output 的同时区分错误/中断 Part。LabexAgent 未复制实质源码；其多用户 durable transcript 需要额外适配 execution metadata，因此把解释规则放入无 DB 依赖的 Java progress reducer。

**Red → Green 证据**：

```text
# Red：reducer 没有 metadata-aware public seam，progress service 只读取 part.status
cd backend && mvn -q -Dtest=AgentRunExecutionProgressReducerTest,AgentRunProgressProjectionServiceTest test

# Green：metadata-aware reducer 与 durable service replay
cd backend && mvn -q -Dtest=AgentRunExecutionProgressReducerTest,AgentRunProgressProjectionServiceTest test
```

**已覆盖的可观察结果**：一条 completed shell Part 带 `failureClass=non_zero_exit` / `execution.status=failed` 后，恢复投影为 `stage=repair`、`lastStatus=error`，不会增加成功 verification；写入前的 unverified changes 也不会在重启后被失败 shell 误清除。

**仍未关闭的风险**：本切片覆盖 durable Part replay，但尚未进行真实 worker 中断、JVM 重启、SSE reconnect 的端到端现场验收；旧历史没有结构化 metadata 时为兼容仍按其原 status 重放。

### 切片 C13：初始 SSE 流漏收 FINAL 时从 durable transcript 恢复（2026-08-16）

**Public seam**：初始 `POST /agent/stream` 的 Vue 消息状态、`AgentSsePublisher` 的 durable/transient 投影边界，以及 task detail 暴露的 `runMessages` / `parts`。

**事故与 Red**：历史运行日志已经写出最终答复，但页面只显示工具/思考时间线，说明“日志写过”不能等价于浏览器已经收到并渲染 `FINAL`。旧的初始直连流只在 approval 等可恢复态转交订阅；普通 terminal stream 结束后不会回读同一 task 的 durable transcript。更糟的是，`streamFinal(...)` 先发送 `FINAL_DELTA`；当 primary SSE 客户端恰在该 transient 帧断开时，`AgentSsePublisher.sendTransient(...)` 会抛出 `IOException`，调用链可能在追加 durable `FINAL` 前退出。

**Green**：`AgentSsePublisher` 将 transient 断线视为观察者关闭：标记连接关闭、继续执行、不把 IOException 传播到 Agent loop；随后 `FINAL` 仍通过既有 lifecycle/outbox 写入 durable Event，并由既有 Part 投影路径持久化。前端新增 `hasDurableFinal`，只在收到 durable `FINAL` 时置位；初始 stream 完成但尚未收到该标记时，以 task ID 请求同一会话、同一 session 的 task snapshot，并将 authoritative Run Message / Part 投影回当前 assistant message。即使只收到了一段 `FINAL_DELTA`，也会用完整 durable final 覆盖；不读取 `AgentTask.summary` 作为最终文本。

**本地参考与适配**：参考 `D:\opencode\opencode-dev\packages\opencode\src\session\processor.ts:248-293`：流式 reasoning/text 的结束会写回持久化 Part；以及 `D:\opencode\opencode-dev\packages\app\src\context\global-sync\event-reducer.ts:228-253`：UI 对 `message.part.updated` 按稳定 Part ID 幂等合并，而不是把连接存活当作事实。LabexAgent 适配为 Spring SSE + outbox + Vue direct-stream fallback；未复制实质源码。

**Red → Green 证据**：

```text
# Red：主 SSE transient FINAL_DELTA 断线会抛出，阻断后续 durable FINAL
cd backend && mvn -q -Dtest=AgentSsePublisherDurabilityTest test

# Red：直连流已有局部文本却未收到 durable FINAL 时，不会从 task transcript 补齐
cd frontend && node --test src/composables/useAgentTaskRuntime.test.mjs src/composables/useAgentEventTimeline.test.mjs src/composables/agentHistoryReducer.test.mjs

# Green：断线不打断 durable final；live/history reducer 与直连 terminal hydration 一致
cd backend && mvn -q -Dtest=AgentSsePublisherDurabilityTest test
cd frontend && node --test src/composables/useAgentTaskRuntime.test.mjs src/composables/useAgentEventTimeline.test.mjs src/composables/agentHistoryReducer.test.mjs src/views/agentStreamIntegration.test.mjs
```

**已覆盖的可观察结果**：浏览器漏收 FINAL、只收到 partial delta、或 final 前 transient client disconnect 时，任务仍可写出 durable final；当前会话不会用 task summary 猜答复，而是从同一 task 的 Run Message/Part 恢复可见文本。已正常收到 FINAL 的会话不会多一次 task snapshot 查询；provider ERROR 仍优先显示既有安全错误，不被 stop final 覆盖。

**仍未关闭的风险**：尚未完成真实浏览器断网/刷新/恢复的现场 smoke，也尚未证明所有 legacy terminal 分支都能产生相同 candidate/accepted/rejected Part 形态。本切片只关闭“最终答复已生成但直连页面为空或截断”的 durable delivery 链。

### 切片 C14：terminal snapshot 滞后时不把 partial delta 认证为 FINAL（2026-08-16）

**Public seam**：`useAgentTaskRuntime.reconcileDirectTerminalTask(...)`：初始直连 SSE 结束后，以同一 task 的 durable Run Message / Part 快照补齐当前 assistant message。

**Red**：C13 的首次回读已经能补齐普通漏收，但 task 已终态而 outbox/Part projection 尚未可读时，快照可能暂时没有 `assistant:final`。旧实现却把页面上已有的任意非空文本（包括只收到的 `FINAL_DELTA`）写为 `hasDurableFinal=true`，从而把 provisional 文本误认证为 durable final，也不再进行后续恢复。

**Green**：runtime 只根据 `runMessages` 中真实的 `messageKey=assistant:final` 设置 `hasDurableFinal`。缺少该 durable Message 时，按集中配置进行有限重读；同一 conversation/session/task 的最终快照一旦出现，才覆盖 partial delta。若重读窗口耗尽，则保持“尚未收到 durable final”，不使用当前内容或 `AgentTask.summary` 猜测成功。

**本地参考与适配**：继续参考 `D:\opencode\opencode-dev\packages\opencode\src\session\processor.ts:248-293` 的流结束持久化 Part，以及 `D:\opencode\opencode-dev\packages\app\src\context\global-sync\event-reducer.ts:228-253` 对 durable Part 的幂等投影。LabexAgent 适配为 Vue 的 direct-stream 收尾读取；未复制实质源码。

**Red → Green 证据**：

```text
# Red：第一次 terminal snapshot 还没有 final 时，旧实现只读一次并把 partial 文本认证为 durable
cd frontend && node --test src/composables/useAgentTaskRuntime.test.mjs
# 新增回归失败：expected task detail requests=2, actual=1

# Green：有限重读后从同一 task 的 assistant:final 覆盖 partial，且只由 durable message 设置 marker
cd frontend && node --test src/composables/useAgentTaskRuntime.test.mjs
```

**已覆盖的可观察结果**：FINAL 的 transient 文本不是最终事实。第一次 task detail 缺 final、下一次出现 final 时，用户看到的是完整 durable 答复而不是已被误认证的截断文本；task detail 永远属于当前 conversation/session，切换会话后立即停止恢复。

**仍未关闭的风险**：有限重读不是无限重试；如果 durable projection 持续不可用，页面不会伪造成功，但仍需要既有刷新/事件订阅路径或服务端 outbox 修复来恢复。尚未完成真实浏览器断网/刷新现场 smoke。
