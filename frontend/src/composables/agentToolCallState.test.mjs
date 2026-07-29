import assert from 'node:assert/strict'
import test from 'node:test'
import { upsertDurableToolCallState, visibleToolCallStatus } from './agentToolCallState.js'

test('durable tool state merges into one idempotent tool card', () => {
  const message = { toolCalls: [], _nextOrder: 0 }
  upsertDurableToolCallState(message, {
    toolCallId: 'call-1', tool: 'run_tests', arguments: { command: 'mvn test' }, status: 'pending'
  })
  upsertDurableToolCallState(message, {
    toolCallId: 'call-1', tool: 'run_tests', arguments: { command: 'mvn test' }, status: 'running'
  })
  upsertDurableToolCallState(message, {
    toolCallId: 'call-1', tool: 'run_tests', arguments: { command: 'mvn test' }, status: 'environment_blocked', detail: 'DNS unavailable'
  })

  assert.equal(message.toolCalls.length, 1)
  assert.equal(message.toolCalls[0].durableStatus, 'environment_blocked')
  assert.equal(message.toolCalls[0].status, 'warning')
  assert.equal(message.toolCalls[0].result, 'DNS unavailable')
})

test('durable status maps to existing visual card states', () => {
  assert.equal(visibleToolCallStatus('pending'), 'running')
  assert.equal(visibleToolCallStatus('waiting_approval'), 'waiting_approval')
  assert.equal(visibleToolCallStatus('waiting_user'), 'waiting_user')
  assert.equal(visibleToolCallStatus('completed'), 'completed')
  assert.equal(visibleToolCallStatus('environment_blocked'), 'warning')
  assert.equal(visibleToolCallStatus('skipped'), 'skipped')
  assert.equal(visibleToolCallStatus('interrupted'), 'interrupted')
})

test('durable waiting_user state preserves the question request for the reply card', () => {
  const message = { toolCalls: [], _nextOrder: 0 }
  upsertDurableToolCallState(message, {
    toolCallId: 'question-1',
    tool: 'question',
    arguments: { question: 'Continue?', options: ['Continue', 'Stop'] },
    status: 'waiting_user',
    interactionPayload: {
      requestId: 'request-1',
      question: 'Continue?',
      summary: 'Please confirm',
      options: ['Continue', 'Stop']
    }
  })

  assert.equal(message.toolCalls[0].status, 'waiting_user')
  assert.equal(message.toolCalls[0].questionRequest.requestId, 'request-1')
  assert.deepEqual(message.toolCalls[0].questionRequest.options, ['Continue', 'Stop'])
})
