# 第 13 轮：持久化恢复领取与执行租约交接

- 日期：2026-07-31
- 分支：`codex/agent-tool-reliability`
- 目标：修复多实例/重复回调在“状态迁移完成但 Engine 尚未取得租约”窗口内重复入队的问题。
- 范围：AgentRunLifecycleService、AgentTaskService、workspace/retry/interaction/environment 恢复入口、AgentLoopEngine 的租约交接，以及对应回归测试。

## 现象与证据

当前恢复链路存在两个独立问题：

1. workspace/environment 恢复先把 `waiting_*` 迁移到 `queued`，Engine 之后才调用 `AgentRunExecutionLeaseService.acquire`。
2. retry/interaction 恢复先把任务迁移到 `recovering`，幂等事件重放仍被调用方当作“本次成功领取”，可能再次调用 `AgentLoopEngine.resume`。

这会让多个 JVM、重复审批回调或重复 scheduler 调度把同一个 task 多次放入 Engine executor。状态机和租约虽然最终可能拒绝其中一部分，但副作用发生在入队之前，不能把它当成可靠的 single-dispatch 保证。

## 本轮方案

1. 在 `AgentRunLifecycleService` 增加事务内的 durable dispatch claim：锁定 task，校验期望状态/版本/未过期租约，原子写入目标状态、execution owner、epoch、lease、事件和 outbox。
2. 恢复入口只在“本次确实新领取”时调用 Engine；已存在的幂等事件、状态已前进或租约已被占用均不再次入队。
3. 将已领取的 `ExecutionLease` 交给 Engine；Engine 启动时续租确认，队列拒绝时释放租约，正常结束仍按原有 finally 路径释放。
4. 保留旧的布尔 API 作为短期兼容投影，但新的 scheduler/controller 不再依赖布尔值判断是否拥有本次 dispatch。

## TDD 顺序

- 先增加失败测试：重复恢复不得二次调用 Engine；workspace/retry/interaction/environment 都必须把 lease 传入 Engine；队列拒绝释放 lease。
- 再实现最小 claim 与 lease handoff。
- 最后重构重复代码并保持既有生命周期事件协议。

## 验收

### 源码/测试

- `mvn -Dtest=AgentRunLifecycleServiceTest,AgentTaskServiceTest,AgentRunResumeSchedulerTest,AgentRunRetrySchedulerTest,AgentWorkspaceAdmissionSchedulerTest,AgentLoopEngineStreamingContractTest test`
- `mvn -q test`
- `npm.cmd test`
- `npm.cmd run build`

### 真实系统

- 启动使用当前 `backend/target/classes` 的 JVM，记录 PID、启动时间和 classpath。
- 通过 acceptance/live harness 触发恢复任务，验证数据库中只有一个 dispatch 事件、一个 execution epoch 的有效 owner，且重复回调不会创建第二个 SSE/Engine 执行。
- 做一次双实例竞争验证：两个当前版本 JVM 共享数据库同时扫描同一 waiting/retry task，最终只能有一个实例成功领取并继续；另一个实例不得调用 Engine。

## 已知边界与后续

本轮只保证当前版本代码之间的持久化 dispatch 竞态。已经加载的旧 JAR 不会自动理解新增 claim 协议，因此旧 JAR 与新 JAR 共用数据库的滚动升级隔离仍需要后续通过部署代际/数据库命名空间或切换闸门解决；本轮不以新增字段但旧代码不检查为伪解决方案。

## 实施结果

- `AgentRunLifecycleService.claimDispatch(...)` 在同一事务内锁定 task、校验状态/版本/租约、写入目标状态、execution owner/epoch/lease、生命周期事件和 outbox；未取得新 claim 时返回 `null`，调用方不得再次入队。
- retry、interaction、workspace、environment 恢复入口均改为 claim 后把 `ExecutionLease` 交给 `AgentLoopEngine`；队列拒绝会释放预领取租约。
- takeover 重复幂等事件不再返回第二个可执行 claim；过期 lease 接管同样把 lease handoff 给 Engine。
- `AgentLoopEngine` 新增预领取租约入口：运行线程启动时续租确认，避免入队后再竞争；普通 start/resume API 保持兼容投影。
- 新增 4 个 dispatch 回归测试文件，并更新原有 lifecycle/scheduler/takeover 测试以验证“只有新 claim 才可调用 Engine”。
- 未修改 `backend/src/main/resources/application-acceptance.yml`、`.codex-tmp/` 及其他已有无关文档/计划改动。

## 验收结果

### TDD/后端

1. 初始 claim 回归测试先因 `DispatchClaim/claimDispatch` 缺失而编译失败，证明测试确实覆盖了新增契约；实现后聚焦测试全部通过。
2. 聚焦命令：

   ```powershell
   cd D:\LabexAgent\backend
   mvn -q "-Dtest=AgentRunLifecycleServiceTest,AgentRunTakeoverSchedulerTest,AgentRunRetrySchedulerTest,AgentRunResumeSchedulerTest,AgentWorkspaceAdmissionSchedulerTest,AgentRunDispatchClaimTest,AgentRunRetryDispatchClaimTest,AgentRunInteractionDispatchClaimTest,AgentWorkspaceDispatchClaimTest,AgentLoopEngineStreamingContractTest,AgentTaskServiceLifecycleTest" test
   ```

   结果：通过。
3. 全量后端：`cd D:\LabexAgent\backend; mvn -q test`，结果：通过。
4. `git diff --check`，结果：通过。

### 前端/验收单元

- `cd D:\LabexAgent\frontend; npm.cmd test`：160/160 通过。
- `npm.cmd run build`：通过；chunk budget：CloudWorkspace `1,449,183/1,500,000`、index `1,259,534/1,300,000`、TerminalPanel `380,367/400,000`。
- `npm.cmd run test:acceptance:unit`：9/9 通过。
- 中途一次前端测试发现新增 Java 注释被写成 `?`；已用显式 UTF-8 修复，重新运行后 source encoding 测试通过。

### 真实 Chromium

- UI：`http://127.0.0.1:13002`；API：`http://127.0.0.1:18080/api`；当前运行 `runId=63d804a3a8a54fbeb2216f9bdedcc13a`、`projectId=172`。
- 1440x900 实际布局：sidebar 240px、center 775px、AI panel 420px，三栏水平对齐。
- conversation isolation、刷新回放去重、question reply、permission approval refresh recovery、multi-tool permission batch、durable provider messages、compaction epoch 1、stream interruption、static context blocker、completion evidence、unverified completion blocked 全部通过。
- 浏览器 console errors=0、network errors=0。
- 本轮未启动 restart handoff 环境变量，因此该次脚本返回 `restartProjectionVerified=false` / `restartInteractionVerified=false`；第 12 轮已有完整 JVM restart projection 证据，本轮不把未运行的场景冒充为本轮通过。

### 双 JVM 真实竞争

- JVM A：acceptance/local，`target/classes`，PID 39968，端口 18080。
- JVM B：acceptance/local，`target/classes`，PID 33088，端口 18082，`instance-id=iteration13-b`。
- 场景：两个当前版本 JVM 共享 MySQL 数据库、同一 project，同时提交 `[acceptance:checkout-hold]`；第二个 task 先进入 `waiting_workspace`，checkout 释放后由两个 scheduler 竞争恢复。
- 结果：`projectId=174`、首 task `981`、等待 task `982`；等待 task 最终 `completed`，`executionEpoch=2`（初始执行租约 1 次 + workspace 恢复租约 1 次），持久事件中 `RUN_WORKSPACE_RESUME` 恰好 1 次。
- 事件序列包含：`RUN_WORKSPACE_WAITING`、`WORKSPACE_WAITING`、`TASK_PAUSED`、唯一 `RUN_WORKSPACE_RESUME`、`RUN_STATE_RUNNING`、`RUN_STATE_COMPLETED`、`DONE`。这证明两个当前 JVM 共享数据库时只有一个 scheduler 取得 durable dispatch，未发生重复恢复入队。

## 本轮未解决边界

- 旧 JAR 完全不知道本轮 claim 协议，仍可能在新 JAR 前先修改 `waiting_workspace`；当前双 JVM 验收覆盖的是两个当前版本。旧版本滚动升级的硬隔离仍需后续通过 deployment generation/数据库命名空间或切换闸门完成，不能只增加一个旧代码不会检查的字段。
- 本轮没有把 `AgentRunLifecycleService` 里全部历史兼容布尔 API 删除；生产 scheduler/controller 已切到 claim API，兼容入口保留到后续删除条件满足。

## 提交

- 本地提交：ix: make recovery dispatch claims durable（最终提交号以本轮 Git HEAD 为准）。
- stage 范围：仅本轮 17 个源码/测试/文档文件；原有 pplication-acceptance.yml、.codex-tmp/ 和其他无关文档未 stage。