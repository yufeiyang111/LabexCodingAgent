import assert from 'node:assert/strict'
import test from 'node:test'

import { createJournal, recordApplied, updateJournalCursor } from './syncJournal.js'
import {
  JOURNAL_CURSOR_STORE,
  JOURNAL_ENTRY_STORE,
  JOURNAL_STORE_NAME,
  clearJournal,
  loadJournal,
  saveJournal
} from './journalStore.js'

// journal 增量存储：每次提交只写"新增的条目"，而不是把整个 journal 重写一遍。
//
// 为什么必须固定这一点：旧实现是 O(n²)。实测 500 个 4KB 文件（内容 2MB）
// 累计写 66.4MB，放大了 33 倍；10 万文件时累计 2.4TB。这不是"慢一点"，
// 是把浏览器的 IndexedDB 配额和寿命直接烧掉。

function journalWith(count, { cursor = count } = {}) {
  let journal = createJournal()
  for (let index = 0; index < count; index += 1) {
    journal = recordApplied(journal, {
      changeId: `c${index}`,
      changeSetId: 1,
      path: `src/f${index}.js`,
      op: 'write',
      content: `body-${index}`,
      previousContent: null,
      previousExisted: false
    })
  }
  return updateJournalCursor(journal, {
    afterChangeSetId: cursor,
    tailUpdateTime: '2026-11-06T10:00:00',
    tailChangeId: ''
  })
}

/** 记录每个 store 上发生了多少次写入（put/delete）。 */
function createCountingIndexedDb() {
  const stores = new Map()
  const writes = []
  function stateOf(name) {
    if (!stores.has(name)) stores.set(name, new Map())
    return stores.get(name)
  }
  function request(result) {
    const req = { result, error: null, onsuccess: null, onerror: null }
    queueMicrotask(() => req.onsuccess?.())
    return req
  }
  function makeStore(name) {
    return {
      get(key) { return request(stateOf(name).get(key)) },
      getAll(range) {
        const map = stateOf(name)
        const keys = [...map.keys()].sort()
        const filtered = range ? keys.filter(key => key >= range.lower && key <= range.upper) : keys
        return request(filtered.map(key => map.get(key)))
      },
      put(value, key) {
        writes.push(['put', name, key])
        stateOf(name).set(key, value)
        return request(undefined)
      },
      delete(key) {
        writes.push(['delete', name, key])
        stateOf(name).delete(key)
        return request(undefined)
      }
    }
  }
  const transaction = { objectStore: makeStore, oncomplete: null, onerror: null, onabort: null }
  const database = {
    objectStoreNames: { contains() { return true } },
    transaction() {
      queueMicrotask(() => transaction.oncomplete?.())
      return transaction
    },
    close() {}
  }
  return {
    writes,
    dump: stateOf,
    open() {
      const openRequest = { result: database, onupgradeneeded: null, onsuccess: null, onerror: null }
      queueMicrotask(() => openRequest.onsuccess?.())
      return openRequest
    }
  }
}

test('a second save only writes the entries that were added since the last one', async () => {
  const indexedDB = createCountingIndexedDb()

  await saveJournal('p1', journalWith(3), { indexedDB })
  const afterFirst = indexedDB.writes.filter(([op, name]) => op === 'put' && name === JOURNAL_ENTRY_STORE).length
  assert.equal(afterFirst, 3, '首次应写 3 条 entry')

  indexedDB.writes.length = 0
  await saveJournal('p1', journalWith(5), { indexedDB })
  const afterSecond = indexedDB.writes.filter(([op, name]) => op === 'put' && name === JOURNAL_ENTRY_STORE).length
  assert.equal(afterSecond, 2, `第二次只应写新增的 2 条，实际 ${afterSecond}`)

  // 游标每次都写（一条小记录，与条目数无关）。
  const cursorWrites = indexedDB.writes.filter(([op, name]) => op === 'put' && name === JOURNAL_CURSOR_STORE).length
  assert.equal(cursorWrites, 1)
})

test('total bytes written grow linearly, not quadratically', async () => {
  // 同一条 journal 序列，分别按「增量写」与「整份重写」累计字节数。
  // 8 次提交、每次 5 条 → 增量应写 40 条；整份重写会写 5+10+…+40 = 180 条。
  const body = 'x'.repeat(1024)
  const indexedDB = createCountingIndexedDb()
  let journal = createJournal()
  const snapshots = []

  for (let step = 1; step <= 40; step += 1) {
    journal = recordApplied(journal, {
      changeId: `c${step}`,
      changeSetId: 1,
      path: `src/f${step}.js`,
      op: 'write',
      content: body,
      previousContent: null,
      previousExisted: false
    })
    if (step % 5 !== 0) continue
    snapshots.push(journal)
    indexedDB.writes.length = 0
    await saveJournal('p1', journal, { indexedDB })
    let written = 0
    for (const [op, name, key] of indexedDB.writes) {
      if (op !== 'put') continue
      written += JSON.stringify(indexedDB.dump(name).get(key) ?? '').length
    }
    snapshots[snapshots.length - 1] = { journal, written }
  }

  const incrementalBytes = snapshots.reduce((sum, item) => sum + item.written, 0)
  const fullRewriteBytes = snapshots.reduce((sum, item) => sum + JSON.stringify(item.journal).length, 0)

  // 整份重写是 O(n²)：180 条 vs 40 条，约 4.5 倍。给足余量，只断言"明显更少"。
  assert.ok(incrementalBytes < fullRewriteBytes / 2,
    `增量写入 ${incrementalBytes} 应远小于整份重写 ${fullRewriteBytes}`)
})

test('rolling back entries shrinks the stored journal instead of leaving orphans', async () => {
  const indexedDB = createCountingIndexedDb()
  await saveJournal('p1', journalWith(5), { indexedDB })
  // 回退 2 条：journal 只剩 3 条
  await saveJournal('p1', journalWith(3), { indexedDB })

  const loaded = await loadJournal('p1', { indexedDB })
  assert.equal(loaded.entries.length, 3, '已撤销的条目不能被读回来')
  assert.equal(indexedDB.dump(JOURNAL_ENTRY_STORE).size, 3, 'store 里不能留孤儿记录')
})

test('entries are scoped per project even though they share one store', async () => {
  const indexedDB = createCountingIndexedDb()
  await saveJournal('p1', journalWith(2), { indexedDB })
  await saveJournal('p2', journalWith(4), { indexedDB })

  const first = await loadJournal('p1', { indexedDB })
  const second = await loadJournal('p2', { indexedDB })
  assert.equal(first.entries.length, 2)
  assert.equal(second.entries.length, 4)
  assert.equal(first.entries[0].path, 'src/f0.js')
})

test('a legacy whole-object journal is migrated once and then removed', async () => {
  const indexedDB = createCountingIndexedDb()
  const legacy = journalWith(3)
  // 旧布局：整个 journal 一条记录放在 sync-journals
  indexedDB.dump(JOURNAL_STORE_NAME).set('p1', legacy)

  const loaded = await loadJournal('p1', { indexedDB })
  assert.equal(loaded.entries.length, 3)
  assert.equal(loaded.changeSetCursor.afterChangeSetId, 3)

  // 迁移后旧记录必须删掉，否则每次 load 都会重复迁移。
  assert.equal(indexedDB.dump(JOURNAL_STORE_NAME).has('p1'), false)
  // 新布局已有数据
  assert.equal(indexedDB.dump(JOURNAL_ENTRY_STORE).size, 3)
})

test('clearing removes every entry of that project and nothing else', async () => {
  const indexedDB = createCountingIndexedDb()
  await saveJournal('p1', journalWith(3), { indexedDB })
  await saveJournal('p2', journalWith(2), { indexedDB })

  await clearJournal('p1', { indexedDB })
  assert.equal(await loadJournal('p1', { indexedDB }), null)
  assert.equal((await loadJournal('p2', { indexedDB })).entries.length, 2)
})
