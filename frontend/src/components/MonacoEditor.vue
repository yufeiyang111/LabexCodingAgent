<template>
  <div ref="wrapperRef" class="monaco-wrapper" :style="{ height }">
    <VueMonacoEditor
      :value="modelValue"
      :language="language"
      :theme="theme"
      :options="editorOptions"
      @update:value="$emit('update:modelValue', $event)"
      @mount="handleMount"
    />
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { loader, VueMonacoEditor } from '@guolao/vue-monaco-editor'
import * as monaco from 'monaco-editor'

loader.config({ monaco })

if (typeof window !== 'undefined' && !window.MonacoEnvironment) {
  window.MonacoEnvironment = {
    getWorker(_, label) {
      return null
    }
  }
}

const props = defineProps({
  modelValue: { type: String, default: '' },
  language: { type: String, default: 'plaintext' },
  height: { type: String, default: '400px' },
  readOnly: { type: Boolean, default: false },
  theme: { type: String, default: 'vs' }
})

const emit = defineEmits(['update:modelValue', 'mount'])

/**
 * 性能关键点：automaticLayout 必须关闭。
 * automaticLayout: true 会让 Monaco 内部用 ResizeObserver 对容器尺寸变化做「同步」完整 layout，
 * 侧边栏/预览面板拖拽时每帧宽度变化 1px 都会触发一次全量重排（wordWrap: 'on' 下还要重算所有可见行折行），
 * 单次可达 5-30ms+，是拖拽掉帧的最大放大器。
 *
 * 替代方案：组件自管尺寸观察 + rAF 合并 + 「拖拽态抑制」：
 *  - 拖拽进行中（body 上有 is-resizing-* 标记）：合入 rAF，最多每帧一次；多次采样只取最后一次，避免同步重排风暴；
 *  - 拖拽结束后：由拖拽结束回调（或 MutationObserver 检测标记移除）强制做一次精确 layout；
 *  - 严格避免「读 offsetWidth/clientWidth 等强制同步布局」的操作与写入交替出现。
 */
let editorInstance = null
let resizeObserver = null
let rafPending = false
let dragObserver = null
let pendingPanel = null

const wrapperRef = ref(null)

const RESIZE_BODY_CLASSES = ['is-resizing-sidebar', 'is-resizing-ai', 'is-resizing-preview']

const editorOptions = computed(() => ({
  minimap: { enabled: false },
  lineNumbers: 'on',
  scrollBeyondLastLine: false,
  automaticLayout: false,
  fontSize: 13,
  tabSize: 2,
  readOnly: props.readOnly,
  wordWrap: 'on',
  padding: { top: 8 }
}))

function isDragging() {
  return RESIZE_BODY_CLASSES.some(cls => document.body?.classList.contains(cls))
}

function scheduleLayout(force = false) {
  if (!editorInstance || rafPending) return
  if (!force && isDragging()) return // 拖拽中依靠结束回调精确重排，避免同步风暴
  rafPending = true
  requestAnimationFrame(() => {
    rafPending = false
    if (!editorInstance) return
    if (!force && isDragging()) return
    editorInstance.layout()
  })
}

/** 拖拽结束（body 标记移除）后强制精确布局一次，保证停靠宽度像素级正确 */
function ensureFinalLayout() {
  requestAnimationFrame(() => {
    if (editorInstance && !isDragging()) editorInstance.layout()
  })
}

function handleMount(editor) {
  editorInstance = editor
  emit('mount', editor)
  const wrapper = wrapperRef.value
  if (wrapper && typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => scheduleLayout(false))
    resizeObserver.observe(wrapper)
    // 监听拖拽标记的移除：任何面板拖拽结束时立即做一次精确 layout
    dragObserver = new MutationObserver(() => {
      if (!isDragging() && editorInstance) ensureFinalLayout()
    })
    dragObserver.observe(document.body, { attributes: true, attributeFilter: ['class'] })
  }
  scheduleLayout(true)
}

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  dragObserver?.disconnect()
  resizeObserver = null
  dragObserver = null
  editorInstance = null
})

defineExpose({ layout: () => scheduleLayout(true) })
</script>

<style scoped>
.monaco-wrapper {
  width: 100%;
  position: relative;
  overflow: hidden;
  /* 声明独立的渲染上下文，宽度变化时把编辑器重排的 paint 成本隔离在局部 */
  contain: layout paint;
}
</style>
