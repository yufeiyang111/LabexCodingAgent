import assert from 'node:assert/strict'
import test from 'node:test'
import { createTokenUsageState } from './cacheTelemetryStatus.js'

import { useAgentEventTimeline } from './useAgentEventTimeline.js'

function message() {
  return {
    taskId: null,
    thinking: '',
    _thinkingDisplay: '',
    thinkingBlocks: [],
    toolCalls: [],
    content: '',
    error: null,
    isStreaming: true,
    _nextOrder: 0,
    timing: {}
  }
}

function harness() {
  const calls = []
  const currentAgentSession = { value: null }
  const agentLoading = { value: true }
  const contextUsageStatus = { value: null }
  const tokenUsage = { value: createTokenUsageState() }
  const sessionHistory = { value: [] }
  const changesRefreshKey = { value: 0 }
  const timeline = useAgentEventTimeline({
    recordTaskEventCursor: (...args) => calls.push(['cursor', ...args]),
    currentAgentSession,
    scheduleAgentRender: () => calls.push(['render']),
    startThinkingReveal: assistant => { assistant._thinkingDisplay = assistant.thinking },
    flushThinkingDisplay: assistant => { assistant._thinkingDisplay = assistant.thinking },
    trackFileChange: (...args) => calls.push(['change', ...args]),
    changesRefreshKey,
    attachCommandApproval: (...args) => calls.push(['command', ...args]),
    updateCommandApprovalLifecycle: (...args) => calls.push(['commandLifecycle', ...args]),
    attachUserQuestion: (...args) => calls.push(['question', ...args]),
    stopMessageTimer: (...args) => calls.push(['stopTimer', ...args]),
    agentLoading,
    logTaskRecovery: (...args) => calls.push(['recovery', ...args]),
    contextUsageStatus,
    reduceContextManagementEvent: (...args) => calls.push(['context', ...args]),
    tokenUsage,
    sessionHistory,
    currentSessionName: { value: '会话 A' },
    onTokenUsageProjected: data => calls.push(['tokenUsageProjected', data])
  })
  return { ...timeline, calls, currentAgentSession, agentLoading, contextUsageStatus, tokenUsage, sessionHistory, changesRefreshKey }
}

test('owns session, thinking, final, done, and cursor lifecycle', () => {
  const state = harness()
  const assistant = message()
  state.handleAgentEvent({ type: 'SESSION', eventId: '1', data: { taskId: 9, conversationId: 'c1' } }, assistant)
  state.handleAgentEvent({ type: 'THINK_DELTA', eventId: '2', data: { taskId: 9, delta: '检查' } }, assistant)
  state.handleAgentEvent({ type: 'FINAL_DELTA', eventId: '3', data: { taskId: 9, delta: '完成' } }, assistant)
  state.handleAgentEvent({ type: 'DONE', eventId: '4', data: { taskId: 9 } }, assistant)

  assert.equal(state.currentAgentSession.value.conversationId, 'c1')
  assert.equal(assistant.taskId, 9)
  assert.equal(assistant.thinking, '检查')
  assert.equal(assistant.content, '完成')
  assert.equal(assistant.isStreaming, false)
  assert.equal(state.agentLoading.value, false)
  assert.deepEqual(state.calls.filter(call => call[0] === 'cursor').map(call => call.slice(1)), [[9, '1'], [9, '2'], [9, '3'], [9, '4']])
})

test('owns tool execution, observations, and change refresh', () => {
  const state = harness()
  const assistant = message()
  state.handleAgentEvent({ type: 'TOOL_CALL', data: { taskId: 4, tool: 'write_file', toolCallId: 'tc1', arguments: { file_path: 'a.txt' } } }, assistant)
  state.handleAgentEvent({ type: 'TOOL_PHASE_CHANGED', data: { taskId: 4, toolCallId: 'tc1', phase: 'post_edit', elapsedMs: 12 } }, assistant)
  state.handleAgentEvent({ type: 'OBSERVE', data: { taskId: 4, success: true, content: 'ok', diff: 'd', pendingChangeId: 'p1' } }, assistant)

  assert.equal(assistant.toolCalls[0].status, 'completed')
  assert.equal(assistant.toolCalls[0].execution.phase, 'post_edit')
  assert.equal(assistant.toolCalls[0].result, 'ok')
  assert.equal(assistant.toolCalls[0].hasDiff, true)
  assert.equal(state.changesRefreshKey.value, 1)
  assert.equal(state.calls.filter(call => call[0] === 'change').length, 1)
})

test('routes question, permission, command approval, and pause events', () => {
  const state = harness()
  const assistant = message()
  assistant.toolCalls.push({ name: 'read_file', status: 'running' })

  state.handleAgentEvent({ type: 'NETWORK_ACCESS_ASK', data: {
    taskId: 7, toolName: 'run_tests', toolCallId: 'network-call', requestId: 'n', request: 'mvn test'
  } }, assistant)
  state.handleAgentEvent({ type: 'PERMISSION_ASK', data: { taskId: 7, toolName: 'read_file', requestId: 'p' } }, assistant)
  state.handleAgentEvent({ type: 'USER_QUESTION', data: { taskId: 7, requestId: 'q' } }, assistant)
  state.handleAgentEvent({ type: 'COMMAND_APPROVAL_REQUIRED', data: { taskId: 7, approvalId: 'a' } }, assistant)
  state.handleAgentEvent({ type: 'COMMAND_EXECUTION_COMPLETED', data: { taskId: 7, approvalId: 'a' } }, assistant)
  state.handleAgentEvent({ type: 'TASK_PAUSED', data: { taskId: 7, reason: 'command_approval', resumeAgentLoop: true } }, assistant)

  const networkCall = assistant.toolCalls.find(call => call.networkRequest?.requestId === 'n')
  assert.equal(networkCall.status, 'waiting_approval')
  assert.equal(networkCall.networkRequest.requestId, 'n')
  assert.equal(state.calls.filter(call => call[0] === 'question').length, 1)
  assert.equal(state.calls.filter(call => call[0] === 'command').length, 1)
  assert.equal(state.calls.filter(call => call[0] === 'commandLifecycle').length, 1)
  assert.equal(state.calls.filter(call => call[0] === 'render').length >= 1, true)
  assert.equal(assistant.waitingForCommandApproval, true)
  assert.equal(assistant.resumeTaskEventsAfterStream, true)
  assert.equal(assistant.taskId, 7)
})

test('schedules a render after a live user question event', () => {
  const state = harness()
  const assistant = message()
  const before = state.calls.filter(call => call[0] === 'render').length

  state.handleAgentEvent({
    type: 'USER_QUESTION',
    data: { taskId: 12, requestId: 'q-live', question: 'Continue?', summary: 'Choose next step', options: ['Yes'] }
  }, assistant)

  assert.equal(state.calls.filter(call => call[0] === 'question').length, 1)
  assert.equal(state.calls.filter(call => call[0] === 'render').length > before, true)
})

test('keeps a live question reply card waiting after its failed tool observation', () => {
  const state = harness()
  const assistant = message()
  assistant.toolCalls.push({ name: 'question', status: 'running', toolCallId: 'q-call' })

  state.handleAgentEvent({
    type: 'USER_QUESTION',
    data: { taskId: 12, requestId: 'q-live', question: 'Continue?', summary: 'Choose next step', options: ['Yes'] }
  }, assistant)
  assistant.toolCalls[0].status = 'waiting_user'
  assistant.toolCalls[0].questionRequest = { requestId: 'q-live', question: 'Continue?', options: ['Yes'] }
  state.handleAgentEvent({
    type: 'OBSERVE',
    data: { taskId: 12, success: false, content: 'Waiting for user input.' }
  }, assistant)

  assert.equal(assistant.toolCalls[0].status, 'waiting_user')
  assert.equal(assistant.toolCalls[0].questionRequest.requestId, 'q-live')
})

test('routes compaction/context status and accumulates token usage', () => {
  const state = harness()
  const assistant = message()
  state.currentAgentSession.value = { conversationId: 'c1' }

  state.handleAgentEvent({ type: 'CONTEXT_STATUS', data: { state: 'READY' } }, assistant)
  state.handleAgentEvent({ type: 'COMPACTION_STARTED', data: { taskId: 10 } }, assistant)
  state.handleAgentEvent({ type: 'COMPACTION_COMPLETED', data: { taskId: 10 } }, assistant)
  state.handleAgentEvent({ type: 'CONTEXT_TOOL_SCHEMA_REDUCED', data: { taskId: 10 } }, assistant)
  state.handleAgentEvent({ type: 'TOKEN_USAGE', data: { promptTokens: 10, completionTokens: 5, totalTokens: 15, conversationTotal: 20, cachedTokens: 4, cacheWriteTokens: 6, cacheStatus: 'hit', cacheTelemetryReported: true } }, assistant)

  assert.equal(state.contextUsageStatus.value.state, 'READY')
  assert.deepEqual(state.calls.filter(call => call[0] === 'context').map(call => call[1]), ['COMPACTION_STARTED', 'COMPACTION_COMPLETED', 'CONTEXT_TOOL_SCHEMA_REDUCED'])
  assert.deepEqual(state.tokenUsage.value, { ...createTokenUsageState(), promptTokens: 10, completionTokens: 5, totalTokens: 15, callCount: 1, conversationTotal: 20, cachedTokens: 4, cacheWriteTokens: 6, cacheStatus: 'hit', cacheTelemetryReported: true, cacheTelemetryCallCount: 1, cacheReportedPromptTokens: 10, cacheHitRate: 40 })
  assert.equal(state.sessionHistory.value[0].conversationId, 'c1')
  assert.equal(state.calls.filter(call => call[0] === 'tokenUsageProjected').length, 1)
})

test('preserves provider error over a later final and handles blockers', () => {
  const state = harness()
  const assistant = message()
  state.handleAgentEvent({ type: 'ERROR', data: { taskId: 8, message: 'provider failed' } }, assistant)
  state.handleAgentEvent({ type: 'FINAL', data: { taskId: 8, content: 'fallback text' } }, assistant)
  assert.equal(assistant.content, '错误：provider failed')
  assert.equal(assistant.error, 'provider failed')

  const blocked = message()
  state.handleAgentEvent({ type: 'ENVIRONMENT_BLOCKED', data: { taskId: 11, detail: 'missing runtime' } }, blocked)
  assert.equal(blocked.taskId, 11)
  assert.equal(blocked.content, 'missing runtime')
  assert.equal(blocked.isStreaming, false)
})


test('renders a context admission blocker as durable actionable state', () => {
  const state = harness()
  const assistant = message()
  const data = {
    taskId: 19,
    reasonCode: 'static_context_exceeds_input_capacity',
    message: 'Static context exceeds the model capacity',
    remediation: ['Use a larger model', 'Reduce tool schemas'],
    budget: { staticTokens: 9000, reducibleTokens: 100, reservedOutputTokens: 2000 }
  }

  state.handleAgentEvent({ type: 'CONTEXT_LIMIT_BLOCKED', data }, assistant)

  assert.equal(assistant.taskId, 19)
  assert.deepEqual(assistant.contextLimitBlocker, data)
  assert.ok(assistant.content.includes(data.message))
  assert.equal(assistant.isStreaming, false)
})


test('attaches server completion evidence before terminal completion', () => {
  const state = harness()
  const assistant = message()
  assistant.toolCalls.push({
    name: 'question',
    status: 'waiting_user',
    interactionStatus: 'resuming',
    questionRequest: { requestId: 'question-22' }
  })
  const evidence = { taskId: 22, changedFiles: ['src/App.vue'], successfulVerifications: ['npm run build'], failedVerifications: [], unresolvedRisks: [], satisfied: true }
  state.handleAgentEvent({ type: 'COMPLETION_EVIDENCE', data: evidence }, assistant)
  state.handleAgentEvent({ type: 'RUN_STATE_COMPLETED', data: { taskId: 22, state: 'completed' } }, assistant)
  assert.deepEqual(assistant.completionEvidence, evidence)
  assert.equal(assistant.runState, 'completed')
  assert.equal(assistant.toolCalls[0].status, 'completed')
  assert.equal(assistant.toolCalls[0].interactionStatus, 'resolved')
})

test('replays a durable thinking snapshot after a refresh during an active task', () => {
  const state = harness()
  const assistant = message()
  state.handleAgentEvent({ type: 'THINK_SNAPSHOT', eventId: 'snapshot-1', data: { taskId: 9, content: 'analyzing file' } }, assistant)

  assert.equal(assistant.thinking, 'analyzing file')
  assert.equal(assistant._thinkingDisplay, 'analyzing file')
})

test('live interaction resume hides duplicate waiting cards without touching another request', () => {
  const state = harness()
  const assistant = message()
  assistant.toolCalls.push(
    { name: 'question', toolCallId: 'question-call', status: 'waiting_user', questionRequest: { requestId: 'interaction-72' } },
    { name: 'question', status: 'waiting_user', questionRequest: { requestId: 'interaction-72' } },
    { name: 'read_file', status: 'waiting_approval', permissionRequest: { requestId: 'other-interaction' } }
  )

  state.handleAgentEvent({
    type: 'RUN_INTERACTION_RESUME_QUEUED',
    data: { taskId: 72, interactionId: 'interaction-72' }
  }, assistant)

  assert.equal(assistant.toolCalls.length, 2)
  assert.equal(assistant.toolCalls[0].toolCallId, 'question-call')
  assert.equal(assistant.toolCalls[0].status, 'running')
  assert.equal(assistant.toolCalls[0].durableStatus, 'resuming')
  assert.equal(assistant.toolCalls[1].status, 'waiting_approval')
  assert.equal(state.calls.some(call => call[0] === 'render'), true)
})


test('live command continuation delegates the durable resume event to the original approval card', () => {
  const state = harness()
  const assistant = message()

  state.handleAgentEvent({
    type: 'RUN_COMMAND_APPROVAL_RESUME_QUEUED',
    eventId: 'command-resume-1',
    data: { taskId: 72, approvalId: 'approval-72' }
  }, assistant)

  assert.equal(assistant.taskId, 72)
  assert.equal(state.calls.some(call => call[0] === 'commandLifecycle'
    && call[1] === assistant
    && call[2] === 'RUN_COMMAND_APPROVAL_RESUME_QUEUED'), true)
  assert.equal(state.calls.some(call => call[0] === 'render'), true)
})

test('live command continuation deferral remains a visible lifecycle update', () => {
  const state = harness()
  const assistant = message()

  state.handleAgentEvent({
    type: 'COMMAND_APPROVAL_RESUME_DEFERRED',
    eventId: 'command-resume-deferred-1',
    data: { taskId: 73, approvalId: 'approval-73' }
  }, assistant)

  assert.equal(assistant.taskId, 73)
  assert.equal(state.calls.some(call => call[0] === 'commandLifecycle'
    && call[2] === 'COMMAND_APPROVAL_RESUME_DEFERRED'), true)
  assert.equal(state.calls.some(call => call[0] === 'render'), true)
})

test('removes internal reasoning delimiters from live events', () => {
  const state = harness()
  const assistant = message()
  state.handleAgentEvent({ type: 'THINK_DELTA', data: { delta: '<thinking>checking</thinking>' } }, assistant)
  state.handleAgentEvent({ type: 'FINAL_DELTA', data: { delta: 'done <THINK>private</THINK>' } }, assistant)

  assert.equal(assistant.thinking, 'checking')
  assert.equal(assistant.content, 'done ')
  assert.equal(assistant.thinking.includes('<'), false)
  assert.equal(assistant.content.includes('<'), false)
})

test('suppresses split internal reasoning blocks during live streaming', () => {
  const state = harness()
  const assistant = message()
  state.handleAgentEvent({ type: 'FINAL_DELTA', data: { delta: 'visible <TH' } }, assistant)
  state.handleAgentEvent({ type: 'FINAL_DELTA', data: { delta: 'INK>private plan' } }, assistant)
  state.handleAgentEvent({ type: 'FINAL_DELTA', data: { delta: '</THINK> answer' } }, assistant)
  assert.equal(assistant.content, 'visible  answer')
})


test('filters split internal reasoning tags across live THINK_DELTA events', () => {
  const state = harness()
  const assistant = message()

  state.handleAgentEvent({ type: 'THINK_START', data: {} }, assistant)
  state.handleAgentEvent({ type: 'THINK_DELTA', data: { delta: '<thi' } }, assistant)
  state.handleAgentEvent({ type: 'THINK_DELTA', data: { delta: 'nk>private plan</THINK' } }, assistant)
  state.handleAgentEvent({ type: 'THINK_DELTA', data: { delta: 'ING>' } }, assistant)

  assert.equal(assistant.thinking, 'private plan')
  assert.equal(assistant._thinkingDisplay, 'private plan')
})


test('removes protocol delimiters from live reasoning summaries', () => {
  const state = harness()
  const assistant = message()

  state.handleAgentEvent({ type: 'THINK_START', data: { summary: '\\<think\\>hidden\\</think\\>Analyze problem' } }, assistant)
  state.handleAgentEvent({ type: 'THINK_DELTA', data: { delta: 'Check boundary' } }, assistant)
  state.handleAgentEvent({ type: 'THINK', data: { content: 'Check boundary', summary: '\\<thinking\\>hidden\\</thinking\\>Analysis complete' } }, assistant)

  assert.equal(assistant.thinkingBlocks[0].summary, 'Analysis complete')
})
