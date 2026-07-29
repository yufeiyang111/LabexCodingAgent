import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'

const source = [
  await readFile(new URL('./CloudWorkspace.vue', import.meta.url), 'utf8'),
  await readFile(new URL('../styles/cloud-workspace.scoped.scss', import.meta.url), 'utf8'),
  await readFile(new URL('../styles/cloud-workspace.scss', import.meta.url), 'utf8')
].join('\n')
const contextSource = await readFile(new URL('../composables/useContextManagement.js', import.meta.url), 'utf8')

test('renders context-management events as status cards in the unified timeline', () => {
  assert.match(source, /reduceContextManagementEvent/)
  assert.match(source, /item\.type === 'context'/)
  assert.match(source, /ai-context-management-card/)
  assert.match(source, /contextManagementTitle/)
  assert.match(source, /compact\|summarize/)
  assert.match(contextSource, /strategy: 'manual'/)
  assert.match(source, /context-management-pulse/)
})

test('normalizes controlled special Markdown blocks before rendering', () => {
  assert.match(source, /normalizeSpecialMarkdownBlocks\(text\)/)
  assert.match(source, /contextManagementEvents/)
})

test('manual compaction stays pending until durable task events complete it', () => {
  assert.match(contextSource, /async function compactConversation\(conversation\)/)
  assert.match(contextSource, /result\.taskId/)
  assert.match(contextSource, /subscribeToTaskEvents/)
  assert.match(contextSource, /压缩任务已提交/)
  assert.doesNotMatch(contextSource, /COMPACTION_COMPLETED/)
  assert.doesNotMatch(contextSource, /上下文已压缩/)
})