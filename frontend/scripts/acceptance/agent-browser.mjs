import { spawn } from 'node:child_process'
import { access, mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { CdpClient } from './cdp-client.mjs'
import { redactEvidence } from './evidence.mjs'
import { isExpectedRestartTransportError } from './browser-error-policy.mjs'

const uiBase = process.env.ACCEPTANCE_UI_BASE || 'http://127.0.0.1:13000'
const apiBase = process.env.ACCEPTANCE_API_BASE || 'http://127.0.0.1:18080/api'
const debugPort = Number.parseInt(process.env.ACCEPTANCE_CDP_PORT || '19222', 10)
const timeoutMs = Number.parseInt(process.env.ACCEPTANCE_BROWSER_TIMEOUT_MS || '90000', 10)
const restartHandoffDir = process.env.ACCEPTANCE_RESTART_HANDOFF_DIR || ''
const interactionRestartHandoffDir = process.env.ACCEPTANCE_INTERACTION_RESTART_HANDOFF_DIR || ''
const handoffStatusDir = restartHandoffDir || interactionRestartHandoffDir
const runId = crypto.randomUUID().replaceAll('-', '')
let token = ''
let projectId = null
let configId = null
const configIds = []
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

function chromeCandidates() {
  return [
    process.env.ACCEPTANCE_CHROME_PATH,
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe'
  ].filter(Boolean)
}

async function findBrowserExecutable() {
  const { access } = await import('node:fs/promises')
  for (const candidate of chromeCandidates()) {
    try {
      await access(candidate)
      return candidate
    } catch {
      // 继续检查下一个系统安装路径。
    }
  }
  throw new Error('Chrome/Edge executable not found; set ACCEPTANCE_CHROME_PATH')
}

async function waitForJson(url, predicate = value => Boolean(value)) {
  const deadline = Date.now() + timeoutMs
  let lastError
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url)
      if (response.ok) {
        const value = await response.json()
        if (predicate(value)) return value
      }
    } catch (error) {
      lastError = error
    }
    await delay(150)
  }
  throw new Error(`Timed out waiting for ${url}: ${lastError?.message ?? 'no matching response'}`)
}

async function launchBrowser() {
  const executable = await findBrowserExecutable()
  profileDir = await mkdtemp(join(tmpdir(), 'labex-agent-browser-'))
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

  const targets = await waitForJson(`http://127.0.0.1:${debugPort}/json/list`, value =>
    Array.isArray(value) && value.some(target => target.type === 'page' && target.webSocketDebuggerUrl))
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
    const url = entry.url ? ` url=${entry.url}` : ''
    consoleErrors.push(`${entry.text || 'Browser log error'}${url}`)
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
    if (!event.canceled) networkErrors.push(`${event.type ?? 'request'}: ${event.errorText ?? 'failed'}${url ? ` url=${url}` : ''}`)
  })
  client.on('Network.loadingFinished', event => {
    networkRequests.delete(event.requestId)
  })
}

async function navigate(url) {
  await client.send('Page.navigate', { url })
  await waitFor(() => client.evaluate('document.readyState === "complete"'))
}

async function waitFor(check, message = 'browser condition') {
  const deadline = Date.now() + timeoutMs
  let lastError
  while (Date.now() < deadline) {
    try {
      if (await check()) return
    } catch (error) {
      lastError = error
    }
    await delay(150)
  }
  throw new Error(`Timed out waiting for ${message}: ${lastError?.message ?? 'condition remained false'}`)
}

function delay(milliseconds) {
  return new Promise(resolve => setTimeout(resolve, milliseconds))
}


async function clickElement(selector, { containsText = '', label = selector, native = false } = {}) {
  await client.evaluate(`(() => {
    const candidates = Array.from(document.querySelectorAll(${JSON.stringify(selector)}))
    const element = candidates.find(candidate => {
      if (candidate.disabled) return false
      if (!${JSON.stringify(containsText)}) return true
      const scope = candidate.closest('.tc-question, .tc-approval, .ai-session-dropdown') || candidate
      return (scope.innerText || '').includes(${JSON.stringify(containsText)})
    })
    if (!element) throw new Error(${JSON.stringify(`${label} not found`)})
    const rect = element.getBoundingClientRect()
    if (rect.width <= 0 || rect.height <= 0) throw new Error(${JSON.stringify(`${label} is not visible`)})
    element.scrollIntoView({ block: 'center', inline: 'center' })
    element.focus?.()
    if (${native ? 'true' : 'false'}) {
      element.click()
      return
    }
    for (const type of ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click']) {
      const EventType = type.startsWith('pointer') && window.PointerEvent ? PointerEvent : MouseEvent
      element.dispatchEvent(new EventType(type, { bubbles: true, cancelable: true, composed: true, view: window, button: 0 }))
    }
  })()`)
}

async function waitForRestartContinuation(handoffDir) {
  if (!handoffDir) return false
  await mkdir(handoffDir, { recursive: true })
  const continuationFile = join(handoffDir, 'continue.signal')
  await waitFor(async () => {
    try {
      await access(continuationFile)
      return true
    } catch {
      return false
    }
  }, 'backend restart continuation signal')
  return true
}

async function setSession() {
  await navigate(`${uiBase}/login`)
  const session = JSON.stringify({ token, userInfo: globalThis.__acceptanceUserInfo })
  await client.evaluate(`(() => {
    const session = ${session};
    localStorage.setItem('token', session.token);
    localStorage.setItem('userInfo', JSON.stringify(session.userInfo));
    return true;
  })()`)
}

async function waitForWorkspace() {
  await waitFor(() => client.evaluate(`Boolean(document.querySelector('.ai-input-text-area textarea'))`), 'Agent input')
}

async function assertDesktopWorkspaceLayout() {
  const layout = await client.evaluate(`(() => {
    const shell = document.querySelector('.ws-shell');
    const body = document.querySelector('.ws-body');
    const sidebar = document.querySelector('.ws-sidebar');
    const center = document.querySelector('.ws-center');
    const panel = document.querySelector('.ai-panel');
    if (!shell || !body || !sidebar || !center || !panel) return null;
    const shellStyle = getComputedStyle(shell);
    const bodyStyle = getComputedStyle(body);
    const shellRect = shell.getBoundingClientRect();
    const bodyRect = body.getBoundingClientRect();
    const sidebarRect = sidebar.getBoundingClientRect();
    const centerRect = center.getBoundingClientRect();
    const panelRect = panel.getBoundingClientRect();
    return {
      shellDisplay: shellStyle.display,
      shellDirection: shellStyle.flexDirection,
      bodyDisplay: bodyStyle.display,
      shellWidth: Math.round(shellRect.width),
      shellHeight: Math.round(shellRect.height),
      bodyHeight: Math.round(bodyRect.height),
      sidebarWidth: Math.round(sidebarRect.width),
      centerWidth: Math.round(centerRect.width),
      panelWidth: Math.round(panelRect.width),
      sidebarLeft: Math.round(sidebarRect.left),
      centerLeft: Math.round(centerRect.left),
      panelLeft: Math.round(panelRect.left),
      sidebarTop: Math.round(sidebarRect.top),
      centerTop: Math.round(centerRect.top),
      panelTop: Math.round(panelRect.top)
    };
  })()`)
  if (!layout) throw new Error('Desktop workspace regions are missing')
  const horizontal = layout.sidebarLeft < layout.centerLeft && layout.centerLeft < layout.panelLeft
  const aligned = Math.abs(layout.sidebarTop - layout.centerTop) <= 2 && Math.abs(layout.centerTop - layout.panelTop) <= 2
  if (layout.shellDisplay !== 'flex' || layout.shellDirection !== 'column' || layout.bodyDisplay !== 'flex'
      || layout.shellWidth < 1200 || layout.shellHeight < 800 || layout.bodyHeight < 600
      || layout.sidebarWidth < 180 || layout.centerWidth < 300 || layout.panelWidth < 320
      || !horizontal || !aligned) {
    throw new Error(`Workspace did not render as a desktop layout: ${JSON.stringify(layout)}`)
  }
  return layout
}

async function waitForAgentIdle(label = 'agent terminal state') {
  await waitFor(
    () => client.evaluate(`Boolean(document.querySelector('button.ai-submit-btn'))
      && !document.querySelector('.ai-generating-indicator')`),
    label
  )
}

async function sendMessage(message) {
  const encoded = JSON.stringify(message)
  const visiblePrefix = JSON.stringify(message.slice(0, 96))
  await client.evaluate(`(() => {
    const textarea = document.querySelector('.ai-input-text-area textarea');
    if (!textarea) throw new Error('Agent textarea missing');
    const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set;
    setter.call(textarea, ${encoded});
    textarea.dispatchEvent(new Event('input', { bubbles: true }));
    textarea.focus();
    return true;
  })()`)
  const waitForReadyComposer = () => waitFor(
    () => client.evaluate(`(() => {
      const textarea = document.querySelector('.ai-input-text-area textarea');
      const submit = document.querySelector('button.ai-submit-btn');
      return textarea?.value === ${encoded}
        && !textarea.disabled
        && Boolean(submit)
        && !submit.disabled
        && !document.querySelector('.ai-generating-indicator');
    })()`),
    'agent composer readiness'
  )
  await waitForReadyComposer()
  // 页面刷新后 active-task 恢复可能稍晚于输入框挂载；要求 composer 连续稳定后再发送。
  await delay(350)
  await waitForReadyComposer()
  // 通过输入框的真实 Enter 键路径触发 Vue 事件，避免脚本 click() 在刷新后丢失处理器。
  await client.send('Input.dispatchKeyEvent', {
    type: 'keyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, nativeVirtualKeyCode: 13
  })
  await client.send('Input.dispatchKeyEvent', {
    type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, nativeVirtualKeyCode: 13
  })
  await delay(350)
  const submittedByKeyboard = await client.evaluate(`(() => {
    const textarea = document.querySelector('.ai-input-text-area textarea');
    return textarea?.value === '' && document.body.innerText.includes(${visiblePrefix});
  })()`)
  if (!submittedByKeyboard) {
    // 刷新后的首次合成按键偶尔会丢失；等待真正的发送按钮恢复后走完整鼠标事件链。
    await waitForReadyComposer()
    await clickElement('button.ai-submit-btn', { label: 'agent submit button fallback' })
  }
  await waitFor(
    () => client.evaluate(`(() => {
      const textarea = document.querySelector('.ai-input-text-area textarea');
      return textarea?.value === '' && document.body.innerText.includes(${visiblePrefix});
    })()`),
    `submitted user message ${message.slice(0, 96)}`
  )
}

async function bodyIncludes(text) {
  return client.evaluate(`document.body.innerText.includes(${JSON.stringify(text)})`)
}

async function markerCount(text) {
  return client.evaluate(`document.body.innerText.split(${JSON.stringify(text)}).length - 1`)
}

async function createNewConversation() {
  await waitFor(
    () => client.evaluate(`Boolean(document.querySelector('.ai-input-text-area textarea'))`),
    'chat tab before creating a conversation'
  )
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const menuOpen = await client.evaluate(`Boolean(document.querySelector('.ai-session-new'))`)
    if (menuOpen) break
    await clickElement('.ai-session-select', { label: 'conversation selector', native: true })
    for (let probe = 0; probe < 20; probe += 1) {
      if (await client.evaluate(`Boolean(document.querySelector('.ai-session-new'))`)) break
      await delay(100)
    }
  }
  await waitFor(() => client.evaluate(`Boolean(document.querySelector('.ai-session-new'))`), 'new conversation action')
  await clickElement('.ai-session-new', { label: 'new conversation action', native: true })
  await waitFor(() => client.evaluate(`!document.querySelector('.ai-session-dropdown')`), 'conversation menu close')
}

async function selectConversationByTitle(title) {
  await waitFor(
    () => client.evaluate(`Boolean(document.querySelector('.ai-input-text-area textarea'))`),
    'chat tab before selecting a conversation'
  )
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const menuOpen = await client.evaluate(`Boolean(document.querySelector('.ai-session-dropdown'))`)
    if (!menuOpen) {
      await clickElement('.ai-session-select', { label: 'conversation selector', native: true })
    }
    const found = await client.evaluate(`Array.from(document.querySelectorAll('.ai-session-item .ai-session-title'))
      .some(element => (element.textContent || '').trim() === ${JSON.stringify(title)})`)
    if (found) break
    await delay(150)
  }
  await waitFor(
    () => client.evaluate(`Array.from(document.querySelectorAll('.ai-session-item .ai-session-title'))
      .some(element => (element.textContent || '').trim() === ${JSON.stringify(title)})`),
    `conversation ${title}`
  )
  await client.evaluate(`(() => {
    const title = ${JSON.stringify(title)};
    const item = Array.from(document.querySelectorAll('.ai-session-item'))
      .find(candidate => (candidate.querySelector('.ai-session-title')?.textContent || '').trim() === title);
    if (!item) throw new Error('Conversation item not found: ' + title);
    item.click();
  })()`)
  await waitFor(() => client.evaluate(`!document.querySelector('.ai-session-dropdown')`), 'conversation menu close')
}

async function assertInternalReasoningProtocolHidden(label) {
  const leakedFragments = await client.evaluate(`(() => {
    const text = (document.body?.innerText || '').toLowerCase()
    return ['<thi', 'nk>acceptance runtime scenario selected.', '</think', 'ing>']
      .filter(fragment => text.includes(fragment))
  })()`)
  if (Array.isArray(leakedFragments) && leakedFragments.length > 0) {
    throw new Error(`${label} rendered internal reasoning protocol fragments: ${leakedFragments.join(', ')}`)
  }
}

async function runScenario() {
  const username = `browser_${runId.slice(0, 16)}`
  const password = `B!${crypto.randomUUID().replaceAll('-', '')}z9`
  const registered = await api('/auth/register', {
    method: 'POST',
    anonymous: true,
    body: { username, password, displayName: 'Browser Acceptance' }
  })
  token = registered.token
  globalThis.__acceptanceUserInfo = registered.userInfo

  const project = await api('/student/projects/empty', {
    method: 'POST', body: { projectName: `browser-${runId.slice(0, 8)}` }
  })
  projectId = project.projectId
  const config = await api('/student/model-configs', {
    method: 'POST',
    body: {
      configName: 'Browser Acceptance', provider: 'acceptance_scripted', modelName: 'acceptance-only',
      apiKey: 'acceptance-placeholder-not-a-secret', baseUrl: 'acceptance://scripted',
      maxTokens: 4096, contextWindowTokens: 32768, temperature: 0, isDefault: true,
      promptCacheKeyEnabled: false, reasoningEffort: 'medium', imageInputEnabled: false
    }
  })
  configId = config.configId
  configIds.push(configId)

  await launchBrowser()
  await setSession()
  await navigate(`${uiBase}/workspace/${projectId}`)
  await waitForWorkspace()
  const desktopLayout = await assertDesktopWorkspaceLayout()

  const markerA = 'Conversation isolation marker: A-ONLY'
  const markerB = 'Conversation isolation marker: B-ONLY'
  await sendMessage('[acceptance:isolation:A-ONLY]')
  await waitFor(() => bodyIncludes(markerA), 'conversation A final reply')

  await createNewConversation()
  await waitFor(async () => !(await bodyIncludes(markerA)), 'conversation A detachment')
  if (await bodyIncludes(markerA)) throw new Error('New conversation still renders conversation A context')

  await sendMessage('[acceptance:isolation:B-ONLY] [acceptance:reasoning-boundary]')
  await waitFor(() => bodyIncludes(markerB), 'conversation B final reply')
  if (await bodyIncludes(markerA)) throw new Error('Conversation B rendered conversation A context')
  await assertInternalReasoningProtocolHidden('Live projection')

  await client.send('Page.reload', { ignoreCache: true })
  await waitForWorkspace()
  await waitFor(() => bodyIncludes(markerB), 'conversation B replay after refresh')
  const duplicates = await markerCount(markerB)
  if (duplicates !== 1) throw new Error(`Expected one replayed B marker, found ${duplicates}`)
  await assertInternalReasoningProtocolHidden('Refresh replay')
  const cursorKeys = await client.evaluate(`Object.keys(sessionStorage).filter(key => key.startsWith('labex-agent:task-event-cursor:'))`)
  if (!Array.isArray(cursorKeys) || cursorKeys.length === 0) {
    throw new Error('No persisted task event cursor was observed after refresh')
  }

  const cacheConfig = await api('/student/model-configs', {
    method: 'POST',
    body: {
      configName: 'Browser Cache Telemetry', provider: 'acceptance_scripted', modelName: 'acceptance-cache',
      apiKey: 'acceptance-placeholder-not-a-secret', baseUrl: 'acceptance://scripted',
      maxTokens: 4096, contextWindowTokens: 32768, temperature: 0, isDefault: true,
      promptCacheKeyEnabled: true, reasoningEffort: 'medium', imageInputEnabled: false
    }
  })
  configIds.push(cacheConfig.configId)
  await client.send('Page.reload', { ignoreCache: true })
  await waitForWorkspace()
  await waitFor(() => bodyIncludes('acceptance-cache'), 'cache telemetry model selection')
  await createNewConversation()
  await sendMessage('[acceptance:cache-telemetry]')
  await waitFor(() => bodyIncludes('Prompt cache telemetry was emitted'), 'cache telemetry final reply')
  await waitForAgentIdle('cache telemetry terminal state')
  await clickElement('.ai-tab', { containsText: '用量', label: 'usage tab' })
  await waitFor(() => client.evaluate(`Boolean(document.querySelector('.usage-cache-card'))`), 'cache telemetry card')
  const cacheCard = await client.evaluate(`document.querySelector('.usage-cache-card')?.innerText || ''`)
  if (!cacheCard.includes('已命中') || !cacheCard.includes('25.00%')
      || !cacheCard.includes('读取 50 tokens') || !cacheCard.includes('写入 10 tokens')) {
    throw new Error(`Cache telemetry card mismatch: ${cacheCard}`)
  }
  const cacheSummary = await api(`/student/projects/${projectId}/agent/tokens/student/summary`)
  if (cacheSummary?.cacheStatus !== 'hit' || Number(cacheSummary?.cacheHitRate) !== 25
      || Number(cacheSummary?.totalCachedTokens) < 50 || Number(cacheSummary?.totalCacheWriteTokens) < 10) {
    throw new Error(`Durable cache telemetry summary mismatch: ${JSON.stringify(cacheSummary)}`)
  }
  await client.send('Page.reload', { ignoreCache: true })
  await waitForWorkspace()
  await clickElement('.ai-tab', { containsText: '用量', label: 'usage tab after refresh' })
  await waitFor(() => client.evaluate(`(document.querySelector('.usage-cache-card')?.innerText || '').includes('已命中')`), 'cache telemetry replay after refresh')
  await clickElement('.ai-tab', { containsText: '对话', label: 'chat tab after cache telemetry' })

  await createNewConversation()
  await sendMessage('[acceptance:question]')
  await waitFor(
    () => client.evaluate(`Boolean(document.querySelector('.tc-question'))`),
    'question reply component'
  )
  const questionCard = await client.evaluate(`(() => {
    const card = document.querySelector('.tc-question')
    return card ? { text: card.innerText, optionCount: card.querySelectorAll('.tc-option-btn').length } : null
  })()`)
  if (!questionCard || !questionCard.text.includes('\u662f\u5426\u7ee7\u7eed\u771f\u5b9e\u9a8c\u6536\uff1f') || questionCard.optionCount < 1) {
    throw new Error(`Question reply component was not hydrated: ${JSON.stringify(questionCard)}`)
  }
  let waitingTasks = []
  let waitingTask = null
  await waitFor(async () => {
    waitingTasks = await api(`/student/projects/${projectId}/agent/tasks`)
    waitingTask = [...waitingTasks]
      .sort((left, right) => Number(right.taskId) - Number(left.taskId))
      .find(task => task.status === 'waiting_user')
    return Boolean(waitingTask?.conversationId)
  }, 'durable waiting_user task')
  const activeTask = await api(`/student/projects/${projectId}/agent/conversations/${encodeURIComponent(waitingTask.conversationId)}/active-task`)
  const providerMessages = (activeTask?.runMessages || []).filter(message => String(message.messageKey || '').startsWith('provider:'))
  const providerParts = (activeTask?.parts || []).filter(part => String(part.partKey || '').startsWith('provider:'))
  if (providerMessages.length < 3) {
    throw new Error(`Durable Provider transcript messages were not persisted: ${JSON.stringify(providerMessages)}`)
  }
  if (!providerParts.some(part => part.partType === 'tool_call' && part.toolCallId)) {
    throw new Error(`Durable Provider tool-call part was not persisted: ${JSON.stringify(providerParts)}`)
  }
  await clickElement('.tc-question .tc-option-btn', { label: 'question option', native: true })
  await waitFor(() => client.evaluate(`Boolean(document.querySelector('.tc-question textarea')?.value?.trim())`), 'question answer selection')
  await clickElement('.tc-question .tc-approval-btn.primary', { label: 'question answer submit', native: true })
  await waitFor(
    () => client.evaluate(`!document.querySelector('.tc-question') && document.body.innerText.includes('durable user-question interaction resumed')`),
    'question reply completion'
  )

  await createNewConversation()
  const tasksBeforePermission = await api(`/student/projects/${projectId}/agent/tasks`)
  const permissionPriorTaskIds = new Set(tasksBeforePermission.map(task => Number(task.taskId)))
  await sendMessage('[acceptance:permission-batch]')
  await waitFor(
    () => client.evaluate(`Array.from(document.querySelectorAll('.tc-approval')).some(card => card.innerText.includes('需要确认后才能继续执行'))`),
    'permission approval component'
  )
  let permissionTasks = []
  let permissionTask = null
  await waitFor(async () => {
    permissionTasks = await api(`/student/projects/${projectId}/agent/tasks`)
    permissionTask = [...permissionTasks]
      .sort((left, right) => Number(right.taskId) - Number(left.taskId))
      .find(task => task.status === 'waiting_approval' && !permissionPriorTaskIds.has(Number(task.taskId)))
    return Boolean(permissionTask?.taskId)
  }, 'durable waiting_approval task')
  const permissionBatchCallIds = [
    'acceptance-permission-batch-read',
    'acceptance-permission-batch-list'
  ]
  const permissionProjection = await api(`/student/projects/${projectId}/agent/tasks/${permissionTask.taskId}`)
  const pendingPermissionCalls = (permissionProjection?.parts || [])
    .filter(part => String(part.partKey || '').startsWith('provider:')
      && part.partType === 'tool_call'
      && permissionBatchCallIds.includes(part.toolCallId))
  const pendingPermissionStatuses = Object.fromEntries(
    pendingPermissionCalls.map(part => [part.toolCallId, part.status])
  )
  if (pendingPermissionStatuses['acceptance-permission-batch-read'] !== 'waiting_approval'
      || pendingPermissionStatuses['acceptance-permission-batch-list'] !== 'skipped') {
    throw new Error(`Multi-tool permission batch was not durably paused: ${JSON.stringify(pendingPermissionCalls)}`)
  }
  if (permissionProjection?.pendingInteraction?.requestPayload?.toolCallId
      && permissionProjection.pendingInteraction.requestPayload.toolCallId !== 'acceptance-permission-batch-read') {
    throw new Error(`Permission interaction points at the wrong tool call: ${JSON.stringify(permissionProjection.pendingInteraction)}`)
  }

  let restartInteractionVerified = false
  if (interactionRestartHandoffDir) {
    await mkdir(interactionRestartHandoffDir, { recursive: true })
    const interactionProjection = permissionProjection
    await writeFile(join(interactionRestartHandoffDir, 'ready.json'), JSON.stringify({
      projectId,
      taskId: permissionTask.taskId,
      conversationId: permissionTask.conversationId,
      interactionId: interactionProjection?.pendingInteraction?.interactionId || null,
      toolCallIds: permissionBatchCallIds
    }, null, 2), 'utf8')
    await waitForRestartContinuation(interactionRestartHandoffDir)
    const restartedInteractionProjection = await api(`/student/projects/${projectId}/agent/tasks/${permissionTask.taskId}`)
    const restartedPermissionCalls = (restartedInteractionProjection?.parts || [])
      .filter(part => String(part.partKey || '').startsWith('provider:')
        && part.partType === 'tool_call'
        && permissionBatchCallIds.includes(part.toolCallId))
    const restartedPermissionStatuses = Object.fromEntries(
      restartedPermissionCalls.map(part => [part.toolCallId, part.status])
    )
    if (Number(restartedInteractionProjection?.taskId) !== Number(permissionTask.taskId)
        || restartedInteractionProjection?.status !== 'waiting_approval'
        || restartedInteractionProjection?.pendingInteraction?.status !== 'waiting'
        || restartedPermissionStatuses['acceptance-permission-batch-read'] !== 'waiting_approval'
        || restartedPermissionStatuses['acceptance-permission-batch-list'] !== 'skipped') {
      throw new Error(`Restart interaction projection mismatch: ${JSON.stringify(restartedInteractionProjection)}`)
    }
    await client.send('Page.reload', { ignoreCache: true })
    await waitForWorkspace()
    await waitFor(() => client.evaluate(`Boolean(document.querySelector('.tc-approval'))`), 'approval after backend restart')
    restartInteractionVerified = true
  }
  await clickElement('.tc-approval .tc-approval-btn.primary', { containsText: '需要确认后才能继续执行', label: 'permission approval' })
  await waitFor(async () => {
    const task = await api(`/student/projects/${projectId}/agent/tasks/${permissionTask.taskId}`)
    return task && task.status !== 'waiting_approval'
  }, 'permission durable decision before refresh')
  await client.send('Page.reload', { ignoreCache: true })
  await waitForWorkspace()
  await waitFor(
    () => bodyIncludes('multi-tool permission batch resumed the original task'),
    'multi-tool permission same-task completion after refresh'
  )
  await waitFor(async () => {
    const task = await api(`/student/projects/${projectId}/agent/tasks/${permissionTask.taskId}`)
    return task && ['completed', 'failed', 'cancelled'].includes(task.status)
  }, 'permission durable terminal state after final response')
  const permissionTasksAfter = await api(`/student/projects/${projectId}/agent/tasks`)
  const createdPermissionTasks = permissionTasksAfter.filter(task => !permissionPriorTaskIds.has(Number(task.taskId)))
  if (createdPermissionTasks.length !== 1 || Number(createdPermissionTasks[0].taskId) !== Number(permissionTask.taskId)) {
    throw new Error(`Permission resume created another task: ${JSON.stringify(createdPermissionTasks)}`)
  }
  const completedPermissionTask = await api(`/student/projects/${projectId}/agent/tasks/${permissionTask.taskId}`)
  if (!['completed', 'failed', 'cancelled'].includes(completedPermissionTask?.status)) {
    throw new Error(`Permission task did not reach a durable terminal state: ${JSON.stringify(completedPermissionTask)}`)
  }
  const completedPermissionParts = completedPermissionTask?.parts || []
  const completedPermissionCalls = completedPermissionParts
    .filter(part => String(part.partKey || '').startsWith('provider:')
      && part.partType === 'tool_call'
      && permissionBatchCallIds.includes(part.toolCallId))
  const completedPermissionResults = completedPermissionParts
    .filter(part => String(part.partKey || '').startsWith('provider:')
      && part.partType === 'tool_result'
      && permissionBatchCallIds.includes(part.toolCallId))
  if (completedPermissionCalls.length !== 2
      || completedPermissionCalls.some(part => part.status !== 'completed')
      || completedPermissionResults.length !== 2
      || completedPermissionResults.map(part => part.toolCallId).join(',') !== permissionBatchCallIds.join(',')) {
    throw new Error(`Multi-tool permission batch did not close every Provider call: ${JSON.stringify({
      calls: completedPermissionCalls,
      results: completedPermissionResults
    })}`)
  }

  await createNewConversation()
  await sendMessage(`[acceptance:isolation:COMPACTION-WARMUP] ${'warmup '.repeat(850)}`)
  await waitFor(() => bodyIncludes('COMPACTION-WARMUP'), 'compaction warmup turn')
  await waitForAgentIdle('compaction warmup terminal state')
  const compactionConfig = await api('/student/model-configs', {
    method: 'POST',
    body: {
      configName: 'Browser Durable Compaction', provider: 'acceptance_scripted', modelName: 'acceptance-compaction',
      apiKey: 'acceptance-placeholder-not-a-secret', baseUrl: 'acceptance://scripted',
      maxTokens: 4096, contextWindowTokens: 32768, temperature: 0, isDefault: true,
      compactionAuto: true, compactionPrune: false, compactionTailTurns: 1,
      compactionPreserveRecentTokens: 8000, compactionReservedTokens: 4096,
      compactionThresholdPercent: 70,
      promptCacheKeyEnabled: false, reasoningEffort: 'medium', imageInputEnabled: false
    }
  })
  configIds.push(compactionConfig.configId)
  await client.send('Page.reload', { ignoreCache: true })
  await waitForWorkspace()
  await waitFor(() => bodyIncludes('acceptance-compaction'), 'durable compaction model selection')
  await waitFor(() => bodyIncludes('COMPACTION-WARMUP'), 'compaction warmup replay after model switch')
  await waitForAgentIdle('reloaded compaction conversation terminal state')
  const tasksBeforeCompaction = await api(`/student/projects/${projectId}/agent/tasks`)
  const priorTaskIds = new Set(tasksBeforeCompaction.map(task => Number(task.taskId)))
  const currentCompactionConversationId = await client.evaluate(
    `sessionStorage.getItem(${JSON.stringify(`labex-agent:selected-conversation:${projectId}`)})`
  )
  if (!currentCompactionConversationId) throw new Error('Compaction conversation identity is missing')
  await sendMessage('[acceptance:compaction]')
  await waitFor(
    () => client.evaluate(`Boolean(document.querySelector('.tc-question'))`),
    'durable compaction question component'
  )
  await clickElement('.tc-question .tc-option-btn', { label: 'question option', native: true })
  await waitFor(() => client.evaluate(`Boolean(document.querySelector('.tc-question textarea')?.value?.trim())`), 'question answer selection')
  await clickElement('.tc-question .tc-approval-btn.primary', { label: 'question answer submit', native: true })
  await waitFor(async () => {
    const activeTask = await api(
      `/student/projects/${projectId}/agent/conversations/${encodeURIComponent(currentCompactionConversationId)}/active-task`
    )
    return !activeTask || String(activeTask.status) !== 'waiting_user'
  }, 'question durable decision before compaction completion')
  await waitFor(
    () => client.evaluate(`Boolean(document.querySelector('.ai-context-management-card.is-completed'))`),
    'durable compaction completion card'
  )
  await waitFor(
    () => bodyIncludes('durable compaction epoch'),
    'durable compaction final reply'
  )
  const tasksAfterCompaction = await api(`/student/projects/${projectId}/agent/tasks`)
  const compactionTask = [...tasksAfterCompaction]
    .filter(task => !priorTaskIds.has(Number(task.taskId)))
    .sort((left, right) => Number(right.taskId) - Number(left.taskId))[0]
  if (!compactionTask?.taskId) {
    throw new Error(`No durable compaction task was found: ${JSON.stringify(tasksAfterCompaction)}`)
  }
  const compactionTaskProjection = await api(
    `/student/projects/${projectId}/agent/tasks/${compactionTask.taskId}`
  )
  const completedCompaction = (compactionTaskProjection?.compactions || [])
    .find(item => item.status === 'completed')
  if (!completedCompaction
      || Number(completedCompaction.compactionEpoch) < 1
      || Number(completedCompaction.estimatedTokensBefore) <= Number(completedCompaction.estimatedTokensAfter)) {
    throw new Error(`Durable compaction audit record is invalid: ${JSON.stringify(compactionTaskProjection?.compactions || [])}`)
  }
  const compactionProviderMessages = (compactionTaskProjection?.runMessages || [])
    .filter(message => String(message.messageKey || '').startsWith('provider:'))
  const compactionProviderParts = (compactionTaskProjection?.parts || [])
    .filter(part => String(part.partKey || '').startsWith('provider:'))
  if (compactionProviderMessages.length === 0 || compactionProviderMessages.length > 16) {
    throw new Error(`Compaction duplicated Provider transcript messages: ${compactionProviderMessages.length}`)
  }
  if (!compactionProviderParts.some(part => part.toolCallId === 'acceptance-compaction-large-tool-call')) {
    throw new Error('The large native tool call was not preserved in the durable Provider transcript')
  }

  const sourceConversationId = compactionTask.conversationId
  const sourceBeforeManualPage = await api(
    `/student/projects/${projectId}/agent/conversations/${encodeURIComponent(sourceConversationId)}/messages?limit=50`
  )
  const sourceBeforeManualEvents = sourceBeforeManualPage?.events || []
  const sourceLastMessageId = sourceBeforeManualEvents.reduce(
    (latest, event) => Math.max(latest, Number(event.messageId) || 0),
    0
  )
  const manualCompaction = await api(
    `/student/projects/${projectId}/agent/conversations/${encodeURIComponent(sourceConversationId)}/compact`,
    { method: 'POST', body: { modelConfigId: compactionConfig.configId } }
  )
  let manualCompactionProjection = null
  await waitFor(async () => {
    try {
      manualCompactionProjection = await api(`/student/projects/${projectId}/agent/tasks/${manualCompaction.taskId}`)
      return ['completed', 'failed', 'cancelled'].includes(String(manualCompactionProjection?.status))
    } catch {
      return false
    }
  }, 'manual compaction durable terminal state')
  if (manualCompactionProjection?.status !== 'completed') {
    throw new Error(`Manual compaction did not complete: ${JSON.stringify(manualCompactionProjection)}`)
  }

  const sourceAfterManualPage = await api(
    `/student/projects/${projectId}/agent/conversations/${encodeURIComponent(sourceConversationId)}/messages?limit=50`
  )
  const sourceAfterManualEvents = sourceAfterManualPage?.events || []
  const manualSummary = [...sourceAfterManualEvents].reverse().find(event =>
    event.eventType === 'COMPACTION_SUMMARY'
      && Number(event.messageId) > sourceLastMessageId
      && String(event.content || '').includes('Compaction model: acceptance-compaction')
  )
  if (!manualSummary) {
    throw new Error('Manual compaction did not persist a new durable COMPACTION_SUMMARY message')
  }
  const sourceConversation = (await api(`/student/projects/${projectId}/agent/conversations`))
    .find(conversation => conversation.conversationId === sourceConversationId)
  if (!sourceConversation || Object.prototype.hasOwnProperty.call(sourceConversation, 'summary')) {
    throw new Error(`Conversation API exposed the retired legacy summary: ${JSON.stringify(sourceConversation)}`)
  }

  const forkedConversation = await api(
    `/student/projects/${projectId}/agent/conversations/${encodeURIComponent(sourceConversationId)}/fork`,
    { method: 'POST', body: { messageId: manualSummary.messageId } }
  )
  if (!forkedConversation?.conversationId
      || forkedConversation.parentConversationId !== sourceConversationId
      || Number(forkedConversation.forkedFromMessageId) !== Number(manualSummary.messageId)
      || Object.prototype.hasOwnProperty.call(forkedConversation, 'summary')) {
    throw new Error(`Fork metadata retained the legacy summary or lost its cutoff: ${JSON.stringify(forkedConversation)}`)
  }
  const forkedPage = await api(
    `/student/projects/${projectId}/agent/conversations/${encodeURIComponent(forkedConversation.conversationId)}/messages?limit=50`
  )
  const forkedSummary = (forkedPage?.events || []).find(event =>
    event.eventType === 'COMPACTION_SUMMARY' && event.content === manualSummary.content
  )
  const forkedMemory = await api(
    `/student/projects/${projectId}/agent/conversations/${encodeURIComponent(forkedConversation.conversationId)}/memory`
  )
  if (!forkedSummary || Number(forkedMemory?.estimatedTokens) <= 0 || forkedMemory?.needsCompact !== true) {
    throw new Error(`Fork did not rebuild memory from the durable compaction message: ${JSON.stringify({
      forkedSummary,
      forkedMemory
    })}`)
  }

  await client.send('Page.reload', { ignoreCache: true })
  await waitForWorkspace()
  await selectConversationByTitle(forkedConversation.title)
  await waitFor(() => bodyIncludes('COMPACTION-WARMUP'), 'forked durable history in browser')
  await client.send('Page.reload', { ignoreCache: true })
  await waitForWorkspace()
  await waitFor(() => bodyIncludes('COMPACTION-WARMUP'), 'forked durable history after browser refresh')
  const manualCompactionForkRefreshVerified = true

  let restartProjectionVerified = false
  let restartManualForkVerified = false
  if (restartHandoffDir) {
    await mkdir(restartHandoffDir, { recursive: true })
    await writeFile(join(restartHandoffDir, 'ready.json'), JSON.stringify({
      projectId,
      taskId: compactionTask.taskId,
      conversationId: compactionTask.conversationId,
      compactionEpoch: completedCompaction.compactionEpoch,
      manualCompactionTaskId: manualCompaction.taskId,
      forkedConversationId: forkedConversation.conversationId,
      forkedSummaryMessageId: forkedSummary.messageId
    }, null, 2), 'utf8')
    await waitForRestartContinuation(restartHandoffDir)
    let restartedProjection = null
    await waitFor(async () => {
      try {
        restartedProjection = await api(`/student/projects/${projectId}/agent/tasks/${compactionTask.taskId}`)
        return Boolean(restartedProjection)
      } catch {
        return false
      }
    }, 'same task projection after real backend restart')
    const restartedCompaction = (restartedProjection.compactions || [])
      .find(item => Number(item.compactionEpoch) === Number(completedCompaction.compactionEpoch)
        && item.status === 'completed')
    const restartedProviderMessages = (restartedProjection.runMessages || [])
      .filter(message => String(message.messageKey || '').startsWith('provider:'))
    if (!restartedCompaction || restartedProviderMessages.length !== compactionProviderMessages.length) {
      throw new Error(`Restart projection mismatch: ${JSON.stringify({
        compactions: restartedProjection.compactions || [],
        providerMessages: restartedProviderMessages.length,
        expectedProviderMessages: compactionProviderMessages.length
      })}`)
    }
    const restartedContextStatus = await api(
      `/student/projects/${projectId}/agent/conversations/${encodeURIComponent(compactionTask.conversationId)}/context-status`
    )
    if (restartedContextStatus?.status === 'AWAITING_FIRST_REQUEST'
        || !Number.isFinite(Number(restartedContextStatus?.usedTokens))
        || Number(restartedContextStatus.usedTokens) <= 0) {
      throw new Error(`Restarted context status was not rebuilt from durable events: ${JSON.stringify(restartedContextStatus)}`)
    }
    const restartedManualCompaction = await api(
      `/student/projects/${projectId}/agent/tasks/${manualCompaction.taskId}`
    )
    const restartedForkedPage = await api(
      `/student/projects/${projectId}/agent/conversations/${encodeURIComponent(forkedConversation.conversationId)}/messages?limit=50`
    )
    const restartedForkedSummary = (restartedForkedPage?.events || []).find(event =>
      event.eventType === 'COMPACTION_SUMMARY' && event.content === manualSummary.content
    )
    const restartedFork = (await api(`/student/projects/${projectId}/agent/conversations`))
      .find(conversation => conversation.conversationId === forkedConversation.conversationId)
    if (restartedManualCompaction?.status !== 'completed'
        || !restartedForkedSummary
        || !restartedFork
        || Object.prototype.hasOwnProperty.call(restartedFork, 'summary')) {
      throw new Error(`Manual compaction/fork did not survive backend restart: ${JSON.stringify({
        manualStatus: restartedManualCompaction?.status,
        restartedForkedSummary,
        restartedFork
      })}`)
    }
    await client.send('Page.reload', { ignoreCache: true })
    await waitForWorkspace()
    await waitFor(() => bodyIncludes('COMPACTION-WARMUP'), 'forked durable history after backend restart')
    restartProjectionVerified = true
    restartManualForkVerified = true
  }

  const streamBreakPriorTasks = await api(`/student/projects/${projectId}/agent/tasks`)
  const streamBreakPriorTaskIds = new Set(streamBreakPriorTasks.map(task => Number(task.taskId)))
  await createNewConversation()
  await sendMessage('[acceptance:stream-break]')
  await waitFor(
    () => bodyIncludes('Partial response before the scripted provider connection closes.'),
    'partial provider response before stream interruption'
  )
  await waitForAgentIdle('stream interruption terminal UI state')
  let streamBreakTask = null
  await waitFor(async () => {
    const tasks = await api(`/student/projects/${projectId}/agent/tasks`)
    const candidate = tasks
      .filter(task => !streamBreakPriorTaskIds.has(Number(task.taskId)))
      .sort((left, right) => Number(right.taskId) - Number(left.taskId))[0]
    if (!candidate) return false
    streamBreakTask = candidate
    return ['retrying', 'failed', 'completed', 'cancelled'].includes(String(candidate.status))
  }, 'durable stream interruption state')
  if (!streamBreakTask || ['completed', 'cancelled'].includes(String(streamBreakTask.status))) {
    throw new Error(`Provider stream interruption was treated as a successful completion: ${JSON.stringify(streamBreakTask)}`)
  }

  const tinyConfig = await api('/student/model-configs', {
    method: 'POST',
    body: {
      configName: 'Browser Static Context', provider: 'acceptance_scripted', modelName: 'acceptance-tiny',
      apiKey: 'acceptance-placeholder-not-a-secret', baseUrl: 'acceptance://scripted',
      maxTokens: 1000, contextWindowTokens: 1024, temperature: 0, isDefault: true,
      promptCacheKeyEnabled: false, reasoningEffort: 'medium', imageInputEnabled: false
    }
  })
  configIds.push(tinyConfig.configId)
  await client.send('Page.reload', { ignoreCache: true })
  await waitForWorkspace()
  await waitFor(() => bodyIncludes('acceptance-tiny'), 'tiny model selection')
  await sendMessage('[acceptance:static-context]')
  await waitFor(
    () => client.evaluate(`Boolean(document.querySelector('.context-limit-card'))`),
    'static context blocker card'
  )
  const blockerText = await client.evaluate(`document.querySelector('.context-limit-card')?.innerText || ''`)
  if (!blockerText.includes('\u9759\u6001\u4e0a\u4e0b\u6587') || !blockerText.includes('\u8f93\u5165\u5bb9\u91cf')) {
    throw new Error('Static context blocker card is missing the budget breakdown')
  }

  const evidenceConfig = await api('/student/model-configs', {
    method: 'POST',
    body: {
      configName: 'Browser Completion Evidence', provider: 'acceptance_scripted', modelName: 'acceptance-evidence',
      apiKey: 'acceptance-placeholder-not-a-secret', baseUrl: 'acceptance://scripted',
      maxTokens: 4096, contextWindowTokens: 32768, temperature: 0, isDefault: true,
      promptCacheKeyEnabled: false, reasoningEffort: 'medium', imageInputEnabled: false
    }
  })
  configIds.push(evidenceConfig.configId)
  await client.send('Page.reload', { ignoreCache: true })
  await waitForWorkspace()
  await waitFor(() => bodyIncludes('acceptance-evidence'), 'completion evidence model selection')
  await createNewConversation()
  await sendMessage('[acceptance:evidence]')
  await waitFor(
    () => client.evaluate(`Boolean(document.querySelector('.completion-evidence.satisfied'))`),
    'satisfied completion evidence card'
  )
  await waitFor(
    () => client.evaluate(`Boolean(document.querySelector('button.ai-submit-btn')) && !document.querySelector('.ai-generating-indicator')`),
    'verified task terminal state'
  )

  await createNewConversation()
  await sendMessage('[acceptance:unverified]')
  await waitFor(
    () => client.evaluate(`Boolean(document.querySelector('.completion-evidence.blocked'))`),
    'blocked completion evidence card'
  )
  await waitFor(
    () => client.evaluate(`Boolean(document.querySelector('button.ai-submit-btn')) && !document.querySelector('.ai-generating-indicator')`),
    'unverified task terminal state'
  )
  if (await client.evaluate(`Boolean(document.querySelector('.completion-evidence.satisfied'))`)) {
    throw new Error('Unverified edit displayed successful completion evidence')
  }

  const restartVerification = { restartProjectionVerified, restartInteractionVerified, restartManualForkVerified }
  const expectedRestartTransportErrors = [...consoleErrors, ...networkErrors]
    .filter(message => isExpectedRestartTransportError(message, restartVerification))
  const meaningfulConsoleErrors = consoleErrors.filter(message =>
    !/favicon|ResizeObserver loop/i.test(message)
    && !isExpectedRestartTransportError(message, restartVerification))
  const meaningfulNetworkErrors = networkErrors.filter(message =>
    !/ERR_ABORTED|canceled/i.test(message)
    && !isExpectedRestartTransportError(message, restartVerification))
  if (meaningfulConsoleErrors.length) throw new Error(`Browser console errors: ${meaningfulConsoleErrors.join(' | ')}`)
  if (meaningfulNetworkErrors.length) throw new Error(`Browser network errors: ${meaningfulNetworkErrors.join(' | ')}`)

  return {
    runId,
    projectId,
    desktopLayout: true,
    desktopLayoutMetrics: desktopLayout,
    conversationIsolation: true,
    refreshReplayDeduplicated: true,
    internalReasoningProtocolHidden: true,
    durableCacheTelemetryProjection: true,
    questionReplyComponent: true,
    permissionApprovalRefreshRecovery: true,
    multiToolPermissionBatchProtocolComplete: true,
    durableProviderMessages: providerMessages.length,
    durableProviderParts: providerParts.length,
    cursorKeys: cursorKeys.length,
    durableCompaction: true,
    compactionEpoch: completedCompaction.compactionEpoch,
    compactionTokensBefore: completedCompaction.estimatedTokensBefore,
    compactionTokensAfter: completedCompaction.estimatedTokensAfter,
    compactionProviderMessages: compactionProviderMessages.length,
    manualCompactionForkRefreshVerified,
    manualCompactionTaskId: manualCompaction.taskId,
    forkedConversationId: forkedConversation.conversationId,
    providerStreamInterruptionHandled: true,
    restartProjectionVerified,
    restartManualForkVerified,
    restartInteractionVerified,
    expectedRestartTransportErrors: expectedRestartTransportErrors.length,
    staticContextBlockerCard: true,
    completionEvidenceCard: true,
    unverifiedCompletionBlocked: true,
    consoleErrors: 0,
    networkErrors: 0
  }
}

let result
try {
  result = await runScenario()
  console.log(JSON.stringify(redactEvidence(result), null, 2))
  if (handoffStatusDir) {
    await writeFile(join(handoffStatusDir, 'done.json'), JSON.stringify(redactEvidence(result), null, 2), 'utf8')
  }
} catch (error) {
  let page = null
  try {
    page = await client?.evaluate(`({ url: location.href, title: document.title, body: document.body?.innerText?.slice(0, 1200) || '' })`)
  } catch {
    // 页面已关闭时忽略 CDP 诊断失败。
  }
  const failure = redactEvidence({
    error: error?.message || String(error),
    consoleErrors,
    networkErrors,
    page
  })
  console.error(JSON.stringify(failure, null, 2))
  if (handoffStatusDir) {
    await mkdir(handoffStatusDir, { recursive: true }).catch(() => {})
    await writeFile(join(handoffStatusDir, 'error.json'), JSON.stringify(failure, null, 2), 'utf8').catch(() => {})
  }
  throw error
} finally {
  for (const id of [...configIds].reverse()) {
    try {
      await api(`/student/model-configs/${id}`, { method: 'DELETE' })
    } catch (error) {
      console.error(`Model config cleanup failed: ${error.message}`)
    }
  }
  try {
    if (projectId) await api(`/student/projects/${projectId}`, { method: 'DELETE' })
  } catch (error) {
    console.error(`Project cleanup failed: ${error.message}`)
  }
  try { client?.close() } catch { }
  try { browser?.kill() } catch { }
  if (profileDir?.startsWith(tmpdir()) && profileDir.includes('labex-agent-browser-')) {
    await rm(profileDir, { recursive: true, force: true }).catch(() => {})
  }
}
