/** 复制/移动/上传共用的冲突协议流程：无决策先探测，冲突交给用户裁决后带决策重发。 */

/**
 * @param {string} kind 'copy' | 'move' | 'upload'
 * @param {(decisions: Object|null) => Promise} invoke 后端调用；decisions 为 null 表示首次探测
 * @param {(conflicts: Array, kind: string) => Promise<Object|null>} resolveConflicts 用户取消时返回 null
 * @returns {{status: 'done'|'cancelled'|'skipped', data: Object|null}}
 */
export async function runWithConflictFlow(kind, invoke, resolveConflicts) {
  const first = await invoke(null)
  const body = first?.data
  if (!body || body.status !== 'conflict') {
    return { status: body?.status === 'skipped' ? 'skipped' : 'done', data: body }
  }
  const decisions = await resolveConflicts(body.conflicts || [], kind)
  if (!decisions) return { status: 'cancelled', data: null }
  const second = await invoke(decisions)
  const payload = second?.data
  return { status: payload?.status === 'skipped' ? 'skipped' : 'done', data: payload }
}
