[CmdletBinding()]
param(
    [int]$BackendPort = 18081,
    [int]$FrontendPort = 13001,
    [int]$CdpPort = 19223,
    [string]$JarPath = '',
    [string]$RedisUrl = 'redis://127.0.0.1:6389/7'
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
$logRoot = Join-Path ([IO.Path]::GetTempPath()) "labex-websearch-browser-$runId"
$workspaceRoot = Join-Path $repoRoot "workspaces\websearch-browser-$runId"
$databaseRoot = Join-Path $workspaceRoot '.acceptance-db'
New-Item -ItemType Directory -Force -Path $logRoot,$databaseRoot | Out-Null

function Test-PortFree {
    param([int]$Port)
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
    try { $listener.Start(); return $true } catch [Net.Sockets.SocketException] { return $false } finally { try { $listener.Stop() } catch { } }
}

function Assert-PortFree {
    param([int]$Port)
    if (-not (Test-PortFree -Port $Port)) { throw "端口 $Port 已被占用，验收脚本拒绝接管。" }
}

function Wait-Http {
    param([string]$Uri, [string]$Method = 'GET', [string]$Body = '')
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $parameters = @{ Uri = $Uri; Method = $Method; UseBasicParsing = $true; SkipHttpErrorCheck = $true; TimeoutSec = 3 }
            if ($Body) { $parameters.ContentType = 'application/json'; $parameters.Body = $Body }
            $response = Invoke-WebRequest @parameters
            if ($response.StatusCode -ge 100 -and $response.StatusCode -lt 600) { return }
        } catch { Start-Sleep -Milliseconds 300 }
    }
    throw "服务未在 120 秒内就绪：$Uri"
}

function Stop-OwnedProcessTree {
    param([int]$RootProcessId)
    $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$RootProcessId" -ErrorAction SilentlyContinue)
    foreach ($child in $children) { Stop-OwnedProcessTree -RootProcessId ([int]$child.ProcessId) }
    Stop-Process -Id $RootProcessId -Force -ErrorAction SilentlyContinue
}

function Remove-OwnedWorkspace {
    $workspaceBase = [IO.Path]::GetFullPath((Join-Path $repoRoot 'workspaces'))
    $resolved = [IO.Path]::GetFullPath($workspaceRoot)
    if ([IO.Path]::GetDirectoryName($resolved) -ne $workspaceBase -or
        -not [IO.Path]::GetFileName($resolved).StartsWith('websearch-browser-', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean a non-websearch-browser workspace: $resolved"
    }
    if ([IO.Directory]::Exists($resolved)) { [IO.Directory]::Delete($resolved, $true) }
}

$h2PortProbe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$h2PortProbe.Start()
$h2Port = ([Net.IPEndPoint]$h2PortProbe.LocalEndpoint).Port
$h2PortProbe.Stop()

Add-Type -AssemblyName System.IO.Compression.FileSystem
$h2JarPath = Join-Path $databaseRoot 'h2.jar'
$archive = [IO.Compression.ZipFile]::OpenRead($JarPath)
try {
    $entry = $archive.Entries | Where-Object { $_.FullName -like 'BOOT-INF/lib/h2-*.jar' } | Select-Object -First 1
    if (-not $entry) { throw 'Packaged backend does not contain H2 runtime.' }
    [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $h2JarPath, $true)
} finally { $archive.Dispose() }

$schemaSource = Join-Path $repoRoot 'backend\src\main\resources\sql\schema.sql'
$schemaText = [IO.File]::ReadAllText($schemaSource, [Text.Encoding]::UTF8)
if (-not $schemaText.Contains('pattern(191)')) { throw 'MySQL schema compatibility marker pattern(191) was not found.' }
$schemaPath = Join-Path $databaseRoot 'schema-h2.sql'
[IO.File]::WriteAllText($schemaPath, $schemaText.Replace('pattern(191)', 'pattern'), [Text.UTF8Encoding]::new($false))

$dataSource = Join-Path $repoRoot 'backend\src\main\resources\sql\data.sql'
$dataText = [IO.File]::ReadAllText($dataSource, [Text.Encoding]::UTF8)
$upsertPattern = '(?s)(INSERT INTO t_tutorial_document \([^\r\n]+\)\s*VALUES\s*\(.*?\))\s*ON DUPLICATE KEY UPDATE\s*.*?;'
$h2DataText = [regex]::Replace($dataText, $upsertPattern, { param($match) $match.Groups[1].Value + ';' })
$h2DataText = $h2DataText -replace '(?i)\bIF\s*\(', 'CASE WHEN '
if ($h2DataText -match '(?i)ON DUPLICATE KEY') { throw 'H2 acceptance data translation left MySQL upsert syntax behind.' }
$dataPath = Join-Path $databaseRoot 'data-h2.sql'
[IO.File]::WriteAllText($dataPath, $h2DataText, [Text.UTF8Encoding]::new($false))

$h2 = $null
$backend = $null
$vite = $null
try {
    Assert-PortFree -Port $BackendPort
    Assert-PortFree -Port $FrontendPort
    Assert-PortFree -Port $CdpPort

    $h2 = Start-Process -FilePath 'java' -ArgumentList @(
        '-Xms64m','-Xmx256m','-XX:MaxMetaspaceSize=96m','-XX:ReservedCodeCacheSize=32m',
        '-cp',$h2JarPath,'org.h2.tools.Server','-tcp','-tcpPort',[string]$h2Port,'-baseDir',$databaseRoot,'-ifNotExists'
    ) -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logRoot 'h2.out.log') -RedirectStandardError (Join-Path $logRoot 'h2.err.log')
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    do {
        Start-Sleep -Milliseconds 200
        $ready = $false
        try { $probe = [Net.Sockets.TcpClient]::new(); $probe.Connect('127.0.0.1',$h2Port); $probe.Dispose(); $ready = $true } catch { }
    } while (-not $ready -and [DateTime]::UtcNow -lt $deadline)
    if (-not $ready) { throw "H2 did not listen on $h2Port" }

    $backendArguments = @(
        '-Xms64m','-Xmx512m','-XX:MaxMetaspaceSize=256m','-XX:ReservedCodeCacheSize=128m',
        '-Dfile.encoding=UTF-8','-jar',$JarPath,
        "--server.port=$BackendPort",'--spring.profiles.active=acceptance,local',
        "--labex-agent.project-base-path=$workspaceRoot",
        "--labex-agent.instance-id=websearch-browser-$runId",
        "--labex-agent.cors.allowed-origins=http://127.0.0.1:$FrontendPort",
        "--labex-agent.websocket.allowed-origins=http://127.0.0.1:$FrontendPort",
        "--labex-agent.auth.redis-url=$RedisUrl",
        "--labex-agent.web-search.cache-redis-key-prefix=labex:web-search-browser-$runId",
        "--spring.data.redis.url=$RedisUrl",
        "--labex-agent.web-search.cache-ttl-seconds=600",
        '--labex-agent.auth.invite-code-enabled=false',
        '--spring.datasource.driver-class-name=org.h2.Driver',
        "--spring.datasource.url=jdbc:h2:tcp://127.0.0.1:$h2Port/$($databaseRoot.Replace('\','/'))/labex-agent;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_ON_EXIT=FALSE",
        '--spring.datasource.username=sa','--spring.datasource.password=',
        "--spring.sql.init.schema-locations=file:$($schemaPath.Replace('\','/'))",
        "--spring.sql.init.data-locations=file:$($dataPath.Replace('\','/'))"
    )
    $backend = Start-Process -FilePath 'java' -ArgumentList $backendArguments -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logRoot 'backend.out.log') -RedirectStandardError (Join-Path $logRoot 'backend.err.log')

    $oldApiTarget = $env:VITE_API_TARGET
    $oldFrontendPort = $env:ACCEPTANCE_FRONTEND_PORT
    try {
        $env:VITE_API_TARGET = "http://127.0.0.1:$BackendPort"
        $env:ACCEPTANCE_FRONTEND_PORT = [string]$FrontendPort
        $viteStdin = Join-Path $logRoot 'vite.stdin'
        [IO.File]::WriteAllText($viteStdin, '')
        $vite = Start-Process -FilePath 'npm.cmd' -ArgumentList @('run','dev','--','--host','127.0.0.1','--clearScreen','false','--strictPort') -WorkingDirectory $frontendRoot -PassThru -WindowStyle Hidden -RedirectStandardInput $viteStdin -RedirectStandardOutput (Join-Path $logRoot 'vite.out.log') -RedirectStandardError (Join-Path $logRoot 'vite.err.log')
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
        $env:ACCEPTANCE_BROWSER_TIMEOUT_MS = '120000'
        & node (Join-Path $frontendRoot 'scripts\acceptance\websearch-browser-smoke.mjs')
        if ($LASTEXITCODE -ne 0) { throw "Web Search browser smoke failed: exitCode=$LASTEXITCODE; logs=$logRoot" }
        Write-Output "LOG_ROOT=$logRoot"
    } finally {
        $env:ACCEPTANCE_UI_BASE = $oldUiBase
        $env:ACCEPTANCE_API_BASE = $oldApiBase
        $env:ACCEPTANCE_CDP_PORT = $oldCdpPort
        $env:ACCEPTANCE_BROWSER_TIMEOUT_MS = $oldTimeout
    }
} finally {
    foreach ($process in @($vite,$backend,$h2)) {
        if ($process -and -not $process.HasExited) {
            Stop-OwnedProcessTree -RootProcessId $process.Id
            try { $process.WaitForExit(10000) | Out-Null } catch { }
        }
    }
    try { Remove-OwnedWorkspace } catch { Write-Warning "Web Search browser workspace cleanup failed: $($_.Exception.Message)" }
}
