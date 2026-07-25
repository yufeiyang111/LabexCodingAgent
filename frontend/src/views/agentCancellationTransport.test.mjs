import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const workspaceSource = await readFile(new URL('./CloudWorkspace.vue', import.meta.url), 'utf8')

assert.match(
  workspaceSource,
  /import \{ useAgentStream \} from '@\/composables\/useAgentStream'/,
  'the workspace must use the shared stream transport'
)

assert.match(
  workspaceSource,
  /await streamAgent\(/,
  'the workspace must delegate stream consumption to the shared transport'
)

assert.match(
  workspaceSource,
  /if \(e\.name === 'AbortError'\) \{[\s\S]*?assistantMsg\.content \+=/,
  'a locally aborted stream must still render an interruption state'
)

assert.match(
  workspaceSource,
  /await stopAgent\(projectId\.value, currentAgentSession\.value\?\.sessionId\)/,
  'the stop control must abort locally and notify the server through the shared transport'
)
