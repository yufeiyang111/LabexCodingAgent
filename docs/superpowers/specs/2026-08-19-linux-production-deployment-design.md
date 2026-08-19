# LabexAgent Linux 生产部署设计

日期：2026-08-19

## 目标与范围

将当前 Windows 工作目录 `D:\LabexAgent` 的可运行版本部署到一台 Debian 13、x86_64、约 2 GiB RAM 的 Linux 服务器，并通过 Cloudflare DNS 使用 `labexagent.123845.xyz` 访问。

本次只增加或调整 `deploy/linux/` 下的部署配置、迁移脚本和部署文档，不改动登录注册以外的业务逻辑，也不覆盖用户当前工作区中的其他未提交改动。

## 约束

- 服务器内存约 1.9 GiB，已启用 2 GiB Swap；默认按低并发部署。
- MySQL 与 Redis 只允许在 Docker 内部网络访问，不发布 3306/6379 到公网。
- 对外只提供 80/443；SSH 防火墙策略在确认云厂商 SSH 端口后单独配置。
- 生产密钥、OAuth Secret、数据库密码和本机 `.env` 不进入构建上下文、不上传到仓库或聊天。
- 现有数据库中 `t_student_project.archive_path` 和 `workspace_path` 可能保存 Windows 绝对路径，迁移时只替换路径前缀，不修改业务数据。
- `workspaces` 与 `uploads` 必须位于容器外的持久目录，容器重建不能影响它们。

## 推荐架构

```text
Cloudflare DNS
    |
    v
Caddy（HTTPS、SPA 静态文件、/api 代理、SSE/WebSocket 透传）
    |
    v
Spring Boot 后端
    |-- /srv/labex-agent/data/workspaces
    |-- /srv/labex-agent/data/uploads
    |-- MySQL（Docker 内部网络）
    |-- Redis（Docker 内部网络）
    `-- Docker Worker（按需、低并发）
```

应用目录与数据目录分离：

```text
/srv/labex-agent/
├── app/                         # 当前发布版本、Compose、Caddyfile、前端 dist
├── data/
│   ├── workspaces/              # Windows D:\LabexAgent\workspaces 的迁移目标
│   └── uploads/                 # Windows D:\LabexAgent\uploads 的迁移目标
└── backups/                     # MySQL 和部署前快照
```

后端以容器运行，使用固定的后端镜像；Caddy、MySQL、Redis 使用固定版本标签。后端仅以只读方式挂载 Docker socket 以保持当前 Worker 契约，同时在部署文档中明确该挂载的宿主机逃逸风险；不开放 Docker TCP 端口。

## 构建与发布流程

1. 在 Windows 的 `D:\LabexAgent` 工作目录构建后端 JAR、前端 `dist` 和 `docker/sandbox/Dockerfile` 对应的 Worker 镜像。
2. 构建上下文排除 `.env`、`node_modules`、`target`、`dist`、工作区和上传目录。
3. 将后端镜像、Worker 镜像、前端 `dist`、`deploy/linux` 配置上传到服务器的发布目录。
4. 在服务器上创建非应用数据目录、设置 Worker 所需的 UID 1001 权限，再启动 MySQL 与 Redis。
5. 首次启动前导入 MySQL 备份，并执行非破坏性的路径前缀迁移：
   `D:\LabexAgent\workspaces` → `/srv/labex-agent/data/workspaces`。
6. 启动后端和 Caddy，先用服务器本机回环地址验证，再切换 Cloudflare DNS。
7. 通过域名验证登录、注册、图形验证码、OAuth 回调、项目列表、原有项目文件、图片上传、SSE 和 WebSocket/终端相关路径。

## 配置边界

生产环境使用 `.env.production`，由部署者在服务器手工创建，至少包括：

- 数据库连接和密码；
- 与现有账号兼容的 `LABEX_AGENT_JWT_SECRET`；
- Secret store 主密钥；
- Redis 内部地址；
- `https://labexagent.123845.xyz` 的 CORS、WebSocket 和 OAuth 回调地址；
- GitHub/Google OAuth Client ID/Secret（未配置时按钮保持隐藏）；
- Linux 工作区、上传目录和固定 Worker 镜像标签；
- 低内存服务器的 Worker 并发和内存上限。

不在 Compose、镜像层或前端静态文件中注入 JWT Secret、数据库密码、OAuth Secret、模型 API Key。

## 数据迁移与回滚

- Windows 端先生成带时间戳的 MySQL 导出文件；原数据库保持不变。
- `workspaces`、`uploads` 采用独立压缩包或 rsync/scp 传输，服务器先写入临时目录，校验完成后再切换到正式目录。
- Redis 不迁移持久业务数据；服务器 Redis 重新创建。
- 迁移脚本必须检查源前缀、目标目录和 SQL 备份存在后才允许执行，禁止执行数据库 reset、drop 或覆盖式 schema 操作。
- 上线失败时，Cloudflare DNS 保持原入口或切回旧记录；服务器保留上一版本应用目录和数据库备份。

## 资源与安全策略

- 2 GiB RAM 只允许低并发；Worker 默认单并发，单容器内存限制低于宿主机可用内存，避免 MySQL/Java 被整体挤死。
- Caddy 请求体上限与 Spring Boot 的 100MB 上传限制一致；SSE 关闭代理缓冲，WebSocket 使用 HTTP/1.1 Upgrade。
- MySQL/Redis 不绑定宿主机公网接口；云安全组和主机防火墙只允许 SSH、80、443。
- SSH 加固、备份和监控在业务可访问后执行，避免第一次配置时误锁 SSH。
- 当前后端挂载 Docker socket 是高风险边界；在受控 worker broker 落地前，必须限制后端权限、保持只读挂载并记录该残余风险。

## 验证清单

### 构建验证

- `mvn clean package -DskipTests` 成功并生成唯一 JAR。
- `npm run build` 成功并生成 `frontend/dist`。
- Worker 镜像能以 uid 1001 启动，不能读取宿主密钥。
- Compose 配置通过 `docker compose config`，不含真实 Secret。

### 服务器验证

- MySQL、Redis 健康检查通过，端口仅在 Docker 内部可见。
- 后端启动日志无 production validator 错误，能访问 `/api/auth/*`。
- Caddy 自动签发证书，HTTP 正确跳转 HTTPS。
- 登录、注册、图形验证码、OAuth 成功/失败回调符合当前设计。
- 迁移前已有项目、项目文件、上传图片和 Agent 附件可读写。
- SSE 能持续收到事件；WebSocket/终端路径按当前前端实际使用情况验证。
- 重启应用容器后数据仍存在；回滚上一应用目录不影响 `data/`。

## 不在本次范围内

- 不迁移 Windows Redis 内部临时键。
- 不将 MySQL 或 Redis 暴露到公网供远程连接。
- 不重构 Agent 运行时、文件存储服务或前端页面。
- 不自动读取或复制任何敏感 `.env` 内容。
- 不在没有用户确认的情况下切换 Cloudflare DNS 或执行生产数据库命令。

