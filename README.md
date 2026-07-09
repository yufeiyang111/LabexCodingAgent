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
| MySQL | 8.0 及以上（服务需启动） | `mysql --version` |

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

### 2.2 存储路径（可选，默认值在 Windows 下可用）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `LABEX_AGENT_PROJECT_BASE_PATH` | `D:/LabexAgent/workspaces` | 用户工作区所在目录 |
| `LABEX_AGENT_UPLOAD_PATH` | `D:/LabexAgent/uploads` | 上传文件存放目录 |

如果你是 macOS / Linux，建议把这两个值改成自己家目录下的路径，例如 `~/labex-agent/workspaces`。

### 2.3 AI 集成（可选）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `MINIMAX_API_KEY` | _空_ | MiniMax provider 的 key —— Agent 对话要靠它，没配聊天就跑不起来 |
| `MINIMAX_BASE_URL` | `https://api.minimaxi.com/v1` | MiniMax API 地址 |
| `MINIMAX_API_HOST` | `https://api.minimaxi.com` | MiniMax API 主机 |
| `MINIMAX_MODEL` | `MiniMax-M2.7-highspeed` | 默认模型名 |
| `TAVILY_API_KEY` | _空_ | Web 搜索工具的 key |

### 2.4 语言服务器（可选，仅供 Monaco LSP 用）

| 变量 | 默认值 |
|---|---|
| `LABEX_LSP_JAVA_CMD` | `jdtls` |
| `LABEX_LSP_TS_CMD` | `typescript-language-server --stdio` |
| `LABEX_LSP_VUE_CMD` | `vue-language-server --stdio` |
| `LABEX_LSP_PY_CMD` | `pyright-langserver --stdio` |

只有想让浏览器里的编辑器有 LSP 智能提示时才需要配，否则保持默认即可。

### 2.5 Windows PowerShell 快速导出

```powershell
$env:LABEX_AGENT_DB_URL        = "jdbc:mysql://localhost:3306/labex_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
$env:LABEX_AGENT_DB_USERNAME   = "root"
$env:LABEX_AGENT_DB_PASSWORD   = "你的 MySQL 密码"
$env:LABEX_AGENT_JWT_SECRET    = "替换成一串至少 64 字节的随机字符串"
$env:MINIMAX_API_KEY           = "你的 MiniMax key"
$env:TAVILY_API_KEY            = "你的 Tavily key"
```

### 2.6 bash / zsh 快速导出

```bash
export LABEX_AGENT_DB_URL='jdbc:mysql://localhost:3306/labex_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
export LABEX_AGENT_DB_USERNAME='root'
export LABEX_AGENT_DB_PASSWORD='你的 MySQL 密码'
export LABEX_AGENT_JWT_SECRET='替换成一串至少 64 字节的随机字符串'
export MINIMAX_API_KEY='你的 MiniMax key'
export TAVILY_API_KEY='你的 Tavily key'
```

> 仓库根目录的 `.env.example` 里也有同一套变量名，可以复制为 `.env` 后填写真实值；不要提交真实 `.env`。

---

## 3. 数据库准备

**首次**启动后端之前，先手动建一个空数据库。Spring Boot 会在启动时自动执行 `backend/src/main/resources/sql/schema.sql` 建表（见配置 `spring.sql.init.mode: always`），所以你**不需要**手动导入 SQL。

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

### 打成可执行 JAR

```bash
cd backend
mvn clean package -DskipTests
java -jar target/labex-agent-backend-*.jar
```

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
