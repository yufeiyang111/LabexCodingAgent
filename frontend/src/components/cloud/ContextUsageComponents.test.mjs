import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const component = name => readFile(new URL(`./${name}`, import.meta.url), 'utf8')

test('ContextUsageIndicator is a numberless accessible ring with hover details', async () => {
  const source = await component('ContextUsageIndicator.vue')
  assert.match(source, /width: 16px/)
  assert.match(source, /aria-label="ariaLabel"/)
  assert.match(source, /@mouseenter="showPopover"/)
  assert.match(source, /opensLeft/)
  assert.match(source, /getBoundingClientRect/)
  assert.match(source, /opens-left/)
  assert.match(source, /@click="\$emit\('open'\)"/)
  assert.match(source, new RegExp('\\u5b57\\u7b26\\u4f30\\u7b97'))
})

test('ContextUsageDialog exposes stable categories and a safely rendered actual-request preview', async () => {
  const source = await component('ContextUsageDialog.vue')
  const workspace = await readFile(new URL('../../views/CloudWorkspace.vue', import.meta.url), 'utf8')
  const api = await readFile(new URL('../../api/index.js', import.meta.url), 'utf8')
  for (const category of ['systemPrompt', 'toolDefinitions', 'projectContext', 'workspaceMemory', 'conversationMemory', 'runRecoveryContext', 'compactionSummary', 'compactedContext', 'skillsAndInstructions', 'conversationMessages', 'toolResults', 'messageProtocol']) {
    assert.match(source, new RegExp(category))
  }
  assert.match(source, /previewSections/)
  assert.match(source, /previewSource/)
  assert.match(source, /LAST_ACTUAL_REQUEST/)
  assert.match(source, /NEXT_REQUEST_ESTIMATE/)
  assert.match(source, /load-next-preview/)
  assert.match(source, /predictionLoading/)
  assert.match(source, /softLimitTokens/)
  assert.match(source, /distanceToSoftLimitTokens/)
  assert.match(source, /本轮未执行压缩/)
  assert.match(source, /旧版恢复上下文/)
  assert.doesNotMatch(source, /compactedContext['"]?, label: ['"]压缩上下文/)
  assert.match(source, /<pre>{{ section\.content }}<\/pre>/)
  assert.match(api, /agentNextContextPreview/)
  assert.match(workspace, /loadNextContextPreview/)
  assert.match(workspace, /draftMessage: agentInput\.value\.trim\(\)/)
  assert.match(source, new RegExp('\u5b9e\u9645\u53d1\u9001\u4e0a\u4e0b\u6587'))
  assert.doesNotMatch(source, /v-html/)
  assert.match(source, /defineEmits\(\['close', 'load-next-preview'\]\)/)
})

test('CloudWorkspace loads and renders context usage components', async () => {
  const source = await readFile(new URL('../../views/CloudWorkspace.vue', import.meta.url), 'utf8')
  const timeline = await readFile(new URL('../../composables/useAgentEventTimeline.js', import.meta.url), 'utf8')
  assert.match(source, /ContextUsageIndicator/)
  assert.match(source, /ContextUsageDialog/)
  assert.match(source, /openContextUsageDialog/)
  assert.match(source, /loadContextUsageStatus/)
  assert.match(timeline, /case 'CONTEXT_STATUS'/)
})
