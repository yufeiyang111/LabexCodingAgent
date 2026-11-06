import assert from 'node:assert/strict'
import test from 'node:test'

import { SYNC_STATES } from './syncSessionState.js'
import { createMemoryAdapter } from './syncTestDoubles.mjs'
import { useChangeSetSync } from './useChangeSetSync.js'

// 用户现场（2026-11-05）：**没有点任何取消按钮**，却看到
//   Failed to execute 'showDirectoryPicker' on 'Window': The user aborted a request.
//
// 根因：目录选择入口有两条（面板的"选择本地目录"按钮、以及首次点"同步"时的自动弹出），
// 两条入口都没有防重入。用户快速点两下 → 同时发起两次 showDirectoryPicker →
// 浏览器只允许一个选择器，第二个立即以 AbortError 失败 → 被当成"用户取消"或错误显示。
//
// 正确行为：同一时刻只允许一个选择器在飞，第二次点击复用同一个 Promise。

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

test('two rapid picker calls share one dialog instead of opening two', async () => {
  let pickerCalls = 0
  let release
  const gate = new Promise(resolve => { release = resolve })

  const { sync } = createHarness(async () => {
    pickerCalls += 1
    await gate
    return { name: 'picked-dir' }
  })

  // 用户快速点两下
  const first = sync.pickDirectory()
  const second = sync.pickDirectory()
  // 两个调用都可能走到 await gate，因此用 resolve 而非单次 resolve 语义：
  // 同一个 Promise 被多个 await 等待时，一次 resolve 即可全部放行。
  release()
  const [a, b] = await Promise.all([first, second])

  // 只应真正弹一次选择器；第二次复用同一个结果，而不是触发 AbortError。
  assert.equal(pickerCalls, 1, `选择器应只弹一次，实际 ${pickerCalls} 次`)
  assert.equal(a, true)
  assert.equal(b, true)
  assert.equal(sync.hasDirectory.value, true)
  assert.equal(sync.session.value.error, null)
})

test('a concurrent pick while the picker is open does not produce an abort error', async () => {
  let pickerCalls = 0
  let release
  const gate = new Promise(resolve => { release = resolve })

  const { sync } = createHarness(async () => {
    pickerCalls += 1
    await gate
    return { name: 'dir' }
  })

  const pending = sync.pickDirectory()
  // 第二次调用必须复用同一个 Promise，而不是另起一个选择器。
  const second = sync.pickDirectory()
  release()
  await Promise.all([pending, second])

  assert.equal(pickerCalls, 1)
  assert.equal(sync.session.value.error, null)
})

test('the picker can be used again after a completed pick', async () => {
  let pickerCalls = 0
  const { sync } = createHarness(async () => {
    pickerCalls += 1
    return { name: `dir-${pickerCalls}` }
  })

  await sync.pickDirectory()
  await sync.pickDirectory()

  // 串行调用是合法的（用户想换目录），必须能再次弹出。
  assert.equal(pickerCalls, 2)
  assert.equal(sync.directoryName.value, 'dir-2')
})

test('a failed pick releases the guard so the user can retry', async () => {
  let pickerCalls = 0
  const { sync } = createHarness(async () => {
    pickerCalls += 1
    if (pickerCalls === 1) throw new Error('SecurityError: blocked')
    return { name: 'ok' }
  })

  assert.equal(await sync.pickDirectory(), false)
  // 失败后必须能重试，不能被"进行中"状态永久卡住
  assert.equal(await sync.pickDirectory(), true)
  assert.equal(pickerCalls, 2)
})

test('a run started while the picker is open does not double-open it', async () => {
  let pickerCalls = 0
  let release
  const gate = new Promise(resolve => { release = resolve })
  const { sync } = createHarness(async () => {
    pickerCalls += 1
    await gate
    return { name: 'dir' }
  })

  const picking = sync.pickDirectory()
  // run() 在无目录时直接失败（不等待选择器），这是有意为之：
  // 同步不能替用户做授权决定，UI 负责先选目录再同步。
  const running = sync.run()
  release()
  const [picked, session] = await Promise.all([picking, running])

  assert.equal(pickerCalls, 1, `两条入口不得各自弹一次，实际 ${pickerCalls} 次`)
  assert.equal(picked, true)
  assert.equal(session.state, SYNC_STATES.FAILED)
  assert.match(session.error, /目录/)
})

test('picking does not flip the session into a failed state while in flight', async () => {
  let release
  const gate = new Promise(resolve => { release = resolve })
  const { sync } = createHarness(async () => { await gate; return { name: 'd' } })

  const pending = sync.pickDirectory()
  assert.notEqual(sync.session.value.state, SYNC_STATES.FAILED)
  release()
  await pending
  assert.notEqual(sync.session.value.state, SYNC_STATES.FAILED)
})
