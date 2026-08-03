# 第 64 轮：Compaction 取消与异常的即时终态化

- 日期：2026-08-03
- 分支：`codex/agent-tool-reliability`
- 前置提交：`3ce2ddc fix: recover interrupted compaction epochs`
- 状态：已完成

## 1. 本轮目标

在第 63 轮已经具备数据库 CAS 与启动恢复的基础上，补齐**同一 JVM 运行期间**的 compaction 终态边界：

1. 压缩开始后收到 task 取消，不得继续确定性 fallback；
2. 取消必须立即把当前 `AgentCompactionRecord` 从 `running` 推进到 `failed`，原因固定为 `Compaction cancelled`；
3. 取消必须中断当前压缩路径，由外层运行状态机把 task 推进到 `cancelled`；
4. `start()` 成功后的任意未预期异常必须在原进程内封口，不能依赖下次 JVM 启动恢复；
5. compaction 已经成功 CAS 为 `completed` 后，即使 SSE/只读投影失败，也不得把事实回退为 `failed`；
6. 终态封口失败不能覆盖原始异常，应作为 suppressed exception 保留；
7. 真实系统验收必须覆盖“自动压缩正在执行 -> 用户 interrupt -> task cancelled + compaction failed + 无 running 残留”。

## 2. 架构归属

| 事实/动作 | 唯一归属 |
|---|---|
| task 取消意图与 task 终态 | `AgentCancellationRegistry` / durable cancellation intent + `AgentRunLifecycleService` |
| compaction epoch 与终态 | `AgentCompactionRecord` + `AgentCompactionService` |
| 压缩执行期间的协调 | `AgentLoopEngine.compactContextWithFallback` |
| 压缩模型能力隔离 | `CompactionAgent` |
| UI/审计投影 | 持久化 task events + task projection API |

本轮不新增内存 compaction 状态、fallback registry、前端推断或第二套取消协议。

## 3. 已确认根因

当前流程是：

```text
start(running)
  -> CompactionAgent.compact(...)
  -> Result.failure("Compaction cancelled")
  -> COMPACTION_FAILED(model strategy)
  -> deterministic fallback
  -> complete(completed)  // 若 fallback 变短
```

因此用户已取消时，压缩记录仍可能 completed，主循环还会继续执行到后续检查。另一个缺陷是 `start()` 之后没有统一 `try/catch`，Provider、token 估算、SSE 或其他运行时异常都可能直接逃逸，记录保持 running，直到下次启动才被第 63 轮恢复逻辑清理。

## 4. 设计

### 4.1 取消优先于 fallback

模型压缩返回后同时检查 durable cancellation token：

- token 已取消，或返回原因为 `Compaction cancelled`：
  1. 以 `Compaction cancelled` 调用同一 `AgentCompactionService.fail` CAS；
  2. 投影 `COMPACTION_FAILED` 的 cancellation 详情；
  3. 抛出 `InterruptedException`；
  4. 禁止进入 deterministic fallback。

外层循环沿用现有取消终态逻辑，不在 compaction 内直接写 `AgentTask.status`。

### 4.2 统一异常封口

从 `start()` 返回持久化记录后开始设置终态保护：

```text
try {
  执行压缩、终态 CAS、事件投影
} catch (Exception original) {
  if (record.status == running) {
    fail(record, safe structured reason)
  }
  if (fail itself throws) {
    original.addSuppressed(finalizationFailure)
  }
  throw original
}
```

只在调用方记录仍为 `running` 时尝试 fail。`AgentCompactionService.complete/fail` 成功后会同步更新该对象，因此 completed/failed 事实不会因为后续 SSE 失败而被回退。

### 4.3 失败原因

- 取消：固定 `Compaction cancelled`；
- 其他异常：记录异常类型和经过边界清洗、限长后的简短消息；
- 不写入 stack trace、请求内容、认证头或 Provider 密钥。

## 5. RED 场景

1. compaction 返回 cancelled 且 token 已取消：当前实现会进入 fallback，测试应失败；
2. compaction 调用抛出运行时异常：当前实现不会 `fail(record, ...)`，测试应失败；
3. completed CAS 后 SSE 投影失败：终态保护不得调用 fail；
4. 系统验收：真实 Spring Boot + H2 + SSE 流中触发 compaction hold，调用 interrupt 后断言 task=`cancelled`、compaction=`failed`、无 `running` 残留。

## 6. 预计修改范围

- `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- `backend/src/main/java/com/labex/labexagent/llm/AcceptanceScriptedProvider.java`
- `backend/src/test/java/com/labex/labexagent/runtime/AgentLoopEngineCompactionTerminalityTest.java`
- `backend/src/test/java/com/labex/labexagent/llm/AcceptanceScriptedProviderTest.java`
- `scripts/acceptance/agent-runtime.ps1`
- 本文档

## 7. 验收门槛

- 新增 RED/GREEN 回归测试；
- 相关后端测试；
- 后端全量测试；
- 前端现有 209 项测试；
- 前端生产构建和既有 bundle budget；
- 独立 runtime acceptance；
- `run-all.ps1` 全链路验收；
- 确认没有修改或提交原有用户工作区改动。

## 8. 实施记录

### 8.1 生产运行时

`AgentLoopEngine.compactContextWithFallback` 现在以一次持久化 compaction record 为边界维护局部终态保护：

1. `AgentCompactionService.start` 返回后，所有压缩、fallback、终态 CAS 和事件投影都进入统一 `try/catch`；
2. 压缩模型返回后先检查 cancellation token 和 `Compaction cancelled` 结果；
3. 取消时先将 record CAS 为 failed，再以 `COMPACTION_FAILED` 投影 cancellation 详情，随后抛出 `InterruptedException`；
4. cancellation 的失败事件如果投影失败，只作为 suppressed exception 附加，不能替代取消信号；
5. 模型 checkpoint 或 deterministic fallback 完成 CAS 后设置本地 terminal guard，后续 SSE/事件投影失败不会把 completed 回退为 failed；
6. 未预期异常会立即将仍为 running 的 record CAS 为 failed，并发送 `COMPACTION_FAILED/execution_error`；
7. compaction fail 自身异常通过 `addSuppressed` 保留，原始 Provider/运行时异常仍是主异常；
8. 异常原因经过 `CommandRedactor`、换行清洗和 240 字符限长后才进入数据库与事件。

本轮没有在 compaction 内直接写 `AgentTask.status`。task 的 cancelled/failed 终态仍由外层 `AgentRunLifecycleService` 负责。

### 8.2 Acceptance fault injection

`AcceptanceScriptedProvider` 新增 acceptance-profile-only 标记 `[acceptance:compaction-cancel]`：

- 仅当请求确实是隔离的 compaction Provider 调用时触发；
- 通过 `labex.acceptance.compaction.hold.ms` 做最大 15 秒的有界暂挂；
- 不访问网络、不读取密钥、不绕过正式 task、transcript、compaction、SSE 或 interrupt 路径。

`scripts/acceptance/agent-runtime.ps1` 新增：

1. `Wait-TaskCompactionState`，从 task projection 轮询真实 `compactions`；
2. 自动压缩开始后调用正式 `/agent/interrupt`；
3. 断言 task 最终为 cancelled；
4. 断言恰有一个 `failureReason=Compaction cancelled` 的 failed compaction；
5. 断言没有 running/completed compaction 残留；
6. 断言 durable events 中恰有一个 `COMPACTION_FAILED`、零个 `COMPACTION_COMPLETED`、一个 `RUN_CANCELLED`；
7. 新增证据字段 `compactionCancellationTerminal`。

### 8.3 回归测试

新增 `AgentLoopEngineCompactionTerminalityTest` 四个场景：

- cancellation 立即 failed，禁止 fallback/complete；
- 未预期异常立即 failed，并实时投影失败事件；
- finalization 失败作为 suppressed exception，不能覆盖原始异常；
- completed CAS 之后投影失败，不得回退 compaction 终态。

`AcceptanceScriptedProviderTest` 增加有界 compaction hold 的 profile fixture 覆盖。

## 9. 验收结果

### 9.1 RED 证据

首次只加入 3 个行为测试、尚未修改生产实现时：

- cancellation 场景预期抛出 `InterruptedException`，旧实现却无异常并继续 fallback；
- 未预期异常场景要求 `AgentCompactionService.fail`，旧实现没有调用；
- 结果：3 tests 中 2 failures，准确复现两个根因。

### 9.2 自动化验证

| 命令 | 结果 |
|---|---|
| `mvn -Dtest=AgentLoopEngineCompactionTerminalityTest test` | 4 项通过 |
| compaction/runtime 相关 6 个测试类 + acceptance Provider 测试 | 50 项通过，0 failure/error |
| `mvn test` | 950 项，0 failure/error，8 skipped |
| `npm test` | 209 项通过 |
| `npm run build` | 通过；`CloudWorkspace` 1,462,276 / 1,500,000 bytes，`index` 1,259,608 / 1,300,000 bytes，`TerminalPanel` 380,367 / 400,000 bytes |
| Windows PowerShell parser + UTF-8 BOM | 通过 |
| `git diff --check` | 通过 |

### 9.3 真实运行时验收

1. 独立 runtime acceptance：`runId=d7b74174bee14602a5f4664afc01381c`，`compactionCancellationTerminal=true`，并保留既有 restart/approval/transcript/compaction gates；
2. 完整 `run-all.ps1` 后端：`runId=a1ff8837845e4cceac0efdbe4a52aee8`，`compactionCancellationTerminal=true`、`compactionEpochRecovery=true`、`outboxTranscriptRepair=true`，所有证据字段通过；
3. 完整浏览器：`runId=c3fc8cfd0f7047deb14c2a4279f3507c`，`durableCompaction=true`、刷新/重启/审批/提问恢复通过，`consoleErrors=0`、`networkErrors=0`；
4. `run-all.ps1` 最终 `package / acceptanceUnitTests / backendRuntime / browserRuntime` 四阶段全部为 `true`。

### 9.4 已知边界

- 本轮没有连接生产 MySQL 或外部云 Provider；系统验收使用隔离 H2 和 acceptance Provider，但 task、SSE、transcript、compaction、interrupt 和生命周期服务均走生产代码路径；
- `chatWithTools` 协议本身没有 cancellation token，因此当前修复保证 Provider 返回后立即正确收口，不能中断一个永不返回的底层 HTTP 调用；Provider transport 的可取消请求/硬超时仍是后续独立可靠性工作；
- compaction 已持久化终态后若实时事件投影失败，当前连接可能暂时看不到最后一条 compaction 事件，但刷新/重连可以从数据库 task projection 与持久化事件恢复，不会改变权威终态。

## 10. 原有工作区改动保护

本轮未修改、未暂存以下原有内容：

- `backend/src/main/resources/application-acceptance.yml`；
- `.codex-tmp/`；
- `docs/agent-context-provider-smoke-test.md`；
- `docs/coding-agent-engineering-roadmap.md`；
- `docs/coding-agent-industrialization/`；
- 原有未跟踪 `docs/superpowers/plans/*` 与 `docs/superpowers/specs/`。

## 11. 本轮文件

- `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- `backend/src/main/java/com/labex/labexagent/llm/AcceptanceScriptedProvider.java`
- `backend/src/test/java/com/labex/labexagent/runtime/AgentLoopEngineCompactionTerminalityTest.java`
- `backend/src/test/java/com/labex/labexagent/llm/AcceptanceScriptedProviderTest.java`
- `scripts/acceptance/agent-runtime.ps1`
- `docs/iterations/2026-08-03-iteration-64-compaction-cancellation-terminality.md`
