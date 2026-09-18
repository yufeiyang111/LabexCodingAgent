import assert from 'node:assert/strict'
import test from 'node:test'
import {
  applyTokenUsageEvent,
  createTokenUsageState,
  mergeConversationSummaries,
  resolveCacheTelemetryScope,
  resolveCacheTelemetryView
} from './cacheTelemetryStatus.js'

test('keeps disabled and not-reported telemetry distinct from a real miss', () => {
  assert.equal(resolveCacheTelemetryView({ cacheStatus: 'disabled' }).label, '未启用')
  assert.equal(resolveCacheTelemetryView({ cacheStatus: 'not_reported' }).label, '供应商未返回缓存遥测')
  assert.equal(resolveCacheTelemetryView({ cacheStatus: 'miss', cacheHitRate: 0 }).label, '未命中')
  assert.equal(resolveCacheTelemetryView({ cacheStatus: 'miss', cacheHitRate: 0 }).showHitRate, true)
})

test('falls back to live cache telemetry when historical summary is empty', () => {
  const view = resolveCacheTelemetryView(
    { cacheStatus: 'not_reported', cacheTelemetryCallCount: 0, totalPromptTokens: 0 },
    { cacheStatus: 'hit', cacheTelemetryReported: true, cachedTokens: 40, promptTokens: 100, cacheHitRate: 40 }
  )

  assert.equal(view.status, 'hit')
  assert.equal(view.hitRate, 40)
  assert.equal(view.sessionScoped, true)
})

test('applies durable token usage events without inventing a zero hit rate', () => {
  const usage = createTokenUsageState()

  applyTokenUsageEvent(usage, {
    promptTokens: 100,
    completionTokens: 20,
    totalTokens: 120,
    cacheStatus: 'not_reported',
    cacheHitRate: null,
    cachedTokens: 0
  })

  assert.equal(usage.totalTokens, 120)
  assert.equal(usage.cacheStatus, 'not_reported')
  assert.equal(usage.cacheHitRate, null)
  assert.equal(resolveCacheTelemetryView(null, usage).showHitRate, false)
})

test('retains task and execution epoch identity on projected usage', () => {
  const usage = createTokenUsageState()

  applyTokenUsageEvent(usage, {
    taskId: 71,
    executionEpoch: 4,
    promptTokens: 10,
    completionTokens: 2,
    totalTokens: 12,
    cacheStatus: 'not_reported'
  })

  assert.equal(usage.taskId, 71)
  assert.equal(usage.executionEpoch, 4)
})

test('ignores duplicate durable usage events by event or task epoch identity', () => {
  const usage = createTokenUsageState()
  const event = {
    taskId: 71,
    executionEpoch: 4,
    iteration: 2,
    promptTokens: 10,
    completionTokens: 2,
    totalTokens: 12,
    cacheStatus: 'not_reported'
  }

  applyTokenUsageEvent(usage, event, 'event-9')
  applyTokenUsageEvent(usage, event, 'event-9')
  applyTokenUsageEvent(usage, event)

  assert.equal(usage.callCount, 1)
  assert.equal(usage.totalTokens, 12)
})

test('aggregates provider-reported cache reads and writes across durable events', () => {
  const usage = createTokenUsageState()

  applyTokenUsageEvent(usage, {
    promptTokens: 200,
    completionTokens: 20,
    totalTokens: 220,
    cacheStatus: 'write_only',
    cacheTelemetryReported: true,
    cachedTokens: 0,
    cacheWriteTokens: 200
  })
  applyTokenUsageEvent(usage, {
    promptTokens: 200,
    completionTokens: 20,
    totalTokens: 220,
    cacheStatus: 'hit',
    cacheTelemetryReported: true,
    cachedTokens: 50,
    cacheWriteTokens: 0
  })
  applyTokenUsageEvent(usage, {
    promptTokens: 100,
    completionTokens: 10,
    totalTokens: 110,
    cacheStatus: 'not_reported',
    cachedTokens: 0
  })

  assert.equal(usage.cachedTokens, 50)
  assert.equal(usage.cacheWriteTokens, 200)
  assert.equal(usage.cacheTelemetryCallCount, 2)
  assert.equal(usage.cacheStatus, 'hit')
  assert.equal(usage.cacheHitRate, 12.5)
  assert.equal(resolveCacheTelemetryView(null, usage).label, '已命中')
})

test('uses miss-aware official denominator when deepseek hit/miss fields are reported', () => {
  const usage = createTokenUsageState()

  applyTokenUsageEvent(usage, {
    promptTokens: 1000,
    completionTokens: 120,
    totalTokens: 1120,
    cacheStatus: 'hit',
    cacheTelemetryReported: true,
    cachedTokens: 550,
    cacheWriteTokens: 450,
    cacheHitTokens: 550,
    cacheMissTokens: 450
  })
  applyTokenUsageEvent(usage, {
    promptTokens: 800,
    completionTokens: 90,
    totalTokens: 890,
    cacheStatus: 'write_only',
    cacheTelemetryReported: true,
    cachedTokens: 0,
    cacheWriteTokens: 800,
    cacheHitTokens: 0,
    cacheMissTokens: 800
  })

  assert.equal(usage.cachedTokens, 550)
  assert.equal(usage.cacheMissTokens, 1250)
  assert.equal(usage.cacheStatus, 'hit')
  // 550 / (550 + 1250) = 30.56 — 与 DeepSeek 官方 hit/(hit+miss) 口径一致
  assert.equal(usage.cacheHitRate, 30.56)
  const view = resolveCacheTelemetryView(null, usage)
  assert.equal(view.ledger.cacheReadTokens, 550)
  assert.equal(view.ledger.cacheWriteTokens, 1250)
  assert.equal(view.sessionScoped, true)
})

test('resolves a selected model cache telemetry scope without changing the all-model summary', () => {
  const stats = {
    cacheStatus: 'hit',
    cacheHitRate: 30,
    totalCachedTokens: 300,
    cacheByModel: {
      'gpt-5': {
        cacheStatus: 'hit',
        cacheHitRate: 80,
        totalPromptTokens: 100,
        totalCachedTokens: 80,
        totalCacheWriteTokens: 20
      },
      'qwen-max': {
        cacheStatus: 'not_reported',
        cacheHitRate: null,
        totalPromptTokens: 70,
        totalCachedTokens: 0,
        totalCacheWriteTokens: 0
      }
    }
  }

  assert.equal(resolveCacheTelemetryScope(stats, ''), stats)
  assert.equal(resolveCacheTelemetryScope(stats, 'gpt-5'), stats.cacheByModel['gpt-5'])
  assert.equal(resolveCacheTelemetryScope(stats, 'missing-model'), stats)

  const selectedView = resolveCacheTelemetryView(resolveCacheTelemetryScope(stats, 'gpt-5'))
  assert.equal(selectedView.hitRate, 80)
  assert.equal(selectedView.ledger.cacheReadTokens, 80)
  assert.equal(selectedView.ledger.cacheWriteTokens, 20)
  assert.equal(selectedView.ledger.nonCachedInputTokens, 20)
  assert.equal(resolveCacheTelemetryView(resolveCacheTelemetryScope(stats, 'qwen-max')).showHitRate, false)
})

test('builds conversation detail from the persisted server aggregate when local history is empty', () => {
  const merged = mergeConversationSummaries([], [
    { conversationId: 'conv-a', title: '会话 A', promptTokens: 900, completionTokens: 100, totalTokens: 1000, callCount: 7 }
  ])

  assert.deepEqual(merged, [
    { conversationId: 'conv-a', title: '会话 A', promptTokens: 900, completionTokens: 100, totalTokens: 1000, callCount: 7 }
  ])
})

test('keeps the larger value per field so a snapshot never lowers live counters', () => {
  // 快照来自后端聚合，SSE 累积来自本次连接；两者对同一会话取较大值，
  // 既不让快照覆盖窗口内的新事件，也不让实时值回退掉历史累计。
  const merged = mergeConversationSummaries(
    [{ conversationId: 'conv-a', title: '本地标题', promptTokens: 1200, completionTokens: 300, totalTokens: 1500, callCount: 9 }],
    [{ conversationId: 'conv-a', title: '服务端标题', promptTokens: 900, completionTokens: 100, totalTokens: 1000, callCount: 7 }]
  )

  assert.equal(merged.length, 1)
  assert.equal(merged[0].promptTokens, 1200)
  assert.equal(merged[0].totalTokens, 1500)
  assert.equal(merged[0].callCount, 9)
  // 服务端给了标题就优先用它（本地标题来自会话名快照，可能为空）。
  assert.equal(merged[0].title, '服务端标题')
})

test('falls back to the local title when the persisted row has none', () => {
  const merged = mergeConversationSummaries(
    [{ conversationId: 'conv-a', title: '本地标题', totalTokens: 10, callCount: 1 }],
    [{ conversationId: 'conv-a', title: null, totalTokens: 10, callCount: 1 }]
  )

  assert.equal(merged[0].title, '本地标题')
})

test('keeps conversations the server has not persisted yet', () => {
  // 新建会话已产生 SSE 用量但聚合行尚未可见时，不能被快照抹掉。
  const merged = mergeConversationSummaries(
    [{ conversationId: 'conv-new', title: '新会话', totalTokens: 40, callCount: 2 }],
    [{ conversationId: 'conv-old', title: '历史会话', totalTokens: 500, callCount: 5 }]
  )

  assert.deepEqual(merged.map(item => item.conversationId), ['conv-old', 'conv-new'])
})

test('keeps the local list untouched when the summary request fails or returns no data', () => {
  const local = [{ conversationId: 'conv-a', totalTokens: 50, callCount: 1 }]

  assert.equal(mergeConversationSummaries(local, null), local)
  assert.equal(mergeConversationSummaries(local, undefined), local)
  assert.equal(mergeConversationSummaries(local, 'not-an-array'), local)
})

test('drops rows without a conversation id and de-duplicates repeats', () => {
  const merged = mergeConversationSummaries([], [
    { conversationId: '', totalTokens: 1 },
    { conversationId: null, totalTokens: 2 },
    { conversationId: 'conv-a', totalTokens: 3, callCount: 1 },
    { conversationId: 'conv-a', totalTokens: 3, callCount: 1 }
  ])

  assert.deepEqual(merged.map(item => item.conversationId), ['conv-a'])
})

test('normalizes missing numeric fields to zero instead of NaN', () => {
  const merged = mergeConversationSummaries([], [{ conversationId: 'conv-a' }])

  assert.deepEqual(merged, [
    { conversationId: 'conv-a', title: null, promptTokens: 0, completionTokens: 0, totalTokens: 0, callCount: 0 }
  ])
})
