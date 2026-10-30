import assert from 'node:assert/strict'
import test from 'node:test'

import {
  clearDirectoryHandle,
  isHandlePersistenceAvailable,
  loadDirectoryHandle,
  saveDirectoryHandle
} from './directoryHandleStore.js'

const UNAVAILABLE = {}

test('detects IndexedDB availability', () => {
  assert.equal(isHandlePersistenceAvailable(), false)
  assert.equal(isHandlePersistenceAvailable(UNAVAILABLE), false)
})

test('fails loudly when saving without a usable backend', async () => {
  await assert.rejects(
    () => saveDirectoryHandle('project-1', { name: 'root' }, { indexedDB: UNAVAILABLE }),
    /IndexedDB unavailable/
  )
})

test('returns null when loading without a usable backend', async () => {
  assert.equal(await loadDirectoryHandle('project-1', { indexedDB: UNAVAILABLE }), null)
})

test('reports clear as no-op without a usable backend', async () => {
  assert.equal(await clearDirectoryHandle('project-1', { indexedDB: UNAVAILABLE }), false)
})

test('requires a persistence key', async () => {
  await assert.rejects(() => saveDirectoryHandle('', {}, { indexedDB: UNAVAILABLE }), /key is required/)
  await assert.rejects(() => loadDirectoryHandle('   ', { indexedDB: UNAVAILABLE }), /key is required/)
})

// 说明：IndexedDB 的真实读写路径无法在 Node 环境验证（无 indexedDB 实现），
// 属"需浏览器 live 验证"范围，不得以本文件通过宣称该路径已验证。
// C10：fake IndexedDB 只验证事务协议，不冒充真实浏览器持久化。
function createFakeIndexedDb({ value, requestError = null, transactionError = null } = {}) {
  let lastRequest = null
  const createRequest = requestValue => {
    lastRequest = { result: requestValue, error: null, onsuccess: null, onerror: null }
    return lastRequest
  }
  const store = {
    get() { return createRequest(value) },
    put() { return createRequest(undefined) },
    delete() { return createRequest(undefined) }
  }
  const transaction = {
    objectStore() { return store },
    oncomplete: null,
    onerror: null,
    onabort: null
  }
  const database = {
    objectStoreNames: { contains() { return true } },
    transaction() {
      queueMicrotask(() => {
        if (requestError) {
          lastRequest.error = requestError
          lastRequest.onerror?.()
          return
        }
        if (transactionError) {
          transaction.error = transactionError
          transaction.onerror?.()
          return
        }
        lastRequest.onsuccess?.()
        transaction.oncomplete?.()
      })
      return transaction
    },
    close() {}
  }
  const openRequest = {
    result: database,
    onupgradeneeded: null,
    onsuccess: null,
    onerror: null
  }
  queueMicrotask(() => openRequest.onsuccess?.())
  return {
    open() { return openRequest },
    createObjectStore() {}
  }
}

test('load returns null for a missing IndexedDB key instead of the request object', async () => {
  const indexedDB = createFakeIndexedDb({ value: undefined })
  const result = await loadDirectoryHandle('project-1', { indexedDB })
  assert.equal(result, null)
})

test('load returns the stored handle when the IndexedDB key exists', async () => {
  const handle = { name: 'root' }
  const indexedDB = createFakeIndexedDb({ value: handle })
  const result = await loadDirectoryHandle('project-1', { indexedDB })
  assert.equal(result, handle)
})

test('rejects when the IndexedDB request fails', async () => {
  const indexedDB = createFakeIndexedDb({ value: undefined, requestError: new Error('request failed') })
  await assert.rejects(() => loadDirectoryHandle('project-1', { indexedDB }), /request failed/)
})

test('documents that browser-level persistence requires live verification', () => {
  assert.equal(typeof globalThis.indexedDB, 'undefined')
})
