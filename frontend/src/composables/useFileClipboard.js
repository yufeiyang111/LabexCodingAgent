import { computed, ref } from 'vue'
import { runWithConflictFlow } from './fileTransferFlow.js'

/**
 * 文件剪贴板（内存态）：剪切/复制单个条目 + 粘贴到目标文件夹。
 * 冲突流程复用 useFileConflictResolver；操作成功后通过 onDone 触发树刷新。
 */
export function useFileClipboard(options) {
  const api = options.api
  const projectId = options.projectId
  const notify = options.notify || { success() {}, info() {}, error() {} }
  const onDone = options.onDone

  const mode = ref(null)
  const sourcePath = ref('')
  const hasClipboard = computed(() => Boolean(sourcePath.value))

  function copy(nodePath) {
    mode.value = 'copy'
    sourcePath.value = nodePath
    notify.success('已复制，请在目标文件夹中粘贴')
  }

  function cut(nodePath) {
    mode.value = 'cut'
    sourcePath.value = nodePath
    notify.success('已剪切，请在目标文件夹中粘贴')
  }

  function clear() {
    mode.value = null
    sourcePath.value = ''
  }

  async function pasteInto(parentPath, resolveConflicts) {
    if (!sourcePath.value || !projectId.value) return false
    const kind = mode.value === 'cut' ? 'move' : 'copy'
    const source = sourcePath.value
    try {
      const result = await runWithConflictFlow(
        kind,
        decisions => kind === 'move'
          ? api.moveItem(projectId.value, source, parentPath, decisions)
          : api.copyItem(projectId.value, source, parentPath, decisions),
        resolveConflicts
      )
      if (result.status === 'cancelled') return false
      if (result.status === 'skipped') notify.info('目标已存在同名条目，已跳过')
      else notify.success(kind === 'move' ? '移动完成' : '粘贴完成')
      clear()
      await (onDone?.() ?? Promise.resolve())
      return true
    } catch (error) {
      notify.error((kind === 'move' ? '移动失败: ' : '粘贴失败: ') + transferErrorMessage(error))
      return false
    }
  }

  return { hasClipboard, copy, cut, clear, pasteInto }
}

export function transferErrorMessage(error) {
  return error?.response?.data?.message || error?.message || '未知错误'
}
