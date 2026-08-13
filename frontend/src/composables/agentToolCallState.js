function nextOrder(message) {
  message._nextOrder = (message._nextOrder || 0) + 1
  return message._nextOrder
}

export function postEditVerificationStatus(result) {
  const text = String(result || '')
  const marker = text.lastIndexOf('[Post-edit hooks]')
  if (marker < 0) return ''
  const section = text.slice(marker)
  const match = section.match(/(?:^|\n)\s*-\s*status=(PASS|FAIL|UNAVAILABLE|SKIPPED)\b/i)
  return match ? match[1].toUpperCase() : ''
}

export function projectToolResultStatus(success, result) {
  const verificationStatus = postEditVerificationStatus(result)
  if (success === false || verificationStatus === 'FAIL') return { status: 'error', verificationStatus }
  if (verificationStatus === 'UNAVAILABLE') return { status: 'warning', verificationStatus }
  return { status: 'completed', verificationStatus }
}

export function visibleToolCallStatus(status, detail = '') {
  switch (String(status || '').toLowerCase()) {
    case 'pending':
    case 'running':
      return 'running'
    case 'waiting_approval':
      return 'waiting_approval'
    case 'waiting_user':
      return 'waiting_user'
    case 'completed':
      return projectToolResultStatus(true, detail).status
    case 'environment_blocked':
      return 'warning'
    case 'skipped':
      return 'skipped'
    case 'interrupted':
    case 'cancelled':
      return 'interrupted'
    case 'timed_out':
    case 'failed':
    case 'error':
      return 'error'
    default:
      return 'error'
  }
}

/**
 * 用后端结构化 executionStatus 投影工具卡状态，禁止从输出文本猜测 timed_out/cancelled。
 * durableStatus 保留原始执行状态，供卡片展示“执行超时/已取消”等区分文案。
 */
export function applyStructuredExecutionStatus(call, executionStatus = '') {
  if (!call || !executionStatus) return call
  const normalized = String(executionStatus).toLowerCase()
  call.executionStatus = normalized
  switch (normalized) {
    case 'timed_out':
      call.durableStatus = 'timed_out'
      call.status = 'error'
      break
    case 'cancelled':
      call.durableStatus = 'cancelled'
      call.status = 'interrupted'
      break
    case 'infrastructure_error':
      call.durableStatus = 'environment_blocked'
      call.status = 'warning'
      break
    default:
      break
  }
  return call
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
  if (state.detail !== undefined && state.detail !== null) call.result = state.detail
  const verificationStatus = postEditVerificationStatus(call.result)
  call.verificationStatus = verificationStatus
  if (state.interactionPayload && typeof state.interactionPayload === 'object') {
    const type = String(state.interactionPayload.interactionType || 'question')
    const field = type === 'permission' ? 'permissionRequest'
      : type === 'network' ? 'networkRequest' : 'questionRequest'
    call[field] = { ...(call[field] || {}), ...state.interactionPayload }
  }
  const waitingApproval = call.permissionRequest || call.networkRequest
  if (call.questionRequest && call.durableStatus === 'waiting_user') {
    call.status = 'waiting_user'
  } else if (waitingApproval && call.durableStatus === 'waiting_approval') {
    call.status = 'waiting_approval'
  } else if (call.questionRequest && call.durableStatus === 'error') {
    // Keep a pending question visible when an older event reports error.
    call.status = 'waiting_user'
  } else if (waitingApproval && call.durableStatus === 'error') {
    // Keep a pending approval visible when a pre-pause observation reports failure.
    call.status = 'waiting_approval'
  } else {
    call.status = visibleToolCallStatus(call.durableStatus, call.result)
  }
  if (call.durableStatus === 'environment_blocked') call.environmentBlocker = state
  return call
}
