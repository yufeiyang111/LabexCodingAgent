// 唯一职责：编排器用到的无状态辅助函数——回退预检、journal 上限校验、游标合成。
// 边界：纯函数/只读检查；不持有会话状态、不写文件、不推进游标。

import { recordAppliedBatch } from './syncJournal.js'
import { SYNC_LIMITS, positiveLimit, utf8ByteLength } from './syncLimits.js'

/**
 * 回退前整体预检：任何一条目标被用户改过、被重建、或 journal 缺应用态，都零写入失败。
 * 必须在动第一个文件之前跑完——"回退到一半发现不能退"比不回退更糟。
 */
export async function preflightRollback(adapter, journal, steps) {
  const entries = Array.isArray(journal?.entries) ? journal.entries : []
  const selected = entries.slice(Math.max(0, entries.length - steps.length))
  if (selected.some(entry => typeof entry.appliedExisted !== 'boolean')) {
    throw new Error('rollback refused: legacy journal entry has no applied state')
  }
  for (const entry of selected) {
    const exists = await adapter.fileExists(entry.path)
    if (entry.appliedExisted === false) {
      if (exists) throw new Error(`rollback target changed: ${entry.path}`)
      continue
    }
    if (!exists) throw new Error(`rollback target changed: ${entry.path}`)
    const content = await adapter.readTextFile(entry.path)
    if (content !== (entry.appliedContent ?? null)) {
      throw new Error(`rollback target changed: ${entry.path}`)
    }
  }
}

/** journal 里出现过的路径集合：判断"本地缺失"是用户删的，还是这个目录从没同步过。 */
export function journalPaths(journal) {
  const entries = Array.isArray(journal?.entries) ? journal.entries : []
  return new Set(entries.map(entry => entry?.path).filter(Boolean))
}

/** 写盘前先校验 journal 体积，避免写到一半才发现存不下（那时回退依据已经没了）。 */
export function assertJournalWithinLimits(journal, limits = {}) {
  const entries = Array.isArray(journal?.entries) ? journal.entries : []
  const maxEntries = positiveLimit(limits.maxJournalEntries, SYNC_LIMITS.MAX_JOURNAL_ENTRIES)
  if (entries.length > maxEntries) {
    throw new Error(`journal entry limit exceeded: ${entries.length} > ${maxEntries}`)
  }
  const maxBytes = positiveLimit(limits.maxJournalBytes, SYNC_LIMITS.MAX_JOURNAL_BYTES)
  const bytes = utf8ByteLength(JSON.stringify(journal ?? null))
  if (bytes > maxBytes) {
    throw new Error(`journal size limit exceeded: ${bytes} > ${maxBytes}`)
  }
}

/** 预演"这批步骤全部写完之后"的 journal 体积。 */
export function recordAppliedBatchPreview(journal, steps, meta = {}) {
  return recordAppliedBatch(journal, steps, meta)
}

/**
 * 成功收尾时的游标：取 feed 返回的 nextCursor、本批已完整落地的集合、原游标三者的最大值。
 * 只前进不后退——重扫时服务端会回退重叠窗口，盲目跟随会让已交付的行被重复拉取。
 */
export function feedCursorAfterSuccess(payload, feedCursor, plan) {
  const returned = payload?.nextCursor ?? {}
  return {
    afterChangeSetId: Math.max(
      Number(feedCursor?.afterChangeSetId ?? 0) || 0,
      Number(returned.afterChangeSetId ?? 0) || 0,
      Number(plan?.nextCursor ?? 0) || 0
    ),
    tailUpdateTime: returned.tailUpdateTime ?? feedCursor?.tailUpdateTime ?? null,
    tailChangeId: returned.tailChangeId ?? feedCursor?.tailChangeId ?? ''
  }
}
