import { computed, ref } from 'vue'
import { projectApi } from '@/api'
import { consumeAgentSse } from './agentSse'
import { initialAgentStreamState, reduceAgentStreamState } from './agentStreamState'

export function useAgentStream() {
  const streamState = ref(initialAgentStreamState())
  const abortController = ref(null)
  const subscriptionController = ref(null)
  const stopRequest = ref(null)
  const isStreaming = computed(() => streamState.value.status !== 'idle')

  async function stream(projectId, payload, options = {}) {
    if (abortController.value) {
      throw new Error('An agent stream is already active')
    }

    const controller = new AbortController()
    abortController.value = controller
    streamState.value = reduceAgentStreamState(streamState.value, {
      type: 'START',
      sessionId: payload?.sessionId
    })

    try {
      const imageFiles = Array.isArray(options.files) ? options.files.filter(Boolean) : []
      const headers = {
        'Authorization': `Bearer ${localStorage.getItem('token') || ''}`
      }
      let body
      if (imageFiles.length > 0) {
        const form = new FormData()
        form.append('request', JSON.stringify(payload))
        imageFiles.forEach(file => form.append('images', file))
        body = form
      } else {
        headers['Content-Type'] = 'application/json'
        body = JSON.stringify(payload)
      }
      const response = await fetch(`/api/student/projects/${projectId}/agent/stream`, {
        method: 'POST',
        headers,
        body,
        signal: controller.signal
      })

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`)
      }
      if (!response.body) {
        throw new Error('Agent stream response has no body')
      }

      await consumeAgentSse(response.body, event => {
        if (!recordEvent(event)) return
        try {
          options.onEvent?.(event)
        } catch (error) {
          console.warn('Agent event handler failed:', error)
        }
      })
    } finally {
      if (abortController.value === controller) {
        abortController.value = null
        streamState.value = reduceAgentStreamState(streamState.value, { type: 'STREAM_ENDED' })
      }
    }
  }

  async function replay(projectId, taskId = streamState.value.taskId, options = {}) {
    if (!taskId) {
      throw new Error('An agent task ID is required to replay events')
    }
    const lastEventId = options.lastEventId ?? streamState.value.lastEventId
    const headers = {
      'Authorization': `Bearer ${localStorage.getItem('token') || ''}`
    }
    if (lastEventId) {
      headers['Last-Event-ID'] = String(lastEventId)
    }
    const response = await fetch(`/api/student/projects/${projectId}/agent/tasks/${taskId}/events`, { headers })
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}: ${response.statusText}`)
    }
    if (!response.body) {
      throw new Error('Agent replay response has no body')
    }

    await consumeAgentSse(response.body, event => {
      if (!recordEvent(event, taskId)) return
      try {
        options.onEvent?.(event)
      } catch (error) {
        console.warn('Agent replay event handler failed:', error)
      }
    })
  }

  async function subscribe(projectId, taskId, options = {}) {
    if (!taskId) {
      throw new Error('An agent task ID is required to subscribe')
    }
    subscriptionController.value?.abort()
    const controller = new AbortController()
    subscriptionController.value = controller
    const lastEventId = options.lastEventId ?? streamState.value.lastEventId
    const headers = {
      'Authorization': `Bearer ${localStorage.getItem('token') || ''}`
    }
    if (lastEventId != null && String(lastEventId).trim()) {
      headers['Last-Event-ID'] = String(lastEventId)
    }

    try {
      const response = await fetch(`/api/student/projects/${projectId}/agent/tasks/${taskId}/subscribe`, {
        headers,
        signal: controller.signal
      })
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`)
      }
      if (!response.body) {
        throw new Error('Agent subscription response has no body')
      }
      await consumeAgentSse(response.body, event => {
        if (!recordEvent(event, taskId)) return
        try {
          options.onEvent?.(event)
        } catch (error) {
          console.warn('Agent subscription event handler failed:', error)
        }
      })
    } finally {
      if (subscriptionController.value === controller) {
        subscriptionController.value = null
      }
    }
  }

  function disconnectSubscription() {
    subscriptionController.value?.abort()
  }

  function disconnect() {
    abortController.value?.abort()
    subscriptionController.value?.abort()
  }

  async function stop(projectId, sessionId = streamState.value.sessionId) {
    if (stopRequest.value) return stopRequest.value
    if (!abortController.value && !subscriptionController.value && streamState.value.status === 'idle') return undefined

    streamState.value = reduceAgentStreamState(streamState.value, { type: 'STOP_REQUESTED' })
    const controller = abortController.value
    const subscriber = subscriptionController.value
    const interrupt = sessionId ? projectApi.agentInterrupt(projectId, sessionId) : Promise.resolve()
    controller?.abort()
    subscriber?.abort()

    stopRequest.value = Promise.resolve(interrupt)
      .catch(error => {
        console.warn('Agent interrupt failed:', error)
      })
      .finally(() => {
        stopRequest.value = null
      })
    return stopRequest.value
  }

  return {
    streamState,
    isStreaming,
    stream,
    replay,
    subscribe,
    disconnect,
    disconnectSubscription,
    stop
  }

  function recordEvent(event, fallbackTaskId = null) {
    const taskId = event?.data?.taskId ?? fallbackTaskId
    const before = streamState.value
    const after = reduceAgentStreamState(before, {
      type: 'EVENT_RECEIVED',
      taskId,
      eventId: event?.eventId ?? null
    })
    streamState.value = after
    return event?.eventId == null || after !== before
  }
}
