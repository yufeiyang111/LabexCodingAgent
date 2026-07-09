# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

---

## Project at a glance

LabexAgent 是从 Labex 云编程工作台剥离出来的独立版本：Spring Boot 3 后端 + Vue 3 前端，提供 Agent 工作区、项目文件操作、终端、MCP 服务配置、流式对话、以及改动 diff 的 apply / reject / undo 流程。鉴权靠 JWT，subject 是用户数字 ID（前端代码仍以 `studentId` 命名沿用历史逻辑）。

完整的启动步骤、环境变量、端口表、故障排查都在 [README.md](README.md) 里。**改 AGENTS.md 之前先读一遍 README.md，不要重复里面的内容。**

---

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