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

LabexAgent 是一个自托管的 AI 编程 Agent 平台：Spring Boot 3 后端 + Vue 3 前端，为每个用户提供独立的云端工作区。核心能力包括 Agent 对话与工具调用、项目文件操作、交互式终端、MCP 服务配置、流式对话，以及文件改动的 diff 审查与 apply / reject / undo 流程。鉴权走 JWT，subject 是用户数字 ID。

## 特性

- **Agent 工作区**：流式对话（SSE 推送）、工具调用过程实时可见、任务状态机（running / waiting_approval / completed / failed / cancelled）、改动 diff 审查与逐条 apply / reject / undo。
- **运行时状态管理**：任务状态、执行 epoch、租约由 `AgentTask` + `AgentRunLifecycleService` 唯一管理；可重放事件走 `AgentRunEvent` 事务型 outbox；模型 transcript 与 tool call/result 落库到 `AgentRunMessage` / `AgentRunPart`，进程重启后可按事件游标恢复，浏览器刷新或 SSE 断线不影响任务推进。
- **上下文与压缩**：基于持久化 transcript 投影生成 provider 请求，支持上下文压缩（compaction）、token 预算控制与超预算时的结构化处理。
- **工具与权限**：内置 31 个工具（文件、搜索、命令执行、测试、LSP、Web 搜索、计划、提问、MCP 等），危险操作进入 `waiting_approval` 等待前端审批；终端命令另有黑白名单校验，出站请求有 SSRF 防护。
- **多模态输入**：对话中支持拖拽 / 粘贴图片，由图片理解工具交给模型分析。
- **交互式终端**：基于 WebSocket 的项目内终端（xterm.js），可运行 shell 命令与语言运行时。
- **MCP 支持**：按用户配置连接 MCP server 并自动适配为 Agent 工具。
- **工作区记忆**：跨会话的 workspace 记忆与项目代码索引（repo map、文件树），帮助 Agent 在多次对话中保持对项目的理解。

---

## 目录

- [技术栈](#技术栈)
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
- [License](#license)

---

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Java 17 · Spring Boot 3 · MyBatis-Plus · Spring Security · Spring WebSocket |
| 前端 | Vue 3 · Vite · Pinia · Vue Router · Element Plus · xterm.js · Monaco Editor |
| 数据库 | MySQL 8.0+（schema 启动时自动执行，表名沿用历史 `t_` 前缀） |
| 缓存 / 会话 | Redis 7+（认证限流、验证码、OAuth 状态） |
| 鉴权 | JWT（HS512）+ BCrypt 密码哈希 + 图形验证码 + OAuth（GitHub / Google，可选） |
| 模型接入 | OpenAI-Compatible 协议（DeepSeek、Qwen、GPT 等），密钥加密存储 |
| 部署 | Docker / docker-compose / Nginx / Caddy（Linux），PowerShell 脚本（Windows） |

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
└── scripts/                          # 验收脚本等
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

Windows 本地开发（命令执行走沙箱）建议安装 WSL2 + Debian；仅调试时可临时用 `unsafe-local` 诊断模式绕过沙箱，生产环境禁用。

---

## 2. 配置

复制根目录 `.env.example` 为 `.env`，后端启动时读取。所有环境变量都有默认值，不配置也能本地跑起来，但生产环境必须覆盖以下几项：

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `LABEX_AGENT_DB_URL` | `jdbc:mysql://localhost:3306/labex_agent?...` | MySQL 连接串 |
| `LABEX_AGENT_DB_USERNAME` / `LABEX_AGENT_DB_PASSWORD` | `root` / 无 | 数据库账号 |
| `LABEX_AGENT_JWT_SECRET` | 占位 | JWT 签名密钥，生产必须为 ≥64 字节的高熵串 |
| `LABEX_AGENT_SECRET_STORE_MASTER_KEY` | 占位 | AES-GCM 主密钥（加密模型 API Key 与 MCP 凭证） |
| `LABEX_AGENT_AUTH_REDIS_URL` | `redis://localhost:6379` | 认证限流 / 验证码 / OAuth 状态存储（Redis 必用） || `LABEX_AGENT_CONVERSATION_CACHE_ENABLED` | `true` | 会话列表、最近历史第一页、上下文状态 Redis 热缓存开关；关闭时回退 MySQL |
| `LABEX_AGENT_CONVERSATION_CACHE_LIST_TTL` | `120s` | 会话列表缓存 TTL |
| `LABEX_AGENT_CONVERSATION_CACHE_HISTORY_TTL` | `60s` | 最近历史第一页缓存 TTL |
| `LABEX_AGENT_CONVERSATION_CACHE_CONTEXT_STATUS_TTL` | `30s` | 上下文状态缓存 TTL |
| `LABEX_AGENT_CONVERSATION_CACHE_DOUBLE_DELETE_DELAY_MS` | `500` | 数据库提交后的延迟二次删缓存时间 |
| `LABEX_AGENT_CONVERSATION_CACHE_KEY_PREFIX` | `labex:conversation` | 会话热缓存键前缀，与认证 Redis key 隔离 |
| `LABEX_AGENT_PROJECT_BASE_PATH` | `D:/LabexAgent/workspaces` | 用户工作区根路径 |
| `LABEX_AGENT_DOCKER_HOST_WORKSPACE_BASE_PATH` | 空 | 控制面容器化部署（backend 容器 + 宿主 docker.sock）时必填：把容器内工作区前缀改写为 daemon 可见的宿主机目录，否则 worker 容器 bind mount 报 `bind source path does not exist`。Linux 生产 compose 填 `/srv/labex-agent/data/workspaces` |
| `LABEX_AGENT_SUBAGENT_MAX_TURNS` | `16` | 子代理工具循环最大轮数 |
| `LABEX_AGENT_SUBAGENT_TOKEN_BUDGET` | `65536` | 单个子代理默认生成 token 预算 |
| `LABEX_AGENT_SUBAGENT_MAX_PARALLEL` | `4` | 同一父任务并行子代理上限 |
| `LABEX_AGENT_SUBAGENT_TASK_TIMEOUT_MS` | `900000` | 单个前台子代理工具调用 watchdog（15 分钟） |
| `LABEX_AGENT_SUBAGENT_MAX_SPAWN_DEPTH` | `3` | 子代理嵌套深度上限（主 Agent=0 层，最多再派 3 层） |
| `LABEX_AGENT_SUBAGENT_RUN_POOL_SIZE` | `12` | 子代理运行专用线程池大小（独立于主 Agent 执行池，防前台等待死锁） |

**子代理会话**：`task` 工具派发的子代理是拥有独立 `AgentConversation + AgentTask` 的真实会话，与主 Agent 复用同一套 durable 运行时（transcript / Part / 状态机 / 压缩 / SSE 订阅），不再使用内存循环。类型分工对齐 OpenCode：`explore`/`scout` 只读白名单，`general` 可写并走既有审批 + diff 审查流；所有子代理开放 `todo_write` 规划自身任务；嵌套由 `spawn_depth ≤ 3` 硬上限约束。父任务卡片可一键在新标签页打开子代理会话，支持多轮追问、断线恢复与刷新回放；子代理会话不混入普通会话列表，且服务端拒绝任何把子会话升级为主 Agent 写模式的请求。


**工作区文件操作护栏**：资源管理器的上传 / 复制 / 移动 / 下载 / 目录搜索 / 图片预览受 `LABEX_AGENT_FILE_OPS_*` 系列环境变量约束（单文件与整批大小、递归深度与文件数、每用户每分钟频控、全局并发槽），完整清单见 `.env.example`。频控复用认证域 Redis 计数，Redis 不可用时退化为进程内窗口计数；图片预览仅放行 PNG/JPEG/GIF/WEBP/BMP/ICO（magic byte 校验，SVG 拒绝），响应带 `nosniff` 与 CSP sandbox；复制/移动/上传遇同名冲突时由用户逐个裁决跳过或覆盖，可勾选对剩余冲突整批生效。

**项目异步导出**：导出走异步任务——点击后创建任务立即返回，后台打包到 `LABEX_AGENT_EXPORT_STORAGE_DIR`（默认 `${LABEX_AGENT_UPLOAD_PATH}/exports`），前端轮询进度条，完成后从磁盘下载成品，长任务不再占用 HTTP 连接、不会触发网关超时。默认排除 node_modules/dist/target 等依赖与构建产物目录（可在对话框勾选"包含全部文件"）；`.labex/` 等平台保留目录任何模式都不导出。成品 zip 按 `LABEX_AGENT_FILE_OPS_EXPORT_JOB_TTL_MINUTES` 定时清理，服务重启后的孤儿文件按 mtime 兜底清除。

**Agent 临时产物清理**：后台单一调度者按保留期清理 workspace 内可再生的诊断文件——`.labex/agent-logs/` 运行日志（默认 7 天）、`.labex-agent/artifacts/task-N/` 工具输出与 `.labex-agent/artifacts/preview/` 预览日志（默认 7 / 3 天）、`.labex-agent/{worker-tmp,terminal-tmp,runtime}/` 执行临时目录（默认 6 小时）。只处理终态（completed/failed/cancelled）任务的产物：归属会话存在 `waiting_*` 等非终态 task 或活跃 preview 时一律跳过，checkpoint、git snapshot/worktree、workspace memory、会话历史与 token 明细等恢复类数据永不触碰；删除失败（Windows 文件锁）留待下一轮重试。单轮项目数 / 条目数 / 字节数有硬预算，完整清单见 `.env.example` 的 `LABEX_AGENT_CLEANUP_*`。

**认证模块**：注册 / 登录 / 图形验证码（按风险阈值按需生成）/ GitHub、Google OAuth（可选，未配置时登录页隐藏按钮）/ 邀请注册。第三方登录不会自动建号，需先在账号设置中绑定。

**模型配置**：主 Agent 使用用户在前端「模型配置」中创建的 OpenAI-Compatible 模型（API Key / Base URL / 模型名 / 推理程度），凭证加密存储。RAG 图片理解等辅助能力可选配 `MINIMAX_API_KEY`、`TAVILY_API_KEY`。

**使用教程**：登录后从主页面主题按钮左侧的书本图标进入 `/tutorials`。教程目录和正文来自 `t_tutorial_document`，首启会幂等写入默认教程；后续可由运维管理端在同一表上进行编辑、发布和下线。`GET /tutorials` 只返回已发布目录，`GET /tutorials/{slug}` 返回已发布正文。

**站点监控（可选）**：`/ops` 页面查看 PV/UV、热门路径、状态码分布、访问明细与系统资源曲线，以及 MySQL / Redis / Provider / Worker / MCP / Workspace 的依赖健康状态（`/ops/health`，单个依赖超时或异常被隔离，不会拖垮整体）、Agent 运行任务查询（`/ops/runtime`：任务列表分页、状态/用户/Provider/超时租约过滤、任务详情、事件时间线，全部只读投影自权威状态）、指标趋势（`/ops/metrics`：系统/任务/token 低频采样、1h~30d 历史趋势、按保留天数自动清理）、告警中心（`/ops/alerts`：规则 CRUD、基于持久化采样的低频评估、FIRING/ACKNOWLEDGED/SILENCED/RESOLVED 状态机、去重与冷却、可选 Webhook 通知及失败重试、确认/静默/恢复）、故障（`/ops/incidents`：OPEN/ACKNOWLEDGED/MITIGATING/RESOLVED/CLOSED 状态机、处理时间线）、事件流（`/ops/events`：启动、告警、故障、受控操作统一落库）与审计日志（`/ops/audit`：敏感键值自动脱敏、按保留天数清理）。入口用独立访问校验码，配置 `LABEX_AGENT_MONITOR_ACCESS_CODE` 后启用；**默认只读**，配置 `LABEX_AGENT_MONITOR_OPERATOR_CODE` 后用操作码登录可获得 `OPS_OPERATOR` 角色以执行告警/故障处理与受控操作（`/ops/operations`：取消任务、重试任务、释放过期租约、Worker 暂停/恢复，全部要求幂等键并写审计；任务取消/重试/租约恢复都委托既有生命周期服务，不直接改 `AgentTask`）。IP 归属地使用离线 `ip2region_v4.xdb` 库本地查询，不外发访客 IP。

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

仓库自带 Maven 配置：`mvn -s settings-local.xml spring-boot:run`（默认空 mirror）。

---

## 5. 启动前端

```bash
cd frontend
npm install
npm run dev
```

- 前端地址：`http://localhost:3000`
- `vite.config.js` 里 `/api` 代理到 `http://localhost:8080`（`ws: true` 透传 WebSocket，流式对话依赖此配置；**改了代理配置必须重启 dev server**）
- 生产构建：`npm run build`（产物在 `frontend/dist`）
- 本地预览：`npm run preview`

---

## 6. 使用流程

1. 打开 `http://localhost:3000`，注册 / 登录。
2. 创建项目（支持本地新建、上传压缩包、克隆已有代码库）。
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
3. Nginx / Caddy 反代 + HTTPS，注意 WebSocket / SSE 透传（`proxy_buffering off`、透传 Upgrade 头等）。

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

## License

本项目源码以 MIT 许可证发布。部分架构设计参考 OpenCode（MIT License, Copyright (c) 2024 SST Inc），复刻与适配记录见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
