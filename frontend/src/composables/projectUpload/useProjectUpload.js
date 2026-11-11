import { computed, onBeforeUnmount, ref } from 'vue'
import { createProgressTracker, validateArchive } from './uploadModel'
import { transferErrorMessage } from '../useFileClipboard'

/**
 * 项目压缩包上传的唯一编排：校验 → 上传（带进度/取消）→ 解压等待 → 完成。
 *
 * 与 useProjectExport 的差异：导出是服务端任务，进度靠轮询；上传的字节由浏览器发出，
 * 进度只能靠 onUploadProgress 实时读，不能照抄轮询。解压阶段没有任何进度通道，
 * 因此只显示已用时间，并明确写出「无法中断」——给一个按不动的取消按钮是骗人。
 */
export function useProjectUpload(options) {
  const api = options.api
  const notify = options.notify || { success() {}, info() {}, error() {} }
  const onDone = options.onDone

  const PHASE = { idle: 'idle', confirm: 'confirm', uploading: 'uploading', extracting: 'extracting', done: 'done' }

  const phase = ref(PHASE.idle)
  const file = ref(null)
  const limits = ref(null)
  const skipDependencies = ref(true)
  const progress = ref({ percent: 0, loaded: 0, total: 0, bytesPerSecond: 0, etaSeconds: null })
  const extractingElapsed = ref(null)
  const result = ref(null)
  const errorMessage = ref('')

  let abortController = null
  let extractingTimer = null
  let extractingStartedAt = 0
  const tracker = createProgressTracker()

  const busy = computed(() => phase.value === PHASE.uploading || phase.value === PHASE.extracting)
  /** 只有上传阶段可取消；解压阶段服务端没有中断通道。 */
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
    extractingElapsed.value = null
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
    phase.value = PHASE.uploading
    errorMessage.value = ''
    abortController = new AbortController()
    try {
      const response = await api.upload(file.value, {
        skipDependencyDirectories: skipDependencies.value,
        signal: abortController.signal,
        onUploadProgress: (event) => {
          const total = event.total > 0 ? event.total : file.value.size
          progress.value = tracker.update(event.loaded, total)
          if (event.loaded >= total) enterExtracting()
        }
      })
      // 有些浏览器不发 loaded===total 的最后一个事件，这里兜底切阶段。
      enterExtracting()
      finish(response.data)
    } catch (error) {
      if (isCancelled(error)) {
        notify.info('已取消上传')
        reset()
        return
      }
      fail(error)
    } finally {
      abortController = null
    }
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

  function finish(data) {
    stopExtractingTimer()
    result.value = {
      projectId: data?.projectId,
      projectName: data?.projectName,
      fileCount: data?.fileCount,
      totalSize: data?.totalSize,
      skippedCount: data?.skippedCount ?? 0,
      skippedSamples: Array.isArray(data?.skippedSamples) ? data.skippedSamples : []
    }
    phase.value = PHASE.done
    notify.success('上传成功')
    void (onDone?.(data) ?? Promise.resolve())
  }

  function fail(error) {
    stopExtractingTimer()
    errorMessage.value = transferErrorMessage(error)
    phase.value = PHASE.idle
    notify.error('上传失败: ' + errorMessage.value)
  }

  async function cancel() {
    if (phase.value === PHASE.uploading) {
      abortController?.abort()
      return
    }
    if (phase.value === PHASE.extracting) {
      notify.info('解压已开始，无法取消；完成后会出现在项目列表里')
      return
    }
    reset()
  }

  function reset() {
    stopExtractingTimer()
    abortController = null
    phase.value = PHASE.idle
    file.value = null
    result.value = null
    errorMessage.value = ''
    progress.value = { percent: 0, loaded: 0, total: 0, bytesPerSecond: 0, etaSeconds: null }
    extractingElapsed.value = null
    tracker.reset()
  }

  function stopExtractingTimer() {
    if (extractingTimer) {
      clearInterval(extractingTimer)
      extractingTimer = null
    }
  }

  function isCancelled(error) {
    return error?.name === 'CanceledError' || error?.code === 'ERR_CANCELED' || error?.name === 'AbortError'
  }

  onBeforeUnmount(stopExtractingTimer)

  return {
    phase: computed(() => phase.value),
    busy,
    cancellable,
    file,
    limits,
    skipDependencies,
    validation,
    progress,
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
