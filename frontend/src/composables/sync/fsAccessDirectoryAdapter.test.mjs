import assert from 'node:assert/strict'
import test from 'node:test'

import { assertDirectoryAdapter, collectMissingAdapterMethods } from './directoryAdapterContract.js'
import { createFsAccessDirectoryAdapter } from './fsAccessDirectoryAdapter.js'

// 测试替身：内存版 FileSystemDirectoryHandle（仅实现适配器用到的子集）
function notFound(name) {
  const error = new Error(`${name} not found`)
  error.name = 'NotFoundError'
  return error
}

function createMemoryFileSystem() {
  const makeDir = name => ({ kind: 'directory', name, dirs: new Map(), files: new Map() })
  const root = makeDir('root')

  const fileHandle = file => ({
    kind: 'file',
    name: file.name,
    async getFile() {
      return { async text() { return file.data } }
    },
    async createWritable() {
      return {
        async write(chunk) { file.data = chunk },
        async close() {}
      }
    }
  })

  const dirHandle = node => ({
    kind: 'directory',
    name: node.name,
    async getFileHandle(name, options = {}) {
      if (!node.files.has(name)) {
        if (options.create !== true) throw notFound(name)
        node.files.set(name, { kind: 'file', name, data: '' })
      }
      return fileHandle(node.files.get(name))
    },
    async getDirectoryHandle(name, options = {}) {
      if (!node.dirs.has(name)) {
        if (options.create !== true) throw notFound(name)
        node.dirs.set(name, makeDir(name))
      }
      return dirHandle(node.dirs.get(name))
    },
    async removeEntry(name) {
      if (node.files.delete(name)) return
      if (node.dirs.delete(name)) return
      throw notFound(name)
    },
    async *values() {
      for (const child of node.dirs.values()) yield dirHandle(child)
      for (const child of node.files.values()) yield fileHandle(child)
    }
  })

  return { root: dirHandle(root) }
}

test('validates the adapter contract shape', () => {
  assert.deepEqual(collectMissingAdapterMethods({}), ['listPaths', 'fileExists', 'readTextFile', 'writeTextFile', 'deleteFile'])
  assert.throws(() => assertDirectoryAdapter({}, 'test adapter'), /does not satisfy/)
})

test('writes content creating intermediate directories', async () => {
  const adapter = createFsAccessDirectoryAdapter(createMemoryFileSystem().root)
  await adapter.writeTextFile('src/nested/deep/app.js', 'export const a = 1\n')
  assert.equal(await adapter.readTextFile('src/nested/deep/app.js'), 'export const a = 1\n')
  assert.equal(await adapter.fileExists('src/nested/deep/app.js'), true)
})

test('reads missing files as null and reports existence separately', async () => {
  const adapter = createFsAccessDirectoryAdapter(createMemoryFileSystem().root)
  assert.equal(await adapter.readTextFile('src/missing.js'), null)
  assert.equal(await adapter.fileExists('src/missing.js'), false)
})

test('lists all file paths sorted', async () => {
  const adapter = createFsAccessDirectoryAdapter(createMemoryFileSystem().root)
  await adapter.writeTextFile('src/b.js', 'b')
  await adapter.writeTextFile('src/a.js', 'a')
  await adapter.writeTextFile('README.md', 'root')
  assert.deepEqual(await adapter.listPaths(), ['README.md', 'src/a.js', 'src/b.js'])
})

test('overwrites existing file content', async () => {
  const adapter = createFsAccessDirectoryAdapter(createMemoryFileSystem().root)
  await adapter.writeTextFile('src/a.js', 'old')
  await adapter.writeTextFile('src/a.js', 'new')
  assert.equal(await adapter.readTextFile('src/a.js'), 'new')
})

test('deletes files and reports missing targets idempotently', async () => {
  const adapter = createFsAccessDirectoryAdapter(createMemoryFileSystem().root)
  await adapter.writeTextFile('src/a.js', 'a')
  assert.equal(await adapter.deleteFile('src/a.js'), true)
  assert.equal(await adapter.deleteFile('src/a.js'), false)
  assert.equal(await adapter.fileExists('src/a.js'), false)
})

test('rejects empty paths and non-handle roots', async () => {
  const adapter = createFsAccessDirectoryAdapter(createMemoryFileSystem().root)
  await assert.rejects(() => adapter.readTextFile(''), /empty relative path/)
  assert.throws(() => createFsAccessDirectoryAdapter(null), /requires a FileSystemDirectoryHandle/)
})
