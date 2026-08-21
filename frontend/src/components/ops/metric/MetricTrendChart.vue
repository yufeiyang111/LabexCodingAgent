<template>
  <div class="mtc-card">
    <div class="mtc-head">
      <span class="mtc-title">指标趋势</span>
      <div class="mtc-range-group">
        <button v-for="r in ranges" :key="r.key" type="button" class="mtc-range-btn" :class="{ active: range === r.key }" @click="$emit('range-change', r.key)">{{ r.label }}</button>
      </div>
    </div>

    <div v-if="error" class="mtc-error">{{ error }}</div>

    <div v-else-if="loading && data.length === 0" class="mtc-empty">正在加载趋势…</div>

    <div v-else-if="data.length === 0" class="mtc-empty">该时间范围内暂无采样数据</div>

    <div v-show="data.length > 0" ref="chartEl" class="mtc-chart"></div>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import echarts from '@/utils/echarts'

const props = defineProps({
  data: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  range: { type: String, default: '24h' },
  ranges: { type: Array, default: () => [] }
})

defineEmits(['range-change'])

const chartEl = ref(null)
let chart = null

function ensureChart() {
  if (!chart && chartEl.value && props.data.length > 0) {
    chart = echarts.init(chartEl.value)
  }
  return chart
}

function render() {
  const instance = ensureChart()
  if (!instance) return
  const points = props.data || []
  instance.setOption({
    tooltip: { trigger: 'axis', axisPointer: { type: 'line' } },
    legend: { data: ['CPU', '内存', '磁盘', '任务'], top: 0, textStyle: { fontSize: 11 } },
    grid: { left: 46, right: 46, top: 34, bottom: 24 },
    xAxis: { type: 'category', data: points.map(p => shortTime(p.time)), axisLabel: { fontSize: 10, color: '#6b7280' }, boundaryGap: false },
    yAxis: [
      { type: 'value', name: '%', min: 0, max: 100, axisLabel: { fontSize: 10, color: '#6b7280' }, splitLine: { lineStyle: { color: '#f3f4f6' } } },
      { type: 'value', name: '任务', min: 0, axisLabel: { fontSize: 10, color: '#6b7280' }, splitLine: { show: false } }
    ],
    series: [
      { name: 'CPU', type: 'line', smooth: true, showSymbol: false, data: points.map(p => p.cpuPercent), yAxisIndex: 0, itemStyle: { color: '#6366f1' } },
      { name: '内存', type: 'line', smooth: true, showSymbol: false, data: points.map(p => p.memoryPercent), yAxisIndex: 0, itemStyle: { color: '#f59e0b' } },
      { name: '磁盘', type: 'line', smooth: true, showSymbol: false, data: points.map(p => p.diskPercent), yAxisIndex: 0, itemStyle: { color: '#10b981' } },
      { name: '任务', type: 'line', smooth: true, showSymbol: false, data: points.map(p => p.taskTotal), yAxisIndex: 1, itemStyle: { color: '#ef4444' } }
    ]
  }, true)
}

function shortTime(time) {
  if (!time) return ''
  const t = String(time)
  return t.includes(' ') ? t.slice(11, 16) : t.slice(5)
}

function resize() {
  chart?.resize()
}

onMounted(() => {
  render()
  window.addEventListener('resize', resize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chart?.dispose()
  chart = null
})

watch(() => props.data, () => {
  nextTick(() => {
    render()
    chart?.resize()
  })
})
</script>

<style scoped>
.mtc-card { background: #fff; border: 1px solid #eef0f3; border-radius: 12px; padding: 14px 16px; }
.mtc-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; flex-wrap: wrap; gap: 8px; }
.mtc-title { font-size: 13px; font-weight: 600; color: #374151; }
.mtc-range-group { display: flex; background: #f1f5f9; border-radius: 8px; padding: 3px; }
.mtc-range-btn { border: 0; background: transparent; padding: 5px 12px; border-radius: 6px; font-size: 12px; font-family: inherit; color: #475569; cursor: pointer; }
.mtc-range-btn.active { background: #fff; color: #4f46e5; font-weight: 600; box-shadow: 0 1px 3px rgba(15, 23, 42, 0.08); }
.mtc-error { font-size: 12px; color: #dc2626; padding: 8px 0; }
.mtc-empty { font-size: 12px; color: #9ca3af; padding: 14px 0; text-align: center; }
.mtc-chart { width: 100%; height: 280px; }
</style>
