// 唯一职责：编排一次「拉取变更 → 探针 → 计划 → 应用 → 记 journal」的完整同步会话。
// 边界：依赖全部以参数注入（变更加载器、目录适配器、journal 存取），本模块不直接发请求、不直接碰 DOM。
// 设计模式：State（复用 syncSessionState）+ Observer（onProgress 回调）+ 依赖注入（便于测试与替换后端）。

import { buildSyncPlan } from './changeSetSyncPlan.js'
import { markRolledBack, recordApplied, buildRollbackSteps, updateJournalCursor } from './syncJournal.js'
import { createJournalCommitter } from './syncJournalCommitter.js'
import { appliedStateOf, applyStep } from './syncStepApplier.js'
import { collectProbe } from './syncProbe.js'
import { SYNC_STATES, createSyncSession, transition } from './syncSessionState.js'
import { SYNC_LIMITS, positiveLimit } from './syncLimits.js'
import {
  assertJournalWithinLimits,
  feedCursorAfterSuccess,
  journalPaths,
  preflightRollback,
  recordAppliedBatchPreview
} from './syncOrchestratorSupport.js'

function noop() {}

export function createChangeSetSyncOrchestrator(dependencies = {}) {
  const loadChanges = dependencies.loadChanges
  const adapter = dependencies.adapter
  const journalStore = dependencies.journalStore
  const onProgress = typeof dependencies.onProgress === 'function' ? dependencies.onProgress : noop
  const limits = dependencies.limits ?? {}
  const now = typeof dependencies.now === 'function' ? dependencies.now : () => Date.now()
  // 每写多少个文件提交一次 journal。取值依据：
  //   - 太密（1）= 每步一次 IndexedDB 事务，45 个文件曾耗时约 5 分钟；
  //   - 太疏（=整批）= 刷新页面会丢掉全部进度与回退依据。
  // 8 是折中：40 个文件只多 5 次事务，而任何时刻中断最多丢 7 个文件的记录。
  const commitInterval = positiveLimit(dependencies.journalCommitInterval, SYNC_LIMITS.JOURNAL_COMMIT_INTERVAL)

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

  // C09：run/rollback 共用同一把单飞锁，防止双击或交叉调用重复写盘。
  let operationInFlight = false
  // 用户主动中止：每次 run 开始时重置，cancel() 只影响当前/下一次 run。
  let cancelRequested = false
  let runActive = false

  function throwIfCancelled() {
    if (cancelRequested) {
      const error = new Error('同步已中止')
      error.name = 'SyncCancelledError'
      throw error
    }
  }

  function beginOperation() {
    if (operationInFlight) throw new Error('sync operation already in progress')
    operationInFlight = true
  }

  function endOperation() {
    operationInFlight = false
  }

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

  async function runLocked(options = {}) {
    const policy = options.policy
    const ignorePatterns = options.ignorePatterns ?? []
    let journal
    let cursor
    let plan
    let changePayload
    let feedCursor

    try {
      // 开跑前先检查：上一次 run 之前就按了中止，不该再写任何文件。
      throwIfCancelled()
      moveTo(SYNC_STATES.SCANNING)
      journal = await journalStore.load()
      cursor = Number(options.cursor ?? journal?.changeSetCursor?.afterChangeSetId ?? journal?.cursor ?? 0) || 0
      emit({ cursor })
      feedCursor = {
        afterChangeSetId: cursor,
        tailUpdateTime: journal?.changeSetCursor?.tailUpdateTime ?? options.tailUpdateTime ?? null,
        tailChangeId: journal?.changeSetCursor?.tailChangeId ?? options.tailChangeId ?? ''
      }

      const changes = await loadChanges({ cursor: feedCursor })
      // loadChanges 可以直接返回数组（旧契约），也可以返回 feed 形态 { changeSets: [...] }：
      // 两种形态都交给 plan 归一化，避免调用方为了接线再写一层适配。
      changePayload = Array.isArray(changes) || changes === null || changes === undefined
        ? { changes: changes ?? [] }
        : changes
      emit({ summary: { total: Array.isArray(changes) ? changes.length : countFeedFiles(changePayload) } })
      moveTo(SYNC_STATES.PLANNING)

      const paths = collectPaths(changePayload)
      const { probe, failures } = await collectProbe(adapter, paths.filter(Boolean), {
        maxFileBytes: limits.maxFileBytes,
        maxFiles: limits.maxFiles,
        maxBatchBytes: limits.maxBatchBytes
      })

      plan = buildSyncPlan({
        ...changePayload,
        cursor,
        probe,
        policy,
        ignorePatterns,
        // journal 是"这个目录收到过哪些路径"的唯一记录：只有它知道某个缺失的本地文件
        // 是用户删的，还是这个目录从来没同步过（后者不该拦，否则首次同步永远走不通）。
        deliveredPaths: journalPaths(journal)
      })
      // session.steps 必须是**实际写盘队列**（合并后的 writeSteps）：
      // 进度条按 applied 索引取"当前文件"，用完整变更链会错位——
      // 同一路径改 3 次时链长 45、写盘只有 22，第 23 步之后索引直接越界。
      // 完整链保留在 changeSteps，供审计与"为什么只写了 22 个"的解释。
      emit({
        summary: plan.summary,
        conflicts: plan.conflicts,
        cursor: plan.cursor,
        steps: plan.writeSteps,
        changeSteps: plan.steps,
        skipped: plan.skipped
      })

      if (failures.length > 0) {
        emit({ summary: { ...plan.summary, probeFailures: failures.length } })
      }
      if (plan.blocked) {
        return moveTo(SYNC_STATES.BLOCKED, { conflicts: plan.conflicts, steps: plan.writeSteps, changeSteps: plan.steps, skipped: plan.skipped })
      }
      if (plan.steps.length === 0) {
        const finalCursor = feedCursorAfterSuccess(changePayload, feedCursor, plan)
        journal = updateJournalCursor(journal, finalCursor)
        await journalStore.save(journal)
        return moveTo(SYNC_STATES.DONE, {
          cursor: finalCursor.afterChangeSetId,
          summary: plan.summary,
          steps: plan.writeSteps,
          changeSteps: plan.steps,
          skipped: plan.skipped
        })
      }
    } catch (error) {
      return moveTo(SYNC_STATES.FAILED, {
        error: error instanceof Error ? error.message : String(error)
      })
    }

    moveTo(SYNC_STATES.APPLYING, { summary: plan.summary })
    let currentJournal = journal
    let applied = 0

    // 写入用合并后的 writeSteps：同一路径只写最终版本一次。
    // 游标完整性仍按原始 steps 判定（一个 change-set 的所有变更都算落地）。
    const writeSteps = Array.isArray(plan.writeSteps) ? plan.writeSteps : plan.steps
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

    let journalCommitted = false
    const committer = createJournalCommitter({
      journalStore,
      interval: commitInterval,
      assertWithinLimits: journal => assertJournalWithinLimits(journal, limits)
    })

    try {
      assertJournalWithinLimits(
        recordAppliedBatchPreview(currentJournal, writeSteps, {
          appliedAt: now(),
          projectId: options.projectId ?? null
        }),
        limits
      )
      for (const step of writeSteps) {
        // 每写一个文件前检查中止：中止后不再写盘，已写的部分由下面的 catch 补存 journal。
        throwIfCancelled()
        await applyStep(adapter, step)
        const appliedState = appliedStateOf(step)
        currentJournal = recordApplied(currentJournal, step, {
          appliedAt: now(),
          projectId: options.projectId ?? null,
          appliedContent: appliedState.appliedContent,
          appliedExisted: appliedState.appliedExisted
        })
        applied += 1
        // 一步合并步骤可能覆盖同一 change-set 的多条原始变更，因此按覆盖数累加。
        const stepChangeSetId = Number(step.changeSetId) || 0
        appliedPerChangeSet.set(
          stepChangeSetId,
          (appliedPerChangeSet.get(stepChangeSetId) || 0) + (step.coalescedCount || 1)
        )
        currentJournal = updateJournalCursor(currentJournal, {
          afterChangeSetId: appliedCursor(),
          tailUpdateTime: feedCursor.tailUpdateTime,
          tailChangeId: feedCursor.tailChangeId
        })
        emit({ applied, cursor: appliedCursor() })

        if (committer.shouldCommit(applied)) {
          await committer.commit(currentJournal, applied)
        }
      }

      // 收尾提交：最后不足一个间隔的那批也要落盘，否则尾部文件的回退依据会丢。
      // 这里直接用**最终游标**（含 feed 的 nextCursor），避免之后再补一次保存。
      const finalCursor = feedCursorAfterSuccess(changePayload, feedCursor, plan)
      currentJournal = updateJournalCursor(currentJournal, finalCursor)
      await committer.commitFinal(currentJournal, applied)
      journalCommitted = true
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      // 失败路径分两种，语义必须区分开：
      //   a) 写文件失败（journal 尚未提交）→ 已写盘的部分必须有回退依据，补存一次；
      //   b) journal 本身存不上 → 恢复记录不存在，不能声称任何一条"已应用"，
      //      否则 UI 会给出一个无法回退的成功假象。
      if (!journalCommitted && applied > 0) {
        try {
          await committer.commit(currentJournal, applied)
          journalCommitted = true
        } catch (saveFailure) {
          const saveMessage = saveFailure instanceof Error ? saveFailure.message : String(saveFailure)
          return moveTo(SYNC_STATES.FAILED, {
            error: `${message}; journal save also failed: ${saveMessage}`,
            applied: 0,
            cursor: appliedCursor()
          })
        }
      }
      return moveTo(SYNC_STATES.FAILED, {
        error: message,
        applied,
        cursor: appliedCursor()
      })
    }

    const finalCursor = feedCursorAfterSuccess(changePayload, feedCursor, plan)

    return moveTo(SYNC_STATES.DONE, {
      summary: { ...plan.summary, applied },
      cursor: finalCursor.afterChangeSetId,
      applied,
      conflicts: plan.conflicts,
      steps: plan.writeSteps,
      changeSteps: plan.steps,
      skipped: plan.skipped
    })
  }

  function countFeedFiles(payload) {    if (!Array.isArray(payload?.changeSets)) return 0
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


  async function rollbackLocked(options = {}) {
    let journal
    let steps
    try {
      journal = await journalStore.load()
      steps = buildRollbackSteps(journal, { limit: options.limit })
      if (steps.length === 0) return moveTo(SYNC_STATES.IDLE, { applied: 0 })
      await preflightRollback(adapter, journal, steps)
    } catch (error) {
      return moveTo(SYNC_STATES.FAILED, {
        error: error instanceof Error ? error.message : String(error)
      })
    }

    try {
      moveTo(SYNC_STATES.ROLLING_BACK)
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

  async function run(options = {}) {
    beginOperation()
    // 只有"运行中取消"才需要重置；用户在没有 run 时按下的中止必须保留，
    // 否则下一次 run 会悄悄跑起来，与用户意图相反。
    if (runActive) cancelRequested = false
    runActive = true
    try {
      return await runLocked(options)
    } finally {
      runActive = false
      endOperation()
    }
  }

  /**
   * 请求中止当前同步。
   * - 在写入循环的下一个检查点生效，已写盘的文件仍会被记入 journal；
   * - 若当前没有 run 在执行，标记会保留到下一次 run 开始时立即失败
   *   （用户按了中止就该停下，而不是下次悄悄跑起来）。
   * 重复调用无副作用。
   */
  function cancel() {
    cancelRequested = true
  }

  async function rollback(options = {}) {
    beginOperation()
    try {
      return await rollbackLocked(options)
    } finally {
      endOperation()
    }
  }

  function getSession() {
    return { ...session }
  }

  return { run, rollback, cancel, getSession, get isRunning() { return runActive } }
}