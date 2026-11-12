import assert from 'node:assert/strict'
import test from 'node:test'

import { useProjectUpload } from './useProjectUpload.js'

/**
 * 分片上传编排的行为测试：用假 api 驱动完整流程，验证「只补缺失片」「失败保留会话」
 * 这两条只有真实网络抖动时才会走到的路径。真机上很难稳定复现，所以在这里固定下来。
 */

function fakeApi(overrides = {}) {
  const calls = { create: [], chunk: [], complete: [], status: [], cancel: [] }
  let received = new Set()
  let completed = false
  const api = {
    async archiveLimits() {
      return { data: { maxArchiveBytes: 500 * 1024 * 1024, chunkSizeBytes: 1024 } }
    },
    async createUploadSession(payload) {
      calls.create.push(payload)
      received = new Set()
      completed = false
      return { data: { sessionId: 's1', totalChunks: 4, status: 'RECEIVING' } }
    },
    async uploadChunk(sessionId, index, blob) {
      calls.chunk.push({ sessionId, index, size: blob.size })
      if (overrides.failChunk && overrides.failChunk(index, calls.chunk.length)) {
        const error = new Error('network down')
        error.code = 'ERR_NETWORK'
        throw error
      }
      received.add(index)
      return { data: { sessionId, receivedChunks: received.size } }
    },
    async completeUploadSession(sessionId) {
      calls.complete.push(sessionId)
      completed = true
      return { data: { sessionId, status: 'IMPORTING' } }
    },
    async getUploadSession(sessionId) {
      calls.status.push(sessionId)
      if (!completed) {
        // 上传未完成时只回报已到达的分片，续传逻辑据此决定补哪几片。
        return {
          data: {
            sessionId,
            status: 'RECEIVING',
            receivedChunkIndexes: [...received].sort((a, b) => a - b)
          }
        }
      }
      return {
        data: {
          sessionId,
          status: 'DONE',
          receivedChunkIndexes: [...received].sort((a, b) => a - b),
          projectId: 9,
          projectName: 'demo',
          fileCount: 4,
          extractedSize: 4096,
          skippedCount: 0,
          skippedSamples: []
        }
      }
    },
    async cancelUploadSession(sessionId) {
      calls.cancel.push(sessionId)
      return { data: { sessionId, status: 'CANCELLED' } }
    }
  }
  return { api, calls }
}

function fakeFile(size) {
  return { name: 'demo.zip', size, slice: (start, end) => ({ size: end - start }) }
}

async function settle(promise) {
  await promise
  // start/retry 是 async 的，内部还有一次 await 链，让微任务跑完。
  await new Promise((resolve) => setTimeout(resolve, 0))
}

test('失败后重试只补传缺失的分片，不从第一片重来', async () => {
  let failed = false
  const { api, calls } = fakeApi({
    failChunk: (index) => {
      // 第 2 片（index 1）第一次到达时模拟断网；重试时放行。
      if (index === 1 && !failed) {
        failed = true
        return true
      }
      return false
    }
  })
  const upload = useProjectUpload({ api, pollIntervalMs: 1, chunkAttempts: 1 })
  await upload.begin(fakeFile(4096))

  await settle(upload.start())
  assert.equal(upload.phase.value, 'failed', '第一次应在第 2 片失败')
  assert.equal(upload.resumable.value, true, '会话仍在，应可续传')
  assert.deepEqual(calls.chunk.map((c) => c.index), [0, 1], '失败前只传了 0、1')

  await settle(upload.retry())
  assert.equal(upload.phase.value, 'done')
  assert.deepEqual(calls.chunk.map((c) => c.index), [0, 1, 1, 2, 3], '重试从缺失的第 2 片开始')
  assert.equal(calls.create.length, 1, '续传不应新建会话')
})

test('单片失败先在同片内重试，避免整包从头再来', async () => {
  let attempts = 0
  const { api, calls } = fakeApi({
    failChunk: (index) => {
      if (index === 0) {
        attempts += 1
        return attempts < 2
      }
      return false
    }
  })
  const upload = useProjectUpload({ api, pollIntervalMs: 1, chunkAttempts: 3 })
  await upload.begin(fakeFile(2048))

  await settle(upload.start())

  assert.equal(upload.phase.value, 'done')
  assert.equal(calls.chunk.filter((c) => c.index === 0).length, 2, '第 1 片尝试两次后成功')
  assert.deepEqual(calls.chunk.map((c) => c.index), [0, 0, 1])
})

test('4xx 是服务端明确拒绝，不在同片内重试', async () => {
  const { api, calls } = fakeApi({
    failChunk: (index) => index === 0
  })
  api.uploadChunk = async (sessionId, index, blob) => {
    calls.chunk.push({ sessionId, index, size: blob.size })
    const error = new Error('分片 0 应为 1024 字节')
    error.response = { status: 400 }
    throw error
  }
  const upload = useProjectUpload({ api, pollIntervalMs: 1, chunkAttempts: 3 })
  await upload.begin(fakeFile(2048))

  await settle(upload.start())

  assert.equal(upload.phase.value, 'failed')
  assert.equal(calls.chunk.length, 1, '400 不该重试')
})

test('导入失败时保留错误信息并允许重试', async () => {
  const { api, calls } = fakeApi()
  api.getUploadSession = async (sessionId) => {
    calls.status.push(sessionId)
    return { data: { sessionId, status: 'FAILED', errorMessage: '项目文件数超过上限 50000 个' } }
  }
  const upload = useProjectUpload({ api, pollIntervalMs: 1 })
  await upload.begin(fakeFile(2048))

  await settle(upload.start())

  assert.equal(upload.phase.value, 'failed')
  assert.match(upload.errorMessage.value, /文件数超过上限/)
})

test('放弃失败的上传会作废服务端会话', async () => {
  const { api, calls } = fakeApi({ failChunk: (index) => index === 0 })
  const upload = useProjectUpload({ api, pollIntervalMs: 1, chunkAttempts: 1 })
  await upload.begin(fakeFile(2048))
  await settle(upload.start())
  assert.equal(upload.phase.value, 'failed')

  upload.cancel()
  await new Promise((resolve) => setTimeout(resolve, 0))

  assert.deepEqual(calls.cancel, ['s1'])
  assert.equal(upload.phase.value, 'idle')
})
