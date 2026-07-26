import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const source = await readFile(new URL('./CloudWorkspace.vue', import.meta.url), 'utf8')
const apiSource = await readFile(new URL('../api/index.js', import.meta.url), 'utf8')
const streamSource = await readFile(new URL('../composables/useAgentStream.js', import.meta.url), 'utf8')
const runtimeSource = await readFile(new URL('../composables/useAgentTaskRuntime.js', import.meta.url), 'utf8')

test('workspace delegates active-task recovery to a conversation-scoped runtime', () => {
  assert.match(apiSource, /agentActiveTask\(projectId, conversationId\)/)
  assert.match(source, /useAgentTaskRuntime/)
  assert.match(runtimeSource, /async function recoverActiveTaskForConversation\(conversationId\)/)
  assert.match(runtimeSource, /sessionId:\s*task\.sessionId/)
  assert.match(runtimeSource, /conversationId:\s*task\.conversationId/)
  assert.match(runtimeSource, /const generation = \+\+recoveryGeneration/)
  assert.match(runtimeSource, /generation !== recoveryGeneration/)
  assert.match(runtimeSource, /ownsConversation\(conversationId\)/)
  assert.match(runtimeSource, /void subscribeToTaskEvents\(task, assistantMsg\)/)
  assert.match(runtimeSource, /console\.info\('\[AgentTaskRecovery\]'/)
  assert.match(source, /case 'TASK_PAUSED':/)
  assert.match(source, /call\._commandApprovalInFlight/)
  assert.match(source, /function reconcileRecoveredCommandApproval\(message, task\)/)
  assert.match(source, /case 'COMMAND_EXECUTION_STARTED':/)
})

test('task recovery uses durable subscription cursors rather than replay polling', () => {
  assert.match(source, /subscribe:\s*subscribeAgent/)
  assert.match(runtimeSource, /await subscribeAgent\(projectId\.value, task\.taskId,/)
  assert.match(runtimeSource, /createTaskEventCursorStore/)
  assert.match(streamSource, /Last-Event-ID/)
  assert.doesNotMatch(runtimeSource, /recoverActiveTaskForConversation[\s\S]{0,1800}replayAgent\(/)
})