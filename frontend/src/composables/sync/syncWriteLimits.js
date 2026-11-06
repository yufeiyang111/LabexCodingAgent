// 唯一职责：在写任何文件之前，判断这一批"能不能写"，不能则给出准确原因。
// 边界：只读 changePayload 与 limits，不写文件、不改会话状态。
//
// 为什么单独成模块：这三条上限保护的是三件不同的事，混在编排器里时
// 报错顺序会互相遮蔽——实测用户看到的是 "journal size limit exceeded"，
// 而真实原因是"这次要写的内容本身就放不进内存"。

import { SYNC_LIMITS, positiveLimit, utf8ByteLength } from './syncLimits.js'

function feedFilesOf(changePayload) {
  if (Array.isArray(changePayload?.changeSets)) {
    return changePayload.changeSets.flatMap(set => (Array.isArray(set?.files) ? set.files : []))
  }
  return Array.isArray(changePayload?.changes) ? changePayload.changes : []
}

function pathOf(file) {
  return file?.relativePath ?? file?.relative_path ?? file?.path ?? ''
}

/**
 * 检查探针阶段的失败里有没有"资源上限"类的。
 * 这类失败不是冲突：用户按冲突去处理永远处理不完，而且整批一个文件都不该写。
 *
 * @returns {string|null} 需要直接终止的错误消息；null 表示可以继续
 */
export function findProbeLimitFailure(failures) {
  const hit = (Array.isArray(failures) ? failures : [])
    .find(item => /limit exceeded/.test(item?.message ?? ''))
  return hit?.message ?? null
}

/**
 * 检查**要写入的内容**本身是否超限。
 *
 * 探针只能看到本地文件多大；本地目录是空的时候它什么都读不到，
 * 于是云端交付的巨型文件会绕过 MAX_FILE_BYTES 直接被写进浏览器。
 * 所以写入侧必须独立再查一次。
 *
 * 顺序有意为之：先查单文件、再查整批。整批超限时如果不先报出来，
 * 用户会看到 journal 的次生错误，误以为"历史记录太多"。
 *
 * @returns {string|null} 需要直接终止的错误消息；null 表示可以继续
 */
export function findWriteLimitFailure(changePayload, limits = {}) {
  const maxFileBytes = positiveLimit(limits.maxFileBytes, SYNC_LIMITS.MAX_FILE_BYTES)
  const maxBatchBytes = positiveLimit(limits.maxBatchBytes, SYNC_LIMITS.MAX_BATCH_BYTES)

  let batchBytes = 0
  for (const file of feedFilesOf(changePayload)) {
    const bytes = utf8ByteLength(file?.afterContent ?? '')
    if (bytes > maxFileBytes) {
      return `file size limit exceeded: ${bytes} > ${maxFileBytes} (${pathOf(file)})`
    }
    batchBytes += bytes
  }
  if (batchBytes > maxBatchBytes) {
    return `batch size limit exceeded: ${batchBytes} > ${maxBatchBytes}`
  }
  return null
}
