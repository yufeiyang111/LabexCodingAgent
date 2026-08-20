export function useAgentInteraction({ projectId, api }) {
  async function submitPermissionDecision(payload) {
    const { call, action, feedback, permissionRequest, networkRequest, requestId: payloadRequestId } = payload || {}
    const request = networkRequest || permissionRequest || call?.networkRequest || call?.permissionRequest
    const requestId = payloadRequestId || request?.requestId || request?.interactionId
    if (!requestId) return { handled: false, reason: 'request_missing' }
    const network = !!(networkRequest || call?.networkRequest)

    const rejected = action === 'reject'
    if (call) {
      call.status = rejected ? 'error' : 'running'
      call.result = rejected
        ? (feedback ? `已拒绝执行：${feedback}` : '已拒绝执行')
        : '已确认，等待工具继续执行...'
    }

    try {
      const response = await (network ? api.agentApproveNetwork : api.agentApprovePermission)(projectId.value, {
        requestId,
        action,
        feedback: rejected ? (feedback || '用户拒绝了本次工具调用') : (feedback || '')
      })
      if (response?.data && !(network ? response.data.approved : response.data.granted) && !rejected) {
        if (call) {
          call.status = 'error'
          call.result = response.data.feedback || '权限确认失败'
        }
        return { handled: true, success: false }
      }
      if (call) {
        call.interactionStatus = 'resuming'
      }
      return { handled: true, success: true }
    } catch (error) {
      if (call) {
        call.status = 'error'
        call.result = '权限确认提交失败：' + (error?.response?.data?.message || error?.message || '未知错误')
      }
      return { handled: true, success: false }
    }
  }

  async function submitQuestionReply(payload) {
    const { call, action, answer, questionRequest, requestId: payloadRequestId } = payload || {}
    const request = questionRequest || call?.questionRequest
    const requestId = payloadRequestId || request?.requestId || request?.interactionId
    if (!requestId) return { handled: false, reason: 'request_missing' }
    if (action === 'answer' && !answer?.trim()) {
      return { handled: false, reason: 'answer_required' }
    }

    const submittedAnswer = answer || ''
    const answered = action === 'answer'
    if (call) {
      call.status = answered ? 'running' : 'error'
      call.questionAnswer = submittedAnswer
      call.result = answered
        ? `已提交回答：${submittedAnswer}\n等待 Agent 继续执行...`
        : '已取消这次提问'
    }

    try {
      const response = await api.agentReplyQuestion(projectId.value, {
        requestId,
        action,
        answer: submittedAnswer
      })
      if (response?.data && !response.data.answered && answered) {
        if (call) {
          call.status = 'error'
          call.result = response.data.feedback || '回答提交失败'
        }
        return { handled: true, success: false }
      }
      if (call) {
        call.interactionStatus = 'resuming'
      }
      return { handled: true, success: true }
    } catch (error) {
      if (call) {
        call.status = 'error'
        call.result = '回答提交失败：' + (error?.response?.data?.message || error?.message || '未知错误')
      }
      return { handled: true, success: false }
    }
  }

  return {
    submitPermissionDecision,
    submitQuestionReply
  }
}
