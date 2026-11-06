<template>
  <section class="sync-panel">
    <header class="sync-panel-head">
      <div class="sync-panel-title">
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="var(--theme-accent)" stroke-width="2">
          <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
          <polyline points="7 10 12 15 17 10" />
          <line x1="12" y1="15" x2="12" y2="3" />
        </svg>
        <span>同步到本地</span>
      </div>
      <SyncToLocalButton
        :state="state"
        :busy="busy"
        :has-directory="hasDirectory"
        :directory-name="directoryName"
        @sync="emit('sync')"
        @pick-directory="emit('pick-directory')"
      />
    </header>

    <SyncProgressBar v-if="showProgress" :view="progressView" />

    <div class="sync-panel-status" :class="'is-' + tone">
      <span class="sync-status-text">{{ statusText }}</span>
      <span v-if="summary" class="sync-status-counts">
        待写入 {{ summary.toApply ?? 0 }} · 冲突 {{ summary.conflicts ?? 0 }} · 跳过 {{ summary.skipped ?? 0 }}
      </span>
    </div>

    <div v-if="upToDate" class="sync-panel-hint">
      <span>本地目录已是最新，云端没有待同步的变更。</span>
      <button class="sync-hint-action" @click="emit('reset-progress')">
        本地文件被删过？重置进度重新拉取
      </button>
    </div>

    <div v-if="error" class="sync-panel-error">{{ error }}</div>

    <SyncProgressList
      class="sync-panel-list"
      :steps="steps"
      :applied="applied"
      :conflicts="conflicts"
      :skipped="skipped"
    />

    <footer v-if="canRollback || canReviewConflicts || busy" class="sync-panel-actions">
      <button v-if="busy" class="sync-action is-danger" @click="emit('cancel')">
        中止本次同步
      </button>
      <button v-if="canReviewConflicts" class="sync-action" @click="emit('review-conflicts')">
        查看冲突（{{ conflicts.length }}）
      </button>
      <button v-if="canRollback" class="sync-action is-danger" :disabled="busy" @click="emit('rollback')">
        回退本次同步
      </button>
    </footer>
  </section>
</template>

<script setup>
// 唯一职责：同步面板的容器/展示层——汇总状态、进度与入口按钮；所有动作上抛给父组件。
import { computed } from 'vue'

import SyncProgressBar from './SyncProgressBar.vue'
import SyncProgressList from './SyncProgressList.vue'
import SyncToLocalButton from './SyncToLocalButton.vue'
import { syncStateLabel } from '@/constants/sync'

const props = defineProps({
  state: { type: String, default: 'idle' },
  busy: { type: Boolean, default: false },
  hasDirectory: { type: Boolean, default: false },
  directoryName: { type: String, default: '' },
  summary: { type: Object, default: null },
  upToDate: { type: Boolean, default: false },
  /** buildSyncProgressView 的输出；为空时不显示进度条。 */
  progressView: { type: Object, default: null },
  error: { type: String, default: '' },
  steps: { type: Array, default: () => [] },
  applied: { type: Number, default: 0 },
  conflicts: { type: Array, default: () => [] },
  skipped: { type: Array, default: () => [] },
  canRollback: { type: Boolean, default: false }
})

const emit = defineEmits(['sync', 'pick-directory', 'rollback', 'review-conflicts', 'reset-progress', 'cancel'])

const canReviewConflicts = computed(() => props.conflicts.length > 0)

// 只在"确实有事可显示"时出现：空闲时不占版面。
const showProgress = computed(() => (
  props.progressView != null
  && (props.busy || props.progressView.state === 'done' || props.progressView.state === 'failed')
  && props.progressView.total > 0
))

const tone = computed(() => {
  if (props.state === 'failed') return 'danger'
  if (props.state === 'blocked') return 'warn'
  if (props.state === 'done') return 'ok'
  return 'neutral'
})

const statusText = computed(() => {
  if (props.busy && props.summary) {
    return `${syncStateLabel(props.state)}… 已写入 ${props.applied}`
  }
  return syncStateLabel(props.state)
})
</script>

<style scoped>
.sync-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  background: var(--theme-surface);
  color: var(--theme-text);
  border-left: 1px solid var(--theme-border);
}
.sync-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  padding: 10px 14px;
  border-bottom: 1px solid var(--theme-border);
  flex-shrink: 0;
}
.sync-panel-title {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  font-weight: 700;
  color: var(--theme-text);
}
.sync-panel-status {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 7px 14px;
  border-bottom: 1px solid var(--theme-border);
  font-size: 11.5px;
  color: var(--theme-text-secondary);
  flex-shrink: 0;
}
.sync-panel-status.is-ok .sync-status-text {
  color: var(--theme-accent);
}
.sync-panel-status.is-warn .sync-status-text {
  color: var(--theme-danger);
}
.sync-panel-status.is-danger .sync-status-text {
  color: var(--theme-danger);
  font-weight: 600;
}
.sync-status-counts {
  font-family: 'JetBrains Mono', monospace;
  color: var(--theme-text-muted);
}
.sync-panel-error {
  margin: 8px 14px 0;
  padding: 7px 10px;
  border-radius: calc(var(--theme-radius) - 6px);
  border: 1px solid var(--theme-danger);
  background: var(--theme-surface-muted);
  color: var(--theme-danger);
  font-size: 11.5px;
  line-height: 1.5;
}
.sync-panel-hint {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 8px 14px 0;
  padding: 7px 10px;
  border-radius: calc(var(--theme-radius) - 6px);
  border: 1px solid var(--theme-border);
  background: var(--theme-surface-muted);
  color: var(--theme-text-secondary);
  font-size: 11.5px;
  line-height: 1.5;
}
.sync-hint-action {
  align-self: flex-start;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--theme-accent);
  font-size: 11.5px;
  font-family: inherit;
  text-decoration: underline;
  cursor: pointer;
}
.sync-hint-action:hover {
  color: var(--theme-accent-strong);
}
.sync-panel-list {
  flex: 1;
  min-height: 0;
}
.sync-panel-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 8px 14px;
  border-top: 1px solid var(--theme-border);
  flex-shrink: 0;
}
.sync-action {
  padding: 5px 12px;
  border-radius: calc(var(--theme-radius) - 4px);
  border: 1px solid var(--theme-border);
  background: var(--theme-surface-muted);
  color: var(--theme-text);
  font-size: 11.5px;
  font-family: inherit;
  cursor: pointer;
}
.sync-action:hover:not(:disabled) {
  border-color: var(--theme-accent);
  color: var(--theme-accent);
}
.sync-action.is-danger:hover:not(:disabled) {
  border-color: var(--theme-danger);
  color: var(--theme-danger);
}
.sync-action:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}
</style>
