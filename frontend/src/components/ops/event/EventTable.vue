<template>
  <div class="evp">
    <div class="evp-toolbar">
      <select v-model="filters.eventType" class="evp-select" @change="onFilter">
        <option value="">全部类型</option>
        <option v-for="t in EVENT_TYPES" :key="t.value" :value="t.value">{{ t.label }}</option>
      </select>
      <select v-model="filters.severity" class="evp-select" @change="onFilter">
        <option value="">全部严重度</option>
        <option value="info">信息</option>
        <option value="warning">警告</option>
        <option value="critical">严重</option>
      </select>
      <button class="evp-btn" @click="$emit('refresh')">刷新</button>
    </div>
    <OpsStateBlock :loading="loading" :error="error" :empty="events.length === 0" empty-text="暂无事件">
      <table class="evp-table">
        <thead>
          <tr>
            <th>时间</th>
            <th>类型</th>
            <th>严重度</th>
            <th>目标</th>
            <th>消息</th>
            <th>操作者</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="ev in events" :key="ev.eventId">
            <td class="evp-time">{{ formatTime(ev.createTime) }}</td>
            <td>{{ eventLabel(ev.eventType) }}</td>
            <td><OpsStatusTag :text="(SEVERITIES[ev.severity] || {}).label || ev.severity" :type="(SEVERITIES[ev.severity] || {}).type" /></td>
            <td>{{ ev.targetType ? ev.targetType + ':' + (ev.targetId || '-') : '-' }}</td>
            <td class="evp-msg" :title="ev.detail || ev.message">{{ ev.message || '-' }}</td>
            <td class="evp-op">{{ ev.operator || '-' }}</td>
          </tr>
        </tbody>
      </table>
      <OpsPagination :page="page" :total="total" :page-size="pageSize" @change="$emit('page-change', $event)" />
    </OpsStateBlock>
  </div>
</template>

<script setup>
import { EVENT_TYPES, SEVERITIES } from '@/constants/ops'
import OpsStateBlock from '@/components/ops/ui/OpsStateBlock.vue'
import OpsPagination from '@/components/ops/ui/OpsPagination.vue'
import OpsStatusTag from '@/components/ops/ui/OpsStatusTag.vue'

defineProps({
  events: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  page: { type: Number, default: 1 },
  total: { type: Number, default: 0 },
  pageSize: { type: Number, default: 20 },
  filters: { type: Object, default: () => ({ eventType: '', severity: '' }) }
})

const emit = defineEmits(['refresh', 'filter', 'page-change'])

function onFilter() {
  emit('filter')
}

function eventLabel(type) {
  return (EVENT_TYPES.find(t => t.value === type) || {}).label || type
}

function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '-'
}
</script>

<style scoped>
.evp { background: #fff; border: 1px solid #eef0f3; border-radius: 12px; padding: 14px 16px; }
.evp-toolbar { display: flex; gap: 8px; margin-bottom: 10px; }
.evp-select { padding: 6px 10px; border: 1px solid #e5e7eb; border-radius: 8px; font-size: 12px; font-family: inherit; color: #374151; background: #fff; }
.evp-btn { border: 1px solid #e5e7eb; background: #fff; padding: 6px 12px; border-radius: 8px; font-size: 12px; font-family: inherit; color: #374151; cursor: pointer; }
.evp-btn:hover { background: #f9fafb; }
.evp-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.evp-table th { text-align: left; padding: 8px 10px; color: #6b7280; font-weight: 500; background: #f9fafb; border-bottom: 1px solid #f0f2f5; position: sticky; top: 0; }
.evp-table td { padding: 8px 10px; border-bottom: 1px solid #f6f8fa; color: #374151; white-space: nowrap; }
.evp-time, .evp-op { color: #6b7280; font-variant-numeric: tabular-nums; }
.evp-msg { max-width: 460px; overflow: hidden; text-overflow: ellipsis; }
</style>
