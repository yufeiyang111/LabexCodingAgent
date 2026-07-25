# Phase 1：命令安全与审批语义重构 — 设计

## 目标

将命令**分类**、用户**审批**与 sandbox **执行**拆开。模型工具参数、HTTP 请求字段和 remembered wildcard permission 均不能构成审批事实；高风险命令只可由对应用户对精确、一次性的 server-side command intent 批准。

本阶段首发范围：

- 禁用学生工作区 WebSocket interactive shell，消除它对逐命令审批的旁路；
- 默认无网络；网络、脚本、管道、重定向、动态展开和编码 shell 输入拒绝；
- 仅允许受限的 `executable + arguments` 命令格式；
- 对 agent `shell`/`bash` 和两个 REST managed-terminal endpoint 使用同一分类与审批服务；
- 一次性审批绑定 project/user/run/invocation/tool call/command digest/CWD/options/deadline，原子消费且可审计。

不在首发范围：恢复自由交互式 shell、可记忆命令授权、可审批网络 egress、完整 cmd/PowerShell/POSIX grammar、把 LSP/MCP/固定系统进程纳入用户命令审批。

## 已核验现状

- `ProjectCommandSafety.check(command, approved)` 以子串规则分类，`approved=true` 会把全部 NEEDS_APPROVAL 命令降为 normal。
- `StudentProjectController` 将请求的 `allowDangerous` 直接当作 `approved`；`AgentLoopEngine` 将 generic permission ALLOW 转写为 `allow_dangerous=true`；`RunCommandTool`/`BashTool` 再次相信该字段。
- `PermissionService` 的 generic permission 能记忆 glob ALLOW，但没有精确 command digest、expiration enforcement、approval consumption 或 execution idempotency。
- `AgentRunInteractionService` 已有持久化 waiting interaction、project/student ownership、task idempotency 的可复用模式，但 resume 会重新让模型决策，不能作为 command execution capability。
- `TerminalWebSocketHandler` 写入原始 keystroke，无法对每个完整命令建立审批绑定。

## 领域模型

新增 `com.labex.labexagent.commandsecurity`：

- `CommandRequest`：服务端创建的不可变执行 intent；保存 actor、project、source、task/conversation/run、invocation/tool-call ID、CWD、shell、命令、timeout、long-running、network 和 sandbox profile。
- `CommandNormalizer`：生成 versioned canonical form、redacted display 和 SHA-256 digest；digest 覆盖所有能改变执行目标/权限的字段。
- `CommandClassification`：`ALLOW`、`REQUIRE_APPROVAL`、`BLOCK`，带 reason code、risk class、policy/normalizer version。
- `CommandPolicyService`：唯一授权点；只接受 server-owned request，创建 approval 或消费有效 approval，返回 `AuthorizedCommand`。不执行进程。
- `CommandApprovalService`：持久化 request、决定、expiry、ownership、原子单次消费和审计。
- `AuthorizedCommandExecutor`：仅接受已授权并消费的 command，检查 sandbox capability 后调用现有 worker/terminal execution。

分类只分析受限 direct-argument 命令。包含 `|`, `;`, `&&`, `||`, `>`, `<`, `$`, backtick、`$()`, `%VAR%`, PowerShell encoded/`Invoke-*`、shell `-c`、引号拆词、base64 decode 或未知控制字符的输入一律 BLOCK。网络工具或 URL egress 一律 BLOCK。风险分类不读取任何 approval boolean。

## 审批与审计持久化

以 additive schema migration 新增：

1. `t_command_approval`：immutable command binding，状态 `pending/approved/rejected/expired/consumed/cancelled`，过期时间、canonical/display/digest、actor/project/run/tool-call/invocation、CWD/options/shell、classification 和 policy version。
2. `t_command_approval_decision`：decision 的 append-only 审计及 client idempotency key。
3. `t_command_execution_audit`：request/classification/decision/consume/start/finish/deny/sandbox-failure lifecycle；命令只保存 redacted display/hash，不保存 secrets 或 output。

审批 reply 必须验证 student/project 和 pending/expiry，重复 reply 返回原决定。执行前 `consume` 通过一个条件更新将 `approved → consumed`，并重新比较完整 binding；影响行数不是 1 时拒绝启动。已消费 approval 永不复用。

既有 `t_agent_permission_approval` 仅继续用于非命令 generic permission；命令入口完全忽略它，也不迁移旧 wildcard grant。

## 入口集成

### REST terminal

`POST /terminal/run` 与 `POST /terminal/sessions/{sessionId}/run`：

1. 加载 owned project/session 并创建 server invocation ID；
2. 忽略 legacy `allowDangerous`；
3. 调用 command policy；
4. ALLOW 直接执行，REQUIRE_APPROVAL 返回 opaque approval ID/expiry/display/risk，BLOCK 返回 typed refusal；
5. 新增项目/用户范围 approval decision endpoint。它只接收 approval ID、approve/reject 和 client decision ID；不能接收替换命令或 bypass 字段；
6. 对批准的 terminal invocation，执行端从审批记录恢复 intent 后消费，不接受客户端重新提交命令。

### Agent shell tools

`AgentLoopEngine` 在 generic `PermissionService` 前针对 `shell`/`bash` 构造 request 并调用 policy。删除 `args.addProperty("allow_dangerous", true)`。审批后由 server-owned pending command continuation 消费同一 intent，而非让模型重新生成调用。`RunCommandTool`/retained `BashTool` 不能读取 `allow_dangerous`；缺少 `AuthorizedCommand` 时 fail closed。

### WebSocket terminal

`TerminalWebSocketHandler` 在 create 前返回稳定 disabled-by-policy 错误，且不得调用 `SandboxWorker.openTerminal`。保留认证、关闭和资源清理；前端显示 managed terminal migration 提示。

## Sandbox 与失败策略

命令执行需验证受支持的 isolated `SandboxWorker` profile、canonical workspace containment 和 network deny profile。sandbox capability 缺失、worker 不可用、approval 不存在/过期/失配、CWD 无法安全解析、或网络请求均在 spawn 前拒绝。不得 fallback 到 host process。

## 测试策略

- pure command parser/normalizer：安全 direct commands、风险命令、bypass corpus、digest coverage；
- approval DB/service：create/reply/ownership/expiry/idempotency/atomic consume/mutation/replay；
- cross-entry contract：REST terminal、AgentLoop、RunCommandTool、BashTool 同命令一致；模型/client `allow_dangerous` 无效；无 approval 零 worker interaction；
- WebSocket disable：create/input 不调用 interactive worker；
- sandbox capability 与 network fail closed；
- 保留 Phase 0 测试名称/注释迁移为 intentional Phase 1 security expectations。

## 验收

- 无 client/model 参数可形成 command approval；
- command 变化任一字节或执行上下文变化后旧 approval 无效；
- 重试/双击/恢复最多消耗一次审批、最多启动一次；
- WebSocket terminal 无法旁路；
- 默认 network deny；
- target/related/full Maven tests 均无新增 failure/error/skip；
- 文档明确分类不是 sandbox 替代品。
