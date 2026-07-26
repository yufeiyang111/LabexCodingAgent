// Shared file-path highlighting. Used by CloudWorkspace's rich markdown
// renderer and SummaryCard's lighter parser so that any path-like string
// in agent output (e.g. `frontend/src/utils/foo.js`) becomes a clickable
// chip the user can use to jump to that file in the workspace tree.

// File extensions we treat as clickable paths. Anything not in this list
// is ignored to avoid false positives like `node.js`, `main.co`, etc.
export const FILE_LINK_EXTS = new Set([
  'js', 'mjs', 'cjs', 'ts', 'tsx', 'jsx', 'vue', 'java', 'kt', 'kts', 'scala', 'groovy',
  'py', 'rb', 'go', 'rs', 'php', 'c', 'cc', 'cpp', 'cxx', 'h', 'hh', 'hpp', 'm', 'mm',
  'cs', 'swift', 'dart', 'lua', 'pl', 'r',
  'json', 'json5', 'jsonc', 'yaml', 'yml', 'toml', 'ini', 'conf', 'cfg',
  'md', 'mdx', 'markdown', 'rst', 'txt',
  'html', 'htm', 'css', 'scss', 'sass', 'less', 'styl',
  'xml', 'svg', 'svelte',
  'sh', 'bash', 'zsh', 'fish', 'ps1', 'bat', 'cmd',
  'sql', 'graphql', 'gql', 'proto',
  'env', 'lock', 'gitignore', 'gitattributes', 'editorconfig', 'eslintrc', 'prettierrc',
  'csv', 'tsv', 'log',
  'png', 'jpg', 'jpeg', 'gif', 'webp', 'ico'
])

const PATTERN_SRC = '((?:[A-Za-z][\\w.-]*\\/)+[\\w.-]+\\.[A-Za-z0-9]{1,10})'

// Skip selectors for the walker. Anything matching these ancestors is
// considered "already styled" — code blocks, buttons, our own chrome, etc.
const SKIP_SELECTORS = [
  'a', 'code', 'pre', 'button',
  '.file-link',
  '.msg-table-tag', '.code-lang',
  '.mermaid-tab', '.mermaid-tool-btn',
  '.code-copy-btn', '.code-tool-btn', '.table-copy-btn',
  '.msg-table-header', '.code-block-header', '.mermaid-header',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
  '.msg-callout-title'
].join(', ')

// Scan text nodes inside `root`, wrap any matching file path in
// <span class="file-link" data-path="...">...</span>.
// `makeIcon()` returns the inner HTML of the leading icon (defaults to empty).
export function enhanceFileLinks(root, { iconHtml = '', linkClass = 'file-link' } = {}) {
  if (!root || typeof document === 'undefined') return []
  const found = []
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null)
  const targets = []
  while (walker.nextNode()) {
    const node = walker.currentNode
    const parent = node.parentElement
    if (!parent) continue
    if (parent.closest(SKIP_SELECTORS)) continue
    const text = node.nodeValue || ''
    if (!text) continue
    const re = new RegExp(PATTERN_SRC, 'g')
    let m
    let hit = false
    while ((m = re.exec(text)) !== null) {
      const ext = m[1].split('.').pop().toLowerCase()
      if (FILE_LINK_EXTS.has(ext)) { hit = true; break }
    }
    if (hit) targets.push(node)
  }
  for (const node of targets) {
    const text = node.nodeValue || ''
    const frag = document.createDocumentFragment()
    let last = 0
    const re = new RegExp(PATTERN_SRC, 'g')
    let match
    while ((match = re.exec(text)) !== null) {
      const path = match[1]
      const ext = path.split('.').pop().toLowerCase()
      if (!FILE_LINK_EXTS.has(ext)) continue
      if (match.index > last) frag.append(document.createTextNode(text.slice(last, match.index)))
      const span = document.createElement('span')
      span.className = linkClass
      span.dataset.path = path
      span.setAttribute('role', 'button')
      span.setAttribute('tabindex', '0')
      span.title = `打开 ${path}`
      const iconPart = iconHtml ? `<span class="${linkClass}-icon">${iconHtml}</span>` : ''
      span.innerHTML = `${iconPart}<span class="${linkClass}-text">${escapeHtml(path)}</span>`
      frag.append(span)
      found.push(path)
      last = match.index + path.length
    }
    if (last < text.length) frag.append(document.createTextNode(text.slice(last)))
    node.parentNode?.replaceChild(frag, node)
  }
  return found
}

function escapeHtml(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}