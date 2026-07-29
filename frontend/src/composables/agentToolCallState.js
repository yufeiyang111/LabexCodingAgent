function nextOrder(message) {
  message._nextOrder = (message._nextOrder || 0) + 1
  return message._nextOrder
}

export function visibleToolCallStatus(status) {
  switch (String(status || '').toLowerCase()) {
    case 'pending':
    case 'running':
      return 'running'
    case 'waiting_approval':
      return 'waiting_approval'
    case 'waiting_user':
      return 'waiting_user'
    case 'completed':
      return 'completed'
    case 'environment_blocked':
      return 'warning'
    case 'skipped':
      return 'skipped'
    case 'interrupted':
    case 'cancelled':
      return 'interrupted'
    default:
      return 'error'
  }
}

export function upsertDurableToolCallState(message, state = {}) {
  if (!message || !state.toolCallId) return null
  message.toolCalls = message.toolCalls || []
  let call = message.toolCalls.find(item => item?.toolCallId === state.toolCallId)
  if (!call) {
    call = {
      name: state.tool || 'tool',
      args: state.arguments || {},
      summary: state.tool || 'tool',
      result: null,
      status: visibleToolCallStatus(state.status),
      durableStatus: state.status || 'pending',
      toolCallId: state.toolCallId,
      startedAt: Date.now(),
      execution: { phase: 'recovered', elapsedMs: 0 },
      _order: nextOrder(message)
    }
    message.toolCalls.push(call)
  }
  call.name = state.tool || call.name
  call.args = state.arguments || call.args || {}
  call.durableStatus = state.status || call.durableStatus || 'pending'
  if (state.interactionPayload && typeof state.interactionPayload === 'object') {
    call.questionRequest = { ...(call.questionRequest || {}), ...state.interactionPayload }
  }
  if (call.questionRequest && call.durableStatus === 'waiting_user') {
    call.status = 'waiting_user'
  } else if (call.questionRequest && call.durableStatus === 'error') {
    // Keep a pending question visible when an older event reports error.
    call.status = 'waiting_user'
  } else {
    call.status = visibleToolCallStatus(call.durableStatus)
  }
  if (state.detail) call.result = state.detail
  if (call.durableStatus === 'environment_blocked') call.environmentBlocker = state
  return call
}
