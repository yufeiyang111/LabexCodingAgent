#!/usr/bin/env bash
#
# LabexAgent 生产数据库增量迁移执行器
#
# 设计目标（为什么需要它）：
#   旧流程在 CI 里写死复制 `upgrade-20260908.sql` 一个文件 —— 之后新增的任何迁移都不会被部署，
#   且没有台账，无法回答「这次发布到底跑了哪些变更」。本脚本改为：自动发现全部迁移 →
#   按文件名顺序逐个执行 → 每个记录到 `t_schema_migration` 台账 → 已记录的直接跳过。
#
# 语义保证：
#   1. 幂等：已记录的迁移不再执行；即使重复跑整条流水线也不会重复应用。
#   2. 可审计：每次发布会打印「应用了哪些 / 跳过了哪些」，台账可随时查。
#   3. 快速失败：任一迁移失败立即退出，不继续后续迁移，也不让发布流程悄悄成功。
#   4. 单次入账：迁移本体与台账写入在同一个 MySQL 会话内完成，避免「执行了但没记账」。
#
# 用法（在服务器 /srv/labex-agent/app 下执行）：
#   ./apply-migrations.sh <迁移目录> [compose文件]
#   例：./apply-migrations.sh migrations docker-compose.yml
#
set -euo pipefail

MIGRATION_DIR="${1:-migrations}"
COMPOSE_FILE="${2:-docker-compose.yml}"
ENV_FILE="${ENV_FILE:-.env.production}"
LEDGER_TABLE="t_schema_migration"

if [ ! -d "$MIGRATION_DIR" ]; then
  echo "==> 未找到迁移目录 $MIGRATION_DIR，跳过数据库迁移。"
  exit 0
fi

# 收集迁移文件并按文件名（含日期）升序执行，保证应用顺序与提交顺序一致
mapfile -t MIGRATIONS < <(find "$MIGRATION_DIR" -maxdepth 1 -type f -name 'upgrade-*.sql' | sort)
if [ "${#MIGRATIONS[@]}" -eq 0 ]; then
  echo "==> 迁移目录内没有 upgrade-*.sql，跳过数据库迁移。"
  exit 0
fi

echo "==> 发现 ${#MIGRATIONS[@]} 个迁移文件，开始按顺序处理。"

# 复用 compose 里的 mysql 服务；-T 关闭 TTY 供 CI 管道使用
mysql_exec() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T mysql \
    sh -c 'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -N -B'
}

# 1. 建立台账表（幂等）。记录文件名、校验和、执行时间，便于事后核对内容是否被篡改。
mysql_exec <<SQL
CREATE TABLE IF NOT EXISTS ${LEDGER_TABLE} (
  migration_name VARCHAR(191) NOT NULL COMMENT '迁移文件名，如 upgrade-20260918.sql',
  checksum CHAR(64) NOT NULL COMMENT '文件内容的 SHA-256，用于发现已执行文件被改动',
  applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次成功执行时间',
  PRIMARY KEY (migration_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='数据库增量迁移台账：每个迁移只执行一次'
SQL
echo "==> 台账表 ${LEDGER_TABLE} 已就绪。"

applied=0
skipped=0

for migration in "${MIGRATIONS[@]}"; do
  name="$(basename "$migration")"

  # sha256sum 与 shasum 在不同发行版上二选一
  if command -v sha256sum >/dev/null 2>&1; then
    checksum="$(sha256sum "$migration" | awk '{print $1}')"
  else
    checksum="$(shasum -a 256 "$migration" | awk '{print $1}')"
  fi

  recorded="$(mysql_exec <<SQL
SELECT COUNT(*) FROM ${LEDGER_TABLE} WHERE migration_name = '${name}';
SQL
)"
  recorded="$(echo "$recorded" | tr -d '[:space:]')"

  if [ "$recorded" = "1" ]; then
    echo "    [跳过] $name （台账已记录）"
    skipped=$((skipped + 1))
    continue
  fi

  echo "    [执行] $name ..."
  # 迁移本体与入账在同一个会话内提交：执行成功才写台账。
  # 入账前先 DELIMITER ; —— 迁移文件常用 DELIMITER // 定义存储过程，若不显式复位，
  # 追加的 INSERT 会被当成存储过程体的一部分而不执行。
  {
    cat "$migration"
    printf '\nDELIMITER ;\n'
    printf 'INSERT INTO %s (migration_name, checksum) VALUES (%s, %s);\n' \
      "$LEDGER_TABLE" "'${name}'" "'${checksum}'"
  } | docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T mysql \
        sh -c 'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"' \
    || { echo "❌ 迁移执行失败：$name —— 已中止发布，后续迁移未执行。"; exit 1; }

  applied=$((applied + 1))
  echo "    [完成] $name （已入账）"
done

echo "==> 数据库迁移结束：本次应用 ${applied} 个，跳过 ${skipped} 个。"
echo "==> 台账快照："
mysql_exec <<SQL
SELECT migration_name, applied_at FROM ${LEDGER_TABLE} ORDER BY migration_name;
SQL
