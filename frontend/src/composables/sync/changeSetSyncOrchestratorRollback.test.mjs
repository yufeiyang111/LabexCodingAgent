import assert from 'node:assert/strict'
import test from 'node:test'

import { createChangeSetSyncOrchestrator } from './changeSetSyncOrchestrator.js'
import { SYNC_STATES } from './syncSessionState.js'
import { createJournalStore, createMemoryAdapter, modify } from './syncTestDoubles.mjs'

// C07：回滚前必须确认文件仍是本次同步写入的结果，不能覆盖用户后续编辑。
test('refuses rollback when a synced file was edited after apply', async () => {
  const adapter = createMemoryAdapter({ 'src/app.js': 'old' })
  const journalStore = createJournalStore(undefined)
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    loadChanges: async () => [modify(1, 'src/app.js', 'old', 'new')],
    journalStore
  })

  await orchestrator.run()
  adapter.files.set('src/app.js', 'manual edit')
  const writesBeforeRollback = adapter.writes.length
  const session = await orchestrator.rollback()

  assert.equal(session.state, SYNC_STATES.FAILED)
  assert.match(session.error, /rollback target changed/)
  assert.equal(adapter.files.get('src/app.js'), 'manual edit')
  assert.equal(adapter.writes.length, writesBeforeRollback)
  assert.equal(journalStore.stored.entries.length, 1)
})

// C07：删除类同步也必须校验目标仍处于“已删除”状态，用户重建同名文件时不得回滚覆盖。
test('refuses rollback when a synced deletion was recreated by the user', async () => {
  const adapter = createMemoryAdapter({ 'src/legacy.js': 'legacy' })
  const journalStore = createJournalStore(undefined)
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    loadChanges: async () => [{
      id: 'delete-1',
      changeSetId: 1,
      relativePath: 'src/legacy.js',
      changeType: 'delete',
      beforeContent: 'legacy',
      afterContent: null
    }],
    journalStore
  })

  await orchestrator.run()
  adapter.files.set('src/legacy.js', 'user recreated')
  const writesBeforeRollback = adapter.writes.length
  const session = await orchestrator.rollback()

  assert.equal(session.state, SYNC_STATES.FAILED)
  assert.match(session.error, /rollback target changed/)
  assert.equal(adapter.files.get('src/legacy.js'), 'user recreated')
  assert.equal(adapter.writes.length, writesBeforeRollback)
  assert.equal(journalStore.stored.entries.length, 1)
})

// C07：回滚必须先整体预检，不能回退一半后才发现后面的目标已被用户修改。
test('preflights every rollback target before applying any inverse step', async () => {
  const adapter = createMemoryAdapter({ 'src/a.js': 'a-old', 'src/b.js': 'b-old' })
  const journalStore = createJournalStore(undefined)
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    loadChanges: async () => [
      modify(1, 'src/a.js', 'a-old', 'a-new'),
      modify(2, 'src/b.js', 'b-old', 'b-new')
    ],
    journalStore
  })

  await orchestrator.run()
  adapter.files.set('src/b.js', 'manual edit')
  const writesBeforeRollback = adapter.writes.length
  const session = await orchestrator.rollback()

  assert.equal(session.state, SYNC_STATES.FAILED)
  assert.equal(adapter.files.get('src/a.js'), 'a-new')
  assert.equal(adapter.files.get('src/b.js'), 'manual edit')
  assert.equal(adapter.writes.length, writesBeforeRollback)
  assert.equal(journalStore.stored.entries.length, 2)
})

// C07：旧 journal 没有记录“同步后内容”时无法证明文件仍归本次同步所有，必须拒绝回滚。
test('refuses rollback for legacy journal entries without appliedContent', async () => {
  const adapter = createMemoryAdapter({ 'src/app.js': 'old' })
  const journalStore = createJournalStore({
    version: 1,
    cursor: 1,
    entries: [{
      changeId: 'legacy-1',
      changeSetId: 1,
      path: 'src/app.js',
      op: 'write',
      content: 'new',
      previousContent: 'old',
      previousExisted: true,
      overwritten: false
    }]
  })
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    loadChanges: async () => [],
    journalStore
  })

  const session = await orchestrator.rollback()
  assert.equal(session.state, SYNC_STATES.FAILED)
  assert.match(session.error, /legacy journal/)
  assert.equal(adapter.files.get('src/app.js'), 'old')
  assert.equal(adapter.writes.length, 0)
  assert.equal(journalStore.stored.entries.length, 1)
})

// C09：并发 rollback 也只能有一个执行，第二个不得裁剪 journal 或重复恢复。
test('rejects a concurrent rollback while the first rollback is in flight', async () => {
  const adapter = createMemoryAdapter({ 'src/app.js': 'old' })
  const journalStore = createJournalStore(undefined)
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    loadChanges: async () => [modify(1, 'src/app.js', 'old', 'new')],
    journalStore
  })
  await orchestrator.run()

  let releaseWrite
  const writeGate = new Promise(resolve => { releaseWrite = resolve })
  const baseWrite = adapter.writeTextFile
  adapter.writeTextFile = async (path, content) => {
    await writeGate
    return baseWrite(path, content)
  }

  const first = orchestrator.rollback()
  const second = orchestrator.rollback()
  releaseWrite()
  const results = await Promise.allSettled([first, second])

  assert.equal(results[0].status, 'fulfilled')
  assert.equal(results[1].status, 'rejected')
  assert.match(String(results[1].reason?.message), /sync operation already in progress/)
  assert.equal(adapter.files.get('src/app.js'), 'old')
  assert.equal(journalStore.stored.entries.length, 0)
})

test('rollback restores previous contents and rewinds the cursor', async () => {
  const adapter = createMemoryAdapter({ 'src/app.js': 'old' })
  const journalStore = createJournalStore(undefined)
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    loadChanges: async () => [
      modify(1, 'src/app.js', 'old', 'new'),
      { id: 'c2', changeSetId: 2, relativePath: 'src/added.js', changeType: 'add', beforeContent: null, afterContent: 'brand new' }
    ],
    journalStore
  })

  await orchestrator.run()
  const session = await orchestrator.rollback()
  assert.equal(session.state, SYNC_STATES.IDLE)
  assert.equal(adapter.files.get('src/app.js'), 'old')
  assert.equal(adapter.files.has('src/added.js'), false)
  assert.equal(journalStore.stored.entries.length, 0)
  assert.equal(journalStore.stored.cursor, 0)
})

// C09：并发 run 必须互斥，第二次调用不得重复读取、写文件或追加 journal。
// C09：并发 run 必须互斥，第二次调用不得重复读取、写文件或追加 journal。
test('rejects a concurrent run while the first run is still in flight', async () => {
  const adapter = createMemoryAdapter({ 'src/app.js': 'old' })
  const journalStore = createJournalStore(undefined)
  let releaseLoad
  const loadGate = new Promise(resolve => { releaseLoad = resolve })
  let loads = 0
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    loadChanges: async () => {
      loads += 1
      await loadGate
      return [modify(1, 'src/app.js', 'old', 'new')]
    },
    journalStore
  })

  const first = orchestrator.run()
  const second = orchestrator.run()
  releaseLoad()
  const results = await Promise.allSettled([first, second])

  assert.equal(results[0].status, 'fulfilled')
  assert.equal(results[1].status, 'rejected')
  assert.match(String(results[1].reason?.message), /sync operation already in progress/)
  assert.equal(loads, 1)
  assert.equal(adapter.writes.length, 1)
  assert.equal(journalStore.stored.entries.length, 1)
})

// C09：rollback 正在执行时，新的 run 必须被拒绝，不能同时改写同一批文件。
test('rejects run while rollback is in progress', async () => {
  const adapter = createMemoryAdapter({ 'src/app.js': 'old' })
  const journalStore = createJournalStore(undefined)
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    loadChanges: async () => [modify(1, 'src/app.js', 'old', 'new')],
    journalStore
  })
  await orchestrator.run()

  let releaseWrite
  const writeGate = new Promise(resolve => { releaseWrite = resolve })
  adapter.writeTextFile = async (path, content) => {
    await writeGate
    adapter.files.set(path, content)
    adapter.writes.push(path)
  }

  const rollback = orchestrator.rollback()
  const rerun = orchestrator.run()
  releaseWrite()
  const results = await Promise.allSettled([rollback, rerun])

  assert.equal(results[0].status, 'fulfilled')
  assert.equal(results[1].status, 'rejected')
  assert.match(String(results[1].reason?.message), /sync operation already in progress/)
})

// C09：锁必须在失败后释放，否则一次异常会让 orchestrator 永久不可用。
test('releases the operation lock after a failed run', async () => {
  const adapter = createMemoryAdapter()
  let shouldFail = true
  let loads = 0
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    loadChanges: async () => {
      loads += 1
      if (shouldFail) throw new Error('temporary feed failure')
      return []
    },
    journalStore: createJournalStore(undefined)
  })

  const failed = await orchestrator.run()
  shouldFail = false
  const retried = await orchestrator.run()

  assert.equal(failed.state, SYNC_STATES.FAILED)
  assert.equal(retried.state, SYNC_STATES.DONE)
  assert.equal(loads, 2)
})
