# Iteration 46：持久交互回答与执行租约交接

## 状态

已完成（2026-08-02）

## 真实失败证据

Iteration 45 的完整浏览器验收在手工 compaction 问题回复后失败。日志目录：

`C:\Users\35475\AppData\Local\Temp\labex-agent-browser-runtime-4ccabe6350f0420eb2d101d52d361458`

关键时间线（task `1766`，interaction `6dc5063d-c769-4b4d-abb6-fc7463c4e1b2`）：

1. `23:16:03.089` 后端收到问题回答；
2. 回答已持久化，最终 API 日志为 `answered=true`；
3. 旧 Agent worker 此时刚进入 terminal cleanup，仍持有 execution lease；
4. `AgentRunResumeScheduler` 立即调用 `claimInteractionResume`；
5. `AgentRunLifecycleService.claimDispatch` 因 active lease 返回 `null`；
6. scheduler 只记录 `AGENT_INTERACTION_RESUME_CLAIM_REJECTED`，不重试、不排队、也不进入明确失败；
7. 任务永久停在 `waiting_user`，前端只能一直等待或刷新。

## 根因

交互状态已经是持久事实，但 continuation dispatch 仍是一次性内存动作。用户回答与旧 worker 释放租约存在正常并发窗口，首次 claim 失败不能被解释为终态失败，更不能被静默丢弃。

## 本轮实现

### `AgentRunResumeScheduler`

- 已解决的 question / permission / network interaction 继续以原始 `interactionId` 作为恢复身份；
- 首次 claim 因旧 execution lease 被拒绝时，HTTP 路径返回“已接受”，并启动 100ms 间隔的后台重试；
- 最多执行 350 次 claim（覆盖默认 30 秒 execution lease，并留出释放窗口）；
- 同一 JVM 内用 interaction ID 合并已排队的 retry；数据库 `RUN_INTERACTION_RESUME_QUEUED` 幂等键继续阻止双 dispatch；
- 如果另一个恢复者已把任务推进到 `recovering` / `preparing` / `running` / `completed`，重复恢复视为已推进，不创建第二条 continuation；
- 重试耗尽或异步重试异常时，仅在任务仍处于 `waiting_user` / `waiting_approval` 时迁移到明确 `failed`，并写入稳定失败幂等键；
- executor 拒绝 continuation 时也改用稳定 interaction 失败键，不再使用随机 occurrence key。

## 回归测试

新增一个聚焦回归：

`AgentRunResumeSchedulerTest.retriesAResolvedInteractionAfterThePreviousWorkerReleasesItsLease`

它使用真实延迟调度和 `CountDownLatch` 固定以下协议：

1. 第一次 `claimInteractionResume` 返回 `null`；
2. scheduler 接受已经持久化的回答，而不是返回失败；
3. 旧 worker 释放 lease 后，第二次 claim 成功；
4. `AgentLoopEngine.resume` 只执行一次。

### RED

```powershell
cd D:\LabexAgent\backend
mvn '-Dtest=AgentRunResumeSchedulerTest' test
```

修复前结果：新增测试在 `assertThat(accepted).isTrue()` 失败；旧实现第一次 claim 被拒绝后直接返回 `false`，不会重试。

### GREEN（聚焦）

```powershell
cd D:\LabexAgent\backend
mvn '-Dtest=AgentRunResumeSchedulerTest,AgentInteractionServicePersistenceTest,AgentRunLifecycleServiceTest,AgentTaskServiceTest' test
```

结果：`33` tests，`0` failures，`0` errors，`0` skipped。

## 全量验证

### 后端

```powershell
cd D:\LabexAgent\backend
mvn test
```

结果：`871` tests，`0` failures，`0` errors，`8` skipped。

随后为真实运行时重新打包当前源码：

```powershell
cd D:\LabexAgent\backend
mvn -DskipTests package
```

结果：成功生成 `D:\LabexAgent\backend\target\labex-agent-backend-1.0.0.jar`，时间为 `2026-08-02 23:54:01`。

### 前端

```powershell
cd D:\LabexAgent\frontend
npm test
npm run build
```

结果：测试通过；生产构建通过；bundle 预算通过：

- `CloudWorkspace`：`1,459,986 / 1,500,000` bytes；
- `index`：`1,259,534 / 1,300,000` bytes；
- `TerminalPanel`：`380,367 / 400,000` bytes。

## 真实浏览器系统验收

有效验收使用刚生成的 JAR，并只对验收 JVM 设置资源上限：

```powershell
$env:JAVA_TOOL_OPTIONS='-Xms64m -Xmx512m -XX:ReservedCodeCacheSize=128m -XX:CICompilerCount=2 -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Dclient.encoding.override=UTF-8'
.\scripts\acceptance\browser-runtime.ps1 `
  -BackendPort 18080 `
  -FrontendPort 13000 `
  -CdpPort 19222 `
  -TimeoutSeconds 180 `
  -RestartBackendForAcceptance
```

结果：通过。日志目录：

`C:\Users\35475\AppData\Local\Temp\labex-agent-browser-runtime-b557a631de5241b18745c151ba58a294`

run ID：`20a4a2f052c4461994bcd86c81e5da22`。

通过的运行时断言包括：

- 桌面三栏布局；
- 新旧会话隔离；
- 刷新回放去重；
- `<think>` / 内部 reasoning 协议不可见；
- question 回复组件可用；
- permission 审批刷新恢复；
- 多 tool permission batch 完整；
- Provider Message/Part 持久化；
- durable compaction、manual compaction fork 和刷新恢复；
- provider stream interruption；
- interaction、projection 和 manual fork 三类重启恢复；
- static context blocker 和 completion evidence 卡片；
- 未验证改动不能伪装成完成；
- browser console errors：`0`；
- browser network errors：`0`。

本轮最终三个端口 `18080`、`13000`、`19222` 均已释放。

## 验收过程中的环境诊断

前两次系统验收不是有效业务失败：

1. 第一次运行时宿主机页文件只剩 `37MB`，JVM native malloc 失败；
2. 第二次宿主机虚拟内存一度只剩 `5MB`，Chromium 报 `ERR_INSUFFICIENT_RESOURCES`；
3. 同时确认 `mvn test` 不会重打 Spring Boot JAR，验收前必须显式执行 `mvn package`，否则 live 日志可能来自旧 class。

清理仅属于验收 run-id 的残留进程后，虚拟内存恢复约 `4.4GB`；重新打包并限制验收 JVM 后，完整系统验收通过。生产配置未被修改。

## 剩余风险

本轮解决的是同一进程内“旧 worker 正在释放 lease”的竞态，并通过持久幂等 claim 防止重复 continuation。若 JVM 恰好在回答已持久化、后台 retry 尚未 claim 成功之间崩溃，仍需要后续由数据库扫描 resolved interaction 与 waiting task 的 reconciler 接管；该项纳入后续生命周期恢复审计，不把当前内存 retry 宣称成跨进程最终一致性方案。