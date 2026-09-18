# 启动 LabexAgent 后端：从根目录 .env 注入环境变量，复用 current_cmdline.txt 记录的启动命令。
#
# 与 restart_backend.ps1 的区别（以及为什么需要它）：
#   1. .env 注入。application.yml 里 `spring.datasource.password: ${LABEX_AGENT_DB_PASSWORD:}` 默认空，
#      且仓库没有 dotenv 加载器 —— 环境变量必须在启动时注入，否则数据源密码为空、启动即失败。
#   2. 日志可靠。原脚本把输出重定向挂在自身进程上，脚本一退出输出流就断开，日志文件会变成空文件
#      （backend-dev.log 长期为空即此原因）。本脚本在前台 WaitForExit 持有管道，日志持续写入。
#   3. 参数来源唯一：current_cmdline.txt（由 scripts/capture_backend_cmdline.ps1 或手工导出）。
#
# 用法：powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start_backend.ps1

$ErrorActionPreference = "Stop"

$workspaceRoot = "D:\LabexAgent"
$backendDir = Join-Path $workspaceRoot "backend"
$envFile = Join-Path $workspaceRoot ".env"
$cmdlineFile = Join-Path $backendDir "current_cmdline.txt"
$logFile = Join-Path $backendDir "backend-dev.log"

if (-not (Test-Path $envFile)) { throw "缺少 $envFile" }
if (-not (Test-Path $cmdlineFile)) { throw "缺少 $cmdlineFile" }

# 1. 解析 .env（KEY=VALUE；# 注释；成对引号剥离）
$envMap = @{}
foreach ($line in (Get-Content $envFile)) {
    $t = $line.Trim()
    if (-not $t) { continue }
    if ($t.StartsWith("#")) { continue }
    $i = $t.IndexOf("=")
    if ($i -lt 1) { continue }
    $k = $t.Substring(0, $i).Trim()
    $v = $t.Substring($i + 1).Trim()
    if ($v.Length -ge 2) {
        $dq = $v.StartsWith('"') -and $v.EndsWith('"')
        $sq = $v.StartsWith("'") -and $v.EndsWith("'")
        if ($dq -or $sq) { $v = $v.Substring(1, $v.Length - 2) }
    }
    $envMap[$k] = $v
}
if ($envMap.Count -eq 0) { throw "$envFile 未解析出任何变量" }

# 关键变量缺失时 fail closed：宁可启动前报错，也不要起一个连不上库的实例。
foreach ($required in @("LABEX_AGENT_DB_PASSWORD", "LABEX_AGENT_DB_USERNAME")) {
    if (-not $envMap.ContainsKey($required) -or -not $envMap[$required]) {
        throw "$envFile 缺少 $required，后端将无法连接数据库"
    }
}

# 2. 解析启动命令（java 路径 + 全部参数）
$rawCmd = (Get-Content $cmdlineFile -Raw).Trim()
if (-not $rawCmd) { throw "$cmdlineFile 为空：请先导出原启动命令行" }
if ($rawCmd -match '^("?[^"]+?java(?:\.exe)?"?)\s+(.*)$') {
    $javaExe = $matches[1].Trim('"')
    $javaArgs = $matches[2]
} else {
    throw "无法从 $cmdlineFile 解析出 java 命令"
}
if (-not (Test-Path $javaExe)) { throw "java 可执行文件不存在：$javaExe" }

# 3. 停止占用 8080 的旧进程
$listeners = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
foreach ($victim in ($listeners | Select-Object -ExpandProperty OwningProcess -Unique)) {
    if ($victim -gt 0) { Stop-Process -Id $victim -Force -ErrorAction SilentlyContinue }
}
Start-Sleep -Seconds 2

# 4. 启动（注入 .env 变量，其余沿用当前环境）
$startInfo = New-Object System.Diagnostics.ProcessStartInfo
$startInfo.FileName = $javaExe
$startInfo.Arguments = $javaArgs
$startInfo.WorkingDirectory = $backendDir
$startInfo.UseShellExecute = $false
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true
$startInfo.CreateNoWindow = $true

# 注入 .env 到当前进程环境，由子进程继承。
# 不要用 $startInfo.EnvironmentVariables —— 在某些运行时该属性返回 null，
# 赋值会抛 "无法对 Null 数组进行索引"，而错误信息很容易被忽略、导致静默注入失败
# （表现：java 起来了但数据源密码为空 → 启动失败）。
foreach ($k in $envMap.Keys) {
    Set-Item -Path ("Env:" + $k) -Value ([string]$envMap[$k])
}
Write-Host ("已从 .env 注入 " + $envMap.Count + " 个环境变量")

$process = New-Object System.Diagnostics.Process
$process.StartInfo = $startInfo
# GetNewClosure() 捕获当前作用域的 $logPath：直接引用 $script:logFile 在 -File 调用形态下
# 不保证可见，事件回调会静默写不进去（日志文件只剩头部即此现象）。
$logPath = $logFile
$process.add_OutputDataReceived({
    param($sender, $e)
    if ($e.Data) { [System.IO.File]::AppendAllText($logPath, $e.Data + "`r`n") }
}.GetNewClosure())
$process.add_ErrorDataReceived({
    param($sender, $e)
    if ($e.Data) { [System.IO.File]::AppendAllText($logPath, "[STDERR] " + $e.Data + "`r`n") }
}.GetNewClosure())

[System.IO.File]::AppendAllText($logFile, "`r`n===== start_backend.ps1 launched " + (Get-Date -Format "yyyy-MM-dd HH:mm:ss") + " =====`r`n")

if (-not $process.Start()) { throw "java 进程启动失败" }
$process.BeginOutputReadLine()
$process.BeginErrorReadLine()
Write-Host ""
Write-Host "已启动 java（PID=$($process.Id)），正在等待 8080 就绪（Spring Boot 首次启动约 30~60 秒）..."
Write-Host "Spring 启动日志正在写入：$logFile"
Write-Host ""

# 5. 轮询健康检查
$ready = $false
for ($i = 0; $i -lt 90; $i++) {
    Start-Sleep -Seconds 1
    if ($process.HasExited) { break }
    try {
        $resp = Invoke-WebRequest -Uri "http://localhost:8080/api/auth/captcha" -UseBasicParsing -TimeoutSec 2 -ErrorAction SilentlyContinue
        if ($resp.StatusCode -eq 200) { $ready = $true; break }
    } catch { }
}

# 6. 结论写进日志（宿主要保持存活，否则输出流断开、日志停写）
if ($process.HasExited) {
    [System.IO.File]::AppendAllText($logFile, "[start_backend] 进程已退出，exitCode=$($process.ExitCode)`r`n")
    Write-Warning "java 进程已退出，exitCode=$($process.ExitCode)，详见 $logFile"
    exit 1
}
if ($ready) {
    [System.IO.File]::AppendAllText($logFile, "[start_backend] 8080 已就绪，PID=$($process.Id)`r`n")
    Write-Host ""
    Write-Host "后端已就绪：http://localhost:8080/api   （PID=$($process.Id)）"
    Write-Host ""
    Write-Host "  此窗口请保持打开 —— 关闭窗口即停止后端。"
    Write-Host "  前端地址：http://localhost:3000"
    Write-Host "  完整日志：$logFile"
    Write-Host ""
} else {
    [System.IO.File]::AppendAllText($logFile, "[start_backend] 健康检查超时，PID=$($process.Id)`r`n")
    Write-Warning "健康检查超时（PID=$($process.Id)），详见 $logFile"
}

$process.WaitForExit()
[System.IO.File]::AppendAllText($logFile, "[start_backend] 进程结束，exitCode=$($process.ExitCode)`r`n")
