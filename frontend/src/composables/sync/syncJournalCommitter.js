// 唯一职责：管理同步 journal 的提交节奏（何时落盘、失败时如何补存）。
// 边界：只决定"存不存"，不写文件、不判定冲突、不推进游标。

/**
 * 周期性提交 journal。
 *
 * 两个极端都不能走：
 *   - 每写一个文件就存一次 → 每步多一次 IndexedDB 事务 + 全量序列化，
 *     45 个小文件曾因此耗时约 5 分钟；
 *   - 整批写完才存一次 → 刷新/关闭页面会丢掉全部进度与回退依据。
 *
 * 所以每 N 个文件提交一次，并在收尾时补一次。
 */
export function createJournalCommitter({ journalStore, interval, assertWithinLimits }) {
  const step = Number.isFinite(interval) && interval > 0 ? Math.floor(interval) : 1
  let committedCount = -1

  return {
    /** 是否到了该提交的步数。 */
    shouldCommit(appliedCount) {
      return appliedCount > 0 && appliedCount % step === 0
    },

    /** 提交一次（含大小校验）。 */
    async commit(journal, appliedCount) {
      if (assertWithinLimits) assertWithinLimits(journal)
      await journalStore.save(journal)
      committedCount = appliedCount
    },

    /** 收尾提交：最后一次提交之后又写过文件就再存一次。 */
    async commitFinal(journal, appliedCount) {
      if (committedCount === appliedCount) return
      await this.commit(journal, appliedCount)
    },

    /** 是否已经提交过（失败路径据此判断要不要补存）。 */
    get hasCommitted() {
      return committedCount >= 0
    }
  }
}
