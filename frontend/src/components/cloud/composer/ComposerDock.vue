<template>
  <div class="composer-dock-container">
    <div class="composer-dock-inner">
      <!-- 1. 顶部模式切换 (构建 / 规划 / 探索) -->
      <div class="composer-mode-bar">
        <ModeSlider
          :model-value="agentMode"
          @update:model-value="val => emit('update:agentMode', val)"
          @change="val => emit('mode-change', val)"
        />
      </div>

      <!-- 2. 主输入卡片 -->
      <div
        class="composer-card"
        :class="{
          'is-image-dragover': isDragOver,
          'is-loading': loading
        }"
        @dragenter.prevent="onDragEnter"
        @dragover.prevent="onDragOver"
        @dragleave.prevent="onDragLeave"
        @drop.prevent="onDrop"
      >
        <!-- 附加选中的代码片段提示 -->
        <div v-if="selectedCode" class="composer-code-context">
          <span class="icon">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>
          </span>
          <span>已附加选中的代码片段 ({{ selectedCode.length }} 字符)</span>
          <button type="button" class="btn-remove-code" @click="emit('clear-selected-code')" title="移除选中代码">
            <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
          </button>
        </div>

        <!-- 文本输入区 -->
        <div class="composer-text-pane">
          <textarea
            ref="textareaRef"
            :value="modelValue"
            class="composer-textarea"
            :placeholder="computedPlaceholder"
            :disabled="loading"
            rows="1"
            @input="onInput"
            @keydown.enter.exact.prevent="onEnterSend"
            @keydown.escape="emit('escape')"
            @paste="onPaste"
          ></textarea>
        </div>

        <!-- 待发送图片附件列表 -->
        <div v-if="pendingImages?.length" class="composer-image-strip">
          <AgentImageAttachments
            :attachments="pendingImages"
            :removable="true"
            aria-label="待发送图片"
            @preview="img => emit('preview-image', img)"
            @remove="img => emit('remove-image', img)"
          />
        </div>

        <!-- 底部纯图标操作栏与模型切换器 -->
        <div class="composer-footer-bar">
          <!-- 左侧操作图标组 -->
          <div class="composer-tools-left">
            <input
              ref="fileInputRef"
              type="file"
              multiple
              accept="image/*"
              style="display: none;"
              @change="onFileInputChange"
            />
            <button
              type="button"
              class="icon-action-btn"
              title="附加图片 (Image)"
              :disabled="!supportsImages || loading"
              @click="fileInputRef?.click()"
            >
              <span class="icon">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>
              </span>
            </button>

            <button
              type="button"
              class="icon-action-btn"
              title="预设指令 (/)"
              :disabled="loading"
              @click="emit('trigger-commands')"
            >
              <span class="icon">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>
              </span>
            </button>

            <button
              type="button"
              class="icon-action-btn"
              title="引用文件 (@)"
              :disabled="loading"
              @click="emit('trigger-at-file')"
            >
              <span class="icon">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
              </span>
            </button>

            <button
              type="button"
              class="icon-action-btn"
              title="优化提示词"
              :disabled="loading"
              @click="emit('optimize-prompt')"
            >
              <span class="icon">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 1 1 7.072 0l-.548.547A3.374 3.374 0 0 0 14 18.469V19a2 2 0 1 1-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z"/></svg>
              </span>
            </button>
          </div>

          <!-- 右侧：生成指示器、上下文指示器、模型切换模态框与发送按钮 -->
          <div class="composer-tools-right">
            <ContextUsageIndicator
              v-if="contextUsageStatus"
              :status="contextUsageStatus"
              @open="emit('open-context-dialog')"
            />

            <!-- 模型切换模态框 -->
            <ModelSelectorPopover
              :current-model="currentModel"
              :thinking-level="thinkingLevel"
              :models="availableModels"
              @change-model="name => emit('change-model', name)"
              @change-thinking="lvl => emit('change-thinking', lvl)"
              @open-config="emit('open-model-config')"
            />

            <!-- 发送 / 停止 按钮 -->
            <button
              v-if="loading"
              type="button"
              class="btn-send-round stop"
              title="停止生成 (Esc)"
              @click="emit('stop')"
            >
              <span class="icon">
                <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><rect x="5" y="5" width="14" height="14" rx="2"/></svg>
              </span>
            </button>
            <button
              v-else
              type="button"
              class="btn-send-round"
              :disabled="!modelValue.trim() && !pendingImages?.length"
              title="发送 (Enter)"
              @click="onSendClick"
            >
              <span class="icon">
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>
              </span>
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import ModeSlider from './ModeSlider.vue'
import ModelSelectorPopover from './ModelSelectorPopover.vue'
import ContextUsageIndicator from '../ContextUsageIndicator.vue'
import AgentImageAttachments from '../AgentImageAttachments.vue'

const props = defineProps({
  modelValue: {
    type: String,
    default: '',
  },
  agentMode: {
    type: String,
    default: 'build',
  },
  currentModel: {
    type: String,
    default: 'Gemini 3.7 Flash',
  },
  thinkingLevel: {
    type: String,
    default: 'High',
  },
  availableModels: {
    type: Array,
    default: () => [],
  },
  loading: {
    type: Boolean,
    default: false,
  },
  supportsImages: {
    type: Boolean,
    default: true,
  },
  selectedCode: {
    type: String,
    default: '',
  },
  pendingImages: {
    type: Array,
    default: () => [],
  },
  contextUsageStatus: {
    type: Object,
    default: null,
  },
  activePath: {
    type: String,
    default: '',
  },
})

const emit = defineEmits([
  'update:modelValue',
  'update:agentMode',
  'mode-change',
  'send',
  'stop',
  'escape',
  'trigger-commands',
  'trigger-at-file',
  'optimize-prompt',
  'clear-selected-code',
  'preview-image',
  'remove-image',
  'image-files',
  'open-context-dialog',
  'change-model',
  'change-thinking',
  'open-model-config',
])

const textareaRef = ref(null)
const fileInputRef = ref(null)
const isDragOver = ref(false)

const computedPlaceholder = computed(() => {
  if (props.agentMode === 'plan') {
    return '规划模式：输入开发需求以生成系统重构与执行计划...'
  } else if (props.agentMode === 'explore') {
    return '探索模式：只读探索代码库、查询架构设计与知识检索...'
  }
  return props.activePath
    ? `针对 ${props.activePath.split('/').pop()} 提问或重构，输入指令...`
    : '输入开发需求，支持文件修改、Mermaid 图表生成与工具链自动化...'
})

function onInput(e) {
  emit('update:modelValue', e.target.value)
}

function onEnterSend() {
  if (!props.loading && (props.modelValue.trim() || props.pendingImages?.length)) {
    emit('send')
  }
}

function onSendClick() {
  if (!props.loading && (props.modelValue.trim() || props.pendingImages?.length)) {
    emit('send')
  }
}

function onPaste(e) {
  const items = e.clipboardData?.items
  if (!items) return
  const imageFiles = []
  for (let i = 0; i < items.length; i++) {
    if (items[i].type.indexOf('image') !== -1) {
      const file = items[i].getAsFile()
      if (file) imageFiles.push(file)
    }
  }
  if (imageFiles.length > 0) {
    emit('image-files', imageFiles)
  }
}

function onFileInputChange(e) {
  const files = Array.from(e.target.files || [])
  if (files.length > 0) {
    emit('image-files', files)
  }
  if (fileInputRef.value) fileInputRef.value.value = ''
}

function onDragEnter() {
  isDragOver.value = true
}

function onDragOver() {
  isDragOver.value = true
}

function onDragLeave() {
  isDragOver.value = false
}

function onDrop(e) {
  isDragOver.value = false
  const files = Array.from(e.dataTransfer?.files || []).filter(f => f.type.startsWith('image/'))
  if (files.length > 0) {
    emit('image-files', files)
  }
}

defineExpose({
  focus: () => textareaRef.value?.focus(),
})
</script>

<style scoped>
.composer-dock-container {
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  user-select: none;
}

.composer-dock-inner {
  width: 100%;
  display: flex;
  flex-direction: column;
  position: relative;
}

.composer-mode-bar {
  margin-bottom: 8px;
}

.composer-card {
  background: #ffffff;
  border: 1px solid #e4e4e7;
  border-radius: 10px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
  display: flex;
  flex-direction: column;
  position: relative;
  transition: border-color 0.16s ease, box-shadow 0.16s ease;
}

.composer-card:focus-within {
  border-color: #09090b;
  box-shadow: 0 4px 14px -2px rgba(0, 0, 0, 0.08);
}

.composer-card.is-image-dragover {
  border-color: #09090b;
  background: #fafafa;
}

.composer-code-context {
  padding: 6px 12px;
  background: #fafafa;
  border-bottom: 1px solid #f4f4f5;
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  color: #3f3f46;
  font-family: 'JetBrains Mono', monospace;
}

.btn-remove-code {
  margin-left: auto;
  border: none;
  background: transparent;
  color: #a1a1aa;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.btn-remove-code:hover {
  color: #09090b;
}

.composer-text-pane {
  padding: 8px 12px 4px 12px;
}

.composer-textarea {
  width: 100%;
  min-height: 44px;
  max-height: 200px;
  border: none;
  outline: none;
  background: transparent;
  padding: 0;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.5;
  color: #09090b;
  resize: none;
}

.composer-image-strip {
  padding: 4px 12px;
}

.composer-footer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 8px 6px 8px;
}

.composer-tools-left,
.composer-tools-right {
  display: flex;
  align-items: center;
  gap: 3px;
}

.icon-action-btn {
  width: 26px;
  height: 26px;
  border: 1px solid transparent;
  background: transparent;
  border-radius: 6px;
  color: #71717a;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.12s;
}

.icon-action-btn:hover {
  background: #f4f4f5;
  color: #09090b;
}

.icon-action-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.btn-send-round {
  width: 26px;
  height: 26px;
  border-radius: 9999px;
  border: 1px solid #09090b;
  background: #09090b;
  color: #ffffff;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.12s ease;
  margin-left: 4px;
}

.btn-send-round:hover {
  background: #27272a;
  transform: scale(1.04);
}

.btn-send-round.stop {
  background: #ef4444;
  border-color: #ef4444;
}

.btn-send-round:disabled {
  opacity: 0.35;
  cursor: not-allowed;
  transform: none;
}

.icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
</style>
