[CmdletBinding()]
param(
    [int]$BackendPort = 18080,
    [string]$JarPath = '',
    [int]$TimeoutSeconds = 90,
    [switch]$SkipNormalProfileCheck
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $candidate = Get-ChildItem -LiteralPath (Join-Path $repoRoot 'backend\target') -Filter 'labex-agent-backend-*.jar' -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '\.original$' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $candidate) { throw '未找到后端 JAR，请先运行 backend\mvn -q -DskipTests package。' }
    $JarPath = $candidate.FullName
}
$JarPath = (Resolve-Path -LiteralPath $JarPath).Path
$baseUrl = "http://127.0.0.1:$BackendPort/api"
$runId = [Guid]::NewGuid().ToString('N')
$logRoot = Join-Path ([IO.Path]::GetTempPath()) "labex-agent-acceptance-$runId"
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
$workspaceRoot = Join-Path $repoRoot "workspaces\acceptance-$runId"
$databaseRoot = Join-Path $workspaceRoot '.acceptance-db'
$h2SchemaPath = Join-Path $databaseRoot 'schema-h2.sql'
$h2JarPath = Join-Path $databaseRoot 'h2.jar'
$h2ServerProcess = $null
$h2PortProbe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$h2PortProbe.Start()
$h2ServerPort = ([Net.IPEndPoint]$h2PortProbe.LocalEndpoint).Port
$h2PortProbe.Stop()
$h2JdbcUrl = "jdbc:h2:tcp://127.0.0.1:$h2ServerPort/./labex-agent;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_ON_EXIT=FALSE"
New-Item -ItemType Directory -Force -Path $databaseRoot | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$backendArchive = [IO.Compression.ZipFile]::OpenRead($JarPath)
try {
    $h2Entry = $backendArchive.Entries | Where-Object { $_.FullName -like 'BOOT-INF/lib/h2-*.jar' } | Select-Object -First 1
    if (-not $h2Entry) { throw 'Packaged backend does not contain the H2 runtime.' }
    [IO.Compression.ZipFileExtensions]::ExtractToFile($h2Entry, $h2JarPath, $true)
} finally {
    $backendArchive.Dispose()
}
$schemaSource = Join-Path $repoRoot 'backend\src\main\resources\sql\schema.sql'
$schemaText = [IO.File]::ReadAllText($schemaSource, [Text.Encoding]::UTF8)
$mysqlPrefixIndex = 'pattern(191)'
if (-not $schemaText.Contains($mysqlPrefixIndex)) {
    throw 'MySQL schema compatibility marker pattern(191) was not found.'
}
[IO.File]::WriteAllText($h2SchemaPath, $schemaText.Replace($mysqlPrefixIndex, 'pattern'), [Text.UTF8Encoding]::new($false))
$backendProcess = $null
$token = $null
$projectId = $null
$configId = $null
$compactionConfigId = $null
$smallConfigId = $null
$unconfiguredConfigId = $null
$evidence = [ordered]@{
    runId = $runId
    backendPort = $BackendPort
    questionRestart = $false
    permissionRestart = $false
    commandApproveRestart = $false
    commandRejectRestart = $false
    approvedCommandCancellation = $false
    approvedCommandCancellationElapsedMs = $null
    approvedCommandRestartRecovery = $false
    approvedCommandRestartProcessId = $null
    checkoutContention = $false
    runMessagePartProjection = $false
    toolPartAuthority = $false
    manualCompaction = $false
    manualCompactionAuthority = $false
    manualCompactionSourceMaxTaskId = $null
    manualCompactionExecutionEpoch = $null
    compactionEpochRecovery = $false
    compactionCancellationTerminal = $false
    compactionCancellationElapsedMs = $null
    staticContextBlocked = $false
    contextWindowUnconfigured = $false
    completionEvidence = $false
    unverifiedEditRejected = $false
    normalProfileProviderIsolation = $false
    lifecycleTransitionAudit = $false
    singleAuthoritativeTerminalEvent = $false
    authoritativeFailureProjection = $false
    authoritativeCancellationProjection = $false
    authoritativeModelRetryProjection = $false
    outboxTranscriptRepair = $false
    outboxSequenceFence = $false
    strictTextToolFallback = $false
    nativeToolInputGate = $false
    isolatedDatabase = $false
    durableForkBoundary = $false
    durableForkRestart = $false
    durableForkCompaction = $false
    durableForkParentTaskId = $null
    durableForkChildSourceMaxTaskId = $null
    legacyHistoryMigration = $false
    legacyHistoryMigrationRestart = $false
    legacyHistoryMigrationTaskId = $null
    cleanup = $false
}

function Test-PortFree {
    param([int]$Port)
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
    try {
        $listener.Start()
        return $true
    } catch [Net.Sockets.SocketException] {
        return $false
    } finally {
        try { $listener.Stop() } catch { }
    }
}

function Assert-PortFree {
    param([int]$Port)
    if (-not (Test-PortFree -Port $Port)) {
        throw "验收端口 $Port 已被其他进程占用，脚本拒绝接管。"
    }
}

function Start-AcceptanceDatabase {
    Assert-PortFree -Port $h2ServerPort
    $stdout = Join-Path $logRoot 'h2-server-out.log'
    $stderr = Join-Path $logRoot 'h2-server-err.log'
    $arguments = @(
        '-cp', $h2JarPath, 'org.h2.tools.Server', '-tcp', '-tcpPort', [string]$h2ServerPort,
        '-baseDir', $databaseRoot, '-ifNotExists'
    )
    $script:h2ServerProcess = Start-Process -FilePath 'java' -ArgumentList $arguments -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($script:h2ServerProcess.HasExited) {
            throw "Isolated H2 server exited early, exitCode=$($script:h2ServerProcess.ExitCode), logs=$logRoot"
        }
        if (-not (Test-PortFree -Port $h2ServerPort)) { return }
        Start-Sleep -Milliseconds 150
    }
    throw "Isolated H2 server did not listen on port $h2ServerPort within 20 seconds."
}

function Invoke-AcceptanceSql {
    param([Parameter(Mandatory)][string]$Sql)
    $previousErrorAction = $ErrorActionPreference
    try {
        # Windows PowerShell 5 会把 Java 的普通 stderr 提示包装成 ErrorRecord；真实成败只看进程退出码。
        $ErrorActionPreference = 'Continue'
        $output = @(& java -cp $h2JarPath org.h2.tools.Shell -url $h2JdbcUrl -user sa -sql $Sql 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    if ($exitCode -ne 0) {
        throw "Acceptance SQL failed with exitCode=$exitCode`n$($output -join "`n")"
    }
    return $output
}

function Get-AcceptanceSqlScalar {
    param([Parameter(Mandatory)][string]$Sql)
    $values = @(Invoke-AcceptanceSql -Sql $Sql |
        ForEach-Object { ([string]$_).Trim() } |
        Where-Object { $_ -match '^-?\d+$' })
    if ($values.Count -eq 0) {
        throw "Acceptance SQL returned no integer scalar for query: $Sql"
    }
    return [long]$values[0]
}

function Stop-AcceptanceDatabase {
    $server = $script:h2ServerProcess
    $script:h2ServerProcess = $null
    if (-not $server) { return }
    $server.Refresh()
    if (-not $server.HasExited) {
        Stop-Process -Id $server.Id -Force
        $server.WaitForExit(10000) | Out-Null
    }
}

function Remove-OwnedWorkspace {
    $workspaceBase = [IO.Path]::GetFullPath((Join-Path $repoRoot 'workspaces'))
    $resolved = [IO.Path]::GetFullPath($workspaceRoot)
    if ([IO.Path]::GetDirectoryName($resolved) -ne $workspaceBase -or
        -not [IO.Path]::GetFileName($resolved).StartsWith('acceptance-', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean a non-acceptance workspace: $resolved"
    }
    if ([IO.Directory]::Exists($resolved)) {
        [IO.Directory]::Delete($resolved, $true)
    }
}

function Start-AcceptanceBackend {
    param([string]$Profile)
    Assert-PortFree -Port $BackendPort
    $stdout = Join-Path $logRoot "$Profile-out.log"
    $stderr = Join-Path $logRoot "$Profile-err.log"
    $arguments = @(
        '-Dfile.encoding=UTF-8',
        '-Dlabex.acceptance.hold.ms=12000',
        '-Dlabex.acceptance.compaction.hold.ms=12000',
        '-jar', $JarPath,
        "--server.port=$BackendPort",
        "--spring.profiles.active=$Profile",
        "--labex-agent.project-base-path=$workspaceRoot",
        "--labex-agent.instance-id=acceptance-$runId-$Profile",
        "--labex-agent.process-host-id=acceptance-host-$runId",
        '--labex-agent.execution-lease-duration-ms=15000',
        '--labex-agent.execution-heartbeat-interval-ms=1000',
        '--spring.datasource.driver-class-name=org.h2.Driver',
        "--spring.datasource.url=$h2JdbcUrl",
        '--spring.datasource.username=sa',
        '--spring.datasource.password=',
        "--spring.sql.init.schema-locations=file:$($h2SchemaPath.Replace('\', '/'))"
    )
    $script:backendProcess = Start-Process -FilePath 'java' -ArgumentList $arguments -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($script:backendProcess.HasExited) {
            throw "后端进程提前退出，exitCode=$($script:backendProcess.ExitCode)，日志目录：$logRoot"
        }
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/auth/login" -Method Post -ContentType 'application/json' -Body '{}' -TimeoutSec 3
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) { return }
        } catch {
            Start-Sleep -Milliseconds 350
        }
    }
    throw "后端在 $TimeoutSeconds 秒内未就绪，日志目录：$logRoot"
}

function Test-ProcessAlive {
    param([Parameter(Mandatory)][long]$ProcessId)
    return $null -ne (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)
}

function Stop-AcceptanceBackend {
    if ($script:backendProcess -and -not $script:backendProcess.HasExited) {
        Stop-Process -Id $script:backendProcess.Id -Force
        $script:backendProcess.WaitForExit(10000) | Out-Null
    }
    $script:backendProcess = $null
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (Test-PortFree -Port $BackendPort) { return }
        Start-Sleep -Milliseconds 200
    }
    throw "停止自有后端后端口 $BackendPort 仍未释放。"
}

function Get-Headers {
    if ([string]::IsNullOrWhiteSpace($script:token)) { return @{} }
    return @{ Authorization = "Bearer $($script:token)" }
}

function Invoke-ApiData {
    param(
        [Parameter(Mandatory)][string]$Path,
        [ValidateSet('GET','POST','PUT','DELETE')][string]$Method = 'GET',
        [object]$Body = $null,
        [switch]$Anonymous
    )
    $parameters = @{
        Uri = "$baseUrl$Path"
        Method = $Method
        TimeoutSec = 30
    }
    if (-not $Anonymous) { $parameters.Headers = Get-Headers }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json'
        $parameters.Body = ($Body | ConvertTo-Json -Depth 20 -Compress)
    }
    $response = Invoke-RestMethod @parameters
    if ($null -eq $response -or $response.code -ne 0) {
        $message = if ($response) { $response.message } else { 'empty response' }
        throw "API $Method $Path 失败：$message"
    }
    return $response.data
}

function ConvertFrom-AgentSse {
    param([string]$Text)
    $normalized = $(if ($null -eq $Text) { '' } else { $Text }) -replace "`r`n", "`n" -replace "`r", "`n"
    $events = [Collections.Generic.List[object]]::new()
    foreach ($frame in [regex]::Split($normalized, "`n`n+")) {
        if ([string]::IsNullOrWhiteSpace($frame)) { continue }
        $eventId = $null
        $eventName = $null
        $dataLines = [Collections.Generic.List[string]]::new()
        foreach ($line in ($frame -split "`n")) {
            if ($line.StartsWith(':')) { continue }
            if ($line.StartsWith('id:')) { $eventId = $line.Substring(3).Trim(); continue }
            if ($line.StartsWith('event:')) { $eventName = $line.Substring(6).Trim(); continue }
            if ($line.StartsWith('data:')) { $dataLines.Add($line.Substring(5).TrimStart()) }
        }
        if ($dataLines.Count -eq 0) { continue }
        try {
            $event = (($dataLines -join "`n") | ConvertFrom-Json)
            if ($eventId) { Add-Member -InputObject $event -NotePropertyName eventId -NotePropertyValue $eventId -Force }
            if ($eventName) { Add-Member -InputObject $event -NotePropertyName sseEvent -NotePropertyValue $eventName -Force }
            $events.Add($event)
        } catch {
            throw "SSE JSON 解析失败（eventId=$eventId）：$($_.Exception.Message)"
        }
    }
    return @($events)
}

function Invoke-AgentStream {
    param([string]$Message, [string]$ConversationId = '', [switch]$BackgroundRun)
    $payload = [ordered]@{
        sessionId = [Guid]::NewGuid().ToString()
        conversationId = $ConversationId
        mode = 'build'
        message = $Message
        activePath = ''
        modelConfigId = $script:configId
        backgroundRun = [bool]$BackgroundRun
    }
    $response = Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/student/projects/$projectId/agent/stream" -Method Post -Headers (Get-Headers) -ContentType 'application/json' -Body ($payload | ConvertTo-Json -Compress) -TimeoutSec $TimeoutSeconds
    return ConvertFrom-AgentSse -Text $response.Content
}

function Get-NextContextPreviewSectionContent {
    param(
        [Parameter(Mandatory)][string]$ConversationId,
        [string]$Key = 'compactedContext'
    )
    $preview = Invoke-ApiData -Path "/student/projects/$projectId/agent/conversations/$ConversationId/context-preview" -Method POST -Body @{
        modelConfigId = $script:configId
        activePath = ''
        agentMode = 'build'
        draftMessage = ''
    }
    $section = @($preview.previewSections | Where-Object { [string]$_.key -eq $Key }) | Select-Object -First 1
    if (-not $section) {
        throw "Context preview for conversation $ConversationId did not expose section $Key."
    }
    if ([bool]$section.truncated) {
        throw "Context preview section $Key for conversation $ConversationId was truncated; isolation evidence would be incomplete."
    }
    return [string]$section.content
}

function Get-RequiredEvent {
    param([object[]]$Events, [string]$Type)
    $event = $Events | Where-Object { $_.type -eq $Type } | Select-Object -First 1
    if (-not $event) {
        $types = ($Events | ForEach-Object { $_.type }) -join ', '
        throw "缺少事件 $Type；实际事件：$types"
    }
    return $event
}

function Wait-TaskTerminal {
    param([long]$TaskId, [int]$Seconds = 60)
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        try {
            $task = Invoke-ApiData -Path "/student/projects/$projectId/agent/tasks/$TaskId"
        } catch {
            $task = $null
        }
        if ($task -and $task.status -in @('completed','failed','cancelled','timeout')) { return $task }
        Start-Sleep -Milliseconds 400
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "任务 $TaskId 在 $Seconds 秒内未进入终态。"
}

function Wait-TaskBySession {
    param([string]$SessionId, [string[]]$Statuses = @(), [int]$Seconds = 30)
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        $tasks = @(Invoke-ApiData -Path "/student/projects/$projectId/agent/tasks")
        $task = $tasks | Where-Object { [string]$_.sessionId -eq $SessionId } | Select-Object -First 1
        if ($task -and ($Statuses.Count -eq 0 -or $task.status -in $Statuses)) { return $task }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Session $SessionId did not reach the expected task state within $Seconds seconds."
}

function Wait-TaskPendingInteraction {
    param(
        [long]$TaskId,
        [string]$PreviousRequestId = '',
        [int]$Seconds = 60
    )
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        $task = Invoke-ApiData -Path "/student/projects/$projectId/agent/tasks/$TaskId"
        $pendingProperty = $task.PSObject.Properties['pendingInteraction']
        $pending = if ($pendingProperty) { $pendingProperty.Value } else { $null }
        if ($task.status -in @('completed','failed','cancelled','timeout')) {
            throw "Task $TaskId reached terminal state $($task.status) before the expected interaction."
        }
        if ($task.status -eq 'waiting_user' -and $pending -and
            [string]$pending.requestId -ne $PreviousRequestId) {
            return $task
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Task $TaskId did not expose a new waiting_user interaction within $Seconds seconds."
}

function Wait-TaskCompactionState {
    param([long]$TaskId, [string]$Status, [int]$Seconds = 60)
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        $task = Invoke-ApiData -Path "/student/projects/$projectId/agent/tasks/$TaskId"
        $compactionsProperty = $task.PSObject.Properties['compactions']
        $compactions = if ($compactionsProperty) { @($compactionsProperty.Value) } else { @() }
        $matching = @($compactions | Where-Object { [string]$_.status -eq $Status } |
            Sort-Object { [long]$_.compactionEpoch })
        if ($matching.Count -gt 0) {
            return [pscustomobject]@{ task = $task; compaction = $matching[-1] }
        }
        if ($task.status -in @('completed','failed','cancelled','timeout')) {
            throw "Task $TaskId reached terminal state $($task.status) before compaction became $Status."
        }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Task $TaskId did not expose a $Status compaction within $Seconds seconds."
}

function Get-TaskEvents {
    param([long]$TaskId)
    $response = Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/student/projects/$projectId/agent/tasks/$TaskId/events?lastEventId=0" -Headers (Get-Headers) -TimeoutSec 20
    return ConvertFrom-AgentSse -Text $response.Content
}

function Assert-SameTaskContinuation {
    param([long]$TaskId, [string]$RequiredType)
    $task = Wait-TaskTerminal -TaskId $TaskId
    if ($task.status -ne 'completed') { throw "任务 $TaskId 续跑后状态为 $($task.status)，不是 completed。" }
    $events = Get-TaskEvents -TaskId $TaskId
    Get-RequiredEvent -Events $events -Type $RequiredType | Out-Null
    if (-not ($events | Where-Object { $_.type -in @('DONE','RUN_STATE_COMPLETED') })) {
        throw "任务 $TaskId 没有可回放的完成事件。"
    }
}

function Restart-AcceptanceBackend {
    Stop-AcceptanceBackend
    Start-AcceptanceBackend -Profile 'acceptance,local'
}

try {
    Start-AcceptanceDatabase
    Start-AcceptanceBackend -Profile 'acceptance,local'
    $evidence.isolatedDatabase = $true

    $username = "accept_$($runId.Substring(0, 16))"
    $password = "A!$([Guid]::NewGuid().ToString('N'))z9"
    $registered = Invoke-ApiData -Path '/auth/register' -Method POST -Anonymous -Body @{
        username = $username; password = $password; displayName = 'Acceptance User'
    }
    $token = [string]$registered.token
    if ([string]::IsNullOrWhiteSpace($token)) { throw '注册响应未返回 JWT。' }

    $project = Invoke-ApiData -Path '/student/projects/empty' -Method POST -Body @{ projectName = "acceptance-$($runId.Substring(0, 8))" }
    $projectId = [int]$project.projectId
    Invoke-ApiData -Path "/student/projects/$projectId/files/item" -Method POST -Body @{
        parentPath = ''
        name = '.labex-acceptance-command-hold.cjs'
        type = 'file'
    } | Out-Null
    Invoke-ApiData -Path "/student/projects/$projectId/files?path=.labex-acceptance-command-hold.cjs" -Method PUT -Body @{
        content = "setTimeout(() => {}, 40000)`n"
    } | Out-Null
    $config = Invoke-ApiData -Path '/student/model-configs' -Method POST -Body @{
        configName = 'Acceptance Scripted'; provider = 'acceptance_scripted'; modelName = 'acceptance-only'
        apiKey = 'acceptance-placeholder-not-a-secret'; baseUrl = 'acceptance://scripted'; maxTokens = 4096; contextWindowTokens = 32768
        temperature = 0.0; isDefault = $true; promptCacheKeyEnabled = $false
        compactionTailTurns = 2; compactionPreserveRecentTokens = 4000
        reasoningEffort = 'medium'; imageInputEnabled = $false
    }
    $configId = [int]$config.configId

    $originalConfigId = $configId
    $unconfiguredConfig = Invoke-ApiData -Path '/student/model-configs' -Method POST -Body @{
        configName = 'Acceptance Unconfigured Context'; provider = 'acceptance_scripted'; modelName = 'acceptance-unconfigured'
        apiKey = 'acceptance-placeholder-not-a-secret'; baseUrl = 'acceptance://scripted'; maxTokens = 4096
        temperature = 0.0; isDefault = $false; promptCacheKeyEnabled = $false
        reasoningEffort = 'medium'; imageInputEnabled = $false
    }
    $unconfiguredConfigId = [int]$unconfiguredConfig.configId
    $configId = $unconfiguredConfigId
    $unconfiguredEvents = Invoke-AgentStream -Message '[acceptance:unconfigured-context] provider must not run'
    $unconfiguredBlock = Get-RequiredEvent -Events $unconfiguredEvents -Type 'CONTEXT_LIMIT_BLOCKED'
    if ($unconfiguredBlock.data.reasonCode -ne 'context_window_unconfigured') {
        throw "Unexpected unconfigured context reason: $($unconfiguredBlock.data.reasonCode)"
    }
    if ($unconfiguredEvents | Where-Object { $_.type -eq 'THINK_DELTA' -and [string]$_.data.delta -like '*Acceptance runtime scenario selected*' }) {
        throw 'Provider was invoked when contextWindowTokens was unconfigured.'
    }
    $evidence.contextWindowUnconfigured = $true
    $configId = $originalConfigId
    Invoke-ApiData -Path "/student/model-configs/$unconfiguredConfigId" -Method DELETE | Out-Null
    $unconfiguredConfigId = $null
    $smallConfig = Invoke-ApiData -Path '/student/model-configs' -Method POST -Body @{
        configName = 'Acceptance Tiny Context'; provider = 'acceptance_scripted'; modelName = 'acceptance-tiny'
        apiKey = 'acceptance-placeholder-not-a-secret'; baseUrl = 'acceptance://scripted'; maxTokens = 1000; contextWindowTokens = 1024
        temperature = 0.0; isDefault = $false; promptCacheKeyEnabled = $false
        reasoningEffort = 'medium'; imageInputEnabled = $false
    }
    $smallConfigId = [int]$smallConfig.configId
    $configId = $smallConfigId
    $blockedEvents = Invoke-AgentStream -Message '[acceptance:static-context] provider must not run'
    $blocked = Get-RequiredEvent -Events $blockedEvents -Type 'CONTEXT_LIMIT_BLOCKED'
    if ($blockedEvents | Where-Object { $_.type -eq 'THINK_DELTA' -and [string]$_.data.delta -like '*Acceptance runtime scenario selected*' }) { throw 'Provider was invoked after static context admission blocked the request.' }
    if ($blocked.data.reasonCode -notlike 'static_context_*') { throw "Unexpected static context blocker reason: $($blocked.data.reasonCode)" }
    $evidence.staticContextBlocked = $true
    $configId = $originalConfigId
    Invoke-ApiData -Path "/student/model-configs/$smallConfigId" -Method DELETE | Out-Null
    $smallConfigId = $null

    $textFallbackEvents = Invoke-AgentStream -Message '[acceptance:text-tool-fallback] strict envelope execution'
    $textFallbackVisible = (($textFallbackEvents | Where-Object { $_.type -eq 'FINAL_DELTA' } |
        ForEach-Object { [string]$_.data.delta }) -join '')
    if ($textFallbackVisible -match '(?i)<\s*(?:minimax:)?(?:tool_call|invoke)\b') {
        throw 'Explicit text tool-call envelope leaked into visible FINAL_DELTA projection.'
    }
    $textFallbackCall = Get-RequiredEvent -Events $textFallbackEvents -Type 'TOOL_CALL'
    if ([string]$textFallbackCall.data.tool -ne 'list_files') {
        throw "Strict text fallback executed the wrong tool: $($textFallbackCall.data.tool)"
    }
    if ([string]$textFallbackCall.data.toolCallId -notlike 'recovered:v1:*') {
        throw "Strict text fallback did not use a deterministic recovered identity: $($textFallbackCall.data.toolCallId)"
    }
    if ($textFallbackEvents | Where-Object { $_.type -in @('COMMAND_APPROVAL_REQUIRED', 'PERMISSION_ASK') }) {
        throw 'Read-only strict text fallback unexpectedly requested approval.'
    }
    $textFallbackObserve = $textFallbackEvents | Where-Object {
        $_.type -eq 'OBSERVE' -and [string]$_.data.tool -eq 'list_files' -and [bool]$_.data.success
    } | Select-Object -First 1
    if (-not $textFallbackObserve) {
        throw 'Strict text fallback did not execute list_files through the production tool pipeline.'
    }
    $textFallbackTaskId = [long]$textFallbackCall.data.taskId
    $textFallbackTask = Wait-TaskTerminal -TaskId $textFallbackTaskId
    if ($textFallbackTask.status -ne 'completed') {
        throw "Strict text fallback task ended as $($textFallbackTask.status)."
    }
    $textFallbackSnapshot = Invoke-ApiData -Path "/student/projects/$projectId/agent/tasks/$textFallbackTaskId"
    $textFallbackPart = @($textFallbackSnapshot.parts) | Where-Object {
        [string]$_.partType -eq 'tool' -and
        [string]$_.toolCallId -eq [string]$textFallbackCall.data.toolCallId -and
        [string]$_.tool -eq 'list_files' -and [string]$_.status -eq 'completed'
    } | Select-Object -First 1
    if (-not $textFallbackPart) {
        throw 'Strict text fallback has no completed durable Tool Part with the recovered identity.'
    }
    $evidence.strictTextToolFallback = $true

    $nativeInputEvents = Invoke-AgentStream -Message '[acceptance:native-tool-input] reject malformed native arguments'
    $nativeCalls = @($nativeInputEvents | Where-Object { $_.type -eq 'TOOL_CALL' } |
        Sort-Object { [int]$_.data.toolCallIndex })
    if ($nativeCalls.Count -ne 2) {
        throw "Native input gate scenario expected 2 tool calls, got $($nativeCalls.Count)."
    }
    if ([string]$nativeCalls[0].data.toolCallId -ne 'acceptance-native-invalid' -or
        [string]$nativeCalls[1].data.toolCallId -ne 'acceptance-native-valid') {
        throw 'Native input gate did not preserve original batch identities and order.'
    }
    if ($nativeInputEvents | Where-Object { $_.type -in @('COMMAND_APPROVAL_REQUIRED', 'PERMISSION_ASK') }) {
        throw 'Rejected read-only native input unexpectedly requested approval.'
    }
    $invalidExecution = $nativeInputEvents | Where-Object {
        $_.type -eq 'TOOL_EXECUTION_STARTED' -and
        [string]$_.data.toolCallId -eq 'acceptance-native-invalid'
    } | Select-Object -First 1
    if ($invalidExecution) {
        throw 'Malformed native arguments crossed the admission boundary and started tool execution.'
    }
    $validExecution = $nativeInputEvents | Where-Object {
        $_.type -eq 'TOOL_EXECUTION_STARTED' -and
        [string]$_.data.toolCallId -eq 'acceptance-native-valid'
    } | Select-Object -First 1
    if (-not $validExecution) {
        throw 'The valid call after a malformed native call did not execute.'
    }
    $nativeTaskId = [long]$nativeCalls[0].data.taskId
    $nativeTask = Wait-TaskTerminal -TaskId $nativeTaskId
    if ($nativeTask.status -ne 'completed') {
        throw "Native input gate task ended as $($nativeTask.status)."
    }
    $nativeSnapshot = Invoke-ApiData -Path "/student/projects/$projectId/agent/tasks/$nativeTaskId"
    $invalidPart = @($nativeSnapshot.parts) | Where-Object {
        [string]$_.partType -eq 'tool' -and
        [string]$_.toolCallId -eq 'acceptance-native-invalid'
    } | Select-Object -First 1
    $validPart = @($nativeSnapshot.parts) | Where-Object {
        [string]$_.partType -eq 'tool' -and
        [string]$_.toolCallId -eq 'acceptance-native-valid'
    } | Select-Object -First 1
    if (-not $invalidPart -or [string]$invalidPart.status -ne 'error') {
        throw 'Malformed native arguments did not produce an error Tool Part.'
    }
    if (-not $validPart -or [string]$validPart.status -ne 'completed') {
        throw 'Valid native call did not produce a completed Tool Part.'
    }
    $invalidPartJson = $invalidPart | ConvertTo-Json -Depth 8 -Compress
    if ($invalidPartJson -like '*{\"path\":*') {
        throw 'Malformed raw native arguments leaked into the durable public Tool Part.'
    }
    if ([long]$invalidPart.partId -ge [long]$validPart.partId) {
        throw 'Native tool batch durable insertion order no longer matches the provider toolCallIndex order.'
    }
    $evidence.nativeToolInputGate = $true

    $completionStreamEvents = Invoke-AgentStream -Message '[acceptance:evidence] persist completion evidence'
    $completionTaskId = [long](($completionStreamEvents | Where-Object { $_.data.taskId } | Select-Object -First 1).data.taskId)
    $completionApproval = $completionStreamEvents | Where-Object { $_.type -eq 'COMMAND_APPROVAL_REQUIRED' } | Select-Object -First 1
    if ($completionApproval) {
        $completionApprovalId = [string]$completionApproval.data.approvalId
        $completionTaskId = [long]$completionApproval.data.taskId
        Invoke-ApiData -Path "/student/projects/$projectId/agent/command-approvals/$completionApprovalId/decision" -Method POST -Body @{
            action = 'approve'; decisionIdempotencyKey = [Guid]::NewGuid().ToString()
        } | Out-Null
        Invoke-ApiData -Path "/student/projects/$projectId/agent/command-approvals/$completionApprovalId/execute" -Method POST | Out-Null
        $completionState = Wait-TaskTerminal -TaskId $completionTaskId
        if ($completionState.status -ne 'completed') { throw "Completion evidence task $completionTaskId status is $($completionState.status)." }
    }
    $directCompletedState = $completionStreamEvents | Where-Object { $_.type -eq 'RUN_STATE_COMPLETED' } | Select-Object -First 1
    $directTransition = $null
    if ($directCompletedState -and $directCompletedState.data -and
        $directCompletedState.data.PSObject.Properties['transition']) {
        $directTransition = $directCompletedState.data.transition
    }
    if (-not $completionApproval) {
        if (-not $directCompletedState -or -not $directTransition -or -not $directCompletedState.eventId) {
            throw 'Direct completion stream did not project the authoritative persisted RUN_STATE_COMPLETED event.'
        }
    }
    $completionEvents = Get-TaskEvents -TaskId $completionTaskId
    $completionEvent = Get-RequiredEvent -Events $completionEvents -Type 'COMPLETION_EVIDENCE'
    $durableCompletedStates = @($completionEvents | Where-Object { $_.type -eq 'RUN_STATE_COMPLETED' })
    if ($durableCompletedStates.Count -ne 1) {
        throw "Expected exactly one durable RUN_STATE_COMPLETED event, found $($durableCompletedStates.Count)."
    }
    $completedState = $durableCompletedStates[0]
    if (-not $completedState.data.transition) {
        throw "Durable task events contain no audited RUN_STATE_COMPLETED transition: $(($completionEvents | ForEach-Object { $_.type }) -join ', ')"
    }
    if ($directCompletedState -and [string]$directCompletedState.eventId -ne [string]$completedState.eventId) {
        throw "Direct and durable completion events use different sequence IDs: direct=$($directCompletedState.eventId), durable=$($completedState.eventId)."
    }
    $transition = $completedState.data.transition
    if (-not $transition -or $transition.previousState -ne 'running' -or $transition.nextState -ne 'completed' -or
        $transition.actor -ne 'agent_run_lifecycle' -or $transition.reason -ne 'RUN_STATE_COMPLETED' -or
        -not $transition.stateChanged -or [long]$transition.executionEpoch -lt 1) {
        throw "Completed state event is missing canonical lifecycle transition audit: $($completedState.data | ConvertTo-Json -Compress -Depth 8)"
    }
    $evidence.lifecycleTransitionAudit = $true
    $evidence.singleAuthoritativeTerminalEvent = $true
    if (-not [bool]$completionEvent.data.satisfied) { throw 'Completion evidence was not satisfied.' }
    $completionTypes = @($completionEvents | ForEach-Object { $_.type })
    if ([array]::IndexOf($completionTypes, 'COMPLETION_EVIDENCE') -gt [array]::IndexOf($completionTypes, 'RUN_STATE_COMPLETED')) {
        throw 'COMPLETION_EVIDENCE must precede RUN_STATE_COMPLETED.'
    }
    $persistedEvidence = Invoke-ApiData -Path "/student/projects/$projectId/agent/tasks/$completionTaskId/completion-evidence"
    if (-not [bool]$persistedEvidence.satisfied) { throw 'Completion evidence API did not return satisfied evidence.' }
    $evidence.completionEvidence = $true

    # 人为删除一个已发布生命周期事件的 transcript 投影，再复用原 outbox 验证真实调度器能够幂等修复。
    $repairSequence = [long]$completedState.eventId
    $repairMessageKey = "event:run_state_completed:$repairSequence"
    $repairPartKey = "lifecycle:$repairSequence"
    $beforeRepair = Invoke-ApiData -Path "/student/projects/$projectId/agent/tasks/$completionTaskId"
    $beforeMessages = @($beforeRepair.runMessages | Where-Object { [string]$_.messageKey -eq $repairMessageKey })
    $beforeParts = @($beforeRepair.parts | Where-Object { [string]$_.partKey -eq $repairPartKey })
    if ($beforeMessages.Count -ne 1 -or $beforeParts.Count -ne 1) {
        throw "Transcript repair fixture was not unique before fault injection: messages=$($beforeMessages.Count), parts=$($beforeParts.Count)."
    }
    $repairSql = @"
DELETE FROM t_agent_run_part WHERE task_id = $completionTaskId AND part_key = '$repairPartKey';
DELETE FROM t_agent_run_message WHERE task_id = $completionTaskId AND message_key = '$repairMessageKey';
UPDATE t_agent_run_outbox
SET status = 'pending', attempts = 0, available_time = CURRENT_TIMESTAMP, published_time = NULL
WHERE event_id = (
    SELECT event_id FROM t_agent_run_event
    WHERE task_id = $completionTaskId AND sequence_number = $repairSequence AND event_type = 'RUN_STATE_COMPLETED'
);
"@
    Invoke-AcceptanceSql -Sql $repairSql | Out-Null
    $repairDeadline = [DateTime]::UtcNow.AddSeconds(15)
    $repairedProjection = $null
    while ([DateTime]::UtcNow -lt $repairDeadline) {
        $candidateProjection = Invoke-ApiData -Path "/student/projects/$projectId/agent/tasks/$completionTaskId"
        $candidateMessages = @($candidateProjection.runMessages | Where-Object { [string]$_.messageKey -eq $repairMessageKey })
        $candidateParts = @($candidateProjection.parts | Where-Object { [string]$_.partKey -eq $repairPartKey })
        if ($candidateMessages.Count -eq 1 -and $candidateParts.Count -eq 1 -and
            [string]$candidateParts[0].messageId -eq [string]$candidateMessages[0].messageId) {
            $repairedProjection = $candidateProjection
            break
        }
        Start-Sleep -Milliseconds 250
    }
    if (-not $repairedProjection) {
        throw "Outbox did not rebuild the deleted RunMessage/RunPart projection for task $completionTaskId."
    }
    $evidence.outboxTranscriptRepair = $true

    # 让同一 task 的后续终态事件先到 available_time，验证真实 outbox 调度器仍按 sequence 发布。
    $terminalEventsConsecutive = Get-AcceptanceSqlScalar -Sql @"
SELECT CASE WHEN
    (SELECT MAX(sequence_number) FROM t_agent_run_event
     WHERE task_id = $completionTaskId AND event_type = 'RUN_STATE_COMPLETED') =
    (SELECT MAX(sequence_number) FROM t_agent_run_event
     WHERE task_id = $completionTaskId AND event_type = 'FINAL') + 1
THEN 1 ELSE 0 END;
"@
    if ($terminalEventsConsecutive -ne 1) {
        throw "Outbox ordering fixture requires FINAL immediately before RUN_STATE_COMPLETED for task $completionTaskId."
    }
    $outboxSequenceFaultSql = @"
UPDATE t_agent_run_outbox
SET status = 'pending', attempts = 0,
    available_time = DATEADD('SECOND', 3, CURRENT_TIMESTAMP), published_time = NULL
WHERE event_id = (
    SELECT MAX(event_id) FROM t_agent_run_event
    WHERE task_id = $completionTaskId AND event_type = 'FINAL'
);
UPDATE t_agent_run_outbox
SET status = 'pending', attempts = 0,
    available_time = CURRENT_TIMESTAMP, published_time = NULL
WHERE event_id = (
    SELECT MAX(event_id) FROM t_agent_run_event
    WHERE task_id = $completionTaskId AND event_type = 'RUN_STATE_COMPLETED'
);
"@
    Invoke-AcceptanceSql -Sql $outboxSequenceFaultSql | Out-Null
    $outboxOrderDeadline = [DateTime]::UtcNow.AddSeconds(15)
    $publishedTerminalOutboxes = 0L
    while ([DateTime]::UtcNow -lt $outboxOrderDeadline) {
        $publishedTerminalOutboxes = Get-AcceptanceSqlScalar -Sql @"
SELECT COUNT(*)
FROM t_agent_run_outbox outbox
INNER JOIN t_agent_run_event run_event ON run_event.event_id = outbox.event_id
WHERE run_event.task_id = $completionTaskId
  AND run_event.event_type IN ('FINAL', 'RUN_STATE_COMPLETED')
  AND outbox.status = 'published';
"@
        if ($publishedTerminalOutboxes -eq 2L) { break }
        Start-Sleep -Milliseconds 250
    }
    if ($publishedTerminalOutboxes -ne 2L) {
        throw "Outbox sequence fault injection did not republish both terminal events for task $completionTaskId."
    }
    $outboxSequenceOrdered = Get-AcceptanceSqlScalar -Sql @"
SELECT CASE WHEN
    MAX(CASE WHEN run_event.event_type = 'FINAL' THEN outbox.published_time END) <=
    MAX(CASE WHEN run_event.event_type = 'RUN_STATE_COMPLETED' THEN outbox.published_time END)
THEN 1 ELSE 0 END
FROM t_agent_run_outbox outbox
INNER JOIN t_agent_run_event run_event ON run_event.event_id = outbox.event_id
WHERE run_event.task_id = $completionTaskId
  AND run_event.event_type IN ('FINAL', 'RUN_STATE_COMPLETED');
"@
    if ($outboxSequenceOrdered -ne 1L) {
        throw "Later terminal outbox was published before its FINAL predecessor for task $completionTaskId."
    }
    $evidence.outboxSequenceFence = $true

    $unverifiedEvents = Invoke-AgentStream -Message '[acceptance:unverified] reject false completion'
    if ($unverifiedEvents | Where-Object { $_.type -eq 'RUN_STATE_COMPLETED' }) { throw 'Unverified edit incorrectly entered completed state.' }
    $unverifiedTaskId = [long](($unverifiedEvents | Where-Object { $_.data.taskId } | Select-Object -First 1).data.taskId)
    $unverifiedTask = Wait-TaskTerminal -TaskId $unverifiedTaskId
    if ($unverifiedTask.status -eq 'completed') { throw 'Unverified edit task incorrectly completed.' }
    $directFailedState = $unverifiedEvents | Where-Object { $_.type -eq 'RUN_STATE_FAILED' } | Select-Object -First 1
    $directFailedTransition = $null
    if ($directFailedState -and $directFailedState.data -and
        $directFailedState.data.PSObject.Properties['transition']) {
        $directFailedTransition = $directFailedState.data.transition
    }
    if (-not $directFailedState -or -not $directFailedTransition -or -not $directFailedState.eventId) {
        throw 'Direct failure stream did not project the authoritative persisted RUN_STATE_FAILED event.'
    }
    $durableUnverifiedEvents = Get-TaskEvents -TaskId $unverifiedTaskId
    $durableFailedStates = @($durableUnverifiedEvents | Where-Object { $_.type -eq 'RUN_STATE_FAILED' })
    if ($durableFailedStates.Count -ne 1) {
        throw "Expected exactly one durable RUN_STATE_FAILED event, found $($durableFailedStates.Count)."
    }
    $durableFailedState = $durableFailedStates[0]
    if ([string]$directFailedState.eventId -ne [string]$durableFailedState.eventId) {
        throw "Direct and durable failure events use different sequence IDs: direct=$($directFailedState.eventId), durable=$($durableFailedState.eventId)."
    }
    $failedTransition = $durableFailedState.data.transition
    if (-not $failedTransition -or $failedTransition.previousState -ne 'running' -or
        $failedTransition.nextState -ne 'failed' -or $failedTransition.actor -ne 'agent_run_lifecycle' -or
        $failedTransition.reason -ne 'RUN_STATE_FAILED' -or -not $failedTransition.stateChanged -or
        [long]$failedTransition.executionEpoch -lt 1) {
        throw "Failed state event is missing canonical lifecycle transition audit: $($durableFailedState.data | ConvertTo-Json -Compress -Depth 8)"
    }
    $unverifiedTypes = @($unverifiedEvents | ForEach-Object { $_.type })
    if ([array]::IndexOf($unverifiedTypes, 'RUN_STATE_FAILED') -gt [array]::IndexOf($unverifiedTypes, 'DONE')) {
        throw 'RUN_STATE_FAILED must precede DONE in the direct stream.'
    }
    $evidence.authoritativeFailureProjection = $true
    $evidence.unverifiedEditRejected = $true

    $questionEvents = Invoke-AgentStream -Message '[acceptance:question] restart recovery'
    $question = Get-RequiredEvent -Events $questionEvents -Type 'USER_QUESTION'
    $questionTaskId = [long]$question.data.taskId
    Restart-AcceptanceBackend
    Invoke-ApiData -Path "/student/projects/$projectId/agent/question/reply" -Method POST -Body @{
        requestId = [string]$question.data.requestId; action = 'answer'; answer = '继续真实验收'
    } | Out-Null
    Assert-SameTaskContinuation -TaskId $questionTaskId -RequiredType 'USER_QUESTION'
    $evidence.questionRestart = $true

    $permissionEvents = Invoke-AgentStream -Message '[acceptance:permission] restart recovery'
    $permission = Get-RequiredEvent -Events $permissionEvents -Type 'PERMISSION_ASK'
    $permissionTaskId = [long]$permission.data.taskId
    Restart-AcceptanceBackend
    Invoke-ApiData -Path "/student/projects/$projectId/agent/permission/approve" -Method POST -Body @{
        requestId = [string]$permission.data.requestId; action = 'once'; feedback = ''
    } | Out-Null
    Assert-SameTaskContinuation -TaskId $permissionTaskId -RequiredType 'PERMISSION_ASK'
    $evidence.permissionRestart = $true

    foreach ($action in @('approve','reject')) {
        $commandEvents = Invoke-AgentStream -Message '[acceptance:approval] restart recovery'
        $approval = Get-RequiredEvent -Events $commandEvents -Type 'COMMAND_APPROVAL_REQUIRED'
        $commandTaskId = [long]$approval.data.taskId
        $approvalId = [string]$approval.data.approvalId
        $beforeRestart = Invoke-ApiData -Path "/student/projects/$projectId/agent/tasks/$commandTaskId"
        if ([string]$beforeRestart.status -ne 'waiting_approval') {
            throw "Command approval task $commandTaskId was $($beforeRestart.status) before restart."
        }
        Restart-AcceptanceBackend
        $afterRestart = Invoke-ApiData -Path "/student/projects/$projectId/agent/tasks/$commandTaskId"
        if ([string]$afterRestart.status -ne 'waiting_approval') {
            throw "Command approval task $commandTaskId was $($afterRestart.status) after restart."
        }
        $decision = Invoke-ApiData -Path "/student/projects/$projectId/agent/command-approvals/$approvalId/decision" -Method POST -Body @{
            action = $action; decisionIdempotencyKey = [Guid]::NewGuid().ToString()
        }
        $afterDecision = Invoke-ApiData -Path "/student/projects/$projectId/agent/tasks/$commandTaskId"
        if ($action -eq 'approve' -and [string]$afterDecision.status -ne 'waiting_approval') {
            throw "Command approval task $commandTaskId was $($afterDecision.status) immediately after approve decision."
        }
        if ($action -eq 'approve') {
            Invoke-ApiData -Path "/student/projects/$projectId/agent/command-approvals/$approvalId/execute" -Method POST | Out-Null
        }
        Assert-SameTaskContinuation -TaskId $commandTaskId -RequiredType 'COMMAND_APPROVAL_REQUIRED'
        $taskProjection = Invoke-ApiData -Path "/student/projects/$projectId/agent/tasks/$commandTaskId"
        $toolParts = @($taskProjection.parts | Where-Object { [string]$_.partType -eq 'tool' })
        $compatibilityCalls = @($taskProjection.toolCalls)
        if ($toolParts.Count -eq 0 -or $compatibilityCalls.Count -ne $toolParts.Count) {
            throw "Tool Part authority projection count mismatch for task $commandTaskId."
        }
        foreach ($compatibilityCall in $compatibilityCalls) {
            $matchingPart = @($toolParts | Where-Object {
                [string]$_.toolCallId -eq [string]$compatibilityCall.toolCallId
            }) | Select-Object -First 1
            $missingPart = -not $matchingPart
            $statusMismatch = [string]$matchingPart.status -ne [string]$compatibilityCall.status
            $toolMismatch = [string]$matchingPart.tool -ne [string]$compatibilityCall.tool
            $detailMismatch = [string]$matchingPart.output -ne [string]$compatibilityCall.detail
            if ($missingPart -or $statusMismatch -or $toolMismatch -or $detailMismatch) {
                throw "Compatibility toolCalls diverged from durable Tool Part for task $commandTaskId."
            }
        }
        if ($action -eq 'approve') { $evidence.commandApproveRestart = $true } else { $evidence.commandRejectRestart = $true }
    }
    $evidence.toolPartAuthority = $true

    $approvedCancelEvents = Invoke-AgentStream -Message '[acceptance:approval-cancel] interrupt approved command'
    $approvedCancelApproval = Get-RequiredEvent -Events $approvedCancelEvents -Type 'COMMAND_APPROVAL_REQUIRED'
    $approvedCancelTaskId = [long]$approvedCancelApproval.data.taskId
    $approvedCancelApprovalId = [string]$approvedCancelApproval.data.approvalId
    $approvedCancelSessionId = [string]$approvedCancelApproval.data.sessionId
    $approvedCancelToolCallId = [string]$approvedCancelApproval.data.toolCallId
    Invoke-ApiData -Path "/student/projects/$projectId/agent/command-approvals/$approvedCancelApprovalId/decision" -Method POST -Body @{
        action = 'approve'; decisionIdempotencyKey = [Guid]::NewGuid().ToString()
    } | Out-Null
    $approvedCancelExecuteUri = "$baseUrl/student/projects/$projectId/agent/command-approvals/$approvedCancelApprovalId/execute"
    $approvedCancelJob = Start-Job -ScriptBlock {
        param($Uri, $Headers, $Timeout)
        Invoke-RestMethod -Uri $Uri -Method Post -Headers $Headers -TimeoutSec $Timeout
    } -ArgumentList $approvedCancelExecuteUri, (Get-Headers), $TimeoutSeconds
    try {
        $executionStarted = $null
        $executionDeadline = [DateTime]::UtcNow.AddSeconds(15)
        do {
            $approvedCancelDurableEvents = Get-TaskEvents -TaskId $approvedCancelTaskId
            $executionStarted = $approvedCancelDurableEvents | Where-Object { $_.type -eq 'COMMAND_EXECUTION_STARTED' } | Select-Object -First 1
            if ($executionStarted) { break }
            if ($approvedCancelJob.State -in @('Completed','Failed','Stopped')) {
                throw "Approved cancellation command ended before interrupt registration; jobState=$($approvedCancelJob.State)."
            }
            Start-Sleep -Milliseconds 100
        } while ([DateTime]::UtcNow -lt $executionDeadline)
        if (-not $executionStarted) { throw 'Approved command did not publish COMMAND_EXECUTION_STARTED in time.' }

        $approvedCancelTimer = [Diagnostics.Stopwatch]::StartNew()
        Invoke-ApiData -Path "/student/projects/$projectId/agent/interrupt" -Method POST -Body @{
            sessionId = $approvedCancelSessionId; taskId = [string]$approvedCancelTaskId
        } | Out-Null
        Wait-Job -Job $approvedCancelJob -Timeout 10 | Out-Null
        if ($approvedCancelJob.State -ne 'Completed') {
            throw "Approved command execute request did not finish after interrupt; jobState=$($approvedCancelJob.State)."
        }
        $approvedCancelResponse = Receive-Job -Job $approvedCancelJob -ErrorAction Stop
        $approvedCancelTimer.Stop()
        $approvedCancelElapsedMs = [int]$approvedCancelTimer.ElapsedMilliseconds
        if ($approvedCancelElapsedMs -ge 5000) {
            throw "Approved command cancellation took ${approvedCancelElapsedMs}ms."
        }
        if (-not $approvedCancelResponse -or [int]$approvedCancelResponse.code -ne 0 -or
            [string]$approvedCancelResponse.data.executionStatus -ne 'cancelled' -or
            [string]$approvedCancelResponse.data.status -ne 'cancelled') {
            throw "Approved command execute response did not report cancelled: $($approvedCancelResponse | ConvertTo-Json -Compress -Depth 8)"
        }

        $approvedCancelledTask = Wait-TaskTerminal -TaskId $approvedCancelTaskId -Seconds 15
        if ([string]$approvedCancelledTask.status -ne 'cancelled') {
            throw "Approved command task ended as $($approvedCancelledTask.status), not cancelled."
        }
        $approvedCancelToolParts = @($approvedCancelledTask.parts | Where-Object {
            [string]$_.toolCallId -eq $approvedCancelToolCallId -and [string]$_.partType -eq 'tool'
        })
        if ($approvedCancelToolParts.Count -ne 1 -or [string]$approvedCancelToolParts[0].status -ne 'interrupted') {
            throw 'Approved command compatibility Tool Part was not terminalized as interrupted.'
        }
        $approvedCancelProviderCalls = @($approvedCancelledTask.parts | Where-Object {
            [string]$_.toolCallId -eq $approvedCancelToolCallId -and [string]$_.partType -eq 'tool_call'
        })
        if ($approvedCancelProviderCalls.Count -eq 0 -or
            @($approvedCancelProviderCalls | Where-Object { [string]$_.status -ne 'interrupted' }).Count -gt 0) {
            throw 'Approved command Provider tool_call Part was not terminalized as interrupted.'
        }
        $approvedCancelToolResults = @($approvedCancelledTask.parts | Where-Object {
            [string]$_.toolCallId -eq $approvedCancelToolCallId -and [string]$_.partType -eq 'tool_result'
        })
        if ($approvedCancelToolResults.Count -ne 1 -or
            [string]$approvedCancelToolResults[0].output -notmatch 'status=interrupted') {
            throw 'Approved command cancellation did not persist a protocol-safe interrupted tool result.'
        }
        $approvedCancelDurableEvents = Get-TaskEvents -TaskId $approvedCancelTaskId
        if (@($approvedCancelDurableEvents | Where-Object { $_.type -eq 'COMMAND_EXECUTION_CANCELLED' }).Count -ne 1 -or
            @($approvedCancelDurableEvents | Where-Object { $_.type -eq 'RUN_CANCELLATION_REQUESTED' }).Count -ne 1 -or
            @($approvedCancelDurableEvents | Where-Object { $_.type -eq 'RUN_CANCELLED' }).Count -ne 1) {
            throw 'Approved command cancellation did not persist one command, request, and terminal cancellation event.'
        }
        $evidence.approvedCommandCancellation = $true
        $evidence.approvedCommandCancellationElapsedMs = $approvedCancelElapsedMs
    } finally {
        Remove-Job -Job $approvedCancelJob -Force -ErrorAction SilentlyContinue
    }

    # Crash the backend after the real approved process identity is durable, then prove startup recovery reaps it and resumes without replay.
    $approvedRestartEvents = Invoke-AgentStream -Message '[acceptance:approval-cancel] restart approved command recovery'
    $approvedRestartApproval = Get-RequiredEvent -Events $approvedRestartEvents -Type 'COMMAND_APPROVAL_REQUIRED'
    $approvedRestartTaskId = [long]$approvedRestartApproval.data.taskId
    $approvedRestartApprovalId = [string]$approvedRestartApproval.data.approvalId
    $approvedRestartToolCallId = [string]$approvedRestartApproval.data.toolCallId
    Invoke-ApiData -Path "/student/projects/$projectId/agent/command-approvals/$approvedRestartApprovalId/decision" -Method POST -Body @{
        action = 'approve'; decisionIdempotencyKey = [Guid]::NewGuid().ToString()
    } | Out-Null
    $approvedRestartExecuteUri = "$baseUrl/student/projects/$projectId/agent/command-approvals/$approvedRestartApprovalId/execute"
    $approvedRestartJob = Start-Job -ScriptBlock {
        param($Uri, $Headers, $Timeout)
        Invoke-RestMethod -Uri $Uri -Method Post -Headers $Headers -TimeoutSec $Timeout
    } -ArgumentList $approvedRestartExecuteUri, (Get-Headers), $TimeoutSeconds
    $approvedRestartBackendStopped = $false
    try {
        $processBound = $null
        $processBoundDeadline = [DateTime]::UtcNow.AddSeconds(15)
        do {
            $approvedRestartDurableEvents = Get-TaskEvents -TaskId $approvedRestartTaskId
            $processBound = $approvedRestartDurableEvents |
                Where-Object { $_.type -eq 'COMMAND_EXECUTION_PROCESS_BOUND' } | Select-Object -First 1
            if ($processBound) { break }
            if ($approvedRestartJob.State -in @('Completed','Failed','Stopped')) {
                throw "Approved restart command ended before process identity became durable; jobState=$($approvedRestartJob.State)."
            }
            Start-Sleep -Milliseconds 100
        } while ([DateTime]::UtcNow -lt $processBoundDeadline)
        if (-not $processBound) { throw 'Approved restart command did not persist COMMAND_EXECUTION_PROCESS_BOUND in time.' }

        $processSql = "SELECT process_id FROM t_command_audit_event WHERE approval_id = '$approvedRestartApprovalId' AND event_type = 'EXECUTION_PROCESS_BOUND' ORDER BY event_id DESC LIMIT 1"
        $processSqlText = (Invoke-AcceptanceSql -Sql $processSql) -join "`n"
        $processIdMatches = [regex]::Matches($processSqlText, '(?m)^\s*(\d+)\s*$')
        if ($processIdMatches.Count -lt 1) {
            throw "Durable process identity query returned no PID: $processSqlText"
        }
        $approvedRestartProcessId = [long]$processIdMatches[$processIdMatches.Count - 1].Groups[1].Value
        if (-not (Test-ProcessAlive -ProcessId $approvedRestartProcessId)) {
            throw "Approved restart process $approvedRestartProcessId was not alive before backend crash."
        }

        Stop-AcceptanceBackend
        $approvedRestartBackendStopped = $true
        Start-Sleep -Milliseconds 500
        if (-not (Test-ProcessAlive -ProcessId $approvedRestartProcessId)) {
            throw "Approved restart process $approvedRestartProcessId did not survive the forced backend crash; orphan recovery was not exercised."
        }

        # Restart before the prior lease expires. Startup must preserve the process, then the durable poller must reap it after expiry.
        Start-AcceptanceBackend -Profile 'acceptance,local'
        $approvedRestartBackendStopped = $false
        if (-not (Test-ProcessAlive -ProcessId $approvedRestartProcessId)) {
            throw "Startup recovery ignored the still-active execution lease for process $approvedRestartProcessId."
        }
        $activeLeaseEvents = Get-TaskEvents -TaskId $approvedRestartTaskId
        if (@($activeLeaseEvents | Where-Object { $_.type -eq 'RUN_RECOVERY_ACTIVE_LEASE' }).Count -ne 1) {
            throw 'Immediate restart did not persist the active-lease recovery fence.'
        }

        $processExitDeadline = [DateTime]::UtcNow.AddSeconds(25)
        while ((Test-ProcessAlive -ProcessId $approvedRestartProcessId) -and
               [DateTime]::UtcNow -lt $processExitDeadline) {
            Start-Sleep -Milliseconds 100
        }
        if (Test-ProcessAlive -ProcessId $approvedRestartProcessId) {
            throw "Expired-lease recovery did not terminate orphan process $approvedRestartProcessId."
        }

        $approvedRestartTask = Wait-TaskTerminal -TaskId $approvedRestartTaskId -Seconds 30
        if ([string]$approvedRestartTask.status -ne 'completed') {
            throw "Restart-recovered approved command task ended as $($approvedRestartTask.status), not completed."
        }
        $approvedRestartToolResults = @($approvedRestartTask.parts | Where-Object {
            [string]$_.toolCallId -eq $approvedRestartToolCallId -and [string]$_.partType -eq 'tool_result'
        })
        if ($approvedRestartToolResults.Count -ne 1 -or
            [string]$approvedRestartToolResults[0].output -notmatch 'outcome_lost_after_restart' -or
            [string]$approvedRestartToolResults[0].output -notmatch 'automatic_replay=false') {
            throw 'Restart recovery did not persist one protocol-safe interrupted tool result.'
        }
        $approvedRestartDurableEvents = Get-TaskEvents -TaskId $approvedRestartTaskId
        if (@($approvedRestartDurableEvents | Where-Object { $_.type -eq 'COMMAND_EXECUTION_PROCESS_BOUND' }).Count -ne 1 -or
            @($approvedRestartDurableEvents | Where-Object { $_.type -eq 'COMMAND_EXECUTION_RECOVERY_INTERRUPTED' }).Count -ne 1) {
            throw 'Restart recovery did not persist exactly one process-bound and one recovery-interrupted event.'
        }
        $evidence.approvedCommandRestartRecovery = $true
        $evidence.approvedCommandRestartProcessId = $approvedRestartProcessId
    } finally {
        Remove-Job -Job $approvedRestartJob -Force -ErrorAction SilentlyContinue
        if ($approvedRestartBackendStopped) {
            Start-AcceptanceBackend -Profile 'acceptance,local'
        }
    }

    $holderSessionId = [Guid]::NewGuid().ToString()
    $jobPayload = @{
        sessionId = $holderSessionId; conversationId = ''; mode = 'build'
        message = '[acceptance:checkout-hold] hold checkout'; activePath = ''; modelConfigId = $configId; backgroundRun = $false
    } | ConvertTo-Json -Compress
    $jobHeaders = Get-Headers
    $jobUri = "$baseUrl/student/projects/$projectId/agent/stream"
    $holder = Start-Job -ScriptBlock {
        param($Uri, $Headers, $Body, $Timeout)
        Invoke-WebRequest -UseBasicParsing -Uri $Uri -Method Post -Headers $Headers -ContentType 'application/json' -Body $Body -TimeoutSec $Timeout | Select-Object -ExpandProperty Content
    } -ArgumentList $jobUri, $jobHeaders, $jobPayload, $TimeoutSeconds
    try {
        $holderTask = Wait-TaskBySession -SessionId $holderSessionId -Statuses @('preparing', 'running') -Seconds 30
        if ([string]$holderTask.status -notin @('preparing', 'running')) {
            throw "Checkout holder was not active: $($holderTask.status)"
        }
        $contenderEvents = Invoke-AgentStream -Message '[acceptance:isolation:contender] checkout contention'
        $contenderWaiting = Get-RequiredEvent -Events $contenderEvents -Type 'WORKSPACE_WAITING'
        $contenderTaskId = [long]$contenderWaiting.data.taskId
        $contenderTaskRow = @(Invoke-ApiData -Path "/student/projects/$projectId/agent/tasks") |
            Where-Object { [long]$_.taskId -eq $contenderTaskId } | Select-Object -First 1
        if (-not $contenderTaskRow -or [string]::IsNullOrWhiteSpace([string]$contenderTaskRow.conversationId)) {
            throw 'Checkout contender did not expose durable conversation ownership.'
        }
        $encodedConversation = [Uri]::EscapeDataString([string]$contenderTaskRow.conversationId)
        $activeProjection = Invoke-ApiData -Path "/student/projects/$projectId/agent/conversations/$encodedConversation/active-task"
        if (-not $activeProjection -or [long]$activeProjection.taskId -ne $contenderTaskId) {
            throw 'Active-task projection did not return the waiting checkout task.'
        }
        $missingRunSession = -not $activeProjection.runSession
        $wrongConversation = [string]$activeProjection.runSession.conversationId -ne [string]$contenderTaskRow.conversationId
        $wrongExecutionSession = [string]$activeProjection.runSession.executionSessionId -ne [string]$contenderTaskRow.sessionId
        if ($missingRunSession -or $wrongConversation -or $wrongExecutionSession) {
            throw 'RunSession projection lost conversation/session identity.'
        }
        if (@($activeProjection.runMessages).Count -eq 0 -or @($activeProjection.parts).Count -eq 0) {
            throw 'RunMessage/RunPart projection was empty for a durable waiting task.'
        }
        $partWithoutMessage = @($activeProjection.parts) | Where-Object { -not $_.messageId } | Select-Object -First 1
        if ($partWithoutMessage) { throw 'A projected RunPart was not attached to a RunMessage.' }
        $evidence.checkoutContention = $true
        $evidence.runMessagePartProjection = $true
    } finally {
        Wait-Job -Job $holder -Timeout $TimeoutSeconds | Out-Null
        Receive-Job -Job $holder -ErrorAction SilentlyContinue | Out-Null
        Remove-Job -Job $holder -Force -ErrorAction SilentlyContinue
    }
    $contenderTask = Wait-TaskTerminal -TaskId $contenderTaskId
    if ($contenderTask.status -notin @('completed', 'failed', 'cancelled')) {
        throw "Checkout contender did not reach a terminal state: $($contenderTask.status)"
    }

    $cancelSessionId = [Guid]::NewGuid().ToString()
    $cancelPayload = @{
        sessionId = $cancelSessionId; conversationId = ''; mode = 'build'
        message = '[acceptance:checkout-hold] cancel active run'; activePath = ''; modelConfigId = $configId; backgroundRun = $false
    } | ConvertTo-Json -Compress
    $cancelJob = Start-Job -ScriptBlock {
        param($Uri, $Headers, $Body, $Timeout)
        Invoke-WebRequest -UseBasicParsing -Uri $Uri -Method Post -Headers $Headers -ContentType 'application/json' -Body $Body -TimeoutSec $Timeout | Select-Object -ExpandProperty Content
    } -ArgumentList $jobUri, $jobHeaders, $cancelPayload, $TimeoutSeconds
    try {
        $cancelTask = Wait-TaskBySession -SessionId $cancelSessionId -Statuses @('preparing', 'running') -Seconds 30
        $cancelTaskId = [long]$cancelTask.taskId
        Invoke-ApiData -Path "/student/projects/$projectId/agent/interrupt" -Method POST -Body @{
            sessionId = $cancelSessionId; taskId = [string]$cancelTaskId
        } | Out-Null
        Wait-Job -Job $cancelJob -Timeout $TimeoutSeconds | Out-Null
        $cancelContent = ((Receive-Job -Job $cancelJob -ErrorAction Stop | ForEach-Object { [string]$_ }) -join "`n")
        $cancelEvents = ConvertFrom-AgentSse -Text $cancelContent
        $directCancelled = $cancelEvents | Where-Object { $_.type -eq 'RUN_CANCELLED' } | Select-Object -First 1
        if (-not $directCancelled -or -not $directCancelled.eventId -or
            -not $directCancelled.data.PSObject.Properties['transition']) {
            throw 'Direct cancellation stream did not project the authoritative persisted RUN_CANCELLED event.'
        }
        $cancelledTask = Wait-TaskTerminal -TaskId $cancelTaskId
        if ([string]$cancelledTask.status -ne 'cancelled') { throw "Cancelled task status is $($cancelledTask.status)." }
        $durableCancelEvents = Get-TaskEvents -TaskId $cancelTaskId
        $requestedCancellation = @($durableCancelEvents | Where-Object { $_.type -eq 'RUN_CANCELLATION_REQUESTED' })
        $durableCancelled = @($durableCancelEvents | Where-Object { $_.type -eq 'RUN_CANCELLED' })
        if ($requestedCancellation.Count -ne 1 -or $durableCancelled.Count -ne 1) {
            throw "Cancellation event count mismatch: requested=$($requestedCancellation.Count), cancelled=$($durableCancelled.Count)."
        }
        if ([string]$directCancelled.eventId -ne [string]$durableCancelled[0].eventId) {
            throw 'Direct and durable cancellation events use different sequence IDs.'
        }
        $directTypes = @($cancelEvents | ForEach-Object { [string]$_.type })
        $cancelledIndex = [array]::IndexOf($directTypes, 'RUN_CANCELLED')
        $interruptedIndex = [array]::IndexOf($directTypes, 'INTERRUPTED')
        $finalIndex = [array]::IndexOf($directTypes, 'FINAL')
        $followingTerminalIndexes = @($interruptedIndex, $finalIndex) | Where-Object { $_ -ge 0 }
        if ($cancelledIndex -lt 0 -or ($followingTerminalIndexes.Count -gt 0 -and
            $cancelledIndex -ge ($followingTerminalIndexes | Measure-Object -Minimum).Minimum)) {
            throw "RUN_CANCELLED was not projected before INTERRUPTED/FINAL: $($directTypes -join ',')."
        }
        if ([long]$requestedCancellation[0].eventId -ge [long]$durableCancelled[0].eventId) {
            throw 'RUN_CANCELLATION_REQUESTED was not persisted before RUN_CANCELLED.'
        }
        $cancelTransition = $durableCancelled[0].data.transition
        if (-not $cancelTransition -or $cancelTransition.previousState -ne 'cancelling' -or
            $cancelTransition.nextState -ne 'cancelled' -or $cancelTransition.actor -ne 'agent_run_lifecycle') {
            throw "Cancelled state event is missing canonical transition audit: $($durableCancelled[0].data | ConvertTo-Json -Compress -Depth 8)"
        }
        Invoke-ApiData -Path "/student/projects/$projectId/agent/interrupt" -Method POST -Body @{
            sessionId = $cancelSessionId; taskId = [string]$cancelTaskId
        } | Out-Null
        $eventsAfterDuplicateCancel = Get-TaskEvents -TaskId $cancelTaskId
        if (@($eventsAfterDuplicateCancel | Where-Object { $_.type -eq 'RUN_CANCELLATION_REQUESTED' }).Count -ne 1 -or
            @($eventsAfterDuplicateCancel | Where-Object { $_.type -eq 'RUN_CANCELLED' }).Count -ne 1) {
            throw 'A duplicate cancellation request created additional durable cancellation events.'
        }
        $evidence.authoritativeCancellationProjection = $true
    } finally {
        Remove-Job -Job $cancelJob -Force -ErrorAction SilentlyContinue
    }

    $retryStreamEvents = Invoke-AgentStream -Message '[acceptance:stream-break]'
    $directRetryScheduled = $retryStreamEvents | Where-Object { $_.type -eq 'RUN_MODEL_RETRY_SCHEDULED' } | Select-Object -First 1
    if (-not $directRetryScheduled -or -not $directRetryScheduled.eventId) {
        throw 'Direct model stream did not project the authoritative persisted RUN_MODEL_RETRY_SCHEDULED event.'
    }
    $retryTaskId = [long](($retryStreamEvents | Where-Object { $_.data.taskId } | Select-Object -First 1).data.taskId)
    $retryTerminalTask = Wait-TaskTerminal -TaskId $retryTaskId -Seconds 60
    if ([string]$retryTerminalTask.status -ne 'failed') {
        throw "Recoverable stream interruption ended as $($retryTerminalTask.status), expected failed after bounded retries."
    }
    $durableRetryEvents = Get-TaskEvents -TaskId $retryTaskId
    $scheduledRetries = @($durableRetryEvents | Where-Object { $_.type -eq 'RUN_MODEL_RETRY_SCHEDULED' })
    $startedRetries = @($durableRetryEvents | Where-Object { $_.type -eq 'RUN_MODEL_RETRY_STARTED' })
    $legacyRetryEvents = @($durableRetryEvents | Where-Object { $_.type -eq 'RETRY_SCHEDULED' })
    if ($scheduledRetries.Count -ne 2 -or $startedRetries.Count -ne 2) {
        throw "Bounded retry event count mismatch: scheduled=$($scheduledRetries.Count), started=$($startedRetries.Count)."
    }
    if ($legacyRetryEvents.Count -ne 0) {
        throw "Legacy RETRY_SCHEDULED events remain durable: $($legacyRetryEvents.Count)."
    }
    if ([string]$directRetryScheduled.eventId -ne [string]$scheduledRetries[0].eventId) {
        throw 'Direct and durable model retry events use different sequence IDs.'
    }
    $retryTransition = $scheduledRetries[0].data.transition
    if (-not $retryTransition -or $retryTransition.previousState -ne 'running' -or
        $retryTransition.nextState -ne 'retrying' -or $retryTransition.actor -ne 'agent_run_lifecycle') {
        throw "Model retry event is missing canonical transition audit: $($scheduledRetries[0].data | ConvertTo-Json -Compress -Depth 8)"
    }
    $retryTaskMatches = @(Invoke-ApiData -Path "/student/projects/$projectId/agent/tasks" |
        Where-Object { [string]$_.sessionId -eq [string]$retryTerminalTask.sessionId })
    if ($retryTaskMatches.Count -ne 1 -or [long]$retryTaskMatches[0].taskId -ne $retryTaskId) {
        throw 'Model retry created a second task instead of resuming the original task/epoch.'
    }
    $evidence.authoritativeModelRetryProjection = $true

    $compactionConfig = Invoke-ApiData -Path '/student/model-configs' -Method POST -Body @{
        configName = 'Acceptance Compaction Restart'; provider = 'acceptance_scripted'; modelName = 'acceptance-compaction-restart'
        apiKey = 'acceptance-placeholder-not-a-secret'; baseUrl = 'acceptance://scripted'; maxTokens = 4096; contextWindowTokens = 32768
        temperature = 0.0; isDefault = $false; promptCacheKeyEnabled = $false
        reasoningEffort = 'medium'; imageInputEnabled = $false
        compactionAuto = $true; compactionPrune = $false; compactionTailTurns = 1
        compactionPreserveRecentTokens = 8000; compactionReservedTokens = 4096; compactionThresholdPercent = 70
    }
    $compactionConfigId = [int]$compactionConfig.configId
    $configId = $compactionConfigId
    $compactionCancelEvents = Invoke-AgentStream -Message '[acceptance:compaction] [acceptance:compaction-cancel] cancel active compaction'
    $compactionCancelQuestion = Get-RequiredEvent -Events $compactionCancelEvents -Type 'USER_QUESTION'
    $compactionCancelTaskId = [long]$compactionCancelQuestion.data.taskId
    $compactionCancelRequestId = [string]$compactionCancelQuestion.data.requestId
    Invoke-ApiData -Path "/student/projects/$projectId/agent/question/reply" -Method POST -Body @{
        requestId = $compactionCancelRequestId; action = 'answer'; answer = 'continue then cancel compaction'
    } | Out-Null
    $runningCompaction = Wait-TaskCompactionState -TaskId $compactionCancelTaskId -Status 'running' -Seconds 60
    if ([string]$runningCompaction.task.status -notin @('running', 'preparing')) {
        throw "Compaction cancellation task was not active while its compaction was running: $($runningCompaction.task.status)"
    }
    $compactionCancelTimer = [Diagnostics.Stopwatch]::StartNew()
    Invoke-ApiData -Path "/student/projects/$projectId/agent/interrupt" -Method POST -Body @{
        sessionId = [string]$runningCompaction.task.sessionId; taskId = [string]$compactionCancelTaskId
    } | Out-Null
    $compactionCancelledTask = Wait-TaskTerminal -TaskId $compactionCancelTaskId -Seconds 60
    $compactionCancelTimer.Stop()
    $compactionCancellationElapsedMs = [int]$compactionCancelTimer.ElapsedMilliseconds
    if ($compactionCancellationElapsedMs -ge 5000) {
        throw "Compaction cancellation took ${compactionCancellationElapsedMs}ms despite a cancellation-aware Provider contract."
    }
    if ([string]$compactionCancelledTask.status -ne 'cancelled') {
        throw "Compaction cancellation task ended as $($compactionCancelledTask.status), expected cancelled."
    }
    $cancelledCompactions = @($compactionCancelledTask.compactions | Where-Object {
        [string]$_.status -eq 'failed' -and [string]$_.failureReason -eq 'Compaction cancelled'
    })
    $runningAfterCancel = @($compactionCancelledTask.compactions | Where-Object { [string]$_.status -eq 'running' })
    $completedAfterCancel = @($compactionCancelledTask.compactions | Where-Object { [string]$_.status -eq 'completed' })
    if ($cancelledCompactions.Count -ne 1 -or $runningAfterCancel.Count -ne 0 -or $completedAfterCancel.Count -ne 0) {
        throw "Compaction cancellation terminal mismatch: $($compactionCancelledTask.compactions | ConvertTo-Json -Compress -Depth 8)"
    }
    $compactionCancelDurableEvents = Get-TaskEvents -TaskId $compactionCancelTaskId
    if (@($compactionCancelDurableEvents | Where-Object { $_.type -eq 'COMPACTION_FAILED' }).Count -ne 1 -or
        @($compactionCancelDurableEvents | Where-Object { $_.type -eq 'COMPACTION_COMPLETED' }).Count -ne 0 -or
        @($compactionCancelDurableEvents | Where-Object { $_.type -eq 'RUN_CANCELLED' }).Count -ne 1) {
        throw 'Compaction cancellation did not persist one failed projection, zero completed projections, and one task cancellation.'
    }
    $evidence.compactionCancellationTerminal = $true
    $evidence.compactionCancellationElapsedMs = $compactionCancellationElapsedMs

    $compactionRestartEvents = Invoke-AgentStream -Message '[acceptance:compaction] [acceptance:compaction-restart] durable epoch restart recovery'
    $firstCompactionQuestion = Get-RequiredEvent -Events $compactionRestartEvents -Type 'USER_QUESTION'
    $compactionTaskId = [long]$firstCompactionQuestion.data.taskId
    $firstCompactionRequestId = [string]$firstCompactionQuestion.data.requestId
    Invoke-ApiData -Path "/student/projects/$projectId/agent/question/reply" -Method POST -Body @{
        requestId = $firstCompactionRequestId; action = 'answer'; answer = 'continue compaction acceptance'
    } | Out-Null
    $checkpointWait = Wait-TaskPendingInteraction -TaskId $compactionTaskId -PreviousRequestId $firstCompactionRequestId -Seconds 90
    $completedCompactions = @($checkpointWait.compactions | Where-Object { [string]$_.status -eq 'completed' } |
        Sort-Object { [long]$_.compactionEpoch })
    if ($completedCompactions.Count -eq 0) {
        throw "Task $compactionTaskId reached the restart checkpoint without a completed durable compaction."
    }
    $completedEpoch = [long]$completedCompactions[-1].compactionEpoch
    $compactionInsertSql = @"
INSERT INTO t_agent_compaction_record (
    task_id, conversation_id, student_id, project_id, execution_epoch, compaction_epoch,
    trigger_reason, status, previous_summary, summary, compacted_head, retained_tail,
    tail_start_index, retained_turns, source_max_sequence, estimated_tokens_before,
    estimated_tokens_after, model_window_tokens, reserved_output_tokens, failure_reason,
    create_time, update_time
)
SELECT task_id, conversation_id, student_id, project_id, execution_epoch, compaction_epoch + 1,
       'acceptance_restart_fault', 'running', previous_summary, NULL, compacted_head, retained_tail,
       tail_start_index, retained_turns, source_max_sequence, estimated_tokens_before,
       NULL, model_window_tokens, reserved_output_tokens, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM t_agent_compaction_record
WHERE compaction_id = (
    SELECT MAX(compaction_id) FROM t_agent_compaction_record
    WHERE task_id = $compactionTaskId AND status = 'completed'
);
"@
    Invoke-AcceptanceSql -Sql $compactionInsertSql | Out-Null
    $beforeCompactionRestart = Invoke-ApiData -Path "/student/projects/$projectId/agent/tasks/$compactionTaskId"
    $syntheticRunning = @($beforeCompactionRestart.compactions | Where-Object {
        [string]$_.triggerReason -eq 'acceptance_restart_fault' -and [string]$_.status -eq 'running'
    })
    if ($syntheticRunning.Count -ne 1 -or [long]$syntheticRunning[0].compactionEpoch -ne ($completedEpoch + 1)) {
        throw "Synthetic running compaction was not persisted at epoch $($completedEpoch + 1)."
    }

    Restart-AcceptanceBackend
    $afterCompactionRestart = Invoke-ApiData -Path "/student/projects/$projectId/agent/tasks/$compactionTaskId"
    if ([string]$afterCompactionRestart.status -ne 'waiting_user') {
        throw "Compaction checkpoint task $compactionTaskId was $($afterCompactionRestart.status) after restart."
    }
    $syntheticFailed = @($afterCompactionRestart.compactions | Where-Object {
        [string]$_.triggerReason -eq 'acceptance_restart_fault'
    })
    if ($syntheticFailed.Count -ne 1 -or [string]$syntheticFailed[0].status -ne 'failed' -or
        [string]$syntheticFailed[0].failureReason -notlike '*without the original compaction execution lease*') {
        throw "Interrupted compaction epoch was not closed by startup recovery: $($syntheticFailed | ConvertTo-Json -Compress -Depth 5)"
    }
    $preservedCompleted = @($afterCompactionRestart.compactions | Where-Object {
        [string]$_.status -eq 'completed' -and [long]$_.compactionEpoch -eq $completedEpoch
    })
    if ($preservedCompleted.Count -ne 1) {
        throw "Startup recovery did not preserve completed compaction epoch $completedEpoch."
    }
    $secondCompactionRequestId = [string]$afterCompactionRestart.pendingInteraction.requestId
    if ([string]::IsNullOrWhiteSpace($secondCompactionRequestId) -or $secondCompactionRequestId -eq $firstCompactionRequestId) {
        throw 'Restart checkpoint did not expose the second durable question interaction.'
    }
    Invoke-ApiData -Path "/student/projects/$projectId/agent/question/reply" -Method POST -Body @{
        requestId = $secondCompactionRequestId; action = 'answer'; answer = 'continue after restart'
    } | Out-Null
    Assert-SameTaskContinuation -TaskId $compactionTaskId -RequiredType 'COMPACTION_COMPLETED'
    $completedAfterResume = @(($(Invoke-ApiData -Path "/student/projects/$projectId/agent/tasks/$compactionTaskId").compactions) |
        Where-Object { [string]$_.status -eq 'completed' } | Sort-Object { [long]$_.compactionEpoch })
    if ($completedAfterResume.Count -eq 0 -or [long]$completedAfterResume[-1].compactionEpoch -ne $completedEpoch) {
        throw 'Interaction resume did not continue from the previously completed compaction epoch.'
    }
    $evidence.compactionEpochRecovery = $true
    Invoke-ApiData -Path "/student/model-configs/$compactionConfigId" -Method DELETE | Out-Null
    $compactionConfigId = $null
    $configId = $originalConfigId

    $seedEvents = Invoke-AgentStream -Message '[acceptance:isolation:compaction-seed-1]'
    $seedDone = Get-RequiredEvent -Events $seedEvents -Type 'DONE'
    $seedTaskId = [long](($seedEvents | Where-Object { $_.data.taskId } | Select-Object -First 1).data.taskId)
    $seedTask = Wait-TaskTerminal -TaskId $seedTaskId
    $conversationId = [string]$seedTask.conversationId
    $secondSeedEvents = Invoke-AgentStream -Message '[acceptance:isolation:compaction-seed-2]' -ConversationId $conversationId
    $secondSeedDone = Get-RequiredEvent -Events $secondSeedEvents -Type 'DONE'
    $secondSeedTaskId = [long](($secondSeedEvents | Where-Object { $_.data.taskId } | Select-Object -First 1).data.taskId)
    $secondSeedTask = Wait-TaskTerminal -TaskId $secondSeedTaskId
    $thirdSeedEvents = Invoke-AgentStream -Message '[acceptance:isolation:compaction-seed-3]' -ConversationId $conversationId
    $thirdSeedDone = Get-RequiredEvent -Events $thirdSeedEvents -Type 'DONE'
    $thirdSeedTaskId = [long](($thirdSeedEvents | Where-Object { $_.data.taskId } | Select-Object -First 1).data.taskId)
    $thirdSeedTask = Wait-TaskTerminal -TaskId $thirdSeedTaskId

    $forkConversation = Invoke-ApiData -Path "/student/projects/$projectId/agent/conversations/$conversationId/fork" -Method POST -Body @{
        taskId = $thirdSeedTaskId
    }
    $forkConversationId = [string]$forkConversation.conversationId
    if ([string]::IsNullOrWhiteSpace($forkConversationId) -or
        [long]$forkConversation.forkedFromTaskId -ne $thirdSeedTaskId -or
        [string]$forkConversation.parentConversationId -ne $conversationId) {
        throw "Fork response did not persist the requested durable boundary: $($forkConversation | ConvertTo-Json -Compress -Depth 6)"
    }
    $forkBoundaryRowCount = Get-AcceptanceSqlScalar -Sql (
        "SELECT COUNT(*) FROM t_agent_conversation WHERE conversation_id = '$forkConversationId' " +
        "AND parent_conversation_id = '$conversationId' AND forked_from_task_id = $thirdSeedTaskId")
    if ($forkBoundaryRowCount -ne 1) {
        throw 'Fork conversation row did not persist exactly one immutable parent task boundary.'
    }

    # fork 只写 immutable task boundary；不得再复制旧 UI 事件。
    $copiedForkEventCount = Get-AcceptanceSqlScalar -Sql "SELECT COUNT(*) FROM t_agent_message WHERE conversation_id = '$forkConversationId'"
    if ($copiedForkEventCount -ne 0) {
        throw "Fork copied $copiedForkEventCount legacy AgentMessage rows instead of reading the durable parent graph."
    }
    $forkHistory = Invoke-ApiData -Path "/student/projects/$projectId/agent/conversations/$forkConversationId/messages?limit=50"
    if ([string]$forkHistory.projectionVersion -ne 'durable-task-history-v1') {
        throw "Fork history did not expose the durable task-turn projection: $($forkHistory | ConvertTo-Json -Compress -Depth 8)"
    }
    $forkInheritedTaskIds = @($forkHistory.turns | Where-Object { [bool]$_.inherited } | ForEach-Object { [long]$_.taskId })
    foreach ($expectedTaskId in @($seedTaskId, $secondSeedTaskId, $thirdSeedTaskId)) {
        if ($forkInheritedTaskIds -notcontains [long]$expectedTaskId) {
            throw "Fork durable history omitted inherited task $expectedTaskId."
        }
    }
    if (@($forkInheritedTaskIds | Where-Object { $_ -gt $thirdSeedTaskId }).Count -ne 0) {
        throw 'Fork durable history crossed the immutable parent task boundary.'
    }

    $fourthSeedEvents = Invoke-AgentStream -Message '[acceptance:isolation:compaction-seed-4]' -ConversationId $conversationId
    $fourthSeedDone = Get-RequiredEvent -Events $fourthSeedEvents -Type 'DONE'
    $fourthSeedTaskId = [long](($fourthSeedEvents | Where-Object { $_.data.taskId } | Select-Object -First 1).data.taskId)
    $fourthSeedTask = Wait-TaskTerminal -TaskId $fourthSeedTaskId
    $fifthSeedEvents = Invoke-AgentStream -Message '[acceptance:isolation:compaction-seed-5]' -ConversationId $conversationId
    $fifthSeedDone = Get-RequiredEvent -Events $fifthSeedEvents -Type 'DONE'
    $fifthSeedTaskId = [long](($fifthSeedEvents | Where-Object { $_.data.taskId } | Select-Object -First 1).data.taskId)
    $fifthSeedTask = Wait-TaskTerminal -TaskId $fifthSeedTaskId

    $forkInheritedPreview = Get-NextContextPreviewSectionContent -ConversationId $forkConversationId
    foreach ($expectedMarker in @('compaction-seed-1', 'compaction-seed-2', 'compaction-seed-3')) {
        if (-not $forkInheritedPreview.Contains($expectedMarker)) {
            throw "Fork durable preview did not inherit boundary marker $expectedMarker without any legacy projection copy."
        }
    }
    foreach ($forbiddenMarker in @('compaction-seed-4', 'compaction-seed-5')) {
        if ($forkInheritedPreview.Contains($forbiddenMarker)) {
            throw "Fork durable preview leaked post-boundary parent marker $forbiddenMarker."
        }
    }
    $evidence.durableForkBoundary = $true
    $evidence.durableForkParentTaskId = $thirdSeedTaskId

    $firstForkChildEvents = Invoke-AgentStream -Message '[acceptance:isolation:fork-child-1]' -ConversationId $forkConversationId
    Get-RequiredEvent -Events $firstForkChildEvents -Type 'DONE' | Out-Null
    $firstForkChildTaskId = [long](($firstForkChildEvents | Where-Object { $_.data.taskId } | Select-Object -First 1).data.taskId)
    $firstForkChildTask = Wait-TaskTerminal -TaskId $firstForkChildTaskId
    $secondForkChildEvents = Invoke-AgentStream -Message '[acceptance:isolation:fork-child-2]' -ConversationId $forkConversationId
    Get-RequiredEvent -Events $secondForkChildEvents -Type 'DONE' | Out-Null
    $secondForkChildTaskId = [long](($secondForkChildEvents | Where-Object { $_.data.taskId } | Select-Object -First 1).data.taskId)
    $secondForkChildTask = Wait-TaskTerminal -TaskId $secondForkChildTaskId

    $forkCombinedPreview = Get-NextContextPreviewSectionContent -ConversationId $forkConversationId
    foreach ($expectedMarker in @('compaction-seed-1', 'compaction-seed-2', 'compaction-seed-3', 'fork-child-1', 'fork-child-2')) {
        if (-not $forkCombinedPreview.Contains($expectedMarker)) {
            throw "Fork durable preview did not contain expected inherited/child marker $expectedMarker."
        }
    }
    foreach ($forbiddenMarker in @('compaction-seed-4', 'compaction-seed-5')) {
        if ($forkCombinedPreview.Contains($forbiddenMarker)) {
            throw "Fork durable preview leaked post-boundary parent marker $forbiddenMarker after child execution."
        }
    }

    $forkCompaction = Invoke-ApiData -Path "/student/projects/$projectId/agent/conversations/$forkConversationId/compact" -Method POST -Body @{ modelConfigId = $configId }
    $forkCompactionTaskId = [long]$forkCompaction.taskId
    $forkCompactionTask = Wait-TaskTerminal -TaskId $forkCompactionTaskId
    if ([string]$forkCompactionTask.status -ne 'completed') {
        throw "Fork compaction task status was $($forkCompactionTask.status): $($forkCompactionTask | ConvertTo-Json -Depth 10 -Compress)"
    }
    $forkCompactionAuthorityCount = Get-AcceptanceSqlScalar -Sql (
        "SELECT COUNT(*) FROM t_agent_compaction_record WHERE task_id = $forkCompactionTaskId " +
        "AND conversation_id = '$forkConversationId' AND scope = 'conversation' AND status = 'completed' " +
        "AND source_max_task_id = $secondForkChildTaskId " +
        "AND compacted_head LIKE '%compaction-seed-1%' AND compacted_head LIKE '%compaction-seed-3%' " +
        "AND retained_tail LIKE '%fork-child-1%' AND retained_tail LIKE '%fork-child-2%'")
    if ($forkCompactionAuthorityCount -ne 1) {
        throw 'Fork compaction did not persist inherited parent history in its head and child history in its retained tail.'
    }
    $evidence.durableForkCompaction = $true
    $evidence.durableForkChildSourceMaxTaskId = $secondForkChildTaskId

    Restart-AcceptanceBackend
    $forkRestartPreview = Get-NextContextPreviewSectionContent -ConversationId $forkConversationId
    if (-not $forkRestartPreview.Contains('<conversation-checkpoint') -or
        -not $forkRestartPreview.Contains('fork-child-1') -or
        -not $forkRestartPreview.Contains('fork-child-2')) {
        throw 'Fork context preview did not recover its completed compaction checkpoint and retained child tail after JVM restart.'
    }
    foreach ($forbiddenMarker in @('compaction-seed-4', 'compaction-seed-5')) {
        if ($forkRestartPreview.Contains($forbiddenMarker)) {
            throw "Fork context preview leaked post-boundary parent marker $forbiddenMarker after JVM restart."
        }
    }
    $forkCompactionAfterRestartCount = Get-AcceptanceSqlScalar -Sql (
        "SELECT COUNT(*) FROM t_agent_compaction_record WHERE task_id = $forkCompactionTaskId " +
        "AND conversation_id = '$forkConversationId' AND scope = 'conversation' AND status = 'completed' " +
        "AND source_max_task_id = $secondForkChildTaskId")
    if ($forkCompactionAfterRestartCount -ne 1) {
        throw 'Fork completed compaction authority was not durable across JVM restart.'
    }
    $evidence.durableForkRestart = $true

    $studentId = Get-AcceptanceSqlScalar -Sql "SELECT student_id FROM t_student_project WHERE project_id = $projectId"
    $legacyConversationId = "legacy-$($runId.Substring(0, 20))"
    $legacyThinkPayload = '{"content":"<think>legacy-private-reasoning</think>legacy-visible-reasoning"}'
    $legacyFinalPayload = '{"content":"legacy-final-visible"}'
    Invoke-AcceptanceSql -Sql (
        "INSERT INTO t_agent_conversation " +
        "(conversation_id, student_id, project_id, title, mode, status, create_time, update_time) VALUES " +
        "('$legacyConversationId', $studentId, $projectId, 'Legacy acceptance conversation', 'build', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)") | Out-Null
    Invoke-AcceptanceSql -Sql (
        "INSERT INTO t_agent_message (conversation_id, student_id, project_id, event_type, role, content, event_data, create_time) VALUES " +
        "('$legacyConversationId', $studentId, $projectId, 'USER', 'user', 'legacy-user-visible', NULL, CURRENT_TIMESTAMP)") | Out-Null
    Invoke-AcceptanceSql -Sql (
        "INSERT INTO t_agent_message (conversation_id, student_id, project_id, event_type, role, content, event_data, create_time) VALUES " +
        "('$legacyConversationId', $studentId, $projectId, 'THINK', 'assistant', 'legacy-visible-reasoning', " +
        "'$legacyThinkPayload', CURRENT_TIMESTAMP)") | Out-Null
    Invoke-AcceptanceSql -Sql (
        "INSERT INTO t_agent_message (conversation_id, student_id, project_id, event_type, role, content, event_data, create_time) VALUES " +
        "('$legacyConversationId', $studentId, $projectId, 'FINAL', 'assistant', 'legacy-final-visible', " +
        "'$legacyFinalPayload', CURRENT_TIMESTAMP)") | Out-Null

    $legacyHistory = Invoke-ApiData -Path "/student/projects/$projectId/agent/conversations/$legacyConversationId/messages?limit=20"
    $legacyTurns = @($legacyHistory.turns)
    if ([string]$legacyHistory.projectionVersion -ne 'durable-task-history-v1' -or
        -not [bool]$legacyHistory.legacyMigrated -or $legacyTurns.Count -ne 1) {
        throw "Legacy history did not migrate into one durable task turn: $($legacyHistory | ConvertTo-Json -Depth 20 -Compress)"
    }
    $legacyTurn = $legacyTurns[0]
    $legacyTaskId = [long]$legacyTurn.taskId
    $legacyEventTypes = @($legacyTurn.events | ForEach-Object { [string]$_.eventType })
    $legacyHistoryJson = $legacyHistory | ConvertTo-Json -Depth 30 -Compress
    if ([string]$legacyTurn.userContent -ne 'legacy-user-visible' -or
        $legacyEventTypes -notcontains 'THINK' -or $legacyEventTypes -notcontains 'FINAL' -or
        -not $legacyHistoryJson.Contains('legacy-final-visible') -or
        $legacyHistoryJson.Contains('legacy-private-reasoning')) {
        throw "Legacy durable projection did not preserve public content or leaked private reasoning: $legacyHistoryJson"
    }
    $legacyTaskCount = Get-AcceptanceSqlScalar -Sql (
        "SELECT COUNT(*) FROM t_agent_task WHERE conversation_id = '$legacyConversationId' AND mode = 'legacy_import'")
    $legacyEventCount = Get-AcceptanceSqlScalar -Sql "SELECT COUNT(*) FROM t_agent_run_event WHERE task_id = $legacyTaskId"
    $legacyMarkerCount = Get-AcceptanceSqlScalar -Sql (
        "SELECT COUNT(*) FROM t_agent_conversation WHERE conversation_id = '$legacyConversationId' " +
        "AND history_projection_version = 'durable-v1' AND history_migrated_at IS NOT NULL")
    if ($legacyTaskCount -ne 1 -or $legacyEventCount -ne 2 -or $legacyMarkerCount -ne 1) {
        throw "Legacy migration authority mismatch: tasks=$legacyTaskCount events=$legacyEventCount marker=$legacyMarkerCount"
    }

    $legacySecondRead = Invoke-ApiData -Path "/student/projects/$projectId/agent/conversations/$legacyConversationId/messages?limit=20"
    $legacySecondTurns = @($legacySecondRead.turns)
    $legacySecondTaskCount = Get-AcceptanceSqlScalar -Sql (
        "SELECT COUNT(*) FROM t_agent_task WHERE conversation_id = '$legacyConversationId' AND mode = 'legacy_import'")
    $legacySecondEventCount = Get-AcceptanceSqlScalar -Sql "SELECT COUNT(*) FROM t_agent_run_event WHERE task_id = $legacyTaskId"
    if ([bool]$legacySecondRead.legacyMigrated -or $legacySecondTurns.Count -ne 1 -or
        [long]$legacySecondTurns[0].taskId -ne $legacyTaskId -or
        $legacySecondTaskCount -ne $legacyTaskCount -or $legacySecondEventCount -ne $legacyEventCount) {
        throw 'Repeated legacy history read was not idempotent.'
    }
    $evidence.legacyHistoryMigration = $true
    $evidence.legacyHistoryMigrationTaskId = $legacyTaskId

    Invoke-AcceptanceSql -Sql "DELETE FROM t_agent_message WHERE conversation_id = '$legacyConversationId'" | Out-Null
    Restart-AcceptanceBackend
    $legacyAfterRestart = Invoke-ApiData -Path "/student/projects/$projectId/agent/conversations/$legacyConversationId/messages?limit=20"
    $legacyRestartTurns = @($legacyAfterRestart.turns)
    $legacyRowsAfterDelete = Get-AcceptanceSqlScalar -Sql "SELECT COUNT(*) FROM t_agent_message WHERE conversation_id = '$legacyConversationId'"
    $legacyTasksAfterRestart = Get-AcceptanceSqlScalar -Sql (
        "SELECT COUNT(*) FROM t_agent_task WHERE conversation_id = '$legacyConversationId' AND mode = 'legacy_import'")
    $legacyEventsAfterRestart = Get-AcceptanceSqlScalar -Sql "SELECT COUNT(*) FROM t_agent_run_event WHERE task_id = $legacyTaskId"
    $legacyRestartJson = $legacyAfterRestart | ConvertTo-Json -Depth 30 -Compress
    if ($legacyRowsAfterDelete -ne 0 -or $legacyRestartTurns.Count -ne 1 -or
        [long]$legacyRestartTurns[0].taskId -ne $legacyTaskId -or
        [string]$legacyRestartTurns[0].userContent -ne 'legacy-user-visible' -or
        $legacyTasksAfterRestart -ne 1 -or $legacyEventsAfterRestart -ne 2 -or
        -not $legacyRestartJson.Contains('legacy-final-visible') -or
        $legacyRestartJson.Contains('legacy-private-reasoning')) {
        throw "Migrated history did not survive legacy-row deletion and JVM restart: $legacyRestartJson"
    }
    $evidence.legacyHistoryMigrationRestart = $true

    $compaction = Invoke-ApiData -Path "/student/projects/$projectId/agent/conversations/$conversationId/compact" -Method POST -Body @{ modelConfigId = $configId }
    $compactionTaskId = [long]$compaction.taskId
    $compactionTask = Wait-TaskTerminal -TaskId $compactionTaskId
    if ($compactionTask.status -ne 'completed') {
        $compactionFailure = $compactionTask | ConvertTo-Json -Depth 10 -Compress
        throw "Manual compaction task status was $($compactionTask.status): $compactionFailure"
    }
    $conversationCompactionCount = Get-AcceptanceSqlScalar -Sql (
        "SELECT COUNT(*) FROM t_agent_compaction_record WHERE task_id = $compactionTaskId " +
        "AND scope = 'conversation' AND status = 'completed' AND source_max_task_id = $fifthSeedTaskId " +
        "AND source_max_sequence = -1 AND estimated_tokens_after < estimated_tokens_before " +
        "AND LENGTH(summary) > 0 AND LENGTH(compacted_head) > 2 AND LENGTH(retained_tail) > 2")
    if ($conversationCompactionCount -ne 1) {
        throw 'Manual compaction did not persist one completed conversation-scope authority record with the stable task boundary.'
    }
    $recordExecutionEpoch = Get-AcceptanceSqlScalar -Sql "SELECT execution_epoch FROM t_agent_compaction_record WHERE task_id = $compactionTaskId AND scope = 'conversation'"
    $taskExecutionEpoch = Get-AcceptanceSqlScalar -Sql "SELECT execution_epoch FROM t_agent_task WHERE task_id = $compactionTaskId"
    if ($recordExecutionEpoch -le 0 -or $recordExecutionEpoch -ne $taskExecutionEpoch) {
        throw "Manual compaction record was not fenced by the task execution lease: record=$recordExecutionEpoch task=$taskExecutionEpoch"
    }
    $legacyProjectionCount = Get-AcceptanceSqlScalar -Sql (
        "SELECT COUNT(*) FROM t_agent_message WHERE conversation_id = '$conversationId' " +
        "AND event_type = 'COMPACTION_SUMMARY' AND event_data LIKE '%$compactionTaskId%'")
    if ($legacyProjectionCount -ne 0) {
        throw 'Manual compaction wrote a retired AgentMessage compatibility projection.'
    }
    $compactionHistory = Invoke-ApiData -Path "/student/projects/$projectId/agent/conversations/$conversationId/messages?limit=50"
    $compactionHistoryTurn = @($compactionHistory.turns | Where-Object { [long]$_.taskId -eq $compactionTaskId })
    $compactionHistoryEvents = @($compactionHistoryTurn.events | Where-Object { [string]$_.eventType -eq 'COMPACTION_COMPLETED' })
    if ([string]$compactionHistory.projectionVersion -ne 'durable-task-history-v1' -or
        $compactionHistoryTurn.Count -ne 1 -or $compactionHistoryEvents.Count -ne 1) {
        throw 'Manual compaction was not visible through the durable task-turn history projection.'
    }
    $compactionAudit = @($compactionTask.compactions | Where-Object {
        [string]$_.scope -eq 'conversation' -and [long]$_.sourceMaxTaskId -eq $fifthSeedTaskId
    })
    if ($compactionAudit.Count -ne 1) {
        throw 'Task detail did not expose the safe conversation-scope compaction audit projection.'
    }
    $evidence.manualCompaction = $true
    $evidence.manualCompactionAuthority = $true
    $evidence.manualCompactionSourceMaxTaskId = $fifthSeedTaskId
    $evidence.manualCompactionExecutionEpoch = $recordExecutionEpoch

    if ($compactionConfigId) { Invoke-ApiData -Path "/student/model-configs/$compactionConfigId" -Method DELETE | Out-Null; $compactionConfigId = $null }
    if ($smallConfigId) { Invoke-ApiData -Path "/student/model-configs/$smallConfigId" -Method DELETE | Out-Null; $smallConfigId = $null }
    if ($unconfiguredConfigId) { Invoke-ApiData -Path "/student/model-configs/$unconfiguredConfigId" -Method DELETE | Out-Null; $unconfiguredConfigId = $null }
    Invoke-ApiData -Path "/student/model-configs/$configId" -Method DELETE | Out-Null
    $configId = $null
    Invoke-ApiData -Path "/student/projects/$projectId" -Method DELETE | Out-Null
    $projectId = $null
    $evidence.cleanup = $true

    if (-not $SkipNormalProfileCheck) {
        Stop-AcceptanceBackend
        Start-AcceptanceBackend -Profile 'local'
        $providers = Invoke-ApiData -Path '/student/model-configs/providers'
        $providerKeys = @($providers.PSObject.Properties.Name)
        $providerIds = @($providers.PSObject.Properties | ForEach-Object { $_.Value.id })
        if ($providerKeys -contains 'acceptance_scripted' -or $providerIds -contains 'acceptance_scripted') {
            throw '普通 local profile 暴露了 acceptance_scripted provider。'
        }
        $evidence.normalProfileProviderIsolation = $true
    }

    $evidence | ConvertTo-Json -Depth 10
} finally {
    try {
        if ($backendProcess -and -not $backendProcess.HasExited -and $token) {
            if ($compactionConfigId) {
                Invoke-ApiData -Path "/student/model-configs/$compactionConfigId" -Method DELETE | Out-Null
                if ($configId -eq $compactionConfigId) { $configId = $null }
            }
            if ($smallConfigId) { Invoke-ApiData -Path "/student/model-configs/$smallConfigId" -Method DELETE | Out-Null }
            if ($unconfiguredConfigId) { Invoke-ApiData -Path "/student/model-configs/$unconfiguredConfigId" -Method DELETE | Out-Null }
            if ($configId) { Invoke-ApiData -Path "/student/model-configs/$configId" -Method DELETE | Out-Null }
            if ($projectId) { Invoke-ApiData -Path "/student/projects/$projectId" -Method DELETE | Out-Null }
        }
    } catch {
        Write-Warning "验收资源清理失败：$($_.Exception.Message)"
    }
    try { Stop-AcceptanceBackend } catch { Write-Warning $_.Exception.Message }
    try { Stop-AcceptanceDatabase } catch { Write-Warning "Acceptance database shutdown failed: $($_.Exception.Message)" }
    try { Remove-OwnedWorkspace } catch { Write-Warning "Acceptance workspace cleanup failed: $($_.Exception.Message)" }
}
