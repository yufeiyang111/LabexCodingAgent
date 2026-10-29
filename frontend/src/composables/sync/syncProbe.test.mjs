import assert from 'node:assert/strict'
import test from 'node:test'

import { buildSyncPlan } from './changeSetSyncPlan.js'
import { collectProbe, probeToObject } from './syncProbe.js'

// 测试替身：只读适配器（无写能力，验证 probe 只依赖读方法）
function createReadOnlyAdapter(files) {
  return {
    async fileExists(path) { return Object.prototype.hasOwnProperty.call(files, path) },
    async readTextFile(path) {
      return Object.prototype.hasOwnProperty.call(files, path) ? files[path] : null
    }
  }
}

test('collects local state for the requested paths', async () => {
  const adapter = createReadOnlyAdapter({ 'src/a.js': 'old' })
  const { probe, failures } = await collectProbe(adapter, ['src/a.js', 'src/new.js'])
  assert.deepEqual(failures, [])
  assert.deepEqual(probe.get('src/a.js'), { state: 'present', exists: true, content: 'old' })
  assert.deepEqual(probe.get('src/new.js'), { state: 'absent', exists: false, content: null })
})

test('deduplicates paths and ignores empty entries', async () => {
  let calls = 0
  const adapter = {
    async fileExists() { calls += 1; return false },
    async readTextFile() { return null }
  }
  await collectProbe(adapter, ['src/a.js', 'src/a.js', '', null])
  assert.equal(calls, 1)
})

test('marks unreadable paths as unknown and still probes the rest of the batch', async () => {
  const adapter = {
    async fileExists(path) {
      if (path === 'broken.js') throw new Error('permission denied')
      return false
    },
    async readTextFile() { return null }
  }
  const { probe, failures } = await collectProbe(adapter, ['broken.js', 'src/a.js'])
  assert.equal(failures.length, 1)
  assert.equal(failures[0].path, 'broken.js')
  assert.deepEqual(probe.get('broken.js'), {
    state: 'unknown',
    exists: null,
    content: null,
    error: 'permission denied'
  })
  assert.equal(probe.get('src/a.js').state, 'absent')
})

test('probe output feeds buildSyncPlan directly', async () => {
  const adapter = createReadOnlyAdapter({ 'src/a.js': 'old' })
  const { probe } = await collectProbe(adapter, ['src/a.js'])
  const plan = buildSyncPlan({
    changes: [{
      id: 'c1',
      changeSetId: 5,
      relativePath: 'src/a.js',
      changeType: 'modify',
      beforeContent: 'old',
      afterContent: 'new'
    }],
    cursor: 0,
    probe: probeToObject(probe)
  })
  assert.equal(plan.blocked, false)
  assert.equal(plan.steps[0].content, 'new')
  assert.equal(plan.steps[0].previousContent, 'old')
})

test('converts probe maps into plain objects', () => {
  const map = new Map([['a', { exists: true, content: 'x' }]])
  assert.deepEqual(probeToObject(map), { a: { exists: true, content: 'x' } })
  assert.deepEqual(probeToObject(null), {})
})
