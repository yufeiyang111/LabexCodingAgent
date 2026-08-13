<template>
  <section class="usage-heatmap-section" aria-labelledby="usage-heatmap-title">
    <div class="usage-heatmap-heading">
      <div>
        <h4 id="usage-heatmap-title">调用热力图</h4>
        <p>最近 6 个月 · 按每日总 Token 消耗分级</p>
      </div>
      <span class="usage-heatmap-period">{{ periodLabel }}</span>
    </div>

    <div v-if="hasData" ref="chartViewport" class="usage-heatmap-viewport">
      <div ref="chartRef" class="usage-heatmap-chart" role="img" aria-label="最近六个月每日 Token 消耗热力图"></div>
    </div>
    <div v-else class="usage-heatmap-empty">暂无近六个月的用量记录</div>

    <div v-if="hasData" class="usage-heatmap-footer">
      <span>每日总 Token 消耗</span>
      <div class="usage-heatmap-legend" aria-label="用量由少到多">
        <span>少</span>
        <i v-for="color in palette" :key="color" :style="{ backgroundColor: color }" aria-hidden="true"></i>
        <span>多</span>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { createUsageHeatmapData, formatUsageTokenCount } from '@/utils/usageHeatmap'

const props = defineProps({
  byDay: { type: Object, default: () => ({}) },
  cacheByDay: { type: Object, default: () => ({}) },
  dark: { type: Boolean, default: false }
})

const chartRef = ref(null)
const chartViewport = ref(null)
const usage = computed(() => createUsageHeatmapData(props.byDay))
const hasData = computed(() => usage.value.hasData)
const periodLabel = computed(() => `${usage.value.startDate.slice(0, 7)} - ${usage.value.endDate.slice(0, 7)}`)
const palette = computed(() => props.dark
  ? ['#343843', '#54322f', '#754238', '#995144', '#c96a4e', '#ed8567']
  : ['#ede9e3', '#f9e4da', '#f6c3b3', '#ee9a80', '#de7055', '#be4938'])

let chart = null
let refreshSequence = 0
let baseCellShapes = new WeakMap()
let initialScrollDone = false
const HEATMAP_CELL_SIZE = 18
const HOVER_SCALE = 1.12
const HOVER_DURATION = 220
const HOVER_ANIMATION_SCOPE = 'usage-heatmap-hover'

function createPieces(max) {
  const step = max / 5
  return [
    { value: 0, color: palette.value[0] },
    { gt: 0, lte: step, color: palette.value[1] },
    { gt: step, lte: step * 2, color: palette.value[2] },
    { gt: step * 2, lte: step * 3, color: palette.value[3] },
    { gt: step * 3, lte: step * 4, color: palette.value[4] },
    { gt: step * 4, color: palette.value[5] }
  ]
}

function getCssColor(name, fallback) {
  return getComputedStyle(chartRef.value).getPropertyValue(name).trim() || fallback
}

function getHeatmapElement(params) {
  if (!chart || params?.componentType !== 'series' || params.seriesType !== 'heatmap') return null
  const seriesModel = chart.getModel()?.getSeriesByIndex(params.seriesIndex)
  return seriesModel?.getData()?.getItemGraphicEl(params.dataIndex) || null
}

function animateCell(params, scale) {
  const element = getHeatmapElement(params)
  if (!element?.shape) return

  let baseShape = baseCellShapes.get(element)
  if (!baseShape) {
    baseShape = { ...element.shape }
    baseCellShapes.set(element, baseShape)
  }

  const width = baseShape.width * scale
  const height = baseShape.height * scale
  element.stopAnimation(HOVER_ANIMATION_SCOPE)
  element.animateTo({
    shape: {
      x: baseShape.x - (width - baseShape.width) / 2,
      y: baseShape.y - (height - baseShape.height) / 2,
      width,
      height,
      r: Math.min(4, (baseShape.r || 3) * scale)
    }
  }, {
    duration: HOVER_DURATION,
    easing: 'cubicOut',
    scope: HOVER_ANIMATION_SCOPE
  })
}

function handleCellMouseOver(params) {
  animateCell(params, HOVER_SCALE)
}

function handleCellMouseOut(params) {
  animateCell(params, 1)
}

function getCacheHitRate(date) {
  const raw = props.cacheByDay?.[date]?.cacheHitRate
  const rate = Number(raw)
  return Number.isFinite(rate) ? rate : null
}

function scrollToLatest() {
  const viewport = chartViewport.value
  if (!viewport) return
  viewport.scrollLeft = Math.max(0, viewport.scrollWidth - viewport.clientWidth)
  initialScrollDone = true
}

function renderChart() {
  if (!chartRef.value || !hasData.value) return
  if (!chart) {
    chart = echarts.init(chartRef.value)
    chart.on('mouseover', handleCellMouseOver)
    chart.on('mouseout', handleCellMouseOut)
  }
  baseCellShapes = new WeakMap()

  const textColor = getCssColor('--ai-text-muted', props.dark ? '#a1a1aa' : '#736d62')
  const borderColor = getCssColor('--ai-border-strong', props.dark ? 'rgba(255,255,255,0.15)' : '#e0d8c8')
  const surfaceColor = getCssColor('--ai-bg-secondary', props.dark ? '#18181b' : '#f2ecdf')
  const accentColor = getCssColor('--ai-purple', props.dark ? '#d97757' : '#d97757')
  const tooltipBackground = getCssColor('--ai-bg-elevated', props.dark ? '#27272a' : '#fcf9f2')

  chart.setOption({
    animation: false,
    tooltip: {
      trigger: 'item',
      backgroundColor: tooltipBackground,
      borderColor,
      textStyle: { color: getCssColor('--ai-text', props.dark ? '#fafafa' : '#2f2b26'), fontSize: 11 },
      formatter: params => {
        const value = Array.isArray(params?.value) ? params.value[1] : 0
        const date = Array.isArray(params?.value) ? params.value[0] : ''
        const cacheHitRate = getCacheHitRate(date)
        const cacheLabel = cacheHitRate === null ? '未上报' : `${cacheHitRate.toFixed(2)}%`
        return `${date}<br/>总 Token：${formatUsageTokenCount(value)}<br/>缓存命中率：${cacheLabel}`
      }
    },
    visualMap: {
      show: false,
      type: 'piecewise',
      pieces: createPieces(usage.value.max),
      outOfRange: { color: palette.value[0] }
    },
    calendar: {
      range: [usage.value.startDate, usage.value.endDate],
      left: 30,
      top: 27,
      cellSize: [HEATMAP_CELL_SIZE, HEATMAP_CELL_SIZE],
      splitLine: { show: false },
      itemStyle: { color: palette.value[0], borderColor: surfaceColor, borderWidth: 2, borderRadius: 3 },
      dayLabel: { firstDay: 1, nameMap: 'cn', color: textColor, fontSize: 9, margin: 6 },
      monthLabel: { nameMap: 'cn', color: textColor, fontSize: 10, margin: 6 },
      yearLabel: { show: false }
    },
    series: [{
      type: 'heatmap',
      coordinateSystem: 'calendar',
      data: usage.value.data,
      itemStyle: { borderColor: surfaceColor, borderWidth: 2, borderRadius: 3 },
      emphasis: { itemStyle: { borderColor: accentColor, borderWidth: 1, borderRadius: 3 } }
    }]
  }, true)
  chart.resize()
}

function disposeChart() {
  if (!chart) return
  chart.off('mouseover', handleCellMouseOver)
  chart.off('mouseout', handleCellMouseOut)
  chart.dispose()
  chart = null
}

async function refreshChart() {
  const sequence = ++refreshSequence
  await nextTick()
  if (sequence !== refreshSequence) return
  if (!hasData.value) {
    disposeChart()
    return
  }
  renderChart()
  if (!initialScrollDone) {
    await nextTick()
    requestAnimationFrame(scrollToLatest)
  }
}

watch(() => [props.byDay, props.cacheByDay, props.dark], refreshChart, { deep: true })

onMounted(() => {
  refreshChart()
})

onBeforeUnmount(() => {
  refreshSequence += 1
  disposeChart()
})
</script>

<style scoped>
.usage-heatmap-section {
  margin-bottom: 16px;
  padding: 12px;
  border: 1px solid var(--ai-border-strong);
  border-radius: 10px;
  background: var(--ai-bg-secondary);
}
.usage-heatmap-heading,
.usage-heatmap-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.usage-heatmap-heading { margin-bottom: 8px; }
.usage-heatmap-heading h4 { margin: 0; color: var(--ai-text-muted); font-size: 11px; font-weight: 600; }
.usage-heatmap-heading p { margin: 4px 0 0; color: var(--ai-text-faint); font-size: 10px; }
.usage-heatmap-period { flex-shrink: 0; color: var(--ai-text-faint); font-size: 10px; }
.usage-heatmap-viewport { overflow-x: auto; }
.usage-heatmap-chart { width: 560px; min-width: 560px; max-width: none; height: 190px; }
.usage-heatmap-empty { padding: 42px 12px; color: var(--ai-text-faint); font-size: 11px; text-align: center; }
.usage-heatmap-footer { margin-top: 4px; color: var(--ai-text-faint); font-size: 10px; }
.usage-heatmap-legend { display: flex; align-items: center; gap: 4px; }
.usage-heatmap-legend i { width: 12px; height: 12px; border-radius: 3px; }
@media (max-width: 380px) {
  .usage-heatmap-heading { align-items: flex-start; flex-direction: column; gap: 3px; }
  .usage-heatmap-footer { align-items: flex-start; flex-direction: column; gap: 6px; }
}
</style>
