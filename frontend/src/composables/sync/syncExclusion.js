// 唯一职责：判定工作区相对路径是否属于「永不同步」集合。
// 说明：本模块是云端 ExportSelectionPolicy 的防御性镜像（前端二次跳过），
// 语义必须与服务端保持同源；任何一侧新增排除项都要同步另一侧。

const EXCLUDED_SEGMENTS = new Set([
  'node_modules', 'bower_components', 'target', 'venv', '.venv', 'dist', 'build', 'out', 'coverage',
  '.git', '.hg', '.svn', '.idea', '.vscode', '__pycache__', '.pytest_cache', '.mypy_cache',
  '.next', '.nuxt', '.svelte-kit', '.vite', 'vendor', '.gradle', '.mvn', '.npm', '.cache',
  'appdata', 'microsoft', 'npm-cache'

const EXCLUDED_PROTECTED_SEGMENTS = new Set(['.labex', '.labex-agent'])
const RENAME_TEMP_PREFIX = '.labex-rename-'
const EXCLUDED_FILENAMES = new Set(['.ds_store', 'thumbs.db'])

export function normalizeRelativePath(input) {
  const raw = String(input ?? '').trim().replace(/\\/g, '/')
  if (!raw) return ''
  const withoutLeading = raw.replace(/^\.?\//, '')
  const collapsed = withoutLeading.split('/').filter(part => part.length > 0 && part !== '.')
  return collapsed.join('/')
}

function globToRegExp(pattern) {
  let regex = '^'
  for (let index = 0; index < pattern.length; index += 1) {
    const char = pattern[index]
    if (char === '*') {
      if (pattern[index + 1] === '*') {
        index += 1
        if (pattern[index + 1] === '/') {
          regex += '(?:.*/)?'
          index += 1
        } else {
          regex += '.*'
        }
      } else {
        regex += '[^/]*'
      }
    } else if (char === '?') {
      regex += '[^/]'
    } else {
      if ('\\.[]()^$+|{}'.includes(char)) regex += '\\'
      regex += char
    }
  }
  return new RegExp(`${regex}$`)
}

function parseIgnorePatterns(patterns) {
  const parsed = []
  for (const raw of Array.isArray(patterns) ? patterns : []) {
    let value = String(raw ?? '').trim().replace(/\\/g, '/')
    if (!value || value.startsWith('#') || value.startsWith('!')) continue
    while (value.startsWith('/')) value = value.slice(1)
    const directoryOnly = value.endsWith('/')
    if (directoryOnly) value = value.slice(0, -1)
    if (!value) continue
    parsed.push({ expression: value, directoryOnly, regex: globToRegExp(value) })
  }
  return parsed
}

function matchesIgnorePattern(path, pattern) {
  if (pattern.directoryOnly && (path === pattern.expression || path.startsWith(`${pattern.expression}/`))) {
    return true
  }
  if (pattern.regex.test(path)) return true
  if (!pattern.expression.includes('/')) {
    return pattern.regex.test(path.split('/').pop() ?? '')
  }
  return false
}

function isProtectedSegment(segment) {
  const lower = segment.toLowerCase()
  return EXCLUDED_PROTECTED_SEGMENTS.has(lower) || lower.startsWith(RENAME_TEMP_PREFIX)
}

export function isExcludedPath(path, options = {}) {
  const normalized = normalizeRelativePath(path)
  if (!normalized) return true

  const segments = normalized.split('/')
  const lowerSegments = segments.map(segment => segment.toLowerCase())
  const basename = lowerSegments[lowerSegments.length - 1] ?? ''
  if (EXCLUDED_FILENAMES.has(basename)) return true
  if (lowerSegments.some(isProtectedSegment)) return true
  if (lowerSegments.some(segment => EXCLUDED_SEGMENTS.has(segment))) return true

  return parseIgnorePatterns(options.ignorePatterns).some(pattern => matchesIgnorePattern(normalized, pattern))
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
