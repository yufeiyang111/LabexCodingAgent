import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

test('AgentTimer owns a live interval and releases it on unmount', async () => {
  const source = await readFile(new URL('./AgentTimer.vue', import.meta.url), 'utf8')

  assert.match(source, /startedAt:\s*\{\s*type:\s*Number/)
  assert.match(source, /activeElapsedMs:\s*\{\s*type:\s*Number/)
  assert.match(source, /isRunning:\s*\{\s*type:\s*Boolean/)
  assert.match(source, /setInterval\(/)
  assert.match(source, /clearInterval\(/)
  assert.match(source, /onBeforeUnmount/)
  assert.match(source, /formatDuration/)
})

test('CloudWorkspace renders the timer underneath the last assistant token summary', async () => {
  const source = await readFile(new URL('../../views/CloudWorkspace.vue', import.meta.url), 'utf8')
  const runtimeSource = await readFile(new URL('../../composables/useAgentTaskRuntime.js', import.meta.url), 'utf8')

  assert.match(source, /const AgentTimer = defineAsyncComponent\(\(\) => import\('@\/components\/cloud\/AgentTimer\.vue'\)\)/)
  assert.match(source, /<TokenChart[\s\S]*?<AgentTimer[\s\S]*?:started-at="msg\.timing\.startedAt"/)
  assert.match(runtimeSource, /api\.agentTasks\(projectId\.value\)/)
})