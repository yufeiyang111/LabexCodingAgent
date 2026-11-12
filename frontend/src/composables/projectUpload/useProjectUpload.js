import { computed, onBeforeUnmount, ref } from 'vue'
import { createProgressTracker, importPercent, planChunks, validateArchive } from './uploadModel'
import { transferErrorMessage } from '../useFileClipboard'

/**
 * 项目压缩包上传的唯一编排：校验 → 分片上传（真实进度/可取消）→ 服务端导入（真实条目进度）→ 完成。
 *
 * <p>为什么分片：整包一次 POST 会被反向代理或 CDN 按单请求体截断（Cloudflare Free/Pro 为 100 MB），
 * 应用层只会收到网关的 413，`maxArchiveBytes` 形同虚设。切片后每片都是独立小请求，
 * 同时「已完成片数 + 当前片已传字节」能给出比整包更细的进度。
 *
 * <p>导入阶段不编数字：`processedEntries / totalEntries` 由服务端在解压时逐条推进；
 * 条目总数读不出时退回不确定进度并只显示已用时间。
 */
export function useProjectUpload(options) {
  const api = options.api
  const notify = options.notify || { success() {}, info() {}, error() {} }
  const onDone = options.onDone
  const pollIntervalMs = Number(options.pollIntervalMs) || 1000

  const PHASE = { idle: 'idle', confirm: 'confirm', uploading: 'uploading', extracting: 'extracting', done: 'done' }

  const phase = ref(PHASE.idle)
  const file = ref(null)
  const limits = ref(null)
  const skipDependencies = ref(true)
  const progress = ref({ percent: 0, loaded: 0, total: 0, bytesPerSecond: 0, etaSeconds: null })
  const importing = ref({ percent: null, processedEntries: 0, totalEntries: 0 })
  const extractingElapsed = ref(null)
  const result = ref(null)
  const errorMessage = ref('')

  let abortController = null
  let extractingTimer = null
  let pollTimer = null
  let pendingSleepResolve = null
  let extractingStartedAt = 0
  let sessionId = null
  /** 每次复位自增，用来让旧的轮询/上传循环自己退出，避免关掉对话框后还在写状态。 */
  let runToken = 0
  const tracker = createProgressTracker()

  const busy = computed(() => phase.value === PHASE.uploading || phase.value === PHASE.extracting)
  /** 只有上传阶段可取消：导入已经开始写工作区，服务端没有中断通道。 */
  const cancellable = computed(() => phase.value === PHASE.uploading)
  const validation = computed(() => (file.value ? validateArchive(file.value, limits.value || {}) : { ok: true }))

  async function loadLimits() {
    if (limits.value) return limits.value
    try {
      const response = await api.archiveLimits({ silent: true })
      limits.value = response.data || {}
      if (typeof limits.value.skipDependencyDirectoriesByDefault === 'boolean') {
        skipDependencies.value = limits.value.skipDependencyDirectoriesByDefault
      }
    } catch (error) {
      // 读不到上限不该挡住上传：服务端仍会校验并给出带数字的错误。
      limits.value = {}
    }
    return limits.value
  }

  /** 入口：传入浏览器 File，打开确认阶段。 */
  async function begin(picked) {
    if (busy.value) return false
    file.value = picked || null
    result.value = null
    errorMessage.value = ''
    progress.value = { percent: 0, loaded: 0, total: 0, bytesPerSecond: 0, etaSeconds: null }
    importing.value = { percent: null, processedEntries: 0, totalEntries: 0 }
    extractingElapsed.value = null
    sessionId = null
    tracker.reset()
    phase.value = PHASE.confirm
    await loadLimits()
    return true
  }

  function setSkipDependencies(value) {
    skipDependencies.value = Boolean(value)
  }

  async function start() {
    if (phase.value !== PHASE.confirm || !file.value) return
    const check = validateArchive(file.value, limits.value || {})
    if (!check.ok) {
      errorMessage.value = check.message
      return
    }
    const chunks = planChunks(file.value, limits.value?.chunkSizeBytes)
    if (!chunks.length) {
      errorMessage.value = '无法读取分片大小，请刷新页面后重试'
      return
    }
    phase.value = PHASE.uploading
    errorMessage.value = ''
    abortController = new AbortController()
    const token = ++runToken
    try {
      const created = await api.createUploadSession({
        fileName: file.value.name,
        size: file.value.size,
        skipDependencyDirectories: skipDependencies.value
      })
      if (token !== runToken) return
      sessionId = created.data.sessionId
      await uploadChunks(chunks, abortController.signal, token)
      if (token !== runToken) return
      enterExtracting()
      await api.completeUploadSession(sessionId)
      await pollUntilFinished(token)
    } catch (error) {
      if (token !== runToken) return
      if (isCancelled(error)) {
        notify.info('已取消上传')
        await discardSession()
        reset()
        return
      }
      await discardSession()
      fail(error)
    } finally {
      if (token === runToken) {
        abortController = null
        stopPolling()
      }
    }
  }

  /** 逐片顺序上传：单片失败立即抛出，由外层决定重试或放弃，不做隐式重传。 */
  async function uploadChunks(chunks, signal, token) {
    let completedBytes = 0
    for (const chunk of chunks) {
      if (signal.aborted || token !== runToken) throw new DOMException('上传已取消', 'AbortError')
      const blob = file.value.slice(chunk.start, chunk.end)
      await api.uploadChunk(sessionId, chunk.index, blob, {
        signal,
        onUploadProgress: (event) => {
          const inChunk = event.total > 0 ? Math.min(event.loaded, event.total) : chunk.size
          progress.value = tracker.update(completedBytes + inChunk, file.value.size)
        }
      })
      completedBytes += chunk.size
      progress.value = tracker.update(completedBytes, file.value.size)
    }
    // 最后一个分片可能不发 loaded===total 的事件，这里把进度收到 100%。
    progress.value = tracker.update(file.value.size, file.value.size)
  }

  function enterExtracting() {
    if (phase.value !== PHASE.uploading) return
    phase.value = PHASE.extracting
    extractingStartedAt = Date.now()
    extractingElapsed.value = 0
    extractingTimer = setInterval(() => {
      extractingElapsed.value = (Date.now() - extractingStartedAt) / 1000
    }, 1000)
  }

  /** 轮询导入进度，直到 DONE / FAILED / CANCELLED。 */
  async function pollUntilFinished(token) {
    while (token === runToken) {
      const response = await api.getUploadSession(sessionId, { silent: true })
      if (token !== runToken) return
      const view = response.data || {}
      importing.value = {
        percent: importPercent(view.processedEntries, view.totalEntries),
        processedEntries: view.processedEntries || 0,
        totalEntries: view.totalEntries || 0
      }
      if (view.status === 'DONE') {
        finish(view)
        return
      }
      if (view.status === 'FAILED') {
        throw new Error(view.errorMessage || '导入失败')
      }
      if (view.status === 'CANCELLED') {
        throw new DOMException('上传已取消', 'AbortError')
      }
      await sleep(pollIntervalMs)
    }
  }

  function finish(view) {
    stopExtractingTimer()
    result.value = {
      projectId: view.projectId,
      projectName: view.projectName,
      fileCount: view.fileCount,
      totalSize: view.extractedSize,
      skippedCount: view.skippedCount ?? 0,
      skippedSamples: Array.isArray(view.skippedSamples) ? view.skippedSamples : []
    }
    phase.value = PHASE.done
    notify.success('上传成功')
    void (onDone?.(view) ?? Promise.resolve())
  }

  function fail(error) {
    stopExtractingTimer()
    errorMessage.value = transferErrorMessage(error)
    phase.value = PHASE.idle
    notify.error('上传失败: ' + errorMessage.value)
  }

  function cancel() {
    if (phase.value === PHASE.uploading) {
      abortController?.abort()
      return
    }
    if (phase.value === PHASE.extracting) {
      notify.info('导入已开始，无法取消；完成后会出现在项目列表里')
      return
    }
    reset()
  }

  /** 作废服务端会话（服务器递归删暂存），失败不影响界面复位。 */
  async function discardSession() {
    const current = sessionId
    sessionId = null
    if (!current) return
    try {
      await api.cancelUploadSession(current, { silent: true })
    } catch (error) {
      // 会话最终由服务端 TTL 清理。
    }
  }

  function reset() {
    runToken += 1
    stopExtractingTimer()
    stopPolling()
    abortController = null
    sessionId = null
    phase.value = PHASE.idle
    file.value = null
    result.value = null
    errorMessage.value = ''
    progress.value = { percent: 0, loaded: 0, total: 0, bytesPerSecond: 0, etaSeconds: null }
    importing.value = { percent: null, processedEntries: 0, totalEntries: 0 }
    extractingElapsed.value = null
    tracker.reset()
  }

  function stopExtractingTimer() {
    if (extractingTimer) {
      clearInterval(extractingTimer)
      extractingTimer = null
    }
  }

  function stopPolling() {
    if (pollTimer) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
    // 让等待中的轮询立即返回，否则复位后那个 Promise 永远不会 settle。
    if (pendingSleepResolve) {
      const resolve = pendingSleepResolve
      pendingSleepResolve = null
      resolve()
    }
  }

  function sleep(ms) {
    return new Promise((resolve) => {
      pendingSleepResolve = resolve
      pollTimer = setTimeout(() => {
        pollTimer = null
        pendingSleepResolve = null
        resolve()
      }, ms)
    })
  }

  function isCancelled(error) {
    return error?.name === 'CanceledError' || error?.code === 'ERR_CANCELED' || error?.name === 'AbortError'
  }

  onBeforeUnmount(() => {
    stopExtractingTimer()
    stopPolling()
  })

  return {
    phase: computed(() => phase.value),
    busy,
    cancellable,
    file,
    limits,
    skipDependencies,
    validation,
    progress,
    importing,
    extractingElapsed,
    result,
    errorMessage,
    begin,
    start,
    cancel,
    reset,
    setSkipDependencies,
    PHASES: PHASE
  }
}
