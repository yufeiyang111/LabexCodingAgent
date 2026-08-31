import { marked } from 'marked'
import hljs from 'highlight.js/lib/common'
import { normalizeSpecialMarkdownBlocks, stripInternalReasoningBlocks, stripInternalReasoningTags } from '@/utils/agentMarkdown'
import { enhanceFileLinks } from '@/utils/fileLinks'

// 从 CloudWorkspace 原样抽取的 Markdown 渲染管线（sanitize → enhance → callout/file-link）。
// 主 Agent 中部视图与子代理会话标签页共用同一实现，禁止再出现第二份渲染逻辑。

const markdownRenderCache = new Map()
const MAX_MD_CACHE_SIZE = 400

function renderMarkdown(text) {
  if (!text) return ''
  const rawHtml = marked.parse(normalizeSpecialMarkdownBlocks(text), { gfm: true, breaks: true, silent: true })
  return enhanceMarkdownHtml(sanitizeMarkdownHtml(String(rawHtml || '')))
}

function getCachedMarkdown(prefix, rawText) {
  if (!rawText) return ''
  const cacheKey = `${prefix}::${rawText}`
  if (markdownRenderCache.has(cacheKey)) {
    return markdownRenderCache.get(cacheKey)
  }
  let result = ''
  if (prefix === 'thinking') {
    result = renderMarkdown(stripInternalReasoningTags(rawText))
  } else if (prefix === 'message_assistant') {
    result = renderMarkdown(stripInternalReasoningBlocks(rawText))
  } else {
    result = renderMarkdown(rawText)
  }
  if (markdownRenderCache.size > MAX_MD_CACHE_SIZE) {
    const firstKey = markdownRenderCache.keys().next().value
    markdownRenderCache.delete(firstKey)
  }
  markdownRenderCache.set(cacheKey, result)
  return result
}

export function renderThinkingMarkdown(text) {
  return getCachedMarkdown('thinking', text)
}

export function renderMessageMarkdown(message) {
  if (!message) return ''
  if (message._renderedHtml && message._renderedContent === message.content && !message.isStreaming) {
    return message._renderedHtml
  }
  const isAssistant = message.role === 'assistant'
  const html = getCachedMarkdown(isAssistant ? 'message_assistant' : 'message_user', message.content)
  if (!message.isStreaming) {
    message._renderedContent = message.content
    message._renderedHtml = html
  }
  return html
}

function sanitizeMarkdownHtml(html) {
  if (typeof document === 'undefined') return html
  const template = document.createElement('template')
  template.innerHTML = html
  const allowedTags = new Set(['p', 'br', 'strong', 'em', 'del', 'code', 'pre', 'blockquote', 'ul', 'ol', 'li', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'a', 'hr'])

  function cleanElement(el) {
    Array.from(el.children).forEach(cleanElement)
    const tag = el.tagName.toLowerCase()
    if (!allowedTags.has(tag)) {
      const parent = el.parentNode
      while (el.firstChild) parent.insertBefore(el.firstChild, el)
      parent.removeChild(el)
      return
    }
    Array.from(el.attributes).forEach((attr) => {
      const name = attr.name.toLowerCase()
      const value = attr.value || ''
      const allowed = (
        (tag === 'a' && ['href', 'title'].includes(name)) ||
        (tag === 'code' && name === 'class')
      )
      if (!allowed) {
        el.removeAttribute(attr.name)
        return
      }
      if (tag === 'a' && name === 'href' && !/^(https?:|mailto:|#)/i.test(value)) {
        el.removeAttribute(attr.name)
      }
    })
  }

  Array.from(template.content.children).forEach(cleanElement)
  return template.innerHTML
}

const codeHighlightCache = new Map()
const MAX_HIGHLIGHT_CACHE_SIZE = 500

function getHighlightedCode(rawCode, lang) {
  const key = `${lang}::${rawCode}`
  if (codeHighlightCache.has(key)) return codeHighlightCache.get(key)
  let result = ''
  try {
    if (lang !== 'text' && hljs.getLanguage(lang)) {
      result = hljs.highlight(rawCode, { language: lang }).value
    } else {
      result = rawCode
    }
  } catch {
    result = rawCode
  }
  if (codeHighlightCache.size > MAX_HIGHLIGHT_CACHE_SIZE) {
    const firstKey = codeHighlightCache.keys().next().value
    codeHighlightCache.delete(firstKey)
  }
  codeHighlightCache.set(key, result)
  return result
}

function enhanceMarkdownHtml(html) {
  if (typeof document === 'undefined') return html
  const template = document.createElement('template')
  template.innerHTML = html

  linkifyPlainUrls(template.content)

  template.content.querySelectorAll('a[href]').forEach((link) => {
    const href = link.getAttribute('href') || ''
    if (/^https?:\/\//i.test(href)) {
      link.setAttribute('target', '_blank')
      link.setAttribute('rel', 'noopener noreferrer')
    }
    link.classList.add('msg-link')
  })

  template.content.querySelectorAll('pre').forEach((pre) => {
    const code = pre.querySelector('code')
    const rawCode = code ? code.textContent || '' : pre.textContent || ''
    const className = code?.getAttribute('class') || ''
    const langMatch = className.match(/language-([a-z0-9_+-]+)/i)
    const requestedLang = normalizeCodeLanguage(langMatch?.[1] || '')
    const lang = requestedLang || guessCodeLanguage(rawCode)

    if (code) {
      if (lang !== 'text' && hljs.getLanguage(lang)) {
        code.innerHTML = getHighlightedCode(rawCode, lang)
        code.classList.add('hljs')
      } else {
        code.textContent = rawCode
      }
    }

    // Mermaid gets a custom block with chart/code tabs (rendered async after
    // the HTML is mounted). Other languages use the standard code block UI.
    if (lang === 'mermaid') {
      const wrapper = document.createElement('div')
      wrapper.className = 'mermaid-block'
      wrapper.dataset.code = rawCode

      const tabs = document.createElement('div')
      tabs.className = 'mermaid-tabs'
      const tabChart = document.createElement('button')
      tabChart.type = 'button'
      tabChart.className = 'mermaid-tab is-active'
      tabChart.dataset.view = 'chart'
      tabChart.innerHTML = `${iconSvg('chart')}<span>图表预览</span>`
      const tabCode = document.createElement('button')
      tabCode.type = 'button'
      tabCode.className = 'mermaid-tab'
      tabCode.dataset.view = 'code'
      tabCode.innerHTML = `${iconSvg('code')}<span>Mermaid 源码</span>`
      tabs.append(tabChart, tabCode)

      const tools = document.createElement('div')
      tools.className = 'mermaid-tools'
      const copyBtn = document.createElement('button')
      copyBtn.type = 'button'
      copyBtn.className = 'mermaid-tool-btn'
      copyBtn.dataset.action = 'copy'
      copyBtn.title = '复制源码'
      copyBtn.innerHTML = iconSvg('copy')
      const zoomOut = document.createElement('button')
      zoomOut.type = 'button'
      zoomOut.className = 'mermaid-tool-btn'
      zoomOut.dataset.action = 'zoom-out'
      zoomOut.title = '缩小'
      zoomOut.innerHTML = iconSvg('zoom-out')
      const zoomIn = document.createElement('button')
      zoomIn.type = 'button'
      zoomIn.className = 'mermaid-tool-btn'
      zoomIn.dataset.action = 'zoom-in'
      zoomIn.title = '放大'
      zoomIn.innerHTML = iconSvg('zoom-in')
      const dl = document.createElement('button')
      dl.type = 'button'
      dl.className = 'mermaid-tool-btn'
      dl.dataset.action = 'download'
      dl.title = '下载 SVG'
      dl.innerHTML = iconSvg('download')
      const fs = document.createElement('button')
      fs.type = 'button'
      fs.className = 'mermaid-tool-btn'
      fs.dataset.action = 'fullscreen'
      fs.title = '全屏'
      fs.innerHTML = iconSvg('fullscreen')
      tools.append(copyBtn, zoomOut, zoomIn, dl, fs)

      const header = document.createElement('div')
      header.className = 'mermaid-header'
      header.append(tabs, tools)

      const chartArea = document.createElement('div')
      chartArea.className = 'mermaid-chart'
      chartArea.style.display = 'flex'
      const status = document.createElement('div')
      status.className = 'mermaid-status'
      status.textContent = '正在渲染图表…'
      chartArea.append(status)

      const codeArea = document.createElement('div')
      codeArea.className = 'mermaid-code'
      codeArea.style.display = 'none'
      const codePre = document.createElement('pre')
      const codeEl = document.createElement('code')
      codeEl.className = 'language-mermaid'
      codeEl.textContent = rawCode
      codePre.append(codeEl)
      codeArea.append(codePre)

      wrapper.append(header, chartArea, codeArea)
      pre.parentNode.insertBefore(wrapper, pre)
      pre.remove()
      return
    }

    const wrapper = document.createElement('div')
    wrapper.className = 'code-block'
    wrapper.dataset.lang = lang
    const header = document.createElement('div')
    header.className = 'code-block-header'
    const label = document.createElement('span')
    label.className = 'code-lang'
    label.innerHTML = `${iconSvg('code')}<span>${lang}</span>`
    const tools = document.createElement('div')
    tools.className = 'code-tools'
    const dl = document.createElement('button')
    dl.type = 'button'
    dl.className = 'code-tool-btn'
    dl.dataset.action = 'download'
    dl.title = '下载代码'
    dl.innerHTML = iconSvg('download')
    const copy = document.createElement('button')
    copy.type = 'button'
    copy.className = 'code-copy-btn'
    copy.dataset.code = rawCode
    copy.title = '复制代码'
    copy.innerHTML = `${iconSvg('copy')}<span>复制</span>`
    tools.append(dl, copy)
    header.append(label, tools)
    pre.parentNode.insertBefore(wrapper, pre)
    wrapper.append(header, pre)
  })

  template.content.querySelectorAll('table').forEach((table) => {
    table.classList.add('msg-table')
    const tableText = tableToTsv(table)
    const wrap = document.createElement('div')
    wrap.className = 'msg-table-wrap'
    const header = document.createElement('div')
    header.className = 'msg-table-header'
    const tag = document.createElement('span')
    tag.className = 'msg-table-tag'
    tag.textContent = '表格'
    const tools = document.createElement('div')
    tools.className = 'msg-table-tools'
    const copy = document.createElement('button')
    copy.type = 'button'
    copy.className = 'msg-table-tool'
    copy.dataset.action = 'copy'
    copy.dataset.table = tableText
    copy.title = '复制为 TSV'
    copy.setAttribute('aria-label', '复制')
    copy.innerHTML = iconSvg('copy')
    const dl = document.createElement('button')
    dl.type = 'button'
    dl.className = 'msg-table-tool'
    dl.dataset.action = 'download'
    dl.dataset.table = tableText
    dl.title = '下载为 CSV'
    dl.setAttribute('aria-label', '下载')
    dl.innerHTML = iconSvg('download')
    const fs = document.createElement('button')
    fs.type = 'button'
    fs.className = 'msg-table-tool'
    fs.dataset.action = 'fullscreen'
    fs.title = '全屏查看'
    fs.setAttribute('aria-label', '全屏')
    fs.innerHTML = iconSvg('fullscreen')
    tools.append(copy, dl, fs)
    header.append(tag, tools)
    const scroll = document.createElement('div')
    scroll.className = 'msg-table-scroll'
    table.parentNode.insertBefore(wrap, table)
    scroll.appendChild(table)
    wrap.append(header, scroll)
  })

  enhanceCallouts(template.content)
  enhanceFileLinks(template.content, { iconHtml: iconSvg('file') })

  return template.innerHTML
}

export function iconSvg(name) {
  const stroke = 'currentColor'
  const sw = 2
  switch (name) {
    case 'copy':
      return `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="${stroke}" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>`
    case 'download':
      return `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="${stroke}" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>`
    case 'zoom-in':
      return `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="${stroke}" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16.65" y2="16.65"/><line x1="11" y1="8" x2="11" y2="14"/><line x1="8" y1="11" x2="14" y1="11"/></svg>`
    case 'zoom-out':
      return `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="${stroke}" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16.65" y2="16.65"/><line x1="8" y1="11" x2="14" y1="11"/></svg>`
    case 'fullscreen':
      return `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="${stroke}" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round"><path d="M8 3H5a2 2 0 0 0-2 2v3"/><path d="M21 8V5a2 2 0 0 0-2-2h-3"/><path d="M3 16v3a2 2 0 0 0 2 2h3"/><path d="M16 21h3a2 2 0 0 0 2-2v-3"/></svg>`
    case 'code':
      return `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="${stroke}" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>`
    case 'chart':
      return `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="${stroke}" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="20" x2="18" y2="10"/><line x1="12" y1="20" x2="12" y2="4"/><line x1="6" y1="20" x2="6" y2="14"/></svg>`
    case 'table':
      return `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="${stroke}" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/><line x1="3" y1="9" x2="21" y2="9"/><line x1="3" y1="15" x2="21" y2="15"/><line x1="9" y1="3" x2="9" y2="21"/></svg>`
    case 'file':
      return `<svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="${stroke}" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>`
    case 'check':
      return `<svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="${stroke}" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>`
    default:
      return ''
  }
}

function linkifyPlainUrls(root) {
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode(node) {
      const parent = node.parentElement
      if (!parent) return NodeFilter.FILTER_REJECT
      if (parent.closest('a, code, pre, button')) return NodeFilter.FILTER_REJECT
      return /https?:\/\/\S+/i.test(node.nodeValue || '') ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT
    }
  })
  const nodes = []
  while (walker.nextNode()) nodes.push(walker.currentNode)
  const urlRe = /https?:\/\/[^\s<>"']+/gi
  nodes.forEach((node) => {
    const text = node.nodeValue || ''
    const frag = document.createDocumentFragment()
    let last = 0
    let match
    while ((match = urlRe.exec(text)) !== null) {
      const raw = match[0]
      const trimmed = raw.replace(/[),.;:!?]+$/g, '')
      const trailing = raw.slice(trimmed.length)
      if (match.index > last) frag.append(document.createTextNode(text.slice(last, match.index)))
      const a = document.createElement('a')
      a.href = trimmed
      a.textContent = trimmed
      a.target = '_blank'
      a.rel = 'noopener noreferrer'
      a.className = 'msg-link'
      frag.append(a)
      if (trailing) frag.append(document.createTextNode(trailing))
      last = match.index + raw.length
    }
    if (last < text.length) frag.append(document.createTextNode(text.slice(last)))
    node.parentNode?.replaceChild(frag, node)
  })
}

function normalizeCodeLanguage(lang) {
  const normalized = String(lang || '').trim().toLowerCase()
  const aliases = {
    js: 'javascript',
    jsx: 'javascript',
    ts: 'typescript',
    tsx: 'typescript',
    py: 'python',
    sh: 'bash',
    shell: 'bash',
    zsh: 'bash',
    yml: 'yaml',
    md: 'markdown',
    vue: 'xml',
    plaintext: 'text',
    txt: 'text'
  }
  return aliases[normalized] || normalized
}

function guessCodeLanguage(code) {
  const text = String(code || '').trim()
  if (!text) return 'text'
  if (/^\s*[{[][\s\S]*[}\]]\s*$/.test(text)) return 'json'
  if (/^\s*(import|export)\s.+from\s|const\s+\w+\s*=|function\s+\w+\s*\(/m.test(text)) return 'javascript'
  if (/^\s*(public|private|class|package|import\s+java\.)/m.test(text)) return 'java'
  if (/^\s*(def|from\s+\w+\s+import|import\s+\w+|class\s+\w+:)/m.test(text)) return 'python'
  if (/^\s*(SELECT|INSERT|UPDATE|DELETE|CREATE)\b/im.test(text)) return 'sql'
  return 'text'
}

function tableToTsv(table) {
  return Array.from(table.querySelectorAll('tr')).map((row) =>
    Array.from(row.children).map((cell) => (cell.textContent || '').trim()).join('\t')
  ).join('\n')
}

function enhanceCallouts(root) {
  const labels = {
    note: '提示',
    tip: '建议',
    success: '完成',
    warning: '注意',
    important: '重点',
    error: '错误'
  }
  root.querySelectorAll('blockquote').forEach((quote) => {
    const marker = (quote.textContent || '').match(/^\s*\[!(NOTE|TIP|SUCCESS|WARNING|IMPORTANT|ERROR)\]\s*/i)
    if (!marker) return
    const type = marker[1].toLowerCase()
    quote.classList.add('msg-callout', `msg-callout-${type}`)
    const walker = document.createTreeWalker(quote, NodeFilter.SHOW_TEXT)
    while (walker.nextNode()) {
      const node = walker.currentNode
      const before = node.nodeValue || ''
      const after = before.replace(/^\s*\[!(NOTE|TIP|SUCCESS|WARNING|IMPORTANT|ERROR)\]\s*/i, '')
      if (before !== after) {
        node.nodeValue = after
        break
      }
    }
    const title = document.createElement('div')
    title.className = 'msg-callout-title'
    title.textContent = labels[type] || '提示'
    quote.prepend(title)
  })
}
