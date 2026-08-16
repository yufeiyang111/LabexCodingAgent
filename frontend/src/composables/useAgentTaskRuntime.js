import { attachDurableInteraction, resolveDurableInteraction } from './agentInteractionProjection.js'
import { applyRunMessageSnapshot, applyRunPartSnapshot } from './agentRunPartState.js'
import { createAgentRuntimeIndex } from './agentRuntimeStore.js'
import { isRecoverableAgentRunState, isTerminalAgentRunState, normalizeAgentRunState } from './agentRunState.js'
import { DIRECT_TERMINAL_TRANSCRIPT_RECOVERY } from '../constants/agentTaskRuntime.js'
import { nextTick as vueNextTick } from 'vue'

export function isTerminalAgentTask(task) {
  return isTerminalAgentRunState(task?.status)
}

function durableEventSequence(value) {
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : null
}

export function createTaskEventCursorStore(projectId, storage = globalThis.sessionStorage) {
  const key = taskId => `labex-agent:task-event-cursor:${projectId.value}:${taskId}`
  return {
    read(taskId) {
      if (!taskId || !storage) return null
      return storage.getItem(key(taskId))
    },
    save(taskId, eventId) {
      if (!taskId || eventId == null || !storage) return false
      const next = durableEventSequence(eventId)
      if (next == null) return false
      const current = durableEventSequence(storage.getItem(key(taskId)))
      if (current != null && next <= current) return false
      storage.setItem(key(taskId), String(eventId))
      return true
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
    reloadConversationHistory,
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
    return durableEventSequence(value) ?? 0
  }

  function ownsConversation(conversationId) {
    return !!conversationId && currentAgentSession.value?.conversationId === conversationId
  }

  function ownsTaskIdentity(task) {
    if (!task || !ownsConversation(task.conversationId)) return false
    const activeSessionId = currentAgentSession.value?.sessionId
    return !activeSessionId || !task.sessionId || activeSessionId === task.sessionId
  }

  function recordTaskEventCursor(taskId, eventId) {
    cursors.save(taskId, eventId)
  }

  function invalidate() {
    subscriptionGeneration++
    recoveryGeneration++
    disconnectSubscription()
    agentLoading.value = false
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
      modelSteps: [],
      plan: null,
      planJson: null,
      hasDurableFinal: false,
      isStreaming: true,
      error: null,
      _nextOrder: 0,
      timestamp: Date.now(),
      taskId: task.taskId,
      runState: normalizeAgentRunState(task.status),
      timing: createMessageTiming()
    }
  }

  function assistantMessageForTask(task) {
    // 归一化索引是 messages 的派生只读视图；消息数组仍是唯一事实源。
    const existing = createAgentRuntimeIndex(messages.value).assistantMessageForTask(task.taskId)
    if (existing) return existing
    const message = recoveredAssistantMessage(task)
    messages.value.push(message)
    return message
  }

  function reconcileRecoveredToolCalls(message, task) {
    if (!message) return
    applyRunMessageSnapshot(message, task?.runMessages)
    applyRunPartSnapshot(message, task?.parts)
    const pending = task?.pendingInteraction
    if (pending?.status !== 'waiting') return
    const request = {
      ...pending,
      ...(pending.requestPayload || {}),
      requestId: pending.requestId || pending.interactionId,
      taskId: pending.taskId || task.taskId,
      conversationId: pending.conversationId || task.conversationId,
      sessionId: pending.sessionId || task.sessionId
    }
    const pendingRequestId = String(request.requestId || request.interactionId || '')
    const resumingCall = message.toolCalls?.find(call => {
      if (call?.interactionStatus !== 'resuming' || !pendingRequestId) return false
      return ['questionRequest', 'permissionRequest', 'networkRequest'].some(field =>
        String(call[field]?.requestId || call[field]?.interactionId || '') === pendingRequestId
      )
    })
    if (resumingCall) {
      // 用户已经提交交互，恢复订阅期间后端快照可能短暂仍为 waiting；不能把已提交的卡片重新渲染成可操作状态。
      resolveDurableInteraction(message, request)
      return
    }
    const taskStatus = String(task?.status || '').toLowerCase()
    if (!['waiting_user', 'waiting_approval'].includes(taskStatus)) {
      // 恢复队列已接管任务时，交互行可能仍短暂保持 waiting；状态机优先，不能把旧审批卡重新渲染出来。
      resolveDurableInteraction(message, request)
      return
    }
    attachDurableInteraction(message, pending.interactionType, request)
  }

  function needsTerminalHistoryReconciliation() {
    const latestTaskMessage = [...messages.value].reverse().find(message =>
      message?.role === 'assistant'
        && (message.taskId || message.completionEvidence || (message.toolCalls || []).length > 0)
    )
    if (!latestTaskMessage) return false
    const runState = String(latestTaskMessage.runState || '').toLowerCase()
    if (!['completed', 'failed', 'cancelled'].includes(runState)) return true
    // 终态与会话 FINAL 分属不同持久化投影；刷新可能先读到 completed，仍必须补齐缺失的最终回答。
    return runState === 'completed'
      && !String(latestTaskMessage.content || '').trim()
      && !latestTaskMessage.error
  }

  async function reconcileTerminalConversationHistory(conversationId, generation, status, force = false) {
    if (typeof reloadConversationHistory !== 'function'
        || (!force && !needsTerminalHistoryReconciliation())) return false
    if (generation !== recoveryGeneration || !ownsConversation(conversationId)) return false
    log('TERMINAL_HISTORY_RECONCILIATION_STARTED', { conversationId, generation, status })
    try {
      const reloaded = await reloadConversationHistory(conversationId)
      if (generation !== recoveryGeneration || !ownsConversation(conversationId) || reloaded === false) return false
      await syncConversationTaskTimings(conversationId)
      log('TERMINAL_HISTORY_RECONCILIATION_COMPLETED', { conversationId, generation, status })
      return true
    } catch (error) {
      console.warn('Failed to reconcile terminal Agent conversation history:', error)
      return false
    }
  }

  function latestTaskMessageForConversation() {
    return [...messages.value].reverse().find(message =>
      message?.role === 'assistant' && message.taskId
    ) || null
  }

  async function hydrateTerminalTaskProjection(conversationId, generation) {
    if (typeof api.agentTask !== 'function') return false
    const historicalMessage = latestTaskMessageForConversation()
    if (!historicalMessage?.taskId) return false
    try {
      const response = await api.agentTask(projectId.value, historicalMessage.taskId)
      if (generation !== recoveryGeneration || !ownsConversation(conversationId)) return false
      const task = response?.data
      if (!task?.taskId || task.conversationId !== conversationId || !isTerminalAgentTask(task)) return false
      const message = assistantMessageForTask(task)
      reconcileRecoveredToolCalls(message, task)
      reconcileRecoveredCommandApproval(message, task)
      message.runState = normalizeAgentRunState(task.status)
      message.isStreaming = false
      stopMessageTimer(message)
      log('TERMINAL_TASK_PROJECTION_HYDRATED', {
        conversationId, taskId: task.taskId, status: task.status, generation
      })
      return true
    } catch (error) {
      console.warn('Failed to hydrate terminal Agent task projection:', error)
      return false
    }
  }

  function hasDurableFinalMessage(task) {
    return Array.isArray(task?.runMessages)
      && task.runMessages.some(message => message?.messageKey === 'assistant:final')
  }

  /**
   * 首次直连流已经结束、但浏览器漏收 FINAL 时，从同一 task 的 durable transcript 补齐可见答复。
   * 不读取 AgentTask.summary；最终文本仍只来自 Run Message / Part 投影。
   */
  async function reconcileDirectTerminalTask(assistantMsg) {
    if (!assistantMsg?.taskId || !projectId.value || typeof api.agentTask !== 'function') return false
    if (assistantMsg.hasDurableFinal === true || assistantMsg.error) return false

    const conversationId = assistantMsg.conversationId || currentAgentSession.value?.conversationId
    if (!conversationId || !ownsConversation(conversationId)) return false

    for (let attempt = 0; attempt < DIRECT_TERMINAL_TRANSCRIPT_RECOVERY.maxAttempts; attempt++) {
      try {
        const response = await api.agentTask(projectId.value, assistantMsg.taskId)
        if (!ownsConversation(conversationId)) return false
        const task = response?.data
        if (!task?.taskId || task.conversationId !== conversationId) return false
        if (currentAgentSession.value?.sessionId && task.sessionId
            && task.sessionId !== currentAgentSession.value.sessionId) return false

        assistantMsg.taskId = task.taskId
        assistantMsg.conversationId = task.conversationId
        reconcileRecoveredToolCalls(assistantMsg, task)
        reconcileRecoveredCommandApproval(assistantMsg, task)
        assistantMsg.runState = normalizeAgentRunState(task.status)
        if (!isTerminalAgentTask(task)) {
          const paused = isRecoverableAgentRunState(assistantMsg.runState)
          assistantMsg.isStreaming = !paused
          if (assistantMsg.timing) {
            assistantMsg.timing.taskId = task.taskId
            assistantMsg.timing.isRunning = !paused
          }
          if (paused) stopMessageTimer(assistantMsg)
          agentLoading.value = !paused
          log('DIRECT_STREAM_CLOSURE_RECOVERED_ACTIVE_TASK', {
            conversationId,
            taskId: task.taskId,
            status: task.status
          })
          void subscribeToTaskEvents(task, assistantMsg)
          return true
        }

        assistantMsg.isStreaming = false
        assistantMsg.hasDurableFinal = hasDurableFinalMessage(task)
        if (assistantMsg.timing) assistantMsg.timing.taskId = task.taskId
        stopMessageTimer(assistantMsg)
        if (assistantMsg.hasDurableFinal) {
          log('DIRECT_TERMINAL_TRANSCRIPT_HYDRATED', {
            conversationId,
            taskId: task.taskId,
            status: task.status
          })
          return true
        }
        log('DIRECT_TERMINAL_TRANSCRIPT_NOT_READY', {
          conversationId,
          taskId: task.taskId,
          status: task.status,
          attempt: attempt + 1
        })
      } catch (error) {
        console.warn('Failed to hydrate a direct terminal Agent reply:', error)
        return false
      }
      if (attempt + 1 < DIRECT_TERMINAL_TRANSCRIPT_RECOVERY.maxAttempts) {
        await wait(DIRECT_TERMINAL_TRANSCRIPT_RECOVERY.retryDelayMs)
      }
    }
    return false
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
      if (!task?.conversationId || task.conversationId === conversationId) {
        await reconcileTerminalConversationHistory(conversationId, generation, task?.status || 'none')
        await hydrateTerminalTaskProjection(conversationId, generation)
      }
      log('ACTIVE_TASK_RECOVERY_NONE', { conversationId, status: task?.status || 'none' })
      return false
    }

    currentAgentSession.value = {
      conversationId: task.conversationId,
      sessionId: task.sessionId
    }
    const assistantMsg = assistantMessageForTask(task)
    assistantMsg.taskId = task.taskId
    reconcileRecoveredToolCalls(assistantMsg, task)
    reconcileRecoveredCommandApproval(assistantMsg, task)
    const taskStatus = normalizeAgentRunState(task.status)
    assistantMsg.runState = taskStatus
    const waitingForCommandApproval = taskStatus === 'waiting_approval'
    const waitingForEnvironment = taskStatus === 'waiting_environment'
    assistantMsg.isStreaming = !waitingForCommandApproval && !waitingForEnvironment
    if (waitingForCommandApproval || waitingForEnvironment) stopMessageTimer(assistantMsg)
    if (assistantMsg.timing) assistantMsg.timing.taskId = task.taskId
    agentLoading.value = !waitingForCommandApproval && !waitingForEnvironment
    void subscribeToTaskEvents(task, assistantMsg)
    return true
  }

  async function subscribeToTaskEvents(initialTask, assistantMsg) {
    let task = initialTask
    let receivedFinalEvent = false
    const generation = ++subscriptionGeneration
    const recoveryBoundary = recoveryGeneration
    try {
      while (generation === subscriptionGeneration && task?.taskId && !isTerminalAgentTask(task)) {
        if (!ownsTaskIdentity(task)) return
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
              if (generation !== subscriptionGeneration || !ownsTaskIdentity(task)) return
              if (event?.eventId != null && !cursors.save(task.taskId, event.eventId)) {
                log('TASK_EVENT_STALE_SEQUENCE_IGNORED', {
                  taskId: task.taskId,
                  eventId: event.eventId,
                  storedCursor: cursors.read(task.taskId)
                })
                return
              }
              handleAgentEvent(event, assistantMsg)
              if (event?.type === 'FINAL' && event?.data?.content) receivedFinalEvent = true
            }
          })
        } catch (error) {
          if (error?.name === 'AbortError' || generation !== subscriptionGeneration) return
          console.warn('Agent task subscription disconnected; reconnecting from durable cursor:', error)
        }
        if (generation !== subscriptionGeneration || !ownsTaskIdentity(task)) return

        const active = (await api.agentActiveTask(projectId.value, task.conversationId))?.data || null
        if (!active || !ownsTaskIdentity(active) || isTerminalAgentTask(active)
            || Number(active.taskId) !== Number(initialTask.taskId)
            || (initialTask.sessionId && active.sessionId !== initialTask.sessionId)) {
          assistantMsg.isStreaming = false
          stopMessageTimer(assistantMsg)
          cursors.clear(initialTask.taskId)
          await syncTaskTiming(assistantMsg)
          await reconcileTerminalConversationHistory(
            task.conversationId,
            recoveryBoundary,
            active?.status || 'terminal',
            !receivedFinalEvent
          )
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

  async function resumeTaskEventSubscription(taskId, assistantMsg, conversationId = assistantMsg?.conversationId || currentAgentSession.value?.conversationId) {
    let task = null
    let resolvedConversationId = conversationId || null
    const activeTaskLookup = async () => {
      if (!resolvedConversationId) return null
      return (await api.agentActiveTask(projectId.value, resolvedConversationId))?.data || null
    }
    const durableTaskLookup = async () => {
      if (typeof api.agentTask !== 'function') return null
      return (await api.agentTask(projectId.value, taskId))?.data || null
    }

    for (let attempt = 0; attempt < 20; attempt++) {
      const candidate = await activeTaskLookup()
      if (candidate && Number(candidate.taskId) === Number(taskId)
          && (!resolvedConversationId || candidate.conversationId === resolvedConversationId)
          && (!currentAgentSession.value?.sessionId || !candidate.sessionId
            || candidate.sessionId === currentAgentSession.value.sessionId)) {
        task = candidate
        break
      }
      if (candidate && (Number(candidate.taskId) !== Number(taskId) || isTerminalAgentTask(candidate))) break

      // 会话 UI 尚未恢复时，按持久化 taskId 兜底查询，避免请求 active-task/undefined。
      const snapshot = await durableTaskLookup()
      if (snapshot && Number(snapshot.taskId) === Number(taskId)) {
        if (resolvedConversationId && snapshot.conversationId !== resolvedConversationId) {
          throw new Error('Agent task belongs to a different conversation')
        }
        if (currentAgentSession.value?.conversationId
            && snapshot.conversationId !== currentAgentSession.value.conversationId) {
          throw new Error('Agent task belongs to a different conversation')
        }
        if (currentAgentSession.value?.sessionId && snapshot.sessionId
            && snapshot.sessionId !== currentAgentSession.value.sessionId) {
          throw new Error('Agent task belongs to a different session')
        }
        resolvedConversationId = snapshot.conversationId || resolvedConversationId
        task = snapshot
        break
      }
      await wait(250)
    }
    if (!task) throw new Error('Agent task is no longer active')
    if (!currentAgentSession.value?.conversationId) {
      currentAgentSession.value = {
        conversationId: task.conversationId,
        sessionId: task.sessionId
      }
    }
    assistantMsg.taskId = task.taskId
    assistantMsg.conversationId = task.conversationId
    reconcileRecoveredToolCalls(assistantMsg, task)
    reconcileRecoveredCommandApproval(assistantMsg, task)
    assistantMsg.isStreaming = true
    if (assistantMsg.timing) assistantMsg.timing.isRunning = true
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
    logTaskRecovery: log,
    recordTaskEventCursor,
    invalidate,
    recoverActiveTaskForConversation,
    reconcileDirectTerminalTask,
    subscribeToTaskEvents,
    resumeTaskEventSubscription,
    syncTaskTiming,
    syncConversationTaskTimings
  }
}
