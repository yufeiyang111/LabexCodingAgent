import { ref } from 'vue'
import { runWithConflictFlow } from './fileTransferFlow'
import { transferErrorMessage } from './useFileClipboard'

/**
 * 上传到指定文件夹：持有隐藏 input 引用与目标目录，冲突流程与其他传输操作一致。
 * v1 仅支持平铺多选文件；webkitRelativePath 目录结构上传留待后续迭代。
 */
export function useFileUploads(options) {
  const api = options.api
  const projectId = options.projectId
  const notify = options.notify || { success() {}, info() {}, error() {} }
  const onDone = options.onDone

  const inputRef = ref(null)
  const pendingTargetDir = ref('')
  const uploading = ref(false)
  let pendingFiles = []

  function pickFiles(targetDir) {
    pendingTargetDir.value = targetDir || ''
    pendingFiles = []
    if (!inputRef.value) return
    inputRef.value.value = ''
    inputRef.value.click()
  }

  function onInputChange(event) {
    pendingFiles = Array.from(event.target?.files || [])
    event.target.value = ''
    if (pendingFiles.length) void upload(pendingFiles)
  }

  async function upload(files, resolveConflicts, targetDirOverride) {
    if (!projectId.value || !files.length || uploading.value) return false
    uploading.value = true
    try {
      const targetDir = targetDirOverride !== undefined ? targetDirOverride : pendingTargetDir.value
      const result = await runWithConflictFlow(
        'upload',
        decisions => api.uploadFiles(projectId.value, targetDir, files, { decisions }),
        resolveConflicts
      )
      if (result.status === 'cancelled') return false
      const payload = result.data || {}
      if (result.status === 'skipped') notify.info(`已跳过同名文件，上传 ${payload.savedCount ?? 0} 个`)
      else notify.success(`已上传 ${payload.savedCount ?? files.length} 个文件`)
      await (onDone?.() ?? Promise.resolve())
      return true
    } catch (error) {
      notify.error('上传失败: ' + transferErrorMessage(error))
      return false
    } finally {
      uploading.value = false
      pendingFiles = []
    }
  }

  return { inputRef, uploading, pickFiles, onInputChange, upload }
}
