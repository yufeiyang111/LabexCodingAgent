<template>
  <div class="op-page">
    <div class="op-toolbar">
      <select v-model="localStatus" name="alert-status" class="op-select" @change="onFilter">
        <option value="">全部状态</option>
        <option v-for="(meta, key) in ALERT_STATUSES" :key="key" :value="key">{{ meta.label }}</option>
      </select>
      <button class="op-btn" @click="$emit('refresh')">刷新</button>
    </div>
    <OpsStateBlock :loading="loading" :error="error" :empty="alerts.length === 0" empty-text="暂无告警">
      <div class="op-table-wrap">
        <table class="op-table">
          <thead>
            <tr>
              <th>状态</th>
              <th>严重度</th>
              <th>规则</th>
              <th>指标</th>
              <th>当前值/阈值</th>
              <th>触发时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="alert in alerts" :key="alert.alertId">
              <td><OpsStatusTag :text="(ALERT_STATUSES[alert.status] || {}).label || alert.status" :type="(ALERT_STATUSES[alert.status] || {}).type" /></td>
              <td><OpsStatusTag :text="(SEVERITIES[alert.severity] || {}).label || alert.severity" :type="(SEVERITIES[alert.severity] || {}).type" /></td>
              <td class="op-cell-name" :title="alert.message">{{ alert.ruleName || ('规则 #' + alert.ruleId) }}</td>
              <td>{{ alert.metricKey }}</td>
              <td class="op-cell-num">{{ fmt(alert.currentValue) }} / {{ fmt(alert.threshold) }}</td>
              <td class="op-cell-time">{{ formatTime(alert.lastFiringAt) }}</td>
              <td class="op-cell-actions">
                <template v-if="alert.status !== 'RESOLVED'">
                  <button v-if="alert.status === 'FIRING'" class="op-btn op-btn-sm" :disabled="actingId === alert.alertId" @click="$emit('acknowledge', alert)">确认</button>
                  <button v-if="alert.status === 'FIRING' || alert.status === 'ACKNOWLEDGED'" class="op-btn op-btn-sm" :disabled="actingId === alert.alertId" @click="$emit('silence', alert)">静默</button>
                  <button v-if="alert.status !== 'SILENCED'" class="op-btn op-btn-sm op-btn-danger" :disabled="actingId === alert.alertId" @click="$emit('resolve', alert)">恢复</button>
                  <button class="op-btn op-btn-sm" :disabled="actingId === alert.alertId" @click="$emit('create-incident', alert)">转故障</button>
                </template>
                <span v-else class="op-muted">{{ formatTime(alert.resolvedAt) }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <OpsPagination :page="page" :total="total" :page-size="pageSize" @change="$emit('page-change', $event)" />
    </OpsStateBlock>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { ALERT_STATUSES, SEVERITIES } from '@/constants/ops'
import OpsStateBlock from '@/components/ops/ui/OpsStateBlock.vue'
import OpsPagination from '@/components/ops/ui/OpsPagination.vue'
import OpsStatusTag from '@/components/ops/ui/OpsStatusTag.vue'

const props = defineProps({
  alerts: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  page: { type: Number, default: 1 },
  total: { type: Number, default: 0 },
  pageSize: { type: Number, default: 20 },
  status: { type: String, default: '' },
  actingId: { type: [Number, null], default: null }
})

const emit = defineEmits(['refresh', 'filter', 'page-change', 'acknowledge', 'silence', 'resolve', 'create-incident'])

const localStatus = ref(props.status)
watch(() => props.status, (v) => { localStatus.value = v })

function onFilter() {
  emit('filter', localStatus.value)
}

function fmt(value) {
  return value == null ? '-' : Number(value).toFixed(1)
}

function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '-'
}
</script>

<style scoped>
.op-page { background: #fff; border: 1px solid #eef0f3; border-radius: 12px; padding: 14px 16px; }
.op-toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 10px; flex-wrap: wrap; }
.op-select { padding: 6px 10px; border: 1px solid #e5e7eb; border-radius: 8px; font-size: 12px; font-family: inherit; color: #374151; background: #fff; }
.op-btn { border: 1px solid #e5e7eb; background: #fff; padding: 6px 12px; border-radius: 8px; font-size: 12px; font-family: inherit; color: #374151; cursor: pointer; }
.op-btn:hover { background: #f9fafb; }
.op-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.op-btn-sm { padding: 3px 8px; font-size: 11px; }
.op-btn-danger { color: #dc2626; border-color: #fecaca; }
.op-table-wrap { overflow: auto; max-height: 480px; }
.op-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.op-table th { text-align: left; padding: 8px 10px; color: #6b7280; font-weight: 500; background: #f9fafb; border-bottom: 1px solid #f0f2f5; position: sticky; top: 0; }
.op-table td { padding: 8px 10px; border-bottom: 1px solid #f6f8fa; color: #374151; white-space: nowrap; }
.op-cell-name { max-width: 300px; overflow: hidden; text-overflow: ellipsis; }
.op-cell-num, .op-cell-time { color: #6b7280; font-variant-numeric: tabular-nums; }
.op-cell-actions { display: flex; gap: 6px; }
.op-muted { color: #9ca3af; font-size: 11px; }
</style>
