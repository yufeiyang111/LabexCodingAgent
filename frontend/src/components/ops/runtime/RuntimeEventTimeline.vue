<template>
  <div class="ret-card">
    <div class="ret-title">事件时间线</div>

    <div v-if="error" class="ret-error">{{ error }}</div>

    <div v-else-if="loading && events.length === 0" class="ret-empty">正在加载事件…</div>

    <div v-else-if="events.length === 0" class="ret-empty">暂无事件</div>

    <div v-else class="ret-list">
      <div v-for="e in events" :key="e.eventId" class="ret-item">
        <div class="ret-rail">
          <span class="ret-dot" :class="dotClass(e.state)"></span>
          <span v-if="e !== events[events.length - 1]" class="ret-line"></span>
        </div>
        <div class="ret-content">
          <div class="ret-head">
            <span class="ret-type">{{ e.eventType }}</span>
            <span class="ret-seq">#{{ e.sequenceNumber }}</span>
            <span class="ret-time">{{ formatTime(e.createTime) }}</span>
          </div>
          <pre v-if="e.payload" class="ret-payload">{{ e.payload }}</pre>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
defineProps({
  events: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' }
})

function dotClass(state) {
  if (['failed', 'cancelled', 'error'].includes(state)) return 'ret-dot-down'
  if (state === 'completed') return 'ret-dot-up'
  return 'ret-dot-run'
}

function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '-'
}
</script>

<style scoped>
.ret-card { background: #fff; border: 1px solid #eef0f3; border-radius: 12px; padding: 14px 16px; }
.ret-title { font-size: 13px; font-weight: 600; color: #374151; margin-bottom: 10px; }
.ret-error { font-size: 12px; color: #dc2626; padding: 8px 0; }
.ret-empty { font-size: 12px; color: #9ca3af; padding: 14px 0; text-align: center; }
.ret-list { max-height: 420px; overflow: auto; }
.ret-item { display: flex; gap: 10px; }
.ret-rail { display: flex; flex-direction: column; align-items: center; width: 10px; flex-shrink: 0; }
.ret-dot { width: 8px; height: 8px; border-radius: 50%; margin-top: 5px; flex-shrink: 0; }
.ret-dot-run { background: #2563eb; }
.ret-dot-up { background: #059669; }
.ret-dot-down { background: #dc2626; }
.ret-line { width: 2px; flex: 1; background: #e5e7eb; margin: 2px 0; }
.ret-content { flex: 1; padding-bottom: 12px; min-width: 0; }
.ret-head { display: flex; align-items: center; gap: 8px; }
.ret-type { font-size: 12px; font-weight: 600; color: #374151; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.ret-seq { font-size: 11px; color: #9ca3af; font-variant-numeric: tabular-nums; }
.ret-time { font-size: 11px; color: #9ca3af; margin-left: auto; font-variant-numeric: tabular-nums; }
.ret-payload { margin: 4px 0 0; font-size: 11px; color: #6b7280; background: #f8fafc; border-radius: 8px; padding: 8px 10px; white-space: pre-wrap; word-break: break-word; max-height: 120px; overflow: auto; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
</style>
