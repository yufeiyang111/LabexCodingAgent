<template>
  <span
    class="project-stack-icon"
    :class="{ 'is-fallback': !icon }"
    :style="chipStyle"
    :title="icon ? icon.name : label"
    role="img"
    :aria-label="icon ? icon.name : label"
  >
    <span v-if="icon" class="project-stack-icon__svg" v-html="icon.svg"></span>
    <span v-else class="project-stack-icon__fallback">{{ fallbackText }}</span>
  </span>
</template>

<script setup>
import { computed } from 'vue'
import { getStackIcon } from '@/assets/icons/stacks'

const props = defineProps({
  /** 技术栈 key，与 assets/icons/stacks/<key>.svg 一一对应 */
  stackKey: {
    type: String,
    default: ''
  },
  /** 芯片边长（px） */
  size: {
    type: [Number, String],
    default: 26
  },
  /** 图标缺失时的兜底文案 */
  label: {
    type: String,
    default: ''
  }
})

const icon = computed(() => getStackIcon(props.stackKey))

const fallbackText = computed(() => {
  const source = props.label || props.stackKey || '?'
  return source.slice(0, 1).toUpperCase()
})

const chipStyle = computed(() => {
  const s = typeof props.size === 'number' ? `${props.size}px` : props.size
  const box = { width: s, height: s, minWidth: s, minHeight: s }

  // 图标缺失时退化为单字母占位，才需要一个可见底色
  if (!icon.value) {
    return {
      ...box,
      background: 'var(--cs-icon-fallback-bg, #64748b)',
      color: '#ffffff',
      borderRadius: `${Math.max(5, Math.round(Number.parseInt(s, 10) * 0.26))}px`
    }
  }

  // 透明底：图形以官方品牌色直接铺满整个尺寸，不套芯片、不加内边距，
  // 保证矢量细节（如 React 细描边）有足够像素可以呈现。
  return { ...box, background: 'transparent', color: icon.value.brandColor }
})
</script>

<style scoped>
.project-stack-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  box-sizing: border-box;
  padding: 0;
  line-height: 0;
  user-select: none;
  overflow: hidden;
}

.project-stack-icon__svg {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.project-stack-icon__svg :deep(svg) {
  width: 100%;
  height: 100%;
  max-width: 100%;
  max-height: 100%;
  display: block;
}

.project-stack-icon__fallback {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 800;
  line-height: 1;
}
</style>
