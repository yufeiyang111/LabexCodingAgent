export function useAgentInteraction({ projectId, api }) {
  async function submitPermissionDecision({ call, action, feedback }) {
    const request = call?.permissionRequest
    if (!request?.requestId) return { handled: false, reason: 'request_missing' }

    const rejected = action === 'reject'
    call.status = rejected ? 'error' : 'running'
    call.result = rejected
      ? (feedback ? `已拒绝执行：${feedback}` : '已拒绝执行')
      : '已确认，等待工具继续执行...'

    try {
      const response = await api.agentApprovePermission(projectId.value, {
        requestId: request.requestId,
        action,
        feedback: rejected ? (feedback || '用户拒绝了本次工具调用') : (feedback || '')
      })
      if (response?.data && !response.data.granted && !rejected) {
        call.status = 'error'
        call.result = response.data.feedback || '权限确认失败'
        return { handled: true, success: false }
      }
      return { handled: true, success: true }
    } catch (error) {
      call.status = 'error'
      call.result = '权限确认提交失败：' + (error?.response?.data?.message || error?.message || '未知错误')
      return { handled: true, success: false }
    }
  }

  async function submitQuestionReply({ call, action, answer }) {
    const request = call?.questionRequest
    if (!request?.requestId) return { handled: false, reason: 'request_missing' }
    if (action === 'answer' && !answer?.trim()) {
      return { handled: false, reason: 'answer_required' }
    }

    const submittedAnswer = answer || ''
    const answered = action === 'answer'
    call.status = answered ? 'running' : 'error'
    call.questionAnswer = submittedAnswer
    call.result = answered
      ? `已提交回答：${submittedAnswer}\n等待 Agent 继续执行...`
      : '已取消这次提问'

    try {
      const response = await api.agentReplyQuestion(projectId.value, {
        requestId: request.requestId,
        action,
        answer: submittedAnswer
      })
      if (response?.data && !response.data.answered && answered) {
        call.status = 'error'
        call.result = response.data.feedback || '回答提交失败'
        return { handled: true, success: false }
      }
      return { handled: true, success: true }
    } catch (error) {
      call.status = 'error'
      call.result = '回答提交失败：' + (error?.response?.data?.message || error?.message || '未知错误')
      return { handled: true, success: false }
    }
  }

  return {
    submitPermissionDecision,
    submitQuestionReply
  }
}
