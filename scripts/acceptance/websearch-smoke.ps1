[CmdletBinding()]
param(
    [int]$BackendPort = 18080,
    [string]$JarPath = 'D:\LabexAgent\backend\target\labex-agent-backend-1.0.0.jar',
    [string]$RedisUrl = 'redis://127.0.0.1:6389/7'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$runId = [Guid]::NewGuid().ToString('N')
$logRoot = Join-Path ([IO.Path]::GetTempPath()) "labex-websearch-smoke-$runId"
$workspaceRoot = Join-Path $repoRoot "workspaces\websearch-smoke-$runId"
$databaseRoot = Join-Path $workspaceRoot '.acceptance-db'
New-Item -ItemType Directory -Force -Path $logRoot,$databaseRoot | Out-Null

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
$schemaText = [IO.File]::ReadAllText($schemaSource, [Text.Encoding]::UTF8).Replace('pattern(191)', 'pattern')
$schemaPath = Join-Path $databaseRoot 'schema-h2.sql'
[IO.File]::WriteAllText($schemaPath, $schemaText, [Text.UTF8Encoding]::new($false))

$dataSource = Join-Path $repoRoot 'backend\src\main\resources\sql\data.sql'
$dataText = [IO.File]::ReadAllText($dataSource, [Text.Encoding]::UTF8)
$pattern = '(?s)(INSERT INTO t_tutorial_document \([^\r\n]+\)\s*VALUES\s*\(.*?\))\s*ON DUPLICATE KEY UPDATE\s*.*?;'
$dataText = [regex]::Replace($dataText, $pattern, { param($m) $m.Groups[1].Value + ';' })
$dataPath = Join-Path $databaseRoot 'data-h2.sql'
[IO.File]::WriteAllText($dataPath, $dataText, [Text.UTF8Encoding]::new($false))

& wsl.exe -d Debian -e sh -lc 'redis-cli -p 6389 -n 7 flushdb >/dev/null'
$h2Out = Join-Path $logRoot 'h2.out.log'
$h2Err = Join-Path $logRoot 'h2.err.log'
$h2 = Start-Process -FilePath 'java' -ArgumentList @('-Xms64m','-Xmx256m','-cp',$h2JarPath,'org.h2.tools.Server','-tcp','-tcpPort',[string]$h2Port,'-baseDir',$databaseRoot,'-ifNotExists') -PassThru -WindowStyle Hidden -RedirectStandardOutput $h2Out -RedirectStandardError $h2Err
$backend = $null
try {
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        Start-Sleep -Milliseconds 200
        $ready = $false
        try { $probe = [Net.Sockets.TcpClient]::new(); $probe.Connect('127.0.0.1',$h2Port); $probe.Dispose(); $ready = $true } catch { }
    } while (-not $ready -and [DateTime]::UtcNow -lt $deadline)
    if (-not $ready) { throw "H2 did not listen on $h2Port" }

    $backendOut = Join-Path $logRoot 'backend.out.log'
    $backendErr = Join-Path $logRoot 'backend.err.log'
    $arguments = @(
        '-Xms64m','-Xmx512m','-XX:MaxMetaspaceSize=256m','-XX:ReservedCodeCacheSize=128m',
        '-Dfile.encoding=UTF-8','-jar',$JarPath,
        "--server.port=$BackendPort",'--spring.profiles.active=acceptance,local',
        "--labex-agent.project-base-path=$workspaceRoot",
        "--labex-agent.instance-id=websearch-smoke-$runId",
        '--labex-agent.cors.allowed-origins=http://127.0.0.1:13000',
        '--labex-agent.websocket.allowed-origins=http://127.0.0.1:13000',
        "--labex-agent.auth.redis-url=$RedisUrl",
        "--spring.data.redis.url=$RedisUrl",
        '--labex-agent.auth.invite-code-enabled=false',
        '--spring.datasource.driver-class-name=org.h2.Driver',
        "--spring.datasource.url=jdbc:h2:tcp://127.0.0.1:$h2Port/$($databaseRoot.Replace('\','/'))/labex-agent;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_ON_EXIT=FALSE",
        '--spring.datasource.username=sa',
        '--spring.datasource.password=',
        "--spring.sql.init.schema-locations=file:$($schemaPath.Replace('\','/'))",
        "--spring.sql.init.data-locations=file:$($dataPath.Replace('\','/'))"
    )
    $backend = Start-Process -FilePath 'java' -ArgumentList $arguments -PassThru -WindowStyle Hidden -RedirectStandardOutput $backendOut -RedirectStandardError $backendErr
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    do {
        Start-Sleep -Milliseconds 500
        if ($backend.HasExited) { throw "Backend exited early: $($backend.ExitCode), logs=$logRoot" }
        try { $response = Invoke-WebRequest -Uri "http://127.0.0.1:$BackendPort/api/auth/login" -Method POST -ContentType 'application/json' -Body '{}' -UseBasicParsing -SkipHttpErrorCheck -TimeoutSec 3; $ready = $true } catch { $ready = $false }
    } while (-not $ready -and [DateTime]::UtcNow -lt $deadline)
    if (-not $ready) { throw "Backend did not become ready, logs=$logRoot" }

    $env:ACCEPTANCE_API_BASE = "http://127.0.0.1:$BackendPort/api"
    & node 'D:\LabexAgent\frontend\scripts\acceptance\websearch-smoke.mjs'
    if ($LASTEXITCODE -ne 0) { throw "Web search smoke failed: exitCode=$LASTEXITCODE" }
} finally {
    if ($backend -and -not $backend.HasExited) { Stop-Process -Id $backend.Id -Force -ErrorAction SilentlyContinue }
    if ($h2 -and -not $h2.HasExited) { Stop-Process -Id $h2.Id -Force -ErrorAction SilentlyContinue }
    Remove-Item Env:ACCEPTANCE_API_BASE -ErrorAction SilentlyContinue
}
