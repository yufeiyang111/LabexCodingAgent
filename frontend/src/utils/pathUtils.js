/**
 * Normalizes an arbitrary path from tool calls, git status, evidence, or workspace events
 * into a clean, project-relative path.
 *
 * Handles:
 * - Backslashes (`\`) to slashes (`/`)
 * - Leading slashes and `./`
 * - Sandbox / container prefixes like `/work-4425/`, `workspace/`, `workspaces/project-123/`
 * - Absolute workspace paths like `D:/LabexAgent/workspaces/project-1/src/App.vue`
 */
export function normalizeWorkspacePath(rawPath) {
  if (!rawPath || typeof rawPath !== 'string') return ''
  let p = rawPath.replace(/\\/g, '/').trim()

  // Strip absolute drive / base workspace prefix if present (e.g. D:/.../workspaces/proj-1/...)
  p = p.replace(/^[a-zA-Z]:\/(?:[^/]+\/)*?workspaces\/[^/]+\//i, '')
  p = p.replace(/^[a-zA-Z]:\/(?:[^/]+\/)*?workspace\//i, '')

  // Strip leading slashes and dot-slashes
  p = p.replace(/^(\.\/|\/)+/, '')

  // Strip sandbox / container prefixes (e.g. work-4425/, workspaces/project-123/, app/workspace/, workspace/)
  p = p.replace(/^workspaces\/[^/]+\//i, '')
  p = p.replace(/^app\/workspace\//i, '')
  p = p.replace(/^work-\d+\//i, '')
  p = p.replace(/^workspace\//i, '')
  p = p.replace(/^(\.\/|\/)+/, '')

  return p
}

/**
 * Splits a workspace path into clean filename, directory display, and normalized full path.
 */
export function splitWorkspacePath(rawPath) {
  const norm = normalizeWorkspacePath(rawPath)
  if (!norm) return { name: '', dir: '', path: '' }
  const parts = norm.split('/')
  const name = parts.pop() || norm
  const dir = parts.join('/')
  return {
    name,
    dir: dir ? `/${dir}` : '',
    path: norm
  }
}

/**
 * Infers editor language mode from file extension.
 */
export function languageForPath(path) {
  const ext = String(path || '').split('.').pop()?.toLowerCase()
  const map = {
    js: 'javascript', mjs: 'javascript', cjs: 'javascript', jsx: 'javascript',
    ts: 'typescript', mts: 'typescript', cts: 'typescript', tsx: 'typescript',
    vue: 'html', html: 'html', htm: 'html',
    css: 'css', scss: 'scss', sass: 'scss', less: 'less',
    json: 'json', json5: 'json',
    md: 'markdown', markdown: 'markdown',
    py: 'python', pyw: 'python',
    java: 'java',
    sql: 'sql',
    sh: 'shell', bash: 'shell', zsh: 'shell', ps1: 'shell',
    yml: 'yaml', yaml: 'yaml',
    xml: 'xml', svg: 'xml',
    go: 'go', rs: 'rust', c: 'c', cpp: 'cpp', h: 'c', hpp: 'cpp'
  }
  return map[ext] || 'plaintext'
}
