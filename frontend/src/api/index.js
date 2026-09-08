import request from '@/utils/request'
import axios from 'axios'

export const authApi = {
  login(data) {
    return request.post('/auth/login', data, authResponseConfig())
  },
  register(data) {
    return request.post('/auth/register', data, authResponseConfig())
  },
  inviteRegister(data) {
    return request.post('/auth/invite-register', data, authResponseConfig())
  },
  getAuthConfig(options = {}) {
    return request.get('/auth/config', { ...authResponseConfig(), silent: Boolean(options?.silent) })
  },
  getCaptcha(scene = 'login', options = {}) {
    return request.get('/auth/captcha', { ...authResponseConfig(), params: { scene }, silent: Boolean(options?.silent) })
  },
  getOAuthProviders(options = {}) {
    return request.get('/auth/oauth/providers', { ...authResponseConfig(), silent: Boolean(options?.silent) })
  },
  getOAuthBindings(options = {}) {
    return request.get('/auth/oauth/bindings', { ...authResponseConfig(), silent: Boolean(options?.silent) })
  },
  unbindOAuth(provider, options = {}) {
    return request.delete('/auth/oauth/' + encodeURIComponent(provider) + '/binding', { ...authResponseConfig(), silent: Boolean(options?.silent) })
  },
  startOAuthBinding(provider) {
    return request.post('/auth/oauth/' + encodeURIComponent(provider) + '/bind/start', {}, authResponseConfig())
  },
  getUserInfo() {
    return request.get('/auth/userinfo')
  },
  updateEmail(email, options = {}) {
    return request.put('/auth/email', { email }, { ...authResponseConfig(), silent: Boolean(options?.silent) })
  },
  clearEmail(options = {}) {
    return request.delete('/auth/email', { ...authResponseConfig(), silent: Boolean(options?.silent) })
  },
  updateUsername(username, options = {}) {
    return request.put('/auth/username', { username }, { ...authResponseConfig(), silent: Boolean(options?.silent) })
  }
}

function authResponseConfig() {
  return { silent: true, validateStatus: (status) => status >= 200 && status < 600 }
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
  agentSubagents(projectId, taskId) {
    return request.get('/student/projects/' + projectId + '/agent/tasks/' + encodeURIComponent(taskId) + '/subagents')
  },
  agentSubagent(projectId, subagentId, params = {}) {
    return request.get('/student/projects/' + projectId + '/agent/subagents/' + encodeURIComponent(subagentId), { params })
  },
  agentActiveTask(projectId, conversationId) {
    return request.get('/student/projects/' + projectId + '/agent/conversations/' + encodeURIComponent(conversationId) + '/active-task')
  },
  agentRetryEnvironment(projectId, taskId) {
    return request.post('/student/projects/' + projectId + '/agent/tasks/' + encodeURIComponent(taskId) + '/retry-environment')
  },
  agentResumeLoopGuard(projectId, taskId) {
    return request.post('/student/projects/' + projectId + '/agent/tasks/' + encodeURIComponent(taskId) + '/resume-loop-guard')
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
  readFile(id, path, options = {}) {
    return request.get('/student/projects/' + id + '/files', {
      params: { path },
      silent: Boolean(options?.silent)
    })
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
  deleteItem(projectId, path, config = {}) {
    return request.delete('/student/projects/' + projectId + '/files/item', { params: { path }, ...config })
  },
  copyItem(projectId, sourcePath, targetParentPath, decisions) {
    return request.post('/student/projects/' + projectId + '/files/copy', {
      sourcePath,
      targetParentPath,
      decisions: decisions || null
    })
  },
  moveItem(projectId, sourcePath, targetParentPath, decisions) {
    return request.put('/student/projects/' + projectId + '/files/item/move', {
      sourcePath,
      targetParentPath,
      decisions: decisions || null
    })
  },
  uploadFiles(projectId, targetDir, files, options = {}) {
    const formData = new FormData()
    files.forEach(file => formData.append('files', file))
    if (targetDir) formData.append('targetDir', targetDir)
    if (options.relativePaths && options.relativePaths.length) {
      formData.append('relativePathsJson', JSON.stringify(options.relativePaths))
    }
    if (options.decisions && Object.keys(options.decisions).length) {
      formData.append('decisionsJson', JSON.stringify(options.decisions))
    }
    return request.post('/student/projects/' + projectId + '/files/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 0
    })
  },
  searchFiles(projectId, keyword, options = {}) {
    const params = { q: keyword }
    if (options.dir) params.dir = options.dir
    if (options.regex) params.regex = true
    if (options.caseSensitive) params.caseSensitive = true
    if (options.include) params.include = options.include
    return request.get('/student/projects/' + projectId + '/files/search', {
      params,
      silent: Boolean(options.silent)
    })
  },
  fileImage(projectId, path) {
    return request.get('/student/projects/' + projectId + '/files/image', {
      params: { path },
      responseType: 'blob'
    })
  },
  downloadFile(projectId, path) {
    return request.get('/student/projects/' + projectId + '/files/download', {
      params: { path },
      responseType: 'blob',
      timeout: 0
    })
  },
  fileHistory(projectId, path, page = 1, pageSize = 50) {
    return request.get('/student/projects/' + projectId + '/agent/files/history', {
      params: { path, page, pageSize }
    })
  },
  fileHistoryDiff(projectId, changeId) {
    return request.get('/student/projects/' + projectId + '/agent/files/history/' + encodeURIComponent(changeId) + '/diff')
  },
  exportProject(projectId) {
    return request.get('/student/projects/' + projectId + '/export', { responseType: 'blob', timeout: 0 })
  },
  createExportJob(projectId, includeAll) {
    return request.post('/student/projects/' + projectId + '/export/jobs', { includeAll: Boolean(includeAll) })
  },
  getExportJob(projectId, jobId) {
    return request.get('/student/projects/' + projectId + '/export/jobs/' + encodeURIComponent(jobId), {
      silent: true
    })
  },
  cancelExportJob(projectId, jobId) {
    return request.delete('/student/projects/' + projectId + '/export/jobs/' + encodeURIComponent(jobId), {
      silent: true
    })
  },
  downloadExportJob(projectId, jobId) {
    return request.get('/student/projects/' + projectId + '/export/jobs/' + encodeURIComponent(jobId) + '/download', {
      responseType: 'blob',
      timeout: 0
    })
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

export const MONITOR_TOKEN_KEY = 'labex-monitor-token'
export const MONITOR_OPERATOR_KEY = 'labex-monitor-operator-code'

const monitorClient = axios.create({
  baseURL: '/api/ops',
  timeout: 15000
})

monitorClient.interceptors.request.use((config) => {
  const token = localStorage.getItem(MONITOR_TOKEN_KEY)
  if (token) {
    config.headers['X-Monitor-Token'] = token
  }
  return config
})

monitorClient.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body?.code === 0) {
      return body.data
    }
    const err = new Error(body?.message || '请求失败')
    err.code = body?.code
    err.status = response.status
    return Promise.reject(err)
  },
  (error) => {
    const status = error.response?.status
    if (status === 401) {
      localStorage.removeItem(MONITOR_TOKEN_KEY)
    }
    const body = error.response?.data
    const err = new Error(body?.message || error.message || '网络异常')
    err.code = body?.code
    err.status = status
    return Promise.reject(err)
  }
)

export const monitorApi = {
  getToken() {
    return localStorage.getItem(MONITOR_TOKEN_KEY)
  },
  setToken(token) {
    localStorage.setItem(MONITOR_TOKEN_KEY, token)
  },
  clearToken() {
    localStorage.removeItem(MONITOR_TOKEN_KEY)
  },
  getOperatorCode() {
    return sessionStorage.getItem(MONITOR_OPERATOR_KEY) || ''
  },
  setOperatorCode(code) {
    sessionStorage.setItem(MONITOR_OPERATOR_KEY, code)
  },
  clearOperatorCode() {
    sessionStorage.removeItem(MONITOR_OPERATOR_KEY)
  },
  auth(accessCode) {
    return monitorClient.post('/auth', { accessCode })
  },
  logout() {
    return monitorClient.post('/logout').catch(() => null)
  },
  summary() {
    return monitorClient.get('/summary')
  },
  traffic(range) {
    return monitorClient.get('/traffic', { params: { range } })
  },
  topPaths(days = 7, limit = 10) {
    return monitorClient.get('/top-paths', { params: { days, limit } })
  },
  statusDistribution(days = 7) {
    return monitorClient.get('/status-distribution', { params: { days } })
  },
  system() {
    return monitorClient.get('/system')
  },
  recentVisitors(limit = 20) {
    return monitorClient.get('/recent-visitors', { params: { limit } })
  },
  visitors(days = 7, limit = 50) {
    return monitorClient.get('/visitors', { params: { days, limit } })
  },
  healthSummary() {
    return monitorClient.get('/health/summary')
  },
  healthDependencies() {
    return monitorClient.get('/health/dependencies')
  },
  runtimeTasks(params) {
    return monitorClient.get('/runtime/tasks', { params })
  },
  runtimeTask(taskId) {
    return monitorClient.get('/runtime/tasks/' + taskId)
  },
  runtimeTimeline(taskId, params = {}) {
    return monitorClient.get('/runtime/tasks/' + taskId + '/timeline', { params })
  },
    metricsOverview() {
      return monitorClient.get('/metrics/overview')
    },
    metricsTimeseries(range) {
      return monitorClient.get('/metrics/timeseries', { params: { range } })
    },
    alerts(params) {
      return monitorClient.get('/alerts', { params })
    },
    alert(alertId) {
      return monitorClient.get('/alerts/' + alertId)
    },
    acknowledgeAlert(alertId, body = {}) {
      return monitorClient.post('/alerts/' + alertId + '/acknowledge', body)
    },
    silenceAlert(alertId, durationMinutes, body = {}) {
      return monitorClient.post('/alerts/' + alertId + '/silence', { durationMinutes, ...body })
    },
    resolveAlert(alertId, reason, body = {}) {
      return monitorClient.post('/alerts/' + alertId + '/resolve', { reason, ...body })
    },
    alertRules(params) {
      return monitorClient.get('/alerts/rules', { params })
    },
    createAlertRule(body) {
      return monitorClient.post('/alerts/rules', body)
    },
    updateAlertRule(ruleId, body) {
      return monitorClient.put('/alerts/rules/' + ruleId, body)
    },
    deleteAlertRule(ruleId) {
      return monitorClient.delete('/alerts/rules/' + ruleId)
    },
    incidents(params) {
      return monitorClient.get('/incidents', { params })
    },
    incident(incidentId) {
      return monitorClient.get('/incidents/' + incidentId)
    },
    incidentTimeline(incidentId) {
      return monitorClient.get('/incidents/' + incidentId + '/timeline')
    },
    createIncident(body) {
      return monitorClient.post('/incidents', body)
    },
    incidentTransition(incidentId, target, body = {}) {
      return monitorClient.post('/incidents/' + incidentId + '/' + target, body)
    },
    events(params) {
      return monitorClient.get('/events', { params })
    },
    auditLogs(params) {
      return monitorClient.get('/audit', { params })
    },
    operations(params) {
      return monitorClient.get('/operations', { params })
    },
    cancelTask(taskId, body = {}) {
      return monitorClient.post('/operations/tasks/' + taskId + '/cancel', body)
    },
    retryTask(taskId, body = {}) {
      return monitorClient.post('/operations/tasks/' + taskId + '/retry', body)
    },
    recoverLeases(body = {}) {
      return monitorClient.post('/operations/leases/recover', body)
    },
    pauseWorker(workerId, body = {}) {
      return monitorClient.post('/operations/workers/' + workerId + '/pause', body)
    },
    resumeWorker(workerId, body = {}) {
      return monitorClient.post('/operations/workers/' + workerId + '/resume', body)
    },
    userOverview(range = '7d') {
      return monitorClient.get('/users/overview', { params: { range } })
    },
    users(params = {}) {
      return monitorClient.get('/users', { params })
    },
    userDetail(userId) {
      return monitorClient.get('/users/' + userId)
    },
    userActivities(userId, params = {}) {
      return monitorClient.get('/users/' + userId + '/activities', { params })
    },
    userAction(userId, action, body = {}) {
      return monitorClient.post('/users/' + userId + '/actions/' + action, body)
    }
  }


export const tutorialApi = {
  list() {
    return request.get('/tutorials')
  },
  get(slug) {
    return request.get('/tutorials/' + encodeURIComponent(slug))
  }
}
