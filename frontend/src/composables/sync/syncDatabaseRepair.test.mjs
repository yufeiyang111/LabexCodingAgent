import assert from 'node:assert/strict'
import test from 'node:test'

import { loadJournal, saveJournal } from './journalStore.js'
import { createJournal } from './syncJournal.js'

// 用户 2026-11-05 的浏览器里已经存在一个"坏"的 version 1 库：
//   早期实现里 directoryHandleStore 先建库，只创建了 directory-handles，
//   之后 journalStore 请求 sync-journals → "One of the specified object stores was not found."
// 光把建库逻辑改对还不够——旧库已经建好了，不会再触发升级。
// 必须能在打开时检测缺失 store 并自动补齐。

/** 模拟一个"已存在但缺 store"的坏库，并支持通过版本提升补齐。 */
function createBrokenDatabase({ existingStores, existingVersion = 1 }) {
  const record = {
    version: existingVersion,
    stores: new Map(existingStores.map(name => [name, new Map()]))
  }
  let upgradeCount = 0

  function makeRequest(value) {
    const request = { result: value, error: null, onsuccess: null, onerror: null }
    queueMicrotask(() => request.onsuccess?.())
    return request
  }

  return {
    record,
    get upgradeCount() { return upgradeCount },
    open(name, version) {
      const openRequest = {
        result: null,
        error: null,
        onupgradeneeded: null,
        onsuccess: null,
        onerror: null
      }
      // 不指定版本（undefined）时浏览器返回现有版本，不触发升级——与真实 API 一致。
      const needsUpgrade = version !== undefined && version > record.version
      const db = {
        // 真实 IDBDatabase 暴露实际版本号；补 store 时据此计算"当前版本 + 1"。
        version: record.version,
        objectStoreNames: {
          contains(storeName) { return record.stores.has(storeName) },
          get length() { return record.stores.size }
        },
        createObjectStore(storeName) {
          record.stores.set(storeName, new Map())
          return {}
        },
        transaction(storeName) {
          if (!record.stores.has(storeName)) {
            throw new Error('One of the specified object stores was not found.')
          }
          const data = record.stores.get(storeName)
          const transaction = {
            objectStore() {
              return {
                get(key) { return makeRequest(data.get(key)) },
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
      queueMicrotask(() => {
        if (needsUpgrade) {
          upgradeCount += 1
          record.version = version
          openRequest.onupgradeneeded?.()
        }
        openRequest.onsuccess?.()
      })
      return openRequest
    }
  }
}

test('an existing database missing the journal store is repaired on open', async () => {
  // 坏库：只有 directory-handles，没有 sync-journals
  const indexedDB = createBrokenDatabase({ existingStores: ['directory-handles'] })

  // 旧实现会在这里抛 "object store was not found"
  await saveJournal('288', createJournal(), { indexedDB })

  const journal = await loadJournal('288', { indexedDB })
  assert.equal(journal.entries.length, 0)
  // 触发了一次升级才补上 store
  assert.equal(indexedDB.upgradeCount, 1)
})

test('the repair preserves data already stored in surviving object stores', async () => {
  const indexedDB = createBrokenDatabase({ existingStores: ['directory-handles'] })
  indexedDB.record.stores.get('directory-handles').set('288', { name: 'keep-me' })

  await saveJournal('288', createJournal(), { indexedDB })

  // 补 store 不得清掉已有数据（版本升级不等于重建数据库）
  assert.deepEqual(indexedDB.record.stores.get('directory-handles').get('288'), { name: 'keep-me' })
})

test('a healthy database is opened without any extra upgrade', async () => {
  const indexedDB = createBrokenDatabase({ existingStores: ['directory-handles', 'sync-journals'] })

  await saveJournal('288', createJournal(), { indexedDB })

  assert.equal(indexedDB.upgradeCount, 0)
})
