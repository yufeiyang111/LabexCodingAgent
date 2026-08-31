import { reactive } from 'vue'

/**
 * 冲突裁决对话框的控制器：把"异步等待用户决策"包装成 Promise。
 * 上传与粘贴共用同一实例；decisions 结构 { [relativePath]: 'skip' | 'overwrite' }，
 * 用户取消时 resolve(null)，调用方据此中止整个操作。
 */
export function useFileConflictResolver() {
  const state = reactive({
    visible: false,
    conflicts: [],
    kind: 'copy'
  })

  let pendingResolve = null

  function resolveConflicts(conflicts, kind) {
    const list = Array.isArray(conflicts) ? conflicts : []
    if (!list.length) return Promise.resolve({})
    return new Promise(resolve => {
      pendingResolve = resolve
      state.conflicts = list
      state.kind = kind || 'copy'
      state.visible = true
    })
  }

  function complete(decisions) {
    const resolve = pendingResolve
    pendingResolve = null
    state.visible = false
    state.conflicts = []
    if (resolve) resolve(decisions)
  }

  return { state, resolveConflicts, complete }
}
