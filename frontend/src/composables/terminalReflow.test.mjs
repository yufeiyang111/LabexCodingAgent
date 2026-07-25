import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

import xterm from '@xterm/xterm'

const { Terminal } = xterm
const useTerminalSource = await readFile(new URL('./useTerminal.js', import.meta.url), 'utf8')
const terminalPanelSource = await readFile(new URL('../components/terminal/TerminalPanel.vue', import.meta.url), 'utf8')

assert.doesNotMatch(
  useTerminalSource,
  /\bwindowsMode\s*:/,
  'legacy windowsMode must stay disabled because it prevents xterm buffer reflow'
)

assert.match(
  terminalPanelSource,
  /function renderManagedOutput[\s\S]*?terminal\.scrollToBottom\(\)/,
  'managed terminal output must scroll to the latest rendered command result'
)

const webSocketHelperSource = useTerminalSource.slice(
  useTerminalSource.indexOf('export function useTerminalWebSocket'),
  useTerminalSource.indexOf('export function useTerminalManager')
)
assert.doesNotMatch(
  webSocketHelperSource,
  /onUnmounted\(/,
  'the WebSocket helper can be created after setup, so it must not register a lifecycle hook itself'
)

const terminal = new Terminal({ cols: 10, rows: 5, scrollback: 100 })
await new Promise(resolve => terminal.write('123456789012345\r\nnext\r\n', resolve))
terminal.resize(20, 5)

assert.equal(
  terminal.buffer.active.getLine(0)?.translateToString(true),
  '123456789012345',
  'soft-wrapped history reflows into the wider terminal'
)
assert.equal(
  terminal.buffer.active.getLine(1)?.translateToString(true),
  'next',
  'the following logical line moves up after reflow'
)

terminal.dispose()
