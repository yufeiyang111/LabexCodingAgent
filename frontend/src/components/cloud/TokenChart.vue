<template>
  <div class="token-chart" v-if="totalTokens > 0">
    <div class="token-header" @click="open = !open">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#8b5cf6" stroke-width="2">
        <path d="M12 2v20M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/>
      </svg>
      <span class="token-title">Token 消耗</span>
      <span class="token-total">{{ formatNumber(totalTokens) }}</span>
      <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="#9ca3af" stroke-width="2"
        :style="{ transform: open ? 'rotate(180deg)' : '' }">
        <polyline points="6 9 12 15 18 9"/>
      </svg>
    </div>
    <Transition name="tc-slide">
      <div v-if="open" class="token-body">
        <div class="token-stats">
          <div class="token-stat">
            <span class="stat-label">输入</span>
            <span class="stat-value prompt">{{ formatNumber(promptTokens) }}</span>
          </div>
          <div class="token-stat">
            <span class="stat-label">输出</span>
            <span class="stat-value completion">{{ formatNumber(completionTokens) }}</span>
          </div>
          <div class="token-stat">
            <span class="stat-label">调用</span>
            <span class="stat-value calls">{{ callCount }}</span>
          </div>
        </div>
        <div ref="chartRef" class="token-echart"></div>
        <div class="token-bar">
          <div class="bar-segment prompt" :style="{ width: promptPct + '%' }"></div>
          <div class="bar-segment completion" :style="{ width: completionPct + '%' }"></div>
        </div>
        <div class="token-legend">
          <span class="legend-item"><span class="legend-dot prompt"></span>输入 {{ promptPct }}%</span>
          <span class="legend-item"><span class="legend-dot completion"></span>输出 {{ completionPct }}%</span>
        </div>
      </div>
    </Transition>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  promptTokens: { type: Number, default: 0 },
  completionTokens: { type: Number, default: 0 },
  callCount: { type: Number, default: 0 }
})

const open = ref(false)
const chartRef = ref(null)
let chartInstance = null

const totalTokens = computed(() => props.promptTokens + props.completionTokens)
const promptPct = computed(() => totalTokens.value > 0 ? Math.round(props.promptTokens / totalTokens.value * 100) : 0)
const completionPct = computed(() => 100 - promptPct.value)

function formatNumber(n) {
  if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M'
  if (n >= 1000) return (n / 1000).toFixed(1) + 'K'
  return String(n)
}

function initChart() {
  if (!chartRef.value) return
  if (chartInstance) { chartInstance.dispose() }
  chartInstance = echarts.init(chartRef.value)
  chartInstance.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    series: [{
      type: 'pie',
      radius: ['45%', '70%'],
      center: ['50%', '50%'],
      avoidLabelOverlap: false,
      itemStyle: { borderRadius: 4, borderColor: '#fff', borderWidth: 2 },
      label: { show: false },
      emphasis: { label: { show: true, fontSize: 11, fontWeight: 'bold' } },
      data: [
        { value: props.promptTokens, name: '输入', itemStyle: { color: '#3b82f6' } },
        { value: props.completionTokens, name: '输出', itemStyle: { color: '#10b981' } }
      ]
    }]
  })
}

watch(open, async (val) => {
  if (val) {
    await nextTick()
    initChart()
  }
})

watch(() => [props.promptTokens, props.completionTokens], () => {
  if (open.value && chartInstance) {
    chartInstance.setOption({
      series: [{
        data: [
          { value: props.promptTokens, name: '输入', itemStyle: { color: '#3b82f6' } },
          { value: props.completionTokens, name: '输出', itemStyle: { color: '#10b981' } }
        ]
      }]
    })
  }
})

onBeforeUnmount(() => {
  if (chartInstance) { chartInstance.dispose(); chartInstance = null }
})
</script>

<style scoped>
.token-chart {
  margin: 8px 0;
  border: 1px solid #e9d5ff;
  border-radius: 8px;
  overflow: hidden;
  background: #faf5ff;
}
.token-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  cursor: pointer;
  font-size: 12px;
  font-weight: 600;
  color: #6b21a8;
  user-select: none;
}
.token-title { flex: 1; }
.token-total {
  font-size: 11px;
  color: #a855f7;
  font-weight: 500;
}
.token-body {
  padding: 4px 12px 10px;
}
.token-stats {
  display: flex;
  gap: 16px;
  margin-bottom: 8px;
}
.token-stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.stat-label {
  font-size: 10px;
  color: #9ca3af;
}
.stat-value {
  font-size: 14px;
  font-weight: 700;
}
.stat-value.prompt { color: #3b82f6; }
.stat-value.completion { color: #10b981; }
.stat-value.calls { color: #8b5cf6; }
.token-echart {
  width: 100%;
  height: 120px;
  margin-bottom: 8px;
}
.token-bar {
  height: 6px;
  background: #e5e7eb;
  border-radius: 3px;
  display: flex;
  overflow: hidden;
  margin-bottom: 6px;
}
.bar-segment.prompt { background: #3b82f6; }
.bar-segment.completion { background: #10b981; }
.token-legend {
  display: flex;
  gap: 12px;
}
.legend-item {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 10px;
  color: #6b7280;
}
.legend-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}
.legend-dot.prompt { background: #3b82f6; }
.legend-dot.completion { background: #10b981; }
</style>
