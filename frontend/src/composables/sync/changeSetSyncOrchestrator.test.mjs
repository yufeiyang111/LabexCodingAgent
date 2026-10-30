import assert from 'node:assert/strict'
import test from 'node:test'

import { createChangeSetSyncOrchestrator } from './changeSetSyncOrchestrator.js'
import { CONFLICT_POLICIES } from './changeSetSyncPlan.js'
import { SYNC_STATES } from './syncSessionState.js'
import { createJournalStore, createMemoryAdapter, modify } from './syncTestDoubles.mjs'

test('completes with an empty plan when no changes are pending', async () => {
  const adapter = createMemoryAdapter()
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    loadChanges: async () => [],
    journalStore: createJournalStore(undefined)
  })
  const session = await orchestrator.run()
  assert.equal(session.state, SYNC_STATES.DONE)
  assert.equal(session.applied, 0)
  assert.equal(adapter.writes.length, 0)
})

test('applies pending changes and persists the journal per step', async () => {
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

  const session = await orchestrator.run()
  assert.equal(session.state, SYNC_STATES.DONE)
  assert.equal(session.applied, 2)
  assert.equal(session.cursor, 2)
  assert.equal(adapter.files.get('src/app.js'), 'new')
  assert.equal(adapter.files.get('src/added.js'), 'brand new')
  assert.equal(journalStore.stored.entries.length, 2)
  assert.equal(journalStore.stored.cursor, 2)
})

test('blocks in strict policy without touching the working directory', async () => {
  const adapter = createMemoryAdapter({ 'src/app.js': 'locally edited' })
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    loadChanges: async () => [modify(1, 'src/app.js', 'old', 'new')],
    journalStore: createJournalStore(undefined)
  })

  const session = await orchestrator.run({ policy: CONFLICT_POLICIES.STRICT })
  assert.equal(session.state, SYNC_STATES.BLOCKED)
  assert.equal(session.conflicts.length, 1)
  assert.equal(adapter.writes.length, 0)
  assert.equal(adapter.files.get('src/app.js'), 'locally edited')
})

test('skip policy applies the safe subset and reports conflicts', async () => {
  const adapter = createMemoryAdapter({ 'src/app.js': 'locally edited', 'src/other.js': 'old' })
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    loadChanges: async () => [
      modify(1, 'src/app.js', 'old', 'new'),
      modify(2, 'src/other.js', 'old', 'updated')
    ],
    journalStore: createJournalStore(undefined)
  })

  const session = await orchestrator.run({ policy: CONFLICT_POLICIES.SKIP })
  assert.equal(session.state, SYNC_STATES.DONE)
  assert.equal(session.applied, 1)
  assert.equal(session.conflicts.length, 1)
  assert.equal(adapter.files.get('src/other.js'), 'updated')
  assert.equal(adapter.files.get('src/app.js'), 'locally edited')
})

// C05：只要批内任一探针 unknown，整次同步必须阻断且零写入。
test('blocks the entire run when any probe is unknown and writes nothing', async () => {
  const adapter = createMemoryAdapter({ 'src/a.js': 'old', 'src/b.js': 'old' })
  adapter.fileExists = async path => {
    if (path === 'src/b.js') throw new Error('permission denied')
    return adapter.files.has(path)
  }
  const journalStore = createJournalStore(undefined)
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    loadChanges: async () => [
      modify(1, 'src/a.js', 'old', 'a-new'),
      modify(2, 'src/b.js', 'old', 'b-new')
    ],
    journalStore
  })

  const session = await orchestrator.run()
  assert.equal(session.state, SYNC_STATES.BLOCKED)
  assert.equal(session.applied, 0)
  assert.equal(adapter.writes.length, 0)
  assert.equal(adapter.files.get('src/a.js'), 'old')
  assert.equal(adapter.files.get('src/b.js'), 'old')
  assert.equal(journalStore.stored, undefined)
  assert.equal(session.conflicts.some(item => item.reason === 'probe-unknown'), true)
})

// C05：overwrite 只允许覆盖“已知且已确认”的本地漂移，不能绕过 unknown probe。
test('unknown probe remains blocked under overwrite policy', async () => {
  const adapter = createMemoryAdapter({ 'src/app.js': 'locally edited' })
  adapter.fileExists = async () => { throw new Error('permission denied') }
  const journalStore = createJournalStore(undefined)
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    loadChanges: async () => [modify(1, 'src/app.js', 'old', 'new')],
    journalStore
  })

  const session = await orchestrator.run({ policy: CONFLICT_POLICIES.OVERWRITE })
  assert.equal(session.state, SYNC_STATES.BLOCKED)
  assert.equal(adapter.writes.length, 0)
  assert.equal(adapter.files.get('src/app.js'), 'locally edited')
  assert.equal(session.conflicts[0].reason, 'probe-unknown')
})

// C08：扫描阶段的加载异常必须收敛到 failed，不能把会话卡在 scanning。
test('moves to failed when change loading throws during scanning', async () => {
  const adapter = createMemoryAdapter()
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    loadChanges: async () => { throw new Error('feed unavailable') },
    journalStore: createJournalStore(undefined)
  })

  const session = await orchestrator.run()
  assert.equal(session.state, SYNC_STATES.FAILED)
  assert.match(session.error, /feed unavailable/)
  assert.equal(adapter.writes.length, 0)
})

// C08：非法回滚不得留下“已完成”或“空闲”等误导状态。
test('rejects rollback without a recoverable journal and does not enter rolling-back', async () => {
  const adapter = createMemoryAdapter()
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    loadChanges: async () => [],
    journalStore: createJournalStore(undefined)
  })

  const session = await orchestrator.rollback()
  assert.equal(session.state, SYNC_STATES.IDLE)
  assert.equal(adapter.writes.length, 0)
})

// C08：done 后再次扫描是合法重试，不能因状态机过严而卡死。
test('can start a fresh scan after a completed run', async () => {
  const adapter = createMemoryAdapter()
  let loads = 0
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    loadChanges: async () => { loads += 1; return [] },
    journalStore: createJournalStore(undefined)
  })

  const first = await orchestrator.run()
  const second = await orchestrator.run()
  assert.equal(first.state, SYNC_STATES.DONE)
  assert.equal(second.state, SYNC_STATES.DONE)
  assert.equal(loads, 2)
})

test('marks the session failed on write error while keeping applied steps reversible', async () => {
  const adapter = createMemoryAdapter({ 'src/a.js': 'a-old', 'src/b.js': 'b-old' }, { failOnWrite: 'src/b.js' })
  const journalStore = createJournalStore(undefined)
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    loadChanges: async () => [
      modify(1, 'src/a.js', 'a-old', 'a-new'),
      modify(2, 'src/b.js', 'b-old', 'b-new')
    ],
    journalStore
  })

  const session = await orchestrator.run()
  assert.equal(session.state, SYNC_STATES.FAILED)
  assert.equal(session.applied, 1)
  assert.match(session.error, /disk full/)
  assert.equal(adapter.files.get('src/a.js'), 'a-new')
  assert.equal(journalStore.stored.entries.length, 1)
})

test('emits progress states in order', async () => {
  const adapter = createMemoryAdapter({ 'src/app.js': 'old' })
  const seen = []
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    loadChanges: async () => [modify(1, 'src/app.js', 'old', 'new')],
    journalStore: createJournalStore(undefined),
    onProgress: event => seen.push(event.session.state)
  })

  await orchestrator.run()
  // scanning ×3：进入状态、读到游标、拿到变更总数；planning ×2：进入状态、产出计划；
  // applying ×2：进入状态、完成第 1 步；done ×1：终态
  assert.deepEqual(seen, [
    SYNC_STATES.SCANNING,
    SYNC_STATES.SCANNING,
    SYNC_STATES.SCANNING,
    SYNC_STATES.PLANNING,
    SYNC_STATES.PLANNING,
    SYNC_STATES.APPLYING,
    SYNC_STATES.APPLYING,
    SYNC_STATES.DONE
  ])
})

test('requires loadChanges and adapter at construction', () => {
  assert.throws(() => createChangeSetSyncOrchestrator({}), /loadChanges/)
  assert.throws(() => createChangeSetSyncOrchestrator({ loadChanges: async () => [] }), /directory adapter/)
})

// C06 回归：journal 是回退的唯一依据，不允许无操作降级。
// 旧实现内置 { save(){} } 降级：文件已改完、恢复记录从未落盘，却仍然报成功。
test('refuses to construct without an explicit journal store and never writes before failing', async () => {
  const adapter = createMemoryAdapter({ 'src/app.js': 'old' })
  const base = { adapter, loadChanges: async () => [modify(1, 'src/app.js', 'old', 'new')] }

  assert.throws(() => createChangeSetSyncOrchestrator(base), /journalStore/)
  assert.throws(() => createChangeSetSyncOrchestrator({ ...base, journalStore: {} }), /journalStore/)
  // 只有 load()（半成品依赖）同样必须拒绝：读得到、写不进去，等于没有恢复依据。
  assert.throws(
    () => createChangeSetSyncOrchestrator({ ...base, journalStore: { async load() { return undefined } } }),
    /journalStore/
  )
  assert.throws(
    () => createChangeSetSyncOrchestrator({ ...base, journalStore: { async save() {} } }),
    /journalStore/
  )

  // 构造失败发生在任何文件写入之前。
  assert.equal(adapter.writes.length, 0)
  assert.equal(adapter.files.get('src/app.js'), 'old')
})

// C12：journal 超过条目或字节上限时必须失败，不能继续写盘。
test('fails before saving a journal that exceeds the entry limit', async () => {
  const adapter = createMemoryAdapter({ 'src/a.js': 'old', 'src/b.js': 'old' })
  const journalStore = createJournalStore(undefined)
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    loadChanges: async () => [
      modify(1, 'src/a.js', 'old', 'a-new'),
      modify(2, 'src/b.js', 'old', 'b-new')
    ],
    journalStore,
    limits: { maxJournalEntries: 1 }
  })

  const session = await orchestrator.run()
  assert.equal(session.state, SYNC_STATES.FAILED)
  assert.match(session.error, /journal entry limit exceeded/)
  assert.equal(adapter.writes.length, 0)
  assert.equal(adapter.files.get('src/a.js'), 'old')
  assert.equal(adapter.files.get('src/b.js'), 'old')
  assert.equal(journalStore.stored, undefined)
})

test('fails before saving a journal that exceeds the byte limit', async () => {
  const adapter = createMemoryAdapter({ 'src/a.js': 'old' })
  const journalStore = createJournalStore(undefined)
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    loadChanges: async () => [modify(1, 'src/a.js', 'old', 'new')],
    journalStore,
    limits: { maxJournalBytes: 1 }
  })

  const session = await orchestrator.run()
  assert.equal(session.state, SYNC_STATES.FAILED)
  assert.match(session.error, /journal size limit exceeded/)
  assert.equal(adapter.writes.length, 0)
  assert.equal(adapter.files.get('src/a.js'), 'old')
  assert.equal(journalStore.stored, undefined)
})

test('surfaces a journal save failure instead of reporting a successful sync', async () => {
  const adapter = createMemoryAdapter({ 'src/app.js': 'old' })
  let writeAttempts = 0
  const failingStore = {
    async load() { return undefined },
    async save() { throw new Error('quota exceeded') }
  }
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter: {
      ...adapter,
      async writeTextFile(path, content) {
        writeAttempts += 1
        return adapter.writeTextFile(path, content)
      }
    },
    loadChanges: async () => [modify(1, 'src/app.js', 'old', 'new')],
    journalStore: failingStore
  })

  const session = await orchestrator.run()
  assert.equal(session.state, SYNC_STATES.FAILED)
  assert.match(session.error, /quota exceeded/)
  // 关键：不得声称任何一条已应用（恢复记录没落盘，客户端必须自己决定如何处置）。
  assert.equal(session.applied, 0)
})

// C03（应用侧）：游标只能停到"完整落地的 change-set"。同一集合里第一个文件成功、
// 第二个失败时，旧实现会把游标推进到整个 changeSetId，重试就会永久跳过第二个文件。
test('does not advance the cursor past a change set that was only half applied', async () => {
  const adapter = createMemoryAdapter({ 'src/a.js': 'a-old', 'src/b.js': 'b-old' }, { failOnWrite: 'src/b.js' })
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    loadChanges: async () => [
      { id: 'a1', changeSetId: 7, relativePath: 'src/a.js', changeType: 'modify', beforeContent: 'a-old', afterContent: 'a-new' },
      { id: 'a2', changeSetId: 7, relativePath: 'src/b.js', changeType: 'modify', beforeContent: 'b-old', afterContent: 'b-new' }
    ],
    journalStore: createJournalStore(undefined)
  })

  const session = await orchestrator.run()
  assert.equal(session.state, SYNC_STATES.FAILED)
  assert.equal(session.applied, 1)
  assert.equal(session.cursor, 0, '集合只落地了一半，游标必须停在它之前（否则重试会跳过 src/b.js）')
})

test('advances the cursor to the last fully applied change set', async () => {
  const adapter = createMemoryAdapter({ 'src/a.js': 'a-old', 'src/b.js': 'b-old' })
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    loadChanges: async () => [
      { id: 's1', changeSetId: 7, relativePath: 'src/a.js', changeType: 'modify', beforeContent: 'a-old', afterContent: 'a-new' },
      { id: 's2', changeSetId: 7, relativePath: 'src/b.js', changeType: 'modify', beforeContent: 'b-old', afterContent: 'b-new' },
      { id: 's3', changeSetId: 8, relativePath: 'src/c.js', changeType: 'create', beforeContent: '', afterContent: 'c-new' }
    ],
    journalStore: createJournalStore(undefined)
  })

  const session = await orchestrator.run()
  assert.equal(session.state, SYNC_STATES.DONE)
  assert.equal(session.applied, 3)
  assert.equal(session.cursor, 8)
})

// 接线路径：loadChanges 直接返回服务端 feed 形态（changeSets[].files[]），
// orchestrator 不需要额外的适配层就能规划——这是把前端切到 /changes/feed 的最小前提。
test('consumes the server feed shape (changeSets[].files[]) without an adapter layer', async () => {
  const adapter = createMemoryAdapter({ 'src/app.js': 'old' })
  const journalStore = createJournalStore(undefined)
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter,
    loadChanges: async () => ({
      changeSets: [{
        changeSetId: 11,
        taskId: 1,
        status: 'pending',
        updateTime: '2026-10-06T10:00:00',
        files: [{
          changeId: 'f1',
          changeSetId: 11,
          relativePath: 'src/app.js',
          changeType: 'modify',
          status: 'pending',
          beforeExisted: true,
          afterExisted: true,
          beforeTruncated: false,
          afterTruncated: false,
          beforeContent: 'old',
          afterContent: 'new',
          updateTime: '2026-10-06T10:00:00'
        }]
      }],
      changedFiles: [],
      nextCursor: { afterChangeSetId: 11, tailUpdateTime: '2026-10-06T10:00:00', tailChangeId: '' },
      hasMore: false
    }),
    journalStore
  })

  const session = await orchestrator.run()
  assert.equal(session.state, SYNC_STATES.DONE)
  assert.equal(session.cursor, 11)
  assert.equal(adapter.files.get('src/app.js'), 'new')
  assert.equal(journalStore.stored.entries.length, 1)
})
