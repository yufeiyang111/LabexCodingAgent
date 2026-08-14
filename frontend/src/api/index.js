import request from '@/utils/request'

export const authApi = {
  login(data) {
    return request.post('/auth/login', data)
  },
  register(data) {
    return request.post('/auth/register', data)
  },
  getUserInfo() {
    return request.get('/auth/userinfo')
  }
}

export const projectApi = {
  askAgent(projectId, data) {
    return request.post('/student/projects/' + projectId + '/agent/ask', data)
  },
  optimizePrompt(projectId, data) {
    return request.post('/student/projects/' + projectId + '/agent/prompt/optimize', data)
  },
  agentCommands(projectId) {
    return request.get('/student/projects/' + projectId + '/agent/commands')
  },
  runCommand(projectId, data) {
    return request.post('/student/projects/' + projectId + '/agent/commands', data)
  },
  agentInterrupt(projectId, sessionId, taskId = null) {
    const payload = { sessionId }
    if (taskId != null) payload.taskId = taskId
    return request.post('/student/projects/' + projectId + '/agent/interrupt', payload)
  },
  agentConversations(projectId) {
    return request.get('/student/projects/' + projectId + '/agent/conversations')
  },
  agentConversationHistory(projectId, conversationId, params = {}) {
    return request.get('/student/projects/' + projectId + '/agent/conversations/' + conversationId + '/messages', { params })
  },
  agentAttachmentPreview(projectId, attachmentId) {
    return request.get('/student/projects/' + projectId + '/agent/attachments/' + attachmentId + '/preview', { responseType: 'blob' })
  },
  agentDeleteConversation(projectId, conversationId) {
    return request.delete('/student/projects/' + projectId + '/agent/conversations/' + conversationId)
  },
  agentForkConversation(projectId, conversationId, messageId, taskId) {
    return request.post('/student/projects/' + projectId + '/agent/conversations/' + conversationId + '/fork', {
      messageId: messageId || null,
      taskId: taskId || null
    })
  },
  agentCompactConversation(projectId, conversationId, payload = {}) {
    return request.post('/student/projects/' + projectId + '/agent/conversations/' + conversationId + '/compact', payload)
  },
  agentConversationMemory(projectId, conversationId) {
    return request.get('/student/projects/' + projectId + '/agent/conversations/' + conversationId + '/memory')
  },
  agentApprovePermission(projectId, data) {
    return request.post('/student/projects/' + projectId + '/agent/permission/approve', data)
  },
  agentApproveNetwork(projectId, data) {
    return request.post('/student/projects/' + projectId + '/agent/network/approve', data)
  },
  agentDecideCommandApproval(projectId, approvalId, data) {
    return request.post('/student/projects/' + projectId + '/agent/command-approvals/' + encodeURIComponent(approvalId) + '/decision', data)
  },
  agentExecuteCommandApproval(projectId, approvalId) {
    return request.post('/student/projects/' + projectId + '/agent/command-approvals/' + encodeURIComponent(approvalId) + '/execute')
  },
  agentReplyQuestion(projectId, data) {
    return request.post('/student/projects/' + projectId + '/agent/question/reply', data)
  },
  agentTokenStats(projectId, conversationId) {
    return request.get('/student/projects/' + projectId + '/agent/tokens/' + conversationId)
  },
  agentTokenSummary(projectId) {
    return request.get('/student/projects/' + projectId + '/agent/tokens/student/summary')
  },
  agentContextStatus(projectId, conversationId) {
    return request.get('/student/projects/' + projectId + '/agent/conversations/' + encodeURIComponent(conversationId) + '/context-status')
  },
  agentImageAttachmentPolicy(projectId) {
    return request.get('/student/projects/' + projectId + '/agent/attachments/policy')
  },
  agentNextContextPreview(projectId, conversationId, payload = {}) {
    return request.post('/student/projects/' + projectId + '/agent/conversations/' + encodeURIComponent(conversationId) + '/context-preview', payload)
  },
  agentTasks(projectId) {
    return request.get('/student/projects/' + projectId + '/agent/tasks')
  },
  agentTask(projectId, taskId) {
    return request.get('/student/projects/' + projectId + '/agent/tasks/' + encodeURIComponent(taskId))
  },
  agentActiveTask(projectId, conversationId) {
    return request.get('/student/projects/' + projectId + '/agent/conversations/' + encodeURIComponent(conversationId) + '/active-task')
  },
  agentRetryEnvironment(projectId, taskId) {
    return request.post('/student/projects/' + projectId + '/agent/tasks/' + encodeURIComponent(taskId) + '/retry-environment')
  },
  agentCompletionEvidence(projectId, taskId) {
    return request.get('/student/projects/' + projectId + '/agent/tasks/' + encodeURIComponent(taskId) + '/completion-evidence')
  },
  terminalRun(projectId, command, timeoutSeconds) {
    return request.post('/student/projects/' + projectId + '/terminal/run', { command, timeoutSeconds: timeoutSeconds || 60 })
  },
  terminalCreateSession(projectId, options = {}) {
    return request.post('/student/projects/' + projectId + '/terminal/sessions', {
      name: options.name,
      path: options.path
    })
  },
  terminalRunSession(projectId, sessionId, command, options = {}) {
    return request.post('/student/projects/' + projectId + '/terminal/sessions/' + sessionId + '/run', {
      command,
      path: options.path,
      timeoutSeconds: options.timeoutSeconds || 60,
      longRunning: Boolean(options.longRunning)
    })
  },
  terminalDecideApproval(projectId, approvalId, action) {
    return request.post('/student/projects/' + projectId + '/terminal/approvals/' + encodeURIComponent(approvalId) + '/decision', {
      action,
      decisionIdempotencyKey: crypto.randomUUID()
    })
  },
  terminalExecuteApproval(projectId, approvalId) {
    return request.post('/student/projects/' + projectId + '/terminal/approvals/' + encodeURIComponent(approvalId) + '/execute')
  },
  terminalGetSession(projectId, sessionId) {
    return request.get('/student/projects/' + projectId + '/terminal/sessions/' + sessionId)
  },
  terminalStopSession(projectId, sessionId) {
    return request.post('/student/projects/' + projectId + '/terminal/sessions/' + sessionId + '/stop')
  },
  terminalDeleteSession(projectId, sessionId) {
    return request.delete('/student/projects/' + projectId + '/terminal/sessions/' + sessionId)
  },
  agentChanges(projectId) {
    return request.get('/student/projects/' + projectId + '/agent/changes')
  },
  agentDiff(projectId, changeId) {
    return request.get('/student/projects/' + projectId + '/agent/diff/' + changeId)
  },
  agentApplyDiff(projectId, changeId) {
    return request.post('/student/projects/' + projectId + '/agent/diff/' + changeId + '/apply')
  },
  agentRejectDiff(projectId, changeId) {
    return request.post('/student/projects/' + projectId + '/agent/diff/' + changeId + '/reject')
  },
  agentUndoDiff(projectId, changeId) {
    return request.post('/student/projects/' + projectId + '/agent/diff/' + changeId + '/undo')
  },
  list() {
    return request.get('/student/projects')
  },
  createEmpty(name) {
    return request.post('/student/projects/empty', { projectName: name })
  },
  upload(file, name) {
    const formData = new FormData()
    formData.append('file', file)
    if (name) formData.append('projectName', name)
    return request.post('/student/projects/upload', formData, { headers: { 'Content-Type': 'multipart/form-data' } })
  },
  detail(id) {
    return request.get('/student/projects/' + id)
  },
  delete(id) {
    return request.delete('/student/projects/' + id)
  },
  getTree(id, path) {
    return request.get('/student/projects/' + id + '/tree', { params: { path: path || '' } })
  },
  getTreePage(id, path, offset = 0, limit = 100) {
    return request.get('/student/projects/' + id + '/tree/page', {
      params: { path: path || '', offset, limit }
    })
  },
  readFile(id, path) {
    return request.get('/student/projects/' + id + '/files', { params: { path } })
  },
  saveFile(id, path, content) {
    return request.put('/student/projects/' + id + '/files', { content }, { params: { path } })
  },
  createWithTemplate(name, templateKey) {
    return request.post('/student/projects/template', null, { params: { name, templateKey } })
  },
  renameItem(id, path, newName) {
    return request.put('/student/projects/' + id + '/files/item/rename', { path, newName })
  },
  createItem(projectId, parentPath, name, type) {
    return request.post('/student/projects/' + projectId + '/files/item', { parentPath, name, type })
  },
  deleteItem(projectId, path) {
    return request.delete('/student/projects/' + projectId + '/files/item', { params: { path } })
  },
  exportProject(projectId) {
    return request.get('/student/projects/' + projectId + '/export', { responseType: 'blob' })
  },
  renameProject(projectId, name) {
    return request.put('/student/projects/' + projectId + '/rename', { name })
  }
}

export const modelConfigApi = {
  list() {
    return request.get('/student/model-configs')
  },
  get(configId) {
    return request.get('/student/model-configs/' + configId)
  },
  create(data) {
    return request.post('/student/model-configs', data)
  },
  update(configId, data) {
    return request.put('/student/model-configs/' + configId, data)
  },
  delete(configId) {
    return request.delete('/student/model-configs/' + configId)
  },
  getDefault() {
    return request.get('/student/model-configs/default')
  },
  getProviders() {
    return request.get('/student/model-configs/providers')
  },
  listModels(data) {
    return request.post('/student/model-configs/model-list', data)
  },
  testConnection(configId) {
    return request.post('/student/model-configs/' + configId + '/test')
  }
}

export const agentExtensionApi = {
  listSkills() {
    return request.get('/student/agent/extensions/skills')
  },
  createSkill(data) {
    return request.post('/student/agent/extensions/skills', data)
  },
  updateSkill(skillId, data) {
    return request.put('/student/agent/extensions/skills/' + skillId, data)
  },
  deleteSkill(skillId) {
    return request.delete('/student/agent/extensions/skills/' + skillId)
  },
  listMcpServers() {
    return request.get('/student/agent/extensions/mcp')
  },
  createMcpServer(data) {
    return request.post('/student/agent/extensions/mcp', data)
  },
  updateMcpServer(serverId, data) {
    return request.put('/student/agent/extensions/mcp/' + serverId, data)
  },
  deleteMcpServer(serverId) {
    return request.delete('/student/agent/extensions/mcp/' + serverId)
  }
}
