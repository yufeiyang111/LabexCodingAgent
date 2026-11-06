// 唯一职责：把同步 journal 持久化到 IndexedDB（Repository 模式，键 = projectId）。
// 边界：只存取 journal 本身，不读写用户目录、不做同步判定。
// 说明：journal 是回退的唯一依据，因此**不允许静默降级**——
//   save 在介质不可用或事务失败时显式抛错；load 在介质不可用时返回 null，
//   由调用方在写任何文件之前显式失败（见 sync/STATUS.md C06）。
//   与 directoryHandleStore 的区别：句柄丢失可由用户重选目录恢复，journal 丢失
//   等于丢掉回退依据，所以两者错误语义刻意不同。
//
// 存储布局（2026-11-06 起）：**按条目增量写**。
//   旧实现每次提交都把整个 journal 序列化后整条 put，写入量是 O(n²)：
//   实测 500 个 4KB 文件（内容 2MB）累计写 66.4MB，放大了 33 倍。
//   现在每条 entry 一条记录、游标单独一条，一次提交只写新增的那几条：
//     - sync-journal-entries：key = `${projectKey}\u0000${seq 补零}`，value = entry
//     - sync-journal-cursors：key = projectKey，value = 游标 + entryCount
//   两条记录在**同一个事务**里写，避免崩溃后留下"有 entry 没游标"或反之。

import { SYNC_DB_NAME, openSyncDatabase } from './syncDatabase.js'

/** 旧格式（整对象）的 store 名，只用于迁移读取，新写入不再使用。 */
export const JOURNAL_STORE_NAME = 'sync-journals'
export const JOURNAL_ENTRY_STORE = 'sync-journal-entries'
export const JOURNAL_CURSOR_STORE = 'sync-journal-cursors'

const SEQ_DIGITS = 10
const SEPARATOR = '\u0000'

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

function entryKey(projectKey, seq) {
  return `${projectKey}${SEPARATOR}${String(seq).padStart(SEQ_DIGITS, '0')}`
}

// 建库统一走 syncDatabase：只在这里建 store 会导致"先选目录后写 journal"时
// 数据库版本已固定、sync-journals 永远不存在（用户实际遇到的报错）。
function openDatabase(factory, options) {
  return openSyncDatabase(factory, {
    dbName: options.dbName ?? SYNC_DB_NAME,
    objectStores: options.objectStores
  })
}

/** 某项目全部条目的键区间：前缀 + 分隔符 到 前缀 + 分隔符 + 最大字符。 */
function entryRange(projectKey) {
  const prefix = `${projectKey}${SEPARATOR}`
  const factory = globalThis.IDBKeyRange
  if (!factory || typeof factory.bound !== 'function') return null
  return factory.bound(prefix, `${prefix}\uffff`)
}

function isRequestLike(value) {
  return Boolean(value) && typeof value === 'object'
    && ('onsuccess' in value || 'onerror' in value)
    && 'result' in value
}

/**
 * 在**一个**事务里跑一组请求，全部成功才算成功。
 * 任意一个失败都 reject——部分成功等于数据不一致。
 */
function runTransaction(database, storeNames, mode, executor) {
  const names = Array.isArray(storeNames) ? storeNames : [storeNames]
  return new Promise((resolve, reject) => {
    const transaction = database.transaction(names, mode)
    const stores = {}
    for (const name of names) stores[name] = transaction.objectStore(name)

    let outcome
    try {
      outcome = executor(stores)
    } catch (error) {
      reject(error)
      return
    }

    const requests = (Array.isArray(outcome) ? outcome : [outcome]).filter(isRequestLike)
    let settled = 0
    let first = null
    for (const request of requests) {
      request.onsuccess = () => {
        settled += 1
        if (first === null) first = request.result ?? null
        if (settled === requests.length) resolve(first)
      }
      request.onerror = () => reject(request.error ?? new Error('indexedDB request failed'))
    }
    if (requests.length === 0) {
      transaction.oncomplete = () => resolve(outcome ?? null)
    }
    transaction.onerror = () => reject(transaction.error ?? new Error('indexedDB transaction failed'))
    transaction.onabort = () => reject(transaction.error ?? new Error('indexedDB transaction aborted'))
  })
}

async function readCursorRecord(database, projectKey) {
  return runTransaction(database, JOURNAL_CURSOR_STORE, 'readonly',
    stores => stores[JOURNAL_CURSOR_STORE].get(projectKey))
}

/**
 * 读回某项目的全部条目（按 seq 升序）。
 * 优先用键区间；没有 IDBKeyRange 的环境退回 getAll + 按前缀过滤。
 */
async function readEntryRecords(database, projectKey) {
  const range = entryRange(projectKey)
  const all = await runTransaction(database, JOURNAL_ENTRY_STORE, 'readonly', stores => {
    const store = stores[JOURNAL_ENTRY_STORE]
    return range ? store.getAll(range) : store.getAll()
  })
  if (!Array.isArray(all)) return []
  const prefix = `${projectKey}${SEPARATOR}`
  return all
    .filter(record => typeof record?.seq === 'number' && record.projectKey === projectKey)
    .filter(record => !range || entryKey(projectKey, record.seq).startsWith(prefix))
    .sort((left, right) => left.seq - right.seq)
}

function stripRecord(record) {
  if (!record || typeof record !== 'object') return record
  const { projectKey, seq, ...entry } = record
  return entry
}

/** 在已打开的库上写 journal（内部用，便于迁移复用同一连接）。 */
async function saveJournalOn(database, projectKey, journal) {
  const entries = Array.isArray(journal?.entries) ? journal.entries : []
  const previous = await readCursorRecord(database, projectKey)
  const storedCount = Number(previous?.entryCount ?? 0) || 0
  const changeSetCursor = journal?.changeSetCursor ?? {
    afterChangeSetId: Number(journal?.cursor ?? 0) || 0,
    tailUpdateTime: null,
    tailChangeId: ''
  }

  const cursorRecord = {
    afterChangeSetId: Number(changeSetCursor.afterChangeSetId ?? 0) || 0,
    tailUpdateTime: changeSetCursor.tailUpdateTime ?? null,
    tailChangeId: changeSetCursor.tailChangeId ?? '',
    entryCount: entries.length,
    updatedAt: Number(journal?.updatedAt ?? 0) || 0
  }

  await runTransaction(database, [JOURNAL_ENTRY_STORE, JOURNAL_CURSOR_STORE], 'readwrite', stores => {
    const entryStore = stores[JOURNAL_ENTRY_STORE]
    const cursorStore = stores[JOURNAL_CURSOR_STORE]
    const requests = []
    // 只写新增的部分：已有条目内容不可变（应用记录只会追加）。
    for (let seq = storedCount; seq < entries.length; seq += 1) {
      const record = { ...entries[seq], projectKey, seq }
      requests.push(entryStore.put(record, entryKey(projectKey, seq)))
    }
    // 回退后条目变少：删掉尾部，否则下次 load 会把已撤销的条目读回来。
    for (let seq = entries.length; seq < storedCount; seq += 1) {
      requests.push(entryStore.delete(entryKey(projectKey, seq)))
    }
    requests.push(cursorStore.put(cursorRecord, projectKey))
    return requests
  })
}

/** 写入 journal。介质不可用或事务失败一律抛错——绝不假装存上了。 */
export async function saveJournal(projectKey, journal, options = {}) {
  const key = requireKey(projectKey)
  const factory = resolveFactory(options.indexedDB)
  if (!factory) throw new Error('IndexedDB unavailable: cannot persist sync journal')
  const database = await openDatabase(factory, options)
  try {
    await saveJournalOn(database, key, journal)
    return true
  } finally {
    database.close?.()
  }
}

/**
 * 读取 journal。介质不可用或键不存在时返回 null。
 * 兼容旧格式：新布局没有游标记录时，尝试把旧整对象迁移过来（一次性）。
 */
export async function loadJournal(projectKey, options = {}) {
  const key = requireKey(projectKey)
  const factory = resolveFactory(options.indexedDB)
  if (!factory) return null
  const database = await openDatabase(factory, options)
  try {
    let cursor = await readCursorRecord(database, key)
    if (!cursor) {
      const legacy = await readLegacyJournal(database, key)
      if (!legacy) return null
      await saveJournalOn(database, key, legacy)
      await deleteLegacyJournal(database, key)
      cursor = await readCursorRecord(database, key)
      if (!cursor) return null
    }
    const records = await readEntryRecords(database, key)
    return {
      version: 1,
      entries: records.map(stripRecord),
      cursor: Number(cursor.afterChangeSetId ?? 0) || 0,
      changeSetCursor: {
        afterChangeSetId: Number(cursor.afterChangeSetId ?? 0) || 0,
        tailUpdateTime: cursor.tailUpdateTime ?? null,
        tailChangeId: cursor.tailChangeId ?? ''
      },
    }
  } finally {
    database.close?.()
  }
}

/**
 * 旧格式迁移的读取侧。返回 null 表示没有可迁移的数据（新库，或旧 store 不存在）。
 * 退出条件：所有用户完成一次同步后即可删除本函数与 `sync-journals` store。
 */
async function readLegacyJournal(database, projectKey) {
  if (!database.objectStoreNames.contains(JOURNAL_STORE_NAME)) return null
  const legacy = await runTransaction(database, JOURNAL_STORE_NAME, 'readonly',
    stores => stores[JOURNAL_STORE_NAME].get(projectKey))
  if (!legacy || !Array.isArray(legacy.entries)) return null
  return legacy
}

async function deleteLegacyJournal(database, projectKey) {
  if (!database.objectStoreNames.contains(JOURNAL_STORE_NAME)) return
  await runTransaction(database, JOURNAL_STORE_NAME, 'readwrite',
    stores => stores[JOURNAL_STORE_NAME].delete(projectKey))
}

export async function clearJournal(projectKey, options = {}) {
  const key = requireKey(projectKey)
  const factory = resolveFactory(options.indexedDB)
  if (!factory) return false
  const database = await openDatabase(factory, options)
  try {
    const cursor = await readCursorRecord(database, key)
    const count = Number(cursor?.entryCount ?? 0) || 0
    await runTransaction(database, [JOURNAL_ENTRY_STORE, JOURNAL_CURSOR_STORE], 'readwrite', stores => {
      const entryStore = stores[JOURNAL_ENTRY_STORE]
      const cursorStore = stores[JOURNAL_CURSOR_STORE]
      const requests = []
      for (let seq = 0; seq < count; seq += 1) requests.push(entryStore.delete(entryKey(key, seq)))
      requests.push(cursorStore.delete(key))
      return requests
    })
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
