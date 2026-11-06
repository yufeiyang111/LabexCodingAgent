import assert from 'node:assert/strict'
import test from 'node:test'

import { SYNC_STATES, canTransition, createSyncSession, describeSession, transition } from './syncSessionState.js'

test('starts idle with a zeroed summary', () => {
  const session = createSyncSession()
  assert.equal(session.state, SYNC_STATES.IDLE)
  assert.equal(session.cursor, 0)
  assert.equal(session.applied, 0)
})

test('accepts the documented happy path', () => {
  let session = createSyncSession()
  session = transition(session, SYNC_STATES.SCANNING)
  session = transition(session, SYNC_STATES.PLANNING)
  session = transition(session, SYNC_STATES.APPLYING)
  session = transition(session, SYNC_STATES.DONE)
  assert.equal(session.state, SYNC_STATES.DONE)
})

test('blocks illegal transitions instead of silently degrading', () => {
  assert.equal(canTransition(SYNC_STATES.IDLE, SYNC_STATES.APPLYING), false)
  assert.throws(
    () => transition(createSyncSession(), SYNC_STATES.APPLYING),
    /illegal sync state transition: idle -> applying/
  )
})

test('allows applying straight from blocked after the user resolves conflicts', () => {
  const blocked = transition(createSyncSession(), SYNC_STATES.SCANNING)
  const planning = transition(blocked, SYNC_STATES.PLANNING)
  const conflicted = transition(planning, SYNC_STATES.BLOCKED, { conflicts: [{ path: 'a' }] })
  assert.equal(canTransition(conflicted.state, SYNC_STATES.APPLYING), true)
})

test('treats self-transition as a no-op patch', () => {
  const session = transition(createSyncSession(), SYNC_STATES.SCANNING)
  const same = transition(session, SYNC_STATES.SCANNING, { cursor: 7 })
  assert.equal(same.state, SYNC_STATES.SCANNING)
  assert.equal(same.cursor, 7)
})

// C08：blocked/done 都必须能重新发起扫描；idle 不能伪装成可回滚状态。
test('allows retry scans from blocked and done, but forbids rollback from idle', () => {
  const blocked = transition(
    transition(transition(createSyncSession(), SYNC_STATES.SCANNING), SYNC_STATES.PLANNING),
    SYNC_STATES.BLOCKED
  )
  assert.equal(canTransition(blocked.state, SYNC_STATES.SCANNING), true)

  const done = transition(
    transition(transition(createSyncSession(), SYNC_STATES.SCANNING), SYNC_STATES.PLANNING),
    SYNC_STATES.DONE
  )
  assert.equal(canTransition(done.state, SYNC_STATES.SCANNING), true)
  assert.equal(canTransition(SYNC_STATES.IDLE, SYNC_STATES.ROLLING_BACK), false)
})

// 现场证据（2026-11-06 真实浏览器）：同步到第 7 个文件时点「中止」，
// 面板显示「同步失败」，再点「回退本次同步」得到
//   illegal sync state transition: failed -> rolling-back
// 已写入的 7 个文件因此无法撤销——回退依据（journal）明明存在，只是状态机拦住了。
//
// 关键区分：`failed` 且 applied === 0 时确实没有东西可回退；
// 但 `failed` 且 applied > 0 时，那批文件已经落盘，回退是**必须**可用的恢复路径。
// 是否展示回退入口由 UI 按 applied 判断（CloudWorkspace 已如此），状态机不该二次拦截。
test('a run that failed after writing files can still be rolled back', () => {
  assert.equal(canTransition(SYNC_STATES.FAILED, SYNC_STATES.ROLLING_BACK), true)

  let session = transition(createSyncSession(), SYNC_STATES.SCANNING)
  session = transition(session, SYNC_STATES.PLANNING)
  session = transition(session, SYNC_STATES.APPLYING)
  session = transition(session, SYNC_STATES.FAILED, { applied: 7, error: '同步已中止' })
  session = transition(session, SYNC_STATES.ROLLING_BACK)
  session = transition(session, SYNC_STATES.IDLE)

  assert.equal(session.state, SYNC_STATES.IDLE)
})

test('a failed run can still be retried by scanning again', () => {
  assert.equal(canTransition(SYNC_STATES.FAILED, SYNC_STATES.SCANNING), true)
})

test('describes busy and terminal states for the UI', () => {
  const busy = describeSession({ state: SYNC_STATES.APPLYING, summary: { toApply: 4 }, applied: 2 })
  assert.equal(busy.busy, true)
  assert.equal(busy.terminal, false)
  assert.equal(busy.total, 4)

  const done = describeSession({ state: SYNC_STATES.DONE, conflicts: [{}, {}] })
  assert.equal(done.terminal, true)
  assert.equal(done.conflictCount, 2)
})
