import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { useProjectExport } from './useProjectExport.js'

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

function setup(overrides = {}) {
  const saved = []
  const errors = []
  const infos = []
  const successes = []
  const calls = { create: 0, status: 0, cancel: 0, download: 0 }
  const api = {
    async createExportJob(projectId, includeAll) {
      calls.create++
      return { data: { jobId: 'job-1', status: 'PENDING', progressPercent: 0 } }
    },
    async getExportJob() {
      calls.status++
      if (overrides.statusSequence && overrides.statusSequence.length) {
        const current = overrides.statusSequence.shift()
        return {
          data: { jobId: 'job-1', ...current, progressPercent: current.progressPercent ?? 0 }
        }
      }
      return { data: { jobId: 'job-1', status: 'SUCCESS', progressPercent: 100 } }
    },
    async cancelExportJob() {
      calls.cancel++
      return { data: {} }
    },
    async downloadExportJob() {
      calls.download++
      return { size: 3 }
    }
  }
  const composable = useProjectExport({
    projectId: { value: 12 },
    projectName: { value: 'demo' },
    api,
    notify: {
      success: m => successes.push(m),
      info: m => infos.push(m),
      error: m => errors.push(m)
    },
    pollIntervalMs: 1,
    saveBlobAs: (blob, name) => saved.push({ blob, name }),
    ...overrides.hooks
  })
  return { composable, saved, errors, infos, successes, calls }
}

test('happy path: create → poll → download → save with project name', async () => {
  const { composable, saved, calls } = setup()
  composable.begin()
  assert.equal(composable.phase.value, 'confirm')
  composable.confirmStart()

  await sleep(30)
  assert.equal(composable.phase.value, 'idle')
  assert.equal(calls.create, 1)
  assert.equal(calls.status >= 1, true)
  assert.equal(calls.download, 1)
  assert.equal(saved.length, 1)
  assert.equal(saved[0].name, 'demo.zip')
})

test('includeAll option is forwarded to job creation', async () => {
  const seen = []
  const { composable } = setup({
    hooks: {
      api: undefined
    }
  })
  // 重建一份可观测 create 的实例
  const created = []
  const instance = useProjectExport({
    projectId: { value: 9 },
    projectName: { value: '' },
    notify: { error() {} },
    pollIntervalMs: 1,
    saveBlobAs: () => {},
    api: {
      async createExportJob(projectId, includeAll) {
        created.push(includeAll)
        return { data: { jobId: 'j2', status: 'CANCELLED' } }
      },
      async getExportJob() {
        return { data: { status: 'CANCELLED', progressPercent: 0 } }
      },
      async cancelExportJob() { return { data: {} } },
      async downloadExportJob() { return {} }
    }
  })
  void composable
  instance.includeAll.value = true
  instance.begin()
  instance.confirmStart()
  await sleep(20)
  assert.deepEqual(created, [true])
})

test('backend FAILED status surfaces an explicit error message', async () => {
  const { composable, errors } = setup({
    statusSequence: [{ status: 'RUNNING', progressPercent: 40 }, { status: 'FAILED', errorMessage: '磁盘已满' }]
  })
  composable.begin()
  composable.confirmStart()
  await sleep(40)
  assert.equal(composable.phase.value, 'idle')
  assert.equal(composable.errorMessage.value, '磁盘已满')
  assert.equal(errors.some(message => message.includes('磁盘已满')), true)
})

test('cancel stops polling and notifies backend', async () => {
  const { composable, calls, infos } = setup({
    statusSequence: Array.from({ length: 50 }, (_, index) => ({ status: 'RUNNING', progressPercent: index }))
  })
  composable.begin()
  composable.confirmStart()
  await sleep(10)
  await composable.cancel()
  assert.equal(composable.phase.value, 'idle')
  assert.equal(calls.cancel, 1)
  assert.equal(infos.some(message => message.includes('已取消导出')), true)
})

test('composable keeps the blob-endpoint contract (no .data unwrapping)', async () => {
  const source = await readFile(new URL('./useProjectExport.js', import.meta.url), 'utf8')
  assert.match(source, /const blob = await api\.downloadExportJob\(/)
  assert.doesNotMatch(source, /downloadExportJob\([^)]*\)[^\n]*\.data/)
})
