import { randomUUID } from 'node:crypto'

const apiBase = process.env.ACCEPTANCE_API_BASE || 'http://127.0.0.1:18080/api'
const runId = randomUUID().replaceAll('-', '')
let token = ''
let projectId = null
let configId = null

async function api(path, { method = 'GET', body, anonymous = false } = {}) {
  const headers = { Accept: 'application/json' }
  if (!anonymous && token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const response = await fetch(`${apiBase}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  const payload = await response.json().catch(() => null)
  if (!response.ok || !payload || payload.code !== 0) {
    throw new Error(`API ${method} ${path} failed: ${payload?.message ?? response.status}`)
  }
  return payload.data
}

async function waitFor(check, label, timeoutMs = 60_000) {
  const deadline = Date.now() + timeoutMs
  let lastError
  while (Date.now() < deadline) {
    try {
      const result = await check()
      if (result) return result
    } catch (error) {
      lastError = error
    }
    await new Promise(resolve => setTimeout(resolve, 200))
  }
  throw new Error(`Timed out waiting for ${label}: ${lastError?.message ?? 'condition remained false'}`)
}

try {
  const registered = await api('/auth/register', {
    method: 'POST', anonymous: true,
    body: {
      username: `websearch_${runId.slice(0, 16)}`,
      password: `B!${randomUUID().replaceAll('-', '')}z9`,
      displayName: 'Web Search Smoke'
    }
  })
  token = registered.token

  const project = await api('/student/projects/empty', {
    method: 'POST', body: { projectName: `websearch-${runId.slice(0, 8)}` }
  })
  projectId = project.projectId

  const config = await api('/student/model-configs', {
    method: 'POST',
    body: {
      configName: 'Web Search Smoke',
      provider: 'acceptance_scripted',
      modelName: 'acceptance-only',
      apiKey: 'acceptance-placeholder-not-a-secret',
      baseUrl: 'acceptance://scripted',
      maxTokens: 4096,
      contextWindowTokens: 32768,
      temperature: 0,
      isDefault: true,
      promptCacheKeyEnabled: false,
      reasoningEffort: 'medium',
      imageInputEnabled: false
    }
  })
  configId = config.configId

  const response = await fetch(`${apiBase}/student/projects/${projectId}/agent/stream`, {
    method: 'POST',
    headers: {
      Accept: 'text/event-stream',
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`
    },
    body: JSON.stringify({
      message: '[acceptance:web-search]',
      mode: 'build',
      sessionId: randomUUID(),
      conversationId: null,
      modelConfigId: configId
    })
  })
  const streamBody = await response.text()
  if (!response.ok) throw new Error(`Agent stream failed: ${response.status} ${streamBody.slice(0, 500)}`)

  const task = await waitFor(async () => {
    const list = await api(`/student/projects/${projectId}/agent/tasks`)
    const latest = list.sort((left, right) => Number(right.taskId) - Number(left.taskId))[0] || null
    return latest?.status === 'completed' ? latest : null
  }, 'web search task')

  const projection = await api(`/student/projects/${projectId}/agent/tasks/${task.taskId}`)
  const part = (projection.toolCalls || []).find(candidate =>
    candidate.tool === 'web_search' && candidate.toolCallId === 'acceptance-web-search')
  if (!part || part.status !== 'completed') {
    throw new Error(`Web search Tool Call did not complete: ${JSON.stringify(projection.toolCalls || [])}`)
  }

  const cache = await api(`/student/projects/${projectId}/agent/acceptance/web-search-cache`)
  if (!cache.redisEnabled || cache.matchingKeys < 1 || cache.queryLeaked) {
    throw new Error(`Web search cache evidence failed: ${JSON.stringify(cache)}`)
  }

  console.log(JSON.stringify({ runId, projectId, taskId: task.taskId, status: task.status, toolPartStatus: part.status, cache }, null, 2))
} finally {
  if (configId) await api(`/student/model-configs/${configId}`, { method: 'DELETE' }).catch(() => {})
  if (projectId) await api(`/student/projects/${projectId}`, { method: 'DELETE' }).catch(() => {})
}
