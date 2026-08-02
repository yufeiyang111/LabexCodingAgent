# Iteration 47：已解决交互的跨进程恢复协调器

## 状态

已完成（2026-08-02）

## 问题

Iteration 46 修复了同一 JVM 内用户回答与旧 execution lease 释放之间的竞态，但仍存在进程崩溃窗口：

1. `AgentRunInteractionService.respond` 已把回答提交为 `answered` / `approved` / `rejected`；
2. `AgentRunResumeScheduler` 尚未成功取得 `RUN_INTERACTION_RESUME_QUEUED` dispatch claim；
3. JVM 在两者之间退出；
4. 重启后的 `AgentRunRecoveryService` 只保留 `waiting_user` / `waiting_approval`，不会主动查询已解决 interaction；
5. 任务永久等待，除非用户重复请求或人工干预。

命令审批已有 `CommandApprovalResumeScheduler.resumeDeferred()` 数据库轮询，但普通 question / permission / network interaction 原先没有等价机制。

## 所有权约束

- `AgentRunInteraction` 是用户回答事实；
- `AgentTask` 是运行状态、epoch 和 execution lease 事实；
- `AgentRunLifecycleService.claimDispatch` 仍是 waiting 到 recovering 的唯一原子迁移；
- reconciler 只发现候选并调用现有 `AgentRunResumeScheduler`，不直接写 task status；
- 同一 task 只允许最新 interaction 参与恢复，禁止旧回答恢复新的等待轮次；
- 重复扫描继续依赖 interaction 级稳定幂等键，不能重复执行 continuation。

## 实现

### 数据库候选查询

`AgentRunInteractionMapper.selectResolvedAwaitingResume` 通过 `t_agent_run_interaction` 与 `t_agent_task` 联表，仅返回：

- task 为 `waiting_user`，且最新 interaction 是已 `answered` / `cancelled` 的 question；
- task 为 `waiting_approval`，且最新 interaction 是已 `approved` / `rejected` 的 permission 或 network 请求。

查询使用 `NOT EXISTS newer` 保证只选 task 的最新 interaction；`create_time` 使用 `COALESCE` 兼容历史空时间记录，并以 `interaction_id` 作为稳定次序兜底。查询有界为 1 到 500 条，本轮 scheduler 批次为 100。

### 恢复协调器

`AgentRunResumeScheduler` 增加每秒执行的 `reconcileScheduled()`：

1. 从数据库读取候选；
2. 对每个候选调用原有 `resumeIfWaiting()`；
3. 仍由 `claimInteractionResume()` 创建稳定的 `RUN_INTERACTION_RESUME_QUEUED` claim；
4. 多实例重复扫描时，数据库状态、lease 和幂等键只允许一个 continuation 获胜；
5. 单条候选异常会记录 `AGENT_INTERACTION_RECONCILE_FAILED`，不阻塞本批其他任务。

两参数构造器仅保留给已有聚焦测试；Spring 使用带 `AgentRunInteractionService` 的三参数构造器。

## RED

新增：

`AgentRunResumeSchedulerTest.reconcilesAPersistedResponseAfterProcessRestart`

它模拟进程重启后没有 HTTP reply 回调，只有数据库中的已回答 interaction。修复前测试编译失败，明确缺少：

- `findResolvedAwaitingResume(int)`；
- 带持久化 interaction service 的 scheduler 构造器；
- `resumeResolvedInteractions()`。

## GREEN 与数据库验证

### 聚焦回归

```powershell
cd D:\LabexAgent\backend
mvn '-Dtest=AgentRunInteractionMapperDatabaseTest,AgentRunResumeSchedulerTest,AgentRunInteractionServiceTest,AgentRunLifecycleServiceTest,AgentTaskServiceTest' test
```

结果：`42` tests，`0` failures，`0` errors，`0` skipped。

### 真实 SQL 方言测试

新增 `AgentRunInteractionMapperDatabaseTest`，使用 H2 的 MySQL 模式和真实 MyBatis mapper 执行注解 SQL，验证：

- 同一 task 只返回最新已解决 interaction；
- 新 interaction 仍为 waiting 时，不使用上一轮已解决记录；
- question 与 permission 只匹配对应 task 等待态；
- completed task 不会被恢复；
- 历史 `create_time = NULL` 不会绕过“最新 interaction”约束。

## 全量验证

### 后端

```powershell
cd D:\LabexAgent\backend
mvn test
```

最终结果：`873` tests，`0` failures，`0` errors，`8` skipped。

### 前端

```powershell
cd D:\LabexAgent\frontend
npm test
npm run build
```

结果：测试与生产构建通过；bundle 预算通过：

- `CloudWorkspace`：`1,459,986 / 1,500,000` bytes；
- `index`：`1,259,534 / 1,300,000` bytes；
- `TerminalPanel`：`380,367 / 400,000` bytes。

### 当前源码重新打包

```powershell
cd D:\LabexAgent\backend
mvn -DskipTests package
```

结果：成功；最终验收使用 `2026-08-03 00:23:35` 生成的当前 JAR。日期归档仍按本轮开始日期 `2026-08-02` 命名。

## 真实浏览器系统验收

```powershell
$env:JAVA_TOOL_OPTIONS='-Xms64m -Xmx512m -XX:ReservedCodeCacheSize=128m -XX:CICompilerCount=2 -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Dclient.encoding.override=UTF-8'
.\scripts\acceptance\browser-runtime.ps1 `
  -BackendPort 18080 `
  -FrontendPort 13000 `
  -CdpPort 19222 `
  -TimeoutSeconds 180 `
  -RestartBackendForAcceptance
```

结果：通过。

- run ID：`c0f04f2e2bfb4e649af764f6828e9b69`；
- 日志：`C:\Users\35475\AppData\Local\Temp\labex-agent-browser-runtime-177d2a873c1143c897e7b8b4efa6d4ac`；
- `restartInteractionVerified = true`；
- `restartProjectionVerified = true`；
- `restartManualForkVerified = true`；
- question reply、permission approval refresh、multi-tool batch 均通过；
- `<think>` / internal reasoning 仍不可见；
- console errors：`0`；
- network errors：`0`。

浏览器场景验证真实服务重启、前端投影和交互恢复集成；“respond 已提交但即时 callback 完全丢失”的精确故障窗口由真实 MyBatis/H2 候选查询与 scheduler 回归测试固定，不把普通浏览器成功路径误报成该故障注入本身。

最终 `18080`、`13000`、`19222` 端口均已释放。

## 结果

普通 question / permission / network interaction 现在和命令审批一样具备数据库驱动的恢复发现机制。用户回答不再依赖一次性 HTTP 调用栈或单个 JVM 内存 retry 才能继续；重启后的 scheduler 可以根据权威 interaction 与 task 状态重新发起同一个幂等 continuation claim。