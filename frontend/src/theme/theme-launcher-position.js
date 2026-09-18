export const LAUNCHER_POSITION_STORAGE_KEY = 'labex-theme-launcher-position.v1'
export const TUTORIALS_LAUNCHER_POSITION_STORAGE_KEY = 'labex-tutorials-launcher-position.v1'

const EDGE_PADDING = 12
const LAUNCHER_SIZE = 48

/*
 * 顶栏保留区：悬浮球不允许停在页面顶部这条带子里。
 *
 * 反例（用户实际遇到）：球被拖到 (12, 12) 后正好压住顶栏的「返回 / 登录」按钮，
 * 用户看到的是「登录按钮被一个黑色圆圈盖住了」，且因为位置已持久化，
 * 刷新也不会恢复。仅靠默认位置在右侧并不能防止用户拖过去，必须在钳制阶段拦住。
 *
 * 取值 64 = 顶栏高度 56 + 8 余量，保证球体完全落在顶栏之下。
 */
const TOP_BAR_RESERVED = 64

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

  // 下界从 EDGE_PADDING 提到 TOP_BAR_RESERVED；窗口极矮时退化为 EDGE_PADDING，避免把球挤出视口
  const minY = Math.min(TOP_BAR_RESERVED, Math.max(EDGE_PADDING, maxY))

  return {
    x: Math.round(clamp(x, EDGE_PADDING, maxX)),
    y: Math.round(clamp(y, minY, maxY)),
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
    const clamped = clampLauncherPosition(parsed, bounds)

    /*
     * 仅当「原始 y 落在顶栏保留区内」时判定该存储值不可用，返回 null 让调用方使用默认位置。
     *
     * 为什么不能只夹紧 y：用户把球拖到左上角 (12, 12) 时本意是「放到不碍事的角落」，
     * 但那里正好压住顶栏的返回/登录按钮。若只把 y 夹到 64，球会落到左侧栏标题上 ——
     * 只是换了个地方挡，问题没解决。
     *
     * 为什么只针对 y 而不看 x：x 越界通常是「换到更窄的窗口」造成的正常夹紧，
     * 若一并丢弃，用户在宽屏摆好的位置会因一次窄屏访问而无故重置。
     * reservedY 取 min 是为了窗口极矮时（maxY 已小于保留区）不误伤。
     */
    const reservedY = Math.min(TOP_BAR_RESERVED, clamped.y)
    if (Math.round(parsed.y) < reservedY) return null
    return clamped
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
