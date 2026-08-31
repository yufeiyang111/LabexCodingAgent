<template>
  <button
    v-if="ready && route.name !== 'CloudWorkspace'"
    type="button"
    class="tutorials-launcher"
    :class="{ 'tutorials-launcher--dragging': isDragging }"
    :style="positionStyle"
    data-tooltip="使用教程（可拖动）"
    title="使用教程（可拖动）"
    aria-label="打开使用教程，可拖动此按钮调整位置"
    @click="openTutorials"
    @pointerdown="startDrag"
  >
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" aria-hidden="true"><path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H20v16H6.5A2.5 2.5 0 0 0 4 21.5z"/><path d="M4 5.5v16M8 7h8M8 11h8M8 15h5"/></svg>
  </button>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  clampLauncherPosition,
  defaultTutorialsLauncherPosition,
  loadLauncherPosition,
  saveLauncherPosition,
  TUTORIALS_LAUNCHER_POSITION_STORAGE_KEY,
} from '@/theme/theme-launcher-position'

const route = useRoute()
const router = useRouter()
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
  position.value = clampLauncherPosition(
    { x: dragStart.position.x + deltaX, y: dragStart.position.y + deltaY },
    viewportBounds()
  )
}

function handlePointerUp(event) {
  if (event.pointerId !== activePointerId) return
  if (didDrag) {
    position.value = saveLauncherPosition(
      position.value,
      window.localStorage,
      viewportBounds(),
      TUTORIALS_LAUNCHER_POSITION_STORAGE_KEY
    )
  }
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

function openTutorials() {
  if (didDrag) {
    didDrag = false
    return
  }
  router.push({ name: 'Tutorials' })
}

onMounted(() => {
  const bounds = viewportBounds()
  position.value = loadLauncherPosition(window.localStorage, bounds, TUTORIALS_LAUNCHER_POSITION_STORAGE_KEY)
    || defaultTutorialsLauncherPosition(bounds)
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
.tutorials-launcher { position: fixed; z-index: 1200; display: grid; width: 48px; height: 48px; place-items: center; border: 1px solid var(--theme-border); border-radius: 50%; background: var(--theme-surface); box-shadow: var(--theme-shadow); color: var(--theme-text); cursor: grab; touch-action: none; user-select: none; transition: border-color .16s ease, color .16s ease, box-shadow .16s ease; }
.tutorials-launcher:hover { border-color: var(--theme-accent); color: var(--theme-accent); box-shadow: 0 10px 28px color-mix(in srgb, var(--theme-accent) 24%, transparent); }
.tutorials-launcher--dragging { cursor: grabbing; transition: none; }
.tutorials-launcher:focus-visible { outline: 3px solid color-mix(in srgb, var(--theme-accent) 56%, transparent); outline-offset: 3px; }
.tutorials-launcher svg { width: 20px; height: 20px; pointer-events: none; }
.tutorials-launcher::after { position: absolute; right: calc(100% + 10px); width: max-content; max-width: 180px; border-radius: calc(var(--theme-radius) * .7); background: var(--theme-text); box-shadow: var(--theme-shadow); color: var(--theme-surface); content: attr(data-tooltip); font-size: 12px; font-weight: 650; opacity: 0; padding: 7px 9px; pointer-events: none; transform: translateX(4px); transition: opacity .15s ease, transform .15s ease; }
.tutorials-launcher:hover::after, .tutorials-launcher:focus-visible::after { opacity: 1; transform: translateX(0); }
@media (max-width: 700px) { .tutorials-launcher { width: 44px; height: 44px; } }
</style>
