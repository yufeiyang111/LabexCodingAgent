<template>
  <div class="rtt-card">
    <div class="rtt-title">
      <span>Agent 运行任务</span>
      <span class="rtt-total">共 {{ total }} 条</span>
    </div>

    <div class="rtt-filters">
      <select v-model="local.status" class="rtt-input" @change="emitFilters">
        <option value="">全部状态</option>
        <option v-for="s in statusOptions" :key="s" :value="s">{{ statusLabel(s) }}</option>
      </select>
      <input v-model="local.studentId" class="rtt-input rtt-narrow" type="number" min="1" placeholder="用户ID" @change="emitFilters" />
      <input v-model="local.provider" class="rtt-input" placeholder="Provider" @change="emitFilters" />
      <label class="rtt-check">
        <input v-model="local.overdueOnly" type="checkbox" @change="emitFilters" />
        仅超时租约
      </label>
      <button type="button" class="rtt-btn" :disabled="loading" @click="emitFilters">查询</button>
      <button type="button" class="rtt-btn-ghost" @click="emitReset">重置</button>
      <span v-if="loading" class="rtt-loading">加载中…</span>
    </div>

    <div v-if="error" class="rtt-error">{{ error }}</div>

    <div v-else-if="loading && tasks.length === 0" class="rtt-empty">正在加载任务列表…</div>

    <div v-else-if="tasks.length === 0" class="rtt-empty">暂无运行任务</div>

    <div v-else class="rtt-table">
      <table>
        <thead>
          <tr>
            <th>任务</th>
            <th>状态</th>
            <th>模式</th>
            <th>租约</th>
            <th>Provider</th>
            <th>重试</th>
            <th>更新时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="t in tasks" :key="t.taskId" class="rtt-row" @click="$emit('select', t.taskId)">
            <td>
              <div class="rtt-id">#{{ t.taskId }}</div>
              <div class="rtt-sub">{{ t.conversationId || '-' }}</div>
            </td>
            <td>
              <span class="rtt-status" :class="statusClass(t.currentStatus)">{{ statusLabel(t.currentStatus) }}</span>
              <span v-if="t.overdue" class="rtt-overdue">租约过期</span>
            </td>
            <td class="rtt-dim">{{ t.mode || '-' }}</td>
            <td>
              <div class="rtt-dim">{{ t.leaseOwner || '-' }}</div>
              <div v-if="t.lastHeartbeatAt" class="rtt-dim rtt-time">{{ formatTime(t.lastHeartbeatAt) }}</div>
            </td>
            <td class="rtt-dim">
              <div>{{ t.provider || '-' }}</div>
              <div v-if="t.modelName" class="rtt-sub">{{ t.modelName }}</div>
            </td>
            <td class="rtt-num">{{ t.retryCount ?? '-' }}</td>
            <td class="rtt-dim rtt-time">{{ formatTime(t.updatedAt) }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="rtt-pager">
      <button type="button" class="rtt-btn-ghost" :disabled="page <= 1 || loading" @click="$emit('change-page', page - 1)">上一页</button>
      <span class="rtt-page-info">第 {{ page }} 页</span>
      <button type="button" class="rtt-btn-ghost" :disabled="!hasMore || loading" @click="$emit('change-page', page + 1)">下一页</button>
    </div>
  </div>
</template>

<script setup>
import { reactive, watch } from 'vue'

const props = defineProps({
  tasks: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  page: { type: Number, default: 1 },
  total: { type: Number, default: 0 },
  hasMore: { type: Boolean, default: false },
  statusOptions: { type: Array, default: () => [] }
})

const emit = defineEmits(['apply-filter', 'reset-filter', 'change-page', 'select'])

const local = reactive({ status: '', studentId: '', provider: '', overdueOnly: false })

watch(() => props.tasks, () => {}, { immediate: true })

function emitFilters() {
  const filter = {
    status: local.status || '',
    studentId: local.studentId ? String(local.studentId).trim() : '',
    provider: local.provider ? local.provider.trim() : '',
    overdueOnly: Boolean(local.overdueOnly)
  }
  emit('apply-filter', filter)
}

function emitReset() {
  local.status = ''
  local.studentId = ''
  local.provider = ''
  local.overdueOnly = false
  emit('reset-filter')
}

function statusLabel(status) {
  return ({ queued: '排队', preparing: '准备', running: '运行', waiting_approval: '待审批',
    waiting_user: '待用户', waiting_workspace: '等工作区', waiting_environment: '等环境',
    waiting_recovery: '等恢复', recovering: '恢复中', retrying: '重试中', cancelling: '取消中',
    cancelled: '已取消', failed: '失败', completed: '完成' })[status] || status || '未知'
}

function statusClass(status) {
  if (['failed', 'cancelled'].includes(status)) return 'rtt-status-down'
  if (['completed'].includes(status)) return 'rtt-status-up'
  if (status && status.startsWith('waiting')) return 'rtt-status-degraded'
  return 'rtt-status-run'
}

function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '-'
}
</script>

<style scoped>
.rtt-card { background: #fff; border: 1px solid #eef0f3; border-radius: 12px; padding: 14px 16px; }
.rtt-title { display: flex; align-items: center; justify-content: space-between; font-size: 13px; font-weight: 600; color: #374151; margin-bottom: 10px; }
.rtt-total { font-size: 12px; font-weight: 400; color: #9ca3af; }
.rtt-filters { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.rtt-input { border: 1px solid #e5e7eb; border-radius: 8px; padding: 5px 10px; font-size: 12px; font-family: inherit; color: #374151; background: #fff; }
.rtt-narrow { width: 90px; }
.rtt-check { display: inline-flex; align-items: center; gap: 4px; font-size: 12px; color: #475569; }
.rtt-btn { border: 1px solid #4f46e5; background: #4f46e5; color: #fff; padding: 5px 14px; border-radius: 8px; font-size: 12px; font-family: inherit; cursor: pointer; }
.rtt-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.rtt-btn-ghost { border: 1px solid #e5e7eb; background: #fff; padding: 5px 12px; border-radius: 8px; font-size: 12px; font-family: inherit; color: #374151; cursor: pointer; }
.rtt-btn-ghost:hover { background: #f9fafb; }
.rtt-btn-ghost:disabled { opacity: 0.5; cursor: not-allowed; }
.rtt-loading { font-size: 12px; color: #6b7280; }
.rtt-error { font-size: 12px; color: #dc2626; padding: 8px 0; }
.rtt-empty { font-size: 12px; color: #9ca3af; padding: 14px 0; text-align: center; }
.rtt-table { overflow: auto; max-height: 420px; }
.rtt-table table { width: 100%; border-collapse: collapse; font-size: 12px; }
.rtt-table th { text-align: left; padding: 8px 10px; color: #6b7280; font-weight: 500; background: #f9fafb; border-bottom: 1px solid #f0f2f5; position: sticky; top: 0; }
.rtt-table td { padding: 8px 10px; border-bottom: 1px solid #f6f8fa; color: #374151; white-space: nowrap; }
.rtt-row { cursor: pointer; }
.rtt-row:hover td { background: #f8fafc; }
.rtt-id { font-weight: 600; color: #111827; }
.rtt-sub { font-size: 11px; color: #9ca3af; }
.rtt-dim { color: #6b7280; }
.rtt-num { color: #6b7280; font-variant-numeric: tabular-nums; }
.rtt-time { font-variant-numeric: tabular-nums; }
.rtt-status { display: inline-block; min-width: 46px; text-align: center; padding: 2px 8px; border-radius: 999px; font-size: 11px; font-weight: 600; }
.rtt-status-run { background: #eff6ff; color: #2563eb; }
.rtt-status-up { background: #ecfdf5; color: #059669; }
.rtt-status-down { background: #fef2f2; color: #dc2626; }
.rtt-status-degraded { background: #fffbeb; color: #d97706; }
.rtt-overdue { display: inline-block; margin-left: 6px; padding: 1px 6px; border-radius: 999px; font-size: 10px; background: #fef2f2; color: #dc2626; }
.rtt-pager { display: flex; align-items: center; justify-content: flex-end; gap: 10px; margin-top: 10px; }
.rtt-page-info { font-size: 12px; color: #6b7280; }
</style>
