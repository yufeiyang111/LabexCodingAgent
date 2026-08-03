# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

---

## Project at a glance

LabexAgent 是从 Labex 云编程工作台剥离出来的独立版本：Spring Boot 3 后端 + Vue 3 前端，提供 Agent 工作区、项目文件操作、终端、MCP 服务配置、流式对话、以及改动 diff 的 apply / reject / undo 流程。鉴权靠 JWT，subject 是用户数字 ID（前端代码仍以 `studentId` 命名沿用历史逻辑）。

完整的启动步骤、环境变量、端口表、故障排查都在 [README.md](README.md) 里。**改 AGENTS.md 之前先读一遍 README.md，不要重复里面的内容。**

---

## Architecture and AI development governance (mandatory)

本项目的 Agent 只能作为实现助手，不能成为架构事实源。任何 AI 生成或修改的代码都必须服从当前仓库的唯一状态模型、服务边界和持久化契约。完整迁移顺序见 [Agent Runtime State Convergence Plan](docs/superpowers/plans/2026-07-29-agent-runtime-state-convergence.md)。

### 1. AI 是实现助手，不是架构负责人

- 修改前必须先定位架构归属：运行生命周期、持久化 transcript、上下文压缩、Provider 协议、工具执行、审批、事件投影、前端 reducer 分别由哪个模块负责。
- 不得因为某个调用点缺少能力，就在 controller、tool、loop、composable 中临时增加第二套 Provider 历史、任务状态、审批等待或上下文摘要。
- 不得新增没有明确唯一所有者的 `Service`、fallback、registry、全局 Map、前端本地状态或字符串协议；如果已有路径失效，应修复或删除旧路径，而不是并行堆叠。
- 不得把“能编译”“mock 返回正确”“字符串里出现某个方法名”作为核心状态机或恢复能力的唯一验证。
- Agent 生成的改动必须小步、可回滚、可定位；跨层改动先写计划，先加失败回归测试，再改实现。
- 不得把兼容代码当作永久架构。兼容期必须写明读取/写入方向、退出条件、删除版本和验证命令。

### 2. 唯一事实源、所有权和投影

运行时必须区分权威事实与派生视图：

| 事实 | 唯一所有者 | 禁止作为权威来源 |
|---|---|---|
| 运行状态、执行 epoch、租约 | `AgentTask` + `AgentRunLifecycleService` | Pinia 状态、SSE 连接、任意 tool 内部 status 写入 |
| 可重放事件 | `AgentRunEvent` + transactional outbox | 仅存在内存的 publisher、前端时间线、日志文本 |
| 模型 transcript、tool call/result、Part 状态 | **目标状态**：`AgentRunMessage` + `AgentRunPart` | Provider 请求中的临时 `Map`、UI DTO、Promise、日志 |
| 审批/用户交互 | 持久化 interaction + 原始 `toolCallId` 对应的 Tool Part | 仅存在内存的 waiter、SSE 回调 |
| 文件改动和验证证据 | workspace + Git/change-set + verification | Agent 自述、diff 文本、前端缓存 |
| workspace 记忆 | 明确作用域的 workspace/conversation memory | `AgentContext` 中未持久化的 prompt 片段 |

Provider 请求、预算、压缩选择和恢复必须以 `AgentRunMessage` / `AgentRunPart` 经 `AgentTranscriptProjectionService` 生成的 durable projection 为唯一读取来源。`AgentLoopEngine` 不得维护跨迭代的可变 `msgs` transcript；`AgentContext`、`ContextUsageRegistry`、前端 composable 状态、SSE/EventSource 连接、`AgentConversation.summary` 或 Markdown 摘要都只能是派生缓存或只读兼容投影。

所有派生数据必须能从权威事实重建；不能反过来把派生数据写回成新的事实源。

### 3. 状态机不变量

- `AgentRunLifecycleService` 是 `AgentTask.status` 的唯一写入入口；其他服务只能发起带原因的迁移请求。
- 每次恢复必须校验 execution epoch、lease owner 和稳定幂等键；重复审批、重复恢复、重复完成必须是幂等操作。
- `completed`、`failed`、`cancelled` 等终态不能回到 `running`；任何重开都必须创建新的 epoch 或新的 task。
- `waiting_approval`、`waiting_user`、`waiting_workspace`、`retry_backoff` 是可恢复等待态，不能发送伪终态 `FINAL/DONE`。
- SSE 断开、浏览器刷新、前端卸载都不等于任务完成；恢复必须从数据库事件和游标继续。
- 每个 `toolCallId` 必须在 task/epoch/request/tool-call 作用域内唯一，并对应一个有明确终态或可恢复等待态的 Tool Part。
- 同一 assistant turn 的全部 tool call 必须先持久化，再按确定性策略执行；中断、审批阻塞或循环保护时，剩余 Part 必须显式标记为 `skipped` 或 `interrupted`。
- 任何状态迁移必须记录 actor、reason、previous state、next state、epoch 和时间；前端只能依据事件/Part reducer 渲染。

### 4. 上下文和压缩不变量

- Provider 请求必须由持久化 Message/Part 的 projector 生成，不能以 `Map<String,Object>` 的内存列表作为长期事实。
- assistant `tool_calls` 与 `role=tool` 必须保持一一对应，不能丢失 `tool_call_id`、工具名、参数和结果归属。
- 裁剪只能删除完整、协议安全的 turn；不得把 assistant tool call 与 tool result 拆开，也不得把工具结果伪装成普通文本。
- compaction 必须持久化 previous summary、压缩 head、保留 tail、token 预算、epoch 和状态；重启后必须可解释、可重放、可继续。
- 最近 tail 必须从真实 user turn 和完整 tool batch 计算，禁止使用“消息数乘二”作为 turn 算法。
- token 估算必须覆盖 system prompt、工具 schema、历史消息、tool call arguments、tool results、role/name 字段和输出预留，并优先采用 Provider 实际 usage。
- `contextWindowTokens` 未知时不得使用 1,000,000 之类的宽松默认值；必须进入明确的配置缺失或安全保守分支。
- context overflow 必须是带结构化原因的可处理错误；禁止用同一 prompt 无限重试或把错误吞成空响应。
- 模型思考过程默认不作为永久原文事实保存；只保存用户可见输出、必要的运行状态和可审计元数据。
- 构建成功不代表运行时已加载修复；验证 JVM 时必须核对启动时间、PID、classpath 和实际加载 class。

### 5. 改动与迁移纪律

所有 Agent Runtime 改造必须遵循 [Agent Runtime State Convergence Plan](docs/superpowers/plans/2026-07-29-agent-runtime-state-convergence.md)：

1. 先写 characterization/regression test，固定当前真实协议和失败现象；
2. 引入新的权威模型或 projector，先双写并做 shadow compare；
3. 切换读取路径后，监控旧路径是否仍被写入；
4. 旧路径只保留明确的兼容投影，达到删除条件后立刻删除；
5. 运行后端单测、前端测试、构建和 live smoke；
6. 最终报告必须区分“源码/测试通过”和“已启动 JVM/浏览器真实验证”。

禁止一次性重写整个 AgentLoopEngine、整个前端工作区或数据库；优先围绕单一事实源、协议 projector、持久化 compaction 和生命周期服务分阶段收敛。

### 6. 强制验收门槛

涉及运行状态、审批、上下文、工具调用、SSE 或前端回放的改动，至少必须覆盖：

- 新会话与旧会话隔离；
- SSE 断线、刷新、重复 cursor 和恢复；
- 审批批准/拒绝/过期/重复提交；
- 多 tool call 的顺序、toolCallId 和终态；
- compaction 后的协议合法性、重启恢复和 token 预算；
- context overflow 的有限重试与明确终态；
- 文件改动、验证证据和失败分级；
- 前端 reducer 只消费持久化事件/Part，不以连接生命周期猜测状态。

任何新增运行时状态字段、事件类型、工具 Part 类型或 API 恢复参数，都必须同步更新对应的 DTO、持久化层、reducer、测试和文档。
## Build & run

### Backend (Spring Boot, Java 17, Maven)

从仓库根目录：

```bash
cd backend
mvn spring-boot:run                                    # 开发模式，热启动由 spring-boot-devtools 接管（如果引入了）
mvn clean package -DskipTests                          # 打 JAR，产物在 backend/target/
java -jar target/labex-agent-backend-*.jar             # 跑 JAR
mvn -s settings-local.xml spring-boot:run              # 用仓库自带的 Maven 配置（settings-local.xml 默认空 mirror）
mvn test                                               # 跑后端单测（spring-boot-starter-test + spring-security-test 已包含）
```

主类：[com.labex.LabexAgentApplication](backend/src/main/java/com/labex/LabexAgentApplication.java)，带 `@MapperScan("com.labex.mapper")`。

### Frontend (Vue 3, Vite)

```bash
cd frontend
npm install                                            # 仅首次或依赖变更后
npm run dev                                            # 开发服务器：http://localhost:3000
npm run build                                          # 生产构建，产物在 frontend/dist
npm run preview                                        # 本地预览生产构建
```

`vite.config.js` 里 `/api` 代理到 `http://localhost:8080`（含 WebSocket 透传 `ws: true`，流式对话依赖这个）。**改了 vite 配置必须重启 dev server。**

### 没有的命令

仓库里**没有**前端 lint、format、test 脚本（package.json 里只有 `dev` / `build` / `preview`）。后端也没有单独的 `lint` 或 `format` profile。引入任何新的构建工具前先确认不会和现有依赖打架。

---

## Backend architecture

主入口之上分两层包结构：**基础设施层** (`com.labex.*`) 和 **Agent 核心层** (`com.labex.labexagent.*`)。

### 基础设施层 (`com.labex.*`)

| 包 | 职责 |
|---|---|
| `LabexAgentApplication` | 启动类，扫描 `com.labex.mapper` |
| `controller/AuthController` | `/auth/register`、`/auth/login`、`/auth/userinfo`（在 `SecurityConfig` 里放行 `/auth/**`） |
| `controller/student/StudentProjectController` | `/student/projects/**` 下的项目 CRUD、文件读写、压缩包上传/下载、agent 入口 |
| `controller/student/ProjectCommandSafety` | 终端命令安全校验（黑白名单） |
| `service/AuthService` | 注册 / 登录 / 当前用户查询，密码用 BCrypt |
| `service/StudentProjectService` (+ `impl/StudentProjectServiceImpl`) | 项目列表、文件读写、上传、目录解析 |
| `service/ProjectTerminalService` | 项目内终端命令调度 |
| `filter/JwtAuthenticationFilter` | 解析 `Authorization: Bearer <token>` 写入 `Authentication` |
| `security/JwtUtil` | JWT 签发 / 解析 |
| `config/SecurityConfig` | 全部路径 `authenticated()`，仅 `/auth/**`、`/error`、`/preview/**`、`/ws/**` 放行；CORS 全部 origin |
| `config/MybatisPlusConfig`、`config/WebSocketConfig` | MyBatis-Plus 分页 / WebSocket 端点注册 |
| `entity/` | MyBatis-Plus 实体，命名沿用历史：`AppUser`（对应表 `t_user`）、`StudentProject`（`t_student_project`）、`AgentConversation`、`AgentMessage`、`AgentTask`、`AgentChangeSet`、`AgentFileChange`、`AgentModelConfig`、`AgentMcpServer`、`AgentSkill`、`AgentTokenUsage`、`AgentVerification` |
| `mapper/` | MyBatis-Plus BaseMapper，每个 entity 一个 |
| `common/Result` | 统一返回 `{code, data, message}`（前端 request.js 里只把 `code === 0` 视作成功） |

**鉴权闭环**：`SecurityConfig` 放行 `/auth/**` → 用户登录拿 token → 前端把 token 放进 `Authorization` header（`stores/user.js`）→ `JwtAuthenticationFilter` 校验 → 把 userId 写进 `Authentication`（`auth.getName()` 就是 userId）→ Controller 从 `Authentication` 取 userId 当 studentId 用。

### Agent 核心层 (`com.labex.labexagent.*`)

这是项目里最复杂的一块。Agent 调用链大致是：

```
HTTP request
  → StudentAgentController / AgentExtensionController / AgentModelConfigController
  → AgentContextOrchestrator / AgentConversationService / AgentInteractionService
  → AgentLoopEngine                       ← 整个 Agent 的主循环
      → LlmProviderFactory                ← 选 provider（OpenAiCompatibleProvider 等）
      → 模型返回 tool_call 序列
      → ToolRegistry                      ← 按 name 找实现
      → labexagent/tool/impl/*Tool        ← 27 个工具
      → DiffService / GitSnapshotService  ← 改动落盘 / 回滚
      → AgentSsePublisher                 ← 通过 SSE 把事件流式推回前端
```

每个子包负责什么：

| 包 | 职责 |
|---|---|
| `runtime/AgentLoopEngine` | Agent 主循环、SSE 推送、工具调度、错误恢复（`MAX_RECOVERABLE_MODEL_ERRORS = 10`） |
| `runtime/AgentContext` / `AgentContextManager` | 会话级上下文（消息历史、工具结果、token 计数） |
| `runtime/AgentCancellationRegistry` | 中断信号（前端 `/agent/interrupt`） |
| `runtime/AgentSsePublisher` | Spring `SseEmitter` 封装 |
| `runtime/ToolCallExtractor` | 从模型输出里解析 tool_call JSON |
| `service/AgentConversationService` | 对话 + 消息的 CRUD（落库到 `t_agent_conversation` / `t_agent_message`） |
| `service/AgentTaskService` | 任务级编排 |
| `service/AgentContextOrchestrator` | 跨轮上下文裁剪、压缩、fork |
| `service/AgentInteractionService` | 跟 permission / question tool 的人机交互 |
| `service/AgentMetricsService` + `service/TokenTracker` | token 用量统计（落库 `t_agent_token_usage`） |
| `service/AgentPostEditHookService` | 文件改完之后的钩子（lint、format、auto-test） |
| `service/AgentWorkspaceMemoryService` | 跨会话的 workspace 记忆 |
| `service/ProjectCodeMapService` / `ProjectIndexService` | 项目代码索引（repo map、文件树） |
| `tool/AgentTool` (接口) + `tool/impl/*Tool` | 27 个工具实现（看 [tool/impl/](backend/src/main/java/com/labex/labexagent/tool/impl/) 列表） |
| `tool/ToolRegistry` | 按 name 注册 / 查找工具实现 |
| `permission/*` | 工具调用前的权限规则匹配 + 等待前端 approve |
| `llm/LlmProvider` (接口) + `OpenAiCompatibleProvider` | 抽象 LLM 调用；走 OpenAI-compatible 协议 |
| `mcp/McpManager` / `McpClient` / `McpToolAdapter` | MCP server 连接与工具适配 |
| `lsp/LspSessionManager` | lsp4j 进程管理（按需启动 jdtls / ts / vue / pyright） |
| `skill/SkillDiscoveryService` | 扫描用户自定义 skill |
| `diff/DiffService` / `GitSnapshotService` / `PendingChange` | 改动快照 + apply/reject/undo |
| `prompt/LabexSystemPrompt` | 系统提示词装配 |
| `terminal/TerminalWebSocketHandler` + `TerminalSession` | 项目内交互式终端（WebSocket） |
| `command/*` | Agent 内部命令（区别于终端命令） |
| `dto/AgentEvent` + `AgentStreamRequest` | SSE 事件 / 流式请求体 |

工具列表（[backend/src/main/java/com/labex/labexagent/tool/impl/](backend/src/main/java/com/labex/labexagent/tool/impl/)）按使用频率看：`ReadFileTool`、`WriteFileTool`、`EditFileTool`、`ApplyPatchTool`、`BashTool`、`RunCommandTool`、`ExecuteCodeTool`、`RunTestsTool`、`GrepTool`、`GlobTool`、`SearchCodeTool`、`RepoMapTool`、`RepoCloneTool`、`ListFilesTool`、`LspTool` / `LspSymbolsTool`、`WebSearchTool`、`WebFetchTool`、`ImageUnderstandingTool`、`RetrieveContextTool`、`SkillTool`、`McpCallTool`、`TodoWriteTool`、`CreatePlanTool`、`PlanExitTool`、`QuestionTool`、`DiagnosticsTool`、`ExternalDirectoryTool`、`ContextNoteTool`，加占位 `InvalidTool` 和别名配置 `AgentAliasToolConfig`。

### RAG 子系统 (`com.labex.rag.*`)

跟 `labexagent.llm` 是两套独立的 LLM 抽象，**不要混用**：
- `rag/llm/MiniMaxChat`、`OllamaChat` + `LLMChat` 接口 —— 给 RAG / 图片理解 / Web 搜索用的。
- `labexagent/llm/OpenAiCompatibleProvider` —— Agent 主循环用的。

`rag/config/RagConfig` 把 `MINIMAX_API_KEY` / `MINIMAX_BASE_URL` / `TAVILY_API_KEY` 读成 Spring Bean。

---

## Frontend architecture

```
main.js  ── 装载 Pinia、Vue Router、Element Plus、wabi-sabi.scss
  └─ App.vue
       ├─ router/index.js  ── 3 条路由
       │    ├─ /login                       → views/Login.vue        (anon)
       │    ├─ /projects                    → views/CloudSpace.vue   (auth)
       │    └─ /workspace/:projectId        → views/CloudWorkspace.vue (auth)
       └─ stores/user.js   ── token + userInfo (localStorage 持久化)
            └─ utils/request.js  ── axios 实例，baseURL /api，自动带 Bearer
                 └─ api/index.js ── 所有后端 API 封装 (authApi / projectApi)
```

- `components/cloud/` 是 CloudSpace / CloudWorkspace 里用到的子组件。
- `components/terminal/` 是 xterm 终端封装（`@xterm/xterm` + `addon-fit` / `addon-search` / `addon-web-links`）。
- `components/MonacoEditor.vue` + `@guolao/vue-monaco-editor` 提供编辑器。
- 主题样式：[src/styles/wabi-sabi.scss](frontend/src/styles/wabi-sabi.scss)。
- 鉴权守卫在 [router/index.js](frontend/src/router/index.js) 的 `beforeEach`：未登录访问受保护路由会被踢到 `/login`。

API 调用全部走 `projectApi.xxx(projectId, ...)` 形式，路径前缀 `/student/projects/{id}/...`。**新增前端 API 调用时在 `src/api/index.js` 集中加，不要在组件里写裸 axios。**

---

## Configuration & data

- 后端配置：[backend/src/main/resources/application.yml](backend/src/main/resources/application.yml)。所有环境变量都带默认值，所以即便不 export 也能起来，但生产环境必须覆盖 `LABEX_AGENT_JWT_SECRET`、`LABEX_AGENT_DB_PASSWORD`、`MINIMAX_API_KEY`。
- 数据库 schema 由 `spring.sql.init.mode: always` 在启动时从 [backend/src/main/resources/sql/schema.sql](backend/src/main/resources/sql/schema.sql) 自动执行。**手工改库前先备份 schema.sql**，否则下次启动会被覆盖式重建。
- 前端配置：[frontend/vite.config.js](frontend/vite.config.js)（别名、端口、代理）。
- 用户级运行时数据落在 `${LABEX_AGENT_PROJECT_BASE_PATH}`（默认 `D:/LabexAgent/workspaces`）下，**已被 .gitignore 忽略**。

---

## Common gotchas

- **改了 application.yml 里的 `server.servlet.context-path`**：前端 Vite 代理的 `target` 也要相应调整（默认就是 `/api`）。
- **改了 vite.config.js 里 `proxy['/api'].target`**：必须重启 Vite dev server，HMR 不会重载代理配置。
- **改了 vite.config.js 把 `ws: true` 去掉**：流式对话回退成一次性返回，用户体验直接崩。
- **`AgentLoopEngine` 用的是 `SseEmitter`**，不是 WebSocket；前端对应看 `api/index.js` 里 `askAgent` 的调用方式（注意 `EVENTSOURCE` 客户端行为和 reconnect）。
- **`TerminalWebSocketHandler` 才走 WebSocket**，端口和 SSE 不共享连接池。
- **新增 Tool**：实现 `labexagent/tool/AgentTool` 接口，然后在 `ToolRegistry` 里注册。权限策略对应在 `labexagent/permission/DefaultPermissionRuleset` 加规则。
- **新增 MCP server**：UI 走 `AgentModelConfigController` / `AgentMcpServerService`，后端通过 `McpManager` 连接。
- **JWT secret 太短**会被后端启动期校验拦截（`JwtUtil` 里有最小长度断言）；用 `openssl rand -base64 64` 生成一个就行。
- **`t_user` / `t_student_project` 等表名沿用历史**（前缀 `t_`，列名 snake_case），跟 entity 类名驼峰无关。MyBatis-Plus 已开 `map-underscore-to-camel-case`，所以 entity 字段直接驼峰即可。