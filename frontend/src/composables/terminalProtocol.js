const DEFAULT_COLS = 120
const DEFAULT_ROWS = 30
const MIN_COLS = 20
const MAX_COLS = 500
const MIN_ROWS = 5
const MAX_ROWS = 200

function clampNumber(value, fallback, min, max) {
  const numberValue = Number(value)
  if (!Number.isFinite(numberValue)) {
    return fallback
  }
  return Math.min(max, Math.max(min, Math.floor(numberValue)))
}

export function normalizeTerminalSize(cols, rows) {
  return {
    cols: clampNumber(cols, DEFAULT_COLS, MIN_COLS, MAX_COLS),
    rows: clampNumber(rows, DEFAULT_ROWS, MIN_ROWS, MAX_ROWS)
  }
}

export function buildCreateTerminalMessage(cwd, size = {}) {
  const normalized = normalizeTerminalSize(size.cols, size.rows)
  const message = {
    type: 'create',
    cols: normalized.cols,
    rows: normalized.rows
  }

  const relativeCwd = normalizeRelativeCwd(cwd)
  if (relativeCwd) {
    message.cwd = relativeCwd
  }

  return message
}

function normalizeRelativeCwd(cwd) {
  if (typeof cwd !== 'string') return ''

  const normalized = cwd.trim().replaceAll('\\', '/')
  if (!normalized || normalized.startsWith('/') || /^[A-Za-z]:/.test(normalized)) {
    return ''
  }

  const parts = normalized.split('/')
  if (parts.some(part => part === '..')) {
    return ''
  }

  return normalized.replace(/^\.\/+/, '')
}

export function buildResizeTerminalMessage(cols, rows) {
  return {
    type: 'resize',
    ...normalizeTerminalSize(cols, rows)
  }
}


export function normalizeManagedTerminalResult(result = {}) {
  const exitCode = result.exitCode === '' || result.exitCode === undefined || result.exitCode === null
    ? null
    : Number(result.exitCode)
  const running = Boolean(result.running)
  const status = typeof result.status === 'string' && result.status
    ? result.status
    : running ? 'running' : exitCode === 0 ? 'succeeded' : exitCode === null ? '' : 'failed'
  return {
    running,
    status,
    exitCode: Number.isFinite(exitCode) ? exitCode : null,
    output: typeof result.output === 'string' ? result.output : '',
    shell: typeof result.shell === 'string' ? result.shell : '',
    workdir: typeof result.workdir === 'string' ? result.workdir : '',
    timeoutSeconds: Number.isFinite(Number(result.timeoutSeconds)) ? Number(result.timeoutSeconds) : 0
  }
}
