import assert from 'node:assert/strict'
import test from 'node:test'
import {
  applyTokenUsageEvent,
  createTokenUsageState,
  resolveCacheTelemetryScope,
  resolveCacheTelemetryView
} from './cacheTelemetryStatus.js'

test('keeps disabled and not-reported telemetry distinct from a real miss', () => {
  assert.equal(resolveCacheTelemetryView({ cacheStatus: 'disabled' }).label, '未启用')
  assert.equal(resolveCacheTelemetryView({ cacheStatus: 'not_reported' }).label, '供应商未返回缓存遥测')
  assert.equal(resolveCacheTelemetryView({ cacheStatus: 'miss', cacheHitRate: 0 }).label, '未命中')
  assert.equal(resolveCacheTelemetryView({ cacheStatus: 'miss', cacheHitRate: 0 }).showHitRate, true)
})

test('applies durable token usage events without inventing a zero hit rate', () => {
  const usage = createTokenUsageState()

  applyTokenUsageEvent(usage, {
    promptTokens: 100,
    completionTokens: 20,
    totalTokens: 120,
    conversationTotal: 120,
    cacheStatus: 'not_reported',
    cacheHitRate: null,
    cachedTokens: 0
  })

  assert.equal(usage.totalTokens, 120)
  assert.equal(usage.cacheStatus, 'not_reported')
  assert.equal(usage.cacheHitRate, null)
  assert.equal(resolveCacheTelemetryView(null, usage).showHitRate, false)
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
