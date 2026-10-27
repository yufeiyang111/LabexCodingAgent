// 唯一职责：以 File System Access API 的目录句柄为底座，实现目录适配器契约。
// 边界：不含任何同步/冲突判定逻辑；只负责"读写这个目录里的文件"。
// 设计模式：Adapter —— 把浏览器句柄 API 适配成 F1/F3 需要的窄接口，
// 使上层代码与 Web 平台 API 解耦（测试用内存句柄替身即可验证）。

import { assertDirectoryAdapter } from './directoryAdapterContract.js'
import { normalizeRelativePath } from './syncExclusion.js'

const NOT_FOUND_CODES = new Set(['NotFoundError'])

function isNotFound(error) {
  return error instanceof Error && NOT_FOUND_CODES.has(error.name)
}

function splitPath(path) {
  const normalized = normalizeRelativePath(path)
  if (!normalized) throw new Error('directory adapter: empty relative path')
  const segments = normalized.split('/')
  const name = segments.pop()
  return { segments, name }
}

async function resolveDirectory(root, segments, options = {}) {
  let current = root
  for (const segment of segments) {
    current = await current.getDirectoryHandle(segment, { create: options.create === true })
  }
  return current
}

async function walkFiles(directory, prefix, output) {
  if (typeof directory.values !== 'function') return
  for await (const entry of directory.values()) {
    const path = prefix ? `${prefix}/${entry.name}` : entry.name
    if (entry.kind === 'directory') {
      await walkFiles(entry, path, output)
    } else {
      output.push(path)
    }
  }
}

export function createFsAccessDirectoryAdapter(rootHandle) {
  if (!rootHandle || typeof rootHandle.getFileHandle !== 'function') {
    throw new Error('fs access adapter requires a FileSystemDirectoryHandle')
  }

  async function listPaths() {
    const output = []
    await walkFiles(rootHandle, '', output)
    return output.sort()
  }

  async function fileExists(path) {
    const { segments, name } = splitPath(path)
    try {
      const directory = await resolveDirectory(rootHandle, segments)
      await directory.getFileHandle(name)
      return true
    } catch (error) {
      if (isNotFound(error)) return false
      throw error
    }
  }

  async function readTextFile(path) {
    const { segments, name } = splitPath(path)
    try {
      const directory = await resolveDirectory(rootHandle, segments)
      const handle = await directory.getFileHandle(name)
      const file = await handle.getFile()
      return await file.text()
    } catch (error) {
      if (isNotFound(error)) return null
      throw error
    }
  }

  async function writeTextFile(path, content) {
    const { segments, name } = splitPath(path)
    const directory = await resolveDirectory(rootHandle, segments, { create: true })
    const handle = await directory.getFileHandle(name, { create: true })
    const writable = await handle.createWritable()
    await writable.write(typeof content === 'string' ? content : '')
    await writable.close()
  }

  async function deleteFile(path) {
    const { segments, name } = splitPath(path)
    try {
      const directory = await resolveDirectory(rootHandle, segments)
      await directory.removeEntry(name)
      return true
    } catch (error) {
      if (isNotFound(error)) return false
      throw error
    }
  }

  return assertDirectoryAdapter({
    listPaths,
    fileExists,
    readTextFile,
    writeTextFile,
    deleteFile,
    rootHandle
  }, 'fs access directory adapter')
}
