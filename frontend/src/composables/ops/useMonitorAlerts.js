import { ref } from 'vue'
import { monitorApi } from '@/api'
import { useMonitorOperator } from '@/composables/ops/useMonitorOperator'

export function useMonitorAlerts() {
  const { withOperator } = useMonitorOperator()
  const alerts = ref([])
  const total = ref(0)
  const page = ref(1)
  const pageSize = ref(20)
  const status = ref('')
  const loading = ref(false)
  const error = ref('')
  const actingId = ref(null)

  const rules = ref([])
  const rulesLoading = ref(false)
  const rulesError = ref('')

  async function load({ page: nextPage = page.value, status: nextStatus = status.value } = {}) {
    page.value = nextPage
    status.value = nextStatus
    loading.value = true
    error.value = ''
    try {
      const data = await monitorApi.alerts({
        page: page.value, pageSize: pageSize.value,
        status: status.value || undefined
      })
      alerts.value = data.records || []
      total.value = data.total || 0
    } catch (e) {
      error.value = e?.message || '告警加载失败'
      if (e?.status === 401) throw e
    } finally {
      loading.value = false
    }
  }

  async function loadRules() {
    rulesLoading.value = true
    rulesError.value = ''
    try {
      rules.value = await monitorApi.alertRules()
    } catch (e) {
      rulesError.value = e?.message || '规则加载失败'
      if (e?.status === 401) throw e
    } finally {
      rulesLoading.value = false
    }
  }

  async function saveRule(ruleId, body) {
    const saved = ruleId
      ? await monitorApi.updateAlertRule(ruleId, body)
      : await monitorApi.createAlertRule(body)
    await loadRules()
    return saved
  }

  async function deleteRule(ruleId) {
    await monitorApi.deleteAlertRule(ruleId)
    await loadRules()
  }

  async function act(action, alertId, payload) {
    actingId.value = alertId
    try {
      await withOperator((body) => action(alertId, payload, body))
      await load()
      return true
    } catch (e) {
      if (e?.status === 401) throw e
      error.value = e?.message || '操作失败'
      return false
    } finally {
      actingId.value = null
    }
  }

  const acknowledge = (alertId) => act(monitorApi.acknowledgeAlert, alertId)
  const resolve = (alertId, reason) => act(monitorApi.resolveAlert, alertId, reason)
  const silence = (alertId, minutes) => act(monitorApi.silenceAlert, alertId, minutes)

  return {
    alerts, total, page, pageSize, status, loading, error, actingId,
    rules, rulesLoading, rulesError,
    load, loadRules, saveRule, deleteRule, acknowledge, resolve, silence
  }
}
