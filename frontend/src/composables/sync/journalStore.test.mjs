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
function createFakeIndexedDb(options = {}) {
  const state = new Map()
  const calls = []
  const store = {
    get(key) {
      calls.push(['get', key])
      return createRequest(state.get(key))
    },
    put(value, key) {
      calls.push(['put', key])
      if (options.putError) return createRequest(undefined, options.putError)
      state.set(key, value)
      return createRequest(undefined)
    },
    delete(key) {
      calls.push(['delete', key])
      state.delete(key)
      return createRequest(undefined)
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
    objectStore() { return store },
    oncomplete: null,
    onerror: null,
    onabort: null
  }
  const database = {
    objectStoreNames: { contains() { return true } },
    transaction(name, mode) {
      calls.push(['transaction', name, mode])
      queueMicrotask(() => transaction.oncomplete?.())
      return transaction
    },
    close() { calls.push(['close']) }
  }
  return {
    state,
    calls,
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
