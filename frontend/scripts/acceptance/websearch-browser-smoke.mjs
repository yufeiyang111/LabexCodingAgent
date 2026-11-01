import { spawn } from 'node:child_process'
import { access, mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { CdpClient } from './cdp-client.mjs'

const uiBase = process.env.ACCEPTANCE_UI_BASE || 'http://127.0.0.1:13001'
const apiBase = process.env.ACCEPTANCE_API_BASE || 'http://127.0.0.1:18081/api'
const debugPort = Number.parseInt(process.env.ACCEPTANCE_CDP_PORT || '19223', 10)
const timeoutMs = Number.parseInt(process.env.ACCEPTANCE_BROWSER_TIMEOUT_MS || '120000', 10)
const runId = crypto.randomUUID().replaceAll('-', '')
let token = ''
let userInfo = null
let projectId = null
let configId = null
let browser = null
let profileDir = null
let client = null
const consoleErrors = []
const networkErrors = []
const networkRequests = new Map()

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

function delay(milliseconds) {
  return new Promise(resolve => setTimeout(resolve, milliseconds))
}

async function waitFor(check, label, timeout = timeoutMs) {
  const deadline = Date.now() + timeout
  let lastError
  while (Date.now() < deadline) {
    try {
      const result = await check()
      if (result) return result
    } catch (error) {
      lastError = error
    }
    await delay(150)
  }
  throw new Error(`Timed out waiting for ${label}: ${lastError?.message ?? 'condition remained false'}`)
}

async function findBrowserExecutable() {
  const candidates = [
    process.env.ACCEPTANCE_CHROME_PATH,
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe'
  ].filter(Boolean)
  for (const candidate of candidates) {
    try {
      await access(candidate)
      return candidate
    } catch {
      // 继续检查下一个系统安装路径。
    }
  }
  throw new Error('Chrome/Edge executable not found; set ACCEPTANCE_CHROME_PATH')
}

async function waitForJson(url, predicate) {
  return waitFor(async () => {
    const response = await fetch(url)
    if (!response.ok) return false
    const value = await response.json()
    return predicate(value) ? value : false
  }, url)
}

async function launchBrowser() {
  const executable = await findBrowserExecutable()
  profileDir = await mkdtemp(join(tmpdir(), 'labex-websearch-browser-'))
  browser = spawn(executable, [
    '--headless=new',
    '--disable-gpu',
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-background-networking',
    `--remote-debugging-port=${debugPort}`,
    `--user-data-dir=${profileDir}`,
    'about:blank'
  ], { stdio: 'ignore', windowsHide: true })

  const targets = await waitForJson(
    `http://127.0.0.1:${debugPort}/json/list`,
    value => Array.isArray(value) && value.some(target => target.type === 'page' && target.webSocketDebuggerUrl)
  )
  const page = targets.find(target => target.type === 'page' && target.webSocketDebuggerUrl)
  client = await CdpClient.connect(page.webSocketDebuggerUrl, { timeoutMs: 15_000 })
  await Promise.all([
    client.send('Page.enable'),
    client.send('Runtime.enable'),
    client.send('Log.enable'),
    client.send('Network.enable'),
    client.send('Emulation.setDeviceMetricsOverride', {
      width: 1440, height: 900, deviceScaleFactor: 1, mobile: false
    })
  ])

  client.on('Runtime.exceptionThrown', event => {
    consoleErrors.push(event.exceptionDetails?.exception?.description ?? event.exceptionDetails?.text ?? 'Runtime exception')
  })
  client.on('Log.entryAdded', ({ entry }) => {
    if (entry?.level !== 'error') return
    const url = String(entry.url || '')
    // 外部字体 CDN 在隔离网络下不可达，不影响本地应用功能和缓存验收。
    if (url.endsWith('/favicon.ico') || url.startsWith('https://fonts.googleapis.com/') || url.startsWith('https://fonts.gstatic.com/')) return
    consoleErrors.push(entry.text || `Browser log error${url ? ` url=${url}` : ''}`)
  })
  client.on('Runtime.consoleAPICalled', event => {
    if (!['error', 'warning'].includes(event?.type)) return
    const text = (event.args || []).map(arg => arg.value ?? arg.description ?? '').join(' ')
    if (text) consoleErrors.push(`${event.type}: ${text}`)
  })
  client.on('Network.requestWillBeSent', event => {
    if (event?.requestId && event?.request?.url) networkRequests.set(event.requestId, event.request.url)
  })
  client.on('Network.loadingFailed', event => {
    const url = networkRequests.get(event.requestId)
    networkRequests.delete(event.requestId)
    if (event.canceled) return
    if (String(url || '').startsWith('https://fonts.googleapis.com/') || String(url || '').startsWith('https://fonts.gstatic.com/')) return
    networkErrors.push(`${event.type ?? 'request'}: ${event.errorText ?? 'failed'}${url ? ` url=${url}` : ''}`)
  })
  client.on('Network.loadingFinished', event => networkRequests.delete(event.requestId))
}

async function navigate(url) {
  await client.send('Page.navigate', { url })
  await waitFor(
    () => client.evaluate(`document.readyState === 'complete' && location.href === ${JSON.stringify(url)}`),
    `page ${url}`
  )
}

async function setSession() {
  await navigate(`${uiBase}/login`)
  await client.evaluate(`(() => {
    localStorage.setItem('token', ${JSON.stringify(token)});
    localStorage.setItem('userInfo', ${JSON.stringify(JSON.stringify(userInfo))});
    return true;
  })()`)
}

async function clickCenter(selector, label) {
  const rect = await client.evaluate(`(() => {
    const element = document.querySelector(${JSON.stringify(selector)});
    if (!element) return null;
    element.scrollIntoView({ block: 'center', inline: 'center' });
    const rect = element.getBoundingClientRect();
    return {
      x: rect.left + rect.width / 2,
      y: rect.top + rect.height / 2,
      width: rect.width,
      height: rect.height
    };
  })()`)
  if (!rect || rect.width <= 0 || rect.height <= 0) throw new Error(`${label} is not visible`)
  await client.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: rect.x, y: rect.y })
  await client.send('Input.dispatchMouseEvent', {
    type: 'mousePressed', x: rect.x, y: rect.y, button: 'left', buttons: 1, clickCount: 1
  })
  await client.send('Input.dispatchMouseEvent', {
    type: 'mouseReleased', x: rect.x, y: rect.y, button: 'left', buttons: 0, clickCount: 1
  })
}

async function sendWebSearchMessage() {
  const priorTasks = await api(`/student/projects/${projectId}/agent/tasks`)
  const priorTaskIds = new Set(priorTasks.map(task => String(task.taskId)))

  await waitFor(
    () => client.evaluate(`Boolean(document.querySelector('textarea.composer-textarea'))
      && Boolean(document.querySelector('button.btn-send'))`),
    'workspace composer'
  )
  await clickCenter('textarea.composer-textarea', 'composer textarea')
  await client.send('Input.insertText', { text: '[acceptance:web-search]' })
  await waitFor(
    () => client.evaluate(`document.querySelector('textarea.composer-textarea')?.value === '[acceptance:web-search]'
      && !document.querySelector('button.btn-send')?.disabled`),
    'web search composer readiness'
  )
  await clickCenter('button.btn-send', 'composer send button')

  return waitFor(async () => {
    const tasks = await api(`/student/projects/${projectId}/agent/tasks`)
    const latest = [...tasks].sort((left, right) => Number(right.taskId) - Number(left.taskId))[0] || null
    return latest && !priorTaskIds.has(String(latest.taskId)) ? latest : false
  }, 'new web search task')
}

async function runScenario() {
  const registered = await api('/auth/register', {
    method: 'POST', anonymous: true,
    body: {
      username: `websearch_ui_${runId.slice(0, 16)}`,
      password: `B!${crypto.randomUUID().replaceAll('-', '')}z9`,
      displayName: 'Web Search Browser Smoke'
    }
  })
  token = registered.token
  userInfo = registered.userInfo

  const project = await api('/student/projects/empty', {
    method: 'POST', body: { projectName: `websearch-ui-${runId.slice(0, 8)}` }
  })
  projectId = project.projectId

  const config = await api('/student/model-configs', {
    method: 'POST',
    body: {
      configName: 'Web Search Browser Smoke',
      provider: 'acceptance_scripted', modelName: 'acceptance-only',
      apiKey: 'acceptance-placeholder-not-a-secret', baseUrl: 'acceptance://scripted',
      maxTokens: 4096, contextWindowTokens: 32768, temperature: 0, isDefault: true,
      promptCacheKeyEnabled: false, reasoningEffort: 'medium', imageInputEnabled: false
    }
  })
  configId = config.configId

  await launchBrowser()
  await setSession()
  await navigate(`${uiBase}/workspace/${projectId}`)
  await waitFor(
    () => client.evaluate(`(document.body.innerText || '').includes('acceptance-only')`),
    'acceptance model selection'
  )

  const createdTask = await sendWebSearchMessage()
  const task = await waitFor(async () => {
    const current = await api(`/student/projects/${projectId}/agent/tasks/${createdTask.taskId}`)
    if (current?.status === 'failed' || current?.status === 'cancelled') {
      throw new Error(`Web search task ended as ${current.status}: ${JSON.stringify(current)}`)
    }
    return current?.status === 'completed' ? current : false
  }, 'completed web search task')

  const projection = await api(`/student/projects/${projectId}/agent/tasks/${task.taskId}`)
  const part = (projection.toolCalls || []).find(candidate =>
    candidate.tool === 'web_search' && candidate.toolCallId === 'acceptance-web-search')
  if (!part || part.status !== 'completed') {
    throw new Error(`Web search Tool Call did not complete: ${JSON.stringify(projection.toolCalls || [])}`)
  }

  await waitFor(
    () => client.evaluate(`Boolean(document.querySelector('.tc-card[data-tool-call-id="acceptance-web-search"].tc-completed'))`),
    'completed web search tool card'
  )
  const cardText = await client.evaluate(
    `document.querySelector('.tc-card[data-tool-call-id="acceptance-web-search"]')?.innerText || ''`
  )
  if (!cardText.includes('web_search')) throw new Error(`Web search card is missing its tool name: ${cardText}`)

  const cache = await api(`/student/projects/${projectId}/agent/acceptance/web-search-cache`)
  if (!cache.redisEnabled || cache.matchingKeys < 1 || cache.queryLeaked) {
    throw new Error(`Web search cache evidence failed: ${JSON.stringify(cache)}`)
  }

  const actionableNetworkErrors = networkErrors.filter(message => message.includes('/api/') || message.includes('/ws'))
  if (consoleErrors.length > 0 || actionableNetworkErrors.length > 0) {
    throw new Error(`Browser runtime errors: ${JSON.stringify({ consoleErrors, networkErrors: actionableNetworkErrors })}`)
  }

  return {
    runId,
    projectId,
    taskId: task.taskId,
    status: task.status,
    toolPartStatus: part.status,
    toolCardVisible: true,
    cache,
    consoleErrors: 0,
    networkErrors: 0
  }
}

let result
try {
  result = await runScenario()
  console.log(JSON.stringify(result, null, 2))
} catch (error) {
  let page = null
  try {
    page = await client?.evaluate(`({ url: location.href, title: document.title, body: document.body?.innerText?.slice(0, 1600) || '' })`)
  } catch {
    // 页面已关闭时忽略 CDP 诊断失败。
  }
  console.error(JSON.stringify({ error: error?.message || String(error), consoleErrors, networkErrors, page }, null, 2))
  throw error
} finally {
  try {
    if (configId) await api(`/student/model-configs/${configId}`, { method: 'DELETE' })
  } catch { }
  try {
    if (projectId) await api(`/student/projects/${projectId}`, { method: 'DELETE' })
  } catch { }
  try { await client?.send('Browser.close') } catch { }
  try { client?.close() } catch { }
  try { browser?.kill() } catch { }
  if (profileDir?.startsWith(tmpdir()) && profileDir.includes('labex-websearch-browser-')) {
    await rm(profileDir, { recursive: true, force: true }).catch(() => {})
  }
}