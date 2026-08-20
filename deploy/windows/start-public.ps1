[CmdletBinding()]
param(
    [switch]$Build,
    [int]$FrontendPort = 4173,
    [switch]$SkipTunnel
)

$ErrorActionPreference = 'Stop'

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$FrontendRoot = Join-Path $ProjectRoot 'frontend'
$TemplatePath = Join-Path $PSScriptRoot 'cloudflared.yml.template'
$RuntimeRoot = Join-Path $env:LOCALAPPDATA 'LabexAgent'
$LogRoot = Join-Path $RuntimeRoot 'logs'
$RuntimeConfig = Join-Path $RuntimeRoot 'cloudflared.yml'
$PreviewPidFile = Join-Path $RuntimeRoot 'frontend-preview.pid'
$TunnelPidFile = Join-Path $RuntimeRoot 'cloudflared.pid'
$TunnelName = 'labex'
$TunnelId = '5c191e4a-3fb0-4ed4-817a-959bc401bb6b'
$Cloudflared = 'C:\Program Files (x86)\cloudflared\cloudflared.exe'
$CredentialPath = Join-Path $env:USERPROFILE ".cloudflared\$TunnelId.json"

function Write-Info([string]$Message) {
    Write-Host "[LabexAgent] $Message"
}

function Assert-Command([string]$Command) {
    if (-not (Get-Command $Command -ErrorAction SilentlyContinue)) {
        throw "找不到命令：$Command"
    }
}

function Test-ProcessPid([string]$PidFile) {
    if (-not (Test-Path -LiteralPath $PidFile)) {
        return $null
    }
    $raw = (Get-Content -LiteralPath $PidFile -Raw).Trim()
    if ($raw -notmatch '^\d+$') {
        return $null
    }
    $process = Get-Process -Id ([int]$raw) -ErrorAction SilentlyContinue
    if ($process -and -not $process.HasExited) {
        return $process
    }
    Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
    return $null
}

function Wait-Http([string]$Url, [int]$TimeoutSeconds = 30) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $response = Invoke-WebRequest -Uri $Url -Method Get -TimeoutSec 3 -UseBasicParsing
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) {
                return
            }
        } catch {
            # 服务启动期间连接失败属于预期状态。
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "本地服务未在 ${TimeoutSeconds}s 内就绪：$Url"
}

Assert-Command 'npm'
if (-not (Test-Path -LiteralPath $Cloudflared)) {
    throw "找不到 cloudflared：$Cloudflared"
}
if (-not (Test-Path -LiteralPath $CredentialPath)) {
    throw "找不到 Tunnel 凭据文件：$CredentialPath；请先在本机执行 cloudflared login 或恢复原有 tunnel 凭据。"
}
if (-not (Test-Path -LiteralPath $TemplatePath)) {
    throw "缺少 Tunnel 模板：$TemplatePath"
}

New-Item -ItemType Directory -Force $RuntimeRoot, $LogRoot | Out-Null

try {
    Write-Info '检查后端 API（127.0.0.1:8080）'
    Wait-Http 'http://127.0.0.1:8080/api/auth/oauth/providers' 15

    if ($Build) {
        Write-Info '构建前端 dist'
        Push-Location $FrontendRoot
        try {
            & npm.cmd run build
            if ($LASTEXITCODE -ne 0) {
                throw "前端构建失败，退出码：$LASTEXITCODE"
            }
        } finally {
            Pop-Location
        }
    }

    $preview = Test-ProcessPid $PreviewPidFile
    if (-not $preview) {
        Write-Info "启动本地生产前端（127.0.0.1:$FrontendPort）"
        $previewOutLog = Join-Path $LogRoot 'frontend-preview.out.log'
        $previewErrLog = Join-Path $LogRoot 'frontend-preview.err.log'
        $preview = Start-Process -FilePath 'npm.cmd' `
            -ArgumentList @('run', 'preview', '--', '--host', '127.0.0.1', '--port', "$FrontendPort") `
            -WorkingDirectory $FrontendRoot `
            -RedirectStandardOutput $previewOutLog `
            -RedirectStandardError $previewErrLog `
            -WindowStyle Hidden `
            -PassThru
        Set-Content -LiteralPath $PreviewPidFile -Value $preview.Id -NoNewline
    } else {
        Write-Info "复用已运行的前端进程（PID=$($preview.Id)）"
    }
    Wait-Http "http://127.0.0.1:$FrontendPort/login" 30

    $credentialForYaml = $CredentialPath.Replace("'", "''")
    $config = Get-Content -LiteralPath $TemplatePath -Raw -Encoding UTF8
    $config = $config.Replace('__CREDENTIALS_FILE__', $credentialForYaml)
    $config = $config.Replace('__FRONTEND_PORT__', "$FrontendPort")
    [IO.File]::WriteAllText($RuntimeConfig, $config, (New-Object Text.UTF8Encoding($false)))

    Write-Info '校验 Tunnel ingress'
    & $Cloudflared tunnel --config $RuntimeConfig ingress validate
    if ($LASTEXITCODE -ne 0) {
        throw "Tunnel ingress 校验失败，退出码：$LASTEXITCODE"
    }

    if ($SkipTunnel) {
        Write-Info '已跳过 Tunnel 启动；本地生产前端和 ingress 规则验证完成。'
        return
    }

    $tunnel = Test-ProcessPid $TunnelPidFile
    if (-not $tunnel) {
        Write-Info "启动 Cloudflare Tunnel（$TunnelName）"
        $tunnelOutLog = Join-Path $LogRoot 'cloudflared.out.log'
        $tunnelErrLog = Join-Path $LogRoot 'cloudflared.err.log'
        $tunnel = Start-Process -FilePath $Cloudflared `
            -ArgumentList @('tunnel', '--config', $RuntimeConfig, 'run', $TunnelName) `
            -WorkingDirectory $RuntimeRoot `
            -RedirectStandardOutput $tunnelOutLog `
            -RedirectStandardError $tunnelErrLog `
            -WindowStyle Hidden `
            -PassThru
        Set-Content -LiteralPath $TunnelPidFile -Value $tunnel.Id -NoNewline
    } else {
        Write-Info "复用已运行的 Tunnel 进程（PID=$($tunnel.Id)）"
    }

    Write-Info '本地入口已启动： https://labexagent.123845.xyz'
    Write-Info '下一步：在 Cloudflare Access 为该 hostname 添加邮箱 Allow 策略。'
} catch {
    Write-Error $_
    exit 1
}
