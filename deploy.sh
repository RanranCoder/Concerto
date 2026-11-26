#!/usr/bin/env bash
# 服务器一键更新脚本：git pull + 重建容器
# 用法：cd /opt/pams && ./deploy.sh
set -euo pipefail
cd "$(dirname "$0")"

# Compose 自行读取并解析 .env，明确配置的 PAMS_WEB_PORT 优先于此默认值。
export PAMS_DEFAULT_WEB_PORT=8080

echo "==> [1/3] git pull"
git pull --ff-only

echo "==> [2/3] docker compose up"
images=$(docker compose config --images)
if ! grep -q '^pams-backend:' <<< "$images"; then
  export PAMS_DEPLOY_COMMIT
  PAMS_DEPLOY_COMMIT=$(git rev-parse HEAD)
  images=$(docker compose config --images)
  if grep -Eq -- '-(backend|web):latest$' <<< "$images"; then
    echo '请去掉 PAMS_IMAGE_TAG=latest，或将其设为两套镜像共有的提交 SHA。' >&2
    exit 1
  fi
  # 先拉取两个同版本镜像，成功后再启动，避免部分发布替换现有服务。
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
