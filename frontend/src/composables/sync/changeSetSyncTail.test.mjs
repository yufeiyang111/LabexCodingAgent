import assert from 'node:assert/strict'
import test from 'node:test'

import { buildSyncPlan } from './changeSetSyncPlan.js'
import { createProbeMap } from './syncProbe.js'

// 尾巴行（changedFiles）：change_set_id <= afterChangeSetId，但状态/内容在游标之后变了。
// 这类变化**不会**产生新 change-set，只靠 changeSets 会永久漏掉（见 sync/README.md §3）。
// 计划层必须把它们纳入计划，同时**不得**推进游标——游标只由完整集合决定。

function probeOf(entries) {
  const probe = createProbeMap()
  for (const [path, value] of Object.entries(entries)) {
    probe.set(path, value.exists === false
      ? { state: 'absent', exists: false, content: null }
      : { state: 'present', exists: true, content: value.content })
  }
  return probe
}

function tailFile(overrides = {}) {
  return {
    changeId: 'tail-1',
    changeSetId: 5,
    relativePath: 'src/app.js',
    changeType: 'modify',
    status: 'applied',
    beforeExisted: true,
    afterExisted: true,
    beforeTruncated: false,
    afterTruncated: false,
    beforeContent: 'old',
    afterContent: 'newer',
    updateTime: '2026-10-06T10:05:00',
    ...overrides
  }
}

test('a tail row for an already-synced change-set is planned instead of being skipped by the cursor', () => {
  const plan = buildSyncPlan({
    changeSets: [],
    changedFiles: [tailFile()],
    cursor: 5,
    probe: probeOf({ 'src/app.js': { content: 'old' } })
  })

  assert.equal(plan.steps.length, 1)
  assert.equal(plan.steps[0].path, 'src/app.js')
  assert.equal(plan.steps[0].content, 'newer')
  // 尾巴行不推进游标：游标只由"从前往后连续完整"的集合决定。
  assert.equal(plan.nextCursor, 5)
  assert.deepEqual(plan.completedChangeSetIds, [])
})

test('a tail row that was undone locally-consistently is accounted, never written', () => {
  const plan = buildSyncPlan({
    changeSets: [],
    changedFiles: [tailFile({ status: 'undone' })],
    cursor: 5,
    probe: probeOf({ 'src/app.js': { content: 'old' } })
  })

  assert.equal(plan.steps.length, 0)
  assert.equal(plan.skipped.length, 1)
  assert.equal(plan.skipped[0].reason, 'status:undone')
})

test('a tail row whose content already matches the local file is skipped as already-applied', () => {
  const plan = buildSyncPlan({
    changeSets: [],
    changedFiles: [tailFile()],
    cursor: 5,
    probe: probeOf({ 'src/app.js': { content: 'newer' } })
  })

  assert.equal(plan.steps.length, 0)
  assert.equal(plan.skipped[0].reason, 'already-applied')
})

test('a tail row that drifts from the local baseline conflicts under strict policy', () => {
  const plan = buildSyncPlan({
    changeSets: [],
    changedFiles: [tailFile()],
    cursor: 5,
    probe: probeOf({ 'src/app.js': { content: 'user edited' } })
  })

  assert.equal(plan.steps.length, 0)
  assert.equal(plan.conflicts.length, 1)
  assert.equal(plan.conflicts[0].reason, 'content-drift')
  assert.equal(plan.blocked, true)
})

test('an unknown probe on a tail row blocks regardless of policy', () => {
  const probe = createProbeMap()
  probe.set('src/app.js', { state: 'unknown', exists: null, content: null, error: 'io' })
  const plan = buildSyncPlan({
    changeSets: [],
    changedFiles: [tailFile()],
    cursor: 5,
    policy: 'overwrite',
    probe
  })

  assert.equal(plan.steps.length, 0)
  assert.equal(plan.blocked, true)
})

test('tail rows are excluded by the same exclusion policy as change-set files', () => {
  const plan = buildSyncPlan({
    changeSets: [],
    changedFiles: [tailFile({ relativePath: 'node_modules/pkg/index.js' })],
    cursor: 5,
    probe: probeOf({ 'node_modules/pkg/index.js': { content: 'x' } })
  })

  assert.equal(plan.steps.length, 0)
  assert.equal(plan.skipped[0].reason, 'excluded')
})

test('two tail rows on the same path chain through the shared local view instead of both using the stale baseline', () => {
  // 现实来源：同一秒内对同一路径的多次修改（README §7 已知残留），
  // 两条尾巴行都会以"同步开始前"的探针为基准，只有串联才能得到正确终态。
  const plan = buildSyncPlan({
    changeSets: [],
    changedFiles: [
      tailFile({ changeId: 'tail-1', beforeContent: 'old', afterContent: 'v2' }),
      tailFile({ changeId: 'tail-2', beforeContent: 'v2', afterContent: 'v3' })
    ],
    cursor: 5,
    probe: probeOf({ 'src/app.js': { content: 'old' } })
  })

  assert.deepEqual(plan.steps.map(step => step.content), ['v2', 'v3'])
  assert.equal(plan.nextCursor, 5)
})

test('a tail row that targets a path the pending change-set already rewrote conflicts rather than overwriting it', () => {
  // 集合 6 已把 app.js 写成 v2，而尾巴行仍以 old 为基线 —— 说明两者不是同一条变更链，
  // 盲目写入会把集合 6 的结果盖掉。这种情况必须停下来让人处理。
  const plan = buildSyncPlan({
    changeSets: [{
      changeSetId: 6,
      files: [{
        changeId: 'c6', changeSetId: 6, relativePath: 'src/app.js', changeType: 'modify',
        status: 'pending', beforeExisted: true, beforeContent: 'old', afterContent: 'v2',
        beforeTruncated: false, afterTruncated: false
      }]
    }],
    changedFiles: [tailFile({ beforeContent: 'old', afterContent: 'v3' })],
    cursor: 5,
    probe: probeOf({ 'src/app.js': { content: 'old' } })
  })

  assert.deepEqual(plan.steps.map(step => step.content), ['v2'])
  assert.equal(plan.conflicts.length, 1)
  assert.equal(plan.conflicts[0].reason, 'content-drift')
  // 集合 6 自身完整，游标仍应推进到 6。
  assert.equal(plan.nextCursor, 6)
})

test('tail rows are deduplicated against identical rows delivered twice by the overlap window', () => {
  const row = tailFile()
  const plan = buildSyncPlan({
    changeSets: [],
    changedFiles: [row, { ...row }],
    cursor: 5,
    probe: probeOf({ 'src/app.js': { content: 'old' } })
  })

  assert.equal(plan.steps.length, 1)
})
