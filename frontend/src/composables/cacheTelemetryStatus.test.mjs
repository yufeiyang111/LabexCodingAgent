import assert from 'node:assert/strict'
import test from 'node:test'
import {
  applyTokenUsageEvent,
  createTokenUsageState,
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
