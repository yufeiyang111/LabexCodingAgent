import assert from 'node:assert/strict'
import test from 'node:test'

import { parseSse } from './agent-sse.mjs'
import { assertEventSequence, redactEvidence } from './evidence.mjs'

test('parseSse preserves event ids, event names, and multiline JSON data', () => {
  const frames = [
    'id: 41',
    'event: THINK',
    'data: {"type":"THINK",',
    'data: "data":{"content":"Inspecting"}}',
    '',
    'id: 42',
    'event: FINAL',
    'data: {"type":"FINAL","data":{"content":"Done"}}',
    '',
    ''
  ].join('\r\n')

  assert.deepEqual(parseSse(frames), [
    { type: 'THINK', data: { content: 'Inspecting' }, eventId: '41', sseEvent: 'THINK' },
    { type: 'FINAL', data: { content: 'Done' }, eventId: '42', sseEvent: 'FINAL' }
  ])
})

test('parseSse unwraps Result-style JSON envelopes and keeps cursor metadata', () => {
  const text = 'id: 9\ndata: {"code":0,"data":{"type":"RUN_STATE","data":{"state":"RUNNING"}}}\n\n'

  assert.deepEqual(parseSse(text), [
    { type: 'RUN_STATE', data: { state: 'RUNNING' }, eventId: '9' }
  ])
})

test('parseSse ignores comments and malformed frames while reporting diagnostics', () => {
  const diagnostics = []
  const text = ': heartbeat\n\nid: bad\ndata: {not-json}\n\nid: 3\ndata: {"type":"FINAL","data":{}}\n\n'

  assert.deepEqual(parseSse(text, { onMalformed: item => diagnostics.push(item) }), [
    { type: 'FINAL', data: {}, eventId: '3' }
  ])
  assert.equal(diagnostics.length, 1)
  assert.equal(diagnostics[0].eventId, 'bad')
  assert.match(diagnostics[0].message, /JSON/i)
})

test('assertEventSequence accepts ordered subsequences and rejects missing transitions', () => {
  const events = [{ type: 'RUN_STATE' }, { type: 'QUESTION' }, { type: 'RUN_STATE' }, { type: 'FINAL' }]
  assert.doesNotThrow(() => assertEventSequence(events, ['RUN_STATE', 'QUESTION', 'FINAL']))
  assert.throws(() => assertEventSequence(events, ['QUESTION', 'PERMISSION_REQUEST']), /PERMISSION_REQUEST/)
})

test('redactEvidence recursively removes credentials, tokens, cookies, and authorization headers', () => {
  const source = {
    taskId: 7,
    token: 'secret-token',
    nested: {
      password: 'secret-password',
      Authorization: 'Bearer secret',
      cookie: 'session=secret',
      safe: 'visible'
    },
    list: [{ apiKey: 'key' }, 'plain']
  }

  assert.deepEqual(redactEvidence(source), {
    taskId: 7,
    token: '[REDACTED]',
    nested: {
      password: '[REDACTED]',
      Authorization: '[REDACTED]',
      cookie: '[REDACTED]',
      safe: 'visible'
    },
    list: [{ apiKey: '[REDACTED]' }, 'plain']
  })
})
