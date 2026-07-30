import { spawn } from 'node:child_process'
import { access, mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { CdpClient } from './cdp-client.mjs'
import { redactEvidence } from './evidence.mjs'

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
    if (entry?.level === 'error') consoleErrors.push(entry.text || 'Browser log error')
  })
  client.on('Runtime.consoleAPICalled', event => {
    if (!['error', 'warning'].includes(event?.type)) return
    const text = (event.args || []).map(arg => arg.value ?? arg.description ?? '').join(' ')
    if (text) consoleErrors.push(`${event.type}: ${text}`)
  })
  client.on('Network.loadingFailed', event => {
    if (!event.canceled) networkErrors.push(`${event.type ?? 'request'}: ${event.errorText ?? 'failed'}`)
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

async function waitForRestartContinuation() {
  if (!restartHandoffDir) return false
  await mkdir(restartHandoffDir, { recursive: true })
  const continuationFile = join(restartHandoffDir, 'continue.signal')
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
  await client.evaluate(`(() => {
    const textarea = document.querySelector('.ai-input-text-area textarea');
    if (!textarea) throw new Error('Agent textarea missing');
    const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set;
    setter.call(textarea, ${encoded});
    textarea.dispatchEvent(new Event('input', { bubbles: true }));
    return true;
  })()`)
  await delay(100)
  await client.evaluate(`(() => {
    const button = document.querySelector('button.ai-submit-btn[title^="发送"]');
    if (!button || button.disabled) throw new Error('Agent submit button unavailable');
    button.click();
    return true;
  })()`)
}

async function bodyIncludes(text) {
  return client.evaluate(`document.body.innerText.includes(${JSON.stringify(text)})`)
}

async function markerCount(text) {
  return client.evaluate(`document.body.innerText.split(${JSON.stringify(text)}).length - 1`)
}

async function createNewConversation() {
  await client.evaluate(`document.querySelector('.ai-session-select')?.click()`)
  await waitFor(() => client.evaluate(`Boolean(document.querySelector('.ai-session-new'))`), 'new conversation action')
  await client.evaluate(`document.querySelector('.ai-session-new').click()`)
  await waitFor(() => client.evaluate(`!document.querySelector('.ai-session-dropdown')`), 'conversation menu close')
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

  await sendMessage('[acceptance:isolation:B-ONLY]')
  await waitFor(() => bodyIncludes(markerB), 'conversation B final reply')
  if (await bodyIncludes(markerA)) throw new Error('Conversation B rendered conversation A context')

  await client.send('Page.reload', { ignoreCache: true })
  await waitForWorkspace()
  await waitFor(() => bodyIncludes(markerB), 'conversation B replay after refresh')
  const duplicates = await markerCount(markerB)
  if (duplicates !== 1) throw new Error(`Expected one replayed B marker, found ${duplicates}`)
  const cursorKeys = await client.evaluate(`Object.keys(sessionStorage).filter(key => key.startsWith('labex-agent:task-event-cursor:'))`)
  if (!Array.isArray(cursorKeys) || cursorKeys.length === 0) {
    throw new Error('No persisted task event cursor was observed after refresh')
  }

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
  await client.evaluate(`document.querySelector('.tc-question .tc-option-btn')?.click()`)
  await client.evaluate(`document.querySelector('.tc-question .tc-approval-btn.primary')?.click()`)
  await waitFor(
    () => client.evaluate(`!document.querySelector('.tc-question') && document.body.innerText.includes('durable user-question interaction resumed')`),
    'question reply completion'
  )

  await createNewConversation()
  const tasksBeforePermission = await api(`/student/projects/${projectId}/agent/tasks`)
  const permissionPriorTaskIds = new Set(tasksBeforePermission.map(task => Number(task.taskId)))
  await sendMessage('[acceptance:permission]')
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
  let restartInteractionVerified = false
  if (interactionRestartHandoffDir) {
    await mkdir(interactionRestartHandoffDir, { recursive: true })
    const interactionProjection = await api(`/student/projects/${projectId}/agent/tasks/${permissionTask.taskId}`)
    await writeFile(join(interactionRestartHandoffDir, 'ready.json'), JSON.stringify({
      projectId,
      taskId: permissionTask.taskId,
      conversationId: permissionTask.conversationId,
      interactionId: interactionProjection?.pendingInteraction?.interactionId || null
    }, null, 2), 'utf8')
    await waitForRestartContinuation()
    const restartedInteractionProjection = await api(`/student/projects/${projectId}/agent/tasks/${permissionTask.taskId}`)
    if (Number(restartedInteractionProjection?.taskId) !== Number(permissionTask.taskId)
        || restartedInteractionProjection?.status !== 'waiting_approval'
        || restartedInteractionProjection?.pendingInteraction?.status !== 'waiting') {
      throw new Error(`Restart interaction projection mismatch: ${JSON.stringify(restartedInteractionProjection)}`)
    }
    await client.send('Page.reload', { ignoreCache: true })
    await waitForWorkspace()
    await waitFor(() => client.evaluate(`Boolean(document.querySelector('.tc-approval'))`), 'approval after backend restart')
    restartInteractionVerified = true
  }
  await client.evaluate(`(() => {
    const card = Array.from(document.querySelectorAll('.tc-approval')).find(item => item.innerText.includes('需要确认后才能继续执行'))
    card?.querySelector('.tc-approval-btn.primary')?.click()
  })()`)
  await waitFor(async () => {
    const task = await api(`/student/projects/${projectId}/agent/tasks/${permissionTask.taskId}`)
    return task && task.status !== 'waiting_approval'
  }, 'permission durable decision before refresh')
  await client.send('Page.reload', { ignoreCache: true })
  await waitForWorkspace()
  await waitFor(
    () => bodyIncludes('tool permission decision resumed the original task'),
    'permission same-task completion after refresh'
  )
  const permissionTasksAfter = await api(`/student/projects/${projectId}/agent/tasks`)
  const createdPermissionTasks = permissionTasksAfter.filter(task => !permissionPriorTaskIds.has(Number(task.taskId)))
  if (createdPermissionTasks.length !== 1 || Number(createdPermissionTasks[0].taskId) !== Number(permissionTask.taskId)) {
    throw new Error(`Permission resume created another task: ${JSON.stringify(createdPermissionTasks)}`)
  }
  const completedPermissionTask = await api(`/student/projects/${projectId}/agent/tasks/${permissionTask.taskId}`)
  if (!['completed', 'failed', 'cancelled'].includes(completedPermissionTask?.status)) {
    throw new Error(`Permission task did not reach a durable terminal state: ${JSON.stringify(completedPermissionTask)}`)
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
  await sendMessage('[acceptance:compaction]')
  await waitFor(
    () => client.evaluate(`Boolean(document.querySelector('.tc-question'))`),
    'durable compaction question component'
  )
  await client.evaluate(`document.querySelector('.tc-question .tc-option-btn')?.click()`)
  await client.evaluate(`document.querySelector('.tc-question .tc-approval-btn.primary')?.click()`)
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

  let restartProjectionVerified = false
  if (restartHandoffDir) {
    await mkdir(restartHandoffDir, { recursive: true })
    await writeFile(join(restartHandoffDir, 'ready.json'), JSON.stringify({
      projectId,
      taskId: compactionTask.taskId,
      conversationId: compactionTask.conversationId,
      compactionEpoch: completedCompaction.compactionEpoch
    }, null, 2), 'utf8')
    await waitForRestartContinuation()
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
    restartProjectionVerified = true
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

  const meaningfulConsoleErrors = consoleErrors.filter(message => !/favicon|ResizeObserver loop/i.test(message))
  const meaningfulNetworkErrors = networkErrors.filter(message => !/ERR_ABORTED|canceled/i.test(message))
  if (meaningfulConsoleErrors.length) throw new Error(`Browser console errors: ${meaningfulConsoleErrors.join(' | ')}`)
  if (meaningfulNetworkErrors.length) throw new Error(`Browser network errors: ${meaningfulNetworkErrors.join(' | ')}`)

  return {
    runId,
    projectId,
    desktopLayout: true,
    desktopLayoutMetrics: desktopLayout,
    conversationIsolation: true,
    refreshReplayDeduplicated: true,
    questionReplyComponent: true,
    permissionApprovalRefreshRecovery: true,
    durableProviderMessages: providerMessages.length,
    durableProviderParts: providerParts.length,
    cursorKeys: cursorKeys.length,
    durableCompaction: true,
    compactionEpoch: completedCompaction.compactionEpoch,
    compactionTokensBefore: completedCompaction.estimatedTokensBefore,
    compactionTokensAfter: completedCompaction.estimatedTokensAfter,
    compactionProviderMessages: compactionProviderMessages.length,
    restartProjectionVerified,
    restartInteractionVerified,
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
    // ???????????????? CDP ???
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
