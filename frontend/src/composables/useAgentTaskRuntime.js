import { nextTick as vueNextTick } from 'vue'

export function isTerminalAgentTask(task) {
  return ['completed', 'failed', 'cancelled'].includes(String(task?.status || '').toLowerCase())
}

export function createTaskEventCursorStore(projectId, storage = globalThis.sessionStorage) {
  const key = taskId => `labex-agent:task-event-cursor:${projectId.value}:${taskId}`
  return {
    read(taskId) {
      if (!taskId || !storage) return null
      return storage.getItem(key(taskId))
    },
    save(taskId, eventId) {
      if (!taskId || eventId == null || !storage) return
      storage.setItem(key(taskId), String(eventId))
    },
    clear(taskId) {
      if (!taskId || !storage) return
      storage.removeItem(key(taskId))
    }
  }
}

export function useAgentTaskRuntime(options) {
  const {
    projectId,
    currentAgentSession,
    messages,
    agentLoading,
    api,
    subscribeAgent,
    disconnectSubscription,
    handleAgentEvent,
    reconcileRecoveredCommandApproval,
    createMessageTiming,
    stopMessageTimer,
    scrollDown,
    storage = globalThis.sessionStorage,
    wait = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds)),
    nextTick = vueNextTick
  } = options

  const cursors = createTaskEventCursorStore(projectId, storage)
  let subscriptionGeneration = 0
  let recoveryGeneration = 0

  function log(event, details = {}) {
    console.info('[AgentTaskRecovery]', event, { projectId: projectId.value, ...details })
  }

  function sequenceNumber(value) {
    const parsed = Number(value)
    return Number.isFinite(parsed) && parsed >= 0 ? parsed : 0
  }

  function ownsConversation(conversationId) {
    return !!conversationId && currentAgentSession.value?.conversationId === conversationId
  }

  function recordTaskEventCursor(taskId, eventId) {
    cursors.save(taskId, eventId)
  }

  function invalidate() {
    subscriptionGeneration++
    recoveryGeneration++
    disconnectSubscription()
  }

  function recoveredAssistantMessage(task) {
    return {
      role: 'assistant',
      content: '',
      thinking: task.currentStep || task.summary || '正在恢复 Agent 任务',
      _thinkingDisplay: task.currentStep || task.summary || '正在恢复 Agent 任务',
      _thinkingTimer: null,
      thinkingBlocks: [],
      toolCalls: [],
      plan: null,
      planJson: null,
      isStreaming: true,
      error: null,
      _nextOrder: 0,
      timestamp: Date.now(),
      taskId: task.taskId,
      timing: createMessageTiming()
    }
  }

  function assistantMessageForTask(task) {
    const existing = [...messages.value]
      .reverse()
      .find(message => message?.role === 'assistant' && Number(message.taskId) === Number(task.taskId))
    if (existing) return existing
    const message = recoveredAssistantMessage(task)
    messages.value.push(message)
    return message
  }

  async function recoverActiveTaskForConversation(conversationId) {
    if (!conversationId || !projectId.value) return false
    const generation = ++recoveryGeneration
    subscriptionGeneration++
    log('ACTIVE_TASK_RECOVERY_STARTED', { conversationId, generation })
    disconnectSubscription()
    const response = await api.agentActiveTask(projectId.value, conversationId)
    if (generation !== recoveryGeneration || !ownsConversation(conversationId)) {
      log('ACTIVE_TASK_RECOVERY_STALE_RESPONSE', { conversationId, generation })
      return false
    }

    const task = response?.data
    if (!task?.taskId || isTerminalAgentTask(task) || task.conversationId !== conversationId) {
      log('ACTIVE_TASK_RECOVERY_NONE', { conversationId, status: task?.status || 'none' })
      return false
    }

    currentAgentSession.value = { conversationId: task.conversationId, sessionId: task.sessionId }
    const assistantMsg = assistantMessageForTask(task)
    assistantMsg.taskId = task.taskId
    reconcileRecoveredCommandApproval(assistantMsg, task)
    const waitingForCommandApproval = String(task.status || '').toLowerCase() === 'waiting_approval'
    assistantMsg.isStreaming = !waitingForCommandApproval
    if (waitingForCommandApproval) stopMessageTimer(assistantMsg)
    if (assistantMsg.timing) assistantMsg.timing.taskId = task.taskId
    agentLoading.value = !waitingForCommandApproval
    void subscribeToTaskEvents(task, assistantMsg)
    return true
  }

  async function subscribeToTaskEvents(initialTask, assistantMsg) {
    let task = initialTask
    const generation = ++subscriptionGeneration
    try {
      while (generation === subscriptionGeneration && task?.taskId && !isTerminalAgentTask(task)) {
        if (!ownsConversation(task.conversationId)) return
        const storedCursor = cursors.read(task.taskId)
        const cursor = storedCursor == null ? sequenceNumber(task.lastEventSequence) : sequenceNumber(storedCursor)
        log('TASK_EVENT_SUBSCRIBE_STARTED', {
          taskId: task.taskId,
          conversationId: task.conversationId,
          afterSequence: cursor,
          cursorSource: storedCursor == null ? 'task_snapshot' : 'session_storage'
        })
        try {
          await subscribeAgent(projectId.value, task.taskId, {
            lastEventId: String(cursor),
            onEvent: event => {
              if (generation !== subscriptionGeneration || !ownsConversation(task.conversationId)) return
              cursors.save(task.taskId, event.eventId)
              handleAgentEvent(event, assistantMsg)
            }
          })
        } catch (error) {
          if (error?.name === 'AbortError' || generation !== subscriptionGeneration) return
          console.warn('Agent task subscription disconnected; reconnecting from durable cursor:', error)
        }
        if (generation !== subscriptionGeneration || !ownsConversation(task.conversationId)) return

        const active = (await api.agentActiveTask(projectId.value, task.conversationId))?.data || null
        if (!active || isTerminalAgentTask(active) || Number(active.taskId) !== Number(initialTask.taskId)) {
          assistantMsg.isStreaming = false
          stopMessageTimer(assistantMsg)
          cursors.clear(initialTask.taskId)
          await syncTaskTiming(assistantMsg)
          break
        }
        task = active
        await wait(500)
      }
    } catch (error) {
      if (generation === subscriptionGeneration && ownsConversation(initialTask?.conversationId)) {
        assistantMsg.error = '恢复 Agent 事件流失败：' + (error?.message || '未知错误')
        if (!assistantMsg.content) assistantMsg.content = assistantMsg.error
        assistantMsg.isStreaming = false
        stopMessageTimer(assistantMsg)
      }
    } finally {
      if (generation === subscriptionGeneration) {
        agentLoading.value = false
        await nextTick()
        scrollDown()
      }
    }
  }

  async function resumeTaskEventSubscription(taskId, assistantMsg, conversationId = currentAgentSession.value?.conversationId) {
    const task = (await api.agentActiveTask(projectId.value, conversationId))?.data
    if (!task || Number(task.taskId) !== Number(taskId) || task.conversationId !== conversationId) {
      throw new Error('Agent task is no longer active')
    }
    assistantMsg.taskId = task.taskId
    assistantMsg.isStreaming = true
    agentLoading.value = true
    void subscribeToTaskEvents(task, assistantMsg)
  }

  function applyTaskTiming(message, task) {
    if (!message || !task) return
    const existing = message.timing || createMessageTiming()
    message.timing = {
      ...existing,
      taskId: task.taskId || existing.taskId,
      activeElapsedMs: Number.isFinite(task.activeElapsedMs) ? task.activeElapsedMs : existing.activeElapsedMs,
      isRunning: message.isStreaming && !isTerminalAgentTask(task)
    }
  }

  async function fetchAgentTasks() {
    if (!projectId.value) return []
    const response = await api.agentTasks(projectId.value)
    return Array.isArray(response?.data) ? response.data : []
  }

  async function syncTaskTiming(message) {
    if (!message?.taskId) return
    for (let attempt = 0; attempt < 3; attempt++) {
      try {
        const task = (await fetchAgentTasks()).find(item => Number(item.taskId) === Number(message.taskId))
        if (!task) return
        applyTaskTiming(message, task)
        if (isTerminalAgentTask(task) || attempt === 2) return
      } catch (error) {
        console.warn('Failed to load agent task timing:', error)
        return
      }
      await wait(150)
    }
  }

  async function syncConversationTaskTimings(conversationId) {
    if (!conversationId) return
    try {
      const tasksById = new Map((await fetchAgentTasks())
        .filter(task => task.conversationId === conversationId)
        .map(task => [Number(task.taskId), task]))
      messages.value
        .filter(message => message.role === 'assistant' && message.taskId)
        .forEach(message => applyTaskTiming(message, tasksById.get(Number(message.taskId))))
    } catch (error) {
      console.warn('Failed to load conversation task timings:', error)
    }
  }

  return {
    recordTaskEventCursor,
    invalidate,
    recoverActiveTaskForConversation,
    subscribeToTaskEvents,
    resumeTaskEventSubscription,
    syncTaskTiming,
    syncConversationTaskTimings
  }
}