<template>
  <div v-if="planItems.length > 0" class="plan-display">
    <div class="plan-header" @click="open = !open">
      <div class="plan-header-left">
        <div class="plan-header-icon-box">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2">
            <path d="M9 11l3 3L22 4"/><path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"/>
          </svg>
        </div>
        <span class="plan-title">Todo 任务计划</span>
        <span class="plan-count-badge">{{ completedCount }}/{{ checkableTotalCount }}</span>
      </div>
      <div class="plan-header-right">
        <div class="plan-progress-ring" :title="`完成度: ${Math.round(progressRatio * 100)}%`">
          <svg width="26" height="26" viewBox="0 0 32 32">
            <circle cx="16" cy="16" r="13" fill="none" stroke="#e4e4e7" stroke-width="3"/>
            <circle
              cx="16" cy="16" r="13" fill="none" stroke="#10b981" stroke-width="3"
              :stroke-dasharray="81.68" :stroke-dashoffset="81.68 * (1 - progressRatio)"
              stroke-linecap="round" transform="rotate(-90 16 16)"
              style="transition: stroke-dashoffset 0.35s ease;"
            />
            <text
              x="16" y="16" text-anchor="middle" dominant-baseline="central"
              font-size="8.5" font-weight="700" fill="#065f46"
            >{{ completedCount }}</text>
          </svg>
        </div>
        <svg
          width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="#9ca3af" stroke-width="2"
          class="plan-collapse-arrow"
          :style="{ transform: open ? 'rotate(180deg)' : 'rotate(0deg)' }"
        >
          <polyline points="6 9 12 15 18 9"/>
        </svg>
      </div>
    </div>

    <div class="plan-body" v-if="open">
      <div class="plan-progress-bar">
        <div class="plan-progress-fill" :style="{ width: (progressRatio * 100) + '%' }"></div>
      </div>

      <div class="plan-items-list">
        <template v-for="(item, idx) in planItems" :key="idx">
          <!-- 阶段大纲标题 -->
          <div v-if="item.isHeader" class="plan-section-header">
            <span class="section-dot"></span>
            <span class="section-title">{{ item.text }}</span>
          </div>

          <!-- 普通待办条目 -->
          <div v-else class="plan-item" :class="item.status">
            <span class="plan-icon">
              <!-- 已完成 -->
              <svg v-if="item.status === 'completed'" class="icon-completed" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#10b981" stroke-width="2.6">
                <polyline points="20 6 9 17 4 12"/>
              </svg>
              <!-- 进行中 -->
              <span v-else-if="item.status === 'current'" class="icon-current-pulse">
                <span class="pulse-dot"></span>
              </span>
              <!-- 待处理 -->
              <svg v-else class="icon-pending" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#d4d4d8" stroke-width="2">
                <circle cx="12" cy="12" r="6"/>
              </svg>
            </span>
            <span class="plan-text" :class="{ done: item.status === 'completed', active: item.status === 'current' }">
              {{ item.text }}
            </span>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'

const props = defineProps({ plan: [String, Array], planJson: String })
const open = ref(true)

function cleanItemText(raw) {
  if (!raw) return ''
  return String(raw)
    .replace(/^#+\s*/, '')
    .replace(/^-\s*\[[xX ]\]\s*/, '')
    .replace(/^\[[xX ]\]\s*/, '')
    .replace(/^[✅☑✓✔🔄⏳👉⬜\[\]已完成进行中当前待办:\s]+/, '')
    .trim()
}

function detectStatus(raw, defaultCompleted = false, defaultCurrent = false) {
  if (defaultCompleted) return 'completed'
  const str = String(raw || '').trim()
  if (
    str.startsWith('✅') || str.startsWith('☑') || str.startsWith('✓') || str.startsWith('✔') ||
    str.startsWith('[已完成]') || str.startsWith('- [x]') || str.startsWith('- [X]') ||
    str.startsWith('[x]') || str.startsWith('[X]') || str.toLowerCase().includes('completed')
  ) {
    return 'completed'
  }
  if (
    defaultCurrent || str.startsWith('🔄') || str.startsWith('⏳') || str.startsWith('👉') ||
    str.startsWith('[当前]') || str.startsWith('[进行中]') || str.toLowerCase().includes('in_progress')
  ) {
    return 'current'
  }
  return 'pending'
}

const planItems = computed(() => {
  // 1. 结构化 JSON 解析
  if (props.planJson) {
    try {
      const arr = JSON.parse(props.planJson)
      if (Array.isArray(arr) && arr.length > 0) {
        return arr.map((item, idx) => {
          const rawText = item.title || item.text || ''
          const isHeader = rawText.startsWith('#')
          const status = detectStatus(rawText, Boolean(item.completed), Boolean(item.current || item.status === 'in_progress'))
          return {
            text: isHeader ? rawText.replace(/^#+\s*/, '').trim() : (cleanItemText(rawText) || rawText),
            status,
            isHeader,
            index: item.index || idx
          }
        })
      }
    } catch (e) {}
  }

  // 2. 数组格式解析
  if (Array.isArray(props.plan) && props.plan.length > 0) {
    return props.plan.map((item, idx) => {
      const rawText = typeof item === 'string' ? item : (item.title || item.text || '')
      const isHeader = rawText.startsWith('#')
      const status = detectStatus(rawText, Boolean(item?.completed), Boolean(item?.current || item?.status === 'in_progress'))
      return {
        text: isHeader ? rawText.replace(/^#+\s*/, '').trim() : (cleanItemText(rawText) || rawText),
        status,
        isHeader,
        index: item?.index || idx
      }
    })
  }

  // 3. 纯文本 / Markdown 字符串格式兜底解析
  if (!props.plan) return []
  const planStr = typeof props.plan === 'string' ? props.plan : ''
  const lines = planStr.split('\n').map(l => l.trim()).filter(Boolean)

  return lines.map((line, idx) => {
    const isHeader = line.startsWith('#')
    const status = detectStatus(line)
    return {
      text: isHeader ? line.replace(/^#+\s*/, '').trim() : (cleanItemText(line) || line),
      status,
      isHeader,
      index: idx
    }
  })
})

const checkableItems = computed(() => planItems.value.filter(i => !i.isHeader))
const checkableTotalCount = computed(() => checkableItems.value.length)
const completedCount = computed(() => checkableItems.value.filter(i => i.status === 'completed').length)
const progressRatio = computed(() => checkableTotalCount.value > 0 ? completedCount.value / checkableTotalCount.value : 0)
</script>

<style scoped>
.plan-display {
  margin: 10px 0;
  border: 1px solid #e4e4e7;
  border-radius: 10px;
  overflow: hidden;
  background: #ffffff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.03);
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}

.plan-display:hover {
  border-color: #d4d4d8;
  box-shadow: 0 4px 16px -2px rgba(0, 0, 0, 0.08), 0 2px 6px -1px rgba(0, 0, 0, 0.04);
}

.plan-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  cursor: pointer;
  user-select: none;
  background: #fafafa;
  border-bottom: 1px solid #f4f4f5;
}

.plan-header-left {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  font-weight: 600;
  color: #18181b;
}

.plan-header-icon-box {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #2563eb;
}

.plan-title {
  letter-spacing: -0.2px;
}

.plan-count-badge {
  font-size: 10.5px;
  font-weight: 700;
  padding: 1px 6px;
  border-radius: 999px;
  background: #eff6ff;
  color: #2563eb;
  border: 1px solid #bfdbfe;
  font-family: 'JetBrains Mono', monospace;
}

.plan-header-right {
  display: flex;
  align-items: center;
  gap: 6px;
}

.plan-collapse-arrow {
  transition: transform 0.2s ease;
}

.plan-body {
  padding: 8px 12px 10px;
}

.plan-progress-bar {
  height: 4px;
  background: #f4f4f5;
  border-radius: 2px;
  margin-bottom: 10px;
  overflow: hidden;
}

.plan-progress-fill {
  height: 100%;
  background: linear-gradient(90deg, #10b981 0%, #059669 100%);
  border-radius: 2px;
  transition: width 0.35s cubic-bezier(0.16, 1, 0.3, 1);
}

.plan-items-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

/* 分组大纲标题 */
.plan-section-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 8px;
  margin: 2px 0 4px;
  border-radius: 6px;
  background: #fffbeb;
  border-left: 3px solid #f59e0b;
}

.section-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #d97706;
}

.section-title {
  font-size: 12px;
  font-weight: 700;
  color: #92400e;
}

/* 计划条目 */
.plan-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 5px 8px;
  border-radius: 6px;
  font-size: 12.5px;
  color: #27272a;
  line-height: 1.45;
  transition: all 0.15s ease;
}

.plan-item.current {
  background: #eff6ff;
  border-left: 3px solid #3b82f6;
  padding-left: 7px;
}

.plan-icon {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-top: 2px;
  width: 14px;
  height: 14px;
}

.icon-current-pulse {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 10px;
  height: 10px;
}

.pulse-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #2563eb;
  box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.25);
  animation: pulsePlanDot 1.6s infinite;
}

@keyframes pulsePlanDot {
  0%, 100% { transform: scale(1); opacity: 1; }
  50% { transform: scale(1.25); opacity: 0.6; }
}

.plan-text {
  flex: 1;
  word-break: break-word;
}

.plan-text.done {
  color: #9ca3af;
  text-decoration: line-through;
}

.plan-text.active {
  color: #1d4ed8;
  font-weight: 600;
}

/* 暗色主题适配 */
:global(.ws-dark) .plan-display,
:global([data-theme="dark"]) .plan-display {
  background: #18181b;
  border-color: #27272a;

  .plan-header {
    background: #27272a;
    border-bottom-color: #3f3f46;
  }

  .plan-header-left {
    color: #f4f4f5;
  }

  .plan-count-badge {
    background: #1e3a8a;
    border-color: #1d4ed8;
    color: #93c5fd;
  }

  .plan-progress-bar {
    background: #27272a;
  }

  .plan-section-header {
    background: #451a03;
    border-left-color: #d97706;
  }

  .section-title {
    color: #fde68a;
  }

  .plan-item {
    color: #e4e4e7;
  }

  .plan-item.current {
    background: #1e293b;
    border-left-color: #3b82f6;
  }

  .plan-text.done {
    color: #71717a;
  }

  .plan-text.active {
    color: #93c5fd;
  }
}
</style>


