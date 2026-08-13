# LabexAgent 上下文构建 / Token 账本 / 提示缓存对齐验收清单（checklist.md）

> 每个勾选项必须附带命令输出或 live 证据；"源码看起来对"不算完成。

## Phase 0：统一账本

- [ ] `OpenAiCompatibleProvider.extractUsage` 提取 DeepSeek `prompt_cache_hit_tokens / prompt_cache_miss_tokens`；miss 计入 cache write。
- [ ] fixture 四件套（deepseek hit/miss、openai cached、anthropic cache）在提取测试中通过。
- [ ] `cache_usage_reported` 在 DeepSeek 官方字段存在时为 true。
- [ ] 仓库 `labexagent` 包内除 `AgentRequestTokenEstimator` 外无 token 估算公式（grep 证据）。
- [ ] `ContextUsageEstimator` 与 `AgentRequestTokenEstimator` 对同一输入输出一致。
- [ ] provider 无 usage 时 TOKEN_USAGE `estimated=true` 且数值等于唯一估算器。
- [ ] admission 门禁、压缩触发、CONTEXT_STATUS 使用同一套数字。
- [ ] `t_agent_token_usage` 新增 `cache_hit_tokens / cache_miss_tokens`（additive 迁移成功，旧列保留）。
- [ ] 前端缓存卡片显示 token 账本（cache read / cache write / 非缓存 input），百分比标注"会话级统计"。
- [ ] `write_only` 状态可由真实 miss token 触发（测试证据）。
- [ ] 超长 content / output_text 落库不被截断（存储层全量，展示层截断）。

## Phase 1：上下文瘦身

- [ ] 首条 durable user message 不含 `<adaptive_project_context>` / `<repo_map>` / `<workspace_diagnostics>`。
- [ ] 首条请求 input ≤ 8,000 token（fixture 项目，唯一估算器，live 或测试证据）。
- [ ] RepoMapTool / RetrieveContextTool / DiagnosticsTool 无预注入时可用。
- [ ] system prompt 不含工具名清单；body tools JSON 完整。
- [ ] `buildInitialBundle` 仅服务 preview/诊断，provider 路径不引用。

## Phase 2：前缀稳定化

- [ ] 静态工具按 name 排序；同一工具集两次序列化字节一致。
- [ ] 溢出降级工具子集有序且为全量前缀的子集。
- [ ] `PromptPrefixStabilityTest` 通过：同 project+mode+language+config 两次 run system+tools 字节一致。
- [ ] system prompt 无日期/时间戳/session ID（断言证据）。

## Phase 3：压缩/裁剪对齐

- [ ] provider usage 溢出优先触发 compaction；估算仅为安全网。
- [ ] 锚定增量摘要：previous summary 存在时注入 `<previous-summary>` 与 update 语义。
- [ ] `TurnAwareContextPruner` 有真实调用方（删除硬编码 false）。
- [ ] 被清除旧工具输出标记 `[Old tool result content cleared]`；保护清单（write/edit/run_tests/question/permission/错误结果/最近 2 turn）生效。
- [ ] prune 后投影通过 `AgentProviderProtocolValidator`。
- [ ] 等量摘要被接受（`<=` 判定）。
- [ ] 压缩历史序列化工具输出截 2,000 字符。
- [ ] shadow compare 记录在迭代文档（旧路径 vs 新路径差异）。

## Phase 4：指令就近附着 + 截断

- [ ] ReadFileTool 向上查找 `Labex.md / AGENTS.md / CLAUDE.md`，`<system-reminder>` 附着，每条 assistant message 一次。
- [ ] `ToolOutputTruncator` 超限写 artifact、返回预览+路径+委托提示。
- [ ] `AgentContextManager` legacy 裁剪路径删除（grep 证据）。

## Phase 5：Subagent

- [ ] `subagent-isolation.md` 完成并评审（本计划不实施代码）。

## 收尾

- [ ] `opencode-alignment-status.md` 更新（无过时旧路径描述）。
- [ ] 后端全量 `mvn test` 通过（输出留存）。
- [ ] 前端 `npm run build` 通过（输出留存）。
- [ ] live smoke：一次真实短任务，记录首请求 input tokens、缓存 read/write、CONTEXT_STATUS 与 TOKEN_USAGE 一致性、PID/URL/cursor 证据，写入迭代文档。

## 已知未覆盖（诚实声明）

- Anthropic 显式 `cache_control` 断点：本计划只写策略文档与测试占位，不接入（当前 provider 为 OpenAI-compatible）。
- Linux Web / Docker / 浏览器验收：不在本计划范围，沿用 upstream 计划的 Linux Web Ready 门槛。
- 命中率与官方控制台对账：只保证口径一致与字段完整；账号级流量差异（其它客户端）不在本系统统计范围内。
