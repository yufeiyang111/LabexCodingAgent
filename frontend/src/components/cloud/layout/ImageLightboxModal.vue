<template>
  <Teleport to="body">
    <Transition name="lightbox-fade">
      <div
        v-if="visible"
        class="image-lightbox-overlay"
        @click="close"
      >
        <div class="lightbox-card" @click.stop>
          <div class="lightbox-header">
            <div class="lightbox-title-wrap">
              <span class="icon">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>
              </span>
              <span class="lightbox-title">{{ title || '图片预览' }}</span>
              <span class="lightbox-hint">滚轮缩放 · 双击复位</span>
            </div>
            <button class="lightbox-close-btn" @click="close" title="关闭 (Esc)">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
            </button>
          </div>
          <div ref="bodyEl" class="lightbox-body" title="滚轮缩放 · 双击复位" @wheel="onWheel" @dblclick="onDblClick">
            <img
              ref="imgEl"
              :src="src"
              :alt="title || 'Preview'"
              class="lightbox-img"
              @load="onImgLoad"
            />
            <Transition name="zoom-badge-pop">
              <span v-if="zoomPercent !== null" class="lightbox-zoom-badge">{{ zoomPercent }}%</span>
            </Transition>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({
  visible: {
    type: Boolean,
    default: false,
  },
  src: {
    type: String,
    default: '',
  },
  title: {
    type: String,
    default: '',
  },
})

const emit = defineEmits(['update:visible', 'close'])

const MIN_SCALE = 1
const MAX_SCALE = 8
const WHEEL_ZOOM_FACTOR = 1.2
const DOUBLE_CLICK_SCALE = 2

const bodyEl = ref(null)
const imgEl = ref(null)
const zoomPercent = ref(null)

// 适配态基准尺寸（scale=1 时图片的显示宽高）；缓存避免每次滚轮触发布局读取。
let baseSize = null
let currentScale = 1

function close() {
  emit('update:visible', false)
  emit('close')
}

function handleKeydown(e) {
  if (e.key === 'Escape' && props.visible) {
    close()
  }
}

/** 清除显式尺寸并测量适配态基准；仅在打开、换图、加载完成后各执行一次。 */
function measureBaseSize() {
  const img = imgEl.value
  if (!img) return
  img.style.width = ''
  img.style.height = ''
  baseSize = null
  currentScale = 1
  zoomPercent.value = null
  const rect = img.getBoundingClientRect()
  if (rect.width > 0 && rect.height > 0) {
    baseSize = { width: rect.width, height: rect.height }
  }
}

function onImgLoad() {
  measureBaseSize()
}

function setScaledSize(scale) {
  const img = imgEl.value
  if (!img || !baseSize) return
  if (scale <= MIN_SCALE + 1e-6) {
    // 回到适配态：交给 CSS 的 max-width/max-height contain 规则。
    img.style.width = ''
    img.style.height = ''
  } else {
    img.style.width = `${baseSize.width * scale}px`
    img.style.height = `${baseSize.height * scale}px`
  }
}

/**
 * 以给定锚点（相对滚动容器的坐标）为中心缩放。
 * 锚点下的图像点在新尺寸下保持屏幕位置不变：newScroll = oldContentPoint * k - anchor。
 */
function zoomTo(nextScale, anchorX, anchorY) {
  const body = bodyEl.value
  if (!body || !baseSize) return
  const next = Math.min(MAX_SCALE, Math.max(MIN_SCALE, nextScale))
  if (Math.abs(next - currentScale) < 1e-6) return
  const k = next / currentScale
  const contentX = body.scrollLeft + anchorX
  const contentY = body.scrollTop + anchorY
  currentScale = next
  setScaledSize(currentScale)
  body.scrollLeft = contentX * k - anchorX
  body.scrollTop = contentY * k - anchorY
  if (currentScale <= MIN_SCALE + 1e-6) {
    body.scrollLeft = 0
    body.scrollTop = 0
    zoomPercent.value = null
  } else {
    zoomPercent.value = Math.round(currentScale * 100)
  }
}

function onWheel(e) {
  if (!props.visible) return
  // 阻止页面级滚动穿透；元素级 wheel 监听默认非 passive，可安全取消默认行为。
  e.preventDefault()
  const body = bodyEl.value
  if (!body) return
  const rect = body.getBoundingClientRect()
  const factor = e.deltaY < 0 ? WHEEL_ZOOM_FACTOR : 1 / WHEEL_ZOOM_FACTOR
  zoomTo(currentScale * factor, e.clientX - rect.left, e.clientY - rect.top)
}

function onDblClick(e) {
  if (!props.visible) return
  const body = bodyEl.value
  if (!body) return
  if (currentScale > MIN_SCALE + 1e-6) {
    zoomTo(MIN_SCALE, 0, 0)
  } else {
    const rect = body.getBoundingClientRect()
    zoomTo(DOUBLE_CLICK_SCALE, e.clientX - rect.left, e.clientY - rect.top)
  }
}

function resetAndMeasure() {
  if (!props.visible) return
  nextTick(() => {
    const img = imgEl.value
    if (!img) return
    if (img.complete && img.naturalWidth > 0) {
      measureBaseSize()
    } else {
      // 未加载完成时先复位徽标与缩放；加载完成由 @load 再次测量。
      currentScale = 1
      zoomPercent.value = null
    }
  })
}

watch(
  () => [props.visible, props.src],
  () => resetAndMeasure(),
)

onMounted(() => {
  window.addEventListener('keydown', handleKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleKeydown)
})
</script>

<style scoped>
.image-lightbox-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.65);
  backdrop-filter: blur(6px);
  z-index: 2100;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}

.lightbox-card {
  max-width: 90vw;
  max-height: 88vh;
  background: #ffffff;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 20px 40px -4px rgba(0, 0, 0, 0.2), 0 0 0 1px rgba(0, 0, 0, 0.08);
  display: flex;
  flex-direction: column;
}

.lightbox-header {
  padding: 10px 14px;
  background: #fafafa;
  border-bottom: 1px solid #e4e4e7;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.lightbox-title-wrap {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12.5px;
  font-weight: 600;
  color: #09090b;
}

.lightbox-hint {
  font-size: 11px;
  font-weight: 400;
  color: #a1a1aa;
}

.lightbox-close-btn {
  width: 24px;
  height: 24px;
  border-radius: 4px;
  border: none;
  background: transparent;
  color: #71717a;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.12s;
}

.lightbox-close-btn:hover {
  background: #f4f4f5;
  color: #09090b;
}

/* margin:auto 而非 flex 居中：放大后内容超出容器时四边仍可滚动到达（flex 居中会裁掉上边）。 */
.lightbox-body {
  position: relative;
  padding: 16px;
  overflow: auto;
  display: flex;
}

.lightbox-img {
  max-width: 100%;
  max-height: 75vh;
  object-fit: contain;
  border-radius: 6px;
  margin: auto;
  will-change: width, height;
}

.lightbox-zoom-badge {
  position: sticky;
  right: 8px;
  bottom: 8px;
  margin-left: auto;
  align-self: flex-end;
  padding: 3px 8px;
  border-radius: 10px;
  background: rgba(15, 23, 42, 0.78);
  color: #fafafa;
  font-size: 11px;
  line-height: 1.4;
  pointer-events: none;
}

.icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

/* Transitions */
.lightbox-fade-enter-active,
.lightbox-fade-leave-active {
  transition: opacity 0.22s ease;
}

.lightbox-fade-enter-from,
.lightbox-fade-leave-to {
  opacity: 0;
}

.lightbox-fade-enter-active .lightbox-card,
.lightbox-fade-leave-active .lightbox-card {
  transition: transform 0.25s cubic-bezier(0.16, 1, 0.3, 1);
}

.lightbox-fade-enter-from .lightbox-card,
.lightbox-fade-leave-to .lightbox-card {
  transform: scale(0.95);
}

.zoom-badge-pop-enter-active,
.zoom-badge-pop-leave-active {
  transition: opacity 0.15s ease;
}

.zoom-badge-pop-enter-from,
.zoom-badge-pop-leave-to {
  opacity: 0;
}
</style>
