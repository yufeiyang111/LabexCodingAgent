import { upsertDurableToolCallState } from './agentToolCallState.js'

function nextOrder(message) {
  message._nextOrder = (message._nextOrder || 0) + 1
  return message._nextOrder
}

function toolResultStatus(success, result) {
  if (success === false) return 'error'
  const text = String(result || '')
  if (text.includes('status=FAIL')) return 'error'
  if (text.includes('status=UNAVAILABLE')) return 'warning'
  return 'completed'
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
        message.thinkingBlocks.push({ content: message.thinking, summary: data.summary || '', _open: false, _order: nextOrder(message) })
        message.thinking = ''
      }
      message._hasThinkStart = true
      break
    case 'THINK_DELTA':
      message.thinking += data.delta || ''
      message._thinkingDisplay = message.thinking
      break
    case 'THINK_SNAPSHOT':
      message.thinking = data.content || message.thinking || ''
      message._thinkingDisplay = message.thinking
      break
    case 'THINK':
      if (data.content) {
        message.thinkingBlocks = message.thinkingBlocks || []
        if (message.thinking) message.thinkingBlocks.push({ content: message.thinking, summary: data.summary || '', _open: false, _order: nextOrder(message) })
        else if (!message._hasThinkStart) message.thinkingBlocks.push({ content: data.content, summary: data.summary || '', _open: false, _order: nextOrder(message) })
        message.thinking = ''
        message._hasThinkStart = false
      }
      break
    case 'TOOL_CALL':
      if (message.thinking) {
        message.thinkingBlocks = message.thinkingBlocks || []
        message.thinkingBlocks.push({ content: message.thinking, summary: data.summary || data.tool || '', _open: false, _order: nextOrder(message) })
        message.thinking = ''
      }
      message.toolCalls = message.toolCalls || []
      message.toolCalls.push({ name: data.tool, args: data.arguments, summary: data.summary, result: null, status: 'running', toolCallId: data.toolCallId || '', startedAt: data.startedAt || Date.now(), execution: { phase: 'tool_delegate', elapsedMs: 0 }, _order: nextOrder(message) })
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
      const toolCall = message.toolCalls?.at(-1)
      if (toolCall) {
        toolCall.result = data.result || data.content
        const preservesWaitingQuestion = toolCall.questionRequest && data.success === false
        toolCall.status = preservesWaitingQuestion ? 'waiting_user' : toolResultStatus(data.success, toolCall.result)
        toolCall.verificationStatus = toolCall.status === 'warning' ? 'UNAVAILABLE' : ''
        toolCall.projection = { resultChars: data.resultChars || 0, modelProjectionChars: data.modelProjectionChars || 0,
          truncated: data.modelProjectionTruncated === true }
      }
      if (data.pendingChangeId) callbacks.onPendingChange?.()
      break
    }
    case 'COMMAND_APPROVAL_REQUIRED': {
      const toolCall = message.toolCalls?.at(-1)
      if (toolCall?.status === 'running' && toolCall.name === data.tool) {
        toolCall.status = 'waiting_approval'
        toolCall.commandApproval = data
        toolCall.summary = data.displayCommand || toolCall.summary
        break
      }
      message.toolCalls = message.toolCalls || []
      message.toolCalls.push({ name: data.tool || 'bash', args: { command: '<redacted; approval required>' }, summary: data.displayCommand || '命令需要一次性批准', result: null, status: 'waiting_approval', commandApproval: data, _order: nextOrder(message) })
      break
    }
    case 'COMMAND_APPROVAL_DECIDED':
    case 'COMMAND_APPROVAL_REJECTED':
    case 'COMMAND_EXECUTION_STARTED':
    case 'COMMAND_EXECUTION_COMPLETED':
    case 'COMMAND_EXECUTION_FAILED':
    case 'COMMAND_EXECUTION_INTERRUPTED': {
      const toolCall = message.toolCalls?.find(call => call.commandApproval?.approvalId === data.approvalId)
      if (!toolCall) break
      if (type === 'COMMAND_EXECUTION_STARTED') {
        toolCall.status = 'running'
        toolCall.result = '正在执行已保存的单次命令...'
      } else if (type === 'COMMAND_EXECUTION_COMPLETED') {
        toolCall.status = 'completed'
        toolCall.result = '命令已执行'
      } else if (type !== 'COMMAND_APPROVAL_DECIDED') {
        toolCall.status = 'error'
        toolCall.result = '命令未执行或执行失败'
      }
      break
    }
    case 'PERMISSION_ASK': {
      const toolCall = message.toolCalls?.at(-1)
      if (toolCall?.status === 'running' && toolCall.name === data.toolName) {
        toolCall.status = 'waiting_approval'
        toolCall.permissionRequest = data
        toolCall.summary = data.summary || toolCall.summary
        break
      }
      message.toolCalls = message.toolCalls || []
      message.toolCalls.push({ name: 'permission_ask', args: { toolName: data.toolName, input: data.input }, summary: data.summary || `${data.toolName} \u9700\u8981\u786e\u8ba4`, result: null, status: 'waiting_approval', permissionRequest: data, _order: nextOrder(message) })
      break
    }
    case 'USER_QUESTION': callbacks.onUserQuestion?.(message, data); break
    case 'WORKSPACE_WAITING':
      message.taskId = data.taskId || message.taskId || null
      message.workspaceWaiting = data
      break
    case 'ENVIRONMENT_BLOCKED':
      message.taskId = data.taskId || message.taskId || null
      message.environmentBlocker = data
      message.content = data.detail || data.message || message.content || '依赖环境暂时不可用，请恢复后重试。'
      break
    case 'CONTEXT_LIMIT_BLOCKED':
      message.taskId = data.taskId || message.taskId || null
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
      message.completionEvidence = data
      break
    case 'RUN_STATE_COMPLETED':
      message.taskId = data.taskId || message.taskId || null
      message.runState = data.state || 'completed'
      break
    case 'FINAL_DELTA': message.content += data.delta || ''; break
    case 'FINAL': if (data.content && !message.error) message.content = data.content; break
    case 'ERROR': message.error = data.message; break
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
