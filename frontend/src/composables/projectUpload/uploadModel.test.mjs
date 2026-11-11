import assert from 'node:assert/strict'
import test from 'node:test'

import {
  createProgressTracker,
  formatBytes,
  formatDuration,
  phaseCopy,
  previewSkippedDirectories,
  validateArchive
} from './uploadModel.js'

test('formatBytes 按 1024 进制换算并保留合适位数', () => {
  assert.equal(formatBytes(0), '0 B')
  assert.equal(formatBytes(512), '512 B')
  assert.equal(formatBytes(1024), '1.0 KB')
  assert.equal(formatBytes(190 * 1024 * 1024), '190 MB')
  assert.equal(formatBytes(1.5 * 1024 * 1024 * 1024), '1.5 GB')
})

test('formatBytes 对负值与非数字返回占位符', () => {
  assert.equal(formatBytes(-1), '-')
  assert.equal(formatBytes(null), '-')
  assert.equal(formatBytes('abc'), '-')
})

test('formatDuration 在 60 秒前后切换单位', () => {
  assert.equal(formatDuration(0), '0 秒')
  assert.equal(formatDuration(25), '25 秒')
  assert.equal(formatDuration(60), '1 分')
  assert.equal(formatDuration(72), '1 分 12 秒')
  assert.equal(formatDuration(null), '')
})

test('validateArchive 拒绝非 zip 文件', () => {
  const result = validateArchive({ name: 'project.tar.gz', size: 1024 }, { maxArchiveBytes: 1024 * 1024 })
  assert.equal(result.ok, false)
  assert.equal(result.reason, 'type')
  assert.match(result.message, /\.zip/)
})

test('validateArchive 在超限时给出超出的具体字节数', () => {
  const maxBytes = 500 * 1024 * 1024
  const result = validateArchive({ name: 'big.zip', size: maxBytes + 180 * 1024 * 1024 }, { maxArchiveBytes: maxBytes })
  assert.equal(result.ok, false)
  assert.equal(result.reason, 'size')
  assert.equal(result.overByBytes, 180 * 1024 * 1024)
  assert.match(result.message, /超过上限/)
  assert.match(result.message, /超出/)
})

test('validateArchive 在没有上限配置时不拦截尺寸', () => {
  const result = validateArchive({ name: 'ok.zip', size: 10 * 1024 * 1024 }, {})
  assert.equal(result.ok, true)
})

test('validateArchive 缺少文件时给出提示', () => {
  const result = validateArchive(null, {})
  assert.equal(result.ok, false)
  assert.equal(result.reason, 'missing')
})

test('进度采样器按滑动窗口算速度，避免瞬时抖动', () => {
  let now = 0
  const tracker = createProgressTracker({ windowSize: 3, now: () => now })

  tracker.update(0, 1000)
  now += 1000
  tracker.update(500, 1000)
  const snapshot = tracker.update(1000, 1000)

  assert.equal(snapshot.percent, 100)
  // 1000 字节 / 1 秒 = 1000 B/s
  assert.equal(Math.round(snapshot.bytesPerSecond), 1000)
  assert.equal(snapshot.etaSeconds, 0)
})

test('进度采样器在还没收到数据时不给 ETA', () => {
  const tracker = createProgressTracker({ now: () => 0 })
  const snapshot = tracker.update(100, 1000)
  assert.equal(snapshot.bytesPerSecond, 0)
  assert.equal(snapshot.etaSeconds, null)
})

test('进度采样器在完成后 ETA 为 0 而不是负数', () => {
  let now = 0
  const tracker = createProgressTracker({ now: () => now })
  tracker.update(0, 100)
  now += 500
  const snapshot = tracker.update(100, 100)
  assert.equal(snapshot.etaSeconds, 0)
})

test('进度采样器 reset 后回到初始状态', () => {
  let now = 0
  const tracker = createProgressTracker({ now: () => now })
  tracker.update(0, 100)
  now += 100
  tracker.update(50, 100)
  tracker.reset()
  const snapshot = tracker.snapshot()
  assert.equal(snapshot.percent, 0)
  assert.equal(snapshot.bytesPerSecond, 0)
  assert.equal(snapshot.etaSeconds, null)
})

test('phaseCopy 解压阶段不编造百分比', () => {
  const copy = phaseCopy('extracting', { elapsedSeconds: 18 })
  assert.equal(copy.title, '正在解压…')
  assert.match(copy.hint, /18 秒/)
  assert.doesNotMatch(copy.hint, /%/)
})

test('phaseCopy 完成阶段带出跳过数量', () => {
  const copy = phaseCopy('done', { fileCount: 2341, totalSize: 48 * 1024 * 1024, skippedCount: 12847 })
  assert.match(copy.hint, /2341 个文件/)
  assert.match(copy.hint, /已跳过 12847 个依赖文件/)
})

test('previewSkippedDirectories 把依赖目录排在扫描忽略目录前面', () => {
  const names = ['.cache', '.git', '.idea', 'node_modules', 'dist', 'target', 'vendor', 'npm-cache']
  const preview = previewSkippedDirectories(names, 4)
  assert.deepEqual(preview, ['node_modules', 'vendor', 'dist', 'target'])
})

test('previewSkippedDirectories 在名单缺项时不报错', () => {
  assert.deepEqual(previewSkippedDirectories(undefined, 3), [])
  assert.deepEqual(previewSkippedDirectories(['.git'], 3), ['.git'])
})
