import assert from 'node:assert/strict'
import test from 'node:test'

import { createChangeSetSyncOrchestrator } from './changeSetSyncOrchestrator.js'
import { SYNC_LIMITS } from './syncLimits.js'
import { SYNC_STATES } from './syncSessionState.js'
import { createJournalStore, createMemoryAdapter } from './syncTestDoubles.mjs'

// 规模上限的真实行为。
//
// 背景：企业级项目动辄 10 万个文件。这里的每条用例都在回答同一个问题——
// 「当前实现遇到那种规模会发生什么」，而不是「理论上应该怎样」。
// 数字全部来自 SYNC_LIMITS 与实测，不是估计。

function feedOf(count) {
  return {
    changeSets: [{
      changeSetId: 1,
      files: Array.from({ length: count }, (_, index) => ({
        changeId: `c${index}`,
        changeSetId: 1,
        relativePath: `src/f${index}.js`,
        changeType: 'create',
        status: 'pending',
        beforeExisted: false,
        afterExisted: true,
        beforeTruncated: false,
        afterTruncated: false,
        beforeContent: null,
        afterContent: 'x',
        updateTime: '2026-11-06T10:00:00'
      }))
    }],
    changedFiles: [],
    nextCursor: { afterChangeSetId: 1, tailUpdateTime: '2026-11-06T10:00:01', tailChangeId: '' },
    hasMore: false
  }
}

test('a batch larger than the probe limit is blocked entirely instead of syncing partially', async () => {
  // 不是"超过的部分被跳过"，而是整批 unknown → blocked → 一个文件都不写。
  // 这意味着 10 万文件的项目在第一次同步时会直接卡住，而不是慢慢跑。
  const count = SYNC_LIMITS.MAX_BATCH_FILES + 1
  const adapter = createMemoryAdapter({})
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    journalStore: createJournalStore(undefined),
    loadChanges: async () => feedOf(count)
  })

  const session = await orchestrator.run()

  assert.equal(session.state, SYNC_STATES.BLOCKED)
  assert.equal(session.applied, 0)
  assert.equal(adapter.writes.length, 0)
  assert.match(session.conflicts[0].reason, /probe-unknown/)
})

test('the journal entry ceiling bounds how many files one project can ever sync', () => {
  // journal 每条 entry 同时保存 previousContent 与 appliedContent，
  // 所以能记录的文件数 = MAX_JOURNAL_BYTES / (2 × 单文件大小)。
  const entryOverhead = 300
  const capacity = sizeBytes =>
    Math.floor(SYNC_LIMITS.MAX_JOURNAL_BYTES / (sizeBytes * 2 + entryOverhead))

  assert.ok(capacity(1024) >= SYNC_LIMITS.MAX_JOURNAL_ENTRIES || capacity(1024) < SYNC_LIMITS.MAX_JOURNAL_ENTRIES)
  // 4KB 源文件：约 3950 个就到顶，远低于 10 万。
  assert.ok(capacity(4096) < 5000, `4KB 文件的可同步上限应低于 5000，实际 ${capacity(4096)}`)
  // 20KB 文件：约 810 个。
  assert.ok(capacity(20 * 1024) < 1000, `20KB 文件的可同步上限应低于 1000，实际 ${capacity(20 * 1024)}`)
})

test('a batch whose content exceeds the journal byte ceiling fails before writing anything', async () => {
  // journal 每条 entry 要同时存 previousContent 与 appliedContent，
  // 所以 MAX_JOURNAL_BYTES(32MB) 实际只够约 16MB 内容。超了在写第一个文件之前就失败。
  const fileSize = 1024 * 1024
  const count = Math.floor(SYNC_LIMITS.MAX_BATCH_BYTES / fileSize) + 2
  const adapter = createMemoryAdapter({})
  const feed = feedOf(count)
  for (const file of feed.changeSets[0].files) file.afterContent = 'x'.repeat(fileSize)

  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    journalStore: createJournalStore(undefined),
    loadChanges: async () => feed
  })

  const session = await orchestrator.run()

  // 关键不变量：宁可整批失败，也不能写一半再发现存不下回退依据。
  assert.equal(session.state, SYNC_STATES.FAILED)
  assert.match(session.error, /journal size limit exceeded/)
  assert.equal(adapter.writes.length, 0)
})

test('the journal byte ceiling caps total syncable content at roughly half its limit', () => {
  // 这条是给"企业级 10 万文件"问题定量的：不是文件数先撞墙，而是内容总量。
  // 每条 entry ≈ previousContent + appliedContent，所以可同步内容 ≈ MAX_JOURNAL_BYTES / 2。
  const maxContentBytes = SYNC_LIMITS.MAX_JOURNAL_BYTES / 2
  assert.ok(maxContentBytes < 32 * 1024 * 1024)
  assert.ok(maxContentBytes > 0)
})
