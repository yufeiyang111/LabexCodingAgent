import assert from 'node:assert/strict'
import test from 'node:test'

import { SYNC_STATES } from './syncSessionState.js'
import { createMemoryAdapter } from './syncTestDoubles.mjs'
import { useChangeSetSync } from './useChangeSetSync.js'

// 现场证据（2026-11-05 真实截图）：
//   1. 按钮仍显示「选择本地目录」→ 说明目录绑定已丢失；
//   2. 状态却是「已完成」，提示「已同步 0 个文件到本地」；
//   3. 面板显示「待写入 0 · 冲突 0 · 跳过 0」，且逐文件列表为空。
// 三者组合说明：游标已经推进过，所以 feed 返回 0 条；而目录绑定没有持久化，
// 刷新后回到"未选目录"状态。用户看到的是"什么都没发生"。

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
  let storedJournal = options.journal ?? null
  const savedHandles = new Map()
  let pickerCalls = 0

  const sync = useChangeSetSync({
    projectId: () => options.projectId ?? 7,
    api: {
      agentChangeFeed: async () => ({ data: options.page ?? feedPage() })
    },
    journalStore: {
      load: async () => storedJournal,
      save: async journal => { storedJournal = journal }
    },
    adapterFactory: async () => adapter,
    directoryPicker: async () => {
      pickerCalls += 1
      return { name: 'picked-dir' }
    },
    handleStore: {
      load: async key => savedHandles.get(key) ?? null,
      save: async (key, handle) => { savedHandles.set(key, handle) },
      clear: async key => { savedHandles.delete(key) }
    },
    notify: { success() {}, error() {}, warning() {} }
  })

  return { sync, adapter, savedHandles, getPickerCalls: () => pickerCalls, getJournal: () => storedJournal }
}

test('a run without an authorized directory fails instead of reporting a successful sync', async () => {
  const { sync } = createHarness()
  const session = await sync.run()

  // 没有目录 → 必须失败。绝不能显示「已完成 / 已同步 0 个文件」，那会让用户以为同步过了。
  assert.equal(session.state, SYNC_STATES.FAILED)
  assert.match(session.error, /目录/)
  assert.equal(sync.hasDirectory.value, false)
})

test('picking a directory persists the handle so it survives a reload', async () => {
  const { sync, savedHandles } = createHarness()

  assert.equal(await sync.pickDirectory(), true)
  assert.equal(sync.hasDirectory.value, true)
  // 句柄必须落到持久层，否则刷新后用户要重新选目录。
  assert.equal(savedHandles.get('7')?.name, 'picked-dir')
})

test('restoring a persisted handle re-authorizes the directory without a picker call', async () => {
  const adapter = createMemoryAdapter({ 'src/app.js': 'old' })
  let pickerCalls = 0
  const sync = useChangeSetSync({
    projectId: () => 7,
    api: { agentChangeFeed: async () => ({ data: feedPage() }) },
    journalStore: { load: async () => null, save: async () => {} },
    adapterFactory: async () => adapter,
    directoryPicker: async () => { pickerCalls += 1; return { name: 'picked' } },
    handleStore: {
      load: async () => ({ name: 'restored-dir' }),
      save: async () => {},
      clear: async () => {}
    },
    notify: { success() {}, error() {}, warning() {} }
  })

  const restored = await sync.restoreDirectory()

  assert.equal(restored, true)
  assert.equal(sync.hasDirectory.value, true)
  assert.equal(sync.directoryName.value, 'restored-dir')
  assert.equal(pickerCalls, 0, '恢复已存句柄时不应再弹选择器')
})

test('restoreDirectory reports false when nothing was persisted', async () => {
  const { sync } = createHarness()
  assert.equal(await sync.restoreDirectory(), false)
  assert.equal(sync.hasDirectory.value, false)
})

test('a run that finds no pending changes says so explicitly instead of a bare 0', async () => {
  const { sync } = createHarness({
    page: feedPage({
      nextCursor: { afterChangeSetId: 379, tailUpdateTime: '2026-08-28 20:52:51', tailChangeId: '' }
    })
  })
  await sync.pickDirectory()
  const session = await sync.run()

  assert.equal(session.state, SYNC_STATES.DONE)
  assert.equal(session.applied, 0)
  // 用户必须能区分"没有新变更"与"同步失败/没同步"。
  assert.equal(session.summary?.total, 0)
  assert.equal(sync.upToDate.value, true)
})

test('upToDate is false when the run actually applied something', async () => {
  const { sync } = createHarness({
    page: feedPage({
      changeSets: [{
        changeSetId: 380,
        files: [{
          changeId: 'f1', changeSetId: 380, relativePath: 'src/app.js', changeType: 'modify',
          status: 'pending', beforeExisted: true, beforeContent: 'old', afterContent: 'new',
          beforeTruncated: false, afterTruncated: false
        }]
      }],
      nextCursor: { afterChangeSetId: 380, tailUpdateTime: '2026-08-29 10:00:00', tailChangeId: '' }
    })
  })
  await sync.pickDirectory()
  const session = await sync.run()

  assert.equal(session.state, SYNC_STATES.DONE)
  assert.equal(session.applied, 1)
  assert.equal(sync.upToDate.value, false)
})
