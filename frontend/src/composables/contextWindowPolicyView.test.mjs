import assert from 'node:assert/strict'
import test from 'node:test'

import { calculateContextWindowPolicy } from './contextWindowPolicyView.js'

test('matches backend effective soft limit when output reservation constrains the input', () => {
  const policy = calculateContextWindowPolicy({
    contextWindowTokens: 204800,
    maxTokens: 51200,
    compactionThresholdPercent: 90,
    compactionReservedTokens: null
  })

  assert.equal(policy.valid, true)
  assert.equal(policy.inputCapacityTokens, 153600)
  assert.equal(policy.reservedTokens, 8192)
  assert.equal(policy.thresholdLimitTokens, 184320)
  assert.equal(policy.softLimitTokens, 153600)
  assert.equal(policy.softLimitPercent, 75)
})

test('uses explicit reserve and threshold limits without exceeding input capacity', () => {
  const policy = calculateContextWindowPolicy({
    contextWindowTokens: 40000,
    maxTokens: 8000,
    compactionThresholdPercent: 80,
    compactionReservedTokens: 2000
  })

  assert.equal(policy.inputCapacityTokens, 32000)
  assert.equal(policy.reservedTokens, 2000)
  assert.equal(policy.thresholdLimitTokens, 32000)
  assert.equal(policy.softLimitTokens, 32000)
  assert.equal(policy.softLimitPercent, 80)
})

test('reports an invalid preview until both window and output capacity are valid', () => {
  const policy = calculateContextWindowPolicy({ contextWindowTokens: 8000, maxTokens: 8000 })

  assert.equal(policy.valid, false)
  assert.equal(policy.softLimitTokens, 0)
  assert.match(policy.reason, /大于 Max Tokens/)
})
