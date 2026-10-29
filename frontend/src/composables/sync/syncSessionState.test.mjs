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

// C08：blocked/done 都必须能重新发起扫描；idle/failed 不能伪装成可回滚状态。
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
  assert.equal(canTransition(SYNC_STATES.FAILED, SYNC_STATES.ROLLING_BACK), false)
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
