const STATIC_KEYS = new Set(['systemPrompt', 'toolDefinitions', 'fixedInstructions', 'skillsAndInstructions'])

export function normalizeContextBudget(status = {}) {
  const categories = status?.categories || {}
  const explicitStatic = status?.staticCategories
  const explicitReducible = status?.reducibleCategories
  const staticCategories = explicitStatic && typeof explicitStatic === 'object'
    ? { ...explicitStatic }
    : selectCategories(categories, key => STATIC_KEYS.has(key))
  const reducibleCategories = explicitReducible && typeof explicitReducible === 'object'
    ? { ...explicitReducible }
    : selectCategories(categories, key => !STATIC_KEYS.has(key))
  const staticTokens = numericOrSum(status?.staticTokens, staticCategories)
  const reducibleTokens = numericOrSum(status?.reducibleTokens, reducibleCategories)
  const reservedOutputTokens = number(status?.reservedOutputTokens)
  const contextWindowTokens = number(status?.contextWindowTokens)
  const inputCapacityTokens = number(status?.inputCapacityTokens) || Math.max(0,
    contextWindowTokens - reservedOutputTokens)
  const hasBackendSoftLimit = positiveNumber(status?.softLimitTokens) !== null
  const softLimitTokens = hasBackendSoftLimit
    ? positiveNumber(status.softLimitTokens)
    : inputCapacityTokens
  const explicitUsedTokens = Number(status?.usedTokens)
  const usedTokens = Number.isFinite(explicitUsedTokens) ? Math.max(0, explicitUsedTokens) : staticTokens + reducibleTokens

  return {
    staticTokens,
    reducibleTokens,
    reservedOutputTokens,
    inputCapacityTokens,
    softLimitTokens,
    contextWindowTokens,
    usedTokens,
    distanceToSoftLimitTokens: Math.max(0, softLimitTokens - usedTokens),
    usedAgainstSoftLimitPercent: ratio(usedTokens, softLimitTokens),
    usedAgainstWindowPercent: ratio(usedTokens, contextWindowTokens),
    softLimitOfWindowPercent: ratio(softLimitTokens, contextWindowTokens),
    softLimitSource: hasBackendSoftLimit ? 'backend' : 'input_capacity_fallback',
    staticCategories,
    reducibleCategories
  }
}

function selectCategories(categories, predicate) {
  return Object.fromEntries(Object.entries(categories || {})
    .filter(([key]) => predicate(key))
    .map(([key, value]) => [key, number(value)]))
}

function numericOrSum(value, categories) {
  const explicit = Number(value)
  if (Number.isFinite(explicit)) return Math.max(0, explicit)
  return Object.values(categories).reduce((total, item) => total + number(item), 0)
}

function positiveNumber(value) {
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null
}

function ratio(value, capacity) {
  return capacity > 0 ? Math.round(value * 1000 / capacity) / 10 : 0
}

function number(value) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? Math.max(0, parsed) : 0
}
