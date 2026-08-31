#!/bin/sh
set -eu
# 让 Worker 新建的文件默认对共享工作区组可写。
umask 0002
exec "$@"
