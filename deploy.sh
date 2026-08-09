#!/usr/bin/env bash
# 服务器一键更新脚本：git pull + 重建容器
# 用法：cd /opt/pams && ./deploy.sh
set -euo pipefail
cd "$(dirname "$0")"

echo "==> [1/3] git pull"
git pull --ff-only

echo "==> [2/3] docker compose up -d --build"
docker compose up -d --build

echo "==> [3/3] 清理悬空镜像"
docker image prune -f

echo "部署完成: $(date '+%Y-%m-%d %H:%M:%S')"
docker compose ps
