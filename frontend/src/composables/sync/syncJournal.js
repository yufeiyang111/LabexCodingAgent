// 唯一职责：记录已应用的同步条目，并据此生成逆操作（回退 = 逆序重放逆操作）。
// 边界：不读写文件系统；journal 必须是可 JSON 序列化的普通对象，便于持久化到 IndexedDB。
// 设计模式：Command（每个已应用条目 = 一个可逆命令）。

export const JOURNAL_VERSION = 1

export function createJournal() {
  return { version: JOURNAL_VERSION, entries: [], cursor: 0 }
}

function normalizeStep(step) {
  const op = step?.op === 'delete' ? 'delete' : 'write'
  return {
    changeId: String(step?.changeId ?? ''),
    changeSetId: Number(step?.changeSetId ?? 0) || 0,
    path: String(step?.path ?? ''),
    op,
    content: step?.content ?? null,
    previousContent: step?.previousContent ?? null,
    previousExisted: step?.previousExisted === true,
    appliedContent: typeof step?.appliedContent === 'string'
      ? step.appliedContent
      : op === 'write' ? String(step?.content ?? '') : null,
    appliedExisted: typeof step?.appliedExisted === 'boolean'
      ? step.appliedExisted
      : op !== 'delete',
    overwritten: step?.overwritten === true
  }
}

export function recordApplied(journal, step, meta = {}) {
  const base = journal ?? createJournal()
  const normalized = normalizeStep(step)
  const entry = {
    ...normalized,
    appliedContent: typeof meta.appliedContent === 'string' ? meta.appliedContent : normalized.appliedContent,
    appliedExisted: typeof meta.appliedExisted === 'boolean' ? meta.appliedExisted : normalized.appliedExisted,
    appliedAt: Number(meta.appliedAt ?? Date.now()),
    projectId: meta.projectId ?? null
  }
  return {
    version: JOURNAL_VERSION,
    cursor: Math.max(Number(base.cursor ?? 0) || 0, entry.changeSetId),
    entries: [...(base.entries ?? []), entry]
  }
}
export function recordAppliedBatch(journal, steps, meta = {}) {
  return (Array.isArray(steps) ? steps : []).reduce(
    (acc, step) => recordApplied(acc, step, meta),
    journal ?? createJournal()
  )
}

// 逆序生成逆操作：最近应用的先回退。limit 为本次回退条数（默认全部）。
export function buildRollbackSteps(journal, options = {}) {
  const entries = Array.isArray(journal?.entries) ? journal.entries : []
  const limit = Number.isFinite(Number(options.limit)) ? Math.max(0, Number(options.limit)) : entries.length
  const slice = entries.slice(Math.max(0, entries.length - limit))
  const steps = []
  for (let index = slice.length - 1; index >= 0; index -= 1) {
    const entry = slice[index]
    if (entry.op === 'delete') {
      // 之前的删除操作：回退 = 恢复 previousContent（若此前不存在则无需动作）
      if (!entry.previousExisted) continue
      steps.push({ path: entry.path, op: 'write', content: entry.previousContent ?? '', from: entry.changeId })
      continue
    }
    if (entry.previousExisted) {
      steps.push({ path: entry.path, op: 'write', content: entry.previousContent ?? '', from: entry.changeId })
    } else {
      steps.push({ path: entry.path, op: 'delete', content: null, from: entry.changeId })
    }
  }
  return steps
}

// 回退完成后裁剪 journal：被回退的条目移出，已确认的回退游标随之回退。
export function markRolledBack(journal, rolledBackCount) {
  const count = Math.max(0, Number(rolledBackCount) || 0)
  const entries = Array.isArray(journal?.entries) ? journal.entries : []
  const kept = entries.slice(0, Math.max(0, entries.length - count))
  const cursor = kept.reduce((max, entry) => Math.max(max, Number(entry.changeSetId) || 0), 0)
  return { version: JOURNAL_VERSION, entries: kept, cursor }
}

export function summarizeJournal(journal) {
  const entries = Array.isArray(journal?.entries) ? journal.entries : []
  const overwritten = entries.filter(entry => entry.overwritten).length
  return { entries: entries.length, cursor: Number(journal?.cursor ?? 0) || 0, overwritten }
}
