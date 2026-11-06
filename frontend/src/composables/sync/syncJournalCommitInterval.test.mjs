import assert from 'node:assert/strict'
import test from 'node:test'

import { createChangeSetSyncOrchestrator } from './changeSetSyncOrchestrator.js'
import { SYNC_STATES } from './syncSessionState.js'
import { createMemoryAdapter } from './syncTestDoubles.mjs'

// 用户现场（2026-11-05）：同步过程中刷新页面，整个流程中断，且已写入的文件没有记录。
//
// 根因：为了提速，journal 改成"整批写完才保存一次"。于是刷新/关标签/崩溃时：
//   - 已写盘的文件没有任何 journal 记录 → 无法回退
//   - 游标没有推进 → 下次重来会把已写的文件再写一遍
//
// 正确做法是**周期性提交**：既不是每步一次（45 个文件 5 分钟的元凶），
// 也不是整批一次（丢进度），而是每 N 步落盘一次。

function feedOf(count, changeSetId = 1) {
  return {
    changeSets: [{
      changeSetId,
      files: Array.from({ length: count }, (_, index) => ({
        changeId: `c${index}`,
        changeSetId,
        relativePath: `src/f${index}.js`,
        changeType: 'create',
        status: 'pending',
        beforeExisted: false,
        afterExisted: true,
        beforeTruncated: false,
        afterTruncated: false,
        beforeContent: null,
        afterContent: `content-${index}`,
        updateTime: '2026-11-05T10:00:00'
      }))
    }],
    changedFiles: [],
    nextCursor: { afterChangeSetId: changeSetId, tailUpdateTime: '2026-11-05T10:00:01', tailChangeId: '' },
    hasMore: false
  }
}

function recordingJournalStore() {
  const saves = []
  let stored = null
  return {
    saves,
    get stored() { return stored },
    async load() { return stored },
    async save(journal) {
      stored = journal
      saves.push(journal.entries.length)
    }
  }
}

test('journal is committed periodically so a refresh mid-run does not lose all progress', async () => {
  const journalStore = recordingJournalStore()
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter: createMemoryAdapter({}),
    journalStore,
    loadChanges: async () => feedOf(10),
    // 每 4 步落盘一次
    journalCommitInterval: 4
  })

  const session = await orchestrator.run()

  assert.equal(session.state, SYNC_STATES.DONE)
  // 10 个文件、每 4 步提交 → 至少 2 次中间提交 + 1 次收尾
  assert.ok(journalStore.saves.length >= 3, `期望至少 3 次提交，实际 ${journalStore.saves.length}`)
  // 中间提交必须是递增的进度，不能只有最后一次的完整结果
  assert.ok(
    journalStore.saves.some(count => count > 0 && count < 10),
    `中间提交应记录部分进度，实际提交序列 ${journalStore.saves.join(',')}`
  )
})

test('periodic commits still keep the total save count far below one-per-file', async () => {
  const journalStore = recordingJournalStore()
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter: createMemoryAdapter({}),
    journalStore,
    loadChanges: async () => feedOf(40),
    journalCommitInterval: 8
  })

  await orchestrator.run()

  // 40 个文件：每 8 步一次 = 5 次中间 + 1 次收尾，远少于 40 次
  assert.ok(journalStore.saves.length <= 8, `提交次数应远少于文件数，实际 ${journalStore.saves.length}`)
})

test('the default interval keeps small batches at a single commit', async () => {
  const journalStore = recordingJournalStore()
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter: createMemoryAdapter({}),
    journalStore,
    loadChanges: async () => feedOf(3)
  })

  await orchestrator.run()

  // 3 个文件不值得中途提交：只在收尾时存一次
  assert.equal(journalStore.saves.length, 1)
})

test('a refresh mid-run leaves a journal covering the files already written', async () => {
  // 模拟：写到第 5 个文件时"刷新"（后续步骤直接中断）
  const adapter = createMemoryAdapter({})
  const journalStore = recordingJournalStore()
  let written = 0
  const failingAdapter = {
    files: adapter.files,
    async listPaths() { return [] },
    async fileExists(path) { return adapter.fileExists(path) },
    async readTextFile(path) { return adapter.readTextFile(path) },
    async writeTextFile(path, content) {
      written += 1
      if (written > 5) throw new Error('page unloaded')
      return adapter.writeTextFile(path, content)
    },
    async deleteFile(path) { return adapter.deleteFile(path) }
  }

  const orchestrator = createChangeSetSyncOrchestrator({
    adapter: failingAdapter,
    journalStore,
    loadChanges: async () => feedOf(12),
    journalCommitInterval: 3
  })

  const session = await orchestrator.run()

  assert.equal(session.state, SYNC_STATES.FAILED)
  // 中断后 journal 必须覆盖已写入的 5 个文件，否则回退无依据
  assert.ok(journalStore.stored, '中断后必须留下 journal')
  assert.ok(
    journalStore.stored.entries.length >= 3,
    `已写入 5 个文件，journal 至少应记录 3 个，实际 ${journalStore.stored.entries.length}`
  )
})
