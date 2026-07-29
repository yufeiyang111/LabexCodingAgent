import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

test('CloudWorkspace delegates conversation state and leaves the history event reducer local', async () => {
  const source = await readFile(new URL('../views/CloudWorkspace.vue', import.meta.url), 'utf8')

  assert.match(source, /import \{ useConversationState \} from '@\/composables\/useConversationState'/)
  assert.match(source, /const conversationState = useConversationState\(\{[\s\S]*?replayHistoryEvent,[\s\S]*?onHistoryLoaded: initialScroll[\s\S]*?\}\)/)
  assert.match(source, /import \{ reduceContextManagementEvent, reduceHistoryEvent \} from '@\/composables\/agentHistoryReducer'/)
  assert.match(source, /function replayHistoryEvent\(type, data, message\) \{[\s\S]*?reduceHistoryEvent\(type, data, message/)
  assert.doesNotMatch(source, /async function loadConversations\(\)/)
  assert.doesNotMatch(source, /async function loadConversationMessages\(conversationId\) \{[\s\S]*?projectApi\.agentMessages/)
})


test('conversation state exposes cursor-based loading of older history', async () => {
  const source = await readFile(new URL('./useConversationState.js', import.meta.url), 'utf8')
  assert.match(source, /async function loadOlderMessages\(\)/)
  assert.match(source, /beforeMessageId/)
  assert.match(source, /hasOlderMessages/)
})


test('CloudWorkspace keeps a visible file-tree error instead of rendering a failed page request as an empty tree', async () => {
  const source = await readFile(new URL('../views/CloudWorkspace.vue', import.meta.url), 'utf8')
  const files = await readFile(new URL('./useWorkspaceFiles.js', import.meta.url), 'utf8')
  assert.match(files, /const treeError = ref\(''\)/)
  assert.match(source, /v-if="treeError"/)
  assert.match(files, /treeNextOffset\.value = null/)
})
