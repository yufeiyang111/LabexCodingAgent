const DATE_KEY_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/
const DAY_MS = 24 * 60 * 60 * 1000

export function createUsageHeatmapData(byDay = {}, today = new Date()) {
  const { start, end } = getRecentSixMonthRange(today)
  const values = normalizeUsageValues(byDay)
  const data = []

  for (let timestamp = start.getTime(); timestamp <= end.getTime(); timestamp += DAY_MS) {
    const date = new Date(timestamp)
    const key = toDateKey(date)
    data.push([key, values.get(key) || 0])
  }

  const max = data.reduce((highest, [, value]) => Math.max(highest, value), 0)
  return {
    startDate: toDateKey(start),
    endDate: toDateKey(end),
    data,
    max,
    hasData: max > 0
  }
}

export function getRecentSixMonthRange(today = new Date()) {
  const end = toUtcDate(today)
  const start = subtractMonthsClamped(end, 6)
  return { start, end }
}

export function formatUsageTokenCount(value) {
  const tokens = Number(value)
  if (!Number.isFinite(tokens)) return '0'
  if (tokens >= 1000000) return `${(tokens / 1000000).toFixed(1)}M`
  if (tokens >= 1000) return `${(tokens / 1000).toFixed(1)}K`
  return String(tokens)
}

function normalizeUsageValues(byDay) {
  const values = new Map()
  if (!byDay || typeof byDay !== 'object' || Array.isArray(byDay)) return values

  for (const [key, rawValue] of Object.entries(byDay)) {
    const date = parseDateKey(key)
    const value = Number(rawValue)
    if (!date || !Number.isFinite(value) || value <= 0) continue
    values.set(toDateKey(date), value)
  }
  return values
}

function parseDateKey(value) {
  if (typeof value !== 'string') return null
  const match = DATE_KEY_PATTERN.exec(value)
  if (!match) return null
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])))
  return date.getUTCFullYear() === Number(match[1])
    && date.getUTCMonth() === Number(match[2]) - 1
    && date.getUTCDate() === Number(match[3])
    ? date
    : null
}

function toUtcDate(value) {
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return toUtcDate(new Date())
  return new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()))
}

function subtractMonthsClamped(date, months) {
  const targetYear = date.getUTCFullYear()
  const targetMonth = date.getUTCMonth() - months
  const targetDay = date.getUTCDate()
  const lastDay = new Date(Date.UTC(targetYear, targetMonth + 1, 0)).getUTCDate()
  return new Date(Date.UTC(targetYear, targetMonth, Math.min(targetDay, lastDay)))
}

function toDateKey(date) {
  return [date.getUTCFullYear(), date.getUTCMonth() + 1, date.getUTCDate()]
    .map((value, index) => index === 0 ? String(value) : String(value).padStart(2, '0'))
    .join('-')
}
