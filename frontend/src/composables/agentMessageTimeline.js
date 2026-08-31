// 主 Agent 中部视图与子代理会话标签共用的消息时间线原语。
// 从 CloudWorkspace 原样抽取；两处必须保持同一实现。

/**
 * 合并时间线解析器（思考块 / 上下文事件 / 工具卡按 _order 排序，带每消息缓存）。
 * @param {import('vue').Ref<boolean>} showThinkingProcessRef
 */
export function createMergedItemsResolver(showThinkingProcessRef) {
  return function getMergedItems(msg) {
    if (!msg) return []
    const thinkCount = msg.thinkingBlocks?.length || 0
    const ctxCount = msg.contextManagementEvents?.length || 0
    const toolCount = msg.toolCalls?.length || 0
    const showThinking = showThinkingProcessRef.value
    const versionKey = `${showThinking}:${thinkCount}:${ctxCount}:${toolCount}:${msg._nextOrder || 0}`

    if (msg._mergedItemsCache && msg._mergedItemsCacheKey === versionKey) {
      return msg._mergedItemsCache
    }

    const items = []
    if (showThinking && msg.thinkingBlocks) {
      for (const tb of msg.thinkingBlocks) {
        items.push({ type: 'thinking', data: tb, _order: tb._order || 0 })
      }
    }
    if (msg.contextManagementEvents) {
      for (const event of msg.contextManagementEvents) {
        items.push({ type: 'context', data: event, _order: event._order || 0 })
      }
    }
    if (msg.toolCalls) {
      for (const tc of msg.toolCalls) {
        items.push({ type: 'tool', data: tc, _order: tc._order || 0 })
      }
    }
    items.sort((a, b) => a._order - b._order)
    msg._mergedItemsCache = items
    msg._mergedItemsCacheKey = versionKey
    return items
  }
}

export function startThinkingReveal(msg) {
  msg._thinkingDisplay = msg.thinking || ''
}
export function stopThinkingReveal(msg) {
  msg._thinkingTimer = null
}
export function flushThinkingDisplay(msg) {
  stopThinkingReveal(msg)
  msg._thinkingDisplay = msg.thinking || ''
}
