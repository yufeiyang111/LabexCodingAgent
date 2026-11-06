import assert from 'node:assert/strict'
import test from 'node:test'

import { loadJournal, saveJournal } from './journalStore.js'
import { SYNC_OBJECT_STORES } from './syncDatabase.js'
import { createJournal } from './syncJournal.js'

// 用户现场（2026-11-05，第二轮）：
//   The requested version (1) is less than the existing version (2).
//
// 前一次自愈把库升到了 version 2（补 store），但 openSyncDatabase 的返回值里
// 记录的是"我请求的版本"而不是"数据库实际版本"。于是下一次打开仍按 version 1 请求，
// IndexedDB 直接拒绝降级 —— 整个同步在扫描阶段失败。
//
// 契约：打开数据库后必须按**实际版本**工作；任何后续打开都不得请求低于实际版本的版本号。

/** 严格模拟真实 IndexedDB：拒绝打开低于现有版本的库。 */
function createVersionedIndexedDb(initialVersion = 1, initialStores = ['directory-handles']) {
  const record = {
    version: initialVersion,
    stores: new Map(initialStores.map(name => [name, new Map()]))
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

      // 真实浏览器行为：请求的版本低于现有版本 → 失败，不触发 upgrade。
      if (version !== undefined && version < record.version) {
        const error = new Error(`The requested version (${version}) is less than the existing version (${record.version}).`)
        error.name = 'VersionError'
        queueMicrotask(() => {
          openRequest.error = error
          openRequest.onerror?.()
        })
        return openRequest
      }

      const needsUpgrade = version !== undefined && version > record.version
      const db = {
        version: Math.max(record.version, version ?? record.version),
        objectStoreNames: {
          contains(storeName) { return record.stores.has(storeName) },
          get length() { return record.stores.size }
        },
        createObjectStore(storeName) {
          record.stores.set(storeName, new Map())
          return {}
        },
        transaction(storeName) {
          // journal 的增量布局在**一个事务里**同时写 entries 与 cursors，因此这里支持数组。
          const names = Array.isArray(storeName) ? storeName : [storeName]
          for (const name of names) {
            if (!record.stores.has(name)) {
              throw new Error('One of the specified object stores was not found.')
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

test('after a repair upgraded the database, the next open does not request the old version', async () => {
  // 坏库：version 1 且缺 sync-journals → 第一次打开会触发自愈升到 2
  const indexedDB = createVersionedIndexedDb(1, ['directory-handles'])

  await saveJournal('288', createJournal(), { indexedDB })
  assert.equal(indexedDB.record.version, 2, '自愈应把版本提升到 2')

  // 第二次打开：旧实现会按 version 1 请求 → VersionError
  const journal = await loadJournal('288', { indexedDB })
  assert.equal(journal.entries.length, 0)
})

test('repeated opens keep working after the database version has advanced', async () => {
  const indexedDB = createVersionedIndexedDb(1, ['directory-handles'])

  for (let index = 0; index < 5; index += 1) {
    await saveJournal('288', createJournal(), { indexedDB })
    const journal = await loadJournal('288', { indexedDB })
    assert.equal(journal.entries.length, 0)
  }
})

test('a database already ahead of the declared version opens without downgrading', async () => {
  // 用户浏览器里可能已经是被自愈升过版本的库
  const indexedDB = createVersionedIndexedDb(3, [...SYNC_OBJECT_STORES])

  const journal = await loadJournal('288', { indexedDB })
  assert.equal(journal, null)
  assert.equal(indexedDB.record.version, 3, '不得把已有版本降下来')
})
