[CmdletBinding()]
param(
    [int]$BackendPort = 18080,
    [int]$FrontendPort = 13000,
    [int]$CdpPort = 19222,
    [string]$JarPath = '',
    [int]$TimeoutSeconds = 120,
    [switch]$RestartBackendForAcceptance
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
$databaseRoot = Join-Path $workspaceRoot '.acceptance-db'
$h2SchemaPath = Join-Path $databaseRoot 'schema-h2.sql'
$h2JarPath = Join-Path $databaseRoot 'h2.jar'
$h2ServerProcess = $null
$h2PortProbe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$h2PortProbe.Start()
$h2ServerPort = ([Net.IPEndPoint]$h2PortProbe.LocalEndpoint).Port
$h2PortProbe.Stop()
New-Item -ItemType Directory -Force -Path $databaseRoot | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$backendArchive = [IO.Compression.ZipFile]::OpenRead($JarPath)
try {
    $h2Entry = $backendArchive.Entries | Where-Object { $_.FullName -like 'BOOT-INF/lib/h2-*.jar' } | Select-Object -First 1
    if (-not $h2Entry) { throw 'Packaged backend does not contain the H2 runtime.' }
    [IO.Compression.ZipFileExtensions]::ExtractToFile($h2Entry, $h2JarPath, $true)
} finally {
    $backendArchive.Dispose()
}
$schemaSource = Join-Path $repoRoot 'backend\src\main\resources\sql\schema.sql'
$schemaText = [IO.File]::ReadAllText($schemaSource, [Text.Encoding]::UTF8)
$mysqlPrefixIndex = 'pattern(191)'
if (-not $schemaText.Contains($mysqlPrefixIndex)) {
    throw 'MySQL schema compatibility marker pattern(191) was not found.'
}
[IO.File]::WriteAllText($h2SchemaPath, $schemaText.Replace($mysqlPrefixIndex, 'pattern'), [Text.UTF8Encoding]::new($false))
$backend = $null
$vite = $null
$browserProcess = $null

function Test-PortFree {
    param([int]$Port)
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
    try { $listener.Start(); return $true } catch [Net.Sockets.SocketException] { return $false } finally { try { $listener.Stop() } catch { } }
}

function Assert-PortFree {
    param([int]$Port)
    if (-not (Test-PortFree -Port $Port)) { throw "端口 $Port 已被占用，验收脚本拒绝接管。" }
}

function Start-AcceptanceDatabase {
    Assert-PortFree -Port $h2ServerPort
    $stdout = Join-Path $logRoot 'h2-server-out.log'
    $stderr = Join-Path $logRoot 'h2-server-err.log'
    $arguments = @(
        '-cp', $h2JarPath, 'org.h2.tools.Server', '-tcp', '-tcpPort', [string]$h2ServerPort,
        '-baseDir', $databaseRoot, '-ifNotExists'
    )
    $script:h2ServerProcess = Start-Process -FilePath 'java' -ArgumentList $arguments -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($script:h2ServerProcess.HasExited) {
            throw "Isolated H2 server exited early, exitCode=$($script:h2ServerProcess.ExitCode), logs=$logRoot"
        }
        if (-not (Test-PortFree -Port $h2ServerPort)) { return }
        Start-Sleep -Milliseconds 150
    }
    throw "Isolated H2 server did not listen on port $h2ServerPort within 20 seconds."
}

function Stop-AcceptanceDatabase {
    $server = $script:h2ServerProcess
    $script:h2ServerProcess = $null
    if (-not $server) { return }
    $server.Refresh()
    if (-not $server.HasExited) {
        Stop-Process -Id $server.Id -Force
        $server.WaitForExit(10000) | Out-Null
    }
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


$backendArguments = @(
    '-Xms64m',
    '-Xmx512m',
    '-XX:MaxMetaspaceSize=256m',
    '-XX:ReservedCodeCacheSize=128m',
    '-Dfile.encoding=UTF-8', '-jar', $JarPath,
    "--server.port=$BackendPort", '--spring.profiles.active=acceptance,local',
    "--labex-agent.project-base-path=$workspaceRoot",
    "--labex-agent.instance-id=browser-acceptance-$runId",
    '--spring.datasource.driver-class-name=org.h2.Driver',
    "--spring.datasource.url=jdbc:h2:tcp://127.0.0.1:$h2ServerPort/./labex-agent;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_ON_EXIT=FALSE",
    '--spring.datasource.username=sa',
    '--spring.datasource.password=',
    "--spring.sql.init.schema-locations=file:$($h2SchemaPath.Replace('\', '/'))"
)

function Start-BackendProcess {
    param([int]$Ordinal)
    $stdoutPath = Join-Path $logRoot ("backend-{0}.out.log" -f $Ordinal)
    $stderrPath = Join-Path $logRoot ("backend-{0}.err.log" -f $Ordinal)
    return Start-Process -FilePath 'java' -ArgumentList $backendArguments -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
}

function Restart-BackendProcess {
    param([int]$Ordinal)
    if ($script:backend -and -not $script:backend.HasExited) {
        Stop-OwnedProcessTree -RootProcessId $script:backend.Id
        try { $script:backend.WaitForExit(10000) | Out-Null } catch { }
    }
    $script:backend = Start-BackendProcess -Ordinal $Ordinal
    Wait-Http -Uri "http://127.0.0.1:$BackendPort/api/auth/login" -Method POST -Body '{}'
}

function Invoke-BrowserRestartHandoffs {
    param(
        [System.Diagnostics.Process]$BrowserProcess,
        [string[]]$HandoffDirs
    )

    $pending = @{}
    foreach ($handoffDir in $HandoffDirs) {
        if (-not [string]::IsNullOrWhiteSpace($handoffDir)) {
            $pending[$handoffDir] = $true
        }
    }
    $restartOrdinal = 1
    while ($pending.Count -gt 0) {
        if ($BrowserProcess.HasExited) {
            throw "Browser acceptance exited before restart handoff completed; exitCode=$($BrowserProcess.ExitCode)"
        }
        foreach ($handoffDir in @($pending.Keys)) {
            $readyPath = Join-Path $handoffDir 'ready.json'
            $continuePath = Join-Path $handoffDir 'continue.signal'
            if ((Test-Path -LiteralPath $readyPath -PathType Leaf) -and
                -not (Test-Path -LiteralPath $continuePath -PathType Leaf)) {
                Write-Host "[browser-restart] handoff ready: $readyPath"
                Restart-BackendProcess -Ordinal $restartOrdinal
                $restartOrdinal++
                New-Item -ItemType File -Force -Path $continuePath | Out-Null
                $pending.Remove($handoffDir)
                Write-Host "[browser-restart] backend restarted and continuation signaled: $handoffDir"
            }
        }
        if ($pending.Count -gt 0) { Start-Sleep -Milliseconds 250 }
    }
}

try {
    Assert-PortFree -Port $BackendPort
    Assert-PortFree -Port $FrontendPort
    Assert-PortFree -Port $CdpPort

    Start-AcceptanceDatabase
    $backend = Start-BackendProcess -Ordinal 0

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
    $oldRestartHandoffDir = $env:ACCEPTANCE_RESTART_HANDOFF_DIR
    $oldInteractionRestartHandoffDir = $env:ACCEPTANCE_INTERACTION_RESTART_HANDOFF_DIR
    $restartHandoffDir = Join-Path $logRoot 'restart-projection'
    $interactionRestartHandoffDir = Join-Path $logRoot 'restart-interaction'
    try {
        $env:ACCEPTANCE_UI_BASE = "http://127.0.0.1:$FrontendPort"
        $env:ACCEPTANCE_API_BASE = "http://127.0.0.1:$BackendPort/api"
        $env:ACCEPTANCE_CDP_PORT = [string]$CdpPort
        $env:ACCEPTANCE_BROWSER_TIMEOUT_MS = [string]($TimeoutSeconds * 1000)
        if ($RestartBackendForAcceptance) {
            $env:ACCEPTANCE_RESTART_HANDOFF_DIR = $restartHandoffDir
            $env:ACCEPTANCE_INTERACTION_RESTART_HANDOFF_DIR = $interactionRestartHandoffDir
        } else {
            $env:ACCEPTANCE_RESTART_HANDOFF_DIR = ''
            $env:ACCEPTANCE_INTERACTION_RESTART_HANDOFF_DIR = ''
        }

        $browserProcess = Start-Process -FilePath 'node.exe' -ArgumentList @((Join-Path $frontendRoot 'scripts\acceptance\agent-browser.mjs')) -WorkingDirectory $frontendRoot -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logRoot 'browser.out.log') -RedirectStandardError (Join-Path $logRoot 'browser.err.log')
        if ($RestartBackendForAcceptance) {
            Invoke-BrowserRestartHandoffs -BrowserProcess $browserProcess -HandoffDirs @($interactionRestartHandoffDir, $restartHandoffDir)
        }
        $browserProcess.WaitForExit()
        $browserProcess.Refresh()
        $browserExitCode = $browserProcess.ExitCode
        $browserOutputPath = Join-Path $logRoot 'browser.out.log'
        $browserErrorPath = Join-Path $logRoot 'browser.err.log'
        $browserOutputLength = if (Test-Path -LiteralPath $browserOutputPath -PathType Leaf) { (Get-Item -LiteralPath $browserOutputPath).Length } else { 0 }
        $browserErrorLength = if (Test-Path -LiteralPath $browserErrorPath -PathType Leaf) { (Get-Item -LiteralPath $browserErrorPath).Length } else { 0 }
        if ($browserOutputLength -gt 0) {
            Get-Content -LiteralPath $browserOutputPath | Write-Host
        }
        if ($browserErrorLength -gt 0) {
            Get-Content -LiteralPath $browserErrorPath | Write-Warning
        }
        if (($null -ne $browserExitCode) -and $browserExitCode -ne 0) {
            throw "Browser acceptance failed; exitCode=$browserExitCode; logRoot=$logRoot"
        }
        if ($browserErrorLength -gt 0) {
            throw "Browser acceptance wrote diagnostics to stderr; logRoot=$logRoot"
        }
        if ($browserOutputLength -eq 0) {
            throw "Browser acceptance produced no result; logRoot=$logRoot"
        }
    } finally {
        $env:ACCEPTANCE_UI_BASE = $oldUiBase
        $env:ACCEPTANCE_API_BASE = $oldApiBase
        $env:ACCEPTANCE_CDP_PORT = $oldCdpPort
        $env:ACCEPTANCE_BROWSER_TIMEOUT_MS = $oldTimeout
        if ($null -eq $oldRestartHandoffDir) { Remove-Item Env:ACCEPTANCE_RESTART_HANDOFF_DIR -ErrorAction SilentlyContinue } else { $env:ACCEPTANCE_RESTART_HANDOFF_DIR = $oldRestartHandoffDir }
        if ($null -eq $oldInteractionRestartHandoffDir) { Remove-Item Env:ACCEPTANCE_INTERACTION_RESTART_HANDOFF_DIR -ErrorAction SilentlyContinue } else { $env:ACCEPTANCE_INTERACTION_RESTART_HANDOFF_DIR = $oldInteractionRestartHandoffDir }
    }
} finally {
    foreach ($process in @($vite, $backend)) {
        if ($process -and -not $process.HasExited) {
            Stop-OwnedProcessTree -RootProcessId $process.Id
            try { $process.WaitForExit(10000) | Out-Null } catch { }
        }
    }
    try { Stop-AcceptanceDatabase } catch { Write-Warning "Browser acceptance database shutdown failed: $($_.Exception.Message)" }
    try { Remove-OwnedWorkspace } catch { Write-Warning "Browser acceptance workspace cleanup failed: $($_.Exception.Message)" }
}
