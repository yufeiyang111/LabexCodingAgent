// 唯一职责：声明并校验「本地目录适配器」的目标接口（Adapter 模式的目标契约）。
// 边界：不做任何 IO；只回答"这个对象是否满足契约"，让调用方在装配阶段快速失败。

export const DIRECTORY_ADAPTER_METHODS = Object.freeze([
  'listPaths',
  'fileExists',
  'readTextFile',
  'writeTextFile',
  'deleteFile'
])

export function collectMissingAdapterMethods(candidate) {
  if (!candidate || typeof candidate !== 'object') return [...DIRECTORY_ADAPTER_METHODS]
  return DIRECTORY_ADAPTER_METHODS.filter(name => typeof candidate[name] !== 'function')
}

export function isDirectoryAdapter(candidate) {
  return collectMissingAdapterMethods(candidate).length === 0
}

export function assertDirectoryAdapter(candidate, label = 'directory adapter') {
  const missing = collectMissingAdapterMethods(candidate)
  if (missing.length > 0) {
    throw new Error(`${label} does not satisfy the directory adapter contract, missing: ${missing.join(', ')}`)
  }
  return candidate
}
