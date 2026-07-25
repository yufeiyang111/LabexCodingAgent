import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

test('ConversationMenu exposes the existing conversation actions through component events', async () => {
  const source = await readFile(new URL('./ConversationMenu.vue', import.meta.url), 'utf8')

  assert.match(source, /defineProps\(\{[\s\S]*?conversations:[\s\S]*?currentConversationId:/)
  assert.match(source, /defineEmits\(\['select', 'fork', 'compact', 'delete', 'create'\]\)/)
  assert.match(source, /@click="emit\('select', conversation\)"/)
  assert.match(source, /@click\.stop="emit\('fork', conversation\)"/)
  assert.match(source, /@click\.stop="emit\('compact', conversation\)"/)
  assert.match(source, /@click\.stop="emit\('delete', conversation\)"/)
  assert.match(source, /@click="emit\('create'\)"/)
})

test('CloudWorkspace lazy-loads the conversation history dropdown', async () => {
  const source = await readFile(new URL('../../views/CloudWorkspace.vue', import.meta.url), 'utf8')

  assert.match(source, /const ConversationMenu = defineAsyncComponent\(\(\) => import\('@\/components\/cloud\/ConversationMenu\.vue'\)\)/)
  assert.match(source, /<ConversationMenu[\s\S]*?@select="selectConversation"[\s\S]*?@create="createNewSession"/)
})

