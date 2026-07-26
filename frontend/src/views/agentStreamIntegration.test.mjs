import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const source = await readFile(new URL('./CloudWorkspace.vue', import.meta.url), 'utf8')
const runtimeSource = await readFile(new URL('../composables/useAgentTaskRuntime.js', import.meta.url), 'utf8')

test('CloudWorkspace delegates SSE lifecycle to useAgentStream', () => {
  assert.match(source, /import \{ useAgentStream \} from '@\/composables\/useAgentStream'/)
  assert.match(source, /const \{ stream: streamAgent, replay: replayAgent, subscribe: subscribeAgent, disconnect: disconnectAgentStream, disconnectSubscription, stop: stopAgent \} = useAgentStream\(\)/)
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


test('direct SSE events persist cursors through the extracted task runtime', () => {
  assert.match(source, /recordTaskEventCursor\(data\?\.taskId \|\| assistantMsg\?\.taskId, event\.eventId\)/)
  assert.match(source, /recordTaskEventCursor,[\s\S]*?syncTaskTiming/)
  assert.doesNotMatch(source, /saveTaskEventCursor\(/)
  assert.match(runtimeSource, /function recordTaskEventCursor\(taskId, eventId\)/)
})

test('command approval continuation reconnects through the extracted durable task runtime', () => {
  assert.match(source, /async function replayResumedAgent\(taskId, assistantMsg\) \{[\s\S]*?resumeTaskEventSubscription\(taskId, assistantMsg\)/)
  assert.match(runtimeSource, /async function subscribeToTaskEvents\(initialTask, assistantMsg\) \{[\s\S]*?await subscribeAgent\(projectId\.value, task\.taskId,/)
  assert.match(runtimeSource, /const cursor = storedCursor == null[\s\S]*?sequenceNumber\(task\.lastEventSequence\)/)
  assert.match(runtimeSource, /Number\(active\.taskId\) !== Number\(initialTask\.taskId\)/)
  assert.doesNotMatch(runtimeSource, /for \(let attempt = 0; attempt < 40 && assistantMsg\.isStreaming; attempt\+\+\)/)
})


test('conversation ownership changes hard-reset the rendered message timeline', () => {
  assert.match(source, /const conversationRenderEpoch = ref\(0\)/)
  assert.match(source, /<TransitionGroup\s+:key="conversationRenderEpoch"/)
  assert.match(source, /function resetRenderedConversation\(\) \{[\s\S]*?conversationRenderEpoch\.value \+= 1/)
  assert.match(source, /function createNewSession\(\) \{[\s\S]*?resetRenderedConversation\(\)[\s\S]*?resetConversation\(\)/)
  assert.match(source, /async function selectConversation\([\s\S]*?resetRenderedConversation\(\)[\s\S]*?selectConversationState\(conversation\)/)
})

test('new conversation invalidates delayed selection and the extracted task runtime', () => {
  assert.match(source, /const startupConversationSelection = conversationSelectionGuard\.capture\(\)/)
  assert.match(source, /conversationSelectionGuard\.isCurrent\(startupConversationSelection\)/)
  assert.match(source, /function createNewSession\(\) \{[\s\S]*?conversationSelectionGuard\.invalidate\(\)[\s\S]*?invalidateTaskRuntime\(\)[\s\S]*?disconnectAgentStream\(\)[\s\S]*?contextUsageStatus\.value = null[\s\S]*?resetConversation\(\)/)
  assert.match(source, /async function selectConversation\(conversation, \{ explicit = true \} = \{\}\)/)
})