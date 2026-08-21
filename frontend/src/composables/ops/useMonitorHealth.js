import { ref } from 'vue'
import { monitorApi } from '@/api'

const HEALTH_POLL_INTERVAL_MS = 30000

export function useMonitorHealth() {
  const loading = ref(false)
  const error = ref('')
  const summary = ref(null)
  const dependencies = ref([])
  const lastCheckedAt = ref(null)
  let timer = null

  async function load() {
    loading.value = true
    error.value = ''
    try {
      const data = await monitorApi.healthSummary()
      summary.value = data
      dependencies.value = data?.dependencies || []
      lastCheckedAt.value = data?.checkedAt || null
      return data
    } catch (e) {
      error.value = e?.message || '健康检查加载失败'
      summary.value = null
      dependencies.value = []
      if (e?.status === 401) {
        throw e
      }
      return null
    } finally {
      loading.value = false
    }
  }

  function startPolling() {
    stopPolling()
    timer = setInterval(() => {
      void load()
    }, HEALTH_POLL_INTERVAL_MS)
  }

  function stopPolling() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  return { loading, error, summary, dependencies, lastCheckedAt, load, startPolling, stopPolling }
}
