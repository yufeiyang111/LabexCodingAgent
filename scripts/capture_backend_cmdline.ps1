# 捕获当前占用 8080 的 java 进程启动命令行，写入 backend/current_cmdline.txt。
# start_backend.ps1 依赖该文件复现"原来的启动方式"；换了启动方式（IDE、不同 classpath）后要重新捕获。
$ErrorActionPreference = "Stop"

$conn = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
if (-not $conn) { throw "8080 端口没有监听进程，无法捕获启动命令" }

$victim = $conn | Select-Object -ExpandProperty OwningProcess -Unique | Select-Object -First 1
$proc = Get-CimInstance Win32_Process -Filter "ProcessId=$victim" -ErrorAction SilentlyContinue
if (-not $proc -or -not $proc.CommandLine) {
    throw "无法读取 PID $victim 的命令行（权限不足或进程已退出）"
}

$out = "D:\LabexAgent\backend\current_cmdline.txt"
$proc.CommandLine | Set-Content -Path $out -Encoding ASCII -NoNewline
Write-Host "已写入 $out"
Write-Host "  PID=$victim  长度=$($proc.CommandLine.Length) 字符"
