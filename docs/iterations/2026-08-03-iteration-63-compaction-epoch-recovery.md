# 第 63 轮：Compaction Epoch 终态原子性与重启封口

- 日期：2026-08-03
- 分支：`codex/agent-tool-reliability`
- 前置提交：`82059c8 fix: repair durable transcript projections`
- 状态：已完成

## 1. 本轮目标

修复自动上下文压缩记录可能出现“内存已完成、事件已发送、数据库仍 running”的伪完成，以及 JVM/worker 中断后 running compaction epoch 永久悬挂的问题：

1. `AgentCompactionRecord.status` 的终态切换必须是数据库条件更新，不得先改内存再忽略更新结果；
2. `start` 必须确认 INSERT 成功并取得 `compactionId`；
3. `complete` / `fail` 只能把相同 record 的 `running` 状态推进到终态；
4. 相同结果的重复 complete/fail 必须幂等，不同结果或不同终态必须明确冲突；
5. `COMPACTION_COMPLETED` 只能在 completed 状态真实持久化之后发送；
6. 启动恢复必须分页扫描 running compaction，只有 task 的相同 execution epoch 仍持有有效 lease 时才能保留；
7. task 缺失、已终态、lease 失效或 execution epoch 已变化时，running compaction 必须标记 failed；
8. 更新失败不能覆盖上一条 completed compaction；Provider projector 仍读取最新 completed epoch；
9. 真实系统验收要覆盖“压缩已完成后人为插入更高 running epoch -> JVM 重启 -> running 变 failed -> 原 waiting task 继续使用上一 completed epoch 恢复”。

## 2. 架构归属

| 事实/动作 | 唯一归属 |
|---|---|
| compaction epoch、head/tail、summary、状态 | `AgentCompactionRecord` |
| compaction 状态迁移和 Provider 投影选择 | `AgentCompactionService` |
| task execution epoch/lease | `AgentTask` + `AgentRunExecutionLeaseService` |
| 启动期跨事实协调 | `AgentRunRecoveryService` |
| Provider transcript 重建 | `AgentTranscriptProjectionService` |
| UI/审计视图 | task API 的 `compactions` 只读投影 |

本轮不新增第二套 summary、内存 epoch、恢复 Map 或前端猜测逻辑。

## 3. 根因

当前实现有三个确定性缺陷：

1. `start()` 调用 `mapper.insert(record)` 后不检查返回值和自动生成主键；
2. `complete()` 先把传入对象改成 completed，再调用 `updateById`，但不检查是否更新到数据库；
3. `fail()` 同样忽略更新结果；启动恢复也从不处理 `t_agent_compaction_record.status='running'`。

因此可能发生：

```text
record.status = completed in memory
  -> UPDATE affected rows = 0
  -> method returns success
  -> COMPACTION_COMPLETED is sent
  -> task continues
  -> DB row remains running
  -> JVM restart
  -> latestCompleted() cannot see the new summary
```

旧 worker 在 lease 失效后继续 finalization 时也没有 CAS 防线，可能覆盖新恢复流程的判断。

## 4. 设计

### 4.1 Compaction 终态 CAS

`complete` / `fail` 使用：

```text
WHERE compaction_id = ?
  AND task_id = ?
  AND compaction_epoch = ?
  AND status = 'running'
```

更新成功后才修改调用方持有的实体。更新 0 行时读取数据库：

- 已经是完全相同的 completed/failed 结果：幂等成功；
- 已是其他终态或内容不同：抛出状态冲突；
- 记录不存在：抛出持久化缺失；
- 仍是 running：抛出未能持久化终态。

### 4.2 启动恢复

`AgentCompactionService` 提供按 `compactionId` 游标分页读取 running 记录；`AgentRunRecoveryService` 在普通 task 恢复前逐条判断：

- task 存在；
- task lease 仍有效；
- task.executionEpoch 与 record.executionEpoch 相同；
- task 不是终态。

只有全部满足才保留 running；否则通过同一 fail CAS 封口。这样不会把其他实例仍在执行的有效压缩误杀，也不会让旧 epoch 永久悬挂。

### 4.3 Provider 投影

不改变 `latestCompleted()` 语义。较新的 failed/running 记录只是审计事实，不能遮蔽较旧但有效的 completed epoch。

## 5. RED 场景

1. INSERT 返回 0 或没有主键时，start 明确失败；
2. complete 更新 0 行且数据库仍 running 时，不修改内存对象并抛错；
3. 相同 completed 结果重复提交幂等成功；
4. fail 使用 running CAS，重复相同失败幂等；
5. 恢复扫描会封口无有效 matching lease 的 running epoch；
6. 相同 execution epoch 且 lease 有效时不封口；
7. 最新记录 failed 时，Provider projection 仍使用更早 completed epoch；
8. 隔离 H2 + 真实 JVM 重启验证 waiting task 能继续，而 synthetic running epoch 变 failed。

## 6. 预计修改范围

- `backend/src/main/java/com/labex/labexagent/context/AgentCompactionService.java`
- `backend/src/main/java/com/labex/labexagent/run/AgentRunRecoveryService.java`
- `backend/src/main/java/com/labex/labexagent/llm/AcceptanceScriptedProvider.java`
- `backend/src/test/java/com/labex/labexagent/context/AgentCompactionServiceTest.java`
- `backend/src/test/java/com/labex/labexagent/run/AgentRunRecoveryServiceTest.java`
- `backend/src/test/java/com/labex/labexagent/llm/AcceptanceScriptedProviderTest.java`
- `scripts/acceptance/agent-runtime.ps1`
- 本文档

## 7. 验证计划

```powershell
cd D:\LabexAgent\backend
mvn -Dtest=AgentCompactionServiceTest,AgentRunRecoveryServiceTest,AcceptanceScriptedProviderTest test
mvn test

cd D:\LabexAgent\frontend
npm test
npm run build

cd D:\LabexAgent
.\scripts\acceptance\run-all.ps1 -BackendPort <free> -FrontendPort <free> -CdpPort <free> -TimeoutSeconds 240 -RestartBrowserBackend
```

## 8. 实际实现

1. `AgentCompactionService.start()` 只有在 INSERT 影响 1 行且获得 `compactionId` 后才返回；
2. `complete()` / `fail()` 改为 `compaction_id + task_id + compaction_epoch + status=running` 的数据库 CAS；
3. CAS 成功后才更新调用方持有的内存对象；CAS miss 时重读数据库，仅允许完全相同的终态幂等返回；
4. 新增按 `compactionId` 游标分页的 running record 扫描；
5. `AgentRunRecoveryService` 在普通 task 恢复前先封口失去 matching execution lease 的 running compaction；
6. 相同 execution epoch 且 lease 仍活跃的记录保留，避免误杀其他实例的合法压缩；
7. acceptance-only Provider 新增“压缩完成后第二次提问”的非生产场景，为真实 JVM 重启提供确定性 waiting checkpoint；
8. 验收脚本会注入更高的 synthetic running epoch，重启后校验它变为 failed，原 completed epoch 仍是 Provider 恢复依据。

## 9. 验收结果

### 9.1 回归与构建

| 验证 | 结果 |
|---|---|
| `mvn -Dtest=AgentCompactionServiceTest,AgentRunRecoveryServiceTest,AcceptanceScriptedProviderTest test` | 36 项通过，0 failure/error |
| `mvn test` | 945 项，0 failure/error，8 skipped |
| `npm test` | 209 项通过 |
| `npm run build` | 通过；`CloudWorkspace` 1,462,276 / 1,500,000 bytes，`index` 1,259,608 / 1,300,000 bytes，`TerminalPanel` 380,367 / 400,000 bytes |
| Windows PowerShell parser + UTF-8 BOM | 通过 |

### 9.2 真实运行时验收

1. 单独后端重启验收：`runId=a728921e68344518901d1dcbf4ce0f8e`，`compactionEpochRecovery=true`；
2. 完整 `run-all.ps1` 后端运行时：`runId=29d928750a9c41049234a20026e6ba60`，包括 `compactionEpochRecovery=true`、`outboxTranscriptRepair=true`、审批/提问重启恢复和清理全部通过；
3. 完整浏览器验收：`runId=f1f037db3450401d82fc71c01cfcec87`，`durableCompaction=true`、`restartInteractionVerified=true`、`restartProjectionVerified=true`，`consoleErrors=0`、`networkErrors=0`；
4. `run-all.ps1` 最终四阶段 `package / acceptanceUnitTests / backendRuntime / browserRuntime` 均为 `true`。

### 9.3 验收过程中发现并修复的问题

首次执行真实验收时，`Wait-TaskPendingInteraction` 在 task 仍为 running、API 尚未返回 `pendingInteraction` 字段时，被 PowerShell `Set-StrictMode` 拦截。脚本已改为通过 `PSObject.Properties` 安全检查可选字段，随后独立后端验收和完整系统验收均通过。

### 9.4 剩余边界

- 本轮真实启动了打包 JAR、隔离 H2、SSE 和浏览器，不是 mock-only 验收；
- 未连接生产 MySQL 或外部云 Provider；本轮修复不依赖外网，Provider 分支仅存在 `acceptance` profile；
- 没有修改前端 reducer 或生产 Provider 协议，前端回归与浏览器验收用于确认无回归。

## 10. 原有工作区改动保护

以下原有内容不修改、不暂存、不提交：

- `backend/src/main/resources/application-acceptance.yml`；
- `.codex-tmp/`；
- `docs/agent-context-provider-smoke-test.md`；
- `docs/coding-agent-engineering-roadmap.md`；
- `docs/coding-agent-industrialization/`；
- 未跟踪的 `docs/superpowers/plans/*` 与 `docs/superpowers/specs/`。