import { THEME_SETTING_OPTIONS } from './theme-options.js'

export const THEME_STORAGE_KEY = 'labex-theme-preferences.v1'

export const DEFAULT_THEME_PREFERENCES = Object.freeze({
  mode: 'light',
  colorPreset: 'default',
  fontFamily: 'auto',
  radius: 'auto',
  density: 'default',
  sidebar: 'embedded',
  layout: 'default',
  contentWidth: 'full',
  direction: 'ltr',
})

const preferenceKeys = Object.keys(DEFAULT_THEME_PREFERENCES)
const allowedValues = Object.fromEntries(
  preferenceKeys.map(key => [key, new Set((THEME_SETTING_OPTIONS[key] || []).map(option => option.value))])
)
const densityScales = Object.freeze({ compact: '0.9', default: '1', relaxed: '1.08', spacious: '1.18' })
const messageGaps = Object.freeze({ compact: '10px', default: '16px', relaxed: '22px', spacious: '28px' })

function readStorage(storage, key) {
  try { return storage?.getItem?.(key) ?? null } catch { return null }
}

function writeStorage(storage, key, value) {
  try { storage?.setItem?.(key, value) } catch {
    // Preferences are optional UI state; privacy or quota failures must not block the app.
  }
}

export function normalizeThemePreferences(candidate) {
  const source = candidate && typeof candidate === 'object' ? candidate : {}
  return preferenceKeys.reduce((normalized, key) => {
    normalized[key] = allowedValues[key]?.has(source[key]) ? source[key] : DEFAULT_THEME_PREFERENCES[key]
    return normalized
  }, {})
}

function legacyPreferences(storage) {
  const legacyTheme = readStorage(storage, 'labex-ai-theme')
  const legacyDensity = readStorage(storage, 'labex-ai-density')
  const legacyFontSize = readStorage(storage, 'labex-ai-fontsize')
  if (!legacyTheme && !legacyDensity && !legacyFontSize) return null

  const migrated = normalizeThemePreferences({
    mode: legacyTheme,
    density: legacyDensity === 'comfortable' ? 'default' : legacyDensity,
  })
  if (legacyFontSize === 'large') migrated.fontFamily = 'sans'
  return migrated
}

export function loadThemePreferences(storage) {
  const raw = readStorage(storage, THEME_STORAGE_KEY)
  if (raw) {
    try { return normalizeThemePreferences(JSON.parse(raw)) } catch { return { ...DEFAULT_THEME_PREFERENCES } }
  }

  const migrated = legacyPreferences(storage)
  return migrated ? saveThemePreferences(migrated, storage) : { ...DEFAULT_THEME_PREFERENCES }
}

export function saveThemePreferences(preferences, storage) {
  const normalized = normalizeThemePreferences(preferences)
  writeStorage(storage, THEME_STORAGE_KEY, JSON.stringify(normalized))
  return normalized
}

export function resetThemePreferences(storage) {
  return saveThemePreferences(DEFAULT_THEME_PREFERENCES, storage)
}

export function resolveThemeMode(mode, systemPrefersDark = false) {
  if (mode === 'dark') return 'dark'
  if (mode === 'light') return 'light'
  return systemPrefersDark ? 'dark' : 'light'
}

export function applyThemePreferences(preferences, { document, systemPrefersDark = false } = {}) {
  const normalized = normalizeThemePreferences(preferences)
  const root = document?.documentElement
  if (!root) return normalized

  root.setAttribute('data-theme', resolveThemeMode(normalized.mode, systemPrefersDark))
  root.setAttribute('data-theme-mode', normalized.mode)
  root.setAttribute('data-color-preset', normalized.colorPreset)
  root.setAttribute('data-font-family', normalized.fontFamily)
  root.setAttribute('data-radius', normalized.radius)
  root.setAttribute('data-density', normalized.density)
  root.setAttribute('data-sidebar-layout', normalized.sidebar)
  root.setAttribute('data-app-layout', normalized.layout)
  root.setAttribute('data-content-width', normalized.contentWidth)
  root.dir = normalized.direction
  root.style?.setProperty?.('--theme-density-scale', densityScales[normalized.density])
  root.style?.setProperty?.('--ai-msg-gap', messageGaps[normalized.density])
  return normalized
}

