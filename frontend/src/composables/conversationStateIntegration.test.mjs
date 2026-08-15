import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

test('CloudWorkspace delegates durable conversation history and keeps one shared event reducer', async () => {
  const source = await readFile(new URL('../views/CloudWorkspace.vue', import.meta.url), 'utf8')
  assert.match(source, /import \{ useConversationState \} from '@\/composables\/useConversationState'/)
  assert.match(source, /const conversationState = useConversationState\(\{[\s\S]*?replayHistoryEvent,[\s\S]*?onHistoryLoaded: initialScroll[\s\S]*?\}\)/)
  assert.match(source, /function replayHistoryEvent\(type, data, message\) \{[\s\S]*?reduceHistoryEvent\(type, data, message/)
  assert.doesNotMatch(source, /async function loadConversationMessages\(conversationId\) \{[\s\S]*?projectApi\.agentConversationHistory/)
})

test('conversation state uses task cursor and durable turn snapshots instead of legacy event rows', async () => {
  const source = await readFile(new URL('./useConversationState.js', import.meta.url), 'utf8')
  assert.match(source, /agentConversationHistory/)
  assert.match(source, /beforeTaskId/)
  assert.match(source, /applyRunMessageSnapshot/)
  assert.match(source, /applyRunPartSnapshot/)
  assert.doesNotMatch(source, /beforeMessageId/)
  assert.doesNotMatch(source, /eventData/)
  assert.doesNotMatch(source, /event\.messageId/)
  assert.doesNotMatch(source, /toolCall\.status === 'running'\) toolCall\.status = 'completed'/)
})

test('CloudWorkspace keeps a visible file-tree error instead of rendering a failed page request as an empty tree', async () => {
  const source = await readFile(new URL('../views/CloudWorkspace.vue', import.meta.url), 'utf8')
  const panel = await readFile(new URL('../components/sidebar/FileExplorerPanel.vue', import.meta.url), 'utf8')
  const files = await readFile(new URL('./useWorkspaceFiles.js', import.meta.url), 'utf8')
  assert.match(files, /const treeError = ref\(''\)/)
  assert.match(panel, /v-if="treeError"/)
  assert.match(source, /FileExplorerPanel[\s\S]*:tree-error="treeError"/)
  assert.match(files, /treeNextOffset\.value = null/)
})

test('workspace startup restores the persisted conversation instead of assuming the first list item', async () => {
  const source = await readFile(new URL('../views/CloudWorkspace.vue', import.meta.url), 'utf8')
  assert.match(source, /const startupConversation = resolveStartupConversation\(\)/)
  assert.match(source, /await selectConversation\(startupConversation, \{ explicit: false \}\)/)
  assert.doesNotMatch(source, /selectConversation\(conversations\.value\[0\]/)
})
