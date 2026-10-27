import assert from 'node:assert/strict'
import test from 'node:test'

import { CONFLICT_POLICIES, buildSyncPlan } from './changeSetSyncPlan.js'

function change(overrides = {}) {
  return {
    id: 'c1',
    changeSetId: 10,
    relativePath: 'src/app.js',
    changeType: 'modify',
    beforeContent: 'old',
    afterContent: 'new',
    ...overrides
  }
}

test('skips changes already covered by cursor', () => {
  const plan = buildSyncPlan({ changes: [change()], cursor: 10, probe: {} })
  assert.equal(plan.steps.length, 0)
  assert.equal(plan.skipped[0].reason, 'already-applied')
  assert.equal(plan.nextCursor, 10)
})

test('plans a write when local content matches the cloud base', () => {
  const plan = buildSyncPlan({
    changes: [change()],
    cursor: 0,
    probe: { 'src/app.js': { exists: true, content: 'old' } }
  })
  assert.equal(plan.blocked, false)
  assert.equal(plan.steps.length, 1)
  assert.deepEqual(plan.steps[0], {
    changeId: 'c1',
    changeSetId: 10,
    path: 'src/app.js',
    // changeType/status 是 2026-10-06 新增的字段：journal 回退与 UI 需要知道"这一笔是什么、
    // 当前处于什么状态"，而不是只看 op。
    changeType: 'modify',
    status: 'pending',
    op: 'write',
    content: 'new',
    previousContent: 'old',
    previousExisted: true,
    overwritten: false
  })
  assert.equal(plan.nextCursor, 10)
})

test('reports content drift as conflict and blocks in strict policy', () => {
  const plan = buildSyncPlan({
    changes: [change()],
    cursor: 0,
    policy: CONFLICT_POLICIES.STRICT,
    probe: { 'src/app.js': { exists: true, content: 'locally edited' } }
  })
  assert.equal(plan.blocked, true)
  assert.equal(plan.steps.length, 0)
  assert.equal(plan.conflicts[0].reason, 'content-drift')
})

test('skip policy converts conflicts into skips without blocking', () => {
  const plan = buildSyncPlan({
    changes: [change()],
    cursor: 0,
    policy: CONFLICT_POLICIES.SKIP,
    probe: { 'src/app.js': { exists: true, content: 'locally edited' } }
  })
  assert.equal(plan.blocked, false)
  assert.equal(plan.steps.length, 0)
  assert.equal(plan.conflicts.length, 1)
  assert.equal(plan.skipped.some(item => item.reason === 'conflict:content-drift'), true)
})

test('overwrite policy plans a write and records previous content', () => {
  const plan = buildSyncPlan({
    changes: [change()],
    cursor: 0,
    policy: CONFLICT_POLICIES.OVERWRITE,
    probe: { 'src/app.js': { exists: true, content: 'locally edited' } }
  })
  assert.equal(plan.blocked, false)
  assert.equal(plan.steps.length, 1)
  assert.equal(plan.steps[0].overwritten, true)
  assert.equal(plan.steps[0].previousContent, 'locally edited')
})

test('new file overlapping an existing local path is a conflict', () => {
  const plan = buildSyncPlan({
    changes: [change({ changeType: 'add', beforeContent: null, relativePath: 'src/new.js' })],
    cursor: 0,
    probe: { 'src/new.js': { exists: true, content: 'occupied' } }
  })
  assert.equal(plan.conflicts[0].reason, 'local-occupied')
})

test('delete of an absent file is idempotently skipped', () => {
  const plan = buildSyncPlan({
    changes: [change({ changeType: 'delete' })],
    cursor: 0,
    probe: { 'src/app.js': { exists: false, content: null } }
  })
  assert.equal(plan.steps.length, 0)
  assert.equal(plan.skipped[0].reason, 'already-absent')
  assert.equal(plan.nextCursor, 10)
})

test('delete of a present file with matching base becomes a delete step', () => {
  const plan = buildSyncPlan({
    changes: [change({ changeType: 'delete' })],
    cursor: 0,
    probe: { 'src/app.js': { exists: true, content: 'old' } }
  })
  assert.equal(plan.steps[0].op, 'delete')
  assert.equal(plan.steps[0].content, null)
})

test('excluded paths are skipped but still advance the cursor', () => {
  const plan = buildSyncPlan({
    changes: [change({ relativePath: 'node_modules/pkg/index.js' })],
    cursor: 0,
    probe: {}
  })
  assert.equal(plan.steps.length, 0)
  assert.equal(plan.skipped[0].reason, 'excluded')
  assert.equal(plan.nextCursor, 10)
})

test('summarizes the whole batch', () => {
  const plan = buildSyncPlan({
    changes: [
      change({ id: 'c1', changeSetId: 1 }),
      change({ id: 'c2', changeSetId: 2, relativePath: 'src/b.js', changeType: 'add', beforeContent: null }),
      change({ id: 'c3', changeSetId: 3, relativePath: 'dist/out.js' })
    ],
    cursor: 0,
    probe: {
      'src/app.js': { exists: true, content: 'old' },
      'src/b.js': { exists: false, content: null }
    }
  })
  assert.deepEqual(plan.summary, { total: 3, toApply: 2, conflicts: 0, skipped: 1 })
  assert.equal(plan.nextCursor, 3)
})

// ---------------------------------------------------------------------------
// 服务端 feed 契约（B1）：这些用例描述"真实响应形态"，原实现全部不通过。
// 契约权威说明：backend/src/main/java/com/labex/labexagent/sync/README.md
// ---------------------------------------------------------------------------

function feedFile(overrides = {}) {
  return {
    changeId: 'f1',
    changeSetId: 10,
    relativePath: 'src/app.js',
    changeType: 'modify',
    status: 'pending',
    beforeExisted: true,
    afterExisted: true,
    beforeTruncated: false,
    afterTruncated: false,
    beforeContent: 'old',
    afterContent: 'new',
    updateTime: '2026-10-06T10:00:00',
    ...overrides
  }
}

function feedChangeSet(changeSetId, files) {
  return { changeSetId, taskId: 1, status: 'pending', updateTime: '2026-10-06T10:00:00', files }
}

// C01：后端真实新增类型是 create（不是 add），且判定存在性靠 beforeExisted，不靠"空字符串"。
test('feed: 新增文件（changeType=create, beforeExisted=false）必须产出写步骤而不是冲突', () => {
  const plan = buildSyncPlan({
    changeSets: [feedChangeSet(10, [
      feedFile({ changeId: 'new-1', changeType: 'create', beforeExisted: false, beforeContent: '', afterContent: 'hello' })
    ])],
    cursor: 0,
    probe: { 'src/app.js': { exists: false, content: null } }
  })
  assert.equal(plan.conflicts.length, 0)
  assert.equal(plan.steps.length, 1)
  assert.equal(plan.steps[0].op, 'write')
  assert.equal(plan.steps[0].content, 'hello')
  assert.equal(plan.steps[0].previousExisted, false)
})

// C01：status 必须被保留并参与判定——未批准/被撤回的变更不能写盘。
test('feed: status=rejected / undone 的文件不得产生写步骤', () => {
  const plan = buildSyncPlan({
    changeSets: [feedChangeSet(10, [
      feedFile({ changeId: 'rej', changeType: 'modify', status: 'rejected' }),
      feedFile({ changeId: 'und', changeType: 'modify', status: 'undone', relativePath: 'src/other.js' })
    ])],
    cursor: 0,
    probe: {
      'src/app.js': { exists: true, content: 'old' },
      'src/other.js': { exists: true, content: 'old' }
    }
  })
  assert.equal(plan.steps.length, 0)
  assert.deepEqual(plan.skipped.map(item => item.reason).sort(), ['status:rejected', 'status:undone'])
})

// C04：同一集合内同一路径的多次修改必须按序串联，用"前一步结果"当后一步的基。
test('feed: 同一路径连续修改必须串成两步且 previousContent 逐级推进', () => {
  const plan = buildSyncPlan({
    changeSets: [feedChangeSet(10, [
      feedFile({ changeId: 's1', beforeContent: 'old', afterContent: 'middle' }),
      feedFile({ changeId: 's2', beforeContent: 'middle', afterContent: 'new' })
    ])],
    cursor: 0,
    probe: { 'src/app.js': { exists: true, content: 'old' } }
  })
  assert.equal(plan.conflicts.length, 0, '链式修改不应被误判冲突')
  assert.deepEqual(plan.steps.map(step => step.content), ['middle', 'new'])
  assert.deepEqual(plan.steps.map(step => step.previousContent), ['old', 'middle'])
})

// C03：游标只能推进到"完整处理完的 change-set"，冲突未解决时不得越过它。
test('feed: 游标不得越过未完整处理的 change-set', () => {
  const plan = buildSyncPlan({
    changeSets: [
      feedChangeSet(5, [feedFile({ changeId: 'ok', changeSetId: 5 })]),
      feedChangeSet(6, [feedFile({ changeId: 'conflict', changeSetId: 6, relativePath: 'src/drift.js' })])
    ],
    cursor: 0,
    probe: {
      'src/app.js': { exists: true, content: 'old' },
      'src/drift.js': { exists: true, content: 'locally edited' }
    }
  })
  assert.equal(plan.blocked, true)
  assert.equal(plan.nextCursor, 5, '遇到未解决的冲突时游标必须停在最后一个完整集合')
  assert.deepEqual(plan.completedChangeSetIds, [5])
})

// C03：集合只被"部分应用"（有文件被跳过/冲突）时不能算完成。
test('feed: 集合内任一文件未产出步骤即视为未完成', () => {
  const plan = buildSyncPlan({
    changeSets: [feedChangeSet(7, [
      feedFile({ changeId: 'a', changeSetId: 7 }),
      feedFile({ changeId: 'b', changeSetId: 7, relativePath: 'src/drift.js' })
    ])],
    cursor: 0,
    probe: {
      'src/app.js': { exists: true, content: 'old' },
      'src/drift.js': { exists: true, content: 'locally edited' }
    }
  })
  assert.deepEqual(plan.completedChangeSetIds, [])
  assert.equal(plan.nextCursor, 0)
  assert.equal(plan.changeSetProgress[0].complete, false)
})
