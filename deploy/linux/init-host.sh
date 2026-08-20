#!/usr/bin/env bash
set -Eeuo pipefail

ROOT=/srv/labex-agent

if [[ "${EUID}" -ne 0 ]]; then
  echo "请使用 root 执行此脚本。" >&2
  exit 1
fi

install -d -m 0755 "$ROOT/app" "$ROOT/app/frontend-dist" "$ROOT/data/workspaces" "$ROOT/data/uploads" "$ROOT/backups"
chown -R 1000:1000 "$ROOT/data/workspaces" "$ROOT/data/uploads"

echo "目录已准备：$ROOT"
echo "下一步请把发布包内容放入 $ROOT/app，并把 .env.production 手工写入该目录。"
