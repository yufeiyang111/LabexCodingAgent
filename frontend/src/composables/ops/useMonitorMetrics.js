import { ref } from 'vue'
import { monitorApi } from '@/api'

const RANGES = [
  { key: '1h', label: '1小时' },
  { key: '24h', label: '24小时' },
  { key: '7d', label: '7天' },
  { key: '30d', label: '30天' }
]

export function useMonitorMetrics() {
  const overview = ref(null)
  const loading = ref(false)
  const error = ref('')
  const range = ref('24h')
  const series = ref([])
  const seriesLoading = ref(false)
  const seriesError = ref('')

  async function loadOverview() {
    loading.value = true
    error.value = ''
    try {
      overview.value = await monitorApi.metricsOverview()
      return overview.value
    } catch (e) {
      if (e?.code === 404) {
        error.value = '暂无指标数据，等待首次采样'
      } else {
        error.value = e?.message || '指标概览加载失败'
      }
      if (e?.status === 401) throw e
      return null
    } finally {
      loading.value = false
    }
  }

  async function loadSeries(nextRange = range.value) {
    range.value = nextRange
    seriesLoading.value = true
    seriesError.value = ''
    try {
      series.value = await monitorApi.metricsTimeseries(nextRange)
      return series.value
    } catch (e) {
      seriesError.value = e?.message || '指标趋势加载失败'
      if (e?.status === 401) throw e
      return []
    } finally {
      seriesLoading.value = false
    }
  }

  return {
    overview, loading, error, range, series, seriesLoading, seriesError,
    loadOverview, loadSeries, ranges: RANGES
  }
}
