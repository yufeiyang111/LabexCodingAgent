import assert from 'node:assert/strict'
import test from 'node:test'
import { createInternalReasoningBlockStreamFilter, normalizeSpecialMarkdownBlocks, stripInternalReasoningBlocks, stripInternalReasoningTags } from './agentMarkdown.js'

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


test('removes internal reasoning delimiters including split streaming prefixes', () => {
  assert.equal(stripInternalReasoningTags('visible <THINK>private</think> answer'), 'visible private answer')
  assert.equal(stripInternalReasoningTags('visible <thin'), 'visible ')
  assert.equal(stripInternalReasoningTags('visible </THINKING> answer'), 'visible  answer')
})


test('removes full internal reasoning blocks from final output', () => {
  assert.equal(stripInternalReasoningBlocks('visible <THINK>private</think> answer'), 'visible  answer')
  assert.equal(stripInternalReasoningBlocks('visible <thinking>private</THINKING> answer'), 'visible  answer')
})


test('holds incomplete internal reasoning blocks across streamed deltas', () => {
  const filter = createInternalReasoningBlockStreamFilter()
  assert.equal(filter.push('visible <TH'), 'visible ')
  assert.equal(filter.push('INK>private'), '')
  assert.equal(filter.push(' plan</THINKING> answer'), ' answer')
})
