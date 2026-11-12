import assert from 'node:assert/strict'
import test from 'node:test'

import { PREFETCH_KEYS, clearWorkspacePrefetch, rememberPrefetched, takePrefetched } from './workspacePrefetchCache.js'

function deferred() {
  let resolve
  const promise = new Promise(res => { resolve = res })
  return { promise, resolve }
}

test('预取中的请求被工作区消费时不会重复发起', async () => {
  clearWorkspacePrefetch()
  const pending = deferred()
  let calls = 0

  const prefetched = rememberPrefetched(PREFETCH_KEYS.project(7), () => { calls++; return pending.promise })
  pending.resolve({ data: { projectName: 'demo' } })
  const response = await takePrefetched(PREFETCH_KEYS.project(7), () => { calls++; return Promise.resolve({ data: {} }) })

  assert.equal(calls, 1)
  assert.deepEqual(await prefetched, { data: { projectName: 'demo' } })
  assert.equal(response.data.projectName, 'demo')
})

test('预取结果只消费一次，之后回源', async () => {
  clearWorkspacePrefetch()
  let calls = 0
  const load = () => { calls++; return Promise.resolve({ data: { seq: calls } }) }

  rememberPrefetched(PREFETCH_KEYS.conversations(3), load)
  await takePrefetched(PREFETCH_KEYS.conversations(3), load)
  await takePrefetched(PREFETCH_KEYS.conversations(3), load)

  assert.equal(calls, 2, '第二次 take 必须回源，不能复用首次结果')
})

test('预取失败时不缓存失败结果，消费方回源成功', async () => {
  clearWorkspacePrefetch()
  let calls = 0
  rememberPrefetched(PREFETCH_KEYS.skills(), () => {
    calls++
    return Promise.reject(new Error('offline'))
  })

  const response = await takePrefetched(PREFETCH_KEYS.skills(), () => {
    calls++
    return Promise.resolve({ data: ['retry-ok'] })
  })

  assert.equal(calls, 2)
  assert.deepEqual(response.data, ['retry-ok'])
})

test('没有预取时 take 直接回源', async () => {
  clearWorkspacePrefetch()
  const response = await takePrefetched(PREFETCH_KEYS.commands(1), () => Promise.resolve({ data: ['x'] }))
  assert.deepEqual(response.data, ['x'])
})

test('清除后不再命中旧账号的预取', async () => {
  clearWorkspacePrefetch()
  let calls = 0
  rememberPrefetched(PREFETCH_KEYS.mcpServers(), () => { calls++; return Promise.resolve({ data: ['old'] }) })
  clearWorkspacePrefetch()
  const response = await takePrefetched(PREFETCH_KEYS.mcpServers(), () => { calls++; return Promise.resolve({ data: ['new'] }) })

  assert.equal(calls, 2)
  assert.deepEqual(response.data, ['new'])
})
