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

    <!-- 弹出的模型切换模态卡片 -->
    <Transition name="popover-fade">
      <div
        v-if="isOpen"
        class="model-popover-menu"
        @click.stop
      >
        <div class="model-menu-header">
          <span>选择模型 (Model)</span>
        </div>

        <div class="model-list-scroll">
          <div
            v-for="model in modelList"
            :key="model.name"
            class="model-menu-item"
            :class="{ active: currentModel === model.name }"
            @click="selectModelItem(model)"
          >
            <div class="model-item-left">
              <span class="model-name-text">{{ model.label || model.name }}</span>
              <span v-if="model.badge" class="model-badge-tag">{{ model.badge }}</span>
            </div>

            <!-- 如果模型支持思考程度选择，显示二级指示与悬浮子菜单 -->
            <template v-if="model.supportsThinking">
              <span class="icon sub-arrow">
                <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="9 18 15 12 9 6"/></svg>
              </span>
              <div class="thinking-level-submenu">
                <div class="submenu-header">思考程度</div>
                <div
                  v-for="lvl in ['Low', 'Medium', 'High']"
                  :key="lvl"
                  class="thinking-level-subitem"
                  :class="{ active: currentModel === model.name && thinkingLevel === lvl }"
                  @click.stop="selectThinkingLevel(model, lvl)"
                >
                  <span>{{ lvl }}</span>
                  <span v-if="currentModel === model.name && thinkingLevel === lvl" class="check-mark">✓</span>
                </div>
              </div>
            </template>
            <template v-else>
              <span v-if="currentModel === model.name" class="check-mark">✓</span>
            </template>
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
    default: 'Gemini 3.7 Flash',
  },
  thinkingLevel: {
    type: String,
    default: 'High',
  },
  models: {
    type: Array,
    default: () => [
      { name: 'Gemini 3.7 Flash', label: 'Gemini 3.7 Flash', supportsThinking: true, badge: 'Fast ⓘ' },
      { name: 'Claude 3.7 Sonnet', label: 'Claude 3.7 Sonnet', supportsThinking: true, badge: 'Coding' },
      { name: 'DeepSeek R1', label: 'DeepSeek R1', supportsThinking: true, badge: 'Reasoning' },
      { name: 'Claude 3.5 Sonnet', label: 'Claude 3.5 Sonnet', supportsThinking: false },
      { name: 'GPT-4o', label: 'GPT-4o', supportsThinking: false },
      { name: 'MiniMax-Text-01', label: 'MiniMax-Text-01', supportsThinking: false },
    ],
  },
})

const emit = defineEmits(['change-model', 'change-thinking', 'open-config'])

const isOpen = ref(false)
const wrapperRef = ref(null)

const modelList = computed(() => props.models)

const displayLabel = computed(() => {
  const m = modelList.value.find(item => item.name === props.currentModel)
  const name = m ? (m.label || m.name) : props.currentModel
  if (m?.supportsThinking && props.thinkingLevel) {
    return `${name} ${props.thinkingLevel}`
  }
  return name
})

function togglePopover() {
  isOpen.value = !isOpen.value
}

function selectModelItem(model) {
  emit('change-model', model.name)
  if (!model.supportsThinking) {
    isOpen.value = false
  }
}

function selectThinkingLevel(model, level) {
  emit('change-model', model.name)
  emit('change-thinking', level)
  isOpen.value = false
}

function openConfigDialog() {
  isOpen.value = false
  emit('open-config')
}

function handleOutsideClick(e) {
  if (wrapperRef.value && !wrapperRef.value.contains(e.target)) {
    isOpen.value = false
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

.model-popover-menu {
  position: absolute;
  bottom: calc(100% + 8px);
  right: 0;
  width: 260px;
  background: #ffffff;
  border: 1px solid #e4e4e7;
  border-radius: 10px;
  box-shadow: 0 10px 30px -4px rgba(0, 0, 0, 0.12), 0 4px 10px -2px rgba(0, 0, 0, 0.05);
  z-index: 200;
  display: flex;
  flex-direction: column;
  padding: 6px 0;
  user-select: none;
}

.model-menu-header {
  padding: 6px 12px 4px 12px;
  font-size: 11px;
  font-weight: 600;
  color: #71717a;
  text-transform: uppercase;
  letter-spacing: 0.03em;
  border-bottom: 1px solid #f4f4f5;
}

.model-list-scroll {
  max-height: 240px;
  overflow-y: auto;
  padding: 4px 0;
}

.model-menu-item {
  padding: 7px 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12.5px;
  color: #09090b;
  cursor: pointer;
  position: relative;
  transition: background 0.1s;
}

.model-menu-item:hover {
  background: #f4f4f5;
}

.model-menu-item.active {
  font-weight: 600;
}

.model-item-left {
  display: flex;
  align-items: center;
  gap: 6px;
}

.model-badge-tag {
  font-size: 10px;
  padding: 1px 5px;
  background: #f4f4f5;
  border: 1px solid #e4e4e7;
  border-radius: 3px;
  color: #71717a;
  font-weight: normal;
}

.check-mark {
  color: #09090b;
  font-weight: 700;
  font-size: 12px;
}

.sub-arrow {
  color: #a1a1aa;
}

/* 思考程度二级子菜单 */
.thinking-level-submenu {
  position: absolute;
  top: 0;
  left: calc(100% + 4px);
  width: 120px;
  background: #ffffff;
  border: 1px solid #e4e4e7;
  border-radius: 8px;
  box-shadow: 0 10px 30px -4px rgba(0, 0, 0, 0.12);
  display: none;
  flex-direction: column;
  padding: 4px 0;
  z-index: 210;
}

.model-menu-item:hover .thinking-level-submenu {
  display: flex;
}

.submenu-header {
  padding: 4px 10px;
  font-size: 10px;
  color: #a1a1aa;
  font-weight: 600;
}

.thinking-level-subitem {
  padding: 5px 10px;
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  cursor: pointer;
  transition: background 0.1s;
}

.thinking-level-subitem:hover {
  background: #f4f4f5;
}

.thinking-level-subitem.active {
  font-weight: 600;
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
