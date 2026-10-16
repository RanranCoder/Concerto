@echo off
chcp 65001 >nul

rem ── 载入 .env ─────────────────────────────────────────────────────────────
rem docker compose 会自动读 .env，但 `mvn spring-boot:run` 不会（pom 里没有 dotenv 依赖），
rem 而 PAMS_AGENT_API_KEY 只从环境变量取（AgentProperties.resolveApiKey）——
rem 不载入的后果是 LlmConfig 拿不到 Key，灵思每次调用都直接降级成 stub，
rem 前端表现为「永远是规则模式」，与网络/密钥本身无关。
rem 只取形如 KEY=VALUE 的行（注释行、空行天然被排除），值可含 = 与空格。
rem .env 已在 .gitignore 中，密钥不会进版本库。
set "ENV_FILE=%~dp0.env"
if exist "%ENV_FILE%" (
    for /f "tokens=1,* delims==" %%a in ('findstr /r /b "^[A-Za-z_][A-Za-z0-9_]*=" "%ENV_FILE%"') do set "%%a=%%b"
    echo [0/4] 已载入 .env
    if not defined PAMS_AGENT_API_KEY echo       注意：.env 里没有 PAMS_AGENT_API_KEY，灵思将走 stub 规则模式
) else (
    echo [0/4] 未找到 .env —— 灵思将走 stub 规则模式（需要真模型时复制 .env.example 为 .env 并填 Key）
)

if not defined DB_PASSWORD set DB_PASSWORD=root
rem 本地开发密钥：.env 已提供则沿用（重启后不必重新登录）；否则每次启动生成随机值
if not defined PAMS_JWT_SECRET (
    for /f %%i in ('powershell -NoProfile -Command "[guid]::NewGuid().ToString('N')+[guid]::NewGuid().ToString('N')"') do set PAMS_JWT_SECRET=%%i
)
echo [1/4] 检查 MySQL 服务...
mysqladmin -u root -p%DB_PASSWORD% ping >nul 2>&1 || (echo 请先启动 MySQL 服务 & pause & exit /b)
echo [2/4] 初始化数据库...
mysql -u root -p%DB_PASSWORD% < %~dp0database\init_db.sql
echo [3/4] 启动后端 (http://localhost:8080)...
start "pams-backend" cmd /k "cd /d %~dp0pams-backend && set PAMS_JWT_SECRET=%PAMS_JWT_SECRET%&& mvn spring-boot:run -Dspring-boot.run.profiles=dev"
echo [4/4] 启动前端 (http://localhost:5173)...
start "pams-web" cmd /k "cd /d %~dp0pams-web && npm run dev"
timeout /t 15 >nul
start http://localhost:5173
