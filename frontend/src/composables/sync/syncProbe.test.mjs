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
    async readTextFile() { calls += 1; return null }
  }
  await collectProbe(adapter, ['src/a.js', 'src/a.js', '', null])
  assert.equal(calls, 1)
})

// 探针必须一个路径只读一次。
// 旧实现先 fileExists 再 readTextFile，同一路径的每层目录解析和 getFileHandle 都做两遍；
// 浏览器实测 40 个文件 667ms → 315ms，几乎是整次同步里最容易被低估的一段。
test('probes each path with a single read instead of an existence check plus a read', async () => {
  const calls = { exists: 0, read: 0 }
  const adapter = {
    async fileExists() { calls.exists += 1; return true },
    async readTextFile() { calls.read += 1; return 'content' }
  }

  const { probe } = await collectProbe(adapter, ['src/a.js', 'src/b.js'])

  assert.equal(calls.read, 2)
  assert.equal(calls.exists, 0, `不应再调用 fileExists，实际 ${calls.exists} 次`)
  assert.deepEqual(probe.get('src/a.js'), { state: 'present', exists: true, content: 'content' })
})

test('marks unreadable paths as unknown and still probes the rest of the batch', async () => {
  const adapter = {
    async readTextFile(path) {
      if (path === 'broken.js') throw new Error('permission denied')
      return null
    }
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

test('marks an oversized local file as unknown without reading it as a valid baseline', async () => {
  const adapter = createReadOnlyAdapter({ 'src/large.txt': '12345' })
  const { probe, failures } = await collectProbe(adapter, ['src/large.txt'], { maxFileBytes: 4 })
  assert.equal(failures.length, 1)
  assert.equal(probe.get('src/large.txt').state, 'unknown')
  assert.match(probe.get('src/large.txt').error, /file size limit exceeded/)
})

test('marks the batch unknown when the file count limit is exceeded', async () => {
  const adapter = createReadOnlyAdapter({})
  const { probe, failures } = await collectProbe(adapter, ['a.js', 'b.js', 'c.js'], { maxFiles: 2 })
  assert.equal(failures.length, 3)
  assert.match(failures[0].message, /batch file limit exceeded/)
  assert.equal([...probe.values()].every(entry => entry.state === 'unknown'), true)
})

test('marks the batch unknown when the total content byte limit is exceeded', async () => {
  const adapter = createReadOnlyAdapter({ 'a.txt': '1234', 'b.txt': '5678' })
  const { probe, failures } = await collectProbe(adapter, ['a.txt', 'b.txt'], { maxBatchBytes: 7 })
  assert.equal(failures.length, 1)
  assert.match(failures[0].message, /batch size limit exceeded/)
  assert.equal(probe.get('a.txt').state, 'unknown')
  assert.equal(probe.get('b.txt').state, 'unknown')
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
