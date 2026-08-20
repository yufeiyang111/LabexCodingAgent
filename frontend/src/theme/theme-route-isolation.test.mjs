import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const root = new URL('../../', import.meta.url)
const source = path => readFile(new URL(path, root), 'utf8')

test('theme controls and global theme overrides exclude the login route', async () => {
  const [app, launcher, themeStyles, authView] = await Promise.all([
    source('src/App.vue'),
    source('src/components/theme/ThemeSettingsLauncher.vue'),
    source('src/styles/theme.scss'),
    source('src/views/Auth.vue')
  ])

  assert.match(app, /isThemeControlsAvailable/)
  assert.match(app, /route\.name !== 'Login'/)
  assert.match(launcher, /route\.name !== 'Login'/)
  assert.doesNotMatch(themeStyles, /html\[data-theme\] \.login-page/)
  assert.doesNotMatch(themeStyles, /html\[data-theme\] \.auth-area/)
  assert.match(authView, /color-scheme: light/)
})

test('workspace shells publish legacy component tokens from the shared theme palette', async () => {
  const [themeStyles, topbar, userPanel] = await Promise.all([
    source('src/styles/theme.scss'),
    source('src/components/cloud/layout/WorkspaceTopBar.vue'),
    source('src/components/cloud/UserPanel.vue')
  ])

  assert.match(themeStyles, /html\[data-theme\] \.ws-shell[\s\S]*--bg-app: var\(--theme-surface\)/)
  assert.match(themeStyles, /--text-primary: var\(--theme-text\)/)
  assert.match(topbar, /--theme-accent/)
  assert.match(userPanel, /var\(--theme-surface-muted\)/)
})

