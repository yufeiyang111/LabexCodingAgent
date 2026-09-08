<template>
  <div class="mode-slider-container" ref="containerRef" role="tablist">
    <div
      class="mode-slider-pill-bg"
      :style="sliderStyle"
    ></div>
    <button
      v-for="(mode, index) in modes"
      :key="mode.key"
      type="button"
      role="tab"
      :aria-selected="currentActiveKey === mode.key"
      :class="['mode-slider-item', { active: currentActiveKey === mode.key }]"
      :ref="el => setItemRef(el, index)"
      @click="selectMode(mode.key, index)"
    >
      <span class="icon mode-item-icon" v-if="mode.icon === 'cube'">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><polyline points="3.27 6.96 12 12.01 20.73 6.96"/><line x1="12" y1="22.08" x2="12" y2="12"/></svg>
      </span>
      <span class="icon mode-item-icon" v-else-if="mode.icon === 'map'">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="1 6 1 22 8 18 16 22 23 18 23 2 16 6 8 2 1 6"/><line x1="8" y1="2" x2="8" y2="18"/><line x1="16" y1="6" x2="16" y2="22"/></svg>
      </span>
      <span class="icon mode-item-icon" v-else-if="mode.icon === 'search'">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
      </span>
      <span>{{ mode.label }}</span>
    </button>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'

const props = defineProps({
  modelValue: {
    type: String,
    default: 'build',
  },
  modes: {
    type: Array,
    default: () => [
      { key: 'build', label: '构建', icon: 'cube' },
      { key: 'plan', label: '规划', icon: 'map' },
      { key: 'explore', label: '探索', icon: 'search' },
    ],
  },
})

const emit = defineEmits(['update:modelValue', 'change'])

const MODE_ALIAS_MAP = {
  scout: 'explore',
  subagent: 'explore',
  general: 'build',
}

const containerRef = ref(null)
const itemRefs = ref([])
const activeIndex = ref(-1)
const hasActive = ref(false)
const sliderWidth = ref(0)
const sliderOffset = ref(0)
let resizeObserver = null

function setItemRef(el, index) {
  if (el) {
    itemRefs.value[index] = el
  }
}

function resolveActiveIndex(val) {
  if (!val || !Array.isArray(props.modes)) return -1
  let idx = props.modes.findIndex(m => m.key === val)
  if (idx === -1 && MODE_ALIAS_MAP[val]) {
    idx = props.modes.findIndex(m => m.key === MODE_ALIAS_MAP[val])
  }
  return idx
}

const currentActiveKey = computed(() => {
  if (activeIndex.value >= 0 && props.modes[activeIndex.value]) {
    return props.modes[activeIndex.value].key
  }
  const resolvedIdx = resolveActiveIndex(props.modelValue)
  return resolvedIdx >= 0 ? props.modes[resolvedIdx].key : ''
})

const sliderStyle = computed(() => {
  if (!hasActive.value || sliderWidth.value <= 0) {
    return {
      opacity: 0,
      pointerEvents: 'none',
    }
  }
  return {
    opacity: 1,
    width: `${sliderWidth.value}px`,
    transform: `translateX(${sliderOffset.value}px)`,
  }
})

function updateSliderPosition(index) {
  if (index < 0) {
    hasActive.value = false
    return
  }
  nextTick(() => {
    const target = itemRefs.value[index]
    const container = containerRef.value
    if (target && container) {
      const cRect = container.getBoundingClientRect()
      const tRect = target.getBoundingClientRect()
      if (tRect.width > 0) {
        sliderWidth.value = tRect.width
        sliderOffset.value = tRect.left - cRect.left
        hasActive.value = true
      } else if (target.offsetWidth > 0) {
        sliderWidth.value = target.offsetWidth
        sliderOffset.value = target.offsetLeft
        hasActive.value = true
      }
    } else {
      hasActive.value = false
    }
  })
}

function selectMode(key, index) {
  activeIndex.value = index
  updateSliderPosition(index)
  emit('update:modelValue', key)
  emit('change', key)
}

function syncPositionFromProp(newVal) {
  const idx = resolveActiveIndex(newVal)
  if (idx !== -1) {
    activeIndex.value = idx
    updateSliderPosition(idx)
  } else {
    activeIndex.value = -1
    hasActive.value = false
  }
}

watch(
  () => props.modelValue,
  newVal => {
    syncPositionFromProp(newVal)
  },
  { immediate: true }
)

watch(
  () => props.modes,
  () => {
    syncPositionFromProp(props.modelValue)
  },
  { deep: true }
)

onMounted(() => {
  syncPositionFromProp(props.modelValue)
  if (typeof ResizeObserver !== 'undefined' && containerRef.value) {
    resizeObserver = new ResizeObserver(() => {
      const idx = resolveActiveIndex(props.modelValue)
      if (idx !== -1) {
        updateSliderPosition(idx)
      }
    })
    resizeObserver.observe(containerRef.value)
  }
})

onBeforeUnmount(() => {
  if (resizeObserver) {
    resizeObserver.disconnect()
    resizeObserver = null
  }
})
</script>

<style scoped>
.mode-slider-container {
  display: inline-flex;
  align-items: center;
  background: #fafafa;
  border: 1px solid #e4e4e7;
  border-radius: 9999px;
  padding: 2px;
  gap: 2px;
  position: relative;
  user-select: none;
}

.mode-slider-pill-bg {
  position: absolute;
  top: 2px;
  bottom: 2px;
  left: 0;
  background: #09090b;
  border-radius: 9999px;
  transition: transform 0.28s cubic-bezier(0.16, 1, 0.3, 1),
              width 0.28s cubic-bezier(0.16, 1, 0.3, 1),
              opacity 0.18s ease;
  z-index: 1;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}

.mode-slider-item {
  position: relative;
  z-index: 2;
  border: none;
  background: transparent;
  padding: 3px 10px;
  font-size: 11.5px;
  font-weight: 500;
  color: #71717a;
  border-radius: 9999px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  transition: color 0.18s ease;
}

.mode-slider-item:hover {
  color: #09090b;
}

.mode-slider-item.active {
  color: #ffffff;
  font-weight: 600;
}

.mode-item-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

/* 暗色主题适配 */
:root[data-theme='dark'] .mode-slider-container {
  background: #181825;
  border-color: #313244;
}

:root[data-theme='dark'] .mode-slider-pill-bg {
  background: #313244;
  border: 1px solid #45475a;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.25);
}

:root[data-theme='dark'] .mode-slider-item {
  color: #a6adc8;
}

:root[data-theme='dark'] .mode-slider-item:hover {
  color: #cdd6f4;
}

:root[data-theme='dark'] .mode-slider-item.active {
  color: #ffffff;
}
</style>
