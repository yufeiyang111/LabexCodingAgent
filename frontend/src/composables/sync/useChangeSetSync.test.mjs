import assert from 'node:assert/strict'
import test from 'node:test'

import { createJournal, updateJournalCursor } from './syncJournal.js'
import { SYNC_STATES } from './syncSessionState.js'
import { createMemoryAdapter } from './syncTestDoubles.mjs'
import { useChangeSetSync } from './useChangeSetSync.js'

// 接线层的职责：把真实 API / IndexedDB / 目录句柄装成 orchestrator 需要的依赖，
// 并把会话状态以响应式形式暴露给 UI。它自己不判定冲突、不写文件。

function feedPage(overrides = {}) {
  return {
    changeSets: [],
    changedFiles: [],
    nextCursor: { afterChangeSetId: 0, tailUpdateTime: null, tailChangeId: '' },
    hasMore: false,
    hasMoreChangeSets: false,
    hasMoreChangedFiles: false,
    ...overrides
  }
}

function createHarness(options = {}) {
  const adapter = options.adapter ?? createMemoryAdapter({ 'src/app.js': 'old' })
  const pages = options.pages ?? [feedPage()]
  const feedCalls = []
  let storedJournal = options.journal ?? null
  const handles = new Map()

  const sync = useChangeSetSync({
    projectId: () => options.projectId ?? 7,
    api: {
      agentChangeFeed: async (projectId, cursor) => {
        feedCalls.push({ projectId, cursor })
        const page = pages.length > 1 ? pages.shift() : pages[0]
        return { data: page }
      }
    },
    journalStore: {
      load: async () => storedJournal,
      save: async journal => { storedJournal = journal }
    },
    adapterFactory: async () => adapter,
    directoryPicker: async () => ({ name: 'picked' }),
    // 目录句柄持久化的替身：Node 环境没有 IndexedDB，不注入会走默认实现并返回 false。
    handleStore: {
      load: async key => handles.get(key) ?? null,
      save: async (key, handle) => { handles.set(key, handle) },
      clear: async key => { handles.delete(key) }
    },
    notify: { success() {}, error() {}, warning() {} }
  })

  return { sync, adapter, feedCalls, handles, getJournal: () => storedJournal }
}

test('exposes an idle session before any directory is authorized', () => {
  const { sync } = createHarness()
  assert.equal(sync.session.value.state, SYNC_STATES.IDLE)
  assert.equal(sync.hasDirectory.value, false)
  assert.equal(sync.busy.value, false)
})

test('refuses to run before a local directory is authorized, without touching the network', async () => {
  const { sync, feedCalls } = createHarness()
  const session = await sync.run()

  assert.equal(session.state, SYNC_STATES.FAILED)
  assert.match(session.error, /目录/)
  assert.equal(feedCalls.length, 0)
})

test('picking a directory makes the adapter available and keeps the picker failure explicit', async () => {
  const { sync } = createHarness()
  assert.equal(await sync.pickDirectory(), true)
  assert.equal(sync.hasDirectory.value, true)

  const failing = useChangeSetSync({
    projectId: () => 7,
    api: { agentChangeFeed: async () => ({ data: feedPage() }) },
    journalStore: { load: async () => null, save: async () => {} },
    adapterFactory: async () => createMemoryAdapter(),
    // 真实失败（不是用户取消）：必须如实报错。
    directoryPicker: async () => { throw new Error('SecurityError: picker blocked') },
    handleStore: { load: async () => null, save: async () => {}, clear: async () => {} },
    notify: { success() {}, error() {}, warning() {} }
  })
  assert.equal(await failing.pickDirectory(), false)
  assert.match(failing.session.value.error, /SecurityError/)
})

test('loads every feed page until both progress lines converge, forwarding the cursor verbatim', async () => {
  const pages = [
    feedPage({
      changeSets: [],
      changedFiles: [],
      nextCursor: { afterChangeSetId: 0, tailUpdateTime: '2026-10-06T10:00:00', tailChangeId: 'a' },
      hasMore: true,
      hasMoreChangedFiles: true
    }),
    feedPage({
      nextCursor: { afterChangeSetId: 0, tailUpdateTime: '2026-10-06T10:00:05', tailChangeId: 'b' }
    })
  ]
  const { sync, feedCalls } = createHarness({ pages })
  await sync.pickDirectory()
  await sync.run()

  assert.equal(feedCalls.length, 2)
  assert.equal(feedCalls[0].projectId, 7)
  assert.deepEqual(feedCalls[1].cursor, {
    afterChangeSetId: 0,
    tailUpdateTime: '2026-10-06T10:00:00',
    tailChangeId: 'a',
    limit: undefined
  })
})

test('persists the journal cursor after a successful run and reports the applied count', async () => {
  const journal = updateJournalCursor(createJournal(), {
    afterChangeSetId: 0,
    tailUpdateTime: '2026-10-06T09:00:00',
    tailChangeId: ''
  })
  const { sync, adapter, getJournal } = createHarness({
    journal,
    pages: [feedPage({
      changeSets: [{
        changeSetId: 1,
        taskId: 3,
        status: 'pending',
        updateTime: '2026-10-06T10:00:00',
        files: [{
          changeId: 'f1', changeSetId: 1, relativePath: 'src/app.js', changeType: 'modify',
          status: 'pending', beforeExisted: true, beforeContent: 'old', afterContent: 'new',
          beforeTruncated: false, afterTruncated: false
        }]
      }],
      nextCursor: { afterChangeSetId: 1, tailUpdateTime: '2026-10-06T10:00:03', tailChangeId: 'z' }
    })]
  })

  await sync.pickDirectory()
  const session = await sync.run()

  assert.equal(session.state, SYNC_STATES.DONE)
  assert.equal(session.applied, 1)
  assert.equal(adapter.files.get('src/app.js'), 'new')
  assert.equal(getJournal().changeSetCursor.afterChangeSetId, 1)
  assert.equal(getJournal().changeSetCursor.tailChangeId, 'z')
})

test('reports a blocked plan with conflicts instead of silently applying the safe subset', async () => {
  const { sync, adapter } = createHarness({
    adapter: createMemoryAdapter({ 'src/app.js': 'locally edited' }),
    pages: [feedPage({
      changeSets: [{
        changeSetId: 1,
        files: [{
          changeId: 'f1', changeSetId: 1, relativePath: 'src/app.js', changeType: 'modify',
          status: 'pending', beforeExisted: true, beforeContent: 'old', afterContent: 'new',
          beforeTruncated: false, afterTruncated: false
        }]
      }]
    })]
  })

  await sync.pickDirectory()
  const session = await sync.run()

  assert.equal(session.state, SYNC_STATES.BLOCKED)
  assert.equal(session.conflicts.length, 1)
  assert.equal(adapter.writes.length, 0)
  assert.equal(sync.conflictCount.value, 1)
})

test('surfaces a feed transport failure as a failed session rather than an empty success', async () => {
  const sync = useChangeSetSync({
    projectId: () => 7,
    api: { agentChangeFeed: async () => { throw new Error('gateway timeout') } },
    journalStore: { load: async () => null, save: async () => {} },
    adapterFactory: async () => createMemoryAdapter(),
    directoryPicker: async () => ({}),
    notify: { success() {}, error() {}, warning() {} }
  })

  await sync.pickDirectory()
  const session = await sync.run()

  assert.equal(session.state, SYNC_STATES.FAILED)
  assert.match(session.error, /gateway timeout/)
})

test('refuses a second run while one is already in flight', async () => {
  let release
  const gate = new Promise(resolve => { release = resolve })
  const { sync } = createHarness({
    pages: [feedPage({
      changeSets: [],
      nextCursor: { afterChangeSetId: 0, tailUpdateTime: '2026-10-06T10:00:00', tailChangeId: '' }
    })]
  })
  await sync.pickDirectory()

  const first = sync.run()
  const second = await sync.run()
  release()
  await first

  assert.equal(second.state, SYNC_STATES.FAILED)
  assert.match(second.error, /in progress/)
  assert.equal(gate instanceof Promise, true)
})

test('rolls back applied steps through the orchestrator and trims the journal', async () => {
  const { sync, adapter, getJournal } = createHarness({
    pages: [feedPage({
      changeSets: [{
        changeSetId: 1,
        files: [{
          changeId: 'f1', changeSetId: 1, relativePath: 'src/app.js', changeType: 'modify',
          status: 'pending', beforeExisted: true, beforeContent: 'old', afterContent: 'new',
          beforeTruncated: false, afterTruncated: false
        }]
      }]
    })]
  })

  await sync.pickDirectory()
  await sync.run()
  assert.equal(adapter.files.get('src/app.js'), 'new')

  const session = await sync.rollback()
  assert.equal(session.state, SYNC_STATES.IDLE)
  assert.equal(adapter.files.get('src/app.js'), 'old')
  assert.equal(getJournal().entries.length, 0)
})

test('refuses rollback when the local file drifted after the sync, leaving the file untouched', async () => {
  const { sync, adapter } = createHarness({
    pages: [feedPage({
      changeSets: [{
        changeSetId: 1,
        files: [{
          changeId: 'f1', changeSetId: 1, relativePath: 'src/app.js', changeType: 'modify',
          status: 'pending', beforeExisted: true, beforeContent: 'old', afterContent: 'new',
          beforeTruncated: false, afterTruncated: false
        }]
      }]
    })]
  })

  await sync.pickDirectory()
  await sync.run()
  adapter.files.set('src/app.js', 'user edited afterwards')

  const session = await sync.rollback()
  assert.equal(session.state, SYNC_STATES.FAILED)
  assert.match(session.error, /rollback target changed/)
  assert.equal(adapter.files.get('src/app.js'), 'user edited afterwards')
})
