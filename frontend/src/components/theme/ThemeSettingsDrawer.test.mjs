import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'

const drawerPath = new URL('./ThemeSettingsDrawer.vue', import.meta.url)
const appPath = new URL('../../App.vue', import.meta.url)
const workspacePath = new URL('../../views/CloudWorkspace.vue', import.meta.url)

test('global theme drawer exposes every requested settings section and reset action', async () => {
  const source = await readFile(drawerPath, 'utf8')
  for (const expected of ['主题设置', 'ThemeSettingsSection', 'THEME_SETTINGS_SECTIONS', 'themeStore.resetPreferences()', "event.key === 'Escape'", 'aria-modal="true"']) {
    assert.ok(source.includes(expected), `expected ${expected}`)
  }
})

test('theme controls are mounted globally and workspace opens the shared drawer', async () => {
  const [app, workspace] = await Promise.all([readFile(appPath, 'utf8'), readFile(workspacePath, 'utf8')])
  assert.ok(app.includes('<ThemeSettingsLauncher />'))
  assert.ok(app.includes('<ThemeSettingsDrawer />'))
  assert.ok(workspace.includes('themeStore.openSettings()'))
  assert.ok(workspace.includes('useThemeStore'))
  assert.ok(!workspace.includes('<!-- Settings Modal -->'))
})

test('theme launcher is compact, draggable, persistent, and hidden in the workspace', async () => {
  const source = await readFile(new URL('./ThemeSettingsLauncher.vue', import.meta.url), 'utf8')
  for (const expected of ["route.name !== 'CloudWorkspace'", 'pointerdown', 'handlePointerMove', 'saveLauncherPosition', 'touch-action: none', '可拖动']) {
    assert.ok(source.includes(expected), `expected ${expected}`)
  }
})

test('workspace exposes a non-floating top-bar theme button', async () => {
  const source = await readFile(workspacePath, 'utf8')
  for (const expected of ['class="ws-btn ws-btn-outline ws-btn-sm ws-theme-settings-btn"', '主题设置', '<span>主题</span>', '@click="themeStore.openSettings()"']) {
    assert.ok(source.includes(expected), `expected ${expected}`)
  }
})
