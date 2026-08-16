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
- [ ] 文件 mutation 记录 before/after 与 target identity。
- [ ] 删除失败不会产生成功 verification。
  - [x] direct `write_file` / `apply_patch` 已在 workspace lease 内记录真实写后 verification；临时 workspace 删除回归已覆盖文件、change-set、ToolResult/Part/Event 投影一致。
  - [x] approval `shell` 已将 snapshot change-set 的真实 postcondition（delete=absent，其它=regular_file）与 identity/changeId 写回原 ToolResult、既有 Tool Part 和 `WORKSPACE_CHANGED`；真实 worker 与浏览器刷新验收仍待完成。
- [ ] 重复恢复不会产生第二次副作用。
  - [x] approval consume 的第二次执行不会再次启动命令或 resume（聚焦回归已覆盖）；完整断线/worker 接管恢复仍待验收。

## Final 与前端

- [x] native final 与服务器验证证据可 durable 回放（legacy 的全部 candidate/rejected 分支仍待统一验收）。
- [ ] 任务终态不由 SSE close 推断。
- [x] native final 在 reducer replay / 流式事件回放后仍显示（真实浏览器 smoke 仍待执行）。
- [ ] tool 失败会在 UI 与 verification 中保留。
  - [x] C9：durable Tool Part / `TOOL_CALL_STATE` 的 `metadata.failureClass` 和 `execution` 已在 reducer 中投影；完成 transport 的非零 shell exit 显示为错误而非绿色完成，native `shell` 卡片可显示命令。真实浏览器 smoke 仍待做。
- [ ] 计划为 UI projection，不是 tool completion gate。
- [ ] workspace change 仅刷新正确项目与 task 的文件树。

## 扩展能力

- [x] native exposure snapshot 以 `TOOL_EXPOSURE` durable Event/Part 持久化，并在同 profile/mode 恢复时重建 scoped MCP binding（真实 MCP 现场恢复 smoke 仍待做）。
- [ ] Web Search 与 Fetch 按条件暴露。
- [ ] 图片工具只在有效附件与模型能力下暴露。
- [ ] LSP 以 operation 型工具按语言 client 暴露。
- [x] Skill catalog 与内容按需分离（native：目录与单项正文分离）。
- [ ] MCP native 已按当前 student/task scoped exposure 挂载，不再读取全局 dynamic registry；仍需以真实连接断线、权限变更与恢复 smoke 验收“只加载连接可用、允许”的完整边界。
- [x] MCP adapter 保留原始 JSON Schema 类型，不将 integer / array / object 参数降级为 string。

## 验收与收口

- [ ] 每个实现切片有红灯和绿灯证据（本轮 ToolExposure/MCP schema 切片已补齐；其余未完成切片仍待补）。
- [x] 当前阶段 A 的后端聚焦测试通过。
- [x] 完整后端测试通过（2026-08-16：`cd backend && mvn -q clean test`）。
- [ ] 前端 reducer 测试通过。
- [ ] 浏览器 smoke 覆盖删除、final、刷新、重连。
- [ ] 旧 conversation legacy 回归通过。
- [ ] native conversation 端到端真实读写验证通过。
- [ ] 文档、DTO、schema、reducer、事件同步更新。
- [ ] 已审查 diff；未提交、未推送，除非用户明确要求。
