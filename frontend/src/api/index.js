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
  runCommand(projectId, data) {
    return request.post('/student/projects/' + projectId + '/agent/commands', data)
  },
  agentInterrupt(projectId, sessionId) {
    return request.post('/student/projects/' + projectId + '/agent/interrupt', { sessionId })
  },
  agentConversations(projectId) {
    return request.get('/student/projects/' + projectId + '/agent/conversations')
  },
  agentMessages(projectId, conversationId) {
    return request.get('/student/projects/' + projectId + '/agent/conversations/' + conversationId + '/messages')
  },
  agentDeleteConversation(projectId, conversationId) {
    return request.delete('/student/projects/' + projectId + '/agent/conversations/' + conversationId)
  },
  agentForkConversation(projectId, conversationId, messageId) {
    return request.post('/student/projects/' + projectId + '/agent/conversations/' + conversationId + '/fork', { messageId: messageId || null })
  },
  agentCompactConversation(projectId, conversationId) {
    return request.post('/student/projects/' + projectId + '/agent/conversations/' + conversationId + '/compact')
  },
  agentConversationMemory(projectId, conversationId) {
    return request.get('/student/projects/' + projectId + '/agent/conversations/' + conversationId + '/memory')
  },
  agentApprovePermission(projectId, data) {
    return request.post('/student/projects/' + projectId + '/agent/permission/approve', data)
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
  terminalRun(projectId, command, timeoutSeconds) {
    return request.post('/student/projects/' + projectId + '/terminal/run', { command, timeoutSeconds: timeoutSeconds || 60 })
  },
  terminalCreateSession(projectId) {
    return request.post('/student/projects/' + projectId + '/terminal/sessions', {})
  },
  terminalRunSession(projectId, sessionId, command, timeoutSeconds) {
    return request.post('/student/projects/' + projectId + '/terminal/sessions/' + sessionId + '/run', { command, timeoutSeconds: timeoutSeconds || 60 })
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
