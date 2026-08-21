<template>
  <div class="rtd-card">
    <div class="rtd-head">
      <span class="rtd-title">任务详情</span>
      <button type="button" class="rtd-close" @click="$emit('close')">✕</button>
    </div>

    <div v-if="detailError" class="rtd-error">{{ detailError }}</div>

    <div v-else-if="loading && !detail" class="rtd-empty">正在加载详情…</div>

    <div v-else-if="!detail" class="rtd-empty">点击左侧任务查看详情</div>

    <div v-else class="rtd-body">
      <div class="rtd-badge-row">
        <span class="rtd-status" :class="statusClass(detail.currentStatus)">{{ statusLabel(detail.currentStatus) }}</span>
        <span v-if="detail.waitingReason" class="rtd-waiting">⏳ {{ detail.waitingReason }}</span>
      </div>

      <div class="rtd-grid">
        <div class="rtd-item"><span class="rtd-key">任务 ID</span>{{ detail.taskId }}</div>
        <div class="rtd-item"><span class="rtd-key">会话</span>{{ detail.conversationId || '-' }}</div>
        <div class="rtd-item"><span class="rtd-key">模式</span>{{ detail.mode || '-' }}</div>
        <div class="rtd-item"><span class="rtd-key">当前步骤</span>{{ detail.currentStep || '-' }}</div>
        <div class="rtd-item"><span class="rtd-key">Provider</span>{{ detail.provider || '-' }} {{ detail.modelName || '' }}</div>
        <div class="rtd-item"><span class="rtd-key">执行 epoch</span>{{ detail.executionEpoch ?? '-' }}</div>
        <div class="rtd-item"><span class="rtd-key">租约 owner</span>{{ detail.leaseOwner || '-' }}</div>
        <div class="rtd-item"><span class="rtd-key">租约到期</span>{{ formatTime(detail.leaseExpireAt) }}</div>
        <div class="rtd-item"><span class="rtd-key">最后心跳</span>{{ formatTime(detail.lastHeartbeatAt) }}</div>
        <div class="rtd-item"><span class="rtd-key">重试 / 恢复</span>{{ detail.retryCount ?? '-' }} / {{ detail.recoveryAttempts ?? '-' }}</div>
        <div class="rtd-item"><span class="rtd-key">提交时间</span>{{ formatTime(detail.submittedAt) }}</div>
        <div class="rtd-item"><span class="rtd-key">开始时间</span>{{ formatTime(detail.startedAt) }}</div>
        <div class="rtd-item"><span class="rtd-key">最新事件</span>{{ detail.latestEvent || '-' }}</div>
        <div class="rtd-item"><span class="rtd-key">事件序号</span>{{ detail.lastEventSequence ?? '-' }}</div>
        <div v-if="detail.failureReason" class="rtd-item rtd-item-wide">
          <span class="rtd-key">失败原因</span>
          <span class="rtd-failure">{{ detail.failureReason }}</span>
        </div>
      </div>

      <div v-if="detail.summary" class="rtd-summary">
        <span class="rtd-key">任务摘要</span>
        <div class="rtd-summary-text">{{ detail.summary }}</div>
      </div>
    </div>
  </div>
</template>

<script setup>
defineProps({
  detail: { type: Object, default: null },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' }
})

defineEmits(['close'])

function statusLabel(status) {
  return ({ queued: '排队', preparing: '准备', running: '运行', waiting_approval: '待审批',
    waiting_user: '待用户', waiting_workspace: '等工作区', waiting_environment: '等环境',
    waiting_recovery: '等恢复', recovering: '恢复中', retrying: '重试中', cancelling: '取消中',
    cancelled: '已取消', failed: '失败', completed: '完成' })[status] || status || '未知'
}

function statusClass(status) {
  if (['failed', 'cancelled'].includes(status)) return 'rtd-status-down'
  if (['completed'].includes(status)) return 'rtd-status-up'
  if (status && status.startsWith('waiting')) return 'rtd-status-degraded'
  return 'rtd-status-run'
}

function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '-'
}
</script>

<style scoped>
.rtd-card { background: #fff; border: 1px solid #eef0f3; border-radius: 12px; padding: 14px 16px; }
.rtd-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.rtd-title { font-size: 13px; font-weight: 600; color: #374151; }
.rtd-close { border: 0; background: transparent; color: #9ca3af; font-size: 14px; cursor: pointer; }
.rtd-error { font-size: 12px; color: #dc2626; padding: 8px 0; }
.rtd-empty { font-size: 12px; color: #9ca3af; padding: 14px 0; text-align: center; }
.rtd-badge-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 12px; }
.rtd-status { display: inline-block; min-width: 52px; text-align: center; padding: 3px 10px; border-radius: 999px; font-size: 12px; font-weight: 600; }
.rtd-status-run { background: #eff6ff; color: #2563eb; }
.rtd-status-up { background: #ecfdf5; color: #059669; }
.rtd-status-down { background: #fef2f2; color: #dc2626; }
.rtd-status-degraded { background: #fffbeb; color: #d97706; }
.rtd-waiting { font-size: 12px; color: #d97706; background: #fffbeb; padding: 3px 10px; border-radius: 999px; }
.rtd-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px 16px; font-size: 12px; color: #374151; }
.rtd-item { min-width: 0; }
.rtd-item-wide { grid-column: 1 / -1; }
.rtd-key { display: block; font-size: 11px; color: #9ca3af; margin-bottom: 1px; }
.rtd-failure { color: #dc2626; }
.rtd-summary { margin-top: 12px; border-top: 1px solid #f0f2f5; padding-top: 10px; }
.rtd-summary-text { font-size: 12px; color: #374151; margin-top: 4px; word-break: break-word; }
</style>
