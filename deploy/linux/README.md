# Linux Web 生产部署说明

本目录用于把 Windows 的当前工作目录构建为发布包，再部署到 Debian Linux。首次部署的目标域名为 `labexagent.123845.xyz`；当前服务器为 2 GiB 内存，默认按低并发运行。

## 必读

- 支持矩阵与发布状态：仓库根 `README.md`
- 环境变量：复制 `.env.production.example` 为 `.env.production` 并填写真实值
- 启动校验：`ProductionStartupValidator` 与认证 Redis 校验会在 production profile 下失败并列出全部缺失项（DB、JWT、Secret Store、Redis、CORS/WebSocket 来源、Windows 盘符路径、`full_access` 权限）

## 部署拓扑

```text
Caddy（HTTPS）
  ├─ /            → frontend/dist 静态文件
  ├─ /api/        → Spring Boot :8080（SSE 关闭 proxy buffering）
  └─ /api/ws/     → WebSocket Upgrade（PTY 未恢复前只用 managed terminal）
Spring Boot（production profile）
  ├─ /srv/labex-agent/data/workspaces（持久用户项目）
  ├─ /srv/labex-agent/data/uploads（持久上传文件与图片）
  ├─ MySQL（Docker 内部网络）
  ├─ Redis（Docker 内部网络，认证限流、图形验证码和 OAuth 临时状态）
  └─ Docker daemon → labex-agent-sandbox 容器（非 root、只读根、bind mount 单个项目 workspace）
```

MySQL、Redis 不映射宿主机端口；公网只开放 HTTPS 和 SSH。

## 第一次发布

### 1. Windows：构建发布包

先启动 Docker Desktop，然后在仓库根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy\linux\build-release.ps1
```

脚本会构建后端 JAR、前端静态文件、后端镜像和 Worker 镜像，并输出到 `deploy/linux/release/<tag>/`。发布包不含 `.env`、数据库备份、上传文件或工作区。

### 2. Windows：导出与打包数据

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy\linux\export-database.ps1
powershell -ExecutionPolicy Bypass -File .\deploy\linux\prepare-data-archive.ps1
```

`export-database.ps1` 会由 MySQL 客户端交互式询问密码。两个生成文件均需要单独上传到服务器，且不能提交到 Git。

### 3. Linux：准备持久目录

把发布包上传到 `/srv/labex-agent/app` 后，以 root 执行：

```bash
cd /srv/labex-agent/app
chmod +x init-host.sh import-database.sh migrate-paths.sh
./init-host.sh
```

在 `/srv/labex-agent/app/.env.production` 手工填写真实环境变量。除 `.env.production.example` 的必填项外，必须把 `LABEX_AGENT_DOCKER_SOCKET_GID` 设置为：

```bash
stat -c '%g' /var/run/docker.sock
```

不要把 `.env.production` 发到聊天、放进发布包或提交到 Git。

### 4. Linux：导入镜像、数据与启动

先将 `backend-image.tar`、`worker-image.tar`、数据库 SQL 备份和数据压缩包上传到服务器；其中 SQL 和数据压缩包建议放进 `/srv/labex-agent/backups`。

```bash
cd /srv/labex-agent/app
docker load -i backend-image.tar
docker load -i worker-image.tar
docker compose --env-file .env.production up -d mysql redis
```

确认 MySQL 与 Redis 健康后，导入 SQL、解压 `workspaces` 和 `uploads` 到 `/srv/labex-agent/data/`，再执行路径迁移。数据库导入与路径迁移均有显式确认变量，避免误操作：

```bash
CONFIRM_DATABASE_IMPORT=yes ./import-database.sh /srv/labex-agent/backups/database.sql
CONFIRM_PATH_MIGRATION=yes ./migrate-paths.sh
docker compose --env-file .env.production up -d
docker compose --env-file .env.production ps
```

最后确认域名 DNS 已指向该服务器、云安全组开放 TCP 80/443，Caddy 会自动申请和续期 HTTPS 证书。

## 安全要点

- 生产 profile 拒绝 `full_access`、拒绝 smoke Worker 镜像、拒绝本地占位 JWT secret
- CORS / WebSocket 来源只接受显式 HTTPS 域名
- 认证 Redis 仅在内部网络暴露；GitHub/Google OAuth 未配置时登录页自动隐藏对应入口
- Worker 容器：`--network none`（按审批打开）、`--read-only`、`--cap-drop ALL`、非 root（uid 1001）、固定 image tag
- backend 需要 Docker socket 才能创建 Worker；即使后端用户不是 root，拥有 Docker socket 仍相当于宿主机高权限。当前通过低权限用户、固定 Worker 镜像、单并发和不开放 Docker TCP 端口降低风险；受控 worker broker 是后续更安全的替代方案
- 容器内不得出现 `LABEX_AGENT_JWT_SECRET`、DB 密码、Provider API key（已验证：镜像环境无密钥，`.dockerignore` 阻断 `.env` 进入构建上下文）
- **workspace 权限契约**：镜像内 sandbox 用户 uid=1001，宿主 workspace 目录必须对其可写：

  ```bash
  mkdir -p /srv/labex-agent/data/workspaces /srv/labex-agent/data/uploads
  chown -R 1000:1000 /srv/labex-agent/data/workspaces /srv/labex-agent/data/uploads
  ```

## 验收状态

| 任务 | 状态 |
|---|---|
| 构建配置 | 已提供，需在本机 Docker Desktop 启动后生成实际镜像 |
| 服务器基础环境 | 已确认 Docker、Compose、2 GiB Swap 可用 |
| 公网首次上线 | 待导入用户数据、配置 `.env.production`、切换 DNS 后验证 |

## 运行验收

```bash
# production 基础设施（无需模型配置）
bash deploy/linux/smoke.sh [backend-jar] [worker-image]

# Agent 链路（acceptance,docker + scripted provider + Docker worker + MySQL）
bash scripts/acceptance/linux-runtime.sh [backend-jar] [worker-image]
```

注意：WSL2 mirrored 网络下 8080 可能与 Windows 侧冲突，脚本使用 18080（`SERVER_PORT`）。
