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
- [ ] 新增 `ToolExposurePlanner` 与 `ToolExposureSnapshot`，在 provider request 对应 durable metadata 保存 exposure。
- [ ] 将 native shell 统一到一个实现，legacy duplicate tool 保留但不对 native schema 暴露。
- [ ] 明确 native `write_file` 与 `apply_patch` 的互斥职责，`edit_file` 仅做 legacy compatibility。
- [ ] 扩展 tool Part metadata 写入 exit status、error class、target、mutation、verification。
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
