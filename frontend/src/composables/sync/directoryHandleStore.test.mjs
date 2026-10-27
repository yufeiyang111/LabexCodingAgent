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
test('documents that browser-level persistence requires live verification', () => {
  assert.equal(typeof globalThis.indexedDB, 'undefined')
})
