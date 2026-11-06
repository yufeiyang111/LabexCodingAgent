// journal 写入成本的**基线对照**（Node，整份重写的替身 store）。
//
// 用途：量化"如果每次提交都重写整个 journal"会写多少字节——也就是 2026-11-06 之前的实现。
// 生产路径已经不是这样了（journalStore.js 改成按条目增量写），
// 真实数字看 sync-journal-browser-bench.mjs（真实 Chromium + 真实 IndexedDB）。
//
// 为什么保留它：改动的收益需要一个可复现的对照基线，否则"从 66.4MB 降到 2.09MB"
// 只是两个孤立的数字。
import { performance } from 'node:perf_hooks'

import { createChangeSetSyncOrchestrator } from '../../src/composables/sync/changeSetSyncOrchestrator.js'
import { createJournalStore, createMemoryAdapter } from '../../src/composables/sync/syncTestDoubles.mjs'

const BODY = 'x'.repeat(4096)

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
        afterContent: BODY,
        updateTime: '2026-11-06T10:00:00'
      }))
    }],
    changedFiles: [],
    nextCursor: { afterChangeSetId: 1, tailUpdateTime: '2026-11-06T10:00:01', tailChangeId: '' },
    hasMore: false
  }
}

/** 记录每次 save 序列化出来的字节数：这就是"累计写入量"。 */
function measuringStore() {
  const inner = createJournalStore(undefined)
  const sizes = []
  return {
    sizes,
    get stored() { return inner.stored },
    async load() { return inner.load() },
    async save(journal) {
      sizes.push(JSON.stringify(journal).length)
      return inner.save(journal)
    }
  }
}

console.log('文件数\t耗时ms\t提交次数\t累计写入MB\t峰值单次MB')
for (const count of [100, 500, 1000, 2000, 4000]) {
  const store = measuringStore()
  const orchestrator = createChangeSetSyncOrchestrator({
    adapter: createMemoryAdapter({}),
    journalStore: store,
    loadChanges: async () => feedOf(count)
  })
  const started = performance.now()
  const session = await orchestrator.run()
  const elapsed = performance.now() - started
  if (session.state !== 'done') {
    console.log(`${count}\t${elapsed.toFixed(0)}\tFAILED: ${session.error}`)
    continue
  }
  const totalBytes = store.sizes.reduce((sum, n) => sum + n, 0)
  const peak = Math.max(...store.sizes)
  console.log([
    count,
    elapsed.toFixed(0),
    store.sizes.length,
    (totalBytes / 1024 / 1024).toFixed(1),
    (peak / 1024 / 1024).toFixed(1)
  ].join('\t'))
}
