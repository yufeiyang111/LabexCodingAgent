// 唯一职责：把同步 journal 持久化到 IndexedDB（Repository 模式，键 = projectId）。
// 边界：只存取 journal 本身，不读写用户目录、不做同步判定。
// 说明：journal 是回退的唯一依据，因此**不允许静默降级**——
//   save 在介质不可用或事务失败时显式抛错；load 在介质不可用时返回 null，
//   由调用方在写任何文件之前显式失败（见 sync/STATUS.md C06）。
//   与 directoryHandleStore 的区别：句柄丢失可由用户重选目录恢复，journal 丢失
//   等于丢掉回退依据，所以两者错误语义刻意不同。

import { SYNC_DB_NAME, openSyncDatabase } from './syncDatabase.js'

export const JOURNAL_STORE_NAME = 'sync-journals'

function resolveFactory(indexedDbLike) {
  const factory = indexedDbLike ?? globalThis.indexedDB
  if (!factory || typeof factory.open !== 'function') return null
  return factory
}

function requireKey(key) {
  const value = String(key ?? '').trim()
  if (!value) throw new Error('journal store: key is required')
  return value
}

// 建库统一走 syncDatabase：只在这里建 store 会导致"先选目录后写 journal"时
// 数据库版本已固定、sync-journals 永远不存在（用户实际遇到的报错）。
function openDatabase(factory, options) {
  return openSyncDatabase(factory, {
    dbName: options.dbName ?? SYNC_DB_NAME,
    objectStores: options.objectStores
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

/** 写入 journal。介质不可用或事务失败一律抛错——绝不假装存上了。 */
export async function saveJournal(projectKey, journal, options = {}) {
  const key = requireKey(projectKey)
  const factory = resolveFactory(options.indexedDB)
  if (!factory) throw new Error('IndexedDB unavailable: cannot persist sync journal')
  const database = await openDatabase(factory, options)
  try {
    await runTransaction(database, options.storeName ?? JOURNAL_STORE_NAME, 'readwrite',
      store => store.put(journal ?? null, key))
    return true
  } finally {
    database.close?.()
  }
}

/** 读取 journal。介质不可用或键不存在时返回 null。 */
export async function loadJournal(projectKey, options = {}) {
  const key = requireKey(projectKey)
  const factory = resolveFactory(options.indexedDB)
  if (!factory) return null
  const database = await openDatabase(factory, options)
  try {
    const journal = await runTransaction(database, options.storeName ?? JOURNAL_STORE_NAME, 'readonly',
      store => store.get(key))
    return journal ?? null
  } finally {
    database.close?.()
  }
}

export async function clearJournal(projectKey, options = {}) {
  const key = requireKey(projectKey)
  const factory = resolveFactory(options.indexedDB)
  if (!factory) return false
  const database = await openDatabase(factory, options)
  try {
    await runTransaction(database, options.storeName ?? JOURNAL_STORE_NAME, 'readwrite',
      store => store.delete(key))
    return true
  } finally {
    database.close?.()
  }
}

/**
 * 绑定单个项目的 journal 存取器，直接满足 orchestrator 的 `{ load, save }` 契约。
 * orchestrator 在构造期就要求这两个方法存在，因此这里不做任何可选降级。
 */
export function createIndexedDbJournalStore(projectKey, options = {}) {
  const key = requireKey(projectKey)
  return {
    load: () => loadJournal(key, options),
    save: journal => saveJournal(key, journal, options),
    clear: () => clearJournal(key, options)
  }
}
