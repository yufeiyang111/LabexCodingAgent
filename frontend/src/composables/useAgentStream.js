import { computed, ref } from 'vue'
import { projectApi } from '@/api'
import { consumeAgentSse } from './agentSse'
import { initialAgentStreamState, reduceAgentStreamState } from './agentStreamState'

export function useAgentStream() {
  const streamState = ref(initialAgentStreamState())
  const abortController = ref(null)
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
      const response = await fetch(`/api/student/projects/${projectId}/agent/stream`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${localStorage.getItem('token') || ''}`
        },
        body: JSON.stringify(payload),
        signal: controller.signal
      })

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`)
      }
      if (!response.body) {
        throw new Error('Agent stream response has no body')
      }

      await consumeAgentSse(response.body, event => {
        recordEvent(event)
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
      recordEvent(event, taskId)
      try {
        options.onEvent?.(event)
      } catch (error) {
        console.warn('Agent replay event handler failed:', error)
      }
    })
  }

  async function stop(projectId, sessionId = streamState.value.sessionId) {
    if (stopRequest.value) return stopRequest.value
    if (!abortController.value && streamState.value.status === 'idle') return undefined

    streamState.value = reduceAgentStreamState(streamState.value, { type: 'STOP_REQUESTED' })
    const controller = abortController.value
    const interrupt = sessionId ? projectApi.agentInterrupt(projectId, sessionId) : Promise.resolve()
    controller?.abort()

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
    stop
  }

  function recordEvent(event, fallbackTaskId = null) {
    const taskId = event?.data?.taskId ?? fallbackTaskId
    streamState.value = reduceAgentStreamState(streamState.value, {
      type: 'EVENT_RECEIVED',
      taskId,
      eventId: event?.eventId ?? null
    })
  }
}
