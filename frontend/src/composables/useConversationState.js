import { computed, ref, watch } from 'vue'
import { createTokenUsageState } from './cacheTelemetryStatus.js'
import { applyRunMessageSnapshot, applyRunPartSnapshot } from './agentRunPartState.js'
import { isTerminalAgentRunState, normalizeAgentRunState } from './agentRunState.js'

const DURABLE_HISTORY_PROJECTION_VERSION = 'durable-task-history-v1'
const NON_STREAMING_HISTORY_STATES = new Set([
  'waiting_approval',
  'waiting_user',
  'waiting_workspace',
  'waiting_environment'
])

function emptyTokenUsage() {
  return createTokenUsageState()
}

function historyTaskIsStreaming(status) {
  const runState = normalizeAgentRunState(status)
  if (!runState || isTerminalAgentRunState(runState)) return false
  return !NON_STREAMING_HISTORY_STATES.has(runState)
}

function historyTimestamp(value) {
  const timestamp = Date.parse(value || '')
  return Number.isFinite(timestamp) ? timestamp : undefined
}

function createAssistantMessage(turn = {}) {
  const runState = normalizeAgentRunState(turn.status)
  const isStreaming = historyTaskIsStreaming(runState)
  const taskId = turn.taskId ?? null
  return {
    role: 'assistant',
    content: '',
    thinking: '',
    _thinkingDisplay: '',
    thinkingBlocks: [],
    toolCalls: [],
    plan: null,
    planJson: null,
    isStreaming,
    error: null,
    _nextOrder: 0,
    timestamp: historyTimestamp(turn.startedAt || turn.submittedAt || turn.createdAt),
    taskId,
    conversationId: turn.conversationId || null,
    sourceConversationId: turn.sourceConversationId || turn.conversationId || null,
    inherited: Boolean(turn.inherited),
    sessionId: turn.sessionId || null,
    mode: turn.mode || null,
    runState,
    executionEpoch: Number(turn.executionEpoch || 0),
    lastEventSequence: Number(turn.lastEventSequence || 0),
    timing: taskId == null ? null : {
      taskId,
      startedAt: historyTimestamp(turn.startedAt || turn.submittedAt || turn.createdAt),
      activeElapsedMs: Number.isFinite(Number(turn.activeElapsedMs)) ? Number(turn.activeElapsedMs) : null,
      isRunning: isStreaming
    }
  }
}

export function createConversationSelectionStore(projectId, storage = globalThis.sessionStorage) {
  const key = () => `labex-agent:selected-conversation:${projectId.value}`
  return {
    read() {
      if (!storage || projectId.value == null) return null
      return storage.getItem(key())
    },
    save(conversationId) {
      if (!storage || projectId.value == null || !conversationId) return
      storage.setItem(key(), String(conversationId))
    },
    clear() {
      if (!storage || projectId.value == null) return
      storage.removeItem(key())
    }
  }
}

export function useConversationState({
  projectId,
  api,
  messages,
  sessionChanges,
  tokenUsage,
  agentLoading,
  currentAgentSession,
  replayHistoryEvent,
  onHistoryLoaded,
  createSessionId = () => crypto.randomUUID(),
  storage = globalThis.sessionStorage
}) {
  const conversations = ref([])
  const historyTurns = ref([])
  const hasOlderMessages = ref(false)
  const loadingOlderMessages = ref(false)
  const nextBeforeTaskId = ref(null)
  let historyRequestVersion = 0
  const selectionStore = createConversationSelectionStore(projectId, storage)
  watch(
    () => currentAgentSession.value?.conversationId,
    conversationId => selectionStore.save(conversationId),
    { flush: 'sync' }
  )
  const currentSessionName = computed(() => {
    const conversationId = currentAgentSession.value?.conversationId
    if (!conversationId) return '新会话'
    const conversation = conversations.value.find(item => item.conversationId === conversationId)
    return conversation?.title || '对话'
  })

  function clearConversationState() {
    historyRequestVersion += 1
    messages.value = []
    historyTurns.value = []
    hasOlderMessages.value = false
    loadingOlderMessages.value = false
    nextBeforeTaskId.value = null
    currentAgentSession.value = null
    selectionStore.clear()
    sessionChanges.value = []
    tokenUsage.value = emptyTokenUsage()
  }

  async function loadConversations() {
    try {
      const response = await api.agentConversations(projectId.value)
      conversations.value = [...(response.data || [])]
      return conversations.value
    } catch {
      return conversations.value
    }
  }

  function resolveStartupConversation() {
    const selectedConversationId = selectionStore.read()
    if (selectedConversationId) {
      const selectedConversation = conversations.value.find(item => item.conversationId === selectedConversationId)
      if (selectedConversation) return selectedConversation
      selectionStore.clear()
    }
    return conversations.value[0] || null
  }

  function createNewSession() {
    clearConversationState()
  }

  async function selectConversation(conversation) {
    if (!conversation?.conversationId || currentAgentSession.value?.conversationId === conversation.conversationId) {
      return false
    }
    await loadConversationMessages(conversation.conversationId)
    return true
  }

  function normalizeHistoryPage(data, conversationId) {
    if (!data || data.projectionVersion !== DURABLE_HISTORY_PROJECTION_VERSION) {
      throw new Error('Unsupported Agent conversation history projection')
    }
    if (!Array.isArray(data.turns)) {
      throw new Error('Agent conversation history is missing durable turns')
    }
    if (data.conversationId && String(data.conversationId) !== String(conversationId)) {
      throw new Error('Agent conversation history belongs to a different conversation')
    }
    return data
  }

  function compareTaskIds(left, right) {
    return Number(left?.taskId || 0) - Number(right?.taskId || 0)
  }

  function eventPayload(event) {
    const data = event?.data
    return data && typeof data === 'object' && !Array.isArray(data) ? data : {}
  }

  function finalizeHistoryMessage(message) {
    if (message.role !== 'assistant') return
    if (message.thinkingBlocks) {
      message.thinkingBlocks = message.thinkingBlocks.filter(block => block.content?.trim())
    }
  }

  function hydrateHistoryTurn(turn) {
    const rendered = []
    const userContent = typeof turn?.userContent === 'string' ? turn.userContent : ''
    if (userContent.trim()) {
      rendered.push({
        role: 'user',
        content: userContent,
        timestamp: historyTimestamp(turn.submittedAt || turn.createdAt),
        taskId: turn.taskId ?? null,
        conversationId: turn.conversationId || null,
        sourceConversationId: turn.sourceConversationId || turn.conversationId || null,
        inherited: Boolean(turn.inherited)
      })
    }

    const assistant = createAssistantMessage(turn)
    const orderedEvents = [...(turn?.events || [])].sort((left, right) =>
      Number(left?.sequence || left?.eventId || 0) - Number(right?.sequence || right?.eventId || 0))
    orderedEvents.forEach(event => replayHistoryEvent(event.eventType, eventPayload(event), assistant))

    const parts = Array.isArray(turn?.parts) ? turn.parts : []
    if (parts.some(part => String(part?.partType || '').toLowerCase() === 'reasoning')) {
      assistant.thinkingBlocks = []
    }
    applyRunMessageSnapshot(assistant, turn?.runMessages || [])
    applyRunPartSnapshot(assistant, parts)

    assistant.runState = normalizeAgentRunState(turn?.status) || assistant.runState
    assistant.isStreaming = historyTaskIsStreaming(assistant.runState)
    if (assistant.timing) assistant.timing.isRunning = assistant.isStreaming
    finalizeHistoryMessage(assistant)

    const hasAssistantProjection = orderedEvents.length > 0
      || (turn?.runMessages || []).length > 0
      || parts.length > 0
      || Boolean(assistant.runState || turn?.currentStep || turn?.summary)
    if (hasAssistantProjection) rendered.push(assistant)
    return rendered
  }

  function rebuildHistoryMessages() {
    messages.value = historyTurns.value.flatMap(hydrateHistoryTurn)
  }

  function mergeHistoryTurns(olderTurns) {
    const uniqueTurns = new Map()
    for (const turn of [...olderTurns, ...historyTurns.value]) {
      if (turn?.taskId != null) uniqueTurns.set(String(turn.taskId), turn)
    }
    historyTurns.value = [...uniqueTurns.values()].sort(compareTaskIds)
  }

  async function loadConversationMessages(conversationId) {
    const requestVersion = ++historyRequestVersion
    messages.value = []
    historyTurns.value = []
    hasOlderMessages.value = false
    loadingOlderMessages.value = false
    nextBeforeTaskId.value = null
    sessionChanges.value = []
    tokenUsage.value = emptyTokenUsage()
    currentAgentSession.value = { sessionId: createSessionId(), conversationId }
    agentLoading.value = false

    try {
      const response = await api.agentConversationHistory(projectId.value, conversationId, { limit: 20 })
      if (requestVersion !== historyRequestVersion || currentAgentSession.value?.conversationId !== conversationId) return false
      const page = normalizeHistoryPage(response.data, conversationId)
      historyTurns.value = [...page.turns].sort(compareTaskIds)
      hasOlderMessages.value = Boolean(page.hasMore)
      nextBeforeTaskId.value = page.nextBeforeTaskId ?? null
      rebuildHistoryMessages()
      return true
    } catch (error) {
      return Promise.reject(error)
    } finally {
      if (requestVersion === historyRequestVersion) onHistoryLoaded?.()
    }
  }

  async function loadOlderMessages() {
    const conversationId = currentAgentSession.value?.conversationId
    const beforeTaskId = nextBeforeTaskId.value
    if (!conversationId || !beforeTaskId || !hasOlderMessages.value || loadingOlderMessages.value || agentLoading.value) return false

    const requestVersion = historyRequestVersion
    loadingOlderMessages.value = true
    try {
      const response = await api.agentConversationHistory(projectId.value, conversationId, { beforeTaskId, limit: 20 })
      if (requestVersion !== historyRequestVersion || currentAgentSession.value?.conversationId !== conversationId) return false
      const page = normalizeHistoryPage(response.data, conversationId)
      mergeHistoryTurns(page.turns)
      hasOlderMessages.value = Boolean(page.hasMore)
      nextBeforeTaskId.value = page.nextBeforeTaskId ?? null
      rebuildHistoryMessages()
      return true
    } catch {
      return false
    } finally {
      if (requestVersion === historyRequestVersion) loadingOlderMessages.value = false
    }
  }

  async function forkConversation(conversation) {
    if (!conversation?.conversationId) return { success: false, message: '缺少会话 ID' }

    const response = await api.agentForkConversation(projectId.value, conversation.conversationId)
    if (response.code !== 0 || !response.data?.conversationId) {
      return { success: false, message: response.message || '创建分支失败' }
    }

    await loadConversations()
    await loadConversationMessages(response.data.conversationId)
    return { success: true, conversationId: response.data.conversationId }
  }

  async function compactConversation(conversation, modelConfigId = null) {
    if (!conversation?.conversationId) return { success: false, message: '缺少会话 ID' }

    const response = await api.agentCompactConversation(projectId.value, conversation.conversationId, { modelConfigId })
    if (response.code !== 0) {
      return { success: false, message: response.message || '压缩会话失败' }
    }

    await loadConversations()
    const result = {
      success: true,
      taskId: response.data?.taskId || null,
      status: response.data?.status || 'queued'
    }
    if (response.data?.sessionId) result.sessionId = response.data.sessionId
    return result
  }

  async function deleteConversation(conversation) {
    if (!conversation?.conversationId) return false

    await api.agentDeleteConversation(projectId.value, conversation.conversationId)
    if (currentAgentSession.value?.conversationId === conversation.conversationId) {
      clearConversationState()
    }
    conversations.value = conversations.value.filter(item => item.conversationId !== conversation.conversationId)
    return true
  }

  return {
    conversations,
    currentSessionName,
    hasOlderMessages,
    loadingOlderMessages,
    clearConversationState,
    loadConversations,
    resolveStartupConversation,
    createNewSession,
    selectConversation,
    loadConversationMessages,
    loadOlderMessages,
    forkConversation,
    compactConversation,
    deleteConversation
  }
}

