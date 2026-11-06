<template>
  <div class="sync-conflict-dialog" v-if="open">
    <div class="sync-dialog-backdrop" @click="emit('close')"></div>
    <div class="sync-dialog" role="dialog" aria-modal="true" aria-labelledby="sync-conflict-title">
      <header class="sync-dialog-head">
        <h3 id="sync-conflict-title">有 {{ conflicts.length }} 个文件与本地不一致</h3>
        <button class="sync-dialog-close" title="关闭" @click="emit('close')">×</button>
      </header>

      <p class="sync-dialog-hint">
        同步不会覆盖你在本地的改动。请选择处理方式，或先手动处理这些文件再重试。
      </p>

      <ul class="sync-conflict-list">
        <li v-for="item in conflicts" :key="item.path" class="sync-conflict-row">
          <span class="sync-conflict-path" :title="item.path">{{ item.path }}</span>
          <span class="sync-conflict-reason">{{ reasonLabel(item.reason) }}</span>
        </li>
      </ul>

      <fieldset class="sync-policy">
        <legend>处理方式</legend>
        <label v-for="option in policyOptions" :key="option.value" class="sync-policy-option">
          <input
            type="radio"
            name="sync-conflict-policy"
            :value="option.value"
            :checked="policy === option.value"
            @change="emit('update:policy', option.value)"
          />
          <span class="sync-policy-text">
            <strong>{{ option.label }}</strong>
            <em>{{ option.hint }}</em>
          </span>
        </label>
      </fieldset>

      <footer class="sync-dialog-actions">
        <button class="sync-dialog-btn" @click="emit('close')">取消</button>
        <button class="sync-dialog-btn is-primary" @click="emit('retry')">按所选方式重试</button>
      </footer>
    </div>
  </div>
</template>

<script setup>
// 唯一职责：展示冲突清单并让用户选择处理策略；不执行同步（重试事件上抛）。
import { SYNC_CONFLICT_POLICY_OPTIONS } from '@/constants/sync'

defineProps({
  open: { type: Boolean, default: false },
  conflicts: { type: Array, default: () => [] },
  policy: { type: String, default: 'strict' }
})

const emit = defineEmits(['close', 'retry', 'update:policy'])

const policyOptions = SYNC_CONFLICT_POLICY_OPTIONS

const REASON_LABELS = {
  'content-drift': '本地内容与云端基线不同',
  'missing-local': '本地缺少这个文件',
  'local-occupied': '本地已有同名文件',
  'truncated-baseline': '云端基线内容被截断，无法安全比对',
  'truncated-target': '云端目标内容被截断，写入会丢失内容',
  'probe-unknown': '无法读取本地文件状态'
}

function reasonLabel(reason) {
  return REASON_LABELS[reason] ?? reason ?? '未知原因'
}
</script>

<style scoped>
.sync-conflict-dialog {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: flex;
  align-items: center;
  justify-content: center;
}
.sync-dialog-backdrop {
  position: absolute;
  inset: 0;
  background: color-mix(in srgb, var(--theme-text) 42%, transparent);
}
.sync-dialog {
  position: relative;
  width: min(560px, calc(100vw - 40px));
  max-height: calc(100vh - 80px);
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px;
  border-radius: var(--theme-radius);
  border: 1px solid var(--theme-border);
  background: var(--theme-surface);
  color: var(--theme-text);
  box-shadow: var(--theme-shadow);
}
.sync-dialog-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.sync-dialog-head h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 700;
}
.sync-dialog-close {
  border: none;
  background: transparent;
  color: var(--theme-text-muted);
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
}
.sync-dialog-close:hover {
  color: var(--theme-text);
}
.sync-dialog-hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--theme-text-secondary);
}
.sync-conflict-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 220px;
  overflow-y: auto;
  border: 1px solid var(--theme-border);
  border-radius: calc(var(--theme-radius) - 4px);
}
.sync-conflict-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 6px 10px;
  border-bottom: 1px solid var(--theme-border);
  font-size: 11.5px;
}
.sync-conflict-row:last-child {
  border-bottom: none;
}
.sync-conflict-path {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: 'JetBrains Mono', monospace;
  color: var(--theme-text);
}
.sync-conflict-reason {
  flex-shrink: 0;
  color: var(--theme-danger);
  font-size: 11px;
}
.sync-policy {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--theme-border);
  border-radius: calc(var(--theme-radius) - 4px);
}
.sync-policy legend {
  padding: 0 4px;
  font-size: 11px;
  color: var(--theme-text-muted);
}
.sync-policy-option {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 4px 0;
  cursor: pointer;
}
.sync-policy-option input {
  margin-top: 2px;
  accent-color: var(--theme-accent);
}
.sync-policy-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12px;
}
.sync-policy-text strong {
  font-weight: 600;
  color: var(--theme-text);
}
.sync-policy-text em {
  font-style: normal;
  font-size: 11px;
  color: var(--theme-text-muted);
}
.sync-dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.sync-dialog-btn {
  padding: 6px 14px;
  border-radius: calc(var(--theme-radius) - 4px);
  border: 1px solid var(--theme-border);
  background: var(--theme-surface-muted);
  color: var(--theme-text);
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
}
.sync-dialog-btn.is-primary {
  border-color: var(--theme-accent);
  background: var(--theme-accent);
  color: var(--theme-accent-contrast);
}
.sync-dialog-btn.is-primary:hover {
  background: var(--theme-accent-strong);
}
</style>
