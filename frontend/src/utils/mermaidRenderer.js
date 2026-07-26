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

function resolveTheme() {
  if (typeof document === 'undefined') return 'default'
  return document.documentElement.classList.contains('ws-dark') || document.body.classList.contains('ws-dark')
    ? 'dark'
    : 'default'
}

export async function initMermaid() {
  const mermaid = await loadMermaid()
  if (initialized) return
  mermaid.initialize({
    startOnLoad: false,
    theme: resolveTheme(),
    securityLevel: 'strict',
    fontFamily: 'inherit',
    themeVariables: {
      fontSize: '13px'
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
    return { ok: true, svg }
  } catch (err) {
    return { ok: false, error: err?.message || String(err) }
  }
}

export async function syncMermaidTheme() {
  if (!initialized) return
  const mermaid = await loadMermaid()
  mermaid.initialize({ theme: resolveTheme() })
}