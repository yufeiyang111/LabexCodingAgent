import { createInternalReasoningBlockStreamFilter, createInternalReasoningTagStreamFilter, stripInternalReasoningBlocks, stripInternalReasoningTags } from '../utils/agentMarkdown.js'
import { isWaitingInputMisNarration } from '../utils/agentThinking.js'
import { applyStructuredExecutionStatus, projectToolResultStatus, upsertDurableToolCallState } from './agentToolCallState.js'
import { attachCommandApprovalState, updateCommandApprovalState } from './agentCommandApprovalState.js'
import { attachDurableInteraction, resolveDurableInteraction } from './agentInteractionProjection.js'
import { isRecoverableAgentRunState, normalizeAgentRunState } from './agentRunState.js'
import { projectVisibleAgentError } from './agentErrorProjection.js'

function nextOrder(message) {
  message._nextOrder = (message._nextOrder || 0) + 1
  return message._nextOrder
}

function normalizedToken(value) {
  return Number.isFinite(value) ? value : null
}

function contextManagementEvents(message) {
  message.contextManagementEvents = message.contextManagementEvents || []
  return message.contextManagementEvents
}

function latestPendingContextManagementEvent(message, taskId = null) {
  const events = contextManagementEvents(message)
  for (let index = events.length - 1; index >= 0; index -= 1) {
    const event = events[index]
    if (taskId != null && String(event.taskId ?? '') !== String(taskId)) continue
    if ((event.phase === 'compacting' || event.phase === 'fallback') && (event.status === 'running' || event.status === 'warning')) return event
  }
  return null
}

function withContextTask(values, taskId) {
  return taskId == null ? values : { ...values, taskId }
}

function releasedTokens(tokensBefore, tokensAfter) {
  return Number.isFinite(tokensBefore) && Number.isFinite(tokensAfter)
    ? Math.max(0, tokensBefore - tokensAfter)
    : null
}

function createContextManagementEvent(message, values) {
  const event = { kind: 'context-management', _order: nextOrder(message), ...values }
  contextManagementEvents(message).push(event)
  return event
}

/**
 * 将实时 SSE 与历史回放的上下文管理事件归并为可更新的时间线状态。
 */
export function reduceContextManagementEvent(type, data = {}, message) {
  const tokensBefore = normalizedToken(data.tokensBefore)
  const tokensAfter = normalizedToken(data.tokensAfter)
  const strategy = typeof data.strategy === 'string' ? data.strategy : ''
  const taskId = data.taskId ?? null
  const sessionId = data.sessionId ?? null

  switch (type) {
    case 'COMPACTION_STARTED': {
      const pending = taskId == null ? null : latestPendingContextManagementEvent(message, taskId)
      if (pending) {
        pending.strategy = strategy || pending.strategy
        pending.tokensBefore = tokensBefore ?? pending.tokensBefore
        pending.tokensAfter = tokensAfter ?? pending.tokensAfter
        if (sessionId) pending.sessionId = sessionId
      } else {
        createContextManagementEvent(message, withContextTask({
          phase: 'compacting', status: 'running', strategy, tokensBefore, tokensAfter,
          releasedTokens: null, reason: '', ...(sessionId ? { sessionId } : {})
        }, taskId))
      }
      return true
    }
    case 'COMPACTION_PROGRESS': {
      const pending = latestPendingContextManagementEvent(message, taskId)
      if (pending) {
        pending.progress = typeof data.phase === 'string' ? data.phase : pending.progress || ''
        if (sessionId) pending.sessionId = sessionId
      }
      return true
    }
    case 'COMPACTION_CANCELLED': {
      const pending = latestPendingContextManagementEvent(message, taskId)
      const reason = typeof data.reason === 'string' ? data.reason : ''
      if (pending) {
        pending.status = 'cancelled'
        pending.phase = 'cancelled'
        pending.reason = reason
      } else {
        createContextManagementEvent(message, withContextTask({
          phase: 'cancelled', status: 'cancelled', strategy, tokensBefore, tokensAfter,
          releasedTokens: null, reason, ...(sessionId ? { sessionId } : {})
        }, taskId))
      }
      return true
    }
    case 'CONTEXT_PRUNED':
      createContextManagementEvent(message, withContextTask({
        phase: 'pruned', status: 'completed', strategy, tokensBefore, tokensAfter,
        releasedTokens: releasedTokens(tokensBefore, tokensAfter), reason: ''
      }, taskId))
      return true
    case 'CONTEXT_TOOL_SCHEMA_REDUCED':
      createContextManagementEvent(message, withContextTask({
        phase: 'tools-reduced', status: 'completed', strategy, tokensBefore, tokensAfter,
        releasedTokens: releasedTokens(tokensBefore, tokensAfter), reason: '',
        toolCountBefore: Number.isFinite(Number(data.toolCountBefore)) ? Number(data.toolCountBefore) : null,
        toolCountAfter: Number.isFinite(Number(data.toolCountAfter)) ? Number(data.toolCountAfter) : null,
        recoveryAttempt: Number.isFinite(Number(data.recoveryAttempt)) ? Number(data.recoveryAttempt) : null
      }, taskId))
      return true
    case 'COMPACTION_FAILED': {
      const pending = latestPendingContextManagementEvent(message, taskId)
      const reason = typeof data.reason === 'string' ? data.reason : ''
      if (pending) {
        pending.status = 'warning'
        pending.phase = 'fallback'
        pending.strategy = strategy || pending.strategy
        pending.tokensBefore = tokensBefore ?? pending.tokensBefore
        pending.tokensAfter = tokensAfter ?? pending.tokensAfter
        pending.releasedTokens = releasedTokens(pending.tokensBefore, pending.tokensAfter)
        pending.reason = reason
      } else {
        createContextManagementEvent(message, withContextTask({
          phase: 'fallback', status: 'warning', strategy, tokensBefore, tokensAfter,
          releasedTokens: releasedTokens(tokensBefore, tokensAfter), reason
        }, taskId))
      }
      return true
    }
    case 'COMPACTION_COMPLETED': {
      const pending = latestPendingContextManagementEvent(message, taskId)
      if (pending) {
        pending.status = 'completed'
        pending.phase = pending.phase === 'fallback' || strategy.includes('fallback') ? 'fallback' : 'compacting'
        pending.strategy = strategy || pending.strategy
        pending.tokensBefore = tokensBefore ?? pending.tokensBefore
        pending.tokensAfter = tokensAfter ?? pending.tokensAfter
        pending.releasedTokens = releasedTokens(pending.tokensBefore, pending.tokensAfter)
      } else {
        createContextManagementEvent(message, withContextTask({
          phase: strategy.includes('fallback') ? 'fallback' : 'compacting', status: 'completed', strategy,
          tokensBefore, tokensAfter, releasedTokens: releasedTokens(tokensBefore, tokensAfter), reason: ''
        }, taskId))
      }
      return true
    }
    default:
      return false
  }
}

export function reduceHistoryEvent(type, data, message, callbacks = {}) {
  switch (type) {
    case 'SESSION':
      message.taskId = data.taskId || message.taskId || null
      break
    case 'THINK_START':
      if (message.thinking) {
        message.thinkingBlocks = message.thinkingBlocks || []
        message.thinkingBlocks.push({ content: message.thinking, summary: stripInternalReasoningBlocks(data.summary || ''), _open: false, _order: nextOrder(message) })
        message.thinking = ''
      }
      message._hasThinkStart = true
      message._thinkingTagFilter = createInternalReasoningTagStreamFilter()
      break
    case 'THINK_DELTA':
      message._thinkingTagFilter ??= createInternalReasoningTagStreamFilter()
      message.thinking += message._thinkingTagFilter.push(data.delta)
      message._thinkingDisplay = message.thinking
      break
    case 'THINK_SNAPSHOT':
      message._thinkingTagFilter?.reset()
      message._thinkingTagFilter = createInternalReasoningTagStreamFilter()
      message.thinking = stripInternalReasoningTags(data.content || message.thinking || '')
      message._thinkingDisplay = message.thinking
      break
    case 'THINK':
      message._thinkingTagFilter?.reset()
      if (data.content) {
        if (isWaitingInputMisNarration(message.thinking) || isWaitingInputMisNarration(data.content)) {
          message.thinking = ''
          message._thinkingDisplay = ''
          message._hasThinkStart = false
          break
        }
        message.thinkingBlocks = message.thinkingBlocks || []
        if (message.thinking) message.thinkingBlocks.push({ content: message.thinking, summary: stripInternalReasoningBlocks(data.summary || ''), _open: false, _order: nextOrder(message) })
        else if (!message._hasThinkStart) message.thinkingBlocks.push({ content: stripInternalReasoningTags(data.content), summary: stripInternalReasoningBlocks(data.summary || ''), _open: false, _order: nextOrder(message) })
        message.thinking = ''
        message._hasThinkStart = false
      }
      break
    case 'TOOL_CALL':
      message._thinkingTagFilter?.reset()
      if (message.thinking) {
        message.thinkingBlocks = message.thinkingBlocks || []
        message.thinkingBlocks.push({ content: message.thinking, summary: stripInternalReasoningBlocks(data.summary || data.tool || ''), _open: false, _order: nextOrder(message) })
        message.thinking = ''
      }
      message.toolCalls = message.toolCalls || []
      {
        const toolCallId = data.toolCallId || ''
        const existing = toolCallId ? message.toolCalls.find(call => call?.toolCallId === toolCallId) : null
        if (existing) {
          // snapshot 与 SSE 重放重叠时按 toolCallId 幂等合并，不重复建卡片。
          existing.name = existing.name || data.tool
          existing.args = existing.args ?? data.arguments
          if (!existing.summary) existing.summary = data.summary
        } else {
          message.toolCalls.push({ name: data.tool, args: data.arguments, summary: data.summary, result: null, status: 'running', toolCallId: data.toolCallId || '', startedAt: data.startedAt || Date.now(), execution: { phase: 'tool_delegate', elapsedMs: 0 }, _order: nextOrder(message) })
        }
      }
      break
    case 'TOOL_CALL_STATE':
      upsertDurableToolCallState(message, data)
      break
    case 'TOOL_EXECUTION_STARTED':
    case 'TOOL_PHASE_CHANGED':
    case 'TOOL_EXECUTION_COMPLETED':
    case 'TOOL_EXECUTION_FAILED':
    case 'TOOL_TIMED_OUT': {
      const toolCall = message.toolCalls?.find(call => call.toolCallId && call.toolCallId === data.toolCallId)
        || message.toolCalls?.at(-1)
      if (toolCall) {
        toolCall.execution = { phase: data.phase || 'tool_delegate', elapsedMs: data.elapsedMs || 0,
          delegateMs: data.delegateMs || 0, beforeSnapshotMs: data.beforeSnapshotMs || 0,
          afterSnapshotMs: data.afterSnapshotMs || 0, snapshotDiffMs: data.snapshotDiffMs || 0,
          postEditMs: data.postEditMs || 0, contextMs: data.contextMs || 0, error: data.error || '' }
        if (type === 'TOOL_EXECUTION_FAILED' || type === 'TOOL_TIMED_OUT') toolCall.status = 'error'
      }
      break
    }
    case 'OBSERVE': {
      const toolCall = (data.toolCallId
        ? message.toolCalls?.find(call => call.toolCallId === data.toolCallId)
        : null) || message.toolCalls?.at(-1)
      if (toolCall) {
        toolCall.result = data.result || data.content
        const preservesWaitingInteraction = data.success === false
          && (toolCall.questionRequest || toolCall.permissionRequest || toolCall.networkRequest)
        const resultProjection = projectToolResultStatus(data.success, toolCall.result)
        toolCall.status = preservesWaitingInteraction
          ? (toolCall.permissionRequest || toolCall.networkRequest ? 'waiting_approval' : 'waiting_user')
          : resultProjection.status
        toolCall.verificationStatus = resultProjection.verificationStatus
        toolCall.projection = { resultChars: data.resultChars || 0, modelProjectionChars: data.modelProjectionChars || 0,
          truncated: data.modelProjectionTruncated === true }
        applyStructuredExecutionStatus(toolCall, data.executionStatus)
      }
      if (data.pendingChangeId) callbacks.onPendingChange?.()
      break
    }
    case 'COMMAND_APPROVAL_REQUIRED':
      attachCommandApprovalState(message, data)
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
      if (data.taskId != null) message.taskId = data.taskId
      updateCommandApprovalState(message, type, data)
      break
    case 'NETWORK_ACCESS_ASK':
      attachDurableInteraction(message, 'network', data)
      break
    case 'PERMISSION_ASK':
      attachDurableInteraction(message, 'permission', data)
      break
    case 'USER_QUESTION': callbacks.onUserQuestion?.(message, data); break
    case 'WORKSPACE_WAITING':
      message.taskId = data.taskId || message.taskId || null
      message.runState = normalizeAgentRunState(data.taskStatus) || 'waiting_workspace'
      message.workspaceWaiting = data
      break
    case 'ENVIRONMENT_BLOCKED':
      message.taskId = data.taskId || message.taskId || null
      message.runState = normalizeAgentRunState(data.taskStatus) || 'waiting_environment'
      message.environmentBlocker = data
      message.content = data.detail || data.message || message.content || '依赖环境暂时不可用，请恢复后重试。'
      break
    case 'CONTEXT_LIMIT_BLOCKED':
      message.taskId = data.taskId || message.taskId || null
      message.runState = normalizeAgentRunState(data.taskStatus) || 'waiting_environment'
      message.contextLimitBlocker = data
      message.environmentBlocker = {
        ...data,
        blockerCode: data.reasonCode || 'CONTEXT_LIMIT_BLOCKED',
        detail: data.message || '当前模型无法容纳本轮上下文。'
      }
      break
    case 'PLAN_UPDATE': message.plan = data.summary || data.plan || null; message.planJson = data.planJson || null; break
    case 'COMPLETION_EVIDENCE':
      message.taskId = data.taskId || message.taskId || null
      if (data.satisfied === true) {
        message.completionEvidence = data
        message.completionBlockedEvidence = null
      } else {
        message.completionEvidence = null
        message.completionBlockedEvidence = data
        message.pendingFinalContent = ''
        message.hasPendingFinalDraft = false
      }
      break
    case 'RUN_INTERACTION_RESUME_QUEUED':
      message.taskId = data.taskId || message.taskId || null
      resolveDurableInteraction(message, data)
      break
    case 'RUN_MODEL_RETRY_SCHEDULED':
      message.taskId = data.taskId || message.taskId || null
      message.runState = normalizeAgentRunState(data.state || data.taskStatus) || 'retrying'
      message.isStreaming = false
      break
    case 'RUN_MODEL_RETRY_STARTED':
      message.taskId = data.taskId || message.taskId || null
      message.runState = normalizeAgentRunState(data.state || data.taskStatus) || 'recovering'
      message.isStreaming = true
      break
    case 'RUN_STATE_QUEUED':
    case 'RUN_STATE_PREPARING':
    case 'RUN_STATE_RUNNING':
    case 'RUN_STATE_RECOVERING':
    case 'RUN_STATE_RETRYING': {
      const fallbackState = type.substring('RUN_STATE_'.length).toLowerCase()
      message.taskId = data.taskId || message.taskId || null
      message.runState = normalizeAgentRunState(data.state || data.taskStatus) || fallbackState
      message.isStreaming = true
      break
    }
    case 'RUN_STATE_COMPLETED':
    case 'RUN_STATE_FAILED':
    case 'RUN_STATE_CANCELLED': {
      const fallbackState = type.substring('RUN_STATE_'.length).toLowerCase()
      message.taskId = data.taskId || message.taskId || null
      message.runState = normalizeAgentRunState(data.state || data.taskStatus) || fallbackState
      message.isStreaming = false
      break
    }
    case 'TASK_PAUSED':
      message.taskId = data.taskId || message.taskId || null
      message.runState = normalizeAgentRunState(data.taskStatus) || message.runState || ''
      message.isStreaming = false
      break
    case 'FINAL_CANDIDATE_DELTA':
      message.pendingFinalContent = (message.pendingFinalContent || '') + (data.delta || '')
      message.hasPendingFinalDraft = Boolean(message.pendingFinalContent)
      break
    case 'FINAL_DELTA': {
      if (isRecoverableAgentRunState(message.runState)) break
      message._finalReasoningFilter ??= createInternalReasoningBlockStreamFilter()
      message.content += message._finalReasoningFilter.push(data.delta)
      break
    }
    case 'FINAL':
      message.pendingFinalContent = ''
      message.hasPendingFinalDraft = false
      if (!isRecoverableAgentRunState(message.runState) && data.content && !message.error) {
        message.content = stripInternalReasoningBlocks(data.content)
      }
      message._finalReasoningFilter?.reset()
      break
    case 'DONE': {
      message.taskId = data.taskId || message.taskId || null
      const doneState = normalizeAgentRunState(data.taskStatus || message.runState)
      if (doneState) message.runState = doneState
      message.isStreaming = false
      break
    }
    case 'ERROR': {
      const error = data.message || '模型服务调用失败'
      message.error = error
      message.content = projectVisibleAgentError(message.content, error)
      message.isStreaming = false
      break
    }
    case 'INTERRUPTED': message.content += '\n[\u5df2\u4e2d\u65ad]'; break
    case 'TOKEN_USAGE': callbacks.onTokenUsage?.(data); break
    case 'CONTEXT_STATUS': callbacks.onContextStatus?.(data); break
    case 'COMPACTION_STARTED':
    case 'CONTEXT_PRUNED':
    case 'CONTEXT_TOOL_SCHEMA_REDUCED':
    case 'COMPACTION_COMPLETED':
    case 'COMPACTION_FAILED':
    case 'COMPACTION_PROGRESS':
    case 'COMPACTION_CANCELLED':
      reduceContextManagementEvent(type, data, message)
      break
  }
}
