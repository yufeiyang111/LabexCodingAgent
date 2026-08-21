<template>
  <div class="oh-card">
    <div class="oh-card-head">
      <span class="oh-card-title">依赖健康概览</span>
      <div class="oh-card-actions">
        <span v-if="loading" class="oh-loading">检查中…</span>
        <button type="button" class="oh-refresh" :disabled="loading" @click="$emit('refresh')">刷新</button>
      </div>
    </div>

    <div v-if="error" class="oh-error">{{ error }}</div>

    <div v-else-if="loading && !summary" class="oh-empty">正在检查依赖状态…</div>

    <div v-else-if="!summary" class="oh-empty">暂无健康数据</div>

    <div v-else class="oh-body">
      <div class="oh-badge-row">
        <span class="oh-badge" :class="badgeClass(summary.overallStatus)">
          {{ statusLabel(summary.overallStatus) }}
        </span>
        <span class="oh-meta">检查时间 {{ formatTime(summary.checkedAt) }}</span>
        <span class="oh-meta">总耗时 {{ summary.totalLatencyMs }} ms</span>
      </div>
      <div class="oh-stats">
        <div class="oh-stat">
          <span class="oh-stat-value oh-stat-up">{{ summary.upCount }}</span>
          <span class="oh-stat-label">正常</span>
        </div>
        <div class="oh-stat">
          <span class="oh-stat-value oh-stat-degraded">{{ summary.degradedCount }}</span>
          <span class="oh-stat-label">降级</span>
        </div>
        <div class="oh-stat">
          <span class="oh-stat-value oh-stat-down">{{ summary.downCount }}</span>
          <span class="oh-stat-label">故障</span>
        </div>
        <div class="oh-stat">
          <span class="oh-stat-value">{{ summary.dependencyCount }}</span>
          <span class="oh-stat-label">依赖总数</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
defineProps({
  loading: { type: Boolean, default: false },
  summary: { type: Object, default: null },
  error: { type: String, default: '' }
})

defineEmits(['refresh'])

function statusLabel(status) {
  return ({ UP: '服务正常', DEGRADED: '部分降级', DOWN: '服务异常', UNKNOWN: '状态未知' })[status] || '状态未知'
}

function badgeClass(status) {
  if (status === 'DOWN') return 'oh-badge-down'
  if (status === 'DEGRADED') return 'oh-badge-degraded'
  if (status === 'UP') return 'oh-badge-up'
  return 'oh-badge-unknown'
}

function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '-'
}
</script>

<style scoped>
.oh-card { background: #fff; border: 1px solid #eef0f3; border-radius: 12px; padding: 14px 16px; }
.oh-card-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.oh-card-title { font-size: 13px; font-weight: 600; color: #374151; }
.oh-card-actions { display: flex; align-items: center; gap: 10px; }
.oh-loading { font-size: 12px; color: #6b7280; }
.oh-refresh { border: 1px solid #e5e7eb; background: #fff; padding: 4px 10px; border-radius: 8px; font-size: 12px; font-family: inherit; color: #374151; cursor: pointer; }
.oh-refresh:hover { background: #f9fafb; }
.oh-refresh:disabled { opacity: 0.6; cursor: not-allowed; }
.oh-error { font-size: 12px; color: #dc2626; padding: 8px 0; }
.oh-empty { font-size: 12px; color: #9ca3af; padding: 10px 0; }
.oh-body { display: flex; flex-direction: column; gap: 12px; }
.oh-badge-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.oh-badge { display: inline-block; padding: 4px 14px; border-radius: 999px; font-size: 13px; font-weight: 600; }
.oh-badge-up { background: #ecfdf5; color: #059669; }
.oh-badge-degraded { background: #fffbeb; color: #d97706; }
.oh-badge-down { background: #fef2f2; color: #dc2626; }
.oh-badge-unknown { background: #f1f5f9; color: #64748b; }
.oh-meta { font-size: 12px; color: #6b7280; font-variant-numeric: tabular-nums; }
.oh-stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
@media (max-width: 720px) { .oh-stats { grid-template-columns: repeat(2, 1fr); } }
.oh-stat { background: #f8fafc; border-radius: 10px; padding: 10px 12px; text-align: center; }
.oh-stat-value { display: block; font-size: 22px; font-weight: 700; color: #111827; font-variant-numeric: tabular-nums; }
.oh-stat-up { color: #059669; }
.oh-stat-degraded { color: #d97706; }
.oh-stat-down { color: #dc2626; }
.oh-stat-label { font-size: 11px; color: #6b7280; }
</style>
