<template>
  <div class="m-page">
    <div v-if="!sessionToken" class="m-gate">
      <form class="m-gate-card" @submit.prevent="doAuth">
        <div class="m-gate-logo">
          <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="#4f46e5" stroke-width="1.8"><path d="M12 20v-6"/><path d="M12 14a3 3 0 1 0 0-6 3 3 0 0 0 0 6z"/><path d="M3 12a9 9 0 1 0 18 0 9 9 0 0 0-18 0z"/></svg>
        </div>
        <h1>运维监控</h1>
        <p class="m-gate-hint">输入访问校验码查看站点流量与运行数据</p>
        <input v-model="accessCode" type="password" class="m-gate-input" placeholder="访问校验码" autofocus autocomplete="off" />
        <button type="submit" class="m-gate-btn" :disabled="authing">
          {{ authing ? '验证中…' : '进入监控' }}
        </button>
        <p class="m-gate-error">{{ gateError }}</p>
      </form>
    </div>

    <div v-else class="m-dash">
      <header class="m-topbar">
        <div class="m-topbar-title">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20v-6"/><path d="M12 14a3 3 0 1 0 0-6 3 3 0 0 0 0 6z"/><path d="M3 12a9 9 0 1 0 18 0 9 9 0 0 0-18 0z"/></svg>
          <span>运维监控</span>
        </div>
        <div class="m-topbar-actions">
          <div class="m-range-group">
            <button v-for="r in ranges" :key="r.key" class="m-range-btn" :class="{ active: range === r.key }" @click="setRange(r.key)">{{ r.label }}</button>
          </div>
          <button class="m-ghost-btn" @click="loadAll">刷新</button>
          <button class="m-ghost-btn" @click="doLogout">退出</button>
        </div>
      </header>

      <main class="m-body">
        <section class="m-cards">
          <div v-for="card in statCards" :key="card.label" class="m-card" :style="{ '--accent': card.accent }">
            <div class="m-card-label">{{ card.label }}</div>
            <div class="m-card-value">{{ card.value }}</div>
            <div class="m-card-sub">{{ card.sub }}</div>
          </div>
        </section>

        <section class="m-grid">
          <div class="m-panel m-panel-wide">
            <div class="m-panel-title">访问量趋势（{{ rangeLabel }}）</div>
            <div ref="trafficEl" class="m-chart m-chart-lg"></div>
          </div>
          <div class="m-panel">
            <div class="m-panel-title">热门路径 Top10（近 7 天）</div>
            <div ref="topEl" class="m-chart"></div>
          </div>
          <div class="m-panel">
            <div class="m-panel-title">状态码分布（近 7 天）</div>
            <div ref="statusEl" class="m-chart"></div>
          </div>
          <div class="m-panel m-panel-wide">
            <div class="m-panel-title">访客来源（近 7 天，按 IP 聚合）</div>
            <div class="m-recent-table m-visitors-table">
              <table>
                <thead>
                  <tr>
                    <th>IP</th>
                    <th>归属地</th>
                    <th>访问次数</th>
                    <th>最后访问</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(v, idx) in visitorRows" :key="idx">
                    <td class="m-cell-ip">{{ v.ip }}</td>
                    <td>{{ v.location || '-' }}</td>
                    <td class="m-cell-num">{{ fmtNum(v.pv) }}</td>
                    <td class="m-cell-time">{{ formatTime(v.lastSeen) }}</td>
                  </tr>
                  <tr v-if="visitorRows.length === 0">
                    <td colspan="4" class="m-empty-row">暂无访客数据</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
          <div class="m-panel m-panel-wide m-recent-panel">
            <div class="m-panel-title">最近访问</div>
            <div class="m-recent-table">
              <table>
                <thead>
                  <tr>
                    <th>时间</th>
                    <th>方法</th>
                    <th>路径</th>
                    <th>状态</th>
                    <th>耗时</th>
                    <th>IP</th>
                    <th>归属地</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(hit, idx) in visitors" :key="idx">
                    <td class="m-cell-time">{{ formatTime(hit.requestTime) }}</td>
                    <td><span class="m-method">{{ hit.method }}</span></td>
                    <td class="m-cell-path" :title="hit.path">{{ hit.path }}</td>
                    <td><span class="m-status" :class="statusClass(hit.status)">{{ hit.status }}</span></td>
                    <td class="m-cell-num">{{ hit.durationMs }} ms</td>
                    <td class="m-cell-ip">{{ hit.ip || '-' }}</td>
                    <td class="m-cell-loc">{{ hit.location || '-' }}</td>
                  </tr>
                  <tr v-if="visitors.length === 0">
                    <td colspan="7" class="m-empty-row">暂无访问记录</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </section>

        <section class="m-panel m-panel-wide m-sys-panel">
          <div class="m-panel-title">运行环境</div>
          <div class="m-sys-grid">
            <div class="m-sys-item"><span class="m-sys-key">操作系统</span>{{ sys?.osName || '-' }} {{ sys?.osArch || '' }}</div>
            <div class="m-sys-item"><span class="m-sys-key">Java</span>{{ sys?.javaVersion || '-' }}</div>
            <div class="m-sys-item"><span class="m-sys-key">进程 PID</span>{{ sys?.processPid ?? '-' }}</div>
            <div class="m-sys-item"><span class="m-sys-key">运行时长</span>{{ formatUptime(sys?.uptimeSeconds) }}</div>
            <div class="m-sys-item"><span class="m-sys-key">CPU 核数</span>{{ sys?.availableProcessors ?? '-' }} 核</div>
            <div class="m-sys-item"><span class="m-sys-key">系统负载</span>{{ sys?.systemLoadAverage != null && sys.systemLoadAverage >= 0 ? sys.systemLoadAverage : '-' }}</div>
            <div class="m-sys-item"><span class="m-sys-key">JVM 堆</span>{{ formatBytes(sys?.heapUsedBytes) }} / {{ formatBytes(sys?.heapMaxBytes) }}</div>
            <div class="m-sys-item"><span class="m-sys-key">服务器时间</span>{{ formatTime(summary?.serverTime) }}</div>
          </div>
        </section>
      </main>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts'
import { monitorApi } from '@/api'

const ranges = [
  { key: '24h', label: '24小时' },
  { key: '7d', label: '7天' },
  { key: '30d', label: '30天' }
]

const sessionToken = ref(monitorApi.getToken())
const accessCode = ref('')
const authing = ref(false)
const gateError = ref('')

const range = ref('24h')
const rangeLabel = computed(() => (ranges.find(r => r.key === range.value) || ranges[0]).label)
const summary = ref(null)
const sys = ref(null)
const visitors = ref([])
const visitorRows = ref([])
const topPaths = ref([])
const statusDist = ref([])
const traffic = ref([])

const trafficEl = ref(null)
const topEl = ref(null)
const statusEl = ref(null)
let trafficChart = null
let topChart = null
let statusChart = null
let sysTimer = null
let summaryTimer = null
let trafficTimer = null
let visitorTimer = null

const statCards = computed(() => {
  const today = summary.value?.today || {}
  const total = summary.value?.total || {}
  const s = sys.value
  return [
    { label: '今日 PV', value: fmtNum(today.pv), sub: '页面与 API 请求', accent: '#4f46e5' },
    { label: '今日 UV', value: fmtNum(today.uv), sub: '按用户/IP 去重', accent: '#7c3aed' },
    { label: '今日错误', value: fmtNum(today.errorCount), sub: 'status >= 400', accent: '#ef4444' },
    { label: '今日平均耗时', value: today.avgDurationMs != null ? today.avgDurationMs + ' ms' : '-', sub: '请求平均响应时间', accent: '#f59e0b' },
    { label: '累计 PV', value: fmtNum(total.pv), sub: '历史累计请求', accent: '#0ea5e9' },
    { label: 'CPU', value: s ? s.cpuPercent + '%' : '-', sub: s ? '负载 ' + (s.systemLoadAverage >= 0 ? s.systemLoadAverage : '-') : '等待采集', accent: '#10b981' },
    { label: '内存', value: s ? s.memoryPercent + '%' : '-', sub: s ? formatBytes(s.memoryUsedBytes) + ' / ' + formatBytes(s.memoryTotalBytes) : '等待采集', accent: '#f97316' },
    { label: '磁盘', value: s ? s.diskPercent + '%' : '-', sub: s ? formatBytes(s.diskUsedBytes) + ' / ' + formatBytes(s.diskTotalBytes) : '等待采集', accent: '#14b8a6' }
  ]
})

function fmtNum(v) {
  return v == null ? '-' : Number(v).toLocaleString()
}

function formatBytes(bytes) {
  if (bytes == null || isNaN(bytes)) return '-'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let v = Number(bytes)
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++ }
  return v.toFixed(1) + ' ' + units[i]
}

function formatUptime(seconds) {
  if (seconds == null || isNaN(seconds)) return '-'
  const d = Math.floor(seconds / 86400)
  const h = Math.floor((seconds % 86400) / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  return d > 0 ? `${d}天 ${h}小时` : h > 0 ? `${h}小时 ${m}分` : `${m}分`
}

function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '-'
}

function statusClass(status) {
  if (status >= 500) return 'm-status-error'
  if (status >= 400) return 'm-status-warn'
  return 'm-status-ok'
}

async function doAuth() {
  gateError.value = ''
  const code = accessCode.value.trim()
  if (!code) { gateError.value = '请输入校验码'; return }
  authing.value = true
  try {
    const data = await monitorApi.auth(code)
    monitorApi.setToken(data.token)
    sessionToken.value = data.token
    accessCode.value = ''
    await enterDashboard()
  } catch (e) {
    gateError.value = e.message || '校验失败，请重试'
  } finally {
    authing.value = false
  }
}

async function enterDashboard() {
  await nextFrame()
  initCharts()
  await loadAll()
  startPolling()
}

function nextFrame() { return new Promise(resolve => requestAnimationFrame(resolve)) }

async function loadAll() {
  await Promise.allSettled([loadSummary(), loadTraffic(), loadTop(), loadStatus(), loadSystem(), loadVisitors(), loadVisitorRows()])
}

async function loadSummary() {
  try { summary.value = await monitorApi.summary() } catch (e) { handleLoadError(e) }
}

async function loadTraffic() {
  try {
    traffic.value = await monitorApi.traffic(range.value)
    renderTraffic()
  } catch (e) { handleLoadError(e) }
}

async function loadTop() {
  try {
    topPaths.value = await monitorApi.topPaths(7, 10)
    renderTop()
  } catch (e) { handleLoadError(e) }
}

async function loadStatus() {
  try {
    statusDist.value = await monitorApi.statusDistribution(7)
    renderStatus()
  } catch (e) { handleLoadError(e) }
}

async function loadSystem() {
  try {
    sys.value = await monitorApi.system()
  } catch (e) { handleLoadError(e) }
}

async function loadVisitors() {
  try { visitors.value = await monitorApi.recentVisitors(20) } catch (e) { handleLoadError(e) }
}

async function loadVisitorRows() {
  try { visitorRows.value = await monitorApi.visitors(7, 50) } catch (e) { handleLoadError(e) }
}

function handleLoadError(e) {
  if (e.status === 401) {
    sessionToken.value = null
    monitorApi.clearToken()
    stopPolling()
  }
}

function setRange(key) {
  range.value = key
  void loadTraffic()
}

function startPolling() {
  stopPolling()
  sysTimer = setInterval(loadSystem, 5000)
  summaryTimer = setInterval(loadSummary, 10000)
  trafficTimer = setInterval(loadTraffic, 60000)
  visitorTimer = setInterval(loadVisitorRows, 60000)
  window.addEventListener('resize', resizeCharts)
}

function stopPolling() {
  if (sysTimer) clearInterval(sysTimer)
  if (summaryTimer) clearInterval(summaryTimer)
  if (trafficTimer) clearInterval(trafficTimer)
  if (visitorTimer) clearInterval(visitorTimer)
  sysTimer = summaryTimer = trafficTimer = visitorTimer = null
  window.removeEventListener('resize', resizeCharts)
}

function initCharts() {
  trafficChart = echarts.init(trafficEl.value)
  topChart = echarts.init(topEl.value)
  statusChart = echarts.init(statusEl.value)
}

function resizeCharts() {
  trafficChart?.resize()
  topChart?.resize()
  statusChart?.resize()
}

function renderTraffic() {
  if (!trafficChart) return
  const points = traffic.value || []
  trafficChart.setOption({
    tooltip: { trigger: 'axis', axisPointer: { type: 'line' } },
    legend: { data: ['PV', 'UV'], top: 0, textStyle: { fontSize: 11 } },
    grid: { left: 46, right: 16, top: 34, bottom: 24 },
    xAxis: { type: 'category', data: points.map(p => shortTime(p.time)), axisLabel: { fontSize: 10, color: '#6b7280' }, boundaryGap: false },
    yAxis: { type: 'value', minInterval: 1, axisLabel: { fontSize: 10, color: '#6b7280' }, splitLine: { lineStyle: { color: '#f3f4f6' } } },
    series: [
      { name: 'PV', type: 'line', smooth: true, showSymbol: false, data: points.map(p => p.pv ?? 0), itemStyle: { color: '#4f46e5' }, areaStyle: { color: 'rgba(99, 102, 241, 0.1)' } },
      { name: 'UV', type: 'line', smooth: true, showSymbol: false, data: points.map(p => p.uv ?? 0), itemStyle: { color: '#f59e0b' } }
    ]
  }, true)
}

function shortTime(time) {
  if (!time) return ''
  const t = String(time)
  return t.includes(' ') ? t.slice(11, 16) : t.slice(5)
}

function renderTop() {
  if (!topChart) return
  const items = [...(topPaths.value || [])].slice(-10)
  topChart.setOption({
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 10, right: 42, top: 10, bottom: 10, containLabel: true },
    xAxis: { type: 'value', minInterval: 1, axisLabel: { fontSize: 10, color: '#6b7280' }, splitLine: { lineStyle: { color: '#f3f4f6' } } },
    yAxis: { type: 'category', data: items.map(p => p.path), axisLabel: { fontSize: 10, color: '#374151', width: 110, overflow: 'truncate' } },
    series: [{ type: 'bar', data: items.map(p => p.pv ?? 0), itemStyle: { color: '#6366f1', borderRadius: [0, 4, 4, 0] }, barMaxWidth: 14 }]
  }, true)
}

function renderStatus() {
  if (!statusChart) return
  const items = statusDist.value || []
  statusChart.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    legend: { bottom: 0, textStyle: { fontSize: 11 } },
    series: [{
      type: 'pie',
      radius: ['38%', '66%'],
      center: ['50%', '44%'],
      label: { fontSize: 11, formatter: '{b}' },
      data: items.map(s => ({ name: String(s.status), value: s.count }))
    }]
  }, true)
}

async function doLogout() {
  await monitorApi.logout()
  monitorApi.clearToken()
  sessionToken.value = null
  stopPolling()
  disposeCharts()
}

function disposeCharts() {
  trafficChart?.dispose()
  topChart?.dispose()
  statusChart?.dispose()
  trafficChart = topChart = statusChart = null
}

onMounted(() => {
  if (sessionToken.value) {
    void enterDashboard()
  }
})

onBeforeUnmount(() => {
  stopPolling()
  disposeCharts()
})
</script>

<style scoped>
.m-page { min-height: 100dvh; background: #f8fafc; font-family: inherit; }
.m-gate { min-height: 100dvh; display: flex; align-items: center; justify-content: center; padding: 24px; }
.m-gate-card { width: 360px; background: #fff; border: 1px solid #eef0f3; border-radius: 16px; padding: 40px 32px 28px; box-shadow: 0 20px 50px rgba(15, 23, 42, 0.08); text-align: center; }
.m-gate-logo { display: flex; justify-content: center; margin-bottom: 14px; }
.m-gate-card h1 { font-size: 20px; font-weight: 700; color: #111827; margin: 0 0 6px; }
.m-gate-hint { font-size: 13px; color: #6b7280; margin: 0 0 24px; }
.m-gate-input { width: 100%; box-sizing: border-box; padding: 11px 14px; border: 1px solid #e5e7eb; border-radius: 10px; font-size: 14px; font-family: inherit; outline: none; transition: border-color 0.2s, box-shadow 0.2s; }
.m-gate-input:focus { border-color: #6366f1; box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.12); }
.m-gate-btn { width: 100%; margin-top: 14px; padding: 11px 0; border: 0; border-radius: 10px; background: #4f46e5; color: #fff; font-size: 14px; font-weight: 600; font-family: inherit; cursor: pointer; transition: background 0.2s; }
.m-gate-btn:hover { background: #4338ca; }
.m-gate-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.m-gate-error { min-height: 18px; margin: 10px 0 0; font-size: 12px; color: #ef4444; }

.m-dash { display: flex; flex-direction: column; min-height: 100dvh; }
.m-topbar { display: flex; align-items: center; justify-content: space-between; padding: 12px 24px; background: #fff; border-bottom: 1px solid #eef0f3; position: sticky; top: 0; z-index: 10; }
.m-topbar-title { display: flex; align-items: center; gap: 8px; font-size: 15px; font-weight: 600; color: #111827; }
.m-topbar-actions { display: flex; align-items: center; gap: 10px; }
.m-range-group { display: flex; background: #f1f5f9; border-radius: 8px; padding: 3px; }
.m-range-btn { border: 0; background: transparent; padding: 6px 14px; border-radius: 6px; font-size: 12px; font-family: inherit; color: #475569; cursor: pointer; }
.m-range-btn.active { background: #fff; color: #4f46e5; font-weight: 600; box-shadow: 0 1px 3px rgba(15, 23, 42, 0.08); }
.m-ghost-btn { border: 1px solid #e5e7eb; background: #fff; padding: 6px 12px; border-radius: 8px; font-size: 12px; font-family: inherit; color: #374151; cursor: pointer; }
.m-ghost-btn:hover { background: #f9fafb; }

.m-body { flex: 1; width: 100%; max-width: 1360px; margin: 0 auto; padding: 20px 24px 40px; display: flex; flex-direction: column; gap: 16px; box-sizing: border-box; }
.m-cards { display: grid; grid-template-columns: repeat(8, 1fr); gap: 12px; }
@media (max-width: 1280px) { .m-cards { grid-template-columns: repeat(4, 1fr); } }
@media (max-width: 720px) { .m-cards { grid-template-columns: repeat(2, 1fr); } }
.m-card { background: #fff; border: 1px solid #eef0f3; border-radius: 12px; padding: 14px 16px; border-top: 3px solid var(--accent); }
.m-card-label { font-size: 12px; color: #6b7280; }
.m-card-value { font-size: 24px; font-weight: 700; color: #111827; margin: 4px 0 2px; }
.m-card-sub { font-size: 11px; color: #9ca3af; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

.m-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.m-panel { background: #fff; border: 1px solid #eef0f3; border-radius: 12px; padding: 16px; }
.m-panel-wide { grid-column: 1 / -1; }
.m-panel-title { font-size: 13px; font-weight: 600; color: #374151; margin-bottom: 10px; }
.m-chart { width: 100%; height: 260px; }
.m-chart-lg { height: 300px; }
.m-recent-panel { display: flex; flex-direction: column; }
.m-recent-panel .m-chart { flex: 1; }
.m-recent-table { overflow: auto; max-height: 380px; }
.m-visitors-table { max-height: 320px; }
.m-cell-loc { max-width: 220px; overflow: hidden; text-overflow: ellipsis; color: #6b7280; }
.m-recent-table table { width: 100%; border-collapse: collapse; font-size: 12px; }
.m-recent-table th { text-align: left; padding: 8px 10px; color: #6b7280; font-weight: 500; background: #f9fafb; position: sticky; top: 0; border-bottom: 1px solid #f0f2f5; }
.m-recent-table td { padding: 8px 10px; border-bottom: 1px solid #f6f8fa; color: #374151; white-space: nowrap; }
.m-cell-path { max-width: 420px; overflow: hidden; text-overflow: ellipsis; }
.m-cell-time, .m-cell-ip, .m-cell-num { color: #6b7280; font-variant-numeric: tabular-nums; }
.m-method { font-weight: 600; color: #4f46e5; }
.m-status { display: inline-block; min-width: 40px; text-align: center; padding: 2px 8px; border-radius: 999px; font-size: 11px; font-weight: 600; }
.m-status-ok { background: #ecfdf5; color: #059669; }
.m-status-warn { background: #fffbeb; color: #d97706; }
.m-status-error { background: #fef2f2; color: #dc2626; }
.m-empty-row { text-align: center; color: #9ca3af; padding: 24px; }

.m-sys-panel { padding-bottom: 18px; }
.m-sys-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px 24px; font-size: 12px; color: #374151; }
@media (max-width: 960px) { .m-sys-grid { grid-template-columns: repeat(2, 1fr); } }
.m-sys-key { display: block; font-size: 11px; color: #9ca3af; margin-bottom: 2px; }
</style>
