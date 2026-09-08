$ErrorActionPreference = "Stop"

$workspaceRoot = "D:\LabexAgent"
$backendDir = "$workspaceRoot\backend"
$logFile = "$backendDir\backend-dev.log"
$cmdlineFile = "$backendDir\current_cmdline.txt"

if (-not (Test-Path $cmdlineFile)) {
    Write-Error "Command line file not found: $cmdlineFile"
    exit 1
}

$rawCmd = (Get-Content $cmdlineFile -Raw).Trim()

# 提取 java 可执行文件路径与参数
if ($rawCmd -match '^("?[^"]+?java(?:\.exe)?"?)\s+(.*)$') {
    $javaExe = $matches[1].Trim('"')
    $javaArgs = $matches[2]
} else {
    Write-Error "Failed to parse command line from $cmdlineFile"
    exit 1
}

# 1. 查找占用 8080 端口的 PID
$tcp = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
if ($tcp) {
    $pids = $tcp | Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($pidToKill in $pids) {
        if ($pidToKill -gt 0) {
            Write-Host "Stopping existing process on 8080: PID $pidToKill"
            Stop-Process -Id $pidToKill -Force -ErrorAction SilentlyContinue
        }
    }
    Start-Sleep -Seconds 2
}

# 2. 在后台启动新的 Java 进程
Write-Host "Starting backend via: $javaExe in $backendDir"
$startInfo = New-Object System.Diagnostics.ProcessStartInfo
$startInfo.FileName = $javaExe
$startInfo.Arguments = $javaArgs
$startInfo.WorkingDirectory = $backendDir
$startInfo.UseShellExecute = $false
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true
$startInfo.CreateNoWindow = $true

$process = New-Object System.Diagnostics.Process
$process.StartInfo = $startInfo

# 输出重定向到文件
$outStream = [System.IO.File]::CreateText($logFile)
$process.add_OutputDataReceived({
    param($sender, $e)
    if ($e.Data) {
        [System.IO.File]::AppendAllText($logFile, $e.Data + "`r`n")
    }
})
$process.add_ErrorDataReceived({
    param($sender, $e)
    if ($e.Data) {
        [System.IO.File]::AppendAllText($logFile, "[STDERR] " + $e.Data + "`r`n")
    }
})

$started = $process.Start()
if (-not $started) {
    Write-Error "Failed to start backend process"
    exit 1
}

$process.BeginOutputReadLine()
$process.BeginErrorReadLine()

Write-Host "Backend started with PID $($process.Id), waiting for port 8080..."

# 3. 轮询健康检查直到 8080 就绪（最多 45 秒）
$ready = $false
for ($i = 0; $i -lt 45; $i++) {
    Start-Sleep -Seconds 1
    try {
        $resp = Invoke-WebRequest -Uri "http://localhost:8080/api/auth/captcha" -UseBasicParsing -TimeoutSec 2 -ErrorAction SilentlyContinue
        if ($resp.StatusCode -eq 200) {
            $ready = $true
            break
        }
    } catch {
        # continue waiting
    }
}

if ($ready) {
    Write-Host "Backend successfully started and ready on port 8080! PID: $($process.Id)"
} else {
    Write-Warning "Backend process started (PID $($process.Id)), but port 8080 healthcheck timed out. Check log: $logFile"
}
