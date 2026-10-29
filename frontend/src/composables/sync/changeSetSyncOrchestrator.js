// 唯一职责：编排一次「拉取变更 → 探针 → 计划 → 应用 → 记 journal」的完整同步会话。
// 边界：依赖全部以参数注入（变更加载器、目录适配器、journal 存取），本模块不直接发请求、不直接碰 DOM。
// 设计模式：State（复用 syncSessionState）+ Observer（onProgress 回调）+ 依赖注入（便于测试与替换后端）。

import { buildSyncPlan } from './changeSetSyncPlan.js'
import { markRolledBack, recordApplied, buildRollbackSteps } from './syncJournal.js'
import { collectProbe } from './syncProbe.js'
import { SYNC_STATES, createSyncSession, transition } from './syncSessionState.js'

function noop() {}

export function createChangeSetSyncOrchestrator(dependencies = {}) {
  const loadChanges = dependencies.loadChanges
  const adapter = dependencies.adapter
  const journalStore = dependencies.journalStore
  const onProgress = typeof dependencies.onProgress === 'function' ? dependencies.onProgress : noop
  const now = typeof dependencies.now === 'function' ? dependencies.now : () => Date.now()

  if (typeof loadChanges !== 'function') throw new Error('orchestrator requires a loadChanges function')
  if (!adapter || typeof adapter.writeTextFile !== 'function') throw new Error('orchestrator requires a directory adapter')
  // journal 是回退的唯一依据：绝不内置"空 journal + 无操作 save"的降级实现。
  // 那种降级会让"文件已经改完、恢复记录却从未落盘"变成静默状态——失败时还报成功。
  // 这里在**任何文件写入之前**（构造函数阶段）就失败，把缺失的持久化依赖暴露成接线错误。
  if (!journalStore || typeof journalStore.load !== 'function' || typeof journalStore.save !== 'function') {
    throw new Error(
      'orchestrator requires an explicit journalStore with load() and save(): ' +
      'journal 是回退的唯一依据，不允许无操作降级（见 sync/STATUS.md C06）'
    )
  }

  let session = createSyncSession()

  function emit(patch = {}) {
    session = { ...session, ...patch, updatedAt: now() }
    onProgress({ session: { ...session }, ...patch })
    return session
  }

  function moveTo(state, patch = {}) {
    session = transition(session, state, { ...patch, updatedAt: now() })
    onProgress({ session: { ...session } })
    return session
  }

  async function run(options = {}) {
    const policy = options.policy
    const ignorePatterns = options.ignorePatterns ?? []
    let journal
    let cursor
    let plan

    try {
      moveTo(SYNC_STATES.SCANNING)
      journal = await journalStore.load()
      cursor = Number(options.cursor ?? journal?.cursor ?? 0) || 0
      emit({ cursor })

      const changes = await loadChanges({ cursor })
      // loadChanges 可以直接返回数组（旧契约），也可以返回 feed 形态 { changeSets: [...] }：
      // 两种形态都交给 plan 归一化，避免调用方为了接线再写一层适配。
      const changePayload = Array.isArray(changes) || changes === null || changes === undefined
        ? { changes: changes ?? [] }
        : changes
      emit({ summary: { total: Array.isArray(changes) ? changes.length : countFeedFiles(changePayload) } })
      moveTo(SYNC_STATES.PLANNING)

      const paths = collectPaths(changePayload)
      const { probe, failures } = await collectProbe(adapter, paths.filter(Boolean))

      plan = buildSyncPlan({ ...changePayload, cursor, probe, policy, ignorePatterns })
      emit({ summary: plan.summary, conflicts: plan.conflicts, cursor: plan.cursor })

      if (failures.length > 0) {
        emit({ summary: { ...plan.summary, probeFailures: failures.length } })
      }
      if (plan.blocked) return moveTo(SYNC_STATES.BLOCKED, { conflicts: plan.conflicts })
      if (plan.steps.length === 0) {
        return moveTo(SYNC_STATES.DONE, { cursor: plan.nextCursor, summary: plan.summary })
      }
    } catch (error) {
      // 扫描/加载/探针/规划阶段的异常都必须成为可观察的失败态，不能冒泡后卡在 scanning。
      return moveTo(SYNC_STATES.FAILED, {
        error: error instanceof Error ? error.message : String(error)
      })
    }

    moveTo(SYNC_STATES.APPLYING, { summary: plan.summary })
    let currentJournal = journal
    let applied = 0

    // 游标只能停在"完整落地"的 change-set 上：同一集合里只写成功一半就推进游标，
    // 重试时剩余文件会被 `<= cursor` 永久跳过（C03 的应验侧）。
    const stepsPerChangeSet = new Map()
    for (const step of plan.steps) {
      const id = Number(step.changeSetId) || 0
      stepsPerChangeSet.set(id, (stepsPerChangeSet.get(id) || 0) + 1)
    }
    const appliedPerChangeSet = new Map()
    const appliedCursor = () => {
      let last = cursor
      for (const id of plan.completedChangeSetIds) {
        const expected = stepsPerChangeSet.get(id) || 0
        const done = appliedPerChangeSet.get(id) || 0
        if (expected > 0 && done < expected) break
        last = Math.max(last, id)
      }
      return last
    }

    try {
      for (const step of plan.steps) {
        await applyStep(adapter, step)
        currentJournal = recordApplied(currentJournal, step, { appliedAt: now(), projectId: options.projectId ?? null })
        // 每步落盘：中途崩溃也能回退已应用的部分（journal 是回退的唯一依据）
        await journalStore.save(currentJournal)
        applied += 1
        const stepChangeSetId = Number(step.changeSetId) || 0
        appliedPerChangeSet.set(stepChangeSetId, (appliedPerChangeSet.get(stepChangeSetId) || 0) + 1)
        emit({ applied, cursor: appliedCursor() })
      }
    } catch (error) {
      return moveTo(SYNC_STATES.FAILED, {
        error: error instanceof Error ? error.message : String(error),
        applied,
        cursor: appliedCursor()
      })
    }

    return moveTo(SYNC_STATES.DONE, {
      summary: { ...plan.summary, applied },
      cursor: appliedCursor(),
      applied,
      conflicts: plan.conflicts
    })
  }

  function countFeedFiles(payload) {
    if (!Array.isArray(payload?.changeSets)) return 0
    return payload.changeSets.reduce((sum, set) => sum + (Array.isArray(set?.files) ? set.files.length : 0), 0)
  }

  function collectPaths(payload) {
    if (Array.isArray(payload?.changeSets)) {
      return payload.changeSets.flatMap(set => (Array.isArray(set?.files) ? set.files : [])
        .map(file => file?.relativePath ?? file?.relative_path ?? file?.path))
    }
    return (Array.isArray(payload?.changes) ? payload.changes : [])
      .map(item => item?.relativePath ?? item?.path)
  }

  async function rollback(options = {}) {
    const journal = await journalStore.load()
    const steps = buildRollbackSteps(journal, { limit: options.limit })
    if (steps.length === 0) return moveTo(SYNC_STATES.IDLE, { applied: 0 })

    moveTo(SYNC_STATES.ROLLING_BACK)
    try {
      for (const step of steps) await applyStep(adapter, step)
      const rolled = markRolledBack(journal, steps.length)
      await journalStore.save(rolled)
      return moveTo(SYNC_STATES.IDLE, { applied: 0, cursor: rolled.cursor })
    } catch (error) {
      return moveTo(SYNC_STATES.FAILED, {
        error: error instanceof Error ? error.message : String(error)
      })
    }
  }

  function getSession() {
    return { ...session }
  }

  return { run, rollback, getSession }
}

async function applyStep(adapter, step) {
  if (step.op === 'delete') {
    await adapter.deleteFile(step.path)
    return
  }
  await adapter.writeTextFile(step.path, step.content ?? '')
}
