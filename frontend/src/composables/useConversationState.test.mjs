import assert from 'node:assert/strict'
import test from 'node:test'
import { ref } from 'vue'
import { createTokenUsageState } from './cacheTelemetryStatus.js'
import { reduceHistoryEvent } from './agentHistoryReducer.js'

const { useConversationState } = await import('./useConversationState.js')

function memoryStorage(initial = {}) {
  const values = new Map(Object.entries(initial))
  return {
    getItem: key => values.has(key) ? values.get(key) : null,
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: key => values.delete(key)
  }
}

function turn(taskId, userContent, answer, overrides = {}) {
  return {
    taskId,
    conversationId: 'conversation-1',
    sourceConversationId: 'conversation-1',
    inherited: false,
    sessionId: `session-${taskId}`,
    mode: 'build',
    status: 'completed',
    currentStep: '',
    summary: '',
    userContent,
    lastEventSequence: 2,
    activeElapsedMs: 17,
    events: [
      { eventId: taskId * 10 + 1, taskId, sequence: 1, state: 'running', eventType: 'THINK', data: { content: `think-${taskId}` } },
      { eventId: taskId * 10 + 2, taskId, sequence: 2, state: 'completed', eventType: 'FINAL', data: { content: answer } }
    ],
    runMessages: [{ messageId: taskId * 100, messageKey: 'assistant:final', sequence: 2, role: 'assistant', status: 'completed', content: answer }],
    parts: [{ partId: taskId * 1000, partKey: 'final', partType: 'text', status: 'completed', output: answer, sequence: 2 }],
    ...overrides
  }
}

function page(turns, overrides = {}) {
  return {
    projectionVersion: 'durable-task-history-v1',
    conversationId: 'conversation-1',
    turns,
    hasMore: false,
    nextBeforeTaskId: null,
    legacyMigrated: false,
    ...overrides
  }
}

function createHarness(overrides = {}) {
  const projectId = ref(42)
  const messages = ref([])
  const sessionChanges = ref([{ id: 'old-change' }])
  const tokenUsage = ref({ ...createTokenUsageState(), totalTokens: 17, callCount: 2, conversationTotal: 17 })
  const agentLoading = ref(true)
  const currentAgentSession = ref(null)
  const replayedEvents = []
  const notifications = []
  const api = {
    agentConversations: async () => ({ data: [
      { conversationId: 'older', title: 'Older conversation' },
      { conversationId: 'newer', title: 'Newer conversation' }
    ] }),
    agentConversationHistory: async (project, conversationId, params) => ({
      data: page([turn(7, 'Question', 'Answer')], { conversationId })
    }),
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
    replayHistoryEvent(type, data, message) {
      replayedEvents.push({ type, data })
      if (overrides.replayHistoryEvent) {
        overrides.replayHistoryEvent(type, data, message)
        return
      }
      if (type === 'FINAL') message.content = data.content || ''
      if (type === 'THINK') message.thinkingBlocks.push({ content: data.content || '' })
    },
    onHistoryLoaded() { notifications.push('history-loaded') },
    createSessionId: () => 'generated-session',
    storage: overrides.storage || memoryStorage()
  })
  return { state, messages, sessionChanges, tokenUsage, agentLoading, currentAgentSession, replayedEvents, notifications }
}

test('loads conversations in server order and exposes the selected title', async () => {
  const { state, currentAgentSession } = createHarness()
  await state.loadConversations()
  assert.deepEqual(state.conversations.value.map(item => item.conversationId), ['older', 'newer'])
  currentAgentSession.value = { conversationId: 'older' }
  assert.equal(state.currentSessionName.value, 'Older conversation')
})

test('keeps a fresh workspace on a new conversation instead of selecting the most recent history', async () => {
  const { state } = createHarness()
  await state.loadConversations()

  assert.equal(state.resolveStartupConversation(), null)
})

test('restores the last selected conversation after refresh and clears it for an explicit new session', async () => {
  const storage = memoryStorage({ 'labex-agent:selected-conversation:42': 'conversation-b' })
  const { state } = createHarness({
    storage,
    api: {
      agentConversations: async () => ({ data: [
        { conversationId: 'conversation-a', title: 'Conversation A' },
        { conversationId: 'conversation-b', title: 'Conversation B' }
      ] }),
      agentConversationHistory: async () => ({ data: page([], { conversationId: 'conversation-b' }) })
    }
  })
  await state.loadConversations()
  assert.equal(state.resolveStartupConversation()?.conversationId, 'conversation-b')
  await state.loadConversationMessages('conversation-b')
  assert.equal(storage.getItem('labex-agent:selected-conversation:42'), 'conversation-b')
  state.createNewSession()
  assert.equal(storage.getItem('labex-agent:selected-conversation:42'), null)
})

test('hydrates durable task turns with the same event reducer and authoritative message/part snapshots', async () => {
  const { state, messages, sessionChanges, tokenUsage, agentLoading, currentAgentSession, replayedEvents, notifications } = createHarness()
  await state.loadConversationMessages('conversation-1')
  assert.deepEqual(currentAgentSession.value, { sessionId: 'generated-session', conversationId: 'conversation-1' })
  assert.equal(agentLoading.value, false)
  assert.deepEqual(messages.value.map(message => message.role), ['user', 'assistant'])
  assert.equal(messages.value[0].content, 'Question')
  assert.equal(messages.value[1].content, 'Answer')
  assert.equal(messages.value[1].taskId, 7)
  assert.equal(messages.value[1].runState, 'completed')
  assert.equal(messages.value[1].isStreaming, false)
  assert.equal(messages.value[1].runMessages[0].messageKey, 'assistant:final')
  assert.equal(messages.value[1].runParts[0].partKey, 'final')
  assert.deepEqual(replayedEvents.map(item => item.type), ['THINK', 'FINAL'])
  assert.deepEqual(sessionChanges.value, [])
  assert.deepEqual(tokenUsage.value, createTokenUsageState())
  assert.deepEqual(notifications, ['history-loaded'])
})

test('loads older durable task pages with beforeTaskId and preserves chronological task order', async () => {
  const calls = []
  const { state, messages } = createHarness({
    api: {
      agentConversationHistory: async (projectId, conversationId, params) => {
        calls.push({ projectId, conversationId, params })
        if (!params.beforeTaskId) {
          return { data: page([turn(30, 'New question', 'New answer')], { hasMore: true, nextBeforeTaskId: 30 }) }
        }
        return { data: page([turn(10, 'Old question', 'Old answer')], { hasMore: false }) }
      }
    }
  })
  await state.loadConversationMessages('conversation-1')
  assert.equal(state.hasOlderMessages.value, true)
  await state.loadOlderMessages()
  assert.deepEqual(messages.value.filter(message => message.role === 'user').map(message => message.content), ['Old question', 'New question'])
  assert.deepEqual(calls, [
    { projectId: 42, conversationId: 'conversation-1', params: { limit: 20 } },
    { projectId: 42, conversationId: 'conversation-1', params: { beforeTaskId: 30, limit: 20 } }
  ])
})

test('does not infer a terminal tool status from history loading alone', async () => {
  const waiting = turn(9, 'run', '', {
    status: 'waiting_approval',
    events: [{ eventId: 91, taskId: 9, sequence: 1, state: 'waiting_approval', eventType: 'RUN_STATE_WAITING_APPROVAL', data: { taskId: 9 } }],
    runMessages: [],
    parts: [{ partId: 90, partKey: 'tool:call-9', partType: 'tool', status: 'waiting_approval', toolCallId: 'call-9', tool: 'run_command', input: '{}', output: '', sequence: 1 }]
  })
  const { state, messages } = createHarness({ api: {
    agentConversationHistory: async () => ({ data: page([waiting]) })
  } })
  await state.loadConversationMessages('conversation-1')
  const assistant = messages.value[1]
  assert.equal(assistant.runState, 'waiting_approval')
  assert.equal(assistant.isStreaming, false)
  assert.equal(assistant.toolCalls[0].status, 'waiting_approval')
})


test('refresh keeps reasoning and tool cards in durable event order while preserving verification failure', async () => {
  const result = 'file updated\n\n[Post-edit hooks]\n- status=FAIL\n- action_required: run compile'
  const refreshedTurn = turn(12, 'fix comments', '', {
    lastEventSequence: 5,
    events: [
      { eventId: 121, taskId: 12, sequence: 1, state: 'running', eventType: 'THINK', data: { content: 'inspect existing implementation' } },
      { eventId: 122, taskId: 12, sequence: 2, state: 'running', eventType: 'TOOL_CALL', data: { tool: 'edit_file', toolCallId: 'call-edit', arguments: { file_path: 'CommentController.java' } } },
      { eventId: 123, taskId: 12, sequence: 3, state: 'running', eventType: 'OBSERVE', data: { success: true, result } },
      { eventId: 124, taskId: 12, sequence: 4, state: 'running', eventType: 'THINK', data: { content: 'diagnostics failed; continue repairing' } },
      { eventId: 125, taskId: 12, sequence: 5, state: 'failed', eventType: 'RUN_STATE_FAILED', data: { taskId: 12 } }
    ],
    runMessages: [],
    parts: [
      { partId: 1201, partKey: 'tool:call-edit', partType: 'tool', status: 'completed', toolCallId: 'call-edit', tool: 'edit_file', input: '{"file_path":"CommentController.java"}', output: result, sequence: 1 },
      { partId: 1202, partKey: 'reasoning:1', partType: 'reasoning', status: 'completed', output: 'inspect existing implementation', sequence: 1 },
      { partId: 1203, partKey: 'reasoning:4', partType: 'reasoning', status: 'completed', output: 'diagnostics failed; continue repairing', sequence: 4 }
    ]
  })
  const { state, messages } = createHarness({
    replayHistoryEvent: (type, data, message) => reduceHistoryEvent(type, data, message),
    api: { agentConversationHistory: async () => ({ data: page([refreshedTurn]) }) }
  })

  await state.loadConversationMessages('conversation-1')

  const assistant = messages.value[1]
  const timeline = [
    ...assistant.thinkingBlocks.map(block => ({ type: 'thinking', content: block.content, order: block._order })),
    ...assistant.toolCalls.map(call => ({ type: 'tool', content: call.toolCallId, order: call._order }))
  ].sort((left, right) => left.order - right.order)
  assert.deepEqual(timeline.map(item => item.type), ['thinking', 'tool', 'thinking'])
  assert.deepEqual(timeline.map(item => item.content), ['inspect existing implementation', 'call-edit', 'diagnostics failed; continue repairing'])
  assert.equal(assistant.toolCalls[0].durableStatus, 'completed')
  assert.equal(assistant.toolCalls[0].status, 'error')
  assert.equal(assistant.toolCalls[0].verificationStatus, 'FAIL')
})

test('forking a conversation refreshes its list and opens the durable branch history', async () => {
  const calls = []
  const { state, currentAgentSession } = createHarness({
    api: {
      agentConversations: async () => ({ data: [{ conversationId: 'forked', title: 'Fork' }] }),
      agentConversationHistory: async (projectId, conversationId) => {
        calls.push(['history', conversationId])
        return { data: page([], { conversationId }) }
      },
      agentForkConversation: async (projectId, conversationId) => {
        calls.push(['fork', projectId, conversationId])
        return { code: 0, data: { conversationId: 'forked' } }
      }
    }
  })
  const result = await state.forkConversation({ conversationId: 'source' })
  assert.deepEqual(result, { success: true, conversationId: 'forked' })
  assert.deepEqual(calls, [['fork', 42, 'source'], ['history', 'forked']])
  assert.equal(currentAgentSession.value.conversationId, 'forked')
})

test('deleting the active conversation clears only conversation state', async () => {
  const { state, messages, currentAgentSession, sessionChanges, tokenUsage } = createHarness()
  messages.value = [{ role: 'user', content: 'To clear' }]
  currentAgentSession.value = { sessionId: 's1', conversationId: 'remove-me' }
  await state.loadConversations()
  await state.deleteConversation({ conversationId: 'remove-me' })
  assert.deepEqual(messages.value, [])
  assert.equal(currentAgentSession.value, null)
  assert.deepEqual(sessionChanges.value, [])
  assert.deepEqual(tokenUsage.value, createTokenUsageState())
})

test('manual compaction forwards the selected model config and returns its asynchronous task identity', async () => {
  let request
  const { state } = createHarness({ api: {
    agentCompactConversation: async (...args) => {
      request = args
      return { code: 0, data: { taskId: 88, status: 'queued' } }
    }
  } })
  const result = await state.compactConversation({ conversationId: 'compact-me' }, 17)
  assert.deepEqual(request, [42, 'compact-me', { modelConfigId: 17 }])
  assert.deepEqual(result, { success: true, taskId: 88, status: 'queued' })
})
