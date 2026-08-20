param(
    [string]$Output = ""
)

$ErrorActionPreference = "Stop"
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$Workspaces = Join-Path $ProjectRoot "workspaces"
$Uploads = Join-Path $ProjectRoot "uploads"

if (-not (Get-Command tar -ErrorAction SilentlyContinue)) {
    throw "找不到 tar。请使用 Windows 自带 tar.exe，或安装后重试。"
}

if (-not (Test-Path $Workspaces -PathType Container)) {
    throw "找不到目录：$Workspaces"
}
if (-not (Test-Path $Uploads -PathType Container)) {
    throw "找不到目录：$Uploads"
}
if ([string]::IsNullOrWhiteSpace($Output)) {
    $Output = Join-Path $ProjectRoot ("deploy/linux/data-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".tar.gz")
}

$parent = Split-Path -Parent $Output
New-Item -ItemType Directory -Force -Path $parent | Out-Null
tar -czf $Output -C $ProjectRoot workspaces uploads
Write-Host "数据归档已生成：$Output"
Write-Host "归档只包含 workspaces 和 uploads，不包含 .env、数据库密码或 OAuth Secret。"
