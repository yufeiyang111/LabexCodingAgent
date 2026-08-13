# LabexAgent 上下文构建 / Token 账本 / 提示缓存对齐任务计划（tasks.md）

> **For agentic workers:** 本文件必须按任务顺序执行。每个任务先写失败回归测试，再做最小实现，再跑 focused verification。实现对应模块前必须先阅读 `spec.md` 第 6 节列出的 `D:\opencode\opencode-dev` 参考文件，并在迭代文档中记录复刻点与 MIT notice 情况。

**Goal:** 把 LabexAgent 的上下文从"预计算塞满"改为"按需懒加载"，token 与缓存度量收敛到"provider 权威 + 单一估算器"，压缩/裁剪对齐 opencode 的 turn 语义与 prune 机制。

**Architecture:** durable transcript（`AgentRunMessage` / `AgentRunPart`）继续是唯一事实源；provider usage 是唯一权威账本；`AgentRequestTokenEstimator` 是唯一估算器；缓存命中靠前缀稳定纪律 + `prompt_cache_key` 路由，OpenAI 族不发显式断点。

**Tech Stack:** Spring Boot 3 / Java 17 / Maven / MyBatis-Plus / MySQL / Vue 3 / Vite / SSE。

**Document set:**
- 规格：`D:\LabexAgent\docs\superpowers\plans\2026-08-14-opencode-context-token-cache-alignment\spec.md`
- 任务：本文件
- 清单：`D:\LabexAgent\docs\superpowers\plans\2026-08-14-opencode-context-token-cache-alignment\checklist.md`

---

## 执行纪律与基线

### T0.1 固定当前工作树基线

**Status: pending.**

**Files:**
- Read: `D:\LabexAgent\AGENTS.md`
- Create: `D:\LabexAgent\docs\iterations\YYYY-MM-DD-iteration-<n>-context-token-cache-alignment.md`

- [ ] 记录 branch、HEAD、status、已跟踪/未跟踪改动；不清理已有工作。
- [ ] 为每阶段改动记录基线测试命令输出。
- [ ] 迭代文档中记录本计划三个验证面：token 账本、缓存遥测、上下文大小。

**Verification:**

```powershell
Set-Location D:\LabexAgent
git branch --show-current
git rev-parse HEAD
git status --short
git diff --stat
```

### T0.2 建立 token/缓存 fixture

**Status: pending.**

**Files:**
- Create: `D:\LabexAgent\backend\src\test\resources\fixtures\usage\deepseek-usage-hit.json`
- Create: `D:\LabexAgent\backend\src\test\resources\fixtures\usage\deepseek-usage-miss.json`
- Create: `D:\LabexAgent\backend\src\test\resources\fixtures\usage\openai-usage-cached.json`
- Create: `D:\LabexAgent\backend\src\test\resources\fixtures\usage\anthropic-usage-cache.json`

- [ ] DeepSeek hit：`prompt_tokens=1000, prompt_cache_hit_tokens=550, prompt_cache_miss_tokens=450`（真实官方字段，来自 api-docs.deepseek.com 口径）。
- [ ] DeepSeek miss：`prompt_cache_hit_tokens=0, prompt_cache_miss_tokens=800`。
- [ ] OpenAI：`prompt_tokens=900, prompt_tokens_details.cached_tokens=400`。
- [ ] Anthropic：`input_tokens=700, cache_read_input_tokens=300, cache_creation_input_tokens=400`。

**Verification:** 文件存在且字段名与 fixture 说明一致。

---

## Phase 0：统一账本（最高优先级，后续所有数字验证依赖它）

### T0.3 补全 extractUsage 的 DeepSeek/官方字段

**Status: pending.**

**Reference first:**
- `D:\opencode\opencode-dev\packages\opencode\src\session\session.ts`（getUsage, 384-453）
- `D:\opencode\opencode-dev\packages\llm\src\protocols\openai-chat.ts`（usage 提取, 383-397）

**Files:**
- Modify: `D:\LabexAgent\backend\src\main\java\com\labex\labexagent\llm\OpenAiCompatibleProvider.java`
- Test: `D:\LabexAgent\backend\src\test\java\com\labex\labexagent\llm\OpenAiCompatibleProviderContractTest.java`（或新建 `UsageExtractionContractTest`）

- [ ] 红测试：DeepSeek fixture 提取出 `cached_tokens=550, cache_write_tokens=450`（miss 即 write）。
- [ ] 提取顺序固定：`prompt_cache_hit_tokens` / `prompt_cache_miss_tokens` 加入 `firstPositive` 链，优先级在 `prompt_tokens_details.cached_tokens` 之后、`cache_read_input_tokens` 之前。
- [ ] `prompt_tokens` 为 0 时的回退：`input_tokens + cached + cacheWrite` 保持（Anthropic 语义）。
- [ ] `cache_usage_reported` 判定加入 `prompt_cache_hit_tokens` / `prompt_cache_miss_tokens` 两个字段。
- [ ] 提取结果新增 `cache_hit_tokens`、`cache_miss_tokens` 键，与既有 `cached_tokens` / `cache_write_tokens` 同时输出（渐进兼容）。

**Run:**

```powershell
Set-Location D:\LabexAgent\backend
mvn -q '-Dtest=OpenAiCompatibleProviderContractTest,UsageExtractionContractTest' test
```

Expected: 红 → 绿。

### T0.4 收敛 token 估算为单一实现

**Status: pending.**

**Files:**
- Modify: `D:\LabexAgent\backend\src\main\java\com\labex\labexagent\runtime\ContextUsageEstimator.java`（内部改调 `AgentRequestTokenEstimator`）
- Modify: `D:\LabexAgent\backend\src\main\java\com\labex\labexagent\runtime\AgentLoopEngine.java`（删除 1066-1096 的 `/4` 兜底、3465-3478 的 `/3` 辅助；兜底统一走 `AgentRequestTokenEstimator`）
- Modify: `D:\LabexAgent\backend\src\main\java\com\labex\labexagent\service\AgentContextOrchestrator.java`（stats 改同一来源）
- Test: `D:\LabexAgent\backend\src\test\java\com\labex\labexagent\runtime\ContextUsageEstimatorTest.java`（或新建 `TokenAccountingConvergenceTest`）

- [ ] 红测试：对同一组 (systemPrompt, tools, messages)，`ContextUsageEstimator` 输出 == `AgentRequestTokenEstimator.estimate(...).inputTokens()`（分类值之和 == 总量）。
- [ ] 红测试：provider 无 usage 时 TOKEN_USAGE 事件 `estimated=true` 且数值等于唯一估算器输出。
- [ ] `grep -rn "/ 3\|/ 4\|length()/3\|length()/4"` 在 `labexagent` 包内除 `AgentRequestTokenEstimator` 外无 token 估算公式残留（`/ 3` 用于 UTF-8 字节在估算器内部是允许的）。
- [ ] admission 门禁与 CONTEXT_STATUS 使用同一估算输出（集成断言）。

**Run:**

```powershell
Set-Location D:\LabexAgent\backend
mvn -q '-Dtest=ContextUsageEstimatorTest,TokenAccountingConvergenceTest,AgentLoopEngineStreamingContractTest' test
```

### T0.5 命中率口径改造（token 账本 + per-request 分布）

**Status: pending.**

**Reference first:**
- `D:\opencode\opencode-dev\packages\app\src\components\session\session-context-metrics.ts`

**Files:**
- Modify: `D:\LabexAgent\backend\src\main\java\com\labex\labexagent\service\TokenTracker.java`（落库新增 `cache_hit_tokens / cache_miss_tokens`）
- Modify: `D:\LabexAgent\backend\src\main\java\com\labex\labexagent\runtime\AgentLoopEngine.java`（`tokenUsagePayload` 新增字段：`cacheHitTokens/cacheMissTokens/inputTokensNonCached`）
- Modify: `D:\LabexAgent\backend\src\main\resources\sql\schema.sql` + `AdditiveSchemaMigrator`（`t_agent_token_usage` 加两列，additive）
- Modify: `D:\LabexAgent\frontend\src\composables\cacheTelemetryStatus.js`（账本展示 + 分布；保留旧百分比但标注口径）
- Modify: `D:\LabexAgent\frontend\src\views\CloudWorkspace.vue`（缓存卡片改为 token 账本）
- Test: 后端 `TokenTrackerTest`；前端 `cacheTelemetryStatus` 单测（现有 test 文件扩展）

- [ ] 红测试：`hitRate` 计算与官方口径一致：`cached / (cached + miss)`；不再使用不含 miss 的分母。
- [ ] 红测试：`write_only` 状态在 `cache_miss_tokens > 0 && cached == 0` 时成立。
- [ ] UI 卡片显示：cache read X tokens / cache write Y tokens / 非缓存 input Z tokens，百分比标注"会话级统计"。
- [ ] `cacheTelemetryStatus.js` 累加 `cacheHitTokens`（用 miss 字段校正旧 `cacheWriteTokens` 为 0 的历史 bug）。

**Run:**

```powershell
Set-Location D:\LabexAgent\backend
mvn -q '-Dtest=TokenTrackerTest' test
Set-Location D:\LabexAgent\frontend
npm run build
```

### T0.6 移除写入层截断（展示层截断保留）

**Status: pending.**

**Files:**
- Modify: `D:\LabexAgent\backend\src\main\java\com\labex\labexagent\run\AgentRunTranscriptService.java`（589 / 688 截断改为不截断或移至投影展示层）
- Test: 回归 `AgentRunTranscriptServiceTest` 或 transcript projection 测试

- [ ] 红测试：超长 content / output_text 落库后与真实请求内容一致。
- [ ] 展示层（前端历史、CONTEXT_STATS preview）截断逻辑不受影响。

**Run:**

```powershell
Set-Location D:\LabexAgent\backend
mvn -q '-Dtest=AgentRunTranscriptServiceTest,AgentTranscriptProjectionServiceTest' test
```

---

## Phase 1：上下文瘦身（懒加载）

### T1.1 首条消息拆解（红测试先锁定目标形态）

**Status: pending.**

**Reference first:**
- `D:\opencode\opencode-dev\packages\opencode\src\session\prompt.ts`（runLoop 1327-1344）
- `D:\opencode\opencode-dev\packages\opencode\src\session\system.ts`（environment 55-92）

**Files:**
- Modify: `D:\LabexAgent\backend\src\main\java\com\labex\labexagent\runtime\AgentLoopEngine.java`（`buildContextMessage` 与 584-594 / 860-873 调用点）
- Modify: `D:\LabexAgent\backend\src\main\java\com\labex\labexagent\service\AgentContextOrchestrator.java`（`buildInitialBundle` 不再作为 provider 输入；保留为独立查询服务）
- Test: `AgentLoopEngineStreamingContractTest` 或新建 `InitialContextShapeTest`

- [ ] 红测试：首条 durable user message 只含 mode policy + language policy + rules(≤10k) + 精简 memory(≤2k)，断言不含 `<adaptive_project_context>`、`<repo_map>`、`<workspace_diagnostics>` 文本。
- [ ] 红测试：首条请求估算 input ≤ 8,000 token（fixture 项目，唯一估算器计算）。
- [ ] `buildInitialBundle` 调用点只剩 preview/诊断 API，provider 路径不引用。

**Run:**

```powershell
Set-Location D:\LabexAgent\backend
mvn -q '-Dtest=InitialContextShapeTest,AgentLoopEngineStreamingContractTest' test
```

### T1.2 工具按需拉取接线

**Status: pending.**

**Files:**
- Modify: `D:\LabexAgent\backend\src\main\java\com\labex\labexagent\tool\impl\RepoMapTool.java`（确认可按需触发，无需预注入）
- Modify: `D:\LabexAgent\backend\src\main\java\com\labex\labexagent\tool\impl\RetrieveContextTool.java`
- Modify: `D:\LabexAgent\backend\src\main\java\com\labex\labexagent\tool\impl\DiagnosticsTool.java`
- Modify: `D:\LabexAgent\backend\src\main\java\com\labex\labexagent\prompt\LabexSystemPrompt.java`（workflow 节提示模型"项目结构用 repo_map 工具获取"，不宣称已注入）

- [ ] 红测试：RepoMapTool / RetrieveContextTool / DiagnosticsTool 在无预注入上下文的场景返回对应内容（fake project）。
- [ ] 系统提示词不宣称已包含 repo map / 诊断。

**Run:**

```powershell
Set-Location D:\LabexAgent\backend
mvn -q '-Dtest=RepoMapToolTest,RetrieveContextToolTest,DiagnosticsToolTest' test
```

### T1.3 删除 system 中重复的 tools 文本清单

**Status: pending.**

**Reference first:**
- `D:\opencode\opencode-dev\packages\opencode\src\session\tools.ts`（74-115：工具只进 body schema）

**Files:**
- Modify: `D:\LabexAgent\backend\src\main\java\com\labex\labexagent\prompt\LabexSystemPrompt.java`（toolPolicy 只保留使用指南，删除 `- name: description` 清单）
- Test: `LabexSystemPromptTest`（如存在）或新建

- [ ] 红测试：system prompt 不含工具名清单；body tools JSON 仍完整。
- [ ] 缓存键生成（`PromptCacheKeyFactory`）输入中 systemPrompt 不再包含工具清单（自动成立，回归锁定）。

**Run:**

```powershell
Set-Location D:\LabexAgent\backend
mvn -q '-Dtest=LabexSystemPromptTest' test
```

---

## Phase 2：前缀稳定化

### T2.1 静态工具按名排序 + schema 字段序锁定

**Status: pending.**

**Reference first:**
- `D:\opencode\opencode-dev\packages\opencode\src\session\llm\request.ts`（174：`toSorted(localeCompare)`）

**Files:**
- Modify: `D:\LabexAgent\backend\src\main\java\com\labex\labexagent\tool\ToolSelectionPolicy.java`（静态定义也按 name 排序）
- Modify: `D:\LabexAgent\backend\src\main\java\com\labex\labexagent\runtime\AgentLoopEngine.java`（`reduceToolSchemaForOverflow` 输出排序）
- Test: `ToolSelectionPolicyTest` 或新建 `ToolSerializationStabilityTest`

- [ ] 红测试：同一工具集两次 buildToolsList 输出字节一致；乱序输入输出一致。
- [ ] 红测试：溢出降级后的 12 工具子集是稳定前缀的子集且有序。

**Run:**

```powershell
Set-Location D:\LabexAgent\backend
mvn -q '-Dtest=ToolSelectionPolicyTest,ToolSerializationStabilityTest' test
```

### T2.2 前缀稳定性回归锁定

**Status: pending.**

**Files:**
- Test: 新建 `D:\LabexAgent\backend\src\test\java\com\labex\labexagent\llm\PromptPrefixStabilityTest.java`

- [ ] 同一 (project, mode, language, modelConfig) 连续两次 run，system prompt 字节一致（含 `<environment>` 无日期时间断言）。
- [ ] 两次 run 间 tools JSON 字节一致。
- [ ] 不同 conversation 的同一 project，system+tools 前缀一致（跨会话命中前提）。

**Run:**

```powershell
Set-Location D:\LabexAgent\backend
mvn -q -Dtest=PromptPrefixStabilityTest test
```

---

## Phase 3：压缩/裁剪对齐

### T3.1 溢出优先触发（provider 上报 > 估算安全网）

**Status: pending.**

**Reference first:**
- `D:\opencode\opencode-dev\packages\opencode\src\session\prompt.ts`（1214-1221：`isOverflow` 触发）
- `D:\opencode\opencode-dev\packages\opencode\src\session\llm\overflow.ts`（8-34）

**Files:**
- Modify: `D:\LabexAgent\backend\src\main\java\com\labex\labexagent\runtime\AgentLoopEngine.java`（1597-1681 的溢出恢复已存在；改为 provider usage 判定优先，估算触发保留为安全网）
- Modify: `D:\LabexAgent\backend\src\main\java\com\labex\labexagent\runtime\ContextWindowSupervisor.java`
- Test: `ContextOverflowRecoveryTest`（或已有等价测试扩展）

- [ ] 红测试：provider 返回 usage 超 usable（`input+output+cache` 计入，对齐 overflow.ts）时触发 compaction，即使估算低于软限制。
- [ ] 红测试：估算预判路径保留，仅当 provider usage 不可用时生效。

**Run:**

```powershell
Set-Location D:\LabexAgent\backend
mvn -q '-Dtest=ContextOverflowRecoveryTest' test
```

### T3.2 锚定增量摘要

**Status: pending.**

**Reference first:**
- `D:\opencode\opencode-dev\packages\core\src\session\compaction.ts`（buildPrompt, 166-173）

**Files:**
- Modify: `D:\LabexAgent\backend\src\main\java\com\labex\labexagent\runtime\CompactionAgent.java`
- Modify: `D:\LabexAgent\backend\src\main\java\com\labex\labexagent\context\AgentCompactionService.java`
- Test: `CompactionAgentTest`

- [ ] 红测试：存在 previous summary 时 prompt 含 "update the anchored summary" 语义与 `<previous-summary>` 注入。
- [ ] 红测试：summary 输出仍满足严格 JSON schema（{summary, facts[], nextActions[], openRisks[], files[], verification[]}）。

**Run:**

```powershell
Set-Location D:\LabexAgent\backend
mvn -q '-Dtest=CompactionAgentTest,AgentCompactionServiceTest' test
```

### T3.3 修通 prune（删除硬编码 false + 接上 TurnAwareContextPruner）

**Status: pending.**

**Reference first:**
- `D:\opencode\opencode-dev\packages\opencode\src\session\compaction.ts`（prune, 253-297）

**Files:**
- Modify: `D:\LabexAgent\backend\src\main\java\com\labex\labexagent\runtime\AgentLoopEngine.java`（3690 附近 `hasPrunableToolResult=false` 改为真实探测；`evaluateContextAdmission` 3569 同样修正）
- Modify: `D:\LabexAgent\backend\src\main\java\com\labex\labexagent\runtime\TurnAwareContextPruner.java`（清除文本对齐 `[Old tool result content cleared]`，保护清单对齐 opencode）
- Test: `TurnAwareContextPrunerTest`（已存在则扩展；不存在则新建）

- [ ] 红测试：超出预算的旧工具结果被替换为 `[Old tool result content cleared]`，write/edit/run_tests/question/permission 结果与最近 2 个 turn 被保护。
- [ ] 红测试：prune 后投影仍构成合法 provider 消息（协议 validator 通过）。
- [ ] shadow compare：旧路径（不 prune）与新路径（prune）在同一输入下的差异记录在迭代文档。

**Run:**

```powershell
Set-Location D:\LabexAgent\backend
mvn -q '-Dtest=TurnAwareContextPrunerTest,AgentProviderProtocolValidatorTest' test
```

### T3.4 等量摘要接受 + 压缩序列化 2000 字符截断

**Status: pending.**

**Files:**
- Modify: `D:\LabexAgent\backend\src\main\java\com\labex\labexagent\runtime\AgentLoopEngine.java`（3450-3452、3763-3777 的 `<` 改为 `<=`）
- Modify: `D:\LabexAgent\backend\src\main\java\com\labex\labexagent\runtime\CompactionAgent.java`（历史序列化时工具输出截 2000 字符）
- Test: 扩展 `AgentCompactionServiceTest`

- [ ] 红测试：等量摘要被接受为成功压缩。
- [ ] 红测试：压缩输入历史中工具输出 > 2000 字符被截断且带标记。

**Run:**

```powershell
Set-Location D:\LabexAgent\backend
mvn -q '-Dtest=AgentCompactionServiceTest' test
```

---

## Phase 4：指令就近附着 + 工具输出截断

### T4.1 ReadFileTool 就近附着 AGENTS.md/Labex.md

**Status: pending.**

**Reference first:**
- `D:\opencode\opencode-dev\packages\opencode\src\session\instruction.ts`（179-221）
- `D:\opencode\opencode-dev\packages\opencode\src\tool\read.ts`（300, 353-357）

**Files:**
- Modify: `D:\LabexAgent\backend\src\main\java\com\labex\labexagent\tool\impl\ReadFileTool.java`
- 新增（如无）：`D:\LabexAgent\backend\src\main\java\com\labex\labexagent\instruction\InstructionAttachService.java`（向上查找 + 每条 assistant message 一次）
- Test: `ReadFileToolTest` / `InstructionAttachServiceTest`

- [ ] 红测试：读 `src/a/b.js` 时向上找到项目根 `Labex.md`，输出尾部出现 `<system-reminder>`。
- [ ] 红测试：同一 assistant turn 第二次读文件不再重复附着；新 turn 重新附着。

**Run:**

```powershell
Set-Location D:\LabexAgent\backend
mvn -q '-Dtest=ReadFileToolTest,InstructionAttachServiceTest' test
```

### T4.2 工具输出截断对齐 truncate.ts（行/字节上限 + artifact + 委托提示）

**Status: pending.**

**Reference first:**
- `D:\opencode\opencode-dev\packages\opencode\src\tool\truncate.ts`

**Files:**
- 新增：`D:\LabexAgent\backend\src\main\java\com\labex\labexagent\tool\ToolOutputTruncator.java`（默认 2000 行 / 50KB，溢出写 artifact，返回预览 + 委托提示）
- Modify: `D:\LabexAgent\backend\src\main\java\com\labex\labexagent\runtime\AgentContextManager.java`（`pruneToolResult` 固定字符预算替换为 truncator；删除 legacy 裁剪路径 30/71/83-94/168-181）
- Test: `ToolOutputTruncatorTest`

- [ ] 红测试：超限输出写 artifact 文件，模型可见内容 ≤ 上限且含 artifact 路径提示。
- [ ] 红测试：不足上限输出原样返回。

**Run:**

```powershell
Set-Location D:\LabexAgent\backend
mvn -q '-Dtest=ToolOutputTruncatorTest,AgentContextManagerTest' test
```

---

## Phase 5：Subagent 子会话隔离（任务分解，实施放独立计划）

### T5.1 子会话隔离设计（仅文档）

**Status: pending.**

**Files:**
- Create: `D:\LabexAgent\docs\superpowers\plans\2026-08-14-opencode-context-token-cache-alignment\subagent-isolation.md`

- [ ] 定义 task 工具 → 真实子会话（AgentTask + conversation 绑定）、派生权限、禁止递归 task、父会话只收 XML 摘要、`task_id` 续跑。
- [ ] 明确与 opencode `tool/task.ts` 的差异适配点（多用户 Web 控制面、durable transcript 投影）。

**Verification:** 文档评审通过；不在本计划实施代码。

---

## 收尾

### T9.1 更新对齐状态文档

**Files:**
- Modify: `D:\LabexAgent\docs\coding-agent-industrialization\opencode-alignment-status.md`

- [ ] 更新 token 账本、缓存遥测、上下文懒加载、压缩/裁剪的当前状态。
- [ ] 删除已过时的旧权威路径描述。

### T9.2 全量验证

```powershell
Set-Location D:\LabexAgent\backend
mvn test
Set-Location D:\LabexAgent\frontend
npm run build
```

- [ ] 后端全量单测通过；前端生产构建通过。
- [ ] live smoke（真实模型一次短任务）：记录首请求 input tokens、缓存 read/write、CONTEXT_STATUS 与 TOKEN_USAGE 数字一致性、PID/URL 证据，写入迭代文档。
