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
  assert.match(source, /const ConversationPanel = defineAsyncComponent\(\(\) => import\('@\/components\/sidebar\/ConversationPanel\.vue'\)\)/)
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

test('SidebarNav prevents text breaking and adapts gracefully when sidebar is narrow', async () => {
  const source = await readFile(new URL('../sidebar/SidebarNav.vue', import.meta.url), 'utf8')

  assert.match(source, /white-space:\s*nowrap/, 'Tab 按钮必须禁止换行，防止窄宽度下文字上下折叠挤压')
  assert.match(source, /\.sn-btn svg\s*\{[^}]*flex-shrink:\s*0/, 'Tab 图标必须防止被 flex 挤压缩小')
  assert.match(source, /\.sn-btn span\s*\{[^}]*text-overflow:\s*ellipsis/, 'Tab 文本必须配置省略与溢出保护')
  assert.match(source, /container-type:\s*inline-size/, 'Tab 导航栏必须声明容器类型以支持超窄自适应')
})
