# LabexAgent

<div align="center">

**自主 AI 编程 Agent 与云端编程工作区**

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3.4.21-blue.svg)](https://vuejs.org/)
[![Vite](https://img.shields.io/badge/Vite-5.1.6-646CFF.svg)](https://vitejs.dev/)
[![MySQL](https://img.shields.io/badge/MySQL-8.0%2B-4479A1.svg)](https://www.mysql.com/)
[![Tests](https://img.shields.io/badge/Backend%20Tests-1714%20PASS-success.svg)]()

</div>

---

LabexAgent 是从 Labex 云编程工作台剥离出来的独立版本：Spring Boot 3 后端 + Vue 3 前端，提供 Agent 工作区、项目文件操作、终端、MCP 服务配置、流式对话，以及改动 diff 的 apply / reject / undo 流程。鉴权用 JWT，subject 是用户数字 ID（前端代码仍以 `studentId` 命名沿用历史逻辑）。

Agent 运行时参考 OpenCode 的架构：以数据库为唯一持久化事实源（运行状态、可重放事件、模型 transcript、审批交互），SSE 向前端推送事件，前端按事件 reducer 渲染。

---

## 目录

- [功能概览](#功能概览)
- [项目结构](#项目结构)
- [1. 环境依赖](#1-环境依赖)
- [2. 配置](#2-配置)
- [3. 数据库初始化](#3-数据库初始化)
- [4. 启动后端](#4-启动后端)
- [5. 启动前端](#5-启动前端)
- [6. 使用流程](#6-使用流程)
- [7. Agent 工具集](#7-agent-工具集)
- [8. 生产部署](#8-生产部署)
- [9. 常见问题](#9-常见问题)
- [License & Notices](#license--notices)

---

## 功能概览

- **Agent 工作区**：流式对话（SSE）、工具调用进度、任务状态机（running / waiting_approval / completed / failed / cancelled）、改动 diff 审查与 apply / reject / undo。
- **运行时状态**：任务状态、执行 epoch、租约由 `AgentTask` + `AgentRunLifecycleService` 唯一管理；可重放事件走 `AgentRunEvent` 事务型 outbox；模型 transcript 与 tool call/result 落库到 `AgentRunMessage` / `AgentRunPart`，重启后可恢复。
- **上下文管理**：基于持久化 transcript 投影生成 provider 请求，支持上下文压缩（compaction）与 token 预算控制。
- **工具与权限**：31 个内置工具（文件、搜索、命令执行、测试、LSP、Web 搜索、计划、提问等），危险操作在 `waiting_approval` 等待前端审批；命令有黑白名单校验。
- **终端**：基于 WebSocket 的项目内交互式终端（xterm.js）。
- **MCP**：按用户配置连接 MCP server 并适配为工具。
- **工作区记忆**：跨会话的 workspace 记忆、项目代码索引（repo map、文件树）。

---

## 项目结构

```text
LabexAgent/
├── backend/                          # 后端服务（Spring Boot 3 + Java 17 + Maven）
│   ├── src/main/java/com/labex/
│   │   ├── controller/               # REST 控制器（Auth、StudentProject、Agent）
│   │   ├── service/                  # 业务服务（Auth、StudentProject、Terminal）
│   │   ├── entity/ + mapper/         # MyBatis-Plus 实体与 Mapper（表名沿用 t_ 前缀）
│   │   ├── filter/ + security/       # JWT 鉴权过滤器与工具
│   │   ├── config/                   # Security、MyBatis-Plus、WebSocket 配置
│   │   ├── common/                   # 统一返回 Result
│   │   ├── labexagent/               # Agent 核心层
│   │   │   ├── runtime/              # AgentLoopEngine（主循环）、SSE 推送、ToolCall 解析
│   │   │   ├── run/                  # 任务生命周期、Part 持久化、transcript 投影、compaction、恢复
│   │   │   ├── tool/                 # 工具接口与 31 个实现（tool/impl/）
│   │   │   ├── permission/           # 工具调用权限规则与审批
│   │   │   ├── llm/                  # LLM Provider 抽象与 OpenAI-Compatible 实现
│   │   │   ├── mcp/                  # MCP server 连接与工具适配
│   │   │   ├── diff/                 # 改动快照、apply/reject/undo（GitSnapshotService）
│   │   │   ├── service/              # 对话、任务、上下文编排、交互、token 统计等
│   │   │   ├── prompt/               # 系统提示词装配
│   │   │   ├── terminal/             # 项目内终端（WebSocket）
│   │   │   ├── command/              # Agent 内部命令
│   │   │   ├── worker/ + preview/    # 沙箱执行与 Web 预览
│   │   │   ├── websearch/            # 网络搜索
│   │   │   ├── lsp/                  # lsp4j 语言服务器进程管理
│   │   │   ├── skill/                # 用户自定义 skill 扫描
│   │   │   ├── network/              # 出站 URL 校验（SSRF 防护）
│   │   │   ├── attachment/ + multimodal/  # 输入附件与图片理解
│   │   │   └── ...                   # 其余子包按职责划分
│   │   ├── rag/                      # 独立 LLM 抽象（RAG / 图片理解 / Web 搜索用）
│   │   └── monitor/                  # 站点访问统计与监控
│   └── src/main/resources/
│       ├── application.yml           # 后端配置（含 labex-agent.* 环境变量默认值）
│       └── sql/schema.sql            # 数据库全量 DDL（启动时自动执行）
├── frontend/                         # 前端应用（Vue 3 + Vite + Element Plus）
│   └── src/
│       ├── views/                    # Login、CloudSpace、CloudWorkspace、ops 监控
│       ├── components/cloud/         # 工作区子组件
│       ├── components/terminal/      # xterm.js 终端封装
│       ├── components/MonacoEditor.vue
│       ├── stores/ + api/            # Pinia 状态与统一 API 封装
│       └── styles/                   # 全局主题样式
├── deploy/linux/                     # 生产部署（Dockerfile、docker-compose、nginx/Caddy）
├── deploy/windows/                   # Windows 公网部署脚本
├── scripts/                          # 验收脚本等
└── docs/                             # 设计文档、迭代记录、计划
```

---

## 1. 环境依赖

| 依赖 | 版本 | 验证命令 |
|---|---|---|
| JDK | 17 | `java -version` |
| Maven | 3.8+ | `mvn -v` |
| Node.js | 18+ | `node -v` |
| MySQL | 8.0+ | `mysql --version` |
| Redis | 7+ | `redis-cli ping` |

Windows 本地开发（命令执行走 WSL2）还需要 WSL2 + Debian（可选，`unsafe-local` 诊断模式可绕过沙箱）。

---

## 2. 配置

复制 `.env.example` 为 `.env`（根目录），后端启动时会读取。所有环境变量都有默认值，不配置也能本地跑起来，但生产环境必须覆盖：

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `LABEX_AGENT_DB_URL` | `jdbc:mysql://localhost:3306/labex_agent?...` | MySQL 连接串 |
| `LABEX_AGENT_DB_USERNAME` / `LABEX_AGENT_DB_PASSWORD` | `root` / 无 | 数据库账号 |
| `LABEX_AGENT_JWT_SECRET` | 占位 | JWT 签名密钥，生产必须 ≥64 字节高熵串 |
| `LABEX_AGENT_SECRET_STORE_MASTER_KEY` | 占位 | AES-GCM 主密钥（加密模型 API Key 与 MCP 凭证） |
| `LABEX_AGENT_AUTH_REDIS_URL` | `redis://localhost:6379` | 认证限流 / 验证码 / OAuth 状态存储（Redis 必用） |
| `LABEX_AGENT_PROJECT_BASE_PATH` | `D:/LabexAgent/workspaces` | 用户工作区根路径 |

认证模块：注册 / 登录 / 图形验证码（按风险阈值按需生成）/ GitHub、Google OAuth（可选，未配置时登录页隐藏按钮）/ 邀请注册。第三方登录不会自动建号，需先在账号设置中绑定。

模型配置：主 Agent 使用用户在前端「模型配置」中创建的 OpenAI-Compatible 模型（API Key / Base URL / 模型名 / 推理程度），凭证加密存储。RAG 图片理解等辅助能力可选配 `MINIMAX_API_KEY`、`TAVILY_API_KEY`。

站点监控（可选）：`/ops` 页面查看 PV/UV、热门路径、状态码分布、访问明细与系统资源曲线。入口用独立访问校验码，配置 `LABEX_AGENT_MONITOR_ACCESS_CODE` 后启用；IP 归属地用离线 `ip2region_v4.xdb` 库（本地查询，不外发访客 IP）。

完整环境变量表见 `.env.example` 与 `backend/src/main/resources/application.yml`。

---

## 3. 数据库初始化

```sql
CREATE DATABASE labex_agent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

后端配置 `spring.sql.init.mode: always`，启动时自动执行 `schema.sql` 建表（表名沿用历史 `t_` 前缀，约 40 张表）。已有数据库通过 JDBC 元数据做加性 `ALTER TABLE` 迁移，不会清表删库。

> 注意：手工改库前先备份 `schema.sql`，否则下次启动会按该文件重建表结构。

---

## 4. 启动后端

```bash
cd backend
mvn spring-boot:run
```

打 JAR 并运行：

```bash
cd backend
mvn clean package -DskipTests
java -jar target/labex-agent-backend-*.jar
```

- REST 根路径：`http://localhost:8080/api`（`server.servlet.context-path=/api`）
- WebSocket 终端端点：`/api/ws/terminal`（与 SSE 不共享连接）

---

## 5. 启动前端

```bash
cd frontend
npm install
npm run dev
```

- 前端地址：`http://localhost:3000`
- `vite.config.js` 里 `/api` 代理到 `http://localhost:8080`（`ws: true` 透传 WebSocket，流式对话依赖此配置；改代理后必须重启 dev server）
- 生产构建：`npm run build`（产物在 `frontend/dist`）

---

## 6. 使用流程

1. 打开 `http://localhost:3000`，注册 / 登录。
2. 创建项目（或上传压缩包 / 克隆已有代码库）。
3. 在「模型配置」中添加一个 OpenAI-Compatible 模型（如 DeepSeek、Qwen、GPT 等），作为主 Agent 的 provider。
4. 进入工作区，输入需求开始对话。Agent 执行工具时：
   - 文件改动会以 diff 形式展示，可逐条 Apply / Reject / Undo；
   - 危险命令（`rm -rf`、`git reset --hard`、`git push --force`、读取 `.env` 等）会先等待你审批；
   - 输入框输入 `/` 可调出快捷指令（`/plan`、`/test`、`/help`、`/clear` 等）；
   - 支持拖拽 / 粘贴图片给模型做多模态理解。

---

## 7. Agent 工具集

`tool/impl/` 下有 34 个工具实现；其中 `invalid` 是内部占位（不注册给模型），`repo_clone`、`external_directory` 已禁用。实际注册给模型的工具 **31 个**，按 `ToolRegistry` 的模式白名单暴露（plan / explore 模式受限，build 模式默认全量）：

| 类别 | 工具 |
|---|---|
| 文件 | `read_file` `write_file` `edit_file` `apply_patch` `list_files` |
| 搜索 | `grep` `glob` `search_code` `repo_map` `project_overview` |
| 执行 | `run_command` `bash` `run_tests` `execute_code` `read_tool_output` |
| LSP | `lsp` |
| 网络 | `web_search` `web_fetch` |
| 计划 | `create_plan` `plan_exit` `todo_write` |
| 交互 | `question` `task` `context_note` |
| 扩展 | `mcp_call` `skill` `understand_image` |
| 预览 | `start_preview` `stop_preview` |
| 配置 | `propose_project_config`（及兼容别名 `repo_overview`→`project_overview`、`todowrite`/`todo`→`todo_write`、`lsp_symbols`/`diagnostics`→`lsp` 等） |

新增工具：实现 `labexagent/tool/AgentTool` 接口，在 `ToolRegistry` 注册，权限规则加到 `permission/DefaultPermissionRuleset`。

---

## 8. 生产部署

Linux 生产部署配置在 [deploy/linux/](deploy/linux/)：`backend.Dockerfile`、`docker-compose.yml`、`nginx.conf` / `Caddyfile`、初始化与迁移脚本、构建发布脚本、`smoke.sh` 冒烟检查，以及独立的 `deploy/linux/README.md`。

要点：
1. `SPRING_PROFILES_ACTIVE=production`；
2. 配置生产沙箱镜像（`LABEX_AGENT_WORKER_DOCKER_IMAGE`）、高熵 `LABEX_AGENT_JWT_SECRET`、`LABEX_AGENT_SECRET_STORE_MASTER_KEY`；
3. Nginx / Caddy 反代 + HTTPS，注意 WebSocket / SSE 透传（`proxy_buffering off` 等）。

生产 profile 启动时做 Fail-Fast 自检：发现 Windows 路径残留、默认弱密钥、未配置沙箱镜像或不安全权限 profile 会拒绝启动并输出诊断。

Windows 公网部署脚本在 [deploy/windows/](deploy/windows/)。

---

## 9. 常见问题

**Q1: 后端报数据库连接失败？**
确认 MySQL 在 `3306` 监听、库已创建、`.env` 中的连接串与账号密码正确。

**Q2: 提示 JWT secret 太短？**
HS512 要求 ≥64 字节。本地占位符会自动派生安全密钥；生产必须配置高熵随机串（`openssl rand -base64 64`）。

**Q3: 前端请求 Network Error？**
确认后端在 `8080` 启动、`vite.config.js` 代理 target 正确；改过代理配置要重启 dev server。

**Q4: 流式对话变成一次性返回？**
`vite.config.js` 里 `proxy['/api']` 的 `ws: true` 被去掉，或反代没有关闭 buffering 且未透传 Upgrade 头。

**Q5: 命令执行报 WSL / bubblewrap 错误？**
确认 WSL2 正常且 Debian 里已装 bubblewrap（`wsl -d Debian -- bash -lc "apt-get install -y bubblewrap"`）。仅调试可临时用 `unsafe-local` 诊断 profile。

---

## License & Notices

- 架构参考 OpenCode（MIT），复刻与适配记录见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 与 `docs/` 下迭代文档。