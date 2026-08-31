import { applyPrefixTelemetryEvent, createPrefixStabilityState } from './prefixStabilityStatus.js'

const CACHE_STATUSES = new Set(['disabled', 'not_reported', 'miss', 'write_only', 'hit'])
const REPORTED_CACHE_STATUSES = new Set(['miss', 'write_only', 'hit'])

const STATUS_META = {
  disabled: {
    label: '未启用',
    detail: '当前模型配置未启用 prompt_cache_key。'
  },
  not_reported: {
    label: '供应商未返回缓存遥测',
    detail: '本次调用没有缓存读写字段，不能把 0 token 解释为未命中。'
  },
  miss: {
    label: '未命中',
    detail: '供应商返回了缓存遥测，但本次没有读取或写入缓存。'
  },
  write_only: {
    label: '已写入，尚未读取',
    detail: '本次创建了缓存内容，但尚未从缓存读取可复用 token。'
  },
  hit: {
    label: '已命中',
    detail: '本次或当前会话已经读取了缓存 token。'
  }
}

function usageIdentity(data) {
  if (data.taskId == null || data.executionEpoch == null || data.iteration == null) return null
  return `task:${data.taskId}:epoch:${data.executionEpoch}:iteration:${data.iteration}`
}

function finiteNumber(value, fallback = 0) {
  const number = Number(value)
  return Number.isFinite(number) ? number : fallback
}

function normalizeStatus(value) {
  return CACHE_STATUSES.has(value) ? value : 'not_reported'
}

export function createTokenUsageState() {
  return {
    promptTokens: 0,
    completionTokens: 0,
    totalTokens: 0,
    callCount: 0,
    conversationTotal: 0,
    cachedTokens: 0,
    cacheWriteTokens: 0,
    cacheHitTokens: 0,
    cacheMissTokens: 0,
    missAwareReported: false,
    cacheStatus: 'not_reported',
    cacheTelemetryReported: false,
    cacheTelemetryCallCount: 0,
    cacheReportedPromptTokens: 0,
    cacheHitRate: null,
    estimated: false,
    taskId: null,
    executionEpoch: null,
    seenUsageEventKeys: [],
    ...createPrefixStabilityState()
  }
}

export function applyTokenUsageEvent(target, data = {}, eventKey = null) {
  if (!target) return target
  const identities = [eventKey, data.usageEventKey, usageIdentity(data)].filter(Boolean)
  if (identities.length > 0) {
    target.seenUsageEventKeys ??= []
    if (identities.some(identity => target.seenUsageEventKeys.includes(identity))) return target
    target.seenUsageEventKeys.push(...identities)
    if (target.seenUsageEventKeys.length > 256) target.seenUsageEventKeys.splice(0, target.seenUsageEventKeys.length - 256)
  }
  if (data.taskId != null) target.taskId = data.taskId
  if (data.executionEpoch != null) target.executionEpoch = data.executionEpoch
  const promptTokens = finiteNumber(data.promptTokens)
  const cachedTokens = finiteNumber(data.cachedTokens)
  const cacheWriteTokens = finiteNumber(data.cacheWriteTokens)
  const cacheHitTokens = finiteNumber(data.cacheHitTokens)
  const cacheMissTokens = finiteNumber(data.cacheMissTokens)
  const incomingStatus = normalizeStatus(data.cacheStatus)
  const telemetryReported = data.cacheTelemetryReported === true || REPORTED_CACHE_STATUSES.has(incomingStatus)

  target.promptTokens = finiteNumber(target.promptTokens) + promptTokens
  target.completionTokens = finiteNumber(target.completionTokens) + finiteNumber(data.completionTokens)
  target.totalTokens = finiteNumber(target.totalTokens) + finiteNumber(data.totalTokens)
  target.callCount = finiteNumber(target.callCount) + 1
  target.conversationTotal = data.conversationTotal == null
    ? target.totalTokens
    : finiteNumber(data.conversationTotal, target.totalTokens)
  target.cachedTokens = finiteNumber(target.cachedTokens) + cachedTokens
  target.cacheWriteTokens = finiteNumber(target.cacheWriteTokens) + cacheWriteTokens
  target.cacheHitTokens = finiteNumber(target.cacheHitTokens) + cacheHitTokens
  target.cacheMissTokens = finiteNumber(target.cacheMissTokens) + cacheMissTokens
  if (data.cacheMissTokens != null || data.cacheHitTokens != null) target.missAwareReported = true
  target.cacheTelemetryCallCount = finiteNumber(target.cacheTelemetryCallCount) + (telemetryReported ? 1 : 0)
  target.cacheReportedPromptTokens = finiteNumber(target.cacheReportedPromptTokens) + (telemetryReported ? promptTokens : 0)
  target.cacheTelemetryReported = target.cacheTelemetryCallCount > 0

  if (target.cacheTelemetryReported) {
    target.cacheStatus = target.cachedTokens > 0
      ? 'hit'
      : target.cacheWriteTokens > 0 ? 'write_only' : 'miss'
    const missAwareDenominator = target.cachedTokens + target.cacheMissTokens
    target.cacheHitRate = target.missAwareReported
      ? (missAwareDenominator > 0
        ? Math.round(target.cachedTokens * 10000 / missAwareDenominator) / 100
        : null)
      : (target.cacheReportedPromptTokens > 0
        ? Math.round(target.cachedTokens * 10000 / target.cacheReportedPromptTokens) / 100
        : null)
  } else {
    target.cacheStatus = incomingStatus
    target.cacheHitRate = null
  }
  target.estimated = data.estimated === true
  applyPrefixTelemetryEvent(target, data)
  return target
}

export function resolveCacheTelemetryScope(stats = null, model = '') {
  if (typeof model !== 'string' || model.length === 0) return stats
  const cacheByModel = stats?.cacheByModel
  if (!cacheByModel || !Object.prototype.hasOwnProperty.call(cacheByModel, model)) return stats
  return cacheByModel[model]
}

export function resolveCacheTelemetryView(stats = null, live = null) {
  const useStats = hasCacheStats(stats)
  const source = useStats ? stats : (live || {})
  const cached = finiteNumber(source.cachedTokens ?? source.totalCachedTokens)
  const write = finiteNumber(source.cacheWriteTokens ?? source.totalCacheWriteTokens)
  const prompt = finiteNumber(source.promptTokens ?? source.totalPromptTokens)

  let status = normalizeStatus(source.cacheStatus)
  if (status !== 'disabled' && status !== 'not_reported' && status !== 'miss' && cached === 0 && write === 0 && prompt === 0) {
    status = 'not_reported'
  } else if (cached === 0 && status === 'hit') {
    status = write > 0 ? 'write_only' : 'miss'
  }

  const rawHitRate = typeof source.cacheHitRate === 'number' && Number.isFinite(source.cacheHitRate)
    ? source.cacheHitRate
    : null
  const hitRate = (status === 'not_reported' || status === 'disabled')
    ? null
    : rawHitRate

  return {
    status,
    ...STATUS_META[status],
    hitRate,
    showHitRate: REPORTED_CACHE_STATUSES.has(status) && hitRate !== null,
    ledger: {
      cacheReadTokens: cached,
      cacheWriteTokens: write,
      nonCachedInputTokens: Math.max(0, prompt - cached)
    },
    sessionScoped: !useStats
  }
}

function hasCacheStats(value) {
  if (value == null) return false
  const status = normalizeStatus(value.cacheStatus)
  if (status === 'disabled' || REPORTED_CACHE_STATUSES.has(status)) return true
  const calls = finiteNumber(value.cacheTelemetryCallCount)
  const prompt = finiteNumber(value.promptTokens ?? value.totalPromptTokens)
  const total = finiteNumber(value.totalTokens)
  return calls > 0 || prompt > 0 || total > 0
}
