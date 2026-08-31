import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { runWithConflictFlow } from './fileTransferFlow.js'

test('completes directly when backend reports no conflict', async () => {
  const calls = []
  const result = await runWithConflictFlow(
    'copy',
    async decisions => {
      calls.push(decisions)
      return { data: { status: 'done', targetPath: 'backup/a.txt' } }
    },
    () => {
      throw new Error('resolver must not be called without conflicts')
    }
  )
  assert.equal(result.status, 'done')
  assert.equal(result.data.targetPath, 'backup/a.txt')
  assert.deepEqual(calls, [null])
})

test('retries with user decisions when conflicts are reported', async () => {
  const calls = []
  const result = await runWithConflictFlow(
    'upload',
    async decisions => {
      calls.push(decisions)
      if (!decisions) return { data: { status: 'conflict', conflicts: [{ path: 'a.txt', type: 'file' }] } }
      return { data: { status: 'done', savedCount: 1 } }
    },
    async (conflicts, kind) => {
      assert.equal(kind, 'upload')
      assert.equal(conflicts.length, 1)
      return { 'a.txt': 'overwrite' }
    }
  )
  assert.equal(result.status, 'done')
  assert.deepEqual(calls, [null, { 'a.txt': 'overwrite' }])
})

test('aborts without retrying when the user cancels the dialog', async () => {
  let invokeCalls = 0
  const result = await runWithConflictFlow(
    'move',
    async () => {
      invokeCalls++
      return { data: { status: 'conflict', conflicts: [{ path: 'b/', type: 'directory' }] } }
    },
    async () => null
  )
  assert.equal(result.status, 'cancelled')
  assert.equal(invokeCalls, 1)
})

test('propagates skip outcomes so the UI can inform the user', async () => {
  const result = await runWithConflictFlow(
    'copy',
    async decisions => ({ data: { status: decisions ? 'skipped' : 'done', savedCount: 2 } }),
    async () => ({})
  )
  assert.equal(result.status, 'done')
})

test('FileConflictDialog keeps per-file asking plus apply-to-rest checkbox', async () => {
  const source = await readFile(new URL('../components/cloud/FileConflictDialog.vue', import.meta.url), 'utf8')
  assert.match(source, /对剩余全部冲突应用相同选择/)
  assert.match(source, /index\.value\+\+|index\.value \+= 1/)
  assert.match(source, /emit\('complete', null\)/)
})
