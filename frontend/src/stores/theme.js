import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  DEFAULT_THEME_PREFERENCES,
  applyThemePreferences,
  loadThemePreferences,
  resetThemePreferences,
  resolveThemeMode,
  saveThemePreferences,
} from '@/theme/theme-preferences'

function browserStorage() {
  return typeof window === 'undefined' ? null : window.localStorage
}

function systemPrefersDark() {
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-color-scheme: dark)').matches
}

export const useThemeStore = defineStore('theme', () => {
  const preferences = ref({ ...DEFAULT_THEME_PREFERENCES })
  const isSettingsOpen = ref(false)
  const effectiveTheme = computed(() => resolveThemeMode(preferences.value.mode, systemPrefersDark()))
  let colorSchemeMediaQuery = null
  let colorSchemeListener = null

  function apply() {
    applyThemePreferences(preferences.value, {
      document: typeof document === 'undefined' ? null : document,
      systemPrefersDark: systemPrefersDark(),
    })
  }

  function persistAndApply() {
    preferences.value = saveThemePreferences(preferences.value, browserStorage())
    apply()
  }

  function subscribeToSystemTheme() {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return
    colorSchemeMediaQuery?.removeEventListener?.('change', colorSchemeListener)
    colorSchemeMediaQuery = window.matchMedia('(prefers-color-scheme: dark)')
    colorSchemeListener = () => {
      if (preferences.value.mode === 'system') apply()
    }
    colorSchemeMediaQuery.addEventListener?.('change', colorSchemeListener)
  }

  function initialize() {
    preferences.value = loadThemePreferences(browserStorage())
    apply()
    subscribeToSystemTheme()
  }

  function updatePreference(key, value) {
    if (!(key in DEFAULT_THEME_PREFERENCES)) return
    preferences.value = { ...preferences.value, [key]: value }
    persistAndApply()
  }

  function resetPreference(key) {
    if (!(key in DEFAULT_THEME_PREFERENCES)) return
    updatePreference(key, DEFAULT_THEME_PREFERENCES[key])
  }

  function resetPreferences() {
    preferences.value = resetThemePreferences(browserStorage())
    apply()
  }

  function openSettings() { isSettingsOpen.value = true }
  function closeSettings() { isSettingsOpen.value = false }
  function toggleLightDark() { updatePreference('mode', effectiveTheme.value === 'dark' ? 'light' : 'dark') }

  return {
    preferences,
    isSettingsOpen,
    effectiveTheme,
    closeSettings,
    initialize,
    openSettings,
    resetPreference,
    resetPreferences,
    toggleLightDark,
    updatePreference,
  }
})

