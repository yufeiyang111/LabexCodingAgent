import { upsertDurableToolCallState } from './agentToolCallState.js'

function executionResultText(data, fallback) {
  const duration = Number(data?.durationMs)
  const durationText = Number.isFinite(duration) && duration >= 0 ? ` · ${duration} ms` : ''
  const exitCode = data?.exitCode === '' || data?.exitCode == null ? '' : ` · exit code ${data.exitCode}`
  return `${fallback}${durationText}${exitCode}`
}

function legacyToolCallId(approvalId) {
  return `legacy-command-approval:${approvalId}`
}

export function findCommandApprovalToolCall(message, approvalId, toolCallId = '') {
  if (!message) return null
  const calls = message.toolCalls || []
  if (approvalId) {
    const byApproval = calls.find(call => call?.commandApproval?.approvalId === approvalId)
    if (byApproval) return byApproval
  }
  return toolCallId ? calls.find(call => call?.toolCallId === toolCallId) || null : null
}

/**
 * 只使用后端持久化的稳定身份绑定审批；旧事件使用确定性兼容 ID，禁止猜最后一个工具。
 */
export function attachCommandApprovalState(message, data = {}) {
  if (!message || !data.approvalId) return null
  message.toolCalls = message.toolCalls || []
  const stableToolCallId = data.toolCallId || legacyToolCallId(data.approvalId)
  let call = findCommandApprovalToolCall(message, data.approvalId, stableToolCallId)
  if (!call) {
    call = upsertDurableToolCallState(message, {
      toolCallId: stableToolCallId,
      tool: data.tool || 'shell',
      arguments: { command: '<redacted; approval required>' },
      status: 'waiting_approval'
    })
  }
  if (!call) return null
  call.commandApproval = { ...(call.commandApproval || {}), ...data, toolCallId: stableToolCallId }
  call.summary = data.displayCommand || call.summary || '命令需要一次性批准'
  call.status = 'waiting_approval'
  call.durableStatus = call.durableStatus || 'waiting_approval'
  return call
}

function hasWaitingDurableInteraction(call) {
  if (!call || !['waiting_approval', 'waiting_user'].includes(String(call.status || ''))) return false
  return Boolean(call.networkRequest || call.permissionRequest || call.questionRequest)
}

function appendExecutionOutput(result, output) {
  return output ? `${result}\n\n${output}` : result
}

export function applyCommandExecutionResponseState(call, data = {}) {
  if (!call) return null
  const status = String(data.status || '').toLowerCase()
  const executionStatus = String(data.executionStatus || '').toLowerCase()
  const waitingForNetwork = status === 'waiting_network'
    || (Boolean(call.networkRequest) && executionStatus === 'failed')

  if (waitingForNetwork) {
    call.status = 'waiting_approval'
    call.durableStatus = 'waiting_approval'
    call.result = appendExecutionOutput(
      executionResultText(data, '\u79bb\u7ebf\u547d\u4ee4\u5931\u8d25\uff0c\u7b49\u5f85\u7f51\u7edc\u6279\u51c6'),
      data.output
    )
    return call
  }
  if (executionStatus === 'completed' || executionStatus === 'failed') {
    call.status = executionStatus === 'completed' ? 'completed' : 'error'
    call.result = appendExecutionOutput(
      executionResultText(data, executionStatus === 'completed'
        ? '\u547d\u4ee4\u6267\u884c\u5b8c\u6210'
        : '\u547d\u4ee4\u6267\u884c\u5931\u8d25'),
      data.output
    )
    return call
  }
  call.status = 'running'
  call.result = '\u547d\u4ee4\u5df2\u6279\u51c6\uff0c\u7b49\u5f85\u6267\u884c\u7ed3\u679c'
  return call
}

export function updateCommandApprovalState(message, type, data = {}) {
  let call = findCommandApprovalToolCall(message, data.approvalId, data.toolCallId)
  if (!call && data.approvalId) call = attachCommandApprovalState(message, data)
  if (!call) return null

  if (hasWaitingDurableInteraction(call)
      && ['COMMAND_EXECUTION_COMPLETED', 'COMMAND_EXECUTION_FAILED', 'COMMAND_EXECUTION_INTERRUPTED'].includes(type)) {
    return call
  }

  const decision = String(data.decision || '').toLowerCase()
  if (type === 'COMMAND_APPROVAL_DECIDED') {
    if (decision === 'rejected' || decision === 'expired') {
      call.status = 'error'
      call.result = decision === 'expired' ? '批准请求已过期，命令未执行' : '已拒绝命令，命令未执行'
    } else {
      call.status = 'running'
      call.result = '批准已记录，准备执行命令'
    }
    return call
  }
  if (type === 'COMMAND_APPROVAL_REJECTED' || type === 'COMMAND_APPROVAL_EXPIRED') {
    call.status = 'error'
    call.result = type === 'COMMAND_APPROVAL_EXPIRED' ? '批准请求已过期，命令未执行' : '已拒绝命令，命令未执行'
    return call
  }
  if (type === 'COMMAND_EXECUTION_STARTED') {
    call.status = 'running'
    call.result = '命令正在执行...'
    return call
  }
  if (type === 'COMMAND_EXECUTION_COMPLETED') {
    call.status = 'completed'
    call.result = executionResultText(data, '命令执行完成')
    return call
  }
  if (type === 'RUN_COMMAND_APPROVAL_RESUME_QUEUED') {
    call.status = 'running'
    call.durableStatus = 'resuming'
    call.interactionStatus = 'resuming'
    call.result = '命令结果已保存，正在恢复 Agent 任务'
    return call
  }
  if (type === 'COMMAND_APPROVAL_RESUME_DEFERRED') {
    call.status = 'running'
    call.durableStatus = 'waiting_resume'
    call.interactionStatus = 'resuming'
    call.result = '等待旧执行器释放后自动恢复'
    return call
  }
  if (type === 'COMMAND_EXECUTION_FAILED') {
    call.status = 'error'
    call.result = executionResultText(data, '命令执行失败')
    return call
  }
  if (type === 'COMMAND_EXECUTION_INTERRUPTED') {
    call.status = 'error'
    call.result = '命令执行已中断'
  }
  return call
}