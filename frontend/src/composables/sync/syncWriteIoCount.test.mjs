import assert from 'node:assert/strict'
import test from 'node:test'

import { createChangeSetSyncOrchestrator } from './changeSetSyncOrchestrator.js'
import { SYNC_STATES } from './syncSessionState.js'
import { createJournalStore, createMemoryAdapter } from './syncTestDoubles.mjs'

// 用户现场（2026-11-05）：45 个小文件同步耗时约 5 分钟。
//
// 瓶颈不是写，而是**写完又读回来**：每应用一个文件，orchestrator 都调用
//   fileExists(path) + readTextFile(path)
// 去确认"到底写成了什么"。但在真实浏览器里，File System Access 的每次
// 句柄解析 + getFile() + text() 都是异步 I/O，45 个文件就是 90 次额外 I/O。
//
// 事实上刚写入的内容就是已知的（step.content），无需回读。

function feedOf(files) {
  return {
    changeSets: [{ changeSetId: 1, files }],
    changedFiles: [],
    nextCursor: { afterChangeSetId: 1, tailUpdateTime: '2026-11-05T10:00:01', tailChangeId: '' },
    hasMore: false
  }
}

function modify(changeId, path, before, after) {
  return {
    changeId,
    changeSetId: 1,
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

/** 统计每个 I/O 方法被调用了多少次。 */
function countingAdapter(initial = {}) {
  const inner = createMemoryAdapter(initial)
  const calls = { write: 0, read: 0, exists: 0, delete: 0 }
  return {
    calls,
    files: inner.files,
    async listPaths() { return inner.listPaths() },
    async fileExists(path) { calls.exists += 1; return inner.fileExists(path) },
    async readTextFile(path) { calls.read += 1; return inner.readTextFile(path) },
    async writeTextFile(path, content) { calls.write += 1; return inner.writeTextFile(path, content) },
    async deleteFile(path) { calls.delete += 1; return inner.deleteFile(path) }
  }
}

test('a write step does not read the file back to learn what it just wrote', async () => {
  const adapter = countingAdapter({ 'src/a.js': 'old' })
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    journalStore: createJournalStore(undefined),
    loadChanges: async () => feedOf([modify('c1', 'src/a.js', 'old', 'new')])
  })

  const session = await orchestrator.run()

  assert.equal(session.state, SYNC_STATES.DONE)
  assert.equal(adapter.calls.write, 1)
  // 探针阶段必须读一次（判断本地基线是否漂移），但**写入之后**不得再回读。
  // 旧实现是 2 次：探针 1 次 + 写入后回读 1 次。
  assert.equal(adapter.calls.read, 1, `回读应只有探针那 1 次，实际 ${adapter.calls.read} 次`)
})

test('I/O call count stays linear in the number of files', async () => {
  const paths = Array.from({ length: 10 }, (_, index) => `src/f${index}.js`)
  const adapter = countingAdapter(Object.fromEntries(paths.map(p => [p, 'old'])))
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    journalStore: createJournalStore(undefined),
    loadChanges: async () => feedOf(paths.map((path, index) => modify(`c${index}`, path, 'old', 'new')))
  })

  await orchestrator.run()

  // 10 个文件：探针 10 次 exists + 10 次 read，写入 10 次，不应再有回读。
  assert.equal(adapter.calls.write, 10)
  assert.equal(adapter.calls.read, 10, `回读次数应为探针的 10 次，实际 ${adapter.calls.read}`)
})

test('journal still records the applied content so rollback remains possible', async () => {
  const adapter = countingAdapter({ 'src/a.js': 'old' })
  const journalStore = createJournalStore(undefined)
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    journalStore,
    loadChanges: async () => feedOf([modify('c1', 'src/a.js', 'old', 'new')])
  })

  await orchestrator.run()

  const entry = journalStore.stored.entries[0]
  // 不回读不等于不记录：appliedContent 直接用写入的内容，回退依据不能丢。
  assert.equal(entry.appliedContent, 'new')
  assert.equal(entry.appliedExisted, true)
})

test('rollback still verifies the file was not changed by the user', async () => {
  const adapter = countingAdapter({ 'src/a.js': 'old' })
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    journalStore: createJournalStore(undefined),
    loadChanges: async () => feedOf([modify('c1', 'src/a.js', 'old', 'new')])
  })

  await orchestrator.run()
  adapter.files.set('src/a.js', 'user edited')

  const session = await orchestrator.rollback()
  assert.equal(session.state, SYNC_STATES.FAILED)
  assert.match(session.error, /rollback target changed/)
})

test('a delete step records that the path no longer exists without reading it', async () => {
  const adapter = countingAdapter({ 'src/gone.js': 'content' })
  const journalStore = createJournalStore(undefined)
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    journalStore,
    loadChanges: async () => feedOf([{
      changeId: 'd1',
      changeSetId: 1,
      relativePath: 'src/gone.js',
      changeType: 'delete',
      status: 'pending',
      beforeExisted: true,
      afterExisted: false,
      beforeTruncated: false,
      afterTruncated: false,
      beforeContent: 'content',
      afterContent: null,
      updateTime: '2026-11-05T10:00:00'
    }])
  })

  const session = await orchestrator.run()

  assert.equal(session.state, SYNC_STATES.DONE)
  const entry = journalStore.stored.entries[0]
  assert.equal(entry.appliedExisted, false)
  assert.equal(entry.appliedContent, null)
})
