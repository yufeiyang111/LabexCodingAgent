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
$smallConfigId = $null
$unconfiguredConfigId = $null
$evidence = [ordered]@{
    runId = $runId
    backendPort = $BackendPort
    questionRestart = $false
    permissionRestart = $false
    commandApproveRestart = $false
    commandRejectRestart = $false
    checkoutContention = $false
    runMessagePartProjection = $false
    toolPartAuthority = $false
    manualCompaction = $false
    staticContextBlocked = $false
    contextWindowUnconfigured = $false
    completionEvidence = $false
    unverifiedEditRejected = $false
    normalProfileProviderIsolation = $false
    lifecycleTransitionAudit = $false
    singleAuthoritativeTerminalEvent = $false
    authoritativeFailureProjection = $false
    authoritativeCancellationProjection = $false
    isolatedDatabase = $false
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
        '-jar', $JarPath,
        "--server.port=$BackendPort",
        "--spring.profiles.active=$Profile",
        "--labex-agent.project-base-path=$workspaceRoot",
        "--labex-agent.instance-id=acceptance-$runId-$Profile",
        '--spring.datasource.driver-class-name=org.h2.Driver',
        "--spring.datasource.url=jdbc:h2:tcp://127.0.0.1:$h2ServerPort/./labex-agent;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_ON_EXIT=FALSE",
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
    $config = Invoke-ApiData -Path '/student/model-configs' -Method POST -Body @{
        configName = 'Acceptance Scripted'; provider = 'acceptance_scripted'; modelName = 'acceptance-only'
        apiKey = 'acceptance-placeholder-not-a-secret'; baseUrl = 'acceptance://scripted'; maxTokens = 4096; contextWindowTokens = 32768
        temperature = 0.0; isDefault = $true; promptCacheKeyEnabled = $false
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

    $seedEvents = Invoke-AgentStream -Message '[acceptance:isolation:compaction-seed]'
    $seedDone = Get-RequiredEvent -Events $seedEvents -Type 'DONE'
    $seedTaskId = [long](($seedEvents | Where-Object { $_.data.taskId } | Select-Object -First 1).data.taskId)
    $seedTask = Wait-TaskTerminal -TaskId $seedTaskId
    $conversationId = [string]$seedTask.conversationId
    $compaction = Invoke-ApiData -Path "/student/projects/$projectId/agent/conversations/$conversationId/compact" -Method POST -Body @{ modelConfigId = $configId }
    $compactionTask = Wait-TaskTerminal -TaskId ([long]$compaction.taskId)
    if ($compactionTask.status -ne 'completed') { throw "压缩任务状态为 $($compactionTask.status)。" }
    $evidence.manualCompaction = $true

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










