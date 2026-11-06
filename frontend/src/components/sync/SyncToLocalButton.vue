<template>
  <div class="sync-button-wrap">
    <button
      class="sync-btn"
      :class="{ 'is-busy': busy }"
      :disabled="busy"
      :title="buttonTitle"
      @click="$emit('sync')"
    >
      <svg v-if="!busy" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
        <polyline points="7 10 12 15 17 10" />
        <line x1="12" y1="15" x2="12" y2="3" />
      </svg>
      <span v-else class="sync-spinner" aria-hidden="true"></span>
      <span class="sync-btn-label">{{ label }}</span>
    </button>
    <button
      v-if="directoryName"
      class="sync-target"
      :title="`目标目录：${directoryName}（点击重新选择）`"
      :disabled="busy"
      @click="$emit('pick-directory')"
    >
      {{ directoryName }}
    </button>
    <span v-else class="sync-target is-empty">未选择本地目录</span>
  </div>
</template>

<script setup>
// 唯一职责：同步入口按钮 + 当前目标目录展示；只上抛事件，不执行同步。
import { computed } from 'vue'

import { syncStateLabel } from '@/constants/sync'

const props = defineProps({
  state: { type: String, default: 'idle' },
  busy: { type: Boolean, default: false },
  hasDirectory: { type: Boolean, default: false },
  directoryName: { type: String, default: '' }
})

defineEmits(['sync', 'pick-directory'])

const label = computed(() => {
  if (props.busy) return syncStateLabel(props.state)
  return props.hasDirectory ? '同步到本地' : '选择本地目录'
})

const buttonTitle = computed(() => (props.hasDirectory
  ? '把云端 Agent 的改动同步到已选择的本地目录'
  : '先选择一个本地目录作为同步目标'))
</script>

<style scoped>
.sync-button-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.sync-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border-radius: calc(var(--theme-radius) - 4px);
  border: 1px solid var(--theme-border);
  background: var(--theme-surface);
  color: var(--theme-text);
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
  transition: background 0.12s ease, border-color 0.12s ease, color 0.12s ease;
}
.sync-btn:hover:not(:disabled) {
  border-color: var(--theme-accent);
  color: var(--theme-accent);
}
.sync-btn:disabled {
  cursor: not-allowed;
  opacity: 0.65;
}
.sync-btn.is-busy {
  border-color: var(--theme-accent);
  color: var(--theme-accent);
  background: var(--theme-accent-soft);
}
.sync-spinner {
  width: 11px;
  height: 11px;
  border: 2px solid var(--theme-accent);
  border-top-color: transparent;
  border-radius: 50%;
  animation: sync-spin 0.8s linear infinite;
}
@keyframes sync-spin {
  to { transform: rotate(360deg); }
}
.sync-btn-label {
  white-space: nowrap;
}
.sync-target {
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding: 4px 8px;
  border-radius: calc(var(--theme-radius) - 6px);
  border: 1px dashed var(--theme-border);
  background: transparent;
  color: var(--theme-text-secondary);
  font-size: 11px;
  font-family: inherit;
  cursor: pointer;
}
.sync-target:hover:not(:disabled) {
  color: var(--theme-text);
  border-color: var(--theme-accent);
}
.sync-target.is-empty {
  cursor: default;
  color: var(--theme-text-muted);
}
</style>
