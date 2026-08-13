# Agent 自我配置与真实环境执行规格

> **文档类型：** `spec.md`
> **状态：** 已批准
> **版本：** 1.0
> **日期：** 2026-08-08
> **任务文档：** [tasks.md](tasks.md)
> **验收清单：** [checklist.md](checklist.md)

## 状态

本规格已完成架构讨论并经用户确认，包含项目级配置、真实工具链执行和分级安全边界。具体代码由实现 Agent 按 [tasks.md](tasks.md) 的任务边界完成，并通过 [checklist.md](checklist.md) 的阶段门禁验收。

## 背景与问题

LabexAgent 已有 durable task、Message/Part transcript、event/outbox、interaction、Worker 和前端 replay reducer，但项目级 Agent 行为仍由分散的用户默认配置、运行时内存和入口参数组合决定。真实 Windows/WSL 工具链与严格 sandbox 之间缺少明确、可审计的选择机制，配置变更、环境修复、审批、恢复和秘密注入也没有统一的项目控制平面。

如果直接让模型改配置、切换 Worker 或执行宿主维护，会产生第二套事实源、跨项目能力泄漏、恢复时配置漂移和宿主级副作用。该功能必须先收敛现有运行时安全缺口，再引入项目配置、任务 epoch 快照、proposal、能力策略和受控环境操作。

## 范围

### 范围内

- Git 可见、非敏感的项目 Agent 配置包及 schema/canonical digest。
- 配置 revision、proposal、audit、task-epoch snapshot 和 environment operation 持久化。
- owner-only 配置审批、专用秘密输入、统一能力策略和 epoch-scoped 扩展目录。
- `strict`、`integrated-wsl`、经验证的 `integrated-windows` 与显式高风险 `full-access`。
- durable 环境探测/修复、受控 WSL host maintenance、前端配置中心和 replay projection。
- direct-resume、interaction claim、ExecutionFence、模型冻结、mode 持久化和 lease reconciliation 前置修复。

### 范围外

- 新建第二套 Agent loop、Provider transcript、任务状态、审批 waiter 或命令执行器。
- 把项目配置当秘密存储，或让模型直接批准自己的配置、系统安装、秘密轮换和宿主维护。
- 用 `unsafe-local` 冒充安全 sandbox，或在 native Windows helper 未通过实测前启用 `integrated-windows`。
- 一次性重写 `AgentLoopEngine`、`CloudWorkspace.vue`、数据库或现有用户级模型/MCP/Skill catalog。

## 用户与场景

| 角色 | 主要场景 | 权限边界 |
|---|---|---|
| 项目所有者 | 查看/编辑配置、审批 proposal、提交秘密、选择 trust/runtime | 只能操作自己拥有的项目；批准绑定精确 proposal digest/revision |
| Agent runtime | 读取当前 epoch snapshot、提出配置或环境变更、执行允许的工具 | 不能创建批准事实、读取秘密明文或提升 platform ceiling |
| 控制平面 | 校验、持久化、CAS apply、生命周期迁移、host maintenance | 必须使用 durable authority、幂等键、lease 和审计 |
| Worker | 按固定 `WorkerRunSpec` 执行命令、工具、LSP/MCP/terminal | 只能获得 allowlisted 环境、workspace scope 和短时 secret lease |
| 前端 | 展示配置、proposal、interaction、environment operation 和 replay | 只消费 HTTP/durable projection，不以 SSE 生命周期猜测状态 |

## 功能需求

| ID | 需求 |
|---|---|
| FR-001 | 系统必须支持 `.labex-agent/project/**` 非敏感配置包，并只允许 manifest 可追溯的声明文件。 |
| FR-002 | 配置必须经过严格 schema、路径、引用、canonicalization 和 digest 校验；外部语义修改必须进入 `external_change` review。 |
| FR-003 | 系统必须持久化单调 project revision、proposal、append-only audit 和 Git/change evidence。 |
| FR-004 | 每个 `taskId + executionEpoch` 必须拥有 immutable effective runtime snapshot，恢复不得重新读取可变默认值。 |
| FR-005 | Agent 或设置页只能创建 proposal；只有 authenticated project owner 的未过期 CAS decision 能应用精确 digest。 |
| FR-006 | 秘密必须通过 write-only `secret-input` 和 scoped Worker lease 处理，配置、proposal、snapshot 和普通 interaction 不承载明文。 |
| FR-007 | `AgentCapabilityPolicy` 必须同时决定模型可见 schema 和实际执行，platform ceiling 永远优先。 |
| FR-008 | 命名 Agent、模型、MCP、Skill 和项目脚本工具必须从当前 epoch snapshot 解析并隔离，不能跨项目共享动态 authority。 |
| FR-009 | Worker 必须支持 `strict`、`integrated-wsl`、受 gate 保护的 `integrated-windows` 和显式高风险 `full-access`。 |
| FR-010 | 环境探测、项目依赖修复和系统级操作必须使用 bounded durable environment operation。 |
| FR-011 | 自动 `wsl --shutdown` 只能在复合 owner approval、global maintenance lease、完整 drain 和健康恢复条件下由控制平面执行。 |
| FR-012 | 恢复必须使用 server-owned dispatch claim、ExecutionFence、持久化 mode/model 和周期性 expired-lease reconciliation。 |
| FR-013 | 前端必须提供模块化配置中心，并从 HTTP history、event replay 和 Part/interaction reducer 重建一致状态。 |
| FR-014 | 迁移必须受 feature flag 和可观测退出条件控制，旧读取只能作为有删除条件的兼容路径。 |

## 非功能需求

| ID | 需求 |
|---|---|
| NFR-001 | 所有 API、proposal、interaction、task 和资源读取必须校验 authenticated owner/project，外部 ID 视为不可信。 |
| NFR-002 | 所有 state-changing 操作必须使用稳定幂等键、CAS/事务和 durable event/outbox，重复提交不得产生重复副作用。 |
| NFR-003 | 秘密不得进入项目文件、proposal、snapshot、interaction response、transcript、Part、event、outbox、SSE、命令行或日志。 |
| NFR-004 | SSE 断开、刷新、进程重启、lease 过期和跨 JVM 接管后，状态必须能从数据库事实重建。 |
| NFR-005 | 未知配置、缺失资源、不健康 Worker、过期 lease、stale epoch 和 sandbox 不可用必须 fail closed。 |
| NFR-006 | 数据库变更必须 additive、幂等、保留数据，禁止 destructive migration/reset。 |
| NFR-007 | 前端必须支持键盘操作、关联 label、明确 loading/error/empty/success 状态，并保持桌面/移动端可用。 |
| NFR-008 | 审批、状态迁移、环境操作和兼容读取必须可审计且不记录敏感内容。 |
| NFR-009 | 实施必须保留脏工作区中的用户改动，不得 reset、clean、覆盖或暂存无关文件。 |
| NFR-010 | 所有派生视图必须可由权威事实重建，不能反向成为第二事实源。 |

## 目标

让每个项目拥有一套可审阅、可版本化、可恢复的 Agent 配置。用户可以在项目级配置中调整 Agent 参数、模型选择、工具能力、Skill、MCP、运行环境和有限的环境修复策略。

完成后，受信任项目可以在用户实际选择的 Windows 或 WSL 工具链中执行构建、测试和开发服务器，而不是被强制放在与真实开发环境不一致的最小隔离环境里。安全性通过平台硬边界、操作系统 sandbox、明确的审批、秘密隔离、durable task 和 Git/change-set 证据保持。

这不是把 Agent 变成拥有宿主机全部权限的自动脚本，也不是引入第二套 Agent runtime。现有 `AgentTask`、`AgentRunMessage`/`AgentRunPart`、`AgentRunEvent`/outbox、interaction、Worker 和前端 durable reducer 仍然是各自领域的唯一事实源。

## 设计原则

- 项目非敏感配置文件是用户可读、可提交 Git 的配置源。
- 数据库只保存配置 revision、审批、审计、运行快照和环境操作事实；这些数据不能被前端缓存或 Agent 内存状态取代。
- 每次 `taskId + executionEpoch` 使用一份 immutable runtime snapshot。恢复时创建新 epoch 和新 snapshot，旧 snapshot 不修改。
- 项目配置只能在 platform capability ceiling 内扩展能力，不能放开秘密读取、metadata、跨用户数据、未审批的宿主写入等硬拒绝。
- Agent 可以提出配置变更，不能自行制造批准事实；只有当前项目所有者的持久化决策可以应用 proposal。
- 所有 state-changing 操作使用稳定幂等键、CAS 和 durable event/outbox；SSE 断开不等于任务完成。
- 兼容路径必须写明读取方向、退出条件、删除版本和验证命令，不保留没有删除条件的永久 fallback。

## 权威状态与模块所有权

| 事实/能力 | 唯一所有者 | 禁止成为权威来源 |
|---|---|---|
| task 状态、epoch、lease | `AgentTask` + `AgentRunLifecycleService` | controller、tool、SSE、前端状态 |
| transcript、tool call/result、Part | `AgentRunMessage` + `AgentRunPart` | Provider 请求临时 Map、UI DTO、日志 |
| 可重放事件 | `AgentRunEvent` + transactional outbox | 仅内存 publisher、EventSource 连接 |
| 配置 head/revision | `.labex-agent/project/**` + accepted revision | 前端草稿、AgentContext、任意 DB JSON 覆盖 |
| proposal decision | `t_agent_project_config_proposal` + audit | interaction response、隐藏按钮、permission cache |
| task effective config | `t_agent_run_config_snapshot` | 当前用户默认、请求 model ID、可变 catalog |
| 配置等待投影 | durable interaction + Tool Part | Promise、SSE 回调、内存 waiter |
| 能力判定 | immutable snapshot + `AgentCapabilityPolicy` | mode 字符串 allowlist、ToolRegistry 动态全局 Map |
| 环境操作 | `t_agent_environment_operation` + lifecycle/lease | shell 输出、前端进度、Agent 自述 |
| 兼容退出与 feature gate | 现有 `AgentLegacyMigrationReadinessService`/`AgentLegacyMigrationGateService` + project-config contributor | application.yml 单独的 flag、前端按钮、内存计数 |
| 文件与验证证据 | workspace + Git/change-set + verification | diff 文本、前端缓存 |

## 配置包

### 项目文件布局

首版使用 JSON 作为机器配置格式，Markdown 只用于用户可读的 Agent/Skill 指令。项目可以提交以下目录：

```text
.labex-agent/
  project/
    agent.json
    agents/*.json
    tools/*.json
    mcp/*.json
    skills/*.md
    environment.json
```

`agent.json` 是入口 manifest，声明 schema 版本、默认 Agent、引用的模型/Skill/MCP、tool capability policy、runtime profile、network policy、verification policy 和环境模板。子文件用于拆分命名 Agent、沙箱脚本工具、MCP endpoint 配置和项目 Skill；所有引用必须能从入口 manifest 追溯。

配置文件只能包含非敏感值，例如模型名、base URL、参数上限、工具 allowlist、脚本路径、环境变量名称、安装策略和 `credentialAlias`。API key、Authorization header、数据库密码、JWT secret 和 Worker lease token 不得写入配置文件。

### Schema 与外部修改

- 每个配置文件使用显式 `schemaVersion`；服务端按 allowlist 校验字段、类型、枚举和引用范围。
- 未知字段默认拒绝，不能静默忽略未知安全配置。
- `.labex-agent/project/**` 由配置服务使用原子写入和 Git/change-set 证据更新，不能通过普通 Agent 写文件路径绕过 proposal。
- 用户在外部编辑器直接修改项目配置时，服务端以文件 tree digest 检测变化；变化必须先进入 `external_change` review，不能被任务静默接受。
- 配置加载时生成 canonical JSON 和 digest。格式化差异不产生变更，语义变化产生新 revision。
- 配置目录受到项目路径策略保护，普通 `write_file`、`edit_file` 和 `apply_patch` 不能直接修改受保护配置。

### 目录与项目覆盖

现有用户级模型、MCP、Skill 表保留为用户-owned catalog，用于存放可复用资源和服务端管理的秘密引用。项目配置是实际启用范围和 Agent 行为的唯一有效选择；项目可以引用 catalog 项并覆盖非敏感参数。引用时必须校验 owner、project scope、enabled 状态和配置 digest，恢复时不能重新读取一个已变化的默认配置。

## 持久化模型

### 项目配置 revision

新增 `t_agent_project_config_revision`，保存项目配置文件的一次已验证 revision：

- `project_id`、`student_id`、单调 `revision`、canonical `config_digest`。
- Git commit/tree 或受控文件 tree 引用、schema 版本、验证结果和来源 actor。
- 可重建的 normalized config snapshot，用于审计和任务创建时的确定性读取；它不是可脱离项目文件任意编辑的第二配置源。
- 唯一约束为 owner/project/revision；应用 revision 使用项目所有权校验和 CAS。

### 配置 proposal

新增 `t_agent_project_config_proposal`，保存一次待决策变更：

- `base_revision`、候选 config digest、文件 patch/tree digest、变更路径摘要和 reason。
- `origin_task_id`、`origin_execution_epoch`、`origin_tool_call_id`、source 和 creator。
- 状态：`pending`、`approved`、`rejected`、`expired`、`stale`、`applying`、`applied`、`failed`。
- 过期时间默认 1 分钟，decision idempotency key、决策 actor、决策时间和 applied revision 引用。
- 不保存秘密值；需要秘密的字段只保存 `secretInputId`、alias 和 configured flag。

proposal 应独立于 `AgentRunInteraction` 存在，因为它可能在任务、SSE 连接或浏览器页面生命周期之外继续有效。若 proposal 阻塞当前任务，interaction 只保存 proposal ID、task ID、epoch、状态和过期时间，作为等待投影而不是事实源。

### 外部修改待审事实

新增 `t_agent_project_config_external_change` 保存受保护配置树被外部编辑器修改后的待审事实：project/owner、accepted base revision、observed tree digest、redacted changed-path summary、detected time、状态和 materialized proposal ID。`AgentProjectConfigRevisionService` 只负责检测并持久化 `external_change_pending`，不能直接写 proposal decision；`AgentProjectConfigProposalService` 在 owner 请求或受控 reconciliation 中把该事实 materialize 为 `external_change` proposal。配置 revision 在 proposal apply 前保持原 accepted head，任务 snapshot 继续被阻塞。

### 配置审计

新增 append-only `t_agent_project_config_audit_event`，记录 proposed、approved、rejected、expired、stale、applied、conflicted、failed 和 external-change-detected 等事件。

审计保存 actor、reason、previous/next status、before/after digest、changed paths、project、task、epoch 和时间。不得保存完整秘密、原始 Authorization header、完整命令参数或未经脱敏的 provider 响应。

### 运行快照

新增 `t_agent_run_config_snapshot`，唯一键为 `task_id + execution_epoch`。快照至少包含：

- project config revision/digest 和完整 effective non-secret configuration。
- 选中的模型配置 revision/fingerprint、Provider 参数、context budget 和 prompt/tool policy。
- 生效的 tool capability policy、Skill/MCP 引用及其 digest。
- runtime profile、network policy、verification policy 和 environment operation 引用。
- secret aliases 和 configured 状态，不包含秘密明文或可解密材料。

恢复任务时必须先完成 execution fence，再加载与该 epoch 对应的 snapshot。不能从请求中的 model ID、当前用户默认值、可变 `AgentContext` 或前端状态重建生效配置。

### 环境操作

新增 `t_agent_environment_operation`，保存环境探测、项目/用户级安装、健康检查和宿主维护操作：

- operation type、project scope 或 host scope、requested config digest、attempt、deadline、状态和结果摘要。
- 状态：`planned`、`waiting_approval`、`draining`、`executing`、`health_check`、`recovering`、`completed`、`failed`、`cancelled`。
- task 关联只用于恢复和展示；host-scope 操作必须额外拥有全局 maintenance lease。
- 每一步必须有 bounded timeout、幂等键和安全的失败分类。

## 提案、审批和秘密流程

### 普通配置变更

1. Agent 或设置页生成完整候选配置和 patch，不直接写受保护配置文件。
2. 服务端验证项目 ownership、schema、引用资源、platform ceiling、路径和 digest。
3. 服务端创建 proposal、配置 interaction（若阻塞任务）、Tool Part 投影、审计事件和 outbox。
4. 项目所有者在 1 分钟内批准或拒绝；重复决策返回第一次结果，不重复应用。
5. 应用事务锁定项目配置 head，要求 `base_revision == current_revision`，原子写入受保护文件、生成下一 revision、CAS proposal、追加 audit 和 outbox。
6. 如果 proposal 来源任务仍需继续执行，应用完成后创建新的 execution epoch 和 runtime snapshot；独立设置页变更不凭空创建 task epoch。正在执行的 tool batch 不中途切换 capability。

### 秘密输入

- UI 使用专用 secure-input card；普通 question、tool argument、Markdown 和 proposal JSON 不承载秘密。
- `secret-input` 请求绑定 project、proposal、field/input ID、owner、TTL 和一次性幂等键。
- 服务端收到值后立即写入现有 SecretStore 或其 project-scoped 扩展，随后清除请求对象中的明文引用。
- Provider 请求只得到受控的短时 secret lease；Worker 只收到必要的环境变量或文件句柄，并在 run 结束后清理。
- transcript、AgentRunPart、interaction response、event、outbox、SSE、日志和错误消息只出现 alias、presence flag、digest 或字段 ID。

### 配置应用的 authorization

配置 UI 的“批准”不是普通文件写入权限，也不是对所有后续命令的永久授权。它只允许指定 proposal digest、指定项目、指定 revision 的一次应用；环境安装、网络访问、宿主维护仍由 capability policy 和独立审批继续约束。

## 执行 profile 与环境自愈

### Profile

- `strict`：未信任项目或安全能力不足时使用现有强隔离 WSL/Docker。
- `integrated-wsl`：用户信任后使用指定 distro、完整项目工具链和 `/mnt/d` workspace；保留 workspace、网络和 secret 边界。
- `integrated-windows`：使用新增原生 Windows sandbox helper；在 helper 未通过验证前不可启用。
- `full-access`：显式高风险模式，界面、日志和每次任务都标识无 sandbox；不是默认，也不能被项目文件自行开启。

### 网络和文件边界

- trusted project 默认允许公网依赖下载和 localhost；LAN、非回环监听和其他项目访问需审批。
- metadata endpoint、跨用户数据、控制平面 secrets 和 `.labex-agent` 保护目录永远拒绝。
- 项目配置只能请求平台允许的 capability，不能修改 platform ceiling。
- 所有文件改动保留 workspace、Git/change-set 和 verification evidence；Agent 自述和 diff 文本不作为事实。

### 项目级环境修复

- 自动修复优先使用项目/用户级 package manager、项目缓存、虚拟环境和确定性探测脚本。
- `apt`、`winget`、系统 PATH、系统证书、服务和驱动属于更高风险操作，需要单独审批。
- 每次修复记录依赖检测输入、实际命令 digest、结果、attempt 和验证证据；不能用无限 retry 掩盖失败。
- 普通 WSL 任务不直接执行全局 `wsl --shutdown`。

### 自动 WSL shutdown 的受控流程

用户选择保留自动 shutdown，因此将它实现为控制平面的高风险 host-scope operation，而不是普通 Agent tool：

1. Agent 只提出 `.wslconfig` 结构化变更 proposal，服务端校验官方键 schema，拒绝未知键和完整文本覆盖。
2. 项目所有者批准绑定配置 digest、影响范围和 drain 条件的高风险复合 proposal；系统据此创建 host-scope environment operation。维护 interaction 是这次持久化决策的等待投影，不是用户看不见的第二次批准。
3. 控制平面获取全局 maintenance lease，停止接收新的 WSL task、terminal、LSP 和 MCP 工作，并等待已有 lease 排空。
4. 到达 drain deadline 仍有不可停止资源时，operation 失败并取消 shutdown；不能强行杀死未知资源。
5. drain 成功后由受控 host adapter 自动执行 `wsl --shutdown`，记录命令摘要、开始/结束时间和 host identity，不把命令交给模型。
6. 系统等待 WSL distro、workspace、MCP、LSP 和健康探针恢复；失败进入 `failed` 或 `waiting_environment`，不发送伪终态。
7. 恢复受影响任务时，每个任务重新 claim 新 execution epoch 和 lease，重新生成 runtime snapshot，再进入 `preparing`。

该流程的自动化不等于无审批：用户决策、global drain、maintenance lease、超时取消和恢复结果都是强制条件。

## 生命周期和恢复

### 恢复顺序

恢复必须按以下顺序执行：

1. 验证 authenticated owner、task/project 归属、稳定恢复键和当前等待原因。
2. 事务性 claim task、interaction/proposal 和 execution lease，生成新 epoch。
3. 校验并加载该 epoch 的 immutable runtime snapshot。
4. 获取 workspace admission 和 Worker lease，准备 scoped secret lease。
5. 从 durable Message/Part 重建 transcript、prompt、tool schema 和 capability policy。
6. `recovering -> preparing -> running` 后才允许调用模型或工具。

必须修复的当前问题包括：

- 公共 `/agent/stream` 不能接受 `resumeTaskId`/`resumeInteractionId` 作为内部恢复指令。
- `AgentRunInteraction` claim 必须同时锁定并校验 task、project、owner、latest interaction、waiting state、TTL 和未消费状态。
- 恢复不能重新加载当前可变模型默认值，也不能在资源缺失时静默 fallback。
- `plan_exit` 必须持久化 mode，并触发下一轮 schema/prompt 重建。
- 所有 transcript、Part、lifecycle、artifact、plan 和 completion 写入都必须带 `ExecutionFence`。
- 必须有周期性 expired-lease reconciler，不能只在 `ApplicationReadyEvent` 扫描一次。

### 状态转换

```text
RUNNING[e]
  -> WAITING_ENVIRONMENT[e]
  -> RECOVERING[e+1]
  -> PREPARING[e+1]
  -> RUNNING[e+1]
```

配置或环境输入本身不创建 epoch。只有经过 owner/status/TTL/CAS 校验的恢复 dispatch 才能创建新 epoch。状态机必须允许 `RECOVERING -> PREPARING`，并允许准备阶段按受控原因回到 `WAITING_ENVIRONMENT`。`PREPARING` 阶段发生可重试环境失败时回到 `WAITING_ENVIRONMENT`；完成、失败、取消等终态不可回到 running。

## API 与事件契约

### HTTP API

| Method | Path | 责任 |
|---|---|---|
| `GET` | `/student/projects/{projectId}/agent/config` | 返回 redacted document、revision/digest、validation、trust/runtime、资源引用和环境状态 |
| `GET` | `/student/projects/{projectId}/agent/config/proposals` | 列出 owner 可见的 proposal 摘要 |
| `GET` | `/student/projects/{projectId}/agent/config/proposals/{proposalId}` | 返回 redacted diff、digest、状态、expiry 和 audit 引用 |
| `POST` | `/student/projects/{projectId}/agent/config/proposals` | 以 expected revision、candidate/patch、reason 和幂等键创建 proposal |
| `POST` | `/student/projects/{projectId}/agent/config/proposals/{proposalId}/decision` | 对精确 proposal 执行 owner-only approve/reject CAS |
| `POST` | `/student/projects/{projectId}/agent/config/proposals/{proposalId}/secret-input` | 写入一次性秘密并只返回 alias/configured 状态 |
| `GET` | `/student/projects/{projectId}/agent/config/audit` | 返回 append-only redacted audit projection |
| `GET` | `/student/projects/{projectId}/agent/environment` | 返回 durable environment operation 和 maintenance 状态 |

公共 `/agent/stream` DTO 不得包含 `resumeTaskId`、`resumeInteractionId` 或 `resumeNote`。内部恢复请求只能由 scheduler/continuation factory 构造。所有 owner/project/actor/task/epoch 均由 authentication、route 和持久化事实推导，不能信任请求体提供的权威字段。

### Durable Events

| Event | 最小 payload |
|---|---|
| `CONFIG_PROPOSAL_CREATED` | proposal ID、digest、changed paths、expiry、task/epoch/toolCall IDs |
| `CONFIG_PROPOSAL_DECIDED` | proposal ID、decision status、actor reference、decision time |
| `CONFIG_REVISION_APPLIED` | proposal ID、revision、before/after digest、evidence reference |
| `CONFIG_PROPOSAL_FAILED` | proposal ID、safe reason code、terminal proposal status |
| `ENVIRONMENT_OPERATION_STATUS` | operation ID、type、scope、status、attempt、safe result/failure digest |
| `RUN_MODE_CHANGED` | task ID、epoch、previous/next mode、idempotency key reference |

事件、interaction 和 Part 只包含 ID、状态、digest 和安全摘要；所有新增字段必须同步 schema、entity/DTO、event persistence、history/live reducer、测试和文档。

## 错误与失败语义

- ownership、foreign project/task/proposal/resource 统一返回安全的 not-found/4xx 语义，不泄露对象存在性。
- stale revision、digest 或 decision CAS 返回 HTTP `409`；不能仅在 `Result` body 中写 409 而保持 HTTP 200。
- invalid schema/path/reference/capability 返回结构化字段路径和 reason code，不回显秘密或完整 provider/host 输出。
- stale `ExecutionFence`、过期 lease、缺失 snapshot/model/resource 和不健康 Worker 返回 typed failure，并且不得发生部分持久化。
- retryable environment failure 进入 `waiting_environment`；non-retryable failure 持久化后停止，不能发送伪完成事件。
- shutdown drain 超时必须取消 shutdown；health check 失败保持 failed/waiting 状态，不恢复为 running。

## 前端投影

- `useProjectAgentConfig` 负责 project/revision/proposal/environment API 状态，不新增全局 Pinia Agent store。
- `AgentConfigPanel.vue` 编排子组件：`AgentConfigEditor`、`ConfigProposalCard`、`ConfigDiffViewer`、`TrustRuntimeCard`、`SecureSecretInputCard`、`EnvironmentOperationStatus`。
- 活动任务中的阻塞 proposal 由现有 durable interaction/tool Part reducer 投影，沿用 `ToolCallCard.vue` 的批准状态语义。
- project settings 页通过 HTTP 查询 revision/proposal/audit；任务 timeline 通过持久化 event replay，不从 EventSource 的 open/close 推断结果。
- 所有新增事件同步更新后端 DTO、持久化 event、Run Part projection、history reducer、live reducer 和 API wrapper。

## 迁移顺序

### Phase 0：先固定安全和恢复失败

新增 direct-resume、stale-epoch、interaction ownership/status、模型 fallback、秘密泄漏和 plan mode durability 的 characterization/regression tests。此阶段不改变用户可见配置语义。

### Phase 1：配置读取和快照

新增 schema parser、项目 config reader、revision/digest、resource reference validation 和 `taskId + epoch` runtime snapshot。先双读/双写并 shadow compare，不能直接删除旧 `request_payload` 读取。

### Phase 2：proposal 和审批

新增 proposal service、CAS apply、audit、config interaction projection 和专用 secret-input。批准只应用精确 digest；过期、冲突、拒绝和重复决策必须幂等。

### Phase 3：Agent 能力收敛

把 model、MCP、Skill、tools、permissions、runtime、verification 统一从 snapshot 读取。建立单一 `AgentCapabilityPolicy`，删除 mode allowlist 与执行门禁的并列判断。

### Phase 4：实际环境 Worker

先实现 integrated WSL、项目依赖修复和 health probes；所有 environment operation 有 bounded attempts、durable result 和恢复测试。需要 host maintenance 时走 global drain。

### Phase 5：Windows sandbox

新增并验证 Windows native sandbox helper。验证 ACL、低权限 token、workspace write boundary、网络策略、进程树清理、秘密清理和逃逸尝试；验证前不启用 `integrated-windows`。

### Phase 6：前端配置中心

加入项目配置中心、proposal diff、trust/runtime 选择、secret card、environment status 和 durable replay projection。前端只能消费 HTTP/history/event/Part projection，不能建立第二套 Agent 状态。

### Phase 7：兼容路径退出与 live acceptance

通过 feature flag 分阶段启用能力，并监控旧 plaintext runtime config、模型 fallback、内存 permission Map、old resource read 和 unsnapshotted task。达到明确观察窗口和删除条件后停止旧写入、移除兼容读取，并分别记录源码、数据库、已启动 JVM、浏览器和真实 Worker 证据。

## 验收标准

- **AC-001：** 同一项目可以在 `strict`、`integrated-wsl` 和已验证的 `integrated-windows` profile 中按用户选择运行。
- **AC-002：** Maven、npm、Vite、Spring Boot 等真实命令在 integrated runtime 使用真实工具链完成，并可访问允许的公网/localhost。
- **AC-003：** workspace 外写入、秘密读取、metadata、LAN 和未批准系统操作被拒绝或进入正确 durable interaction。
- **AC-004：** 配置修改可看到完整 redacted diff、base revision、digest、审批人、应用 revision、audit 和验证结果。
- **AC-005：** 同一 proposal、secret input、dispatch、tool call 和 completion 重复提交不会产生重复副作用。
- **AC-006：** SSE 断开、刷新、重启、lease 过期和跨 JVM 接管都能从 durable facts 恢复。
- **AC-007：** 多 tool call、toolCallId、Part 状态、execution epoch 和 proposal 状态在重放后保持一致。
- **AC-008：** WSL 自动 shutdown 只在审批、全局 drain 和 maintenance lease 成功后执行；资源排空失败时不 shutdown。
- **AC-009：** 秘密不出现在 transcript、interaction response、event、outbox、SSE、命令行、Worker output 和日志。
- **AC-010：** 完成报告分别记录源码测试、数据库迁移、已启动 JVM、浏览器和 live runtime 证据，不能把编译成功当成真实环境验证。

## 非目标

- 不引入 LangGraph、LangChain 或第二套 Agent loop。
- 不把全部模型/MCP/Skill 用户目录重写成项目表；项目配置只拥有有效选择和覆盖规则。
- 不允许项目 config 自行提升 platform capability ceiling。
- 不把 `unsafe-local` 包装成安全的 integrated profile。
- 不让普通工具直接执行 `wsl --shutdown`、系统级安装或秘密轮换。
- 不一次性重写 `AgentLoopEngine`、`CloudWorkspace.vue` 或数据库。

## 关键现有边界

- 运行生命周期：`backend/src/main/java/com/labex/labexagent/run/AgentRunLifecycleService.java`
- execution lease：`backend/src/main/java/com/labex/labexagent/run/AgentRunExecutionLeaseService.java`
- interaction：`backend/src/main/java/com/labex/labexagent/run/AgentRunInteractionService.java`
- 项目所有权：`backend/src/main/java/com/labex/service/impl/StudentProjectServiceImpl.java`
- 工具目录：`backend/src/main/java/com/labex/labexagent/tool/ToolRegistry.java`
- Worker：`backend/src/main/java/com/labex/labexagent/worker/`
- SecretStore：`backend/src/main/java/com/labex/labexagent/secret/`
- 前端 durable reducer：`frontend/src/composables/agentHistoryReducer.js`、`frontend/src/composables/useAgentTaskRuntime.js`
- 前端工作区编排：`frontend/src/views/CloudWorkspace.vue`
- 配置和扩展 API：`frontend/src/api/index.js`
- schema：`backend/src/main/resources/sql/schema.sql`
- additive migration：`backend/src/main/java/com/labex/config/AdditiveSchemaMigrator.java`

## 需求追踪

| 需求 | 主要任务 |
|---|---|
| FR-001, FR-002 | 0.0, 1.1, 1.3 |
| FR-003 | 1.2, 1.3, 2.1, 2.2 |
| FR-004 | 0.7, 1.2, 1.4 |
| FR-005 | 2.1, 2.2, 2.3 |
| FR-006 | 2.4, 3.2, 4.2 |
| FR-007 | 3.1 |
| FR-008 | 3.2, 3.3, 3.4 |
| FR-009 | 4.1, 4.2, 5.2, 5.3 |
| FR-010 | 4.3, 4.4 |
| FR-011 | 4.4, 7.2 |
| FR-012 | 0.1-0.8 |
| FR-013 | 6.1-6.3 |
| FR-014 | 7.1, 7.2 |
| NFR-001-NFR-010 | Cross-cutting checks in [checklist.md](checklist.md) and each relevant task |

## 已确认决策

- 项目配置以 JSON 为机器格式，Markdown 仅用于用户可读 Agent/Skill 指令。
- proposal 默认有效期为 1 分钟，平台可配置更短但不能无上限延长。
- `integrated-wsl` 是首个真实环境 profile；`integrated-windows` 在 native helper 证据完成前保持不可用。
- 自动 WSL shutdown 保留，但只能作为受控 host-scope operation；普通工具永远不能直接调用。
- 本规格没有阻塞实现的开放架构问题。实现发现与当前代码冲突时，必须停止并回到规格评审，而不是新增平行 fallback。
