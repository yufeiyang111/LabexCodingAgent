const TERMINAL_RUN_STATES = new Set(['completed', 'failed', 'cancelled'])
const RECOVERABLE_RUN_STATES = new Set([
  'waiting_approval',
  'waiting_user',
  'waiting_workspace',
  'waiting_environment',
  'retrying',
  'retry_backoff'
])

export function normalizeAgentRunState(value) {
  return String(value || '').trim().toLowerCase()
}

export function isTerminalAgentRunState(value) {
  return TERMINAL_RUN_STATES.has(normalizeAgentRunState(value))
}

export function isRecoverableAgentRunState(value) {
  return RECOVERABLE_RUN_STATES.has(normalizeAgentRunState(value))
}
