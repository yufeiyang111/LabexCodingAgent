// 唯一职责：把计划步骤按路径合并成"最终只写一次"的写盘计划。
// 边界：纯函数，不读写文件、不判定冲突、不决定游标；输入输出都是步骤数组。
//
// 为什么需要它：同一次同步里同一文件常被改多次（Agent 连续编辑），
// 中间版本对本地没有任何价值——写 v1、v2 再写 v3，最终只有 v3 留在盘上。
// 不合并的代价是实打实的：45 个小文件曾因此耗时约 5 分钟。

/**
 * 合并规则（按路径取第一个与最后一个）：
 *   - 基态取**第一个**步骤的 previousContent/previousExisted（同步前的真实状态）；
 *   - 目标取**最后一个**步骤的 op/content（最终落盘状态）；
 *   - 首个是「新建」且最后是「删除」→ 净效果为零，整条移除；
 *   - `coalescedCount` 记录这条合并覆盖了几条原始变更，供调用方累加游标进度。
 *
 * @param {Array<object>} steps
 * @returns {{ steps: Array<object>, merged: number }}
 */
export function coalesceSteps(steps) {
  const list = Array.isArray(steps) ? steps : []
  const byPath = new Map()
  const order = []

  for (const step of list) {
    if (!byPath.has(step.path)) {
      byPath.set(step.path, { ...step, coalescedCount: 1 })
      order.push(step.path)
      continue
    }
    const head = byPath.get(step.path)
    byPath.set(step.path, {
      ...step,
      // 基态永远来自第一次变更之前
      previousContent: head.previousContent,
      previousExisted: head.previousExisted,
      overwritten: head.overwritten || step.overwritten,
      coalescedCount: (head.coalescedCount || 1) + 1
    })
  }

  const mergedSteps = []
  let merged = 0
  for (const path of order) {
    const step = byPath.get(path)
    // 同步前不存在、同步后也不存在：净效果为零，不产生任何磁盘操作。
    if (step.previousExisted === false && step.op === 'delete') {
      merged += step.coalescedCount || 1
      continue
    }
    mergedSteps.push(step)
  }
  merged += list.length - order.length
  return { steps: mergedSteps, merged }
}
