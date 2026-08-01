import assert from 'node:assert/strict'
import test from 'node:test'

import { isExpectedRestartTransportError } from './browser-error-policy.mjs'

test('only allows the task subscription transport error after a real restart was verified', () => {
  const verified = { restartProjectionVerified: true, restartInteractionVerified: true }
  assert.equal(isExpectedRestartTransportError(
    'Failed to load resource: the server responded with a status of 500 (Internal Server Error) url=http://127.0.0.1:13000/api/student/projects/203/agent/tasks/1217/subscribe',
    verified
  ), true)
  assert.equal(isExpectedRestartTransportError(
    'warning: Agent task subscription disconnected; reconnecting from durable cursor: Error: HTTP 500: Internal Server Error',
    verified
  ), true)
  assert.equal(isExpectedRestartTransportError(
    'Failed to load resource: the server responded with a status of 500 url=http://127.0.0.1:13000/api/student/projects/203/agent/conversations/abc/active-task',
    verified
  ), true)
  assert.equal(isExpectedRestartTransportError(
    'Failed to load resource: the server responded with a status of 500 url=http://127.0.0.1:13000/api/student/projects/203/files',
    verified
  ), false)
  assert.equal(isExpectedRestartTransportError(
    'Failed to load resource: the server responded with a status of 500 url=http://127.0.0.1:13000/api/student/projects/203/agent/tasks/1217/subscribe',
    { restartProjectionVerified: false, restartInteractionVerified: false }
  ), false)
})
