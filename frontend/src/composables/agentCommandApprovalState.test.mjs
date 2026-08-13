import assert from 'node:assert/strict'
import test from 'node:test'
import { applyCommandExecutionResponseState, attachCommandApprovalState, updateCommandApprovalState } from './agentCommandApprovalState.js'
import { upsertDurableToolCallState } from './agentToolCallState.js'

function message(toolCalls = []) {
  return { toolCalls, _nextOrder: toolCalls.length }
}

test('command approval binds the declared toolCallId instead of the last tool', () => {
  const target = message([
    { name: 'shell', toolCallId: 'shell-call', status: 'running', summary: 'shell' },
    { name: 'list_files', toolCallId: 'list-call', status: 'running', summary: 'list' }
  ])

  const call = attachCommandApprovalState(target, {
    approvalId: 'approval-61',
    toolCallId: 'shell-call',
    tool: 'shell',
    displayCommand: 'git add .'
  })

  assert.equal(call, target.toolCalls[0])
  assert.equal(target.toolCalls[0].status, 'waiting_approval')
  assert.equal(target.toolCalls[0].commandApproval.approvalId, 'approval-61')
  assert.equal(target.toolCalls[1].commandApproval, undefined)
})

test('out-of-order durable tool state merges into the approval placeholder', () => {
  const target = message()

  const placeholder = attachCommandApprovalState(target, {
    approvalId: 'approval-early',
    toolCallId: 'shell-early',
    tool: 'shell',
    displayCommand: 'npm test'
  })
  upsertDurableToolCallState(target, {
    toolCallId: 'shell-early',
    tool: 'shell',
    arguments: { command: '<redacted>' },
    status: 'waiting_approval'
  })

  assert.equal(target.toolCalls.length, 1)
  assert.equal(target.toolCalls[0], placeholder)
  assert.equal(target.toolCalls[0].toolCallId, 'shell-early')
  assert.equal(target.toolCalls[0].commandApproval.approvalId, 'approval-early')
})

test('legacy approval creates a deterministic isolated card instead of mutating the last tool', () => {
  const target = message([
    { name: 'read_file', toolCallId: 'read-call', status: 'running', summary: 'read' }
  ])

  const call = attachCommandApprovalState(target, {
    approvalId: 'legacy-approval',
    tool: 'shell',
    displayCommand: 'mvn test'
  })

  assert.equal(target.toolCalls.length, 2)
  assert.equal(target.toolCalls[0].commandApproval, undefined)
  assert.equal(call.toolCallId, 'legacy-command-approval:legacy-approval')
  assert.equal(call.commandApproval.approvalId, 'legacy-approval')
})

test('network approval remains authoritative over the failed offline command snapshot', () => {
  const target = message([{
    name: 'shell',
    toolCallId: 'shell-network',
    status: 'waiting_approval',
    commandApproval: { approvalId: 'approval-network' },
    networkRequest: { requestId: 'network-request', toolCallId: 'shell-network' }
  }])

  const call = updateCommandApprovalState(target, 'COMMAND_EXECUTION_FAILED', {
    approvalId: 'approval-network',
    toolCallId: 'shell-network',
    executionStatus: 'failed',
    exitCode: 1
  })

  assert.equal(call.status, 'waiting_approval')
  assert.equal(call.networkRequest.requestId, 'network-request')
})

test('waiting_network HTTP response cannot hide a live network approval', () => {
  const call = {
    name: 'shell',
    status: 'running',
    networkRequest: { requestId: 'network-request' }
  }

  applyCommandExecutionResponseState(call, {
    status: 'waiting_network',
    executionStatus: 'failed',
    durationMs: 6400,
    exitCode: 1,
    output: 'offline dependency resolution failed'
  })

  assert.equal(call.status, 'waiting_approval')
  assert.equal(call.durableStatus, 'waiting_approval')
  assert.equal(call.result.includes('\u7b49\u5f85\u7f51\u7edc\u6279\u51c6'), true)
  assert.match(call.result, /offline dependency resolution failed/)
})

test('ordinary failed command response remains an error', () => {
  const call = { name: 'shell', status: 'running' }

  applyCommandExecutionResponseState(call, {
    status: 'failed', executionStatus: 'failed', durationMs: 20, exitCode: 2
  })

  assert.equal(call.status, 'error')
  assert.equal(call.result.includes('\u547d\u4ee4\u6267\u884c\u5931\u8d25'), true)
})
