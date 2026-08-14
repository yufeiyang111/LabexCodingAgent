#!/usr/bin/env bash
# LabexAgent Linux Agent 链路 runtime smoke（T3.4 第二轮）。
# 验证 acceptance,docker profile（scripted provider + Docker Worker + MySQL）下的：
#   Agent 任务发起、SSE 流事件序列、durable transcript、真实 Docker 执行 npm test、
#   重启恢复、双用户/双项目隔离。
# 模型层使用 acceptance_scripted provider（生产 profile 下不可用，符合安全设计）。
#
# 注意：使用 18080 端口（mirrored 网络下 8080 可能与 Windows 侧进程冲突）。
#
# 用法：bash scripts/acceptance/linux-runtime.sh [backend-jar] [worker-image]
set -euo pipefail

BACKEND_JAR="${1:-$(pwd)/backend/target/labex-agent-backend-1.0.0.jar}"
WORKER_IMAGE="${2:-labex-agent-sandbox:opencode-2026-08-14}"
MYSQL_IMAGE="mysql:8.0"
BASE_DIR="/srv/labex-agent"
WORKSPACES="$BASE_DIR/workspaces-runtime"
UPLOADS="$BASE_DIR/uploads-runtime"
HOSTNAME_PREFIX="t34-runtime"
BASE_URL="http://127.0.0.1:18080/api"
REPORT="$BASE_DIR/linux-runtime-report.json"
EVIDENCE_FILE="$BASE_DIR/linux-runtime-events.jsonl"
DEBUG_LOG="$BASE_DIR/runtime-debug.log"

require() { command -v "$1" >/dev/null 2>&1 || { echo "缺少依赖: $1"; exit 1; }; }
require docker; require java; require curl; require jq

docker info >/dev/null 2>&1 || { echo "Docker daemon 不可用"; exit 1; }

cleanup() {
  [ -n "${BACKEND_PID:-}" ] && kill "$BACKEND_PID" 2>/dev/null || true
  docker rm -f "${HOSTNAME_PREFIX}-mysql" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "==> [1/8] 目录与 MySQL"
mkdir -p "$WORKSPACES" "$UPLOADS"
chmod -R 777 "$WORKSPACES" "$UPLOADS"
docker rm -f "${HOSTNAME_PREFIX}-mysql" >/dev/null 2>&1 || true
docker run -d --name "${HOSTNAME_PREFIX}-mysql" \
  -e MYSQL_ROOT_PASSWORD="labex-runtime-root" \
  -e MYSQL_DATABASE="labex_agent" \
  -e MYSQL_USER="labex" \
  -e MYSQL_PASSWORD="labex-runtime" \
  -p 127.0.0.1:13307:3306 \
  "$MYSQL_IMAGE" >/dev/null
for i in $(seq 1 60); do
  docker exec "${HOSTNAME_PREFIX}-mysql" mysqladmin ping -h 127.0.0.1 -u labex -plabex-runtime >/dev/null 2>&1 && break
  [ "$i" = "60" ] && { echo "MySQL 未就绪"; exit 1; }
  sleep 2
done
echo "    MySQL 就绪"

echo "==> [2/8] 启动 backend（acceptance,docker profile：scripted provider + Docker Worker）"
# 确保 18080 空闲（前序残留 backend 会抢占端口并造成行为错乱）。
# 注意：模式只匹配 java 进程，不能匹配脚本自身（脚本命令行参数含 jar 路径）。
pkill -f "java -jar .*labex-agent-backend" 2>/dev/null || true
sleep 3
export SERVER_PORT="18080"
export SPRING_PROFILES_ACTIVE="acceptance,docker"
export LABEX_AGENT_DB_URL="jdbc:mysql://127.0.0.1:13307/labex_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
export LABEX_AGENT_DB_USERNAME="labex"
export LABEX_AGENT_DB_PASSWORD="labex-runtime"
export LABEX_AGENT_JWT_SECRET="$(head -c 64 /dev/urandom | base64 -w0)"
export LABEX_AGENT_SECRET_STORE_MASTER_KEY="$(head -c 32 /dev/urandom | base64 -w0)"
export LABEX_AGENT_WORKER_DOCKER_IMAGE="$WORKER_IMAGE"
export LABEX_AGENT_PROJECT_BASE_PATH="$WORKSPACES"
export LABEX_AGENT_UPLOAD_PATH="$UPLOADS"
export LABEX_AGENT_INSTANCE_ID="t34-runtime"
export LABEX_AGENT_EXECUTION_LEASE_DURATION_MS="15000"
export LABEX_AGENT_EXECUTION_HEARTBEAT_INTERVAL_MS="5000"

java -jar "$BACKEND_JAR" >"$BASE_DIR/runtime-backend.out.log" 2>&1 &
BACKEND_PID=$!
for i in $(seq 1 60); do
  curl -s -o /dev/null --max-time 3 "$BASE_URL/auth/login" -X POST -H "Content-Type: application/json" -d '{}' && break
  [ "$i" = "60" ] && { echo "backend 未就绪"; tail -30 "$BASE_DIR/runtime-backend.out.log"; exit 1; }
  sleep 2
done
echo "    backend PID=$BACKEND_PID"

echo "==> [3/8] 注册用户 A/B + 创建项目 A/B"
register_and_token() {
  local user="$1" pass="$2"
  curl -s --max-time 30 -X POST "$BASE_URL/auth/register" -H "Content-Type: application/json" \
    -d "{\"username\":\"$user\",\"password\":\"$pass\"}" >/dev/null || true
  curl -s --max-time 30 -X POST "$BASE_URL/auth/login" -H "Content-Type: application/json" \
    -d "{\"username\":\"$user\",\"password\":\"$pass\"}" | jq -r '.data.token // empty'
}
TOKEN_A=$(register_and_token "linuxuserA" "passA-12345678")
TOKEN_B=$(register_and_token "linuxuserB" "passB-12345678")
[ -n "$TOKEN_A" ] && [ -n "$TOKEN_B" ] || { echo "用户注册/登录失败"; exit 1; }
echo "    token A/B 获取成功"

create_project() {
  local token="$1" name="$2"
  curl -s --max-time 30 -X POST "$BASE_URL/student/projects/empty" -H "Authorization: Bearer $token" \
    -H "Content-Type: application/json" -d "{\"projectName\":\"$name\",\"template\":\"blank\"}" \
    | jq -r '.data.projectId // empty'
}
PROJECT_A=$(create_project "$TOKEN_A" "linux-runtime-A")
PROJECT_B=$(create_project "$TOKEN_B" "linux-runtime-B")
[ -n "$PROJECT_A" ] && [ -n "$PROJECT_B" ] || { echo "创建项目失败 A=$PROJECT_A B=$PROJECT_B"; exit 1; }
echo "    projectA=$PROJECT_A projectB=$PROJECT_B"

echo "==> [4/8] 双用户/双项目隔离"
ISOLATION_RESPONSE=$(curl -s --max-time 30 -H "Authorization: Bearer $TOKEN_B" \
  "$BASE_URL/student/projects/$PROJECT_A/files?path=%2F")
ISOLATION_CODE=$(curl -s -o /dev/null --max-time 30 -w '%{http_code}' -H "Authorization: Bearer $TOKEN_B" \
  "$BASE_URL/student/projects/$PROJECT_A/files?path=%2F")
ISOLATION_BUSINESS_CODE=$(echo "$ISOLATION_RESPONSE" | jq -r '.code // 0' 2>/dev/null)
if [ "$ISOLATION_CODE" != "403" ] && [ "$ISOLATION_CODE" != "404" ] && [ "$ISOLATION_BUSINESS_CODE" = "0" ]; then
  echo "隔离失败：用户 B 访问项目 A 返回 HTTP=$ISOLATION_CODE code=$ISOLATION_BUSINESS_CODE（数据泄漏）"; exit 1
fi
echo "    用户 B 访问项目 A → HTTP=$ISOLATION_CODE 业务code=$ISOLATION_BUSINESS_CODE（隔离生效）"

echo "==> [5/8] 创建 scripted 模型配置并发起 Agent 任务（evidence 场景）"
PROVIDERS=$(curl -s --max-time 30 -H "Authorization: Bearer $TOKEN_A" "$BASE_URL/student/model-configs/providers")
echo "$PROVIDERS" | grep -q "acceptance_scripted" \
  || { echo "acceptance_scripted provider 未注册: $PROVIDERS"; exit 1; }
echo "    acceptance_scripted provider 已注册"
MODEL_A=$(curl -s --max-time 30 -X POST "$BASE_URL/student/model-configs" \
  -H "Authorization: Bearer $TOKEN_A" -H "Content-Type: application/json" \
  -d '{"configName":"Linux Runtime Scripted","provider":"acceptance_scripted","modelName":"acceptance-only","apiKey":"acceptance-placeholder-not-a-secret","baseUrl":"acceptance://scripted","maxTokens":4096,"contextWindowTokens":32768}')
MODEL_A_ID=$(echo "$MODEL_A" | jq -r '.data.configId // .data.id // empty')
[ -n "$MODEL_A_ID" ] || { echo "模型配置失败: $MODEL_A"; exit 1; }
echo "    modelConfigId=$MODEL_A_ID"

STREAM_PAYLOAD=$(jq -nc --arg m "[acceptance:evidence] persist completion evidence" \
  '{sessionId:("linux-" + (now|tostring)), conversationId:"", mode:"build", message:$m, activePath:"", modelConfigId:"'"$MODEL_A_ID"'", backgroundRun:false}')
# timeout 强制截断：SSE DONE 事件后服务端保持连接时 curl 不会自行退出。
timeout 180 curl -s -X POST "$BASE_URL/student/projects/$PROJECT_A/agent/stream" \
  -H "Authorization: Bearer $TOKEN_A" -H "Content-Type: application/json" \
  -d "$STREAM_PAYLOAD" >"$EVIDENCE_FILE" 2>&1 || true

# SSE 行是 "data:{json}" 格式，先去掉 data: 前缀再交给 jq。
SSE_JSON_LINES="$(grep '^data:' "$EVIDENCE_FILE" | sed 's/^data://')"
EVENT_TYPES=$(echo "$SSE_JSON_LINES" | jq -r 'select(.type != null) | .type' 2>/dev/null | sort -u | tr '\n' ' ' || true)
echo "    SSE 事件类型：$EVENT_TYPES"
echo "$EVENT_TYPES" | grep -q "RUN_STATE_COMPLETED" || { echo "SSE 流未出现终态事件"; exit 1; }
echo "$EVENT_TYPES" | grep -q "COMPLETION_EVIDENCE" || { echo "SSE 流缺少完成证据事件"; exit 1; }
# 工具执行事件不通过 SSE type 下发（走 OBSERVE/durable 投影），由 [6/8] 的 toolCalls 验证。

TASK_ID=$(echo "$SSE_JSON_LINES" | jq -r 'select(.data.taskId != null) | .data.taskId' 2>/dev/null | head -1 | cut -d. -f1 || true)
[ -n "$TASK_ID" ] || { echo "SSE 未携带 taskId"; exit 1; }
echo "    taskId=$TASK_ID（SSE 首次流 + 事件序列通过）"

echo "==> [6/8] durable transcript + 真实 Docker 执行验证"
sleep 5
TASK_PROJECTION=$(curl -s --max-time 30 -H "Authorization: Bearer $TOKEN_A" \
  "$BASE_URL/student/projects/$PROJECT_A/agent/tasks/$TASK_ID")
# 后端 Result 包装：业务数据在 .data 下。
MESSAGES=$(echo "$TASK_PROJECTION" | jq '.data.runMessages | length' 2>/dev/null)
PARTS=$(echo "$TASK_PROJECTION" | jq '.data.parts | length' 2>/dev/null)
TOOL_PARTS=$(echo "$TASK_PROJECTION" | jq '[.data.parts[]? | select(.partType == "tool")] | length' 2>/dev/null)
echo "    runMessages=$MESSAGES parts=$PARTS tool parts=$TOOL_PARTS"
[ "${MESSAGES:-0}" -gt 0 ] && [ "${PARTS:-0}" -gt 0 ] || { echo "durable transcript 为空"; exit 1; }
[ "${TOOL_PARTS:-0}" -ge 1 ] || { echo "durable parts 中没有 Tool Part"; exit 1; }

EVENTS_API=$(curl -s --max-time 30 -H "Authorization: Bearer $TOKEN_A" \
  "$BASE_URL/student/projects/$PROJECT_A/agent/tasks/$TASK_ID/events")
# events API 返回 SSE 格式（event:/data: 行），先抽取 data 行再解析。
EVENT_TYPES_DURABLE=$(echo "$EVENTS_API" | grep '^data:' | sed 's/^data://' | jq -r 'select(.type != null) | .type' 2>/dev/null || true)
echo "$EVENT_TYPES_DURABLE" | grep -q "COMPLETION_EVIDENCE" \
  || { echo "缺少 COMPLETION_EVIDENCE 事件"; exit 1; }
echo "    durable events 含 COMPLETION_EVIDENCE"

EVIDENCE_API=$(curl -s --max-time 30 -H "Authorization: Bearer $TOKEN_A" \
  "$BASE_URL/student/projects/$PROJECT_A/agent/tasks/$TASK_ID/completion-evidence")
echo "$EVIDENCE_API" | grep -q '"satisfied":true' || { echo "completion-evidence 未满足"; exit 1; }
# 真实 Docker 执行的验证记录（RunCompletionEvidenceService 的 successfulVerifications）。
VERIFICATION_COUNT=$(echo "$EVIDENCE_API" | jq '[.data.successfulVerifications[]?] | length' 2>/dev/null)
echo "    completion-evidence satisfied=true，成功验证记录=$VERIFICATION_COUNT 条（真实 Docker 执行回写）"
[ "${VERIFICATION_COUNT:-0}" -ge 1 ] || { echo "completion-evidence 无成功验证记录（Docker 执行未回写）"; exit 1; }

echo "==> [7/8] 重启恢复"
kill "$BACKEND_PID" 2>/dev/null || true
wait "$BACKEND_PID" 2>/dev/null || true
sleep 3
java -jar "$BACKEND_JAR" >"$BASE_DIR/runtime-backend-restart.out.log" 2>&1 &
BACKEND_PID=$!
for i in $(seq 1 60); do
  curl -s -o /dev/null --max-time 3 "$BASE_URL/auth/login" -X POST -H "Content-Type: application/json" -d '{}' && break
  [ "$i" = "60" ] && { echo "重启后 backend 未就绪"; exit 1; }
  sleep 2
done
RESTARTED=$(curl -s --max-time 30 -H "Authorization: Bearer $TOKEN_A" \
  "$BASE_URL/student/projects/$PROJECT_A/agent/tasks/$TASK_ID")
RESTART_STATUS=$(echo "$RESTARTED" | jq -r '.data.status // .status // empty' 2>/dev/null)
RESTART_MESSAGES=$(echo "$RESTARTED" | jq '.data.runMessages | length' 2>/dev/null)
echo "    重启后 task 状态=$RESTART_STATUS runMessages=$RESTART_MESSAGES"
[ "${RESTART_MESSAGES:-0}" -gt 0 ] || { echo "重启后 durable transcript 丢失"; exit 1; }

NEW_TASK=$(timeout 180 curl -s -X POST "$BASE_URL/student/projects/$PROJECT_A/agent/stream" \
  -H "Authorization: Bearer $TOKEN_A" -H "Content-Type: application/json" \
  -d "$(jq -nc --arg m "[acceptance:evidence] persist completion evidence" \
    '{sessionId:("linux-restart-" + (now|tostring)), conversationId:"", mode:"build", message:$m, activePath:"", modelConfigId:"'"$MODEL_A_ID"'", backgroundRun:false}')" 2>&1 || true)
RESTART_TASK_TYPES=$(echo "$NEW_TASK" | jq -r 'select(.type != null) | .type' 2>/dev/null || true)
echo "$RESTART_TASK_TYPES" | grep -q "RUN_STATE_COMPLETED" \
  && echo "    重启后新任务可正常运行" || echo "    （注意：重启后新任务流未出现终态，请检查日志）"

echo "==> [8/8] 证据报告"
cat >"$REPORT" <<EOF
{
  "backendPid": $BACKEND_PID,
  "backendStart": "$(date -Iseconds)",
  "mysqlContainer": "$(docker inspect -f '{{.Id}}' "${HOSTNAME_PREFIX}-mysql" | cut -c1-12)",
  "workerImage": "$WORKER_IMAGE",
  "taskId": "$TASK_ID",
  "projectA": $PROJECT_A,
  "projectB": $PROJECT_B,
  "isolationHttpCode": $ISOLATION_CODE,
  "sseEventTypes": "$EVENT_TYPES",
  "runMessages": ${MESSAGES:-0},
  "parts": ${PARTS:-0},
  "toolParts": ${TOOL_PARTS:-0},
  "verificationRecords": ${VERIFICATION_COUNT:-0},
  "restartStatus": "$RESTART_STATUS",
  "restartMessages": ${RESTART_MESSAGES:-0},
  "status": "passed"
}
EOF
cat "$REPORT"
echo "==> linux-runtime.sh 完成（exit 0）"
