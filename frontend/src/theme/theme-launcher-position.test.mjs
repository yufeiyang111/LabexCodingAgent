import assert from 'node:assert/strict'
import test from 'node:test'

import {
  LAUNCHER_POSITION_STORAGE_KEY,
  clampLauncherPosition,
  defaultLauncherPosition,
  loadLauncherPosition,
  saveLauncherPosition,
} from './theme-launcher-position.js'

function createStorage(initial = {}) {
  const values = new Map(Object.entries(initial))
  return {
    getItem(key) { return values.get(key) ?? null },
    setItem(key, value) { values.set(key, String(value)) },
  }
}

const desktopBounds = { width: 1280, height: 800 }

test('uses an edge-safe default launcher position and keeps it inside the viewport', () => {
  const position = defaultLauncherPosition(desktopBounds)
  // y 下界是顶栏保留区（64），不是边缘内距（12）—— 见下方避让测试
  assert.ok(position.x >= 12 && position.x <= desktopBounds.width - 60)
  assert.ok(position.y >= 64 && position.y <= desktopBounds.height - 60)
})

test('clamps an out-of-bounds dragged position before persistence', () => {
  assert.deepEqual(clampLauncherPosition({ x: -80, y: 9999 }, desktopBounds), { x: 12, y: 740 })
})

test('keeps the launcher out of the top bar so it never covers header controls', () => {
  // 反例（用户实际遇到）：球被拖到 (12, 12) 后正好压住顶栏的「返回 / 登录」按钮，
  // 且位置已持久化，刷新也不会恢复。钳制阶段必须拦住，仅靠默认位置不够。
  assert.deepEqual(clampLauncherPosition({ x: 12, y: 12 }, desktopBounds), { x: 12, y: 64 })
  assert.equal(clampLauncherPosition({ x: 12, y: 40 }, desktopBounds).y, 64)
  // 顶栏下方的位置不受影响
  assert.equal(clampLauncherPosition({ x: 12, y: 200 }, desktopBounds).y, 200)
})

test('a position stored inside the top bar is discarded so the default is used', () => {
  const storage = createStorage()
  // 模拟存量脏数据：用户此前拖到过顶栏区域，压住了返回/登录按钮
  storage.setItem(LAUNCHER_POSITION_STORAGE_KEY, JSON.stringify({ x: 12, y: 12 }))

  // 返回 null 而非夹紧后的值：只夹 y 会让球落到左侧栏标题上，依然遮挡。
  // 调用方收到 null 后使用 defaultLauncherPosition，回到右侧的安全位置。
  assert.equal(loadLauncherPosition(storage, desktopBounds), null,
    '顶栏内的历史位置必须被丢弃，否则老用户刷新后仍被遮挡')
})

test('a position clamped only by a smaller viewport is kept, not discarded', () => {
  const storage = createStorage()
  // 用户在宽屏摆好的位置
  storage.setItem(LAUNCHER_POSITION_STORAGE_KEY, JSON.stringify({ x: 1200, y: 400 }))

  // 换到更窄的窗口读取：x 被夹紧但 y 合法，应保留夹紧结果而不是重置布局
  const clamped = loadLauncherPosition(storage, { width: 900, height: 800 })
  assert.deepEqual(clamped, { x: 840, y: 400 })
})

test('persists a valid dragged launcher position and ignores malformed saved data', () => {
  const storage = createStorage()
  const saved = saveLauncherPosition({ x: 420, y: 280 }, storage, desktopBounds)

  assert.deepEqual(saved, { x: 420, y: 280 })
  assert.deepEqual(loadLauncherPosition(storage, desktopBounds), saved)

  storage.setItem(LAUNCHER_POSITION_STORAGE_KEY, '{bad json')
  assert.equal(loadLauncherPosition(storage, desktopBounds), null)
})
