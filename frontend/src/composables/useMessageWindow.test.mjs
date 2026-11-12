import assert from 'node:assert/strict'
import test from 'node:test'

import { ref } from 'vue'

import { useMessageWindow } from './useMessageWindow.js'
import { INITIAL_VISIBLE_MESSAGES, REVEAL_BATCH_MESSAGES } from '../constants/messageWindow.js'

function makeMessages(count) {
  return Array.from({ length: count }, (_, i) => ({ id: i + 1, role: i % 2 ? 'assistant' : 'user', content: `m${i + 1}` }))
}

test('初始窗口只包含最近 INITIAL_VISIBLE_MESSAGES 条，且保留原始下标', () => {
  const messages = ref(makeMessages(20))
  const { visibleMessages, visibleStart, hiddenMessageCount, hasHiddenMessages } = useMessageWindow(messages)

  assert.equal(INITIAL_VISIBLE_MESSAGES, 6)
  assert.equal(visibleMessages.value.length, 6)
  assert.equal(visibleStart.value, 14)
  assert.equal(hiddenMessageCount.value, 14)
  assert.equal(hasHiddenMessages.value, true)
  assert.equal(visibleMessages.value[0].msg.id, 15)
  assert.equal(visibleMessages.value[0].i, 14)
  assert.equal(visibleMessages.value[5].msg.id, 20)
  assert.equal(visibleMessages.value[5].i, 19)
})

test('消息数少于窗口大小时全部可见，没有隐藏项', () => {
  const messages = ref(makeMessages(3))
  const { visibleMessages, visibleStart, hasHiddenMessages } = useMessageWindow(messages)

  assert.equal(visibleMessages.value.length, 3)
  assert.equal(visibleStart.value, 0)
  assert.equal(hasHiddenMessages.value, false)
})

test('展开一批后隐藏数量减少 REVEAL_BATCH_MESSAGES，内容仍连续', () => {
  const messages = ref(makeMessages(20))
  const { visibleMessages, visibleStart, revealEarlierMessages } = useMessageWindow(messages)

  assert.equal(REVEAL_BATCH_MESSAGES, 10)
  assert.equal(revealEarlierMessages(), true)
  assert.equal(visibleStart.value, 4)
  assert.equal(visibleMessages.value.length, 16)
  assert.deepEqual(
    visibleMessages.value.map(v => v.i),
    Array.from({ length: 16 }, (_, i) => i + 4)
  )
})

test('反复展开最终收敛到 0，且不再声称还有隐藏消息', () => {
  const messages = ref(makeMessages(20))
  const { visibleStart, hasHiddenMessages, revealEarlierMessages } = useMessageWindow(messages)

  let guard = 0
  while (revealEarlierMessages() && guard < 50) guard += 1

  assert.equal(visibleStart.value, 0)
  assert.equal(hasHiddenMessages.value, false)
  assert.equal(revealEarlierMessages(), false, '没有隐藏消息时应返回 false')
})

test('新消息到达且窗口贴着末尾时，窗口跟随到最新', () => {
  const messages = ref(makeMessages(20))
  const { visibleStart } = useMessageWindow(messages)

  assert.equal(visibleStart.value, 14)
  messages.value.push({ id: 21, role: 'assistant', content: 'm21' })
  // watch 默认异步 flush，手动触发一次
  return Promise.resolve().then(() => {
    assert.equal(visibleStart.value, 15, '窗口应右移一条，保持最近 6 条可见')
  })
})

test('用户往回翻后新消息到达，不打扰当前窗口', () => {
  const messages = ref(makeMessages(20))
  const { visibleStart, revealEarlierMessages } = useMessageWindow(messages)

  revealEarlierMessages()
  assert.equal(visibleStart.value, 4)

  messages.value.push({ id: 21, role: 'assistant', content: 'm21' })
  return Promise.resolve().then(() => {
    assert.equal(visibleStart.value, 4, '用户正在看历史时窗口不应跳动')
  })
})

test('expandWindowTo 只在目标落在窗口之前时才前移窗口', () => {
  const messages = ref(makeMessages(20))
  const { visibleStart, expandWindowTo } = useMessageWindow(messages)

  expandWindowTo(18)
  assert.equal(visibleStart.value, 14, '目标已在窗口内，不应改变窗口')

  expandWindowTo(2)
  assert.equal(visibleStart.value, 1, '窗口应前移到目标前一条')
})

test('resetVisibleWindow 把窗口重新对齐到最近 N 条', () => {
  const messages = ref(makeMessages(20))
  const { visibleStart, revealEarlierMessages, resetVisibleWindow } = useMessageWindow(messages)

  revealEarlierMessages()
  revealEarlierMessages()
  assert.equal(visibleStart.value, 0)

  resetVisibleWindow()
  assert.equal(visibleStart.value, 14)
})

test('空消息列表不会越界', () => {
  const messages = ref([])
  const { visibleMessages, visibleStart, hasHiddenMessages } = useMessageWindow(messages)

  assert.deepEqual(visibleMessages.value, [])
  assert.equal(visibleStart.value, 0)
  assert.equal(hasHiddenMessages.value, false)
})
