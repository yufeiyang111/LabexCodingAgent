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
      :aria-selected="modelValue === mode.key"
      :class="['mode-slider-item', { active: modelValue === mode.key }]"
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
import { ref, computed, onMounted, nextTick, watch } from 'vue'

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

const containerRef = ref(null)
const itemRefs = ref([])
const activeIndex = ref(0)
const sliderWidth = ref(48)
const sliderOffset = ref(0)

function setItemRef(el, index) {
  if (el) {
    itemRefs.value[index] = el
  }
}

const sliderStyle = computed(() => {
  return {
    width: `${sliderWidth.value}px`,
    transform: `translateX(${sliderOffset.value}px)`,
  }
})

function updateSliderPosition(index) {
  nextTick(() => {
    const target = itemRefs.value[index]
    if (target && containerRef.value) {
      sliderWidth.value = target.offsetWidth
      sliderOffset.value = target.offsetLeft - 2
    }
  })
}

function selectMode(key, index) {
  activeIndex.value = index
  updateSliderPosition(index)
  emit('update:modelValue', key)
  emit('change', key)
}

watch(
  () => props.modelValue,
  newVal => {
    const idx = props.modes.findIndex(m => m.key === newVal)
    if (idx !== -1) {
      activeIndex.value = idx
      updateSliderPosition(idx)
    }
  },
  { immediate: true }
)

onMounted(() => {
  const idx = props.modes.findIndex(m => m.key === props.modelValue)
  if (idx !== -1) {
    activeIndex.value = idx
    updateSliderPosition(idx)
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
  left: 2px;
  background: #09090b;
  border-radius: 9999px;
  transition: transform 0.28s cubic-bezier(0.16, 1, 0.3, 1),
              width 0.28s cubic-bezier(0.16, 1, 0.3, 1);
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
</style>
