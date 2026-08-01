const RESTART_RECONNECT_ENDPOINT_500 = /\/api\/student\/projects\/\d+\/agent\/(?:tasks\/\d+\/subscribe|conversations\/[^/?]+\/active-task)(?:\s|$|[?#])/i
const RECONNECT_500 = /Agent task subscription disconnected; reconnecting from durable cursor: Error: HTTP 500/i

export function isExpectedRestartTransportError(message, verification = {}) {
  const restartVerified = verification.restartProjectionVerified === true
    || verification.restartInteractionVerified === true
  if (!restartVerified) return false
  const text = String(message || '')
  return RECONNECT_500.test(text) || (/status of 500|HTTP 500/i.test(text) && RESTART_RECONNECT_ENDPOINT_500.test(text))
}
