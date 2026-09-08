<template>
  <Teleport to="body">
    <Transition name="theme-drawer-transition">
      <div v-if="themeStore.isSettingsOpen" class="theme-drawer-overlay" @click.self="themeStore.closeSettings()">
        <aside ref="drawerRef" class="theme-drawer" role="dialog" aria-modal="true" aria-labelledby="theme-drawer-title" tabindex="-1">
          <header class="theme-drawer__header">
            <div>
              <h2 id="theme-drawer-title">主题设置</h2>
              <p>调整外观和布局以适应您的偏好。</p>
            </div>
            <button type="button" class="theme-drawer__close" aria-label="关闭主题设置" @click="themeStore.closeSettings()">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="m6 6 12 12M18 6 6 18" /></svg>
            </button>
          </header>

          <div class="theme-drawer__body">
            <ThemeSettingsSection
              v-for="section in THEME_SETTINGS_SECTIONS"
              :key="section.key"
              :options="THEME_SETTING_OPTIONS[section.key]"
              :setting="section"
              :value="themeStore.preferences[section.key]"
              @reset="themeStore.resetPreference(section.key)"
              @select="themeStore.updatePreference(section.key, $event)"
            />
          </div>

          <footer class="theme-drawer__footer">
            <button type="button" class="theme-drawer__reset-all" @click="themeStore.resetPreferences()">重置</button>
          </footer>
        </aside>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useThemeStore } from '@/stores/theme'
import { THEME_SETTING_OPTIONS, THEME_SETTINGS_SECTIONS } from '@/theme/theme-options'
import ThemeSettingsSection from './ThemeSettingsSection.vue'

const themeStore = useThemeStore()
const drawerRef = ref(null)

function handleKeydown(event) {
  if (event.key === 'Escape') themeStore.closeSettings()
}

watch(() => themeStore.isSettingsOpen, async isOpen => {
  if (typeof document === 'undefined') return
  if (isOpen) {
    document.addEventListener('keydown', handleKeydown)
    await nextTick()
    drawerRef.value?.focus()
    return
  }
  document.removeEventListener('keydown', handleKeydown)
})

onBeforeUnmount(() => {
  if (typeof document !== 'undefined') document.removeEventListener('keydown', handleKeydown)
})
</script>

<style scoped>
.theme-drawer-overlay { position: fixed; inset: 0; z-index: 5000; display: flex; justify-content: flex-end; background: rgba(0, 0, 0, .46); backdrop-filter: blur(3px); }
.theme-drawer { display: grid; width: min(100vw, 660px); height: 100%; grid-template-rows: auto minmax(0, 1fr) auto; overflow: hidden; border-left: 1px solid var(--theme-panel-border); background: var(--theme-panel); box-shadow: -24px 0 70px rgba(0, 0, 0, .28); color: var(--theme-panel-text); outline: none; }
.theme-drawer__header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 29px 42px 26px; border-bottom: 1px solid var(--theme-panel-border); }
.theme-drawer__header h2 { margin: 0; color: var(--theme-panel-text); font-size: 25px; font-weight: 780; letter-spacing: -.03em; }
.theme-drawer__header p { margin: 7px 0 0; color: var(--theme-panel-text-muted); font-size: 16px; font-weight: 550; }
.theme-drawer__close { display: grid; width: 34px; height: 34px; flex: 0 0 auto; place-items: center; margin: -3px -10px 0 0; border: 0; border-radius: 8px; background: transparent; color: var(--theme-panel-text); cursor: pointer; }
.theme-drawer__close:hover { background: var(--theme-panel-muted); }
.theme-drawer__close:focus-visible { outline: 3px solid var(--theme-accent); outline-offset: 2px; }
.theme-drawer__close svg { width: 22px; height: 22px; }
.theme-drawer__body { min-height: 0; overflow-y: auto; padding: 31px 42px 48px; scrollbar-color: var(--theme-panel-border) transparent; }
.theme-drawer__footer { display: flex; justify-content: flex-end; padding: 24px 42px; border-top: 1px solid var(--theme-panel-border); background: color-mix(in srgb, var(--theme-panel) 94%, black); }
.theme-drawer__reset-all { border: 0; border-radius: calc(var(--theme-radius) * .8); background: color-mix(in srgb, #ea4e4e 24%, var(--theme-panel)); color: #ff7777; padding: 10px 18px; font: inherit; font-size: 15px; font-weight: 700; cursor: pointer; }
.theme-drawer__reset-all:hover { background: color-mix(in srgb, #ea4e4e 34%, var(--theme-panel)); color: #ff9898; }
.theme-drawer__reset-all:focus-visible { outline: 3px solid #ff7777; outline-offset: 3px; }
.theme-drawer-transition-enter-active, .theme-drawer-transition-leave-active { transition: opacity .2s ease; }
.theme-drawer-transition-enter-active .theme-drawer, .theme-drawer-transition-leave-active .theme-drawer { transition: transform .24s ease; }
.theme-drawer-transition-enter-from, .theme-drawer-transition-leave-to { opacity: 0; }
.theme-drawer-transition-enter-from .theme-drawer, .theme-drawer-transition-leave-to .theme-drawer { transform: translateX(100%); }
@media (max-width: 640px) {
  .theme-drawer { width: 100vw; }
  .theme-drawer__header { padding: 18px 20px; }
  .theme-drawer__header h2 { font-size: 20px; }
  .theme-drawer__header p { font-size: 13px; }
  .theme-drawer__body { padding: 20px 16px 36px; }
  .theme-drawer__footer { padding: 16px 20px; }
}
</style>
