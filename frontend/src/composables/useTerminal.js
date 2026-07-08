/**
 * useTerminal - xterm.js 终端 composable
 * 提供 WebSocket 连接管理、多终端会话、ANSI 色彩支持
 */
import { ref, onUnmounted } from 'vue'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import { WebLinksAddon } from '@xterm/addon-web-links'
import { SearchAddon } from '@xterm/addon-search'
import '@xterm/xterm/css/xterm.css'

// 终端主题 - VSCode Dark+ 配色
const VSCODE_THEME = {
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

/**
 * 创建单个终端实例
 */
export function createTerminalInstance(options = {}) {
  const {
    fontSize = 14,
    fontFamily = "'JetBrains Mono', 'Cascadia Code', 'Fira Code', 'Consolas', 'Courier New', monospace",
    theme = VSCODE_THEME,
    cursorBlink = true,
    cursorStyle = 'block',
    scrollback = 10000,
    tabStopWidth = 4
  } = options

  const terminal = new Terminal({
    fontSize,
    fontFamily,
    theme,
    cursorBlink,
    cursorStyle,
    scrollback,
    tabStopWidth,
    allowProposedApi: true,
    convertEol: true,
    disableStdin: false,
    drawBoldTextInBrightColors: true,
    minimumContrastRatio: 1,
    screenReaderMode: false,
    windowsMode: navigator.platform?.toLowerCase()?.includes('win') || false
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
 */
export function useTerminalWebSocket() {
  const ws = ref(null)
  const sessionId = ref(null)
  const connected = ref(false)
  const reconnectAttempts = ref(0)
  const MAX_RECONNECT = 5
  let reconnectTimer = null

  function getWsUrl() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const token = localStorage.getItem('token') || ''
    const params = token ? `?token=${encodeURIComponent(token)}` : ''
    return `${protocol}//${window.location.host}/api/ws/terminal${params}`
  }

  function connect(handlers = {}) {
    const { onOutput, onSessionCreated, onExit, onError, onConnect, onDisconnect } = handlers

    if (ws.value && ws.value.readyState === WebSocket.OPEN) {
      return
    }

    const url = getWsUrl()
    const socket = new WebSocket(url)

    socket.onopen = () => {
      connected.value = true
      reconnectAttempts.value = 0
      console.log('[Terminal] WebSocket connected successfully')
      onConnect?.()
    }

    socket.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data)
        console.log('[Terminal] Received message:', msg.type)
        switch (msg.type) {
          case 'session':
            sessionId.value = msg.sessionId
            console.log('[Terminal] Session ID:', msg.sessionId)
            break
          case 'created':
            sessionId.value = msg.sessionId
            console.log('[Terminal] Terminal created, sessionId:', msg.sessionId)
            onSessionCreated?.(msg.sessionId)
            break
          case 'output':
            console.log('[Terminal] Output received, length:', msg.data?.length)
            onOutput?.(msg.data)
            break
          case 'exit':
            console.log('[Terminal] Process exited with code:', msg.code)
            onExit?.(msg.code)
            break
          case 'error':
            console.error('[Terminal] Error from server:', msg.message)
            onError?.(msg.message)
            break
          case 'pong':
            break
        }
      } catch (e) {
        console.error('[Terminal] Parse error:', e)
      }
    }

    socket.onclose = (event) => {
      connected.value = false
      ws.value = null
      console.log('[Terminal] WebSocket disconnected, code:', event.code, 'reason:', event.reason)
      onDisconnect?.()
      // 自动重连（如果不是正常关闭）
      if (event.code !== 1000 && reconnectAttempts.value < MAX_RECONNECT) {
        reconnectAttempts.value++
        console.log(`[Terminal] Reconnecting in ${reconnectAttempts.value}s...`)
        reconnectTimer = setTimeout(() => connect(handlers), 1000 * reconnectAttempts.value)
      }
    }

    socket.onerror = (err) => {
      console.error('[Terminal] WebSocket error:', err)
      onError?.('WebSocket connection error')
    }

    ws.value = socket
  }

  function send(msg) {
    if (ws.value && ws.value.readyState === WebSocket.OPEN) {
      ws.value.send(JSON.stringify(msg))
    }
  }

  function createTerminal(cwd) {
    send({ type: 'create', cwd })
  }

  function sendInput(data) {
    send({ type: 'input', data })
  }

  function resize(cols, rows) {
    send({ type: 'resize', cols, rows })
  }

  function closeTerminal() {
    send({ type: 'close' })
  }

  function disconnect() {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    if (ws.value) {
      ws.value.close()
      ws.value = null
    }
    connected.value = false
  }

  onUnmounted(() => {
    disconnect()
  })

  return {
    ws,
    sessionId,
    connected,
    connect,
    send,
    createTerminal,
    sendInput,
    resize,
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

  function createTerminal(containerEl, options = {}) {
    terminalCounter++
    const id = `term-${terminalCounter}`
    const name = options.name || `Terminal ${terminalCounter}`

    // 创建 xterm 实例
    const { terminal, fitAddon, searchAddon } = createTerminalInstance(options)

    // 创建 WebSocket 连接
    const wsConn = useTerminalWebSocket()

    const termData = {
      id,
      name,
      terminal,
      fitAddon,
      searchAddon,
      wsConn,
      element: null,
      disposables: []
    }

    // 连接 WebSocket
    wsConn.connect({
      onOutput: (data) => {
        terminal.write(data)
      },
      onSessionCreated: (sid) => {
        console.log(`[Terminal] Session ${sid} created for ${id}`)
      },
      onExit: (code) => {
        terminal.write(`\r\n\x1b[90m[Process exited with code ${code}]\x1b[0m\r\n`)
      },
      onError: (msg) => {
        console.error(`[Terminal] Error for ${id}:`, msg)
        terminal.write(`\r\n\x1b[31m[Error: ${msg}]\x1b[0m\r\n`)
      },
      onConnect: () => {
        console.log(`[Terminal] WebSocket connected for ${id}, creating terminal process...`)
        // WebSocket 连接成功后，延迟创建终端进程
        setTimeout(() => {
          console.log(`[Terminal] Sending create command for ${id}`)
          wsConn.createTerminal(options.cwd)
        }, 100)
      }
    })

    // 终端输入 -> WebSocket
    termData.disposables.push(
      terminal.onData((data) => {
        wsConn.sendInput(data)
      })
    )

    // 终端二进制输入
    termData.disposables.push(
      terminal.onBinary((data) => {
        wsConn.sendInput(data)
      })
    )

    // 终端大小调整
    termData.disposables.push(
      terminal.onResize(({ cols, rows }) => {
        wsConn.resize(cols, rows)
      })
    )

    // 渲染到容器
    if (containerEl) {
      terminal.open(containerEl)
      termData.element = containerEl

      // 延迟 fit 确保 DOM 已渲染
      setTimeout(() => {
        try {
          fitAddon.fit()
        } catch (e) {
          console.debug('[Terminal] Initial fit failed:', e.message)
        }
      }, 100)
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
    termData.wsConn.closeTerminal()
    termData.wsConn.disconnect()
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
      try {
        termData.fitAddon.fit()
      } catch (e) {
        console.debug('[Terminal] Fit failed:', e.message)
      }
    }
  }

  function fitAllTerminals() {
    terminals.value.forEach(t => {
      try {
        t.fitAddon.fit()
      } catch (e) {
        console.debug('[Terminal] Fit failed:', e.message)
      }
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
    searchInTerminal,
    clearTerminal,
    writeToTerminal
  }
}
