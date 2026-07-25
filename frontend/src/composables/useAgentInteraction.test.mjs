import assert from 'node:assert/strict'
import test from 'node:test'
import { ref } from 'vue'

const interactionModule = await import('./useAgentInteraction.js').catch(() => ({}))
const { useAgentInteraction } = interactionModule

function createHarness(apiOverrides = {}) {
  const projectId = ref(42)
  const calls = []
  const api = {
    agentApprovePermission: async (id, payload) => {
      calls.push(['permission', id, payload])
      return { data: { granted: true } }
    },
    agentReplyQuestion: async (id, payload) => {
      calls.push(['question', id, payload])
      return { data: { answered: true } }
    },
    ...apiOverrides
  }
  return { interaction: useAgentInteraction({ projectId, api }), calls }
}

test('submits a rejected permission with a safe default feedback message', async () => {
  assert.equal(typeof useAgentInteraction, 'function')
  const { interaction, calls } = createHarness()
  const call = { status: 'waiting_approval', permissionRequest: { requestId: 'permission-1' } }

  const result = await interaction.submitPermissionDecision({ call, action: 'reject', feedback: '' })

  assert.deepEqual(result, { handled: true, success: true })
  assert.equal(call.status, 'error')
  assert.equal(call.result, '已拒绝执行')
  assert.deepEqual(calls, [['permission', 42, {
    requestId: 'permission-1', action: 'reject', feedback: '用户拒绝了本次工具调用'
  }]])
})

test('marks a permission as failed when the backend does not grant it', async () => {
  const { interaction } = createHarness({
    agentApprovePermission: async () => ({ data: { granted: false, feedback: '策略拒绝' } })
  })
  const call = { status: 'waiting_approval', permissionRequest: { requestId: 'permission-2' } }

  await interaction.submitPermissionDecision({ call, action: 'approve', feedback: '' })

  assert.equal(call.status, 'error')
  assert.equal(call.result, '策略拒绝')
})

test('requires a non-empty answer before submitting a user question', async () => {
  const { interaction, calls } = createHarness()
  const call = { status: 'waiting_user', questionRequest: { requestId: 'question-1' } }

  const result = await interaction.submitQuestionReply({ call, action: 'answer', answer: '  ' })

  assert.deepEqual(result, { handled: false, reason: 'answer_required' })
  assert.equal(call.status, 'waiting_user')
  assert.deepEqual(calls, [])
})

test('submits an answer and records the resumed state on the tool call', async () => {
  const { interaction, calls } = createHarness()
  const call = { status: 'waiting_user', questionRequest: { requestId: 'question-2' } }

  const result = await interaction.submitQuestionReply({ call, action: 'answer', answer: '继续执行' })

  assert.deepEqual(result, { handled: true, success: true })
  assert.equal(call.status, 'running')
  assert.match(call.result, /已提交回答：继续执行/)
  assert.deepEqual(calls, [['question', 42, { requestId: 'question-2', action: 'answer', answer: '继续执行' }]])
})
