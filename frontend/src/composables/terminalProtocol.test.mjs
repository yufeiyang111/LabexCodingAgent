import assert from 'node:assert/strict'

import {
  buildCreateTerminalMessage,
  buildResizeTerminalMessage,
  normalizeManagedTerminalResult,
  normalizeTerminalSize
} from './terminalProtocol.js'

assert.deepEqual(
  buildCreateTerminalMessage('D:/workspace', { cols: 72, rows: 24 }),
  { type: 'create', cols: 72, rows: 24 },
  'absolute host paths must never be sent to the terminal service'
)

assert.deepEqual(
  buildCreateTerminalMessage('../outside', { cols: 72, rows: 24 }),
  { type: 'create', cols: 72, rows: 24 },
  'working directories cannot escape the authorized project workspace'
)

assert.deepEqual(
  buildCreateTerminalMessage('src/components', { cols: 72, rows: 24 }),
  { type: 'create', cwd: 'src/components', cols: 72, rows: 24 },
  'workspace-relative directories remain available to the terminal'
)

assert.deepEqual(
  buildCreateTerminalMessage('', { cols: 72, rows: 24 }),
  { type: 'create', cols: 72, rows: 24 },
  'blank cwd is omitted from create messages'
)

assert.deepEqual(
  buildResizeTerminalMessage(0, 999),
  { type: 'resize', cols: 20, rows: 200 },
  'resize messages clamp unusable terminal dimensions'
)

assert.deepEqual(
  normalizeTerminalSize(undefined, undefined),
  { cols: 120, rows: 30 },
  'missing terminal dimensions fall back to stable defaults'
)


assert.deepEqual(
  normalizeManagedTerminalResult({ running: false, exitCode: 0, output: 'ok', shell: 'bash', workdir: 'frontend', timeoutSeconds: 240 }),
  { running: false, status: 'succeeded', exitCode: 0, output: 'ok', shell: 'bash', workdir: 'frontend', timeoutSeconds: 240 },
  'managed terminal exposes the shared Shell result contract'
)

assert.deepEqual(
  normalizeManagedTerminalResult({ running: true, output: 'building' }),
  { running: true, status: 'running', exitCode: null, output: 'building', shell: '', workdir: '', timeoutSeconds: 0 },
  'long-running managed commands remain explicitly running until the session reports an exit'
)


assert.deepEqual(
  normalizeManagedTerminalResult({ running: false, status: 'cancelled', exitCode: -1, output: '[Stopped]' }),
  { running: false, status: 'cancelled', exitCode: -1, output: '[Stopped]', shell: '', workdir: '', timeoutSeconds: 0 },
  'cancelled managed commands retain an explicit terminal status'
)
