import { ref, reactive, nextTick } from 'vue'
import { projectApi } from '@/api'

export function useAgentStream() {
  const messages = ref([])
  const isStreaming = ref(false)
  const currentSession = ref(null)
  const abortController = ref(null)

  function createUserMessage(content) {
    return {
      id: 'user-' + Date.now(),
      role: 'user',
      content,
      timestamp: Date.now()
    }
  }

  function createAssistantMessage() {
    return {
      id: 'msg-' + Date.now(),
      role: 'assistant',
      content: '',
      thinking: '',
      toolCalls: [],
      plan: null,
      isStreaming: true,
      error: null
    }
  }

  async function sendMessage(projectId, { message, activePath, mode, conversationId, modelConfigId }) {
    if (isStreaming.value) return

    messages.value.push(createUserMessage(message))
    const assistantMsg = createAssistantMessage()
    messages.value.push(assistantMsg)

    isStreaming.value = true
    abortController.value = new AbortController()

    const sessionId = currentSession.value?.sessionId || crypto.randomUUID()

    try {
      const response = await fetch(`/api/student/projects/${projectId}/agent/stream`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${localStorage.getItem('token') || ''}`
        },
        body: JSON.stringify({
          sessionId,
          conversationId: conversationId || currentSession.value?.conversationId,
          mode: mode || 'agent',
          message,
          activePath: activePath || '',
          modelConfigId: modelConfigId || null
        }),
        signal: abortController.value.signal
      })

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`)
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const line of lines) {
          if (line.startsWith('event: ')) {
            // 事件类型从 JSON 数据的 type 字段获取，此处有意忽略 SSE event 字段
            const _eventType = line.substring(7).trim()
            continue
          }
          if (line.startsWith('data: ')) {
            const dataStr = line.substring(6).trim()
            if (!dataStr) continue
            try {
              const event = JSON.parse(dataStr)
              handleEvent(event, assistantMsg)
            } catch (e) {
              console.warn('SSE parse error:', e, dataStr)
            }
          }
        }
      }
    } catch (error) {
      if (error.name === 'AbortError') {
        assistantMsg.content += '\n\n[已中断]'
      } else {
        assistantMsg.error = error.message
        assistantMsg.content = `错误: ${error.message}`
      }
    } finally {
      assistantMsg.isStreaming = false
      isStreaming.value = false
      abortController.value = null
    }
  }

  function handleEvent(event, assistantMsg) {
    const type = event.type
    const data = event.data

    switch (type) {
      case 'SESSION':
        currentSession.value = data
        break

      case 'THINK_START':
        assistantMsg.thinking = ''
        break

      case 'THINK_DELTA':
        assistantMsg.thinking += (data.delta || '')
        break

      case 'THINK':
        assistantMsg.thinking = data.content || assistantMsg.thinking
        break

      case 'TOOL_CALL':
        assistantMsg.toolCalls.push({
          name: data.tool,
          args: data.arguments,
          summary: data.summary,
          content: data.content,
          result: null,
          status: 'running',
          startTime: Date.now()
        })
        break

      case 'OBSERVE':
        if (assistantMsg.toolCalls.length > 0) {
          const lastTool = assistantMsg.toolCalls[assistantMsg.toolCalls.length - 1]
          lastTool.result = data.result || data.content
          lastTool.status = data.success ? 'completed' : 'error'
          lastTool.endTime = Date.now()
        }
        break

      case 'PLAN_UPDATE':
        assistantMsg.plan = data.plan || null
        break

      case 'FINAL_DELTA':
        assistantMsg.content += (data.delta || '')
        break

      case 'FINAL':
        assistantMsg.content = data.content || assistantMsg.content
        assistantMsg.isStreaming = false
        break

      case 'DONE':
        assistantMsg.isStreaming = false
        break

      case 'ERROR':
        assistantMsg.error = data.message || 'Unknown error'
        break

      case 'INTERRUPTED':
        assistantMsg.content += '\n\n[用户中断]'
        assistantMsg.isStreaming = false
        break

      case 'COMMAND_APPROVAL_REQUIRED':
        assistantMsg.toolCalls.push({
          name: 'approval_required',
          args: { command: data.command },
          summary: '等待确认: ' + data.command,
          result: null,
          status: 'waiting_approval',
          approvalCommand: data.command,
          taskId: data.taskId
        })
        break

      case 'PERMISSION_ASK': {
        const permissionRequest = {
          requestId: data.requestId,
          toolName: data.toolName,
          input: data.input,
          summary: data.summary,
          matchedRulePermission: data.matchedRulePermission,
          matchedRulePattern: data.matchedRulePattern
        }
        assistantMsg.toolCalls.push({
          name: 'permission_ask',
          args: { toolName: data.toolName, summary: data.summary },
          summary: '需要权限: ' + data.summary,
          result: null,
          status: 'waiting_approval',
          permissionRequest,
          startTime: Date.now()
        })
        break
      }
    }
  }

  async function stopGeneration(projectId) {
    if (abortController.value) {
      abortController.value.abort()
    }
    if (currentSession.value?.sessionId) {
      try {
        await fetch(`/api/student/projects/${projectId}/agent/interrupt`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${localStorage.getItem('token') || ''}`
          },
          body: JSON.stringify({ sessionId: currentSession.value.sessionId })
        })
      } catch (e) {
        console.warn('Interrupt failed:', e)
      }
    }
  }

  function clearMessages() {
    messages.value = []
    currentSession.value = null
  }

  return {
    messages,
    isStreaming,
    currentSession,
    sendMessage,
    stopGeneration,
    clearMessages
  }
}
