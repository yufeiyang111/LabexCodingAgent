import assert from 'node:assert/strict'
import test from 'node:test'

import {
  formatAgentStreamError,
  initialAgentStreamState,
  needsAgentResponseFallback,
  reduceAgentStreamState
} from './agentStreamState.js'

test('stop transitions a running stream once and allows a later reconnect', () => {
  const running = reduceAgentStreamState(initialAgentStreamState(), { type: 'START', sessionId: 'session-1' })
  const stopping = reduceAgentStreamState(running, { type: 'STOP_REQUESTED' })
  const repeatedStop = reduceAgentStreamState(stopping, { type: 'STOP_REQUESTED' })
  const stopped = reduceAgentStreamState(repeatedStop, { type: 'STREAM_ENDED' })
  const reconnected = reduceAgentStreamState(stopped, { type: 'START', sessionId: 'session-2' })

  assert.equal(running.status, 'streaming')
  assert.equal(stopping.status, 'cancelling')
  assert.equal(repeatedStop, stopping)
  assert.equal(stopped.status, 'idle')
  assert.equal(reconnected.status, 'streaming')
  assert.equal(reconnected.sessionId, 'session-2')
})

test('retains the latest task and event cursor after a stream ends', () => {
  const started = reduceAgentStreamState(initialAgentStreamState(), { type: 'START', sessionId: 'session-1' })
  const received = reduceAgentStreamState(started, { type: 'EVENT_RECEIVED', taskId: 71, eventId: '42' })
  const ended = reduceAgentStreamState(received, { type: 'STREAM_ENDED' })

  assert.equal(received.taskId, 71)
  assert.equal(received.lastEventId, '42')
  assert.equal(ended.status, 'idle')
  assert.equal(ended.taskId, 71)
  assert.equal(ended.lastEventId, '42')
})

test('durable event cursor never moves backward on duplicate or out-of-order delivery', () => {
  const started = reduceAgentStreamState(initialAgentStreamState(), { type: 'START', sessionId: 'session-1' })
  const sequence10 = reduceAgentStreamState(started, { type: 'EVENT_RECEIVED', taskId: 71, eventId: '10' })
  const stale9 = reduceAgentStreamState(sequence10, { type: 'EVENT_RECEIVED', taskId: 71, eventId: '9' })
  const duplicate10 = reduceAgentStreamState(stale9, { type: 'EVENT_RECEIVED', taskId: 71, eventId: '10' })
  const sequence11 = reduceAgentStreamState(duplicate10, { type: 'EVENT_RECEIVED', taskId: 71, eventId: '11' })

  assert.equal(stale9.lastEventId, '10')
  assert.equal(duplicate10.lastEventId, '10')
  assert.equal(sequence11.lastEventId, '11')
})

test('formats a stream error as visible assistant content', () => {
  assert.equal(formatAgentStreamError('模型 API 调用失败'), '错误: 模型 API 调用失败')
  assert.equal(formatAgentStreamError(), '错误: Agent 请求失败，请稍后重试')
})

test('marks an eventless completed stream for a visible fallback message', () => {
  assert.equal(needsAgentResponseFallback({ content: '', thinking: '', thinkingBlocks: [], toolCalls: [], error: null }), true)
  assert.equal(needsAgentResponseFallback({ content: '', thinkingBlocks: [{ content: 'Preparing context' }], toolCalls: [], error: null }), false)
  assert.equal(needsAgentResponseFallback({ content: '', thinkingBlocks: [], toolCalls: [{ name: 'read_file' }], error: null }), false)
  assert.equal(needsAgentResponseFallback({ content: 'Completed', thinkingBlocks: [], toolCalls: [], error: null }), false)
})
