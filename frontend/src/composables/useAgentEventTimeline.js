import { createInternalReasoningBlockStreamFilter, createInternalReasoningTagStreamFilter, stripInternalReasoningBlocks, stripInternalReasoningTags } from '../utils/agentMarkdown.js'
import { isWaitingInputMisNarration } from '../utils/agentThinking.js'
import { applyStructuredExecutionStatus, projectToolResultStatus, upsertDurableToolCallState } from './agentToolCallState.js'
import { attachDurableInteraction, resolveDurableInteraction } from './agentInteractionProjection.js'
import { applyTokenUsageEvent } from './cacheTelemetryStatus.js'
import { isRecoverableAgentRunState, normalizeAgentRunState } from './agentRunState.js'
import { projectVisibleAgentError } from './agentErrorProjection.js'

function toolResultStatus(success, result) {
  if (success === false) return 'error'
  const text = String(result || '')
  if (text.includes('status=FAIL')) return 'error'
  if (text.includes('status=UNAVAILABLE')) return 'warning'
  return 'completed'
}
function resolveWaitingInteractions(assistantMsg) {
  for (const call of assistantMsg?.toolCalls || []) {
    if (!['waiting_user', 'waiting_approval'].includes(call.status) && call.interactionStatus !== 'resuming') continue
    call.status = 'completed'
    call.durableStatus = 'completed'
    call.interactionStatus = 'resolved'
  }
}

export function useAgentEventTimeline(options) {
  const {
    recordTaskEventCursor,
    currentAgentSession,
    scheduleAgentRender,
    startThinkingReveal,
    flushThinkingDisplay,
    trackFileChange,
    changesRefreshKey,
    attachCommandApproval,
    updateCommandApprovalLifecycle,
    attachUserQuestion,
    stopMessageTimer,
    agentLoading,
    logTaskRecovery,
    contextUsageStatus,
    reduceContextManagementEvent,
    tokenUsage,
    sessionHistory,
    currentSessionName,
    onTokenUsageProjected
  } = options

  function handleAgentEvent(event, assistantMsg) {
    const type = event.type
    const data = event.data || {}
    recordTaskEventCursor(data.taskId || assistantMsg?.taskId, event.eventId)
    switch (type) {
      case 'SESSION':
        currentAgentSession.value = data
        assistantMsg.taskId = data.taskId || null
        assistantMsg.conversationId = data.conversationId || assistantMsg.conversationId || null
        if (assistantMsg.timing) assistantMsg.timing.taskId = assistantMsg.taskId
        break
      case 'THINK_START':
        if (assistantMsg.thinking) {
          assistantMsg.thinkingBlocks.push({ content: assistantMsg.thinking, summary: stripInternalReasoningBlocks(data.summary || ''), iteration: data.iteration || 0, _open: false, _order: (assistantMsg._nextOrder = (assistantMsg._nextOrder || 0) + 1) })
        }
        assistantMsg.thinking = ''
        assistantMsg._thinkingTagFilter = createInternalReasoningTagStreamFilter()
        break
      case 'THINK_DELTA':
        assistantMsg._thinkingTagFilter ??= createInternalReasoningTagStreamFilter()
        assistantMsg.thinking += assistantMsg._thinkingTagFilter.push(data.delta)
        assistantMsg._thinkingDisplay = assistantMsg.thinking
        scheduleAgentRender()
        break
      case 'THINK_SNAPSHOT':
        assistantMsg._thinkingTagFilter?.reset()
        assistantMsg._thinkingTagFilter = createInternalReasoningTagStreamFilter()
        assistantMsg.thinking = stripInternalReasoningTags(data.content || assistantMsg.thinking || '')
        assistantMsg._thinkingDisplay = assistantMsg.thinking
        scheduleAgentRender()
        break
      case 'THINK':
        assistantMsg._thinkingTagFilter?.reset()
        if (data.content) {
          if (isWaitingInputMisNarration(assistantMsg.thinking) || isWaitingInputMisNarration(data.content)) {
            assistantMsg.thinking = ''
            assistantMsg._thinkingDisplay = ''
            break
          }
          if (assistantMsg.thinking) {
            assistantMsg.thinkingBlocks.push({ content: assistantMsg.thinking, summary: stripInternalReasoningBlocks(data.summary || ''), iteration: data.iteration || 0, _open: false, _order: (assistantMsg._nextOrder = (assistantMsg._nextOrder || 0) + 1) })
            assistantMsg.thinking = ''
            assistantMsg._thinkingDisplay = ''
          } else {
            assistantMsg.thinking = stripInternalReasoningTags(data.content)
            startThinkingReveal(assistantMsg)
          }
        }
        break
      case 'TOOL_CALL':
        assistantMsg._thinkingTagFilter?.reset()
        if (assistantMsg.thinking && !assistantMsg.streamSaving) {
          flushThinkingDisplay(assistantMsg)
          assistantMsg.thinkingBlocks.push({ content: assistantMsg.thinking, summary: stripInternalReasoningBlocks(data.summary || data.tool || ''), iteration: 0, _open: false, _order: (assistantMsg._nextOrder = (assistantMsg._nextOrder || 0) + 1) })
          assistantMsg.thinking = ''
          assistantMsg._thinkingDisplay = ''
        }
        {
          const toolCallId = data.toolCallId || ''
          const existing = toolCallId ? assistantMsg.toolCalls.find(call => call?.toolCallId === toolCallId) : null
          if (existing) {
            // snapshot 与 SSE 重放重叠时按 toolCallId 幂等合并，不重复建卡片。
            existing.name = existing.name || data.tool
            existing.args = existing.args ?? data.arguments
            if (!existing.summary) existing.summary = data.summary
          } else {
            assistantMsg.toolCalls.push({ name: data.tool, args: data.arguments, summary: data.summary, result: null, status: 'running', toolCallId: data.toolCallId || '', startedAt: data.startedAt || Date.now(), execution: { phase: 'tool_delegate', elapsedMs: 0 }, _order: (assistantMsg._nextOrder = (assistantMsg._nextOrder || 0) + 1) })
          }
        }
        scheduleAgentRender()
        break
      case 'TOOL_CALL_STATE':
        upsertDurableToolCallState(assistantMsg, data)
        scheduleAgentRender()
        break
      case 'TOOL_EXECUTION_STARTED':
      case 'TOOL_PHASE_CHANGED':
      case 'TOOL_EXECUTION_COMPLETED':
      case 'TOOL_EXECUTION_FAILED':
      case 'TOOL_TIMED_OUT': {
        const toolCall = assistantMsg.toolCalls.find(call => call.toolCallId && call.toolCallId === data.toolCallId)
          || assistantMsg.toolCalls.at(-1)
        if (toolCall) {
          toolCall.execution = { phase: data.phase || 'tool_delegate', elapsedMs: data.elapsedMs || 0,
            delegateMs: data.delegateMs || 0, beforeSnapshotMs: data.beforeSnapshotMs || 0,
            afterSnapshotMs: data.afterSnapshotMs || 0, snapshotDiffMs: data.snapshotDiffMs || 0,
            postEditMs: data.postEditMs || 0, contextMs: data.contextMs || 0, error: data.error || '' }
          if (type === 'TOOL_EXECUTION_FAILED' || type === 'TOOL_TIMED_OUT') toolCall.status = 'error'
        }
        scheduleAgentRender()
        break
      }
      case 'OBSERVE':
        if (assistantMsg.toolCalls.length > 0) {
          const observed = (data.toolCallId
            ? assistantMsg.toolCalls.find(call => call.toolCallId === data.toolCallId)
            : null) || assistantMsg.toolCalls[assistantMsg.toolCalls.length - 1]
          observed.result = data.result || data.content
          const preservesWaitingInteraction = data.success === false
            && (observed.questionRequest || observed.permissionRequest || observed.networkRequest)
          const resultProjection = projectToolResultStatus(data.success, observed.result)
          observed.status = preservesWaitingInteraction
            ? (observed.permissionRequest || observed.networkRequest ? 'waiting_approval' : 'waiting_user')
            : resultProjection.status
          observed.verificationStatus = resultProjection.verificationStatus
          observed.projection = { resultChars: data.resultChars || 0, modelProjectionChars: data.modelProjectionChars || 0,
            truncated: data.modelProjectionTruncated === true }
          applyStructuredExecutionStatus(observed, data.executionStatus)
          if (data.diff) {
            observed.hasDiff = true
            trackFileChange(data, observed)
          }
          if (data.pendingChangeId) changesRefreshKey.value++
        }
        scheduleAgentRender()
        break
      case 'COMMAND_APPROVAL_REQUIRED':
        assistantMsg.taskId = data.taskId || assistantMsg.taskId || null
        assistantMsg.resumeTaskEventsAfterStream = true
        attachCommandApproval(assistantMsg, data)
        scheduleAgentRender()
        break
      case 'COMMAND_APPROVAL_DECIDED':
      case 'COMMAND_APPROVAL_REJECTED':
      case 'COMMAND_APPROVAL_EXPIRED':
      case 'COMMAND_EXECUTION_STARTED':
      case 'COMMAND_EXECUTION_COMPLETED':
      case 'COMMAND_EXECUTION_FAILED':
      case 'COMMAND_EXECUTION_INTERRUPTED':
      case 'RUN_COMMAND_APPROVAL_RESUME_QUEUED':
      case 'COMMAND_APPROVAL_RESUME_DEFERRED':
        assistantMsg.taskId = data.taskId || assistantMsg.taskId || null
        assistantMsg.resumeTaskEventsAfterStream = true
        if (type === 'RUN_COMMAND_APPROVAL_RESUME_QUEUED') {
          assistantMsg.runState = normalizeAgentRunState(data.state || data.taskStatus) || 'recovering'
          assistantMsg.isStreaming = true
          agentLoading.value = true
        }
        updateCommandApprovalLifecycle(assistantMsg, type, data)
        scheduleAgentRender()
        break
      case 'NETWORK_ACCESS_ASK':
        assistantMsg.taskId = data.taskId || assistantMsg.taskId || null
        assistantMsg.resumeTaskEventsAfterStream = true
        attachDurableInteraction(assistantMsg, 'network', data)
        scheduleAgentRender()
        break
      case 'PERMISSION_ASK':
        assistantMsg.taskId = data.taskId || assistantMsg.taskId || null
        assistantMsg.resumeTaskEventsAfterStream = true
        attachDurableInteraction(assistantMsg, 'permission', data)
        scheduleAgentRender()
        break
      case 'USER_QUESTION':
        assistantMsg.taskId = data.taskId || assistantMsg.taskId || null
        assistantMsg.resumeTaskEventsAfterStream = true
        attachUserQuestion(assistantMsg, data)
        scheduleAgentRender()
        break
      case 'WORKSPACE_WAITING':
        assistantMsg.taskId = data.taskId || assistantMsg.taskId || null
        assistantMsg.workspaceWaiting = data
        break
      case 'ENVIRONMENT_BLOCKED':
        assistantMsg.taskId = data.taskId || assistantMsg.taskId || null
        assistantMsg.runState = normalizeAgentRunState(data.taskStatus) || 'waiting_environment'
        assistantMsg.environmentBlocker = data
        assistantMsg.content = data.detail || '依赖环境暂时不可用，请恢复后重试。'
        assistantMsg.isStreaming = false
        stopMessageTimer(assistantMsg)
        break
      case 'CONTEXT_LIMIT_BLOCKED':
        assistantMsg.taskId = data.taskId || assistantMsg.taskId || null
        assistantMsg.runState = normalizeAgentRunState(data.taskStatus) || 'waiting_environment'
        assistantMsg.contextLimitBlocker = data
        assistantMsg.environmentBlocker = {
          ...data,
          blockerCode: data.reasonCode || 'CONTEXT_LIMIT_BLOCKED',
          detail: data.message || '当前模型无法容纳本轮上下文。'
        }
        assistantMsg.content = formatContextLimitMessage(data)
        assistantMsg.isStreaming = false
        stopMessageTimer(assistantMsg)
        scheduleAgentRender()
        break
      case 'PLAN_UPDATE':
        assistantMsg.plan = data.summary || data.plan || null
        assistantMsg.planJson = data.planJson || null
        break
      case 'COMPLETION_EVIDENCE':
        assistantMsg.taskId = data.taskId || assistantMsg.taskId || null
        assistantMsg.completionEvidence = data
        scheduleAgentRender()
        break
      case 'RUN_INTERACTION_RESUME_QUEUED':
        assistantMsg.taskId = data.taskId || assistantMsg.taskId || null
        assistantMsg.runState = normalizeAgentRunState(data.state || data.taskStatus) || 'recovering'
        assistantMsg.isStreaming = true
        agentLoading.value = true
        resolveDurableInteraction(assistantMsg, data)
        scheduleAgentRender()
        break
      case 'RUN_ENVIRONMENT_RESUME':
      case 'RUN_WORKSPACE_RESUME':
        assistantMsg.taskId = data.taskId || assistantMsg.taskId || null
        assistantMsg.runState = normalizeAgentRunState(data.state || data.taskStatus) || 'queued'
        assistantMsg.environmentBlocker = null
        assistantMsg.contextLimitBlocker = null
        assistantMsg.workspaceWaiting = null
        assistantMsg.error = null
        assistantMsg.isStreaming = true
        agentLoading.value = true
        scheduleAgentRender()
        break
      case 'RUN_MODEL_RETRY_SCHEDULED':
        assistantMsg.taskId = data.taskId || assistantMsg.taskId || null
        assistantMsg.runState = normalizeAgentRunState(data.state || data.taskStatus) || 'retrying'
        assistantMsg.resumeTaskEventsAfterStream = true
        assistantMsg.isStreaming = false
        stopMessageTimer(assistantMsg)
        agentLoading.value = false
        logTaskRecovery('MODEL_RETRY_SCHEDULED', {
          taskId: assistantMsg.taskId,
          attempt: data.attempt || 0,
          nextRetryAt: data.nextRetryAt || '',
          taskStatus: assistantMsg.runState
        })
        scheduleAgentRender()
        break
      case 'RUN_MODEL_RETRY_STARTED':
        assistantMsg.taskId = data.taskId || assistantMsg.taskId || null
        assistantMsg.runState = normalizeAgentRunState(data.state || data.taskStatus) || 'recovering'
        assistantMsg.isStreaming = true
        agentLoading.value = true
        scheduleAgentRender()
        break
      case 'RUN_STATE_QUEUED':
      case 'RUN_STATE_PREPARING':
      case 'RUN_STATE_RUNNING':
      case 'RUN_STATE_RECOVERING':
      case 'RUN_STATE_RETRYING': {
        const fallbackState = type.substring('RUN_STATE_'.length).toLowerCase()
        assistantMsg.taskId = data.taskId || assistantMsg.taskId || null
        assistantMsg.runState = normalizeAgentRunState(data.state || data.taskStatus) || fallbackState
        assistantMsg.isStreaming = true
        agentLoading.value = true
        scheduleAgentRender()
        break
      }
      case 'RUN_STATE_COMPLETED':
      case 'RUN_STATE_FAILED':
      case 'RUN_STATE_CANCELLED': {
        const fallbackState = type.substring('RUN_STATE_'.length).toLowerCase()
        assistantMsg.taskId = data.taskId || assistantMsg.taskId || null
        assistantMsg.runState = normalizeAgentRunState(data.state || data.taskStatus) || fallbackState
        resolveWaitingInteractions(assistantMsg)
        assistantMsg.isStreaming = false
        stopMessageTimer(assistantMsg)
        agentLoading.value = false
        scheduleAgentRender()
        break
      }
      case 'FINAL_DELTA':
        if (isRecoverableAgentRunState(assistantMsg.runState)) break
        assistantMsg._finalReasoningFilter ??= createInternalReasoningBlockStreamFilter()
        assistantMsg.content += assistantMsg._finalReasoningFilter.push(data.delta)
        scheduleAgentRender()
        break
      case 'FINAL':
        if (!isRecoverableAgentRunState(assistantMsg.runState) && data.content && !assistantMsg.error) {
          assistantMsg.content = stripInternalReasoningBlocks(data.content)
        }
        assistantMsg._finalReasoningFilter?.reset()
        break
      case 'TASK_PAUSED':
        assistantMsg.taskId = data.taskId || assistantMsg.taskId || null
        assistantMsg.runState = normalizeAgentRunState(data.taskStatus) || assistantMsg.runState || ''
        assistantMsg.waitingForCommandApproval = data.reason === 'command_approval'
        assistantMsg.resumeTaskEventsAfterStream = data.resumeAgentLoop === true
          || ['workspace_checkout', 'command_approval', 'permission', 'network', 'question'].includes(data.reason)
        assistantMsg.isStreaming = false
        stopMessageTimer(assistantMsg)
        agentLoading.value = false
        logTaskRecovery('TASK_PAUSED', {
          taskId: data.taskId || assistantMsg.taskId,
          taskStatus: data.taskStatus || 'waiting_approval',
          reason: data.reason || '',
          resumeAgentLoop: data.resumeAgentLoop === true
        })
        break
      case 'DONE': {
        assistantMsg.taskId = data.taskId || assistantMsg.taskId || null
        const doneState = normalizeAgentRunState(data.taskStatus || assistantMsg.runState)
        if (doneState) assistantMsg.runState = doneState
        flushThinkingDisplay(assistantMsg)
        assistantMsg.isStreaming = false
        stopMessageTimer(assistantMsg)
        agentLoading.value = false
        if (isRecoverableAgentRunState(assistantMsg.runState)) {
          logTaskRecovery('LEGACY_PSEUDO_TERMINAL_IGNORED', {
            taskId: assistantMsg.taskId,
            taskStatus: assistantMsg.runState,
            eventType: type
          })
          break
        }
        resolveWaitingInteractions(assistantMsg)
        if (data.waitingForApproval) {
          assistantMsg.waitingForCommandApproval = true
          logTaskRecovery('TASK_WAITING_APPROVAL', {
            taskId: data.taskId || assistantMsg.taskId,
            taskStatus: data.taskStatus || 'waiting_approval',
            resumeAgentLoop: data.resumeAgentLoop === true
          })
        }
        break
      }
      case 'CONTEXT_STATUS':
        contextUsageStatus.value = data
        break
      case 'COMPACTION_STARTED':
      case 'COMPACTION_PROGRESS':
      case 'CONTEXT_PRUNED':
      case 'CONTEXT_TOOL_SCHEMA_REDUCED':
      case 'COMPACTION_COMPLETED':
      case 'COMPACTION_FAILED':
      case 'COMPACTION_CANCELLED':
        reduceContextManagementEvent(type, data, assistantMsg)
        scheduleAgentRender()
        break
      case 'TOKEN_USAGE': {
        applyTokenUsageEvent(tokenUsage.value, data)
        onTokenUsageProjected?.(data)
        const existingSession = sessionHistory.value.find(session => session.conversationId === currentAgentSession.value?.conversationId)
        if (existingSession) {
          existingSession.promptTokens += (data.promptTokens || 0)
          existingSession.completionTokens += (data.completionTokens || 0)
          existingSession.totalTokens += (data.totalTokens || 0)
          existingSession.callCount++
        } else {
          sessionHistory.value.push({
            conversationId: currentAgentSession.value?.conversationId,
            title: currentSessionName.value,
            promptTokens: data.promptTokens || 0,
            completionTokens: data.completionTokens || 0,
            totalTokens: data.totalTokens || 0,
            callCount: 1
          })
        }
        break
      }
      case 'ERROR': {
        const message = data.message || '模型服务调用失败'
        assistantMsg.error = message
        assistantMsg.content = projectVisibleAgentError(assistantMsg.content, message)
        assistantMsg.isStreaming = false
        stopMessageTimer(assistantMsg)
        break
      }
      case 'INTERRUPTED':
        assistantMsg.content += '\n[已中断]'
        assistantMsg.isStreaming = false
        stopMessageTimer(assistantMsg)
        break
    }
  }

  return { handleAgentEvent }
}

function formatContextLimitMessage(data = {}) {
  const message = data.message || '当前模型无法容纳本轮上下文。'
  const remediation = Array.isArray(data.remediation) ? data.remediation.filter(Boolean) : []
  if (!remediation.length) return message
  return `${message}\n\n可采取的措施：\n${remediation.map(item => `- ${item}`).join('\n')}`
}
