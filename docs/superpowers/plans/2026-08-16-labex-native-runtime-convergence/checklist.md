# LabexAgent 原生运行时实施与验收清单

## 文档与边界

- [x] 已有设计规格已关联。
- [x] 已写入分阶段实施计划。
- [x] 已写入 TDD 测试与验收文档。
- [x] 用户已于 2026-08-16 确认 S1 至 S6 public seam。
- [x] 已记录基线工作树，并在后续切片中保留既有未提交改动。
- [ ] 未在 runtime、API、数据库、事件或 UI 中引入外部参考项目名称。
- [x] 当前只复刻本地参考实现的不变量，未复制实质外部源代码。

## Profile

- [x] conversation 保存运行时 profile。
- [x] task 保存 immutable profile snapshot。
- [ ] 历史空值稳定回退 legacy。
- [ ] 新 conversation 可选择 profile。
- [x] profile 冲突有明确错误。
- [ ] fork、resume、background、approval 保持 profile。


## 当前阶段 A 进度

- [x] `AgentRuntimeProfile`、集中化默认 profile 配置、conversation/task additive 持久化字段和请求映射。
- [x] 新建、冲突、fork、task snapshot 与 durable continuation 的聚焦回归。
- [x] `AgentLoopEngine` 在 task 已确定后只采纳 task profile snapshot，恢复请求不能以 payload 覆盖它。
- [x] native profile 已实际改变 Provider system prompt，并以 task snapshot 过滤 Harness 控制工具 schema。
- [x] `labex-native` 已分派至 `LabexNativeToolBatchExecutor`，并有“全批先 pending、顺序执行、approval skipped、cancellation interrupted、toolCallId 配对”的组件回归；受控 Provider + H2 已覆盖正常 batch 的真实 Message/Part/Event 和 approval 的 `waiting_approval`/`skipped`。
- [ ] 前端新 conversation profile 选择入口（必须等 native 实际语义可执行后再显示）。
## Transcript 与生命周期

- [x] Provider 请求的 durable 部分只从 `AgentTranscriptProjectionService` 投影读取；运行时进度作为只读调用边界投影追加，不回写为第二事实源（public start/resume 的现场 smoke 仍待做）。
- [x] tool call 与 tool result 按 ID 一一对应（受控 Provider + H2 正常多工具 batch 回归）。
- [x] 同批 tool call 先持久化后执行（首个 delegate 执行前查询真实 `tool_call` Part）。
- [x] 未执行 Part 显式 interrupted 或 skipped（approval 的真实 `skipped` 已覆盖；cancellation 组件回归已覆盖 `interrupted`，其 H2 回归待补）。
- [ ] epoch、lease、approval、resume 幂等。
- [ ] overflow 与循环保护有限且可解释。
  - [x] 不同参数的失败工具调用不再被累计为“模型连续无进展”；同签名重复失败仍由持久化签名循环检测阻断。
  - [x] shell 非零 exit 仍以可读的已完成工具输出交给模型，但不能重置循环保护，也会进入同签名失败检测。

## 工具与安全

- [ ] native schema 没有重叠 command/test/control 工具。
  - [x] 文件 mutation 已收敛：新 native build 不暴露 `edit_file`；`write_file` 只负责单文件创建/全量替换，`apply_patch` 只负责精确上下文替换/删除；旧 exposure snapshot 保持其原始 schema 可恢复。
  - [x] native command schema 仅暴露 `shell`；`bash`、`run_command`、`run_tests`、`execute_code` 不进入新 native build 的模型 schema。
- [ ] normal install、build、test、lint、dev、Git add/commit 可用。
- [ ] destructive、越界、secret、生产高风险操作受结构化保护。
- [x] shell 结果记录 exit、error、target、证据：C8 已将退出码、稳定 `failureClass`、安全相对 workdir/artifact path 与既有 identity/verification 写入 durable Tool Part/Event；真实 worker/browser 现场仍待验收。
- [x] 文件 mutation 记录 before/after 与 target identity。
  - [x] direct `write_file` / `apply_patch` 在 workspace lease 内采集真实写前状态和真实写后 verification；临时 workspace 删除回归覆盖文件、change-set、ToolResult/Part/Event 投影一致。
  - [x] approval `shell` 与普通 native `shell` 都从 durable snapshot change-set 取得写前事实，再对实际 workspace 执行写后 postcondition；只投影安全相对路径、hash、字节数、changeId 与 task/epoch identity。
- [x] 删除失败不会产生成功 verification。
  - [x] `delete` 只有 observed `absent` 才会是 target `after.verified=true`；`create` / `modify` / `rename` 必须实际 `present` 且 hash、字节数一致。mismatch、unavailable 或脏绝对路径不能被投影为 verified。
  - [x] 同一份 `workspaceMutation` / `workspaceVerification` metadata 同时驱动即时 `AgentContext` 与重启后的 durable Part replay；mismatch 保留目标为未验证并进入 repair，Native completion-readiness 不会因此写入提示。
  - [ ] 真实 worker、浏览器文件树刷新和断线恢复验收仍待完成。
- [ ] 重复恢复不会产生第二次副作用。
  - [x] approval consume 的第二次执行不会再次启动命令或 resume（聚焦回归已覆盖）；完整断线/worker 接管恢复仍待验收。

## Final 与前端

- [x] native final 与服务器验证证据可 durable 回放（legacy 的全部 candidate/rejected 分支仍待统一验收）。
- [ ] 任务终态不由 SSE close 推断。
- [x] native final 在 reducer replay / 流式事件回放后仍显示（真实浏览器 smoke 仍待执行）。
- [ ] tool 失败会在 UI 与 verification 中保留。
  - [x] C9：durable Tool Part / `TOOL_CALL_STATE` 的 `metadata.failureClass` 和 `execution` 已在 reducer 中投影；完成 transport 的非零 shell exit 显示为错误而非绿色完成，native `shell` 卡片可显示命令。真实浏览器 smoke 仍待做。
  - [x] C10：随后到达的实时 `OBSERVE` 复用并发送同一份 failure/execution 事实；即使 transport `success=true`，也不会把非零 shell exit 从 error 覆盖回 completed。history reducer 与 live timeline 都有回归。
  - [x] C11：非零 shell exit 的 transport completed 与真实 execution failure 已在工程进度、环境恢复分类、command failure guard、持久化 workspace memory、metrics 与运行日志中分离；模型工具协议仍保留完整输出。
  - [x] C12：恢复时的 `AgentRunProgressProjectionService` 也读取 Tool Part metadata；重启后 completed transport + `failureClass=non_zero_exit` 重放为 repair，last status 也显示 error。
  - [x] C13：初始 POST SSE 漏收或只收到部分 final 时，前端按同一 task 的 durable Run Message / Part 补齐最终答复；transient SSE 断线也不会阻止随后 `FINAL` 事实落库。
  - [x] C14：terminal task detail 与 outbox Part 投影短暂不同步时，直连页会有限重读同一 task；只有真实 `assistant:final` Run Message 才能把 partial delta 标记为 durable final。
  - [x] C15：初始 SSE transport 提前关闭但 task 仍为非终态时，前端改用同一 task 的 durable event subscription 继续回放；不再把连接 close 当成任务完成。
- [ ] 计划为 UI projection，不是 tool completion gate。
- [ ] workspace change 仅刷新正确项目与 task 的文件树。

## 扩展能力

- [x] native exposure snapshot 以 `TOOL_EXPOSURE` durable Event/Part 持久化，并在同 profile/mode 恢复时重建 scoped MCP binding（真实 MCP 现场恢复 smoke 仍待做）。
- [ ] Web Search 与 Fetch 按条件暴露。
  - [x] C16：live native exposure 只在当前配置路由有可执行 Web Search provider 时包含 `web_search`；`web_fetch` 保持本地 HTTP(S) capability，不增加额外确认。已持久化 exposure snapshot 不受后续配置变化影响。
  - [x] C17：`web_fetch` 在读取前校验声明长度，并对未知长度响应执行流式字节上限；连接、请求、重定向、响应与模型输出范围均集中在 `labex-agent.web-fetch`。
- [ ] 图片工具只在有效附件与模型能力下暴露。
- [ ] LSP 以 operation 型工具按语言 client 暴露。
- [x] Skill catalog 与内容按需分离（native：目录与单项正文分离）。
- [ ] MCP native 已按当前 student/task scoped exposure 挂载，不再读取全局 dynamic registry；仍需以真实连接断线、权限变更与恢复 smoke 验收“只加载连接可用、允许”的完整边界。
- [x] MCP adapter 保留原始 JSON Schema 类型，不将 integer / array / object 参数降级为 string。

## 验收与收口

- [ ] 每个实现切片有红灯和绿灯证据（本轮 ToolExposure/MCP schema 切片已补齐；其余未完成切片仍待补）。
- [x] 当前阶段 A 的后端聚焦测试通过。
- [x] 完整后端测试通过（2026-08-17：`cd backend && mvn -q test`；342 份 Surefire XML 报告均无 failures/errors）。
- [x] 相关前端 reducer/timeline 聚焦测试通过（2026-08-17：87/87）。
- [x] 前端生产构建通过（2026-08-17：`cd frontend && npm run build`）。
- [ ] 浏览器 smoke 覆盖删除、final、刷新、重连。
- [ ] 旧 conversation legacy 回归通过。
- [ ] native conversation 端到端真实读写验证通过。
- [ ] 文档、DTO、schema、reducer、事件同步更新。
- [ ] 已审查 diff；未提交、未推送，除非用户明确要求。

## 切片 N1：模型超时与用户取消分离（2026-08-16）

- [x] 在单轮模型调用中使用独立的 transport cancellation token；watchdog 只能终止该次 provider stream，不能写入 ActiveRun 的用户取消状态。
- [x] `model_timeout` 作为结构化失败原因投影到运行日志、`ERROR`/`DONE` 事件和任务终态；不得落为 `cancelled` 或“用户主动取消”。
- [x] 将外层模型轮次总超时集中为 `labex-agent.model-turn.total-timeout-ms`，默认 300000ms；`0` 明确表示关闭外层总时限，provider 自身超时仍生效。
- [x] 原始 provider `thinking`/`reasoning` 流继续按现有逐 delta、完整落盘策略透传；本切片不摘要、不隐藏、不以 CoT 文本驱动状态迁移。
- [x] 聚焦回归覆盖：watchdog 超时不会取消 parent ActiveRun，provider transport 被本地取消，真实用户取消仍向 provider token 传播。
- N1 验证：聚焦 runtime 回归与当时的完整后端 `mvn -q test` 均通过。事故日志与当前工作日期同为 2026-08-17；诊断依据是旧 45000ms watchdog 与约 46 秒无模型输出的对应关系，而非把墙钟日期误判为取消原因。

## 切片 N2：完成证据就绪后的有限收束（2026-08-17）

- [x] 仅在 native 编码/改动任务已有真实 workspace 改动，且 `RunCompletionEvidence` 满足时，向下一轮 Provider transcript 写入一次 durable completion-readiness 指令。
- [x] 指令以 `task + epoch + evidence fingerprint` 作为稳定身份；恢复、重放和同一证据重复观察不会重复写入。
- [x] transcript 指令与 `COMPLETION_READY` durable event 在同一事务内写入；只有两者均成功持久化才向实时 SSE 投影该事件。
- [x] 不从 CoT、关键词或计划文本猜测“已完成”，不强制关闭工具；模型只能在能够指出具体未满足用户要求时继续探索，否则应直接给出最终答复。
- [x] 只读/分析任务与未验证/无改动任务不会触发该提示，避免服务端验证事实抢占用户语义。
- [x] completion-readiness 指令标记为 `visibility=internal`，任务公开 snapshot 会过滤它；兼容同一类旧 key，避免内部 Provider 指令错误显示到前端。
- [x] 聚焦后端回归通过：证据门槛、幂等、transaction event、transcript、Part、finalization、公开历史与 Native batch wiring。
- [x] 聚焦前端 reducer 回归通过：`COMPLETION_READY` 仅成为状态投影，不渲染为对话文本或工具卡。
- [ ] 仍需真实浏览器/受控 Provider smoke：验证一次已通过的真实文件改动后模型在下一轮立即 final，且刷新/重连不显示内部指令。


## 切片 N3：Workspace 真实事实链跨路径收口（2026-08-17）

- [x] 直接文件工具、审批命令和普通 native `shell` 都复用 `WorkspaceMutationEvidence` 投影 `workspaceMutation` / `workspaceVerification`，不从模型参数或命令文本猜测文件结果。
- [x] 真实 postcondition mismatch 不会把 target 写成 `verified=true`；相对路径大小写保持原样，非法绝对路径不会进入运行进度或 prompt。
- [x] `AgentRunExecutionProgressReducer` 是即时上下文和 durable Part replay 的唯一 progress 规则：已验证 target 只清除自身，mismatch 保留未验证 target 并进入 repair。
- [x] `maybeSignalCompletionReadiness(...)` 在存在未验证 workspace target 时直接返回；这是 readiness nudge 的事实门槛，不是对模型最终答复的全局强制拦截。
- [ ] 仍未实现用户另行排期的“所有 tool failure 后禁止最终答复假称成功”、循环保护最终 Part 一致性和真实浏览器 / worker smoke。

### N4：同会话跨 Task Provider 投影（2026-08-17）

- [x] Provider 请求历史由“稳定 Conversation 前缀 + 当前 Task durable transcript”构成；前缀顺序在当前 Task 之前。
- [x] 使用当前 `AgentTask` 的 student/project/conversation identity 与 `beforeTaskIdExclusive=taskId`，不重复投影当前 Task。
- [x] `loadDurableProjection(...)` / interaction resume 仍是 Task-only，不能因 conversation 前缀破坏首次 append 或可恢复 Tool Part。
- [x] 找不到当前 Task 时 fail closed；不从 UI、日志、`buildMemoryContext` 字符串或全局 workspace memory 回退。
- [x] Provider 合并后重新执行原生工具协议校验；跨 Task 历史不会形成孤立 tool result。
- [x] Task compaction 只读取当前 Task durable transcript；预算与真实请求仍统计完整 Provider projection。
- [ ] 会话级自动 compaction 尚未实现；跨 Task 历史过大时必须给出明确 context-limit 结果，不能将会话前缀写入 Task compaction。
- [ ] 真实 Provider + 浏览器验收：同一 Conversation 的下一 Task 可引用上一稳定方案；新 Conversation 不泄漏；刷新/恢复不重复历史。
