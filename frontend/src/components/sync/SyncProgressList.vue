<template>
  <div class="sync-progress">
    <div v-if="entries.length === 0" class="sync-progress-empty">
      尚未同步任何文件
    </div>
    <ul v-else class="sync-progress-list">
      <li
        v-for="entry in entries"
        :key="entry.key"
        class="sync-progress-row"
        :class="'is-' + entry.tone"
      >
        <span class="sync-progress-op">{{ entry.opLabel }}</span>
        <span class="sync-progress-path" :title="entry.path">{{ entry.path }}</span>
        <span class="sync-progress-status">{{ entry.statusLabel }}</span>
      </li>
    </ul>
  </div>
</template>

<script setup>
// 唯一职责：逐文件展示本次同步的写入结果；只读 props，不发起任何操作。
import { computed } from 'vue'

const props = defineProps({
  /** 计划中的写入步骤：[{ path, op, changeType }] */
  steps: { type: Array, default: () => [] },
  /** 已写入条数（按步骤顺序推进） */
  applied: { type: Number, default: 0 },
  /** 冲突：[{ path, reason }] */
  conflicts: { type: Array, default: () => [] },
  /** 被跳过的条目：[{ path, reason }] */
  skipped: { type: Array, default: () => [] }
})

const OP_LABELS = { write: '写入', delete: '删除' }

const REASON_LABELS = {
  'content-drift': '本地内容已变化',
  'missing-local': '本地缺少基线文件',
  'local-occupied': '本地已存在同名文件',
  'truncated-baseline': '云端基线被截断',
  'truncated-target': '云端目标内容被截断',
  'probe-unknown': '无法读取本地文件',
  'already-applied': '内容已一致',
  'already-absent': '本地已不存在',
  excluded: '在排除列表中',
  'binary-content': '二进制文件，不能用文本同步',
  'invalid-path': '路径非法'
}

function reasonLabel(reason) {
  if (!reason) return ''
  if (reason.startsWith('status:')) return `状态为 ${reason.slice('status:'.length)}，未写入`
  if (reason.startsWith('conflict:')) return reasonLabel(reason.slice('conflict:'.length))
  return REASON_LABELS[reason] ?? reason
}

const entries = computed(() => {
  const rows = []
  const conflictsByPath = new Map(props.conflicts.map(item => [item.path, item]))
  const skippedByPath = new Map(props.skipped.map(item => [item.path, item]))

  props.steps.forEach((step, index) => {
    const conflict = conflictsByPath.get(step.path)
    const applied = index < props.applied
    rows.push({
      key: `step-${step.path}-${index}`,
      path: step.path,
      opLabel: OP_LABELS[step.op] ?? step.op,
      statusLabel: applied ? '已写入' : '待写入',
      tone: applied ? 'done' : 'pending'
    })
    if (conflict) {
      rows.push({
        key: `conflict-${conflict.path}`,
        path: conflict.path,
        opLabel: '冲突',
        statusLabel: reasonLabel(conflict.reason),
        tone: 'conflict'
      })
    }
  })

  for (const conflict of props.conflicts) {
    if (props.steps.some(step => step.path === conflict.path)) continue
    rows.push({
      key: `conflict-${conflict.path}`,
      path: conflict.path,
      opLabel: '冲突',
      statusLabel: reasonLabel(conflict.reason),
      tone: 'conflict'
    })
  }

  for (const item of props.skipped) {
    if (conflictsByPath.has(item.path) || skippedByPath.get(item.path) !== item) continue
    rows.push({
      key: `skip-${item.path}-${item.reason}`,
      path: item.path,
      opLabel: '跳过',
      statusLabel: reasonLabel(item.reason),
      tone: 'skipped'
    })
  }

  return rows
})
</script>

<style scoped>
.sync-progress {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow-y: auto;
}
.sync-progress-empty {
  padding: 12px 14px;
  color: var(--theme-text-muted);
  font-size: 12px;
}
.sync-progress-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.sync-progress-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 14px;
  border-bottom: 1px solid var(--theme-border);
  font-size: 11.5px;
  font-family: 'JetBrains Mono', monospace;
  color: var(--theme-text-secondary);
}
.sync-progress-row:last-child {
  border-bottom: none;
}
.sync-progress-op {
  flex-shrink: 0;
  min-width: 34px;
  padding: 1px 6px;
  border-radius: 4px;
  border: 1px solid var(--theme-border);
  font-size: 10px;
  text-align: center;
  color: var(--theme-text-secondary);
}
.sync-progress-path {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--theme-text);
}
.sync-progress-status {
  flex-shrink: 0;
  font-size: 10.5px;
}
.sync-progress-row.is-done .sync-progress-status {
  color: var(--theme-accent);
}
.sync-progress-row.is-conflict .sync-progress-op,
.sync-progress-row.is-conflict .sync-progress-status {
  color: var(--theme-danger);
  border-color: var(--theme-danger);
}
.sync-progress-row.is-skipped {
  opacity: 0.72;
}
</style>
