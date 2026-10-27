# 启动 LabexAgent 后端：从根目录 .env 注入环境变量，复用 current_cmdline.txt 记录的启动命令。
#
# 与 restart_backend.ps1 的区别（以及为什么需要它）：
#   1. .env 注入。application.yml 里 `spring.datasource.password: ${LABEX_AGENT_DB_PASSWORD:}` 默认空，
#      且仓库没有 dotenv 加载器 —— 环境变量必须在启动时注入，否则数据源密码为空、启动即失败。
#   2. 日志可靠。本脚本把 java 的 stdout/stderr **直接重定向到文件**（由操作系统写，见下），
#      不依赖 PowerShell 在后台泵输出流。
#   3. 参数来源唯一：current_cmdline.txt（由 scripts/capture_backend_cmdline.ps1 或手工导出）。
#
# 用法：powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start_backend.ps1 [-ReadyTimeoutSeconds 90]
#
# ── 就绪判据（2026-10-06 修）────────────────────────────────────────────
# 旧实现把 `GET /api/auth/captcha == 200` 当作唯一就绪信号，有两个问题：
#   a) captcha 依赖 Redis；Redis 不可用时它返回 503，于是一个**已经可以服务**的后端会被判成
#      “健康检查超时/启动失败”；
#   b) 只探测一个业务端点，端口在听但应用还在初始化时也可能被误判。
# 现在改为：
#   1) **主判据**：日志出现 Spring 的 `Started LabexAgentApplication` —— 这是应用自己宣告就绪；
#   2) 辅助：8080 是否在监听（用于区分“还没起来”与“起来了但没宣告”）；
#   3) captcha 只用来报告**依赖降级**（非 200 时打印警告并给出状态码），不参与就绪判定。
# 结论只有三种：就绪 / 进程已退出（失败，附退出码与日志尾部）/ 超时未确认（不臆断成功或失败）。
#
# ── 日志管道（宿主生命周期）─────────────────────────────────────────────
# 旧实现用 `$process.add_OutputDataReceived` + `BeginOutputReadLine` 异步事件回调写日志：
# 回调只在宿主进程存活且仍在泵消息循环时才触发，宿主一旦退出日志就停写（表现为日志只剩头部）。
# 现在用 Start-Process 的文件重定向：写入由 OS 完成，与宿主是否存活无关，宿主观测进程只负责等待。

param(
    [int]$ReadyTimeoutSeconds = 90
)

$ErrorActionPreference = "Stop"

$workspaceRoot = "D:\LabexAgent"
$backendDir = Join-Path $workspaceRoot "backend"
$envFile = Join-Path $workspaceRoot ".env"
$cmdlineFile = Join-Path $backendDir "current_cmdline.txt"
$logFile = Join-Path $backendDir "backend-dev.log"            # java stdout（由 OS 重定向写入，java 运行期间不可追加）
$errLogFile = Join-Path $backendDir "backend-dev.err.log"      # java stderr
# 启动器自己的消息必须写**另一个文件**：Start-Process 重定向的目标文件在子进程存活期间被独占，
# 往同一个文件 AppendAllText 会抛 IOException（旧实现因此静默死在就绪之后）。
$launcherLogFile = Join-Path $backendDir "backend-dev.launcher.log"

if (-not (Test-Path $envFile)) { throw "缺少 $envFile" }
if (-not (Test-Path $cmdlineFile)) { throw "缺少 $cmdlineFile" }

function Write-Both([string]$message) {
    Write-Host $message
    [System.IO.File]::AppendAllText($launcherLogFile, $message + "`r`n")
}

# PS 5.1 已知限制：Start-Process -PassThru 与 -RedirectStandardOutput/-RedirectStandardError 同时使用时，
# 子进程退出后 ExitCode 恒为空（Refresh()/WaitForExit() 也拿不到）。
# 与其写出一条 “exitCode=” 的空尾巴，不如明说拿不到，并把日志尾部作为真正的判据。
function Get-ProcessExitCode($proc) {
    try {
        if ($proc.HasExited) {
            $code = [string]$proc.ExitCode
            if ($code -ne "") { return $code }
        }
    } catch { }
    return "unknown（该启动方式不提供退出码，以日志尾部为准）"
}

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

# 4. 注入 .env 到当前进程环境，由子进程继承
foreach ($k in $envMap.Keys) {
    Set-Item -Path ("Env:" + $k) -Value ([string]$envMap[$k])
}
Write-Host ("已从 .env 注入 " + $envMap.Count + " 个环境变量")

# 5. 启动（日志走 OS 级重定向，不依赖宿主泵流）
Remove-Item $logFile, $errLogFile, $launcherLogFile -ErrorAction SilentlyContinue
$startedAt = Get-Date
# 记下“到底加载了哪份代码”：classpath 条目数 + **最新编译出的 class 文件**时间（目录 mtime 不可靠，
# 它只在直接子文件变动时更新，嵌套编译不会改它）。排查“改了代码却没生效”时先看这里，而不是猜。
$classPathArgument = if ($javaArgs -match '-cp\s+("?[^"]+"?)\s') { $matches[1].Trim('"') } else { "" }
$classPathEntries = if ($classPathArgument) { ($classPathArgument -split ';').Count } else { 0 }
$classesDir = Join-Path $backendDir "target\classes"
$newestClass = if (Test-Path $classesDir) {
    Get-ChildItem -Path $classesDir -Recurse -Filter *.class -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
} else { $null }
$classesStamp = if ($newestClass) { $newestClass.LastWriteTime.ToString("yyyy-MM-dd HH:mm:ss") + " (" + $newestClass.Name + ")" } else { "(missing)" }
$process = $null
Write-Both ("===== start_backend.ps1 launched " + $startedAt.ToString("yyyy-MM-dd HH:mm:ss") + " =====")
Write-Both "[start_backend] java=$javaExe classpathEntries=$classPathEntries target/classes=$classesStamp"

$process = Start-Process -FilePath $javaExe -ArgumentList $javaArgs -WorkingDirectory $backendDir `
    -RedirectStandardOutput $logFile -RedirectStandardError $errLogFile -PassThru -WindowStyle Hidden
Write-Host ""
Write-Host "已启动 java（PID=$($process.Id)），等待就绪（最多 $ReadyTimeoutSeconds 秒）..."
Write-Host "Spring 日志正在写入：$logFile"

# 6. 轮询就绪：主判据是日志里 Spring 自己的启动宣告
$startedMarker = "Started LabexAgentApplication"
$ready = $false
$portListening = $false
for ($i = 0; $i -lt $ReadyTimeoutSeconds; $i++) {
    Start-Sleep -Seconds 1
    if ($process.HasExited) { break }
    if ((Test-Path $logFile) -and (Select-String -Path $logFile -SimpleMatch $startedMarker -Quiet -ErrorAction SilentlyContinue)) {
        $ready = $true
        break
    }
    if (-not $portListening) {
        $portListening = [bool](Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)
    }
}
if (-not $ready -and -not $process.HasExited) {
    $ready = [bool](Select-String -Path $logFile -SimpleMatch $startedMarker -Quiet -ErrorAction SilentlyContinue)
    if (-not $portListening) {
        $portListening = [bool](Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)
    }
}

# 7. 依赖降级探测：captcha 依赖 Redis，非 200 不代表后端不可用，只报告状态码
$captchaStatus = $null
if ($ready) {
    try {
        $resp = Invoke-WebRequest -Uri "http://localhost:8080/api/auth/captcha" -UseBasicParsing -TimeoutSec 3 -ErrorAction Stop
        $captchaStatus = [int]$resp.StatusCode
    } catch {
        $statusCode = $null
        if ($_.Exception.Response -ne $null -and $_.Exception.Response.StatusCode -ne $null) {
            $statusCode = [int]$_.Exception.Response.StatusCode
        }
        $captchaStatus = if ($statusCode) { $statusCode } else { "unreachable" }
    }
}

# 8. 结论：就绪 / 进程已退出（失败） / 超时未确认（不臆断）
if ($process.HasExited) {
    $exitCode = Get-ProcessExitCode $process
    $tail = if (Test-Path $logFile) { (Get-Content $logFile -Tail 15) -join "`r`n" } else { "(无输出)" }
    [System.IO.File]::AppendAllText($logFile, "[start_backend] 进程已退出，exitCode=$exitCode`r`n")
    Write-Warning "java 进程已退出，exitCode=$exitCode"
    Write-Host "日志尾部（$logFile）："
    Write-Host $tail
    exit 1
}

if ($ready) {
    Write-Both "[start_backend] 就绪（Spring 已宣告 $startedMarker），PID=$($process.Id)"
    Write-Host ""
    Write-Host "后端已就绪：http://localhost:8080/api   （PID=$($process.Id)）"
    if ($captchaStatus -ne 200) {
        Write-Warning "依赖降级：GET /api/auth/captcha 返回 $captchaStatus（该端点依赖 Redis）。后端本身可用，但验证码/登录相关功能可能不可用。"
        Write-Both "[start_backend] 警告：captcha=$captchaStatus（Redis 依赖降级），后端已就绪但相关功能可能不可用"
    }
    Write-Host ""
    Write-Host "  此窗口请保持打开 —— 关闭窗口即停止后端（日志由 OS 直接写文件，不受影响）。"
    Write-Host "  前端地址：http://localhost:3000"
    Write-Host "  完整日志：$logFile"
    Write-Host ""
} else {
    # 除“已退出”外不轻易断言失败：可能只是初始化慢，端口已在监听。
    $hint = if ($portListening) { "8080 已在监听，应用可能仍在初始化" } else { "8080 尚未监听" }
    Write-Both "[start_backend] 就绪超时（$ReadyTimeoutSeconds 秒），PID=$($process.Id)，$hint"
    Write-Warning "就绪超时（PID=$($process.Id)，$hint）—— 未确认就绪，请查看 $logFile"
    exit 2
}

$process.WaitForExit()
$finalExitCode = Get-ProcessExitCode $process
[System.IO.File]::AppendAllText($logFile, "[start_backend] 进程结束，exitCode=$finalExitCode`r`n")
