const DEFAULT_THRESHOLD_PERCENT = 90
const MIN_THRESHOLD_PERCENT = 70
const MAX_THRESHOLD_PERCENT = 99
const MIN_RESERVED_TOKENS = 2048
const MAX_RESERVED_TOKENS = 8192

/**
 * 与后端 ContextWindowPolicy 保持相同公式，仅用于模型配置表单的即时预览。
 */
export function calculateContextWindowPolicy(form = {}) {
  const contextWindowTokens = positiveInteger(form.contextWindowTokens)
  const maxTokens = positiveInteger(form.maxTokens)
  if (!contextWindowTokens || !maxTokens || contextWindowTokens <= maxTokens) {
    return {
      valid: false,
      reason: '上下文窗口 Tokens 必须大于 Max Tokens，填写后才能计算有效自动压缩线。',
      inputCapacityTokens: 0,
      reservedTokens: 0,
      thresholdLimitTokens: 0,
      reserveLimitTokens: 0,
      softLimitTokens: 0,
      softLimitPercent: 0
    }
  }

  const inputCapacityTokens = contextWindowTokens - maxTokens
  const defaultReserved = clamp(Math.ceil(contextWindowTokens * 0.1), MIN_RESERVED_TOKENS, MAX_RESERVED_TOKENS)
  const configuredReserved = optionalInteger(form.compactionReservedTokens)
  const reservedTokens = clamp(configuredReserved ?? defaultReserved, 0, Math.max(0, contextWindowTokens - 1024))
  const configuredThreshold = optionalInteger(form.compactionThresholdPercent)
  const thresholdPercent = clamp(configuredThreshold ?? DEFAULT_THRESHOLD_PERCENT,
    MIN_THRESHOLD_PERCENT, MAX_THRESHOLD_PERCENT)
  const thresholdLimitTokens = Math.floor(contextWindowTokens * thresholdPercent / 100)
  const reserveLimitTokens = contextWindowTokens - reservedTokens
  const softLimitTokens = Math.min(inputCapacityTokens, reserveLimitTokens, thresholdLimitTokens)

  return {
    valid: true,
    reason: '',
    contextWindowTokens,
    maxTokens,
    inputCapacityTokens,
    reservedTokens,
    thresholdPercent,
    thresholdLimitTokens,
    reserveLimitTokens,
    softLimitTokens,
    softLimitPercent: roundOne(softLimitTokens * 100 / contextWindowTokens)
  }
}

function positiveInteger(value) {
  const parsed = Number(value)
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null
}

function optionalInteger(value) {
  if (value === '' || value === null || value === undefined) return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? Math.trunc(parsed) : null
}

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value))
}

function roundOne(value) {
  return Math.round(value * 10) / 10
}
