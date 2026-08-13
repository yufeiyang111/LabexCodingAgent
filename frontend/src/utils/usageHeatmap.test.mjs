import assert from 'node:assert/strict'
import test from 'node:test'

import {
  createUsageHeatmapData,
  formatUsageTokenCount,
  getRecentSixMonthRange
} from './usageHeatmap.js'

test('creates an inclusive six-month date window with clamped month boundaries', () => {
  const { start, end } = getRecentSixMonthRange(new Date('2026-08-31T18:30:00+08:00'))

  assert.equal(start.toISOString().slice(0, 10), '2026-02-28')
  assert.equal(end.toISOString().slice(0, 10), '2026-08-31')
})

test('fills missing dates with zero and keeps valid daily totals', () => {
  const result = createUsageHeatmapData({
    '2026-08-05': 12000,
    '2026-08-06': 0,
    '2026-08-07': -10,
    '2026-08-08': 'not-a-number',
    '2026-02-30': 5000
  }, new Date('2026-08-08T12:00:00Z'))

  assert.equal(result.startDate, '2026-02-08')
  assert.equal(result.endDate, '2026-08-08')
  assert.equal(result.data.length, 182)
  assert.deepEqual(result.data.find(([date]) => date === '2026-08-05'), ['2026-08-05', 12000])
  assert.deepEqual(result.data.find(([date]) => date === '2026-08-06'), ['2026-08-06', 0])
  assert.equal(result.max, 12000)
  assert.equal(result.hasData, true)
})

test('returns an empty usage state for missing or invalid values', () => {
  const result = createUsageHeatmapData({
    '2026-08-05': 0,
    '2026-08-06': -1,
    'not-a-date': 500
  }, new Date('2026-08-08T12:00:00Z'))

  assert.equal(result.hasData, false)
  assert.equal(result.max, 0)
  assert.ok(result.data.every(([, value]) => value === 0))
})

test('formats token totals consistently with the usage summary', () => {
  assert.equal(formatUsageTokenCount(900), '900')
  assert.equal(formatUsageTokenCount(1200), '1.2K')
  assert.equal(formatUsageTokenCount(1200000), '1.2M')
  assert.equal(formatUsageTokenCount('invalid'), '0')
})
