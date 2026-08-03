# 第 62 轮：运行事件到持久化 Transcript 的可重试投影屏障

- 日期：2026-08-03
- 分支：`codex/agent-tool-reliability`
- 前置提交：`396aa37 fix: stabilize command approval recovery`
- 状态：已完成

## 1. 本轮目标

修复 `AgentRunEvent` 已经持久化并可通过 SSE/历史事件回放，但对应 `AgentRunMessage` / `AgentRunPart` 因瞬时数据库异常缺失的问题：

1. `AgentRunEvent + transactional outbox` 继续作为可重放事件事实；
2. `AgentRunMessage / AgentRunPart` 继续作为 transcript 与 UI Part 的权威事实；
3. 生命周期事务中的即时投影只作为低延迟优化，失败不得回滚权威生命周期事件；
4. outbox 在广播事件前，必须从权威 `AgentRunEvent` 幂等补投影 Message/Part；
5. 投影失败时不得广播、不得标记 published，必须沿现有 outbox 退避重试；
6. 重试、重复投递和“即时投影已成功”的正常路径都不能生成重复 Message/Part；
7. 静默 catch 改为包含 taskId、eventType、sequence 和异常堆栈的结构化日志；
8. 进程在 claim 后崩溃遗留的 `publishing` outbox 必须在租约到期后重新进入 pending；
9. 通过单元回归、真实数据库/进程级故障注入和整套浏览器验收证明修复，而不是只证明能编译。

## 2. 架构归属与不变量

| 事实/动作 | 唯一归属 |
|---|---|
| 生命周期状态、epoch、租约 | `AgentTask` + `AgentRunLifecycleService` |
| 可重放运行事件 | `AgentRunEvent` |
| 待发布/重试 | `AgentRunOutbox` |
| transcript Message/Part | `AgentRunMessage` + `AgentRunPart` |
| Message/Part 幂等投影 | `AgentRunPartService` / `AgentRunMessageService` |
| 发布前一致性屏障、过期 claim 回收 | `AgentRunOutboxPublisher` |
| 实时客户端投递 | `AgentRunOutboxSink` |

强制顺序：

```text
AgentRunEvent committed
  -> outbox claimed with lease
  -> load authoritative AgentRunEvent
  -> idempotently project AgentRunMessage/AgentRunPart
  -> publish sink/SSE
  -> mark outbox published
```

禁止顺序：

```text
outbox claimed -> publish SSE -> transcript projection best effort
```

否则用户可能先看到实时事件，但刷新后权威 transcript 缺失。

## 3. 根因与实际失败链

改造前的 `AgentRunLifecycleService.recordEventPartBestEffort(...)` 捕获并完全忽略 `RuntimeException`：

1. 生命周期事务写入 `t_agent_run_event`；
2. 同事务写入 `t_agent_run_outbox`；
3. 内联 `AgentRunPartService.recordEventPart(...)` 因瞬时异常失败；
4. catch 不记录 task/event/sequence，也没有持久化修复信号；
5. `AgentRunOutboxPublisher` 只调用 sink，然后直接把 outbox 标记 published；
6. SSE/事件历史仍存在，但 task API、刷新恢复、前端 Part reducer 和 Provider transcript 可能永久缺一段。

另一个同路径问题是 claim 没有租约：进程在 `pending -> publishing` 后崩溃时，没有代码把该记录重新放回 pending，事件可能永久不再发布。

这不是普通 UI 延迟，而是可重放事件和 transcript 权威模型之间失去可恢复的一致性。

## 4. 方案选择

### 4.1 采用：复用 transactional outbox 做发布前投影屏障

原因：

- outbox 已与事件同事务落库，不会丢失修复意图；
- 已有 claim、attempt、availableTime 和指数退避；
- `recordEventMessage` / `recordEventPart` 使用稳定 messageKey/partKey，支持重复执行；
- 无需新增 schema、队列、全局 Map 或第二套状态机；
- sink 只有在 transcript 已经 durable 后才看到事件，刷新投影与实时事件保持顺序。

实现中只信任 `AgentRunEvent.payload`、eventType、taskId 和 sequence，不使用可能独立漂移的 outbox envelope 重建 transcript。

### 4.2 采用：给 publishing claim 复用 availableTime 作为租约到期时间

claim 时把 `available_time` 更新为当前时间后 30 秒；每轮轮询先将租约已过期的 `publishing` 行恢复为 `pending`。因此即使：

- 进程在投影前崩溃；
- sink 调用期间崩溃；
- `scheduleRetry` 的数据库更新瞬时失败；

记录也不会永久卡死。重复投影和重复 sink 投递仍遵循既有 at-least-once 语义，由 sequence/event cursor 和稳定 Part key 去重。

### 4.3 不采用：把 Part 投影改成生命周期强原子写

Part 是派生投影。让其瞬时失败回滚状态迁移，会扩大核心状态机可用性风险，并可能使外部动作已经发生、生命周期事件却不存在。

### 4.4 不采用：新增 projection repair 表/后台任务

现有 outbox 已完整表达“未成功发布的事件”，再加一张表会产生双重重试、双重状态和新的竞态。

## 5. TDD 与回归场景

定向测试从旧构造器不支持权威 Event/Part 依赖开始 RED，最终覆盖 7 个场景：

1. 正常 outbox：先投影权威事件，再调用 sink，最后 published；
2. 投影失败：sink 不调用，outbox 回到 pending 并增加退避；
3. 下一次重试成功：同一事件再次投影后只发布一次；
4. eventId 不存在：不信任 outbox JSON 自行构造 transcript，不广播；
5. outbox.taskId 与 event.taskId 不一致：拒绝投递，避免跨 task 污染；
6. 权威 payload：投影参数来自 `AgentRunEvent.payload`；
7. 轮询开始时回收过期 `publishing` claim。

真实进程验收额外执行：在隔离 H2 中删除一个已发布 `RUN_STATE_COMPLETED` 对应的 Message/Part，把原 outbox 重新置为 pending，等待真实 `@Scheduled` publisher 重建，并校验 Message/Part 各只有一行且 messageId 关联正确。

## 6. 实施内容

### 6.1 后端

- `AgentRunOutboxPublisher`
  - 构造依赖增加 `AgentRunEventMapper` 和 `AgentRunPartService`；
  - 发布前按 eventId 读取权威事件；
  - 校验 outbox/event task 身份和事件必要字段；
  - 严格解析权威 JSON payload；
  - 先幂等调用 `recordEventPart`，再执行 sink；
  - 投影失败复用原 outbox 指数退避；
  - claim 写入 30 秒租约，并回收过期 publishing 记录。
- `AgentRunLifecycleService`
  - 保留不回滚生命周期事务的即时投影优化；
  - 删除静默 catch，记录可定位日志，并明确由 outbox 修复。

本轮没有新增数据库表、状态枚举、SSE 事件、API 字段或第二套 repair 状态机。

### 6.2 验收基础

- `agent-runtime.ps1`
  - 增加 `outboxTranscriptRepair` 证据；
  - 通过 H2 Shell 注入 transcript 缺失并恢复原 outbox；
  - SQL 子进程只在局部把 `ErrorActionPreference` 调整为 `Continue`，仍严格检查真实退出码；
  - 保持 UTF-8 BOM，兼容 Windows PowerShell 5。
- `runtime-config.test.mjs`
  - 把 UTF-8 BOM 守卫从浏览器脚本扩展到后端运行时验收脚本。

## 7. 验证过程中的真实失败

### 7.1 第一次系统验收：PowerShell 脚本 BOM 丢失

修改脚本时曾写成 UTF-8 无 BOM。Windows PowerShell 5 按本地代码页解析中文内容，导致脚本在第 23 行即出现连锁 ParserError。修复方式：

- 恢复 `EF BB BF`；
- 先加入失败回归测试；
- 使用真实 `powershell.exe` Parser 再验证。

### 7.2 第二次系统验收：Java 的普通 stderr 被 PS5 当成终止错误

H2 Shell 进程因环境中的 `JAVA_TOOL_OPTIONS` 向 stderr 输出提示；全局 `ErrorActionPreference=Stop` 将其包装成 `NativeCommandError`，虽然进程并未失败。修复方式：

- 只在 `Invoke-AcceptanceSql` 的进程调用范围临时使用 `Continue`；
- 立即恢复原 ErrorActionPreference；
- 以 `$LASTEXITCODE` 作为唯一成败依据；
- 第三次完整系统验收通过。

这些问题没有通过关闭验收或放宽业务断言绕过。

## 8. 最终验证

### 8.1 定向后端回归

```powershell
cd D:\LabexAgent\backend
mvn -Dtest=AgentRunOutboxPublisherTest test
```

结果：`7` tests，`0` failures，`0` errors。

### 8.2 后端全量

```powershell
cd D:\LabexAgent\backend
mvn test
```

结果：`938` tests，`0` failures，`0` errors，`8` skipped，`BUILD SUCCESS`。

### 8.3 前端全量与生产构建

```powershell
cd D:\LabexAgent\frontend
npm test
npm run build
```

结果：

- 前端全量：`209/209` 通过；
- 生产构建成功；
- `CloudWorkspace-D-M8hXon.js`：`1,462,276 / 1,500,000` bytes；
- `index-CRNg7UPw.js`：`1,259,608 / 1,300,000` bytes；
- `TerminalPanel-ChGYrHNr.js`：`380,367 / 400,000` bytes。

### 8.4 Windows PowerShell 5 编码/语法

- `agent-runtime.ps1` 首字节：`EF BB BF`；
- `runtime-config.test.mjs`：agent/browser 两份脚本 BOM 守卫通过；
- `powershell.exe` 原生 Parser：`Windows PowerShell 5 parse OK`。

### 8.5 完整系统验收

```powershell
cd D:\LabexAgent
.\scripts\acceptance\run-all.ps1 `
  -BackendPort 18118 `
  -FrontendPort 13038 `
  -CdpPort 19260 `
  -TimeoutSeconds 240 `
  -RestartBrowserBackend
```

结果：

- acceptance unit：`15/15`；
- backend acceptance runId：`81866e68003f4e22ae7a255d66e444d5`；
- browser acceptance runId：`dc4f557f451e4063a7c5c3b62d27cd2a`；
- `outboxTranscriptRepair: true`；
- `runMessagePartProjection: true`；
- `toolPartAuthority: true`；
- 审批批准/拒绝、用户问题、权限、workspace contention、取消、模型重试和 compaction 重启恢复全部为 true；
- `restartProjectionVerified: true`；
- `restartInteractionVerified: true`；
- `consoleErrors: 0`；
- `networkErrors: 0`；
- 最终：`package=true`、`acceptanceUnitTests=true`、`backendRuntime=true`、`browserRuntime=true`。

## 9. 文件范围

- `backend/src/main/java/com/labex/labexagent/run/AgentRunLifecycleService.java`
- `backend/src/main/java/com/labex/labexagent/run/AgentRunOutboxPublisher.java`
- `backend/src/test/java/com/labex/labexagent/run/AgentRunOutboxPublisherTest.java`
- `frontend/scripts/acceptance/runtime-config.test.mjs`
- `scripts/acceptance/agent-runtime.ps1`
- `docs/iterations/2026-08-03-iteration-62-transcript-projection-repair.md`

## 10. 原有工作区改动保护

以下原有内容保持原样，不暂存、不提交：

- `backend/src/main/resources/application-acceptance.yml`；
- `.codex-tmp/`；
- `docs/agent-context-provider-smoke-test.md`；
- `docs/coding-agent-engineering-roadmap.md`；
- `docs/coding-agent-industrialization/`；
- 未跟踪的 `docs/superpowers/plans/*` 与 `docs/superpowers/specs/`。

## 11. 后续候选

第 63 轮可继续审计 outbox 多实例发布顺序和投影积压可观测性：当前已保证不丢和最终修复，但尚未提供 pending/publishing oldest-age、repair attempts、连续失败事件等运维指标，也没有显式证明相同 availableTime 下跨实例仍按 task sequence 广播。