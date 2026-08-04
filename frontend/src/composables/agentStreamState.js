export function formatAgentStreamError(message) {
  const detail = typeof message === 'string' && message.trim()
    ? message.trim()
    : 'Agent 请求失败，请稍后重试'
  return `错误: ${detail}`
}

export function needsAgentResponseFallback(message) {
  const hasText = value => typeof value === 'string' && value.trim().length > 0
  const hasThinkingBlock = Array.isArray(message?.thinkingBlocks)
    && message.thinkingBlocks.some(block => hasText(block?.content))
  return !hasText(message?.content)
    && !hasText(message?.error)
    && !hasText(message?.thinking)
    && !hasText(message?._thinkingDisplay)
    && !hasThinkingBlock
    && (!Array.isArray(message?.toolCalls) || message.toolCalls.length === 0)
}

function durableSequence(value) {
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : null
}

export function initialAgentStreamState() {
  return {
    status: 'idle',
    sessionId: null,
    taskId: null,
    lastEventId: null
  }
}

export function reduceAgentStreamState(state, event) {
  const current = state || initialAgentStreamState()
  switch (event?.type) {
    case 'START':
      if (current.status !== 'idle') return current
      return {
        status: 'streaming',
        sessionId: event.sessionId || null,
        taskId: null,
        lastEventId: null
      }
    case 'EVENT_RECEIVED': {
      const nextTaskId = event.taskId ?? current.taskId
      const sameTask = current.taskId == null || nextTaskId == null
        || String(current.taskId) === String(nextTaskId)
      const currentSequence = durableSequence(current.lastEventId)
      const nextSequence = durableSequence(event.eventId)
      if (sameTask && currentSequence != null && nextSequence != null && nextSequence <= currentSequence) {
        return current
      }
      return {
        ...current,
        taskId: nextTaskId,
        lastEventId: event.eventId ?? current.lastEventId
      }
    }
    case 'STOP_REQUESTED':
      if (current.status !== 'streaming') return current
      return {
        ...current,
        status: 'cancelling'
      }
    case 'STREAM_ENDED':
      if (current.status === 'idle') return current
      return {
        ...initialAgentStreamState(),
        taskId: current.taskId,
        lastEventId: current.lastEventId
      }
    default:
      return current
  }
}
