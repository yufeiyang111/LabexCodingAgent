// 唯一职责：持久化「本地目录句柄」（Repository 模式，介质 = IndexedDB）。
// 边界：只存取句柄本身，不涉及文件内容、不做同步判定。
// 说明：FileSystemDirectoryHandle 可结构化克隆，能直接存进 IndexedDB；
// 介质不可用时**不静默降级**——save 显式抛错，load 返回 null 交由调用方引导用户重选目录。

const DEFAULT_DB_NAME = 'labex-sync'
const DEFAULT_STORE_NAME = 'directory-handles'
const DEFAULT_DB_VERSION = 1

function resolveFactory(indexedDbLike) {
  const factory = indexedDbLike ?? globalThis.indexedDB
  if (!factory || typeof factory.open !== 'function') return null
  return factory
}

export function isHandlePersistenceAvailable(indexedDbLike) {
  return resolveFactory(indexedDbLike) !== null
}

function requireKey(key) {
  const value = String(key ?? '').trim()
  if (!value) throw new Error('directory handle store: key is required')
  return value
}

function openDatabase(factory, options) {
  const dbName = options.dbName ?? DEFAULT_DB_NAME
  const storeName = options.storeName ?? DEFAULT_STORE_NAME
  return new Promise((resolve, reject) => {
    const request = factory.open(dbName, options.dbVersion ?? DEFAULT_DB_VERSION)
    request.onupgradeneeded = () => {
      const database = request.result
      if (!database.objectStoreNames.contains(storeName)) database.createObjectStore(storeName)
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error ?? new Error('indexedDB open failed'))
  })
}

function isRequestLike(value) {
  return Boolean(value) && typeof value === 'object'
    && ('onsuccess' in value || 'onerror' in value)
    && 'result' in value
}

function runTransaction(database, storeName, mode, executor) {
  return new Promise((resolve, reject) => {
    const transaction = database.transaction(storeName, mode)
    const store = transaction.objectStore(storeName)
    let result
    try {
      result = executor(store)
    } catch (error) {
      reject(error)
      return
    }

    const fail = error => reject(error ?? new Error('indexedDB transaction failed'))
    if (isRequestLike(result)) {
      result.onsuccess = () => resolve(result.result ?? null)
      result.onerror = () => fail(result.error)
    }
    transaction.oncomplete = () => {
      if (!isRequestLike(result)) resolve(result ?? null)
    }
    transaction.onerror = () => fail(transaction.error ?? new Error('indexedDB transaction failed'))
    transaction.onabort = () => fail(transaction.error ?? new Error('indexedDB transaction aborted'))
  })
}

export async function saveDirectoryHandle(key, handle, options = {}) {
  const name = requireKey(key)
  const factory = resolveFactory(options.indexedDB)
  if (!factory) throw new Error('IndexedDB unavailable: cannot persist directory handle')
  const database = await openDatabase(factory, options)
  try {
    await runTransaction(database, options.storeName ?? DEFAULT_STORE_NAME, 'readwrite', store => store.put(handle, name))
    return true
  } finally {
    database.close?.()
  }
}

export async function loadDirectoryHandle(key, options = {}) {
  const name = requireKey(key)
  const factory = resolveFactory(options.indexedDB)
  if (!factory) return null
  const database = await openDatabase(factory, options)
  try {
    const handle = await runTransaction(database, options.storeName ?? DEFAULT_STORE_NAME, 'readonly', store => store.get(name))
    return handle ?? null
  } finally {
    database.close?.()
  }
}

export async function clearDirectoryHandle(key, options = {}) {
  const name = requireKey(key)
  const factory = resolveFactory(options.indexedDB)
  if (!factory) return false
  const database = await openDatabase(factory, options)
  try {
    await runTransaction(database, options.storeName ?? DEFAULT_STORE_NAME, 'readwrite', store => store.delete(name))
    return true
  } finally {
    database.close?.()
  }
}
