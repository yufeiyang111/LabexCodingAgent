import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'

const source = await readFile(new URL('./CloudWorkspace.vue', import.meta.url), 'utf8')

test('renders context-management events as status cards in the unified timeline', () => {
  assert.match(source, /reduceContextManagementEvent/)
  assert.match(source, /item\.type === 'context'/)
  assert.match(source, /ai-context-management-card/)
  assert.match(source, /contextManagementTitle/)
  assert.match(source, /compact\|summarize/)
  assert.match(source, /strategy: 'manual'/)
  assert.match(source, /context-management-pulse/)
})

test('normalizes controlled special Markdown blocks before rendering', () => {
  assert.match(source, /normalizeSpecialMarkdownBlocks\(text\)/)
  assert.match(source, /contextManagementEvents/)
})
test('manual compaction stays pending until durable task events complete it', () => {
  const block = source.match(/async function compactConversation\(conversation\) \{[\s\S]*?\n\}/)?.[0] || ''
  assert.match(block, /result\.taskId/)
  assert.match(block, /subscribeToTaskEvents/)
  assert.match(block, /压缩任务已提交/)
  assert.doesNotMatch(block, /COMPACTION_COMPLETED/)
  assert.doesNotMatch(block, /上下文已压缩/)
})
