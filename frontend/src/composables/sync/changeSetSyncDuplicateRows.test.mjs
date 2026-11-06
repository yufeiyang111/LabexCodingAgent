import assert from 'node:assert/strict'
import test from 'node:test'

import { buildSyncPlan } from './changeSetSyncPlan.js'
import { createProbeMap } from './syncProbe.js'

// 现场证据（2026-11-05 真实截图）：审查页里同一文件出现 2–4 次
// （AGENTS.md ×2、server.test.ts ×3、upstream.test.ts ×4），同步耗时约 5 分钟。
//
// 根因不是"写得太慢"，而是**计划层把同一路径的重复变更当成了冲突或重复步骤**：
// 服务端对同一文件连续产生的 change 行，changeType 可能都是 create，
// 而本地视图在第一步之后已认为文件存在 → 第二步被判 local-occupied。
// 一旦 strict 策略命中冲突，整批同步直接 blocked（零写入），用户只看到转圈。

function probeOf(entries) {
  const probe = createProbeMap()
  for (const [path, value] of Object.entries(entries)) {
    probe.set(path, value.exists === false
      ? { state: 'absent', exists: false, content: null }
      : { state: 'present', exists: true, content: value.content })
  }
  return probe
}

function file(changeId, overrides = {}) {
  return {
    changeId,
    changeSetId: 10,
    relativePath: 'src/a.ts',
    changeType: 'create',
    status: 'pending',
    beforeExisted: false,
    afterExisted: true,
    beforeTruncated: false,
    afterTruncated: false,
    beforeContent: null,
    afterContent: `${changeId}-content`,
    updateTime: '2026-11-05T10:00:00',
    ...overrides
  }
}

test('two create rows for the same new path coalesce instead of conflicting as local-occupied', () => {
  const plan = buildSyncPlan({
    changeSets: [{ changeSetId: 10, files: [file('f1'), file('f2')] }],
    cursor: 0,
    probe: probeOf({ 'src/a.ts': { exists: false } })
  })

  assert.equal(plan.blocked, false, '同一路径的连续 create 不是冲突')
  assert.equal(plan.conflicts.length, 0)
  // 只写最终版本一次。
  assert.deepEqual(plan.writeSteps.map(step => step.content), ['f2-content'])
  assert.equal(plan.writeSteps.length, 1)
})

test('create rows on the same path keep the chain in steps but a single write', () => {
  const plan = buildSyncPlan({
    changeSets: [{ changeSetId: 10, files: [file('f1'), file('f2'), file('f3')] }],
    cursor: 0,
    probe: probeOf({ 'src/a.ts': { exists: false } })
  })

  // steps 仍是完整链（审计与游标判定需要），writeSteps 才是实际写盘计划。
  assert.deepEqual(plan.steps.map(step => step.content), ['f1-content', 'f2-content', 'f3-content'])
  assert.deepEqual(plan.writeSteps.map(step => step.content), ['f3-content'])
  assert.equal(plan.summary.toApply, 1)
  assert.equal(plan.summary.coalesced, 2)
})

test('repeated create rows still advance the cursor because the change-set completed', () => {
  const plan = buildSyncPlan({
    changeSets: [{ changeSetId: 10, files: [file('f1'), file('f2')] }],
    cursor: 0,
    probe: probeOf({ 'src/a.ts': { exists: false } })
  })

  assert.equal(plan.nextCursor, 10)
  assert.deepEqual(plan.completedChangeSetIds, [10])
})

test('a genuinely occupied local path is still a conflict, not silently overwritten', () => {
  const plan = buildSyncPlan({
    changeSets: [{ changeSetId: 10, files: [file('f1')] }],
    cursor: 0,
    probe: probeOf({ 'src/a.ts': { content: 'user already has this file' } })
  })

  assert.equal(plan.blocked, true)
  assert.equal(plan.conflicts[0].reason, 'local-occupied')
  assert.equal(plan.writeSteps.length, 0)
})

test('modify rows on the same path still coalesce to the final content', () => {
  const plan = buildSyncPlan({
    changeSets: [{
      changeSetId: 10,
      files: [
        file('m1', { changeType: 'modify', beforeExisted: true, beforeContent: 'v0', afterContent: 'v1' }),
        file('m2', { changeType: 'modify', beforeExisted: true, beforeContent: 'v1', afterContent: 'v2' })
      ]
    }],
    cursor: 0,
    probe: probeOf({ 'src/a.ts': { content: 'v0' } })
  })

  assert.equal(plan.blocked, false)
  assert.deepEqual(plan.writeSteps.map(step => step.content), ['v2'])
  assert.equal(plan.writeSteps[0].previousContent, 'v0')
})

// 现场证据（2026-11-06 真实浏览器）：同一个 package.json 在冲突对话框里出现 3 次，
// 标题写「有 3 个文件与本地不一致」，实际只有 1 个文件冲突。
// 根因：冲突按**变更行**记录，而同一路径在服务端有 3 行变更（1 个 create + 2 个 modify）。
// 冲突是"这个文件不能安全写入"的判断，属于路径级事实，不是行级事实。
test('one conflicting path produces exactly one conflict entry no matter how many rows it has', () => {
  const plan = buildSyncPlan({
    changeSets: [{
      changeSetId: 10,
      files: [
        file('c1', { changeType: 'create', beforeExisted: false, beforeContent: null, afterContent: 'v1' }),
        file('c2', { changeType: 'modify', beforeExisted: true, beforeContent: 'v1', afterContent: 'v2' }),
        file('c3', { changeType: 'modify', beforeExisted: true, beforeContent: 'v2', afterContent: 'v3' })
      ]
    }],
    cursor: 0,
    probe: probeOf({ 'src/a.ts': { content: 'user already has this file' } })
  })

  assert.equal(plan.blocked, true)
  assert.equal(plan.conflicts.length, 1, `同一路径应只记 1 条冲突，实际 ${plan.conflicts.length} 条`)
  assert.equal(plan.conflicts[0].path, 'src/a.ts')
  // 保留最先出现的判定：它是这个路径"为什么写不了"的根因。
  assert.equal(plan.conflicts[0].reason, 'local-occupied')
  assert.equal(plan.summary.conflicts, 1)
})

test('distinct conflicting paths are all reported', () => {
  const plan = buildSyncPlan({
    changeSets: [{
      changeSetId: 10,
      files: [
        file('a1', { relativePath: 'src/a.ts' }),
        file('b1', { relativePath: 'src/b.ts' })
      ]
    }],
    cursor: 0,
    probe: probeOf({
      'src/a.ts': { content: 'local a' },
      'src/b.ts': { content: 'local b' }
    })
  })

  assert.equal(plan.conflicts.length, 2)
  assert.deepEqual(plan.conflicts.map(c => c.path).sort(), ['src/a.ts', 'src/b.ts'])
})
