<template>
  <div class="model-selector-wrapper" ref="wrapperRef">
    <!-- 触发胶囊 -->
    <button
      type="button"
      class="model-trigger-pill"
      :class="{ open: isOpen }"
      @click="togglePopover"
      :title="`当前模型: ${displayLabel}`"
    >
      <span class="model-label">{{ displayLabel }}</span>
      <span class="icon caret-icon" :class="{ rotated: isOpen }">
        <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="18 15 12 9 6 15"/></svg>
      </span>
    </button>

    <!-- 弹出的模型切换模态卡片 (单卡自适应展开，解决遮挡与宽度溢出问题) -->
    <Transition name="popover-fade">
      <div
        v-if="isOpen"
        class="model-popover-menu"
        @click.stop
      >
        <div class="model-menu-header">
          <span>选择模型 (MODEL)</span>
        </div>

        <div class="model-list-scroll">
          <div
            v-for="model in modelList"
            :key="model.name"
            class="model-item-group"
          >
            <!-- 主模型行 -->
            <div
              class="model-menu-item"
              :class="{
                active: currentModel === model.name,
                expanded: expandedThinkingModel === model.name
              }"
              @click="handleModelClick(model)"
            >
              <div class="model-item-left">
                <span class="model-name-text">{{ model.label || model.name }}</span>
                <span v-if="model.badge" class="model-badge-tag">{{ model.badge }}</span>
              </div>

              <div class="model-item-right">
                <!-- 支持思考程度的模型展示展开箭头 -->
                <button
                  v-if="model.supportsThinking"
                  type="button"
                  class="sub-arrow-btn"
                  :class="{ rotated: expandedThinkingModel === model.name }"
                  @click.stop="toggleThinkingAccordion(model)"
                  title="选择思考程度"
                >
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="9 18 15 12 9 6"/></svg>
                </button>
                <span v-else-if="currentModel === model.name" class="check-mark">✓</span>
              </div>
            </div>

            <!-- 思考程度展开区 (流畅手风琴下展，绝不越界被裁切) -->
            <div
              v-if="model.supportsThinking && expandedThinkingModel === model.name"
              class="thinking-levels-accordion"
            >
              <div class="thinking-accordion-header">思考深度</div>
              <div class="thinking-level-grid">
                <button
                  v-for="lvl in ['Low', 'Medium', 'High']"
                  :key="lvl"
                  type="button"
                  class="thinking-pill-btn"
                  :class="{ active: currentModel === model.name && thinkingLevel === lvl }"
                  @click.stop="selectThinkingLevel(model, lvl)"
                >
                  <span>{{ lvl }}</span>
                  <span v-if="currentModel === model.name && thinkingLevel === lvl" class="check-mark">✓</span>
                </button>
              </div>
            </div>
          </div>
        </div>

        <div class="model-menu-footer">
          <button type="button" class="footer-action-link" @click="openConfigDialog">
            <span class="icon"><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg></span>
            <span>模型配置与 Key</span>
          </button>
        </div>
      </div>
    </Transition>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'

const props = defineProps({
  currentModel: {
    type: String,
    default: 'deepseek-v4-flash',
  },
  thinkingLevel: {
    type: String,
    default: 'High',
  },
  models: {
    type: Array,
    default: () => [],
  },
})

const emit = defineEmits(['change-model', 'change-thinking', 'open-config'])

const isOpen = ref(false)
const wrapperRef = ref(null)
const expandedThinkingModel = ref(null)

const defaultModelPresets = [
  { name: 'deepseek-v4-flash', label: 'deepseek-v4-flash', supportsThinking: true, badge: '默认' },
  { name: 'deepseek-reasoner', label: 'deepseek-reasoner', supportsThinking: true, badge: 'R1' },
  { name: 'Claude 3.7 Sonnet', label: 'Claude 3.7 Sonnet', supportsThinking: true, badge: 'Coding' },
  { name: 'Gemini 2.5 Pro', label: 'Gemini 2.5 Pro', supportsThinking: true, badge: 'Pro' },
  { name: 'GPT-4o', label: 'GPT-4o', supportsThinking: false },
]

const modelList = computed(() => {
  if (!props.models || props.models.length === 0) {
    return defaultModelPresets
  }
  return props.models.map(m => ({
    id: m.configId || m.id,
    name: m.configName || m.modelName || m.name || 'default',
    label: m.configName || m.modelName || m.label || m.name || 'default',
    supportsThinking: m.supportsThinking ?? true,
    badge: m.isDefault ? '默认' : (m.badge || '')
  }))
})

const displayLabel = computed(() => {
  const m = modelList.value.find(item => item.name === props.currentModel || item.label === props.currentModel)
  const name = m ? (m.label || m.name) : props.currentModel
  if (m?.supportsThinking && props.thinkingLevel) {
    return `${name} (${props.thinkingLevel})`
  }
  return name
})

function togglePopover() {
  isOpen.value = !isOpen.value
  if (isOpen.value) {
    // 默认展开当前选中模型的思考级别
    const curr = modelList.value.find(m => m.name === props.currentModel || m.label === props.currentModel)
    if (curr?.supportsThinking) {
      expandedThinkingModel.value = curr.name
    }
  } else {
    expandedThinkingModel.value = null
  }
}

function toggleThinkingAccordion(model) {
  if (expandedThinkingModel.value === model.name) {
    expandedThinkingModel.value = null
  } else {
    expandedThinkingModel.value = model.name
  }
}

function handleModelClick(model) {
  if (model.supportsThinking) {
    toggleThinkingAccordion(model)
  } else {
    emit('change-model', model.name)
    isOpen.value = false
    expandedThinkingModel.value = null
  }
}

function selectThinkingLevel(model, level) {
  emit('change-model', model.name)
  emit('change-thinking', level)
  isOpen.value = false
  expandedThinkingModel.value = null
}

function openConfigDialog() {
  isOpen.value = false
  emit('open-config')
}

function handleOutsideClick(e) {
  if (wrapperRef.value && !wrapperRef.value.contains(e.target)) {
    isOpen.value = false
    expandedThinkingModel.value = null
  }
}

onMounted(() => {
  document.addEventListener('click', handleOutsideClick)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', handleOutsideClick)
})
</script>

<style scoped>
.model-selector-wrapper {
  position: relative;
  display: inline-flex;
  align-items: center;
}

.model-trigger-pill {
  height: 26px;
  padding: 0 8px 0 10px;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
  color: #3f3f46;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  cursor: pointer;
  transition: all 0.12s ease;
}

.model-trigger-pill:hover,
.model-trigger-pill.open {
  background: #f4f4f5;
  color: #09090b;
}

.model-label {
  white-space: nowrap;
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.caret-icon {
  color: #71717a;
  transition: transform 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}

.caret-icon.rotated {
  transform: rotate(180deg);
}

/* 主模型列表卡片 */
.model-popover-menu {
  position: absolute;
  bottom: calc(100% + 8px);
  right: 0;
  width: 256px;
  background: #ffffff;
  border: 1px solid #e4e4e7;
  border-radius: 10px;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.12), 0 4px 10px rgba(0, 0, 0, 0.05);
  display: flex;
  flex-direction: column;
  padding: 6px 0;
  user-select: none;
  z-index: 100000;
}

.model-menu-header {
  padding: 6px 12px 6px 12px;
  font-size: 11px;
  font-weight: 700;
  color: #52525b;
  letter-spacing: 0.02em;
  border-bottom: 1px solid #f4f4f5;
}

.model-list-scroll {
  max-height: 280px;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 4px 0;
}

.model-item-group {
  display: flex;
  flex-direction: column;
}

.model-menu-item {
  padding: 7px 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12.5px;
  color: #09090b;
  cursor: pointer;
  transition: background 0.1s;
}

.model-menu-item:hover,
.model-menu-item.expanded {
  background: #f4f4f5;
}

.model-menu-item.active {
  font-weight: 600;
}

.model-item-left {
  display: flex;
  align-items: center;
  gap: 6px;
  overflow: hidden;
}

.model-name-text {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.model-badge-tag {
  font-size: 10px;
  padding: 1px 5px;
  background: #f4f4f5;
  border: 1px solid #e4e4e7;
  border-radius: 3px;
  color: #71717a;
  font-weight: normal;
  flex-shrink: 0;
}

.model-item-right {
  display: flex;
  align-items: center;
  gap: 4px;
}

.check-mark {
  color: #09090b;
  font-weight: 700;
  font-size: 12px;
}

.sub-arrow-btn {
  background: transparent;
  border: none;
  padding: 2px 4px;
  color: #a1a1aa;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: transform 0.16s ease, color 0.12s;
}

.sub-arrow-btn:hover {
  color: #09090b;
}

.sub-arrow-btn.rotated {
  transform: rotate(90deg);
}

/* 思考程度手风琴折叠区 */
.thinking-levels-accordion {
  background: #fafafa;
  border-top: 1px dashed #e4e4e7;
  border-bottom: 1px dashed #e4e4e7;
  padding: 8px 12px;
  margin: 2px 0 4px 0;
  animation: accordionIn 0.15s ease;
}

.thinking-accordion-header {
  font-size: 10.5px;
  font-weight: 600;
  color: #71717a;
  margin-bottom: 6px;
}

.thinking-level-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
}

.thinking-pill-btn {
  padding: 4px 6px;
  background: #ffffff;
  border: 1px solid #e4e4e7;
  border-radius: 6px;
  font-size: 11.5px;
  color: #3f3f46;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 3px;
  transition: all 0.12s;
}

.thinking-pill-btn:hover {
  border-color: #d4d4d8;
  background: #f4f4f5;
  color: #09090b;
}

.thinking-pill-btn.active {
  background: #18181b;
  border-color: #18181b;
  color: #ffffff;
  font-weight: 600;
}

.thinking-pill-btn.active .check-mark {
  color: #ffffff;
}

.model-menu-footer {
  padding: 4px 8px 2px 8px;
  border-top: 1px solid #f4f4f5;
  margin-top: 4px;
}

.footer-action-link {
  width: 100%;
  padding: 6px 8px;
  background: transparent;
  border: none;
  border-radius: 5px;
  font-size: 11.5px;
  color: #71717a;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  transition: all 0.12s;
}

.footer-action-link:hover {
  background: #f4f4f5;
  color: #09090b;
}

.icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

@keyframes accordionIn {
  from {
    opacity: 0;
    transform: translateY(-4px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* Animations */
.popover-fade-enter-active,
.popover-fade-leave-active {
  transition: opacity 0.16s ease, transform 0.16s cubic-bezier(0.16, 1, 0.3, 1);
}

.popover-fade-enter-from,
.popover-fade-leave-to {
  opacity: 0;
  transform: translateY(4px);
}
</style>
