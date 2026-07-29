import assert from 'node:assert/strict'
import test from 'node:test'

import { CdpClient } from './cdp-client.mjs'

class FakeSocket extends EventTarget {
  static OPEN = 1
  readyState = 1
  sent = []

  send(payload) {
    this.sent.push(JSON.parse(payload))
  }

  reply(message) {
    const event = new Event('message')
    Object.defineProperty(event, 'data', { value: JSON.stringify(message) })
    this.dispatchEvent(event)
  }

  close() {
    this.readyState = 3
    this.dispatchEvent(new Event('close'))
  }
}

test('correlates out-of-order CDP responses by request id', async () => {
  const socket = new FakeSocket()
  const client = new CdpClient(socket, { timeoutMs: 100 })

  const first = client.send('Runtime.evaluate', { expression: '1 + 1' })
  const second = client.send('Runtime.evaluate', { expression: '2 + 2' })
  assert.equal(socket.sent.length, 2)

  socket.reply({ id: socket.sent[1].id, result: { result: { value: 4 } } })
  socket.reply({ id: socket.sent[0].id, result: { result: { value: 2 } } })

  assert.equal((await first).result.value, 2)
  assert.equal((await second).result.value, 4)
  client.close()
})

test('evaluate returns JSON-serializable values and surfaces exception details', async () => {
  const socket = new FakeSocket()
  const client = new CdpClient(socket, { timeoutMs: 100 })

  const success = client.evaluate('({ ok: true })')
  socket.reply({ id: socket.sent[0].id, result: { result: { value: { ok: true } } } })
  assert.deepEqual(await success, { ok: true })

  const failure = client.evaluate('throw new Error("boom")')
  socket.reply({
    id: socket.sent[1].id,
    result: { exceptionDetails: { text: 'Uncaught', exception: { description: 'Error: boom' } } }
  })
  await assert.rejects(failure, /Error: boom/)
  client.close()
})

test('times out pending requests and rejects requests when the socket closes', async () => {
  const socket = new FakeSocket()
  const client = new CdpClient(socket, { timeoutMs: 20 })

  await assert.rejects(client.send('Runtime.evaluate', {}), /timed out/i)

  const pending = client.send('Page.navigate', { url: 'http://localhost' })
  socket.close()
  await assert.rejects(pending, /closed/i)
})
