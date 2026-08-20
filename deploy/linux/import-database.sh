#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="${ROOT:-/srv/labex-agent/app}"
BACKUP_FILE="${1:-}"
COMPOSE=(docker compose --env-file "$ROOT/.env.production" -f "$ROOT/docker-compose.yml")

if [[ "${CONFIRM_DATABASE_IMPORT:-}" != "yes" ]]; then
  echo "这是生产数据库导入。确认服务器 MySQL 为空且本机备份可用后，用 CONFIRM_DATABASE_IMPORT=yes 再执行。" >&2
  exit 2
fi
if [[ -z "$BACKUP_FILE" || ! -f "$BACKUP_FILE" ]]; then
  echo "请传入存在的 SQL 备份文件路径。" >&2
  exit 2
fi

"${COMPOSE[@]}" exec -T mysql sh -ec \
  'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" -D "$MYSQL_DATABASE"' < "$BACKUP_FILE"

echo "数据库导入完成：$BACKUP_FILE"
