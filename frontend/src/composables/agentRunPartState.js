import { upsertDurableToolCallState } from './agentToolCallState.js'

function parseJson(value, fallback = {}) {
  if (!value) return fallback
  if (typeof value === 'object') return value
  try {
    return JSON.parse(value)
  } catch {
    return fallback
  }
}

function partIdentity(part = {}) {
  return part.partKey || `part:${part.partId || part.sequence || 'unknown'}`
}

function appendReasoningPart(message, part) {
  const content = String(part.output || '').trim()
  if (!content) return
  message.thinkingBlocks ||= []
  const key = partIdentity(part)
  const existing = message.thinkingBlocks.find(block => block._partKey === key)
  if (existing) {
    existing.content = content
    return
  }
  message._nextOrder = (message._nextOrder || 0) + 1
  message.thinkingBlocks.push({
    content,
    summary: '',
    _open: false,
    _partKey: key,
    _order: message._nextOrder
  })
}

/** 将持久化 RunPart 投影到现有消息视图，兼容旧事件 reducer。 */
export function applyRunPartSnapshot(message, parts = []) {
  if (!message || !Array.isArray(parts)) return message
  const ordered = [...parts].sort((left, right) =>
    Number(left?.sequence || left?.partId || 0) - Number(right?.sequence || right?.partId || 0))
  ordered.forEach(part => {
    const type = String(part?.partType || '').toLowerCase()
    if (type === 'tool') {
      upsertDurableToolCallState(message, {
        toolCallId: part.toolCallId,
        tool: part.tool,
        arguments: parseJson(part.input),
        status: part.status,
        detail: part.output
      })
      return
    }
    if (type === 'reasoning') {
      appendReasoningPart(message, part)
      return
    }
    if (type === 'text' && String(part.output || '').trim()) {
      message.content = String(part.output)
      return
    }
    if (type === 'completion_evidence') {
      message.completionEvidence = parseJson(part.output, parseJson(part.input, {}))
      return
    }
    if (type === 'error') {
      message.error = String(part.output || 'Agent 运行失败')
    }
  })
  message.runParts = ordered
  return message
}

/** 保留 Message 身份，使多个 Part 在刷新后仍归属于原来的模型回合。 */
export function applyRunMessageSnapshot(message, runMessages = []) {
  if (!message || !Array.isArray(runMessages)) return message
  const ordered = [...runMessages].sort((left, right) =>
    Number(left?.sequence || left?.messageId || 0) - Number(right?.sequence || right?.messageId || 0))
  message.runMessages = ordered
  const finalMessage = [...ordered].reverse().find(item => item?.messageKey === 'assistant:final')
  if (finalMessage?.content) message.content = finalMessage.content
  return message
}