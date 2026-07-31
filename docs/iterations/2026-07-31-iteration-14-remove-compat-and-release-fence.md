# 第 14 轮：删除旧恢复兼容路径与 release 启动闸门

- 日期：2026-07-31
- 分支：`codex/agent-tool-reliability`
- 目标：在 durable dispatch claim 已稳定后，删除当前版本生产代码中仍可能绕过 claim 的旧布尔恢复入口，并让标准 release launcher 在启动前拒绝已有 Labex JVM，避免旧/新 classpath 混用。

## 当前证据

第 13 轮后，生产 scheduler/controller 已调用 `DispatchClaim`；以下旧 API 只剩兼容实现、测试或 workspace 旧构造器 fallback：

- `AgentRunLifecycleService.beginScheduledRetry`
- `AgentTaskService.beginInteractionResume`
- `AgentTaskService.beginWorkspaceResume`
- `AgentTaskService.beginEnvironmentResume`
- `AgentWorkspaceAdmissionScheduler` 的五参数构造器和无 claim fallback

同时，`backend/scripts/start-release.ps1` 只负责 build + start，没有检查已有 Labex JVM；这允许开发者在旧 JVM 仍持有旧 classes 时继续启动新 release。

## 本轮方案

1. 先增加失败回归测试，扫描生产源码不得再出现上述旧 API，且 release launcher 必须声明并调用已有 Labex JVM preflight。
2. 删除兼容布尔 API、旧 workspace 构造器和 fallback；保留唯一的 `DispatchClaim` 恢复路径。
3. 为 release launcher 增加只读进程检查：匹配 `com.labex.LabexAgentApplication` 或 Labex executable JAR；发现已有进程时在 build 前失败，并输出 PID/启动时间/建议。
4. 在 README 和本轮文档中写明：生产/验收启动必须通过 release launcher 或等价 deployment controller；直接手工启动旧 JAR 属于未受管部署，不宣称被 launcher fence 覆盖。

## 验收

### 源码与测试

```powershell
cd D:\LabexAgent\backend
mvn -q test
cd D:\LabexAgent\frontend
npm.cmd test
npm.cmd run build
npm.cmd run test:acceptance:unit
```

### 真实 release fence

1. 启动一个当前版本 acceptance JVM，记录 PID/端口/classpath。
2. 在该 JVM 仍运行时执行 `D:\LabexAgent\backend\scripts\start-release.ps1 -SkipTests`。
3. 预期：脚本在 build 前以非零退出，报告已有 Labex JVM；不得启动第二个 release。
4. 停止测试 JVM 后再次执行启动前 preflight，确认不再因残留进程误报；完整 application 启动不在本轮脚本测试中重复占用生产端口。

## 边界

进程闸门只约束标准 release launcher；旧 JAR 可以被用户绕过 launcher 手工启动，因此不能把它描述成数据库级强制隔离。若未来需要任意启动方式都安全，仍需 OS/service manager 或物理数据库/队列 namespace 的部署控制。

## 结果

### 实施

- 已删除 `AgentRunLifecycleService.beginScheduledRetry`。
- 已删除 `AgentTaskService.beginInteractionResume`、`beginWorkspaceResume`、`beginEnvironmentResume` 以及旧的外部恢复入口。
- `AgentWorkspaceAdmissionScheduler` 仅保留带 `AgentRunLifecycleService` 和 `AgentRunExecutionLeaseService` 的构造路径，不再接受空依赖或无 claim fallback。
- `backend/scripts/start-release.ps1` 已增加 `Assert-NoExistingLabexBackend` 和 `-PreflightOnly`；检查发生在 Maven build 之前，并输出 PID、启动时间和命令行。
- `README.md` 已注明 launcher fence 的作用范围和手工启动旧 JAR 的未受管边界。

### 测试

- 首次契约测试暴露了测试文件中的非法字面量换行；修复测试源码后重新运行通过。
- 聚焦回归：
  `mvn -q "-Dtest=AgentRuntimeConvergenceContractTest,AgentRunLifecycleServiceTest,AgentTaskServiceLifecycleTest,AgentWorkspaceAdmissionSchedulerTest,AgentWorkspaceDispatchClaimTest,AgentRunRetrySchedulerTest,AgentRunResumeSchedulerTest" test` —— 通过。
- 后端全量：`mvn -q test` —— 通过，退出码 0。
- 前端全量：`npm.cmd test` —— 通过。
- 前端构建：`npm.cmd run build` —— 通过，chunk budget 检查通过。
- 前端 acceptance unit：`npm.cmd run test:acceptance:unit` —— 9/9 通过。

### Live

- 启动当前版本 acceptance JVM：PID `54380`，端口 `18083`，classpath 为 `D:\LabexAgent\backend\target\classes`。
- JVM 仍运行时执行 `D:\LabexAgent\backend\scripts\start-release.ps1 -SkipTests`，脚本在构建前拒绝启动，并报告 PID `54380`；没有产生第二个 release JVM。
- 停止 PID `54380` 后执行 `D:\LabexAgent\backend\scripts\start-release.ps1 -PreflightOnly`，输出 `Labex backend preflight passed.`，退出码 0。
- 本轮没有把手工旧 JAR 启动描述为受到 launcher fence 保护；该边界已记录在 README 和本文件中。

### 提交

- 本轮代码、测试、启动脚本、README 和验收文档待在完成最终 diff 审计后创建本地 Git 提交。
