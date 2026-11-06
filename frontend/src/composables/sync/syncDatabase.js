// 唯一职责：定义同步特性使用的 IndexedDB 数据库 schema（库名 + 全部 object store）与打开逻辑。
// 边界：只声明 schema 与建库/补 store，不读写任何业务数据。
//
// 为什么必须集中：journalStore 与 directoryHandleStore 共用同一个数据库，
// 而浏览器的 onupgradeneeded 只在**版本提升**时触发一次。
// 如果两个模块各自在自己的建库回调里只创建自己的 object store，那么
//   - 用户先选目录 → 库只含 directory-handles
//   - 随后写 journal → 事务请求 sync-journals → "object store was not found"
// 这正是 2026-11-05 用户看到的报错。所有 object store 必须在同一处一次建齐。

export const SYNC_DB_NAME = 'labex-sync'

/**
 * 本特性用到的全部 object store。
 *
 * **没有导出的版本号常量**：数据库版本由浏览器持有，代码只在确认缺 store 时
 * 用「实际版本 + 1」触发升级。写死一个版本常量会导致库被升级后
 * 再次打开时请求旧版本，IndexedDB 抛
 * "The requested version (1) is less than the existing version (2)."
 * 新增 store 只需加进这个数组，打开逻辑会自动补齐。
 */
export const SYNC_OBJECT_STORES = Object.freeze([
  'directory-handles',
  'sync-journals',
  'sync-journal-entries',
  'sync-journal-cursors'
])

/**
 * 打开（必要时创建/升级）同步数据库。
 * 升级时把**全部** object store 补齐，而不是只补当前调用方需要的那一个。
 *
 * 两条硬约束（都来自真实故障）：
 *
 * 1. **建库必须一次建齐所有 store**。早期两个模块各自建库，用户先选目录时
 *    版本 1 的库只含 directory-handles，之后写 journal 就报
 *    "object store was not found"。
 *
 * 2. **不能按固定版本号打开**。自愈补 store 会把库升到更高版本；如果后续仍按
 *    声明的常量版本请求，IndexedDB 会拒绝降级并抛
 *    "The requested version (1) is less than the existing version (2)."
 *    因此这里先用**不指定版本**的方式打开（浏览器返回当前版本），
 *    只有在确认缺 store 时才用「当前版本 + 1」触发升级。
 */
export function openSyncDatabase(factory, options = {}) {
  const dbName = options.dbName ?? SYNC_DB_NAME
  const requiredStores = options.objectStores ?? SYNC_OBJECT_STORES

  return openWithoutVersion(factory, dbName, requiredStores).then(result => {
    const missing = requiredStores.filter(name => !result.database.objectStoreNames.contains(name))
    if (missing.length === 0) return result.database

    // 缺 store：必须提升版本才会触发 onupgradeneeded。
    // 用数据库**实际**版本 +1，而不是声明的常量，避免与已升级过的库冲突。
    const nextVersion = (result.database.version ?? 0) + 1
    result.database.close?.()
    return openWithVersion(factory, dbName, nextVersion, requiredStores).then(repaired => {
      const stillMissing = requiredStores.filter(name => !repaired.database.objectStoreNames.contains(name))
      if (stillMissing.length > 0) {
        repaired.database.close?.()
        throw new Error(`indexedDB schema is missing object stores: ${stillMissing.join(', ')}`)
      }
      return repaired.database
    })
  })
}

/**
 * 不指定版本打开：浏览器打开现有库（任意版本），或按默认版本新建。
 * 这是唯一能同时兼容"新用户"与"库已被升级过"的打开方式。
 */
function openWithoutVersion(factory, dbName, requiredStores) {
  return new Promise((resolve, reject) => {
    const request = factory.open(dbName)
    request.onupgradeneeded = () => {
      // 新用户首次建库：一次把全部 store 建齐。
      const database = request.result
      for (const storeName of requiredStores) {
        if (!database.objectStoreNames.contains(storeName)) {
          database.createObjectStore(storeName)
        }
      }
    }
    request.onsuccess = () => resolve({ database: request.result })
    request.onerror = () => reject(request.error ?? new Error('indexedDB open failed'))
  })
}

function openWithVersion(factory, dbName, version, requiredStores) {
  return new Promise((resolve, reject) => {
    const request = factory.open(dbName, version)
    request.onupgradeneeded = () => {
      const database = request.result
      for (const storeName of requiredStores) {
        if (!database.objectStoreNames.contains(storeName)) {
          database.createObjectStore(storeName)
        }
      }
    }
    request.onsuccess = () => resolve({ database: request.result, version })
    request.onerror = () => reject(request.error ?? new Error('indexedDB open failed'))
  })
}
