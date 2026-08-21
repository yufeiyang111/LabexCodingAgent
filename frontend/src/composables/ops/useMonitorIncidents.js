import { ref } from 'vue'
import { monitorApi } from '@/api'
import { useMonitorOperator } from '@/composables/ops/useMonitorOperator'

export function useMonitorIncidents() {
  const { withOperator } = useMonitorOperator()
  const incidents = ref([])
  const total = ref(0)
  const page = ref(1)
  const pageSize = ref(20)
  const status = ref('')
  const loading = ref(false)
  const error = ref('')
  const detail = ref(null)
  const timeline = ref([])
  const timelineLoading = ref(false)
  const actingId = ref(null)

  async function load({ page: nextPage = page.value, status: nextStatus = status.value } = {}) {
    page.value = nextPage
    status.value = nextStatus
    loading.value = true
    error.value = ''
    try {
      const data = await monitorApi.incidents({
        page: page.value, pageSize: pageSize.value,
        status: status.value || undefined
      })
      incidents.value = data.records || []
      total.value = data.total || 0
    } catch (e) {
      error.value = e?.message || '故障加载失败'
      if (e?.status === 401) throw e
    } finally {
      loading.value = false
    }
  }

  async function open(incidentId) {
    try {
      detail.value = await monitorApi.incident(incidentId)
      await loadTimeline(incidentId)
    } catch (e) {
      if (e?.status === 401) throw e
      error.value = e?.message || '故障详情加载失败'
    }
  }

  function close() {
    detail.value = null
    timeline.value = []
  }

  async function loadTimeline(incidentId) {
    timelineLoading.value = true
    try {
      timeline.value = await monitorApi.incidentTimeline(incidentId)
    } catch (e) {
      if (e?.status === 401) throw e
    } finally {
      timelineLoading.value = false
    }
  }

  async function create(body) {
    await withOperator((opBody) => monitorApi.createIncident({ ...body, ...opBody }))
    await load()
  }

  async function transition(incidentId, target) {
    actingId.value = incidentId
    try {
      await withOperator((opBody) => monitorApi.incidentTransition(incidentId, target, opBody))
      await load()
      if (detail.value && detail.value.incidentId === incidentId) {
        await open(incidentId)
      }
      return true
    } catch (e) {
      if (e?.status === 401) throw e
      error.value = e?.message || '状态迁移失败'
      return false
    } finally {
      actingId.value = null
    }
  }

  return {
    incidents, total, page, pageSize, status, loading, error,
    detail, timeline, timelineLoading, actingId,
    load, open, close, create, transition
  }
}
