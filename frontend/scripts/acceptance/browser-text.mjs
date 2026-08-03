export function bodyIncludesAnyExpression(candidates) {
  const normalized = (Array.isArray(candidates) ? candidates : [])
    .map(candidate => String(candidate || ''))
    .filter(Boolean)
  return `(${JSON.stringify(normalized)}).some(candidate => document.body.innerText.includes(candidate))`
}
