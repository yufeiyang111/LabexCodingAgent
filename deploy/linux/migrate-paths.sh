#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="${ROOT:-/srv/labex-agent/app}"
COMPOSE=(docker compose --env-file "$ROOT/.env.production" -f "$ROOT/docker-compose.yml")

if [[ "${CONFIRM_PATH_MIGRATION:-}" != "yes" ]]; then
  echo "这是一次数据库路径迁移。确认备份已完成后，用 CONFIRM_PATH_MIGRATION=yes 再执行。" >&2
  exit 2
fi
# D:\LabexAgent\workspaces 的 UTF-8 十六进制表示，避免 SQL 反斜杠转义歧义。
OLD_PREFIX="CONVERT(0x443A5C4C616265784167656E745C776F726B737061636573 USING utf8mb4)"
NEW_PREFIX="/srv/labex-agent/workspaces"

"${COMPOSE[@]}" exec -T mysql sh -ec \
  'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" -D "$MYSQL_DATABASE" --batch --skip-column-names -e "$1"' -- \
  "SELECT COUNT(*) FROM t_student_project WHERE workspace_path LIKE CONCAT($OLD_PREFIX, '%') OR archive_path LIKE CONCAT($OLD_PREFIX, '%');"

"${COMPOSE[@]}" exec -T mysql sh -ec \
  'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" -D "$MYSQL_DATABASE" --batch --skip-column-names' <<SQL
START TRANSACTION;
UPDATE t_student_project
SET workspace_path = REPLACE(workspace_path, $OLD_PREFIX, '$NEW_PREFIX'),
    archive_path = REPLACE(archive_path, $OLD_PREFIX, '$NEW_PREFIX')
WHERE workspace_path LIKE CONCAT($OLD_PREFIX, '%')
   OR archive_path LIKE CONCAT($OLD_PREFIX, '%');
SELECT ROW_COUNT();
COMMIT;
SQL

echo "路径迁移完成。请随后检查项目列表和任意一个项目文件。"
