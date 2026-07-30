const WAITING_STATUSES = new Set(['running', 'waiting_user', 'waiting_approval'])

const INTERACTION_CONFIG = {
  question: {
    field: 'questionRequest',
    waitingStatus: 'waiting_user',
    fallbackName: 'question',
    fallbackSummary: '等待用户回答'
  },
  permission: {
    field: 'permissionRequest',
    waitingStatus: 'waiting_approval',
    fallbackName: 'permission_ask',
    fallbackSummary: '等待用户批准'
  },
  network: {
    field: 'networkRequest',
    waitingStatus: 'waiting_approval',
    fallbackName: 'network_access',
    fallbackSummary: '等待网络批准'
  }
}

function requestId(request = {}) {
  return String(request.requestId || request.interactionId || '')
}

function callRequestId(call = {}) {
  for (const field of ['questionRequest', 'permissionRequest', 'networkRequest']) {
    const value = requestId(call[field])
    if (value) return value
  }
  return ''
}

function candidateFor(call, type, request, config) {
  if (!call || !WAITING_STATUSES.has(call.status)) return false
  const expectedRequestId = requestId(request)
  const existingRequestId = callRequestId(call)
  if (expectedRequestId && existingRequestId === expectedRequestId) return true
  if (request.toolCallId && call.toolCallId === request.toolCallId) return true
  if (existingRequestId && existingRequestId !== expectedRequestId) return false
  if (type === 'question') return call.name === 'question'
  const expectedName = request.toolName || config.fallbackName
  return call.name === expectedName || call.name === config.fallbackName
}

function canonicalCall(candidates, request) {
  if (request.toolCallId) {
    const exactToolCall = candidates.find(call => call.toolCallId === request.toolCallId)
    if (exactToolCall) return exactToolCall
  }
  const durableToolCall = candidates.find(call => call.toolCallId)
  if (durableToolCall) return durableToolCall
  const expectedRequestId = requestId(request)
  return candidates.find(call => callRequestId(call) === expectedRequestId) || candidates.at(-1)
}

function mergeProjection(target, source) {
  if (!target || !source || target === source) return
  for (const key of ['args', 'summary', 'result', 'execution', 'startedAt', 'toolCallId',
    'questionRequest', 'permissionRequest', 'networkRequest']) {
    if ((target[key] == null || target[key] === '') && source[key] != null) target[key] = source[key]
  }
  if (!target.name || ['permission_ask', 'network_access'].includes(target.name)) {
    target.name = source.name || target.name
  }
  if (!Number.isFinite(target._order) && Number.isFinite(source._order)) target._order = source._order
}

function removeDuplicateCandidates(message, canonical, candidates) {
  const duplicates = new Set(candidates.filter(call => call !== canonical))
  if (!duplicates.size) return
  for (const duplicate of duplicates) mergeProjection(canonical, duplicate)
  message.toolCalls = message.toolCalls.filter(call => !duplicates.has(call))
}

/**
 * 将持久化交互绑定到唯一工具卡片，并清理刷新或重放产生的重复投影。
 */
export function attachDurableInteraction(message, type, request = {}) {
  const config = INTERACTION_CONFIG[type]
  if (!message || !config) return null
  message.toolCalls ||= []
  const candidates = message.toolCalls.filter(call => candidateFor(call, type, request, config))
  let call = canonicalCall(candidates, request)
  if (!call) {
    call = {
      name: request.toolName || config.fallbackName,
      args: type === 'question'
        ? { question: request.question, options: request.options || [] }
        : (request.input || { request: request.request || '' }),
      summary: request.summary || config.fallbackSummary,
      result: null,
      status: config.waitingStatus,
      toolCallId: request.toolCallId || '',
      _order: (message._nextOrder = (message._nextOrder || 0) + 1)
    }
    message.toolCalls.push(call)
  } else {
    removeDuplicateCandidates(message, call, candidates)
  }
  call.status = config.waitingStatus
  call.durableStatus = config.waitingStatus
  call[config.field] = request
  call.summary = request.summary || call.summary || config.fallbackSummary
  if (request.toolCallId && !call.toolCallId) call.toolCallId = request.toolCallId
  return call
}

/**
 * 使用持久化 interactionId 收敛交互卡片，确保恢复事件不会留下重复等待状态。
 */
export function resolveDurableInteraction(message, data = {}) {
  if (!message?.toolCalls?.length) return false
  const interactionId = requestId(data)
  if (!interactionId) return false
  const matches = message.toolCalls.filter(call => callRequestId(call) === interactionId)
  if (!matches.length) return false
  const canonical = canonicalCall(matches, data)
  removeDuplicateCandidates(message, canonical, matches)
  canonical.status = 'running'
  canonical.durableStatus = 'resuming'
  canonical.interactionStatus = 'resuming'
  canonical.interactionId = interactionId
  return true
}
