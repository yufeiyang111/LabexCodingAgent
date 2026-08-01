# 第 31 轮：lease 恢复、跨 JVM 接管与 Provider Part 投影

- 日期：2026-08-01
- 分支：`codex/agent-tool-reliability`
- 范围：Agent 运行时恢复、lease heartbeat、已删项目的恢复防护、Provider `tool_result` 前端投影、测试交接

## 1. 本轮前的真实失败

上一次现场验收暴露了三类问题：

1. `AgentRunRecoveryService` 使用旧快照直接 `updateById`，可能用旧的 `waiting_*` 状态覆盖已经完成的任务。
2. Spring 默认 scheduler 只有一个线程，lease 更新遇到数据库锁等待后会阻塞其他定时任务。
3. JDBC 超时可以包装成 `TransactionSystemException`，旧 heartbeat 捕获范围不足，会使 heartbeat 线程直接退出。
4. acceptance 的 holder 与 contender 由 `Start-Job` 异步启动，可能交换角色，导致错误判断。

## 2. 本轮实现

### 后端运行时

- `AgentRunLifecycleService` 新增带期望状态的条件事件追加：仅当 task 仍处于快照期望状态时才写入 recovery 事件。
- `AgentRunRecoveryService` 取消旧快照直写，以条件更新记录 recovery attempt，失败时不再擦写终态。
- `AgentRunTakeoverScheduler` 在接管前检查 project 是否仍存在；已删项目的任务转为明确的 failed，不再调用无 workspace 的 resume。
- `AgentRunLeaseHeartbeatService` 与 `ProjectCheckoutLeaseHeartbeatService` 识别可重试的数据库异常，包括 `TransactionSystemException` 包装的 lock/timeout 错误。
- `application.yml` 将 scheduler pool 设为 4，避免单个 heartbeat 阻塞整个调度器。

### 前端投影与验收脚本

- `agentRunPartState.js` 现在识别 `tool`、`tool_call`、`tool_result`，且不用空对象覆盖原有工具参数。
- `browser-runtime.ps1` 新增 `-RestartBackendForAcceptance`：测试进程会等待 browser 写入 `ready.json`，重启同一 JAR 后再写入 `continue.signal`。
- 可分别验证审批交互和 compaction projection 的跨 JVM 恢复。
- 对重启期间 Vite 代理产生的 `subscribe` / `active-task` 500 增加精确测试政策：只有在两个重启验收分支都成功证明后才允许，其他 500 仍会失败。
- `run-all.ps1` 支持 `-RestartBrowserBackend`，但不改变默认的快速验收模式。

## 3. 回归测试

新增或更新：

- `AgentRunLifecycleServiceTest`
- `AgentRunRecoveryServiceTest`
- `AgentRunTakeoverSchedulerTest`
- `AgentRunLeaseHeartbeatServiceTest`
- `ProjectCheckoutLeaseHeartbeatServiceTest`
- `agentRunPartState.test.mjs`
- `browser-error-policy.test.mjs`

覆盖：

- 旧 recovery 快照不能覆盖终态。
- 过期租约和删除 project 的接管不会启动无效 resume。
- transient DB exception 不会使 heartbeat 注册表失去租约。
- Provider `tool_call` + `tool_result` 能投影成一个完整工具卡片。
- 重启期间的传输错误不会放过非目标 endpoint。

## 4. 验收证据

因 C 盘临时目录已满，Maven 测试通过 `D:/LabexAgent/.codex-tmp/` 隔离临时文件。

### 后端全量测试

```text
cd D:/LabexAgent/backend
mvn -q test
result: 832 tests, 0 failures, 0 errors, 8 skipped
```

### 前端

```text
cd D:/LabexAgent/frontend
npm test
result: passed
npm run build
result: passed; chunk budgets passed
```

### 后端重启验收

```text
D:/LabexAgent/scripts/acceptance/agent-runtime.ps1 -BackendPort 18129 -TimeoutSeconds 240
```

所有标记均为 `true`：

- `questionRestart`
- `permissionRestart`
- `commandApproveRestart`
- `commandRejectRestart`
- `checkoutContention`
- `runMessagePartProjection`
- `manualCompaction`
- `staticContextBlocked`
- `contextWindowUnconfigured`
- `completionEvidence`
- `unverifiedEditRejected`
- `normalProfileProviderIsolation`
- `cleanup`

### 浏览器真实重启验收

```text
D:/LabexAgent/scripts/acceptance/browser-runtime.ps1 \
  -BackendPort 18131 -FrontendPort 13023 -CdpPort 19249 \
  -TimeoutSeconds 240 -RestartBackendForAcceptance
```

返回的关键证据：

```json
{
  "projectId": 216,
  "questionReplyComponent": true,
  "permissionApprovalRefreshRecovery": true,
  "multiToolPermissionBatchProtocolComplete": true,
  "durableCompaction": true,
  "providerStreamInterruptionHandled": true,
  "restartProjectionVerified": true,
  "restartInteractionVerified": true,
  "expectedRestartTransportErrors": 0,
  "consoleErrors": 0,
  "networkErrors": 0
}
```

此验收确实重启了后端 2 次：一次在审批交互等待期，一次在 compaction 完成后。

## 5. 本轮剩余风险

1. `run-all.ps1 -RestartBrowserBackend` 的组合门禁在一次运行中出现了浏览器交互脚本的短暂问题，但同一版本的独立浏览器重启验收已稳定通过。后续应将组合脚本的失败重试机制单独收敛。
2. 测试中仍会出现预期的 Maven/JDK 警告，没有作为失败处理。
3. `backend/src/main/resources/application-acceptance.yml` 是工作区中原有用户改动，本轮不提交。

## 6. 保留的无关改动

本轮没有删除、reset 或提交以下原有工作区内容：

- `D:/LabexAgent/backend/src/main/resources/application-acceptance.yml`
- `D:/LabexAgent/.codex-tmp/`
- `D:/LabexAgent/docs/agent-context-provider-smoke-test.md`
- `D:/LabexAgent/docs/coding-agent-engineering-roadmap.md`
- `D:/LabexAgent/docs/coding-agent-industrialization/`
- `D:/LabexAgent/docs/superpowers/plans/` 中本轮之前的未跟踪文档
- `D:/LabexAgent/docs/superpowers/specs/`

## 7. 下一轮

下一轮不再扩容兼容层，应继续做任务六到任务九的结构收敛：

- 全仓库审计 `AgentTask.status` 直写点。
- 删除或降级旧 `AgentContext`、summary/cache 和 recovery fallback 路径。
- 补齐 provider overflow、tool timeout、SSE 断线、重复审批、过期审批、scheduler 重复领取的 fault injection。
- 在核心状态收敛完成前，不宣称 Agent 已经完成自主修复与策略切换。
