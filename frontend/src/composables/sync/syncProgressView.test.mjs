import assert from 'node:assert/strict'
import test from 'node:test'

import { buildSyncProgressView } from './syncProgressView.js'

// 用户反馈（2026-11-05）：同步过程只有"待写入 N"一个静态数字，
// 看不到进度、当前在写哪个文件、还要多久。这里把会话状态投影成进度视图。

function session(overrides = {}) {
  return {
    state: 'applying',
    applied: 0,
    summary: null,
    steps: [],
    error: null,
    ...overrides
  }
}

function steps(count) {
  return Array.from({ length: count }, (_, index) => ({
    path: `src/file-${index}.js`,
    op: 'write'
  }))
}

test('reports a determinate percentage while applying', () => {
  const view = buildSyncProgressView(session({
    applied: 5,
    steps: steps(20),
    summary: { total: 20, toApply: 20, conflicts: 0, skipped: 0 }
  }))

  assert.equal(view.total, 20)
  assert.equal(view.done, 5)
  assert.equal(view.percent, 25)
  assert.equal(view.indeterminate, false)
})

test('is indeterminate before the plan is known', () => {
  const view = buildSyncProgressView(session({ state: 'scanning', steps: [] }))

  assert.equal(view.indeterminate, true)
  assert.equal(view.percent, 0)
})

test('shows the file currently being written', () => {
  const view = buildSyncProgressView(session({
    applied: 3,
    steps: steps(10),
    summary: { total: 10, toApply: 10, conflicts: 0, skipped: 0 }
  }))

  // applied=3 表示前 3 个已完成，正在写第 4 个（索引 3）
  assert.equal(view.currentPath, 'src/file-3.js')
})

test('reaches 100 percent when done and clears the current file', () => {
  const view = buildSyncProgressView(session({
    state: 'done',
    applied: 10,
    steps: steps(10),
    summary: { total: 10, toApply: 10, conflicts: 0, skipped: 0 }
  }))

  assert.equal(view.percent, 100)
  assert.equal(view.currentPath, null)
  assert.equal(view.indeterminate, false)
})

test('never exceeds 100 percent when applied overshoots the plan', () => {
  const view = buildSyncProgressView(session({
    applied: 15,
    steps: steps(10),
    summary: { total: 10, toApply: 10, conflicts: 0, skipped: 0 }
  }))

  assert.equal(view.percent, 100)
})

test('reports conflicts and skipped counts alongside progress', () => {
  const view = buildSyncProgressView(session({
    state: 'blocked',
    applied: 0,
    steps: steps(4),
    summary: { total: 4, toApply: 4, conflicts: 2, skipped: 1 }
  }))

  assert.equal(view.conflicts, 2)
  assert.equal(view.skipped, 1)
})

test('an empty plan is 100 percent rather than an empty bar', () => {
  const view = buildSyncProgressView(session({
    state: 'done',
    applied: 0,
    steps: [],
    summary: { total: 0, toApply: 0, conflicts: 0, skipped: 0 }
  }))

  assert.equal(view.total, 0)
  assert.equal(view.percent, 100)
  assert.equal(view.indeterminate, false)
})

test('a failed run keeps the progress it reached', () => {
  const view = buildSyncProgressView(session({
    state: 'failed',
    applied: 7,
    steps: steps(20),
    error: 'disk full',
    summary: { total: 20, toApply: 20, conflicts: 0, skipped: 0 }
  }))

  assert.equal(view.done, 7)
  assert.equal(view.percent, 35)
  assert.equal(view.error, 'disk full')
})

test('estimates remaining time from elapsed time and completed count', () => {
  const view = buildSyncProgressView(session({
    applied: 5,
    steps: steps(20),
    summary: { total: 20, toApply: 20, conflicts: 0, skipped: 0 }
  }), { startedAt: 1000, now: 6000 })

  // 5 个用了 5 秒 → 每个 1 秒 → 剩 15 个 ≈ 15 秒
  assert.equal(view.elapsedMs, 5000)
  assert.equal(view.remainingMs, 15000)
})

test('remaining time is null before any file completes', () => {
  const view = buildSyncProgressView(session({
    applied: 0,
    steps: steps(20),
    summary: { total: 20, toApply: 20, conflicts: 0, skipped: 0 }
  }), { startedAt: 1000, now: 6000 })

  assert.equal(view.remainingMs, null)
})

test('handles a missing summary without throwing', () => {
  const view = buildSyncProgressView(session({ applied: 2, steps: steps(5) }))

  assert.equal(view.total, 5)
  assert.equal(view.done, 2)
})

// 同一路径被改多次时，plan.steps 是 45 条、实际写盘是 22 条（合并后）。
// 进度条的"当前文件"必须按实际写盘顺序取，否则会显示一个根本不在写队列里的路径，
// 而且随着 applied 前进会越错越离谱（45 vs 22 的索引直接错位）。
test('the current file follows the coalesced write order, not the full change chain', () => {
  const writeSteps = [
    { path: 'src/a.js', op: 'write' },
    { path: 'src/b.js', op: 'write' }
  ]
  const view = buildSyncProgressView(session({
    applied: 1,
    steps: writeSteps,
    summary: { total: 45, toApply: 2, conflicts: 0, skipped: 0 }
  }))

  assert.equal(view.total, 2)
  assert.equal(view.currentPath, 'src/b.js')
})
