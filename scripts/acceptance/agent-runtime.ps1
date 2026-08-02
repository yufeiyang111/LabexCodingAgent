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
        "--labex-agent.instance-id=acceptance-$runId-$Profile"
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
    Start-AcceptanceBackend -Profile 'acceptance,local'

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

    $completionEvents = Invoke-AgentStream -Message '[acceptance:evidence] persist completion evidence'
    $completionApproval = $completionEvents | Where-Object { $_.type -eq 'COMMAND_APPROVAL_REQUIRED' } | Select-Object -First 1
    if ($completionApproval) {
        $completionApprovalId = [string]$completionApproval.data.approvalId
        $completionTaskId = [long]$completionApproval.data.taskId
        Invoke-ApiData -Path "/student/projects/$projectId/agent/command-approvals/$completionApprovalId/decision" -Method POST -Body @{
            action = 'approve'; decisionIdempotencyKey = [Guid]::NewGuid().ToString()
        } | Out-Null
        Invoke-ApiData -Path "/student/projects/$projectId/agent/command-approvals/$completionApprovalId/execute" -Method POST | Out-Null
        $completionState = Wait-TaskTerminal -TaskId $completionTaskId
        if ($completionState.status -ne 'completed') { throw "Completion evidence task $completionTaskId status is $($completionState.status)." }
        $completionEvents = Get-TaskEvents -TaskId $completionTaskId
    }
    $completionEvent = Get-RequiredEvent -Events $completionEvents -Type 'COMPLETION_EVIDENCE'
    $completedState = Get-RequiredEvent -Events $completionEvents -Type 'RUN_STATE_COMPLETED'
    if (-not [bool]$completionEvent.data.satisfied) { throw 'Completion evidence was not satisfied.' }
    $completionTypes = @($completionEvents | ForEach-Object { $_.type })
    if ([array]::IndexOf($completionTypes, 'COMPLETION_EVIDENCE') -gt [array]::IndexOf($completionTypes, 'RUN_STATE_COMPLETED')) {
        throw 'COMPLETION_EVIDENCE must precede RUN_STATE_COMPLETED.'
    }
    $persistedEvidence = Invoke-ApiData -Path "/student/projects/$projectId/agent/tasks/$($completionEvent.data.taskId)/completion-evidence"
    if (-not [bool]$persistedEvidence.satisfied) { throw 'Completion evidence API did not return satisfied evidence.' }
    $evidence.completionEvidence = $true

    $unverifiedEvents = Invoke-AgentStream -Message '[acceptance:unverified] reject false completion'
    if ($unverifiedEvents | Where-Object { $_.type -eq 'RUN_STATE_COMPLETED' }) { throw 'Unverified edit incorrectly entered completed state.' }
    $unverifiedTaskId = [long](($unverifiedEvents | Where-Object { $_.data.taskId } | Select-Object -First 1).data.taskId)
    $unverifiedTask = Wait-TaskTerminal -TaskId $unverifiedTaskId
    if ($unverifiedTask.status -eq 'completed') { throw 'Unverified edit task incorrectly completed.' }
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
        Restart-AcceptanceBackend
        $decision = Invoke-ApiData -Path "/student/projects/$projectId/agent/command-approvals/$approvalId/decision" -Method POST -Body @{
            action = $action; decisionIdempotencyKey = [Guid]::NewGuid().ToString()
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
    try { Remove-OwnedWorkspace } catch { Write-Warning "Acceptance workspace cleanup failed: $($_.Exception.Message)" }
}










