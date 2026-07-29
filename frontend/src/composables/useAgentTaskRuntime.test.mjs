import assert from 'node:assert/strict'
import test from 'node:test'
import { createTaskEventCursorStore, useAgentTaskRuntime } from './useAgentTaskRuntime.js'

function deferred() {
  let resolve
  const promise = new Promise(done => { resolve = done })
  return { promise, resolve }
}

function memoryStorage() {
  const values = new Map()
  return {
    getItem: key => values.has(key) ? values.get(key) : null,
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: key => values.delete(key),
    keys: values
  }
}

function harness(overrides = {}) {
  const projectId = { value: 42 }
  const currentAgentSession = { value: { conversationId: 'conversation-a', sessionId: 'session-a' } }
  const messages = { value: [] }
  const agentLoading = { value: false }
  const events = []
  const subscriptions = []
  const api = overrides.api || {
    agentActiveTask: async () => ({ data: null }),
    agentTasks: async () => ({ data: [] })
  }
  const runtime = useAgentTaskRuntime({
    projectId,
    currentAgentSession,
    messages,
    agentLoading,
    api,
    subscribeAgent: async (pid, taskId, options) => {
      subscriptions.push({ pid, taskId, options })
    },
    disconnectSubscription: () => events.push('disconnect'),
    handleAgentEvent: event => events.push(event.type),
    reconcileRecoveredCommandApproval: () => {},
    createMessageTiming: () => ({ taskId: null, startedAt: 1, activeElapsedMs: null, isRunning: true }),
    stopMessageTimer: message => { if (message?.timing) message.timing.isRunning = false },
    scrollDown: () => {},
    storage: overrides.storage || memoryStorage(),
    wait: async () => {},
    nextTick: async () => {}
  })
  return { runtime, projectId, currentAgentSession, messages, agentLoading, events, subscriptions }
}

test('task event cursors are isolated by project and task', () => {
  const storage = memoryStorage()
  const first = createTaskEventCursorStore({ value: 7 }, storage)
  const second = createTaskEventCursorStore({ value: 8 }, storage)

  first.save(71, 9)
  second.save(71, 12)

  assert.equal(first.read(71), '9')
  assert.equal(second.read(71), '12')
  first.clear(71)
  assert.equal(first.read(71), null)
  assert.equal(second.read(71), '12')
})


test('direct stream events persist their durable task cursor through the runtime boundary', () => {
  const storage = memoryStorage()
  const state = harness({ storage })

  state.runtime.recordTaskEventCursor(71, 19)

  assert.equal(storage.getItem('labex-agent:task-event-cursor:42:71'), '19')
})

test('stale active-task recovery cannot attach to a newly selected conversation', async () => {
  const pending = deferred()
  const state = harness({ api: {
    agentActiveTask: async () => pending.promise,
    agentTasks: async () => ({ data: [] })
  } })

  const recovery = state.runtime.recoverActiveTaskForConversation('conversation-a')
  state.currentAgentSession.value = { conversationId: 'conversation-b', sessionId: 'session-b' }
  pending.resolve({ data: { taskId: 71, conversationId: 'conversation-a', sessionId: 'session-a', status: 'running' } })

  assert.equal(await recovery, false)
  assert.equal(state.subscriptions.length, 0)
  assert.equal(state.messages.value.length, 0)
})

test('old task events cannot attach after the same conversation switches to a new session', async () => {
  let onEvent
  const state = harness()
  state.runtime.subscribeToTaskEvents({
    taskId: 71,
    conversationId: 'conversation-a',
    sessionId: 'session-a',
    status: 'running',
    lastEventSequence: 0
  }, { role: 'assistant', taskId: 71, isStreaming: true })
  await Promise.resolve()
  onEvent = state.subscriptions[0]?.options?.onEvent
  assert.equal(typeof onEvent, 'function')

  state.currentAgentSession.value = { conversationId: 'conversation-a', sessionId: 'session-b' }
  onEvent({ type: 'THINK', eventId: 9, data: {} })

  assert.deepEqual(state.events, [])
})

test('active-task recovery hydrates durable tool call states after refresh', async () => {
  let activeTaskCalls = 0
  const recoveredTask = {
    taskId: 71,
    conversationId: 'conversation-a',
    sessionId: 'session-a',
    status: 'waiting_environment',
    currentStep: '等待环境恢复',
    toolCalls: [{
      toolCallId: 'call-1',
      tool: 'run_tests',
      arguments: { command: 'mvn compile' },
      status: 'environment_blocked',
      detail: 'failure_code=ENVIRONMENT_BLOCKED'
    }]
  }
  const state = harness({ api: {
    agentActiveTask: async () => ({ data: activeTaskCalls++ === 0 ? recoveredTask : { ...recoveredTask, status: 'completed' } }),
    agentTasks: async () => ({ data: [] })
  } })

  assert.equal(await state.runtime.recoverActiveTaskForConversation('conversation-a'), true)
  const call = state.messages.value[0].toolCalls[0]
  assert.equal(call.toolCallId, 'call-1')
  assert.equal(call.status, 'warning')
  assert.equal(call.durableStatus, 'environment_blocked')
  assert.equal(state.agentLoading.value, false)
})

test('active-task recovery hydrates a pending question reply card', async () => {
  const recoveredTask = {
    taskId: 72,
    conversationId: 'conversation-a',
    sessionId: 'session-a',
    status: 'waiting_user',
    currentStep: 'Waiting for user input',
    toolCalls: [],
    parts: [],
    pendingInteraction: {
      interactionId: 'request-72',
      requestId: 'request-72',
      taskId: 72,
      conversationId: 'conversation-a',
      sessionId: 'session-a',
      interactionType: 'question',
      status: 'waiting',
      question: 'Continue?',
      summary: 'Please choose',
      options: ['Continue', 'Stop']
    }
  }
  let activeTaskCalls = 0
  const state = harness({ api: {
    agentActiveTask: async () => ({ data: activeTaskCalls++ === 0 ? recoveredTask : { ...recoveredTask, status: 'completed' } }),
    agentTasks: async () => ({ data: [] })
  } })

  assert.equal(await state.runtime.recoverActiveTaskForConversation('conversation-a'), true)
  const call = state.messages.value[0].toolCalls[0]
  assert.equal(call.status, 'waiting_user')
  assert.equal(call.questionRequest.requestId, 'request-72')
  assert.deepEqual(call.questionRequest.options, ['Continue', 'Stop'])
})

test('explicit invalidation immediately releases loading ownership from the detached conversation', () => {
  const state = harness()
  state.agentLoading.value = true

  state.runtime.invalidate()

  assert.equal(state.agentLoading.value, false)
})

test('explicit invalidation disconnects transport and invalidates pending recovery', async () => {
  const pending = deferred()
  const state = harness({ api: {
    agentActiveTask: async () => pending.promise,
    agentTasks: async () => ({ data: [] })
  } })

  const recovery = state.runtime.recoverActiveTaskForConversation('conversation-a')
  state.runtime.invalidate()
  pending.resolve({ data: { taskId: 71, conversationId: 'conversation-a', sessionId: 'session-a', status: 'running' } })

  assert.equal(await recovery, false)
  assert.deepEqual(state.events, ['disconnect', 'disconnect'])
})