[CmdletBinding()]
param()

$ErrorActionPreference = 'Continue'
$RuntimeRoot = Join-Path $env:LOCALAPPDATA 'LabexAgent'

function Stop-ProcessTree([int]$ProcessId) {
    # 仅遍历已记录进程的子进程，避免 npm.cmd 退出后遗留 vite/node 进程。
    Get-CimInstance Win32_Process -Filter "ParentProcessId=$ProcessId" -ErrorAction SilentlyContinue |
        ForEach-Object { Stop-ProcessTree ([int]$_.ProcessId) }
    Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
}

function Stop-RecordedProcess([string]$Name) {
    $pidFile = Join-Path $RuntimeRoot "$Name.pid"
    if (-not (Test-Path -LiteralPath $pidFile)) {
        return
    }
    $raw = (Get-Content -LiteralPath $pidFile -Raw).Trim()
    if ($raw -match '^\d+$') {
        $process = Get-Process -Id ([int]$raw) -ErrorAction SilentlyContinue
        if ($process) {
            Stop-ProcessTree ([int]$process.Id)
            Write-Host "已停止 $Name（PID=$($process.Id)）"
        }
    }
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
}

Stop-RecordedProcess 'cloudflared'
Stop-RecordedProcess 'frontend-preview'
