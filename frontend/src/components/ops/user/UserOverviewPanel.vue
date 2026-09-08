<template>
  <div class="uop">
    <!-- 概况指标卡片 -->
    <OpsStateBlock :loading="loading && !overview" :error="error" :empty="!overview" empty-text="暂无用户概况数据">
      <div v-if="overview" class="uop-cards">
        <div class="uop-card" style="--accent: #6366f1">
          <div class="uop-card-label">实时在线</div>
          <div class="uop-card-value">{{ fmtNum(overview.onlineCount) }} <span class="uop-unit">人</span></div>
          <div class="uop-card-sub">最近 5 分钟内有请求</div>
        </div>

        <div class="uop-card" style="--accent: #0ea5e9">
          <div class="uop-card-label">今日 DAU</div>
          <div class="uop-card-value">{{ fmtNum(overview.todayDau) }}</div>
          <div class="uop-card-sub">
            较昨日
            <span :class="overview.dauChangeRatio >= 0 ? 'uop-inc' : 'uop-dec'">
              {{ overview.dauChangeRatio >= 0 ? '+' : '' }}{{ Math.round(overview.dauChangeRatio * 100) }}%
            </span>
          </div>
        </div>

        <div class="uop-card" style="--accent: #10b981">
          <div class="uop-card-label">7日 WAU</div>
          <div class="uop-card-value">{{ fmtNum(overview.weeklyWau) }}</div>
          <div class="uop-card-sub">近 7 天独立访问用户</div>
        </div>

        <div class="uop-card" style="--accent: #8b5cf6">
          <div class="uop-card-label">总注册用户</div>
          <div class="uop-card-value">{{ fmtNum(overview.totalRegistered) }}</div>
          <div class="uop-card-sub">平台累计账户量</div>
        </div>

        <div class="uop-card" style="--accent: #f59e0b">
          <div class="uop-card-label">活跃用户均耗 Token</div>
          <div class="uop-card-value">{{ fmtNum(overview.avgTokensPerActiveUser) }}</div>
          <div class="uop-card-sub">今日人均模型调用成本</div>
        </div>

        <div class="uop-card" style="--accent: #ec4899">
          <div class="uop-card-label">活跃用户均发起任务</div>
          <div class="uop-card-value">{{ overview.avgTasksPerActiveUser }} <span class="uop-unit">次</span></div>
          <div class="uop-card-sub">今日人均 Agent 编码互动</div>
        </div>
      </div>

      <!-- 活跃趋势折线图 -->
      <div class="uop-chart-panel">
        <div class="uop-chart-header">
          <div class="uop-chart-title">用户活跃与调用趋势</div>
          <div class="uop-range-group">
            <button
              v-for="r in ranges"
              :key="r.key"
              class="uop-range-btn"
              :class="{ active: range === r.key }"
              @click="$emit('range-change', r.key)"
            >
              {{ r.label }}
            </button>
            <button class="uop-refresh-btn" @click="$emit('refresh')">刷新大盘</button>
          </div>
        </div>
        <div ref="chartEl" class="uop-chart"></div>
      </div>
    </OpsStateBlock>
  </div>
</template>

<script setup>
import { ref, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import echarts from '@/utils/echarts'
import OpsStateBlock from '@/components/ops/ui/OpsStateBlock.vue'

const props = defineProps({
  overview: { type: Object, default: () => null },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  range: { type: String, default: '7d' }
})

defineEmits(['range-change', 'refresh'])

const ranges = [
  { key: '24h', label: '近 24 小时' },
  { key: '7d', label: '近 7 天' },
  { key: '30d', label: '近 30 天' }
]

const chartEl = ref(null)
let chartInstance = null

function fmtNum(n) {
  if (n == null) return '-'
  return Number(n).toLocaleString()
}

function initOrUpdateChart() {
  if (!chartEl.value) return
  if (!chartInstance) {
    chartInstance = echarts.init(chartEl.value)
  }

  const seriesData = props.overview?.trendSeries || []
  const xData = seriesData.map(d => (props.range === '24h' ? d.timestamp.slice(11, 16) : d.timestamp))
  const userCounts = seriesData.map(d => d.activeUsers)
  const reqCounts = seriesData.map(d => d.requestCount)
  const taskCounts = seriesData.map(d => d.taskCount)

  const option = {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross' }
    },
    legend: {
      data: ['活跃用户数 (UV)', '接口访问量 (PV)', 'Agent 任务量'],
      top: 4,
      textStyle: { fontSize: 11, color: '#6b7280' }
    },
    grid: {
      top: 36,
      left: 45,
      right: 45,
      bottom: 24
    },
    xAxis: {
      type: 'category',
      data: xData,
      axisLabel: { fontSize: 11, color: '#9ca3af' },
      axisLine: { lineStyle: { color: '#e5e7eb' } }
    },
    yAxis: [
      {
        type: 'value',
        name: '用户 / 任务',
        nameTextStyle: { fontSize: 10, color: '#9ca3af' },
        axisLabel: { fontSize: 10, color: '#9ca3af' },
        splitLine: { lineStyle: { color: '#f3f4f6' } }
      },
      {
        type: 'value',
        name: '请求量',
        nameTextStyle: { fontSize: 10, color: '#9ca3af' },
        axisLabel: { fontSize: 10, color: '#9ca3af' },
        splitLine: { show: false }
      }
    ],
    series: [
      {
        name: '活跃用户数 (UV)',
        type: 'line',
        smooth: true,
        data: userCounts,
        itemStyle: { color: '#4f46e5' },
        areaStyle: {
          color: 'rgba(79, 70, 229, 0.08)'
        }
      },
      {
        name: 'Agent 任务量',
        type: 'line',
        smooth: true,
        data: taskCounts,
        itemStyle: { color: '#ec4899' }
      },
      {
        name: '接口访问量 (PV)',
        type: 'bar',
        yAxisIndex: 1,
        barMaxWidth: 16,
        data: reqCounts,
        itemStyle: { color: '#cbd5e1', borderRadius: [3, 3, 0, 0] }
      }
    ]
  }

  chartInstance.setOption(option, true)
}

function handleResize() {
  if (chartInstance) chartInstance.resize()
}

watch(() => props.overview, () => {
  nextTick(initOrUpdateChart)
}, { deep: true })

onMounted(() => {
  window.addEventListener('resize', handleResize)
  nextTick(initOrUpdateChart)
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
.uop { margin-bottom: 16px; }
.uop-cards {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 12px;
  margin-bottom: 14px;
}
@media (max-width: 1280px) {
  .uop-cards { grid-template-columns: repeat(3, 1fr); }
}
@media (max-width: 768px) {
  .uop-cards { grid-template-columns: repeat(2, 1fr); }
}
.uop-card {
  background: #fff;
  border: 1px solid #eef0f3;
  border-radius: 12px;
  padding: 14px 16px;
  border-left: 3px solid var(--accent);
}
.uop-card-label { font-size: 11px; color: #6b7280; margin-bottom: 4px; }
.uop-card-value { font-size: 20px; font-weight: 600; color: #111827; font-variant-numeric: tabular-nums; }
.uop-unit { font-size: 12px; font-weight: 400; color: #9ca3af; margin-left: 2px; }
.uop-card-sub { font-size: 11px; color: #9ca3af; margin-top: 4px; }
.uop-inc { color: #10b981; font-weight: 500; }
.uop-dec { color: #ef4444; font-weight: 500; }

.uop-chart-panel {
  background: #fff;
  border: 1px solid #eef0f3;
  border-radius: 12px;
  padding: 14px 16px;
}
.uop-chart-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}
.uop-chart-title { font-size: 13px; font-weight: 600; color: #111827; }
.uop-range-group { display: flex; gap: 6px; }
.uop-range-btn {
  border: 1px solid #e5e7eb;
  background: #fff;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 11px;
  color: #4b5563;
  cursor: pointer;
}
.uop-range-btn.active {
  background: #4f46e5;
  color: #fff;
  border-color: #4f46e5;
}
.uop-refresh-btn {
  border: 1px solid #e5e7eb;
  background: #fff;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 11px;
  color: #374151;
  cursor: pointer;
}
.uop-refresh-btn:hover { background: #f9fafb; }
.uop-chart { width: 100%; height: 230px; }
</style>
