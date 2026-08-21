import { ref } from 'vue'
import { monitorApi } from '@/api'

const VALID_STATUSES = [
  'queued', 'preparing', 'running', 'waiting_approval', 'waiting_user',
  'waiting_workspace', 'waiting_environment', 'waiting_recovery', 'recovering',
  'retrying', 'cancelling', 'cancelled', 'failed', 'completed'
]

export function useMonitorRuntime() {
  const tasks = ref([])
  const loading = ref(false)
  const error = ref('')
  const page = ref(1)
  const pageSize = ref(20)
  const total = ref(0)
  const hasMore = ref(false)
  const filters = ref({ status: '', studentId: '', provider: '', overdueOnly: false })

  const detail = ref(null)
  const detailLoading = ref(false)
  const detailError = ref('')
  const events = ref([])
  const eventsLoading = ref(false)
  const eventsError = ref('')

  async function loadTasks() {
    loading.value = true
    error.value = ''
    try {
      const params = { page: page.value, pageSize: pageSize.value }
      if (filters.value.status) params.status = filters.value.status
      if (filters.value.studentId) params.studentId = filters.value.studentId
      if (filters.value.provider) params.provider = filters.value.provider
      if (filters.value.overdueOnly) params.overdueOnly = true
      const data = await monitorApi.runtimeTasks(params)
      tasks.value = data?.items || []
      total.value = data?.total || 0
      hasMore.value = Boolean(data?.hasMore)
      return data
    } catch (e) {
      error.value = e?.message || '任务列表加载失败'
      tasks.value = []
      if (e?.status === 401) throw e
      return null
    } finally {
      loading.value = false
    }
  }

  function applyFilters(next) {
    filters.value = { ...filters.value, ...next }
    page.value = 1
    return loadTasks()
  }

  function changePage(next) {
    page.value = next
    return loadTasks()
  }

  function changePageSize(size) {
    pageSize.value = size
    page.value = 1
    return loadTasks()
  }

  function resetFilters() {
    filters.value = { status: '', studentId: '', provider: '', overdueOnly: false }
    page.value = 1
    return loadTasks()
  }

  async function selectTask(taskId) {
    detail.value = null
    events.value = []
    detailError.value = ''
    eventsError.value = ''
    detailLoading.value = true
    eventsLoading.value = true
    try {
      const [d, tl] = await Promise.all([
        monitorApi.runtimeTask(taskId),
        monitorApi.runtimeTimeline(taskId, { limit: 50 })
      ])
      detail.value = d
      events.value = tl?.items || []
      return d
    } catch (e) {
      if (e?.status === 404) {
        detailError.value = '任务不存在或已删除'
      } else {
        detailError.value = e?.message || '任务详情加载失败'
        eventsError.value = e?.message || '事件时间线加载失败'
      }
      throw e
    } finally {
      detailLoading.value = false
      eventsLoading.value = false
    }
  }

  return {
    tasks, loading, error, page, pageSize, total, hasMore, filters,
    detail, detailLoading, detailError, events, eventsLoading, eventsError,
    loadTasks, applyFilters, changePage, changePageSize, resetFilters, selectTask,
    statusOptions: VALID_STATUSES
  }
}
