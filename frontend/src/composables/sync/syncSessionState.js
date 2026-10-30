// 唯一职责：同步会话的状态机（State 模式）——定义合法状态与合法迁移，非法迁移显式抛错。
// 边界：不做 IO、不判定冲突、不渲染 UI；只回答"当前处于哪个状态、能否迁移到下一个"。
// 纪律：禁止静默降级——非法迁移必须抛错，而不是"兜底回 idle"。

export const SYNC_STATES = Object.freeze({
  IDLE: 'idle',
  SCANNING: 'scanning',
  PLANNING: 'planning',
  BLOCKED: 'blocked',
  APPLYING: 'applying',
  ROLLING_BACK: 'rolling-back',
  DONE: 'done',
  FAILED: 'failed'
})

const TRANSITIONS = Object.freeze({
  [SYNC_STATES.IDLE]: [SYNC_STATES.SCANNING, SYNC_STATES.FAILED],
  [SYNC_STATES.SCANNING]: [SYNC_STATES.PLANNING, SYNC_STATES.FAILED],
  [SYNC_STATES.PLANNING]: [SYNC_STATES.APPLYING, SYNC_STATES.BLOCKED, SYNC_STATES.DONE, SYNC_STATES.FAILED],
  [SYNC_STATES.BLOCKED]: [SYNC_STATES.SCANNING, SYNC_STATES.APPLYING, SYNC_STATES.IDLE, SYNC_STATES.FAILED],
  [SYNC_STATES.APPLYING]: [SYNC_STATES.DONE, SYNC_STATES.ROLLING_BACK, SYNC_STATES.FAILED],
  [SYNC_STATES.ROLLING_BACK]: [SYNC_STATES.IDLE, SYNC_STATES.FAILED],
  [SYNC_STATES.DONE]: [SYNC_STATES.SCANNING, SYNC_STATES.ROLLING_BACK, SYNC_STATES.IDLE, SYNC_STATES.FAILED],
  [SYNC_STATES.FAILED]: [SYNC_STATES.IDLE, SYNC_STATES.SCANNING]
})

export function createSyncSession(overrides = {}) {
  return {
    state: SYNC_STATES.IDLE,
    cursor: 0,
    summary: null,
    conflicts: [],
    error: null,
    applied: 0,
    updatedAt: null,
    ...overrides
  }
}

export function canTransition(from, to) {
  const allowed = TRANSITIONS[from]
  return Array.isArray(allowed) && allowed.includes(to)
}

export function transition(session, to, patch = {}) {
  const current = session?.state ?? SYNC_STATES.IDLE
  if (current === to) return { ...session, ...patch }
  if (!canTransition(current, to)) {
    throw new Error(`illegal sync state transition: ${current} -> ${to}`)
  }
  return { ...session, ...patch, state: to }
}

export function isTerminalState(state) {
  return state === SYNC_STATES.DONE || state === SYNC_STATES.FAILED
}

export function isBusyState(state) {
  return state === SYNC_STATES.SCANNING
    || state === SYNC_STATES.PLANNING
    || state === SYNC_STATES.APPLYING
    || state === SYNC_STATES.ROLLING_BACK
}

export function describeSession(session) {
  const state = session?.state ?? SYNC_STATES.IDLE
  const summary = session?.summary ?? null
  return {
    state,
    busy: isBusyState(state),
    terminal: isTerminalState(state),
    cursor: Number(session?.cursor ?? 0) || 0,
    applied: Number(session?.applied ?? 0) || 0,
    conflictCount: Array.isArray(session?.conflicts) ? session.conflicts.length : 0,
    total: summary ? Number(summary.toApply ?? 0) : 0,
    error: session?.error ?? null
  }
}
