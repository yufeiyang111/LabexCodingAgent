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

function visibleFailureStatus(failureClass = '') {
  switch (String(failureClass || '').toLowerCase()) {
    case 'cancelled':
      return 'interrupted'
    case 'infrastructure_error':
      return 'warning'
    case '':
      return ''
    default:
      return 'error'
  }
}

function structuredMetadata(value) {
  if (value && typeof value === 'object' && !Array.isArray(value)) return value
  if (typeof value !== 'string' || !value.trim()) return {}
  try {
    const parsed = JSON.parse(value)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}
  } catch {
    return {}
  }
}

export function visibleToolCallStatus(status, detail = '', failureClass = '') {
  switch (String(status || '').toLowerCase()) {
    case 'pending':
    case 'running':
      return 'running'
    case 'waiting_approval':
      return 'waiting_approval'
    case 'waiting_user':
      return 'waiting_user'
    case 'completed':
      return visibleFailureStatus(failureClass) || projectToolResultStatus(true, detail).status
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
    case 'failed':
    case 'timed_out':
      if (normalized === 'timed_out') call.durableStatus = 'timed_out'
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

/**
 * 将 durable Tool Part 与实时 OBSERVE 的同一份结构化执行事实投影到工具卡，
 * 不依赖 transport success 或输出文本推断命令是否真正成功。
 */
export function applyStructuredExecutionOutcome(call, outcome = {}) {
  if (!call || !outcome || typeof outcome !== 'object') return call
  const execution = { ...structuredMetadata(outcome.execution) }
  if (!execution.status && outcome.executionStatus) execution.status = outcome.executionStatus
  if (execution.exitCode == null && outcome.executionExitCode != null) execution.exitCode = outcome.executionExitCode
  if (execution.durationMs == null && outcome.executionDurationMs != null) execution.durationMs = outcome.executionDurationMs
  if (Object.keys(execution).length > 0) {
    call.executionResult = { ...(call.executionResult || {}), ...execution }
  }

  const failureClass = typeof outcome.failureClass === 'string' ? outcome.failureClass.trim().toLowerCase() : ''
  if (failureClass) call.failureClass = failureClass
  applyStructuredExecutionStatus(call, execution.status || outcome.executionStatus)

  if (!['waiting_user', 'waiting_approval'].includes(call.status)) {
    const failureStatus = visibleFailureStatus(call.failureClass)
    if (failureStatus) call.status = failureStatus
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
      failureClass: '',
      executionResult: null,
      _order: nextOrder(message)
    }
    message.toolCalls.push(call)
  }
  call.name = state.tool || call.name
  call.args = state.arguments || call.args || {}
  call.durableStatus = state.status || call.durableStatus || 'pending'
  const metadata = structuredMetadata(state.metadata)
  if (state.detail !== undefined && state.detail !== null) call.result = state.detail
  if (state.outputTruncated) {
    call.outputTruncated = true
    call.outputLength = Number(state.outputLength) || call.result?.length || 0
  }
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
    call.status = visibleToolCallStatus(call.durableStatus, call.result, call.failureClass)
  }
  applyStructuredExecutionOutcome(call, {
    failureClass: metadata.failureClass,
    execution: metadata.execution
  })
  if (call.durableStatus === 'environment_blocked') call.environmentBlocker = state
  return call
}
