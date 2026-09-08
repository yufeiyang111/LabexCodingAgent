<template>
  <div ref="rootRef" class="sgt-root">
    <div v-if="loadError" class="sgt-load-error">
      <span>{{ loadError }}</span>
      <button type="button" class="sgt-retry" @click="initialize">重试</button>
    </div>
    <CenterAiWorkspace
      v-else
      v-model:active-tab="activeTab"
      :title="displayName"
      :project-id="projectIdRef"
      :changes-refresh-key="changesRefreshKey"
      :session-changes="sessionChanges"
      :session-history="sessionHistory"
      :all-token-stats="null"
      :is-dark="isDark"
      :terminal-visible="false"
      :messages="messages"
      v-model:agent-input="agentInput"
      v-model:agent-mode="agentMode"
      :current-model="currentModelLabel"
      :thinking-level="currentThinkingLevel"
      :available-models="availableModels"
      :agent-loading="agentLoading"
      :current-session-name="statusLabel"
      :has-older-messages="hasOlderMessages"
      :loading-older-messages="loadingOlderMessages"
      :show-thinking-process="showThinkingProcess"
      :supports-images="false"
      :selected-code="selectedCode"
      :pending-images="[]"
      :context-usage-status="contextUsageStatus"
      :token-usage="tokenUsage"
      :quick-chips="[]"
      :active-path="''"
      :command-list="[]"
      :get-merged-items="getMergedItems"
      :render-thinking-markdown="renderThinkingMarkdown"
      :render-message-markdown="renderMessageMarkdown"
      @dock-back="emit('request-close')"
      @mode-change="lockSubagentMode"
      @send="sendMessage"
      @stop="stopGeneration"
      @trigger-commands="noop"
      @trigger-at-file="noop"
      @optimize-prompt="notifyFixed('子代理会话不支持提示词优化')"
      @clear-selected-code="selectedCode = ''"
      @preview-image="noop"
      @remove-image="noop"
      @image-files="noop"
      @open-context-dialog="notifyFixed('上下文用量请查看子代理详情')"
      @change-model="notifyFixed('子代理继承主 Agent 的模型配置')"
      @change-thinking="notifyFixed('子代理继承主 Agent 的推理配置')"
      @open-model-config="notifyFixed('请在主 Agent 中管理模型配置')"
      @load-older-history="loadOlderHistory"
      @apply-chip="noop"
      @markdown-click="handleMarkdownClick"
      @permission="handlePermissionDecision"
      @command-approval="handleCommandApproval"
      @question="handleQuestionReply"
      @copy-message="copyMessage"
      @insert-editor="p => emit('insert-editor', p)"
      @review-changes="activeTab = 'review'"
      @open-file-diff="f => emit('open-file-diff', f)"
      @revert-change="c => revertChange(c)"
      @undo-change="onUndoChange"
      @toggle-terminal="notifyFixed('子代理会话不提供终端')"
      @open-file="p => emit('open-file', p)"
      @open-preview="u => emit('open-preview', u)"
    />
  </div>
</template>

<script setup>
// 子代理独立会话标签：UI 与主 Agent 中部放大完全同源（CenterAiWorkspace），
// 运行时复用主线 composable 全家桶（useConversationState / useAgentTaskRuntime /
// useAgentEventTimeline / useAgentInteraction / useChangeSetState / 共享 Markdown 渲染器），
// 仅以“每标签一份实例”的方式隔离状态；本组件不实现任何专属 UI 或事件协议。
import { ref, computed, watch, nextTick, onMounted, onBeforeUnmount, defineAsyncComponent } from 'vue'
import { ElMessage } from 'element-plus'
import { projectApi } from '@/api'
import { useAgentStream } from '@/composables/useAgentStream'
import { useConversationState } from '@/composables/useConversationState'
import { useAgentTaskRuntime } from '@/composables/useAgentTaskRuntime'
import { useAgentEventTimeline } from '@/composables/useAgentEventTimeline'
import { useAgentInteraction } from '@/composables/useAgentInteraction'
import { useChangeSetState } from '@/composables/useChangeSetState'
import { createMergedItemsResolver, startThinkingReveal, flushThinkingDisplay } from '@/composables/agentMessageTimeline'
import { renderThinkingMarkdown, renderMessageMarkdown } from '@/utils/agentMarkdownRenderer'
import { reduceHistoryEvent, reduceContextManagementEvent } from '@/composables/agentHistoryReducer'
import {
  applyCommandExecutionResponseState,
  attachCommandApprovalState as attachCommandApproval,
  findCommandApprovalToolCall as commandApprovalToolCall,
  updateCommandApprovalState as updateCommandApprovalLifecycle
} from '@/composables/agentCommandApprovalState'
import { attachDurableInteraction } from '@/composables/agentInteractionProjection'
import { normalizeWorkspacePath } from '@/utils/pathUtils'
import { createTokenUsageState } from '@/composables/cacheTelemetryStatus'

const props = defineProps({
  projectId: { type: [Number, String], required: true },
  subagentId: { type: [Number, String], required: true },
  childTaskId: { type: [Number, String], default: null },
  initialConversationId: { type: String, default: null },
  name: { type: String, default: '' },
  isDark: { type: Boolean, default: false },
  availableModels: { type: Array, default: () => [] },
  parentModelConfigId: { type: [Number, String], default: null },
  parentModelName: { type: String, default: '' },
  parentThinkingLevel: { type: String, default: '' }
})

const emit = defineEmits(['request-close', 'open-file', 'open-file-diff', 'insert-editor', 'open-preview'])

const CenterAiWorkspace = defineAsyncComponent({
  loader: () => import('@/components/cloud/chat/CenterAiWorkspace.vue'),
  delay: 0,
  timeout: 15000
})

const projectIdRef = computed(() => Number(props.projectId))
const rootRef = ref(null)
const detail = ref(null)
const loadError = ref('')

const activeTab = ref('chat')
const messages = ref([])
const agentInput = ref('')
const effectiveSubagentMode = computed(() => {
  const type = String(detail.value?.agentType || '').toLowerCase()
  return type === 'general' ? 'build' : 'explore'
})
const agentMode = ref('explore')

watch(
  effectiveSubagentMode,
  mode => {
    agentMode.value = mode
  },
  { immediate: true }
)

const agentLoading = ref(false)
const currentAgentSession = ref(null)
const sessionChanges = ref([])
const sessionHistory = ref([])
const tokenUsage = ref(createTokenUsageState())
const contextUsageStatus = ref(null)
const changesRefreshKey = ref(0)
const showThinkingProcess = ref(true)
const selectedCode = ref('')

const displayName = computed(() => detail.value?.identity || props.name || ('子代理 #' + props.subagentId))

const effectiveModelConfig = computed(() => {
  const models = Array.isArray(props.availableModels) ? props.availableModels : []
  const explicitId = detail.value?.modelConfigId
  if (explicitId != null) {
    const found = models.find(m => String(m.configId || m.id) === String(explicitId))
    if (found) return found
  }
  if (props.parentModelConfigId != null) {
    const foundParent = models.find(m => String(m.configId || m.id) === String(props.parentModelConfigId))
    if (foundParent) return foundParent
  }
  if (models.length > 0) {
    return models.find(m => m.isDefault === 1) || models[0]
  }
  return null
})

const effectiveModelConfigId = computed(() => {
  return effectiveModelConfig.value?.configId || effectiveModelConfig.value?.id || detail.value?.modelConfigId || props.parentModelConfigId || null
})

const currentModelLabel = computed(() => {
  if (effectiveModelConfig.value) {
    return effectiveModelConfig.value.modelName || effectiveModelConfig.value.configName || effectiveModelConfig.value.name
  }
  if (detail.value?.modelName || detail.value?.configName) {
    return detail.value.modelName || detail.value.configName
  }
  if (props.parentModelName && props.parentModelName !== '未配置模型') {
    return props.parentModelName
  }
  return '未配置模型'
})

const currentThinkingLevel = computed(() => {
  return props.parentThinkingLevel || 'High'
})
const statusLabel = computed(() => {
  const map = { completed: '已完成', failed: '失败', cancelled: '已取消', running: '运行中', queued: '排队中', waiting_user: '等待输入', waiting_approval: '等待批准' }
  const s = String(detail.value?.status || '').toLowerCase()
  return map[s] || (s || '子代理会话')
})

const { stream: streamAgent, subscribe: subscribeAgent, disconnect: disconnectAgentStream, disconnectSubscription, stop: stopAgent } = useAgentStream()

function scrollDown(force = false) {
  nextTick(() => {
    const pane = rootRef.value?.querySelector('.center-ai-scroll-pane')
    if (!pane) return
    if (force || pane.scrollHeight - pane.scrollTop - pane.clientHeight < 400) {
      pane.scrollTop = pane.scrollHeight
    }
  })
}

let agentRenderFrame = null
function scheduleAgentRender() {
  if (agentRenderFrame != null) return
  agentRenderFrame = requestAnimationFrame(async () => {
    agentRenderFrame = null
    await nextTick()
    scrollDown()
  })
}

function createMessageTiming() {
  return { taskId: null, startedAt: Date.now(), activeElapsedMs: null, isRunning: true }
}
function stopMessageTimer(message) {
  if (message?.timing) message.timing.isRunning = false
}
function attachUserQuestion(msg, data) {
  attachDurableInteraction(msg, 'question', data)
}
function noop() {}
function notifyFixed(text) {
  ElMessage.info(text)
}
function lockSubagentMode() {
  const current = effectiveSubagentMode.value
  agentMode.value = current
  const typeName = detail.value?.agentType || 'scout'
  const modeLabel = current === 'build' ? '构建' : '探索'
  notifyFixed(`当前子代理为 ${typeName}（${modeLabel}模式），能力边界已固定`)
}
function copyMessage(text) {
  if (!text) return
  navigator.clipboard?.writeText(text)
  ElMessage.success('已复制')
}
function getMergedItems(msg) {
  return mergedItemsResolver(msg)
}
const mergedItemsResolver = createMergedItemsResolver(showThinkingProcess)

function replayHistoryEvent(eventType, data, message) {
  reduceHistoryEvent(eventType, data, message, {})
}

const changeSet = useChangeSetState({
  projectId: projectIdRef,
  api: projectApi,
  sessionChanges,
  onFileReverted: () => {}
})
const { trackFileChange, revertChange, removeChange } = changeSet

const conversationState = useConversationState({
  projectId: projectIdRef,
  api: projectApi,
  messages,
  sessionChanges,
  tokenUsage,
  agentLoading,
  currentAgentSession,
  replayHistoryEvent,
  onHistoryAttachments: async () => {},
  onHistoryLoaded: () => scrollDown(true),
  onClearMessages: () => {}
})
const {
  hasOlderMessages,
  loadingOlderMessages,
  loadConversationMessages,
  loadOlderMessages
} = conversationState

const { submitPermissionDecision, submitQuestionReply } = useAgentInteraction({
  projectId: projectIdRef,
  api: projectApi
})

// timeline ↔ taskRuntime 互相引用：与 CloudWorkspace 相同，用可变句柄打破初始化顺序。
const handleAgentEventRef = { current: () => {} }

const taskRuntime = useAgentTaskRuntime({
  projectId: projectIdRef,
  currentAgentSession,
  messages,
  agentLoading,
  api: projectApi,
  subscribeAgent,
  disconnectSubscription,
  handleAgentEvent: (event, assistantMsg) => handleAgentEventRef.current(event, assistantMsg),
  reconcileRecoveredCommandApproval,
  createMessageTiming,
  stopMessageTimer,
  scrollDown,
  reloadConversationHistory: conversationId => loadConversationMessages(conversationId)
})
const {
  logTaskRecovery,
  recordTaskEventCursor,
  invalidate: invalidateTaskRuntime,
  recoverActiveTaskForConversation,
  reconcileDirectTerminalTask,
  resumeTaskEventSubscription,
  syncTaskTiming
} = taskRuntime

const timeline = useAgentEventTimeline({
  recordTaskEventCursor,
  currentAgentSession,
  scheduleAgentRender,
  startThinkingReveal,
  flushThinkingDisplay,
  trackFileChange,
  changesRefreshKey,
  attachCommandApproval,
  updateCommandApprovalLifecycle,
  attachUserQuestion,
  stopMessageTimer,
  agentLoading,
  logTaskRecovery,
  contextUsageStatus,
  reduceContextManagementEvent,
  tokenUsage,
  sessionHistory,
  currentSessionName: displayName,
  onTokenUsageProjected: () => {},
  onWorkspaceChanged: () => {}
})
handleAgentEventRef.current = timeline.handleAgentEvent

function reconcileRecoveredCommandApproval(message, task) {
  const approval = task?.commandApproval
  if (!message || !approval?.approvalId) return
  message.toolCalls = message.toolCalls || []
  const taskStatus = String(task?.status || '').toLowerCase()
  const approvalStatus = String(approval.status || '').toLowerCase()
  message.toolCalls.forEach(call => {
    const historicalApprovalId = call?.commandApproval?.approvalId
    if (historicalApprovalId && historicalApprovalId !== approval.approvalId && call.status === 'waiting_approval') {
      call.status = 'error'
      call.result = '旧批准请求已失效，命令未执行'
    }
  })
  let call = commandApprovalToolCall(message, approval.approvalId)
  if (!call && approvalStatus === 'pending' && taskStatus === 'waiting_approval') {
    attachCommandApproval(message, approval)
    call = commandApprovalToolCall(message, approval.approvalId)
  }
  if (!call) return
  call.commandApproval = { ...call.commandApproval, ...approval }
  call.summary = approval.displayCommand || call.summary
  if (approvalStatus === 'pending' && taskStatus === 'waiting_approval') {
    call.status = 'waiting_approval'
    call.result = null
    return
  }
  if (approval.executionStatus === 'completed') {
    updateCommandApprovalLifecycle(message, 'COMMAND_EXECUTION_COMPLETED', approval)
    return
  }
  if (approval.executionStatus === 'failed' || approval.executionStatus === 'interrupted') {
    updateCommandApprovalLifecycle(message,
      approval.executionStatus === 'interrupted' ? 'COMMAND_EXECUTION_INTERRUPTED' : 'COMMAND_EXECUTION_FAILED', approval)
    return
  }
  if (approvalStatus === 'rejected' || approvalStatus === 'expired') {
    updateCommandApprovalLifecycle(message,
      approvalStatus === 'expired' ? 'COMMAND_APPROVAL_EXPIRED' : 'COMMAND_APPROVAL_REJECTED', approval)
    return
  }
  if (approvalStatus === 'approved' || approvalStatus === 'consumed') {
    call.status = 'running'
    call.result = '命令已批准，正在恢复执行'
  }
}

async function sendMessage() {
  const text = agentInput.value.trim()
  if (!text || agentLoading.value) return
  messages.value.push({ role: 'user', content: text, timestamp: Date.now() })
  messages.value.push({
    role: 'assistant', content: '', pendingFinalContent: '', hasPendingFinalDraft: false, hasDurableFinal: false,
    thinking: '', _thinkingDisplay: '', _thinkingTimer: null, thinkingBlocks: [], toolCalls: [], plan: null,
    isStreaming: true, error: null, _nextOrder: 0, timestamp: Date.now(),
    conversationId: currentAgentSession.value?.conversationId || null, timing: createMessageTiming()
  })
  const assistantMsg = messages.value[messages.value.length - 1]
  agentInput.value = ''
  agentLoading.value = true
  await nextTick()
  scrollDown(true)

  const sessionId = currentAgentSession.value?.sessionId || crypto.randomUUID()
  try {
    await streamAgent(projectIdRef.value, {
      sessionId,
      conversationId: currentAgentSession.value?.conversationId,
      mode: 'subagent',
      message: text,
      modelConfigId: effectiveModelConfigId.value
    }, {
      onEvent: event => handleAgentEventRef.current(event, assistantMsg)
    })
  } catch (e) {
    if (e.name === 'AbortError') {
      assistantMsg.content += '\n[连接已中断]'
    } else {
      assistantMsg.error = e.message
      assistantMsg.content = `错误：${e.message}`
    }
  } finally {
    flushThinkingDisplay(assistantMsg)
    const shouldResumeTaskEvents = assistantMsg.resumeTaskEventsAfterStream === true && !!assistantMsg.taskId
    assistantMsg.resumeTaskEventsAfterStream = false
    if (shouldResumeTaskEvents) {
      assistantMsg.isStreaming = true
      if (assistantMsg.timing) assistantMsg.timing.isRunning = true
      agentLoading.value = true
      await replayResumedAgent(assistantMsg.taskId, assistantMsg)
    } else {
      assistantMsg.isStreaming = false
      stopMessageTimer(assistantMsg)
      if (assistantMsg.hasDurableFinal !== true && !assistantMsg.error) {
        await reconcileDirectTerminalTask(assistantMsg)
      }
      await syncTaskTiming(assistantMsg)
      agentLoading.value = assistantMsg.isStreaming
    }
    await nextTick()
    scrollDown()
  }
}

async function stopGeneration() {
  disconnectAgentStream()
  disconnectSubscription()
  try {
    await stopAgent(projectIdRef.value, currentAgentSession.value?.sessionId)
  } catch (e) {
    console.warn('[SubagentTab] 停止失败：', e)
  }
  agentLoading.value = false
  messages.value.forEach(m => {
    if (m.isStreaming) {
      m.isStreaming = false
      stopMessageTimer(m)
    }
  })
  void refreshDetail()
}

async function replayResumedAgent(taskId, assistantMsg) {
  try {
    await resumeTaskEventSubscription(taskId, assistantMsg)
  } catch (error) {
    assistantMsg.error = '恢复子代理任务失败：' + (error?.message || '未知错误')
    if (!assistantMsg.content) assistantMsg.content = assistantMsg.error
    assistantMsg.isStreaming = false
    stopMessageTimer(assistantMsg)
    agentLoading.value = false
  }
}

async function handlePermissionDecision(payload) {
  const result = await submitPermissionDecision(payload)
  const call = payload?.call || messages.value.flatMap(m => m?.toolCalls || []).find(c => c?.toolCallId === payload?.toolCallId)
  if (!result.success || (call?.status === 'error' && payload?.action !== 'reject')) return
  const request = call?.networkRequest || call?.permissionRequest
  const assistantMsg = messages.value.find(m => m?.toolCalls?.includes(call))
  const taskId = request?.taskId || assistantMsg?.taskId
  if (assistantMsg && taskId) {
    assistantMsg.isStreaming = true
    if (assistantMsg.timing) assistantMsg.timing.isRunning = true
    agentLoading.value = true
    void replayResumedAgent(taskId, assistantMsg)
  }
}

async function handleQuestionReply(payload) {
  const result = await submitQuestionReply(payload)
  if (result.reason === 'answer_required') { ElMessage.warning('请先输入回答'); return }
  if (result.reason === 'request_missing') { ElMessage.warning('提问请求仍在同步，请稍后重试'); return }
  if (result.success) {
    const call = payload?.call || messages.value.flatMap(m => m?.toolCalls || []).find(c => c?.toolCallId === payload?.toolCallId)
    const assistantMsg = messages.value.find(m => m?.toolCalls?.includes(call))
    const taskId = call?.questionRequest?.taskId || assistantMsg?.taskId
    if (assistantMsg && taskId) {
      assistantMsg.isStreaming = true
      agentLoading.value = true
      void replayResumedAgent(taskId, assistantMsg)
    }
  }
}

async function handleCommandApproval(payload) {
  const approval = payload?.call?.commandApproval
  if (!approval?.approvalId) return
  const call = payload.call
  if (call._commandApprovalInFlight) return
  call._commandApprovalInFlight = true
  if (payload.action === 'reject') {
    call.status = 'error'
    call.result = '正在拒绝命令...'
  } else {
    call.status = 'running'
    call.result = '正在批准命令...'
  }
  try {
    const decision = await projectApi.agentDecideCommandApproval(projectIdRef.value, approval.approvalId, {
      action: payload.action,
      decisionIdempotencyKey: call._commandDecisionIdempotencyKey ||
        (call._commandDecisionIdempotencyKey = crypto.randomUUID())
    })
    if (decision?.data?.approvalUnavailable) {
      call.status = 'error'
      call.result = '批准请求不可用或已被处理'
      return
    }
    if (payload.action === 'reject') {
      call.status = 'error'
      call.result = '已拒绝命令，命令未执行'
      continueCommandTaskProjection(call, approval)
      return
    }
    const execution = await projectApi.agentExecuteCommandApproval(projectIdRef.value, approval.approvalId)
    const executionData = execution?.data || {}
    if (executionData.approvalUnavailable) {
      call.status = 'error'
      call.result = '批准请求不可用或已被处理'
      return
    }
    applyCommandExecutionResponseState(call, executionData)
    continueCommandTaskProjection(call, approval)
  } catch (error) {
    call.status = 'error'
    call.result = '命令审批失败：' + (error?.response?.data?.message || error?.message || '未知错误')
  } finally {
    call._commandApprovalInFlight = false
  }
}

function continueCommandTaskProjection(call, approval) {
  const assistantMsg = messages.value.find(m => m?.toolCalls?.includes(call))
  const taskId = approval?.taskId || assistantMsg?.taskId
  if (!assistantMsg || !taskId) return
  assistantMsg.waitingForCommandApproval = false
  assistantMsg.isStreaming = true
  if (assistantMsg.timing) assistantMsg.timing.isRunning = true
  agentLoading.value = true
  void replayResumedAgent(taskId, assistantMsg, approval?.conversationId || assistantMsg.conversationId)
}

async function onUndoChange(change) {
  try {
    await projectApi.agentUndoDiff(projectIdRef.value, change.changeId)
    removeChange(change)
    changesRefreshKey.value++
    ElMessage.success('已撤销该变更')
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '撤销失败')
  }
}

function handleMarkdownClick(event) {
  const fileLink = event.target?.closest?.('.file-link')
  if (fileLink) {
    const raw = fileLink.dataset.path || ''
    const cleanPath = normalizeWorkspacePath(raw) || raw.replace(/^(\.\/|\/)/, '').replace(/\\/g, '/')
    if (cleanPath) emit('open-file', cleanPath)
  }
}

async function loadOlderHistory() {
  const pane = rootRef.value?.querySelector('.center-ai-scroll-pane')
  const oldScrollHeight = pane?.scrollHeight || 0
  const oldScrollTop = pane?.scrollTop || 0
  const loaded = await loadOlderMessages()
  if (!loaded || !pane) return
  await nextTick()
  pane.scrollTop = pane.scrollHeight - oldScrollHeight + oldScrollTop
}

let detailTimer = null
function refreshDetailSoon() {
  if (detailTimer) clearTimeout(detailTimer)
  detailTimer = setTimeout(() => { detailTimer = null; void refreshDetail() }, 900)
}
async function refreshDetail() {
  try {
    const r = await projectApi.agentSubagent(projectIdRef.value, props.subagentId)
    if (r.code === 0 && r.data) detail.value = { ...detail.value, ...r.data, events: undefined }
  } catch { /* 静默 */ }
}

async function resolveConversationId(subagentData) {
  if (subagentData?.conversationId) return subagentData.conversationId
  if (subagentData?.childConversationId) return subagentData.childConversationId
  if (props.initialConversationId) return props.initialConversationId
  const taskId = subagentData?.childTaskId || props.childTaskId
  if (taskId) {
    try {
      const taskRes = await projectApi.agentTask(projectIdRef.value, taskId)
      const convId = taskRes?.data?.conversationId || taskRes?.data?.runSession?.conversationId
      if (convId) return convId
    } catch {
      // 忽略兜底任务查询异常
    }
  }
  return null
}

async function initialize() {
  loadError.value = ''
  try {
    let subagentData = null
    let conversationId = null

    // 如果子代理会话/子任务刚在后端异步创建，重试最多 4 次（每次 400ms）确保能平滑拉取到
    for (let attempt = 0; attempt < 4; attempt++) {
      try {
        const r = await projectApi.agentSubagent(projectIdRef.value, props.subagentId)
        if (r.code === 0 && r.data) {
          subagentData = r.data
          conversationId = await resolveConversationId(subagentData)
          if (conversationId) break
        }
      } catch {
        // 忽略重试期间的临时异常
      }
      if (attempt < 3) {
        await new Promise(resolve => setTimeout(resolve, 400))
      }
    }

    if (!subagentData) {
      throw new Error('子代理不存在或无权访问')
    }
    detail.value = subagentData

    if (!conversationId) {
      conversationId = await resolveConversationId(subagentData)
    }

    if (!conversationId) {
      throw new Error('子代理会话尚未创建，请稍后重试')
    }

    currentAgentSession.value = { sessionId: crypto.randomUUID(), conversationId }
    await loadConversationMessages(conversationId)
    // 若子任务仍在运行，恢复主线的订阅/恢复机制（断线、刷新、waiting_* 全覆盖）。
    void recoverActiveTaskForConversation(conversationId).then(() => refreshDetailSoon())
    scrollDown(true)
  } catch (error) {
    loadError.value = error?.message || '加载子代理会话失败'
  }
}

onMounted(initialize)
onBeforeUnmount(() => {
  invalidateTaskRuntime()
  disconnectAgentStream()
})

defineExpose({ refreshDetail })
</script>

<style scoped>
.sgt-root { height: 100%; display: flex; flex-direction: column; }
.sgt-load-error {
  flex: 1; display: flex; flex-direction: column; gap: 12px;
  align-items: center; justify-content: center; color: #dc2626; font-size: 13px;
}
.sgt-retry {
  border: 1px solid #cbd5e1; background: #fff; color: #334155;
  border-radius: 8px; padding: 6px 16px; cursor: pointer; font-size: 12px;
}
.sgt-retry:hover { background: #f1f5f9; }
</style>
