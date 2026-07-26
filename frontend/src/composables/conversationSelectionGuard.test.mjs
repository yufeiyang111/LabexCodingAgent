import assert from 'node:assert/strict'
import test from 'node:test'
import { createConversationSelectionGuard } from './conversationSelectionGuard.js'

test('invalidates a delayed startup conversation selection after explicit new-session intent', () => {
  const guard = createConversationSelectionGuard()
  const startupSelection = guard.capture()

  guard.invalidate()

  assert.equal(guard.isCurrent(startupSelection), false)
  assert.equal(guard.isCurrent(guard.capture()), true)
})

test('each explicit intent invalidates every older asynchronous selection', () => {
  const guard = createConversationSelectionGuard()
  const first = guard.capture()
  guard.invalidate()
  const second = guard.capture()
  guard.invalidate()

  assert.equal(guard.isCurrent(first), false)
  assert.equal(guard.isCurrent(second), false)
  assert.equal(guard.isCurrent(guard.capture()), true)
})