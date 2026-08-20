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
        <!-- 预设指令选择列表框 (从输入框向上延伸的浮动菜单) -->
        <Transition name="composer-slash-pop">
          <div
            v-if="showSlashMenu"
            ref="slashMenuRef"
            class="composer-slash-menu"
            @click.stop
          >
            <div class="slash-menu-header">
              <span class="slash-menu-title">预设指令</span>
              <span class="slash-menu-tip">↑↓ 导航 · Enter 选择 · Esc 关闭</span>
            </div>
            <div class="slash-menu-list">
              <div
                v-for="(cmd, cIdx) in filteredCommands"
                :key="cmd.name"
                class="slash-menu-item"
                :class="{ active: cIdx === activeCommandIdx }"
                @click="selectCommand(cmd)"
                @mouseenter="activeCommandIdx = cIdx"
              >
                <div class="slash-item-left">
                  <span class="slash-item-cmd">/{{ cmd.name }}</span>
                </div>
                <span class="slash-item-desc">{{ cmd.description }}</span>
              </div>
              <div v-if="filteredCommands.length === 0" class="slash-menu-empty">未匹配到相关指令</div>
            </div>
          </div>
        </Transition>

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
            @keydown="onKeydown"
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
              :class="{ active: showSlashMenu }"
              title="预设指令 (/)"
              :disabled="loading"
              @click.stop="toggleSlashMenu"
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

            <!-- 发送/终止按钮 -->
            <button
              v-if="loading"
              type="button"
              class="btn-send is-loading"
              title="停止生成"
              @click="emit('stop')"
            >
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>
            </button>
            <button
              v-else
              type="button"
              class="btn-send"
              :disabled="!modelValue.trim() && !pendingImages?.length"
              title="发送指令 (Enter)"
              @click="onSendClick"
            >
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="12" y1="19" x2="12" y2="5"/><polyline points="5 12 12 5 19 12"/></svg>
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted, onUnmounted } from 'vue'
import ModeSlider from './ModeSlider.vue'
import ModelSelectorPopover from './ModelSelectorPopover.vue'
import AgentImageAttachments from '@/components/cloud/AgentImageAttachments.vue'
import ContextUsageIndicator from '@/components/cloud/ContextUsageIndicator.vue'

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
    default: '',
  },
  thinkingLevel: {
    type: String,
    default: 'Medium',
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
  commands: {
    type: Array,
    default: () => [],
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

const showSlashMenu = ref(false)
const activeCommandIdx = ref(0)
const slashQuery = ref('')

const defaultPresetCommands = [
  { name: 'init', description: '引导式创建或更新 AGENTS.md / LabexAgent.md 项目规范' },
  { name: 'review', description: '审查本次工作区生成的所有文件改动差异' },
  { name: 'goal', description: '设定长时自主目标，不达成目标不停止执行' },
  { name: 'plan', description: '生成详细的技术实施计划与任务分解' },
  { name: 'test', description: '执行项目测试套件并报告结果与修复建议' },
  { name: 'lint', description: '运行代码检查工具排查语法与风格问题' },
  { name: 'format', description: '执行代码自动格式化' },
  { name: 'fix', description: '分析并修复指定的代码缺陷或错误' },
  { name: 'explain', description: '解释指定代码或模块的实现原理' },
  { name: 'refactor', description: '在保持功能不变的前提下重构代码架构' },
  { name: 'browser', description: '调用浏览器执行自动化网络搜索与交互' },
  { name: 'diff', description: '打开差异面板查看当前所有未提交代码变更' },
  { name: 'compact', description: '立即压缩当前上下文历史以释放 Token 窗口' },
  { name: 'clear', description: '清空当前会话并开启全新对话' },
  { name: 'models', description: '打开模型配置面板切换当前大模型' },
  { name: 'mcps', description: '管理已接入的 MCP 服务器与扩展能力' },
  { name: 'skills', description: '打开技能选择器使用自定义项目技能' },
]

const effectiveCommands = computed(() => {
  if (Array.isArray(props.commands) && props.commands.length > 0) {
    // 优先使用后端注册表动态提供的命令，同时保留前端特有扩展
    const names = new Set(props.commands.map(c => c.name))
    const merged = [...props.commands]
    for (const preset of defaultPresetCommands) {
      if (!names.has(preset.name)) {
        merged.push(preset)
      }
    }
    return merged
  }
  return defaultPresetCommands
})

const filteredCommands = computed(() => {
  const list = effectiveCommands.value
  if (!slashQuery.value) return list
  const q = slashQuery.value.toLowerCase().replace(/^\//, '')
  return list.filter(c => c.name.toLowerCase().includes(q) || (c.description && c.description.toLowerCase().includes(q)))
})

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
  const val = e.target.value
  emit('update:modelValue', val)
  // 仅在开头为 / 且尚未输入空格参数（纯命令前缀阶段）时显示自动补全菜单
  const slashMatch = val.match(/^\/([a-zA-Z0-9_-]*)$/)
  if (slashMatch) {
    slashQuery.value = slashMatch[1]
    showSlashMenu.value = true
    activeCommandIdx.value = 0
  } else {
    showSlashMenu.value = false
  }
}

function toggleSlashMenu() {
  showSlashMenu.value = !showSlashMenu.value
  if (showSlashMenu.value) {
    slashQuery.value = ''
    activeCommandIdx.value = 0
    nextTick(() => textareaRef.value?.focus())
  }
}

function selectCommand(cmd) {
  emit('update:modelValue', '/' + cmd.name + ' ')
  showSlashMenu.value = false
  slashQuery.value = ''
  nextTick(() => textareaRef.value?.focus())
}

function onKeydown(e) {
  if (showSlashMenu.value) {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      if (filteredCommands.value.length > 0) {
        activeCommandIdx.value = (activeCommandIdx.value + 1) % filteredCommands.value.length
      }
      return
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault()
      if (filteredCommands.value.length > 0) {
        activeCommandIdx.value = (activeCommandIdx.value - 1 + filteredCommands.value.length) % filteredCommands.value.length
      }
      return
    }
    if (e.key === 'Tab') {
      // Tab 键补全指令
      if (filteredCommands.value[activeCommandIdx.value]) {
        e.preventDefault()
        selectCommand(filteredCommands.value[activeCommandIdx.value])
        return
      }
    }
    if (e.key === 'Escape') {
      e.preventDefault()
      showSlashMenu.value = false
      return
    }
  }

  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    showSlashMenu.value = false
    onEnterSend()
  } else if (e.key === 'Escape') {
    emit('escape')
  }
}

function onEnterSend() {
  if (!props.loading && (props.modelValue.trim() || props.pendingImages?.length)) {
    emit('send')
  }
}

function onSendClick() {
  showSlashMenu.value = false
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

function onClickOutside(e) {
  if (showSlashMenu.value && !e.target.closest('.composer-card')) {
    showSlashMenu.value = false
  }
}

onMounted(() => {
  window.addEventListener('click', onClickOutside)
})

onUnmounted(() => {
  window.removeEventListener('click', onClickOutside)
})

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
  overflow: visible;
}

.composer-dock-inner {
  width: 100%;
  display: flex;
  flex-direction: column;
  position: relative;
  overflow: visible;
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
  transition: border-color 0.16s ease, box-shadow 0.16s ease;
  position: relative;
  overflow: visible;
}

.composer-card:focus-within {
  border-color: #a1a1aa;
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.06);
}

.composer-card.is-image-dragover {
  border-color: #09090b;
  background: #fafafa;
}

/* 预设指令选择列表框 (从输入框向上延展) */
.composer-slash-menu {
  position: absolute;
  bottom: calc(100% + 8px);
  left: 0;
  width: min(340px, 100%);
  max-height: 280px;
  background: #ffffff;
  border: 1px solid #e4e4e7;
  border-radius: 10px;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.12);
  z-index: 100000;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.slash-menu-header {
  padding: 8px 12px;
  background: #fafafa;
  border-bottom: 1px solid #f4f4f5;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.slash-menu-title {
  font-size: 11.5px;
  font-weight: 600;
  color: #09090b;
}

.slash-menu-tip {
  font-size: 10.5px;
  color: #a1a1aa;
}

.slash-menu-list {
  padding: 4px;
  overflow-y: auto;
  max-height: 220px;
}

.slash-menu-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.1s ease;
}

.slash-menu-item.active,
.slash-menu-item:hover {
  background: #f4f4f5;
}

.slash-item-cmd {
  font-family: 'JetBrains Mono', monospace;
  font-weight: 700;
  font-size: 12px;
  color: #09090b;
}

.slash-item-desc {
  font-size: 11.5px;
  color: #71717a;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.slash-menu-empty {
  padding: 12px;
  text-align: center;
  font-size: 11.5px;
  color: #a1a1aa;
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

.icon-action-btn:hover,
.icon-action-btn.active {
  background: #f4f4f5;
  color: #09090b;
}

.icon-action-btn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

.btn-send {
  width: 26px;
  height: 26px;
  border: none;
  background: #18181b;
  color: #ffffff;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.12s;
  margin-left: 2px;
}

.btn-send:hover:not(:disabled) {
  background: #27272a;
}

.btn-send:disabled {
  background: #f4f4f5;
  color: #d4d4d8;
  cursor: not-allowed;
}

.btn-send.is-loading {
  background: #fee2e2;
  color: #dc2626;
}

.btn-send.is-loading:hover {
  background: #fecaca;
}

/* Transitions */
.composer-slash-pop-enter-active,
.composer-slash-pop-leave-active {
  transition: opacity 0.14s ease, transform 0.14s cubic-bezier(0.16, 1, 0.3, 1);
}

.composer-slash-pop-enter-from,
.composer-slash-pop-leave-to {
  opacity: 0;
  transform: translateY(6px);
}
</style>
