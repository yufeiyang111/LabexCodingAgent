[CmdletBinding()]
param(
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent $PSScriptRoot
$pom = Join-Path $backendRoot 'pom.xml'

if (-not (Test-Path -LiteralPath $pom)) {
    throw "Backend pom.xml was not found: $pom"
}

Push-Location $backendRoot
try {
    $mavenArgs = @('clean', 'package')
    if ($SkipTests) {
        $mavenArgs += '-DskipTests'
    }

    Write-Host 'Building one fresh backend artifact before startup...'
    & mvn @mavenArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed with exit code $LASTEXITCODE"
    }

    $jar = Get-ChildItem -LiteralPath (Join-Path $backendRoot 'target') -Filter 'labex-agent-backend-*.jar' -File |
        Where-Object { $_.Name -notlike '*.original' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $jar) {
        throw 'No executable backend JAR was produced.'
    }

    Write-Host "Starting freshly built artifact: $($jar.FullName)"
    Write-Host 'Stop any old IDE/Java backend process before using this command; do not hot-swap enum or state-machine changes.'
    & java -jar $jar.FullName
    if ($LASTEXITCODE -ne 0) {
        throw "Backend exited with code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}
