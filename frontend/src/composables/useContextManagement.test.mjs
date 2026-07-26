import assert from 'node:assert/strict'
import test from 'node:test'
import { contextManagementStrategyText, useContextManagement } from './useContextManagement.js'

function harness(result = { success: true, taskId: 81, status: 'queued' }) {
  const messages = { value: [] }
  const subscriptions = []
  const events = []
  const notices = []
  const state = useContextManagement({
    messages,
    agentLoading: { value: false },
    projectId: { value: 42 },
    currentAgentSession: { value: { conversationId: 'conversation-a' } },
    conversations: { value: [{ conversationId: 'conversation-a' }] },
    selectedModelConfigId: { value: 17 },
    api: { agentInterrupt: async () => {} },
    compactConversationState: async () => result,
    subscribeToTaskEvents: (task, message) => subscriptions.push({ task, message }),
    reduceContextManagementEvent: (type, data, message) => {
      events.push({ type, data })
      message.contextManagementEvents ||= []
    },
    notify: {
      success: message => notices.push(['success', message]),
      error: message => notices.push(['error', message]),
      info: message => notices.push(['info', message])
    },
    scheduleAgentRender: () => {}
  })
  return { state, messages, subscriptions, events, notices }
}

test('manual compaction remains queued and attaches to the exact durable task', async () => {
  const target = harness()

  assert.equal(await target.state.compactConversation({ conversationId: 'conversation-a' }), true)
  assert.equal(target.subscriptions.length, 1)
  assert.deepEqual(target.subscriptions[0].task, {
    taskId: 81,
    conversationId: 'conversation-a',
    status: 'queued',
    lastEventSequence: 0
  })
  assert.deepEqual(target.events.map(event => event.type), ['COMPACTION_STARTED'])
  assert.equal(target.events[0].data.taskId, 81)
  assert.equal(target.notices[0][0], 'success')
})

test('manual compaction fails closed when enqueue response has no task id', async () => {
  const target = harness({ success: true, taskId: null, status: 'queued' })

  assert.equal(await target.state.compactConversation({ conversationId: 'conversation-a' }), false)
  assert.equal(target.subscriptions.length, 0)
  assert.deepEqual(target.events.map(event => event.type), ['COMPACTION_FAILED'])
})

test('context strategy labels remain explicit', () => {
  assert.equal(contextManagementStrategyText({ strategy: 'manual_model' }), '手动模型摘要')
  assert.equal(contextManagementStrategyText({ strategy: 'unknown' }), '')
})