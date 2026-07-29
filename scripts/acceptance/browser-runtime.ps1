[CmdletBinding()]
param(
    [int]$BackendPort = 18080,
    [int]$FrontendPort = 13000,
    [int]$CdpPort = 19222,
    [string]$JarPath = '',
    [int]$TimeoutSeconds = 120
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$frontendRoot = Join-Path $repoRoot 'frontend'
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $repoRoot 'backend\target') -Filter 'labex-agent-backend-*.jar' -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '\.original$' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) { throw '未找到后端 JAR，请先执行 Maven package。' }
    $JarPath = $jar.FullName
}
$JarPath = (Resolve-Path -LiteralPath $JarPath).Path
$runId = [Guid]::NewGuid().ToString('N')
$logRoot = Join-Path ([IO.Path]::GetTempPath()) "labex-agent-browser-runtime-$runId"
$workspaceRoot = Join-Path $repoRoot "workspaces\browser-acceptance-$runId"
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
$backend = $null
$vite = $null

function Test-PortFree {
    param([int]$Port)
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
    try { $listener.Start(); return $true } catch [Net.Sockets.SocketException] { return $false } finally { try { $listener.Stop() } catch { } }
}

function Assert-PortFree {
    param([int]$Port)
    if (-not (Test-PortFree -Port $Port)) { throw "端口 $Port 已被占用，验收脚本拒绝接管。" }
}

function Remove-OwnedWorkspace {
    $workspaceBase = [IO.Path]::GetFullPath((Join-Path $repoRoot 'workspaces'))
    $resolved = [IO.Path]::GetFullPath($workspaceRoot)
    if ([IO.Path]::GetDirectoryName($resolved) -ne $workspaceBase -or
        -not [IO.Path]::GetFileName($resolved).StartsWith('browser-acceptance-', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean a non-browser-acceptance workspace: $resolved"
    }
    if ([IO.Directory]::Exists($resolved)) {
        [IO.Directory]::Delete($resolved, $true)
    }
}

function Stop-OwnedProcessTree {
    param([int]$RootProcessId)
    $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$RootProcessId" -ErrorAction SilentlyContinue)
    foreach ($child in $children) { Stop-OwnedProcessTree -RootProcessId ([int]$child.ProcessId) }
    Stop-Process -Id $RootProcessId -Force -ErrorAction SilentlyContinue
}

function Wait-Http {
    param([string]$Uri, [string]$Method = 'GET', [string]$Body = '')
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $parameters = @{ Uri = $Uri; Method = $Method; UseBasicParsing = $true; TimeoutSec = 3 }
            if ($Body) { $parameters.ContentType = 'application/json'; $parameters.Body = $Body }
            $response = Invoke-WebRequest @parameters
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) { return }
        } catch { Start-Sleep -Milliseconds 300 }
    }
    throw "服务未在 $TimeoutSeconds 秒内就绪：$Uri"
}

try {
    Assert-PortFree -Port $BackendPort
    Assert-PortFree -Port $FrontendPort
    Assert-PortFree -Port $CdpPort

    $backend = Start-Process -FilePath 'java' -ArgumentList @(
        '-Dfile.encoding=UTF-8', '-jar', $JarPath,
        "--server.port=$BackendPort", '--spring.profiles.active=acceptance,local',
        "--labex-agent.project-base-path=$workspaceRoot",
        "--labex-agent.instance-id=browser-acceptance-$runId"
    ) -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logRoot 'backend.out.log') -RedirectStandardError (Join-Path $logRoot 'backend.err.log')

    $oldApiTarget = $env:VITE_API_TARGET
    $oldFrontendPort = $env:ACCEPTANCE_FRONTEND_PORT
    try {
        $env:VITE_API_TARGET = "http://127.0.0.1:$BackendPort"
        $env:ACCEPTANCE_FRONTEND_PORT = [string]$FrontendPort
        $vite = Start-Process -FilePath 'npm.cmd' -ArgumentList @('run','dev','--','--host','127.0.0.1') -WorkingDirectory $frontendRoot -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logRoot 'vite.out.log') -RedirectStandardError (Join-Path $logRoot 'vite.err.log')
    } finally {
        $env:VITE_API_TARGET = $oldApiTarget
        $env:ACCEPTANCE_FRONTEND_PORT = $oldFrontendPort
    }

    Wait-Http -Uri "http://127.0.0.1:$BackendPort/api/auth/login" -Method POST -Body '{}'
    Wait-Http -Uri "http://127.0.0.1:$FrontendPort/login"

    $oldUiBase = $env:ACCEPTANCE_UI_BASE
    $oldApiBase = $env:ACCEPTANCE_API_BASE
    $oldCdpPort = $env:ACCEPTANCE_CDP_PORT
    $oldTimeout = $env:ACCEPTANCE_BROWSER_TIMEOUT_MS
    try {
        $env:ACCEPTANCE_UI_BASE = "http://127.0.0.1:$FrontendPort"
        $env:ACCEPTANCE_API_BASE = "http://127.0.0.1:$BackendPort/api"
        $env:ACCEPTANCE_CDP_PORT = [string]$CdpPort
        $env:ACCEPTANCE_BROWSER_TIMEOUT_MS = [string]($TimeoutSeconds * 1000)
        & node (Join-Path $frontendRoot 'scripts\acceptance\agent-browser.mjs')
        if ($LASTEXITCODE -ne 0) { throw "浏览器验收失败，exitCode=$LASTEXITCODE，日志目录：$logRoot" }
    } finally {
        $env:ACCEPTANCE_UI_BASE = $oldUiBase
        $env:ACCEPTANCE_API_BASE = $oldApiBase
        $env:ACCEPTANCE_CDP_PORT = $oldCdpPort
        $env:ACCEPTANCE_BROWSER_TIMEOUT_MS = $oldTimeout
    }
} finally {
    foreach ($process in @($vite, $backend)) {
        if ($process -and -not $process.HasExited) {
            Stop-OwnedProcessTree -RootProcessId $process.Id
            try { $process.WaitForExit(10000) | Out-Null } catch { }
        }
    }
    try { Remove-OwnedWorkspace } catch { Write-Warning "Browser acceptance workspace cleanup failed: $($_.Exception.Message)" }
}
