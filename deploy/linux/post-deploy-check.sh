#!/usr/bin/env bash
#
# LabexAgent 部署后应用级健康检查。
#
# 设计目标（为什么需要它）：
#   旧流程在 docker compose up 之后只看 docker compose ps —— 容器 Up 不代表
#   Spring Boot 完整启动、不代表数据库连接正常、不代表 API 可用。本脚本把
#   “发布完成”从“容器没崩”升级为“应用真的可用”，失败立即阻断，不再打印发布完成。
#
# 检查项（全部通过才算成功）：
#   1. 后端容器进程存在；
#   2. 后端日志出现 Spring 启动完成标志 Started LabexAgentApplication；
#   3. 匿名接口可访问：GET /api/auth/config 返回统一结果结构（含 code 字段）；
#   4. 鉴权保护生效：GET /api/student/projects 未携带 token 返回 401 或 403；
#   5. 静态资源可访问：GET https://<PUBLIC_HOST>/ 返回 200。
#
# 判定依据的选择：
#   - 后端镜像为 eclipse-temurin:17-jre，不含 curl，因此 API 探针不能进入容器执行；
#     统一走 Caddy 公网入口（LABEX_AGENT_PUBLIC_HOST），同时验证 Caddy 到后端链路。
#   - /api/auth/config 是 SecurityConfig 明确放行（/auth/**）的真实业务匿名端点，
#     不引入 actuator 依赖。
#   - /api/student/projects 是任意 authenticated() 端点，验证鉴权过滤器链确实生效。
#   - /ops/health 虽在 SecurityConfig 放行，但被 MonitorAuthInterceptor 拦截，
#     不能当匿名端点用，故不纳入。
#
# 用法（在服务器部署目录执行）：
#   ./post-deploy-check.sh [compose文件]
#   例：./post-deploy-check.sh docker-compose.yml
#
# 环境变量（均有安全默认值，不泄露任何密钥）：
#   HEALTH_TIMEOUT_SECONDS   启动等待总时长（默认 120）
#   HEALTH_INTERVAL_SECONDS  轮询间隔（默认 3）
#   PUBLIC_BASE_URL          覆盖公网入口（默认 https://<LABEX_AGENT_PUBLIC_HOST>）
#
set -euo pipefail

COMPOSE_FILE="${1:-docker-compose.yml}"
ENV_FILE="${ENV_FILE:-.env.production}"
TIMEOUT="${HEALTH_TIMEOUT_SECONDS:-120}"
INTERVAL="${HEALTH_INTERVAL_SECONDS:-3}"
STARTED_MARKER="Started LabexAgentApplication"
PUBLIC_HOST=""
PUBLIC_URL=""

compose() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

# 从 .env.production 读取公网域名；不打印除域名外的任何内容。
load_public_host() {
  if [ -f "$ENV_FILE" ]; then
    PUBLIC_HOST="$(grep -E "^LABEX_AGENT_PUBLIC_HOST=" "$ENV_FILE" | head -n1 | cut -d= -f2- | tr -d "\r" | xargs || true)"
  fi
  if [ -z "$PUBLIC_HOST" ]; then
    echo "::error::未在 $ENV_FILE 找到 LABEX_AGENT_PUBLIC_HOST，无法构造公网探针地址。" >&2
    exit 1
  fi
}

resolve_public_url() {
  PUBLIC_URL="${PUBLIC_BASE_URL:-https://$PUBLIC_HOST}"
}

fail_with_context() {
  local reason="$1"
  echo ""
  echo "::error::健康检查失败：$reason"
  echo "==> backend 容器状态："
  compose ps backend 2>/dev/null || echo "    （backend 服务不可用）"
  echo "==> backend 最近 200 行日志："
  compose logs --tail=200 backend 2>/dev/null || echo "    （无日志）"
  echo "==> caddy 最近 100 行日志："
  compose logs --tail=100 caddy 2>/dev/null || echo "    （无日志）"
  echo "==> 迁移台账快照："
  compose exec -T mysql sh -ec \
    "MYSQL_PWD=\"$MYSQL_PASSWORD\" mysql -u\"$MYSQL_USER\" \"$MYSQL_DATABASE\" -N -B -e \"SELECT migration_name, applied_at FROM t_schema_migration ORDER BY migration_name\"" \
    2>/dev/null || echo "    （台账不可用）"
  exit 1
}

check_container_running() {
  local status
  status="$(compose ps --format "{{.State}}" backend 2>/dev/null | head -n1 || true)"
  if [ "$status" != "running" ]; then
    fail_with_context "backend 容器未处于 running（当前=${status:-不存在}）"
  fi
  echo "    [1/5] backend 容器运行中"
}

check_startup_marker() {
  local waited=0
  while [ "$waited" -lt "$TIMEOUT" ]; do
    if compose logs backend 2>/dev/null | grep -q "$STARTED_MARKER"; then
      echo "    [2/5] 启动完成标志已出现（${waited}s）"
      return 0
    fi
    local status
    status="$(compose ps --format "{{.State}}" backend 2>/dev/null | head -n1 || true)"
    if [ "$status" = "exited" ] || [ "$status" = "dead" ]; then
      fail_with_context "backend 容器在出现启动标志前已退出（$status）"
    fi
    sleep "$INTERVAL"
    waited=$((waited + INTERVAL))
  done
  fail_with_context "${TIMEOUT}s 内未出现启动完成标志：$STARTED_MARKER"
}

# 匿名端点：走 Caddy 公网入口（后端镜像无 curl，不能进容器探测）。
check_anonymous_endpoint() {
  local body
  body="$(curl -fsS --max-time 10 "$PUBLIC_URL/api/auth/config" 2>/dev/null)" \
    || fail_with_context "匿名接口 /api/auth/config 不可访问（$PUBLIC_URL）"
  if ! echo "$body" | grep -q "\"code\""; then
    fail_with_context "匿名接口 /api/auth/config 响应缺少 code 字段：${body:0:200}"
  fi
  echo "    [3/5] 匿名接口 /api/auth/config 可用"
}

check_protected_endpoint() {
  local code
  code="$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 "$PUBLIC_URL/api/student/projects" 2>/dev/null | tr -d "[:space:]")" \
    || fail_with_context "受保护接口探针执行失败"
  if [ "$code" != "401" ] && [ "$code" != "403" ]; then
    fail_with_context "受保护接口 /api/student/projects 未携带 token 应返回 401/403，实际 ${code:-连接失败}（鉴权可能失效）"
  fi
  echo "    [4/5] 受保护接口鉴权生效（未认证返回 $code）"
}

check_static_resource() {
  local code
  code="$(curl -s -o /dev/null -w "%{http_code}" --max-time 15 "$PUBLIC_URL/" 2>/dev/null || true)"
  if [ "$code" != "200" ]; then
    fail_with_context "静态资源 $PUBLIC_URL/ 应返回 200，实际 ${code:-连接失败}"
  fi
  echo "    [5/5] 静态资源 $PUBLIC_URL/ 返回 200"
}

main() {
  load_public_host
  resolve_public_url
  echo "==> 部署后健康检查开始（公网入口 $PUBLIC_URL，超时 ${TIMEOUT}s，间隔 ${INTERVAL}s）"
  check_container_running
  check_startup_marker
  check_anonymous_endpoint
  check_protected_endpoint
  check_static_resource
  echo "==> 健康检查全部通过。"
}

main "$@"
