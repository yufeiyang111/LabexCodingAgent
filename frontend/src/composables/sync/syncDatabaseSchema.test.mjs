import assert from 'node:assert/strict'
import test from 'node:test'

import { loadDirectoryHandle, saveDirectoryHandle } from './directoryHandleStore.js'
import { JOURNAL_STORE_NAME, loadJournal, saveJournal } from './journalStore.js'
import { createJournal } from './syncJournal.js'

// 用户现场（2026-11-05）：
//   Failed to execute 'transaction' on 'IDBDatabase':
//   One of the specified object stores was not found.
//
// 根因：journalStore 与 directoryHandleStore 共用同一个 IndexedDB 数据库（labex-sync）
// 和同一个版本号（1），但各自只在自己的 onupgradeneeded 里创建自己的 object store。
// 浏览器只会对**第一次**建库触发 onupgradeneeded：
//   用户先选目录 → 建库时只创建 directory-handles
//   随后写 journal → 事务请求 sync-journals → 不存在 → 抛错
// 两个 store 必须共享同一套 schema 定义，建库时把所有 object store 一次建齐。

/** 模拟真实 IndexedDB 的 schema 语义：onupgradeneeded 只在版本提升时触发一次。 */
function createSchemaAwareIndexedDb() {
  const databases = new Map()
  let openCount = 0

  function openDatabase(name, version) {
    const existing = databases.get(name)
    const openRequest = {
      result: null,
      error: null,
      onupgradeneeded: null,
      onsuccess: null,
      onerror: null
    }

    const needsUpgrade = !existing || existing.version < version
    if (!existing) {
      databases.set(name, { version, stores: new Map() })
    }
    const record = databases.get(name)

    const db = {
      objectStoreNames: {
        contains(storeName) { return record.stores.has(storeName) },
        get length() { return record.stores.size }
      },
      createObjectStore(storeName) {
        const store = {
          get(key) { return makeRequest(record.stores.get(storeName)?.get(key)) },
          put(value, key) {
            if (!record.stores.has(storeName)) throw new Error('store missing')
            record.stores.get(storeName).set(key, value)
            return makeRequest(undefined)
          },
          delete(key) {
            record.stores.get(storeName)?.delete(key)
            return makeRequest(undefined)
          }
        }
        record.stores.set(storeName, new Map())
        return store
      },
      transaction(storeName, _mode) {
        // 真实浏览器在 store 不存在时立即抛错，这里必须同样失败，
        // 否则测试会掩盖用户看到的那个 bug。
        // journal 的增量布局在**一个事务里**同时写 entries 与 cursors，因此这里支持数组。
        const names = Array.isArray(storeName) ? storeName : [storeName]
        for (const name of names) {
          if (!record.stores.has(name)) {
            throw new Error("One of the specified object stores was not found.")
          }
        }
        const transaction = {
          objectStore(name) {
            const data = record.stores.get(name)
            return {
              get(key) { return makeRequest(data.get(key)) },
              getAll(range) {
                const keys = [...data.keys()].sort()
                const filtered = range ? keys.filter(key => key >= range.lower && key <= range.upper) : keys
                return makeRequest(filtered.map(key => data.get(key)))
              },
              put(value, key) { data.set(key, value); return makeRequest(undefined) },
              delete(key) { data.delete(key); return makeRequest(undefined) }
            }
          },
          oncomplete: null,
          onerror: null,
          onabort: null
        }
        queueMicrotask(() => transaction.oncomplete?.())
        return transaction
      },
      close() {}
    }

    openRequest.result = db
    record.version = Math.max(record.version, version)
    openCount += 1
    queueMicrotask(() => {
      if (needsUpgrade) openRequest.onupgradeneeded?.()
      openRequest.onsuccess?.()
    })
    return openRequest
  }

  function makeRequest(value) {
    const request = { result: value, error: null, onsuccess: null, onerror: null }
    queueMicrotask(() => request.onsuccess?.())
    return request
  }

  return {
    databases,
    get openCount() { return openCount },
    open: openDatabase
  }
}

test('saving a directory handle first does not break journal writes', async () => {
  const indexedDB = createSchemaAwareIndexedDb()

  // 用户先选目录（这时才第一次建库）
  await saveDirectoryHandle('288', { name: 'my-dir' }, { indexedDB })
  // 随后同步时写 journal —— 这是用户实际遇到的失败点
  await saveJournal('288', createJournal(), { indexedDB })

  const journal = await loadJournal('288', { indexedDB })
  assert.equal(journal.entries.length, 0)
})

test('writing a journal first does not break directory handle storage', async () => {
  const indexedDB = createSchemaAwareIndexedDb()

  await saveJournal('288', createJournal(), { indexedDB })
  await saveDirectoryHandle('288', { name: 'my-dir' }, { indexedDB })

  const handle = await loadDirectoryHandle('288', { indexedDB })
  assert.equal(handle.name, 'my-dir')
})

test('both object stores exist after a single upgrade', async () => {
  const indexedDB = createSchemaAwareIndexedDb()

  await saveJournal('288', createJournal(), { indexedDB })
  await saveDirectoryHandle('288', { name: 'my-dir' }, { indexedDB })

  const record = indexedDB.databases.get('labex-sync')
  assert.ok(record.stores.has(JOURNAL_STORE_NAME), 'journal object store must exist')
  assert.ok(record.stores.has('directory-handles'), 'directory handle object store must exist')
})

test('the schema module exposes every object store the sync feature needs', async () => {
  const schema = await import('./syncDatabase.js')
  assert.ok(Array.isArray(schema.SYNC_OBJECT_STORES))
  assert.ok(schema.SYNC_OBJECT_STORES.includes(JOURNAL_STORE_NAME))
  assert.ok(schema.SYNC_OBJECT_STORES.includes('directory-handles'))
})
