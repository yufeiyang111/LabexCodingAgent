<template>
  <button
    v-if="ready && route.name !== 'CloudWorkspace' && route.name !== 'Login'"
    type="button"
    class="theme-settings-launcher"
    :class="{ 'theme-settings-launcher--dragging': isDragging }"
    :style="positionStyle"
    :data-tooltip="'主题设置（可拖动）'"
    title="主题设置（可拖动）"
    aria-label="打开主题设置，可拖动此按钮调整位置"
    @click="openSettings"
    @pointerdown="startDrag"
  >
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
  </button>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useThemeStore } from '@/stores/theme'
import {
  clampLauncherPosition,
  defaultLauncherPosition,
  loadLauncherPosition,
  saveLauncherPosition,
} from '@/theme/theme-launcher-position'

const themeStore = useThemeStore()
const route = useRoute()
const ready = ref(false)
const isDragging = ref(false)
const position = ref({ x: 12, y: 12 })
let activePointerId = null
let dragStart = null
let didDrag = false

const positionStyle = computed(() => ({ left: `${position.value.x}px`, top: `${position.value.y}px` }))

function viewportBounds() {
  return { width: window.innerWidth, height: window.innerHeight }
}

function startDrag(event) {
  if (event.button !== 0) return
  activePointerId = event.pointerId
  dragStart = { x: event.clientX, y: event.clientY, position: { ...position.value } }
  didDrag = false
  isDragging.value = true
  window.addEventListener('pointermove', handlePointerMove)
  window.addEventListener('pointerup', handlePointerUp)
  window.addEventListener('pointercancel', handlePointerUp)
}

function handlePointerMove(event) {
  if (event.pointerId !== activePointerId || !dragStart) return
  const deltaX = event.clientX - dragStart.x
  const deltaY = event.clientY - dragStart.y
  if (Math.abs(deltaX) > 4 || Math.abs(deltaY) > 4) didDrag = true
  if (!didDrag) return
  position.value = clampLauncherPosition({ x: dragStart.position.x + deltaX, y: dragStart.position.y + deltaY }, viewportBounds())
}

function handlePointerUp(event) {
  if (event.pointerId !== activePointerId) return
  if (didDrag) position.value = saveLauncherPosition(position.value, window.localStorage, viewportBounds())
  activePointerId = null
  dragStart = null
  isDragging.value = false
  window.removeEventListener('pointermove', handlePointerMove)
  window.removeEventListener('pointerup', handlePointerUp)
  window.removeEventListener('pointercancel', handlePointerUp)
}

function handleResize() {
  position.value = clampLauncherPosition(position.value, viewportBounds())
}

function openSettings() {
  if (didDrag) {
    didDrag = false
    return
  }
  themeStore.openSettings()
}

onMounted(() => {
  position.value = loadLauncherPosition(window.localStorage, viewportBounds()) || defaultLauncherPosition(viewportBounds())
  ready.value = true
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('pointermove', handlePointerMove)
  window.removeEventListener('pointerup', handlePointerUp)
  window.removeEventListener('pointercancel', handlePointerUp)
  window.removeEventListener('resize', handleResize)
})
</script>

<style scoped>
.theme-settings-launcher { position: fixed; z-index: 1200; display: grid; width: 48px; height: 48px; place-items: center; border: 1px solid var(--theme-border); border-radius: 50%; background: var(--theme-surface); box-shadow: var(--theme-shadow); color: var(--theme-text); cursor: grab; touch-action: none; user-select: none; transition: border-color .16s ease, color .16s ease, box-shadow .16s ease; }
.theme-settings-launcher:hover { border-color: var(--theme-accent); color: var(--theme-accent); box-shadow: 0 10px 28px color-mix(in srgb, var(--theme-accent) 24%, transparent); }
.theme-settings-launcher--dragging { cursor: grabbing; transition: none; }
.theme-settings-launcher:focus-visible { outline: 3px solid color-mix(in srgb, var(--theme-accent) 56%, transparent); outline-offset: 3px; }
.theme-settings-launcher svg { width: 20px; height: 20px; pointer-events: none; }
.theme-settings-launcher::after { position: absolute; right: calc(100% + 10px); width: max-content; max-width: 180px; border-radius: calc(var(--theme-radius) * .7); background: var(--theme-text); box-shadow: var(--theme-shadow); color: var(--theme-surface); content: attr(data-tooltip); font-size: 12px; font-weight: 650; opacity: 0; padding: 7px 9px; pointer-events: none; transform: translateX(4px); transition: opacity .15s ease, transform .15s ease; }
.theme-settings-launcher:hover::after, .theme-settings-launcher:focus-visible::after { opacity: 1; transform: translateX(0); }
@media (max-width: 700px) { .theme-settings-launcher { width: 44px; height: 44px; } }
</style>
