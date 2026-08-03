# 第 58 轮：删除 AgentLoopEngine 可变运行时 transcript

- 日期：2026-08-03
- 分支：`codex/agent-tool-reliability`
- 对应计划：`docs/superpowers/plans/2026-07-29-agent-runtime-state-convergence.md` 的任务四、任务八、任务九
- 状态：完成

## 1. 审计结论

最初假设是 Provider 请求仍直接读取 `AgentLoopEngine.msgs`。现场代码和 Git 历史证明这个假设已经过时：自 2026-07-31 起，正式 Provider 请求、静态 admission 和主动预算都通过 `AgentTranscriptProjectionService.loadProviderMessages(taskId)` 读取持久化 transcript 与最新 compaction epoch。

但迁移没有完全退出旧路径。`AgentLoopEngine` 仍创建并持续修改一份完整的 `List<Map<String, Object>> msgs`，并把它用于：

1. compaction 的 head/tail 选择；
2. Provider context overflow 恢复前后的 token 估算；
3. 交互恢复时识别待补写的 tool result；
4. Provider 未返回 usage 时的 prompt token 估算；
5. 一个已经没有生产调用点的 `streamFinalFromProvider(...)` 直接 Provider helper。

因此，Provider 的正常调用虽已收敛，但压缩、恢复和错误分支仍可能被进程内副本影响。该副本一旦与数据库投影漂移，正常请求与 overflow/compaction 会观察到不同上下文。

## 2. 本轮目标

1. `AgentLoopEngine` 不再持有跨迭代的可变 Provider transcript 列表；
2. 所有消息 append 只写 `AgentRunTranscriptService`；
3. compaction 每次从 `AgentTranscriptProjectionService` 读取当前 durable projection 后再选择 head/tail；
4. overflow 的 before/after token 估算与状态事件读取同一 durable projection；
5. interaction resume 只使用 compaction-aware durable resume projection；
6. 删除无调用的直接 Provider helper 和 `replaceProviderProjection(...)`；
7. 不改变 Provider message 协议、tool batch 顺序、审批恢复 task/epoch 或前端事件契约。

## 3. RED 验收

新增架构回归要求：

- 源码不存在 `List<Map<String, Object>> msgs = new ArrayList<>()`；
- compaction 不得执行 `CompactionSelection.select(msgs, ...)`；
- overflow 不得执行 `estimateProviderRequestTokens(..., msgs, ...)`；
- 不存在 `streamFinalFromProvider(...)` 和 `replaceProviderProjection(...)`；
- compaction 必须显式选择 `durableMessages`。

先只修改测试运行：

```powershell
cd D:\LabexAgent\backend
mvn -q '-Dtest=AgentLoopEngineDurableCompactionWiringTest' test
```

预期当前生产代码失败，证明测试捕获的就是仍存在的退役运行时 transcript，而非无关编译问题。

## 4. 计划

1. 取得 RED 证据；
2. 把 `appendProviderMessage(s)` 改成 durable-only writer；
3. 启动/恢复只保留一次性 durable snapshot，不建立跨迭代缓存；
4. compaction、overflow 和 usage fallback 改读 durable projection；
5. 删除本地投影替换与死 Provider helper；
6. 运行聚焦、后端全量、前端、系统和浏览器验收；
7. 更新本文件为完成记录并创建本地 Git 提交。

## 5. 退出条件

- `AgentLoopEngine` 不再存在可变运行时 transcript 事实副本；
- 下一次 Provider 请求、预算、压缩、overflow 恢复和 interaction resume 均可由 task + durable transcript + compaction epoch 重建；
- 多 tool call 的 `tool_call_id/name/arguments/result` 在压缩、审批恢复和重启后不丢失；
- 后端、前端、构建、真实系统和浏览器验收全部通过；
- 原有无关工作区改动保持未暂存、未提交。
## 6. RED 结果

执行聚焦测试时，新增架构回归按预期失败：

```text
Tests run: 2, Failures: 1, Errors: 0, Skipped: 0
AgentLoopEngineDurableCompactionWiringTest.removesTheRetiredMutableProviderTranscriptFromTheRunLoop
expected: <false> but was: <true>
```

首个失败断言直接命中 `List<Map<String, Object>> msgs = new ArrayList<>()`，证明旧运行时 transcript 确实仍存在。

## 7. 实施内容

### 7.1 durable-only append

`appendProviderMessage(...)` / `appendProviderMessages(...)` 删除目标内存列表参数。消息在复制并校验后只写 `AgentRunTranscriptService`，下一次读取统一经过 `AgentTranscriptProjectionService`。

### 7.2 启动与交互恢复

- 新任务先查询 durable projection；为空时写入初始上下文和用户请求；
- 恢复任务只保留一次性的 `persistedMessages` snapshot，用于识别当前交互所属的未闭合 tool batch；
- 交互结果写回原 task/epoch 后，后续模型请求重新从数据库投影，不保留跨迭代副本。

### 7.3 durable compaction source

新增 `selectDurableCompaction(taskId, ...)`：

1. 通过 `loadProviderMessages(taskId)` 读取最新 completed compaction epoch 与其后的 transcript；
2. 在这份协议已校验的 durable projection 上选择完整 head/tail；
3. compaction 完成只提交 `AgentCompactionRecord`，不再回写本地列表。

直接单测使用 mock durable projection 验证：旧 user turn 进入 compacted head，最近 user turn 进入 retained tail；API 不接收任何外部消息列表。

### 7.4 overflow 与 usage 估算

- Provider 未返回 usage 时，估算实际发给 Provider 的 `providerMessages`；
- overflow 恢复前、压缩后、工具 schema 缩减前后及 `CONTEXT_STATUS` 均重新读取同一 durable projection；
- 删除所有 `estimateProviderRequestTokens(..., msgs, ...)` 调用。

### 7.5 删除退役旁路

删除：

- `replaceProviderProjection(...)`；
- 无生产调用点、可直接接收任意消息列表调用 Provider 的 `streamFinalFromProvider(...)`；
- `AgentLoopEngine` 中全部跨迭代 `msgs` 读写。

删除死 helper 后，旧 `AgentLoopEngineStreamingContractTest` 暴露出一个假阳性：它依赖死 helper 中的 `sse.sendTransient("THINK_DELTA")`。测试已改为检查真正执行路径 `AgentModelTurnExecutor.EventSink.transientEvent(...)`。

### 7.6 架构规则同步

`AGENTS.md` 更新为当前强制契约：Provider 请求、预算、压缩选择和恢复都必须读取 durable projection，并明确禁止 `AgentLoopEngine` 重新维护跨迭代可变 transcript。

## 8. 修改文件

- `AGENTS.md`
- `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- `backend/src/test/java/com/labex/labexagent/runtime/AgentLoopEngineContextBudgetTest.java`
- `backend/src/test/java/com/labex/labexagent/runtime/AgentLoopEngineDurableCompactionWiringTest.java`
- `backend/src/test/java/com/labex/labexagent/runtime/AgentLoopEngineStreamingContractTest.java`
- `docs/iterations/2026-08-03-iteration-58-remove-runtime-transcript-cache.md`

## 9. GREEN 与真实验收

### 9.1 聚焦运行时回归

```powershell
cd D:\LabexAgent\backend
mvn -q '-Dtest=AgentLoopEngineStreamingContractTest,AgentLoopEngineParallelToolCallTest,AgentLoopEngineDurableCompactionWiringTest,AgentLoopEngineContextBudgetTest,AgentTranscriptProjectionServiceTest,AgentRunTranscriptServiceTest,AgentRunStateCharacterizationTest,AgentProviderProtocolCharacterizationTest,AgentInteractionContinuationIntegrationTest,AgentRunResumeSchedulerTest,CommandApprovalResumeSchedulerTest' test
```

结果：47 个测试通过。日志中的 `executor rejected` 是 `CommandApprovalResumeSchedulerTest` 主动注入的预期故障，测试结果为成功。

### 9.2 后端全量

```powershell
cd D:\LabexAgent\backend
mvn test
```

结果：

- tests：926；
- failures：0；
- errors：0；
- skipped：8；
- `BUILD SUCCESS`。

### 9.3 前端与构建

```powershell
cd D:\LabexAgent\frontend
npm test
npm run test:acceptance:unit
npm run build
```

结果：

- 前端：196/196 通过；
- acceptance unit：12/12 通过；
- Vite production build：通过；
- `CloudWorkspace`：1,463,644 / 1,500,000 bytes；
- `index`：1,259,534 / 1,300,000 bytes；
- `TerminalPanel`：380,367 / 400,000 bytes。

### 9.4 真实后端重启系统验收

```powershell
.\scripts\acceptance\run-all.ps1 -BackendPort 18088 -FrontendPort 13008 -CdpPort 19230 -TimeoutSeconds 180 -RestartBrowserBackend
```

后端运行 ID：`e66d776a5d134dbba7c78e675aca7cb6`。

通过项包括：

- question / permission / command approve / command reject restart；
- checkout contention；
- run Message/Part projection 与 tool Part authority；
- manual compaction；
- static context 和未配置 context window 阻断；
- completion evidence 与未验证改动拒绝；
- lifecycle transition audit；
- completion / failure / cancellation / model retry 权威投影；
- strict text tool fallback；
- native tool input gate；
- 隔离数据库与 cleanup。

### 9.5 真实 Chromium 与两次 JVM handoff

浏览器运行 ID：`b3489892f2f94467a875c7cf4787394b`。

关键结果：

- 桌面三栏布局：通过；
- 新旧会话隔离、刷新回放去重：通过；
- 推理协议标签隐藏：通过；
- 问题回复、审批刷新恢复、多工具审批批次：通过；
- durable compaction：epoch `1`，tokens `23744 -> 22024`，Provider messages `7`；
- interaction 等待期重启恢复：通过；
- compaction 后重启投影：通过；
- manual compaction fork 重启恢复：通过；
- Provider stream interruption 与 model retry live projection：通过；
- recoverable wait 无伪终态：通过；
- environment retry 保持同一 task：通过；
- console errors：0；
- network errors：0。

## 10. 保留的原工作区改动

以下内容不是本轮创建，保持未暂存、未提交：

- `backend/src/main/resources/application-acceptance.yml`；
- `.codex-tmp/`；
- `docs/agent-context-provider-smoke-test.md`；
- `docs/coding-agent-engineering-roadmap.md`；
- `docs/coding-agent-industrialization/`；
- 既有未跟踪的 `docs/superpowers/plans/` 与 `docs/superpowers/specs/`。

## 11. 结论与下一轮

本轮关闭了收敛计划任务四/任务八的关键遗留：`AgentLoopEngine` 已没有可变运行时 transcript，正常请求、压缩、overflow 和恢复使用同一 durable projection。

仍需继续审计的不是重新增加上下文缓存，而是：

1. 明确 conversation-level 手动 memory compaction 与 task-level Provider compaction 的作用域和 UI 命名，防止用户把两者误认为同一个事实；
2. 确认前端 `message.toolCalls` 只作为 durable Part reducer 的视图模型，不存在旧 API 覆盖权威 Part 的路径；
3. 在不读取用户密钥的前提下继续保留 fake Provider/故障注入证据；真实外部 Provider 只能在用户提供安全配置后单独验收。