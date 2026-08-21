<template>
  <div class="mop-card">
    <div class="mop-title">最新指标</div>

    <div v-if="error" class="mop-error">{{ error }}</div>

    <div v-else-if="loading && !overview" class="mop-empty">正在加载指标…</div>

    <div v-else-if="!overview" class="mop-empty">等待首次采样（默认每 5 分钟）</div>

    <div v-else class="mop-body">
      <div class="mop-meta">采样时间 {{ formatTime(overview.sampleTime) }}</div>
      <div class="mop-grid">
        <div class="mop-item">
          <span class="mop-value">{{ fmt(overview.cpuPercent) }}%</span>
          <span class="mop-label">CPU</span>
        </div>
        <div class="mop-item">
          <span class="mop-value">{{ fmt(overview.memoryPercent) }}%</span>
          <span class="mop-label">内存</span>
        </div>
        <div class="mop-item">
          <span class="mop-value">{{ fmt(overview.diskPercent) }}%</span>
          <span class="mop-label">磁盘</span>
        </div>
        <div class="mop-item">
          <span class="mop-value">{{ fmtBytes(overview.heapUsedBytes) }} / {{ fmtBytes(overview.heapMaxBytes) }}</span>
          <span class="mop-label">JVM 堆</span>
        </div>
        <div class="mop-item">
          <span class="mop-value">{{ overview.systemLoadAverage ?? '-' }}</span>
          <span class="mop-label">系统负载</span>
        </div>
        <div class="mop-item">
          <span class="mop-value">{{ overview.taskTotal ?? '-' }}</span>
          <span class="mop-label">任务总数</span>
        </div>
      </div>

      <div class="mop-section">任务状态分布</div>
      <div class="mop-task-row">
        <span class="mop-task mop-task-run">运行 {{ overview.taskRunning ?? 0 }}</span>
        <span class="mop-task mop-task-wait">等待 {{ overview.taskWaiting ?? 0 }}</span>
        <span class="mop-task mop-task-ok">完成 {{ overview.taskCompleted ?? 0 }}</span>
        <span class="mop-task mop-task-fail">失败 {{ overview.taskFailed ?? 0 }}</span>
        <span class="mop-task mop-task-cancel">取消 {{ overview.taskCancelled ?? 0 }}</span>
      </div>

      <div class="mop-section">Token（近采样窗口）</div>
      <div class="mop-token-row">
        <span class="mop-token">总 {{ fmtNum(overview.tokenTotal) }}</span>
        <span class="mop-token">输入 {{ fmtNum(overview.tokenPromptTotal) }}</span>
        <span class="mop-token">输出 {{ fmtNum(overview.tokenCompletionTotal) }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
defineProps({
  overview: { type: Object, default: null },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' }
})

function fmt(value) {
  return value == null ? '-' : Number(value).toFixed(1)
}

function fmtNum(value) {
  return value == null ? '-' : Number(value).toLocaleString()
}

function fmtBytes(bytes) {
  if (bytes == null || isNaN(bytes)) return '-'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let v = Number(bytes)
  let i = 0
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++ }
  return v.toFixed(1) + ' ' + units[i]
}

function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '-'
}
</script>

<style scoped>
.mop-card { background: #fff; border: 1px solid #eef0f3; border-radius: 12px; padding: 14px 16px; }
.mop-title { font-size: 13px; font-weight: 600; color: #374151; margin-bottom: 10px; }
.mop-error { font-size: 12px; color: #dc2626; padding: 8px 0; }
.mop-empty { font-size: 12px; color: #9ca3af; padding: 14px 0; text-align: center; }
.mop-meta { font-size: 11px; color: #9ca3af; margin-bottom: 10px; font-variant-numeric: tabular-nums; }
.mop-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
@media (max-width: 720px) { .mop-grid { grid-template-columns: repeat(2, 1fr); } }
.mop-item { background: #f8fafc; border-radius: 10px; padding: 10px 12px; }
.mop-value { display: block; font-size: 15px; font-weight: 700; color: #111827; font-variant-numeric: tabular-nums; }
.mop-label { font-size: 11px; color: #6b7280; }
.mop-section { font-size: 12px; font-weight: 600; color: #374151; margin: 12px 0 6px; }
.mop-task-row { display: flex; gap: 6px; flex-wrap: wrap; }
.mop-task { padding: 2px 8px; border-radius: 999px; font-size: 11px; font-weight: 600; }
.mop-task-run { background: #eff6ff; color: #2563eb; }
.mop-task-wait { background: #fffbeb; color: #d97706; }
.mop-task-ok { background: #ecfdf5; color: #059669; }
.mop-task-fail { background: #fef2f2; color: #dc2626; }
.mop-task-cancel { background: #f1f5f9; color: #64748b; }
.mop-token-row { display: flex; gap: 6px; flex-wrap: wrap; }
.mop-token { font-size: 12px; color: #475569; background: #f8fafc; padding: 4px 10px; border-radius: 8px; font-variant-numeric: tabular-nums; }
</style>
