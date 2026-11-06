// 唯一职责：维护「同步过程中的虚拟本地视图」。
// 边界：只在内存里记录路径状态，不读写文件、不判定冲突、不推进游标。
//
// 为什么需要它：探针给的是"同步开始前"的状态，而同一批次里同一路径可能被多次修改。
// 每产出一个步骤就把结果写回视图，后一步才能拿到正确的"基"（C04）。

/**
 * @param {object|Map} probe     探针结果：{ [path]: { state, exists, content } }
 * @param {Iterable<string>} deliveredPaths  journal 里出现过的路径（这个目录收到过哪些文件）
 */
export function createLocalView(probe, deliveredPaths) {
  const overlay = new Map()
  const createdInBatch = new Set()
  const delivered = deliveredPaths instanceof Set
    ? deliveredPaths
    : new Set(Array.isArray(deliveredPaths) ? deliveredPaths : [])

  return {
    get(path) {
      const entry = overlay.has(path)
        ? overlay.get(path)
        : (() => {
          const probed = probe?.get ? probe.get(path) : probe?.[path]
          if (!probed) return { exists: false, content: null, unknown: true }
          return {
            exists: probed.exists === true,
            content: probed.content ?? null,
            unknown: probed.state === 'unknown' || probed.exists === null
          }
        })()
      // delivered：这个路径以前被同步交付到过本地目录（journal 里有记录）。
      // "本地缺失"只有在交付过时才说明用户删了它；从未交付过的路径缺失只是"还没同步"。
      return { ...entry, createdInBatch: createdInBatch.has(path), delivered: delivered.has(path) }
    },
    set(path, value) {
      overlay.set(path, value)
    },
    markCreated(path) {
      createdInBatch.add(path)
    },
    markDelivered(path) {
      delivered.add(path)
    },
    markRemoved(path) {
      createdInBatch.delete(path)
    }
  }
}

/**
 * 把一步的结果写回虚拟本地视图，并维护"本批次创建"与"已交付"标记。
 * 标记的作用：让后续对同一路径的 create/modify 知道"这个文件是我刚建的"，
 * 而不是用户预先占用的文件（后者才该判 local-occupied）。
 */
export function applyToLocalView(local, file, step) {
  local.set(file.path, {
    exists: step.op !== 'delete',
    content: step.op === 'delete' ? null : file.afterContent
  })
  // 本批次写过就算"交付过"：同一批次里后续再判缺失时不该回退成 missing-local。
  local.markDelivered(file.path)
  if (step.op === 'delete') {
    local.markRemoved(file.path)
  } else if (file.changeType === 'create' || file.beforeExisted === false) {
    local.markCreated(file.path)
  }
}
