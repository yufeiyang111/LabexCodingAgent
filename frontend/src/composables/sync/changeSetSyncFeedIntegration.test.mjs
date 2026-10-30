import assert from 'node:assert/strict'
import test from 'node:test'

import { createChangeSetSyncOrchestrator } from './changeSetSyncOrchestrator.js'
import { SYNC_STATES } from './syncSessionState.js'
import { createJournalStore, createMemoryAdapter } from './syncTestDoubles.mjs'

// 接线路径：loadChanges 直接返回服务端 feed 形态（changeSets[].files[]），
// orchestrator 不需要额外的适配层就能规划——这是把前端切到 /changes/feed 的最小前提。
test('persists the feed nextCursor as two progress lines after a successful run', async () => {
  const adapter = createMemoryAdapter({ 'src/app.js': 'old' })
  const cursorSeen = []
  const journalStore = createJournalStore({
    version: 1,
    entries: [],
    cursor: 10,
    changeSetCursor: {
      afterChangeSetId: 10,
      tailUpdateTime: '2026-10-06T09:59:59',
      tailChangeId: 'tail-0'
    }
  })
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    loadChanges: async ({ cursor }) => {
      cursorSeen.push(cursor)
      return {
        changeSets: [{
          changeSetId: 11,
          taskId: 1,
          status: 'pending',
          updateTime: '2026-10-06T10:00:00',
          files: [{
            changeId: 'f1', changeSetId: 11, relativePath: 'src/app.js', changeType: 'modify',
            status: 'pending', beforeExisted: true, afterExisted: true,
            beforeTruncated: false, afterTruncated: false,
            beforeContent: 'old', afterContent: 'new', updateTime: '2026-10-06T10:00:00'
          }]
        }],
        changedFiles: [],
        nextCursor: { afterChangeSetId: 11, tailUpdateTime: '2026-10-06T10:00:01', tailChangeId: 'tail-1' },
        hasMore: false,
        hasMoreChangeSets: false,
        hasMoreChangedFiles: false
      }
    },
    journalStore
  })

  const session = await orchestrator.run()
  assert.equal(session.state, SYNC_STATES.DONE)
  assert.deepEqual(cursorSeen[0], {
    afterChangeSetId: 10,
    tailUpdateTime: '2026-10-06T09:59:59',
    tailChangeId: 'tail-0'
  })
  assert.deepEqual(journalStore.stored.changeSetCursor, {
    afterChangeSetId: 11,
    tailUpdateTime: '2026-10-06T10:00:01',
    tailChangeId: 'tail-1'
  })
  assert.equal(journalStore.stored.cursor, 11)
})

test('persists a tail-only feed cursor even when there are no apply steps', async () => {
  const adapter = createMemoryAdapter()
  const journalStore = createJournalStore({
    version: 1,
    entries: [],
    cursor: 10,
    changeSetCursor: { afterChangeSetId: 10, tailUpdateTime: '2026-10-06T09:59:59', tailChangeId: 'old' }
  })
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    loadChanges: async () => ({
      changeSets: [],
      changedFiles: [],
      nextCursor: { afterChangeSetId: 10, tailUpdateTime: '2026-10-06T10:00:02', tailChangeId: 'new' },
      hasMore: false,
      hasMoreChangeSets: false,
      hasMoreChangedFiles: false
    }),
    journalStore
  })

  const session = await orchestrator.run()
  assert.equal(session.state, SYNC_STATES.DONE)
  assert.deepEqual(journalStore.stored.changeSetCursor, {
    afterChangeSetId: 10,
    tailUpdateTime: '2026-10-06T10:00:02',
    tailChangeId: 'new'
  })
  assert.equal(adapter.writes.length, 0)
})

test('consumes the server feed shape (changeSets[].files[]) without an adapter layer', async () => {
  const adapter = createMemoryAdapter({ 'src/app.js': 'old' })
  const journalStore = createJournalStore(undefined)
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    loadChanges: async () => ({
      changeSets: [{
        changeSetId: 11,
        taskId: 1,
        status: 'pending',
        updateTime: '2026-10-06T10:00:00',
        files: [{
          changeId: 'f1',
          changeSetId: 11,
          relativePath: 'src/app.js',
          changeType: 'modify',
          status: 'pending',
          beforeExisted: true,
          afterExisted: true,
          beforeTruncated: false,
          afterTruncated: false,
          beforeContent: 'old',
          afterContent: 'new',
          updateTime: '2026-10-06T10:00:00'
        }]
      }],
      changedFiles: [],
      nextCursor: { afterChangeSetId: 11, tailUpdateTime: '2026-10-06T10:00:00', tailChangeId: '' },
      hasMore: false
    }),
    journalStore
  })

  const session = await orchestrator.run()
  assert.equal(session.state, SYNC_STATES.DONE)
  assert.equal(session.cursor, 11)
  assert.equal(adapter.files.get('src/app.js'), 'new')
  assert.equal(journalStore.stored.entries.length, 1)
})
