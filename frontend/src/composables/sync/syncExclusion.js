// 唯一职责：判定工作区相对路径是否属于「永不同步」集合。
// 说明：本模块是云端 ExportSelectionPolicy 的防御性镜像（前端二次跳过），
// 语义必须与服务端保持同源；任何一侧新增排除项都要同步另一侧。

const EXCLUDED_SEGMENTS = new Set([
  'node_modules', 'target', 'venv', '.venv', 'dist', 'build',
  '.git', '.idea', '.vscode', '__pycache__', '.next', '.nuxt', 'vendor'
])

const EXCLUDED_PREFIXES = ['.labex/', '.labex-agent/']

const EXCLUDED_FILENAMES = new Set(['.DS_Store', 'Thumbs.db'])

export function normalizeRelativePath(input) {
  const raw = String(input ?? '').trim().replace(/\\/g, '/')
  if (!raw) return ''
  const withoutLeading = raw.replace(/^\.?\//, '')
  const collapsed = withoutLeading.split('/').filter(part => part.length > 0 && part !== '.')
  return collapsed.join('/')
}

function wildcardToRegExp(pattern) {
  const escaped = pattern.split('*').map(part => part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('.*')
  return new RegExp(`^${escaped}$`)
}

function matchesIgnorePattern(path, pattern) {
  const normalized = normalizeRelativePath(pattern)
  if (!normalized) return false
  if (!normalized.includes('*')) {
    return path === normalized
      || path.startsWith(`${normalized}/`)
      || path.split('/').includes(normalized)
  }
  const regex = wildcardToRegExp(normalized)
  if (regex.test(path)) return true
  const basename = path.split('/').pop() ?? ''
  return regex.test(basename)
}

export function isExcludedPath(path, options = {}) {
  const normalized = normalizeRelativePath(path)
  if (!normalized) return true

  const basename = normalized.split('/').pop() ?? ''
  if (EXCLUDED_FILENAMES.has(basename)) return true
  if (EXCLUDED_PREFIXES.some(prefix => normalized.startsWith(prefix))) return true

  const segments = normalized.split('/')
  if (segments.some(segment => EXCLUDED_SEGMENTS.has(segment))) return true

  const patterns = Array.isArray(options.ignorePatterns) ? options.ignorePatterns : []
  return patterns.some(pattern => matchesIgnorePattern(normalized, pattern))
}

export function filterSyncPaths(paths, options = {}) {
  const list = Array.isArray(paths) ? paths : []
  const included = []
  const excluded = []
  for (const item of list) {
    const path = normalizeRelativePath(item)
    if (isExcludedPath(path, options)) excluded.push(path)
    else included.push(path)
  }
  return { included, excluded }
}

export const SYNC_EXCLUSION_SEGMENTS = EXCLUDED_SEGMENTS
