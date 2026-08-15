import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

test('ConversationPanel exposes the conversation actions through component events', async () => {
  const source = await readFile(new URL('../sidebar/ConversationPanel.vue', import.meta.url), 'utf8')

  assert.match(source, /defineProps\(\{[\s\S]*?conversations:[\s\S]*?currentConversationId:/)
  assert.match(source, /defineEmits\(\['select', 'fork', 'compact', 'delete', 'create'\]\)/)
  assert.match(source, /@click="\$emit\('select', conversation\)"/)
  assert.match(source, /@click\.stop="\$emit\('fork', conversation\)"/)
  assert.match(source, /@click\.stop="\$emit\('compact', conversation\)"/)
  assert.match(source, /@click\.stop="\$emit\('delete', conversation\)"/)
  assert.match(source, /@click="\$emit\('create'\)"/)
})

test('CloudWorkspace wires the conversation panel into the left sidebar', async () => {
  const source = await readFile(new URL('../../views/CloudWorkspace.vue', import.meta.url), 'utf8')

  assert.match(source, /import SidebarNav from '@\/components\/sidebar\/SidebarNav\.vue'/)
  assert.match(source, /import ConversationPanel from '@\/components\/sidebar\/ConversationPanel\.vue'/)
  assert.match(source, /<SidebarNav :view="sidebarView" @change="sidebarView = \$event" \/>/)
  assert.match(source, /<ConversationPanel[\s\S]*?@select="selectConversation"[\s\S]*?@create="createNewSession"/)
  assert.doesNotMatch(source, /ConversationMenu/)
})

test('SidebarNav switches between file explorer and conversation views', async () => {
  const source = await readFile(new URL('../sidebar/SidebarNav.vue', import.meta.url), 'utf8')

  assert.match(source, /@click="\$emit\('change', 'files'\)"/)
  assert.match(source, /@click="\$emit\('change', 'conversations'\)"/)
  assert.match(source, /view === 'files'/)
  assert.match(source, /view === 'conversations'/)
})
