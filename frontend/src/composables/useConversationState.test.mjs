import assert from 'node:assert/strict'
import test from 'node:test'
import { ref } from 'vue'

const conversationModule = await import('./useConversationState.js').catch(() => ({}))
const { useConversationState } = conversationModule

function createHarness(overrides = {}) {
  const projectId = ref(42)
  const messages = ref([])
  const sessionChanges = ref([{ id: 'old-change' }])
  const tokenUsage = ref({ promptTokens: 9, completionTokens: 8, totalTokens: 17, callCount: 2, conversationTotal: 17 })
  const agentLoading = ref(true)
  const currentAgentSession = ref(null)
  const changesRefreshKey = ref(0)
  const replayedEvents = []
  const notifications = []
  const api = {
    agentConversations: async () => ({ data: [
      { conversationId: 'older', title: '旧会话', createTime: '2026-07-10T00:00:00Z' },
      { conversationId: 'newer', title: '新会话', createTime: '2026-07-11T00:00:00Z' }
    ] }),
    agentMessages: async () => ({ data: [
      { eventType: 'USER', content: '你好', eventData: '{}' },
      { eventType: 'FINAL_DELTA', eventData: JSON.stringify({ delta: '你好呀' }) }
    ] }),
    agentForkConversation: async () => ({ code: 0, data: { conversationId: 'forked' } }),
    agentCompactConversation: async () => ({ code: 0 }),
    agentDeleteConversation: async () => ({ code: 0 }),
    ...overrides.api
  }
  const state = useConversationState({
    projectId,
    api,
    messages,
    sessionChanges,
    tokenUsage,
    agentLoading,
    currentAgentSession,
    changesRefreshKey,
    replayHistoryEvent(type, data, message) {
      replayedEvents.push({ type, data })
      if (type === 'FINAL_DELTA') message.content += data.delta || ''
    },
    onHistoryLoaded() { notifications.push('history-loaded') },
    createSessionId: () => 'generated-session'
  })
  return { state, api, messages, sessionChanges, tokenUsage, agentLoading, currentAgentSession, changesRefreshKey, replayedEvents, notifications }
}

test('loads conversations in newest-first order and exposes the selected title', async () => {
  assert.equal(typeof useConversationState, 'function')
  const { state, currentAgentSession } = createHarness()

  await state.loadConversations()
  assert.deepEqual(state.conversations.value.map(item => item.conversationId), ['newer', 'older'])

  currentAgentSession.value = { conversationId: 'older' }
  assert.equal(state.currentSessionName.value, '旧会话')
})

test('rebuilds persisted conversation messages without owning the event reducer', async () => {
  const { state, messages, sessionChanges, tokenUsage, agentLoading, currentAgentSession, replayedEvents, notifications } = createHarness()

  await state.loadConversationMessages('conversation-1')

  assert.deepEqual(currentAgentSession.value, { sessionId: 'generated-session', conversationId: 'conversation-1' })
  assert.equal(agentLoading.value, false)
  assert.deepEqual(sessionChanges.value, [])
  assert.deepEqual(tokenUsage.value, { promptTokens: 0, completionTokens: 0, totalTokens: 0, callCount: 0, conversationTotal: 0 })
  assert.deepEqual(messages.value.map(message => message.role), ['user', 'assistant'])
  assert.equal(messages.value[1].content, '你好呀')
  assert.deepEqual(replayedEvents, [{ type: 'FINAL_DELTA', data: { delta: '你好呀' } }])
  assert.deepEqual(notifications, ['history-loaded'])
})

test('loads older history with the server cursor and prepends complete turns', async () => {
  const messageCalls = []
  const { state, messages } = createHarness({
    api: {
      agentMessages: async (projectId, conversationId, params) => {
        messageCalls.push({ projectId, conversationId, params })
        if (!params.beforeMessageId) {
          return { data: {
            events: [
              { messageId: 30, eventType: 'USER', content: '新问题', eventData: '{}' },
              { messageId: 31, eventType: 'FINAL_DELTA', eventData: JSON.stringify({ delta: '新回答' }) }
            ],
            hasMore: true,
            nextBeforeMessageId: 30
          } }
        }
        return { data: {
          events: [
            { messageId: 10, eventType: 'USER', content: '旧问题', eventData: '{}' },
            { messageId: 11, eventType: 'FINAL_DELTA', eventData: JSON.stringify({ delta: '旧回答' }) }
          ],
          hasMore: false,
          nextBeforeMessageId: 10
        } }
      }
    }
  })

  await state.loadConversationMessages('conversation-1')
  assert.equal(state.hasOlderMessages.value, true)

  const loaded = await state.loadOlderMessages()

  assert.equal(loaded, true)
  assert.equal(state.hasOlderMessages.value, false)
  assert.deepEqual(messageCalls, [
    { projectId: 42, conversationId: 'conversation-1', params: { limit: 20 } },
    { projectId: 42, conversationId: 'conversation-1', params: { beforeMessageId: 30, limit: 20 } }
  ])
  assert.deepEqual(messages.value.map(message => message.content), ['旧问题', '旧回答', '新问题', '新回答'])
})

test('forking a conversation refreshes its list and opens the branch', async () => {
  const calls = []
  const { state, currentAgentSession } = createHarness({
    api: {
      agentConversations: async () => ({ data: [{ conversationId: 'forked', title: '分支', createTime: '2026-07-12T00:00:00Z' }] }),
      agentMessages: async (projectId, conversationId) => {
        calls.push(['messages', conversationId])
        return { data: [] }
      },
      agentForkConversation: async (projectId, conversationId) => {
        calls.push(['fork', projectId, conversationId])
        return { code: 0, data: { conversationId: 'forked' } }
      }
    }
  })

  const result = await state.forkConversation({ conversationId: 'source' })

  assert.deepEqual(result, { success: true, conversationId: 'forked' })
  assert.deepEqual(calls, [['fork', 42, 'source'], ['messages', 'forked']])
  assert.equal(currentAgentSession.value.conversationId, 'forked')
})

test('deleting the active conversation clears only conversation state', async () => {
  const { state, messages, currentAgentSession, sessionChanges, tokenUsage } = createHarness()
  messages.value = [{ role: 'user', content: '待清除' }]
  currentAgentSession.value = { sessionId: 's1', conversationId: 'remove-me' }
  await state.loadConversations()

  await state.deleteConversation({ conversationId: 'remove-me' })

  assert.deepEqual(messages.value, [])
  assert.equal(currentAgentSession.value, null)
  assert.deepEqual(sessionChanges.value, [])
  assert.deepEqual(tokenUsage.value, { promptTokens: 0, completionTokens: 0, totalTokens: 0, callCount: 0, conversationTotal: 0 })
})

test('manual compaction forwards the selected model config and returns its asynchronous task identity', async () => {
  let request
  const { state } = createHarness({
    api: {
      agentCompactConversation: async (...args) => {
        request = args
        return { code: 0, data: { taskId: 88, status: 'queued' } }
      }
    }
  })

  const result = await state.compactConversation({ conversationId: 'compact-me' }, 17)

  assert.deepEqual(request, [42, 'compact-me', { modelConfigId: 17 }])
  assert.deepEqual(result, { success: true, taskId: 88, status: 'queued' })
})
