import assert from 'node:assert/strict'
import test from 'node:test'
import { createInternalReasoningBlockStreamFilter, createInternalReasoningTagStreamFilter, normalizeSpecialMarkdownBlocks, stripInternalReasoningBlocks, stripInternalReasoningTags } from './agentMarkdown.js'

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


test('removes attributed and HTML-escaped internal reasoning blocks', () => {
  assert.equal(stripInternalReasoningBlocks("visible <THINK data-kind='hidden'>private</THINKING> answer"), 'visible  answer')
  assert.equal(stripInternalReasoningBlocks('visible &lt;think&gt;private&lt;/thinking&gt; answer'), 'visible  answer')
  assert.equal(stripInternalReasoningTags('&lt;THINK data-kind=&quot;hidden&quot;&gt;private&lt;/THINK&gt;'), 'private')
})

test('holds incomplete internal reasoning blocks across streamed deltas', () => {
  const filter = createInternalReasoningBlockStreamFilter()
  assert.equal(filter.push('visible <TH'), 'visible ')
  assert.equal(filter.push('INK>private'), '')
  assert.equal(filter.push(' plan</THINKING> answer'), ' answer')
})


test('holds attributed reasoning delimiters split across final-output deltas', () => {
  const filter = createInternalReasoningBlockStreamFilter()
  assert.equal(filter.push('visible <TH'), 'visible ')
  assert.equal(filter.push("INK data-kind='hidden'>private"), '')
  assert.equal(filter.push(' plan</THINKING> answer'), ' answer')
})

test('removes protocol delimiters split across dedicated reasoning deltas', () => {
  const filter = createInternalReasoningTagStreamFilter()
  assert.equal(filter.push('<thi'), '')
  assert.equal(filter.push('nk>private plan</THINK'), 'private plan')
  assert.equal(filter.push('ING>'), '')
})

test('removes Markdown-escaped internal reasoning tags across streamed deltas', () => {
  const tagFilter = createInternalReasoningTagStreamFilter()
  assert.equal(tagFilter.push('\\'), '')
  assert.equal(tagFilter.push('<THINK\\>private plan\\</TH'), 'private plan')
  assert.equal(tagFilter.push('INK\\>'), '')

  const blockFilter = createInternalReasoningBlockStreamFilter()
  assert.equal(blockFilter.push('Visible \\'), 'Visible ')
  assert.equal(blockFilter.push('<think\\>outer \\<thinking\\>inner\\</thinking\\> tail\\</think\\> answer'), ' answer')
})

test('keeps nested reasoning private and treats self-closing protocol tags as delimiters only', () => {
  assert.equal(stripInternalReasoningBlocks('before <think>outer <thinking>inner</thinking> tail</think> after'), 'before  after')
  assert.equal(stripInternalReasoningBlocks('before <think/> after'), 'before  after')
  assert.equal(stripInternalReasoningBlocks('before \\<thinking/\\> after'), 'before  after')
})