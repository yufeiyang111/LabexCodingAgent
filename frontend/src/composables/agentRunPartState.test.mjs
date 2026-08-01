import test from 'node:test'
import assert from 'node:assert/strict'
import { applyRunMessageSnapshot, applyRunPartSnapshot } from './agentRunPartState.js'

function message() {
  return { content: '', thinkingBlocks: [], toolCalls: [], _nextOrder: 0 }
}

test('replays durable tool and reasoning parts idempotently', () => {
  const target = message()
  const parts = [
    { partId: 1, partKey: 'reasoning:1', partType: 'reasoning', output: 'inspect project', sequence: 1 },
    { partId: 2, partKey: 'tool:call-1', partType: 'tool', toolCallId: 'call-1', tool: 'run_tests', input: '{"strategy":"test"}', output: 'passed', status: 'completed', sequence: 2 }
  ]

  applyRunPartSnapshot(target, parts)
  applyRunPartSnapshot(target, parts)

  assert.equal(target.thinkingBlocks.length, 1)
  assert.equal(target.toolCalls.length, 1)
  assert.equal(target.toolCalls[0].durableStatus, 'completed')
  assert.deepEqual(target.toolCalls[0].args, { strategy: 'test' })
})

test('projects provider tool_call and tool_result parts into one durable tool card', () => {
  const target = message()
  const parts = [
    { partId: 11, partKey: 'provider:0:tool-call:1:call-1', partType: 'tool_call',
      toolCallId: 'call-1', tool: 'read_file', input: '{"path":"README.md"}',
      output: 'Waiting for user approval.', status: 'waiting_approval', sequence: 2 },
    { partId: 12, partKey: 'provider:0:tool-result:2:call-1', partType: 'tool_result',
      toolCallId: 'call-1', tool: 'read_file', input: '{}', output: 'file content',
      status: 'completed', sequence: 3 }
  ]

  applyRunPartSnapshot(target, parts)

  assert.equal(target.toolCalls.length, 1)
  assert.equal(target.toolCalls[0].toolCallId, 'call-1')
  assert.deepEqual(target.toolCalls[0].args, { path: 'README.md' })
  assert.equal(target.toolCalls[0].durableStatus, 'completed')
  assert.equal(target.toolCalls[0].result, 'file content')
})

test('restores final content and completion evidence from message and part snapshots', () => {
  const target = message()
  applyRunMessageSnapshot(target, [
    { messageId: 7, messageKey: 'assistant:final', sequence: 9, content: 'done', status: 'completed' }
  ])
  applyRunPartSnapshot(target, [
    { partId: 8, partType: 'completion_evidence', output: '{"satisfied":true}', sequence: 8 }
  ])

  assert.equal(target.content, 'done')
  assert.equal(target.completionEvidence.satisfied, true)
})