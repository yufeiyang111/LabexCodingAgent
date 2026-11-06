import assert from 'node:assert/strict'
import test from 'node:test'

import { SYNC_STATES } from './syncSessionState.js'
import { createMemoryAdapter } from './syncTestDoubles.mjs'
import { useChangeSetSync } from './useChangeSetSync.js'

// 用户现场（2026-11-05）：
//   Failed to execute 'showDirectoryPicker' on 'Window': The user aborted a request.
// 这是用户**点取消**，属于正常操作，却显示成红色报错，看起来像功能坏了。

function feedPage() {
  return {
    changeSets: [],
    changedFiles: [],
    nextCursor: { afterChangeSetId: 0, tailUpdateTime: null, tailChangeId: '' },
    hasMore: false,
    hasMoreChangeSets: false,
    hasMoreChangedFiles: false
  }
}

function createHarness(picker) {
  const handles = new Map()
  const sync = useChangeSetSync({
    projectId: () => 7,
    api: { agentChangeFeed: async () => ({ data: feedPage() }) },
    journalStore: { load: async () => null, save: async () => {}, clear: async () => {} },
    adapterFactory: async () => createMemoryAdapter(),
    directoryPicker: picker,
    handleStore: {
      load: async key => handles.get(key) ?? null,
      save: async (key, handle) => { handles.set(key, handle) },
      clear: async key => { handles.delete(key) }
    },
    notify: { success() {}, error() {}, warning() {} }
  })
  return { sync, handles }
}

function abortError() {
  const error = new Error("Failed to execute 'showDirectoryPicker' on 'Window': The user aborted a request.")
  error.name = 'AbortError'
  return error
}

test('cancelling the directory picker is not surfaced as an error', async () => {
  const { sync } = createHarness(async () => { throw abortError() })

  assert.equal(await sync.pickDirectory(), false)
  // 用户主动取消 → 不应出现红色报错
  assert.equal(sync.session.value.error, null)
})

test('cancelling after a directory was already bound keeps the binding', async () => {
  let callCount = 0
  const { sync, handles } = createHarness(async () => {
    callCount += 1
    if (callCount === 1) return { name: 'good-dir' }
    throw abortError()
  })

  await sync.pickDirectory()
  assert.equal(sync.hasDirectory.value, true)
  assert.equal(sync.directoryName.value, 'good-dir')

  // 第二次点"重新选择"又取消：原绑定必须保留，不能因为取消就丢掉。
  assert.equal(await sync.pickDirectory(), false)
  assert.equal(sync.hasDirectory.value, true)
  assert.equal(sync.directoryName.value, 'good-dir')
  assert.equal(handles.get('7')?.name, 'good-dir')
  assert.equal(sync.session.value.error, null)
})

test('a genuine picker failure is still reported', async () => {
  const { sync } = createHarness(async () => {
    throw new Error('SecurityError: cross-origin sub-frames cannot show a directory picker')
  })

  assert.equal(await sync.pickDirectory(), false)
  assert.match(sync.session.value.error, /SecurityError/)
})

test('a cancelled pick does not put the session into a failed state', async () => {
  const { sync } = createHarness(async () => { throw abortError() })

  await sync.pickDirectory()

  assert.notEqual(sync.session.value.state, SYNC_STATES.FAILED)
})
