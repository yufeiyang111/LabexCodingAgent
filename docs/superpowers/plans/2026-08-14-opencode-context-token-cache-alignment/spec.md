# LabexAgent 上下文构建 / Token 账本 / 提示缓存对齐规格（spec.md）

**状态：** 设计完成，未开始实施
**制定日期：** 2026-08-14
**适用仓库：** `D:\LabexAgent`
**本地 OpenCode 参考快照：** `D:\opencode\opencode-dev`（`packages/opencode/package.json` 版本 `1.17.4`，MIT License）
**关联任务：** [tasks.md](./tasks.md)
**验收清单：** [checklist.md](./checklist.md)
**上游约束：** [Agent Runtime State Convergence Plan](../2026-07-29-agent-runtime-state-convergence.md)、[OpenCode-First 可用性改造规格](../2026-08-12-opencode-first-agent-usability/spec.md)

---

## 1. 背景与问题定义

OpenCode-first Shell 基线（Phase 1，2026-08-12 已完成）打通了执行层。但 Coding Agent 仍然"连用都难用"，根因不在工具执行，而在**上下文哲学与度量体系**：

1. **首条消息塞满巨型预计算上下文**：`buildContextMessage` 把 `<session_context>`（60,000 字符上限，含 adaptive 项目索引 / repo map / workspace memory / LSP 诊断）、skills（16,000）、MCP context（12,000）、project rules（10,000）全部焊死进第一个 durable user message。约 3 万 token 的静态内容按"第一条提问"做相关性排序，后续提问无法适应，只能靠压缩清掉。openCode 的同类内容全部按需懒加载。
2. **4+1 套互不一致的 token 估算**：`length()/3`（ContextUsageEstimator，UI CONTEXT_STATUS + 硬门禁输入）、`utf8bytes/3 + 信封`（AgentRequestTokenEstimator，压缩触发）、`length()/4`（provider 无 usage 兜底）、`length()/3`（orchestrator stats / LoopEngine 辅助）。同一请求在 UI、门禁、压缩触发、兜底显示四个不同数字。
3. **缓存命中率口径失真**：本地 UI 显示 95% 命中率，而 DeepSeek 官方控制台对同一账号显示约 55%。根因（详见第 4 节）是"会话级 token 加权聚合 + 任何 hit>0 都标 HIT + 提取器漏抓 DeepSeek 官方字段"，不是缓存真的做得好。
4. **压缩/裁剪半套死代码**：`TurnAwareContextPruner` 零调用、`ContextWindowSupervisor` 的 PRUNE 分支被硬编码 `hasPrunableToolResult=false` 封死；模型摘要不接受等量替代（`<` 判断）；写库截断 12k/8k 使持久化 transcript 与真实请求不一致。

本规格目标：

> **把上下文从"预计算塞满"改为"按需懒加载"，把 token 与缓存度量收敛到"provider 权威 + 单一估算器"，把压缩/裁剪对齐 opencode 的 turn 语义与 prune 机制，并用可观测的账本验证每一步。**

---

## 2. 已确认的架构决策

### 2.1 度量二分（对齐 opencode `session/session.ts getUsage` + `core/util/token.ts`）

- **权威账本 = provider 上报 usage**：UI、落库、成本、溢出判定一律以 provider usage 为准（含 `prompt_tokens_details.cached_tokens`、DeepSeek `prompt_cache_hit_tokens / prompt_cache_miss_tokens`、Anthropic `cache_read_input_tokens / cache_creation_input_tokens`）。
- **估算只用于预算决策**：唯一估算器是 `AgentRequestTokenEstimator`（UTF-8 字节/3 + 每消息 4 + 请求 12 信封）。只允许用于：压缩触发预判、admission 门禁、压缩前后对比。绝不用于对外展示的 usage 数字。
- **删除其余所有估算公式**：`ContextUsageEstimator` 的分类展示改为调用唯一估算器；`AgentLoopEngine` 的 `/4` 兜底与 `/3` 辅助删除；`AgentContextOrchestrator` 的 stats 改为同一来源。

### 2.2 缓存策略分层（对齐 opencode `provider/transform.ts applyCaching` + `packages/llm/src/cache-policy.ts`）

- **OpenAI-compatible / DeepSeek：不发显式缓存标记**（协议层 no-op），靠服务端隐式前缀缓存。命中率由**前缀稳定纪律**决定（第 5 节）。
- **Anthropic 族（未来接入时）：** 显式 `cache_control: {type: ephemeral}`，断点按 opencode 规则：tools 末尾 + system 末尾 + 最新 user 消息，4 断点上限，超限按 tools→system→messages 优先级丢弃。
- **`prompt_cache_key` 保持现状**（SHA-256 of system+tools 路由键），语义是"把同一静态前缀路由到同一分片"，不做内容寻址缓存。
- **不做**：把 sessionID、时间戳、日期写入 system prompt（任何 per-request 变化内容只允许 append 到消息尾部）。

### 2.3 上下文懒加载（对齐 opencode `session/prompt.ts runLoop` + `tool/read.ts` 就近附着）

- 首条 durable user message 只保留：mode policy + 可见语言 policy + project rules（≤10k）+ 精简 workspace memory（≤2k）+ 原始用户消息。
- 删除 `<session_context>` 巨型 bundle 中的 adaptive 索引 / repo map / LSP 诊断 / conversation memory，改为工具按需拉取（RepoMapTool、RetrieveContextTool、DiagnosticsTool 均已存在）。
- `AgentContextOrchestrator.buildInitialBundle` 保留为**独立查询服务**（供 preview/诊断页使用），不再作为 provider 请求的输入。
- ReadFileTool 读文件时向上查找 `Labex.md / AGENTS.md / CLAUDE.md`，以 `<system-reminder>` 附着到工具输出尾部，每条 assistant message 只附着一次（对齐 opencode `session/instruction.ts Instruction.resolve`）。

### 2.4 压缩/裁剪（对齐 opencode `session/compaction.ts`）

- 触发顺序：provider 上报 overflow（reactive，最高优先）→ 估算预判（安全网，用唯一估算器）。
- 摘要改为**锚定增量更新**：存在 previous summary 时使用"update the anchored summary"提示词，保留仍为真的事实、合并新事实。
- **修通 prune**：删除 `hasPrunableToolResult=false` 硬编码；`TurnAwareContextPruner` 接上真实调用；被清空的旧工具输出标记 `[Old tool result content cleared]`，保护 write/edit/patch/run_tests/question/permission 及含错误的结果。
- 压缩序列化时工具输出截 2,000 字符、媒体降级为占位文本。
- 接受等量摘要：`tokensAfter <= tokensBefore` 即接受（修复 `<` 死循环隐患）。

### 2.5 持久化完整（对齐 opencode Part 全量 JSON 落库）

- 删除 `AgentRunTranscriptService` 写入时的 12,000（message content）/ 8,000（part output_text）截断；展示层截断，存储层全量。为兼容存量超长行，通过 `AdditiveSchemaMigrator` 把列改为 LONGTEXT（已是 LONGTEXT 的仅确认）。

### 2.6 不变量（继承上游两个计划，不重复引入第二事实源）

- 不新增第二套 transcript / task status / 内存审批权威。
- 不把 SSE 连接当作任务生命周期；不绕过 `AgentRunLifecycleService`。
- 上下文可重建性保持：任何派生视图（预览、stats、热力图）必须能从 durable transcript + provider usage 重建。

---

## 3. 产品目标与非目标

### 3.1 目标

#### G1. 单一 token 账本

- 同一请求：UI CONTEXT_STATUS、admission 门禁、压缩触发、TOKEN_USAGE 落库使用**同一套数字**（provider usage 优先，估算兜底且标注 `estimated=true`）。
- 删除全部 `/3`、`/4` 重复公式，仓库中只允许存在 `AgentRequestTokenEstimator` 一个估算实现。

#### G2. 命中率口径可对账

- UI 不再显示单一误导性百分比；改为 token 账本（cache read / cache write / 非缓存 input）+ per-request 命中分布。
- 与 DeepSeek 官方控制台口径可对账：官方 = `prompt_cache_hit_tokens / prompt_tokens`（全量流量）；本地 = 会话级 + 全库统计，明确标注统计范围差异。
- `extractUsage` 完整抓取 DeepSeek 官方字段（`prompt_cache_hit_tokens`、`prompt_cache_miss_tokens`），miss 计入 cache write。

#### G3. 冷启动上下文瘦身

- 首条 provider 请求的 input token 从 ~30k 量级降到 ~5k 量级（fixture 断言）。
- 项目文件、repo map、LSP 诊断、conversation memory 通过工具按需获取，不预注入。

#### G4. 前缀稳定

- 同一 project + mode + language + 模型配置下，system prompt 与 tools JSON 字节稳定（回归测试锁定）。
- 静态工具定义按 name 排序，不依赖 Spring bean 注册顺序。

#### G5. 压缩/裁剪真正可用

- prune 路径有真实调用方与回归测试；被清除输出有明确标记。
- 压缩摘要支持锚定增量更新；等量摘要不再导致恢复死循环。

### 3.2 非目标

- 不一次性重写 `AgentLoopEngine`（只在指定任务点做最小修改）。
- 不做内容寻址缓存 / Anthropic 协议接入（仅预留断点策略文档与测试占位）。
- 不做 subagent 子会话隔离的完整实现（Phase 5 只出任务分解，实施放后续计划）。
- 不修改 `.env`、密钥、数据库 reset；所有 schema 变更走 additive 迁移。

---

## 4. 缓存命中率 95% vs 55% 根因（已核实）

DeepSeek 官方文档（`api-docs.deepseek.com` Chat Completions usage）明确：

> `prompt_tokens` = `prompt_cache_hit_tokens + prompt_cache_miss_tokens`

即官方命中率 = `hit / (hit + miss)`。本地 `CacheTelemetry.hitRate` 公式同为 `cached / prompt_tokens`，但存在四个偏差源：

| # | 偏差源 | 位置 | 影响 |
|---|---|---|---|
| 1 | **统计范围**：本地是"当前会话 token 加权聚合"，官方是"账号全量流量（含冷启动首请求、其它客户端）" | `cacheTelemetryStatus.js:79-80` | 长会话静态前缀命中后聚合值冲向 90%+，官方被冷启动拖到 ~55% |
| 2 | **状态粗化**：任何一次 `cached_tokens > 0` 即标 HIT | `CacheTelemetry.java:12` | 5% 命中与 100% 命中无区别，单一聚合百分比掩盖 per-request 分布 |
| 3 | **字段漏抓**：`extractUsage` 不认 `prompt_cache_hit_tokens / prompt_cache_miss_tokens` | `OpenAiCompatibleProvider.java:471-486` | DeepSeek 官方字段不命中时 `cached=0`；cache write 永远为 0（miss 即 write），`write_only` 状态从不正确 |
| 4 | **无 per-request 明细** | `TokenTracker` / `t_agent_token_usage` | 无法验证官方数字，也无法做分布统计 |

opencode 的处理：不显示百分比，只显示 `cache.read / cache.write` token 账本（`packages/app/.../session-context-metrics.ts`），判断缓存质量靠 token 差量而非比率。

---

## 5. 前缀稳定纪律（提高真实命中率的核心）

对齐 opencode `session/llm/request.ts:56-112` + `cache-policy.ts`：

1. system 恒为**一条**消息、恒在 messages[0]；内容只依赖：模型配置、mode、可见语言、权限 profile、项目名/结构摘要（结构摘要不超 12k 字符）。
2. **禁止**进入 system：日期、时间戳、session/task/request ID、workspace memory、对话摘要。
3. 静态工具定义按 `name` 排序后再序列化；工具 schema 字段顺序固定（LinkedHashMap）。
4. 新内容（用户消息、工具结果、derived projection）只 append 到历史尾部，绝不插入头部。
5. 压缩重排（summary + tail）是确定性变换，且 summary 注入位置在 system 之后，不破坏 system+tools 前缀。
6. `reduceToolSchemaForOverflow` 的裁剪同样按 name 排序输出，保证溢出降级后前缀仍是稳定前缀的子集。
7. 系统提示词删除 `<tools>` 文本清单（`LabexSystemPrompt.toolPolicy` 中 `- name: description` 列表），工具说明只保留在 body `tools` JSON；保留工具使用指南文字。理由：opencode 不在 system 里重复工具列表，重复注入使前缀无谓膨胀且两份描述可能漂移。

---

## 6. OpenCode 参考复刻规则（强制）

实现下表中模块前必须先阅读对应 OpenCode 源码，并在迭代文档记录：参考文件、复刻不变量、LabexAgent 适配、是否复制实质代码（复制则需 MIT notice）。

| LabexAgent 模块 | 必须参考的 OpenCode 源码 | 复刻重点 |
|---|---|---|
| 缓存遥测归一化 | `packages/opencode/src/session/session.ts`（getUsage, 384-453） | cache read/write 拆列、input 减缓存、多 provider 元数据归一 |
| 缓存断点策略 | `packages/opencode/src/provider/transform.ts`（applyCaching, 323-372）、`packages/llm/src/cache-policy.ts` | Anthropic 族显式断点；OpenAI 族隐式前缀缓存 + 断点 no-op |
| 请求组装 | `packages/opencode/src/session/llm/request.ts`（56-112） | system 一条前置、工具按名排序 |
| 消息序列化 | `packages/opencode/src/session/message-v2.ts`（toModelMessagesEffect, 142-426） | 严格时间序、tool result 状态映射、prune 后占位文本 |
| 压缩触发与算法 | `packages/opencode/src/session/compaction.ts`（299-552）、`core/src/session/compaction.ts`（buildPrompt, 166-173） | 溢出优先触发、turn 尾选择、锚定摘要、prune 保护 |
| 指令就近附着 | `packages/opencode/src/session/instruction.ts`（Instruction.resolve, 179-221）、`tool/read.ts`（300, 353-357） | 向上查找、每 message 一次、`<system-reminder>` 容器 |
| 工具输出截断 | `packages/opencode/src/tool/truncate.ts` | 行/字节上限、溢出 artifact、委托子代理提示 |
| 溢出判定 | `packages/opencode/src/session/llm/overflow.ts`（8-34） | 用 model.limit + provider usage，不用字符估算 |

**License：** OpenCode 本地快照为 MIT。仅复刻设计在迭代文档注明；复制实质代码须新增 `THIRD_PARTY_NOTICES.md` 保留版权声明。

---

## 7. 目标执行形态

### 7.1 每次模型调用的最终消息线序（变更后）

```text
[system]                     ← 一条稳定 system（无 tools 文本清单、无动态内容）
[user: 初始上下文]            ← mode policy + language policy + rules(≤10k) + 精简 memory(≤2k)  [仅会话首条]
[user: 原始用户消息]
[assistant: content + tool_calls]
[tool: [Tool x result] ...]  ← 结构化结果；prune 后为 [Old tool result content cleared]
...
[user: <agent_runtime_projection>]  ← derived，恒 append 在尾部，不落库
[assistant: MAX_STEPS sentinel]     ← 仅最后一步
```

`tools` JSON 在 body 参数中，按 name 排序。

### 7.2 账本数据流（变更后）

```text
provider usage（唯一权威）
  → extractUsage 归一化（含 DeepSeek 官方字段）
  → TokenTracker 落库（per-request 明细：prompt/cached/cacheWrite/completion/status）
  → TOKEN_USAGE 事件（estimated=false）
  → 前端 token 账本 + per-request 分布

AgentRequestTokenEstimator（唯一估算）
  → admission 门禁（BLOCK 判定）
  → 压缩触发安全网
  → CONTEXT_STATUS 事件（estimated=true 标注）
```

---

## 8. 数据、配置与兼容性

### 8.1 新增/变更列（AdditiveSchemaMigrator）

- `t_agent_token_usage`：新增 `cache_hit_tokens INT`、`cache_miss_tokens INT`（与既有 `cached_tokens / cache_write_tokens` 并存：`cached_tokens` 保持兼容读取，新写入同时填两个字段源）。
- `t_agent_run_message.content` / `t_agent_run_part.output_text`：确认 LONGTEXT；如是 VARCHAR 则改 LONGTEXT（additive）。

### 8.2 兼容策略

- `ContextUsageEstimator.estimateCategories` 对外签名保持，内部改为调用唯一估算器；分类字段（systemPrompt/toolResults/...）保留供 UI 分组展示。
- TOKEN_USAGE 事件 payload 字段保持（`cachedTokens/cacheWriteTokens/cacheStatus/cacheHitRate/estimated`），新增 `cacheHitTokens/cacheMissTokens/inputTokensNonCached`，前端渐进使用新字段。
- 命中率百分比保留一个版本但标注"会话级统计，非官方口径"，同时展示 token 账本；下一迭代删除百分比。

### 8.3 迁移不变量（继承）

- 压缩/裁剪变更必须先 shadow compare（旧路径只读、新路径投影对比），切换读取后监控旧写入，最后删除旧路径。
- 每项完成提供：回归测试、命令结果、live 验证记录（PID/URL/cursor 证据）。
- 小步可回滚：每个任务独立 commit。

---

## 9. 验收标准（摘要，详见 checklist.md）

1. 仓库中仅存在一个 token 估算实现；同一请求 UI/门禁/压缩数字一致。
2. DeepSeek 官方字段被抓取；`write_only` 状态可用真实 miss token 触发；本地统计可分解到 per-request。
3. 首条请求 input ≤ ~5k token 量级（fixture 对比）。
4. system + tools 前缀字节稳定（跨两次 run 的回归测试）。
5. prune 有真实调用方；被清除输出有标记；压缩摘要锚定更新；等量摘要被接受。
6. 全量后端 focused tests + 前端 build 通过；live smoke 记录在迭代文档。

---

## 10. 实施顺序

固定顺序（依赖关系：账本先行，后续所有数字验证依赖它）：

1. **Phase 0**：统一账本（extractUsage 补字段 → 删多余公式 → 命中率口径 → per-request 明细落库）
2. **Phase 1**：上下文瘦身（首条消息拆解 → 工具按需拉取 → 删 system tools 文本清单）
3. **Phase 2**：前缀稳定化（工具排序 → 前缀稳定性回归锁定 → 溢出降级排序）
4. **Phase 3**：压缩/裁剪对齐（溢出优先触发 → 锚定摘要 → prune 修通 → 等量摘要接受）
5. **Phase 4**：指令就近附着 + 工具输出截断对齐 truncate.ts
6. **Phase 5**：subagent 子会话隔离（本计划仅任务分解，实施放独立计划）

详细任务见 [tasks.md](./tasks.md)。
