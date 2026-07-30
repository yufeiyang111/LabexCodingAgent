import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

test('CloudWorkspace delegates permission and question submissions to useAgentInteraction', async () => {
  const source = await readFile(new URL('../views/CloudWorkspace.vue', import.meta.url), 'utf8')

  assert.match(source, /import \{ useAgentInteraction \} from '@\/composables\/useAgentInteraction'/)
  assert.match(source, /const \{ submitPermissionDecision, submitQuestionReply \} = useAgentInteraction\(\{[\s\S]*?api: projectApi[\s\S]*?\}\)/)
  assert.match(source, /async function handlePermissionDecision\(payload\) \{\s*const result = await submitPermissionDecision\(payload\)/)
  assert.match(source, /async function handleQuestionReply\(payload\) \{[\s\S]*?submitQuestionReply\(payload\)/)
  assert.doesNotMatch(source, /async function handlePermissionDecision\([\s\S]*?projectApi\.agentApprovePermission/)
  assert.doesNotMatch(source, /async function handleQuestionReply\([\s\S]*?projectApi\.agentReplyQuestion/)
})
