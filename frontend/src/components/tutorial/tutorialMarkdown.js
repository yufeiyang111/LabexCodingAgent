import { marked } from 'marked'
import hljs from 'highlight.js/lib/common'
import 'highlight.js/styles/github-dark.css'
import { headingId } from '@/components/tutorial/tutorialHeading'

const ALLOWED_TAGS = new Set([
  'p', 'br', 'strong', 'em', 'del', 's', 'u', 'sup', 'sub',
  'code', 'pre', 'blockquote', 'ul', 'ol', 'li',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'a', 'hr',
  'table', 'thead', 'tbody', 'tr', 'th', 'td', 'img', 'input'
])

const ALIGN_STYLE_RE = /^text-align:\s*(left|center|right|justify);?$/i
const CODE_CLASS_RE = /^(language-[\w+#.-]+|hljs|nohighlight)$/
const SAFE_HREF_RE = /^(https?:\/\/|mailto:|#|\/)/i
const SAFE_IMG_SRC_RE = /^(https?:\/\/|data:image\/|\/)/i
const LANG_ALIASES = { js: 'javascript', ts: 'typescript', py: 'python', sh: 'bash', shell: 'bash', yml: 'yaml', htm: 'xml', html: 'xml' }

const COPY_ICON = '<svg class="tut-code__ico tut-code__ico-copy" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>'
const CHECK_ICON = '<svg class="tut-code__ico tut-code__ico-check" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>'

export function renderTutorialHtml(markdown) {
  if (typeof document === 'undefined') return ''
  const source = String(markdown || '').replace(/^#\s+.*(?:\r?\n){1,2}/, '')
  const html = marked.parse(source, { gfm: true, breaks: true, silent: true })
  const template = document.createElement('template')
  template.innerHTML = String(html || '')
  Array.from(template.content.children).forEach(cleanNode)
  return template.innerHTML
}

export function enhanceTutorialBody(body) {
  if (!body) return
  Array.from(body.children).forEach((el, index) => el.style.setProperty('--i', String(index)))
  body.querySelectorAll('pre').forEach(wrapCodeBlock)
}

function cleanNode(el) {
  Array.from(el.children).forEach(cleanNode)
  const tag = el.tagName.toLowerCase()
  if (!ALLOWED_TAGS.has(tag)) {
    unwrap(el)
    return
  }
  filterAttributes(el, tag)
  if (/^h[1-6]$/.test(tag)) {
    const id = headingId((el.textContent || '').replace(/[`*_]/g, ''))
    if (id) el.setAttribute('id', id)
  }
  if (tag === 'a') {
    el.setAttribute('target', '_blank')
    el.setAttribute('rel', 'noopener noreferrer')
  }
}

function unwrap(el) {
  const parent = el.parentNode
  while (el.firstChild) parent.insertBefore(el.firstChild, el)
  parent.removeChild(el)
}

function filterAttributes(el, tag) {
  Array.from(el.attributes).forEach((attr) => {
    const name = attr.name.toLowerCase()
    const value = attr.value || ''
    if (!attributeAllowed(tag, name, value)) el.removeAttribute(attr.name)
  })
}

function attributeAllowed(tag, name, value) {
  switch (tag) {
    case 'a':
      return (name === 'href' || name === 'title') && !(name === 'href' && !SAFE_HREF_RE.test(value))
    case 'img':
      if (name === 'src') return SAFE_IMG_SRC_RE.test(value)
      return name === 'alt' || name === 'title' || name === 'width' || name === 'height'
    case 'input':
      return name === 'type' || name === 'checked' || name === 'disabled'
    case 'code':
      return name === 'class' && CODE_CLASS_RE.test(value)
    case 'th':
    case 'td':
      return name === 'align' || (name === 'style' && ALIGN_STYLE_RE.test(value))
    default:
      return false
  }
}

function wrapCodeBlock(pre) {
  if (pre.closest('.tut-code')) return
  const code = pre.querySelector('code')
  const rawCode = (((code || pre).textContent || '').replace(/\n$/, ''))
  let lang = ''
  const matched = code ? (code.getAttribute('class') || '').match(/language-([\w+#.-]+)/i) : null
  if (matched) lang = normalizeLang(matched[1])
  if (!lang || !hljs.getLanguage(lang)) lang = guessCodeLanguage(rawCode)

  if (code) {
    if (lang !== 'text' && hljs.getLanguage(lang)) {
      try {
        code.innerHTML = hljs.highlight(rawCode, { language: lang }).value
      } catch {
        code.textContent = rawCode
      }
    } else {
      code.textContent = rawCode
    }
    code.classList.add('hljs')
  }

  const shell = document.createElement('div')
  shell.className = 'tut-code'
  const bar = document.createElement('div')
  bar.className = 'tut-code__bar'
  const langLabel = document.createElement('span')
  langLabel.className = 'tut-code__lang'
  langLabel.textContent = lang === 'plaintext' ? 'text' : lang
  const copyButton = document.createElement('button')
  copyButton.type = 'button'
  copyButton.className = 'tut-code__copy'
  copyButton.title = '复制代码'
  copyButton.setAttribute('aria-label', '复制代码')
  copyButton.innerHTML = COPY_ICON + CHECK_ICON
  copyButton.addEventListener('click', () => copyToClipboard(copyButton, rawCode))
  bar.appendChild(langLabel)
  bar.appendChild(copyButton)
  pre.parentNode.insertBefore(shell, pre)
  shell.appendChild(bar)
  shell.appendChild(pre)
}

function normalizeLang(name) {
  const lower = String(name || '').toLowerCase()
  return LANG_ALIASES[lower] || lower
}

function guessCodeLanguage(code) {
  const head = code.slice(0, 500)
  if (/^\s*[{[]/.test(head)) return 'json'
  if (/\b(SELECT|INSERT\s+INTO|UPDATE\s+\w+\s+SET|CREATE\s+TABLE)\b/i.test(head)) return 'sql'
  if (/\b(public|private|protected)\s+(static\s+)?(class|interface|void|record)\b/.test(head)) return 'java'
  if (/^\s*(def |import \w|from \w+ import|print\()/m.test(head)) return 'python'
  if (/\b(const|let|var)\s+\w+\s*=|=>|\bfunction\s+\w*\(/.test(head)) return 'javascript'
  if (/^\s*<\/?[\w-]+/.test(head)) return 'xml'
  if (/^\$\s+/m.test(head) || /^\s*(npm|pnpm|yarn|git|cd|mvn|java|node|pip|docker|curl|mkdir)\b/m.test(head)) return 'bash'
  return 'text'
}

async function copyToClipboard(button, text) {
  let copied = false
  try {
    await navigator.clipboard.writeText(text)
    copied = true
  } catch {
    copied = legacyCopy(text)
  }
  button.classList.toggle('is-copied', copied)
  clearTimeout(button.__copyTimer)
  button.__copyTimer = setTimeout(() => button.classList.remove('is-copied'), 1600)
}

function legacyCopy(text) {
  try {
    const area = document.createElement('textarea')
    area.value = text
    area.setAttribute('readonly', '')
    area.style.position = 'fixed'
    area.style.opacity = '0'
    document.body.appendChild(area)
    area.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(area)
    return ok
  } catch {
    return false
  }
}
