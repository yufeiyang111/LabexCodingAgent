[CmdletBinding()]
param(
    [int]$RedisPort = 6391,
    [int]$ConnectTimeoutSeconds = 2,
    [int]$CommandTimeoutSeconds = 2
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$runId = [Guid]::NewGuid().ToString('N')
$root = Join-Path ([IO.Path]::GetTempPath()) "labex-websearch-runtime-$runId"
$classesRoot = Join-Path $root 'classes'
New-Item -ItemType Directory -Force -Path $classesRoot | Out-Null

function Assert-PortFree {
    param([int]$Port)
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
    try { $listener.Start() } catch { throw "端口 $Port 已被占用，Web Search runtime smoke 拒绝接管。" } finally { try { $listener.Stop() } catch { } }
}

function Wait-Redis {
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    while ([DateTime]::UtcNow -lt $deadline) {
        & wsl.exe -d Debian -e sh -lc "redis-cli -h 127.0.0.1 -p $RedisPort ping 2>/dev/null" | Out-Null
        if ($LASTEXITCODE -eq 0) { return }
        Start-Sleep -Milliseconds 200
    }
    throw "临时 Redis 未在 20 秒内就绪：$RedisPort"
}

Assert-PortFree -Port $RedisPort
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw 'JAVA_HOME 必须指向 JDK 17+。' }
Write-Output "JAVA=$(& (Join-Path $env:JAVA_HOME 'bin\java.exe') -version 2>&1 | Select-Object -First 1)"
Write-Output "REDIS_PORT=$RedisPort"

$mvn = if (Test-Path -LiteralPath 'D:\apache-maven-3.9.10\bin\mvn.cmd') { 'D:\apache-maven-3.9.10\bin\mvn.cmd' } else { 'mvn.cmd' }
Push-Location (Join-Path $repoRoot 'backend')
try {
    & $mvn -s settings-local.xml -DskipTests dependency:build-classpath "-Dmdep.outputFile=$root\classpath.txt"
    if ($LASTEXITCODE -ne 0) { throw 'Maven dependency:build-classpath failed.' }
} finally { Pop-Location }
$dependencyClasspath = [IO.File]::ReadAllText((Join-Path $root 'classpath.txt'), [Text.Encoding]::UTF8).Trim()
$classpath = "$classesRoot;$(Join-Path $repoRoot 'backend\target\classes');$dependencyClasspath"

& wsl.exe -d Debian -e sh -lc "redis-server --port $RedisPort --save '' --appendonly no --daemonize yes --pidfile /tmp/labex-websearch-$RedisPort.pid --logfile /tmp/labex-websearch-$RedisPort.log"
if ($LASTEXITCODE -ne 0) { throw '临时 Redis 启动失败。' }
try {
    Wait-Redis
    Write-Output 'REDIS=PONG'
    & (Join-Path $env:JAVA_HOME 'bin\javac.exe') -encoding UTF-8 -cp "backend\target\classes;$dependencyClasspath" -d $classesRoot (Join-Path $PSScriptRoot 'WebSearchRuntimeAcceptanceHarness.java')
    if ($LASTEXITCODE -ne 0) { throw 'Harness compile failed.' }

    $prefix = "labex:websearch-runtime-$runId"
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
    foreach ($mode in @('writer','reader','shared','isolation','parallel','corrupt','ttl','timeout')) {
        Write-Output "=== $mode ==="
        & $java -cp $classpath WebSearchRuntimeAcceptanceHarness $mode $RedisPort $CommandTimeoutSeconds $prefix
        if ($LASTEXITCODE -ne 0) { throw "Harness failed: $mode" }
    }
} finally {
    & wsl.exe -d Debian -e sh -lc "redis-cli -h 127.0.0.1 -p $RedisPort shutdown nosave >/dev/null 2>&1 || true"
    Write-Output "LOG_ROOT=$root"
}
