import assert from 'node:assert/strict'
import test from 'node:test'

import { CONFLICT_POLICIES, buildSyncPlan } from './changeSetSyncPlan.js'

// 项目在接入 Agent 之前就存在的文件，服务端的第一条记录是 `modify`（不是 `create`）。
// 把这种项目同步到一个空目录时，本地探针给的是 absent，旧逻辑判 `missing-local`，
// strict 策略整批阻断——于是"同步一个真实项目到新机器"这件事永远做不成。
// 实测：项目 2 有 1 个这样的路径、277 有 13 个、287 有 4 个。
//
// 判据：`missing-local` 的语义是"云端说这个文件有基线，你本地却不见了"，
// 这只在**我们确实交付过这个路径**时才成立（用户可能删了它）。
// 从未交付过的路径，absent 只说明"还没同步过"，写下去不会覆盖任何本地内容。

function modifyPayload() {
  return {
    changeSets: [
      {
        changeSetId: 20,
        files: [
          {
            changeId: 'm-1',
            relativePath: 'README.md',
            changeType: 'modify',
            status: 'applied',
            beforeContent: '# old',
            afterContent: '# new'
          }
        ]
      }
    ]
  }
}

const absentProbe = { 'README.md': { state: 'absent', exists: false, content: null } }

test('a never-delivered path is written on first sync instead of blocking the batch', () => {
  const plan = buildSyncPlan({
    ...modifyPayload(),
    cursor: 0,
    probe: absentProbe,
    // 空 journal：这个目录从未收到过这个项目的任何东西。
    deliveredPaths: []
  })

  assert.equal(plan.blocked, false, 'first sync must not be blocked by a modify row')
  assert.equal(plan.steps.length, 1)
  assert.equal(plan.steps[0].path, 'README.md')
  assert.equal(plan.steps[0].content, '# new')
})

test('a path we delivered before is still a conflict when the user deleted it', () => {
  // 这条保护必须留住：同步过 → 用户删掉 → 云端又改 → 不能悄悄复活。
  const plan = buildSyncPlan({
    ...modifyPayload(),
    cursor: 0,
    probe: absentProbe,
    deliveredPaths: ['README.md']
  })

  assert.equal(plan.conflicts.length, 1)
  assert.equal(plan.conflicts[0].reason, 'missing-local')
})

test('deliveredPaths is per-path, not a global switch', () => {
  const payload = modifyPayload()
  payload.changeSets[0].files.push({
    changeId: 'm-2',
    relativePath: 'docs/guide.md',
    changeType: 'modify',
    status: 'applied',
    beforeContent: 'a',
    afterContent: 'b'
  })

  const plan = buildSyncPlan({
    ...payload,
    cursor: 0,
    probe: {
      'README.md': { state: 'absent', exists: false, content: null },
      'docs/guide.md': { state: 'absent', exists: false, content: null }
    },
    deliveredPaths: ['README.md']
  })

  assert.deepEqual(plan.steps.map(step => step.path), ['docs/guide.md'])
  assert.deepEqual(plan.conflicts.map(item => item.path), ['README.md'])
})

test('a local file with unexpected content is still a drift conflict, never overwritten silently', () => {
  const plan = buildSyncPlan({
    ...modifyPayload(),
    cursor: 0,
    probe: { 'README.md': { state: 'present', exists: true, content: '# something else' } },
    deliveredPaths: []
  })

  assert.equal(plan.steps.length, 0)
  assert.equal(plan.conflicts[0].reason, 'content-drift')
})

test('overwrite policy still overwrites a drifted local file', () => {
  const plan = buildSyncPlan({
    ...modifyPayload(),
    cursor: 0,
    policy: CONFLICT_POLICIES.OVERWRITE,
    probe: { 'README.md': { state: 'present', exists: true, content: '# something else' } },
    deliveredPaths: []
  })

  assert.equal(plan.steps.length, 1)
  assert.equal(plan.steps[0].overwritten, true)
})
