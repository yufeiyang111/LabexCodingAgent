import assert from 'node:assert/strict'
import test from 'node:test'
import { attachCommandApprovalState } from './agentCommandApprovalState.js'
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