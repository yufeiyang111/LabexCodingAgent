<template>
  <div class="arp">
    <div class="arp-head">
      <span class="arp-title">告警规则</span>
      <button class="arp-add" @click="openCreate">新增规则</button>
    </div>
    <OpsStateBlock :loading="loading" :error="error" :empty="rules.length === 0" empty-text="暂无规则">
      <table class="arp-table">
        <thead>
          <tr>
            <th>名称</th>
            <th>指标</th>
            <th>条件</th>
            <th>窗口/冷却</th>
            <th>严重度</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="rule in rules" :key="rule.ruleId">
            <td class="arp-name" :title="rule.description">{{ rule.name }}</td>
            <td>{{ metricLabel(rule.metricKey) }}</td>
            <td class="arp-num">{{ rule.operator }} {{ fmt(rule.threshold) }}</td>
            <td class="arp-num">{{ rule.durationMinutes }}m / {{ rule.cooldownMinutes }}m</td>
            <td><OpsStatusTag :text="(SEVERITIES[rule.severity] || {}).label || rule.severity" :type="(SEVERITIES[rule.severity] || {}).type" /></td>
            <td><OpsStatusTag :text="rule.enabled ? '启用' : '停用'" :type="rule.enabled ? 'success' : 'info'" /></td>
            <td class="arp-actions">
              <button class="arp-btn" @click="openEdit(rule)">编辑</button>
              <OpsConfirmButton :label="rule.enabled ? '停用' : '启用'" variant="default" @confirm="$emit('toggle', rule)" />
              <OpsConfirmButton label="删除" danger confirm-text="确认删除?" @confirm="$emit('delete', rule)" />
            </td>
          </tr>
        </tbody>
      </table>
    </OpsStateBlock>

    <div v-if="formVisible" class="arp-dialog">
      <div class="arp-dialog-card">
        <div class="arp-dialog-title">{{ editing ? '编辑规则' : '新增规则' }}</div>
        <div class="arp-form">
          <label class="arp-field"><span>名称</span><input v-model="form.name" name="rule-name" maxlength="128" /></label>
          <label class="arp-field"><span>指标</span>
            <select v-model="form.metricKey" name="rule-metric-key">
              <option v-for="m in ALERT_METRIC_KEYS" :key="m.value" :value="m.value">{{ m.label }}</option>
            </select>
          </label>
          <label class="arp-field"><span>比较符</span>
            <select v-model="form.operator" name="rule-operator">
              <option v-for="o in ALERT_OPERATORS" :key="o.value" :value="o.value">{{ o.label }}</option>
            </select>
          </label>
          <label class="arp-field"><span>阈值</span><input v-model.number="form.threshold" name="rule-threshold" type="number" step="0.1" /></label>
          <label class="arp-field"><span>评估窗口(分)</span><input v-model.number="form.durationMinutes" name="rule-duration" type="number" min="1" max="1440" /></label>
          <label class="arp-field"><span>冷却(分)</span><input v-model.number="form.cooldownMinutes" name="rule-cooldown" type="number" min="0" max="10080" /></label>
          <label class="arp-field"><span>严重度</span>
            <select v-model="form.severity" name="rule-severity">
              <option v-for="s in SEVERITY_OPTIONS" :key="s.value" :value="s.value">{{ s.label }}</option>
            </select>
          </label>
          <label class="arp-field arp-field-wide"><span>描述</span><input v-model="form.description" name="rule-description" maxlength="512" /></label>
          <label class="arp-field arp-field-wide arp-field-inline"><span>启用</span>
            <input v-model="form.enabled" type="checkbox" name="rule-enabled" class="arp-check" />
          </label>
        </div>
        <p v-if="formError" class="arp-error">{{ formError }}</p>
        <div class="arp-dialog-actions">
          <button class="arp-btn" @click="formVisible = false">取消</button>
          <button class="arp-btn arp-btn-primary" :disabled="saving" @click="submit">{{ saving ? '保存中…' : '保存' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { ALERT_METRIC_KEYS, ALERT_OPERATORS, SEVERITIES, SEVERITY_OPTIONS } from '@/constants/ops'
import OpsStateBlock from '@/components/ops/ui/OpsStateBlock.vue'
import OpsStatusTag from '@/components/ops/ui/OpsStatusTag.vue'
import OpsConfirmButton from '@/components/ops/ui/OpsConfirmButton.vue'

const props = defineProps({
  rules: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' }
})

const emit = defineEmits(['save', 'toggle', 'delete'])

const formVisible = ref(false)
const editing = ref(null)
const saving = ref(false)
const formError = ref('')
const form = reactive(emptyForm())

function emptyForm() {
  return {
    name: '', metricKey: 'cpu_percent', operator: 'GT', threshold: 90,
    durationMinutes: 5, cooldownMinutes: 30, severity: 'warning',
    description: '', enabled: true
  }
}

function openCreate() {
  editing.value = null
  Object.assign(form, emptyForm())
  formError.value = ''
  formVisible.value = true
}

function openEdit(rule) {
  editing.value = rule
  Object.assign(form, {
    name: rule.name, metricKey: rule.metricKey, operator: rule.operator,
    threshold: rule.threshold, durationMinutes: rule.durationMinutes,
    cooldownMinutes: rule.cooldownMinutes, severity: rule.severity,
    description: rule.description, enabled: !!rule.enabled
  })
  formError.value = ''
  formVisible.value = true
}

async function submit() {
  saving.value = true
  formError.value = ''
  try {
    await emit('save', editing.value?.ruleId || null, { ...form })
    formVisible.value = false
  } catch (e) {
    formError.value = e?.message || '保存失败'
  } finally {
    saving.value = false
  }
}

function metricLabel(key) {
  return (ALERT_METRIC_KEYS.find(m => m.value === key) || {}).label || key
}

function fmt(value) {
  return value == null ? '-' : Number(value).toFixed(1)
}
</script>

<style scoped>
.arp { background: #fff; border: 1px solid #eef0f3; border-radius: 12px; padding: 14px 16px; }
.arp-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.arp-title { font-size: 13px; font-weight: 600; color: #374151; }
.arp-add { border: 0; background: #4f46e5; color: #fff; padding: 5px 12px; border-radius: 8px; font-size: 12px; font-family: inherit; cursor: pointer; }
.arp-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.arp-table th { text-align: left; padding: 8px 10px; color: #6b7280; font-weight: 500; background: #f9fafb; border-bottom: 1px solid #f0f2f5; }
.arp-table td { padding: 8px 10px; border-bottom: 1px solid #f6f8fa; color: #374151; white-space: nowrap; }
.arp-name { max-width: 220px; overflow: hidden; text-overflow: ellipsis; }
.arp-num { color: #6b7280; font-variant-numeric: tabular-nums; }
.arp-actions { display: flex; gap: 6px; align-items: center; }
.arp-btn { border: 1px solid #e5e7eb; background: #fff; padding: 4px 10px; border-radius: 6px; font-size: 11px; font-family: inherit; color: #374151; cursor: pointer; }
.arp-btn-primary { background: #4f46e5; border-color: #4f46e5; color: #fff; }
.arp-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.arp-dialog { position: fixed; inset: 0; background: rgba(15, 23, 42, 0.4); display: flex; align-items: center; justify-content: center; z-index: 40; padding: 20px; }
.arp-dialog-card { background: #fff; border-radius: 14px; width: 560px; max-width: 100%; padding: 20px; }
.arp-dialog-title { font-size: 14px; font-weight: 600; color: #111827; margin-bottom: 14px; }
.arp-form { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.arp-field { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: #374151; }
.arp-field span { font-size: 11px; color: #6b7280; }
.arp-field input, .arp-field select { padding: 7px 10px; border: 1px solid #e5e7eb; border-radius: 8px; font-size: 12px; font-family: inherit; }
.arp-field-wide { grid-column: 1 / -1; }
.arp-field-inline { flex-direction: row; align-items: center; gap: 8px; }
.arp-check { width: auto; margin: 0; }
.arp-error { color: #dc2626; font-size: 12px; margin: 10px 0 0; }
.arp-dialog-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }
</style>
