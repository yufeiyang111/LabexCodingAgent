#!/usr/bin/env bash
# LabexAgent Linux 生产基础设施 smoke（T3.4 第一轮）。
# 验证 production profile 全链路：MySQL 容器 + backend JAR + Docker Worker image + 真实命令执行。
# 不依赖任何模型配置（Agent 任务链路见 scripts/acceptance/linux-runtime.sh）。
#
# 注意：使用 18080 端口（WSL2 localhost 转发可能把 Windows 侧 8080 转发进来导致端口冲突）。
#
# 用法：bash deploy/linux/smoke.sh [backend-jar] [worker-image]
set -euo pipefail

BACKEND_JAR="${1:-$(pwd)/backend/target/labex-agent-backend-1.0.0.jar}"
WORKER_IMAGE="${2:-labex-agent-sandbox:opencode-2026-08-14}"
MYSQL_IMAGE="mysql:8.0"
REDIS_IMAGE="redis:7-alpine"
COMPOSE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_DIR="/srv/labex-agent"
WORKSPACES="$BASE_DIR/workspaces"
UPLOADS="$BASE_DIR/uploads"
HOSTNAME_PREFIX="t34-prod"
REPORT="$BASE_DIR/smoke-report.json"
BASE_URL="http://127.0.0.1:18080/api"

require() { command -v "$1" >/dev/null 2>&1 || { echo "缺少依赖: $1"; exit 1; }; }
require docker; require java; require curl; require jq

# 真实 Docker daemon 可用性
docker info >/dev/null 2>&1 || { echo "Docker daemon 不可用"; exit 1; }

echo "==> [1/7] 准备目录与权限（sandbox uid=1001 可写）"
mkdir -p "$WORKSPACES" "$UPLOADS"
chown -R 1001:1001 "$WORKSPACES" "$UPLOADS" 2>/dev/null || chmod -R 777 "$WORKSPACES" "$UPLOADS"
# 兼容本机测试（非 root 用户运行时）
[ "$(id -u)" = "0" ] || chmod -R 777 "$WORKSPACES" "$UPLOADS"

echo "==> [2/7] 启动 MySQL 容器"
docker rm -f "${HOSTNAME_PREFIX}-mysql" >/dev/null 2>&1 || true
docker rm -f "${HOSTNAME_PREFIX}-redis" >/dev/null 2>&1 || true
docker run -d --name "${HOSTNAME_PREFIX}-mysql" \
  -e MYSQL_ROOT_PASSWORD="labex-smoke-root" \
  -e MYSQL_DATABASE="labex_agent" \
  -e MYSQL_USER="labex" \
  -e MYSQL_PASSWORD="labex-smoke" \
  -p 127.0.0.1:13306:3306 \
  "$MYSQL_IMAGE" >/dev/null
for i in $(seq 1 60); do
  docker exec "${HOSTNAME_PREFIX}-mysql" mysqladmin ping -h 127.0.0.1 -u labex -plabex-smoke >/dev/null 2>&1 && break
  [ "$i" = "60" ] && { echo "MySQL 未就绪"; exit 1; }
  sleep 2
done
echo "    MySQL 就绪（容器 $(docker inspect -f '{{.Id}}' "${HOSTNAME_PREFIX}-mysql" | cut -c1-12)）"

docker run -d --name "${HOSTNAME_PREFIX}-redis" -p 127.0.0.1:16379:6379 "$REDIS_IMAGE" >/dev/null
for i in $(seq 1 60); do
  docker exec "${HOSTNAME_PREFIX}-redis" redis-cli ping >/dev/null 2>&1 && break
  [ "$i" = "60" ] && { echo "Redis 未就绪"; exit 1; }
  sleep 2
done
echo "    Redis 就绪（容器 $(docker inspect -f '{{.Id}}' "${HOSTNAME_PREFIX}-redis" | cut -c1-12)）"

echo "==> [3/7] 启动 backend（production profile + Docker Worker）"
export SERVER_PORT="18080"
export SPRING_PROFILES_ACTIVE="production"
export LABEX_AGENT_DB_URL="jdbc:mysql://127.0.0.1:13306/labex_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
export LABEX_AGENT_DB_USERNAME="labex"
export LABEX_AGENT_DB_PASSWORD="labex-smoke"
export LABEX_AGENT_AUTH_REDIS_URL="redis://127.0.0.1:16379"
export LABEX_AGENT_JWT_SECRET="$(head -c 64 /dev/urandom | base64 -w0)"
export LABEX_AGENT_SECRET_STORE_MASTER_KEY="$(head -c 32 /dev/urandom | base64 -w0)"
export LABEX_AGENT_WORKER_DOCKER_IMAGE="$WORKER_IMAGE"
export LABEX_AGENT_CORS_ALLOWED_ORIGINS="https://agent.example.com"
export LABEX_AGENT_WEBSOCKET_ALLOWED_ORIGINS="https://agent.example.com"
export LABEX_AGENT_PROJECT_BASE_PATH="$WORKSPACES"
export LABEX_AGENT_UPLOAD_PATH="$UPLOADS"
export LABEX_AGENT_PERMISSION_PROFILE="opencode"
export LABEX_AGENT_INSTANCE_ID="t34-smoke"
export LABEX_AGENT_EXECUTION_LEASE_DURATION_MS="10000"
export LABEX_AGENT_EXECUTION_HEARTBEAT_INTERVAL_MS="5000"

java -jar "$BACKEND_JAR" >"$BASE_DIR/backend.out.log" 2>&1 &
BACKEND_PID=$!
echo "    backend PID=$BACKEND_PID（日志 $BASE_DIR/backend.out.log）"

for i in $(seq 1 60); do
  curl -s -o /dev/null --max-time 3 "$BASE_URL/auth/login" -X POST -H "Content-Type: application/json" -d '{}' && break
  [ "$i" = "60" ] && { echo "backend 未就绪"; tail -30 "$BASE_DIR/backend.out.log"; kill $BACKEND_PID; exit 1; }
  sleep 2
done
echo "    backend 就绪（PID=$BACKEND_PID）"

echo "==> [4/7] 注册 / 登录 / 创建项目"
REGISTER=$(curl -s -X POST "$BASE_URL/auth/register" \
  -H "Content-Type: application/json" \
  -d '{"username":"smokeuser","password":"smokepass123"}')
TOKEN=$(echo "$REGISTER" | jq -r '.data.token // .data // empty' 2>/dev/null)
if [ -z "$TOKEN" ]; then
  LOGIN=$(curl -s -X POST "$BASE_URL/auth/login" \
    -H "Content-Type: application/json" -d '{"username":"smokeuser","password":"smokepass123"}')
  TOKEN=$(echo "$LOGIN" | jq -r '.data.token // .data // empty' 2>/dev/null)
fi
[ -z "$TOKEN" ] && { echo "注册/登录失败: $REGISTER"; kill $BACKEND_PID; exit 1; }
echo "    token 获取成功（前 12 字符 ${TOKEN:0:12}...）"

PROJECT=$(curl -s -X POST "$BASE_URL/student/projects/empty" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"projectName":"linux-smoke-fixture","template":"blank"}')
PROJECT_ID=$(echo "$PROJECT" | jq -r '.data.projectId // empty' 2>/dev/null)
[ -z "$PROJECT_ID" ] && { echo "创建项目失败: $PROJECT"; kill $BACKEND_PID; exit 1; }
echo "    项目创建成功 projectId=$PROJECT_ID"

echo "==> [5/7] 真实 Docker Worker 命令执行（REST managed terminal）"
# 普通命令 opencode profile 下 ALLOW，直接执行（无需审批）。
TERM_RESULT=$(curl -s -X POST "$BASE_URL/student/projects/$PROJECT_ID/terminal/run" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"command":"printf worker-linux-ok > worker-proof.txt && cat worker-proof.txt","path":".","timeoutSeconds":60}')
echo "$TERM_RESULT" | grep -q "worker-linux-ok" || { echo "Docker worker 执行失败: $TERM_RESULT"; kill $BACKEND_PID; exit 1; }
echo "    Docker worker 真实执行通过（容器内写文件并读回）"
ls "$WORKSPACES"/*/worker-proof.txt >/dev/null 2>&1 || echo "    （提示：文件在项目 workspace 下，宿主可读）"

echo "==> [6/7] 密钥隔离复验 + 镜像工具集"
docker run --rm "$WORKER_IMAGE" env | grep -qiE 'LABEX|SECRET|PASSWORD|API_KEY|TOKEN' \
  && { echo "镜像内发现密钥泄漏"; kill $BACKEND_PID; exit 1; } || echo "    镜像 env 无密钥"
docker run --rm "$WORKER_IMAGE" bash -lc 'which mvn java python3 rg git' >/dev/null 2>&1 \
  && echo "    工具集齐全（mvn/java/python3/rg/git）" || echo "    工具集检查异常"

echo "==> [7/7] 记录证据并清理"
REVISION=$(curl -s -H "Authorization: Bearer $TOKEN" "$BASE_URL/auth/userinfo" | jq -r '.timestamp // ""')
cat >"$REPORT" <<EOF
{
  "backendPid": $BACKEND_PID,
  "backendStart": "$(date -Iseconds)",
  "mysqlContainer": "$(docker inspect -f '{{.Id}}' "${HOSTNAME_PREFIX}-mysql" | cut -c1-12)",
  "redisContainer": "$(docker inspect -f '{{.Id}}' "${HOSTNAME_PREFIX}-redis" | cut -c1-12)",
  "workerImage": "$WORKER_IMAGE",
  "workerImageId": "$(docker image inspect "$WORKER_IMAGE" --format '{{.Id}}' | cut -c8-19)",
  "projectId": $PROJECT_ID,
  "apiTimestamp": "$REVISION",
  "status": "passed"
}
EOF
cat "$REPORT"
kill $BACKEND_PID 2>/dev/null || true
docker rm -f "${HOSTNAME_PREFIX}-mysql" >/dev/null 2>&1 || true
docker rm -f "${HOSTNAME_PREFIX}-redis" >/dev/null 2>&1 || true
echo "==> smoke.sh 完成（exit 0）"
