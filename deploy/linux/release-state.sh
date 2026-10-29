#!/usr/bin/env bash
#
# LabexAgent 发布版本状态管理：后端镜像切换、前端版本目录切换、回滚与状态查询。
#
# 为什么需要它：
#   1. 历史缺陷：流水线构建的镜像 tag 是 github.ref_name，但 docker-compose 读取
#      .env.production 的 LABEX_AGENT_BACKEND_IMAGE，流水线从不更新该变量 —— 新镜像
#      从未真正被部署。本脚本是唯一写入该变量的入口。
#   2. 前端原先是直接覆盖 frontend-dist/，失败后没有上一版可回退。改为版本目录 +
#      原子软链切换，保留上一版。
#   3. 回滚后必须复用 post-deploy-check.sh 做同一套健康判定，不能"切完就算成功"。
#
# 用法（在 /srv/labex-agent/app 下执行）：
#   ./release-state.sh set-backend-image <image-ref>
#   ./release-state.sh activate-frontend <tarball> <release-id>
#   ./release-state.sh rollback-backend
#   ./release-state.sh rollback-frontend
#   ./release-state.sh status
#
# 约定：
#   - .env.production 含真实密钥，本脚本只替换 LABEX_AGENT_BACKEND_IMAGE 一行，
#     不打印文件内容；临时文件权限 600。
#   - 数据库迁移不可自动反向。回滚只切代码/静态资源，脚本会显式提示。
#
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${ENV_FILE:-$APP_DIR/.env.production}"
COMPOSE_FILE="${COMPOSE_FILE:-$APP_DIR/docker-compose.yml}"
STATE_DIR="$APP_DIR/.release-state"
FRONTEND_ROOT="$APP_DIR/frontend-releases"
FRONTEND_LINK="$APP_DIR/frontend-dist"

compose() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

ensure_state_dir() {
  mkdir -p "$STATE_DIR"
  chmod 700 "$STATE_DIR"
}

current_backend_image() {
  grep -E '^LABEX_AGENT_BACKEND_IMAGE=' "$ENV_FILE" | head -n1 | cut -d= -f2- | tr -d '\r'
}

write_backend_image() {
  local image="$1"
  local tmp
  tmp="$(mktemp "$ENV_FILE.XXXXXX")"
  chmod 600 "$tmp"
  awk -v img="$image" '
    BEGIN { replaced = 0 }
    /^LABEX_AGENT_BACKEND_IMAGE=/ { print "LABEX_AGENT_BACKEND_IMAGE=" img; replaced = 1; next }
    { print }
    END { if (!replaced) print "LABEX_AGENT_BACKEND_IMAGE=" img }
  ' "$ENV_FILE" > "$tmp"
  mv "$tmp" "$ENV_FILE"
}

set_backend_image() {
  local new_image="$1"
  [ -n "$new_image" ] || { echo "用法: $0 set-backend-image <image-ref>" >&2; exit 2; }
  ensure_state_dir
  local old_image
  old_image="$(current_backend_image || true)"
  if [ -n "$old_image" ]; then
    printf '%s\n' "$old_image" > "$STATE_DIR/previous-backend-image"
    chmod 600 "$STATE_DIR/previous-backend-image"
    echo "==> 已记录上一版后端镜像：$old_image"
  fi
  write_backend_image "$new_image"
  echo "==> 后端镜像已切换：$new_image"
}

activate_frontend() {
  local tarball="$1"
  local release_id="$2"
  # 相对路径统一按 APP_DIR 解析，避免从其他目录调用时找不到包
  case "$tarball" in
    /*) ;;
    *) [ -f "$tarball" ] || tarball="$APP_DIR/$tarball" ;;
  esac
  [ -f "$tarball" ] || { echo "缺少前端包：$tarball" >&2; exit 2; }
  [ -n "$release_id" ] || { echo "缺少 release-id" >&2; exit 2; }
  case "$release_id" in
    *[!A-Za-z0-9._-]*) echo "非法 release-id：$release_id" >&2; exit 2 ;;
  esac

  ensure_state_dir
  mkdir -p "$FRONTEND_ROOT"
  local target="$FRONTEND_ROOT/$release_id"
  rm -rf "$target"
  mkdir -p "$target"
  tar -xzf "$tarball" -C "$target"
  [ -f "$target/index.html" ] || { echo "前端包缺少 index.html，拒绝切换" >&2; exit 1; }

  local previous=""
  if [ -L "$FRONTEND_LINK" ]; then
    previous="$(basename "$(readlink "$FRONTEND_LINK")")"
  elif [ -d "$FRONTEND_LINK" ]; then
    previous="legacy-$(date +%Y%m%d%H%M%S)"
    mv "$FRONTEND_LINK" "$FRONTEND_ROOT/$previous"
    echo "==> 已将遗留 frontend-dist 归档为 $previous"
  fi
  if [ -n "$previous" ]; then
    printf '%s\n' "$previous" > "$STATE_DIR/previous-frontend-release"
  fi

  # 先移除旧入口再建软链，避免 ln 在目标为目录时把链接建到目录内部
  if [ -e "$FRONTEND_LINK" ] || [ -L "$FRONTEND_LINK" ]; then
    rm -rf "$FRONTEND_LINK"
  fi
  ln -s "$target" "$FRONTEND_LINK"
  echo "==> 前端版本已切换：$release_id"
  # 软链已切换成功；caddy 重载失败要明确区分，避免误以为切换失败
  if [ "${RELEASE_STATE_SKIP_COMPOSE:-0}" = "1" ]; then
    echo "    （RELEASE_STATE_SKIP_COMPOSE=1，跳过 caddy 重载）"
  else
    compose up -d --no-deps --force-recreate caddy || {
      echo "::error::前端软链已切换到 $release_id，但 caddy 重载失败；请手动执行 docker compose up -d --no-deps caddy" >&2
      exit 1
    }
  fi
}

# 回滚前校验目标镜像确实可拉取。
# 为什么需要它：上一版记录可能是早期短名（如 labex-agent-backend:2026-08-19），
# Docker 会去 Docker Hub 找而失败；必须在写状态之前就明确失败，避免把
# previous-backend-image 覆盖掉、让本来还能人工救火的记录也丢了。
verify_backend_image_pullable() {
  local image="$1"
  if docker manifest inspect "$image" >/dev/null 2>&1; then
    return 0
  fi
  # manifest inspect 需要仓库已登录；失败时再用 pull 做一次真实校验
  if compose pull backend >/dev/null 2>&1; then
    return 0
  fi
  echo "::error::目标镜像不可拉取：$image" >&2
  echo "    可能原因：" >&2
  echo "    1) 该镜像是早期短名（未带 TCR 仓库地址），Docker 会去 Docker Hub 查找而失败；" >&2
  echo "    2) TCR 未登录或凭据过期；" >&2
  echo "    3) 该 tag 已被清理。" >&2
  echo "    当前 .env.production 未被修改，可继续用当前版本运行。" >&2
  return 1
}

rollback_backend() {
  ensure_state_dir
  local prev_file="$STATE_DIR/previous-backend-image"
  [ -f "$prev_file" ] || { echo "没有可回滚的上一版后端镜像记录" >&2; exit 1; }
  local prev current
  prev="$(tr -d '\r\n' < "$prev_file")"
  current="$(current_backend_image || true)"
  [ -n "$prev" ] || { echo "上一版镜像记录为空" >&2; exit 1; }
  if [ "$prev" = "$current" ]; then
    echo "::error::上一版记录与当前镜像相同（$prev），无法回滚。" >&2
    exit 1
  fi

  echo "==> 校验目标镜像可拉取：$prev"
  # 临时把目标镜像写入 env 供 compose pull 使用；失败则按原权限恢复，不污染状态
  local env_backup env_mode
  env_backup="$(mktemp)"
  env_mode="$(stat -c '%a' "$ENV_FILE" 2>/dev/null || echo 600)"
  cp -p "$ENV_FILE" "$env_backup"
  write_backend_image "$prev"
  if ! verify_backend_image_pullable "$prev"; then
    cp -p "$env_backup" "$ENV_FILE"
    chmod "$env_mode" "$ENV_FILE" 2>/dev/null || true
    rm -f "$env_backup"
    exit 1
  fi
  rm -f "$env_backup"

  echo "==> 后端回滚：$current -> $prev"
  # 校验通过后才覆盖 previous 记录，保证失败时旧记录仍在
  printf '%s\n' "$current" > "$STATE_DIR/previous-backend-image"
  compose up -d --no-deps backend
  "$APP_DIR/post-deploy-check.sh" "$COMPOSE_FILE"
  echo "==> 后端回滚完成且健康检查通过。"
  echo "!! 数据库迁移不会自动反向；如新迁移不向后兼容，请补充迁移。"
}

rollback_frontend() {
  ensure_state_dir
  local prev_file="$STATE_DIR/previous-frontend-release"
  [ -f "$prev_file" ] || { echo "没有可回滚的上一版前端记录" >&2; exit 1; }
  local prev current
  prev="$(tr -d '\r\n' < "$prev_file")"
  [ -d "$FRONTEND_ROOT/$prev" ] || { echo "上一版前端目录不存在：$FRONTEND_ROOT/$prev" >&2; exit 1; }
  if [ -L "$FRONTEND_LINK" ]; then
    current="$(basename "$(readlink "$FRONTEND_LINK")")"
    printf '%s\n' "$current" > "$STATE_DIR/previous-frontend-release"
  fi
  if [ -e "$FRONTEND_LINK" ] || [ -L "$FRONTEND_LINK" ]; then
    rm -rf "$FRONTEND_LINK"
  fi
  ln -s "$FRONTEND_ROOT/$prev" "$FRONTEND_LINK"
  if [ "${RELEASE_STATE_SKIP_COMPOSE:-0}" = "1" ]; then
    echo "    （RELEASE_STATE_SKIP_COMPOSE=1，跳过 caddy 重载与健康检查）"
  else
    compose up -d --no-deps --force-recreate caddy
    "$APP_DIR/post-deploy-check.sh" "$COMPOSE_FILE"
  fi
  if [ "${RELEASE_STATE_SKIP_COMPOSE:-0}" = "1" ]; then
    echo "==> 前端回滚完成（已跳过 compose 与健康检查）。"
  else
    echo "==> 前端回滚完成且健康检查通过。"
  fi
}

show_status() {
  echo "== 发布状态 =="
  echo "当前后端镜像: $(current_backend_image || echo '(未配置)')"
  if [ -f "$STATE_DIR/previous-backend-image" ]; then
    echo "上一版后端镜像: $(cat "$STATE_DIR/previous-backend-image")"
  else
    echo "上一版后端镜像: (无记录)"
  fi
  if [ -L "$FRONTEND_LINK" ]; then
    echo "当前前端版本: $(basename "$(readlink "$FRONTEND_LINK")")"
  elif [ -d "$FRONTEND_LINK" ]; then
    echo "当前前端版本: (遗留目录，未版本化)"
  else
    echo "当前前端版本: (缺失)"
  fi
  if [ -f "$STATE_DIR/previous-frontend-release" ]; then
    echo "上一版前端版本: $(cat "$STATE_DIR/previous-frontend-release")"
  else
    echo "上一版前端版本: (无记录)"
  fi
  echo "== 迁移台账 =="
  compose exec -T mysql sh -ec \
    'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" -N -B -e "SELECT migration_name, applied_at FROM t_schema_migration ORDER BY migration_name"' \
    2>/dev/null || echo '(台账不可用)'
}

case "${1:-}" in
  set-backend-image) shift; set_backend_image "${1:-}" ;;
  activate-frontend) shift; activate_frontend "${1:-}" "${2:-}" ;;
  rollback-backend) rollback_backend ;;
  rollback-frontend) rollback_frontend ;;
  status) show_status ;;
  *) echo "用法: $0 {set-backend-image <ref>|activate-frontend <tar> <id>|rollback-backend|rollback-frontend|status}" >&2; exit 2 ;;
esac