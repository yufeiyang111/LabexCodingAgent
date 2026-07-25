import assert from 'node:assert/strict'
import test from 'node:test'

import { consumeAgentSse } from './agentSse.js'

test('preserves SSE event ids across split chunks for replay cursors', async () => {
  const encoder = new TextEncoder()
  const body = new ReadableStream({
    start(controller) {
      controller.enqueue(encoder.encode('id: 41\nevent: THINK\ndata: {"type":"THINK","data":{"content":"Inspect'))
      controller.enqueue(encoder.encode('ing"}}\n\nid: 42\nevent: FINAL\ndata: {"type":"FINAL","data":{"content":"Done"}}\n\n'))
      controller.close()
    }
  })
  const events = []

  await consumeAgentSse(body, event => events.push(event))

  assert.deepEqual(events, [
    { type: 'THINK', data: { content: 'Inspecting' }, eventId: '41' },
    { type: 'FINAL', data: { content: 'Done' }, eventId: '42' }
  ])
})
