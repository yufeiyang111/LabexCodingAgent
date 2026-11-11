/**
 * 项目压缩包上传的纯展示逻辑：字节格式化、上传前校验、进度采样与速度估算。
 *
 * 单独成模块的原因与 sourceControlModel 相同：这些函数的输入输出完全确定，
 * 放在组件里就只能靠浏览器测，而它们恰恰是最容易算错的部分（速度抖动、ETA 跳变、上限判定）。
 */

const BYTE_UNITS = ['B', 'KB', 'MB', 'GB', 'TB']

/** 字节数 → 「190.4 MB」。负值、null 与非数字都返回 '-'，避免把 NaN 显示给用户。 */
export function formatBytes(bytes) {
  if (bytes === null || bytes === undefined || bytes === '') return '-'
  const value = Number(bytes)
  if (!Number.isFinite(value) || value < 0) return '-'
  let scaled = value
  let unitIndex = 0
  while (scaled >= 1024 && unitIndex < BYTE_UNITS.length - 1) {
    scaled /= 1024
    unitIndex += 1
  }
  const digits = scaled >= 100 || unitIndex === 0 ? 0 : 1
  return `${scaled.toFixed(digits)} ${BYTE_UNITS[unitIndex]}`
}

/** 秒数 → 「25 秒」/「1 分 12 秒」。未知返回空串。 */
export function formatDuration(seconds) {
  if (seconds === null || seconds === undefined || seconds === '') return ''
  const value = Number(seconds)
  if (!Number.isFinite(value) || value < 0) return ''
  const total = Math.round(value)
  if (total < 60) return `${total} 秒`
  const minutes = Math.floor(total / 60)
  const rest = total % 60
  return rest === 0 ? `${minutes} 分` : `${minutes} 分 ${rest} 秒`
}

/**
 * 上传前校验。
 *
 * @param {{ name?: string, size?: number }} file 浏览器 File 对象（只读取 name/size）
 * @param {{ maxArchiveBytes?: number }} limits 后端 /archive-limits 的返回值
 * @returns {{ ok: boolean, reason?: string, message?: string, overByBytes?: number }}
 */
export function validateArchive(file, limits = {}) {
  if (!file) {
    return { ok: false, reason: 'missing', message: '请选择要上传的压缩包' }
  }
  const name = String(file.name || '')
  if (!name.toLowerCase().endsWith('.zip')) {
    return { ok: false, reason: 'type', message: '只支持 .zip 压缩包' }
  }
  const size = Number(file.size)
  const maxBytes = Number(limits.maxArchiveBytes)
  if (Number.isFinite(maxBytes) && maxBytes > 0 && Number.isFinite(size) && size > maxBytes) {
    const overByBytes = size - maxBytes
    return {
      ok: false,
      reason: 'size',
      overByBytes,
      message: `压缩包 ${formatBytes(size)}，超过上限 ${formatBytes(maxBytes)}，超出 ${formatBytes(overByBytes)}`
    }
  }
  return { ok: true }
}

/**
 * 进度采样器。速度按最近若干个采样点的滑动窗口计算，避免瞬时抖动让剩余时间乱跳。
 *
 * 用法：`tracker.update(loaded, total)` 返回当前快照；`tracker.snapshot()` 读最近一次结果。
 */
export function createProgressTracker(options = {}) {
  const windowSize = Math.max(2, Number(options.windowSize) || 5)
  const now = typeof options.now === 'function' ? options.now : () => Date.now()
  const samples = []
  let snapshot = { percent: 0, loaded: 0, total: 0, bytesPerSecond: 0, etaSeconds: null }

  function update(loaded, total) {
    const safeTotal = Number(total) > 0 ? Number(total) : 0
    const safeLoaded = Math.max(0, Number(loaded) || 0)
    const timestamp = now()
    samples.push({ loaded: safeLoaded, at: timestamp })
    while (samples.length > windowSize) samples.shift()

    let bytesPerSecond = 0
    if (samples.length >= 2) {
      const first = samples[0]
      const last = samples[samples.length - 1]
      const elapsedSeconds = (last.at - first.at) / 1000
      if (elapsedSeconds > 0) {
        bytesPerSecond = Math.max(0, (last.loaded - first.loaded) / elapsedSeconds)
      }
    }

    const percent = safeTotal > 0 ? Math.min(100, Math.round((safeLoaded / safeTotal) * 100)) : 0
    let etaSeconds = null
    if (bytesPerSecond > 0) {
      // 完成时剩余量为 0，ETA 就是 0；不要退化成 null（前端会显示成「剩余未知」）。
      etaSeconds = Math.max(0, (safeTotal - safeLoaded) / bytesPerSecond)
    }
    snapshot = { percent, loaded: safeLoaded, total: safeTotal, bytesPerSecond, etaSeconds }
    return snapshot
  }

  function reset() {
    samples.length = 0
    snapshot = { percent: 0, loaded: 0, total: 0, bytesPerSecond: 0, etaSeconds: null }
  }

  return { update, reset, snapshot: () => snapshot }
}

/** 阶段 → 标题与说明。解压阶段刻意不显示百分比：服务端没有进度通道，编一个假数字更糟。 */
export function phaseCopy(phase, context = {}) {
  switch (phase) {
    case 'confirm':
      return { title: '上传项目压缩包', hint: '上传后会自动解压并创建项目。' }
    case 'uploading':
      return {
        title: '正在上传…',
        hint: `${formatBytes(context.loaded)} / ${formatBytes(context.total)}`
          + (context.bytesPerSecond > 0 ? ` · ${formatBytes(context.bytesPerSecond)}/s` : '')
          + (context.etaSeconds != null ? ` · 剩余 ${formatDuration(context.etaSeconds)}` : '')
      }
    case 'extracting':
      return {
        title: '正在解压…',
        hint: '服务端解压无法中断，请等待完成。'
          + (context.elapsedSeconds != null ? `已用时 ${formatDuration(context.elapsedSeconds)}。` : '')
      }
    case 'done':
      return {
        title: '上传完成',
        hint: `${context.fileCount ?? 0} 个文件 · ${formatBytes(context.totalSize ?? 0)}`
          + (context.skippedCount > 0 ? ` · 已跳过 ${context.skippedCount} 个依赖文件` : '')
      }
    default:
      return { title: '', hint: '' }
  }
}

/**
 * 复选框标签里展示的目录名预览。
 *
 * 后端返回的是完整的扫描忽略名单（含 .git / .idea / .labex 这类非依赖目录），
 * 按字母序取前几个会把 .cache、.git 排在最前，读起来像"要跳过版本库"，与实际重点不符。
 * 这里按"用户最可能关心的依赖/构建目录"优先排序，剩余项补在后面。
 */
const PREVIEW_PRIORITY = [
  'node_modules', 'bower_components', 'vendor', 'dist', 'build', 'target',
  'out', 'coverage', '.next', '.nuxt', '.gradle', '.mvn', '__pycache__'
]

export function previewSkippedDirectories(names, limit = 6) {
  const list = Array.isArray(names) ? names : []
  const prioritized = PREVIEW_PRIORITY.filter(name => list.includes(name))
  const rest = list.filter(name => !prioritized.includes(name))
  return [...prioritized, ...rest].slice(0, Math.max(0, limit))
}

