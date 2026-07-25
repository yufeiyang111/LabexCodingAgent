# LabexAgent 工程级 Coding Agent 剩余路线图

> 本文只描述当前代码库在既有工业化工作完成后的**剩余真实缺口**。历史完成项与验证记录见 [2026-07-10-coding-agent-industrialization.md](superpowers/plans/2026-07-10-coding-agent-industrialization.md)。不要重复实现其中已经落地的 worker、路径安全、run 状态机、provider gateway、增量检索、前端拆分、background worktree、subagent 与 evaluation gate。

## 目标

把 LabexAgent 从“主要骨架已经齐全，但关键边界仍有工程债务”的 coding agent，推进到可持续演进、可审计、可恢复、可验证的生产级 harness。参考 Claude Code 的是**设计原则**，不是复制未公开实现：

- 原生结构化工具调用为主，文本恢复路径可控且可观测；
- 权限判断与真正的沙箱安全边界分离；
- 长任务的状态、投递、重放与恢复有明确的持久化语义；
- 高风险工具有行为测试，变更先失败、再实现、后验证；
- 每个阶段都以文档、测试和验收证据驱动，不靠“代码看起来完成了”。

## 文档驱动开发协议

每个阶段必须按以下顺序推进：

1. **现状核验**：重新读取目标代码、现有测试和上一阶段输出；不复用过期假设。
2. **独立设计 spec**：保存到 `docs/superpowers/specs/YYYY-MM-DD-<phase>-design.md`。
3. **用户审核设计**：设计未批准，不写实现计划、不改生产代码。
4. **详细实施 plan**：保存到 `docs/superpowers/plans/YYYY-MM-DD-<phase>.md`；每项任务必须包含完整 Agent 提示词、精确文件、命令、预期结果和禁止事项。
5. **隔离执行**：优先用独立 worktree；当前主分支存在大量未提交修改时，绝不覆盖、还原或清理用户工作。
6. **逐任务 TDD**：行为变更必须 RED → GREEN → REFACTOR；测试基座阶段属于只新增测试的 characterization 工作，不修改生产行为。
7. **双重审查**：每个 task 先做 spec 合规审查，再做代码质量审查；两者通过后才能进入下一个 task。
8. **阶段验收**：运行目标测试、相关子系统测试、完整后端测试；记录命令、总数、失败、错误、跳过和环境限制。
9. **更新路线图**：只在有新鲜验证证据后，把阶段状态改为完成。

### 阶段证据记录

每个阶段在进入实现前和退出时，在对应 plan 或单独 exit report 中记录：

- 阶段开始时的 `HEAD` SHA 与 working-tree 状态摘要；
- 实际执行的验证命令、退出码、tests/failures/errors/skips；
- 与基线相比的新增或消失项；
- 环境限制和遗留风险。

这是一份轻量证据区，不额外引入新的通用状态机；既有“路线图 → spec → 用户批准 → plan → 实现/双审查 → 验收”协议保持不变。

### Agent 提示词最低信息集

每个 task 的实现提示词必须自包含，至少写明：

- 角色与目标；
- 当前仓库路径和技术栈；
- 唯一允许修改/创建的文件；
- 必须先读的参考文件；
- 当前行为矩阵与期望行为；
- 明确禁止事项；
- 精确验证命令与预期输出；
- 要求返回的结构化报告（状态、文件、测试、发现、风险）；
- 若代码与计划矛盾，停止并报告，禁止猜测。

---

## 阶段关系

```text
Phase 0 测试基座 ──> Phase 1 命令安全与审批语义
Phase 2 Durable Outbox 与恢复语义（复用现有 outbox 测试，独立按 TDD 补故障场景）
Phase 3 工具调用恢复路径鲁棒性（复用现有 provider 测试，独立按 TDD 补解析/runtime 场景）

默认实施顺序为 0 → 1 → 2 → 3；Phase 2/3 不把 Phase 0 五类工具测试误作自身技术前置。
```

---

## Phase 0：核心工具测试基座

**状态：** 设计已批准，详细计划待执行。

**设计：** [2026-07-17-phase0-tool-test-coverage-design.md](superpowers/specs/2026-07-17-phase0-tool-test-coverage-design.md)

### 完成目标

- 为 `EditFileTool`、`WriteFileTool`、`BashTool`、`ApplyPatchTool`、`ProjectCommandSafety` 建立专属测试文件。
- 覆盖参数校验、路径拒绝、diff 调用参数、审批状态、timeout clamp、shell 包装、取消令牌、批量 patch 的跳过和部分应用语义。
- 用 characterization tests 锁定已知问题，但不修改任何生产代码。
- 记录基线测试中的 Windows 临时目录清理错误，明确区分新增失败与既有环境失败。

### 阶段退出条件

- 5 个测试文件存在且目标测试全部通过。
- `git diff -- backend/src/main/java` 证明阶段未修改生产代码。
- 完整 `mvn test` 无新增失败；如果既有 `SandboxWorkerContractTest` 临时目录清理错误仍存在，单独记录并用“排除该已知测试后的完整套件”作为补充证据，不能写成“全绿”。
- 每个已知缺陷测试的方法名或注释指向后续阶段。

---

## Phase 1：命令安全与审批语义重构

**依赖：** Phase 0 完成。

### 问题边界

当前 `ProjectCommandSafety` 是 `contains(...)` 子串匹配；编码、引号、环境变量展开等可绕过。更关键的是 `BashTool`/`RunCommandTool` 的 `allow_dangerous` 由模型工具参数控制，这不应等价于用户审批。沙箱是真正的执行安全边界；命令分类应是权限与 UX 边界，不能伪装成不可绕过的安全控制。

### 完成目标

1. **角色分离**
   - 将命令风险分类（NORMAL / REQUIRE_APPROVAL / DENY）与用户批准记录分开。
   - 按实际执行的 shell/平台选择显式解析与规范化策略；无法可靠解析、含编码载荷或动态展开的命令默认 DENY 或 REQUIRE_APPROVAL，不承诺在控制平面完整模拟 shell。
   - `StudentProjectController`、`AgentLoopEngine`、`RunCommandTool` 以及保留的 `BashTool` 使用同一决策服务与分类结果。
   - 模型不能通过工具参数自我批准。
   - `DENY` 仅保留少量不应在任何工作区执行的控制平面风险；大多数高风险行为由真实人机审批 + 沙箱约束处理。

2. **审批绑定**
   - approval 绑定 `runId + toolCallId + normalized command digest + userId + deadline`。
   - 命令在审批后若任何字节变化，旧批准失效。
   - 重试和恢复幂等，不重复执行同一已批准调用。

3. **策略透明**
   - `ProjectCommandSafety` 不再声称可检测所有 shell 绕过。
   - 记录 matched policy、risk class、审批来源；不记录秘密值。
   - 提供跨 shell（POSIX / cmd / PowerShell）策略测试，但不试图构建完整 shell 解释器。

4. **沙箱 fail-closed**
   - 生产 profile 继续强制 Docker worker；本地默认 WSL/bwrap。
   - 未配置隔离 worker、审批状态丢失或命令摘要不匹配时拒绝执行。

5. **测试**
   - 把 Phase 0 中锁定的可绕过 characterization tests 改成新的明确语义测试。
   - 添加“模型传 allow_dangerous 无效”“用户审批绑定原命令”“审批后变更命令被拒绝”“重放不重复执行”测试。
   - 增加统一的跨入口契约测试，覆盖 base64 管道、`$IFS`、引号拆词、链式命令、重定向、子 shell、PowerShell `-EncodedCommand`/`-enc`，并断言 controller、AgentLoopEngine、RunCommandTool/BashTool 分类与审批语义一致。

### Phase 1 实施证据（当前工作区）

已实现并在当前工作区验证：受限 direct-argv 分类与规范化 digest、模型 `allow_dangerous` 无效、一次性 owner-scoped approval、完整绑定 CAS consume、Agent/managed-terminal 决策与执行路径、provider/recovered tool-call identity、`RunTestsTool` 无 shell wrapper、WebSocket interactive terminal 服务端禁用、network deny、metadata-only command audit、Agent lifecycle/outbox command outcome、以及 terminal/API/SSE 公共输出脱敏。

最新验证：focused command/approval suite 30 tests（0 failures/errors/skips）、frontend test 44 tests（0 failures/errors/skips）、frontend production build 通过；完整 Maven suite 396 tests、0 failures、0 errors、7 existing environment/platform skips。H2 并发测试证明同一 exact binding 的 12 个并发 consume 尝试恰好一个成功。对已有 MySQL 数据库，`AdditiveSchemaMigrator` 会在缺失时创建 metadata-only `t_command_audit_event`。terminal retained output 采用有界 stateful redactor，覆盖 token 跨 output chunk 的拆分场景。H2 仅证明 SQL/CAS 语义；MySQL/InnoDB 的生产锁竞争仍应在部署环境或 Testcontainers 基础设施中额外验证。

命令分类是 authorization/UX 边界，不替代 Docker/WSL/bwrap sandbox；审批不改变网络拒绝策略。已 consume 的命令在进程崩溃或结果丢失后绝不自动重放，系统记录 interrupted/failed 事实，用户必须启动新的 Agent run 重新评估工作区。

### 阶段退出条件

- 任何 LLM 生成的工具参数都不能生成审批事实。
- 用户批准记录可持久化、过期、审计、绑定命令摘要。
- 新安全策略测试、permission 测试、Bash/RunCommand 测试与完整后端测试通过。
- 文档明确：命令分类不是沙箱替代品。

---

## Phase 2：Durable Outbox 与进程恢复语义

**依赖：** 复用既有 run lifecycle / outbox 单测；该阶段按自身 TDD 新增故障场景与集成测试。默认在 Phase 1 后实施；不要与其他阶段并行修改同一 run lifecycle 文件。

### 问题边界

数据库 outbox 行和轮询 publisher 已存在，但 `InProcessAgentRunOutboxSink` 最终只调用 Spring `ApplicationEventPublisher`。这可以做单实例实时通知，却不能提供跨进程、跨实例或消费者确认意义上的 durable delivery。必须先明确事件类别，而不是为了“像生产”盲目加 Redis/Kafka。

### 完成目标

1. **事件分类**
   - `RunDomainEvent`：状态机与事实，必须持久化、可重放。
   - `RunDeliveryEvent`：SSE/前端即时通知，可丢后从 durable event replay 恢复。
   - `RunWorkCommand`：驱动后台继续执行，必须至少一次投递、幂等消费。

2. **默认实现与生产实现**
   - 单实例开发模式允许 DB outbox + in-process wake-up，但命名和文档不能宣称外部队列保障。
   - 多实例生产 profile 必须配置 durable broker adapter（优先 Redis Streams 或 RabbitMQ，最终在该阶段 brainstorming 时基于部署约束选择一个）；缺失时 fail-closed。
   - `AgentRunOutboxSink` 增加 delivery result / acknowledgment 语义，避免“方法不抛异常就算永久送达”。

3. **幂等与恢复**
   - consumer 使用 event id / idempotency key 防重复执行。
   - claim、lease、retry、dead-letter/terminal-failure 有状态与指标。
   - 进程在 publish 前、publish 后 ack 前、consumer 执行中三个窗口崩溃，都有确定行为。

4. **可观测性**
   - outbox backlog、oldest age、attempt count、dead-letter count、consumer lag 可观测。
   - 不把 SSE 在线连接当 durable consumer。

5. **测试**
   - 数据库事务测试验证状态转换与 outbox 原子写入。
   - duplicate delivery、restart recovery、lease expiry、poison message、broker unavailable 的集成测试。
   - 至少增加一个真正加载 Spring transaction + test DB 的集成测试；这一阶段不再受 Phase 0 “纯单测”限制。

### 阶段退出条件

- 文档和代码对“持久状态”“实时通知”“后台工作命令”的交付保证分别有清晰定义。
- 单实例和生产多实例 profile 有明确、可验证的启动约束。
- 崩溃窗口测试证明无永久丢失；允许重复但无重复副作用。
- outbox 相关指标和告警阈值落地。

---

## Phase 3：工具调用恢复路径鲁棒性

**依赖：** 复用既有 provider gateway contract tests；该阶段按自身 TDD 新增 parser/runtime 端到端样本。默认在 Phase 2 后实施，且不得与 Phase 2 并行修改主循环/恢复相关文件。

### 问题边界

原生 OpenAI-compatible `tool_calls` 流式 delta 是主路径，方向正确；`ToolCallExtractor` 是模型把工具意图吐成普通文本时的兜底。当前兜底依赖 XML/中文短语/工具名扫描和手写首个 JSON 对象提取，容易误触发、只能恢复单调用，且缺少专属测试。目标不是让文本解析取代原生 function calling，而是把兜底限制成可预测、可观测、可关闭的恢复机制。

### 完成目标

1. **Native-first 契约**
   - provider capability 明确是否支持 native tool calls。
   - 支持 native 时，文本恢复默认只处理严格框定格式，不扫描任意自然语言中的 `toolName(`。
   - native tool call 半截断流时返回 typed incomplete-call error，不静默执行猜测参数。

2. **恢复解析器**
   - 使用独立 parser + typed result：NONE / VALID / INCOMPLETE / AMBIGUOUS / INVALID。
   - 只解析允许的明确 envelope；完整 JSON 后才可形成待执行调用。
   - schema validation 在进入 `ToolRegistry` 前完成；未知字段/缺失必填字段按 tool definition 报告。
   - 多候选时拒绝猜测，要求模型重试结构化调用。

3. **主循环恢复策略**
   - 用命名常量替代硬编码重试上限，删除未使用的 `MAX_RECOVERABLE_MODEL_ERRORS` 或真正接入。
   - retry/backoff/cancellation 不阻塞错误的执行器线程；明确 first-event timeout、stream interruption、invalid tool args 的不同策略。
   - 每次恢复带 bounded retry budget，避免无限自愈循环。

4. **可观测性**
   - 记录 native success、text fallback attempted/succeeded/rejected、schema error、incomplete stream 计数。
   - 日志不记录敏感 tool arguments；只记录 tool name、call id、错误类别、参数摘要哈希。

5. **测试**
   - `ToolCallExtractorTest` 覆盖嵌套 JSON、转义、代码块、多个候选、截断 JSON、自然语言误触发、未知工具。
   - provider fixture 覆盖 interleaved tool calls、分块 arguments、断流。
   - `AgentLoopEngine` 测试覆盖 fallback budget、取消、typed recovery outcome 和最终失败。

### 阶段退出条件

- native tool call 始终优先；文本兜底不能从普通解释性文本中误执行工具。
- 不完整/歧义调用不会执行。
- 重试上限、退避、取消和错误分类都有专属测试。
- 关键 fallback 指标可用于判断是否该禁用某个 provider/model 的文本恢复能力。

---

## 全局完成定义

只有同时满足以下条件，才能把“工程级 coding agent harness 收口”标为完成：

- Phase 0-3 各自设计、计划、实现和双重审查全部完成；
- 生产 profile 的 worker、approval 和 durable work delivery 均 fail-closed；
- 核心工具、权限、outbox、provider/tool-call、恢复流程有自动化测试；
- 后端完整测试、新增集成测试、生产配置验证与 evaluation gate 有新鲜通过记录；
- 已知环境 skip 和失败被准确报告，没有把“排除失败后通过”写成“全部通过”；
- 未提交用户改动没有被覆盖、删除或混入未经审查的提交。
