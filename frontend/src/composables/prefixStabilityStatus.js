const PREFIX_STATES = new Set(['not_reported', 'baseline', 'stable', 'reset', 'unavailable'])

const PREFIX_META = {
  not_reported: {
    label: '未上报前缀证据',
    detail: '当前调用没有可比较的 durable request fingerprint。'
  },
  baseline: {
    label: '建立基线',
    detail: '这是当前 task 的第一条请求前缀，尚无上一条请求可比较。'
  },
  stable: {
    label: '前缀稳定',
    detail: '当前请求保持了上一条请求的静态前缀，并只追加了后续内容。'
  },
  reset: {
    label: '前缀已重置',
    detail: '当前请求的静态前缀或历史消息前缀发生了变化。'
  },
  unavailable: {
    label: '前缀不可比较',
    detail: '历史 request evidence 不完整，无法安全计算前缀稳定性。'
  }
}

function finiteNumber(value, fallback = 0) {
  const number = Number(value)
  return Number.isFinite(number) ? Math.max(0, number) : fallback
}

function nullableFiniteNumber(value) {
  if (value == null || value === '') return null
  const number = Number(value)
  return Number.isFinite(number) ? Math.max(0, number) : null
}

function normalizeState(value) {
  return PREFIX_STATES.has(value) ? value : 'not_reported'
}

function isComparisonState(state) {
  return state === 'stable' || state === 'reset'
}

export function createPrefixStabilityState() {
  return {
    prefixState: 'not_reported',
    prefixReportedCalls: 0,
    prefixStableCalls: 0,
    prefixResetCalls: 0,
    prefixStabilityRate: null,
    prefixResetReason: '',
    prefixCommonPrefixMessages: 0,
    prefixCommonPrefixShapeChars: 0,
    prefixInputShapeChars: 0,
    prefixPreviousInputShapeChars: 0
  }
}

export function applyPrefixTelemetryEvent(target, data = {}) {
  if (!target) return target
  const state = normalizeState(data.prefixState)
  const reported = data.prefixStabilityReported === true || isComparisonState(state)

  if (reported) {
    target.prefixReportedCalls = finiteNumber(target.prefixReportedCalls) + 1
    if (data.prefixStable === true || state === 'stable') {
      target.prefixStableCalls = finiteNumber(target.prefixStableCalls) + 1
    } else {
      target.prefixResetCalls = finiteNumber(target.prefixResetCalls) + 1
    }
    target.prefixStabilityRate = target.prefixReportedCalls > 0
      ? Math.round(target.prefixStableCalls * 10000 / target.prefixReportedCalls) / 100
      : null
    target.prefixState = state
    target.prefixResetReason = typeof data.prefixResetReason === 'string' ? data.prefixResetReason : ''
  } else if (target.prefixReportedCalls === 0) {
    target.prefixState = state
  }

  target.prefixCommonPrefixMessages = finiteNumber(data.prefixCommonPrefixMessages, target.prefixCommonPrefixMessages)
  target.prefixCommonPrefixShapeChars = finiteNumber(data.prefixCommonPrefixShapeChars, target.prefixCommonPrefixShapeChars)
  target.prefixInputShapeChars = finiteNumber(data.prefixInputShapeChars, target.prefixInputShapeChars)
  target.prefixPreviousInputShapeChars = finiteNumber(data.prefixPreviousInputShapeChars, target.prefixPreviousInputShapeChars)
  return target
}

export function resolvePrefixStabilityView(stats = null, live = null) {
  const source = hasPrefixStats(stats) ? stats : (live || {})
  const state = normalizeState(source.prefixState)
  const reportedCalls = finiteNumber(source.prefixReportedCalls ?? source.prefixStabilityReportedCalls)
  const stableCalls = finiteNumber(source.prefixStableCalls)
  const resetCalls = finiteNumber(source.prefixResetCalls)
  const rawRate = nullableFiniteNumber(source.prefixStabilityRate)
  const stabilityRate = rawRate !== null
    ? Math.min(100, rawRate)
    : reportedCalls > 0 ? Math.round(stableCalls * 10000 / reportedCalls) / 100 : null
  const effectiveState = reportedCalls > 0
    ? state
    : ['baseline', 'unavailable'].includes(state) ? state : 'not_reported'
  return {
    state: effectiveState,
    ...PREFIX_META[effectiveState],
    reportedCalls,
    stableCalls,
    resetCalls,
    stabilityRate,
    showRate: reportedCalls > 0 && stabilityRate !== null,
    resetReason: typeof source.prefixResetReason === 'string' ? source.prefixResetReason : '',
    sessionScoped: !hasPrefixStats(stats)
  }
}

function hasPrefixStats(value) {
  if (value == null) return false
  const reportedCalls = finiteNumber(value.prefixReportedCalls ?? value.prefixStabilityReportedCalls)
  if (reportedCalls > 0) return true
  const state = normalizeState(value.prefixState)
  return state === 'baseline' || state === 'stable' || state === 'reset' || state === 'unavailable'
}