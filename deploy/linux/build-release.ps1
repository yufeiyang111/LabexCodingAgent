param(
    [string]$Tag = "2026-08-19",
    [switch]$SkipDependencyInstall
)

$ErrorActionPreference = "Stop"
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$ReleaseRoot = Join-Path $ProjectRoot "deploy/linux/release/$Tag"
$BackendImage = "labex-agent-backend:$Tag"
$WorkerImage = "labex-agent-sandbox:$Tag"

function Assert-Command([string]$Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "找不到命令：$Name"
    }
}

Assert-Command "mvn"
Assert-Command "npm"
Assert-Command "docker"

docker info | Out-Null

if (-not $SkipDependencyInstall) {
    Push-Location (Join-Path $ProjectRoot "frontend")
    try { npm ci } finally { Pop-Location }
}

Push-Location (Join-Path $ProjectRoot "backend")
try { mvn clean package -DskipTests } finally { Pop-Location }

Push-Location (Join-Path $ProjectRoot "frontend")
try { npm run build } finally { Pop-Location }

$jar = Get-ChildItem (Join-Path $ProjectRoot "backend/target") -Filter "*.jar" -File |
    Where-Object { $_.Name -notmatch "^original-" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if ($null -eq $jar) {
    throw "backend/target 中没有找到后端 JAR"
}

New-Item -ItemType Directory -Force -Path $ReleaseRoot | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $ReleaseRoot "frontend-dist") | Out-Null
Copy-Item (Join-Path $ProjectRoot "frontend/dist/*") (Join-Path $ReleaseRoot "frontend-dist") -Recurse -Force
Copy-Item (Join-Path $ProjectRoot "deploy/linux/docker-compose.yml") $ReleaseRoot -Force
Copy-Item (Join-Path $ProjectRoot "deploy/linux/Caddyfile") $ReleaseRoot -Force
Copy-Item (Join-Path $ProjectRoot "deploy/linux/.env.production.example") $ReleaseRoot -Force
Copy-Item (Join-Path $ProjectRoot "deploy/linux/init-host.sh") $ReleaseRoot -Force
Copy-Item (Join-Path $ProjectRoot "deploy/linux/import-database.sh") $ReleaseRoot -Force
Copy-Item (Join-Path $ProjectRoot "deploy/linux/migrate-paths.sh") $ReleaseRoot -Force

docker build -t $WorkerImage -f (Join-Path $ProjectRoot "docker/sandbox/Dockerfile") $ProjectRoot
docker build -t $BackendImage -f (Join-Path $ProjectRoot "deploy/linux/backend.Dockerfile") (Join-Path $ProjectRoot "backend/target")

docker save -o (Join-Path $ReleaseRoot "worker-image.tar") $WorkerImage
docker save -o (Join-Path $ReleaseRoot "backend-image.tar") $BackendImage

@"
LABEX_AGENT_PUBLIC_HOST=labexagent.123845.xyz
LABEX_AGENT_BACKEND_IMAGE=$BackendImage
LABEX_AGENT_WORKER_DOCKER_IMAGE=$WorkerImage
"@ | Set-Content (Join-Path $ReleaseRoot "release-images.env") -Encoding ascii

Write-Host "发布包已生成：$ReleaseRoot"
Write-Host "不要把 .env.production、数据库密码或 OAuth Secret 放进这个目录。"
