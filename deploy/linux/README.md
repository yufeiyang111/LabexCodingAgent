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
- Worker 容器：`--network none`（按审批打开）、`--read-only`、`--cap-drop ALL`、非 root、固定 image tag
- backend 若直接挂载 Docker socket，等同 root 权限：见 T3.2 完成后补充的 broker 替代方案与风险记录
- 容器内不得出现 `LABEX_AGENT_JWT_SECRET`、DB 密码、Provider API key（T3.2 有验证步骤）

## 验收状态

| 任务 | 状态 |
|---|---|
| T3.1 生产配置固化 | verified（`ProductionConfigurationTest` 10 tests） |
| T3.2 Docker Worker image | unverified（需 Linux + Docker） |
| T3.3 反向代理与静态前端 | unverified |
| T3.4 真实 Linux smoke | unverified |
