#!/usr/bin/env bash
# 服务器一键更新脚本：git pull + 重建容器
# 用法：cd /opt/pams && ./deploy.sh
set -euo pipefail
cd "$(dirname "$0")"

echo "==> [1/3] git pull"
git pull --ff-only

echo "==> [2/3] docker compose up"
if [ -n "${PAMS_IMAGE_PREFIX:-}" ] && [ "$PAMS_IMAGE_PREFIX" != "pams" ]; then
  # 生产：镜像从仓库拉取，不构建
  docker compose pull
  docker compose up -d --no-build
else
  # 本地：构建并启动
  docker compose up -d --build
fi

echo "==> [3/3] 清理悬空镜像"
docker image prune -f

echo "部署完成: $(date '+%Y-%m-%d %H:%M:%S')"
docker compose ps
