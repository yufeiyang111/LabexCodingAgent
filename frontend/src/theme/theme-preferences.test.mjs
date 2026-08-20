import assert from 'node:assert/strict'
import test from 'node:test'

import {
  DEFAULT_THEME_PREFERENCES,
  THEME_STORAGE_KEY,
  applyThemePreferences,
  loadThemePreferences,
  normalizeThemePreferences,
  resetThemePreferences,
  resolveThemeMode,
  saveThemePreferences,
} from './theme-preferences.js'

function createStorage(initial = {}) {
  const values = new Map(Object.entries(initial))
  return {
    getItem(key) { return values.has(key) ? values.get(key) : null },
    removeItem(key) { values.delete(key) },
    setItem(key, value) { values.set(key, String(value)) },
  }
}

function createDocument() {
  const attributes = new Map()
  const styles = new Map()
  return {
    documentElement: {
      dir: '',
      setAttribute(name, value) { attributes.set(name, value) },
      getAttribute(name) { return attributes.get(name) ?? null },
      style: {
        setProperty(name, value) { styles.set(name, value) },
        getPropertyValue(name) { return styles.get(name) ?? '' },
      },
    },
  }
}

test('uses light mode as the default application base', () => {
  assert.equal(DEFAULT_THEME_PREFERENCES.mode, 'light')
})

test('normalizes invalid or partial stored preferences to safe defaults', () => {
  const normalized = normalizeThemePreferences({
    mode: 'dark',
    colorPreset: 'not-a-preset',
    density: 'relaxed',
    direction: 'rtl',
  })

  assert.equal(normalized.mode, 'dark')
  assert.equal(normalized.colorPreset, DEFAULT_THEME_PREFERENCES.colorPreset)
  assert.equal(normalized.density, 'relaxed')
  assert.equal(normalized.direction, 'rtl')
  assert.equal(normalized.fontFamily, DEFAULT_THEME_PREFERENCES.fontFamily)
})

test('migrates legacy workspace values once when no versioned record exists', () => {
  const storage = createStorage({
    'labex-ai-theme': 'dark',
    'labex-ai-fontsize': 'large',
    'labex-ai-density': 'compact',
  })

  const preferences = loadThemePreferences(storage)

  assert.equal(preferences.mode, 'dark')
  assert.equal(preferences.density, 'compact')
  assert.equal(preferences.fontFamily, 'sans')
  assert.deepEqual(JSON.parse(storage.getItem(THEME_STORAGE_KEY)), preferences)
})

test('saves a normalized copy under the single versioned key', () => {
  const storage = createStorage()
  const saved = saveThemePreferences({ ...DEFAULT_THEME_PREFERENCES, radius: '0.75' }, storage)

  assert.equal(saved.radius, '0.75')
  assert.deepEqual(JSON.parse(storage.getItem(THEME_STORAGE_KEY)), saved)
})

test('applies resolved root attributes, direction, and density variables', () => {
  const document = createDocument()
  const preferences = {
    ...DEFAULT_THEME_PREFERENCES,
    mode: 'system',
    colorPreset: 'ocean',
    fontFamily: 'serif',
    radius: '0.5',
    density: 'spacious',
    sidebar: 'floating',
    layout: 'compact',
    contentWidth: 'centered',
    direction: 'rtl',
  }

  applyThemePreferences(preferences, { document, systemPrefersDark: true })

  assert.equal(document.documentElement.getAttribute('data-theme'), 'dark')
  assert.equal(document.documentElement.getAttribute('data-theme-mode'), 'system')
  assert.equal(document.documentElement.getAttribute('data-color-preset'), 'ocean')
  assert.equal(document.documentElement.getAttribute('data-font-family'), 'serif')
  assert.equal(document.documentElement.getAttribute('data-radius'), '0.5')
  assert.equal(document.documentElement.getAttribute('data-density'), 'spacious')
  assert.equal(document.documentElement.getAttribute('data-sidebar-layout'), 'floating')
  assert.equal(document.documentElement.getAttribute('data-app-layout'), 'compact')
  assert.equal(document.documentElement.getAttribute('data-content-width'), 'centered')
  assert.equal(document.documentElement.dir, 'rtl')
  assert.equal(document.documentElement.style.getPropertyValue('--theme-density-scale'), '1.18')
})

test('resolves system mode without requiring window APIs', () => {
  assert.equal(resolveThemeMode('light', true), 'light')
  assert.equal(resolveThemeMode('dark', false), 'dark')
  assert.equal(resolveThemeMode('system', true), 'dark')
  assert.equal(resolveThemeMode('system', false), 'light')
})

test('resets the persisted preference record to documented defaults', () => {
  const storage = createStorage({ [THEME_STORAGE_KEY]: JSON.stringify({ mode: 'dark', density: 'spacious' }) })
  const reset = resetThemePreferences(storage)

  assert.deepEqual(reset, DEFAULT_THEME_PREFERENCES)
  assert.deepEqual(JSON.parse(storage.getItem(THEME_STORAGE_KEY)), DEFAULT_THEME_PREFERENCES)
})
