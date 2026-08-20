<template>
  <div class="web-preview-panel" :class="{ 'is-dark': isDark }">
    <!-- 顶部浏览器控制栏 (纯净 SVG 图标体系) -->
    <header class="wp-toolbar">
      <div class="wp-toolbar-left">
        <!-- 实时服务指示灯 -->
        <div class="wp-status-indicator" title="实时预览服务运行中">
          <span class="wp-status-dot"></span>
          <span class="wp-status-text">LIVE PREVIEW</span>
        </div>

        <!-- 刷新按钮 -->
        <button
          type="button"
          class="wp-tool-btn"
          :class="{ 'is-refreshing': isRefreshing }"
          @click="reloadFrame"
          title="重新加载 (Auto Live Reload)"
        >
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M23 4v6h-6"/><path d="M1 20v-6h6"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></svg>
        </button>

        <!-- 视口尺寸切换 (Desktop / Tablet / Mobile) -->
        <div class="wp-viewport-group">
          <button
            type="button"
            class="wp-viewport-btn"
            :class="{ active: currentViewport === 'responsive' }"
            @click="currentViewport = 'responsive'"
            title="自适应全宽 (Responsive)"
          >
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2" ry="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>
          </button>
          <button
            type="button"
            class="wp-viewport-btn"
            :class="{ active: currentViewport === 'tablet' }"
            @click="currentViewport = 'tablet'"
            title="平板视口 (768px)"
          >
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="4" y="2" width="16" height="20" rx="2" ry="2"/><line x1="12" y1="18" x2="12.01" y2="18"/></svg>
          </button>
          <button
            type="button"
            class="wp-viewport-btn"
            :class="{ active: currentViewport === 'mobile' }"
            @click="currentViewport = 'mobile'"
            title="移动端视口 (375px)"
          >
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="5" y="2" width="14" height="20" rx="2" ry="2"/><line x1="12" y1="18" x2="12.01" y2="18"/></svg>
          </button>
        </div>
      </div>

      <!-- 地址栏 -->
      <div class="wp-address-bar">
        <svg class="wp-addr-icon" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/></svg>
        <input
          v-model="inputUrl"
          type="text"
          class="wp-addr-input"
          placeholder="输入预览路径，例如 http://localhost:3000..."
          @keydown.enter="applyUrlChange"
        />
      </div>

      <!-- 顶部右侧操作 -->
      <div class="wp-toolbar-right">
        <!-- 在独立浏览器标签页打开 -->
        <a
          :href="effectiveUrl"
          target="_blank"
          class="wp-tool-btn"
          title="在新标签页中打开"
        >
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/></svg>
        </a>

        <!-- 关闭视窗 -->
        <button
          type="button"
          class="wp-tool-btn wp-close-btn"
          @click="$emit('close')"
          title="关闭实时预览视窗"
        >
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
        </button>
      </div>
    </header>

    <!-- 预览核心容器 -->
    <main class="wp-viewport-stage" :class="'viewport-' + currentViewport">
      <div class="wp-frame-wrapper" :style="wrapperStyle">
        <iframe
          ref="iframeRef"
          :key="reloadKey"
          :src="effectiveUrl"
          class="wp-iframe"
          sandbox="allow-scripts allow-same-origin allow-forms allow-popups allow-modals allow-downloads"
          @load="onFrameLoad"
        ></iframe>

        <!-- 加载中指示器 -->
        <div v-if="loading" class="wp-loading-overlay">
          <div class="wp-loading-spinner">
            <svg class="wp-spin-icon" width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="#2563eb" stroke-width="2.5"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg>
            <span class="wp-loading-text">正在加载 Web 实时渲染...</span>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'

const props = defineProps({
  url: {
    type: String,
    default: 'http://localhost:3000'
  },
  refreshKey: {
    type: Number,
    default: 0
  },
  isDark: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['close', 'url-change'])

const iframeRef = ref(null)
const inputUrl = ref(props.url || 'http://localhost:3000')
const effectiveUrl = ref(props.url || 'http://localhost:3000')
const currentViewport = ref('responsive')
const reloadKey = ref(0)
const loading = ref(true)
const isRefreshing = ref(false)

watch(() => props.url, (newVal) => {
  if (newVal && newVal !== effectiveUrl.value) {
    inputUrl.value = newVal
    effectiveUrl.value = newVal
    loading.value = true
    reloadKey.value++
  }
})

// 当父组件（文件修改或热重载触发）更新 refreshKey 时自动刷新 iframe
watch(() => props.refreshKey, () => {
  reloadFrame()
})

function applyUrlChange() {
  let val = String(inputUrl.value || '').trim()
  if (!val) return
  if (!/^https?:\/\//i.test(val)) {
    val = 'http://' + val
  }
  inputUrl.value = val
  effectiveUrl.value = val
  loading.value = true
  reloadKey.value++
  emit('url-change', val)
}

function reloadFrame() {
  isRefreshing.value = true
  loading.value = true
  reloadKey.value++
  setTimeout(() => {
    isRefreshing.value = false
  }, 600)
}

function onFrameLoad() {
  loading.value = false
}

const wrapperStyle = computed(() => {
  if (currentViewport.value === 'mobile') {
    return { width: '375px', height: '667px' }
  }
  if (currentViewport.value === 'tablet') {
    return { width: '768px', height: '100%' }
  }
  return { width: '100%', height: '100%' }
})
</script>

<style scoped>
.web-preview-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  width: 100%;
  background: #ffffff;
  border-left: 1px solid #e4e4e7;
  overflow: hidden;
}

.web-preview-panel.is-dark {
  background: #18181b;
  border-color: #27272a;
}

/* 控制栏 */
.wp-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 6px 12px;
  background: #f4f4f5;
  border-bottom: 1px solid #e4e4e7;
  flex-shrink: 0;
  min-height: 38px;
}

.is-dark .wp-toolbar {
  background: #27272a;
  border-color: #3f3f46;
}

.wp-toolbar-left,
.wp-toolbar-right {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

/* Live 指示灯 */
.wp-status-indicator {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 2px 7px;
  border-radius: 4px;
  background: #ecfdf5;
  border: 1px solid #a7f3d0;
}

.is-dark .wp-status-indicator {
  background: #064e3b;
  border-color: #047857;
}

.wp-status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #10b981;
  box-shadow: 0 0 0 2px rgba(16, 185, 129, 0.25);
  animation: pulseDot 2s infinite;
}

.wp-status-text {
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.5px;
  color: #065f46;
  font-family: 'JetBrains Mono', monospace;
}

.is-dark .wp-status-text {
  color: #a7f3d0;
}

@keyframes pulseDot {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.6; transform: scale(1.15); }
}

/* 工具按钮 */
.wp-tool-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: 4px;
  border: 1px solid #e4e4e7;
  background: #ffffff;
  color: #52525b;
  cursor: pointer;
  transition: all 0.15s ease;
  text-decoration: none;
}

.is-dark .wp-tool-btn {
  background: #18181b;
  border-color: #3f3f46;
  color: #a1a1aa;
}

.wp-tool-btn:hover {
  background: #f4f4f5;
  color: #09090b;
  border-color: #d4d4d8;
}

.is-dark .wp-tool-btn:hover {
  background: #3f3f46;
  color: #fafafa;
}

.wp-tool-btn.is-refreshing svg {
  animation: spin 0.6s linear infinite;
}

.wp-close-btn:hover {
  background: #fee2e2;
  color: #dc2626;
  border-color: #fca5a5;
}

/* 视口切换组 */
.wp-viewport-group {
  display: inline-flex;
  align-items: center;
  background: #e4e4e7;
  padding: 2px;
  border-radius: 4px;
  gap: 1px;
}

.is-dark .wp-viewport-group {
  background: #3f3f46;
}

.wp-viewport-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border: none;
  background: transparent;
  color: #71717a;
  border-radius: 3px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.is-dark .wp-viewport-btn {
  color: #a1a1aa;
}

.wp-viewport-btn.active {
  background: #ffffff;
  color: #09090b;
  box-shadow: 0 1px 2px rgba(0,0,0,0.05);
}

.is-dark .wp-viewport-btn.active {
  background: #18181b;
  color: #fafafa;
}

/* 地址栏 */
.wp-address-bar {
  flex: 1;
  max-width: 480px;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 8px;
  height: 24px;
  background: #ffffff;
  border: 1px solid #e4e4e7;
  border-radius: 4px;
}

.is-dark .wp-address-bar {
  background: #18181b;
  border-color: #3f3f46;
}

.wp-addr-icon {
  color: #a1a1aa;
  flex-shrink: 0;
}

.wp-addr-input {
  flex: 1;
  border: none;
  background: transparent;
  outline: none;
  font-family: 'JetBrains Mono', monospace;
  font-size: 11.5px;
  color: #27272a;
}

.is-dark .wp-addr-input {
  color: #e4e4e7;
}

/* 视窗核心舞台 */
.wp-viewport-stage {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f4f4f5;
  overflow: auto;
  position: relative;
}

.is-dark .wp-viewport-stage {
  background: #09090b;
}

.wp-viewport-stage.viewport-responsive .wp-frame-wrapper {
  box-shadow: none;
  border-radius: 0;
}

.wp-viewport-stage:not(.viewport-responsive) .wp-frame-wrapper {
  box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.1), 0 8px 10px -6px rgba(0, 0, 0, 0.1);
  border-radius: 8px;
  border: 1px solid #e4e4e7;
}

.is-dark .wp-viewport-stage:not(.viewport-responsive) .wp-frame-wrapper {
  border-color: #3f3f46;
}

.wp-frame-wrapper {
  position: relative;
  background: #ffffff;
  transition: width 0.2s ease;
  overflow: hidden;
}

.wp-iframe {
  width: 100%;
  height: 100%;
  border: none;
  display: block;
}

/* 加载遮罩 */
.wp-loading-overlay {
  position: absolute;
  inset: 0;
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(2px);
  display: flex;
  align-items: center;
  justify-content: center;
}

.is-dark .wp-loading-overlay {
  background: rgba(24, 24, 27, 0.85);
}

.wp-loading-spinner {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}

.wp-spin-icon {
  animation: spin 0.8s linear infinite;
}

.wp-loading-text {
  font-size: 12px;
  color: #52525b;
  font-weight: 500;
}

.is-dark .wp-loading-text {
  color: #a1a1aa;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
