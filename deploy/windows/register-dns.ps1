[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$Cloudflared = 'C:\Program Files (x86)\cloudflared\cloudflared.exe'
if (-not (Test-Path -LiteralPath $Cloudflared)) {
    throw "找不到 cloudflared：$Cloudflared"
}

Write-Host '正在为 labex Tunnel 创建 labexagent.123845.xyz DNS 路由。'
& $Cloudflared tunnel route dns labex labexagent.123845.xyz
if ($LASTEXITCODE -ne 0) {
    throw "DNS 路由创建失败，退出码：$LASTEXITCODE"
}
Write-Host 'DNS 路由已创建。'
