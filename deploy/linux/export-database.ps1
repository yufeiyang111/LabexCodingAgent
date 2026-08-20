param(
    [string]$HostName = "127.0.0.1",
    [int]$Port = 3306,
    [string]$User = "root",
    [string]$Database = "labex_agent",
    [string]$Output = ""
)

$ErrorActionPreference = "Stop"

if (-not (Get-Command mysqldump -ErrorAction SilentlyContinue)) {
    throw "找不到 mysqldump。请安装 MySQL Client，或把 mysqldump.exe 所在目录加入 PATH 后重试。"
}

if ([string]::IsNullOrWhiteSpace($Output)) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $Output = Join-Path $PSScriptRoot "database-$stamp.sql"
}

$outputDirectory = Split-Path -Parent $Output
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null

& mysqldump `
    --host=$HostName `
    --port=$Port `
    --user=$User `
    --password `
    --single-transaction `
    --routines `
    --triggers `
    --events `
    --default-character-set=utf8mb4 `
    --set-gtid-purged=OFF `
    --result-file=$Output `
    $Database

if ($LASTEXITCODE -ne 0) {
    throw "MySQL 导出失败，未使用该备份继续迁移。"
}

Write-Host "数据库备份已生成：$Output"
