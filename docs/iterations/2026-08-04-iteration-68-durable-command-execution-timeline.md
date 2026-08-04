# 第 68 轮：批准命令的持久执行时间线

- 日期：2026-08-04
- 分支：`codex/agent-tool-reliability`
- 前置提交：`deb7539 refactor: converge command approval recovery`
- 状态：已完成

## 1. 本轮目标

不新建第二套命令状态机，而是把现有 `t_command_audit_event` 从“结果审计”扩展为完整的批准执行时间线：

```text
EXECUTION_CLAIMED -> EXECUTION_STARTED(running) -> EXECUTION_SUCCEEDED/FAILED/INTERRUPTED
```

目标是：

1. 在进程启动前持久记录 `EXECUTION_STARTED`，防止 JVM 崩溃后只剩一个无法解释的 consumed approval；
2. 让恢复服务只根据持久审计时间线判断是否存在未完成执行；
3. 保持安全边界：没有持久 PID/进程组租约前，重启后只标记不确定，绝不自动重放或猜测终止命令；
4. 为下一轮记录进程身份预留安全的扩展位置，但本轮不伪装已能跨 JVM 杀进程。

## 2. 架构归属

| 事实 | 唯一所有者 |
|---|---|
| 一次性 approval 能力 | `CommandApprovalService` |
| 执行时间线审计 | `CommandAuditService` + `t_command_audit_event` |
| 执行与取消编排 | `CommandApprovalOrchestrator` |
| 重启后不确定分类 | `AgentRunRecoveryService` |
| 实际进程树终止 | `SandboxWorker` / `LocalProcessExecutor` |

禁止在 controller、frontend store、static Map 或新建的 command registry 中重复记录执行状态。

## 3. 实施计划

1. 先为 `CommandAuditService.recordExecutionStarted` 加失败回归测试，固定事件字段和幂等 key。
2. 在 `CommandApprovalOrchestrator` 发布 `COMMAND_EXECUTION_STARTED` 后、worker 调用前写入持久 `EXECUTION_STARTED`。
3. 在 `AgentRunRecoveryService` 查询最新执行审计；只对 `claimed/running` 且没有 durable tool result 的 consumed approval 记录 `COMMAND_EXECUTION_RECOVERY_UNCERTAIN`。
4. 验证终态审计不会被误标为不确定，且幂等重启恢复不会触发命令重放。
5. 完成后运行后端、前端、打包、独立 runtime 和浏览器验收，以本地 Git 提交结束本轮。

## 4. 验收门槛

- `EXECUTION_STARTED` 是进程执行前的必须持久事实。
- 事件重放幂等，不会重复写入同一个执行开始标记。
- 有已完成或已中断审计时，恢复服务不误报不确定。
- 现有批准命令中断的真实长进程验收不回归。
- 前端源码不允许新增的 ASCII question-mark character 乱码。


## 5. 实施记录

- `CommandAuditService` 新增 `recordExecutionStarted`，使用已有幂等 key `execution-started` 写入 `EXECUTION_STARTED` / `executionStatus=running`，不写入命令原文或输出内容。
- `CommandApprovalOrchestrator` 在 `COMMAND_EXECUTION_STARTED` durable event 后、调用 `AgentApprovedCommandExecutor` 前写入该持久审计标记；这使进程执行的开始边界可在重启后解释。
- `AgentRunRecoveryService` 注入审批和审计服务；对没有 durable tool result 的 consumed approval，只对最新执行状态是 `claimed`/`running` 时发布 `COMMAND_EXECUTION_RECOVERY_UNCERTAIN`。如果最新执行已是 `succeeded`、`failed` 或 `interrupted`，不重复标记。
- 为事件持久化、幂等性、恢复不重放、终态不误判回归和审批编排新增测试。
- 本轮没有增加新表、新的静态 PID Map 或自动重放逻辑，保持当前审计表为这个事实的唯一持久来源。

## 6. RED/GREEN 记录

- RED 1：新增 `CommandAuditServiceTest` 在方法尚未存在时编译失败，固定了 `EXECUTION_STARTED` 的字段和幂等 key契约。
- RED 2：恢复测试在执行审计依赖加入前编译失败，固定了新恢复构造器契约。
- GREEN：执行开始审计、恢复判定和兼容构造器全部转绿，且已加入已完成执行不标记不确定的负面回归。

## 7. 验收结果

验证日期：2026-08-04。

- 后端全量：`mvn -f backend/pom.xml test` —— `964 tests, 0 failures, 0 errors, 8 skipped`，`BUILD SUCCESS`。
- 前端全量：`npm test` —— `209 tests, 209 pass, 0 fail, 0 skipped`。
- 前端构建：`npm run build` —— `built in 25.76s`，CloudWorkspace/index/TerminalPanel 体积预算通过。
- PowerShell acceptance 脚本解析：`PS_PARSE_OK`；前端源码编码回归测试也通过。
- 独立系统验收：`powershell -NoProfile -ExecutionPolicy Bypass -File scripts/acceptance/run-all.ps1 -BackendPort 18120 -FrontendPort 13040 -CdpPort 19262 -TimeoutSeconds 240 -RestartBrowserBackend`全部通过，四个总门禁均为 `true`。
- 后端 runtime runId：`3efcda68a0fe4a13bda0f7861c7e2dfa`；批准命令取消 `287ms`，压缩取消 `512ms`，其余既有门禁均通过。
- 浏览器 runId：`3f904a603b1a45788124a3690eb574ee`；布局、审批刷新恢复、持久上下文、重启投影、编码和问题回复等门禁均为 `true`。
- 浏览器 `consoleErrors=0`、`networkErrors=0`；端口 `18120/13040/19262` 在验收后均已释放。

## 8. 本轮仍存在的边界

1. 当前持久的是审计时间线，不是可跨 JVM 释放进程的完整 execution record。
2. `LocalProcessExecutor` 可以在当前 JVM 内终止进程树，但还没有在持久审计中写入 PID/进程组租约；重启后只能安全标记不确定，不能声称已经杀掉孤儿进程。
3. 下一轮应在不破坏当前 `SandboxWorker` 边界的前提下，为进程身份和进程组增加可回收的持久租约，并将真实 JVM 重启系统测试加入 acceptance。
