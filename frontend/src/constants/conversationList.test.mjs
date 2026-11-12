import assert from 'node:assert/strict'
import test from 'node:test'
import { formatRelativeTime, selectVisibleConversations, conversationActivityTime } from './conversationList.js'

const NOW = new Date('2026-11-06T16:00:00')

test('formatRelativeTime produces compact relative labels', () => {
  assert.equal(formatRelativeTime('2026-11-06T15:59:40', NOW), '刚刚')
  assert.equal(formatRelativeTime('2026-11-06T15:55:00', NOW), '5分钟')
  assert.equal(formatRelativeTime('2026-11-05T19:00:00', NOW), '21小时')
  assert.equal(formatRelativeTime('2026-11-04T16:00:00', NOW), '2天')
  assert.equal(formatRelativeTime('2026-08-20T03:12:00', NOW), '8月20日')
  assert.equal(formatRelativeTime('2025-08-20T03:12:00', NOW), '2025/8/20')
  assert.equal(formatRelativeTime(null, NOW), '')
  assert.equal(formatRelativeTime('not-a-date', NOW), '')
})

test('conversationActivityTime prefers updateTime over createTime', () => {
  assert.equal(conversationActivityTime({ createTime: 'a', updateTime: 'b' }), 'b')
  assert.equal(conversationActivityTime({ createTime: 'a' }), 'a')
})

test('selectVisibleConversations collapses beyond limit and keeps the active conversation visible', () => {
  const list = Array.from({ length: 25 }, (_, i) => ({ conversationId: `c${i}` }))

  const collapsed = selectVisibleConversations(list, { limit: 20, expanded: false, activeId: 'c1' })
  assert.equal(collapsed.visible.length, 20)
  assert.equal(collapsed.hiddenCount, 5)

  const withHiddenActive = selectVisibleConversations(list, { limit: 20, expanded: false, activeId: 'c23' })
  assert.equal(withHiddenActive.visible.length, 21)
  assert.equal(withHiddenActive.visible.at(-1).conversationId, 'c23')
  assert.equal(withHiddenActive.hiddenCount, 4)

  const expanded = selectVisibleConversations(list, { limit: 20, expanded: true, activeId: null })
  assert.equal(expanded.visible.length, 25)
  assert.equal(expanded.hiddenCount, 0)

  const short = selectVisibleConversations(list.slice(0, 3), { limit: 20, expanded: false, activeId: null })
  assert.equal(short.hiddenCount, 0)
})
