import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const source = await readFile(new URL('./CloudWorkspace.vue', import.meta.url), 'utf8')

test('CloudWorkspace delegates SSE lifecycle to useAgentStream', () => {
  assert.match(source, /import \{ useAgentStream \} from '@\/composables\/useAgentStream'/)
  assert.match(source, /const \{ stream: streamAgent, replay: replayAgent, stop: stopAgent \} = useAgentStream\(\)/)
  assert.match(source, /await streamAgent\(projectId\.value,/)
  assert.match(source, /await stopAgent\(projectId\.value,/)
  assert.doesNotMatch(source, /response\.body\.getReader\(\)/)
})

test('CloudWorkspace starts secondary initial requests without blocking the file tree', () => {
  assert.match(source, /import \{ loadWorkspaceResources \} from '@\/composables\/workspaceInitialization'/)
  assert.match(source, /const secondaryResources = loadWorkspaceResources\(\[/)
  assert.match(source, /await loadRoot\(\)/)
})

test('CloudWorkspace lazy-loads heavy editor, terminal, and chart components', () => {
  assert.match(source, /defineAsyncComponent\(\(\) => import\('@\/components\/MonacoEditor\.vue'\)\)/)
  assert.match(source, /defineAsyncComponent\(\(\) => import\('@\/components\/terminal\/TerminalPanel\.vue'\)\)/)
  assert.match(source, /defineAsyncComponent\(\(\) => import\('@\/components\/cloud\/TokenChart\.vue'\)\)/)
})

test('CloudWorkspace renders provider failures instead of leaving a loading skeleton', () => {
  assert.match(source, /case 'ERROR':[\s\S]*assistantMsg\.content = `错误：\$\{message\}`[\s\S]*assistantMsg\.isStreaming = false/)
})
test('CloudWorkspace keeps the actual provider error when a stop final event follows', () => {
  assert.match(source, /case 'FINAL':[\s\S]*if \(data\.content && !assistantMsg\.error\) assistantMsg\.content = data\.content/)
})

test('CloudWorkspace renders live thinking and answer deltas immediately', () => {
  assert.match(source, /case 'THINK_DELTA':[\s\S]*assistantMsg\._thinkingDisplay = assistantMsg\.thinking/)
  assert.match(source, /case 'FINAL_DELTA':[\s\S]*assistantMsg\.content \+= \(data\.delta \|\| ''\)[\s\S]*scheduleAgentRender\(\)/)
  assert.doesNotMatch(source, /function startThinkingReveal\([\s\S]*?setInterval\(/)
})
test('CloudWorkspace keeps command-approval continuation active until the durable run finishes', () => {
  assert.match(source, /async function replayResumedAgent\(taskId, assistantMsg\) \{[\s\S]*?while \(assistantMsg\.isStreaming\)/)
  assert.match(source, /const task = \(await fetchAgentTasks\(\)\)\.find\(item => Number\(item\.taskId\) === Number\(taskId\)\)/)
  assert.match(source, /if \(isTerminalTask\(task\)\) \{[\s\S]*?assistantMsg\.isStreaming = false/)
  assert.doesNotMatch(source, /for \(let attempt = 0; attempt < 40 && assistantMsg\.isStreaming; attempt\+\+\)/)
})
