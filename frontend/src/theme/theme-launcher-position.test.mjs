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
  assert.ok(position.x >= 12 && position.x <= desktopBounds.width - 60)
  assert.ok(position.y >= 12 && position.y <= desktopBounds.height - 60)
})

test('clamps an out-of-bounds dragged position before persistence', () => {
  assert.deepEqual(clampLauncherPosition({ x: -80, y: 9999 }, desktopBounds), { x: 12, y: 740 })
})

test('persists a valid dragged launcher position and ignores malformed saved data', () => {
  const storage = createStorage()
  const saved = saveLauncherPosition({ x: 420, y: 280 }, storage, desktopBounds)

  assert.deepEqual(saved, { x: 420, y: 280 })
  assert.deepEqual(loadLauncherPosition(storage, desktopBounds), saved)

  storage.setItem(LAUNCHER_POSITION_STORAGE_KEY, '{bad json')
  assert.equal(loadLauncherPosition(storage, desktopBounds), null)
})
