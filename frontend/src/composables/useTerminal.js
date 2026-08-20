/**
 * useTerminal - xterm.js 终端 composable
 * 提供 WebSocket 连接管理、多终端会话、ANSI 色彩支持
 */
import { ref, onUnmounted, markRaw } from 'vue'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import { WebLinksAddon } from '@xterm/addon-web-links'
import { SearchAddon } from '@xterm/addon-search'
import '@xterm/xterm/css/xterm.css'
import {
  normalizeTerminalSize
} from './terminalProtocol'

export const INTERACTIVE_TERMINAL_DISABLED_MESSAGE = '交互式终端已按策略禁用，请使用受管终端。'

// 终端主题 - VSCode Dark+ 配色
export const VSCODE_DARK_THEME = {
  background: '#1e1e1e',
  foreground: '#d4d4d4',
  cursor: '#aeafad',
  cursorAccent: '#1e1e1e',
  selectionBackground: '#264f78',
  selectionForeground: '#ffffff',
  black: '#000000',
  red: '#cd3131',
  green: '#0dbc79',
  yellow: '#e5e510',
  blue: '#2472c8',
  magenta: '#bc3fbc',
  cyan: '#11a8cd',
  white: '#e5e5e5',
  brightBlack: '#666666',
  brightRed: '#f14c4c',
  brightGreen: '#23d18b',
  brightYellow: '#f5f543',
  brightBlue: '#3b8eea',
  brightMagenta: '#d670d6',
  brightCyan: '#29b8db',
  brightWhite: '#e5e5e5'
}

export const VSCODE_LIGHT_THEME = {
  background: '#ffffff',
  foreground: '#24292f',
  cursor: '#0969da',
  cursorAccent: '#ffffff',
  selectionBackground: '#b6d7ff',
  selectionForeground: '#24292f',
  black: '#24292f',
  red: '#cf222e',
  green: '#1a7f37',
  yellow: '#9a6700',
  blue: '#0969da',
  magenta: '#8250df',
  cyan: '#1b7c83',
  white: '#6e7781',
  brightBlack: '#57606a',
  brightRed: '#a40e26',
  brightGreen: '#116329',
  brightYellow: '#7d4e00',
  brightBlue: '#0550ae',
  brightMagenta: '#6639ba',
  brightCyan: '#0a6b75',
  brightWhite: '#24292f'
}

/**
 * 创建单个终端实例
 */
export function createTerminalInstance(options = {}) {
  const {
    fontSize = 14,
    fontFamily = "'JetBrains Mono', 'Cascadia Code', 'Fira Code', 'Consolas', 'Courier New', monospace",
    theme = VSCODE_DARK_THEME,
    cursorBlink = true,
    cursorStyle = 'block',
    scrollback = 10000,
    tabStopWidth = 4,
    lineHeight = 1.12,
    letterSpacing = 0
  } = options

  const terminal = new Terminal({
    fontSize,
    fontFamily,
    theme,
    cursorBlink,
    cursorStyle,
    scrollback,
    tabStopWidth,
    lineHeight,
    letterSpacing,
    allowProposedApi: true,
    convertEol: true,
    disableStdin: false,
    drawBoldTextInBrightColors: true,
    rightClickSelectsWord: true,
    macOptionIsMeta: true,
    altClickMovesCursor: true,
    minimumContrastRatio: 1,
    screenReaderMode: false
  })

  const fitAddon = new FitAddon()
  const webLinksAddon = new WebLinksAddon()
  const searchAddon = new SearchAddon()

  terminal.loadAddon(fitAddon)
  terminal.loadAddon(webLinksAddon)
  terminal.loadAddon(searchAddon)

  return { terminal, fitAddon, webLinksAddon, searchAddon }
}

/**
 * WebSocket 终端连接管理
 * 连接 /ws/terminal 端点，通过 JWT query parameter 鉴权
 */
export function useTerminalWebSocket(projectId) {
  const ws = ref(null)
  const sessionId = ref(null)
  const connected = ref(false)
  const reconnectAttempts = ref(0)
  const MAX_RECONNECT_ATTEMPTS = 5
  let reconnectTimer = null
  let isExplicitlyClosed = false
  let currentHandlers = {}

  function buildWsUrl() {
    const token = localStorage.getItem('token') || ''
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const host = window.location.host
    return `${protocol}//${host}/api/ws/terminal?token=${encodeURIComponent(token)}&projectId=${encodeURIComponent(projectId)}`
  }

  function connect(handlers = {}) {
    currentHandlers = handlers
    isExplicitlyClosed = false
    reconnectAttempts.value = 0
    doConnect()
  }

  function doConnect() {
    if (isExplicitlyClosed) return
    disconnect()
    isExplicitlyClosed = false
    const url = buildWsUrl()
    const socket = new WebSocket(url)

    socket.onopen = () => {
      connected.value = true
      reconnectAttempts.value = 0
      currentHandlers.onConnect?.()
    }

    socket.onmessage = (event) => {
      let msg
      try {
        msg = JSON.parse(event.data)
      } catch {
        return
      }
      switch (msg.type) {
        case 'created':
          sessionId.value = msg.sessionId
          currentHandlers.onCreated?.(msg.sessionId)
          break
        case 'output':
          currentHandlers.onOutput?.(msg.data)
          break
        case 'exit':
          currentHandlers.onExit?.(msg.code)
          break
        case 'error':
          if (msg.code === 'TERMINAL_WEBSOCKET_DISABLED') {
            currentHandlers.onDisabled?.(msg.message)
          } else {
            currentHandlers.onError?.(msg.message)
          }
          break
        case 'pong':
          break
        default:
          break
      }
    }

    socket.onclose = (event) => {
      connected.value = false
      if (isExplicitlyClosed) {
        return
      }
      if (event.code === 1008) {
        // Policy violation — don't reconnect
        currentHandlers.onDisabled?.(INTERACTIVE_TERMINAL_DISABLED_MESSAGE)
        return
      }
      currentHandlers.onDisconnect?.()
    }

    socket.onerror = () => {
      currentHandlers.onError?.('WebSocket connection error')
    }

    ws.value = socket
  }

  function send(data) {
    if (ws.value && ws.value.readyState === WebSocket.OPEN) {
      ws.value.send(typeof data === 'string' ? data : JSON.stringify(data))
    }
  }

  function createTerminalSession(cwd, cols, rows) {
    send({ type: 'create', cwd: cwd || '', cols: cols || 120, rows: rows || 30 })
  }

  function sendInput(data) {
    send({ type: 'input', data })
  }

  function resize(cols, rows) {
    const size = normalizeTerminalSize(cols, rows)
    send({ type: 'resize', cols: size.cols, rows: size.rows })
  }

  function sendPing() {
    send({ type: 'ping' })
  }

  function closeTerminal() {
    isExplicitlyClosed = true
    send({ type: 'close' })
  }

  function disconnect() {
    isExplicitlyClosed = true
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    if (ws.value) {
      ws.value.onopen = null
      ws.value.onmessage = null
      ws.value.onerror = null
      ws.value.onclose = null
      try {
        ws.value.close()
      } catch {}
      ws.value = null
    }
    connected.value = false
    sessionId.value = null
  }

  return {
    ws,
    sessionId,
    connected,
    connect,
    send,
    createTerminalSession,
    sendInput,
    resize,
    sendPing,
    closeTerminal,
    disconnect
  }
}

/**
 * 完整终端管理器
 * 组合 xterm.js + WebSocket
 */
export function useTerminalManager() {
  const terminals = ref([])
  const activeTerminalId = ref(null)
  let terminalCounter = 0

  function afterLayout(callback) {
    if (typeof window !== 'undefined' && typeof window.requestAnimationFrame === 'function') {
      window.requestAnimationFrame(() => window.requestAnimationFrame(callback))
      return
    }
    setTimeout(callback, 0)
  }

  function getTerminalSize(termData) {
    return normalizeTerminalSize(termData.terminal.cols, termData.terminal.rows)
  }

  function fitTerminalData(termData, options = {}) {
    const { notifyBackend = true, focus = false, afterFit } = options
    try {
      termData.fitAddon.fit()
    } catch (e) {
      console.debug('[Terminal] Fit failed:', e.message)
    }

    // The managed REST terminal does not expose a raw PTY resize endpoint.
    // Keep fitting the local xterm viewport without attempting a disabled WebSocket resize.
    void notifyBackend

    if (focus) {
      termData.terminal.focus()
    }

    afterFit?.()
  }

  function scheduleFitTerminalData(termData, options = {}) {
    afterLayout(() => fitTerminalData(termData, options))
  }

  function createTerminal(containerEl, options = {}) {
    terminalCounter++
    const id = `term-${terminalCounter}`
    const name = options.name || `Terminal ${terminalCounter}`

    // 创建 xterm 实例
    const { terminal, fitAddon, searchAddon } = createTerminalInstance(options)

    // 创建 WebSocket 连接
    const runManagedCommand = typeof options.runManagedCommand === 'function'
      ? options.runManagedCommand
      : null
    const onRawInput = typeof options.onRawInput === 'function'
      ? options.onRawInput
      : null

    const termData = {
      id,
      name,
      terminal: markRaw(terminal),
      fitAddon: markRaw(fitAddon),
      searchAddon: markRaw(searchAddon),
      managedSessionId: options.managedSessionId || null,
      managedPath: options.managedPath || '',
      timeoutSeconds: options.timeoutSeconds || 60,
      lastExecution: null,
      element: null,
      inputBuffer: '',
      commandPending: false,
      isPtyMode: !!onRawInput,
      disposables: markRaw([])
    }

    if (onRawInput) {
      // PTY mode: forward raw keystrokes directly, no local echo or line buffering.
      // The PTY process handles echo, line editing, tab completion, etc.
      termData.disposables.push(terminal.onData(onRawInput))
      termData.disposables.push(terminal.onBinary(onRawInput))
    } else {
      // Managed REST mode: local line-buffer with echo, submit on Enter.
      function writeInput(data) {
        for (const character of data) {
          if (character === '\r') {
            const command = termData.inputBuffer.trim()
            termData.inputBuffer = ''
            terminal.write('\r\n')
            if (!command || termData.commandPending) continue
            if (!runManagedCommand || !termData.managedSessionId) {
              terminal.write('[Managed terminal session is unavailable]\r\n')
              continue
            }
            termData.commandPending = true
            Promise.resolve(runManagedCommand(command, termData))
              .catch(error => {
                terminal.write(`\r\n[Error: ${error?.message || 'Command failed'}]\r\n`)
              })
              .finally(() => {
                termData.commandPending = false
              })
            continue
          }
          if (character === '\u007f') {
            if (termData.inputBuffer.length > 0) {
              termData.inputBuffer = termData.inputBuffer.slice(0, -1)
              terminal.write('\b \b')
            }
            continue
          }
          if (character >= ' ') {
            termData.inputBuffer += character
            terminal.write(character)
          }
        }
      }

      termData.disposables.push(terminal.onData(writeInput))
      termData.disposables.push(terminal.onBinary(writeInput))
    }

    if (containerEl) {
      terminal.open(containerEl)
      termData.element = containerEl

      // 延迟到布局稳定后测量字符网格，避免 PTY 与 xterm 初始列宽不同步。
      scheduleFitTerminalData(termData, { notifyBackend: false, focus: true })
    }

    terminals.value.push(termData)
    activeTerminalId.value = id

    return termData
  }

  function getTerminal(id) {
    return terminals.value.find(t => t.id === id)
  }

  function getActiveTerminal() {
    return terminals.value.find(t => t.id === activeTerminalId.value)
  }

  function setActiveTerminal(id) {
    activeTerminalId.value = id
  }

  function removeTerminal(id) {
    const idx = terminals.value.findIndex(t => t.id === id)
    if (idx === -1) return

    const termData = terminals.value[idx]

    // 清理资源
    termData.disposables.forEach(d => d.dispose())
    termData.terminal.dispose()

    terminals.value.splice(idx, 1)

    // 切换到相邻终端
    if (activeTerminalId.value === id) {
      if (terminals.value.length > 0) {
        const newIdx = Math.min(idx, terminals.value.length - 1)
        activeTerminalId.value = terminals.value[newIdx].id
      } else {
        activeTerminalId.value = null
      }
    }
  }

  function fitTerminal(id) {
    const termData = getTerminal(id)
    if (termData) {
      scheduleFitTerminalData(termData)
    }
  }

  function fitAllTerminals() {
    terminals.value.forEach(t => {
      if (!t.element || t.element.classList.contains('visible')) {
        scheduleFitTerminalData(t)
      }
    })
  }

  function setTerminalTheme(theme) {
    terminals.value.forEach(termData => {
      termData.terminal.options.theme = theme
    })
  }

  function searchInTerminal(id, text) {
    const termData = getTerminal(id)
    if (termData) {
      termData.searchAddon.findNext(text)
    }
  }

  function clearTerminal(id) {
    const termData = getTerminal(id)
    if (termData) {
      termData.terminal.clear()
    }
  }

  function writeToTerminal(id, data) {
    const termData = getTerminal(id)
    if (termData) {
      termData.terminal.write(data)
    }
  }

  onUnmounted(() => {
    [...terminals.value].forEach(t => removeTerminal(t.id))
  })

  return {
    terminals,
    activeTerminalId,
    createTerminal,
    getTerminal,
    getActiveTerminal,
    setActiveTerminal,
    removeTerminal,
    fitTerminal,
    fitAllTerminals,
    setTerminalTheme,
    searchInTerminal,
    clearTerminal,
    writeToTerminal
  }
}
