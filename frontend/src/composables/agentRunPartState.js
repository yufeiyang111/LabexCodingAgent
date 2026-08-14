import { upsertDurableToolCallState } from './agentToolCallState.js'
import { stripInternalReasoningBlocks, stripInternalReasoningTags } from '../utils/agentMarkdown.js'

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

function sanitizeRunPart(part = {}) {
  const projected = { ...part }
  const type = String(projected.partType || '').toLowerCase()
  if (type === 'reasoning') projected.output = stripInternalReasoningTags(projected.output || '')
  if (type === 'text') projected.output = stripInternalReasoningBlocks(projected.output || '')
  return projected
}

function sanitizeRunMessage(runMessage = {}) {
  const projected = { ...runMessage }
  const key = String(projected.messageKey || '').toLowerCase()
  if (key.startsWith('assistant:turn:')) {
    projected.content = stripInternalReasoningTags(projected.content || '')
  } else if (String(projected.role || '').toLowerCase() === 'assistant' || key === 'assistant:final') {
    projected.content = stripInternalReasoningBlocks(projected.content || '')
  }
  return projected
}

function appendReasoningPart(message, part) {
  const content = stripInternalReasoningTags(part.output || '').trim()
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

function hasLiveReasoningTimeline(message) {
  if (String(message?.thinking || '').trim()) return true
  return Array.isArray(message?.thinkingBlocks)
    && message.thinkingBlocks.some(block => String(block?.content || '').trim() && !block?._partKey)
}

/** 将持久化 RunPart 投影到现有消息视图，兼容旧事件 reducer。 */
export function applyRunPartSnapshot(message, parts = [], options = {}) {
  if (!message || !Array.isArray(parts)) return message
  const preserveReasoningTimeline = options.preserveReasoningTimeline === true || hasLiveReasoningTimeline(message)
  const ordered = [...parts]
    .sort((left, right) =>
      Number(left?.sequence || left?.partId || 0) - Number(right?.sequence || right?.partId || 0))
    .map(sanitizeRunPart)
  ordered.forEach(part => {
    const type = String(part?.partType || '').toLowerCase()
    if (type === 'tool' || type === 'tool_call' || type === 'tool_result') {
      upsertDurableToolCallState(message, {
        toolCallId: part.toolCallId,
        tool: part.tool,
        arguments: type === 'tool_result' ? undefined : parseJson(part.input),
        status: part.status,
        detail: part.output,
        outputTruncated: part.outputTruncated === true,
        outputLength: part.outputLength || 0
      })
      return
    }
    if (type === 'reasoning') {
      if (!preserveReasoningTimeline) appendReasoningPart(message, part)
      return
    }
    if (type === 'text' && String(part.output || '').trim()) {
      message.content = stripInternalReasoningBlocks(part.output)
      return
    }
    if (type === 'completion_evidence') {
      const evidence = parseJson(part.output, parseJson(part.input, {}))
      if (evidence.satisfied === true) {
        message.completionEvidence = evidence
        message.completionBlockedEvidence = null
      } else {
        message.completionEvidence = null
        message.completionBlockedEvidence = evidence
      }
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
  const ordered = [...runMessages]
    .sort((left, right) =>
      Number(left?.sequence || left?.messageId || 0) - Number(right?.sequence || right?.messageId || 0))
    .map(sanitizeRunMessage)
  message.runMessages = ordered
  const finalMessage = [...ordered].reverse().find(item => item?.messageKey === 'assistant:final')
  if (finalMessage?.content) message.content = stripInternalReasoningBlocks(finalMessage.content)
  return message
}
