<template>
  <div class="model-selector-wrapper" ref="wrapperRef">
    <!-- 触发胶囊 -->
    <button
      type="button"
      class="model-trigger-pill"
      :class="{ open: isOpen, 'is-unconfigured': !hasModels && (!currentModel || currentModel === '未配置模型') }"
      @click="togglePopover"
      :title="(hasModels || (currentModel && currentModel !== '未配置模型')) ? `当前模型: ${displayLabel}` : '未配置模型，点击进行配置'"
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

        <!-- 1. 有模型时的选择列表 -->
        <div v-if="hasModels" class="model-list-scroll">
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

        <!-- 2. 无模型时的提示与引导卡片 -->
        <div v-else class="model-empty-state">
          <div class="model-empty-icon">
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
              <circle cx="12" cy="12" r="10"/>
              <line x1="12" y1="8" x2="12" y2="12"/>
              <line x1="12" y1="16" x2="12.01" y2="16"/>
            </svg>
          </div>
          <p class="model-empty-title">未配置 AI 模型</p>
          <p class="model-empty-tip">请先添加模型并配置 API Key 即可开始使用</p>
          <button type="button" class="model-empty-create-btn" @click="openConfigDialog">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
            <span>立即配置模型</span>
          </button>
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
    default: '',
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

const hasModels = computed(() => Array.isArray(props.models) && props.models.length > 0)

const modelList = computed(() => {
  if (!hasModels.value) {
    return []
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
  if (!hasModels.value) {
    if (props.currentModel && props.currentModel !== '未配置模型') {
      return props.thinkingLevel ? `${props.currentModel} (${props.thinkingLevel})` : props.currentModel
    }
    return '未配置模型'
  }
  const m = modelList.value.find(item => item.name === props.currentModel || item.label === props.currentModel)
  const name = m ? (m.label || m.name) : (props.currentModel || '请选择模型')
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
  color: var(--ai-text-secondary, #3f3f46);
  display: inline-flex;
  align-items: center;
  gap: 5px;
  cursor: pointer;
  transition: all 0.12s ease;
}

.model-trigger-pill.is-unconfigured {
  color: var(--ai-text-muted, #71717a);
  border-color: var(--ai-border-strong, #e4e4e7);
  background: var(--ai-bg-secondary, #fbfbfb);
}

.model-trigger-pill:hover,
.model-trigger-pill.open {
  background: var(--ai-bg-tertiary, #f4f4f5);
  color: var(--ai-text, #09090b);
}

.model-label {
  white-space: nowrap;
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  font-weight: 550;
  letter-spacing: -0.01em;
}

.caret-icon {
  color: var(--ai-text-muted, #71717a);
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
  background: var(--ai-bg, #ffffff);
  border: 1px solid var(--ai-border-strong, #e4e4e7);
  border-radius: 10px;
  box-shadow: var(--ai-shadow-lg, 0 12px 32px rgba(0, 0, 0, 0.12));
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
  color: var(--ai-text-muted, #52525b);
  letter-spacing: 0.02em;
  border-bottom: 1px solid var(--ai-border, #f4f4f5);
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
  color: var(--ai-text, #09090b);
  cursor: pointer;
  transition: background 0.1s;
}

.model-menu-item:hover,
.model-menu-item.expanded {
  background: var(--ai-bg-tertiary, #f4f4f5);
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
  background: var(--ai-bg-tertiary, #f4f4f5);
  border: 1px solid var(--ai-border-strong, #e4e4e7);
  border-radius: 3px;
  color: var(--ai-text-muted, #71717a);
  font-weight: normal;
  flex-shrink: 0;
}

.model-item-right {
  display: flex;
  align-items: center;
  gap: 4px;
}

.check-mark {
  color: var(--ai-accent, #09090b);
  font-weight: 700;
  font-size: 12px;
}

.sub-arrow-btn {
  background: transparent;
  border: none;
  padding: 2px 4px;
  color: var(--ai-text-faint, #a1a1aa);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: transform 0.16s ease, color 0.12s;
}

.sub-arrow-btn:hover {
  color: var(--ai-text, #09090b);
}

.sub-arrow-btn.rotated {
  transform: rotate(90deg);
}

/* 思考程度手风琴折叠区 */
.thinking-levels-accordion {
  background: var(--ai-bg-secondary, #fafafa);
  border-top: 1px dashed var(--ai-border-strong, #e4e4e7);
  border-bottom: 1px dashed var(--ai-border-strong, #e4e4e7);
  padding: 8px 12px;
  margin: 2px 0 4px 0;
  animation: accordionIn 0.15s ease;
}

.thinking-accordion-header {
  font-size: 10.5px;
  font-weight: 600;
  color: var(--ai-text-muted, #71717a);
  margin-bottom: 6px;
}

.thinking-level-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
}

.thinking-pill-btn {
  padding: 4px 6px;
  background: var(--ai-bg, #ffffff);
  border: 1px solid var(--ai-border-strong, #e4e4e7);
  border-radius: 6px;
  font-size: 11.5px;
  color: var(--ai-text-secondary, #3f3f46);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 3px;
  transition: all 0.12s;
}

.thinking-pill-btn:hover {
  border-color: var(--ai-border-focus, #d4d4d8);
  background: var(--ai-bg-tertiary, #f4f4f5);
  color: var(--ai-text, #09090b);
}

.thinking-pill-btn.active {
  background: var(--ai-accent, #18181b);
  border-color: var(--ai-accent, #18181b);
  color: #ffffff;
  font-weight: 600;
}

.thinking-pill-btn.active .check-mark {
  color: #ffffff;
}

.model-menu-footer {
  padding: 4px 8px 2px 8px;
  border-top: 1px solid var(--ai-border, #f4f4f5);
  margin-top: 4px;
}

.footer-action-link {
  width: 100%;
  padding: 6px 8px;
  background: transparent;
  border: none;
  border-radius: 5px;
  font-size: 11.5px;
  color: var(--ai-text-muted, #71717a);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  transition: all 0.12s;
}

.footer-action-link:hover {
  background: var(--ai-bg-tertiary, #f4f4f5);
  color: var(--ai-text, #09090b);
}

/* 未配置模型空状态 */
.model-empty-state {
  padding: 24px 16px 20px 16px;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
}

.model-empty-icon {
  color: var(--ai-text-faint, #a1a1aa);
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.model-empty-title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--ai-text, #27272a);
}

.model-empty-tip {
  margin: 5px 0 14px 0;
  font-size: 11.5px;
  color: var(--ai-text-muted, #71717a);
  line-height: 1.4;
}

.model-empty-create-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 6px 14px;
  background: var(--ai-accent, #18181b);
  color: #ffffff;
  border: none;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s ease;
}

.model-empty-create-btn:hover {
  background: var(--ai-accent-hover, #27272a);
}

/* 暗色主题深度增强 */
:global(html[data-theme="dark"] .model-popover-menu) {
  background: #181b24;
  border-color: #303747;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.45);
}
:global(html[data-theme="dark"] .model-menu-header) {
  background: #202430;
  border-bottom-color: #272a37;
  color: #a6adc8;
}
:global(html[data-theme="dark"] .model-menu-item) {
  color: #edf1fb;
}
:global(html[data-theme="dark"] .model-menu-item:hover),
:global(html[data-theme="dark"] .model-menu-item.expanded) {
  background: #242538;
}
:global(html[data-theme="dark"] .model-menu-item.active) {
  background: rgba(99, 102, 241, 0.22);
  color: #ffffff;
}
:global(html[data-theme="dark"] .model-name-text) {
  color: #edf1fb;
}
:global(html[data-theme="dark"] .model-badge-tag) {
  background: #242538;
  border-color: #384158;
  color: #cdd6f4;
}
:global(html[data-theme="dark"] .check-mark) {
  color: #818cf8;
}
:global(html[data-theme="dark"] .sub-arrow-btn) {
  color: #8c96a8;
}
:global(html[data-theme="dark"] .sub-arrow-btn:hover) {
  color: #ffffff;
}
:global(html[data-theme="dark"] .thinking-levels-accordion) {
  background: #141720;
  border-top-color: #2e3547;
  border-bottom-color: #2e3547;
}
:global(html[data-theme="dark"] .thinking-accordion-header) {
  color: #8c96a8;
}
:global(html[data-theme="dark"] .thinking-pill-btn) {
  background: #202430;
  border-color: #384158;
  color: #edf1fb;
}
:global(html[data-theme="dark"] .thinking-pill-btn:hover) {
  background: #2a3142;
  border-color: #4f5d80;
  color: #ffffff;
}
:global(html[data-theme="dark"] .thinking-pill-btn.active) {
  background: #4f46e5;
  border-color: #4f46e5;
  color: #ffffff;
}
:global(html[data-theme="dark"] .thinking-pill-btn.active .check-mark) {
  color: #ffffff;
}
:global(html[data-theme="dark"] .model-menu-footer) {
  border-top-color: #272a37;
}
:global(html[data-theme="dark"] .footer-action-link) {
  color: #89b4fa;
}
:global(html[data-theme="dark"] .footer-action-link:hover) {
  background: #202430;
  color: #ffffff;
}
:global(html[data-theme="dark"] .model-empty-title) {
  color: #edf1fb;
}
:global(html[data-theme="dark"] .model-empty-tip) {
  color: #a6adc8;
}
:global(html[data-theme="dark"] .model-empty-create-btn) {
  background: #4f46e5;
  color: #ffffff;
}
:global(html[data-theme="dark"] .model-empty-create-btn:hover) {
  background: #4338ca;
}
:global(html[data-theme="dark"] .model-trigger-pill) {
  color: #cdd6f4;
}
:global(html[data-theme="dark"] .model-trigger-pill .model-label) {
  color: #cdd6f4;
}
:global(html[data-theme="dark"] .model-trigger-pill .caret-icon) {
  color: #8c96a8;
}
:global(html[data-theme="dark"] .model-trigger-pill.is-unconfigured) {
  background: #202430;
  border-color: #384158;
  color: #a6adc8;
}
:global(html[data-theme="dark"] .model-trigger-pill:hover),
:global(html[data-theme="dark"] .model-trigger-pill.open) {
  background: rgba(255, 255, 255, 0.08);
  color: #ffffff;
}
:global(html[data-theme="dark"] .model-trigger-pill:hover .model-label),
:global(html[data-theme="dark"] .model-trigger-pill.open .model-label) {
  color: #ffffff;
}
:global(html[data-theme="dark"] .model-trigger-pill:hover .caret-icon),
:global(html[data-theme="dark"] .model-trigger-pill.open .caret-icon) {
  color: #ffffff;
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
