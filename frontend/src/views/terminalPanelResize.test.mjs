import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const workspaceSource = await readFile(new URL('./CloudWorkspace.vue', import.meta.url), 'utf8')
const terminalPanelSource = await readFile(new URL('../components/terminal/TerminalPanel.vue', import.meta.url), 'utf8')
const fileTreeSource = await readFile(new URL('../components/cloud/FileTreeNode.vue', import.meta.url), 'utf8')

assert.doesNotMatch(
  workspaceSource,
  /window\.innerWidth\s*\*\s*0\.4/,
  'drag resizing must not cap the terminal panel at 40 percent of the viewport'
)

assert.doesNotMatch(
  workspaceSource,
  /max-width:\s*45vw/,
  'CSS must not impose a second, inconsistent terminal panel width cap'
)

assert.match(
  workspaceSource,
  /@pointerdown="startResize"/,
  'the resize handle must use pointer events so it can retain the pointer while dragging'
)

assert.match(
  workspaceSource,
  /requestAnimationFrame\(/,
  'drag updates must be throttled to browser paint frames'
)

assert.match(
  workspaceSource,
  /\.ai-panel\.is-resizing\s*\{[^}]*transition:\s*none/s,
  'the width transition must be disabled while the user is actively dragging'
)

assert.doesNotMatch(
  workspaceSource,
  /document\.addEventListener\('mousemove'/,
  'legacy document mousemove listeners must not drive the resize loop'
)

assert.match(
  workspaceSource,
  /class="ws-resize-handle" @pointerdown="startSidebarResize"/,
  'the file tree must expose a pointer-based resize handle'
)

assert.match(
  workspaceSource,
  /function startSidebarResize\(/,
  'the file tree must use its own resize entry point'
)

assert.match(
  workspaceSource,
  /class="ws-terminal-dock"/,
  'the terminal must render in a bottom workbench dock'
)

assert.match(
  workspaceSource,
  /v-show="terminalPanelVisible"/,
  'closing the terminal panel must hide it without unmounting the terminal session'
)

assert.match(
  workspaceSource,
  /@click="selectAiTab\(tab\.key\)"/,
  'the terminal tab must use the shared tab-selection handler'
)

assert.match(
  workspaceSource,
  /if \(key === 'terminal'\)[\s\S]*terminalPanelVisible\.value = true[\s\S]*fitAllTerminals/,
  'selecting the terminal tab must open and fit the bottom terminal dock'
)
assert.doesNotMatch(
  workspaceSource,
  /activeAiTab === 'terminal'/,
  'the terminal must no longer render as an AI sidebar tab'
)

assert.doesNotMatch(
  terminalPanelSource,
  /<div class="terminal-statusbar">/,
  'the terminal must not reserve a bottom status bar that can cover recent output'
)

assert.match(
  terminalPanelSource,
  /\.terminal-container\s*\{[^}]*min-height:\s*0/s,
  'the terminal viewport must be allowed to shrink inside the bottom dock'
)

assert.match(
  workspaceSource,
  /ws-btn-icon[^>]*@click="toggleTerminalPanel"/,
  'the terminal toggle must use the compact icon-only button style'
)

assert.match(
  workspaceSource,
  /<button class="ws-btn ws-btn-outline ws-btn-icon"[^>]*@click="toggleTerminalPanel"[^>]*>\s*<svg[\s\S]*?<\/svg>\s*<\/button>/,
  'the terminal toggle must render only its icon'
)

assert.match(
  terminalPanelSource,
  /v-for="host in terminalHosts"/,
  'terminal host elements must be owned by Vue so rerenders cannot detach xterm instances'
)

assert.doesNotMatch(
  terminalPanelSource,
  /document\.createElement\('div'\)/,
  'terminal hosts must not be appended outside Vue lifecycle management'
)

assert.match(
  terminalPanelSource,
  /@click="toggleTheme"/,
  'the terminal toolbar must expose a theme toggle'
)

assert.match(
  terminalPanelSource,
  /projectApi\.terminalCreateSession/,
  'the panel must create managed REST terminal sessions'
)
assert.match(
  terminalPanelSource,
  /projectApi\.terminalRunSession/,
  'the panel must run commands through managed REST terminal sessions'
)
assert.doesNotMatch(
  terminalPanelSource,
  /useTerminalWebSocket/,
  'the panel must not connect to the disabled interactive WebSocket endpoint'
)

assert.match(
  workspaceSource,
  /:is-dark="aiDarkTheme"\s+@toggle-theme="toggleAiTheme"/,
  'terminal colors must follow the workspace theme and update through its existing toggle'
)

assert.match(
  workspaceSource,
  /class="ws-shell"\s*:class="\{ 'ws-dark': aiDarkTheme \}"/,
  'the workspace shell must receive the shared theme state'
)

assert.match(
  workspaceSource,
  /class="ws-terminal-dock"\s*:class="\{ dark: aiDarkTheme \}"/,
  'the terminal dock header must follow the shared theme state'
)

assert.match(
  workspaceSource,
  /<MonacoEditor[^>]*:theme="editorTheme"/,
  'the code editor must switch alongside the workspace theme'
)

assert.match(
  workspaceSource,
  /\.ws-terminal-dock-header\s*\{[^}]*background:\s*#f6f8fa/s,
  'the terminal dock header must be light in the light theme'
)

assert.match(
  workspaceSource,
  /\.ws-terminal-dock\.dark\s+\.ws-terminal-dock-header\s*\{[^}]*background:\s*#252526/s,
  'the terminal dock header must turn dark only in the dark theme'
)

assert.match(
  fileTreeSource,
  /:global\(\[data-theme="dark"\]\)\s+\.ftn-row/,
  'the file tree must respond to the shared dark theme attribute'
)

assert.match(
  fileTreeSource,
  /:global\(\[data-theme="dark"\]\)\s+\.ftn-name\s*\{[^}]*color:\s*#c0caf5/s,
  'file names must use a high-contrast light color in the dark theme'
)

assert.match(
  fileTreeSource,
  /:global\(\[data-theme="dark"\]\)\s+\.ftn-folder-icon svg,[\s\S]*?\.ftn-file-icon svg\s*\{[^}]*stroke:\s*#a9b1d6/s,
  'file and folder icons must use a high-contrast stroke in the dark theme'
)

assert.match(
  workspaceSource,
  /class="ws-center"/,
  'the editor and terminal must share a center-only workspace column'
)

assert.match(
  workspaceSource,
  /class="ws-terminal-resize-handle" @pointerdown="startTerminalResize"/,
  'the terminal must expose a horizontal pointer resize handle'
)

assert.match(
  workspaceSource,
  /title="\u62d6\u52a8\u8c03\u6574\u7ec8\u7aef\u9ad8\u5ea6"/,
  'the terminal resize handle must expose a localized accessible hint'
)

assert.match(
  workspaceSource,
  /:style="\{ height: `\$\{terminalHeight\}px` \}"/,
  'the terminal dock height must be controlled by reactive state'
)

assert.match(
  workspaceSource,
  /function startTerminalResize\(/,
  'the terminal height must be adjustable by pointer dragging'
)

assert.match(
  workspaceSource,
  /\.ws-center\s*\{[^}]*display:\s*flex;[^}]*flex-direction:\s*column/s,
  'the center workspace must stack the editor and terminal vertically without affecting sidebars'
)