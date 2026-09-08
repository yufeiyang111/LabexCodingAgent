<template>
  <div class="utp">
    <div class="utp-title">Token 消耗模型分布</div>
    <div v-if="tokenBreakdown && tokenBreakdown.length > 0" ref="chartEl" class="utp-chart"></div>
    <div v-else class="utp-empty">暂无 Token 消耗数据</div>
  </div>
</template>

<script setup>
import { ref, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import echarts from '@/utils/echarts'

const props = defineProps({
  tokenBreakdown: { type: Array, default: () => [] }
})

const chartEl = ref(null)
let chartInstance = null

function initChart() {
  if (!chartEl.value || !props.tokenBreakdown || props.tokenBreakdown.length === 0) return
  if (!chartInstance) {
    chartInstance = echarts.init(chartEl.value)
  }

  const pieData = props.tokenBreakdown.map(item => ({
    name: `${item.provider}/${item.model}`,
    value: item.totalTokens
  }))

  const option = {
    tooltip: {
      trigger: 'item',
      formatter: '{b}: {c} Tokens ({d}%)'
    },
    legend: {
      orient: 'vertical',
      right: 10,
      top: 'center',
      textStyle: { fontSize: 10, color: '#6b7280' }
    },
    series: [
      {
        name: '模型占比',
        type: 'pie',
        radius: ['45%', '70%'],
        center: ['35%', '50%'],
        avoidLabelOverlap: false,
        label: { show: false },
        emphasis: {
          label: {
            show: true,
            fontSize: 11,
            fontWeight: 'bold'
          }
        },
        data: pieData
      }
    ]
  }

  chartInstance.setOption(option, true)
}

function handleResize() {
  if (chartInstance) chartInstance.resize()
}

watch(() => props.tokenBreakdown, () => {
  nextTick(initChart)
}, { deep: true })

onMounted(() => {
  window.addEventListener('resize', handleResize)
  nextTick(initChart)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  if (chartInstance) {
    chartInstance.dispose()
    chartInstance = null
  }
})
</script>

<style scoped>
.utp { background: #f9fafb; border-radius: 8px; padding: 10px 12px; }
.utp-title { font-size: 11px; font-weight: 500; color: #4b5563; margin-bottom: 6px; }
.utp-chart { width: 100%; height: 160px; }
.utp-empty { height: 100px; display: flex; align-items: center; justify-content: center; font-size: 11px; color: #9ca3af; }
</style>
