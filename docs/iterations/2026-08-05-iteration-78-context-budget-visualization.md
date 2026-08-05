# 第 78 轮：上下文来源与有效压缩线可视化修正

- 日期：2026-08-05
- 分支：`codex/agent-tool-reliability`
- 前置提交：`40b9a72 fix: gate legacy runtime migration removal`
- 计划来源：用户对 39.2K / 204.8K 上下文截图的实际反馈，以及 `docs/superpowers/plans/2026-07-29-agent-runtime-state-convergence.md` 的上下文唯一事实源与可解释压缩要求

## 1. 问题基线

本轮开始前已经核对截图对应的持久化 `CONTEXT_STATUS` 快照：

- `usedTokens=39227`
- `contextWindowTokens=204800`
- `reservedOutputTokens=51200`
- `inputCapacityTokens=153600`
- `softLimitTokens=153600`
- `staticTokens=22275`
- `reducibleTokens=16952`
- `trimState=NONE`
- 旧分类中的 `compactedContext=328`

同一任务没有 `COMPACTION_STARTED`、`COMPACTION_COMPLETED` 或 `COMPACTION_FAILED` 事件，也没有对应 compaction record。因此 328 tokens 不是一次已被证明发生的压缩结果。

真实预算关系是：

```text
总窗口 204800
- 预留输出 51200
= 输入容量 153600

百分比阈值线 = floor(204800 * 90%) = 184320
默认安全缓冲 = clamp(ceil(204800 * 10%), 2048, 8192) = 8192
安全缓冲线 = 204800 - 8192 = 196608
有效软上限 = min(153600, 184320, 196608) = 153600
```

因此截图中的 39.2K：

- 占总窗口约 19.2%；
- 占有效软上限约 25.5%；
- 距有效触发线仍有 114373 tokens；
- 本轮没有执行压缩。

## 2. 根因

### 2.1 后端把三个来源合并成了一个错误分类

`ContextUsageEstimator.PromptContext.of(...)` 原先把以下内容直接连接：

- conversation memory；
- recent run recovery log；
- checkpoint / compaction summary。

合并后的字段统一命名为 `compactedContext`。这使普通持久化会话记忆和运行恢复上下文被错误解释为“压缩上下文”。

### 2.2 前端丢弃了后端的有效软上限

`ContextUsageSnapshot.toPayload()` 已经输出 `softLimitTokens`，但 `normalizeContextBudget(...)` 没有保留该字段。弹窗只能展示总窗口百分比、输入容量和输出预留，无法解释真正的触发线。

### 2.3 模型配置提示只讲百分比线

模型配置原提示写成“200K 窗口 90% 会在 180K 触发”，没有说明最终有效线还会受 Max Tokens 输出预留和安全缓冲约束。对于 204.8K / 51.2K 配置，真实有效线是 153.6K，而不是 184.3K。

## 3. 本轮计划与停止边界

计划：

1. 先写失败回归测试，固定来源分类、有效软上限和模型配置预览公式。
2. 后端拆分来源并为新快照增加分类版本；旧持久化快照继续可读。
3. 前端展示总窗口与有效软上限两套百分比、本轮 trim state 和距触发剩余量。
4. 用与后端一致的只读公式预览未保存模型配置；后端校验仍是权威。
5. 运行定向测试、完整测试、生产构建和真实浏览器组件渲染检查。
6. 只提交本轮文件，保留共享工作区中并发出现的其他改动。

停止边界：

- 不修改 AgentLoopEngine 的 compaction 决策顺序；
- 不修改实际阈值、输出预留或安全缓冲默认值；
- 不伪造 compaction before/after 数据；真实压缩仍以 durable `COMPACTION_COMPLETED` 为准；
- 不迁移或重写旧 `CONTEXT_STATUS` 事件；旧快照只做兼容显示；
- 不进行无关 UI 重构或 legacy reader 删除。

## 4. 架构决策

### 4.1 后端分类是派生投影，不是新事实源

`AgentTask`、durable transcript、compaction record 和 run event 继续是权威事实。`ContextUsageSnapshot` 只从 Provider 请求投影生成可重建预算视图，不反向写入 transcript 或运行状态。

### 4.2 新分类版本

新快照输出 `contextCategoryVersion=context-budget-v2`，分类拆分为：

- `projectContext`
- `workspaceMemory`
- `conversationMemory`
- `runRecoveryContext`
- `compactionSummary`
- 其余 system/tool/skills/message/tool-result/protocol 分类

旧快照没有版本时恢复为 `context-budget-v1`，原 `compactedContext` 键继续保留，但前端显示为“旧版恢复上下文”，不再宣称它一定来自压缩。

### 4.3 真实 durable checkpoint 单独识别

完成 compaction 后，摘要会以包含 `<conversation-checkpoint ...>` 的 durable user message 投影给 Provider。本轮估算器识别该协议标记，将其计入 `compactionSummary`，不再混入普通 `conversationMessages`。

### 4.4 实际预算以后端为准

实际 `CONTEXT_STATUS` 直接消费后端 `softLimitTokens`。前端只计算：

- 当前占有效软上限百分比；
- 有效软上限占总窗口百分比；
- 距触发剩余 tokens。

旧快照没有 `softLimitTokens` 时，前端明确标记为兼容估算，并暂按输入容量展示。

模型配置表单尚未保存时没有后端快照，因此新增只读预览 helper，逐项镜像 `ContextWindowPolicy` 公式；保存时仍由后端验证和持久化，前端预览不能成为权威配置。

## 5. 实际修改

### 5.1 后端

- `ContextUsageEstimator`
  - 拆分 conversation memory、run recovery 和 compaction summary；
  - preview 同步使用新分类；
  - 识别 durable `<conversation-checkpoint>` 消息；
  - 保留旧构造器和 deprecated `compactedContext()` 访问器，兼容现有调用但只返回真实摘要。
- `ContextUsageSnapshot`
  - 新快照标记 `context-budget-v2`；
  - 从旧事件恢复时保留 `context-budget-v1`；
  - status payload 与 preview payload 都携带分类版本。

### 5.2 前端

- `contextBudgetView.js`
  - 保留 `softLimitTokens`；
  - 计算有效线占用、总窗口占比和剩余距离；
  - 兼容只有静态/可压缩分项的旧快照。
- `ContextUsageDialog.vue`
  - 同时展示总窗口用量与有效软上限用量；
  - 展示有效软上限、距触发距离和本轮 `trimState`；
  - 新来源分别显示为“持久化会话记忆”“运行恢复上下文”“压缩摘要”；
  - 旧 `compactedContext` 显示为“旧版恢复上下文”；
  - 增加协议元数据行，使分类表能够解释完整 token 总量。
- `ContextUsageIndicator.vue`
  - 环形进度和告警色改为基于有效软上限；
  - popover 同时保留总窗口百分比；
  - 补齐新旧分类色板。
- `contextWindowPolicyView.js`
  - 镜像后端默认阈值、默认安全缓冲 clamp、输出预留和最小有效线公式。
- `ModelConfigDialog.vue`
  - 删除误导性的固定 200K / 180K 示例；
  - 实时展示输入容量、百分比线、安全缓冲线和最终最小值。

## 6. TDD 红绿证据

先修改测试后运行，确认旧实现按预期失败：

- 后端无法找到 `conversationMemory` / `runRecoveryContext` / `compactionSummary`；
- 新快照没有 `contextCategoryVersion`；
- durable `<conversation-checkpoint>` 被错误计入普通消息；
- 前端没有 `softLimitTokens`、距触发量和有效线百分比；
- 模型配置预览 helper 不存在；
- 弹窗缺少新分类、旧快照兼容标签和本轮未压缩状态。

实现后定向测试全部转绿。

## 7. 验证结果

### 7.1 后端定向与完整测试

```powershell
cd D:\LabexAgentackend
mvn -q "-Dtest=ContextUsageEstimatorTest,ContextUsageSnapshotBudgetTest,ContextUsageRegistryDurabilityTest" test
mvn clean test
```

结果：

- 定向测试退出码 0；
- 干净重编译成功；
- 完整后端测试 `1044` 项，`0` failure，`0` error，`9` skipped，`BUILD SUCCESS`。

完整后端门槛完成后，共享工作区才出现未归属本轮的 `TokenTracker` 并发修改；这些文件没有进入本轮提交。

### 7.2 前端测试与构建

```powershell
cd D:\LabexAgentrontend
npm.cmd test
npm.cmd run test:acceptance:unit
npm.cmd run build
```

当前共享工作区结果：

- 前端测试 `223/223` 通过；
- acceptance 辅助测试 `16/16` 通过；
- Vite 生产构建通过；
- `CloudWorkspace` 1465351 / 1500000 bytes；
- `index` 1259636 / 1300000 bytes；
- `TerminalPanel` 380367 / 400000 bytes。

这里的 223 项会包含共享工作区中并发出现、但未纳入本轮提交的 UsageHeatmap 测试；本轮自己的上下文相关定向测试为 `12/12` 通过。

### 7.3 真实浏览器组件验收

为了不读取用户 JWT、账号或修改真实项目数据，本轮通过当前 Vite 服务临时挂载仓库中的真实 Vue 组件，并在验收后删除临时入口。注入数据与问题截图的预算值一致。

`ContextUsageDialog.vue` 的浏览器可访问性树和页面渲染确认：

- `39.2K / 204.8K tokens（总窗口 19%）`；
- `有效自动压缩线 153.6K tokens`；
- `占总窗口 75.0%`；
- `当前占有效压缩线 25.5%`；
- `距触发还剩 114.4K tokens`；
- `本轮未执行压缩`；
- 328 tokens 显示为“持久化会话记忆”；
- 切换到 v1 兼容快照后显示“旧版恢复上下文 328 0.2%”，不存在错误的“压缩上下文”行。

`ModelConfigDialog.vue` 的真实浏览器渲染确认：

- 有效线 `153,600 tokens（占总上下文窗口 75.0%）`；
- 输入容量 `153,600`；
- 百分比线 `184,320`；
- 安全缓冲线 `196,608`；
- 页面明确说明系统取三者最小值。

浏览器 console 没有组件运行错误；ModelConfigDialog 仍存在历史遗留的 label/id 可访问性提示，本轮没有新增表单控件，也没有把该既有问题混入上下文预算修复。

## 8. 文件范围

本轮代码和测试：

- `backend/src/main/java/com/labex/labexagent/runtime/ContextUsageEstimator.java`
- `backend/src/main/java/com/labex/labexagent/runtime/ContextUsageSnapshot.java`
- `backend/src/test/java/com/labex/labexagent/runtime/ContextUsageEstimatorTest.java`
- `backend/src/test/java/com/labex/labexagent/runtime/ContextUsageSnapshotBudgetTest.java`
- `frontend/src/composables/contextBudgetView.js`
- `frontend/src/composables/contextBudgetView.test.mjs`
- `frontend/src/composables/contextWindowPolicyView.js`
- `frontend/src/composables/contextWindowPolicyView.test.mjs`
- `frontend/src/components/cloud/ContextUsageDialog.vue`
- `frontend/src/components/cloud/ContextUsageIndicator.vue`
- `frontend/src/components/cloud/ContextUsageComponents.test.mjs`
- `frontend/src/components/cloud/ModelConfigDialog.vue`
- `frontend/src/components/cloud/ModelConfigDialog.test.mjs`
- `frontend/src/styles/cloud-workspace.scss`

文档：

- `docs/iterations/2026-08-05-iteration-78-context-budget-visualization.md`
- `docs/superpowers/plans/2026-07-29-agent-runtime-state-convergence.md`

## 9. 完成状态与后续边界

本轮上下文可视化修正已完成并达到停止边界，不继续扩大 Agent runtime 重构。

仍需用户手工验收的客观边界：

1. 已运行的旧后端 JVM 不会因为编译自动加载新分类；需要重启实际 8080 后端后再发起一次 Agent 请求，才能产生 `context-budget-v2` 快照。
2. 旧持久化快照不会被改写；在新请求前会继续以“旧版恢复上下文”兼容显示。
3. 本轮没有故意触发真实长上下文 compaction，因此没有伪造 before/after；实际压缩仍应在时间线看到 `COMPACTION_COMPLETED` 和对应 tokensBefore/tokensAfter。
4. 最终浏览器验收应由用户在自己的账号、项目和常用浏览器中完成。
