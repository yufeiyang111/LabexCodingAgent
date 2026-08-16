<template>
  <div
    class="dynamic-resizer"
    :class="[direction, { resizing: isResizing }]"
    :title="resizerTitle"
    @pointerdown="onPointerDown"
  ></div>
</template>

<script setup>
import { ref, computed } from 'vue'

const props = defineProps({
  direction: {
    type: String,
    default: 'vertical', // 'vertical' (col-resize) | 'horizontal' (row-resize)
  },
  min: {
    type: Number,
    default: 100,
  },
  max: {
    type: Number,
    default: 800,
  },
  invert: {
    type: Boolean,
    default: false, // For right panel or bottom panel where delta is inverted
  },
})

const emit = defineEmits(['resize', 'resize-start', 'resize-end'])

const isResizing = ref(false)
let startPos = 0

const resizerTitle = computed(() => {
  return props.direction === 'vertical' ? '拖拽调整宽度' : '拖拽调整高度'
})

function onPointerDown(e) {
  isResizing.value = true
  startPos = props.direction === 'vertical' ? e.clientX : e.clientY
  emit('resize-start')

  document.body.style.userSelect = 'none'
  document.body.style.cursor = props.direction === 'vertical' ? 'col-resize' : 'row-resize'

  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp)
}

function onPointerMove(e) {
  if (!isResizing.value) return
  const currentPos = props.direction === 'vertical' ? e.clientX : e.clientY
  const delta = props.invert ? startPos - currentPos : currentPos - startPos
  emit('resize', delta, currentPos)
}

function onPointerUp() {
  if (!isResizing.value) return
  isResizing.value = false
  document.body.style.userSelect = ''
  document.body.style.cursor = ''
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
  emit('resize-end')
}
</script>

<style scoped>
.dynamic-resizer {
  position: relative;
  z-index: 40;
  background: transparent;
  transition: background 0.15s ease;
  user-select: none;
  touch-action: none;
}

.dynamic-resizer.vertical {
  width: 4px;
  margin: 0 -2px;
  cursor: col-resize;
  height: 100%;
}

.dynamic-resizer.horizontal {
  height: 4px;
  margin: -2px 0;
  cursor: row-resize;
  width: 100%;
}

.dynamic-resizer:hover,
.dynamic-resizer.resizing {
  background: #09090b;
}
</style>
