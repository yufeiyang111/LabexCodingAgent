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

test('active-task recovery prefers durable parts over a stale compatibility toolCalls snapshot', async () => {
  let activeTaskCalls = 0
  const recoveredTask = {
    taskId: 71,
    conversationId: 'conversation-a',
    sessionId: 'session-a',
    status: 'waiting_environment',
    currentStep: 'Waiting for environment recovery',
    parts: [{
      partId: 91,
      partKey: 'tool:call-1',
      partType: 'tool',
      toolCallId: 'call-1',
      tool: 'run_tests',
      input: '{"command":"mvn compile"}',
      output: 'failure_code=ENVIRONMENT_BLOCKED',
      status: 'environment_blocked',
      sequence: 2
    }],
    toolCalls: [{
      toolCallId: 'call-1',
      tool: 'run_tests',
      arguments: { command: 'mvn compile' },
      status: 'waiting_approval',
      detail: 'stale legacy artifact state'
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
  assert.deepEqual(call.args, { command: 'mvn compile' })
  assert.equal(call.result, 'failure_code=ENVIRONMENT_BLOCKED')
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

test('active-task recovery hydrates pending permission and network approval cards', async () => {
  const pendingInteractions = [
    {
      taskId: 73,
      interactionId: 'permission-73',
      requestId: 'permission-73',
      interactionType: 'permission',
      status: 'waiting',
      toolName: 'write_file',
      summary: '允许写入文件？'
    },
    {
      taskId: 74,
      interactionId: 'network-74',
      requestId: 'network-74',
      interactionType: 'network',
      status: 'waiting',
      toolName: 'run_tests',
      summary: '允许当前命令联网？',
      request: 'mvn compile'
    }
  ]

  for (const pendingInteraction of pendingInteractions) {
    let activeTaskCalls = 0
    const recoveredTask = {
      taskId: pendingInteraction.taskId,
      conversationId: 'conversation-a',
      sessionId: 'session-a',
      status: 'waiting_approval',
      toolCalls: [],
      parts: [],
      pendingInteraction
    }
    const state = harness({ api: {
      agentActiveTask: async () => ({ data: activeTaskCalls++ === 0 ? recoveredTask : { ...recoveredTask, status: 'completed' } }),
      agentTasks: async () => ({ data: [] })
    } })

    assert.equal(await state.runtime.recoverActiveTaskForConversation('conversation-a'), true)
    const call = state.messages.value[0].toolCalls[0]
    assert.equal(call.status, 'waiting_approval')
    assert.equal(call.permissionRequest?.requestId || call.networkRequest?.requestId, pendingInteraction.requestId)
  }
})

test('approval resume waits for the same durable task to become active', async () => {
  let activeTaskCalls = 0
  const task = {
    taskId: 75,
    conversationId: 'conversation-a',
    sessionId: 'session-a',
    status: 'waiting_approval',
    lastEventSequence: 4,
    toolCalls: [],
    parts: [],
    pendingInteraction: {
      interactionId: 'permission-75', requestId: 'permission-75', taskId: 75,
      interactionType: 'permission', status: 'waiting', toolName: 'read_file', summary: '等待权限批准'
    }
  }
  const state = harness({ api: {
    agentActiveTask: async () => ({ data: activeTaskCalls++ === 0 ? null : activeTaskCalls === 2 ? task : { ...task, status: 'completed' } }),
    agentTasks: async () => ({ data: [] })
  } })
  const assistant = { role: 'assistant', taskId: 75, isStreaming: false, timing: { isRunning: false } }

  await state.runtime.resumeTaskEventSubscription(75, assistant)
  await Promise.resolve()

  assert.equal(activeTaskCalls >= 2, true)
  assert.equal(state.subscriptions[0].taskId, 75)
  assert.equal(assistant.toolCalls[0].permissionRequest.requestId, 'permission-75')
})

test('interaction resume resolves by durable task id when the UI session identity is temporarily empty', async () => {
  const task = {
    taskId: 79,
    conversationId: 'conversation-a',
    sessionId: 'session-a',
    status: 'waiting_approval',
    lastEventSequence: 6,
    toolCalls: [],
    parts: [],
    pendingInteraction: {
      interactionId: 'permission-79', requestId: 'permission-79', taskId: 79,
      conversationId: 'conversation-a', sessionId: 'session-a',
      interactionType: 'permission', status: 'waiting', toolName: 'run_tests', summary: '运行测试'
    }
  }
  const activeTaskCalls = []
  const state = harness({ api: {
    agentActiveTask: async (_projectId, conversationId) => {
      activeTaskCalls.push(conversationId)
      return { data: { ...task, status: 'completed', pendingInteraction: null } }
    },
    agentTask: async (_projectId, taskId) => {
      assert.equal(taskId, 79)
      return { data: task }
    },
    agentTasks: async () => ({ data: [] })
  } })
  state.currentAgentSession.value = null
  const assistant = { role: 'assistant', taskId: 79, conversationId: null, isStreaming: false, timing: { isRunning: false } }

  await state.runtime.resumeTaskEventSubscription(79, assistant)
  await Promise.resolve()

  assert.deepEqual(activeTaskCalls, ['conversation-a'])
  assert.deepEqual(state.currentAgentSession.value, { conversationId: 'conversation-a', sessionId: 'session-a' })
  assert.equal(assistant.conversationId, 'conversation-a')
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

test('active task recovery does not restore a waiting card after the task entered recovery', async () => {
  const recoveringTask = {
    taskId: 76,
    conversationId: 'conversation-a',
    sessionId: 'session-a',
    status: 'recovering',
    lastEventSequence: 24,
    pendingInteraction: {
      interactionId: 'interaction-76',
      requestId: 'interaction-76',
      interactionType: 'question',
      status: 'waiting',
      taskId: 76,
      conversationId: 'conversation-a',
      sessionId: 'session-a',
      toolCallId: 'question-call-76',
      requestPayload: { question: 'Continue?', summary: 'Choose next step' }
    }
  }
  let activeTaskCalls = 0
  const state = harness({ api: {
    agentActiveTask: async () => ({ data: ++activeTaskCalls === 1
      ? recoveringTask
      : { ...recoveringTask, status: 'completed', pendingInteraction: null } }),
    agentTasks: async () => ({ data: [] })
  } })
  state.messages.value.push({
    role: 'assistant', taskId: 76, toolCalls: [
      {
        name: 'question',
        toolCallId: 'question-call-76',
        status: 'waiting_user',
        questionRequest: { requestId: 'interaction-76', question: 'Continue?' }
      }
    ], _nextOrder: 1, timing: {}
  })

  assert.equal(await state.runtime.recoverActiveTaskForConversation('conversation-a'), true)

  const waitingCards = state.messages.value[0].toolCalls.filter(call => call.status === 'waiting_user')
  assert.equal(waitingCards.length, 0)
  assert.equal(state.messages.value[0].toolCalls[0].durableStatus, 'resuming')
})

test('active task recovery collapses duplicate cards for one pending durable interaction', async () => {
  let activeTaskCalls = 0
  const waitingTask = {
    taskId: 75,
    conversationId: 'conversation-a',
    sessionId: 'session-a',
    status: 'waiting_user',
    lastEventSequence: 23,
    pendingInteraction: {
      interactionId: 'interaction-75',
      requestId: 'interaction-75',
      interactionType: 'question',
      status: 'waiting',
      taskId: 75,
      conversationId: 'conversation-a',
      sessionId: 'session-a',
      toolCallId: 'question-call',
      requestPayload: { question: 'Continue?', summary: 'Choose next step' }
    }
  }
  const state = harness({ api: {
    agentActiveTask: async () => ({ data: ++activeTaskCalls === 1
      ? waitingTask
      : { ...waitingTask, status: 'completed', pendingInteraction: null } }),
    agentTasks: async () => ({ data: [] })
  } })
  state.messages.value.push({
    role: 'assistant', taskId: 75, toolCalls: [
      { name: 'question', toolCallId: 'question-call', status: 'waiting_user' },
      { name: 'question', status: 'waiting_user', questionRequest: { requestId: 'interaction-75' } }
    ], _nextOrder: 2, timing: {}
  })

  assert.equal(await state.runtime.recoverActiveTaskForConversation('conversation-a'), true)

  const cards = state.messages.value[0].toolCalls.filter(call =>
    call.questionRequest?.requestId === 'interaction-75' && call.status === 'waiting_user')
  assert.equal(cards.length, 1)
  assert.equal(cards[0].toolCallId, 'question-call')
})

test('active task recovery keeps a submitted interaction non-actionable while the backend snapshot is still waiting', async () => {
  const waitingTask = {
    taskId: 77,
    conversationId: 'conversation-a',
    sessionId: 'session-a',
    status: 'waiting_user',
    lastEventSequence: 24,
    pendingInteraction: {
      interactionId: 'interaction-77',
      requestId: 'interaction-77',
      interactionType: 'question',
      status: 'waiting',
      taskId: 77,
      conversationId: 'conversation-a',
      sessionId: 'session-a',
      toolCallId: 'question-call-77',
      requestPayload: { question: 'Continue?', summary: 'Choose next step' }
    }
  }
  let activeTaskCalls = 0
  const state = harness({ api: {
    agentActiveTask: async () => ({ data: ++activeTaskCalls === 1
      ? waitingTask
      : { ...waitingTask, status: 'completed', pendingInteraction: null } }),
    agentTasks: async () => ({ data: [] })
  } })
  state.messages.value.push({
    role: 'assistant', taskId: 77, toolCalls: [
      {
        name: 'question',
        toolCallId: 'question-call-77',
        status: 'running',
        interactionStatus: 'resuming',
        questionRequest: { requestId: 'interaction-77', question: 'Continue?' }
      }
    ], _nextOrder: 1, timing: {}
  })

  assert.equal(await state.runtime.recoverActiveTaskForConversation('conversation-a'), true)

  assert.equal(state.messages.value[0].toolCalls[0].status, 'running')
  assert.equal(state.messages.value[0].toolCalls[0].durableStatus, 'resuming')
})
