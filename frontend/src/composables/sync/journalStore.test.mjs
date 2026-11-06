import assert from 'node:assert/strict'
import test from 'node:test'

import { createJournal, recordApplied, updateJournalCursor } from './syncJournal.js'
import {
  JOURNAL_STORE_NAME,
  clearJournal,
  createIndexedDbJournalStore,
  loadJournal,
  saveJournal
} from './journalStore.js'

const UNAVAILABLE = {}

function journalWithOneEntry() {
  const journal = recordApplied(createJournal(), {
    changeId: 'c1',
    changeSetId: 3,
    path: 'src/app.js',
    op: 'write',
    content: 'new',
    previousContent: 'old',
    previousExisted: true
  })
  return updateJournalCursor(journal, {
    afterChangeSetId: 3,
    tailUpdateTime: '2026-10-06T10:00:00',
    tailChangeId: 'tail-1'
  })
}

// 说明：IndexedDB 的真实持久化只能在浏览器里验证（Node 无实现）。
// 这里用 fake 只验证「读写协议 + 错误语义」，不冒充浏览器路径已验证。
//
// fake 必须实现真实 IndexedDB 用到的全部方法：journal 增量存储依赖
// getAll(range) 按键区间取某项目的条目，缺了它测试就会在替身上失败而不是在真实现上。
function createFakeIndexedDb(options = {}) {
  // 每个 store 一份独立状态：entries 与 cursors 是两个不同的 store。
  const stores = new Map()
  const calls = []
  function stateOf(name) {
    if (!stores.has(name)) stores.set(name, new Map())
    return stores.get(name)
  }
  function sortedKeys(map, range) {
    const keys = [...map.keys()].sort()
    if (!range) return keys
    return keys.filter(key => key >= range.lower && key <= range.upper)
  }
  function makeStore(name) {
    return {
      get(key) {
        calls.push(['get', name, key])
        return createRequest(stateOf(name).get(key))
      },
      getAll(range) {
        calls.push(['getAll', name])
        const map = stateOf(name)
        return createRequest(sortedKeys(map, range).map(key => map.get(key)))
      },
      put(value, key) {
        calls.push(['put', name, key])
        if (options.putError) return createRequest(undefined, options.putError)
        stateOf(name).set(key, value)
        return createRequest(undefined)
      },
      delete(key) {
        calls.push(['delete', name, key])
        stateOf(name).delete(key)
        return createRequest(undefined)
      }
    }
  }
  function createRequest(result, error = null) {
    const request = { result, error, onsuccess: null, onerror: null }
    queueMicrotask(() => {
      if (error) {
        request.onerror?.()
        return
      }
      request.onsuccess?.()
    })
    return request
  }
  const transaction = {
    objectStore(name) { return makeStore(name) },
    oncomplete: null,
    onerror: null,
    onabort: null
  }
  const database = {
    // 库 schema 里始终有全部 store（与 openSyncDatabase 的建库行为一致）；
    // 旧格式 journal 的"是否存在"由 get 的结果决定，不靠 schema 判断。
    objectStoreNames: { contains() { return true } },
    transaction(name, mode) {
      calls.push(['transaction', name, mode])
      queueMicrotask(() => transaction.oncomplete?.())
      return transaction
    },
    close() { calls.push(['close']) }
  }
  return {
    stores,
    calls,
    /** 直接读某个 store 的内容（测试断言用）。 */
    dump(name) { return stateOf(name) },
    // 与真实 IndexedDB 一致：每次 open() 返回一个新的请求对象，各自异步成功。
    open() {
      const openRequest = {
        result: database,
        onupgradeneeded: null,
        onsuccess: null,
        onerror: null
      }
      queueMicrotask(() => openRequest.onsuccess?.())
      return openRequest
    },
    createObjectStore() {}
  }
}

test('saving fails loudly when IndexedDB is unavailable instead of silently dropping the journal', async () => {
  await assert.rejects(
    () => saveJournal('project-1', journalWithOneEntry(), { indexedDB: UNAVAILABLE }),
    /IndexedDB unavailable/
  )
})

test('loading returns null when IndexedDB is unavailable, so the caller must re-authorize the directory', async () => {
  assert.equal(await loadJournal('project-1', { indexedDB: UNAVAILABLE }), null)
})

test('a journal round-trips through the store with both progress lines intact', async () => {
  const indexedDB = createFakeIndexedDb()
  const journal = journalWithOneEntry()

  await saveJournal('project-1', journal, { indexedDB })
  const loaded = await loadJournal('project-1', { indexedDB })

  assert.deepEqual(loaded, journal)
  assert.equal(loaded.changeSetCursor.afterChangeSetId, 3)
  assert.equal(loaded.changeSetCursor.tailUpdateTime, '2026-10-06T10:00:00')
  assert.equal(loaded.changeSetCursor.tailChangeId, 'tail-1')
})

test('journals are scoped per project and do not leak across projects', async () => {
  const indexedDB = createFakeIndexedDb()
  await saveJournal('project-1', journalWithOneEntry(), { indexedDB })
  await saveJournal('project-2', createJournal(), { indexedDB })

  assert.equal((await loadJournal('project-1', { indexedDB })).entries.length, 1)
  assert.equal((await loadJournal('project-2', { indexedDB })).entries.length, 0)
})

test('clearing removes only the requested project journal', async () => {
  const indexedDB = createFakeIndexedDb()
  await saveJournal('project-1', journalWithOneEntry(), { indexedDB })
  await saveJournal('project-2', journalWithOneEntry(), { indexedDB })

  assert.equal(await clearJournal('project-1', { indexedDB }), true)
  assert.equal(await loadJournal('project-1', { indexedDB }), null)
  assert.equal((await loadJournal('project-2', { indexedDB })).entries.length, 1)
})

test('a failed IndexedDB write rejects instead of reporting success', async () => {
  const indexedDB = createFakeIndexedDb({ putError: new Error('quota exceeded') })
  await assert.rejects(() => saveJournal('project-1', journalWithOneEntry(), { indexedDB }), /quota exceeded/)
})

test('requires a project key for every operation', async () => {
  const indexedDB = createFakeIndexedDb()
  await assert.rejects(() => saveJournal('', journalWithOneEntry(), { indexedDB }), /key is required/)
  await assert.rejects(() => loadJournal('  ', { indexedDB }), /key is required/)
  await assert.rejects(() => clearJournal('', { indexedDB }), /key is required/)
})

test('the composable store exposes the orchestrator journalStore contract', async () => {
  const indexedDB = createFakeIndexedDb()
  const store = createIndexedDbJournalStore('project-1', { indexedDB })

  assert.equal(await store.load(), null)
  await store.save(journalWithOneEntry())
  const loaded = await store.load()
  assert.equal(loaded.entries.length, 1)
  assert.equal(loaded.changeSetCursor.tailChangeId, 'tail-1')
})

test('the journal object store name is stable so upgrades do not orphan data', () => {
  assert.equal(JOURNAL_STORE_NAME, 'sync-journals')
})
