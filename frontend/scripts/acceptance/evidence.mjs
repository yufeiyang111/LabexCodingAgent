const SENSITIVE_KEY = /^(?:authorization|cookie|set-cookie|password|passwd|secret|token|access[_-]?token|refresh[_-]?token|api[_-]?key)$/i

export function assertEventSequence(events, requiredTypes) {
  const actual = Array.isArray(events) ? events : []
  let cursor = 0
  for (const requiredType of requiredTypes ?? []) {
    while (cursor < actual.length && actual[cursor]?.type !== requiredType) cursor += 1
    if (cursor >= actual.length) {
      const observed = actual.map(event => event?.type ?? '<unknown>').join(', ')
      throw new Error(`Missing required Agent event ${requiredType}; observed: ${observed || '<none>'}`)
    }
    cursor += 1
  }
}

export function redactEvidence(value, seen = new WeakSet()) {
  if (value === null || typeof value !== 'object') return value
  if (seen.has(value)) return '[CIRCULAR]'
  seen.add(value)

  if (Array.isArray(value)) {
    return value.map(item => redactEvidence(item, seen))
  }

  const redacted = {}
  for (const [key, item] of Object.entries(value)) {
    redacted[key] = SENSITIVE_KEY.test(key) ? '[REDACTED]' : redactEvidence(item, seen)
  }
  return redacted
}
