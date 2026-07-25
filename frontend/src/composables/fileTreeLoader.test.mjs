import assert from 'node:assert/strict'
import test from 'node:test'

import { loadFileTreeWithRetry } from './fileTreeLoader.js'

test('retries a transient file-tree request failure before returning data', async () => {
  let calls = 0
  const nodes = await loadFileTreeWithRetry(async () => {
    calls += 1
    if (calls === 1) throw new Error('backend starting')
    return [{ path: 'README.md' }]
  }, { attempts: 2, delayMs: 0 })

  assert.equal(calls, 2)
  assert.deepEqual(nodes, [{ path: 'README.md' }])
})

test('does not retry a successful empty tree response', async () => {
  let calls = 0
  const nodes = await loadFileTreeWithRetry(async () => {
    calls += 1
    return []
  }, { attempts: 2, delayMs: 0 })

  assert.equal(calls, 1)
  assert.deepEqual(nodes, [])
})

test('throws the original error after all file-tree attempts fail', async () => {
  const failure = new Error('backend unavailable')
  let calls = 0

  await assert.rejects(
    () => loadFileTreeWithRetry(async () => {
      calls += 1
      throw failure
    }, { attempts: 2, delayMs: 0 }),
    (error) => error === failure
  )

  assert.equal(calls, 2)
})
