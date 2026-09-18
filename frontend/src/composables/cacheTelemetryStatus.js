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

function toSummary(conversationId, title, source) {
  return {
    conversationId,
    title: title || null,
    promptTokens: finiteNumber(source?.promptTokens),
    completionTokens: finiteNumber(source?.completionTokens),
    totalTokens: finiteNumber(source?.totalTokens),
    callCount: finiteNumber(source?.callCount)
  }
}

/**
 * 合并「会话明细」的两路来源，供用量面板展示。
 *
 * <p>权威来源是后端按 conversation_id 聚合的持久化列表（刷新/换设备后仍在）；
 * 实时来源是本次连接 SSE 累积的会话（后端聚合尚未落库的窗口内数据）。合并规则：
 * 逐字段取较大值，既不让快照覆盖窗口内的新事件，也不让实时值回退掉历史累计。
 * 后端未返回但本地已有的会话会保留（新建会话尚未产生持久化行的场景）。
 *
 * @param existing 本地已累积的会话列表（可为空）
 * @param summaries 后端返回的聚合列表（可为空/null，此时保持 existing）
 * @returns 新的会话列表，按后端给出的顺序（最近使用倒序）在前
 */
export function mergeConversationSummaries(existing = [], summaries = null) {
  const current = Array.isArray(existing) ? existing : []
  if (!Array.isArray(summaries)) return current
  const existingById = new Map()
  for (const item of current) {
    if (item?.conversationId) existingById.set(item.conversationId, item)
  }
  const merged = []
  const seen = new Set()
  for (const summary of summaries) {
    const conversationId = summary?.conversationId
    if (!conversationId || seen.has(conversationId)) continue
    seen.add(conversationId)
    const local = existingById.get(conversationId)
    if (!local) {
      merged.push(toSummary(conversationId, summary.title, summary))
      continue
    }
    const persisted = toSummary(conversationId, summary.title || local.title, summary)
    merged.push({
      ...persisted,
      title: persisted.title || local.title || null,
      promptTokens: Math.max(persisted.promptTokens, finiteNumber(local.promptTokens)),
      completionTokens: Math.max(persisted.completionTokens, finiteNumber(local.completionTokens)),
      totalTokens: Math.max(persisted.totalTokens, finiteNumber(local.totalTokens)),
      callCount: Math.max(persisted.callCount, finiteNumber(local.callCount))
    })
  }
  for (const item of current) {
    if (item?.conversationId && !seen.has(item.conversationId)) merged.push(item)
  }
  return merged
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
