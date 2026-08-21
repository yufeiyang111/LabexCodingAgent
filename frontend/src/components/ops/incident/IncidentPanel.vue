<template>
  <div class="ipa">
    <div class="ipa-head">
      <span class="ipa-title">故障</span>
      <button class="ipa-add" @click="createVisible = true">新建故障</button>
    </div>
    <div class="ipa-toolbar">
      <select v-model="localStatus" name="incident-status" class="ipa-select" @change="onFilter">
        <option value="">全部状态</option>
        <option v-for="(meta, key) in INCIDENT_STATUSES" :key="key" :value="key">{{ meta.label }}</option>
      </select>
      <button class="ipa-btn" @click="$emit('refresh')">刷新</button>
    </div>
    <OpsStateBlock :loading="loading" :error="error" :empty="incidents.length === 0" empty-text="暂无故障">
      <table class="ipa-table">
        <thead>
          <tr>
            <th>标题</th>
            <th>状态</th>
            <th>严重度</th>
            <th>关联告警</th>
            <th>开启时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="inc in incidents" :key="inc.incidentId" @click="onOpen(inc)">
            <td class="ipa-title-cell" :title="inc.summary">{{ inc.title }}</td>
            <td><OpsStatusTag :text="(INCIDENT_STATUSES[inc.status] || {}).label || inc.status" :type="(INCIDENT_STATUSES[inc.status] || {}).type" /></td>
            <td><OpsStatusTag :text="(SEVERITIES[inc.severity] || {}).label || inc.severity" :type="(SEVERITIES[inc.severity] || {}).type" /></td>
            <td>{{ inc.sourceAlertId ? '#' + inc.sourceAlertId : '-' }}</td>
            <td class="ipa-time">{{ formatTime(inc.openedAt) }}</td>
            <td class="ipa-actions" @click.stop>
              <button v-if="inc.status === 'OPEN'" class="ipa-btn" :disabled="actingId === inc.incidentId" @click="$emit('transition', inc, 'acknowledge')">确认</button>
              <button v-if="inc.status === 'OPEN' || inc.status === 'ACKNOWLEDGED'" class="ipa-btn" :disabled="actingId === inc.incidentId" @click="$emit('transition', inc, 'mitigate')">缓解</button>
              <button v-if="inc.status !== 'RESOLVED' && inc.status !== 'CLOSED'" class="ipa-btn ipa-btn-danger" :disabled="actingId === inc.incidentId" @click="$emit('transition', inc, 'resolve')">解决</button>
              <button v-if="inc.status === 'RESOLVED'" class="ipa-btn" :disabled="actingId === inc.incidentId" @click="$emit('transition', inc, 'close')">关闭</button>
            </td>
          </tr>
        </tbody>
      </table>
      <OpsPagination :page="page" :total="total" :page-size="pageSize" @change="$emit('page-change', $event)" />
    </OpsStateBlock>

    <div v-if="detail" class="ipa-dialog">
      <div class="ipa-dialog-card">
        <div class="ipa-dialog-head">
          <div>
            <div class="ipa-dialog-title">{{ detail.title }}</div>
            <div class="ipa-dialog-meta">
              <OpsStatusTag :text="(INCIDENT_STATUSES[detail.status] || {}).label || detail.status" :type="(INCIDENT_STATUSES[detail.status] || {}).type" />
              <OpsStatusTag :text="(SEVERITIES[detail.severity] || {}).label || detail.severity" :type="(SEVERITIES[detail.severity] || {}).type" />
            </div>
          </div>
          <button class="ipa-btn" @click="$emit('close-detail')">关闭</button>
        </div>
        <div class="ipa-detail-grid">
          <div class="ipa-detail-item"><span>关联告警</span>{{ detail.sourceAlertId ? '#' + detail.sourceAlertId : '-' }}</div>
          <div class="ipa-detail-item"><span>开启</span>{{ formatTime(detail.openedAt) }}</div>
          <div class="ipa-detail-item"><span>确认</span>{{ formatTime(detail.acknowledgedAt) }}</div>
          <div class="ipa-detail-item"><span>解决</span>{{ formatTime(detail.resolvedAt) }}</div>
          <div class="ipa-detail-item"><span>关闭</span>{{ formatTime(detail.closedAt) }}</div>
          <div class="ipa-detail-item"><span>摘要</span>{{ detail.summary || '-' }}</div>
        </div>
        <div class="ipa-timeline-title">处理时间线</div>
        <OpsStateBlock :loading="timelineLoading" :empty="timeline.length === 0" empty-text="暂无时间线记录">
          <div class="ipa-timeline">
            <div v-for="ev in timeline" :key="ev.eventId" class="ipa-tl-item">
              <span class="ipa-tl-dot"></span>
              <div class="ipa-tl-body">
                <div class="ipa-tl-head">
                  <span class="ipa-tl-type">{{ eventLabel(ev.eventType) }}</span>
                  <span class="ipa-tl-time">{{ formatTime(ev.createTime) }}</span>
                </div>
                <div class="ipa-tl-msg">{{ ev.message || '-' }}</div>
                <div v-if="ev.operator" class="ipa-tl-op">操作者：{{ ev.operator }}</div>
              </div>
            </div>
          </div>
        </OpsStateBlock>
      </div>
    </div>

    <div v-if="createVisible" class="ipa-dialog">
      <div class="ipa-dialog-card">
        <div class="ipa-dialog-title">新建故障</div>
        <div class="ipa-form">
          <label class="ipa-field"><span>标题</span><input v-model="createForm.title" name="incident-title" maxlength="256" /></label>
          <label class="ipa-field"><span>严重度</span>
            <select v-model="createForm.severity" name="incident-severity">
              <option v-for="s in SEVERITY_OPTIONS" :key="s.value" :value="s.value">{{ s.label }}</option>
            </select>
          </label>
          <label class="ipa-field"><span>关联告警 ID（可选）</span><input v-model.number="createForm.sourceAlertId" name="incident-source-alert" type="number" min="1" /></label>
          <label class="ipa-field ipa-field-wide"><span>摘要</span><input v-model="createForm.summary" name="incident-summary" maxlength="2048" /></label>
        </div>
        <p v-if="createError" class="ipa-error">{{ createError }}</p>
        <div class="ipa-dialog-actions">
          <button class="ipa-btn" @click="createVisible = false">取消</button>
          <button class="ipa-btn ipa-btn-primary" :disabled="creating" @click="submitCreate">{{ creating ? '创建中…' : '创建' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'
import { INCIDENT_STATUSES, SEVERITIES, SEVERITY_OPTIONS, EVENT_TYPES } from '@/constants/ops'
import OpsStateBlock from '@/components/ops/ui/OpsStateBlock.vue'
import OpsPagination from '@/components/ops/ui/OpsPagination.vue'
import OpsStatusTag from '@/components/ops/ui/OpsStatusTag.vue'

const props = defineProps({
  incidents: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  page: { type: Number, default: 1 },
  total: { type: Number, default: 0 },
  pageSize: { type: Number, default: 20 },
  status: { type: String, default: '' },
  detail: { type: Object, default: null },
  timeline: { type: Array, default: () => [] },
  timelineLoading: { type: Boolean, default: false },
  actingId: { type: [Number, null], default: null }
})

const emit = defineEmits(['refresh', 'filter', 'page-change', 'open', 'close-detail', 'transition', 'create'])

const localStatus = ref(props.status)
watch(() => props.status, (v) => { localStatus.value = v })

const createVisible = ref(false)
const creating = ref(false)
const createError = ref('')
const createForm = reactive({ title: '', severity: 'warning', sourceAlertId: null, summary: '' })

function onFilter() {
  emit('filter', localStatus.value)
}

function onOpen(inc) {
  emit('open', inc.incidentId)
}

async function submitCreate() {
  if (!createForm.title.trim()) { createError.value = '请输入标题'; return }
  creating.value = true
  createError.value = ''
  try {
    await emit('create', { ...createForm, sourceAlertId: createForm.sourceAlertId || undefined })
    createVisible.value = false
    createForm.title = ''
    createForm.summary = ''
    createForm.sourceAlertId = null
  } catch (e) {
    createError.value = e?.message || '创建失败'
  } finally {
    creating.value = false
  }
}

function eventLabel(type) {
  return (EVENT_TYPES.find(t => t.value === type) || {}).label || type
}

function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '-'
}
</script>

<style scoped>
.ipa { background: #fff; border: 1px solid #eef0f3; border-radius: 12px; padding: 14px 16px; }
.ipa-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.ipa-title { font-size: 13px; font-weight: 600; color: #374151; }
.ipa-add { border: 0; background: #4f46e5; color: #fff; padding: 5px 12px; border-radius: 8px; font-size: 12px; font-family: inherit; cursor: pointer; }
.ipa-toolbar { display: flex; gap: 8px; margin-bottom: 10px; }
.ipa-select { padding: 6px 10px; border: 1px solid #e5e7eb; border-radius: 8px; font-size: 12px; font-family: inherit; color: #374151; background: #fff; }
.ipa-btn { border: 1px solid #e5e7eb; background: #fff; padding: 4px 10px; border-radius: 6px; font-size: 11px; font-family: inherit; color: #374151; cursor: pointer; }
.ipa-btn:hover { background: #f9fafb; }
.ipa-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.ipa-btn-primary { background: #4f46e5; border-color: #4f46e5; color: #fff; }
.ipa-btn-danger { color: #dc2626; border-color: #fecaca; }
.ipa-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.ipa-table th { text-align: left; padding: 8px 10px; color: #6b7280; font-weight: 500; background: #f9fafb; border-bottom: 1px solid #f0f2f5; }
.ipa-table td { padding: 8px 10px; border-bottom: 1px solid #f6f8fa; color: #374151; white-space: nowrap; cursor: pointer; }
.ipa-title-cell { max-width: 320px; overflow: hidden; text-overflow: ellipsis; }
.ipa-time { color: #6b7280; font-variant-numeric: tabular-nums; }
.ipa-actions { display: flex; gap: 6px; }
.ipa-dialog { position: fixed; inset: 0; background: rgba(15, 23, 42, 0.4); display: flex; align-items: center; justify-content: center; z-index: 40; padding: 20px; }
.ipa-dialog-card { background: #fff; border-radius: 14px; width: 640px; max-width: 100%; max-height: 88vh; overflow: auto; padding: 20px; }
.ipa-dialog-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; margin-bottom: 12px; }
.ipa-dialog-title { font-size: 15px; font-weight: 600; color: #111827; margin-bottom: 6px; }
.ipa-dialog-meta { display: flex; gap: 6px; }
.ipa-detail-grid { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 8px; font-size: 12px; color: #374151; }
.ipa-detail-item span { display: block; font-size: 11px; color: #9ca3af; }
.ipa-timeline-title { font-size: 13px; font-weight: 600; color: #374151; margin: 16px 0 8px; }
.ipa-timeline { display: flex; flex-direction: column; }
.ipa-tl-item { display: flex; gap: 10px; position: relative; padding-bottom: 12px; }
.ipa-tl-item:not(:last-child)::before { content: ''; position: absolute; left: 4px; top: 12px; bottom: 0; width: 2px; background: #eef0f3; }
.ipa-tl-dot { width: 10px; height: 10px; border-radius: 50%; background: #6366f1; margin-top: 4px; flex-shrink: 0; }
.ipa-tl-body { flex: 1; }
.ipa-tl-head { display: flex; justify-content: space-between; gap: 8px; }
.ipa-tl-type { font-size: 12px; font-weight: 600; color: #374151; }
.ipa-tl-time { font-size: 11px; color: #9ca3af; }
.ipa-tl-msg { font-size: 12px; color: #6b7280; margin-top: 2px; }
.ipa-tl-op { font-size: 11px; color: #9ca3af; margin-top: 2px; }
.ipa-form { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.ipa-field { display: flex; flex-direction: column; gap: 4px; font-size: 12px; }
.ipa-field span { font-size: 11px; color: #6b7280; }
.ipa-field input, .ipa-field select { padding: 7px 10px; border: 1px solid #e5e7eb; border-radius: 8px; font-size: 12px; font-family: inherit; }
.ipa-field-wide { grid-column: 1 / -1; }
.ipa-error { color: #dc2626; font-size: 12px; margin: 10px 0 0; }
.ipa-dialog-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }
</style>
