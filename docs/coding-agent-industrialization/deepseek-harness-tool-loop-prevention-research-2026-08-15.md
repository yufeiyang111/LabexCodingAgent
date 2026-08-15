# DeepSeek Harness：防止 Tool-call 死循环的公开设计研究

- **研究日期**：2026-08-15
- **范围**：仅查阅 DeepSeek 官方 API 文档与 `deepseek-ai` GitHub 组织中公开可见的源码/文档；未读取或修改 LabexAgent 的应用代码。
- **固定源码快照**：[`deepseek-ai/deepseek-harness` commit `47f943859bef60e4160492346772ded9b24f765a`](https://github.com/deepseek-ai/deepseek-harness/tree/47f943859bef60e4160492346772ded9b24f765a)。以下源码链接均固定到该提交，避免 `master` 漂移。

## 结论先行

1. **DeepSeek 已发布 source-visible 的 `deepseek-harness`，不是只有模型 API 示例。**官方 API 文档将它列为 Agent Integrations；公开仓库包含 concrete loop、session、tool pipeline、sandbox、compaction 和 test-support 包。[官方 API 文档](https://api-docs.deepseek.com/guides/thinking_mode/)；[公开仓库](https://github.com/deepseek-ai/deepseek-harness/tree/47f943859bef60e4160492346772ded9b24f765a)
2. **不能据此声称它等同于 DeepSeek 平台/产品的内部生产 harness。**本次限定的第一方资料没有声明该仓库与其内部线上控制面完全同构，也没有公开线上运行参数。因此本文只讨论 DeepSeek 发布的可审查 harness；不对未公开实现作推断。
3. **核心 loop 没有内建“全局最大 tool-call/step 数”。**它限制单请求输出和并发工具数，但公开文档明确说明没有 built-in turn budget；跑飞 turn 需要从 lifecycle extension point 取消。[核心 loop 配置](https://github.com/deepseek-ai/deepseek-harness/blob/47f943859bef60e4160492346772ded9b24f765a/packages/core/agent-loop/README.md#L30-L47)；[限制声明](https://github.com/deepseek-ai/deepseek-harness/blob/47f943859bef60e4160492346772ded9b24f765a/packages/core/agent-loop/README.md#L116-L120)
4. **对相同工具和相同参数的重复调用，它默认用 `[3, 5, 8]` 阈值逐级提醒模型，但不会强制阻断。**这是有意保留模型决策权的软防护；高阈值 block 仍是 deferred work。[重复工具提醒](https://github.com/deepseek-ai/deepseek-harness/blob/47f943859bef60e4160492346772ded9b24f765a/packages/guard/repeat-tool-reminder/README.md#L3-L29)；[已知限制](https://github.com/deepseek-ai/deepseek-harness/blob/47f943859bef60e4160492346772ded9b24f765a/packages/guard/repeat-tool-reminder/README.md#L72-L78)
5. **其关键工程手段是可组合边界，而不是一个万能次数阈值：**可审计 session event、每个 tool call 的稳定结果、工具输出 bounded projection、隔离策略和 trajectory replay。它们降低协议错误/上下文污染导致的二次循环，但不自动保证任务终止。

## 1. API 协议层：何时停止由 harness 决定

DeepSeek 官方 tool-call 示例本身是 `while True`：模型返回 `tool_calls` 后，客户端执行工具，将带原始 `tool_call_id` 的 `role=tool` 结果追加回 messages；直到模型不再返回 `tool_calls` 才退出。带 tools 的 thinking-mode 后续请求还必须完整回传 `reasoning_content`，否则 API 会报 400。[官方 Thinking Mode / Tool Calls](https://api-docs.deepseek.com/guides/thinking_mode/#tool-calls)

**可证实的含义：**API 规定的是多轮协议闭环（assistant tool call、`tool_call_id`、tool result、reasoning context）；它没有替调用方提供总轮次、重复调用识别或失败升级策略。因此一个仅按示例实现的客户端天然存在无限循环可能，循环终止/预算属于 harness 责任。

## 2. 迭代预算与重复调用检测

### 2.1 不把全局轮次上限写死在 loop 内核

`dsh-agent-loop` 是唯一包含 concrete loop 的包。公开配置包含 `maxParallelToolCalls`（默认并发池大小）和 `maxTokens`（单请求输出限制），但并不提供通用的最大 step/tool-call 数。文档明确写明：没有内建 turn budget；tool calls 或 steering 可以持续当前 turn，需由 `agent/turn-stopping` 等生命周期扩展点取消 runaway turn。[核心 loop](https://github.com/deepseek-ai/deepseek-harness/blob/47f943859bef60e4160492346772ded9b24f765a/packages/core/agent-loop/README.md#L4-L6)；[配置](https://github.com/deepseek-ai/deepseek-harness/blob/47f943859bef60e4160492346772ded9b24f765a/packages/core/agent-loop/README.md#L30-L47)；[限制](https://github.com/deepseek-ai/deepseek-harness/blob/47f943859bef60e4160492346772ded9b24f765a/packages/core/agent-loop/README.md#L116-L120)

**结论：**DeepSeek 的公开设计不支持“成熟 harness 必须硬编码固定 20/50 次循环上限”这一说法。通用 loop 保持策略可插拔，避免粗粒度数字误伤长任务；代价是部署方必须明确实现资源和终止策略。

### 2.2 精确重复检测是 advisory guard

`dsh-repeat-tool-reminder` 的公开语义：

- 链键是 `(tool name, canonical arguments)`；参数先深度 key-sort 再 JSON 序列化，因此只是对象键顺序变化仍视为同一调用。
- 默认连续第 `3/5/8` 次触发渐进提醒；`todo_write` 等排除工具不重置链，因此 `grep → todo_write → grep` 仍可被识别为连续 grep。
- 被拒绝的调用也计数，避免模型反复请求已被策略拒绝的动作。
- 计数按 agent 隔离；新用户输入重置；**会话从持久化恢复后计数从零开始**。
- 提醒作为带来源标记的附加 context 放到下一模型请求；原始 `tool/result` 保持不变，便于审计和重放。
- 它从不 veto/rewrite 调用；最高阈值后不再提醒；近似参数变化可绕过；高阈值 block 尚未实现。

来源：[配置与链语义](https://github.com/deepseek-ai/deepseek-harness/blob/47f943859bef60e4160492346772ded9b24f765a/packages/guard/repeat-tool-reminder/README.md#L3-L29)；[模型可见提醒与限制](https://github.com/deepseek-ai/deepseek-harness/blob/47f943859bef60e4160492346772ded9b24f765a/packages/guard/repeat-tool-reminder/README.md#L30-L78)。

**结论：**这是“促使模型自我纠偏”的 guard，而不是终止性保证。若目标是控制成本和时间，不能只依赖 `[3,5,8]`；还应结合持久化硬预算、无进展判断和明确终态。

### 2.3 有限轮次只属于特定 workflow

`dsh-goal-round-driver` 的 `maxGoalRounds` 属于 goal 定义，不复制到 driver；它把 round 作为可持久化、可预留的 workflow 控制。文档同时说明：round cap 不是 token、费用、时间或 provider quota 的资源预算，异常也不会隐式自动重试。[goal round driver](https://github.com/deepseek-ai/deepseek-harness/blob/47f943859bef60e4160492346772ded9b24f765a/packages/goal/goal-round-driver/README.md#L20-L40)；[限制](https://github.com/deepseek-ai/deepseek-harness/blob/47f943859bef60e4160492346772ded9b24f765a/packages/goal/goal-round-driver/README.md#L58-L64)

**结论：**最大轮数是特定 workflow 策略，不能偷换成所有普通 tool loop 的通用防护。

## 3. Tool result 处理、截断与失败归一化

### 3.1 一个 call 对应一个稳定、可审计的最终结果

工具管线把 `tool/call` 在执行前记录；依次经过 pre-execute（hook、approval、sandbox）、monotonic guards、around execute（timeout/retry/metrics）、post-execute、`finalizeContent` 和最终 `tools/result`。拒绝、审批失败和 wrapper 异常也要归一化为最终模型可见结果；tool batch 完成后才追加附加上下文。[工具执行管线](https://github.com/deepseek-ai/deepseek-harness/blob/47f943859bef60e4160492346772ded9b24f765a/docs/tool-execution-pipeline.md#L4-L60)

取消时，核心 loop 会为未派发 call 产生配对的 synthetic call/result，并用 `ABORTED_BEFORE_DISPATCH` 表示；已启动调用需要 drain 已完成结果，同时保持模型顺序的 result context。[核心 loop：取消与工具调度](https://github.com/deepseek-ai/deepseek-harness/blob/47f943859bef60e4160492346772ded9b24f765a/packages/core/agent-loop/README.md#L61-L77)；[取消后的模型视图](https://github.com/deepseek-ai/deepseek-harness/blob/47f943859bef60e4160492346772ded9b24f765a/packages/core/agent-loop/README.md#L104-L115)

**与循环的关系：**空结果、丢失 call id、只写日志而不回传确定失败，都会诱发模型盲目重试。统一且稳定的 tool result 不能直接保证正确，但能消除这类协议性循环来源。

### 3.2 工具结果截断是 projection，不丢审计原始事实

`dsh-compaction-tool-result-pruner` 对超预算 `tool/result` 创建 surface replacement：模型只见 head + 固定省略标记 + tail；替换保留原 `turn/step/callId/error/meta`，原事件仍在 append-only log 中，可用于持久化、replay 和精确检查。默认阈值为 8192 Unicode code points；替换严格变小，因此第二次不会重复重写。[工具结果裁剪](https://github.com/deepseek-ai/deepseek-harness/blob/47f943859bef60e4160492346772ded9b24f765a/packages/compaction/compaction-tool-result-pruner/README.md#L4-L22)；[模型视图与限制](https://github.com/deepseek-ai/deepseek-harness/blob/47f943859bef60e4160492346772ded9b24f765a/packages/compaction/compaction-tool-result-pruner/README.md#L34-L51)

**与循环的关系：**它不是重复调用检测器；它防止大 stdout、搜索结果或 diff 挤掉上下文中的有效证据，继而诱发重复查询。其预算是字符而非 token，且可能裁掉关键中段，因此仍需 token meter 和工具级摘要策略。

### 3.3 超时要变成结构化结果

`dsh-tool-call-timeout-policy` 仅对工具自己声明 `timeoutMs` 的调用设置 cooperative deadline，超时以结构化 `TOOL_TIMEOUT` result 返回。它没有全局默认超时；不遵守 abort signal 的工具也不会被强杀。[工具超时策略](https://github.com/deepseek-ai/deepseek-harness/blob/47f943859bef60e4160492346772ded9b24f765a/packages/guard/timeout-policy/README.md#L3-L32)

**与循环的关系：**超时能将“卡住”变为模型可理解的失败证据，但不阻止模型重新调用同一超时工具，因此必须与重复/预算策略组合。

## 4. Sandbox 与 verification：隔离不等于验证

`dsh-sandbox-policy` 以单一 owner 解析 deployment default、session 持久化 mode override 和 immutable workspace root；默认 `read-only`，模式是 `read-only` / `workspace-write` / `danger-full-access`。模式切换是 session event，可重启重放且不会与其他会话串扰。[sandbox policy](https://github.com/deepseek-ai/deepseek-harness/blob/47f943859bef60e4160492346772ded9b24f765a/packages/sandbox/sandbox-policy/README.md#L4-L22)

该策略只约束文件效果，不涵盖网络和进程策略。[sandbox 限制](https://github.com/deepseek-ai/deepseek-harness/blob/47f943859bef60e4160492346772ded9b24f765a/packages/sandbox/sandbox-policy/README.md#L52-L55)

**公开资料边界：**本次查阅范围内，没有找到由 `deepseek-harness` 核心 loop 强制的通用规则——“写代码后必须 build/test，且验证通过才能完成”。所以 sandbox、权限批准和命令 exit code 都不是任务正确性的充分证据。

## 5. Trajectory、回放与公开评测边界

`dsh-llm-replay` 从持久化 session JSONL 的 `assistant/chunk` 重建 `(turn, step)` LLM stream，使真实 agent loop 无需 API key 即可回放固定轨迹；纯抛错、取消/挂起通过显式 override sidecar 表达。回放结束还要求消费完整脚本，避免测试静默地只执行了比预期更少的模型调用。[LLM replay](https://github.com/deepseek-ai/deepseek-harness/blob/47f943859bef60e4160492346772ded9b24f765a/packages/test-support/llm-replay/README.md#L3-L19)；[回放导出与 `assertConsumed`](https://github.com/deepseek-ai/deepseek-harness/blob/47f943859bef60e4160492346772ded9b24f765a/packages/test-support/llm-replay/README.md#L57-L80)

`dsh-invariants` 默认启用，可检查 session enclosure/call-result trace、agent status、inbox FIFO、模型请求重建、stream grammar、工具管线及冻结结果、compaction、sandbox mode 和 approval audit pairing。[运行时不变量](https://github.com/deepseek-ai/deepseek-harness/blob/47f943859bef60e4160492346772ded9b24f765a/packages/runtime-diagnostics/invariants/README.md#L4-L39)

公开的 [`BENCHMARK.md`](https://github.com/deepseek-ai/deepseek-harness/blob/47f943859bef60e4160492346772ded9b24f765a/BENCHMARK.md) 只说明使用 Python SDK 的 `jsonrpc-agent` 变体，并为独立 benchmark 使用不同 workspace/session id。本次第一方资料**没有提供**该 harness 的公开 coding benchmark 评分、loop-rate 指标、trajectory judge 或生产验证门槛报告。

**结论：**可以证实它具备真实 loop 的 trajectory regression 基础设施；不能从公开资料推出它已在某个特定 coding benchmark 上达到何种成绩，或其防循环效果一定优于其他项目。

## 6. 可迁移的工程推断（非 DeepSeek 对任何未公开系统的承诺）

1. **软硬两级防线同时存在。**软防线是 canonical `(tool,args)` 重复提醒；硬防线应按 task/turn/epoch 持久化 `maxSteps`、wall-clock deadline、工具类别预算和无进展预算。达到硬限制时写明确 `LOOP_GUARD_STOP`/`BUDGET_EXHAUSTED` 终态，不能静默重试。
2. **检测 key 要加入 progress，而不仅是 tool/args。**可使用文件版本/diff 指纹、命令退出码与输出摘要、搜索命中摘要、测试失败签名、approval/denial 原因，区分合法 polling/重试和无证据空转。判断输入必须来自可回放的持久化事实。
3. **call/result 必须是一等 durable 协议。**先持久化 call；成功、拒绝、超时、取消、内部异常均恰有一个终态 result，保留原始 `toolCallId`、epoch、错误分类和模型可见摘要。超长原始输出与模型 projection 分离。
4. **“执行成功”不等于“任务完成”。**验证应是任务类型和风险驱动的 build/test/diagnostics 证据，而不是模型自述、沙箱许可或一次工具 exit code。
5. **把死循环固化成 trajectory regression。**保存最小真实 event fixture，覆盖重复调用、每次结果、取消/恢复点和预期 terminal reason；用真实 loop + replay adapter 断言调用数上界、toolCallId 配对、重启后预算不丢、终态不回到 running。

## 7. 研究限制

- 未检查 LabexAgent 的应用代码、运行日志、数据库或配置；本文不能判断任何一次具体死循环的根因，也不能给出当前项目与该 harness 的逐文件差距。
- 未使用第三方测评文章、社区实现或媒体报道；所有事实结论只限 DeepSeek 官方 API 文档和官方 GitHub 源码。
- 公开仓库未来版本，以及 DeepSeek 未公开的内部线上实现，可能与该固定快照不同。
