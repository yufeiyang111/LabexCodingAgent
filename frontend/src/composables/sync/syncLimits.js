// 同步模块的集中资源上限。
// 这些值用于防止异常批次或损坏 journal 把浏览器内存打满；允许测试通过 options 覆盖。
export const SYNC_LIMITS = Object.freeze({
  MAX_FILE_BYTES: 20 * 1024 * 1024,
  MAX_BATCH_FILES: 500,
  MAX_BATCH_BYTES: 64 * 1024 * 1024,
  MAX_JOURNAL_ENTRIES: 10_000,
  MAX_JOURNAL_BYTES: 32 * 1024 * 1024
})

export function positiveLimit(value, fallback) {
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback
}

export function utf8ByteLength(value) {
  return new TextEncoder().encode(typeof value === 'string' ? value : String(value ?? '')).byteLength
}
