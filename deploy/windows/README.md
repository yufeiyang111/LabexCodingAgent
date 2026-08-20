# Windows 公网入口

该目录只负责本机发布入口，不修改业务模块。拓扑如下：

```text
Cloudflare Access（仅允许指定邮箱）
  -> Cloudflare Tunnel（labex）
  -> 127.0.0.1:4173（frontend/dist 的 Vite preview）
  -> /api、/api/ws -> 127.0.0.1:8080（Spring Boot）
```

## 首次配置

1. 确认后端已在 `127.0.0.1:8080` 运行，并且 Redis 已启动。
2. 执行一次 DNS 路由：

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\deploy\windows\register-dns.ps1
   ```

3. 在 Cloudflare Zero Trust 中创建 Self-hosted Application：

   - Domain：`labexagent.123845.xyz`
   - Policy：`Allow` -> `Emails` -> 你的邮箱
   - 不添加其他 Allow 策略；默认拒绝其他访问者
   - 建议开启一次性邮箱验证码（One-time PIN）

4. 在本机受保护的 `.env` 中设置 OAuth 公网回调（不要把密钥提交到仓库）：

   ```text
   LABEX_AGENT_AUTH_OAUTH_CALLBACK_BASE_URL=https://labexagent.123845.xyz/api/auth/oauth
   LABEX_AGENT_AUTH_OAUTH_FRONTEND_CALLBACK=https://labexagent.123845.xyz/login
   LABEX_AGENT_AUTH_OAUTH_BINDING_FRONTEND_CALLBACK=https://labexagent.123845.xyz/projects
   ```

5. 启动发布入口：

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\deploy\windows\start-public.ps1 -Build
   ```

## 日常操作

```powershell
# 启动（已有 dist 时不重新构建）
powershell -ExecutionPolicy Bypass -File .\deploy\windows\start-public.ps1

# 停止前端预览和 Tunnel；不会停止 Spring Boot
powershell -ExecutionPolicy Bypass -File .\deploy\windows\stop-public.ps1
```

运行日志和 PID 文件位于 `%LOCALAPPDATA%\LabexAgent\logs`，运行时 Tunnel 配置位于 `%LOCALAPPDATA%\LabexAgent\cloudflared.yml`，不会进入 Git。

## 安全边界

- 前端预览和后端只绑定 `127.0.0.1`，不开放 Windows 防火墙端口。
- Tunnel 只匹配 `labexagent.123845.xyz`；其他 hostname 返回 404。
- Access 是第一道门，应用本身的登录、限流、图形验证码和 JWT 仍然生效。
- 不要把 Tunnel credential JSON、`cert.pem` 或 `.env` 上传到仓库或发送给他人。
