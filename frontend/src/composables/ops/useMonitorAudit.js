import { ref } from 'vue'
import { monitorApi } from '@/api'

export function useMonitorAudit() {
  const records = ref([])
  const total = ref(0)
  const page = ref(1)
  const pageSize = ref(20)
  const loading = ref(false)
  const error = ref('')
  const filters = ref({ operatorId: '', actionType: '', result: '' })

  async function load() {
    loading.value = true
    error.value = ''
    try {
      const params = {
        page: page.value, pageSize: pageSize.value,
        operatorId: filters.value.operatorId || undefined,
        actionType: filters.value.actionType || undefined,
        result: filters.value.result || undefined
      }
      const data = await monitorApi.auditLogs(params)
      records.value = data.records || []
      total.value = data.total || 0
    } catch (e) {
      error.value = e?.message || '审计日志加载失败'
      if (e?.status === 401) throw e
    } finally {
      loading.value = false
    }
  }

  return { records, total, page, pageSize, loading, error, filters, load }
}
