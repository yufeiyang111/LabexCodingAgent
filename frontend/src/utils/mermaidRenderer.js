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
    // 'antiscript' blocks script injection but allows Chinese and special chars.
    // 'strict' rejects Chinese text nodes which causes "Syntax error in text".
    securityLevel: 'loose',
    fontFamily: "'Inter', 'Segoe UI', 'Noto Sans SC', sans-serif",
    theme: 'base',
    themeVariables: {
      fontSize: '13px',
      // Black/neutral palette — clear on any light background
      background: '#ffffff',
      primaryColor: '#f8fafc',
      primaryTextColor: '#0f172a',
      primaryBorderColor: '#374151',
      secondaryColor: '#f1f5f9',
      tertiaryColor: '#f8fafc',
      lineColor: '#374151',
      edgeLabelBackground: '#ffffff',
      mainBkg: '#f8fafc',
      nodeBorder: '#374151',
      clusterBkg: '#f8fafc',
      clusterBorder: '#94a3b8',
      titleColor: '#0f172a',
      textColor: '#0f172a',
      // Sequence diagram
      actorBorder: '#374151',
      actorBkg: '#f8fafc',
      actorTextColor: '#0f172a',
      actorLineColor: '#374151',
      signalColor: '#0f172a',
      signalTextColor: '#0f172a',
      labelBoxBkgColor: '#f8fafc',
      labelBoxBorderColor: '#374151',
      labelTextColor: '#0f172a',
      loopTextColor: '#0f172a',
      noteBorderColor: '#94a3b8',
      noteBkgColor: '#fef9c3',
      noteTextColor: '#0f172a',
      activationBorderColor: '#374151',
      activationBkgColor: '#e2e8f0',
    }
  })
  initialized = true
}

export async function renderMermaidDiagram(code) {
  await initMermaid()
  const mermaid = await loadMermaid()
  const id = `mmd-${Date.now().toString(36)}-${(renderCounter++).toString(36)}`
  try {
    const { svg } = await mermaid.render(id, code)
    // mermaid returns a "Syntax error" SVG instead of throwing — detect and
    // treat it as a render failure so we show our own error UI.
    if (svg && svg.includes('Syntax error in text')) {
      return { ok: false, error: 'Mermaid 语法错误，请检查图表代码' }
    }
    return { ok: true, svg }
  } catch (err) {
    return { ok: false, error: err?.message || String(err) }
  }
}

export async function syncMermaidTheme() {
  if (!initialized) return
  const mermaid = await loadMermaid()
  mermaid.initialize({ theme: 'base' })
}