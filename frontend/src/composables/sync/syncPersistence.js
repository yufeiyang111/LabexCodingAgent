// 唯一职责：为同步会话提供持久化依赖（journal 存取、目录句柄存取）。
// 边界：只负责"从哪存/从哪取"，不判断业务语义、不碰同步流程。
//
// 抽出来的原因：这些是纯粹的介质选择，和同步编排无关；
// 放在 useChangeSetSync 里会让那个文件同时承担"编排"与"存储装配"两种职责。

import {
  clearDirectoryHandle,
  loadDirectoryHandle,
  saveDirectoryHandle
} from './directoryHandleStore.js'
import { createIndexedDbJournalStore } from './journalStore.js'

/**
 * @param {object} options
 * @param {object} [options.journalStore] 测试可注入的 journal 存取器
 * @param {object} [options.handleStore]  测试可注入的目录句柄存取器
 * @param {object} [options.indexedDb]    自定义 IndexedDB 实现（测试用）
 */
export function createSyncPersistence(options = {}) {
  const dbOptions = options.indexedDb ? { indexedDB: options.indexedDb } : {}

  return {
    journalStore(projectKey) {
      if (options.journalStore) return options.journalStore
      return createIndexedDbJournalStore(projectKey, dbOptions)
    },
    handleStore() {
      if (options.handleStore) return options.handleStore
      return {
        load: key => loadDirectoryHandle(key, dbOptions),
        save: (key, handle) => saveDirectoryHandle(key, handle, dbOptions),
        clear: key => clearDirectoryHandle(key, dbOptions)
      }
    }
  }
}
