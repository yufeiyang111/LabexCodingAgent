// 唯一职责：同步相关的可调常量（冲突策略、状态文案、目录选择器选项）。
// 边界：只放常量与纯展示映射，不放同步逻辑（逻辑在 sync/ 域内）。

/** 与 changeSetSyncPlan.CONFLICT_POLICIES 保持同名，避免 UI 层出现第二套字符串。 */
export const SYNC_CONFLICT_POLICY_OPTIONS = Object.freeze([
  { value: 'strict', label: '严格：有冲突就停下', hint: '任一文件与云端基线不一致即整体不写入' },
  { value: 'skip', label: '跳过冲突文件', hint: '写入不冲突的部分，冲突项留给你手动处理' },
  { value: 'overwrite', label: '用云端覆盖本地', hint: '本地改动会被覆盖，仅在你确认放弃本地版本时使用' }
])

export const SYNC_DEFAULT_CONFLICT_POLICY = 'strict'

/** File System Access API 的选择器选项：只允许选目录，不创建新目录。 */
export const SYNC_DIRECTORY_PICKER_OPTIONS = Object.freeze({
  mode: 'readwrite',
  id: 'labex-sync-target',
  startIn: 'documents'
})

export const SYNC_STATE_LABELS = Object.freeze({
  idle: '待同步',
  scanning: '读取云端变更',
  planning: '比对本地文件',
  blocked: '存在冲突',
  applying: '写入本地',
  'rolling-back': '回退中',
  done: '已完成',
  failed: '失败'
})

export function syncStateLabel(state) {
  return SYNC_STATE_LABELS[state] ?? state ?? '未知状态'
}

/** 与 syncSessionState.SYNC_STATES 的取值逐一对齐；用于契约测试防止两处漂移。 */
export const SYNC_STATE_VALUES = Object.freeze(Object.keys(SYNC_STATE_LABELS))
