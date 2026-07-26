import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const source = await readFile(new URL('./CloudWorkspace.vue', import.meta.url), 'utf8')
const apiSource = await readFile(new URL('../api/index.js', import.meta.url), 'utf8')
const streamSource = await readFile(new URL('../composables/useAgentStream.js', import.meta.url), 'utf8')

test('workspace recovers the original active task and session after conversation history loads', () => {
  assert.match(apiSource, /agentActiveTask\(projectId, conversationId\)/)
  assert.match(source, /async function recoverActiveTaskForConversation\(conversationId\)/)
  assert.match(source, /sessionId:\s*task\.sessionId/)
  assert.match(source, /conversationId:\s*task\.conversationId/)
  assert.match(source, /const recoveryGeneration = \+\+taskRecoveryGeneration/)
  assert.match(source, /recoveryGeneration !== taskRecoveryGeneration/)
  assert.match(source, /void subscribeToTaskEvents\(task, assistantMsg\)/)
  assert.match(source, /function logTaskRecovery\(event, details = \{\}\)/)
  assert.match(source, /logTaskRecovery\('ACTIVE_TASK_RECOVERY_STARTED'/)
  assert.match(source, /logTaskRecovery\('TASK_EVENT_SUBSCRIBE_STARTED'/)
  assert.match(source, /console\.info\('\[AgentTaskRecovery\]'/)
  assert.match(source, /case 'TASK_PAUSED':/)
  assert.match(source, /call\._commandApprovalInFlight/)
  assert.match(source, /function reconcileRecoveredCommandApproval\(message, task\)/)
  assert.match(source, /case 'COMMAND_EXECUTION_STARTED':/)
})

test('workspace uses a dedicated task event subscription rather than polling replay for refresh recovery', () => {
  assert.match(source, /subscribe:\s*subscribeAgent/)
  assert.match(source, /await subscribeAgent\(projectId\.value, task\.taskId,/)
  assert.match(streamSource, /Last-Event-ID/)
  assert.doesNotMatch(source, /recoverActiveTaskForConversation[\s\S]{0,1800}replayAgent\(/)
})
