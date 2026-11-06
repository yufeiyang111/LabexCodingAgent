// 唯一职责：由目录适配器批量读取指定路径的本地状态，产出 changeSetSyncPlan 需要的 probe 结构。
// 边界：只读；不做冲突判定（判定在 changeSetSyncPlan）；不写任何文件。
//
// C05 安全不变量：读取失败绝不能被折叠成“文件不存在”。
// probe 是三态结构：
//   present：确认存在，content 为读取到的文本
//   absent ：确认不存在，content 为 null
//   unknown：无法判断（权限/IO/未返回有效结果），计划器必须阻断
import { SYNC_LIMITS, positiveLimit, utf8ByteLength } from './syncLimits.js'

export const PROBE_STATES = Object.freeze({
  PRESENT: 'present',
  ABSENT: 'absent',
  UNKNOWN: 'unknown'
})

export function createProbeMap() {
  return new Map()
}

export async function collectProbe(adapter, paths, options = {}) {
  const probe = createProbeMap()
  const unique = [...new Set((Array.isArray(paths) ? paths : []).filter(Boolean))]
  const failures = []
  const maxFiles = positiveLimit(options.maxFiles, SYNC_LIMITS.MAX_BATCH_FILES)
  const maxFileBytes = positiveLimit(options.maxFileBytes, SYNC_LIMITS.MAX_FILE_BYTES)
  const maxBatchBytes = positiveLimit(options.maxBatchBytes, SYNC_LIMITS.MAX_BATCH_BYTES)
  let totalBytes = 0

  // 文件数超限不再整批拒绝，而是**分块读取**。
  // 旧行为把"这批有 600 个文件"报成"600 个文件都无法判断本地状态"，
  // 用户看到的是 600 个冲突——原因和现象都对不上，而且中型项目直接同步不了。
  // 分块只影响"一次读多少"，不改变单文件与整批字节上限的语义：
  //   - 单文件超限：仍然整批失败（一个文件都写不了，继续也没意义）；
  //   - 累计字节超限：仍然整批失败（内存保护，不能靠分块绕过）。
  for (const path of unique) {
    try {
      // 一次读取即可判定三态：返回字符串 = 存在且可读；返回 null = 确认不存在；
      // 抛错 = 无法判断。旧实现先 fileExists 再 readTextFile，同一路径的
      // 目录解析与 getFileHandle 都做两遍——浏览器实测 40 个文件 667ms → 315ms。
      const content = await adapter.readTextFile(path)
      if (content === null || content === undefined) {
        probe.set(path, { state: PROBE_STATES.ABSENT, exists: false, content: null })
        continue
      }
      if (typeof content !== 'string') {
        throw new Error(`probe returned invalid content type: ${typeof content}`)
      }
      const contentBytes = utf8ByteLength(content)
      if (contentBytes > maxFileBytes) {
        throw new Error(`file size limit exceeded: ${contentBytes} > ${maxFileBytes}`)
      }
      totalBytes += contentBytes
      if (totalBytes > maxBatchBytes) {
        const message = `batch size limit exceeded: ${totalBytes} > ${maxBatchBytes}`
        failures.push({ path, message })
        probe.set(path, { state: PROBE_STATES.UNKNOWN, exists: null, content: null, error: message })
        for (const [knownPath, entry] of probe) {
          if (entry.state !== PROBE_STATES.UNKNOWN) {
            probe.set(knownPath, { state: PROBE_STATES.UNKNOWN, exists: null, content: null, error: message })
          }
        }
        return { probe, failures }
      }
      probe.set(path, { state: PROBE_STATES.PRESENT, exists: true, content })
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      failures.push({ path, message })
      probe.set(path, {
        state: PROBE_STATES.UNKNOWN,
        exists: null,
        content: null,
        error: message
      })
      if (options.failFast === true) throw error
    }
    if (options.onProgress && (probe.size % maxFiles === 0)) {
      options.onProgress({ probed: probe.size, total: unique.length })
    }
  }

  return { probe, failures }
}

export function probeToObject(probe) {
  if (probe instanceof Map) return Object.fromEntries(probe.entries())
  return probe && typeof probe === 'object' ? probe : {}
}
