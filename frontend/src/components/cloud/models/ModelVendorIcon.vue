<template>
  <div
    class="model-vendor-icon-wrapper"
    :style="wrapperStyle"
    :title="name || 'Vendor Icon'"
  >
    <div
      v-if="svgContent"
      class="model-vendor-icon-svg"
      :style="svgStyle"
      v-html="svgContent"
    ></div>
    <span
      v-else
      class="model-vendor-icon-fallback"
      :style="{ background: accent || '#4b5563' }"
    >
      {{ displayFallbackText }}
    </span>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { getVendorIcon } from '@/assets/icons/models'

const props = defineProps({
  iconKey: {
    type: String,
    default: ''
  },
  name: {
    type: String,
    default: ''
  },
  fallbackText: {
    type: String,
    default: ''
  },
  accent: {
    type: String,
    default: ''
  },
  size: {
    type: [Number, String],
    default: 38
  }
})

const svgContent = computed(() => {
  return getVendorIcon(props.iconKey)
})

const displayFallbackText = computed(() => {
  if (props.fallbackText) return props.fallbackText
  if (props.name) return props.name.slice(0, 2).toUpperCase()
  return 'AI'
})

const wrapperStyle = computed(() => {
  const s = typeof props.size === 'number' ? `${props.size}px` : props.size
  return {
    width: s,
    height: s,
    minWidth: s,
    minHeight: s
  }
})

const svgStyle = computed(() => {
  // 单色 SVG 会继承 currentColor，使用厂商 accent 色或默认深色文本
  return {
    color: props.accent || 'var(--ai-text, #0f172a)'
  }
})
</script>

<style scoped>
.model-vendor-icon-wrapper {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 9px;
  overflow: hidden;
  flex-shrink: 0;
  user-select: none;
  background: var(--ai-card-bg, #f8fafc);
  border: 1px solid var(--ai-border, #e2e8f0);
  box-sizing: border-box;
  padding: 6px;
  transition: all 0.2s ease;
}

.model-vendor-icon-svg {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.model-vendor-icon-svg :deep(svg) {
  width: 100%;
  height: 100%;
  max-width: 100%;
  max-height: 100%;
  display: block;
}

.model-vendor-icon-fallback {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #ffffff;
  font-size: 11px;
  font-weight: 800;
  line-height: 1;
  border-radius: 6px;
}
</style>
