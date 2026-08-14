# Linux Web 部署说明（T3 系列）

本目录是 Linux Web 生产部署的配置与文档。当前状态：**配置已固化（T3.1），Docker Worker image 与真实 Linux smoke（T3.2–T3.4）尚未验收**，不能宣称 `Linux Web Ready`。

## 必读

- 支持矩阵与发布状态：仓库根 `README.md`
- 环境变量：复制 `.env.production.example` 为 `.env.production` 并填写真实值
- 启动校验：`ProductionStartupValidator` 在 production profile 下启动前失败并列出全部缺失项（DB、JWT、Secret Store、CORS/WebSocket 来源、Windows 盘符路径、`full_access` 权限）

## 部署拓扑（目标）

```text
HTTPS (Nginx/Caddy)
  ├─ /            → frontend/dist 静态文件
  ├─ /api/        → Spring Boot :8080（SSE 关闭 proxy buffering）
  └─ /api/ws/     → WebSocket Upgrade（PTY 未恢复前只用 managed terminal）
Spring Boot (SPRING_PROFILES_ACTIVE=production)
  ├─ MySQL（生产凭据 + 备份）
  └─ Docker daemon → labex-agent-sandbox 容器（非 root、只读根、bind mount 单个项目 workspace）
```

## 启动步骤（T3.4 验收后更新为已验证命令）

```bash
cd /srv/labex-agent
cp deploy/linux/.env.production.example .env.production
# 填写真实值后：
mvn clean package -DskipTests
SPRING_PROFILES_ACTIVE=production java -jar backend/target/labex-agent-backend-*.jar
```

## 安全要点

- 生产 profile 拒绝 `full_access`、拒绝 smoke Worker 镜像、拒绝本地占位 JWT secret
- CORS / WebSocket 来源只接受显式 HTTPS 域名
- Worker 容器：`--network none`（按审批打开）、`--read-only`、`--cap-drop ALL`、非 root（uid 1001）、固定 image tag
- backend 直接挂载 Docker socket 等同 root 权限（compose 内 `:ro` + 低权限 user）：替代方案是受控 worker broker（白名单 API：create-run/attach/cleanup）；broker 落地前不得移除 compose 中的风险注释
- 容器内不得出现 `LABEX_AGENT_JWT_SECRET`、DB 密码、Provider API key（已验证：镜像环境无密钥，`.dockerignore` 阻断 `.env` 进入构建上下文）
- **workspace 权限契约**：镜像内 sandbox 用户 uid=1001，宿主 workspace 目录必须对其可写：

  ```bash
  mkdir -p /srv/labex-agent/workspaces /srv/labex-agent/uploads
  chown -R 1001:1001 /srv/labex-agent/workspaces /srv/labex-agent/uploads
  ```

## 验收状态

| 任务 | 状态 |
|---|---|
| T3.1 生产配置固化 | verified（`ProductionConfigurationTest` 10 tests） |
| T3.2 Docker Worker image | verified（WSL2 真实 Docker：非 root、离线执行、无密钥、容器清理、smoke test PASS） |
| T3.3 反向代理与静态前端 | verified（真实 nginx 容器：静态/SPA fallback/代理路径/HTTPS 跳转） |
| T3.4 真实 Linux smoke | **verified（WSL2 内真实 Linux + Docker + MySQL）**：`smoke.sh`（production 基础设施）+ `linux-runtime.sh`（Agent 链路：真实 Docker 执行 npm test 回写、SSE、重启恢复、双用户隔离）全绿 |

## 运行验收

```bash
# production 基础设施（无需模型配置）
bash deploy/linux/smoke.sh [backend-jar] [worker-image]

# Agent 链路（acceptance,docker + scripted provider + Docker worker + MySQL）
bash scripts/acceptance/linux-runtime.sh [backend-jar] [worker-image]
```

注意：WSL2 mirrored 网络下 8080 可能与 Windows 侧冲突，脚本使用 18080（`SERVER_PORT`）。
