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

            <!-- 思考程度：单排分段控件。档位由后端按模型能力下发，折叠关系只在脚注里说一次 -->
            <div
              v-if="model.supportsThinking && expandedThinkingModel === model.name"
              class="thinking-levels-accordion"
            >
              <div class="thinking-accordion-header">
                <span>思考深度</span>
                <span v-if="activeLevelEffectiveNote(model)" class="thinking-header-note">
                  {{ activeLevelEffectiveNote(model) }}
                </span>
              </div>

              <div class="thinking-segmented" role="radiogroup" aria-label="思考深度">
                <!-- 滑动指示块：承载选中态背景，靠 transform 位移产生滑动动画。
                     它与按钮分离，因此切换档位时高亮是"滑过去"而不是"闪一下"。 -->
                <span
                  v-if="thumbStyle(model)"
                  class="thinking-thumb"
                  :style="thumbStyle(model)"
                  aria-hidden="true"
                ></span>
                <button
                  v-for="opt in model.reasoningOptions"
                  :key="opt.value"
                  type="button"
                  role="radio"
                  :aria-checked="isSelectedLevel(model, opt.value)"
                  class="thinking-segment"
                  :class="{
                    active: isSelectedLevel(model, opt.value),
                    folded: opt.effective !== opt.value
                  }"
                  :title="levelTitle(opt)"
                  @click.stop="selectThinkingLevel(model, opt)"
                >
                  {{ opt.label }}
                </button>
              </div>

              <p v-if="foldedSummary(model)" class="thinking-footnote">{{ foldedSummary(model) }}</p>
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
    default: '',
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
    // name 用于 emit 与后端交互；configName / modelName 单独保留，供识别当前选中行。
    name: m.configName || m.modelName || m.name || 'default',
    configName: m.configName || '',
    modelName: m.modelName || '',
    label: m.configName || m.modelName || m.label || m.name || 'default',
    // 档位与折叠关系由后端按 modelName + requestOptionsJson 判定，组件不得自行猜测。
    reasoningOptions: Array.isArray(m.reasoningOptions) ? m.reasoningOptions : [],
    supportsThinking: Array.isArray(m.reasoningOptions) && m.reasoningOptions.length > 0,
    badge: m.isDefault ? '默认' : (m.badge || '')
  }))
})

/**
 * 当前选中的模型行。档位是该模型配置的属性，不属于组件自身状态。
 *
 * 父组件传入的 currentModel 是 modelName（如 deepseek-v4.1-flash），而列表标签优先用 configName
 * （如 Deepseek go）。只比较单一字段会导致选中行识别不到，表现是档位没有选中态、胶囊不显示档位。
 * 这里按 configName → modelName → 显示名逐级匹配。
 */
const activeModelRow = computed(() => {
  const current = String(props.currentModel || '')
  if (!current) return null
  const rows = modelList.value
  return rows.find(item => item.configName === current)
    || rows.find(item => item.modelName === current)
    || rows.find(item => item.name === current || item.label === current)
    || null
})

function labelOf(model, value) {
  const found = (model.reasoningOptions || []).find(opt => opt.value === value)
  return found ? found.label : value
}

function isSelectedLevel(model, value) {
  return activeModelRow.value === model && String(props.thinkingLevel || '').toLowerCase() === value
}

/**
 * 滑动指示块的位置与宽度。
 *
 * 用 `width = 轨道内宽 / 档位数` + `translateX(index × 100%)` 表达位置：位移量以自身宽度为
 * 单位，因此不需要在 JS 里测量像素，窗口缩放与档位数变化都能自动适配。
 * 轨道左右各 2px 内边距、按钮之间无 gap —— 这两个前提必须与样式保持一致，否则会错位。
 * 无选中项（例如展开的是非当前模型）时返回 null，由 v-if 隐藏指示块。
 */
function thumbStyle(model) {
  const options = model.reasoningOptions || []
  const index = options.findIndex(opt => isSelectedLevel(model, opt.value))
  if (index < 0) return null
  return {
    width: `calc((100% - 4px) / ${options.length})`,
    transform: `translateX(${index * 100}%)`
  }
}

/**
 * 当前选中档在上游的实际执行档；只在被折叠（与所选档位不同）时给出说明。
 * 未折叠时不显示任何文字——档位名本身已经说明一切，多余的提示就是噪音。
 */
function activeLevelEffectiveNote(model) {
  const level = String(props.thinkingLevel || '').toLowerCase()
  if (!activeModelRow.value || activeModelRow.value !== model || !level) return ''
  const hit = (model.reasoningOptions || []).find(opt => opt.value === level)
  if (!hit || hit.effective === hit.value) return ''
  return `按「${labelOf(model, hit.effective)}」执行`
}

/**
 * 折叠关系摘要（如「中 → 高  超高 → 极致」）。整块只出现一次，
 * 避免把同样的角标塞进每个按钮里造成视觉噪音。
 */
function foldedSummary(model) {
  const folded = (model.reasoningOptions || []).filter(opt => opt.effective !== opt.value)
  if (!folded.length) return ''
  return folded.map(opt => `${opt.label} → ${labelOf(model, opt.effective)}`).join('　')
}

function levelTitle(opt) {
  if (!opt || opt.effective === opt.value) return `${opt?.label || ''}：会原样写入请求`
  return `${opt.label}：上游会按「${labelOf({ reasoningOptions: activeModelRow.value?.reasoningOptions || [] }, opt.effective)}」执行`
}

const displayLabel = computed(() => {
  if (!hasModels.value) {
    if (props.currentModel && props.currentModel !== '未配置模型') {
      return props.currentModel
    }
    return '未配置模型'
  }
  const m = activeModelRow.value
  const name = m ? (m.label || m.name) : (props.currentModel || '请选择模型')
  const level = String(props.thinkingLevel || '').toLowerCase()
  if (m?.supportsThinking && level) {
    return `${name} (${labelOf(m, level)})`
  }
  return name
})

function togglePopover() {
  isOpen.value = !isOpen.value
  if (isOpen.value) {
    // 默认展开当前选中模型的思考级别
    const curr = activeModelRow.value
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

/**
 * 选择档位：交给父组件落库。
 *
 * 这里刻意**不关闭**浮层：保存是异步的，若立刻收起，滑块动画与结果都来不及被看见，
 * 用户只会觉得"点了一下东西就没了"。保持展开可以让指示块滑到新位置作为确认；
 * 若保存失败，指示块不动（拦截器会给出原因），界面不会宣称未发生的事。
 */
function selectThinkingLevel(model, option) {
  emit('change-thinking', {
    configId: model.id,
    modelName: model.name,
    value: option.value
  })
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
  /* 288px：三列档位在最长文案「超高→极致 ✓」时仍能单行放下（实测 78px 单元格会换行） */
  width: 288px;
  /* 窄屏（320px）保护：靠右锚定，加宽只会向左展开，但要防止贴边 */
  max-width: calc(100vw - 20px);
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

/* 思考程度折叠区：单排分段控件，不分行、不堆角标 */
.thinking-levels-accordion {
  background: var(--ai-bg-secondary, #fafafa);
  border-top: 1px dashed var(--ai-border-strong, #e4e4e7);
  border-bottom: 1px dashed var(--ai-border-strong, #e4e4e7);
  padding: 9px 12px 10px 12px;
  margin: 2px 0 4px 0;
  animation: accordionIn 0.15s ease;
}

.thinking-accordion-header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  font-size: 10.5px;
  font-weight: 600;
  color: var(--ai-text-muted, #71717a);
  margin-bottom: 7px;
}

/* 选中档被上游折叠时的即时说明。色值取可读下限而非最浅的令牌：
   --ai-text-faint 在本底色上仅 2.46:1，达不到正文 4.5:1。 */
.thinking-header-note {
  font-size: 10px;
  font-weight: 400;
  color: #64748b;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 轨道：左右各 2px 内边距、按钮之间不留 gap。
   这两个数值与 thumbStyle() 的位移算式（(100% - 4px) / N）严格绑定，改任一处都必须同步。 */
.thinking-segmented {
  position: relative;
  display: flex;
  align-items: stretch;
  padding: 2px;
  background: var(--ai-bg-tertiary, #f4f4f5);
  border: 1px solid var(--ai-border, #f4f4f5);
  border-radius: 8px;
}

/* 滑动指示块：只负责背景，压在按钮之下 */
.thinking-thumb {
  position: absolute;
  top: 2px;
  left: 2px;
  bottom: 2px;
  z-index: 0;
  border-radius: 6px;
  background: var(--ai-accent, #18181b);
  /* 平滑滑动：ease-out 缓出，末段收得干净，比 linear 更像"滑到位" */
  transition: transform 0.24s cubic-bezier(0.22, 1, 0.36, 1),
              width 0.24s cubic-bezier(0.22, 1, 0.36, 1);
  will-change: transform;
}

@media (prefers-reduced-motion: reduce) {
  .thinking-thumb { transition: none; }
}

.thinking-segment {
  position: relative;
  z-index: 1;
  flex: 1 1 0;
  min-width: 0;
  height: 26px;
  padding: 0 2px;
  background: transparent;
  border: none;
  border-radius: 6px;
  font-size: 11.5px;
  font-family: inherit;
  color: var(--ai-text-secondary, #3f3f46);
  cursor: pointer;
  white-space: nowrap;
  transition: color 0.18s ease;
}

.thinking-segment:hover:not(.active) {
  color: var(--ai-text, #09090b);
}

/* 按下时轻微收一下，补足接口往返期间的手感（此处不宣称已保存，真实状态由指示块体现） */
.thinking-segment:active {
  transform: scale(0.97);
}

/* 折叠档位的文字更淡，暗示「与另一档等价」；实际生效档位由脚注说明。
   这里直写色值而不用令牌：现有 --ai-text-faint 在本底色上只有 2.33:1、
   --ai-text-muted 只有 4.33:1，都达不到正文 4.5:1；本值是该约束下的可辨识下限。
   选中态必须排除，否则会与滑块上的白字互相覆盖。 */
.thinking-segment.folded:not(.active) {
  color: #5b6b80;
}

/* 选中档只改文字色 —— 背景由 .thinking-thumb 承担，切换时才能滑动 */
.thinking-segment.active {
  color: #ffffff;
  font-weight: 550;
}

/* 折叠关系脚注：整块只出现一次。这是「哪些档位等价」的唯一解释，必须可读，
   因此同样取 4.5:1 的下限色值，而不是用更浅的装饰色。 */
.thinking-footnote {
  margin: 7px 0 0 0;
  font-size: 10px;
  line-height: 1.5;
  color: #64748b;
  overflow-wrap: anywhere;
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
:global(html[data-theme="dark"] .thinking-header-note) {
  color: #7c8499;
}
:global(html[data-theme="dark"] .thinking-segmented) {
  background: #141720;
  border-color: #262c3a;
}
:global(html[data-theme="dark"] .thinking-segment) {
  color: #cdd6f4;
}
:global(html[data-theme="dark"] .thinking-segment.folded:not(.active)) {
  color: #838ba0;
}
:global(html[data-theme="dark"] .thinking-segment:hover:not(.active)) {
  color: #ffffff;
}
:global(html[data-theme="dark"] .thinking-thumb) {
  background: #4f46e5;
}
:global(html[data-theme="dark"] .thinking-footnote) {
  color: #7c8499;
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
