// Mermaid renderer — lazy-loaded on first use so the heavy library does
// not end up in the main bundle. Keeps the markdown render path
// responsive even when no mermaid block is present.

let mermaidPromise = null
let initialized = false
let renderCounter = 0

async function loadMermaid() {
  if (!mermaidPromise) {
    mermaidPromise = import('mermaid').then((mod) => mod.default)
  }
  return mermaidPromise
}

export async function initMermaid() {
  const mermaid = await loadMermaid()
  if (initialized) return
  mermaid.initialize({
    startOnLoad: false,
    suppressErrorRendering: true,
    securityLevel: 'loose',
    fontFamily: "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif",
    theme: 'base',
    themeVariables: {
      fontSize: '13px',
      background: '#ffffff',
      primaryColor: '#f4f4f5',
      primaryTextColor: '#09090b',
      primaryBorderColor: '#27272a',
      secondaryColor: '#fafafa',
      tertiaryColor: '#f4f4f5',
      lineColor: '#27272a',
      edgeLabelBackground: '#ffffff',
      mainBkg: '#ffffff',
      nodeBorder: '#18181b',
      clusterBkg: '#fafafa',
      clusterBorder: '#d4d4d8',
      titleColor: '#09090b',
      textColor: '#09090b',
      actorBorder: '#18181b',
      actorBkg: '#f4f4f5',
      actorTextColor: '#09090b',
      actorLineColor: '#27272a',
      signalColor: '#09090b',
      signalTextColor: '#09090b',
      labelBoxBkgColor: '#f4f4f5',
      labelBoxBorderColor: '#27272a',
      labelTextColor: '#09090b',
      loopTextColor: '#09090b',
      noteBorderColor: '#d4d4d8',
      noteBkgColor: '#fef9c3',
      noteTextColor: '#09090b',
      activationBorderColor: '#18181b',
      activationBkgColor: '#e4e4e7',
    }
  })
  initialized = true
}

function decodeEntities(str) {
  if (!str) return ''
  return str
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&nbsp;/g, ' ')
}

export async function renderMermaidDiagram(code) {
  await initMermaid()
  const mermaid = await loadMermaid()
  const id = `mmd${Math.random().toString(36).substring(2, 8)}${renderCounter++}`
  const cleanCode = decodeEntities(code || '').trim()
  if (!cleanCode) {
    return { ok: false, error: 'Mermaid 代码为空' }
  }
  try {
    const { svg } = await mermaid.render(id, cleanCode)
    if (svg && (svg.includes('Syntax error in text') || svg.includes('Syntax error in graph'))) {
      return { ok: false, error: 'Mermaid 语法解析异常，请核对图表代码' }
    }
    return { ok: true, svg }
  } catch (err) {
    return { ok: false, error: err?.message || String(err) }
  } finally {
    if (typeof document !== 'undefined' && document.body) {
      const stray = document.querySelectorAll(
        `body > [id^="dmmd"], body > [id^="mmd"], body > svg[id^="mmd"], body > [id^="d${id}"], body > [id="${id}"]`
      )
      stray.forEach(el => el.remove())
    }
  }
}

export async function syncMermaidTheme() {
  if (!initialized) return
  const mermaid = await loadMermaid()
  mermaid.initialize({ theme: 'base', suppressErrorRendering: true })
}

export async function renderMermaidBlocks(root) {
  const targetRoot = root || (typeof document !== 'undefined' ? document : null)
  if (!targetRoot || typeof targetRoot.querySelectorAll !== 'function') return
  const blocks = targetRoot.querySelectorAll('.mermaid-block:not([data-rendered="1"])')
  for (const block of blocks) {
    if (block.dataset.rendered === 'pending') continue
    block.dataset.rendered = 'pending'
    const code = block.dataset.code || ''
    const chart = block.querySelector('.mermaid-chart')
    const status = block.querySelector('.mermaid-status')
    const result = await renderMermaidDiagram(code)
    if (!chart) continue
    if (result.ok) {
      chart.innerHTML = result.svg
      const svg = chart.querySelector('svg')
      if (svg) {
        svg.style.maxWidth = '100%'
        svg.style.height = 'auto'
        svg.dataset.scale = svg.dataset.scale || '1'
      }
    } else {
      if (status) status.remove()
      const err = document.createElement('div')
      err.className = 'mermaid-error'
      err.textContent = result.error
      chart.append(err)
    }
    block.dataset.rendered = '1'
  }
}