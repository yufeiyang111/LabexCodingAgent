<template>
  <div class="center-ai-container" :class="{ 'is-dark': isDark }">
    <!-- 顶部状态栏与导航 Tab 栏 (复刻侧边栏导航: 对话, 用量, 审查, 扩展, 终端) -->
    <div class="center-ai-topbar">
      <div class="topbar-title-wrap">
        <span class="icon agent-symbol-icon">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>
        </span>
        <span class="topbar-title">{{ title }}</span>
        <span class="topbar-session-badge" v-if="currentSessionName">{{ currentSessionName }}</span>
      </div>

      <!-- 居中导航 Tabs -->
      <div class="center-ai-tabs">
        <button
          v-for="tab in centerTabs"
          :key="tab.key"
          type="button"
          class="center-ai-tab-btn"
          :class="{ active: tab.key === 'terminal' ? terminalVisible : currentTab === tab.key }"
          @click="handleTabClick(tab.key)"
        >
          <span class="tab-icon" v-html="tab.icon"></span>
          <span>{{ tab.label }}</span>
        </button>
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

    <!-- ==================== TAB 1: 对话 (CHAT) ==================== -->
    <div v-show="currentTab === 'chat'" class="center-chat-tab-wrapper">
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
              :key="msg.taskId || msg.id || msg.timestamp || ('msg-' + index)"
              class="center-msg-item"
              :class="msg.role"
            >
              <!-- 用户消息 (Figure 3) -->
              <template v-if="msg.role === 'user'">
                <div class="user-msg-bubble-wrap">
                  <div class="user-msg-bubble">
                    <!-- 用户发送的图片缩略图列表 (可点击预览大图；复用统一附件组件的 previewUrl 链路) -->
                    <AgentImageAttachments
                      v-if="msg.attachments?.length"
                      :attachments="msg.attachments"
                      variant="message"
                      :show-names="false"
                      aria-label="已发送图片"
                      @preview="att => emit('preview-image', att)"
                    />
                    <div class="user-msg-text">{{ msg.content }}</div>
                    <div class="user-msg-footer">
                      <span v-if="msg.timestamp" class="msg-time">{{ formatTime(msg.timestamp) }}</span>
                      <button class="msg-copy-btn" title="复制内容" @click.stop="emit('copy-message', msg.content)">
                        <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                        <span>复制</span>
                      </button>
                    </div>
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
                          @permission="d => emit('permission', d)"
                          @command-approval="d => emit('command-approval', d)"
                          @question="d => emit('question', d)"
                          @open-file="p => emit('open-file', p)"
                          @open-preview="u => emit('open-preview', u)"
                          @open-subagent="p => emit('open-subagent', p)"
                          @markdown-click="e => emit('markdown-click', e)"
                        />
                      </template>
                    </template>

                    <!-- 实时流式思考过程 -->
                    <ThinkingProcessBlock
                      v-if="showThinkingProcess && msg.thinking"
                      :content="msg._thinkingDisplay || msg.thinking"
                      :rendered-content="renderThinkingMarkdown(msg._thinkingDisplay || msg.thinking)"
                      :is-streaming="true"
                      @markdown-click="e => emit('markdown-click', e)"
                    />

                    <!-- 正文 Markdown 内容 -->
                    <div class="msg-content-box" v-if="msg.content">
                      <div
                        class="markdown-rendered"
                        v-html="renderMessageMarkdown(msg)"
                        @click="e => emit('markdown-click', e)"
                      ></div>
                    </div>

                    <!-- 流式骨架加载动画 -->
                    <div v-else-if="msg.isStreaming && !msg.thinking" class="center-streaming-indicator">
                      <span class="streaming-dot"></span>
                      <span class="streaming-dot"></span>
                      <span class="streaming-dot"></span>
                    </div>

                    <!-- 文件变更组件（按当前助理轮次/消息独立展示该次对话产生的变更） -->
                    <FileChangesSummaryCard
                      v-if="msg.role === 'assistant' && getMessageChanges(msg).length > 0"
                      :changes="getMessageChanges(msg)"
                      :additions="getMessageAdditions(msg)"
                      :deletions="getMessageDeletions(msg)"
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

                    <!-- 消息工具栏与时间 (无边框/无中文，靠近悬浮时显示) -->
                    <div class="msg-actions-bar">
                      <span v-if="msg.timestamp" class="msg-time">{{ formatTime(msg.timestamp) }}</span>
                      <button class="msg-action-btn" title="复制内容" @click.stop="emit('copy-message', msg.content)">
                        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                      </button>
                      <button class="msg-action-btn" title="插入到编辑器" v-if="activePath" @click.stop="emit('insert-editor', msg.content)">
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

      <!-- 浮动回顶/滚底与右上角导航按钮组 -->
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

      <!-- 消息上下导航 (右上角胶囊) -->
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
            :commands="commandList"
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
    </div>

    <!-- ==================== TAB 2: 审查 (REVIEW - ChangesPanel) ==================== -->
    <div v-if="currentTab === 'review'" class="center-subtab-pane">
      <ChangesPanel
        :changes="sessionChanges || []"
        :project-id="projectId"
        :refresh-key="changesRefreshKey"
        :is-dark="isDark"
        @open-diff="f => emit('open-file-diff', f)"
        @revert="c => emit('revert-change', c)"
        @undo="c => emit('undo-change', c)"
      />
    </div>

    <!-- ==================== TAB 3: 用量 (USAGE) ==================== -->
    <div v-if="currentTab === 'usage'" class="center-subtab-pane">
      <UsagePanel
        :token-usage="tokenUsage"
        :all-token-stats="allTokenStats"
        :session-history="sessionHistory"
        :is-dark="isDark"
      />
    </div>

    <!-- 底部微状态指示条 -->
    <div class="center-status-strip">
      <div class="status-left">
        <span class="status-dot" :class="currentModel && currentModel !== '未配置模型' ? 'online' : 'warning'"></span>
        <span>{{ currentModel || '未配置模型' }} {{ (thinkingLevel && currentModel && currentModel !== '未配置模型') ? `(${thinkingLevel})` : '' }}</span>
      </div>
      <div class="status-right" v-if="tokenUsage?.totalTokens">
        <span>{{ tokenUsage.totalTokens >= 1000 ? (tokenUsage.totalTokens / 1000).toFixed(1) + 'K' : tokenUsage.totalTokens }} Tokens</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, watch, onMounted, defineAsyncComponent } from 'vue'
import ComposerDock from '../composer/ComposerDock.vue'
import ThinkingProcessBlock from './ThinkingProcessBlock.vue'
import ToolCallCard from '../ToolCallCard.vue'
import AgentImageAttachments from '@/components/cloud/AgentImageAttachments.vue'
import { renderMermaidBlocks } from '@/utils/mermaidRenderer'
import { normalizeWorkspacePath } from '@/utils/pathUtils'
import { resolveEffectiveChanges, resolveMessageChanges, resolveMessageStats } from '@/composables/useEffectiveChanges'

function getMessageChanges(msg) {
  return resolveMessageChanges(msg)
}

function getMessageAdditions(msg) {
  return resolveMessageStats(msg).additions
}

function getMessageDeletions(msg) {
  return resolveMessageStats(msg).deletions
}

const CompletionEvidenceCard = defineAsyncComponent(() => import('../CompletionEvidenceCard.vue'))
const PlanDisplay = defineAsyncComponent(() => import('../PlanDisplay.vue'))
const TokenChart = defineAsyncComponent(() => import('../TokenChart.vue'))
const AgentTimer = defineAsyncComponent(() => import('../AgentTimer.vue'))
const FileChangesSummaryCard = defineAsyncComponent(() => import('./FileChangesSummaryCard.vue'))
const ChangesPanel = defineAsyncComponent(() => import('../ChangesPanel.vue'))
const UsagePanel = defineAsyncComponent(() => import('../UsagePanel.vue'))

const props = defineProps({
  title: {
    type: String,
    default: 'LabexAgent',
  },
  activeTab: {
    type: String,
    default: 'chat',
  },
  projectId: {
    type: [Number, String],
    default: null,
  },
  changesRefreshKey: {
    type: [Number, String],
    default: 0,
  },
  sessionChanges: {
    type: Array,
    default: () => [],
  },
  sessionHistory: {
    type: Array,
    default: () => [],
  },
  allTokenStats: {
    type: Object,
    default: null,
  },
  isDark: {
    type: Boolean,
    default: false,
  },
  terminalVisible: {
    type: Boolean,
    default: false,
  },
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
    default: '',
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
  commandList: {
    type: Array,
    default: () => [],
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
    default: m => m?.content || '',
  },
})

const emit = defineEmits([
  'update:activeTab',
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
  'revert-change',
  'undo-change',
  'toggle-terminal',
  'open-file',
  'open-preview',
  'open-subagent',
])

const currentTab = computed({
  get: () => props.activeTab || 'chat',
  set: val => emit('update:activeTab', val),
})

const centerTabs = [
  { key: 'chat', label: '对话', icon: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>' },
  { key: 'usage', label: '用量', icon: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="2" x2="12" y2="22"/><path d="M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/></svg>' },
  { key: 'review', label: '审查', icon: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><line x1="8" y1="12" x2="16" y2="12"/><line x1="12" y1="8" x2="12" y2="16"/></svg>' },
  { key: 'terminal', label: '终端', icon: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>' },
]

function handleTabClick(key) {
  if (key === 'terminal') {
    emit('toggle-terminal')
  } else {
    currentTab.value = key
  }
}

function formatTime(timestamp) {
  if (!timestamp) return ''
  try {
    const d = new Date(timestamp)
    if (isNaN(d.getTime())) return String(timestamp).substring(0, 16).replace('T', ' ')
    const hours = String(d.getHours()).padStart(2, '0')
    const minutes = String(d.getMinutes()).padStart(2, '0')
    return `${hours}:${minutes}`
  } catch {
    return ''
  }
}

const effectiveChanges = computed(() => resolveEffectiveChanges(props.sessionChanges, props.messages))

const scrollPaneRef = ref(null)
const userScrolled = ref(false)
const showScrollBtn = ref(false)
const showScrollTopBtn = ref(false)
const currentMsgIdx = ref(0)

let centerScrollTimeout = null
function onScroll(e) {
  const el = e.target
  if (!el) return
  const distanceFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight
  showScrollBtn.value = distanceFromBottom > 150
  showScrollTopBtn.value = el.scrollTop > 150
  userScrolled.value = distanceFromBottom > 80

  if (centerScrollTimeout) clearTimeout(centerScrollTimeout)
  centerScrollTimeout = setTimeout(() => {
    if (!scrollPaneRef.value) return
    const items = scrollPaneRef.value.querySelectorAll('.center-msg-item')
    if (items && items.length > 0) {
      const containerTop = scrollPaneRef.value.getBoundingClientRect().top + 60
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
  }, 120)
}

function scrollToBottomManual() {
  userScrolled.value = false
  showScrollBtn.value = false
  if (scrollPaneRef.value) {
    scrollPaneRef.value.scrollTo({
      top: scrollPaneRef.value.scrollHeight,
      behavior: 'smooth',
    })
  }
}

function scrollToTopManual() {
  userScrolled.value = true
  if (scrollPaneRef.value) {
    scrollPaneRef.value.scrollTo({
      top: 0,
      behavior: 'smooth',
    })
  }
}

let centerScrollRaf = null
function scrollDown(force = false) {
  if (userScrolled.value && !force) return
  if (centerScrollRaf) return
  centerScrollRaf = requestAnimationFrame(() => {
    centerScrollRaf = null
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

let mermaidTimer = null
function scheduleMermaidRender() {
  if (mermaidTimer) clearTimeout(mermaidTimer)
  mermaidTimer = setTimeout(() => {
    renderMermaidBlocks(scrollPaneRef.value || document)
  }, 160)
}

watch(
  () => [
    props.messages?.length || 0,
    props.messages?.[props.messages.length - 1]?.content?.length || 0,
    props.messages?.[props.messages.length - 1]?._thinkingDisplay?.length || 0
  ],
  () => {
    scrollDown(false)
    scheduleMermaidRender()
  },
  { flush: 'post' }
)

watch(
  () => currentTab.value,
  val => {
    if (val === 'chat') {
      scheduleMermaidRender()
    }
  }
)

onMounted(() => {
  scheduleMermaidRender()
})

defineExpose({
  scrollToBottom: () => scrollDown(true),
  scheduleMermaidRender,
})
</script>

<style scoped>
.center-ai-container {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: var(--ai-bg-secondary, #ffffff);
  color: var(--ai-text, #09090b);
  position: relative;
  overflow: hidden;
  height: 100%;
  width: 100%;
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  -webkit-font-smoothing: antialiased;
}

.center-chat-tab-wrapper {
  flex: 1;
  display: flex;
  flex-direction: column;
  position: relative;
  overflow: hidden;
  height: calc(100% - 40px);
}

.center-subtab-pane {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  background: var(--ai-bg-secondary, #ffffff);
  height: calc(100% - 64px);
}

/* 顶部状态栏与 Tab */
.center-ai-topbar {
  height: 40px;
  min-height: 40px;
  padding: 0 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: var(--ai-bg-secondary, #ffffff);
  border-bottom: 1px solid var(--ai-border-strong, #e4e4e7);
  user-select: none;
  z-index: 10;
}

.topbar-title-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
}

.agent-symbol-icon {
  color: var(--ai-purple, #6366f1);
}

.topbar-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ai-text, #09090b);
}

.topbar-session-badge {
  font-size: 11px;
  font-weight: 500;
  padding: 1.5px 7px;
  border-radius: 4px;
  background: var(--ai-bg-tertiary, #f4f4f5);
  color: var(--ai-text-secondary, #52525b);
  border: 1px solid var(--ai-border, transparent);
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 导航 Tabs */
.center-ai-tabs {
  display: flex;
  align-items: center;
  gap: 4px;
}

.center-ai-tab-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 10px;
  border-radius: 6px;
  border: 1px solid transparent;
  background: transparent;
  color: var(--ai-text-muted, #71717a);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.12s ease;
}

.center-ai-tab-btn:hover {
  background: var(--ai-bg-tertiary, #f4f4f5);
  color: var(--ai-text, #09090b);
}

.center-ai-tab-btn.active {
  background: var(--ai-bg-tertiary, #f4f4f5);
  border-color: var(--ai-border-strong, #e4e4e7);
  color: var(--ai-text, #09090b);
  font-weight: 600;
}

.topbar-actions-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.btn-dockback {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 10px;
  border-radius: 6px;
  border: 1px solid var(--ai-border-strong, #e4e4e7);
  background: var(--ai-bg, #ffffff);
  color: var(--ai-text-secondary, #3f3f46);
  font-size: 11.5px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.12s ease;
}

.btn-dockback:hover {
  background: var(--ai-bg-tertiary, #09090b);
  color: var(--ai-text, #ffffff);
  border-color: var(--ai-border-focus, #09090b);
}

/* 滚动区与内容 */
.center-ai-scroll-pane {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 16px 24px 140px 24px;
}

.center-ai-content-inner {
  max-width: 960px;
  margin: 0 auto;
  width: 100%;
}

.btn-load-older {
  display: block;
  margin: 0 auto 16px;
  padding: 4px 12px;
  border-radius: 999px;
  border: 1px solid var(--ai-border-strong, #e4e4e7);
  background: var(--ai-bg, #ffffff);
  color: var(--ai-text-muted, #71717a);
  font-size: 11.5px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.12s ease;
}

.btn-load-older:hover:not(:disabled) {
  background: var(--ai-bg-tertiary, #f4f4f5);
  color: var(--ai-text, #09090b);
}

/* 空状态 */
.center-empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  text-align: center;
}

.empty-icon-box {
  width: 52px;
  height: 52px;
  border-radius: 14px;
  background: var(--ai-bg-tertiary, #f4f4f5);
  border: 1px solid var(--ai-border-strong, #e4e4e7);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--ai-text, #09090b);
  margin-bottom: 16px;
}

.empty-greeting {
  font-size: 20px;
  font-weight: 600;
  color: var(--ai-text, #09090b);
  margin-bottom: 8px;
}

.empty-subtext {
  font-size: 13px;
  color: var(--ai-text-muted, #71717a);
  max-width: 500px;
  line-height: 1.6;
  margin-bottom: 24px;
}

.center-quick-chips {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
  max-width: 600px;
}

.quick-chip-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border-radius: 8px;
  border: 1px solid var(--ai-border-strong, #e4e4e7);
  background: var(--ai-bg, #ffffff);
  color: var(--ai-text-secondary, #3f3f46);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.12s ease;
}

.quick-chip-btn:hover {
  border-color: var(--ai-border-focus, #09090b);
  color: var(--ai-text, #09090b);
  background: var(--ai-bg-tertiary, #f4f4f5);
}

/* 消息列表 */
.center-message-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
  width: 100%;
}

.center-msg-item {
  width: 100%;
}

.user-msg-bubble-wrap {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 8px;
}

.user-msg-bubble {
  max-width: 80%;
  background: var(--ai-bg, #ffffff);
  color: var(--ai-text, #09090b);
  padding: 10px 14px;
  border: 1px solid var(--ai-border-strong, #e4e4e7);
  border-radius: 12px 12px 2px 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}

.user-msg-bubble:hover {
  border-color: var(--ai-border-focus, #d4d4d8);
  box-shadow: 0 6px 18px -2px rgba(0, 0, 0, 0.08), 0 2px 6px -1px rgba(0, 0, 0, 0.04);
  transform: translateY(-1px);
}

.user-msg-text {
  font-size: 13.5px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.user-msg-bubble :deep(.agent-image-attachments) {
  margin-bottom: 8px;
}

.user-msg-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 6px;
}

.user-msg-footer .msg-time {
  color: var(--ai-text-muted, #71717a);
  font-size: 10.5px;
  font-family: 'Inter', -apple-system, sans-serif;
  font-weight: 500;
}

.user-msg-footer .msg-copy-btn {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 1.5px 6px;
  border-radius: 4px;
  background: var(--ai-bg-tertiary, #f4f4f5);
  border: 1px solid var(--ai-border-strong, #e4e4e7);
  color: var(--ai-text-secondary, #52525b);
  font-size: 10.5px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.12s ease;
}

.user-msg-footer .msg-copy-btn:hover {
  background: var(--ai-bg-secondary, #e4e4e7);
  color: var(--ai-text, #18181b);
  border-color: var(--ai-border-focus, #d4d4d8);
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
  color: var(--ai-text, #09090b);
}

.msg-actions-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding-top: 4px;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.15s ease;
}

.center-msg-card:hover .msg-actions-bar,
.center-msg-card:focus-within .msg-actions-bar {
  opacity: 1;
  pointer-events: auto;
}

.msg-actions-bar .msg-time {
  font-size: 10px;
  color: var(--ai-text-faint, #a1a1aa);
  font-family: 'Inter', -apple-system, sans-serif;
  letter-spacing: -0.01em;
}

.msg-action-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 3px;
  border-radius: 4px;
  border: none;
  background: transparent;
  color: var(--ai-text-faint, #a1a1aa);
  cursor: pointer;
  transition: all 0.12s ease;
}

.msg-action-btn:hover {
  background: var(--ai-bg-tertiary, #f4f4f5);
  color: var(--ai-text, #18181b);
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
  background: var(--ai-bg, #ffffff);
  border: 1px solid var(--ai-border-strong, #e4e4e7);
  box-shadow: var(--ai-shadow-md, 0 4px 12px rgba(0, 0, 0, 0.08));
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--ai-text, #18181b);
  cursor: pointer;
  transition: all 0.15s ease;
}

.center-scroll-fab-btn:hover {
  background: var(--ai-bg-tertiary, #f4f4f5);
  transform: translateY(-2px);
  box-shadow: var(--ai-shadow-lg, 0 6px 18px rgba(0, 0, 0, 0.12));
}

.center-msg-navigator {
  position: absolute;
  top: 14px;
  right: 24px;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  border-radius: 20px;
  background: var(--ai-bg, #ffffff);
  border: 1px solid var(--ai-border-strong, #e4e4e7);
  box-shadow: var(--ai-shadow-md, 0 4px 14px rgba(0, 0, 0, 0.08));
  z-index: 55;
  font-size: 11px;
  font-family: 'JetBrains Mono', monospace;
  font-weight: 600;
  color: var(--ai-text, #18181b);
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
  color: var(--ai-text-muted, #71717a);
  transition: all 0.12s;
}

.center-msg-navigator .nav-btn:hover:not(.disabled) {
  background: var(--ai-bg-tertiary, #f4f4f5);
  color: var(--ai-text, #18181b);
}

.center-msg-navigator .nav-btn.disabled {
  opacity: 0.3;
  cursor: not-allowed;
}

.nav-indicator {
  color: var(--ai-text-muted, #71717a);
  padding: 0 4px;
}

/* 悬浮在底部的 Composer */
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

/* 底部状态条 */
.center-status-strip {
  height: 24px;
  padding: 0 16px;
  background: var(--ai-bg-secondary, #fafafa);
  border-top: 1px solid var(--ai-border, #f4f4f5);
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11px;
  font-weight: 500;
  color: var(--ai-text-muted, #71717a);
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
  background: var(--ai-green, #10b981);
}

.status-dot.warning {
  background: var(--ai-yellow, #f59e0b);
}

.icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

@media (max-width: 768px) {
  .center-ai-topbar {
    padding: 0 8px;
    height: 38px;
  }
  .btn-dockback {
    display: none !important;
  }
  .topbar-session-badge {
    max-width: 100px;
    font-size: 10px;
  }
  .center-chat-history {
    padding: 8px 8px 110px 8px;
  }
  .center-msg-item {
    max-width: 100%;
    margin-bottom: 12px;
  }
  .center-composer-dock-pinned {
    padding: 0 8px 4px 8px;
  }
  .center-status-strip {
    padding: 0 8px;
    font-size: 10px;
  }
}
</style>
