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
  return {
    staticTokens: numericOrSum(status?.staticTokens, staticCategories),
    reducibleTokens: numericOrSum(status?.reducibleTokens, reducibleCategories),
    reservedOutputTokens: number(status?.reservedOutputTokens),
    inputCapacityTokens: number(status?.inputCapacityTokens) || Math.max(0,
      number(status?.contextWindowTokens) - number(status?.reservedOutputTokens)),
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

function number(value) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? Math.max(0, parsed) : 0
}
