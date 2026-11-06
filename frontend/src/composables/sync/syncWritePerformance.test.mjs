import assert from 'node:assert/strict'
import test from 'node:test'

import { createChangeSetSyncOrchestrator } from './changeSetSyncOrchestrator.js'
import { createJournal, recordApplied } from './syncJournal.js'
import { SYNC_STATES } from './syncSessionState.js'
import { createJournalStore, createMemoryAdapter } from './syncTestDoubles.mjs'

// 现场证据（2026-11-05 真实截图）：45 个小文件同步耗时约 5 分钟，
// 进度列表里同一路径出现 2–4 次（AGENTS.md ×2、server.test.ts ×3、upstream.test.ts ×4）。
// 本文件固定"同一路径多次变更只写一次盘"这一行为，防止再次退化。

function modify(changeSetId, changeId, path, before, after) {
  return {
    changeId,
    changeSetId,
    relativePath: path,
    changeType: 'modify',
    status: 'pending',
    beforeExisted: true,
    afterExisted: true,
    beforeTruncated: false,
    afterTruncated: false,
    beforeContent: before,
    afterContent: after,
    updateTime: '2026-11-05T10:00:00'
  }
}

function feedOf(files, changeSetId = 1) {
  return {
    changeSets: [{ changeSetId, taskId: 1, status: 'pending', updateTime: '2026-11-05T10:00:00', files }],
    changedFiles: [],
    nextCursor: { afterChangeSetId: changeSetId, tailUpdateTime: '2026-11-05T10:00:01', tailChangeId: '' },
    hasMore: false,
    hasMoreChangeSets: false,
    hasMoreChangedFiles: false
  }
}

test('writes each path once even when the same file is modified several times in one batch', async () => {
  const adapter = createMemoryAdapter({ 'src/app.js': 'v0' })
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    journalStore: createJournalStore(undefined),
    loadChanges: async () => feedOf([
      modify(1, 'c1', 'src/app.js', 'v0', 'v1'),
      modify(1, 'c2', 'src/app.js', 'v1', 'v2'),
      modify(1, 'c3', 'src/app.js', 'v2', 'v3')
    ])
  })

  const session = await orchestrator.run()

  assert.equal(session.state, SYNC_STATES.DONE)
  // 中间版本 v1、v2 没有任何价值：本地最终只需要 v3。
  assert.deepEqual(adapter.writes, ['src/app.js'])
  assert.equal(adapter.files.get('src/app.js'), 'v3')
})

test('rollback after coalescing still restores the pre-sync content exactly once', async () => {
  const adapter = createMemoryAdapter({ 'src/app.js': 'v0' })
  const journalStore = createJournalStore(undefined)
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    journalStore,
    loadChanges: async () => feedOf([
      modify(1, 'c1', 'src/app.js', 'v0', 'v1'),
      modify(1, 'c2', 'src/app.js', 'v1', 'v2')
    ])
  })

  await orchestrator.run()
  assert.equal(adapter.files.get('src/app.js'), 'v2')

  await orchestrator.rollback()
  // 合并后只有一条 journal 记录，回退必须回到同步前的 v0（不是中间态 v1）。
  assert.equal(adapter.files.get('src/app.js'), 'v0')
  assert.equal(journalStore.stored.entries.length, 0)
})

test('coalescing keeps a create-then-delete pair from leaving a file behind', async () => {
  const adapter = createMemoryAdapter({})
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    journalStore: createJournalStore(undefined),
    loadChanges: async () => feedOf([
      {
        changeId: 'c1', changeSetId: 1, relativePath: 'src/temp.js', changeType: 'create',
        status: 'pending', beforeExisted: false, afterExisted: true,
        beforeTruncated: false, afterTruncated: false, beforeContent: null, afterContent: 'x'
      },
      {
        changeId: 'c2', changeSetId: 1, relativePath: 'src/temp.js', changeType: 'delete',
        status: 'pending', beforeExisted: true, afterExisted: false,
        beforeTruncated: false, afterTruncated: false, beforeContent: 'x', afterContent: null
      }
    ])
  })

  const session = await orchestrator.run()

  assert.equal(session.state, SYNC_STATES.DONE)
  // 创建后立即删除 = 本地净效果为零，不应留下文件，也不应产生写入。
  assert.equal(adapter.files.has('src/temp.js'), false)
  assert.equal(adapter.writes.length, 0)
})

test('a journal save failure still leaves the file on disk in its final state', async () => {
  const adapter = createMemoryAdapter({ 'src/app.js': 'v0' })
  let saveCalls = 0
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    journalStore: {
      async load() { return null },
      async save() {
        saveCalls += 1
        if (saveCalls > 0) throw new Error('quota exceeded')
      }
    },
    loadChanges: async () => feedOf([modify(1, 'c1', 'src/app.js', 'v0', 'v1')])
  })

  const session = await orchestrator.run()

  assert.equal(session.state, SYNC_STATES.FAILED)
  assert.match(session.error, /quota exceeded/)
  // 文件已写、journal 未存 → 必须显式失败，而不是报告成功。
  assert.equal(adapter.files.get('src/app.js'), 'v1')
})

test('journal does not store the same content twice per entry', () => {
  const body = 'x'.repeat(1024)
  const journal = recordApplied(createJournal(), {
    changeId: 'c1',
    changeSetId: 1,
    path: 'src/a.js',
    op: 'write',
    content: body,
    previousContent: null,
    previousExisted: false
  }, { appliedContent: body, appliedExisted: true })

  const entry = journal.entries[0]
  const size = JSON.stringify(entry).length
  // 单条 entry 只应保存一份完整内容（约 1KB + 元数据），而不是两份。
  assert.ok(size < body.length * 1.5, `entry 体积 ${size} 超过单份内容的 1.5 倍`)
})
