// 唯一职责：由目录适配器批量读取指定路径的本地状态，产出 changeSetSyncPlan 需要的 probe 结构。
// 边界：只读；不做冲突判定（判定在 F1）；不写任何文件。

export function createProbeMap() {
  return new Map()
}

// probe 结构：Map<path, { exists: boolean, content: string|null }>
// 与 changeSetSyncPlan.resolveProbe 读取的约定一致。
export async function collectProbe(adapter, paths, options = {}) {
  const probe = createProbeMap()
  const unique = [...new Set((Array.isArray(paths) ? paths : []).filter(Boolean))]
  const failures = []

  for (const path of unique) {
    try {
      const exists = await adapter.fileExists(path)
      const content = exists ? await adapter.readTextFile(path) : null
      probe.set(path, { exists: exists === true, content })
    } catch (error) {
      failures.push({ path, message: error instanceof Error ? error.message : String(error) })
      if (options.failFast === true) throw error
    }
  }

  return { probe, failures }
}

export function probeToObject(probe) {
  if (probe instanceof Map) return Object.fromEntries(probe.entries())
  return probe && typeof probe === 'object' ? probe : {}
}
