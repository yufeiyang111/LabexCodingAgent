import assert from 'node:assert/strict'
import test from 'node:test'

import { createChangeSetSyncOrchestrator } from './changeSetSyncOrchestrator.js'
import { SYNC_STATES } from './syncSessionState.js'
import { createJournalStore, createMemoryAdapter } from './syncTestDoubles.mjs'

// 需求（用户 2026-11-05）：同步要能中止，而不是只能等它跑完或关页面。
// 中止必须是**受控**的：已写盘的文件仍要有 journal 记录，游标不能越过未完成的部分。

function feedOf(count) {
  return {
    changeSets: [{
      changeSetId: 1,
      files: Array.from({ length: count }, (_, index) => ({
        changeId: `c${index}`,
        changeSetId: 1,
        relativePath: `src/f${index}.js`,
        changeType: 'create',
        status: 'pending',
        beforeExisted: false,
        afterExisted: true,
        beforeTruncated: false,
        afterTruncated: false,
        beforeContent: null,
        afterContent: `content-${index}`,
        updateTime: '2026-11-05T10:00:00'
      }))
    }],
    changedFiles: [],
    nextCursor: { afterChangeSetId: 1, tailUpdateTime: 'x', tailChangeId: '' },
    hasMore: false
  }
}

test('cancelling mid-run stops writing further files', async () => {
  const inner = createMemoryAdapter({})
  const writes = []
  let orchestrator = null
  // 写到第 5 个时请求中止，确保取消发生在循环中途（而非 run 结束后）。
  const adapter = {
    files: inner.files,
    async listPaths() { return inner.listPaths() },
    async fileExists(p) { return inner.fileExists(p) },
    async readTextFile(p) { return inner.readTextFile(p) },
    async writeTextFile(p, c) {
      writes.push(p)
      if (writes.length === 5) orchestrator.cancel()
      return inner.writeTextFile(p, c)
    },
    async deleteFile(p) { return inner.deleteFile(p) }
  }

  orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    journalStore: createJournalStore(undefined),
    loadChanges: async () => feedOf(20)
  })

  const session = await orchestrator.run({ projectId: 1 })

  assert.equal(session.state, SYNC_STATES.FAILED)
  assert.match(session.error, /中止|cancel/i)
  assert.equal(writes.length, 5, `中止后不应继续写，实际写了 ${writes.length}`)
})

test('a cancelled run still records the files it already wrote', async () => {
  const inner = createMemoryAdapter({})
  const journalStore = createJournalStore(undefined)
  let orchestrator = null
  let writes = 0
  const adapter = {
    files: inner.files,
    async listPaths() { return inner.listPaths() },
    async fileExists(p) { return inner.fileExists(p) },
    async readTextFile(p) { return inner.readTextFile(p) },
    async writeTextFile(p, c) {
      writes += 1
      if (writes === 6) orchestrator.cancel()
      return inner.writeTextFile(p, c)
    },
    async deleteFile(p) { return inner.deleteFile(p) }
  }

  orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    journalStore,
    loadChanges: async () => feedOf(20)
  })

  const session = await orchestrator.run({ projectId: 1 })
  assert.equal(session.state, SYNC_STATES.FAILED)

  // 已写盘的文件必须有回退依据，否则中止后无法回退
  assert.ok(journalStore.stored, '中止后必须留下 journal')
  assert.equal(journalStore.stored.entries.length, writes)
})

test('cancelling before the run starts makes it fail fast without writing', async () => {
  const adapter = createMemoryAdapter({})
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    journalStore: createJournalStore(undefined),
    loadChanges: async () => feedOf(10)
  })

  orchestrator.cancel()
  const session = await orchestrator.run({ projectId: 1 })

  assert.equal(session.state, SYNC_STATES.FAILED)
  assert.equal(adapter.writes.length, 0)
})

test('a completed run is not affected by a late cancel', async () => {
  const adapter = createMemoryAdapter({})
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    journalStore: createJournalStore(undefined),
    loadChanges: async () => feedOf(3)
  })

  const session = await orchestrator.run({ projectId: 1 })
  orchestrator.cancel()

  assert.equal(session.state, SYNC_STATES.DONE)
  assert.equal(adapter.writes.length, 3)
})

test('cancel is idempotent', async () => {
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter: createMemoryAdapter({}),
    journalStore: createJournalStore(undefined),
    loadChanges: async () => feedOf(2)
  })

  orchestrator.cancel()
  orchestrator.cancel()
  orchestrator.cancel()

  const session = await orchestrator.run({ projectId: 1 })
  assert.equal(session.state, SYNC_STATES.FAILED)
})
