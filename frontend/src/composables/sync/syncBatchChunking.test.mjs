import assert from 'node:assert/strict'
import test from 'node:test'

import { createChangeSetSyncOrchestrator } from './changeSetSyncOrchestrator.js'
import { SYNC_LIMITS } from './syncLimits.js'
import { SYNC_STATES } from './syncSessionState.js'
import { createJournalStore, createMemoryAdapter } from './syncTestDoubles.mjs'

// 超过 MAX_BATCH_FILES 的批次。
//
// 旧行为：整批 blocked、零写入，并且把每个文件都标成 `probe-unknown`——
// 用户在界面上看到的是"有 500 个文件与本地不一致"，而真实原因是"这批太大"。
// 这是两条独立的错误：
//   1. 500 个文件的中型项目直接同步不了，而这不是资源上限该有的表现（应该分块读完）；
//   2. 报错原因误导，用户按"冲突"去处理永远处理不完。

function feedOf(count, { changeSetId = 1, size = 16 } = {}) {
  return {
    changeSets: [{
      changeSetId,
      files: Array.from({ length: count }, (_, index) => ({
        changeId: `c${changeSetId}-${index}`,
        changeSetId,
        relativePath: `src/f${index}.js`,
        changeType: 'create',
        status: 'pending',
        beforeExisted: false,
        afterExisted: true,
        beforeTruncated: false,
        afterTruncated: false,
        beforeContent: null,
        afterContent: 'x'.repeat(size),
        updateTime: '2026-11-06T10:00:00'
      }))
    }],
    changedFiles: [],
    nextCursor: { afterChangeSetId: changeSetId, tailUpdateTime: '2026-11-06T10:00:01', tailChangeId: '' },
    hasMore: false
  }
}

test('a batch larger than the probe chunk size is synced in chunks instead of being blocked', async () => {
  const count = SYNC_LIMITS.MAX_BATCH_FILES + 120
  const adapter = createMemoryAdapter({})
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    journalStore: createJournalStore(undefined),
    loadChanges: async () => feedOf(count)
  })

  const session = await orchestrator.run()

  assert.equal(session.state, SYNC_STATES.DONE, `应分块完成，实际 ${session.state}: ${session.error ?? ''}`)
  assert.equal(session.applied, count)
  assert.equal(adapter.writes.length, count, '每个文件都应写一次')
  assert.equal(session.conflicts.length, 0, '分块读取不是冲突')
})

test('chunked reads still report per-file progress and a final cursor', async () => {
  const count = SYNC_LIMITS.MAX_BATCH_FILES + 60
  const adapter = createMemoryAdapter({})
  const seen = []
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    journalStore: createJournalStore(undefined),
    loadChanges: async () => feedOf(count),
    onProgress: patch => {
      if (typeof patch.applied === 'number') seen.push(patch.applied)
    }
  })

  const session = await orchestrator.run()

  assert.equal(session.state, SYNC_STATES.DONE)
  assert.ok(seen.length >= count / 50, `进度应持续推进，实际只收到 ${seen.length} 次`)
  assert.equal(session.cursor, 1, '完成后游标应推进到该集合')
})

test('a single file larger than the per-file limit is still refused with an honest reason', async () => {
  // 分块只解决"文件太多"，不解决"单个文件太大"。后者必须仍然拒绝，
  // 而且原因要说清楚，不能伪装成冲突。
  const adapter = createMemoryAdapter({})
  const feed = feedOf(1, { size: SYNC_LIMITS.MAX_FILE_BYTES + 1 })
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    journalStore: createJournalStore(undefined),
    loadChanges: async () => feed
  })

  const session = await orchestrator.run()

  assert.equal(session.state, SYNC_STATES.FAILED)
  assert.match(session.error, /file size limit exceeded/)
  assert.equal(adapter.writes.length, 0)
})

test('the batch byte budget still refuses a batch that cannot fit in memory', async () => {
  // 分块解决"文件太多"，不解决"一次要读进内存的内容太多"。
  // MAX_BATCH_BYTES 保护的是后者：它按整批累计，与分块读取无关。
  // 构造方式：让单个文件接近但不超过单文件上限，累计超过整批上限。
  const perFile = Math.floor(SYNC_LIMITS.MAX_FILE_BYTES / 2)
  const count = Math.ceil(SYNC_LIMITS.MAX_BATCH_BYTES / perFile) + 1
  const adapter = createMemoryAdapter({})
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    journalStore: createJournalStore(undefined),
    loadChanges: async () => feedOf(count, { size: perFile })
  })

  const session = await orchestrator.run()

  assert.equal(session.state, SYNC_STATES.FAILED)
  assert.match(session.error, /batch size limit exceeded/)
  assert.equal(adapter.writes.length, 0, '超限必须在写第一个文件之前失败')
})
