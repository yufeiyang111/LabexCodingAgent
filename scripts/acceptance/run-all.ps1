[CmdletBinding()]
param(
    [int]$BackendPort = 18080,
    [int]$FrontendPort = 13000,
    [int]$CdpPort = 19222,
    [int]$TimeoutSeconds = 120,
    [switch]$RestartBrowserBackend
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

foreach ($command in @('java','mvn','node','npm.cmd')) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "缺少验收依赖：$command"
    }
}

$summary = [ordered]@{
    package = $false
    acceptanceUnitTests = $false
    backendRuntime = $false
    browserRuntime = $false
}

Write-Host '[1/4] Packaging backend...'
& mvn -q -f (Join-Path $repoRoot 'backend\pom.xml') -DskipTests package
if ($LASTEXITCODE -ne 0) { throw "Maven package 失败，exitCode=$LASTEXITCODE" }
$summary.package = $true

Write-Host '[2/4] Running acceptance unit tests...'
Push-Location (Join-Path $repoRoot 'frontend')
try {
    & npm.cmd run test:acceptance:unit
    if ($LASTEXITCODE -ne 0) { throw "验收单元测试失败，exitCode=$LASTEXITCODE" }
} finally { Pop-Location }
$summary.acceptanceUnitTests = $true

Write-Host '[3/4] Running backend restart acceptance...'
& (Join-Path $PSScriptRoot 'agent-runtime.ps1') -BackendPort $BackendPort -TimeoutSeconds $TimeoutSeconds
if ($LASTEXITCODE -ne 0) { throw "后端重启验收失败，exitCode=$LASTEXITCODE" }
$summary.backendRuntime = $true

Write-Host '[4/4] Running browser acceptance...'
if ($RestartBrowserBackend) {
    & (Join-Path $PSScriptRoot 'browser-runtime.ps1') -BackendPort $BackendPort -FrontendPort $FrontendPort -CdpPort $CdpPort -TimeoutSeconds $TimeoutSeconds -RestartBackendForAcceptance
} else {
    & (Join-Path $PSScriptRoot 'browser-runtime.ps1') -BackendPort $BackendPort -FrontendPort $FrontendPort -CdpPort $CdpPort -TimeoutSeconds $TimeoutSeconds
}
if ($LASTEXITCODE -ne 0) { throw "Browser acceptance failed; exitCode=$LASTEXITCODE" }
$summary.browserRuntime = $true


$summary | ConvertTo-Json
