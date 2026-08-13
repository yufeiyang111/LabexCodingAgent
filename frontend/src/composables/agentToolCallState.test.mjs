import assert from 'node:assert/strict'
import test from 'node:test'
import { applyStructuredExecutionStatus, upsertDurableToolCallState, visibleToolCallStatus } from './agentToolCallState.js'

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

test('shell part terminal statuses stay distinguishable', () => {
  assert.equal(visibleToolCallStatus('timed_out'), 'error')
  assert.equal(visibleToolCallStatus('failed'), 'error')
  assert.equal(visibleToolCallStatus('error'), 'error')
  assert.equal(visibleToolCallStatus('cancelled'), 'interrupted')
  assert.equal(visibleToolCallStatus('running'), 'running')
})

test('structured executionStatus projects timed_out without parsing output text', () => {
  const call = { toolCallId: 'call-1', status: 'running' }
  applyStructuredExecutionStatus(call, 'timed_out')

  assert.equal(call.status, 'error')
  assert.equal(call.durableStatus, 'timed_out')
  assert.equal(call.executionStatus, 'timed_out')
})

test('structured executionStatus projects cancelled and infrastructure_error', () => {
  const cancelled = { toolCallId: 'call-2', status: 'running' }
  applyStructuredExecutionStatus(cancelled, 'cancelled')
  assert.equal(cancelled.status, 'interrupted')
  assert.equal(cancelled.durableStatus, 'cancelled')

  const infrastructure = { toolCallId: 'call-3', status: 'running' }
  applyStructuredExecutionStatus(infrastructure, 'infrastructure_error')
  assert.equal(infrastructure.status, 'warning')
  assert.equal(infrastructure.durableStatus, 'environment_blocked')
})

test('structured executionStatus leaves ordinary successes untouched', () => {
  const call = { toolCallId: 'call-4', status: 'completed', durableStatus: 'completed' }
  applyStructuredExecutionStatus(call, 'succeeded')

  assert.equal(call.status, 'completed')
  assert.equal(call.durableStatus, 'completed')
})

test('durable waiting_approval state preserves a permission request for the approval card', () => {
  const message = { toolCalls: [], _nextOrder: 0 }
  upsertDurableToolCallState(message, {
    toolCallId: 'permission-1',
    tool: 'read_file',
    arguments: { file_path: '.env' },
    status: 'waiting_approval',
    interactionPayload: {
      requestId: 'request-permission-1',
      interactionType: 'permission',
      toolName: 'read_file',
      summary: 'Approval required'
    }
  })

  assert.equal(message.toolCalls[0].status, 'waiting_approval')
  assert.equal(message.toolCalls[0].permissionRequest.requestId, 'request-permission-1')
  assert.equal(message.toolCalls[0].questionRequest, undefined)
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
