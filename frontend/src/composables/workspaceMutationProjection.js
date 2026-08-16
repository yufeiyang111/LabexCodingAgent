function normalizedIdentifier(value) {
  if (value === null || value === undefined) return ''
  return String(value).trim()
}

function workspaceChangeKey(data, eventId) {
  const taskId = normalizedIdentifier(data?.taskId)
  const changeId = normalizedIdentifier(data?.workspaceChangeId)
    || normalizedIdentifier(data?.approvalId)
    || normalizedIdentifier(data?.toolCallId)
    || normalizedIdentifier(eventId)
  return taskId && changeId ? `${taskId}:${changeId}` : ''
}

/**
 * 将 durable workspace 变更事件投影为当前工作区的文件树刷新。
 * 仅接受当前路由项目且归属当前 assistant task 的事件，避免历史回放或其他 task 污染文件树。
 */
export function createWorkspaceMutationProjection({ projectId, loadRoot }) {
  const appliedChangeKeys = new Set()

  async function projectWorkspaceChange(event, message) {
    const data = event?.data || {}
    const eventProjectId = normalizedIdentifier(data.projectId)
    const currentProjectId = normalizedIdentifier(projectId?.value)
    const eventTaskId = normalizedIdentifier(data.taskId)
    const messageTaskId = normalizedIdentifier(message?.taskId)
    if (!eventProjectId || eventProjectId !== currentProjectId) return false
    if (!eventTaskId || eventTaskId !== messageTaskId) return false

    const key = workspaceChangeKey(data, event?.eventId)
    if (!key || appliedChangeKeys.has(key)) return false
    appliedChangeKeys.add(key)
    try {
      const refreshed = await loadRoot()
      if (refreshed === true) return true
      appliedChangeKeys.delete(key)
      return false
    } catch {
      appliedChangeKeys.delete(key)
      return false
    }
  }

  return { projectWorkspaceChange }
}
