import { computed, ref, watch } from 'vue'
import { createTokenUsageState } from './cacheTelemetryStatus.js'

function emptyTokenUsage() {
  return createTokenUsageState()
}

function createAssistantMessage() {
  return {
    role: 'assistant',
    content: '',
    thinking: '',
    _thinkingDisplay: '',
    thinkingBlocks: [],
    toolCalls: [],
    plan: null,
    planJson: null,
    isStreaming: false,
    error: null,
    _nextOrder: 0
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
  const historyEvents = ref([])
  const hasOlderMessages = ref(false)
  const loadingOlderMessages = ref(false)
  const nextBeforeMessageId = ref(null)
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
    historyEvents.value = []
    hasOlderMessages.value = false
    loadingOlderMessages.value = false
    nextBeforeMessageId.value = null
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

  function normalizeMessagePage(data) {
    return Array.isArray(data)
      ? { events: data, hasMore: false, nextBeforeMessageId: null }
      : (data || {})
  }

  function rebuildHistoryMessages(events) {
    messages.value = []
    for (const event of events) {
      let data = {}
      try {
        data = JSON.parse(event.eventData || '{}')
      } catch {
        data = {}
      }
      if (event.eventType === 'USER') {
        messages.value.push({ role: 'user', content: event.content || data.content || '' })
        messages.value.push(createAssistantMessage())
        continue
      }
      const message = messages.value.at(-1)
      if (message?.role === 'assistant') {
        replayHistoryEvent(event.eventType, data, message)
      }
    }
    finalizeHistoryMessages()
  }

  function mergeHistoryEvents(olderEvents) {
    const uniqueEvents = new Map()
    for (const event of [...olderEvents, ...historyEvents.value]) {
      if (event?.messageId != null) uniqueEvents.set(event.messageId, event)
    }
    historyEvents.value = [...uniqueEvents.values()].sort((left, right) => left.messageId - right.messageId)
  }

  async function loadConversationMessages(conversationId) {
    const requestVersion = ++historyRequestVersion
    messages.value = []
    historyEvents.value = []
    hasOlderMessages.value = false
    loadingOlderMessages.value = false
    nextBeforeMessageId.value = null
    sessionChanges.value = []
    tokenUsage.value = emptyTokenUsage()
    currentAgentSession.value = { sessionId: createSessionId(), conversationId }
    agentLoading.value = false

    try {
      const response = await api.agentMessages(projectId.value, conversationId, { limit: 20 })
      if (requestVersion !== historyRequestVersion || currentAgentSession.value?.conversationId !== conversationId) return false
      const page = normalizeMessagePage(response.data)
      historyEvents.value = page.events || []
      hasOlderMessages.value = Boolean(page.hasMore)
      nextBeforeMessageId.value = page.nextBeforeMessageId ?? null
      rebuildHistoryMessages(historyEvents.value)
      return true
    } catch (error) {
      return Promise.reject(error)
    } finally {
      if (requestVersion === historyRequestVersion) onHistoryLoaded?.()
    }
  }

  async function loadOlderMessages() {
    const conversationId = currentAgentSession.value?.conversationId
    const beforeMessageId = nextBeforeMessageId.value
    if (!conversationId || !beforeMessageId || !hasOlderMessages.value || loadingOlderMessages.value || agentLoading.value) return false

    const requestVersion = historyRequestVersion
    loadingOlderMessages.value = true
    try {
      const response = await api.agentMessages(projectId.value, conversationId, { beforeMessageId, limit: 20 })
      if (requestVersion !== historyRequestVersion || currentAgentSession.value?.conversationId !== conversationId) return false
      const page = normalizeMessagePage(response.data)
      mergeHistoryEvents(page.events || [])
      hasOlderMessages.value = Boolean(page.hasMore)
      nextBeforeMessageId.value = page.nextBeforeMessageId ?? null
      rebuildHistoryMessages(historyEvents.value)
      return true
    } catch {
      return false
    } finally {
      if (requestVersion === historyRequestVersion) loadingOlderMessages.value = false
    }
  }

  function finalizeHistoryMessages() {
    for (const message of messages.value) {
      if (message.role !== 'assistant') continue
      if (message.thinkingBlocks) {
        message.thinkingBlocks = message.thinkingBlocks.filter(block => block.content?.trim())
      }
      for (const toolCall of message.toolCalls || []) {
        if (toolCall.status === 'running') toolCall.status = 'completed'
      }
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

