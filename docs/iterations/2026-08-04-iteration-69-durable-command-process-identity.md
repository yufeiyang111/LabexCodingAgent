# 第 69 轮：命令进程身份、执行租约与重启回收

- 日期：2026-08-04
- 分支：`codex/agent-tool-reliability`
- 前置提交：`98fe201 feat: persist command execution timeline`
- 状态：已完成

## 1. 本轮目标

第 68 轮已经把批准命令的 `claimed -> running -> terminal` 时间线持久化，但 `running` 仍然只表示“准备调用执行器”。如果 JVM 在命令进程启动后崩溃，数据库缺少可核验的进程身份，无法安全区分进程仍在运行、已经退出、PID 被复用或 worker 边界不同等情况。

本轮在不新建第二套命令状态机、不自动重放命令的前提下完成以下收敛：

1. 进程真正启动后，持久化宿主身份、执行器 owner、worker runtime/runId、PID、进程启动时间和有界租约；
2. 批准命令执行前先取得任务执行租约，运行期间通过既有 heartbeat 续租；
3. JVM 重启后先尊重尚未过期的旧租约，租约到期后再由恢复轮询器认领新 epoch 并核验进程；
4. 只有 PID、宿主和启动时间全部精确匹配时才允许终止进程树；无法核验时进入明确的不确定分支；
5. 可确定的重启中断只生成一次 durable interrupted tool result，然后恢复既有 continuation，绝不重放原命令。

## 2. 架构归属与不变量

| 事实 | 唯一所有者 | 本轮约束 |
|---|---|---|
| 实际 PID、启动时间、执行器 owner | `LocalProcessExecutor` 启动后的 observer | `ProcessBuilder.start()` 成功后才产生；持久化失败必须终止进程树 |
| worker runtime / runId | `SandboxWorker` 投影 | local、WSL、Docker 统一传递，但恢复策略按 runtime 区分 |
| 持久进程身份 | `CommandAuditService` + `t_command_audit_event` | 追加式 `EXECUTION_PROCESS_BOUND`，不写命令原文、环境变量或输出 |
| task epoch、执行租约和 heartbeat | `AgentRunLifecycleService` / `AgentRunExecutionLeaseService` | 恢复前必须取得新租约并二次核对权威状态 |
| 重启后的本机进程核验与回收 | `CommandProcessRecoveryService` | 宿主、PID、start time 三重匹配；拒绝模糊终止 |
| interrupted tool result 与继续执行 | durable transcript + Tool Part + `CommandApprovalResumeScheduler` | 幂等投影；禁止自动重放命令 |

裸 PID 不是安全身份。恢复服务不能仅凭 PID 杀进程，也不能把 SSE、前端状态或 JVM 内存中的 waiter 当作执行事实。

## 3. 实际实施内容

### 3.1 进程身份与启动观察

- 新增 `ProcessExecutionIdentity`，保存 `hostId`、`ownerId`、`workerRuntime`、`workerRunId`、`processId`、`processStartEpochMs` 和 `leaseExpiresEpochMs`，并集中校验字段合法性。
- 新增 `ProcessExecutionObserver`，为执行器提供进程启动后的单次持久绑定回调。
- 新增 `ProcessHostIdentity`：优先使用 `LABEX_AGENT_PROCESS_HOST_ID` / `labex-agent.process-host-id`，未配置时根据宿主环境生成非敏感稳定摘要；README 和配置文件同步记录该边界。
- `LocalProcessExecutor` 在 `ProcessBuilder.start()` 成功后、读取输出前建立进程身份。observer 写入失败时立即终止进程树并返回基础设施错误，避免留下无法追踪的长进程。
- `ProcessExecutor`、`SandboxWorker`、local/WSL/Docker worker 和 `AgentApprovedCommandExecutor` 增加向后兼容的 observer 通道；现有无 observer 调用保持兼容。

### 3.2 持久审计与 schema

- `CommandAuditEvent`、`schema.sql` 和 `AdditiveSchemaMigrator` 同步增加进程身份与租约字段。
- `CommandAuditService.recordExecutionProcessBound` 以稳定幂等键写入 `EXECUTION_PROCESS_BOUND`。
- additive migrator 会先确保审计表存在，再补充字段，避免旧数据库升级顺序导致启动失败。
- 审计事件不保存命令文本、环境变量或进程输出，继续遵守最小化审计数据原则。

### 3.3 审批执行链与执行租约

- `CommandApprovalOrchestrator` 在消费 approval 前获取 `AgentRunExecutionLeaseService` 租约并纳入 heartbeat 跟踪。
- 实际进程启动后，通过 observer 写入进程绑定审计和 durable `COMMAND_EXECUTION_PROCESS_BOUND` 事件；写入成功后才继续等待命令结果。
- 命令终止后，先持久化 tool result，再停止 heartbeat 并释放执行租约，最后调用既有 resume scheduler。
- 批准、重复批准、恢复和继续执行继续使用原始 `toolCallId` 与现有幂等边界，没有引入第二个内存态命令注册表。

### 3.4 JVM 重启恢复

- 新增 `CommandProcessRecoveryService`，仅恢复 local/WSL/host 进程；要求当前 hostId 一致、PID 存活、系统报告的启动时间与持久值精确一致。
- 对不同宿主、PID 复用、启动时间缺失、Docker runtime 或终止失败，服务返回明确分类，不进行猜测性 kill。
- 对可核验进程，先捕获根进程和后代集合，再终止进程树；只有捕获的全部进程句柄退出后才返回 `TERMINATED`。
- `AgentRunRecoveryService` 启动时先尊重尚未过期的旧执行租约；周期恢复器会在租约过期后重新扫描 consumed approval、认领新 epoch/lease、二次核对 durable 状态，再执行进程恢复。
- 对 `TERMINATED` / `NOT_RUNNING`，写入唯一的 interrupted 审计、durable tool result 和 `COMMAND_EXECUTION_RECOVERY_INTERRUPTED`，其中包含 `automatic_replay=false`。
- 如果 interrupted 审计已经写入而 transcript 投影中途失败，后续轮询使用 `ALREADY_INTERRUPTED` 补齐 transcript，不重复写审计或重放命令。
- 无法确定原进程状态时只发布 `COMMAND_EXECUTION_RECOVERY_UNCERTAIN`，不伪造成功、不自动继续执行危险命令。

### 3.5 真实系统验收场景

- acceptance scripted provider 使用 `python3 -m http.server 0` 触发正式网络审批分类，形成无需修改 workspace 的真实长进程。
- 系统测试会批准命令、等待 `EXECUTION_PROCESS_BOUND`、从 H2 查询实际 PID、强制终止后端 JVM，并验证命令进程仍存活。
- 随后在旧租约尚未过期时立即重启后端，验证 `RUN_RECOVERY_ACTIVE_LEASE` 且进程不会被提前终止。
- 租约过期后由恢复轮询器核验并终止原进程，最终断言只有一个 interrupted tool result、`automatic_replay=false`，且进程绑定与恢复中断事件各只有一个。
- 浏览器验收继续覆盖后端重启后的 SSE/持久投影恢复，确认前端无需刷新即可接收最终状态。

## 4. RED/GREEN 与真实故障记录

### 4.1 回归测试驱动

- RED：启动 observer、进程身份字段、持久化失败终止进程、PID/start time 双重校验、执行租约顺序、租约过期后的延迟恢复和 partial projection repair 在实现前均由新增/修改测试固定协议。
- GREEN：定向测试通过后，再运行完整后端、前端和真实重启验收；没有通过删除断言、放宽完成证据或关闭安全门禁来换取通过。

### 4.2 系统验收实际发现并修复的问题

1. **Spring 构造器选择失败**
   - 首次验收日志：`C:\Users\35475\AppData\Local\Temp\labex-agent-acceptance-b0090cf5b09b4458b5bde5abdc5f342e`。
   - 根因：`ProcessHostIdentity` 存在多个构造器，Spring 误选无参路径，应用未能启动。
   - 修复：对配置构造器显式添加 `@Autowired`，并增加真实 Spring context 构造回归。

2. **完成证据门禁拒绝未验证 workspace 改动**
   - 第二次验收日志：`C:\Users\35475\AppData\Local\Temp\labex-agent-acceptance-4b83085a72f24608a0eb8a86fb2341c3`。
   - 根因：测试 fixture 先写入 Java 文件再运行长命令，恢复后该改动没有验证证据；完成门禁正确拒绝任务完成。
   - 修复：改用不修改 workspace 的长命令，不削弱完成证据门禁。

3. **`sleep 30` 不会触发审批**
   - 第三次验收日志：`C:\Users\35475\AppData\Local\Temp\labex-agent-acceptance-6fbed01f3d9c47be8c403df33d8b63e7`。
   - 根因：正式命令分类器允许纯 `sleep`，系统测试等不到 `COMMAND_APPROVAL_REQUIRED`。
   - 修复：改用 `python3 -m http.server 0`，它按正式规则需要网络审批、会形成真实长进程且不修改 workspace。

## 5. 最终验收结果

验证日期：2026-08-04。

### 5.1 后端与前端

- 后端全量：`mvn -f backend/pom.xml test`
  - `Tests run: 976, Failures: 0, Errors: 0, Skipped: 8`
  - `BUILD SUCCESS`，耗时 `53.808s`。
- 前端全量：在 `frontend` 执行 `npm.cmd test`
  - `209 tests, 209 pass, 0 fail, 0 skipped`。
- 前端生产构建：在 `frontend` 执行 `npm.cmd run build`
  - `built in 26.60s`。
  - CloudWorkspace：`1,462,276 <= 1,500,000`。
  - index：`1,259,608 <= 1,300,000`。
  - TerminalPanel：`380,367 <= 400,000`。

### 5.2 真实后端重启与浏览器验收

执行命令：

```powershell
.\scripts\acceptance\run-all.ps1 -BackendPort 18133 -FrontendPort 13053 -CdpPort 19275 -TimeoutSeconds 180 -RestartBrowserBackend
```

结果：

- 四个总门禁 `package`、`acceptanceUnitTests`、`backendRuntime`、`browserRuntime` 全部为 `true`。
- acceptance 单测：`15/15` 通过。
- 后端 runtime runId：`c38530e1fb9e4864a96a29211724e8ae`。
- 批准命令取消：`298ms`；压缩取消：`485ms`。
- 重启恢复场景查询到并核验的真实子进程 PID：`59360`。
- 已验证：后端 JVM 被终止后子进程仍在；旧租约有效期内不误杀；租约过期后恢复轮询终止进程；没有自动重放；只产生一个 interrupted tool result。
- 浏览器 runId：`c168d478956f46358b69c5e32235e225`。
- 浏览器 `consoleErrors=0`、`networkErrors=0`，全部既有浏览器门禁通过。
- 验收清理完成，没有遗留后端、前端、CDP 或测试长进程。

## 6. 文件范围

本轮修改覆盖：

- 执行身份与 observer：`execution/ProcessExecutionIdentity`、`ProcessExecutionObserver`、`ProcessHostIdentity`、`LocalProcessExecutor`、`ProcessExecutor`；
- worker 投影：`SandboxWorker`、`LocalDevelopmentWorker`、`WslSandboxWorker`、`DockerSandboxWorker`；
- 审批与审计：`AgentApprovedCommandExecutor`、`CommandApprovalOrchestrator`、`CommandAuditService`、`CommandProcessRecoveryService`、`CommandAuditEvent`；
- 生命周期恢复：`AgentRunLifecycleService`、`AgentRunRecoveryService`；
- schema/config/docs：`schema.sql`、`AdditiveSchemaMigrator`、`application.yml`、`README.md`；
- 回归与系统验收：对应后端测试、`AcceptanceScriptedProvider` 及 `scripts/acceptance/agent-runtime.ps1`。

原有用户工作区改动 `backend/src/main/resources/application-acceptance.yml` 和其他未跟踪文档不属于本轮，不纳入提交。

## 7. 明确边界与停止点

1. 本轮只安全恢复宿主 local/WSL/host 进程。Docker 当前记录的是宿主 `docker` CLI 进程身份，不能冒充容器身份；稳定 container ID/label、容器级 lease 与重启回收没有在本轮实现。
2. 不同宿主、PID 启动时间无法读取或 runtime 不受支持时，会保守进入 `COMMAND_EXECUTION_RECOVERY_UNCERTAIN`，需要人工处置；这是刻意的安全边界。
3. `LABEX_AGENT_PROCESS_HOST_ID` 在稳定部署中应显式配置；如果容器/虚拟机镜像频繁重建且未配置，自动摘要不应被视为跨实例的基础设施身份。
4. 本轮完成并创建本地 Git 提交后，按用户要求停止，不继续 Docker 身份、下一阶段架构审计或其他重构。