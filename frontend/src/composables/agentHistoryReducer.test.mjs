import assert from 'node:assert/strict'
import test from 'node:test'
import { reduceHistoryEvent } from './agentHistoryReducer.js'

function message() { return { content: '', thinking: '', _thinkingDisplay: '', thinkingBlocks: [], toolCalls: [], _nextOrder: 0 } }

test('reduces thinking, tool calls, observations, and permission waits', () => {
  const target = message()
  let pendingChanges = 0
  reduceHistoryEvent('THINK_DELTA', { delta: 'plan' }, target)
  reduceHistoryEvent('TOOL_CALL', { tool: 'write_file', arguments: { path: 'a.js' }, summary: 'write' }, target)
  reduceHistoryEvent('PERMISSION_ASK', { requestId: 'r1', toolName: 'write_file' }, target)
  reduceHistoryEvent('OBSERVE', { result: 'done', pendingChangeId: 'c1' }, target, { onPendingChange: () => pendingChanges++ })

  assert.deepEqual(target.thinkingBlocks.map(block => block.content), ['plan'])
  assert.equal(target.toolCalls[0].status, 'completed')
  assert.equal(target.toolCalls[0].result, 'done')
  assert.equal(target.toolCalls[0].permissionRequest.requestId, 'r1')
  assert.equal(pendingChanges, 1)
})

test('delegates user questions and token accounting while applying final output', () => {
  const target = message()
  const calls = []
  reduceHistoryEvent('USER_QUESTION', { requestId: 'q1' }, target, { onUserQuestion: (_, data) => calls.push(data.requestId) })
  reduceHistoryEvent('TOKEN_USAGE', { totalTokens: 9 }, target, { onTokenUsage: data => calls.push(data.totalTokens) })
  reduceHistoryEvent('FINAL_DELTA', { delta: 'final' }, target)

  assert.deepEqual(calls, ['q1', 9])
  assert.equal(target.content, 'final')
})

test('reconstructs one-time command approval and terminal execution state without loop resume', () => {
  const target = message()
  reduceHistoryEvent('TOOL_CALL', { tool: 'shell', arguments: { command: '<redacted>' }, summary: 'shell' }, target)
  reduceHistoryEvent('COMMAND_APPROVAL_REQUIRED', {
    approvalId: 'approval-71', tool: 'shell', displayCommand: 'npm test --token=<redacted>', resumeAgentLoop: false
  }, target)
  reduceHistoryEvent('COMMAND_EXECUTION_STARTED', { approvalId: 'approval-71', resumeAgentLoop: false }, target)
  reduceHistoryEvent('COMMAND_EXECUTION_COMPLETED', { approvalId: 'approval-71', resumeAgentLoop: false }, target)

  assert.equal(target.toolCalls[0].commandApproval.approvalId, 'approval-71')
  assert.equal(target.toolCalls[0].commandApproval.resumeAgentLoop, false)
  assert.equal(target.toolCalls[0].status, 'completed')
  assert.equal(target.toolCalls[0].args.command, '<redacted>')
})

test('preserves the task id from persisted session events for historical timing lookup', () => {
  const target = message()

  reduceHistoryEvent('SESSION', { taskId: 73 }, target)

  assert.equal(target.taskId, 73)
})


test('records persisted context-management status events in the thinking timeline', () => {
  const target = message()
  reduceHistoryEvent('COMPACTION_STARTED', { tokensBefore: 12000, tokensAfter: 12000 }, target)
  reduceHistoryEvent('CONTEXT_PRUNED', { tokensBefore: 12000, tokensAfter: 9000 }, target)
  reduceHistoryEvent('COMPACTION_COMPLETED', { tokensBefore: 9000, tokensAfter: 3000 }, target)

  assert.equal(target.thinkingBlocks.length, 3)
  assert.match(target.thinkingBlocks[0].content, /12000/)
  assert.match(target.thinkingBlocks[2].content, /3000/)
})
