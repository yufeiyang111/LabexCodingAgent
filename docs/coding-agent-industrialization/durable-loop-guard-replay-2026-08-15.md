# 可恢复 Loop Guard 后缀重放记录

- 日期：2026-08-15
- 范围：Agent 主循环在暂停、恢复或 JVM 重启后的重复 Tool-call 防护。
- 结论：重放的唯一权威是当前 task / execution epoch 的 durable Tool Part；不使用 SSE、内存 `AgentLoopGuard` 或旧 epoch 的验证结果作为恢复事实。

## 参考

1. **OpenCode 本地快照 v1.17.4**
   - `D:\opencode\opencode-dev\packages\opencode\src\session\prompt.ts:1134-1149`：每次 run-loop 从 `MessageV2.filterCompactedEffect(sessionID)` 重新读取持久化 message/part。
   - `D:\opencode\opencode-dev\packages\opencode\src\session\processor.ts:519-545`：从最近 durable Tool Part 检查同 tool + input 的重复后缀，进入 `doom_loop` 审批。
   - `D:\opencode\opencode-dev\packages\opencode\src\session\prompt.ts:1231-1243`：step 预算不立即终止，而是向最后一步注入可见 sentinel。
2. **DeepSeek Harness 公开资料**
   - `https://github.com/deepseek-ai/deepseek-harness/blob/main/docs/architecture.md`：Session Event 为 append-only 流，turn/step/tool 可以回放，且 model-visible 输入必须可从持久化事实重建。
   - 早先的固定快照研究：`docs/coding-agent-industrialization/deepseek-harness-tool-loop-prevention-research-2026-08-15.md`。

## 复制的不变量

- 恢复前先从 durable 历史重建最近 tool-call 后缀，再判断新调用是否重复。
- 仅使用 `part_type=tool` 且有显式终态（`completed/error/blocked/skipped/interrupted`）的 Part；`pending/running/waiting_*` 不计入后缀。
- 仅使用 Part metadata 中与当前 `executionEpoch` 一致的记录；缺少 epoch 的旧 Part 不能触发恢复重放判断。
- 重放只恢复近期 signature 后缀，**不恢复分散的 failed-attempt 计数**。后者在当前运行中仍由 durable progress fingerprint 失效，防止新验证成功后被旧失败记录阻断。

## LabexAgent 适配

- `AgentRunPartService.currentEpochToolHistory` 集中定义 task/epoch/terminal-part 读取边界。
- `AgentLoopEngine` 创建 `AgentLoopGuard` 后立即重放该后缀，并对 durable 入参使用同一个 command-normalization 路径。
- `PlanVerificationEvidenceService` 复用这个读取边界，因而计划验证和 loop replay 不会对当前 epoch 得出不同结论。

未复制 OpenCode 或 DeepSeek Harness 的实质代码；仅复制了 durable replay 和 suffix-based loop detection 的设计不变量。
