export const LAUNCHER_POSITION_STORAGE_KEY = 'labex-theme-launcher-position.v1'
export const TUTORIALS_LAUNCHER_POSITION_STORAGE_KEY = 'labex-tutorials-launcher-position.v1'

const EDGE_PADDING = 12
const LAUNCHER_SIZE = 48

function safeDimension(value) {
  return Number.isFinite(value) && value > 0 ? value : LAUNCHER_SIZE + EDGE_PADDING * 2
}

function clamp(value, min, max) {
  return Math.min(Math.max(value, min), max)
}

export function clampLauncherPosition(position, bounds, size = LAUNCHER_SIZE) {
  const width = safeDimension(bounds?.width)
  const height = safeDimension(bounds?.height)
  const maxX = Math.max(EDGE_PADDING, width - size - EDGE_PADDING)
  const maxY = Math.max(EDGE_PADDING, height - size - EDGE_PADDING)
  const x = Number.isFinite(position?.x) ? position.x : maxX
  const y = Number.isFinite(position?.y) ? position.y : EDGE_PADDING

  return {
    x: Math.round(clamp(x, EDGE_PADDING, maxX)),
    y: Math.round(clamp(y, EDGE_PADDING, maxY)),
  }
}

export function defaultLauncherPosition(bounds) {
  const safeBounds = { width: safeDimension(bounds?.width), height: safeDimension(bounds?.height) }
  const defaultY = Math.round(safeBounds.height * 0.42)
  return clampLauncherPosition({ x: safeBounds.width - LAUNCHER_SIZE - EDGE_PADDING, y: defaultY }, safeBounds)
}

export function defaultTutorialsLauncherPosition(bounds) {
  const safeBounds = { width: safeDimension(bounds?.width), height: safeDimension(bounds?.height) }
  const defaultY = Math.round(safeBounds.height * 0.52)
  return clampLauncherPosition({ x: safeBounds.width - LAUNCHER_SIZE - EDGE_PADDING, y: defaultY }, safeBounds)
}

export function loadLauncherPosition(storage, bounds, key = LAUNCHER_POSITION_STORAGE_KEY) {
  try {
    const raw = storage?.getItem?.(key)
    if (!raw) return null
    const parsed = JSON.parse(raw)
    if (!Number.isFinite(parsed?.x) || !Number.isFinite(parsed?.y)) return null
    return clampLauncherPosition(parsed, bounds)
  } catch {
    return null
  }
}

export function saveLauncherPosition(position, storage, bounds, key = LAUNCHER_POSITION_STORAGE_KEY) {
  const normalized = clampLauncherPosition(position, bounds)
  try {
    storage?.setItem?.(key, JSON.stringify(normalized))
  } catch {
    // The launcher remains movable even when browser storage is unavailable.
  }
  return normalized
}
