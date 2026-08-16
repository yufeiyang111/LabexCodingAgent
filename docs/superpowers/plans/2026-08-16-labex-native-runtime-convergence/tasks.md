# LabexAgent 原生运行时收敛实施任务

> 执行规则：每一项都必须以一条已确认 public seam 的失败测试开始；只实现使该测试变绿的最小改动；不要在同一切片预做后续重构。

## 阶段 0：基线与事故特征化

- [x] 固化已批准的架构设计：`docs/superpowers/specs/2026-08-15-labex-native-runtime-convergence-design.md`。
- [x] 写入本实施计划与 TDD 验收文档。
- [x] 记录开始实施前的 `git status --short` 与相关 diff，不覆盖既有未提交改动。
- [ ] 为三次删除 Skill 未真实落盘的日志建立黑盒回归：模型结论必须与真实文件系统和 tool result 一致。
- [ ] 为最终答复已生成但前端未显示建立 replay 回归：durable final 在刷新后可见。
- [ ] 为工具失败后模型声称完成建立回归：final 中可说明失败，但 verification 不能变成功。
- [ ] 为连续无进展循环建立回归：停止原因、最后 Part 状态和用户可见提示一致。
- [ ] 运行现有聚焦测试，记录测试名、结果与任何已有失败，不在本阶段修复无关问题。

## 阶段 A：对话级 Runtime Profile

### 先写红测

- [x] `AgentConversationService` 的 public 创建接口：新 conversation 可保存指定 profile。
- [ ] 历史 conversation 的空 profile 读取为 `labex-legacy`。
- [x] task 创建时复制 conversation profile，后续 conversation 变更不能影响该 task。
- [x] 用已有 conversation 发起请求却传入不同 profile 时返回明确冲突。
- [ ] fork、后台 task、审批恢复、断线恢复均保持同一 profile。

### 最小实现

- [x] 新增 `AgentRuntimeProfile` 领域枚举与集中化运行时配置。
- [x] 给 `AgentConversation`、`AgentTask`、schema 与 `AdditiveSchemaMigrator` 增加 additive `runtime_profile`。
- [x] 扩展 `AgentStreamRequest`、multipart request 映射、conversation DTO 与 API 校验。
- [x] 在 `AgentConversationService` 与 `AgentTaskService` 中实现单向 profile snapshot。
- [x] 在 `AgentLoopEngine` 内接入 `AgentRuntimeProfileResolver`，不改变 legacy 执行路径。
- [x] native profile 的 Provider 投影：直接、证据优先系统提示词与不含 Harness 控制工具的 schema。
- [ ] 在前端新 conversation 创建入口展示 Labex 风格的运行时选择及当前 profile，只影响新建 conversation。

### 绿测与验收

- [x] 运行 profile 相关后端单测和 controller 集成测试。
- [ ] 用两个 conversation 做现场 smoke：同一项目分别保持 legacy 与 native 路由。

## 阶段 B：Native durable turn processor

### 先写红测

- [ ] native 首轮请求只能从 durable Message/Part projector 得到 Provider messages。
- [x] 一个 assistant turn 的多个 tool call 先全部落 Part，再按确定性顺序执行（受控 Provider + H2 durable Message/Part/Event 回归）。
- [x] tool call 与 tool result 的 `toolCallId` 一一对应，恢复后不丢失（正常多工具回归已覆盖；resume 仍单列验收）。
- [ ] 审批等待、用户问题、取消、断线恢复不会把任务伪标记为 final（approval 的 durable `waiting_approval`/`skipped` 已覆盖；Engine public start/resume、用户问题与断线恢复待覆盖）。
- [ ] context overflow 只有有限重试，最终产生结构化原因与可恢复或失败终态。

### 最小实现

- [ ] 新增 `runtime/labexnative/LabexNativeTurnProcessor`，由现有 `AgentLoopEngine` 分派调用。
- [ ] 新增 native transcript request builder，复用 `AgentTranscriptProjectionService` 与现有 Run Message/Part 服务。
- [x] 新增 `LabexNativeToolBatchExecutor`：native profile 下整批 assistant tool_calls/Part 先 durable 落库，再串行委托既有工具执行包装；approval 时剩余 `skipped`，取消时剩余 `interrupted`。
- [ ] 将 native 跨迭代可变 transcript 移除或降为单次派生局部变量。
- [ ] 仅为纯 projection 做 shadow compare；禁止双执行任何 tool 副作用。

### 绿测与验收

- [x] 运行 native multi-tool、approval、cancellation、transcript/journal、Engine wiring 聚焦测试；受控 Provider + H2 已验证正常 batch 的 Message/Part/Event 顺序与 approval 的 `waiting_approval`/`skipped`。`resume`、compaction、overflow 仍需下一切片覆盖。
- [ ] 在测试 workspace 真实执行一轮读、改、验证并中断恢复。

## 阶段 C：核心工具、目标身份与真实验证

### 先写红测

- [ ] native schema 只包含预期核心工具，重叠 command/test/control tools 不可见。
- [ ] `shell` 的成功、非零退出、超时、取消均产生结构化真实结果。
- [ ] 文件写入或补丁成功后记录一致的 workspace target identity 与 before/after 证据。
- [ ] `rm`、危险 Git 操作、工作区外 mutation 进入正确 approval 或拒绝分支；正常 install/build/test/commit 不弹确认。
- [ ] 删除文件的 tool result、文件树和 verification 三方一致，禁止仅凭模型文本完成。
- [ ] 同一 `toolCallId` 的恢复或重复审批不会执行第二次副作用。

### 最小实现

- [ ] 新增 `WorkspaceOperationIdentity` 与稳定 fingerprint，并接入 workspace、shell、file mutation、diff、verification。
- [x] 新增 `ToolExposurePlanner` 与 `ToolExposureSnapshot`：native 在 task/student 作用域选择扩展工具，向 durable `TOOL_EXPOSURE` Event/Part 保存 exposure，并在恢复时优先读取匹配快照。
- [x] 将 native shell 统一到一个实现，legacy duplicate tool 保留但不对 native schema 暴露：native build 仅暴露 `shell`（`RunCommandTool`）；`bash`、`run_command`、`run_tests`、`execute_code` 仅保留 legacy/兼容执行路径。
- [x] 明确 native `write_file` 与 `apply_patch` 的互斥职责，`edit_file` 仅做 legacy compatibility：新 native build 仅暴露 `write_file`（单文件创建/全量替换）与 `apply_patch`（上下文替换/删除），历史 exposure snapshot 仍按原 schema 回放。
- [x] 扩展 tool Part metadata 写入 exit status、error class、target、mutation、verification：`ToolResult` 的 durable metadata 已包含执行状态/退出码、稳定 `failureClass`、安全相对 workdir/artifact path，以及既有 workspace identity/mutation/verification；Journal 原样写入 Tool Part/Event。
- [ ] 成功 mutation 后发布 durable `WORKSPACE_CHANGED`。

### 绿测与验收

- [ ] 运行 shell、权限、identity、删除、幂等、verification 聚焦测试。
- [ ] 在真实 worker worktree、主 workspace、相对路径嵌套目录分别验证 target 不串位。

## 阶段 D：Final、计划投影与前端回放

### 先写红测

- [ ] accepted final 可以从 durable event 与 Part 在刷新后重建。
- [ ] candidate 被拒绝或任务中断时前端显示明确状态，不静默吞答复。
- [ ] 已失败 tool result 不会被 final 或 verification 投影为成功。
- [ ] `WORKSPACE_CHANGED` 只刷新匹配的项目和 task 文件树，不污染其他 conversation。
- [ ] native task 在没有 `create_plan`、`plan_exit`、`todo_write` 时仍可完成。

### 最小实现

- [ ] 新增 `LabexNativeCompletionProjector`，写入 candidate、accepted、rejected、interrupted durable 事实。
- [ ] 调整现有 finalizer，使 legacy 行为不变，native 不走关键词或计划门槛。
- [ ] 让 `AgentRunPlanService`、Part 和 event reducer 生成计划 UI projection。
- [ ] 修改前端 timeline、history reducer、task runtime、workspace files 与 CloudWorkspace 处理 durable final 和 workspace change。
- [ ] 清理任何以 SSE close、日志文本或临时 Pinia state 推断任务完成的 native 路径。

### 绿测与验收

- [ ] 运行前端 reducer、stream reconnect、workspace refresh 测试。
- [ ] 浏览器现场复验：删除真实文件、查看 final、刷新页面、重新进入 conversation。

## 阶段 E：扩展工具按需挂载

### 先写红测

- [ ] 未启用能力不进入 native schema。
- [ ] Skill catalog 不包含完整正文；只有显式调用后才读取对应内容。
- [ ] MCP 断线或 schema 变更时恢复使用持久化 exposure snapshot，并提供可解释失败。
- [ ] LSP 无 client 或路径不支持时不暴露或返回明确不可用结果。
- [ ] 图片工具没有有效授权附件或模型能力时不可用。
- [ ] Web Fetch 对异常 URL、超时、过大响应和内网目标保持安全失败，不影响其他工具。

### 最小实现

- [ ] 将 Web Search、Web Fetch、Image、LSP、Skill、MCP 接入 `ToolExposurePlanner`。
- [ ] 为每一类能力写入 exposure 原因、schema fingerprint 和可恢复错误分类。
- [ ] 将 MCP 已连接 tool definition 动态适配为 native schema，移除 native generic `mcp_call` 依赖。
- [ ] 将 LSP 合并为 operation 型 native 工具，保留旧工具兼容层。

### 绿测与验收

- [ ] 运行 capability gating、skill lazy load、MCP dynamic definition、LSP availability、Web Fetch boundary 测试。
- [ ] 用真实可用的一个 MCP、一个 Skill、一个 LSP 项目做现场 smoke。

## 阶段 F：兼容路径收口

- [ ] 增加 profile usage、projection shadow 差异、恢复错误与工具 exposure 不一致的可观测指标。
- [ ] 确认 native conversation 不再读取 legacy transcript、plan gate、final gate 或 duplicate command tool。
- [ ] 确认 legacy conversation 没有因 native 改造改变行为。
- [ ] 仅在删除条件满足后移除 dead branch；每次删除先增加保护测试。
- [ ] 更新 README、运行时文档、API DTO 文档与迁移说明。
- [ ] 跑完整后端测试、前端相关测试、构建和浏览器现场验收。
- [ ] 审查 `git diff`，只在用户明确要求后创建提交。

### 增量实现记录：最终答复、工作区证据与按需扩展（2026-08-16）

> 本节只记录已由 Red → Green 覆盖的窄切片，不将其误标记为阶段 C/D/E 的整体完成。

1. **最终答复与服务器验证分离**：native profile 不再以关键词、长度、Markdown 格式或“工程任务必须先调工具”阻断模型最终答复；当服务器验证未满足时，答复仍作为 durable `FINAL` 展示，任务以 `failed/unverified` 终止并附验证证据。legacy 保留原有恢复门槛。
2. **工作区 mutation 事实投影**：命令审批与文件 mutation 通过 before/after snapshot、change-set 和 durable `WORKSPACE_CHANGED` 事件投影文件树刷新；前端按 project/task 归属去重，不能用模型自述代替文件系统证据。
3. **native 按需扩展的第一段**：LSP 作为 operation 型原子工具进入 native schema；仅当用户存在启用 Skill 时才挂载 `skill`。无 `name` 的 Skill 调用仅返回紧凑目录，只有明确 name 才读取一份正文；context preview 不再无效读取全量 Skill/MCP prompt 文本。
4. **MCP schema 保真**：`McpToolAdapter` 不再把所有 MCP 参数扁平化为 string，而是保留 server 提供的 JSON Schema（含 integer、array、nested object、required、additionalProperties）。这修复了模型 schema 与参数校验契约不一致的高频失败源。
5. **native MCP task-scoped exposure**：`ToolExposurePlanner` 对 native profile 禁止读取单例 `ToolRegistry.dynamicTools`；只把当前 student 的可用 MCP 定义附加到本次 request，并把执行 binding 存入 `AgentContext` 的 scoped map。`TOOL_EXPOSURE` durable Event/Part 保存无认证信息的 schema、MCP 路由和 fingerprint；恢复优先重建同一快照，不重新发现工具。工具 schema 中的显式 `null` 也会在 Event 与 outbox 持久化时保留。legacy 的全局动态工具兼容路径不变。
6. **仍未关闭的边界**：尚未完成真实 MCP 断线/恢复现场 smoke、LSP 基于语言 client 的细粒度 gating，以及 Web/Image 的全部可用性组合验收；这些不能因本轮 unit/runtime wiring 通过而标记为阶段 E 完成。

**本地参考与适配说明**：

- 已阅读 `D:\opencode\opencode-dev\packages\opencode\src\session\llm\request.ts` 的 `resolveTools`（按 agent/session permission 过滤本次请求工具）；
- 已阅读 `D:\opencode\opencode-dev\packages\opencode\src\session\tools.ts` 的 `resolve`（每个 session request 组装静态工具与 MCP tool，并以原始 schema 经 provider transform 发送）；
- 已阅读 `D:\opencode\opencode-dev\packages\opencode\src\tool\skill.ts`（只在明确 skill name 后加载具体内容）。

LabexAgent 因为是 Spring Boot 多用户 Web + durable transcript，Skill 目录通过显式空 name 工具调用提供，而非把用户级内容写入公共 system prompt；本轮未复制任何外部实质源码。

**本切片红绿证据**：

```text
# Red（Skill/LSP capability 与 Skill catalog API 缺失）
cd backend && mvn -q -Dtest=ToolSelectionPolicyTest,SkillToolTest,AgentLoopEngineNextPreviewTest test

# Green
cd backend && mvn -q -Dtest=ToolSelectionPolicyTest,SkillToolTest,AgentLoopEngineNextPreviewTest test

# Red（MCP integer / object / array schema 被扁平化为 string）
cd backend && mvn -q -Dtest=McpToolAdapterWorkerTest test

# Green（工具 schema、native final、durable Part 聚焦回归）
cd backend && mvn -q -Dtest=ToolSelectionPolicyTest,SkillToolTest,AgentLoopEngineNextPreviewTest,McpToolAdapterWorkerTest,McpClientWorkerTest,ToolExposurePlannerTest,AgentToolExposureSnapshotServiceTest,AgentToolTurnExecutorTest,AgentRunPartServiceTest,AgentLoopEngineToolExposureWiringTest,AgentLoopEngineToolExposureSnapshotWiringTest,ToolArgumentSchemaValidatorTest,ToolSchemaCanonicalizerTest,LabexNativeCompletionProjectorTest,AgentLoopEngineFinalReplyPolicyTest,AgentRunLifecycleServiceTest test


# Red（native 全局 dynamic MCP 泄漏；TOOL_EXPOSURE 尚无 task-scoped snapshot）
cd backend && mvn -q -Dtest=ToolSelectionPolicyTest,ToolExposurePlannerTest test

# Red（合法 JSON Schema 的 default:null 在 durable event/outbox 中被通用 Gson 丢弃）
cd backend && mvn -q -Dtest=AgentRunLifecycleServiceTest#preservesNullableToolExposureSchemaInDurableEventAndOutbox test

# Green（native exposure、恢复快照、schema 保真与 nullable schema durable persistence）
cd backend && mvn -q -Dtest=ToolSelectionPolicyTest,ToolExposurePlannerTest,AgentToolExposureSnapshotServiceTest,AgentRunLifecycleServiceTest#preservesNullableToolExposureSchemaInDurableEventAndOutbox test
```

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

**公共 seam**：批准命令执行后的 transcript continuation、既有 Tool Part 和 `WORKSPACE_CHANGED` durable event。

**Red**：批准的 `rm` 只能回写字符串 `workspace_evidence=captured`；日志、恢复模型和 Part replay 都不能安全关联 changeId 或知道目标当时是否实际 absent。外部 resolved ToolResult 也没有可承载 metadata 的 `completedExisting`/`resolveExistingToolCall` 契约。

**Green**：批准命令记录 snapshot change-set 后，只校验已经进入 change-set 的安全相对路径。删除 target 需 observed `absent`，其余 target 需 observed `regular_file`。这个结果作为 `workspaceVerification` 与 change ID mutation、identity 一并附着在原 ToolResult；控制面 journal 合并其 metadata 到已有 Part，不覆盖原 epoch/sequence/part type，并以同一 metadata 发布 Tool Part event。无工作区检查证据不写作 verified。

**本地参考与适配**：参考 `D:\opencode\opencode-dev\packages\opencode\src\tool\shell.ts:482-539` 的 shell 过程 metadata 持续更新和 `D:\opencode\opencode-dev\packages\opencode\src\snapshot\index.ts:47-52` 的 snapshot 生命周期。LabexAgent 使用已有审批、change-set、durable transcript 和 execution epoch，不复制实质代码。

**Red → Green 证据**：

```text
# Red：新增 public seam 的缺失使测试编译失败
mvn -q -Dtest=AgentToolCallJournalServiceTest#externallyResolvedToolResultPreservesStructuredWorkspaceEvidence,CommandApprovalOrchestratorTest#approvedDeletionProjectsVerifiedWorkspaceEvidenceIntoTheDeferredToolResult test

# Green：同一 ToolResult / Part / event 证据链与既有审批恢复不回归
mvn -q -Dtest=CommandApprovalOrchestratorTest,AgentToolCallJournalServiceTest,AgentRunPartServiceTest test
```

**未关闭**：没有将任意 shell 文本意图猜测成路径；真实 worker、浏览器文件树刷新和断线恢复仍属于后续 C/D 的验收。
