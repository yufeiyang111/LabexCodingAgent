export function hasContextUsageSnapshot(status) {
  return Boolean(
    Number(status?.usedTokens || 0) > 0 ||
    Object.keys(status?.categories || {}).length > 0
  )
}

export function resolveContextUsageStatus(currentStatus, nextStatus, conversationId = null) {
  const canRestoreCurrent = hasContextUsageSnapshot(currentStatus) &&
    (!conversationId || currentStatus?.conversationId === conversationId)

  if (!nextStatus && canRestoreCurrent) {
    return currentStatus
  }
  if (nextStatus?.status === 'AWAITING_FIRST_REQUEST' && canRestoreCurrent) {
    return currentStatus
  }
  return nextStatus || null
}
