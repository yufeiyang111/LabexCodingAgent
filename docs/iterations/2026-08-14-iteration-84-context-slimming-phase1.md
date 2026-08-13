# 第 84 轮：上下文瘦身 Phase 1 —— 懒加载首条消息与提示词对齐

## 1. 本轮目标

按 [2026-08-14-opencode-context-token-cache-alignment](../superpowers/plans/2026-08-14-opencode-context-token-cache-alignment/tasks.md) Phase 1 执行 T1.1/T1.2/T1.3：首条 durable user message 从 ~60k 字符的预计算 bundle 收敛为"策略 + 规则 + 精简记忆"瘦身形态；系统提示词不再重复注入工具名清单；workflow 提示词改为 opencode 式按需获取（中文）。

本轮不触碰压缩/裁剪、不重写 AgentLoopEngine 主循环、保留 preview/诊断 API 的完整 bundle 展示。

## 2. 工作树基线

- 分支：`codex/agent-tool-reliability`；上一轮 12 个本地提交全部保留，本轮在其上继续。

## 3. OpenCode 参考与复刻边界（强制记录）

已阅读：

- `D:\opencode\opencode-dev\packages\opencode\src\session\system.ts`（55-106：system = env 块 + 指令文件 + 技能**名称列表**，无项目文件/结构/诊断）
- `D:\opencode\opencode-dev\packages\opencode\src\session\prompt.ts`（1327-1347：`system = [...env, ...instructions, ...skills]`，messages 只含 transcript）
- `D:\opencode\opencode-dev\packages\opencode\src\session\llm\request.ts`（56-112：system 一条前置、不承载 per-request 内容）
- `D:\opencode\opencode-dev\packages\opencode\src\session\prompt\default.txt`（提示词语气：简洁、直给、按需工具）

复刻点：

1. 首条消息只承载稳定策略（mode/language）+ 项目规则 + 精简持久记忆（≤2k）；项目文件、结构、符号、诊断一律工具按需拉取。
2. system prompt 不重复注入工具名清单（工具 schema 只进请求 body，`session/tools.ts` 语义）。
3. workspace memory 是"少量持久事实 + 与现状冲突时以现状为准"，不是全文资料库。
4. 恢复（resume）连续性由 durable transcript 投影重放（含 compaction checkpoint）+ 每次调用追加的 `<agent_runtime_projection>` 提供；旧版文件 checkpoint（`AgentCheckpointStore`）是只读迁移入口，运行时恢复路径不注入文件内容。

LabexAgent 适配：preview/诊断 API 保留 `buildInitialBundle` 全量 bundle（spec 2.3），但预览的"下一条请求估算"使用瘦身消息，避免展示与真实请求脱节。本轮为独立 Java 实现，未复制 TypeScript 源码，无需更新 THIRD_PARTY_NOTICES。

## 4. 变更清单

| 文件 | 变更 |
|---|---|
| `runtime/AgentLoopEngine.java` | 新增 `buildLeanInitialContextMessage`（静态，mode/language/rules≤10k/leanMemory≤2k/runLog≤12k/checkpoint≤12k）；删除旧 `buildContextMessage`（skills 16k/MCP 12k/conversation memory 16k/session_context 60k）；run 路径不再调用 `buildInitialBundle`，CONTEXT_STATS 改发瘦身统计（contextMode=lean）；preview 路径估算改用瘦身消息（bundle 仅作展示诊断） |
| `service/AgentContextOrchestrator.java` | 新增 `buildLeanWorkspaceMemory`（≤2000 字符，失败静默返回空） |
| `prompt/LabexSystemPrompt.java` | `toolPolicy` 删除 `Available tools` 名称清单注入（参数保留兼容）；workflow "上下文与诊断"段改为中文按需获取指南 |
| `runtime/AgentLoopEngineStreamingContractTest.java` | 源码契约断言更新为新瘦身上限（10k/2k/12k/12k）并断言旧 60k/16k/12k 注入不存在 |
| `runtime/InitialContextShapeTest.java`（新） | 4 个红绿测试：无预加载 bundle、恢复上下文保留、硬上限下 ≤8k token、空输入为空串 |
| `prompt/LabexSystemPromptTest.java` | 新增：system prompt 不含工具名清单 |

## 5. 验证证据

```powershell
# 红态
mvn '-Dtest=InitialContextShapeTest' test   # COMPILATION ERROR: buildLeanInitialContextMessage 不存在
mvn '-Dtest=LabexSystemPromptTest' test    # 新测试 would fail：旧 prompt 含工具名清单（实现后变绿）

# 绿态（focused）
mvn '-Dtest=InitialContextShapeTest,LabexSystemPromptTest,AgentLoopEngineNextPreviewTest,AgentLoopEngineStreamingContractTest,AgentLoopEngineContextBudgetTest,AgentContextOrchestratorVerificationTrustTest,AgentContextOrchestratorIndexReuseTest,ContextUsageEstimatorTest,AgentLoopEngineWiringContractTest,PromptCacheKeyFactoryStablePrefixTest' test
# → 全部 PASS

# 后端全量
mvn test   # → Tests run: 1587, Failures: 0, Errors: 0, Skipped: 13 — BUILD SUCCESS
```

首条消息体积变化（hard limits 下估算，唯一估算器）：

- 旧：rules 10k + skills 16k + MCP 12k + conversation memory 16k + session_context 60k ≈ 114k 字符 ≈ 38k token
- 新：rules 10k + lean memory 2k ≈ 12k 字符 ≈ 4k token（InitialContextShapeTest 断言 ≤8k）

## 6. 肉眼验收步骤

1. 发起一次真实 Agent 任务，观察 run log（或日志 `AGENT_CONTEXT_READY`）：`contextChars` 从数万降到 ~2-15k，`contextMode=lean`。
2. 上下文面板 CONTEXT_STATS：显示 lean 统计与唯一估算器 token 数。
3. 首条请求输入 token 明显下降（对照上一轮 TOKEN_USAGE 账本）。
4. 模型行为：任务开始时主动调用仓库地图/搜索工具探查结构（而不是假设上下文已注入）。
5. 预览/诊断 API 仍能看到完整 bundle（含 adaptive index / repo map），与真实请求分开标注。

## 7. 风险与遗留

- **能力回归风险**：不再预注入 adaptive index/repo map，模型必须主动调用工具获取结构；弱模型可能在陌生大仓库中探查变慢。缓解：workflow 明确指引"先结构后文件"，repo_map 工具仍在。
- **恢复路径**：resume 不重新注入 run log / checkpoint 文件内容（durable transcript 投影已重放完整历史与 compaction checkpoint）；跨重启恢复的上下文质量由 live smoke 覆盖。
- **preview 双份组装仍在**（preview 的 metadata 携带 bundle 诊断 + 估算用瘦身消息），属已知收敛点，Phase 4 前保留。
- **skills/MCP 名称列表**尚未移入 system prompt（opencode 有 `<available_skills>` 名称清单）；当前技能完全靠 SkillTool 发现，留给后续迭代对齐。
- 前端未改动；本轮无前端验证需求（CONTEXT_STATS payload 字段兼容旧前端）。
