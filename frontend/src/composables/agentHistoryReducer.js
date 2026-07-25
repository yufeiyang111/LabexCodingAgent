function nextOrder(message) {
  message._nextOrder = (message._nextOrder || 0) + 1
  return message._nextOrder
}

function contextManagementText(type, data = {}) {
  const before = Number.isFinite(data.tokensBefore) ? ` ${data.tokensBefore}` : ''
  const after = Number.isFinite(data.tokensAfter) ? ` \u2192 ${data.tokensAfter}` : ''
  switch (type) {
    case 'COMPACTION_STARTED': return `\u6b63\u5728\u538b\u7f29\u4e0a\u4e0b\u6587${before}${after}`
    case 'CONTEXT_PRUNED': return `\u5df2\u6e05\u7406\u65e7\u5de5\u5177\u8f93\u51fa${before}${after}`
    case 'COMPACTION_COMPLETED': return `\u4e0a\u4e0b\u6587\u538b\u7f29\u5b8c\u6210${before}${after}`
    case 'COMPACTION_FAILED': return `\u6a21\u578b\u538b\u7f29\u672a\u6210\u529f\uff0c\u5df2\u5c1d\u8bd5\u5b89\u5168\u56de\u9000`
    default: return ''
  }
}

function appendContextManagementEvent(message, type, data) {
  const content = contextManagementText(type, data)
  if (!content) return
  message.thinkingBlocks = message.thinkingBlocks || []
  message.thinkingBlocks.push({ content, summary: '\u4e0a\u4e0b\u6587\u7ba1\u7406', _open: false, _order: nextOrder(message) })
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
      message.toolCalls.push({ name: data.tool, args: data.arguments, summary: data.summary, result: null, status: 'running', _order: nextOrder(message) })
      break
    case 'OBSERVE': {
      const toolCall = message.toolCalls?.at(-1)
      if (toolCall) {
        toolCall.result = data.result || data.content
        toolCall.status = data.success !== false ? 'completed' : 'error'
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
    case 'PLAN_UPDATE': message.plan = data.summary || data.plan || null; message.planJson = data.planJson || null; break
    case 'FINAL_DELTA': message.content += data.delta || ''; break
    case 'FINAL': if (data.content && !message.error) message.content = data.content; break
    case 'ERROR': message.error = data.message; break
    case 'INTERRUPTED': message.content += '\n[\u5df2\u4e2d\u65ad]'; break
    case 'TOKEN_USAGE': callbacks.onTokenUsage?.(data); break
    case 'CONTEXT_STATUS': callbacks.onContextStatus?.(data); break
    case 'COMPACTION_STARTED':
    case 'CONTEXT_PRUNED':
    case 'COMPACTION_COMPLETED':
    case 'COMPACTION_FAILED': appendContextManagementEvent(message, type, data); break
  }
}
