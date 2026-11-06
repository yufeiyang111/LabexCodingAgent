<template>
  <div class="sync-progress-bar" :class="{ 'is-indeterminate': view.indeterminate }">
    <div class="sync-bar-head">
      <span class="sync-bar-label">{{ label }}</span>
      <span v-if="!view.indeterminate" class="sync-bar-count">{{ view.done }} / {{ view.total }}</span>
    </div>

    <div
      class="sync-bar-track"
      role="progressbar"
      :aria-valuenow="view.indeterminate ? undefined : view.percent"
      aria-valuemin="0"
      aria-valuemax="100"
      :aria-valuetext="label"
    >
      <div
        class="sync-bar-fill"
        :class="'is-' + tone"
        :style="view.indeterminate ? {} : { width: view.percent + '%' }"
      ></div>
    </div>

    <div v-if="detail" class="sync-bar-detail" :title="detail">{{ detail }}</div>
  </div>
</template>

<script setup>
// 唯一职责：把进度视图渲染成进度条；不做任何状态计算（由 syncProgressView 提供）。
import { computed } from 'vue'

import { formatDuration, syncProgressLabel } from '@/composables/sync/syncProgressView'

const props = defineProps({
  /** buildSyncProgressView 的输出 */
  view: { type: Object, required: true }
})

const label = computed(() => syncProgressLabel(props.view))

const tone = computed(() => {
  if (props.view.state === 'failed') return 'danger'
  if (props.view.state === 'blocked') return 'warn'
  if (props.view.state === 'done') return 'ok'
  return 'active'
})

const detail = computed(() => {
  const parts = []
  if (props.view.currentPath) parts.push(props.view.currentPath)
  if (props.view.remainingMs != null) parts.push(`约剩 ${formatDuration(props.view.remainingMs)}`)
  if (props.view.conflicts > 0) parts.push(`${props.view.conflicts} 个冲突`)
  if (props.view.skipped > 0) parts.push(`${props.view.skipped} 个跳过`)
  return parts.join(' · ')
})
</script>

<style scoped>
.sync-progress-bar {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 8px 14px 10px;
  border-bottom: 1px solid var(--theme-border);
}
.sync-bar-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  font-size: 11.5px;
  color: var(--theme-text-secondary);
}
.sync-bar-label {
  font-weight: 600;
  color: var(--theme-text);
}
.sync-bar-count {
  font-family: 'JetBrains Mono', monospace;
  color: var(--theme-text-muted);
}
.sync-bar-track {
  position: relative;
  height: 4px;
  border-radius: 2px;
  background: var(--theme-surface-muted);
  overflow: hidden;
}
.sync-bar-fill {
  height: 100%;
  border-radius: 2px;
  background: var(--theme-accent);
  transition: width 0.2s ease;
}
.sync-bar-fill.is-ok {
  background: var(--theme-accent);
}
.sync-bar-fill.is-warn,
.sync-bar-fill.is-danger {
  background: var(--theme-danger);
}
.sync-bar-fill.is-active {
  background: var(--theme-accent);
}
/* 不确定态：跑马灯，避免"卡在 0%"让人以为没动 */
.sync-progress-bar.is-indeterminate .sync-bar-fill {
  width: 35%;
  animation: sync-bar-slide 1.1s ease-in-out infinite;
}
@keyframes sync-bar-slide {
  0% { transform: translateX(-100%); }
  100% { transform: translateX(320%); }
}
.sync-bar-detail {
  font-size: 10.5px;
  color: var(--theme-text-muted);
  font-family: 'JetBrains Mono', monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
