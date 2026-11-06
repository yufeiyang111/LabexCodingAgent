// 唯一职责：把同步步骤真正落到目录适配器上，并给出可写入 journal 的结果状态。
// 边界：不判定冲突、不推进游标、不决定提交时机。

/**
 * 应用单个步骤（写入或删除）。
 */
export async function applyStep(adapter, step) {
  if (step.op === 'delete') {
    await adapter.deleteFile(step.path)
    return
  }
  await adapter.writeTextFile(step.path, step.content ?? '')
}

/**
 * 计算 journal 需要记录的"已应用状态"。
 *
 * **不回读文件**：刚写入的内容就是 step.content，删除后就是"不存在"。
 * 旧实现每应用一个文件都调用 fileExists + readTextFile 去确认结果，
 * 在真实浏览器的 File System Access 下这是额外的句柄解析 + getFile + text()，
 * 45 个文件因此多出 90 次异步 I/O（用户实测约 5 分钟）。
 *
 * 安全性不降低：journal 记录的仍是"我们写下去的内容"，
 * 回退时 preflightRollback 会重新读取磁盘内容与它比对，
 * 用户中途手改文件依然会被拦下。
 */
export function appliedStateOf(step) {
  if (step.op === 'delete') return { appliedExisted: false, appliedContent: null }
  const content = step.content ?? ''
  if (typeof content !== 'string') {
    throw new Error('sync step has no text content to record: ' + step.path)
  }
  return { appliedExisted: true, appliedContent: content }
}
