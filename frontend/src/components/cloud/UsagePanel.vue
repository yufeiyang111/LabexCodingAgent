<template>
  <div class="usage-panel-root" :class="{ 'is-dark': isDark }">
    <!-- 顶部概览指标卡片组 -->
    <div class="usage-kpi-grid">
      <div class="kpi-card total-card">
        <div class="kpi-label">总 Token 消耗</div>
        <div class="kpi-value">{{ formatTokens(computedTotal) }}</div>
        <div class="kpi-sub">{{ computedCallCount }} 次模型调用</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">输入 Tokens</div>
        <div class="kpi-value text-prompt">{{ formatTokens(computedPrompt) }}</div>
        <div class="kpi-sub">Prompt 占比 {{ promptPercent }}%</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">输出 Tokens</div>
        <div class="kpi-value text-completion">{{ formatTokens(computedCompletion) }}</div>
        <div class="kpi-sub">Completion 占比 {{ completionPercent }}%</div>
      </div>
      <div class="kpi-card" v-if="cacheView.showHitRate">
        <div class="kpi-label">Prompt 缓存命中率</div>
        <div class="kpi-value text-cache">{{ cacheView.hitRate.toFixed(1) }}%</div>
        <div class="kpi-sub">读取 {{ formatTokens(cacheTotals.cachedTokens) }} Tokens</div>
      </div>
    </div>

    <!-- Prompt 缓存统计遥测卡片 -->
    <div class="cache-telemetry-card" :data-cache-status="cacheView.status">
      <div class="cache-heading">
        <div class="cache-title-wrap">
          <span class="cache-icon">⚡</span>
          <span class="cache-title">Prompt 缓存统计</span>
          <span class="cache-badge">{{ cacheView.label }}</span>
          <span v-if="cacheView.showHitRate" class="cache-rate-tag">
            命中率 {{ cacheView.hitRate.toFixed(1) }}%
          </span>
        </div>
        <select
          v-model="selectedModel"
          :disabled="cacheModels.length === 0"
          class="cache-model-select"
          aria-label="按模型查看 Prompt 缓存统计"
        >
          <option value="">全部模型</option>
          <option v-for="m in cacheModels" :key="m" :value="m">{{ m }}</option>
        </select>
      </div>
      <p class="cache-desc">{{ cacheView.detail }}</p>
      <div class="cache-chips-row">
        <span class="cache-chip">缓存读取: <strong>{{ formatTokens(cacheTotals.cachedTokens) }}</strong></span>
        <span class="cache-chip">缓存写入: <strong>{{ formatTokens(cacheTotals.cacheWriteTokens) }}</strong></span>
        <span class="cache-chip">非缓存输入: <strong>{{ formatTokens(cacheTotals.nonCachedInputTokens) }}</strong></span>
      </div>
    </div>

    <!-- 热力图 -->
    <div class="usage-section" v-if="allTokenStats?.byDay">
      <div class="section-title">Token 消耗热力图</div>
      <UsageHeatmap
        :by-day="allTokenStats.byDay"
        :cache-by-day="allTokenStats.cacheByDay"
        :dark="isDark"
      />
    </div>

    <!-- 可视化图表网格 (自适应响应式) -->
    <div class="usage-charts-grid">
      <!-- 饼图：输入 / 输出占比 -->
      <div class="chart-box">
        <div class="chart-header">输入 / 输出占比</div>
        <div ref="pieRef" class="echart-container"></div>
      </div>

      <!-- 趋势图：每日消耗趋势 -->
      <div class="chart-box" v-if="hasDaysData">
        <div class="chart-header">每日消耗趋势</div>
        <div ref="timelineRef" class="echart-container"></div>
      </div>

      <!-- 模型分布图 -->
      <div class="chart-box" v-if="hasModelData">
        <div class="chart-header">模型 Token 分布</div>
        <div ref="modelRef" class="echart-container"></div>
      </div>

      <!-- 会话消耗对比 -->
      <div class="chart-box" v-if="sessionHistory?.length > 0">
        <div class="chart-header">本次会话对比</div>
        <div ref="sessionRef" class="echart-container"></div>
      </div>
    </div>

    <!-- 会话列表明细 -->
    <div class="usage-section" v-if="sessionHistory?.length > 0">
      <div class="section-title">会话明细统计 ({{ sessionHistory.length }})</div>
      <div class="session-list-grid">
        <div
          v-for="(s, idx) in sessionHistory"
          :key="idx"
          class="session-card-item"
        >
          <div class="session-item-left">
            <span class="session-index">#{{ idx + 1 }}</span>
            <span class="session-name" :title="s.title">{{ s.title || ('会话 ' + (idx + 1)) }}</span>
          </div>
          <div class="session-item-right">
            <span class="session-tokens">{{ formatTokens(s.totalTokens) }}</span>
            <span class="session-io">in: {{ formatTokens(s.promptTokens) }} / out: {{ formatTokens(s.completionTokens) }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import * as echarts from 'echarts'
import UsageHeatmap from './UsageHeatmap.vue'
import { resolveCacheTelemetryScope, resolveCacheTelemetryView } from '@/composables/cacheTelemetryStatus'

const props = defineProps({
  tokenUsage: {
    type: Object,
    default: () => ({ totalTokens: 0, promptTokens: 0, completionTokens: 0, callCount: 0 })
  },
  allTokenStats: {
    type: Object,
    default: null
  },
  sessionHistory: {
    type: Array,
    default: () => []
  },
  isDark: {
    type: Boolean,
    default: false
  }
})

const selectedModel = ref('')
const pieRef = ref(null)
const timelineRef = ref(null)
const modelRef = ref(null)
const sessionRef = ref(null)

let pieChart = null
let timelineChart = null
let modelChart = null
let sessionChart = null
let resizeObserver = null

const computedTotal = computed(() => {
  return props.allTokenStats?.totalTokens || props.tokenUsage?.totalTokens || 0
})

const computedPrompt = computed(() => {
  return props.allTokenStats?.totalPromptTokens ?? props.tokenUsage?.promptTokens ?? 0
})

const computedCompletion = computed(() => {
  return props.allTokenStats?.totalCompletionTokens ?? props.tokenUsage?.completionTokens ?? 0
})

const computedCallCount = computed(() => {
  return props.allTokenStats?.callCount || props.tokenUsage?.callCount || 0
})

const promptPercent = computed(() => {
  if (computedTotal.value === 0) return 0
  return Math.round((computedPrompt.value / computedTotal.value) * 100)
})

const completionPercent = computed(() => {
  if (computedTotal.value === 0) return 0
  return Math.round((computedCompletion.value / computedTotal.value) * 100)
})

const cacheModels = computed(() => {
  const byModelKeys = Object.keys(props.allTokenStats?.byModel || {})
  const cacheByModelKeys = Object.keys(props.allTokenStats?.cacheByModel || {})
  return Array.from(new Set([...byModelKeys, ...cacheByModelKeys]))
})

const selectedCacheScope = computed(() => {
  return resolveCacheTelemetryScope(props.allTokenStats, selectedModel.value)
})

const cacheTotals = computed(() => {
  const scope = selectedCacheScope.value
  const stats = props.allTokenStats
  if (!scope && !stats) return { cachedTokens: 0, cacheWriteTokens: 0, nonCachedInputTokens: 0 }
  const target = scope || stats
  const cachedTokens = target.cachedTokens ?? target.totalCachedTokens ?? 0
  const cacheWriteTokens = target.cacheWriteTokens ?? target.totalCacheWriteTokens ?? 0
  const promptTokens = target.promptTokens ?? target.totalPromptTokens ?? 0
  const nonCachedInputTokens = Math.max(0, promptTokens - cachedTokens)
  return {
    cachedTokens,
    cacheWriteTokens,
    nonCachedInputTokens
  }
})

const cacheView = computed(() => {
  const scope = selectedCacheScope.value
  if (selectedModel.value && !scope) {
    return resolveCacheTelemetryView({ cacheStatus: 'not_reported', cachedTokens: 0, cacheWriteTokens: 0, promptTokens: 0 })
  }
  return resolveCacheTelemetryView(scope, props.tokenUsage)
})

const hasDaysData = computed(() => {
  return props.allTokenStats?.byDay && Object.keys(props.allTokenStats.byDay).length > 0
})

const hasModelData = computed(() => {
  return props.allTokenStats?.byModel && Object.keys(props.allTokenStats.byModel).length > 0
})

function formatTokens(n) {
  if (!n) return '0'
  if (n >= 1000000) return (n / 1000000).toFixed(2) + 'M'
  if (n >= 1000) return (n / 1000).toFixed(1) + 'K'
  return String(n)
}

function handleResize() {
  pieChart?.resize()
  timelineChart?.resize()
  modelChart?.resize()
  sessionChart?.resize()
}

function initOrUpdatePieChart() {
  if (!pieRef.value) return
  if (!pieChart) pieChart = echarts.init(pieRef.value)
  pieChart.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    series: [{
      type: 'pie',
      radius: ['45%', '72%'],
      center: ['50%', '50%'],
      itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
      label: { show: true, fontSize: 11, color: '#71717a', formatter: '{b}\n{d}%' },
      data: [
        { value: computedPrompt.value, name: '输入 (Prompt)', itemStyle: { color: '#3b82f6' } },
        { value: computedCompletion.value, name: '输出 (Completion)', itemStyle: { color: '#10b981' } }
      ]
    }]
  })
}

function initOrUpdateTimelineChart() {
  if (timelineRef.value && hasDaysData.value) {
    if (!timelineChart) timelineChart = echarts.init(timelineRef.value)
    const days = Object.keys(props.allTokenStats.byDay).sort()
    const totals = days.map(d => props.allTokenStats.byDay[d] || 0)
    timelineChart.setOption({
      tooltip: { trigger: 'axis' },
      grid: { left: 45, right: 16, top: 16, bottom: 28 },
      xAxis: { type: 'category', data: days.map(d => d.substring(5)), axisLabel: { fontSize: 10, color: '#71717a' }, axisLine: { lineStyle: { color: '#e4e4e7' } } },
      yAxis: { type: 'value', axisLabel: { fontSize: 10, color: '#71717a', formatter: v => formatTokens(v) }, splitLine: { lineStyle: { color: '#f4f4f5' } } },
      series: [{
        type: 'bar',
        data: totals,
        barWidth: '40%',
        itemStyle: { color: '#09090b', borderRadius: [4, 4, 0, 0] }
      }]
    })
  } else if (timelineChart) {
    timelineChart.dispose()
    timelineChart = null
  }
}

function initOrUpdateModelChart() {
  if (modelRef.value && hasModelData.value) {
    if (!modelChart) modelChart = echarts.init(modelRef.value)
    const models = Object.entries(props.allTokenStats.byModel).map(([name, val]) => ({ name, value: val }))
    const colors = ['#09090b', '#3b82f6', '#10b981', '#f59e0b', '#8b5cf6', '#ec4899']
    modelChart.setOption({
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      series: [{
        type: 'pie',
        radius: ['35%', '65%'],
        center: ['50%', '50%'],
        roseType: 'area',
        itemStyle: { borderRadius: 4, borderColor: '#fff', borderWidth: 2 },
        label: { fontSize: 10, color: '#71717a' },
        data: models.map((m, i) => ({ ...m, itemStyle: { color: colors[i % colors.length] } }))
      }]
    })
  } else if (modelChart) {
    modelChart.dispose()
    modelChart = null
  }
}

function initOrUpdateSessionChart() {
  if (sessionRef.value && props.sessionHistory?.length > 0) {
    if (!sessionChart) sessionChart = echarts.init(sessionRef.value)
    sessionChart.setOption({
      tooltip: { trigger: 'axis' },
      grid: { left: 45, right: 16, top: 16, bottom: 28 },
      xAxis: { type: 'category', data: props.sessionHistory.map((s, i) => s.title ? s.title.substring(0, 6) : `#${i + 1}`), axisLabel: { fontSize: 10, color: '#71717a' }, axisLine: { lineStyle: { color: '#e4e4e7' } } },
      yAxis: { type: 'value', axisLabel: { fontSize: 10, color: '#71717a', formatter: v => formatTokens(v) }, splitLine: { lineStyle: { color: '#f4f4f5' } } },
      series: [
        { name: '输入', type: 'bar', stack: 'total', data: props.sessionHistory.map(s => s.promptTokens || 0), itemStyle: { color: '#3b82f6' } },
        { name: '输出', type: 'bar', stack: 'total', data: props.sessionHistory.map(s => s.completionTokens || 0), itemStyle: { color: '#10b981' } }
      ]
    })
  } else if (sessionChart) {
    sessionChart.dispose()
    sessionChart = null
  }
}

function updateAllCharts() {
  nextTick(() => {
    initOrUpdatePieChart()
    initOrUpdateTimelineChart()
    initOrUpdateModelChart()
    initOrUpdateSessionChart()
  })
}

watch([() => props.allTokenStats, () => props.tokenUsage, () => props.sessionHistory], () => {
  updateAllCharts()
}, { deep: true })

onMounted(() => {
  updateAllCharts()
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => handleResize())
    if (pieRef.value?.parentElement) resizeObserver.observe(pieRef.value.parentElement)
  }
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  resizeObserver?.disconnect()
  if (pieChart) { pieChart.dispose(); pieChart = null }
  if (timelineChart) { timelineChart.dispose(); timelineChart = null }
  if (modelChart) { modelChart.dispose(); modelChart = null }
  if (sessionChart) { sessionChart.dispose(); sessionChart = null }
})
</script>

<style scoped>
.usage-panel-root {
  display: flex;
  flex-direction: column;
  gap: 20px;
  width: 100%;
  max-width: 1100px;
  margin: 0 auto;
  padding: 16px 20px 60px 20px;
  overflow-y: auto;
  overflow-x: hidden;
  height: 100%;
  flex: 1;
  box-sizing: border-box;
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
}

.usage-panel-root::-webkit-scrollbar {
  width: 5px;
}

.usage-panel-root::-webkit-scrollbar-track {
  background: transparent;
}

.usage-panel-root::-webkit-scrollbar-thumb {
  background: #d4d4d8;
  border-radius: 4px;
}

/* KPI 指标卡片 */
.usage-kpi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
  gap: 12px;
}

.kpi-card {
  padding: 14px 16px;
  background: #ffffff;
  border: 1px solid #e4e4e7;
  border-radius: 8px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.02);
}

.kpi-card.total-card {
  background: #fafafa;
  border-color: #09090b;
}

.kpi-label {
  font-size: 11.5px;
  font-weight: 600;
  color: #71717a;
  text-transform: uppercase;
  letter-spacing: 0.3px;
}

.kpi-value {
  font-size: 24px;
  font-weight: 700;
  font-family: 'JetBrains Mono', monospace;
  color: #09090b;
  margin: 4px 0 2px;
}

.text-prompt { color: #2563eb; }
.text-completion { color: #059669; }
.text-cache { color: #7c3aed; }

.kpi-sub {
  font-size: 11px;
  font-weight: 500;
  color: #a1a1aa;
}

/* 遥测卡片 */
.cache-telemetry-card {
  padding: 14px 16px;
  background: #fafafa;
  border: 1px solid #e4e4e7;
  border-radius: 8px;
}

.cache-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 6px;
}

.cache-title-wrap {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.cache-title {
  font-size: 13px;
  font-weight: 600;
  color: #09090b;
}

.cache-badge {
  font-size: 10.5px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 4px;
  background: #f4f4f5;
  color: #52525b;
  border: 1px solid #e4e4e7;
}

.cache-rate-tag {
  font-size: 11px;
  font-weight: 700;
  font-family: 'JetBrains Mono', monospace;
  color: #7c3aed;
  background: #f5f3ff;
  border: 1px solid #ddd6fe;
  padding: 1px 6px;
  border-radius: 4px;
}

.cache-model-select {
  padding: 2px 8px;
  border-radius: 4px;
  border: 1px solid #e4e4e7;
  background: #ffffff;
  font-size: 11.5px;
  font-weight: 500;
  color: #09090b;
}

.cache-desc {
  font-size: 12px;
  color: #71717a;
  margin: 0 0 10px;
}

.cache-chips-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.cache-chip {
  font-size: 11.5px;
  padding: 3px 8px;
  background: #ffffff;
  border: 1px solid #e4e4e7;
  border-radius: 4px;
  color: #52525b;
}

.cache-chip strong {
  color: #09090b;
  font-family: 'JetBrains Mono', monospace;
}

/* 图表网格 */
.usage-charts-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 16px;
}

.chart-box {
  padding: 16px;
  background: #ffffff;
  border: 1px solid #e4e4e7;
  border-radius: 8px;
}

.chart-header {
  font-size: 12.5px;
  font-weight: 600;
  color: #09090b;
  margin-bottom: 10px;
}

.echart-container {
  width: 100%;
  height: 220px;
}

.usage-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.section-title {
  font-size: 13px;
  font-weight: 600;
  color: #09090b;
}

.session-list-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 8px;
}

.session-card-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  background: #ffffff;
  border: 1px solid #e4e4e7;
  border-radius: 6px;
}

.session-item-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.session-index {
  font-size: 11px;
  font-weight: 600;
  color: #a1a1aa;
}

.session-name {
  font-size: 12.5px;
  font-weight: 500;
  color: #09090b;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-item-right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  flex-shrink: 0;
}

.session-tokens {
  font-size: 12px;
  font-weight: 600;
  font-family: 'JetBrains Mono', monospace;
  color: #09090b;
}

.session-io {
  font-size: 10.5px;
  color: #a1a1aa;
}
</style>
