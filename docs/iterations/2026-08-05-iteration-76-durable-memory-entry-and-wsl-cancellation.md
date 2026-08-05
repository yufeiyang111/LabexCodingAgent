# 第 76 轮：durable memory 构造入口收口与 WSL 审批命令优雅终止

- 日期：2026-08-05
- 分支：`codex/agent-tool-reliability`
- 前置提交：`4624028 feat: project durable execution progress`
- 计划来源：`docs/superpowers/plans/2026-07-29-agent-runtime-state-convergence.md` 第 12、13 节

## 1. 本轮目标

本轮只处理最终架构审计中已经定位的两类遗留问题：

1. 删除 `AgentConversationService` 中仍允许测试或旧调用方装配 `AgentMessageMapper`、`AgentTaskMapper`、`legacy/shadow` 字符串的兼容构造入口，保证 Provider memory、UI history、fork boundary 均从 durable projector 进入。
2. 真实系统验收暴露出 WSL 审批命令取消后，直接销毁 Windows `wsl.exe` 包装进程可能污染后续 bwrap/WSL 执行的问题；为非交互命令增加 Linux 进程组级别的优雅取消/超时协议，同时保留原有强制终止兜底。

停止边界不包括数据库 schema 删除、`AgentMessage` 表删除、legacy history migration 删除、checkpoint reader 删除或 AgentLoopEngine 整体重写。

## 2. 架构审计结论

### 2.1 conversation memory/history

- `AgentConversationService` 生产装配现在只有一个五参数构造器：conversation metadata mapper、RAG metadata config、durable fork boundary、durable memory projector、durable history projector。
- Provider memory 通过 `AgentConversationMemoryProjectionService` 读取；UI history 和 memory stats 通过 `AgentConversationHistoryProjectionService` 读取。
- 显式 `taskId` fork 直接走 durable task boundary，不得访问 `AgentMessageMapper`。
- 旧 `messageId` fork 仍允许一次性读取旧消息以映射稳定 task boundary；这是兼容读路径，不是常规事实源。

### 2.2 保留的只读迁移入口

- `AgentLegacyConversationHistoryMigrationService` 只读取旧 `t_agent_message` 行，然后创建 terminal `AgentTask + AgentRunEvent + AgentRunMessage + AgentRunPart` 图，并写入 conversation migration version；不再向旧消息表双写。
- `AgentCheckpointStore` 只读取 v1/v2 checkpoint seed；已有 durable Tool Part/Event 时不会覆盖数据库事实，新任务不再写 checkpoint。
- 上述 reader 仍需服务未迁移存量，因此本轮不删除表、文件 reader 或迁移服务。

## 3. 实现内容

### 3.1 durable conversation 构造入口

- 删除 `AgentConversationService(AgentConversationMapper, AgentMessageMapper, RagConfig)`。
- 删除携带 `AgentTaskMapper` 和 `legacy/shadow` mode 的六参数构造器。
- 更新 memory、compaction、fork、model metadata 测试，直接装配生产五参数构造路径。
- fork 回归测试新增断言：请求带稳定 `taskId` 时，`AgentMessageMapper.selectById(...)` 必须零调用。

### 3.2 WSL 非交互命令 supervisor

- 新增 package-private `WslCommandSupervisor`，作为一次性控制协议的唯一所有者；`WslSandboxWorker` 只负责 bwrap 挂载、命令装配和执行调用。
- 每次非交互命令在 `.labex-agent/worker-tmp/wsl-exec-*` 创建临时 supervisor/control 目录，不写 transcript、审批、task 或 event 表。
- supervisor 使用 `setsid` 创建 Linux 进程组；取消或超时时先写 `cancel` / `timeout` 标记，再向进程组发送 TERM，短暂宽限后发送 KILL。
- Java 侧在 2.5 秒优雅宽限内不请求 `LocalProcessExecutor` 销毁 Windows `wsl.exe`；若 supervisor 无法退出，才恢复原来的强制终止兜底。
- 正常成功、失败、取消、超时仍映射为现有 `ProcessExecutionResult`；交互终端与 `startProcess` 路径保持不变。
- 退出后仅清理本次 `wsl-exec-*` 控制目录；清理失败不会扩大删除范围，也不会覆盖原始命令结果。

### 3.3 真实回归场景

在现有 opt-in WSL smoke 中增加：

1. 在真实 Debian+bwrap 中执行与 acceptance 相同的 `node .labex-acceptance-command-hold.cjs`；
2. 等待命令真实启动后发出 cancellation；
3. 断言第一条命令返回 `CANCELLED`；
4. 不重启 WSL，立即执行第二条短命令并断言成功；
5. 继续运行原有 workspace-only/toolchain smoke 和交互终端 smoke。

## 4. 验证证据

### 4.1 定向与全量测试

| 命令 | 结果 |
|---|---|
| `mvn -q -Dtest=AgentConversationMemoryModeTest,AgentConversationServiceCompactionTest,AgentConversationForkDurableBoundaryTest,AgentConversationUserModelMetadataTest test` | 通过 |
| `mvn -q -Dtest=WslSandboxWorkerTest test` | 通过 |
| `mvn -q -Dlabex.wsl.smoke=true -Dtest=WslSandboxWorkerSmokeTest,WslSandboxWorkerTerminalSmokeTest test` | 真实 Debian+bwrap smoke 通过 |
| `mvn -q test` | 1,033 tests，0 failure，0 error，9 skipped |
| `npm.cmd test` | 213/213 通过 |
| `npm.cmd run test:acceptance:unit` | 15/15 通过；首次沙箱内运行因 Node 子进程 `spawn EPERM` 未进入断言，按规则在批准的非沙箱环境重跑后通过 |
| `npm.cmd run build` | Vite production build 通过，chunk budget 通过 |

### 4.2 后端真实系统验收

后端 acceptance run：`631419761ea245a2a010485a4b91f1b4`。

已通过：

- question / permission / command approve / command reject 的 JVM restart 恢复；
- 真实 WSL 审批命令取消，耗时 `2903ms`，task、Tool Part、tool result 和 cancellation events 均终态化；
- 已持久化进程身份后的强制 JVM crash、active lease fence、lease 过期和孤儿进程回收；
- durable Message/Part projection、tool authority、manual compaction、compaction cancellation；
- durable fork、legacy history migration restart、durable plan、durable execution progress；
- checkpoint 退役和直接 SSE/durable replay event order。

### 4.3 真实浏览器验收

浏览器 acceptance run：`3dddca4eaad14b60aeacbfbcedf286f5`。

已通过：

- 1440×900 desktop layout；
- 新旧 conversation 隔离、刷新回放去重、durable history projection `durable-task-history-v1`；
- question reply、permission approval、command approval 的刷新恢复与稳定 `toolCallId`；
- provider interruption、model retry、compaction、fork、backend restart；
- 内部思考协议标签隐藏；
- `consoleErrors = 0`，`networkErrors = 0`。

### 4.4 验收环境问题与处理

- 一次完整 `run-all.ps1` 在打包阶段发现旧 browser acceptance Java PID `35788` 持有 JAR；Restart Manager 证明其命令行和 workspace 均属于已中断的验收运行，精确终止后 JAR 锁释放。
- 后端 3/4 验收通过后，浏览器阶段发现旧 Vite 进程链 `7044 -> 37588 -> 40208 -> 10080` 占用 `13028`；逐 PID 核对命令行后只终止该验收链。
- 另清理同一旧 browser acceptance 的孤儿 H2 PID `62244`；未删除其 workspace，也未影响开发数据库。
- 清理后浏览器 acceptance 独立重跑通过。上述属于前一次工具超时中断后的验收资源泄漏，不是产品断言失败。

## 5. 变更文件

- `backend/src/main/java/com/labex/labexagent/service/AgentConversationService.java`
- `backend/src/main/java/com/labex/labexagent/worker/WslSandboxWorker.java`
- `backend/src/main/java/com/labex/labexagent/worker/WslCommandSupervisor.java`
- `backend/src/test/java/com/labex/labexagent/service/AgentConversationForkDurableBoundaryTest.java`
- `backend/src/test/java/com/labex/labexagent/service/AgentConversationMemoryModeTest.java`
- `backend/src/test/java/com/labex/labexagent/service/AgentConversationServiceCompactionTest.java`
- `backend/src/test/java/com/labex/labexagent/service/AgentConversationUserModelMetadataTest.java`
- `backend/src/test/java/com/labex/labexagent/worker/WslSandboxWorkerSmokeTest.java`
- `docs/superpowers/plans/2026-07-29-agent-runtime-state-convergence.md`
- `docs/iterations/2026-08-05-iteration-76-durable-memory-entry-and-wsl-cancellation.md`

## 6. 风险、回滚与停止边界

- supervisor 只影响 `WslSandboxWorker.execute(...)` 的非交互命令；终端和长驻 `startProcess` 不变。
- 控制标记失败时仍由 `LocalProcessExecutor` 强制终止，避免命令无限挂起。
- 本轮没有 schema 变更，没有删除旧消息或 checkpoint 数据，没有新增第二套运行状态或 transcript。
- deterministic acceptance 使用 `acceptance_scripted` Provider；真实外部云 Provider、TLS/代理抖动和供应商侧 stream 终止仍需在具备本地凭据时单独 smoke，不能由本轮结果替代。
- 总计划仍未完成：必须为 `t_agent_message` migration 和 v1/v2 checkpoint reader 建立可观测的存量归零/删除条件，并在达到退出条件后删除兼容 reader；在此之前不能宣称 OpenCode 等价收敛完成。

本轮到本地 Git 提交后停止，不继续扩大范围。