# LabexAgent

LabexAgent 是从 Labex 云编程工作台剥离出来的独立版本。保留了 Agent 工作区、项目文件操作、终端、模型配置、Skills、MCP 服务配置、流式对话、以及改动 diff 的 apply / reject / undo 流程，并加了一层简单的用户名密码登录用于隔离用户数据。

## 项目结构

- `backend/` —— Spring Boot 3 + Java 17 + MyBatis-Plus + JWT 鉴权 + Agent 运行时 + 项目 / 工作区 API。
- `frontend/` —— Vue 3 + Vite + Pinia + Element Plus + Monaco 编辑器 + 工作区 UI。

---

## 1. 环境依赖

启动前先装好以下软件。

| 依赖 | 版本要求 | 验证命令 |
|---|---|---|
| Java（JDK） | 17 及以上 | `java -version` |
| Maven | 3.9 及以上 | `mvn -v` |
| Node.js | 18 及以上 | `node -v` |
| npm | 9 及以上（Node 18 自带） | `npm -v` |
| MySQL / MariaDB | 当前 Spring Boot JDBC 驱动支持的稳定版本（服务需启动） | `mysql --version` |

确认 MySQL 服务在 `localhost:3306`（默认端口）上可访问。如果你的 MySQL 用了别的地址或端口，请相应修改下面 `LABEX_AGENT_DB_URL` 的值。

---

## 2. 环境变量

后端会自动读取仓库根目录或 `backend/` 目录下的 `.env` 文件，也可以直接读取当前 shell / IDE 里的环境变量。MySQL 密码没有安全通用的默认值，首次启动前请至少设置 `LABEX_AGENT_DB_PASSWORD`。

推荐做法：在 shell 里 export，或者在 IDE 的运行配置里设置。

### 2.1 必填项（实际使用时必须改）

| 变量 | 示例值 | 说明 |
|---|---|---|
| `LABEX_AGENT_DB_URL` | `jdbc:mysql://localhost:3306/labex_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true` | MySQL JDBC 地址 |
| `LABEX_AGENT_DB_USERNAME` | `root` | 数据库用户名 |
| `LABEX_AGENT_DB_PASSWORD` | 你的 MySQL 密码 | 数据库密码 |
| `LABEX_AGENT_JWT_SECRET` | 一段随机字符串，至少 64 字节 | JWT 签名密钥（**必须**改默认值） |
| `LABEX_AGENT_SECRET_STORE_MASTER_KEY` | Base64 编码的随机 32 字节 | 模型 API key 与 MCP Authorization header 的 AES-GCM 主密钥；生产环境**必须**设置，开发环境仅有固定的兼容回退值 |

### 2.2 存储路径（可选，默认值在 Windows 下可用）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `LABEX_AGENT_PROJECT_BASE_PATH` | `D:/LabexAgent/workspaces` | 用户工作区所在目录 |
| `LABEX_AGENT_WEBSOCKET_ALLOWED_ORIGINS` | `http://localhost:3000,http://127.0.0.1:3000` | 允许连接云终端 WebSocket 的前端来源；生产环境必须设置为实际 HTTPS 域名 |
| `LABEX_AGENT_UPLOAD_PATH` | `D:/LabexAgent/uploads` | 上传文件存放目录 |

如果你是 macOS / Linux，建议把这两个值改成自己家目录下的路径，例如 `~/labex-agent/workspaces`。
### 2.2.1 Agent 验证与自动恢复策略

Agent 不会把所有失败都当成代码错误。命令失败会先识别 DNS/网络/依赖解析等环境阻塞；同一任务、同一命令、同一工作目录的重复失败会被持久化熔断，避免审批死循环。

| 变量 | 默认值 | 说明 |
|---|---:|---|
| `LABEX_AGENT_AUTO_REPAIR_ENABLED` | `true` | 普通测试/编译失败时，允许模型按“读取错误 → 修改 → 有限重试”推进；环境阻塞不会自动修改依赖配置。 |
| `LABEX_AGENT_MAX_AUTOMATIC_REPAIRS` | `3` | 普通代码失败最多给模型的自动修复建议次数。 |
| `LABEX_AGENT_MAX_REPEATED_COMMAND_FAILURES` | `2` | 同一命令在同一任务中的普通失败上限；达到后不再创建新审批。 |
| `LABEX_AGENT_MAX_ENVIRONMENT_FAILURES` | `1` | DNS、网络、Maven 父 POM/依赖解析等环境失败的尝试上限。 |
| `LABEX_AGENT_VERIFICATION_STRATEGY` | `auto` | 验证策略：`auto`、`test`、`compile`、`build`、`offline_test`、`manual`。例如 Maven 可切换到 `compile` 或 `offline_test`，不会再强制使用 `mvn test`。 |
| `LABEX_AGENT_FALLBACK_VERIFICATION_STRATEGY` | `manual` | 当前策略不可用时的安全回退策略；默认只提示人工确认，不猜测新的命令。 |
| `LABEX_AGENT_HARD_MAX_ITERATIONS` | `0` | Agent 总轮数最终保险丝；`0` 表示不设置任意总轮数上限。 |
| `LABEX_AGENT_REPEATED_TOOL_CALL_THRESHOLD` | `3` | 完全相同工具输入或短工具调用循环的触发次数；首次命中会要求模型自动换策略。 |
| `LABEX_AGENT_MAX_TOOL_CYCLE_LENGTH` | `3` | 可识别的交替工具调用循环最大长度，例如 `A-B-A-B-A-B`。 |
| `LABEX_AGENT_MAX_AUTOMATIC_STRATEGY_SWITCHES` | `1` | 同一循环模式允许 Agent 自动切换策略的次数；之后升级为持久化用户问题。 |
| `LABEX_AGENT_MAX_NON_PROGRESS_ITERATIONS` | `8` | 连续没有成功工具进展的模型回合上限；防止空答复、反复失败或策略无效时无限消耗请求。 |

用户点击“环境恢复后重试”时，Agent 会开启新的失败代际，但仍受上述次数限制；切换验证策略也会生成不同的命令指纹，从而避免旧失败记录阻塞新策略。


#### 命令网络访问审批

命令执行默认在无网络沙箱中运行，不再按 Maven、npm、Gradle、Python 等技术栈维护网络白名单。需要联网的 `shell` 或 `run_tests` 调用可在工具参数中显式设置 `network: true`，后端会为当前任务和当前规范化命令创建一次性网络审批；审批不会转化为永久权限，也不会授权其他命令。

如果命令在离线沙箱中出现 DNS、连接超时、下载失败等网络型错误，系统会自动创建一次“离线失败重试”审批。用户批准后，Agent 只能按原命令携带 `network: true` 重试一次；拒绝或重试仍失败时，不会继续无限弹窗。`web_search`、`web_fetch` 和 `repo_clone` 继续使用它们自己的受控服务路径，不进入这条命令网络审批链。
### Agent 忽略文件

项目根目录可以放置 `.labex-agentignore`，用于排除不希望进入 Agent 上下文、代码索引和搜索结果的生成物或大型日志。

规则使用类 Git ignore 语法，支持目录、文件名和 glob 模式。`Repo Map`、`grep`、`glob` 和 `list_files` 都会尊重这些规则；以 `#` 开头的行是注释。

```text
# 排除 Agent 不需要读取的生成物
node_modules/
logs/
generated/**
*.log
```

`.labex-agentignore` 只影响 Agent 的上下文和搜索边界，不是操作系统级安全隔离。超过 20 MiB 的文件不会自动进入 Agent 上下文；`.git` 和 `.labex` 等内部目录会始终排除。

### 2.3 Execution isolation

On Windows, the default `local` profile runs the **WSL2 Debian + bubblewrap** Worker. Agent commands, tests, interactive terminals, and LSP child processes use this Worker instead of falling back to the Windows host.

Every invocation receives separate mount, PID, network, IPC, and UTS namespaces with all Linux capabilities dropped. Only the current project workspace is writable at `/workspace`; WSL `/mnt/c`, `/mnt/d`, and the `/proc/<pid>/root` host-filesystem bypass are unavailable. Runtime, Java, and Maven configuration directories are read-only. Control-plane secrets such as model API keys, JWT secrets, and database passwords are never forwarded to the Worker.

Before first use, install a WSL distribution (default name: `Debian`) and the required tools. Run the following in PowerShell; skip tools that are already installed:

```powershell
wsl --install -d Debian   # Run only when WSL/the distribution is not installed; reboot when Windows asks.
wsl -d Debian -- bash -lc "apt-get update && apt-get install -y bubblewrap nodejs npm git python3 openjdk-21-jdk-headless maven"
```

If the distribution has a different name, set it before starting the backend:

```powershell
$env:LABEX_AGENT_WSL_DISTRIBUTION = "your WSL distribution name"
```

The local Worker is acceptance-tested with Node.js, npm, Git, Python 3, Java 21, Maven, JDTLS, TypeScript Language Server, Vue Language Server, and Pyright inside the isolated environment. The default Java LSP command starts JDTLS with a 256 MiB initial heap; override it through `LABEX_LSP_JAVA_CMD` only when a larger workspace needs more memory.

For Monaco LSP completion, place `jdtls`, `typescript-language-server`, `vue-language-server`, and `pyright-langserver` in the same WSL distribution, then configure Linux commands through `LABEX_LSP_*_CMD` when needed. For an offline local setup, copy a trusted pre-existing LSP runtime into the WSL native `/usr/local/lib` path and expose only wrappers under `/usr/local/bin`; do not point the sandbox at an executable under `/mnt/c` or another Windows drive.

Use the explicit `unsafe-local` profile only to diagnose isolation failures. It executes child processes directly on the Windows host and must not be used for regular Agent work or demonstrations.

For a server deployment, switch to the Docker Worker:

| Variable | Example | Purpose |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `production` | Disables the local WSL Worker and enables production startup validation. |
| `LABEX_AGENT_WORKER_DOCKER_IMAGE` | `registry.example.com/labex-agent-sandbox:2026-07` | Required prebuilt OCI image containing the shell, language runtimes, package managers, and LSP tools. |

The Docker daemon must be available. The Docker Worker also mounts only the current project workspace, disables the network, uses a read-only root filesystem, applies CPU/memory/PID limits, and removes the container after terminal or command completion. Do not bake model API keys, JWT secrets, or database credentials into the image.

### 2.4 AI 集成（可选）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `MINIMAX_API_KEY` | _空_ | 旧 RAG / 兼容调用链的 MiniMax key；主 Agent 请通过界面中的用户模型配置设置模型和 key |
| `MINIMAX_BASE_URL` | `https://api.minimaxi.com/v1` | MiniMax API 地址 |
| `MINIMAX_API_HOST` | `https://api.minimaxi.com` | MiniMax API 主机 |
| `MINIMAX_MODEL` | `MiniMax-M2.7-highspeed` | 默认模型名 |
| `TAVILY_API_KEY` | _空_ | RAG 子系统可选搜索 key；Agent `web_search` 不会自动调用它 |
| `EXA_API_KEY` | _空_ | Agent `web_search` 的 Exa MCP key；未设置时使用 Exa MCP 的匿名访问能力（以服务端策略为准） |
| `PARALLEL_API_KEY` | _空_ | 仅当显式启用 Parallel 时使用的 MCP key |
| `LABEX_AGENT_WEB_SEARCH_PROVIDER` | `auto` | `auto` 使用 Exa；可显式设为 `exa`、`parallel` 或 `public_fallback` |
| `LABEX_AGENT_WEB_SEARCH_EXA_ENABLED` | `true` | 是否允许 Exa MCP provider |
| `LABEX_AGENT_WEB_SEARCH_PARALLEL_ENABLED` | `false` | 是否允许 Parallel MCP provider；默认关闭且不会自动选择 |
| `LABEX_AGENT_WEB_SEARCH_PUBLIC_FALLBACK_ENABLED` | `true` | Exa 发生可恢复故障时，是否使用 DuckDuckGo/Bing 公开结果页兜底 |
| `LABEX_AGENT_WEB_SEARCH_TIMEOUT_SECONDS` | `25` | 每次 Agent 搜索 provider 请求的超时秒数 |
| `LABEX_AGENT_WEB_SEARCH_MAX_RESPONSE_BYTES` | `1048576` | 单次 provider 响应最大字节数 |

Agent `web_search` 只返回搜索发现结果和来源 URL，不抓取结果正文；需要读取某个来源时由 Agent 使用 `web_fetch`。在 `auto` 模式下，Exa 是唯一自动尝试的付费/托管 provider；发生可恢复失败后才使用免费的 DuckDuckGo/Bing 结果页兜底。Agent 不会自动调用 Tavily 或 Parallel。


#### 主 Agent 的用户模型配置

主 Agent 不再以 `MINIMAX_*` 环境变量作为模型选择和调用路径。用户需在工作区的「模型配置」中创建并选中一个 OpenAI-Compatible 配置，由其 API Key、Base URL 和模型名称驱动对话、工具循环和上下文压缩。

- 「推理程度」可选 `low`、`medium`、`high`、`xhigh`。系统会将它作为 `reasoning_effort` 发给支持的服务；兼容网关拒绝该字段时会安全地去掉该字段后重试一次。
- 只有显式勾选「支持图片理解」的模型配置才能调用 `understand_image`。图片会被验证并转为受限的 data URL，不会回退到 MiniMax 或其他全局模型。
- 「启用 Prompt Cache Key」仍是一个供应商兼容性开关。开启时，系统会用模型配置、稳定系统提示和工具定义生成不可逆路由键，以提高同一稳定前缀的 KV Cache 命中率。

`web_search` 保持独立的 Exa / Parallel MCP 检索路径，不使用上述模型配置代替搜索提供商。

#### Agent interaction timeout (optional)

| Variable | Default | Description |
|---|---|---|
| `LABEX_AGENT_INTERACTION_TIMEOUT_POLL_INTERVAL_MS` | `30000` | Milliseconds between backend scans for expired question or permission interactions. If the run is still waiting when an interaction expires, the backend records a `RUN_INTERACTION_TIMED_OUT` event and marks the run as failed. Lower values release stuck runs sooner but increase database polling. |

#### Durable model retry (optional)

| Variable | Default | Description |
|---|---|---|
| `LABEX_AGENT_RETRY_POLL_INTERVAL_MS` | `1000` | Milliseconds between scans for persisted `retrying` tasks whose `next_retry_at` is due. Recoverable model failures are retried at most twice with persisted retry metadata, so a backend restart does not discard a pending retry. |

#### Execution lease and heartbeat (optional)

| Variable | Default | Description |
|---|---|---|
| `LABEX_AGENT_INSTANCE_ID` | generated at startup | Stable identifier for one backend instance. Set it explicitly when running multiple instances. |
| `LABEX_AGENT_EXECUTION_LEASE_DURATION_MS` | `30000` | Duration of the durable execution lease held by an active Agent task. |
| `LABEX_AGENT_EXECUTION_HEARTBEAT_INTERVAL_MS` | `10000` | Lease renewal interval. If renewal loses the fencing token, the local Agent run is cancelled. |

### 2.5 语言服务器（可选，仅供 Monaco LSP 用）

| 变量 | 默认值 |
|---|---|
| `LABEX_LSP_JAVA_CMD` | `jdtls` |
| `LABEX_LSP_TS_CMD` | `typescript-language-server --stdio` |
| `LABEX_LSP_VUE_CMD` | `vue-language-server --stdio` |
| `LABEX_LSP_PY_CMD` | `pyright-langserver --stdio` |

只有想让浏览器里的编辑器有 LSP 智能提示时才需要配，否则保持默认即可。

### 2.6 Windows PowerShell 快速导出

```powershell
$env:LABEX_AGENT_DB_URL        = "jdbc:mysql://localhost:3306/labex_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
$env:LABEX_AGENT_DB_USERNAME   = "root"
$env:LABEX_AGENT_DB_PASSWORD   = "你的 MySQL 密码"
$env:LABEX_AGENT_JWT_SECRET    = "替换成一串至少 64 字节的随机字符串"
$env:LABEX_AGENT_SECRET_STORE_MASTER_KEY = "替换成 Base64 编码的随机 32 字节"
$env:MINIMAX_API_KEY           = "你的 MiniMax key"
$env:TAVILY_API_KEY            = "你的 Tavily key"
```

生产服务器额外设置：

```powershell
$env:SPRING_PROFILES_ACTIVE = "production"
$env:LABEX_AGENT_WORKER_DOCKER_IMAGE = "registry.example.com/labex-agent-sandbox:2026-07"
```

### 2.7 bash / zsh 快速导出

```bash
export LABEX_AGENT_DB_URL='jdbc:mysql://localhost:3306/labex_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
export LABEX_AGENT_DB_USERNAME='root'
export LABEX_AGENT_DB_PASSWORD='你的 MySQL 密码'
export LABEX_AGENT_JWT_SECRET='替换成一串至少 64 字节的随机字符串'
export LABEX_AGENT_SECRET_STORE_MASTER_KEY='替换成 Base64 编码的随机 32 字节'
export MINIMAX_API_KEY='你的 MiniMax key'
export TAVILY_API_KEY='你的 Tavily key'
```

生产服务器额外设置：

```bash
export SPRING_PROFILES_ACTIVE='production'
export LABEX_AGENT_WORKER_DOCKER_IMAGE='registry.example.com/labex-agent-sandbox:2026-07'
```

> 仓库根目录的 `.env.example` 里也有同一套变量名，可以复制为 `.env` 后填写真实值；不要提交真实 `.env`。

---

## 3. 数据库准备

**首次**启动后端之前，先手动建一个空数据库。Spring Boot 会在启动时自动执行 `backend/src/main/resources/sql/schema.sql` 建表（见配置 `spring.sql.init.mode: always`），所以你**不需要**手动导入 SQL。

已有数据库启动时会通过 JDBC 元数据检查缺少列，只对缺失列执行加性 `ALTER TABLE ... ADD COLUMN` ；不会执行 `DROP` 、`TRUNCATE` 或删除数据。随后后端会把旧 `api_key` 和 `auth_header` 记录加密迁移并清空明文列。升级前仍应先备份数据库，回滚时恢复该备份并部署旧版本，详见 `docs/coding-agent-industrialization/secret-store-migration.md`。

```sql
CREATE DATABASE labex_agent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

这是唯一需要手动执行的 SQL。

---

## 4. 启动后端

从仓库根目录进入：

### Windows（PowerShell）

```powershell
cd backend
mvn spring-boot:run
```

### macOS / Linux

```bash
cd backend
mvn spring-boot:run
```

当控制台打出类似 `Started LabexAgentApplication in x.xx seconds` 的日志时，后端就起来了。

- API 基础路径：`http://localhost:8080/api`
- 健康检查（如果开了 actuator）：`http://localhost:8080/api/actuator/health`

### 用仓库自带的 Maven 配置启动（可选）

如果需要用本地 Maven 配置：

```bash
mvn -s settings-local.xml spring-boot:run
```

### 生产隔离 Worker

生产 profile 没有配置 `LABEX_AGENT_WORKER_DOCKER_IMAGE` 时，后端会拒绝启动。镜像必须先构建并由 Docker 守护进程拉取完成；未启动 Docker 时，命令、终端、LSP 或 stdio MCP 会返回基础设施错误，而不会回退到宿主机执行。

### 打成可执行 JAR

```bash
cd backend
mvn clean package -DskipTests
java -jar target/labex-agent-backend-*.jar
```

### Fresh release start after runtime state changes

When changing Java enums, state machines, or other runtime-linkage types, stop the old backend process completely. Do not rely on IDE HotSwap. On Windows, the release launcher now checks for an existing Labex backend JVM **before building** and refuses to continue if one is still running:

```powershell
cd backend
.\scripts\start-release.ps1
```

To run only the startup fence without building or launching a process:

```powershell
.\scripts\start-release.ps1 -PreflightOnly
```

The launcher fence covers starts performed through this script. A manually launched old JAR or an IDE process outside the launcher must still be stopped by the operator; it is not possible for this script to control an unmanaged deployment. See `docs/agent-context-provider-smoke-test.md` for a safe real-provider context smoke test.

Current OpenCode-style runtime alignment, Session/Message/Part persistence, recovery invariants, and acceptance evidence are documented in [`docs/coding-agent-industrialization/opencode-alignment-status.md`](docs/coding-agent-industrialization/opencode-alignment-status.md).

---

## 5. 启动前端

**另开一个**终端，从仓库根目录进入：

```bash
cd frontend
npm install        # 仅首次或依赖变更后需要执行
npm run dev
```

Vite 开发服务器监听：

- URL：`http://localhost:3000`
- 绑定地址：`0.0.0.0`（同一局域网下其他设备可访问）
- API 代理：`/api/*` → `http://localhost:8080/api/*`（含 WebSocket 透传，见 `vite.config.js`）

### 生产构建

```bash
cd frontend
npm run build      # 产物输出到 frontend/dist
npm run preview    # 本地预览生产构建
```

---

## 6. 首次使用流程

1. 按顺序启动 MySQL、后端、前端。
2. 浏览器打开 `http://localhost:3000`。
3. 注册第一个用户。如果前端 UI 暂未提供注册页，可以直接调用 API：

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"test123"}'
```

4. 用刚注册的账号登录。
5. 创建一个工作区，就可以跟 Agent 对话了。

登录后下发的 JWT 以用户数字 ID 作为 subject，所以剥离出来的 Agent / 项目代码可以继续沿用原本的 `studentId` 归属校验逻辑。

---

## 7. 鉴权接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/auth/register` | 注册新用户 |
| `POST` | `/api/auth/login` | 登录，返回 JWT |
| `GET`  | `/api/auth/userinfo` | 获取当前用户信息（需要 JWT） |

后续请求请在 Header 里带上 `Authorization: Bearer <token>`。

---

## 8. 端口速查

| 服务 | 默认端口 | 在哪里改 |
|---|---|---|
| 后端 | `8080`（context 为 `/api`） | `backend/src/main/resources/application.yml` |
| 前端开发服务器 | `3000` | `frontend/vite.config.js` → `server.port` |
| MySQL | `3306` | MySQL 服务端配置 |

如果端口被占用，要么释放它（Windows：`netstat -ano | findstr :8080` + 任务管理器；macOS/Linux：`lsof -i :8080`），要么改上面的值，同时把 Vite 代理的 target 同步改成新的后端地址。

---

## 9. 常见问题排查

### 后端启动报 `Communications link failure`
MySQL 没启动，或者 URL / 用户名 / 密码不对。检查 MySQL 服务状态和 `LABEX_AGENT_DB_*` 这几个环境变量。

### 后端报 `Unknown database 'labex_agent'`
你跳过了第 3 步。先手动建数据库再重启。

### 报 `JWT secret must be at least 64 bytes`
`LABEX_AGENT_JWT_SECRET` 为空或者太短。开发环境会对短占位串派生 512-bit 签名 key 以避免启动/登录中断；生产环境请换成至少 64 字节的高熵随机字符串。

### 前端所有请求都报 "Network Error"
- 后端没起来，或
- Vite 代理的 target 跟实际后端地址不一致。

打开浏览器 DevTools → Network，看看失败的请求到底打到 `localhost:3000/api/...` 还是别的地址，返回码是什么。

### 流式对话不是一段段出来的
确认 `vite.config.js` 里 `proxy['/api'].ws: true` 还在。没有这个配置，WebSocket 升级会回退成 HTTP，Agent 回复就变成一次性刷出来。

### 端口被占用
- Windows：`netstat -ano | findstr :8080`，再 `taskkill /PID <pid> /F`。
- macOS / Linux：`lsof -ti:8080 | xargs kill -9`。

### Agent 对话一上来就 401 / 403
`MINIMAX_API_KEY` 没配或配错了。其他功能（登录、项目、文件）不受影响。

### 首次 `mvn` 构建特别慢
Maven 在第一次运行时要把整个依赖树下载下来。后续构建会很快。确认网络可用，或者在 `backend/settings-local.xml` 里配镜像。

---

## 10. 其他说明

- 原 Labex 项目文件运行时不再被本项目依赖。
- 工作区运行时数据存放在 `LABEX_AGENT_PROJECT_BASE_PATH` 下。
- `node_modules/`、`dist/`、`target/`、`workspaces/`、`uploads/` 这些生成目录已被 `.gitignore` 忽略。
- **不要**提交真实密钥。仓库里的 `.env.example` 只有占位符。
