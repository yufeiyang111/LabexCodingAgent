// 唯一职责：把「云端 feed + 本地目录 + journal 持久化」装成 orchestrator，并以响应式会话暴露给 UI。
// 边界：本模块只做装配与状态转译；不判定冲突（changeSetSyncPlan）、不写文件（adapter）、不发 HTTP（注入 api）。
// 设计模式：依赖注入（全部依赖可替换）+ Observer（orchestrator 的 onProgress 驱动 ref）。

import { computed, ref } from 'vue'

import { loadAllAgentChangeFeed } from './agentChangeFeedLoader.js'
import { createChangeSetSyncOrchestrator } from './changeSetSyncOrchestrator.js'
import { createSyncPersistence } from './syncPersistence.js'
import { buildSyncProgressView } from './syncProgressView.js'
import { createUnloadGuard } from './syncUnloadGuard.js'
import { SYNC_STATES, createSyncSession, describeSession } from './syncSessionState.js'

const noopNotify = { success: noop, error: noop, warning: noop, info: noop }

function noop() {}

function errorMessage(error) {
  return error instanceof Error ? error.message : String(error)
}

/**
 * 用户取消目录选择是**正常操作**，不是错误。
 * showDirectoryPicker 在用户点取消时抛出 AbortError / "The user aborted a request"，
 * 把它显示成红色报错会让用户以为功能坏了。
 */
function isUserCancelled(error) {
  if (!error) return false
  const name = error.name ?? ''
  const message = errorMessage(error)
  return name === 'AbortError' || /aborted|user cancelled|未选择目录/i.test(message)
}

function projectKeyOf(value) {
  const key = String(value ?? '').trim()
  return key || ''
}

/**
 * @param {object} options
 * @param {() => (string|number)} options.projectId  当前项目 id（函数形式，切换项目后仍取最新值）
 * @param {object} options.api                        需提供 agentChangeFeed(projectId, cursor)
 * @param {object} options.journalStore               { load, save }；缺省按项目 id 落到 IndexedDB
 * @param {() => Promise<object>} options.adapterFactory  目录句柄 → 目录适配器
 * @param {() => Promise<object>} options.directoryPicker  弹出目录选择器，返回目录句柄
 */
export function useChangeSetSync(options = {}) {
  const api = options.api ?? {}
  const projectIdOf = typeof options.projectId === 'function'
    ? options.projectId
    : () => options.projectId
  const notify = options.notify ?? noopNotify

  const session = ref(createSyncSession())
  const directoryName = ref('')
  // 本次同步的开始时间与"当前时间"：进度视图用它算速率与剩余时间。
  // clock 由定时器推进，避免在 computed 里读 Date.now()（那样不会触发重算）。
  const startedAt = ref(0)
  const clock = ref(Date.now())
  let clockTimer = null
  // 误关页面守卫：同步在飞时挂 beforeunload。
  const unloadGuard = createUnloadGuard()
  if (typeof window !== 'undefined') unloadGuard.attach(window)
  let adapter = null
  let adapterProjectKey = ''
  let orchestrator = null
  let orchestratorProjectKey = ''

  const busy = computed(() => describeSession(session.value).busy)
  const hasDirectory = computed(() => adapter !== null)
  const conflictCount = computed(() => (session.value.conflicts ?? []).length)
  const summary = computed(() => session.value.summary ?? null)
  // 进度视图：UI 只消费它，不再自己算百分比/剩余时间。
  const progress = computed(() => buildSyncProgressView(session.value, {
    startedAt: startedAt.value,
    now: clock.value
  }))
  // 最近一次同步没有任何待写入变更：UI 据此显示"已是最新"，而不是含糊的"已同步 0 个文件"。
  const upToDate = computed(() => (
    session.value.state === SYNC_STATES.DONE
    && (session.value.applied ?? 0) === 0
    && (session.value.summary?.total ?? 0) === 0
  ))

  function requireProjectKey() {
    const key = projectKeyOf(projectIdOf())
    if (!key) throw new Error('同步需要当前项目，项目尚未就绪')
    return key
  }

  const persistence = createSyncPersistence({
    journalStore: options.journalStore,
    handleStore: options.handleStore,
    indexedDb: options.indexedDb
  })

  function resolveJournalStore(projectKey) {
    return persistence.journalStore(projectKey)
  }

  /** 目录句柄的持久化仓库：默认走 IndexedDB，测试可注入替身。 */
  function handleStore() {
    return persistence.handleStore()
  }

  async function attachHandle(handle) {
    adapter = await options.adapterFactory(handle)
    adapterProjectKey = projectKeyOf(projectIdOf())
    directoryName.value = handle.name ?? ''
    orchestrator = null
    session.value = { ...session.value, error: null }
  }

  /**
   * 目录选择器同一时刻只能有一个在飞。
   *
   * 为什么必须防重入：入口有两条（面板按钮、首次点同步时自动弹出），
   * 且都没有天然互斥。用户快速点两下就会同时发起两次 showDirectoryPicker，
   * 浏览器只允许一个选择器，第二个立即以 AbortError 失败——
   * 用户明明没点取消却看到 "The user aborted a request"。
   * 这里让并发调用复用同一个 Promise，只弹一次。
   */
  let pickerInFlight = null

  async function pickDirectory() {
    if (typeof options.directoryPicker !== 'function') {
      session.value = { ...session.value, error: '当前环境不支持选择本地目录（需要 Chromium 的 File System Access API）' }
      return false
    }
    if (pickerInFlight) return pickerInFlight

    pickerInFlight = (async () => {
      try {
        const handle = await options.directoryPicker()
        if (!handle) throw new Error('未选择目录')
        await attachHandle(handle)
        // 句柄必须持久化：否则刷新页面后绑定丢失，用户会看到"按钮又变回选择本地目录"。
        await handleStore().save(requireProjectKey(), handle)
        return true
      } catch (error) {
        // 用户取消是正常路径：不报错、不改变已有绑定状态。
        if (isUserCancelled(error)) return false
        session.value = { ...session.value, error: errorMessage(error) }
        return false
      } finally {
        // 无论成功失败都必须释放，否则一次失败会让按钮永久失效。
        pickerInFlight = null
      }
    })()
    return pickerInFlight
  }

  /**
   * 从持久层恢复上次授权的目录，避免每次刷新都要重选。
   * 浏览器可能已撤销权限（句柄仍在但不可用），此时返回 false 并清掉失效句柄。
   */
  async function restoreDirectory() {
    if (adapter) return true
    let handle = null
    try {
      handle = await handleStore().load(requireProjectKey())
    } catch (error) {
      session.value = { ...session.value, error: errorMessage(error) }
      return false
    }
    if (!handle) return false

    try {
      // 浏览器要求显式重新确认权限；queryPermission 不存在时直接尝试使用。
      if (typeof handle.queryPermission === 'function') {
        const state = await handle.queryPermission({ mode: 'readwrite' })
        if (state !== 'granted') {
          const requested = typeof handle.requestPermission === 'function'
            ? await handle.requestPermission({ mode: 'readwrite' })
            : 'denied'
          if (requested !== 'granted') return false
        }
      }
      await attachHandle(handle)
      return true
    } catch (error) {
      // 句柄失效（目录被删/权限被撤销）：清掉，让用户重新选择，而不是留着必然失败的绑定。
      await handleStore().clear(requireProjectKey()).catch(() => {})
      session.value = { ...session.value, error: errorMessage(error) }
      return false
    }
  }

  function ensureOrchestrator() {
    const projectKey = requireProjectKey()
    if (!adapter) throw new Error('请先选择要同步到的本地目录')
    if (orchestrator && orchestratorProjectKey === projectKey && adapterProjectKey === projectKey) {
      return orchestrator
    }
    orchestrator = createChangeSetSyncOrchestrator({
      adapter,
      journalStore: resolveJournalStore(projectKey),
      loadChanges: ({ cursor }) => loadFeed(projectKey, cursor),
      onProgress: patch => {
        session.value = patch.session ?? { ...session.value, ...patch }
      },
      limits: options.limits
    })
    orchestratorProjectKey = projectKey
    return orchestrator
  }

  /** 逐页拉取到两条进度线都收敛；游标原样回传，不在前端做任何加工。 */
  async function loadFeed(projectKey, cursor) {
    const response = await fetchPage(projectKey, {
      afterChangeSetId: cursor?.afterChangeSetId,
      tailUpdateTime: cursor?.tailUpdateTime ?? undefined,
      tailChangeId: cursor?.tailChangeId ?? undefined,
      limit: options.pageLimit
    })
    return loadAllAgentChangePages(projectKey, response, cursor)
  }

  async function fetchPage(projectKey, request) {
    const response = await api.agentChangeFeed(projectIdOf(), {
      afterChangeSetId: request.afterChangeSetId,
      tailUpdateTime: request.tailUpdateTime ?? undefined,
      tailChangeId: request.tailChangeId ?? undefined,
      limit: request.limit ?? options.pageLimit
    })
    return response?.data ?? response
  }

  async function loadAllAgentChangePages(projectKey, firstPage, cursor) {
    const queue = [firstPage]
    const nextPage = async request => {
      if (queue.length > 0) return queue.shift()
      return fetchPage(projectKey, request)
    }
    return loadAllAgentChangeFeed(nextPage, { ...cursor, maxPages: options.maxPages })
  }

  function startClock() {
    stopClock()
    // 1s 足够：剩余时间只需要秒级精度，过密会白白消耗主线程。
    clockTimer = setInterval(() => { clock.value = Date.now() }, 1000)
  }

  function stopClock() {
    if (clockTimer !== null) {
      clearInterval(clockTimer)
      clockTimer = null
    }
  }

  async function run(runOptions = {}) {
    let current
    try {
      current = ensureOrchestrator()
    } catch (error) {
      session.value = { ...session.value, state: SYNC_STATES.FAILED, error: errorMessage(error) }
      return { ...session.value }
    }

    startedAt.value = Date.now()
    clock.value = startedAt.value
    startClock()
    // 同步进行中禁止误关页面：目录句柄只在页面存活期间可用，
    // 关掉标签页会丢掉未提交的进度（"关掉后继续跑"是 daemon/L3 的能力）。
    unloadGuard.begin()

    let result
    try {
      result = await current.run({
        projectId: orchestratorProjectKey,
        policy: runOptions.policy,
        ignorePatterns: runOptions.ignorePatterns
      })
    } catch (error) {
      // 并发调用在 orchestrator 里是显式拒绝；这里必须转成会话状态，而不是把异常抛给 UI。
      session.value = { ...session.value, state: SYNC_STATES.FAILED, error: errorMessage(error) }
      stopClock()
      unloadGuard.end()
      return { ...session.value }
    }
    stopClock()
    unloadGuard.end()
    clock.value = Date.now()
    notifyResult(result)
    return result
  }

  /** 中止当前同步：已写盘的文件仍可回退，未写的停止。 */
  function cancel() {
    if (orchestrator) orchestrator.cancel()
  }

  async function rollback(rollbackOptions = {}) {
    let current
    try {
      current = ensureOrchestrator()
    } catch (error) {
      session.value = { ...session.value, state: SYNC_STATES.FAILED, error: errorMessage(error) }
      return { ...session.value }
    }
    let result
    try {
      result = await current.rollback(rollbackOptions)
    } catch (error) {
      session.value = { ...session.value, state: SYNC_STATES.FAILED, error: errorMessage(error) }
      return { ...session.value }
    }
    notifyResult(result)
    return result
  }

  function notifyResult(result) {
    if (result?.state === SYNC_STATES.DONE) {
      const applied = result.applied ?? 0
      if (applied === 0) {
        // 「已同步 0 个文件」会让用户以为同步没生效。这里必须说清是"本来就没有新变更"。
        notify.success('本地已是最新，没有需要同步的变更')
        return
      }
      notify.success(`已同步 ${applied} 个文件到本地`)
      return
    }
    if (result?.state === SYNC_STATES.BLOCKED) {
      notify.warning(`有 ${(result.conflicts ?? []).length} 个文件冲突，未写入本地`)
      return
    }
    if (result?.state === SYNC_STATES.FAILED) {
      notify.error(result.error ?? '同步失败')
    }
  }

  /**
   * 重置同步进度：清空 journal（游标 + 回退记录），下次同步从零重新拉全部变更。
   *
   * 为什么需要它：游标记录的是"服务端变更处理到哪了"，不是"本地磁盘上有什么"。
   * 用户把本地目录清空后，游标不会自动回退，feed 会一直返回空，
   * 同步变成空转（界面显示"已完成 / 已同步 0 个文件"，而本地什么都没有）。
   * 这是唯一的恢复路径，因此必须由用户显式触发。
   *
   * 注意：只清同步进度，**不动目录授权**——用户删的是文件，不是取消授权。
   */
  async function resetProgress() {
    let store
    try {
      store = resolveJournalStore(requireProjectKey())
    } catch (error) {
      session.value = { ...session.value, error: errorMessage(error) }
      return false
    }
    if (typeof store.clear !== 'function') {
      session.value = { ...session.value, error: '当前 journal 存储不支持重置' }
      return false
    }
    const existing = await store.load().catch(() => null)
    if (!existing) return false

    try {
      await store.clear()
    } catch (error) {
      session.value = { ...session.value, error: errorMessage(error) }
      return false
    }
    // 编排器缓存了旧游标的读取结果，必须重建。
    orchestrator = null
    session.value = createSyncSession()
    return true
  }

  /** 切换项目时丢弃旧项目的适配器与编排器，避免把 A 项目的变化写进 B 项目的目录。 */
  function reset() {
    adapter = null
    adapterProjectKey = ''
    orchestrator = null
    orchestratorProjectKey = ''
    directoryName.value = ''
    session.value = createSyncSession()
  }

  return {
    session,
    busy,
    hasDirectory,
    conflictCount,
    summary,
    progress,
    upToDate,
    directoryName,
    pickDirectory,
    restoreDirectory,
    resetProgress,
    run,
    cancel,
    rollback,
    reset
  }
}
