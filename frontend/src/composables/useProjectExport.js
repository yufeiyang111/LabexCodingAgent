import { computed, onBeforeUnmount, ref } from 'vue'
import { saveBlobAs } from '../constants/workspaceFiles.js'
import { transferErrorMessage } from './useFileClipboard.js'

/**
 * 项目异步导出的唯一编排：确认选项 → 创建任务 → 轮询进度 → 完成后从磁盘取 zip 保存。
 * 工作区顶栏与项目列表页共用；长任务不再占用 HTTP 连接，网关超时无从触发。
 */
export function useProjectExport(options) {
  const api = options.api
  const notify = options.notify || { success() {}, info() {}, error() {} }
  const defaultProjectName = options.projectName
  const saveFile = options.saveBlobAs || saveBlobAs
  const pollIntervalMs = typeof options.pollIntervalMs === 'number' ? options.pollIntervalMs : 1500

  const PHASE = { idle: 'idle', confirm: 'confirm', creating: 'creating', running: 'running', saving: 'saving' }
  const phase = ref(PHASE.idle)
  const progressPercent = ref(0)
  const packedBytes = ref(0)
  const totalBytes = ref(0)
  const errorMessage = ref('')
  const includeAll = ref(false)

  let jobId = null
  let targetProjectId = null
  let targetProjectName = ''
  let pollTimer = null

  const busy = computed(() => [PHASE.creating, PHASE.running, PHASE.saving].includes(phase.value))

  /** 入口：传入目标项目（列表页传具体项目，工作区可不传走默认）。 */
  function begin(projectOverride) {
    const projectId = projectOverride?.projectId ?? options.projectId?.value
    if (!projectId || busy.value) return
    targetProjectId = projectId
    targetProjectName = projectOverride?.projectName
      ?? (typeof defaultProjectName?.value === 'string' ? defaultProjectName.value : '')
      ?? ''
    if (projectOverride?.includeAll !== undefined) {
      includeAll.value = !!projectOverride.includeAll
    }
    errorMessage.value = ''
    progressPercent.value = 0
    packedBytes.value = 0
    totalBytes.value = 0
    phase.value = PHASE.confirm
  }

  function confirmStart() {
    if (phase.value !== PHASE.confirm) return
    void createJob()
  }

  async function createJob() {
    phase.value = PHASE.creating
    try {
      const response = await api.createExportJob(targetProjectId, includeAll.value)
      jobId = response.data?.jobId
      if (!jobId) throw new Error('导出任务创建失败')
      applyProgress(response.data)
      phase.value = PHASE.running
      schedulePoll()
    } catch (error) {
      fail(error)
    }
  }

  function schedulePoll() {
    pollTimer = setTimeout(() => void pollOnce(), pollIntervalMs)
  }

  async function pollOnce() {
    if (!jobId) return
    try {
      const response = await api.getExportJob(targetProjectId, jobId)
      const data = response.data || {}
      applyProgress(data)
      if (data.status === 'SUCCESS') {
        await finishDownload()
      } else if (data.status === 'FAILED') {
        fail(new Error(data.errorMessage || '导出失败'))
      } else if (data.status === 'CANCELLED') {
        reset()
      } else {
        schedulePoll()
      }
    } catch (error) {
      fail(error)
    }
  }

  async function finishDownload() {
    phase.value = PHASE.saving
    try {
      // blob 端点解析值就是 Blob 本体（request.js 拦截器契约）。
      const blob = await api.downloadExportJob(targetProjectId, jobId)
      const name = `${targetProjectName || 'project'}.zip`
      saveFile(blob, name)
      notify.success(`已导出 ${name}`)
      reset()
    } catch (error) {
      fail(error)
    }
  }

  /** 取消：停止轮询并通知后端中止打包、清理半成品。 */
  async function cancel() {
    stopPolling()
    const pendingJobId = jobId
    reset()
    if (!pendingJobId) return
    try {
      await api.cancelExportJob(targetProjectId, pendingJobId)
      notify.info('已取消导出')
    } catch (ignored) {
      // 后端清理有 TTL 兜底，取消失败不打扰用户。
    }
  }

  function applyProgress(data) {
    if (typeof data.progressPercent === 'number') progressPercent.value = data.progressPercent
    if (typeof data.sizeBytes === 'number') packedBytes.value = data.sizeBytes
    if (typeof data.totalBytes === 'number') totalBytes.value = data.totalBytes
  }

  function fail(error) {
    stopPolling()
    errorMessage.value = transferErrorMessage(error)
    phase.value = PHASE.idle
    notify.error('导出失败: ' + errorMessage.value)
  }

  function reset() {
    stopPolling()
    jobId = null
    phase.value = PHASE.idle
  }

  function stopPolling() {
    if (pollTimer) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
  }

  onBeforeUnmount(stopPolling)

  return {
    phase: computed(() => phase.value),
    busy,
    progressPercent,
    packedBytes,
    totalBytes,
    errorMessage,
    includeAll,
    begin,
    confirmStart,
    cancel,
    PHASES: PHASE
  }
}
