<template>
  <div class="agent-timer" :class="{ running: isRunning }" aria-live="polite">
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
      <circle cx="12" cy="12" r="9"/>
      <path d="M12 7v5l3 2"/>
    </svg>
    <span class="agent-timer-label">&#x601D;&#x8003;&#x65F6;&#x957F;</span>
    <strong class="agent-timer-value">{{ formatDuration(durationMs) }}</strong>
    <span class="agent-timer-status">{{ isRunning ? '\u8FDB\u884C\u4E2D' : '\u5DF2\u7ED3\u675F' }}</span>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'

const props = defineProps({
  startedAt: { type: Number, default: null },
  activeElapsedMs: { type: Number, default: null },
  isRunning: { type: Boolean, default: false }
})

const now = ref(Date.now())
const stoppedAt = ref(null)
let intervalId = null

function refresh() {
  now.value = Date.now()
}

function clearTimer() {
  if (intervalId !== null) {
    clearInterval(intervalId)
    intervalId = null
  }
}

function syncTimer() {
  clearTimer()
  refresh()
  if (props.isRunning) {
    stoppedAt.value = null
    intervalId = setInterval(refresh, 1_000)
  } else if (stoppedAt.value === null) {
    stoppedAt.value = now.value
  }
}

const durationMs = computed(() => {
  const persisted = Number.isFinite(props.activeElapsedMs) && props.activeElapsedMs >= 0
    ? props.activeElapsedMs
    : null
  if (!props.isRunning && persisted !== null) return persisted

  const startedAt = Number(props.startedAt)
  if (!Number.isFinite(startedAt) || startedAt <= 0) return persisted || 0

  const endAt = props.isRunning ? now.value : (stoppedAt.value || now.value)
  return Math.max(persisted || 0, endAt - startedAt)
})

function formatDuration(value) {
  const totalSeconds = Math.max(0, Math.floor((Number(value) || 0) / 1_000))
  const hours = Math.floor(totalSeconds / 3_600)
  const minutes = Math.floor((totalSeconds % 3_600) / 60)
  const seconds = totalSeconds % 60
  if (hours > 0) return `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
}

watch(() => [props.isRunning, props.startedAt], syncTimer, { immediate: true })
onBeforeUnmount(clearTimer)
</script>

<style scoped>
.agent-timer {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin: 0 0 8px;
  padding: 6px 10px;
  border: 1px solid var(--ai-accent-border, #bfdbfe);
  border-radius: 8px;
  color: var(--ai-purple-deep, #1d4ed8);
  background: var(--ai-bg, #ffffff);
  box-shadow: var(--ai-shadow-sm, 0 1px 3px rgba(0, 0, 0, 0.03));
  font-size: 11px;
  line-height: 1;
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}
.agent-timer:hover {
  border-color: var(--ai-border-focus, #93c5fd);
  box-shadow: var(--ai-shadow-md, 0 3px 10px -1px rgba(15, 23, 42, 0.08));
  transform: translateY(-1px);
}
.agent-timer.running svg { animation: agent-timer-spin 1.8s linear infinite; }
.agent-timer-label { color: var(--ai-text-muted, #64748b); }
.agent-timer-value { color: var(--ai-text, #1e3a8a); font-variant-numeric: tabular-nums; font-weight: 600; }
.agent-timer-status { color: var(--ai-purple-text, #3b82f6); }
.agent-timer:not(.running) {
  border-color: var(--ai-border-strong, #e2e8f0);
  color: var(--ai-text-muted, #64748b);
  background: var(--ai-bg, #ffffff);
}
.agent-timer:not(.running) .agent-timer-value { color: var(--ai-text, #334155); }
.agent-timer:not(.running) .agent-timer-status { color: var(--ai-text-faint, #94a3b8); }
.agent-timer:not(.running):hover {
  border-color: var(--ai-border-focus, #cbd5e1);
  box-shadow: var(--ai-shadow-md, 0 3px 10px -1px rgba(0, 0, 0, 0.06));
}

/* 暗色主题增强 */
:root[data-theme='dark'] .agent-timer {
  background: rgba(59, 130, 246, 0.12);
  border-color: rgba(59, 130, 246, 0.3);
  color: #93c5fd;
}
:root[data-theme='dark'] .agent-timer-label {
  color: #a6adc8;
}
:root[data-theme='dark'] .agent-timer-value {
  color: #60a5fa;
}
:root[data-theme='dark'] .agent-timer-status {
  color: #93c5fd;
}
:root[data-theme='dark'] .agent-timer:not(.running) {
  background: #1e1e2e;
  border-color: #313244;
  color: #a6adc8;
}
:root[data-theme='dark'] .agent-timer:not(.running) .agent-timer-value {
  color: #cdd6f4;
}
:root[data-theme='dark'] .agent-timer:not(.running) .agent-timer-status {
  color: #6c7086;
}

@keyframes agent-timer-spin { to { transform: rotate(360deg); } }
</style>
