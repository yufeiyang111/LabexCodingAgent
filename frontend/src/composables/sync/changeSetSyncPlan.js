// 唯一职责：把「服务端 feed 的 change-set + 本地探针结果」编译成可执行的同步计划。
// 边界：不触碰文件系统、不发网络请求、不渲染 UI；本地状态一律由调用方以 probe 注入。
// 设计模式：纯函数 + 冲突策略 Strategy（strict / skip / overwrite）。
//
// 输入契约（权威说明见 backend/src/main/java/com/labex/labexagent/sync/README.md）：
//   changeSets: [{ changeSetId, taskId, status, updateTime, files: [FileEntry] }]
//   兼容旧的扁平 changes（按 changeSetId 分组后同样处理），避免调用方两套逻辑。
// 关键不变量：
//   1. 单个 change-set 必须整体处理 —— 只应用一半就推进游标会漏同步（历史 P0）；
//   2. 同一路径的多次修改必须按序串联，后一步的"基"是前一步的结果（不能都拿初始探针判）；
//   3. 存在性由 changeType / beforeExisted 决定，不从"内容是否为空串"猜（新增文件的 beforeContent 也是空串）；
//   4. status 属于 {rejected, undone, failed} 的文件不得写盘（未批准/被撤回的变更）；
//   5. 被服务端截断的内容（*Truncated）不得用作基线，也不得作为写入目标。

import { isExcludedPath, normalizeRelativePath } from './syncExclusion.js'

export const CONFLICT_POLICIES = Object.freeze({ STRICT: 'strict', SKIP: 'skip', OVERWRITE: 'overwrite' })

const CONFLICT_MISSING_LOCAL = 'missing-local'
const CONFLICT_CONTENT_DRIFT = 'content-drift'
const CONFLICT_LOCAL_OCCUPIED = 'local-occupied'
const CONFLICT_TRUNCATED_BASELINE = 'truncated-baseline'
const CONFLICT_TRUNCATED_TARGET = 'truncated-target'
const CONFLICT_PROBE_UNKNOWN = 'probe-unknown'

/** 这些状态表示"该变更当前不该落到工作区"（未批准 / 用户已撤回 / 执行失败）。 */
const NON_APPLICABLE_STATUSES = new Set(['rejected', 'undone', 'failed'])

const CHANGE_TYPE_ALIASES = new Map([
  ['add', 'create'],
  ['new', 'create'],
  ['del', 'delete'],
  ['remove', 'delete'],
  ['update', 'modify'],
  ['edit', 'modify']
])

function normalizeFile(raw) {
  const changeSetId = Number(raw?.changeSetId ?? raw?.change_set_id ?? 0)
  const path = normalizeRelativePath(raw?.relativePath ?? raw?.relative_path ?? raw?.path)
  const rawType = String(raw?.changeType ?? raw?.change_type ?? raw?.type ?? 'modify').toLowerCase()
  const changeType = CHANGE_TYPE_ALIASES.get(rawType) ?? rawType
  const beforeContent = raw?.beforeContent ?? raw?.before_content ?? null
  const afterContent = raw?.afterContent ?? raw?.after_content ?? null
  // 存在性：服务端给了 beforeExisted 就用它；旧输入按"create 一定不存在 / delete 一定存在 /
  // 其余看 beforeContent 是否为 null"推导（与历史语义等价）。
  const beforeExisted = typeof raw?.beforeExisted === 'boolean'
    ? raw.beforeExisted
    : changeType === 'create'
      ? false
      : changeType === 'delete'
        ? true
        : beforeContent !== null
  return {
    changeId: String(raw?.changeId ?? raw?.id ?? raw?.change_id ?? ''),
    changeSetId: Number.isFinite(changeSetId) ? changeSetId : 0,
    path,
    changeType,
    status: String(raw?.status ?? 'pending').toLowerCase(),
    beforeContent,
    afterContent,
    beforeExisted,
    beforeTruncated: raw?.beforeTruncated === true,
    afterTruncated: raw?.afterTruncated === true
  }
}

/** 收集 change-set（按 id 升序）：服务端顺序即权威顺序，调用方不要依赖输入顺序。 */
function collectChangeSets(payload) {
  const sets = []
  if (Array.isArray(payload.changeSets) && payload.changeSets.length > 0) {
    for (const raw of payload.changeSets) {
      const changeSetId = Number(raw?.changeSetId ?? raw?.change_set_id ?? 0) || 0
      const seen = new Set()
      const files = []
      for (const file of Array.isArray(raw?.files) ? raw.files : []) {
        const normalized = normalizeFile({ ...file, changeSetId })
        // 同一集合内按 changeId 去重：尾巴重扫/重试会重复投递同一条（幂等要求）。
        if (normalized.changeId && seen.has(normalized.changeId)) continue
        if (normalized.changeId) seen.add(normalized.changeId)
        files.push(normalized)
      }
      sets.push({ changeSetId, files })
    }
  } else {
    const byId = new Map()
    for (const raw of Array.isArray(payload.changes) ? payload.changes : []) {
      const file = normalizeFile(raw)
      if (!byId.has(file.changeSetId)) byId.set(file.changeSetId, [])
      byId.get(file.changeSetId).push(file)
    }
    for (const [changeSetId, files] of byId.entries()) sets.push({ changeSetId, files })
  }
  sets.sort((left, right) => left.changeSetId - right.changeSetId)
  return sets
}

/**
 * 虚拟本地视图：探针给的是"同步开始前"的状态，而同一批次里同一路径可能被多次修改。
 * 每产出一个步骤就把结果写回视图，后一步才能拿到正确的"基"（C04）。
 */
function createLocalView(probe) {
  const overlay = new Map()
  return {
    get(path) {
      if (overlay.has(path)) return overlay.get(path)
      const entry = probe?.get ? probe.get(path) : probe?.[path]
      if (!entry) return { exists: false, content: null, unknown: true }
      return {
        exists: entry.exists === true,
        content: entry.content ?? null,
        unknown: entry.state === 'unknown' || entry.exists === null
      }
    },
    set(path, value) {
      overlay.set(path, value)
    }
  }
}

function classify(file, local) {
  if (local.unknown) return { kind: 'conflict', reason: CONFLICT_PROBE_UNKNOWN }
  if (file.changeType === 'delete') {
    if (!local.exists) return { kind: 'skip', reason: 'already-absent' }
    if (file.beforeTruncated) return { kind: 'conflict', reason: CONFLICT_TRUNCATED_BASELINE }
    if (file.beforeContent !== null && local.content !== file.beforeContent) {
      return { kind: 'conflict', reason: CONFLICT_CONTENT_DRIFT }
    }
    return { kind: 'step', op: 'delete' }
  }
  if (file.afterTruncated) {
    // 目标内容本身被截断：写下去就是把残缺内容落盘，宁可停在冲突让人处理。
    return { kind: 'conflict', reason: CONFLICT_TRUNCATED_TARGET }
  }
  if (file.changeType === 'create' || file.beforeExisted === false) {
    if (!local.exists) return { kind: 'step', op: 'write' }
    if (file.afterContent !== null && local.content === file.afterContent) {
      return { kind: 'skip', reason: 'already-applied' }
    }
    return { kind: 'conflict', reason: CONFLICT_LOCAL_OCCUPIED }
  }
  if (!local.exists) return { kind: 'conflict', reason: CONFLICT_MISSING_LOCAL }
  if (file.beforeTruncated) return { kind: 'conflict', reason: CONFLICT_TRUNCATED_BASELINE }
  if (file.beforeContent === null || local.content === file.beforeContent) {
    return { kind: 'step', op: 'write' }
  }
  return { kind: 'conflict', reason: CONFLICT_CONTENT_DRIFT }
}

export function buildSyncPlan(payload = {}) {
  const cursor = Number(payload.cursor ?? 0) || 0
  const policy = payload.policy ?? CONFLICT_POLICIES.STRICT
  const ignorePatterns = Array.isArray(payload.ignorePatterns) ? payload.ignorePatterns : []
  const changeSets = collectChangeSets(payload)
  const local = createLocalView(payload.probe)

  const steps = []
  const conflicts = []
  const skipped = []
  const changeSetProgress = []

  for (const changeSet of changeSets) {
    const progress = {
      changeSetId: changeSet.changeSetId,
      total: changeSet.files.length,
      planned: 0,
      accounted: 0,
      conflicts: 0,
      complete: false
    }
    for (const file of changeSet.files) {
      const account = reason => {
        skipped.push({ ...file, reason })
        progress.accounted += 1
      }
      if (!file.path) {
        account('invalid-path')
        continue
      }
      if (file.changeSetId <= cursor) {
        account('already-applied')
        continue
      }
      if (NON_APPLICABLE_STATUSES.has(file.status)) {
        // undone 额外标记：客户端需要的是"把本地那一笔撤回"，而不是再写一次内容。
        account(`status:${file.status}`)
        continue
      }
      if (isExcludedPath(file.path, { ignorePatterns })) {
        account('excluded')
        continue
      }

      const current = local.get(file.path)
      const verdict = classify(file, current)

      if (verdict.kind === 'skip') {
        account(verdict.reason)
        continue
      }
      if (verdict.kind === 'conflict') {
        conflicts.push({ ...file, reason: verdict.reason, localContent: current.content })
        const probeUnknown = verdict.reason === CONFLICT_PROBE_UNKNOWN
        // 未知本地状态不是“冲突”，而是不可判断；任何策略都不得绕过。
        if (probeUnknown) {
          progress.conflicts += 1
          if (policy === CONFLICT_POLICIES.SKIP) account(`conflict:${verdict.reason}`)
          continue
        }
        if (policy === CONFLICT_POLICIES.OVERWRITE) {
          steps.push(buildStep(file, current, true))
          local.set(file.path, { exists: true, content: file.afterContent })
          progress.planned += 1
          continue
        }
        progress.conflicts += 1
        if (policy === CONFLICT_POLICIES.SKIP) account(`conflict:${verdict.reason}`)
        continue
      }

      steps.push(buildStep(file, current, false))
      local.set(file.path, {
        exists: verdict.op !== 'delete',
        content: verdict.op === 'delete' ? null : file.afterContent
      })
      progress.planned += 1
    }
    progress.complete = progress.total > 0
      && progress.conflicts === 0
      && progress.planned + progress.accounted === progress.total
    changeSetProgress.push(progress)
  }

  // 游标只能停在"从前往后连续完整"的 change-set 上：任何一个未完整处理的集合都必须拦住它，
  // 否则重试时那个集合里没落地的文件会被 `<= cursor` 永久跳过。
  const hasProbeUnknown = conflicts.some(item => item.reason === CONFLICT_PROBE_UNKNOWN)
  const completedChangeSetIds = []
  for (const progress of changeSetProgress) {
    if (!progress.complete) break
    completedChangeSetIds.push(progress.changeSetId)
  }
  const lastCompleted = completedChangeSetIds.length > 0
    ? completedChangeSetIds[completedChangeSetIds.length - 1]
    : cursor
  const nextCursor = Math.max(cursor, lastCompleted)

  return {
    cursor,
    nextCursor,
    policy,
    blocked: hasProbeUnknown || (policy === CONFLICT_POLICIES.STRICT && conflicts.length > 0),
    steps,
    conflicts,
    skipped,
    completedChangeSetIds,
    changeSetProgress,
    summary: {
      total: changeSets.reduce((sum, set) => sum + set.files.length, 0),
      toApply: steps.length,
      conflicts: conflicts.length,
      skipped: skipped.length
    }
  }
}

function buildStep(file, local, overwritten) {
  return {
    changeId: file.changeId,
    changeSetId: file.changeSetId,
    path: file.path,
    changeType: file.changeType,
    status: file.status,
    op: file.changeType === 'delete' ? 'delete' : 'write',
    content: file.changeType === 'delete' ? null : file.afterContent,
    previousContent: local.exists ? local.content : null,
    previousExisted: local.exists,
    overwritten: overwritten === true
  }
}
