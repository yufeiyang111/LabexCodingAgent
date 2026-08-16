# LabexAgent 原生运行时渐进式收敛实施计划

> 状态：设计已获确认；本计划与测试验收文档已进入审阅阶段。
>
> 日期：2026-08-16。
>
> 设计依据：`D:/LabexAgent/docs/superpowers/specs/2026-08-15-labex-native-runtime-convergence-design.md`。
>
> 约束：在现有 `AgentLoopEngine` 入口内渐进演进，不进行全量重写；运行时、API、数据库字段、前端文案不得使用外部参考项目名称。

## 1. 目标与非目标

### 1.1 目标

1. 让每个对话固定选择 `labex-legacy` 或 `labex-native` 运行时，旧会话保持兼容，新会话可以显式选择。
2. 把 `AgentRunMessage`、`AgentRunPart`、`AgentRunEvent` 作为 native 路径的唯一可重放事实，Provider 请求只能由 durable transcript 投影生成。
3. 保留 `AgentLoopEngine` 作为唯一公开入口，但把 native 单回合处理、工具暴露、最终答复判定、工作区变更投影提取为内部深模块。
4. 将工具收敛为清晰的原子能力；普通编程操作保持通用可用，不把 harness 内部状态控制伪装成模型工具。
5. 让模型的最终答复、工具真实结果、任务终态三者分离，阻止工具失败或未执行时的虚假完成声明。
6. 让前端只根据 durable event 与 Part reducer 展示最终答复、计划、工具状态和文件树刷新，不把 SSE 连接生命周期当作执行状态。

### 1.2 非目标

- 不重写 Spring Boot、Vue、SSE、任务恢复、数据库 transcript 或现有 workspace 层。
- 不把 `create_plan`、`plan_exit`、`run_tests` 等控制面工具重新变成 native 模型完成门槛。
- 不限制正常的 `npm install`、`pnpm install`、`pip install`、`git clone`、`git fetch`、构建、测试、lint、启动开发服务、`git add`、`git commit`。
- 不逐行复制任何外部参考实现；仅复刻被验证过的协议、不变量与模块边界。

## 2. 已确认的架构决定

| 决定 | 实现方式 | 不允许的替代方案 |
|---|---|---|
| 两套运行时隔离 | conversation 保存 profile，task 创建时快照 profile，之后不可切换 | 用前端临时状态或 prompt 字符串决定路径 |
| 主入口保留 | `AgentLoopEngine` 只负责生命周期、分派、SSE 与兼容桥接 | 再造第二个 controller、第二个 loop 或并行 transcript |
| transcript 权威来源 | native 回合从 `AgentRunMessage` 与 `AgentRunPart` 投影 | 在 native loop 中长期维护可变 `msgs` 列表 |
| 计划展示 | `AgentRunPlanItem` 与 durable Part 的 UI projection | 强制调用 `create_plan` 或根据模型文本猜计划 |
| shell 能力 | native 只暴露一个通用 shell 执行语义 | `bash`、`run_command`、`execute_code`、`run_tests` 多套重叠入口 |
| 文件写入 | 全量写入与补丁写入职责明确，其他相近 mutation 工具仅保留 legacy 兼容 | 多个无边界的 edit、patch、write 同时向 native 暴露 |
| 扩展能力 | Web、图片、LSP、Skill、MCP 仍是工具，但根据条件按回合挂载 | 每个请求全量加载 schema、Skill 内容和全部 MCP 定义 |

## 3. 不变量与事实所有权

1. `AgentRunLifecycleService` 继续是 `AgentTask.status` 的唯一写入入口；native processor 只能请求迁移。
2. `AgentRunMessage`、`AgentRunPart` 是模型 transcript、tool call、tool result、final candidate 的唯一 durable 来源。
3. 同一 assistant turn 的全部 tool call 必须先持久化，再依照确定性顺序执行；未执行 Part 必须写入 `skipped` 或 `interrupted`。
4. 一个 `toolCallId` 只能对应一次真实副作用；审批、恢复、SSE 重连不能导致重复执行。
5. 每个工具结果必须包含成功或失败、退出码或错误分类、目标身份、可核验的变更或验证证据。
6. native 运行时不能用 Markdown 关键字、回答长度、计划未完成状态或 SSE 关闭作为最终答复阻拦条件。
7. 浏览器刷新和 SSE 断线不是终态；前端必须从 event cursor 与 durable Part 重建。
8. 现有未提交改动是基线，不得 reset、checkout、覆盖或借本次工作清理。

## 4. 目标调用链

```text
HTTP / 流式请求
  -> StudentAgentController
  -> AgentLoopEngine
      -> AgentRuntimeProfileResolver
          -> legacy compatibility path
          -> LabexNativeTurnProcessor
              -> AgentTranscriptProjectionService
              -> ToolExposurePlanner
              -> AgentModelTurnExecutor
              -> ToolRegistry / AgentTool
              -> AgentRunMessage / AgentRunPart / AgentRunEvent
              -> LabexNativeCompletionProjector
  -> AgentSsePublisher
  -> 前端 event reducer / task runtime / workspace refresh
```

`AgentLoopEngine` 不再在 native 路径中承担长期 transcript、工具 schema 拼装、最终文本猜测和 UI 状态推断。新模块只在其内部调用，避免第二套控制面。

## 5. Profile、持久化与兼容迁移

### 5.1 Profile 规则

- 运行时枚举仅使用 Labex 名称：`labex-legacy`、`labex-native`。
- 为 `t_agent_conversation` 与 `t_agent_task` 增加 `runtime_profile` 可空列；历史空值读取为 `labex-legacy`。
- 新 conversation 在创建时接受显式 profile；未指定时读取集中化配置的默认值。切换默认值前必须完成 native 全部验收。
- task 从 conversation 复制 profile；恢复、fork、后台任务、审批恢复均使用 task snapshot，禁止中途切换。
- 对已存在 conversation 再传不同 profile 必须返回明确冲突，不得静默改写。

### 5.2 事实写入与读取方向

| 阶段 | 写入 | 读取 | 退出条件 |
|---|---|---|---|
| Profile 引入 | 新 conversation 与 task 双写 profile | 旧空值回退 legacy | profile 迁移与隔离测试通过 |
| Native turn | 写入现有 Run Message、Part、Event | native 只读 transcript projector | 能完成多 tool call、恢复、压缩 |
| 工具快照 | 在 provider request 对应 Message 或 Part metadata 写 `ToolExposureSnapshot` | 恢复时读取快照而不是重新猜工具集合 | 多次恢复工具集合一致 |
| 目标身份 | 在真实 tool result Part metadata 写 `WorkspaceOperationIdentity` | verification 与 UI 使用同一身份 | 删除、shell、worker worktree 三类测试通过 |
| 最终答复 | 写入 final candidate、accepted、rejected、interrupted Part/Event | 前端只消费 durable 结果 | 重连与最终回复显示测试通过 |

迁移只做加字段、加索引、加 projection 的兼容演进。`schema.sql` 与 `AdditiveSchemaMigrator` 必须同步；不允许 destructive migration。

## 6. 工具收敛与权限边界

### 6.1 Native 核心工具

| 工具 | 唯一职责 | legacy 兼容处理 |
|---|---|---|
| `read_file` | 读取受限工作区内一个文件或范围 | 保留 |
| `glob` | 按模式定位路径 | 保留 |
| `grep` | 文本搜索并返回可截断结果 | 保留 |
| `shell` | 在解析后的 workspace target 内执行普通工程命令 | `bash`、`run_command`、`execute_code`、`run_tests` 不向 native 暴露 |
| `write_file` | 创建或完整替换一个文件 | 保留并明确覆盖语义 |
| `apply_patch` | 原子应用结构化补丁 | `edit_file` 仅保留 legacy 兼容 |
| `question` | 需要用户提供业务选择或不可推断信息时询问 | 保留 |

`todo_write` 仅作为兼容 UI progress projection，native 完成与否绝不依赖它。`create_plan` 与 `plan_exit` 不进入 native schema。

### 6.2 按需扩展工具

- Web Search 与 Web Fetch：由 feature 配置、用户请求和可用 provider 决定；Fetch 保留 URL、超时、尺寸、内网地址防护。
- 图片理解：只有已授权的图片附件和模型能力均满足时才挂载。
- LSP：一个 operation 型工具，根据当前文件语言、可用 client 和合法工作区路径决定是否暴露。
- Skill：请求只暴露紧凑 catalog；模型明确选择后才读取对应 Skill 内容。
- MCP：只将已连接、允许且 schema 可用的 server tool 转换为本回合定义；断线与失效必须产生可解释结果。

每次 Provider 请求持久化 `ToolExposureSnapshot`，记录 profile、模型能力、曝光工具名、schema 指纹、MCP 定义版本和原因。它是恢复的一部分，不是前端缓存。

### 6.3 权限模型

普通工程操作默认允许，前提是目标位于解析后的受控 workspace：依赖安装、clone、fetch、build、test、lint、dev server、普通 Git add 和 commit 不弹确认。需要审批或拒绝的仅限可造成不可逆损失、明显越界或泄密的操作，例如广泛删除、`git reset --hard`、可能覆盖未提交代码的 checkout/clean、工作区外写入、暴露 secret、生产部署和高风险远程执行。规则必须依据解析后的结构化 operation identity，不得只靠命令字符串关键词。

## 7. 工作区目标身份与变更投影

`WorkspaceOperationIdentity` 由一次真实操作解析并传递，至少包含：student、project、conversation、task、epoch、主 workspace root、worker root、resolved workdir、repository root、worktree、HEAD、相对路径集合与稳定 fingerprint。

- shell、文件 mutation、Git snapshot、Diff、verification recorder 共享同一身份对象。
- tool result 记录 before、after、exit status、失败分类、验证证据；模型文本不能替代这些事实。
- 任何影响文件树的成功 mutation 写入 durable `WORKSPACE_CHANGED` 事件，前端按 conversation、task、project guard 调 `loadRoot`。
- 对模型与普通 UI 返回的路径必须使用项目相对路径或受控脱敏形式；完整宿主路径只保留在授权审计上下文。

## 8. Final、任务终态与前端回放

native 把以下概念分离：模型生成最终候选、系统接受最终候选、任务生命周期终态。新增的 durable Part/Event 需要至少表达 candidate、accepted、rejected、interrupted 与理由。

接受 final candidate 的前置条件是：没有未持久化 tool call、没有 running 或 waiting interaction、当前 epoch 有效、必要的 tool result 已落盘。它不依赖文本格式启发式。若工具失败，模型可以说明失败并建议下一步，但系统必须把真实失败证据留在同一回合的 Part 中。

前端 reducer 以 event sequence 和 Part status 为准：accepted final 一定可在断线重连后重建；rejected 或 interrupted 需要展示可理解状态，不得静默吞掉模型已写入的最终文本。

## 9. 分阶段实施与切换门槛

### 阶段 0：基线与特征回归

冻结当前工作树差异，针对删除未生效、final 未显示、工具失败后错误完成、循环保护四个已知事故编写 characterization test。此阶段不改变行为。

### 阶段 A：Profile 与路由

增加 profile enum、字段、迁移、请求 DTO、conversation/task 服务和 profile resolver；在 `AgentLoopEngine` 内建立显式分派，但 native 先由受控测试入口使用。完成后旧 conversation 仍走 legacy。

### 阶段 B：Native transcript 与单回合 processor

引入 `LabexNativeTurnProcessor`、`LabexNativeTranscriptProjector`、`LabexNativeToolBatchExecutor`。重用现有 Run Message、Part、Event、lifecycle、模型 executor，不建第二套存储。完成后支持多 tool call、审批、恢复、上下文压缩和 context overflow 有限处理。

### 阶段 C：核心工具与 target identity

引入 `ToolExposurePlanner`、`ToolExposureSnapshot`、`WorkspaceOperationIdentity`；收敛 native shell 与文件 mutation surface，补齐真实结果、verification 与 workspace change 事件。完成后模型不能仅凭自述宣称删除或测试成功。

### 阶段 D：Final、计划投影与前端回放

引入 native completion projector，修复 final accepted 的 durable replay；前端 reducer、timeline、task runtime、workspace files 只消费事实事件。计划来自 `AgentRunPlanItem` 或 Part projection，不再需要 native plan tools。

### 阶段 E：扩展能力按需挂载

将 Web、图片、LSP、Skill、MCP 接入 snapshot 与 capability planner。完成后默认上下文不再包含无关扩展 schema 或大段 Skill 内容。

### 阶段 F：legacy 收口

监控 profile usage、shadow projection 差异和恢复失败；只在 native 验收稳定后删除 native 不再依赖的 legacy 分支。旧 conversation 的兼容读取必须保留到明确的删除版本。

## 10. 预期文件范围

后端重点：

- `D:/LabexAgent/backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- `D:/LabexAgent/backend/src/main/java/com/labex/labexagent/runtime/AgentTranscriptProjectionService.java`
- `D:/LabexAgent/backend/src/main/java/com/labex/labexagent/run/AgentRunMessageService.java`
- `D:/LabexAgent/backend/src/main/java/com/labex/labexagent/run/AgentRunPartService.java`
- `D:/LabexAgent/backend/src/main/java/com/labex/labexagent/tool/ToolRegistry.java`
- `D:/LabexAgent/backend/src/main/java/com/labex/labexagent/tool/ToolSelectionPolicy.java`
- `D:/LabexAgent/backend/src/main/java/com/labex/labexagent/tool/impl/RunCommandTool.java`
- `D:/LabexAgent/backend/src/main/java/com/labex/labexagent/service/AgentConversationService.java`
- `D:/LabexAgent/backend/src/main/java/com/labex/labexagent/service/AgentTaskService.java`
- `D:/LabexAgent/backend/src/main/java/com/labex/config/AdditiveSchemaMigrator.java`
- `D:/LabexAgent/backend/src/main/resources/sql/schema.sql`

前端重点：

- `D:/LabexAgent/frontend/src/composables/useAgentEventTimeline.js`
- `D:/LabexAgent/frontend/src/composables/agentHistoryReducer.js`
- `D:/LabexAgent/frontend/src/composables/useAgentTaskRuntime.js`
- `D:/LabexAgent/frontend/src/composables/useWorkspaceFiles.js`
- `D:/LabexAgent/frontend/src/views/CloudWorkspace.vue`
- `D:/LabexAgent/frontend/src/components/cloud/PlanDisplay.vue`
- `D:/LabexAgent/frontend/src/api/index.js`

新增模块应归入明确领域包，例如 `runtime/labexnative`、`runtime/profile`、`tool/exposure`，不得新增无归属的全局 helper 或第二套 service registry。

## 11. 外部成熟实现的参考记录

以下仅作为设计与协议参考，运行时代码不使用其命名、字段或复制其实现：

| 参考主题 | 本地参考位置 | 复刻的不变量 | Labex 适配 |
|---|---|---|---|
| 主循环与处理结果 | `D:/opencode/opencode-dev/packages/opencode/src/session/prompt.ts`、`processor.ts` | durable turn、pending tool part、有限 continue/stop | 多用户 task、epoch、SSE 与 MyBatis 持久化 |
| transcript 协议 | `session/message-v2.ts`、`session/llm/request.ts` | assistant tool call 与 tool result 一一配对 | `AgentRunMessage`、`AgentRunPart` projector |
| 工具 registry | `tool/registry.ts:195-305` | 按模型和能力过滤后生成 schema | `ToolSelectionPolicy` 加 exposure snapshot |
| Skill | `tool/skill.ts:9-68` | catalog 与内容读取分离 | 用户 workspace Skill 与授权路径 |
| LSP | `tool/lsp.ts:11-113` | 一个 operation tool、按文件可用性暴露 | lsp4j session manager |
| Web Fetch | `tool/webfetch.ts:13-115` | URL 校验、permission、超时、尺寸 | 现有 Web Fetch 与服务端网络边界 |
| MCP | `mcp/index.ts:116-160,500-609` | 连接状态驱动动态 tool definition | 用户级 MCP server 与 durable snapshot |
| 前端 reducer | `packages/app/src/context/global-sync/event-reducer.ts:187-296` | durable event 归约而非连接猜测 | Vue composable 与 Pinia 状态 |

未复制实质源代码，因此本阶段不需要新增第三方代码声明；若后续确有复制，必须先补充 `THIRD_PARTY_NOTICES.md` 和 MIT 文本。

## 12. 验证、停止与回滚

每个阶段都遵循一条垂直 TDD 切片：先在已确认的公共 seam 写失败测试，再写最小实现，再跑聚焦测试；通过后才进入下一切片。不得横向先写一批假设性测试或先铺大范围重构。

停止门槛：profile 混用、toolCallId 重复副作用、tool result 无目标身份、final 无法 durable replay、SSE 重连丢失状态、旧 conversation 被意外路由到 native 任一出现时，停止扩大范围，先补回归并修复。

回滚：profile 是对话级开关；native 问题只将新建或指定 conversation 路由回 legacy，不修改已存在 transcript，不删除 migration 字段，不以 reset 或丢数据方式回退。

## 13. 完成定义

只有当 TDD 验收文档中所有关键 seam 的红绿回归、后端聚焦测试、完整后端测试、前端 reducer 测试、浏览器 reconnect smoke、删除与 final 两个历史事故复验均通过，且 native profile 可以独立完成真实读写、shell、验证、final 展示、断线恢复与扩展能力按需挂载时，本收敛计划才可以声明完成。
### 2026-08-16 B1 实施记录：Native durable tool batch bridge

- **本地参考**：`D:/opencode/opencode-dev/packages/opencode/src/session/processor.ts:172-224, 468-549`（以稳定 tool call/Part ID 查找、更新和完成 Tool Part）；`D:/opencode/opencode-dev/packages/opencode/src/session/message-v2.ts:301-371`（completed 输出按 call ID 投影，pending/running 不留下悬挂 tool_use）；`D:/opencode/opencode-dev/packages/opencode/src/session/prompt.ts:80-84`（中断 tool 的可恢复/非待执行区分）。
- **复刻的不变量**：assistant tool call 是结果配对源头；同 turn 的工具 Part 必须先持久化；执行顺序确定；被 approval、loop 或 cancellation 截断的剩余调用必须有显式状态，不能只停留在前端或内存。
- **LabexAgent 适配**：新增 `AgentProviderTranscriptAppender` 与 `LabexNativeToolBatchExecutor`，复用 `AgentRunMessage`、`AgentRunPart`、`AgentToolCallJournalService`、execution fence、现有 Engine 的权限/快照/验证执行包装。仅 `labex-native` 走新 bridge，`labex-legacy` 保持原逻辑；没有引入第二套 transcript、task status 或 interaction waiter。
- **未复制实质代码**：仅参考协议和状态不变量，未复制本地参考快照中的 TypeScript 实现。
- **退出条件**：完成真实 controlled-provider 的 S2 持久化黑盒测试、resume/compaction/overflow 覆盖后，将 legacy 中重复的 structured batch 分支迁移或删除；在此之前双路径按 profile 隔离，禁止双执行副作用。

### 2026-08-16 B2 实施记录：统一 Provider transcript 追加边界与 durable batch 黑盒

- **本地参考**：`D:/opencode/opencode-dev/packages/opencode/src/session/llm/request.ts:56-195`（将已准备的消息、系统提示和经权限过滤的工具统一组装到单次 Provider 请求）；`D:/opencode/opencode-dev/packages/opencode/src/session/message-v2.ts:503-514, 532-583`（按持久化 ID 顺序读取 Part，并以压缩边界重建消息）；`D:/opencode/opencode-dev/packages/opencode/src/session/processor.ts:172-224`（通过稳定 tool call/Part 身份更新同一条持久化事实）。
- **复刻的不变量**：Provider 请求的历史来自可重建持久化消息/Part，追加路径不能在不同编排器中各自维护序号；同一 tool call 的状态与结果必须以稳定 ID 查询、更新和回放。
- **LabexAgent 适配**：`AgentLoopEngine` 的所有 Provider transcript 追加改为委托 `AgentProviderTranscriptAppender`，由该唯一入口负责复制、序号和 fenced durable 写入；原有 `AgentTranscriptProjectionService` 继续为 Provider 调用、预算与 compaction 提供唯一读取投影。新增受控 Provider + H2/MyBatis 黑盒，直接验证真实 Run Message/Part/Event/outbox 写入顺序和 approval 未伪终结。
- **未复制实质代码**：仅参考边界与不变量，未复制本地参考快照中的 TypeScript 实现。
- **退出条件**：仍需 public `AgentLoopEngine` start/resume 受控 Provider、交互恢复、compaction/overflow、断线 replay 和浏览器现场验证；在这些验收完成前，不声明阶段 B 完成。
