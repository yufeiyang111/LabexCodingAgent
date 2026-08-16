<template>
  <div class="center-ai-container">
    <!-- 顶部状态栏与还原至侧边栏按钮 -->
    <div class="center-ai-topbar">
      <div class="topbar-title-wrap">
        <span class="icon agent-symbol-icon">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>
        </span>
        <span class="topbar-title">LabexAgent (中心主视图)</span>
        <span class="topbar-session-badge" v-if="currentSessionName">{{ currentSessionName }}</span>
      </div>

      <div class="topbar-actions-right">
        <button
          type="button"
          class="btn-dockback"
          @click="emit('dock-back')"
          title="还原回右侧边栏"
        >
          <span class="icon">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M15 3v18"/></svg>
          </span>
          <span>还原至侧边栏</span>
        </button>
      </div>
    </div>

    <!-- 独立滚动消息内容区 -->
    <div class="center-ai-scroll-pane" ref="scrollPaneRef" @scroll="onScroll">
      <div class="center-ai-content-inner">
        <!-- 历史记录加载按钮 -->
        <button
          v-if="hasOlderMessages"
          class="btn-load-older"
          type="button"
          :disabled="loadingOlderMessages"
          @click="emit('load-older-history')"
        >
          {{ loadingOlderMessages ? '正在加载更早记录...' : '加载更早记录' }}
        </button>

        <!-- 空白欢迎态 -->
        <div v-if="messages.length === 0" class="center-empty-state">
          <div class="empty-icon-box">
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>
          </div>
          <h2 class="empty-greeting">有什么可以帮您的吗？</h2>
          <p class="empty-subtext">输入需求，LabexAgent 将自动执行代码检索、架构设计、文件重构与测试验证。</p>

          <!-- 快速指令 Pills -->
          <div class="center-quick-chips" v-if="quickChips?.length">
            <button
              v-for="chip in quickChips"
              :key="chip.label"
              class="quick-chip-btn"
              @click="emit('apply-chip', chip.prompt)"
            >
              <span v-html="chip.icon"></span>
              <span>{{ chip.label }}</span>
            </button>
          </div>
        </div>

        <!-- 消息列表 -->
        <div v-else class="center-message-list">
          <div
            v-for="(msg, index) in messages"
            :key="index"
            class="center-msg-item"
            :class="msg.role"
          >
            <!-- 用户消息 (Figure 3) -->
            <template v-if="msg.role === 'user'">
              <div class="user-msg-bubble-wrap">
                <div class="user-msg-bubble">
                  <!-- 用户发送的图片缩略图列表 (可点击预览大图) -->
                  <div v-if="msg.attachments?.length" class="user-msg-thumbnails">
                    <div
                      v-for="(att, aIdx) in msg.attachments"
                      :key="aIdx"
                      class="user-msg-thumb-item"
                      @click="emit('preview-image', att)"
                      title="点击查看原图"
                    >
                      <img :src="att.dataUrl || att.url || att.src" :alt="att.name || '图片附件'" />
                    </div>
                  </div>
                  <div class="user-msg-text">{{ msg.content }}</div>
                </div>
              </div>
            </template>

            <!-- Assistant 消息 -->
            <template v-else>
              <div class="assistant-msg-wrap">
                <div class="assistant-msg-inner">
                  <!-- 合并的时间序思考与工具调用卡片 -->
                  <template v-if="(showThinkingProcess && msg.thinkingBlocks?.length > 0) || (msg.toolCalls?.length > 0)">
                    <template v-for="item in getMergedItems(msg)" :key="item._order">
                      <!-- 思考过程 -->
                      <ThinkingProcessBlock
                        v-if="item.type === 'thinking'"
                        :content="item.data.content"
                        :rendered-content="renderThinkingMarkdown(item.data.content)"
                        :summary="item.data.summary"
                        :default-open="item.data._open"
                        @markdown-click="e => emit('markdown-click', e)"
                      />

                      <!-- 工具调用卡片 -->
                      <ToolCallCard
                        v-else-if="item.type === 'tool'"
                        :call="item.data"
                        @permission="args => emit('permission', args)"
                        @command-approval="args => emit('command-approval', args)"
                        @question="args => emit('question', args)"
                      />
                    </template>
                  </template>

                  <!-- 实时流式思考 -->
                  <ThinkingProcessBlock
                    v-if="showThinkingProcess && msg.thinking"
                    :content="msg._thinkingDisplay || msg.thinking"
                    :rendered-content="renderThinkingMarkdown(msg._thinkingDisplay || msg.thinking)"
                    :is-streaming="true"
                    @markdown-click="e => emit('markdown-click', e)"
                  />

                  <!-- 富文本 Markdown 输出 -->
                  <div class="msg-content-box">
                    <div
                      v-if="msg.isStreaming && !msg.content && !msg.thinking"
                      class="streaming-skeleton-box"
                    >
                      <div class="skeleton-line w-80"></div>
                      <div class="skeleton-line w-60"></div>
                    </div>
                    <div
                      v-else-if="msg.content"
                      class="msg-markdown-rendered markdown-rendered"
                      v-html="renderMessageMarkdown(msg)"
                      @click="e => emit('markdown-click', e)"
                    ></div>
                  </div>

                  <!-- 文件改动卡片 (Figure 1: 常驻展示，无变动时显示 No changes) -->
                  <FileChangesSummaryCard
                    :changes="msg.fileChanges || msg.changes || []"
                    :additions="msg.additions || 0"
                    :deletions="msg.deletions || 0"
                    @review-all="emit('review-changes', msg)"
                    @open-file-diff="file => emit('open-file-diff', file)"
                  />

                  <!-- 任务证据与计划 -->
                  <CompletionEvidenceCard
                    v-if="msg.completionEvidence || msg.completionBlockedEvidence"
                    :evidence="msg.completionEvidence || msg.completionBlockedEvidence"
                  />
                  <PlanDisplay
                    v-if="msg.plan || msg.planJson"
                    :plan="msg.plan"
                    :plan-json="msg.planJson"
                  />

                  <!-- Token 图表与耗时 -->
                  <TokenChart
                    v-if="index === messages.length - 1 && tokenUsage?.totalTokens > 0"
                    :prompt-tokens="tokenUsage.promptTokens"
                    :completion-tokens="tokenUsage.completionTokens"
                    :call-count="tokenUsage.callCount"
                  />
                  <AgentTimer
                    v-if="msg.timing"
                    :started-at="msg.timing.startedAt"
                    :active-elapsed-ms="msg.timing.activeElapsedMs"
                    :is-running="msg.timing.isRunning"
                  />

                  <!-- 消息工具栏 -->
                  <div class="msg-actions-bar">
                    <button class="msg-action-btn" title="复制内容" @click="emit('copy-message', msg.content)">
                      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                    </button>
                    <button class="msg-action-btn" title="插入到编辑器" v-if="activePath" @click="emit('insert-editor', msg.content)">
                      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
                    </button>
                  </div>
                </div>
              </div>
            </template>
          </div>
        </div>
      </div>
    </div>

    <!-- 浮动回顶/滚底按钮组 -->
    <div class="center-scroll-fab-group">
      <Transition name="fade-pop">
        <button
          v-if="showScrollTopBtn"
          class="center-scroll-fab-btn"
          type="button"
          @click="scrollToTopManual"
          title="回到顶部"
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="18 15 12 9 6 15"/></svg>
        </button>
      </Transition>
      <Transition name="fade-pop">
        <button
          v-if="showScrollBtn"
          class="center-scroll-fab-btn"
          type="button"
          @click="scrollToBottomManual"
          title="回到底部"
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="6 9 12 15 18 9"/></svg>
        </button>
      </Transition>
    </div>

    <!-- 浮动消息上下导航 -->
    <div v-if="messages.length > 2" class="center-msg-navigator">
      <button
        class="nav-btn"
        :class="{ disabled: currentMsgIdx <= 0 }"
        @click="navigateMessage(-1)"
        title="上一条消息"
      >
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><polyline points="18 15 12 9 6 15"/></svg>
      </button>
      <span class="nav-indicator">{{ currentMsgIdx + 1 }}/{{ messages.length }}</span>
      <button
        class="nav-btn"
        :class="{ disabled: currentMsgIdx >= messages.length - 1 }"
        @click="navigateMessage(1)"
        title="下一条消息"
      >
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><polyline points="6 9 12 15 18 9"/></svg>
      </button>
    </div>

    <!-- 固定在底部的 Composer 区域 -->
    <div class="center-composer-dock-pinned">
      <div class="center-composer-inner-wrapper">
        <ComposerDock
          :model-value="agentInput"
          :agent-mode="agentMode"
          :current-model="currentModel"
          :thinking-level="thinkingLevel"
          :available-models="availableModels"
          :loading="agentLoading"
          :supports-images="supportsImages"
          :selected-code="selectedCode"
          :pending-images="pendingImages"
          :context-usage-status="contextUsageStatus"
          :active-path="activePath"
          @update:model-value="val => emit('update:agentInput', val)"
          @update:agent-mode="val => emit('update:agentMode', val)"
          @mode-change="val => emit('mode-change', val)"
          @send="emit('send')"
          @stop="emit('stop')"
          @trigger-commands="emit('trigger-commands')"
          @trigger-at-file="emit('trigger-at-file')"
          @optimize-prompt="emit('optimize-prompt')"
          @clear-selected-code="emit('clear-selected-code')"
          @preview-image="img => emit('preview-image', img)"
          @remove-image="img => emit('remove-image', img)"
          @image-files="files => emit('image-files', files)"
          @open-context-dialog="emit('open-context-dialog')"
          @change-model="name => emit('change-model', name)"
          @change-thinking="lvl => emit('change-thinking', lvl)"
          @open-model-config="emit('open-model-config')"
        />
      </div>
    </div>

    <!-- 底部微状态指示条 -->
    <div class="center-status-strip">
      <div class="status-left">
        <span class="status-dot online"></span>
        <span>{{ currentModel }} {{ thinkingLevel ? `(${thinkingLevel})` : '' }}</span>
      </div>
      <div class="status-right" v-if="tokenUsage?.totalTokens">
        <span>{{ tokenUsage.totalTokens >= 1000 ? (tokenUsage.totalTokens / 1000).toFixed(1) + 'K' : tokenUsage.totalTokens }} Tokens</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick, watch, defineAsyncComponent } from 'vue'
import ComposerDock from '../composer/ComposerDock.vue'
import ThinkingProcessBlock from './ThinkingProcessBlock.vue'
import ToolCallCard from '../ToolCallCard.vue'

const CompletionEvidenceCard = defineAsyncComponent(() => import('../CompletionEvidenceCard.vue'))
const PlanDisplay = defineAsyncComponent(() => import('../PlanDisplay.vue'))
const TokenChart = defineAsyncComponent(() => import('../TokenChart.vue'))
const AgentTimer = defineAsyncComponent(() => import('../AgentTimer.vue'))
const FileChangesSummaryCard = defineAsyncComponent(() => import('./FileChangesSummaryCard.vue'))

const props = defineProps({
  messages: {
    type: Array,
    default: () => [],
  },
  agentInput: {
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
  agentLoading: {
    type: Boolean,
    default: false,
  },
  currentSessionName: {
    type: String,
    default: '',
  },
  hasOlderMessages: {
    type: Boolean,
    default: false,
  },
  loadingOlderMessages: {
    type: Boolean,
    default: false,
  },
  showThinkingProcess: {
    type: Boolean,
    default: true,
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
  tokenUsage: {
    type: Object,
    default: () => ({ totalTokens: 0, promptTokens: 0, completionTokens: 0, callCount: 0 }),
  },
  quickChips: {
    type: Array,
    default: () => [],
  },
  activePath: {
    type: String,
    default: '',
  },
  getMergedItems: {
    type: Function,
    default: () => [],
  },
  renderThinkingMarkdown: {
    type: Function,
    default: t => t,
  },
  renderMessageMarkdown: {
    type: Function,
    default: m => m.content || '',
  },
})

const emit = defineEmits([
  'dock-back',
  'update:agentInput',
  'update:agentMode',
  'mode-change',
  'send',
  'stop',
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
  'load-older-history',
  'apply-chip',
  'markdown-click',
  'permission',
  'command-approval',
  'question',
  'copy-message',
  'insert-editor',
  'review-changes',
  'open-file-diff',
])

const scrollPaneRef = ref(null)
const userScrolled = ref(false)
const showScrollBtn = ref(false)
const showScrollTopBtn = ref(false)
const currentMsgIdx = ref(0)

function onScroll(e) {
  const el = e.target
  const distanceFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight
  showScrollBtn.value = distanceFromBottom > 150
  showScrollTopBtn.value = el.scrollTop > 150
  userScrolled.value = distanceFromBottom > 80

  // 精准计算当前视口中央所在的消息索引
  const items = el.querySelectorAll('.center-msg-item')
  if (items && items.length > 0) {
    const containerTop = el.getBoundingClientRect().top + 60
    let found = 0
    for (let i = 0; i < items.length; i++) {
      const r = items[i].getBoundingClientRect()
      if (r.bottom >= containerTop) {
        found = i
        break
      }
      found = i
    }
    currentMsgIdx.value = found
  }
}

function scrollToBottomManual() {
  userScrolled.value = false
  showScrollBtn.value = false
  if (scrollPaneRef.value) {
    scrollPaneRef.value.scrollTo({
      top: scrollPaneRef.value.scrollHeight,
      behavior: 'smooth'
    })
  }
}

function scrollToTopManual() {
  userScrolled.value = true
  if (scrollPaneRef.value) {
    scrollPaneRef.value.scrollTo({
      top: 0,
      behavior: 'smooth'
    })
  }
}

function scrollDown(force = false) {
  if (userScrolled.value && !force) return
  nextTick(() => {
    if (scrollPaneRef.value) {
      scrollPaneRef.value.scrollTop = scrollPaneRef.value.scrollHeight
    }
  })
}

function navigateMessage(direction) {
  const items = scrollPaneRef.value?.querySelectorAll('.center-msg-item')
  if (!items || items.length === 0) return
  const newIndex = Math.max(0, Math.min(currentMsgIdx.value + direction, props.messages.length - 1))
  currentMsgIdx.value = newIndex
  if (items[newIndex]) {
    items[newIndex].scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
}

watch(
  () => props.messages,
  () => {
    scrollDown(false)
  },
  { deep: true }
)

defineExpose({
  scrollToBottom: () => scrollDown(true),
})
</script>

<style scoped>
.center-ai-container {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: #ffffff;
  overflow: hidden;
  position: relative;
}

.center-ai-topbar {
  height: 42px;
  padding: 0 16px;
  background: #ffffff;
  border-bottom: 1px solid #e4e4e7;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-shrink: 0;
  user-select: none;
}

.topbar-title-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
}

.agent-symbol-icon {
  color: #09090b;
}

.topbar-title {
  font-weight: 600;
  font-size: 13px;
  color: #09090b;
}

.topbar-session-badge {
  font-size: 11.5px;
  color: #71717a;
  padding: 1px 6px;
  background: #f4f4f5;
  border-radius: 4px;
}

.btn-dockback {
  height: 26px;
  padding: 0 9px;
  background: #fafafa;
  border: 1px solid #e4e4e7;
  border-radius: 6px;
  font-size: 11.5px;
  font-weight: 500;
  color: #3f3f46;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
  transition: all 0.12s;
}

.btn-dockback:hover {
  background: #f4f4f5;
  border-color: #d4d4d8;
  color: #09090b;
}

/* 独立滚动区 */
.center-ai-scroll-pane {
  flex: 1;
  overflow-y: auto;
  padding: 18px 24px 200px 24px;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.center-ai-content-inner {
  width: 100%;
  max-width: 1040px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.btn-load-older {
  align-self: center;
  padding: 4px 12px;
  background: #fafafa;
  border: 1px solid #e4e4e7;
  border-radius: 6px;
  font-size: 11.5px;
  color: #71717a;
  cursor: pointer;
  transition: all 0.12s;
}

.btn-load-older:hover {
  background: #f4f4f5;
  color: #09090b;
}

.center-empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48px 0;
  text-align: center;
}

.empty-icon-box {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  background: #f4f4f5;
  border: 1px solid #e4e4e7;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #09090b;
  margin-bottom: 12px;
}

.empty-greeting {
  font-size: 18px;
  font-weight: 600;
  color: #09090b;
  margin-bottom: 6px;
}

.empty-subtext {
  font-size: 13px;
  color: #71717a;
  max-width: 480px;
  line-height: 1.5;
  margin-bottom: 20px;
}

.center-quick-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
}

.quick-chip-btn {
  padding: 6px 12px;
  background: #fafafa;
  border: 1px solid #e4e4e7;
  border-radius: 20px;
  font-size: 12px;
  color: #3f3f46;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  transition: all 0.15s ease;
}

.quick-chip-btn:hover {
  background: #f4f4f5;
  border-color: #d4d4d8;
  color: #09090b;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
}

.center-message-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.center-msg-item.user {
  display: flex;
  justify-content: flex-end;
}

.user-msg-bubble-wrap {
  max-width: 80%;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
}

.user-msg-bubble {
  background: #fafafa;
  border: 1px solid #e4e4e7;
  border-radius: 10px;
  padding: 9px 13px;
  font-size: 13px;
  line-height: 1.55;
  color: #09090b;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.02);
}

.user-msg-thumbnails {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
}

.user-msg-thumb-item {
  width: 38px;
  height: 38px;
  border-radius: 6px;
  overflow: hidden;
  border: 1px solid #e4e4e7;
  background: #ffffff;
  cursor: pointer;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}

.user-msg-thumb-item img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.user-msg-thumb-item:hover {
  transform: scale(1.06);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
}

.assistant-msg-wrap {
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 100%;
}

.assistant-msg-inner {
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 100%;
}

.msg-content-box {
  font-size: 13.5px;
  line-height: 1.65;
  color: #09090b;
}

.msg-actions-bar {
  display: flex;
  align-items: center;
  gap: 4px;
  padding-top: 4px;
}

.msg-action-btn {
  width: 24px;
  height: 24px;
  border-radius: 4px;
  border: 1px solid transparent;
  background: transparent;
  color: #a1a1aa;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.1s;
}

.msg-action-btn:hover {
  background: #f4f4f5;
  color: #09090b;
}

/* 浮动回顶/滚底与导航按钮 */
.center-scroll-fab-group {
  position: absolute;
  bottom: 125px;
  right: 32px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  z-index: 55;
}

.center-scroll-fab-btn {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: #ffffff;
  border: 1px solid #e4e4e7;
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.08);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #18181b;
  cursor: pointer;
  transition: all 0.15s ease;
}

.center-scroll-fab-btn:hover {
  background: #f4f4f5;
  transform: translateY(-2px);
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.12);
}

.center-msg-navigator {
  position: absolute;
  bottom: 125px;
  left: 32px;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  border-radius: 20px;
  background: #ffffff;
  border: 1px solid #e4e4e7;
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.08);
  z-index: 55;
  font-size: 11px;
  font-family: 'JetBrains Mono', monospace;
}

.center-msg-navigator .nav-btn {
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  border: none;
  background: transparent;
  cursor: pointer;
  color: #71717a;
  transition: all 0.12s;
}

.center-msg-navigator .nav-btn:hover:not(.disabled) {
  background: #f4f4f5;
  color: #18181b;
}

.center-msg-navigator .nav-btn.disabled {
  opacity: 0.3;
  cursor: not-allowed;
}

.nav-indicator {
  color: #71717a;
  padding: 0 4px;
}

/* 悬浮在底部的 Composer (Figure 4) */
.center-composer-dock-pinned {
  position: absolute;
  bottom: 30px;
  left: 0;
  right: 0;
  width: 100%;
  max-width: 960px;
  margin: 0 auto;
  padding: 0 16px;
  background: transparent;
  z-index: 50;
  pointer-events: none;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.center-composer-inner-wrapper {
  width: 100%;
  pointer-events: auto;
}

/* 底部状态条 (Figure 4) */
.center-status-strip {
  height: 24px;
  padding: 0 16px;
  background: #fafafa;
  border-top: 1px solid #f4f4f5;
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11px;
  color: #71717a;
  flex-shrink: 0;
  user-select: none;
}

.status-left,
.status-right {
  display: flex;
  align-items: center;
  gap: 6px;
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.status-dot.online {
  background: #10b981;
}

.icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
</style>
