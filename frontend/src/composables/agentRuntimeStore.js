function stringKey(value) {
  return String(value ?? '')
}

function interactionRequestId(request) {
  if (typeof request === 'string') return stringKey(request)
  if (!request || typeof request !== 'object') return ''
  return stringKey(request.requestId || request.interactionId)
}

function partKeyOf(part = {}) {
  return stringKey(part.partKey || (part.partId != null ? `part:${part.partId}` : ''))
}

function messageKeyOf(runMessage = {}) {
  return stringKey(runMessage.messageKey || (runMessage.messageId != null ? `message:${runMessage.messageId}` : ''))
}

/**
 * 从 messages 数组派生的归一化索引。messages 仍是唯一事实源，
 * 本索引只是只读查找视图，不保存第二份状态。
 *
 * - byTaskId：每个 assistant taskId 指向最新的消息对象（与反向查找语义一致）。
 * - byToolCallId / byInteractionId：跨消息去重的稳定工具卡与持久化交互。
 * - byPartKey / byMessageKey：持久化 snapshot 的 part/message 身份。
 */
export function createAgentRuntimeIndex(messages = []) {
  const byTaskId = new Map()
  const byToolCallId = new Map()
  const byInteractionId = new Map()
  const byPartKey = new Map()
  const byMessageKey = new Map()

  for (const message of messages) {
    if (!message || typeof message !== 'object') continue
    if (message.role === 'assistant' && message.taskId != null) {
      byTaskId.set(stringKey(message.taskId), message)
    }
    for (const runMessage of message.runMessages || []) {
      const messageKey = messageKeyOf(runMessage)
      if (messageKey && !byMessageKey.has(messageKey)) byMessageKey.set(messageKey, { message, runMessage })
    }
    for (const part of message.runParts || []) {
      const partKey = partKeyOf(part)
      if (partKey && !byPartKey.has(partKey)) byPartKey.set(partKey, { message, part })
    }
    for (const call of message.toolCalls || []) {
      if (!call || typeof call !== 'object') continue
      if (call.toolCallId) byToolCallId.set(stringKey(call.toolCallId), { message, call })
      for (const request of [call.interactionId, call.questionRequest, call.permissionRequest, call.networkRequest]) {
        const interactionId = interactionRequestId(request)
        if (interactionId && !byInteractionId.has(interactionId)) byInteractionId.set(interactionId, { message, call })
      }
    }
  }

  return {
    byTaskId,
    byToolCallId,
    byInteractionId,
    byPartKey,
    byMessageKey,
    assistantMessageForTask(taskId) {
      return byTaskId.get(stringKey(taskId)) || null
    },
    toolCall(toolCallId) {
      return byToolCallId.get(stringKey(toolCallId)) || null
    },
    interaction(interactionId) {
      return byInteractionId.get(stringKey(interactionId)) || null
    },
    part(partKey) {
      return byPartKey.get(stringKey(partKey)) || null
    }
  }
}

/**
 * 保证同一 taskId 只有一条 assistant 消息（snapshot/重放/订阅重复应用时不会产生重复回合）。
 * 返回既有消息或新建并推入的消息；messages 数组仍是调用方持有的唯一事实源。
 */
export function ensureAssistantMessage(messages, taskId, factory) {
  const existing = createAgentRuntimeIndex(messages).assistantMessageForTask(taskId)
  if (existing) return existing
  const message = factory()
  messages.push(message)
  return message
}
