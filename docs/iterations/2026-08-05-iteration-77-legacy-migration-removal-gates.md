# 第 77 轮：legacy migration 读取遥测与删除门槛

- 日期：2026-08-05
- 分支：`codex/agent-tool-reliability`
- 前置提交：`2453835 fix: converge durable memory and WSL cancellation`
- 计划来源：`docs/superpowers/plans/2026-07-29-agent-runtime-state-convergence.md` 第 12、13 节

## 1. 问题基线

第 76 轮已经证明 Provider、UI history、memory stats 和显式 task fork 不再把旧 `t_agent_message` 或文件 checkpoint 当作常规事实源，但两个兼容 reader 仍缺少可删除证据：

1. `AgentLegacyConversationHistoryMigrationService` 只有逐会话 `history_projection_version` / `history_migrated_at`，没有全局 pending inventory、读取命中和无命中观察窗口。
2. `AgentLoopEngine` 在每次 resumed run 都先调用 `AgentCheckpointStore.loadLegacy(...)`，即使 Tool Part、`RUN_PROGRESS_MIGRATED` 或 durable plan 已足以恢复，仍会重复读取已经失去权威的 v1/v2 文件。
3. 当前没有一个受权限保护的报告能够回答：还有多少未迁移来源、reader 最近何时命中、零存量从何时开始、最早哪个版本可以删除兼容代码。

## 2. 本轮架构决策

### 2.1 不新增第二套 transcript 或运行状态

- `AgentTask`、`AgentRunEvent`、`AgentRunMessage`、`AgentRunPart` 继续是运行时权威事实。
- 新增 migration gate 只保存兼容 reader 的运维证据：命中次数、命中条目数、最近命中、pending inventory、零存量起点和目标删除版本。
- gate 不参与 Provider prompt、任务恢复、审批恢复、SSE reducer 或前端消息渲染。

### 2.2 checkpoint 只允许一次检查

- resumed task 首次进入兼容检查时，读取一次 v1/v2 checkpoint。
- plan/progress seed 必须先迁移到现有 durable plan/event 事实源。
- 完成检查后写入稳定幂等键的 `LEGACY_CHECKPOINT_INSPECTED` durable event；后续恢复检测到该事件时不得再次访问文件 reader。
- 文件不存在也写入 `sourcePresent=false` 的 inspection event，避免每次恢复重复探测同一路径。
- 损坏文件不能写成功 marker；任务必须明确失败并允许修复后重试。

### 2.3 删除门槛

reader 只有同时满足以下条件才可报告 `readyForRemoval=true`：

1. 当前 pending source 数为 0；
2. 已建立 `zeroInventorySince`；
3. 自最近一次 reader hit 起没有重新命中；任何新 hit 都必须重置零存量观察期；
4. 零存量持续达到配置的观察窗口，默认 14 天；
5. 已配置目标删除版本，默认 `1.1.0`。

物理保留的旧消息行或 checkpoint 文件可以作为归档存在，但只有已经具备 durable marker 的来源才属于 covered/retired inventory；未检查、损坏或无法归属的来源必须继续阻断删除。

### 2.4 报告权限

- 新增只读管理员 API，使用 Spring Security `@PreAuthorize("hasRole('ADMIN')")`。
- 普通用户不得读取全局项目、会话或 workspace 迁移统计。
- API 只返回聚合计数和门槛，不返回用户消息、checkpoint 内容、路径或凭据。

## 3. 计划修改

1. 新增 `t_agent_legacy_migration_gate`、entity 和 mapper。
2. 新增 `AgentLegacyMigrationGateService`，以事务方式记录 hit 和 inventory，并计算删除门槛。
3. 新增 `AgentLegacyCheckpointMigrationService`，从 `AgentLoopEngine` 接管旧 checkpoint 的一次性检查、durable 迁移和 marker。
4. 扩展 `AgentCheckpointStore` 的只读 inventory scanner，不跟随符号链接并对扫描数量设上限。
5. 新增 `AgentLegacyMigrationReadinessService` 和 ADMIN 只读 controller。
6. 更新 history migration，在真实读取旧表时记录 gate hit；已是 durable-v1 时不能产生 hit。
7. 更新 schema、DTO/mapper、定向测试、全量测试和 acceptance。

## 4. 验收场景

### 4.1 后端回归

- history 第一次迁移只记录一次 reader hit，重复 history 请求不再命中旧表；
- checkpoint 第一次 resumed run 读取并迁移，写入 `LEGACY_CHECKPOINT_INSPECTED`；
- 同一 task 第二次恢复不调用 `AgentCheckpointStore.loadLegacy(...)`；
- checkpoint 不存在也只检查一次；
- 损坏/未检查来源阻断 readiness；
- pending 从正数变为 0 时建立零存量时间；新 hit 重置该时间；
- 观察窗口未满时不得提前报告可删除；
- 普通用户访问全局报告返回 403，ADMIN 返回聚合报告。

### 4.2 真实系统验收

- H2/MySQL 模式实际创建并更新 gate 表；
- 启动真实 Spring JVM，验证 ADMIN API、普通用户拒绝、history migration hit 和 checkpoint inspection marker；
- 重启后再次恢复同一 task，证明 checkpoint hit count 不增加；
- 继续运行既有审批、SSE、compaction、fork、plan/progress 和浏览器刷新门槛，确保 migration event 不形成新的前端事实源。

## 5. 停止边界

本轮不删除 `t_agent_message`、旧消息行、checkpoint 文件、`AgentMessage` entity/mapper、`AgentLegacyConversationHistoryMigrationService` 或 `AgentCheckpointStore`。只有报告经过真实运行观察并达到默认 14 天零存量窗口后，后续版本才允许执行物理删除。本轮也不进行 UI 改版、AgentLoopEngine 整体重写或真实云 Provider 凭据配置。
## 6. 实际实施

### 6.1 迁移门槛持久化

- 新增 `t_agent_legacy_migration_gate`、`AgentLegacyMigrationGate` 和显式 SQL mapper，按 reader 保存目标删除版本、观察窗口、读取命中、来源条目命中、最近命中、pending inventory、最近盘点和零存量起点。
- `AgentLegacyMigrationGateService` 以事务方式更新 gate；任何真实 reader hit 都会清空 `zeroInventorySince`，pending 从正数变为 0 时才开始新的连续观察窗口。
- mapper 使用显式 `updateGate(...)`，确保 `zero_inventory_since` 可以真正更新为 SQL `NULL`，不受 MyBatis-Plus 默认忽略 null 字段影响。

### 6.2 history 与 checkpoint 兼容 reader 收口

- history migration 只在真实读取旧消息并生成 durable history 时记录一次命中；已经完成 `durable-task-history-v1` 投影的重复读取不会继续累计 hit。
- 新增 `AgentLegacyCheckpointMigrationService`：首次 resumed inspection 才读取 v1/v2 文件，完成后写稳定幂等 `LEGACY_CHECKPOINT_INSPECTED` 事件；文件不存在也写 `sourcePresent=false` marker，后续恢复直接跳过旧 reader。
- 损坏、身份不匹配或无法安全解析的 checkpoint 按失败关闭处理，不写成功 marker，保留修复后重新检查的能力。
- `AgentCheckpointStore` 保持只读，并增加规范路径校验、身份校验、最大 1 MiB 文件限制、最大两层目录、最多 10,000 个条目和不跟随符号链接的 inventory 扫描边界。

### 6.3 删除准备度与权限边界

- 新增 `AgentLegacyMigrationReadinessService`，批量读取 task 并校验 checkpoint 的 student/project/conversation/task 归属；未归属、归属不匹配、损坏或未检查来源都会阻断删除。
- 新增 `GET /admin/agent/runtime/legacy-migration-readiness`，controller 以 `hasRole('ADMIN')` 限制，只返回聚合门槛，不返回消息内容、路径或凭据。
- 默认目标删除版本为 `1.1.0`，默认连续观察窗口为 14 天，可通过 `LABEX_AGENT_LEGACY_REMOVAL_VERSION` 与 `LABEX_AGENT_LEGACY_OBSERVATION_WINDOW_DAYS` 配置。

### 6.4 系统验收中发现并修复的前端终态投影缺陷

真实浏览器验收第一次运行时发现：Provider 流已经返回部分正文、随后有限重试失败时，timeline 虽记录 `message.error`，但因为正文非空而没有把终态错误渲染给用户，页面只停留在截断正文。该问题不是扩大本轮功能范围，而是本轮 durable marker 接入后的系统验收阻断项。

- 新增 `agentErrorProjection.js`，把部分正文与 `错误：<message>` 组合为幂等的用户可见终态。
- live timeline 与 history replay 共用该投影，避免实时界面和刷新回放行为分叉。
- 增加实时与历史回放回归测试，并更新源码契约测试。

### 6.5 验收脚本可靠性

- `agent-runtime.ps1` 增加 history/checkpoint hit-once、JVM 重启、普通用户 403 和 ADMIN 报告验证。
- 修复 checkpoint 测试路径中隐藏 ASCII 控制字符风险，并增加脚本字符完整性回归。
- 将命令恢复中的 lease 过期断言改为校验 H2 实际 `Update count: 1`，避免第二次查询与后台续租发生竞态。
- 浏览器验收验证内部 `LEGACY_CHECKPOINT_INSPECTED` marker 不作为消息文本显示，并验证部分 Provider 输出后的终态错误无需刷新即可看到。

## 7. 回归覆盖

新增或更新的关键回归覆盖包括：

- history reader 只在首次真实迁移时命中；
- checkpoint 存在、不存在、损坏、身份不匹配和重复恢复；
- inspection marker 的幂等键、事件归属和重启后跳过旧 reader；
- gate 的零存量起点、命中后重置、观察窗口和 SQL null 更新；
- readiness 的 pending、covered、invalid、unowned、truncated inventory；
- ADMIN/USER 权限边界；
- live/history 在部分正文后持久显示 Provider 终态错误；
- acceptance PowerShell/JavaScript 脚本不包含隐藏 ASCII 控制字符。

## 8. 验证结果

### 8.1 后端测试

定向测试：

```powershell
cd D:\LabexAgent\backend
mvn -q "-Dtest=AgentCheckpointStoreTest,AgentLegacyCheckpointMigrationServiceTest,AgentLegacyMigrationReadinessServiceTest,AgentLegacyMigrationGateServiceTest,AgentLegacyMigrationGateMapperDatabaseTest,AgentLegacyConversationHistoryMigrationServiceTest,AgentRunSchemaTest,AgentLoopEnginePolicyContractTest" test
```

结果：退出码 0。

完整后端测试：

```powershell
cd D:\LabexAgent\backend
mvn test
```

结果：`1043` 项测试，`0` failure，`0` error，`9` skipped，`BUILD SUCCESS`。

### 8.2 前端测试与构建

```powershell
cd D:\LabexAgent\frontend
npm.cmd test
npm.cmd run test:acceptance:unit
npm.cmd run build
```

结果：

- 前端全量测试：`215/215` 通过；
- acceptance 单元测试：`16/16` 通过；
- Vite 生产构建通过，三个受预算约束的主 chunk 均未超限。

### 8.3 真实后端系统验收

```powershell
cd D:\LabexAgent
.\scripts\acceptance\agent-runtime.ps1 -TimeoutSeconds 180
```

结果：runId `f1685da75d9a42d9bd9f366f6b19b073` 通过。真实 Spring JVM、H2、HTTP、SSE 和重启链路证明：

- `legacyHistoryReaderHitOnce=true`；
- `legacyCheckpointInspectionRestart=true`；
- `legacyMigrationUserForbidden=true`；
- `legacyMigrationAdminReport=true`；
- 重复恢复和 JVM 重启后 checkpoint reader hit 不增长；
- 既有审批、提问、compaction、fork、plan/progress、completion evidence 和清理门槛继续通过。

### 8.4 真实浏览器验收

```powershell
cd D:\LabexAgent
.\scripts\acceptance\browser-runtime.ps1 -TimeoutSeconds 180
```

结果：浏览器 runId `af6451e436cb4d66bf4fda72b0006719` 通过：

- `legacyCheckpointInspectionHidden=true`；
- `providerStreamInterruptionHandled=true`；
- `modelRetryLiveProjection=true`；
- `consoleErrors=0`；
- `networkErrors=0`；
- 默认浏览器场景中的会话隔离、刷新回放、审批/提问、durable history、compaction、fork 和终态证据均通过。

本次浏览器命令未启用 `RestartBackendForAcceptance`，所以浏览器报告中的三个 restart 专用字段为 `false`；后端系统验收已独立覆盖 JVM 重启后的 checkpoint、interaction、plan/progress 和 replay 边界。

## 9. 完成状态与停止边界

本轮代码、回归测试、完整构建和自动化系统验收已经完成。按照既定边界，本轮到此停止，不继续删除兼容 reader 或扩大重构范围。

仍然保留的客观边界：

1. 默认 14 天连续零存量观察窗口必须由真实运行时间形成，不能用测试或手工改时间伪造；因此本轮不会物理删除 `t_agent_message`、旧 checkpoint 文件或两个兼容 reader。
2. deterministic acceptance 使用本地 scripted Provider；没有读取或配置真实云 Provider 凭据，外部代理、TLS 和真实上游流中断仍需独立 smoke。
3. 自动化浏览器门槛已经通过，但最终用户体验仍由用户在自己的常用浏览器和现有数据上手工验收。
