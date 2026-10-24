# 重置 PAMS 数据库并写入演示组织/账号
#
# 用法（仓库根目录）：
#   powershell -ExecutionPolicy Bypass -File scripts/demo-data/apply-demo-data.ps1
#
# 可选环境变量：DB_USER（默认 root）、DB_PASSWORD（默认 root）、DB_HOST（默认 localhost）、DB_PORT（3306）
# 脚本会先在仓库根目录 .backups/ 生成一份 mysqldump 备份，再执行清空与种子 SQL。

$ErrorActionPreference = 'Stop'

$repo = Resolve-Path (Join-Path $PSScriptRoot '..\..')
Set-Location $repo

$sqlFile = Join-Path $PSScriptRoot 'reset-and-seed-demo.sql'
if (-not (Test-Path $sqlFile)) { throw "找不到种子 SQL：$sqlFile" }

# 读取仓库根 .env 中的 DB_PASSWORD（如果未显式设置）
if (-not $env:DB_PASSWORD -and (Test-Path (Join-Path $repo '.env'))) {
    $line = Get-Content (Join-Path $repo '.env') | Where-Object { $_ -match '^\s*DB_PASSWORD\s*=' } | Select-Object -First 1
    if ($line) { $env:DB_PASSWORD = ($line -split '=', 2)[1].Trim().Trim('"').Trim("'") }
}
if (-not $env:DB_USER) { $env:DB_USER = 'root' }
if (-not $env:DB_PASSWORD) { $env:DB_PASSWORD = 'root' }
if (-not $env:DB_HOST) { $env:DB_HOST = 'localhost' }
if (-not $env:DB_PORT) { $env:DB_PORT = '3306' }
$env:MYSQL_PWD = $env:DB_PASSWORD

$mysqlArgs = @("-h", $env:DB_HOST, "-P", $env:DB_PORT, "-u", $env:DB_USER)

Write-Host '[1/4] 检查 MySQL 连接...'
& mysqladmin @mysqlArgs ping | Out-Null

Write-Host '[2/4] 备份 pams_db 到 .backups/ ...'
$backupDir = Join-Path $repo '.backups'
New-Item -ItemType Directory -Force $backupDir | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backup = Join-Path $backupDir "pams_db-$stamp.sql"
& mysqldump @mysqlArgs --single-transaction --routines --triggers --set-gtid-purged=OFF `
    --default-character-set=utf8mb4 --result-file=$backup pams_db
if ($LASTEXITCODE -ne 0) { throw '备份失败，已中止' }
Write-Host "      备份：$backup"

Write-Host '[3/4] 清空并写入演示数据...'
$cmd = "`"$((Get-Command mysql).Source)`" -h $($env:DB_HOST) -P $($env:DB_PORT) -u $($env:DB_USER) --default-character-set=utf8mb4 pams_db < `"$sqlFile`""
cmd /c $cmd
if ($LASTEXITCODE -ne 0) { throw '种子 SQL 执行失败，可用备份恢复' }

Write-Host '[4/4] 完成。演示账号统一密码：123456'
Write-Host '      账号清单：scripts/demo-data/demo-accounts.md'
