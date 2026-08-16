/**
 * 合并同一模型回合的拒绝完成证据与最终拦截原因。
 * COMPLETION_EVIDENCE 保存完整事实，FINALIZATION_BLOCKED 只补充最终拒绝原因，
 * 因此不能让后者覆盖前者已经持久化的改动、验证和风险明细。
 */
export function mergeFinalizationBlockedEvidence(existingEvidence, blocker = {}) {
  const existing = existingEvidence?.satisfied === false ? existingEvidence : {}
  return {
    ...existing,
    ...blocker,
    satisfied: false
  }
}
