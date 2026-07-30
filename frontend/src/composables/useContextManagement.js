export function contextManagementTitle(event) {
  if (event.phase === 'pruned') return '已清理历史工具结果'
  if (event.phase === 'tools-reduced') return '已缩减工具上下文'
  if (event.status === 'running') return '正在压缩上下文'
  if (event.status === 'warning') return '上下文压缩正在安全回退'
  if (event.phase === 'fallback') return '上下文压缩完成（安全回退）'
  return '上下文压缩完成'
}

export function contextManagementStatusText(event) {
  if (event.status === 'running') return '处理中'
  if (event.status === 'warning') return '回退中'
  return '已完成'
}

export function contextManagementStrategyText(event) {
  const labels = {
    manual: '手动压缩',
    manual_model: '手动模型摘要',
    manual_deterministic_fallback: '手动安全回退',
    proactive: '自动触发',
    tool_result_prune: '工具结果裁剪',
    tool_schema_reduction: '工具 Schema 缩减',
    model: '模型摘要',
    deterministic_fallback: '确定性安全回退'
  }
  return labels[event.strategy] || ''
}

export function useContextManagement(options) {
  const {
    messages,
    agentLoading,
    projectId,
    currentAgentSession,
    conversations,
    selectedModelConfigId,
    api,
    compactConversationState,
    subscribeToTaskEvents,
    reduceContextManagementEvent,
    notify,
    scheduleAgentRender
  } = options

  function timelineMessage() {
    const existing = [...messages.value].reverse().find(message => message.role === 'assistant')
    if (existing) return existing
    const message = {
      role: 'assistant', content: '', thinking: '', _thinkingDisplay: '', thinkingBlocks: [],
      toolCalls: [], contextManagementEvents: [], isStreaming: false, _nextOrder: 0, timestamp: Date.now()
    }
    messages.value.push(message)
    return message
  }

  async function compactConversation(conversation) {
    if (!conversation?.conversationId) {
      notify.error('缺少会话 ID，无法压缩上下文')
      return false
    }
    if (agentLoading.value) {
      notify.info('Agent 任务运行中，请等当前任务结束后再压缩')
      return false
    }

    const message = timelineMessage()
    try {
      const result = await compactConversationState(conversation, selectedModelConfigId.value)
      if (!result.success) {
        const reason = result.message || '手动压缩失败'
        reduceContextManagementEvent('COMPACTION_FAILED', { strategy: 'manual', reason }, message)
        notify.error(reason)
        return false
      }
      if (!result.taskId) {
        const reason = '压缩任务未返回任务 ID'
        reduceContextManagementEvent('COMPACTION_FAILED', { strategy: 'manual', reason }, message)
        notify.error(reason)
        return false
      }

      const task = {
        taskId: result.taskId,
        conversationId: conversation.conversationId,
        status: result.status || 'queued',
        lastEventSequence: 0
      }
      reduceContextManagementEvent('COMPACTION_STARTED', {
        taskId: result.taskId,
        strategy: 'manual'
      }, message)
      agentLoading.value = true
      void subscribeToTaskEvents(task, message)
      notify.success('压缩任务已提交，完成状态会显示在时间线中')
      return true
    } catch (error) {
      const reason = error?.response?.data?.message || error?.message || '未知错误'
      reduceContextManagementEvent('COMPACTION_FAILED', { strategy: 'manual', reason }, message)
      notify.error('压缩失败：' + reason)
      return false
    } finally {
      scheduleAgentRender()
    }
  }

  async function cancelContextCompaction(event) {
    if (!event?.taskId) return false
    try {
      event.cancelRequested = true
      await api.agentInterrupt(projectId.value, event.sessionId || '', event.taskId)
      notify.info('已请求取消上下文压缩')
      return true
    } catch (error) {
      event.cancelRequested = false
      notify.error('取消压缩失败：' + (error?.response?.data?.message || error?.message || '未知错误'))
      return false
    }
  }

  async function compactCurrentConversation() {
    const conversationId = currentAgentSession.value?.conversationId
    if (!conversationId) {
      notify.info('当前还没有可压缩的会话')
      return false
    }
    const conversation = conversations.value.find(item => item.conversationId === conversationId)
    return compactConversation(conversation || { conversationId })
  }

  return { compactConversation, compactCurrentConversation, cancelContextCompaction }
}