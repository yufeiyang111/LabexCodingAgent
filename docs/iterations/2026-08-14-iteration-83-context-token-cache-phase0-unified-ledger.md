# 第 83 轮：上下文/Token/缓存对齐 Phase 0 —— 统一账本（extractUsage + 单一估算器 + 命中率口径）

## 1. 本轮目标

按 [2026-08-14-opencode-context-token-cache-alignment](../superpowers/plans/2026-08-14-opencode-context-token-cache-alignment/spec.md) 的 Phase 0 切片执行：T0.1–T0.5（T0.6 写库截断留到下一轮）。核心是"度量二分"落地：provider usage 是唯一权威，`AgentRequestTokenEstimator` 是唯一估算器，缓存命中率口径与 DeepSeek 官方 `hit/(hit+miss)` 对齐。

本轮不重写 `AgentLoopEngine`、不动压缩/裁剪逻辑、不做上下文瘦身（Phase 1 范围）。

## 2. 工作树基线（2026-08-14）

- 分支：`codex/agent-tool-reliability`
- HEAD：`aae859717afe67b87a22e7e88ffdffe089b0be94`
- `git status --short` 基线：283 项（延续既有脏树，本轮未 reset/clean 任何历史改动）。

## 3. OpenCode 参考与复刻边界

本轮已阅读：

- `D:\opencode\opencode-dev\packages\opencode\src\session\session.ts`（getUsage 384-453：cache read/write 归一化、input 减缓存）
- `D:\opencode\opencode-dev\packages\llm\src\protocols\openai-chat.ts`（usage 提取 383-397）
- `D:\opencode\opencode-dev\packages\opencode\src\session\llm\overflow.ts`（8-34：provider usage 判定、字符估算只做预算）
- `D:\opencode\opencode-dev\packages\app\src\components\session\session-context-metrics.ts`（UI 只展示 cache read/write token 账本，不给误导性百分比）

复刻点：

1. cache read/write 拆列记账；input 与缓存 token 分离（getUsage 语义）。
2. DeepSeek 官方字段 `prompt_cache_hit_tokens / prompt_cache_miss_tokens` 纳入提取；miss 即写入（DeepSeek 计费口径）。
3. 命中率分母 miss-aware：`cached/(cached+miss)`，无 miss 字段时退回 `cached/prompt_tokens`。
4. 估算器二分：字符估算只服务预算/门禁，展示数字一律 provider usage 优先。

LabexAgent 适配：多用户 Web 控制面 + MySQL 持久化（`t_agent_token_usage` 加列、additive 迁移）；前端 SSE TOKEN_USAGE 事件渐进兼容（保留旧字段 + 新增字段）。本轮未复制 TypeScript 实质代码，均为独立 Java/JS 实现，仅复刻不变量；暂不需要 THIRD_PARTY_NOTICES 增补。

## 4. 变更清单

| 文件 | 变更 |
|---|---|
| `backend/src/main/java/com/labex/labexagent/llm/OpenAiCompatibleProvider.java` | `extractUsage`：cached 链加 `prompt_cache_hit_tokens`；cacheWrite 链加 `prompt_cache_miss_tokens`（miss 即写）；`cache_usage_reported` 判定加两个官方字段；输出新增 `cache_hit_tokens` / `cache_miss_tokens` 键 |
| `backend/src/main/java/com/labex/labexagent/llm/CacheTelemetry.java` | `hitRate` miss-aware 分母 |
| `backend/src/main/java/com/labex/labexagent/runtime/ContextUsageEstimator.java` | `estimateTokens` 委托 `AgentRequestTokenEstimator.estimateValue`（删 `/3`） |
| `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java` | 删 provider 无 usage 的 `/4` 兜底与 `/3` 辅助方法；兜底统一走 `requestTokenEstimator`；`tokenUsagePayload` 新增 `cacheHitTokens/cacheMissTokens/inputTokensNonCached` |
| `backend/src/main/java/com/labex/labexagent/service/AgentContextOrchestrator.java` | CONTEXT_STATS `estimatedTokens` 改唯一估算器 |
| `backend/src/main/java/com/labex/labexagent/service/TokenTracker.java` | `recordFromMap` 读新键并落库；stats 输出 `totalCacheHitTokens/totalCacheMissTokens` + perIteration 新字段 |
| `backend/src/main/java/com/labex/entity/AgentTokenUsage.java` | 新增 `cacheHitTokens/cacheMissTokens` |
| `backend/src/main/resources/sql/schema.sql` + `config/AdditiveSchemaMigrator.java` | `t_agent_token_usage` 加 `cache_hit_tokens/cache_miss_tokens`（additive） |
| `frontend/src/composables/cacheTelemetryStatus.js` | miss-aware 命中率、新字段累积、`resolveCacheTelemetryView` 输出 token 账本（cacheRead/cacheWrite/nonCachedInput）+ `sessionScoped` 标注 |
| `frontend/src/views/CloudWorkspace.vue` | 缓存卡片显示三列 token 账本，百分比标注"会话级" |

测试：新增 `UsageExtractionDeepSeekTest`（fixture 四件套：`backend/src/test/resources/fixtures/usage/*.json`）、`TokenAccountingConvergenceTest`、`AdditiveSchemaMigratorTimingTest.addsCacheTelemetryTokenColumnsToExistingUsageTable`、`cacheTelemetryStatus.test.mjs` miss-aware 用例。

## 5. 验证证据

```powershell
# 后端 focused（红→绿后）
mvn '-Dtest=UsageExtractionDeepSeekTest,CacheTelemetryTest,OpenAiCompatibleProviderUsageTest,OpenAiCompatibleProviderContractTest' test
# → Tests run: 30, Failures: 0, Errors: 0

mvn '-Dtest=TokenAccountingConvergenceTest,ContextUsageEstimatorTest,AgentLoopEngineContextBudgetTest,AgentLoopEngineCompactionTerminalityTest,AgentLoopEnginePolicyContractTest,AgentLoopEngineNextPreviewTest,AgentContextOrchestratorVerificationTrustTest,AgentContextOrchestratorIndexReuseTest' test
# → 通过

mvn '-Dtest=AdditiveSchemaMigratorTimingTest,TokenTrackerCacheTelemetryTest,CompactionSelectionTest,AgentCompactionServiceTest,AgentCompactionConversationScopeTest,PromptCacheKeyFactoryTest,PromptCacheKeyFactoryStablePrefixTest' test
# → Tests run: 37, Failures: 0, Errors: 0

# 后端全量
mvn test
# → Tests run: 1576, Failures: 0, Errors: 0, Skipped: 13 — BUILD SUCCESS

# 前端
npm test   # → tests 249, pass 249, fail 0
npm run build  # → built in ~1m，chunk budget 通过
```

红态记录：`UsageExtractionDeepSeekTest` 初始 5 tests 中 4 失败（DeepSeek 字段 cached=0/write=0）+ 1 分母断言失败（66.67 vs 40.0）；`TokenAccountingConvergenceTest` 初始 3 失败（/3 vs utf8/3 差异）。修复后全绿。

## 6. 肉眼验收步骤（用户可自行核对）

1. 启动前后端（MySQL 迁移会在启动时自动加两列），登录进任一项目，发起一次真实 Agent 对话（使用 DeepSeek 或带 `prompt_cache_key` 的模型配置）。
2. 第一次模型调用后，AI 面板"Prompt 缓存"卡片应显示 **已写入，尚未读取（write_only）**——此前这个状态从未正确出现过（miss 从不算写入）。
3. 后续工具循环轮次，卡片变为 **已命中**，并展示三列：**读取 X tokens / 写入 Y tokens / 非缓存输入 Z tokens**。
4. 百分比旁标注 **（会话级）**；用 DeepSeek 时，该百分比应约等于 `读取/(读取+写入)`，与 DeepSeek 官网控制台的 hit/miss token 数对得上（官网是全账号流量，数字不完全相同属正常，但口径一致）。
5. 打开上下文预算面板（CONTEXT_STATUS）与 TOKEN_USAGE 数字：估算值标注 `estimated=true`，有 provider usage 时以 usage 为准——不再出现 `/3` 与 `/4` 两套数字。
6. 刷新页面：缓存卡片与热力图数值保持（来自 `t_agent_token_usage` 新列）。

## 7. 风险与遗留

- **T0.6 写库截断移除**：本轮后续补充完成（同轮第二次实施）——`AgentRunTranscriptService` 与 `AgentRunPartService` 的 `output_text` 8k 截断全部移除，存储层全量落库（LONGTEXT）；红测试 `AgentRunPartServiceTest.persistsToolCallDetailWithoutTruncation`、`AgentRunTranscriptServiceTest.persistsToolResultPartOutputWithoutTruncation` 先红后绿。展示层截断保持（前端 ToolCallCard 1000 字符、CONTEXT_STATS preview 上限）。
- **T9.1 对齐状态文档**：`docs/coding-agent-industrialization/opencode-alignment-status.md` 已更新（Phase 0 证据、差异 10-12 条、剩余阶段清单）。
- **DeepSeek 字段存在性**：`prompt_cache_hit_tokens / prompt_cache_miss_tokens` 以 api-docs.deepseek.com 官方文档为准；若实际返回仅为 `prompt_tokens_details.cached_tokens`，提取链仍能命中（老字段优先保留）。
- **百分比保留一个版本**：按 spec 8.2，旧百分比与"会话级"标注共存，下一迭代删除百分比、只留 token 账本。
- **live smoke 未跑**：本轮只有单测+前端 build 证据；真实模型流式 usage（`stream_options.include_usage`）路径由既有 `OpenAiCompatibleProviderUsageTest` 的本地 HTTP server 覆盖，未在真实 provider 上验证。用户按第 6 节验收即补上 live 证据。
- 全量后端测试：1578 tests（T0.6 红绿后全量复核），0 failures。
