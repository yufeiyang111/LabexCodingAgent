# LabexAgent.md

This file is the model-facing project memory for LabexAgent agents. It should
help an LLM quickly rebuild the repository map, preserve durable engineering
decisions, and choose safe verification steps. Keep run commands and user setup
details in `README.md`; keep implementation orientation and change rules here.

---

## Context Contract

When initializing or refreshing project context, preserve these facts first:

- Product shape: LabexAgent is a standalone extraction of the Labex cloud coding workspace.
- Stack: Spring Boot 3 backend, Java 17+, Maven, MyBatis-Plus, MySQL, Vue 3, Vite, Pinia, Element Plus, Monaco.
- Core user flow: login -> project/workspace -> agent conversation -> tool execution -> diff apply/reject/undo.
- Auth model: JWT subject is the numeric user id; much frontend/backend code still names it `studentId`.
- Runtime data: project workspaces live under `LABEX_AGENT_PROJECT_BASE_PATH`, ignored by git.
- Secrets rule: never read, print, or commit `.env`; use `.env.example` and `application.yml` for names/defaults.
- Documentation split: `README.md` is the operator runbook; this file is the coding-agent navigation map.

Do not preserve noisy facts such as one-off terminal output, local port conflicts,
temporary file paths, generated target files, or personal credentials.

---

## Required Reading Order

Before changing code, read only the slices relevant to the task:

1. `README.md` for setup, environment variables, ports, and troubleshooting.
2. `backend/src/main/resources/application.yml` for backend config and env names.
3. `frontend/package.json` or `backend/pom.xml` before running or inventing commands.
4. The owning controller/service/component for the requested behavior.
5. The related persistence schema in `backend/src/main/resources/sql/schema.sql` when data shape changes.
6. Existing tests under `backend/src/test/java` before adding new tests.

For broad architecture work, also read `AGENTS.md` and `CLAUDE.md`, but avoid
duplicating them when updating this file.

---

## Repository Index

| Area | Path | What To Remember |
|---|---|---|
| Backend app | `backend/src/main/java/com/labex` | Infrastructure layer, auth, project APIs, config, entities, mappers |
| Agent core | `backend/src/main/java/com/labex/labexagent` | Agent loop, tools, LLM provider, MCP, LSP, diff, prompt assembly |
| RAG utilities | `backend/src/main/java/com/labex/rag` | Separate LLM abstraction for RAG/image/web helper paths |
| Backend config | `backend/src/main/resources/application.yml` | MySQL, JWT, workspace paths, LSP commands, MiniMax/Tavily env |
| Schema | `backend/src/main/resources/sql/schema.sql` | Startup schema; table names use `t_` prefix and snake_case columns |
| Frontend app | `frontend/src` | Vue routes, workspace UI, API wrapper, terminal, Monaco editor |
| Shared UI style | `frontend/src/styles/wabi-sabi.scss` | Global visual language and Element Plus overrides |
| API wrapper | `frontend/src/api/index.js` | Add frontend backend calls here, not as raw axios in components |
| Generated/runtime | `backend/target`, `frontend/dist`, `workspaces`, `uploads` | Do not edit or commit generated/runtime data |

---

## Build And Verification Index

Detect commands from the package manager files before running them.

Backend:

```bash
cd backend
mvn test
mvn spring-boot:run
mvn clean package -DskipTests
```

Frontend:

```bash
cd frontend
npm install
npm run dev
npm run build
npm run preview
```

Known missing commands:

- No frontend lint/test/format scripts in `frontend/package.json`.
- No backend lint/format Maven profile.
- Do not add new tooling only to satisfy a local check unless the task requires it.

Minimum verification choices:

| Change Type | First Check | Broader Check |
|---|---|---|
| Backend logic | Targeted Maven test, e.g. `mvn -Dtest=Name test` | `mvn test` |
| Backend config/schema | Config or context test | `mvn test`; manual backend start only if needed |
| Frontend UI/API wrapper | `npm run build` | Browser/dev-server inspection when visual behavior matters |
| Vite proxy/config | Restart Vite dev server after edit | Check API/WS behavior in browser |
| Docs only | Review rendered markdown mentally or with diff | No build required unless examples changed |

If you start the backend for verification, stop it before handing control back
when the user wants to inspect startup output themselves.

---

## Backend Architecture Index

There are two main backend layers:

1. Infrastructure layer: `com.labex.*`
2. Agent core layer: `com.labex.labexagent.*`

Infrastructure layer:

| Component | Path | Responsibility |
|---|---|---|
| App entry | `LabexAgentApplication` | Starts Spring Boot; scans `com.labex.mapper` |
| Auth API | `controller/AuthController` | Register, login, userinfo |
| Project API | `controller/student/StudentProjectController` | Project CRUD, tree/files, upload/export, terminal/agent entry |
| Command safety | `controller/student/ProjectCommandSafety` | Terminal command allow/deny rules |
| Auth service | `service/AuthService` | BCrypt password handling and JWT login response |
| Project service | `service/StudentProjectService` | Workspace/project file operations |
| JWT filter | `filter/JwtAuthenticationFilter` | Reads `Authorization: Bearer <token>` and sets authentication |
| JWT utility | `security/JwtUtil` | HS512 token signing/parsing; production secret should be 64+ bytes |
| Security config | `config/SecurityConfig` | Protected routes, auth allowlist, CORS |
| Result wrapper | `common/Result` | Frontend treats only `code === 0` as success |

Agent core request flow:

```text
StudentAgentController / AgentExtensionController / AgentModelConfigController
  -> AgentContextOrchestrator / AgentConversationService / AgentInteractionService
  -> AgentLoopEngine
      -> LlmProviderFactory
      -> OpenAiCompatibleProvider
      -> ToolCallExtractor
      -> ToolRegistry
      -> tool/impl/*
      -> DiffService / GitSnapshotService
      -> AgentSsePublisher
```

Agent core package index:

| Package | Preserve This |
|---|---|
| `runtime` | Main loop, SSE publishing, cancellation, context, tool-call extraction |
| `service` | Conversation/task/context orchestration, metrics, token usage, memory, indexing |
| `tool` | Tool interface, registry, tool definitions, aliases, permission-facing behavior |
| `permission` | Ask/allow/reject flow and persisted project approvals |
| `llm` | Agent LLM abstraction; OpenAI-compatible protocol lives here |
| `mcp` | MCP server connection and tool adaptation |
| `lsp` | jdtls, ts, vue, pyright process/session management |
| `diff` | File changes, snapshots, apply/reject/undo |
| `prompt` | System prompt construction for the agent |
| `terminal` | WebSocket terminal sessions, separate from SSE agent stream |
| `command` | Slash/internal agent commands, separate from project terminal shell commands |

Important distinction:

- `labexagent.llm.*` drives the main Agent loop.
- `rag.llm.*` supports RAG/image/web helper paths.
- Do not merge or casually reuse the two abstractions.

---

## Frontend Architecture Index

Frontend route flow:

```text
main.js
  -> App.vue
    -> router/index.js
      -> /login                  views/Login.vue
      -> /projects               views/CloudSpace.vue
      -> /workspace/:projectId   views/CloudWorkspace.vue
```

Frontend state/API flow:

```text
stores/user.js
  -> localStorage token/userInfo
  -> utils/request.js
    -> Authorization header
    -> api/index.js
      -> authApi / projectApi / modelConfigApi / agentExtensionApi
```

Frontend component index:

| Path | Responsibility |
|---|---|
| `components/cloud` | Workspace/project subcomponents |
| `components/terminal` | xterm wrapper with fit/search/web-links addons |
| `components/MonacoEditor.vue` | Monaco editor integration |
| `views/CloudWorkspace.vue` | Main IDE/workspace surface |
| `views/CloudSpace.vue` | Project list/creation entry |
| `views/Login.vue` | Auth entry |

Frontend rules:

- Add new backend calls in `frontend/src/api/index.js`.
- Keep server state and UI state separate when touching workspace views.
- Preserve keyboard/focus behavior for dialogs, menus, terminal, and editor.
- Vite proxy includes `ws: true`; removing it breaks streaming/terminal behavior.
- If visual changes touch dialogs/modals, verify opaque backgrounds, masks, stacking, and responsive layout.

---

## Data And Persistence Index

Schema source of truth:

- `backend/src/main/resources/sql/schema.sql`
- Initialized by `spring.sql.init.mode: always`
- Tables use `t_` prefix; columns use snake_case; Java entities use camelCase.
- MyBatis-Plus maps snake_case to camelCase.

Core tables:

| Table | Java Entity | Purpose |
|---|---|---|
| `t_user` | `AppUser` | Login users |
| `t_student_project` | `StudentProject` | User projects/workspaces |
| `t_agent_model_config` | `AgentModelConfig` | User LLM provider configs |
| `t_agent_conversation` | `AgentConversation` | Agent conversation sessions |
| `t_agent_message` | `AgentMessage` | Persisted conversation/event messages |
| `t_agent_task` | `AgentTask` | Agent task lifecycle |
| `t_agent_change_set` | `AgentChangeSet` | Grouped file changes |
| `t_agent_file_change` | `AgentFileChange` | Individual diff/apply/reject/undo records |
| `t_agent_verification` | `AgentVerification` | Verification command records |
| `t_agent_token_usage` | `AgentTokenUsage` | Token accounting |
| `t_agent_skill` | `AgentSkill` | User-defined skills |
| `t_agent_mcp_server` | `AgentMcpServer` | User MCP server configs |
| `t_agent_permission_approval` | JDBC in `PermissionService` | Remembered project-level permission approvals |

Schema rules:

- Prefer additive schema changes.
- Do not run destructive migrations/resets without explicit user approval.
- Keep tenant/user isolation in every project/user query.
- If adding a table, add entity, mapper, service pattern, and schema entry consistently.
- If adding a query path, consider indexes in `schema.sql`.

---

## Auth And Security Index

Auth closure:

```text
SecurityConfig allows /auth/** and selected public paths
  -> login/register returns JWT
  -> frontend stores token
  -> request.js sends Bearer token
  -> JwtAuthenticationFilter validates token
  -> auth.getName() is numeric userId
  -> controllers treat it as studentId
```

Security invariants:

- Never trust frontend-only checks for authorization.
- Every project-specific endpoint must verify current user owns the project/object.
- Never expose raw API keys; mask model config keys in responses.
- Never log passwords, JWTs, API keys, cookies, authorization headers, or `.env` contents.
- Treat URLs submitted for model-list fetching as SSRF-sensitive; keep HTTPS/public-address validation.
- User-provided file paths must remain inside the project workspace.

---

## LLM Provider And Model Config Index

Model config API:

- Backend controller: `labexagent/controller/AgentModelConfigController`
- Frontend wrapper: `modelConfigApi` in `frontend/src/api/index.js`
- Persistence: `t_agent_model_config`
- Provider resolution: `labexagent/llm/LlmProviderFactory`
- Main provider: `labexagent/llm/OpenAiCompatibleProvider`

Model list rules:

- Fetch model lists from official provider URLs when supported.
- Use HTTPS only, default port only, no credentials in URL, no localhost/private IP resolution.
- Apply provider-specific auth only in code that owns model-list fetching.
- Keep `/student/model-configs/model-list` as POST to avoid colliding with `/{configId}` routes.
- Parse common response shapes: `{data:[...]}`, `{models:[...]}`, or raw arrays.

Template rules:

- A template should fill base URL, model list URL, default flagship model, max tokens, and auth method.
- User should only need to provide API key/token/project key when possible.
- Do not guess undocumented provider contracts; mark unsupported fetch behavior explicitly.

---

## Agent Tooling Index

When adding a tool:

1. Implement `labexagent/tool/AgentTool`.
2. Register it in `ToolRegistry`.
3. Add permission rules in `permission/DefaultPermissionRuleset` when needed.
4. Make tool input/output schema explicit and stable.
5. Ensure dangerous operations request permission and stay scoped to the workspace.
6. Add targeted tests or a verification path for the tool behavior.

Common high-use tools:

- File: `ReadFileTool`, `WriteFileTool`, `EditFileTool`, `ApplyPatchTool`, `ListFilesTool`
- Shell/tests: `BashTool`, `RunCommandTool`, `RunTestsTool`, `ExecuteCodeTool`
- Search/index: `GrepTool`, `GlobTool`, `SearchCodeTool`, `RepoMapTool`
- Agent workflow: `TodoWriteTool`, `CreatePlanTool`, `PlanExitTool`, `QuestionTool`
- Integrations: `McpCallTool`, `WebSearchTool`, `WebFetchTool`, `ImageUnderstandingTool`
- Context: `RetrieveContextTool`, `ContextNoteTool`, `DiagnosticsTool`

---

## Streaming, Terminal, And Diff Index

Do not confuse transports:

- Agent conversation streaming uses Spring `SseEmitter`.
- Terminal sessions use WebSocket through `TerminalWebSocketHandler`.
- Vite proxy must support both HTTP/SSE and WebSocket.

Diff lifecycle:

```text
tool edit/write/patch
  -> DiffService records pending change
  -> GitSnapshotService captures before/after state
  -> frontend lists changes
  -> user apply/reject/undo
```

Rules:

- Preserve user changes in the workspace.
- Do not silently apply or discard pending diffs.
- If changing diff semantics, verify apply, reject, and undo paths.

---

## Configuration Index

Key files:

- `backend/src/main/resources/application.yml`
- `.env.example`
- `frontend/vite.config.js`
- `backend/settings-local.xml`

Environment behavior:

- Spring imports optional `.env` from repository root or `backend/`.
- MySQL remains the backend database.
- `.env` is a secret file; do not read or print it.
- `LABEX_AGENT_JWT_SECRET` should be high entropy and at least 64 bytes in production.
- Development may tolerate short placeholder secrets through `JwtUtil` key derivation, but do not rely on that in production.

Vite behavior:

- Frontend dev server defaults to port `3000`.
- `/api` proxies to backend port `8080`.
- Proxy `ws: true` is required for WebSocket paths.
- Restart Vite after changing `vite.config.js`.

---

## Change Workflow Rules

For every task:

1. Identify the owning layer: frontend, backend, schema, agent runtime, tool, provider, or docs.
2. Read the smallest relevant set of files before editing.
3. Preserve existing patterns and names unless the task asks for a redesign.
4. Make focused changes; do not mix unrelated refactors.
5. Add or update tests for bugs, config regressions, security-sensitive paths, or shared behavior.
6. Run the smallest relevant verification first, then broader checks if needed.
7. Report what changed, files touched, verification output, and remaining risk.

Do not:

- Read `.env` or other secret files.
- Commit, push, or run destructive commands unless asked.
- Disable tests or weaken security gates to pass a check.
- Introduce dependencies before checking whether existing utilities solve the problem.
- Modify generated/runtime folders.

---

## Prompt/Context Initialization Rules

When a model initializes LabexAgent context, build these indexes in memory:

1. Repo map: root folders, backend/frontend split, generated/runtime ignore areas.
2. Command map: actual scripts from `pom.xml` and `package.json`.
3. Route/API map: backend controllers and `frontend/src/api/index.js`.
4. Data map: schema tables, entity classes, mapper/service ownership.
5. Agent runtime map: request -> context -> loop -> provider -> tools -> diff -> SSE.
6. Security map: JWT flow, project ownership checks, secret handling, URL/file-path validation.
7. Verification map: minimal commands for the current task type.
8. Gotcha map: SSE vs WebSocket, RAG LLM vs Agent LLM, MySQL schema init, Vite proxy restart.

Use these indexes to answer questions and plan changes. Refresh only the slices
that are likely stale for the current task.

---

## Common Gotchas

- `auth.getName()` is expected to be the numeric user id.
- Frontend names may say `studentId` because of historical Labex naming.
- Backend path context is `/api`; frontend requests use `/api` proxy.
- `Result` success is `code === 0`; frontend request handling depends on this.
- `AgentLoopEngine` streaming is SSE, not WebSocket.
- Terminal is WebSocket, not SSE.
- `rag.llm` and `labexagent.llm` are separate abstractions.
- Model-list URLs are SSRF-sensitive.
- Schema runs at startup; MySQL-specific SQL must stay compatible with the chosen database.
- Existing docs may lag behind recent code changes; verify current files before editing.

---

## Update Policy

Update this file when:

- A major package/module responsibility changes.
- A new subsystem, integration, or transport is added.
- Build/test commands change.
- Auth, schema, provider, MCP, tool, diff, or streaming behavior changes.
- A repeated debugging lesson becomes a stable project gotcha.

Keep this file concise. Prefer durable routing facts and invariants over
exhaustive explanations that belong in `README.md` or code comments.
