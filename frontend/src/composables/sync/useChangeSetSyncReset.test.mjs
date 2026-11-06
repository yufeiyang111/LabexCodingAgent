import assert from 'node:assert/strict'
import test from 'node:test'

import { SYNC_STATES } from './syncSessionState.js'
import { createMemoryAdapter } from './syncTestDoubles.mjs'
import { useChangeSetSync } from './useChangeSetSync.js'

// 用户现场（2026-11-05）：同步过一次 → 把本地目录里的文件全删了 → 再点同步 →
// 提示「已同步 0 个文件」，本地依然是空的。
//
// 根因：游标是"服务端变更已处理到哪"，不是"本地磁盘上有哪些文件"。
// 用户删文件不会回退游标，所以 feed 永远返回空，同步变成空转。
// 这不是文案问题，而是缺少"重置进度"这条恢复路径。

function feedPage(overrides = {}) {
  return {
    changeSets: [],
    changedFiles: [],
    nextCursor: { afterChangeSetId: 379, tailUpdateTime: '2026-08-28 20:52:51', tailChangeId: '' },
    hasMore: false,
    hasMoreChangeSets: false,
    hasMoreChangedFiles: false,
    ...overrides
  }
}

function createHarness(options = {}) {
  const adapter = options.adapter ?? createMemoryAdapter({})
  // 用 hasOwnProperty 区分"显式传 null"与"没传"，否则 journal: null 会被默认值覆盖。
  let storedJournal = Object.hasOwn(options, 'journal')
    ? options.journal
    : {
      version: 1,
      entries: [],
      cursor: 379,
      changeSetCursor: { afterChangeSetId: 379, tailUpdateTime: '2026-08-28 20:52:51', tailChangeId: '' }
    }
  const handles = new Map([['288', { name: 'cleared-dir' }]])
  const pages = options.pages ?? [feedPage()]

  const sync = useChangeSetSync({
    projectId: () => 288,
    api: {
      agentChangeFeed: async () => ({ data: pages.length > 1 ? pages.shift() : pages[0] })
    },
    journalStore: {
      load: async () => storedJournal,
      save: async journal => { storedJournal = journal },
      clear: async () => { storedJournal = null }
    },
    adapterFactory: async () => adapter,
    directoryPicker: async () => ({ name: 'picked' }),
    handleStore: {
      load: async key => handles.get(key) ?? null,
      save: async (key, handle) => { handles.set(key, handle) },
      clear: async key => { handles.delete(key) }
    },
    notify: { success() {}, error() {}, warning() {} }
  })

  return { sync, adapter, getJournal: () => storedJournal, handles }
}

test('after the local directory is wiped, a plain run still reports up-to-date with zero writes', async () => {
  const { sync, adapter } = createHarness()
  await sync.restoreDirectory()
  const session = await sync.run()

  // 这是用户看到的现象本身：游标停在 379，feed 返回空，什么都没写。
  assert.equal(session.state, SYNC_STATES.DONE)
  assert.equal(session.applied, 0)
  assert.equal(adapter.writes.length, 0)
  assert.equal(sync.upToDate.value, true)
})

test('resetProgress clears the cursor so the next run re-downloads everything', async () => {
  const { sync, getJournal } = createHarness({
    pages: [
      // 第一次：游标已在 379，feed 空
      feedPage(),
      // 重置后：feed 从 0 开始，重新下发全部 45 条
      feedPage({
        changeSets: [{
          changeSetId: 379,
          files: [{
            changeId: 'f1', changeSetId: 379, relativePath: 'AGENTS.md', changeType: 'create',
            status: 'applied', beforeExisted: false, afterExisted: true,
            beforeTruncated: false, afterTruncated: false,
            beforeContent: null, afterContent: '# agents'
          }]
        }],
        nextCursor: { afterChangeSetId: 379, tailUpdateTime: '2026-08-28 20:52:51', tailChangeId: '' }
      })
    ]
  })

  await sync.restoreDirectory()
  await sync.run()
  assert.equal(getJournal().cursor, 379)

  const cleared = await sync.resetProgress()
  assert.equal(cleared, true)
  // journal 被清空 → 下次 run 从游标 0 开始
  assert.equal(getJournal(), null)

  const session = await sync.run()
  assert.equal(session.applied, 1)
})

test('resetProgress keeps the directory binding so the user does not have to re-pick', async () => {
  const { sync, handles } = createHarness()
  await sync.restoreDirectory()

  await sync.resetProgress()

  // 只重置同步进度，不动目录授权：用户删的是文件，不是取消授权。
  assert.equal(handles.get('288')?.name, 'cleared-dir')
  assert.equal(sync.hasDirectory.value, true)
})

test('resetProgress reports false when there is no journal to clear', async () => {
  const { sync } = createHarness({ journal: null })
  assert.equal(await sync.resetProgress(), false)
})
