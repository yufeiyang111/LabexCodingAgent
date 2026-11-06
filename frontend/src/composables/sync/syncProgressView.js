// 唯一职责：把同步会话投影成进度视图（百分比、当前文件、剩余时间）。
// 边界：纯函数，不读会话之外的状态、不触发副作用；UI 只消费它的输出。

/**
 * @param {object} session 同步会话（含 state / applied / steps / summary）
 * @param {object} options
 * @param {number} [options.startedAt] 本次同步开始时间戳（ms）
 * @param {number} [options.now]       当前时间戳（ms），用于计算速率
 */
export function buildSyncProgressView(session, options = {}) {
  const state = session?.state ?? 'idle'
  const steps = Array.isArray(session?.steps) ? session.steps : []
  const summary = session?.summary ?? null
  const applied = Number(session?.applied ?? 0) || 0

  // 总数优先取 summary.toApply（合并后的实际写盘数），退回 steps 长度。
  const plannedTotal = summary ? Number(summary.toApply ?? 0) || 0 : steps.length
  const total = Math.max(plannedTotal, 0)
  const done = Math.min(Math.max(applied, 0), total)

  // 尚未产出计划时无法给出百分比：用不确定态（跑马灯）而不是假的 0%。
  const indeterminate = total === 0 && state !== 'done' && state !== 'failed'
  const percent = total === 0
    ? (state === 'done' || state === 'failed' ? 100 : 0)
    : Math.round((done / total) * 100)

  const inProgress = ['applying', 'rolling-back'].includes(state)
  const currentPath = inProgress && done < total ? (steps[done]?.path ?? null) : null

  const startedAt = Number(options.startedAt ?? 0) || 0
  const now = Number(options.now ?? 0) || 0
  const elapsedMs = startedAt > 0 && now > startedAt ? now - startedAt : 0
  // 剩余时间只在"已写过至少一个文件"时才有意义：否则样本量为 0，任何估计都是编的。
  const remainingMs = done > 0 && total > done && elapsedMs > 0
    ? Math.round((elapsedMs / done) * (total - done))
    : null

  return {
    state,
    total,
    done,
    percent,
    indeterminate,
    currentPath,
    conflicts: Number(summary?.conflicts ?? 0) || 0,
    skipped: Number(summary?.skipped ?? 0) || 0,
    elapsedMs,
    remainingMs,
    error: session?.error ?? null
  }
}

/** 把毫秒格式化成 "1分23秒"，用于 UI 文案。 */
export function formatDuration(ms) {
  const value = Number(ms)
  if (!Number.isFinite(value) || value <= 0) return ''
  const totalSeconds = Math.round(value / 1000)
  if (totalSeconds < 60) return `${totalSeconds} 秒`
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return seconds === 0 ? `${minutes} 分钟` : `${minutes} 分 ${seconds} 秒`
}

const LABELS = {
  idle: '待同步',
  scanning: '正在读取云端变更',
  planning: '正在比对本地文件',
  blocked: '存在冲突，未写入',
  applying: '正在写入本地',
  'rolling-back': '正在回退',
  done: '同步完成',
  failed: '同步失败'
}

/** 进度条上方的状态文案。 */
export function syncProgressLabel(view) {
  const state = view?.state ?? 'idle'
  if (state === 'applying' && view?.total > 0) {
    return `正在写入本地（${view.percent}%）`
  }
  if (state === 'done' && view?.total === 0) {
    return '本地已是最新'
  }
  return LABELS[state] ?? state
}
