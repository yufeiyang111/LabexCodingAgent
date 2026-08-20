<template>
  <div
    class="clean-thinking-card"
    :class="{
      expanded: isExpanded,
      'is-streaming': isStreaming
    }"
  >
    <div class="thinking-header-bar" @click="toggleExpand">
      <div class="thinking-header-left">
        <span class="icon thinking-chevron" :class="{ rotated: isExpanded }">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="9 18 15 12 9 6"/></svg>
        </span>
        <span
          v-if="isStreaming"
          class="shimmer-text-stream thinking-preview-title"
        >
          {{ streamingTitle || '正在深度思考...' }}
        </span>
        <span
          v-else
          class="thinking-preview-title"
        >
          {{ collapsedTitle || '已完成深度思考' }}
        </span>
      </div>
      <span v-if="displayElapsed" class="thinking-meta-time">{{ displayElapsed }}</span>
    </div>

    <!-- 平滑风琴折叠展开 -->
    <div class="smooth-accordion" :class="{ open: isExpanded }">
      <div class="smooth-accordion-inner">
        <slot>
          <div
            class="thinking-content-body markdown-rendered"
            v-html="renderedContent"
            @click="emit('markdown-click', $event)"
          ></div>
        </slot>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onUnmounted } from 'vue'

const props = defineProps({
  content: {
    type: String,
    default: '',
  },
  renderedContent: {
    type: String,
    default: '',
  },
  summary: {
    type: String,
    default: '',
  },
  isStreaming: {
    type: Boolean,
    default: false,
  },
  elapsedSeconds: {
    type: [Number, String],
    default: null,
  },
  defaultOpen: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['markdown-click', 'toggle'])

const isExpanded = ref(props.defaultOpen)
const liveElapsed = ref('0.0')
let ticker = null
let startTimestamp = null

watch(
  () => props.isStreaming,
  newVal => {
    if (newVal) {
      if (!startTimestamp) startTimestamp = Date.now()
      if (ticker) clearInterval(ticker)
      ticker = setInterval(() => {
        liveElapsed.value = ((Date.now() - startTimestamp) / 1000).toFixed(1)
      }, 100)
    } else {
      if (ticker) {
        clearInterval(ticker)
        ticker = null
      }
      if (startTimestamp) {
        liveElapsed.value = ((Date.now() - startTimestamp) / 1000).toFixed(1)
      }
    }
  },
  { immediate: true }
)

onUnmounted(() => {
  if (ticker) clearInterval(ticker)
})

const streamingTitle = computed(() => {
  if (props.content) {
    const clean = props.content.replace(/[#*`_]/g, '').trim()
    if (clean) return clean.slice(0, 45) + (clean.length > 45 ? '...' : '')
  }
  return '正在深度思考...'
})

const collapsedTitle = computed(() => {
  if (props.summary) return props.summary
  if (props.content) {
    const clean = props.content.replace(/[#*`_]/g, '').trim()
    if (clean) return clean.slice(0, 50) + (clean.length > 50 ? '...' : '')
  }
  return '已完成深度思考'
})

const displayElapsed = computed(() => {
  if (props.elapsedSeconds !== null && props.elapsedSeconds !== undefined) {
    return props.isStreaming ? `已思考 ${Number(props.elapsedSeconds).toFixed(1)}s` : `耗时 ${Number(props.elapsedSeconds).toFixed(1)}s`
  }
  if (parseFloat(liveElapsed.value) > 0) {
    return props.isStreaming ? `已思考 ${liveElapsed.value}s` : `耗时 ${liveElapsed.value}s`
  }
  return ''
})

function toggleExpand() {
  isExpanded.value = !isExpanded.value
  emit('toggle', isExpanded.value)
}
</script>

<style scoped>
.clean-thinking-card {
  background: #ffffff;
  border: 1px solid #e4e4e7;
  border-radius: 8px;
  padding: 8px 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.03);
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
  user-select: none;
}

.clean-thinking-card:hover {
  border-color: #d4d4d8;
  background: #ffffff;
  box-shadow: 0 4px 16px -2px rgba(0, 0, 0, 0.08), 0 2px 6px -1px rgba(0, 0, 0, 0.04);
  transform: translateY(-1px);
}

.clean-thinking-card.is-streaming {
  border-color: #d4d4d8;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.thinking-header-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  cursor: pointer;
}

.thinking-header-left {
  display: flex;
  align-items: center;
  gap: 7px;
  flex: 1;
  overflow: hidden;
}

.thinking-preview-title {
  font-size: 12px;
  font-weight: 500;
  color: #3f3f46;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.thinking-chevron {
  color: #71717a;
  transition: transform 0.22s cubic-bezier(0.16, 1, 0.3, 1);
}

.thinking-chevron.rotated {
  transform: rotate(90deg);
}

.thinking-meta-time {
  font-size: 11px;
  color: #a1a1aa;
  font-family: 'JetBrains Mono', monospace;
  margin-left: 8px;
  flex-shrink: 0;
}

/* 流光渐变动画 */
@keyframes shimmer-sweep {
  0% { background-position: -200% 0; }
  100% { background-position: 200% 0; }
}

.shimmer-text-stream {
  background: linear-gradient(90deg, #18181b 0%, #71717a 35%, #a1a1aa 50%, #71717a 65%, #18181b 100%);
  background-size: 200% 100%;
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  animation: shimmer-sweep 2.2s infinite linear;
  font-weight: 500;
  display: inline-block;
}

/* 平滑风琴动画 */
.smooth-accordion {
  display: grid;
  grid-template-rows: 0fr;
  transition: grid-template-rows 0.28s cubic-bezier(0.16, 1, 0.3, 1),
              opacity 0.22s ease;
  opacity: 0;
}

.smooth-accordion.open {
  grid-template-rows: 1fr;
  opacity: 1;
}

.smooth-accordion-inner {
  overflow: hidden;
}

.thinking-content-body {
  padding-top: 8px;
  font-size: 12.5px;
  line-height: 1.6;
  color: #3f3f46;
  font-family: 'JetBrains Mono', monospace;
  user-select: text;
}

.icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
</style>
