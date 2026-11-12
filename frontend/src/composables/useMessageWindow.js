import { computed, ref, watch } from 'vue'

import { INITIAL_VISIBLE_MESSAGES, REVEAL_BATCH_MESSAGES } from '../constants/messageWindow.js'

/**
 * 消息列表渲染窗口。
 *
 * 会话历史一次把整页 20 轮塞进 DOM 时，长会话首屏要挂载上千节点（每条消息含
 * Markdown、工具卡、diff、代码高亮），点击后 1 秒多才出画面。这里只把最近
 * INITIAL_VISIBLE_MESSAGES 条交给模板，更早的留在 messages 里但不进 DOM；
 * 往上滚到顶时先按 REVEAL_BATCH_MESSAGES 条本地展开，本地都展开完了才请求服务端更早的一页。
 *
 * 侧栏（CloudWorkspace）与中栏（CenterAiWorkspace）共用这一份实现：
 * 两处的滚动容器不同，但窗口本身的行为必须一致，否则同一个会话在两边看起来会不一样。
 *
 * @param {import('vue').Ref<Array>} messages 完整消息数组（含被隐藏的更早消息）
 */
export function useMessageWindow(messages) {
  const visibleStart = ref(0)

  const hiddenMessageCount = computed(() => visibleStart.value)
  const hasHiddenMessages = computed(() => visibleStart.value > 0)

  const visibleMessages = computed(() => {
    const all = messages.value || []
    const start = Math.min(visibleStart.value, Math.max(0, all.length - 1))
    return all.slice(start).map((msg, offset) => ({ msg, i: start + offset }))
  })

  /** 把窗口对齐到「最近 N 条」。 */
  function resetVisibleWindow() {
    visibleStart.value = Math.max(0, (messages.value?.length || 0) - INITIAL_VISIBLE_MESSAGES)
  }

  // 挂载时先对齐一次：历史消息可能早于本组件创建（中栏在会话已加载后才被拖出来）。
  resetVisibleWindow()

  /**
   * 新消息到达（用户发问、Agent 回包）时窗口要跟上，否则刚发出的消息会落在
   * 窗口之外、屏幕上什么也不出现。判断依据是「窗口原本贴着末尾」：
   * 用户正在往回翻历史时不打扰他。
   */
  watch(
    () => messages.value?.length || 0,
    (next, prev) => {
      if (next <= prev) return
      const wasAtTail = prev - visibleStart.value <= INITIAL_VISIBLE_MESSAGES
      if (wasAtTail) resetVisibleWindow()
    }
  )

  /** 本地展开一批；返回是否真的展开了（false 表示已经没有隐藏消息）。 */
  function revealEarlierMessages() {
    if (!hasHiddenMessages.value) return false
    visibleStart.value = Math.max(0, visibleStart.value - REVEAL_BATCH_MESSAGES)
    return true
  }

  /** 把窗口扩到能看见指定下标的消息（用于「跳转到第 N 条」）。 */
  function expandWindowTo(index) {
    if (index >= 0 && index < visibleStart.value) {
      visibleStart.value = Math.max(0, index - 1)
    }
  }

  return {
    visibleStart,
    hiddenMessageCount,
    hasHiddenMessages,
    visibleMessages,
    resetVisibleWindow,
    revealEarlierMessages,
    expandWindowTo
  }
}
