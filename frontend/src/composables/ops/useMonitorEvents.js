import { ref } from 'vue'
import { monitorApi } from '@/api'

export function useMonitorEvents() {
  const events = ref([])
  const total = ref(0)
  const page = ref(1)
  const pageSize = ref(20)
  const loading = ref(false)
  const error = ref('')
  const filters = ref({ eventType: '', severity: '' })

  async function load() {
    loading.value = true
    error.value = ''
    try {
      const params = {
        page: page.value, pageSize: pageSize.value,
        eventType: filters.value.eventType || undefined,
        severity: filters.value.severity || undefined
      }
      const data = await monitorApi.events(params)
      events.value = data.records || []
      total.value = data.total || 0
    } catch (e) {
      error.value = e?.message || '事件流加载失败'
      if (e?.status === 401) throw e
    } finally {
      loading.value = false
    }
  }

  return { events, total, page, pageSize, loading, error, filters, load }
}
