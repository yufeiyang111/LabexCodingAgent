<template>
  <div class="adt">
    <div class="adt-toolbar">
      <input v-model="filters.operatorId" name="audit-operator" class="adt-input" placeholder="操作者" @change="onFilter" />
      <input v-model="filters.actionType" name="audit-action" class="adt-input" placeholder="动作类型" @change="onFilter" />
      <select v-model="filters.result" name="audit-result" class="adt-select" @change="onFilter">
        <option value="">全部结果</option>
        <option value="SUCCESS">成功</option>
        <option value="FAILED">失败</option>
      </select>
      <button class="adt-btn" @click="$emit('refresh')">刷新</button>
    </div>
    <OpsStateBlock :loading="loading" :error="error" :empty="records.length === 0" empty-text="暂无审计记录">
      <table class="adt-table">
        <thead>
          <tr>
            <th>时间</th>
            <th>操作者</th>
            <th>动作</th>
            <th>目标</th>
            <th>原因</th>
            <th>变更</th>
            <th>结果</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in records" :key="r.auditId">
            <td class="adt-time">{{ formatTime(r.createTime) }}</td>
            <td>{{ r.operatorId || '-' }} <span class="adt-role">{{ r.operatorRole || '' }}</span></td>
            <td>{{ r.actionType }}</td>
            <td>{{ r.targetType ? r.targetType + ':' + (r.targetId || '-') : '-' }}</td>
            <td class="adt-reason" :title="r.reason">{{ r.reason || '-' }}</td>
            <td class="adt-state">{{ shortState(r.beforeState) }} → {{ shortState(r.afterState) }}</td>
            <td><OpsStatusTag :text="r.result === 'SUCCESS' ? '成功' : '失败'" :type="r.result === 'SUCCESS' ? 'success' : 'danger'" /></td>
          </tr>
        </tbody>
      </table>
      <OpsPagination :page="page" :total="total" :page-size="pageSize" @change="$emit('page-change', $event)" />
    </OpsStateBlock>
  </div>
</template>

<script setup>
import OpsStateBlock from '@/components/ops/ui/OpsStateBlock.vue'
import OpsPagination from '@/components/ops/ui/OpsPagination.vue'
import OpsStatusTag from '@/components/ops/ui/OpsStatusTag.vue'

defineProps({
  records: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  page: { type: Number, default: 1 },
  total: { type: Number, default: 0 },
  pageSize: { type: Number, default: 20 },
  filters: { type: Object, default: () => ({ operatorId: '', actionType: '', result: '' }) }
})

const emit = defineEmits(['refresh', 'filter', 'page-change'])

function onFilter() {
  emit('filter')
}

function shortState(state) {
  if (!state) return '-'
  return String(state).length > 24 ? String(state).slice(0, 24) + '…' : state
}

function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '-'
}
</script>

<style scoped>
.adt { background: #fff; border: 1px solid #eef0f3; border-radius: 12px; padding: 14px 16px; }
.adt-toolbar { display: flex; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.adt-input, .adt-select { padding: 6px 10px; border: 1px solid #e5e7eb; border-radius: 8px; font-size: 12px; font-family: inherit; color: #374151; background: #fff; }
.adt-input { width: 140px; }
.adt-btn { border: 1px solid #e5e7eb; background: #fff; padding: 6px 12px; border-radius: 8px; font-size: 12px; font-family: inherit; color: #374151; cursor: pointer; }
.adt-btn:hover { background: #f9fafb; }
.adt-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.adt-table th { text-align: left; padding: 8px 10px; color: #6b7280; font-weight: 500; background: #f9fafb; border-bottom: 1px solid #f0f2f5; position: sticky; top: 0; }
.adt-table td { padding: 8px 10px; border-bottom: 1px solid #f6f8fa; color: #374151; white-space: nowrap; }
.adt-time { color: #6b7280; font-variant-numeric: tabular-nums; }
.adt-role { font-size: 10px; color: #9ca3af; margin-left: 4px; }
.adt-reason { max-width: 260px; overflow: hidden; text-overflow: ellipsis; }
.adt-state { color: #6b7280; font-size: 11px; }
</style>
