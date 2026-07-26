import assert from 'node:assert/strict'
import test from 'node:test'
import { normalizeSpecialMarkdownBlocks } from './agentMarkdown.js'

test('normalizes supported callout directives into safe Markdown blockquotes', () => {
  assert.equal(normalizeSpecialMarkdownBlocks(':::warning\n请先备份数据库。\n:::'), '> [!WARNING]\n> 请先备份数据库。')
})

test('does not treat directives inside fenced code as formatting instructions', () => {
  const input = '```md\n:::warning\n示例\n:::\n```'
  assert.equal(normalizeSpecialMarkdownBlocks(input), input)
})

test('keeps unsupported or unterminated directives as ordinary text', () => {
  assert.equal(normalizeSpecialMarkdownBlocks(':::custom\n内容\n:::'), ':::custom\n内容\n:::')
  assert.equal(normalizeSpecialMarkdownBlocks(':::tip\n内容'), ':::tip\n内容')
})
