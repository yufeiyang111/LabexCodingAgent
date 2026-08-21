<template>
  <div class="dht-card">
    <div class="dht-title">依赖状态</div>

    <div v-if="error" class="dht-error">{{ error }}</div>

    <div v-else-if="loading && dependencies.length === 0" class="dht-empty">正在检查依赖状态…</div>

    <div v-else-if="dependencies.length === 0" class="dht-empty">暂无依赖数据</div>

    <div v-else class="dht-table">
      <table>
        <thead>
          <tr>
            <th>依赖</th>
            <th>状态</th>
            <th>耗时</th>
            <th>信息</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="dep in dependencies" :key="dep.dependencyName">
            <td>
              <span class="dht-name">{{ dep.dependencyName }}</span>
              <span v-if="dep.affectsCoreService" class="dht-core">核心</span>
            </td>
            <td>
              <span class="dht-status" :class="statusClass(dep.status)">{{ statusLabel(dep.status) }}</span>
            </td>
            <td class="dht-num">{{ dep.latencyMs }} ms</td>
            <td class="dht-message">
              <span>{{ dep.safeMessage || '-' }}</span>
              <span v-if="dep.errorCode" class="dht-code">{{ dep.errorCode }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
defineProps({
  loading: { type: Boolean, default: false },
  dependencies: { type: Array, default: () => [] },
  error: { type: String, default: '' }
})

function statusLabel(status) {
  return ({ UP: '正常', DEGRADED: '降级', DOWN: '异常', UNKNOWN: '未知' })[status] || '未知'
}

function statusClass(status) {
  if (status === 'DOWN') return 'dht-status-down'
  if (status === 'DEGRADED') return 'dht-status-degraded'
  if (status === 'UP') return 'dht-status-up'
  return 'dht-status-unknown'
}
</script>

<style scoped>
.dht-card { background: #fff; border: 1px solid #eef0f3; border-radius: 12px; padding: 14px 16px; }
.dht-title { font-size: 13px; font-weight: 600; color: #374151; margin-bottom: 10px; }
.dht-error { font-size: 12px; color: #dc2626; padding: 8px 0; }
.dht-empty { font-size: 12px; color: #9ca3af; padding: 10px 0; }
.dht-table { overflow: auto; }
.dht-table table { width: 100%; border-collapse: collapse; font-size: 12px; }
.dht-table th { text-align: left; padding: 8px 10px; color: #6b7280; font-weight: 500; background: #f9fafb; border-bottom: 1px solid #f0f2f5; }
.dht-table td { padding: 8px 10px; border-bottom: 1px solid #f6f8fa; color: #374151; white-space: nowrap; }
.dht-name { font-weight: 600; color: #111827; }
.dht-core { display: inline-block; margin-left: 6px; padding: 1px 6px; border-radius: 999px; font-size: 10px; background: #eef2ff; color: #4f46e5; }
.dht-status { display: inline-block; min-width: 44px; text-align: center; padding: 2px 8px; border-radius: 999px; font-size: 11px; font-weight: 600; }
.dht-status-up { background: #ecfdf5; color: #059669; }
.dht-status-degraded { background: #fffbeb; color: #d97706; }
.dht-status-down { background: #fef2f2; color: #dc2626; }
.dht-status-unknown { background: #f1f5f9; color: #64748b; }
.dht-num { color: #6b7280; font-variant-numeric: tabular-nums; }
.dht-message { max-width: 360px; overflow: hidden; text-overflow: ellipsis; color: #6b7280; }
.dht-code { margin-left: 6px; padding: 1px 6px; border-radius: 6px; font-size: 10px; background: #f1f5f9; color: #64748b; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
</style>
