import assert from 'node:assert/strict'
import test from 'node:test'
import { createAgentRuntimeIndex, ensureAssistantMessage } from './agentRuntimeStore.js'

function assistantMessage(taskId, toolCalls = [], extra = {}) {
  return {
    role: 'assistant',
    taskId,
    content: '',
    toolCalls,
    runParts: [],
    runMessages: [],
    ...extra
  }
}

test('indexes assistant messages by taskId with latest-wins semantics', () => {
  const messages = [
    { role: 'user', content: 'hi' },
    assistantMessage(7),
    assistantMessage(9),
    assistantMessage(7, [], { content: 'latest projection' })
  ]

  const index = createAgentRuntimeIndex(messages)

  assert.equal(index.byTaskId.size, 2)
  assert.equal(index.assistantMessageForTask(7).content, 'latest projection')
  assert.equal(index.assistantMessageForTask(9).taskId, 9)
  assert.equal(index.assistantMessageForTask(11), null)
})

test('indexes tool cards and durable interactions across messages', () => {
  const call = {
    toolCallId: 'call-1', name: 'shell', status: 'completed',
    interactionId: 'interaction-1'
  }
  const permissionCall = {
    toolCallId: 'call-2', name: 'read_file', status: 'waiting_approval',
    permissionRequest: { requestId: 'request-2' }
  }
  const messages = [
    assistantMessage(1, [call]),
    assistantMessage(2, [permissionCall])
  ]

  const index = createAgentRuntimeIndex(messages)

  assert.equal(index.toolCall('call-1').call.name, 'shell')
  assert.equal(index.toolCall('call-1').message.taskId, 1)
  assert.equal(index.interaction('interaction-1').call.toolCallId, 'call-1')
  assert.equal(index.interaction('request-2').call.toolCallId, 'call-2')
  assert.equal(index.toolCall('missing'), null)
})

test('indexes durable run parts and run messages by stable keys', () => {
  const messages = [
    assistantMessage(5, [], {
      runMessages: [
        { messageId: 3, messageKey: 'assistant:turn:3', content: 't3' },
        { messageId: 4, messageKey: 'assistant:final', content: 'done' }
      ],
      runParts: [
        { partId: 10, partKey: 'tool:call-1', partType: 'tool', toolCallId: 'call-1', status: 'completed' },
        { partId: 11, partKey: 'reasoning:11', partType: 'reasoning', output: 'plan' }
      ]
    })
  ]

  const index = createAgentRuntimeIndex(messages)

  assert.equal(index.part('tool:call-1').part.toolCallId, 'call-1')
  assert.equal(index.part('reasoning:11').part.partType, 'reasoning')
  assert.equal(index.byMessageKey.get('assistant:final').runMessage.content, 'done')
})

test('ensureAssistantMessage reuses the existing message for the same task', () => {
  const messages = []
  const first = ensureAssistantMessage(messages, 42, () => assistantMessage(42))

  const second = ensureAssistantMessage(messages, 42, () => assistantMessage(42))

  assert.equal(second, first)
  assert.equal(messages.length, 1)

  const third = ensureAssistantMessage(messages, 43, () => assistantMessage(43))
  assert.notEqual(third, first)
  assert.equal(messages.length, 2)
})
