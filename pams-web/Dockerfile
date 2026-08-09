# ---- 构建阶段：Node 打包前端 ----
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
# 国内构建加速：npmmirror 镜像源
RUN npm ci --no-audit --no-fund --registry=https://registry.npmmirror.com
COPY . .
RUN npm run build

# ---- 运行阶段：nginx 托管 + 反代后端 ----
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
