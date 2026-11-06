// 唯一职责：把「服务端 feed 的 change-set + 本地探针结果」编译成可执行的同步计划。
// 边界：不触碰文件系统、不发网络请求、不渲染 UI；本地状态一律由调用方以 probe 注入。
// 设计模式：纯函数 + 冲突策略 Strategy（strict / skip / overwrite）。
//
// 输入契约（权威说明见 backend/src/main/java/com/labex/labexagent/sync/README.md）：
//   changeSets:    [{ changeSetId, taskId, status, updateTime, files: [FileEntry] }]
//   changedFiles:  [FileEntry]  ← 尾巴行：change_set_id <= 游标，但状态/内容在游标之后变了
//   兼容旧的扁平 changes（按 changeSetId 分组后同样处理），避免调用方两套逻辑。
// 关键不变量：
//   1. 单个 change-set 必须整体处理 —— 只应用一半就推进游标会漏同步（历史 P0）；
//   2. 同一路径的多次修改必须按序串联，后一步的"基"是前一步的结果（不能都拿初始探针判）；
//   3. 存在性由 changeType / beforeExisted 决定，不从"内容是否为空串"猜（新增文件的 beforeContent 也是空串）；
//   4. status 属于 {rejected, undone, failed} 的文件不得写盘（未批准/被撤回的变更）；
//   5. 被服务端截断的内容（*Truncated）不得用作基线，也不得作为写入目标；
//   6. 尾巴行必须纳入计划（否则"已同步集合被追加文件/被 undo"永久漏同步），
//      但**不得**推进游标 —— 游标只由"从前往后连续完整"的集合决定。

import { isExcludedPath, normalizeRelativePath } from './syncExclusion.js'
import { coalesceSteps } from './syncStepCoalescing.js'
import { CONFLICT_REASONS, SKIP_REASONS, buildStep, classifyFile, isBinaryContent } from './syncFileVerdict.js'
import { applyToLocalView, createLocalView } from './syncLocalView.js'

export const CONFLICT_POLICIES = Object.freeze({ STRICT: 'strict', SKIP: 'skip', OVERWRITE: 'overwrite' })

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

export function buildSyncPlan(payload = {}) {
  const cursor = Number(payload.cursor ?? 0) || 0
  const policy = payload.policy ?? CONFLICT_POLICIES.STRICT
  const ignorePatterns = Array.isArray(payload.ignorePatterns) ? payload.ignorePatterns : []
  const changeSets = collectChangeSets(payload)
  const tailFiles = collectTailFiles(payload)
  const local = createLocalView(payload.probe, payload.deliveredPaths)

  const steps = []
  const conflicts = []
  const skipped = []
  const changeSetProgress = []
  // 冲突是"这个文件不能安全写入"的判断，属于**路径级**事实。
  // 同一路径在服务端常有多行变更（create + 多次 modify），若按行记录，
  // 冲突对话框会把 1 个文件说成 3 个（用户现场实测），计数也会虚高。
  // 只保留最先出现的判定：它是这个路径"为什么写不了"的根因。
  const conflictPaths = new Set()
  const recordConflict = entry => {
    if (conflictPaths.has(entry.path)) return
    conflictPaths.add(entry.path)
    conflicts.push(entry)
  }

  const context = { cursor, policy, ignorePatterns, local, steps, conflicts, skipped }

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
      const outcome = evaluateFile(file, context)
      if (outcome.kind === 'skip') {
        skipped.push({ ...file, reason: outcome.reason })
        progress.accounted += 1
        continue
      }
      if (outcome.kind === 'conflict') {
        recordConflict({ ...file, reason: outcome.reason, localContent: outcome.localContent })
        progress.conflicts += 1
        // 未知本地状态不是"冲突"，而是不可判断；任何策略都不得绕过。
        if (outcome.reason === CONFLICT_REASONS.PROBE_UNKNOWN || policy === CONFLICT_POLICIES.SKIP) {
          if (policy === CONFLICT_POLICIES.SKIP) {
            skipped.push({ ...file, reason: `conflict:${outcome.reason}` })
            progress.accounted += 1
          }
        }
        continue
      }
      steps.push(outcome.step)
      applyToLocalView(local, file, outcome.step)
      progress.planned += 1
    }
    progress.complete = progress.total > 0
      && progress.conflicts === 0
      && progress.planned + progress.accounted === progress.total
    changeSetProgress.push(progress)
  }

  // 尾巴行（changedFiles）：change_set_id 已 <= 游标，但状态/内容在游标之后变了。
  // 它们不属于任何待下发集合，因此只影响"要写哪些文件"，**不参与**游标推进。
  for (const file of tailFiles) {
    const outcome = evaluateFile(file, context)
    if (outcome.kind === 'skip') {
      skipped.push({ ...file, reason: outcome.reason })
      continue
    }
    if (outcome.kind === 'conflict') {
      recordConflict({ ...file, reason: outcome.reason, localContent: outcome.localContent })
      if (policy === CONFLICT_POLICIES.SKIP) {
        skipped.push({ ...file, reason: `conflict:${outcome.reason}` })
      }
      continue
    }
    steps.push(outcome.step)
    applyToLocalView(local, file, outcome.step)
  }

  // 游标只能停在"从前往后连续完整"的 change-set 上：任何一个未完整处理的集合都必须拦住它，
  // 否则重试时那个集合里没落地的文件会被 `<= cursor` 永久跳过。
  const hasProbeUnknown = conflicts.some(item => item.reason === CONFLICT_REASONS.PROBE_UNKNOWN)
  const completedChangeSetIds = []
  for (const progress of changeSetProgress) {
    if (!progress.complete) break
    completedChangeSetIds.push(progress.changeSetId)
  }
  const lastCompleted = completedChangeSetIds.length > 0
    ? completedChangeSetIds[completedChangeSetIds.length - 1]
    : cursor
  const nextCursor = Math.max(cursor, lastCompleted)
  // steps 保留完整链式步骤（分类结果，审计与 UI 逐条可见）；
  // writeSteps 是合并同路径后的实际写盘计划——中间版本写下去没有任何价值。
  const coalesced = coalesceSteps(steps)
  return {
    cursor,
    nextCursor,
    policy,
    blocked: hasProbeUnknown || (policy === CONFLICT_POLICIES.STRICT && conflicts.length > 0),
    steps,
    writeSteps: coalesced.steps,
    conflicts,
    skipped,
    completedChangeSetIds,
    changeSetProgress,
    summary: {
      total: changeSets.reduce((sum, set) => sum + set.files.length, 0) + tailFiles.length,
      toApply: coalesced.steps.length,
      conflicts: conflicts.length,
      skipped: skipped.length,
      // 合并掉的中间版本数：解释"为什么待写入数小于变更数"。
      coalesced: coalesced.merged
    }
  }
}

/**
 * 单个文件条目的判定：跳过 / 冲突 / 产出步骤。
 * 集合内文件与尾巴行共用同一套规则，区别只在"游标过滤"（尾巴行按定义已在游标之内）。
 */
function evaluateFile(file, context) {
  const { cursor, policy, ignorePatterns, local } = context
  if (!file.path) return { kind: 'skip', reason: 'invalid-path' }
  if (!file.tail && file.changeSetId <= cursor) return { kind: 'skip', reason: 'already-applied' }
  if (NON_APPLICABLE_STATUSES.has(file.status)) {
    // undone 额外标记：客户端需要的是"把本地那一笔撤回"，而不是再写一次内容。
    return { kind: 'skip', reason: `status:${file.status}` }
  }
  if (isExcludedPath(file.path, { ignorePatterns })) return { kind: 'skip', reason: 'excluded' }
  // 二进制内容无论本地状态如何都不写盘：服务端存的是有损文本，写下去就是损坏文件。
  // 这条必须排在 classify 之前——否则本地恰好已有同文本时会走 already-applied，
  // 看起来"同步成功"，实际上这个路径从来没被正确落过。
  if (file.changeType !== 'delete' && isBinaryContent(file.afterContent)) {
    return { kind: 'skip', reason: SKIP_REASONS.BINARY_CONTENT }
  }

  const current = local.get(file.path)
  const verdict = classifyFile(file, current)

  if (verdict.kind === 'skip') return { kind: 'skip', reason: verdict.reason }
  if (verdict.kind === 'conflict') {
    const overwrite = policy === CONFLICT_POLICIES.OVERWRITE
      && verdict.reason !== CONFLICT_REASONS.PROBE_UNKNOWN
    if (overwrite) return { kind: 'step', step: buildStep(file, current, true) }
    return { kind: 'conflict', reason: verdict.reason, localContent: current.content }
  }
  return { kind: 'step', step: buildStep(file, current, false) }
}

/**
 * 收集尾巴行并幂等去重。
 * 服务端为了"同秒边界行不丢"会主动回退重叠窗口重扫，同一条 (changeId, status)
 * 可能被交付多次——重复交付必须折叠成一次。
 */
function collectTailFiles(payload) {
  const raw = Array.isArray(payload.changedFiles) ? payload.changedFiles : []
  const seen = new Set()
  const files = []
  for (const item of raw) {
    const normalized = { ...normalizeFile(item), tail: true }
    const key = `${normalized.changeId}\u0000${normalized.status}`
    if (normalized.changeId && seen.has(key)) continue
    if (normalized.changeId) seen.add(key)
    files.push(normalized)
  }
  return files
}