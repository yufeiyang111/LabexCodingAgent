<template>
  <div v-if="plan" class="plan-display">
    <div class="plan-header" @click="open = !open">
      <div class="plan-header-left">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#2563eb" stroke-width="2">
          <path d="M9 11l3 3L22 4"/><path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"/>
        </svg>
        <span class="plan-title">Todo</span>
      </div>
      <div class="plan-header-right">
        <div class="plan-progress-ring">
          <svg width="28" height="28" viewBox="0 0 32 32">
            <circle cx="16" cy="16" r="13" fill="none" stroke="#e5e7eb" stroke-width="3"/>
            <circle cx="16" cy="16" r="13" fill="none" stroke="#3b82f6" stroke-width="3"
              :stroke-dasharray="81.68" :stroke-dashoffset="81.68 * (1 - progressRatio)"
              stroke-linecap="round" transform="rotate(-90 16 16)"/>
            <text x="16" y="16" text-anchor="middle" dominant-baseline="central"
              font-size="8" font-weight="700" fill="#1e40af">{{ completedCount }}</text>
          </svg>
        </div>
        <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="#9ca3af" stroke-width="2"
          :style="{ transform: open ? 'rotate(180deg)' : '' }">
          <polyline points="6 9 12 15 18 9"/>
        </svg>
      </div>
    </div>
    <div class="plan-body" v-if="open">
      <div class="plan-progress-bar">
        <div class="plan-progress-fill" :style="{ width: (progressRatio * 100) + '%' }"></div>
      </div>
      <div v-for="(item, idx) in planItems" :key="idx" class="plan-item" :class="item.status">
        <span class="plan-icon">
          <svg v-if="item.status === 'completed'" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#10b981" stroke-width="3"><path d="M20 6L9 17l-5-5"/></svg>
          <svg v-else-if="item.status === 'current'" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#3b82f6" stroke-width="2"><circle cx="12" cy="12" r="5" fill="#3b82f6"/></svg>
          <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#d1d5db" stroke-width="2"><circle cx="12" cy="12" r="5"/></svg>
        </span>
        <span class="plan-text" :class="{ done: item.status === 'completed' }">{{ item.text }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'

const props = defineProps({ plan: [String, Array], planJson: String })
const open = ref(true)

const planItems = computed(() => {
  // Try structured JSON first
  if (props.planJson) {
    try {
      const arr = JSON.parse(props.planJson)
      if (Array.isArray(arr)) {
        return arr.map(item => ({
          text: item.title || item.text || '',
          status: item.completed ? 'completed' : (item.current ? 'current' : 'pending'),
          index: item.index || 0
        }))
      }
    } catch (e) {}
  }
  // Try array prop
  if (Array.isArray(props.plan)) {
    return props.plan.map(item => ({
      text: typeof item === 'string' ? item : (item.title || item.text || ''),
      status: item.completed ? 'completed' : (item.current ? 'current' : 'pending'),
      index: item.index || 0
    }))
  }
  // Fallback: parse string
  if (!props.plan) return []
  const planStr = typeof props.plan === 'string' ? props.plan : ''
  return planStr.split('\n').filter(l => l.trim()).map(line => {
    const trimmed = line.trim()
    if (trimmed.startsWith('✅') || trimmed.startsWith('[已完成]') || trimmed.toLowerCase().includes('completed')) {
      return { text: trimmed.replace(/^[✅\[\]已完成]+\s*/g, '').trim(), status: 'completed' }
    }
    if (trimmed.startsWith('👉') || trimmed.startsWith('[当前]') || trimmed.startsWith('[进行中]')) {
      return { text: trimmed.replace(/^[👉\[\]当前进行中]+\s*/g, '').trim(), status: 'current' }
    }
    if (trimmed.startsWith('⬜') || trimmed.startsWith('[待办]') || /^\d+\./.test(trimmed)) {
      return { text: trimmed.replace(/^[⬜\[\]待办]+\s*/g, '').replace(/^\d+\.\s*/, '').trim(), status: 'pending' }
    }
    return { text: trimmed, status: 'pending' }
  })
})

const totalCount = computed(() => planItems.value.length)
const completedCount = computed(() => planItems.value.filter(i => i.status === 'completed').length)
const progressRatio = computed(() => totalCount.value > 0 ? completedCount.value / totalCount.value : 0)
</script>

<style scoped>
.plan-display {
  margin: 8px 0;
  border: 1px solid #dbeafe;
  border-radius: 10px;
  overflow: hidden;
  background: linear-gradient(135deg, #eff6ff 0%, #f0f7ff 100%);
  box-shadow: 0 1px 3px rgba(37,99,235,0.08);
}
.plan-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  cursor: pointer;
  user-select: none;
}
.plan-header-left {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 700;
  color: #1e40af;
}
.plan-header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.plan-count { font-size: 12px; color: #3b82f6; font-weight: 600; }
.plan-body {
  padding: 0 12px 10px;
}
.plan-progress-bar {
  height: 3px;
  background: #e5e7eb;
  border-radius: 2px;
  margin-bottom: 8px;
  overflow: hidden;
}
.plan-progress-fill {
  height: 100%;
  background: linear-gradient(90deg, #3b82f6, #2563eb);
  border-radius: 2px;
  transition: width 0.3s ease;
}
.plan-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 5px 0;
  font-size: 12px;
  color: #374151;
  line-height: 1.5;
}
.plan-item.current {
  background: #eff6ff;
  margin: 0 -12px;
  padding: 5px 12px;
  border-radius: 6px;
}
.plan-item.completed .plan-text {
  color: #9ca3af;
  text-decoration: line-through;
}
.plan-item.current .plan-text {
  color: #1e40af;
  font-weight: 600;
}
.plan-icon {
  flex-shrink: 0;
  margin-top: 2px;
}
</style>
