import assert from 'node:assert/strict'
import test from 'node:test'
import {
  applyPrefixTelemetryEvent,
  createPrefixStabilityState,
  resolvePrefixStabilityView
} from './prefixStabilityStatus.js'

test('keeps the first request as a baseline instead of counting it as stable', () => {
  const state = createPrefixStabilityState()
  applyPrefixTelemetryEvent(state, { prefixState: 'baseline', prefixStabilityReported: false })

  assert.equal(state.prefixState, 'baseline')
  assert.equal(state.prefixReportedCalls, 0)
  assert.equal(resolvePrefixStabilityView(null, state).showRate, false)
})

test('aggregates stable and reset comparisons independently from provider cache telemetry', () => {
  const state = createPrefixStabilityState()
  applyPrefixTelemetryEvent(state, {
    prefixState: 'stable',
    prefixStabilityReported: true,
    prefixStable: true,
    prefixCommonPrefixMessages: 4,
    prefixResetReason: 'append_only'
  })
  applyPrefixTelemetryEvent(state, {
    prefixState: 'reset',
    prefixStabilityReported: true,
    prefixStable: false,
    prefixResetReason: 'static_prefix_changed'
  })

  assert.equal(state.prefixReportedCalls, 2)
  assert.equal(state.prefixStableCalls, 1)
  assert.equal(state.prefixResetCalls, 1)
  assert.equal(state.prefixStabilityRate, 50)
  assert.equal(resolvePrefixStabilityView(null, state).label, '前缀已重置')
})

test('does not fall back to another session when a selected model has no prefix projection', () => {
  const view = resolvePrefixStabilityView({ prefixState: 'not_reported' }, null)

  assert.equal(view.reportedCalls, 0)
  assert.equal(view.stabilityRate, null)
  assert.equal(view.sessionScoped, true)
})

test('does not let an empty historical summary hide live prefix telemetry', () => {
  const view = resolvePrefixStabilityView(
    { prefixState: 'not_reported', prefixReportedCalls: 0, prefixStabilityRate: null },
    { prefixState: 'stable', prefixReportedCalls: 1, prefixStableCalls: 1, prefixStabilityRate: 100 }
  )

  assert.equal(view.state, 'stable')
  assert.equal(view.stabilityRate, 100)
  assert.equal(view.sessionScoped, true)
})

test('derives a rate when the historical backend omits it', () => {
  const view = resolvePrefixStabilityView({
    prefixState: 'stable',
    prefixReportedCalls: 1,
    prefixStableCalls: 1,
    prefixStabilityRate: null
  })

  assert.equal(view.stabilityRate, 100)
})

test('preserves a historical baseline or unavailable state without live telemetry', () => {
  assert.equal(resolvePrefixStabilityView({ prefixState: 'baseline', prefixReportedCalls: 0 }, null).state, 'baseline')
  assert.equal(resolvePrefixStabilityView({ prefixState: 'unavailable', prefixReportedCalls: 0 }, null).state, 'unavailable')
})
