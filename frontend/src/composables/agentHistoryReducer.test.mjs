import assert from 'node:assert/strict'
import test from 'node:test'
import { reduceHistoryEvent } from './agentHistoryReducer.js'

function message() { return { content: '', thinking: '', _thinkingDisplay: '', thinkingBlocks: [], toolCalls: [], _nextOrder: 0 } }

test('reduces thinking, tool calls, observations, and permission waits', () => {
  const target = message()
  let pendingChanges = 0
  reduceHistoryEvent('THINK_DELTA', { delta: 'plan' }, target)
  reduceHistoryEvent('TOOL_CALL', { tool: 'write_file', arguments: { path: 'a.js' }, summary: 'write' }, target)
  reduceHistoryEvent('PERMISSION_ASK', { requestId: 'r1', toolName: 'write_file' }, target)
  reduceHistoryEvent('OBSERVE', { result: 'done', pendingChangeId: 'c1' }, target, { onPendingChange: () => pendingChanges++ })

  assert.deepEqual(target.thinkingBlocks.map(block => block.content), ['plan'])
  assert.equal(target.toolCalls[0].status, 'completed')
  assert.equal(target.toolCalls[0].result, 'done')
  assert.equal(target.toolCalls[0].permissionRequest.requestId, 'r1')
  assert.equal(pendingChanges, 1)
})

test('delegates user questions and token accounting while applying final output', () => {
  const target = message()
  const calls = []
  reduceHistoryEvent('USER_QUESTION', { requestId: 'q1' }, target, { onUserQuestion: (_, data) => calls.push(data.requestId) })
  reduceHistoryEvent('TOKEN_USAGE', { totalTokens: 9 }, target, { onTokenUsage: data => calls.push(data.totalTokens) })
  reduceHistoryEvent('FINAL_DELTA', { delta: 'final' }, target)

  assert.deepEqual(calls, ['q1', 9])
  assert.equal(target.content, 'final')
})

test('keeps a replayed question reply card waiting after its failed tool observation', () => {
  const target = message()
  reduceHistoryEvent('TOOL_CALL', { tool: 'question', toolCallId: 'q-call', arguments: {} }, target)
  reduceHistoryEvent('USER_QUESTION', {
    requestId: 'q-replay', question: 'Continue?', summary: 'Choose next step', options: ['Yes']
  }, target)
  target.toolCalls[0].status = 'waiting_user'
  target.toolCalls[0].questionRequest = { requestId: 'q-replay', question: 'Continue?', options: ['Yes'] }
  reduceHistoryEvent('OBSERVE', { success: false, content: 'Waiting for user input.' }, target)

  assert.equal(target.toolCalls[0].status, 'waiting_user')
  assert.equal(target.toolCalls[0].questionRequest.requestId, 'q-replay')
})

test('reconstructs one-time command approval and terminal execution state without loop resume', () => {
  const target = message()
  reduceHistoryEvent('TOOL_CALL', { tool: 'shell', arguments: { command: '<redacted>' }, summary: 'shell' }, target)
  reduceHistoryEvent('COMMAND_APPROVAL_REQUIRED', {
    approvalId: 'approval-71', tool: 'shell', displayCommand: 'npm test --token=<redacted>', resumeAgentLoop: false
  }, target)
  reduceHistoryEvent('COMMAND_EXECUTION_STARTED', { approvalId: 'approval-71', resumeAgentLoop: false }, target)
  reduceHistoryEvent('COMMAND_EXECUTION_COMPLETED', { approvalId: 'approval-71', resumeAgentLoop: false }, target)

  assert.equal(target.toolCalls[0].commandApproval.approvalId, 'approval-71')
  assert.equal(target.toolCalls[0].commandApproval.resumeAgentLoop, false)
  assert.equal(target.toolCalls[0].status, 'completed')
  assert.equal(target.toolCalls[0].args.command, '<redacted>')
})

test('preserves the task id from persisted session events for historical timing lookup', () => {
  const target = message()

  reduceHistoryEvent('SESSION', { taskId: 73 }, target)

  assert.equal(target.taskId, 73)
})


test('records persisted context-management status events in a dedicated timeline', () => {
  const target = message()
  reduceHistoryEvent('COMPACTION_STARTED', { tokensBefore: 12000, tokensAfter: 12000 }, target)
  reduceHistoryEvent('CONTEXT_PRUNED', { tokensBefore: 12000, tokensAfter: 9000 }, target)
  reduceHistoryEvent('COMPACTION_COMPLETED', { tokensBefore: 12000, tokensAfter: 3000 }, target)

  assert.equal(target.thinkingBlocks.length, 0)
  assert.equal(target.contextManagementEvents.length, 2)
  assert.equal(target.contextManagementEvents[0].status, 'completed')
  assert.equal(target.contextManagementEvents[0].releasedTokens, 9000)
  assert.equal(target.contextManagementEvents[1].phase, 'pruned')
  assert.equal(target.contextManagementEvents[1].releasedTokens, 3000)
})


test('records checkout and environment blockers without treating them as completed work', () => {
  const target = message()

  reduceHistoryEvent('WORKSPACE_WAITING', { taskId: 71, blockingTaskId: 59, resumeAutomatically: true }, target)
  reduceHistoryEvent('ENVIRONMENT_BLOCKED', { taskId: 71, blockerCode: 'DNS_UNAVAILABLE', detail: '网络不可用，请先恢复环境。', manualRetryRequired: true }, target)

  assert.equal(target.taskId, 71)
  assert.equal(target.workspaceWaiting.blockingTaskId, 59)
  assert.equal(target.environmentBlocker.blockerCode, 'DNS_UNAVAILABLE')
  assert.equal(target.content, '网络不可用，请先恢复环境。')
})

test('shows compaction progress as one updateable context-management timeline item', () => {
  const target = message()

  reduceHistoryEvent('COMPACTION_STARTED', { strategy: 'proactive', tokensBefore: 40_500, tokensAfter: 40_500 }, target)
  reduceHistoryEvent('COMPACTION_FAILED', { strategy: 'model', tokensBefore: 40_500, reason: '压缩模型不可用' }, target)
  reduceHistoryEvent('COMPACTION_COMPLETED', { strategy: 'deterministic_fallback', tokensBefore: 40_500, tokensAfter: 14_400 }, target)

  assert.equal(target.contextManagementEvents.length, 1)
  assert.deepEqual(target.contextManagementEvents[0], {
    kind: 'context-management',
    phase: 'fallback',
    status: 'completed',
    strategy: 'deterministic_fallback',
    tokensBefore: 40_500,
    tokensAfter: 14_400,
    releasedTokens: 26_100,
    reason: '压缩模型不可用',
    _order: 1
  })
})

test('keeps concurrent compaction tasks isolated by task id', () => {
  const target = message()

  reduceHistoryEvent('COMPACTION_STARTED', { taskId: 71, strategy: 'manual' }, target)
  reduceHistoryEvent('COMPACTION_STARTED', { taskId: 72, strategy: 'proactive' }, target)
  reduceHistoryEvent('COMPACTION_COMPLETED', { taskId: 71, strategy: 'manual_model', tokensBefore: 10_000, tokensAfter: 4_000 }, target)

  assert.equal(target.contextManagementEvents.length, 2)
  assert.equal(target.contextManagementEvents[0].taskId, 71)
  assert.equal(target.contextManagementEvents[0].status, 'completed')
  assert.equal(target.contextManagementEvents[1].taskId, 72)
  assert.equal(target.contextManagementEvents[1].status, 'running')
})
test('keeps a completed tool-result prune as a separate context-management item', () => {
  const target = message()

  reduceHistoryEvent('CONTEXT_PRUNED', { strategy: 'tool_result_prune', tokensBefore: 40_500, tokensAfter: 37_100 }, target)

  assert.equal(target.contextManagementEvents.length, 1)
  assert.equal(target.contextManagementEvents[0].phase, 'pruned')
  assert.equal(target.contextManagementEvents[0].status, 'completed')
  assert.equal(target.contextManagementEvents[0].releasedTokens, 3_400)
})

test('keeps tool-schema reduction as a visible completed recovery strategy', () => {
  const target = message()

  reduceHistoryEvent('CONTEXT_TOOL_SCHEMA_REDUCED', {
    strategy: 'tool_schema_reduction', tokensBefore: 48_000, tokensAfter: 39_000,
    toolCountBefore: 27, toolCountAfter: 12, recoveryAttempt: 2
  }, target)

  assert.equal(target.contextManagementEvents.length, 1)
  assert.equal(target.contextManagementEvents[0].phase, 'tools-reduced')
  assert.equal(target.contextManagementEvents[0].status, 'completed')
  assert.equal(target.contextManagementEvents[0].releasedTokens, 9_000)
  assert.equal(target.contextManagementEvents[0].toolCountBefore, 27)
  assert.equal(target.contextManagementEvents[0].toolCountAfter, 12)
})

test('attaches lifecycle timings to the matching tool call id', () => {
  const target = message()
  reduceHistoryEvent('TOOL_CALL', { tool: 'run_tests', toolCallId: 'call-42', arguments: {} }, target)
  reduceHistoryEvent('TOOL_EXECUTION_STARTED', { toolCallId: 'call-42', phase: 'tool_delegate' }, target)
  reduceHistoryEvent('TOOL_EXECUTION_COMPLETED', {
    toolCallId: 'call-42', phase: 'post_edit_hook', elapsedMs: 1840,
    delegateMs: 1200, beforeSnapshotMs: 100, afterSnapshotMs: 50, snapshotDiffMs: 200, postEditMs: 150, contextMs: 140
  }, target)

  assert.equal(target.toolCalls[0].toolCallId, 'call-42')
  assert.equal(target.toolCalls[0].execution.phase, 'post_edit_hook')
  assert.equal(target.toolCalls[0].execution.elapsedMs, 1840)
  assert.equal(target.toolCalls[0].execution.delegateMs, 1200)
})


test('marks a tool watchdog timeout as an errored tool call with the captured budget', () => {
  const target = message()
  reduceHistoryEvent('TOOL_CALL', { tool: 'mcp_call', toolCallId: 'call-timeout', arguments: {} }, target)
  reduceHistoryEvent('TOOL_TIMED_OUT', {
    toolCallId: 'call-timeout', phase: 'tool_delegate', elapsedMs: 90000, budgetMs: 90000, timedOut: true
  }, target)

  assert.equal(target.toolCalls[0].status, 'error')
  assert.equal(target.toolCalls[0].execution.elapsedMs, 90000)
  assert.equal(target.toolCalls[0].execution.phase, 'tool_delegate')
})


test('restores context limit blocker evidence from persisted history', () => {
  const target = message()
  const data = {
    taskId: 81, reasonCode: 'static_context_exceeds_input_capacity',
    message: 'Tool schemas exceed the current model capacity', remediation: ['Reduce tool schemas'],
    budget: { staticTokens: 12000, reducibleTokens: 0, reservedOutputTokens: 4096 }
  }

  reduceHistoryEvent('CONTEXT_LIMIT_BLOCKED', data, target)

  assert.equal(target.taskId, 81)
  assert.deepEqual(target.contextLimitBlocker, data)
  assert.equal(target.environmentBlocker.blockerCode, 'static_context_exceeds_input_capacity')
})


test('replays completion evidence card and terminal state', () => {
  const target = message()
  const evidence = { taskId: 91, changedFiles: [], successfulVerifications: [], failedVerifications: [], unresolvedRisks: [], satisfied: true }
  reduceHistoryEvent('COMPLETION_EVIDENCE', evidence, target)
  reduceHistoryEvent('RUN_STATE_COMPLETED', { taskId: 91, state: 'completed' }, target)
  assert.deepEqual(target.completionEvidence, evidence)
  assert.equal(target.runState, 'completed')
})

test('renders unavailable post-edit diagnostics as a warning instead of success', () => {
  const target = message()
  reduceHistoryEvent('TOOL_CALL', { tool: 'edit_file', arguments: {}, toolCallId: 'edit-1' }, target, {})
  reduceHistoryEvent('OBSERVE', { success: true, result: 'edited\n[Post-edit hooks]\n- status=UNAVAILABLE' }, target, {})

  assert.equal(target.toolCalls[0].status, 'warning')
  assert.equal(target.toolCalls[0].verificationStatus, 'UNAVAILABLE')
})

test('replayed command continuation keeps the original approval card running', () => {
  const target = message()
  target.toolCalls.push({
    name: 'run_tests',
    status: 'waiting_approval',
    result: null,
    commandApproval: { approvalId: 'approval-72' }
  })

  reduceHistoryEvent('RUN_COMMAND_APPROVAL_RESUME_QUEUED', { taskId: 72, approvalId: 'approval-72' }, target)

  assert.equal(target.taskId, 72)
  assert.equal(target.toolCalls[0].status, 'running')
  assert.equal(target.toolCalls[0].durableStatus, 'resuming')
  assert.equal(target.toolCalls[0].result, '命令结果已保存，正在恢复 Agent 任务')
})

test('replayed command continuation waiting for lease keeps the approval card visible', () => {
  const target = message()
  target.toolCalls.push({
    name: 'run_tests',
    status: 'waiting_approval',
    result: null,
    commandApproval: { approvalId: 'approval-73' }
  })

  reduceHistoryEvent('COMMAND_APPROVAL_RESUME_DEFERRED', { taskId: 73, approvalId: 'approval-73' }, target)

  assert.equal(target.taskId, 73)
  assert.equal(target.toolCalls[0].status, 'running')
  assert.equal(target.toolCalls[0].durableStatus, 'waiting_resume')
  assert.equal(target.toolCalls[0].result, '等待旧执行器释放后自动恢复')
})

test('replayed interaction resume hides every duplicate card for the same durable request', () => {
  const target = message()
  target.toolCalls.push(
    { name: 'question', toolCallId: 'question-call', status: 'waiting_user', questionRequest: { requestId: 'interaction-72' } },
    { name: 'question', status: 'waiting_user', questionRequest: { requestId: 'interaction-72' } },
    { name: 'read_file', status: 'waiting_approval', permissionRequest: { requestId: 'other-interaction' } }
  )

  reduceHistoryEvent('RUN_INTERACTION_RESUME_QUEUED', { taskId: 72, interactionId: 'interaction-72' }, target)

  assert.equal(target.toolCalls.length, 2)
  assert.equal(target.toolCalls[0].toolCallId, 'question-call')
  assert.equal(target.toolCalls[0].status, 'running')
  assert.equal(target.toolCalls[0].durableStatus, 'resuming')
  assert.equal(target.toolCalls[1].status, 'waiting_approval')
})

test('removes internal reasoning delimiters from replayed events', () => {
  const target = message()
  reduceHistoryEvent('THINK_DELTA', { delta: '<THINK>checking</THINK>' }, target)
  reduceHistoryEvent('FINAL_DELTA', { delta: 'result <thinking>private</thinking> visible' }, target)

  assert.equal(target.thinking, 'checking')
  assert.equal(target.content, 'result  visible')
  assert.equal(target.thinking.includes('<'), false)
  assert.equal(target.content.includes('<'), false)
})

test('suppresses split internal reasoning blocks during replay', () => {
  const target = message()
  reduceHistoryEvent('FINAL_DELTA', { delta: 'visible <TH' }, target)
  reduceHistoryEvent('FINAL_DELTA', { delta: 'INK>private plan' }, target)
  reduceHistoryEvent('FINAL_DELTA', { delta: '</THINK> answer' }, target)
  assert.equal(target.content, 'visible  answer')
})


test('filters split internal reasoning tags while replaying persisted THINK_DELTA events', () => {
  const target = message()

  reduceHistoryEvent('THINK_START', {}, target)
  reduceHistoryEvent('THINK_DELTA', { delta: '<thi' }, target)
  reduceHistoryEvent('THINK_DELTA', { delta: 'nk>private plan</THINK' }, target)
  reduceHistoryEvent('THINK_DELTA', { delta: 'ING>' }, target)

  assert.equal(target.thinking, 'private plan')
  assert.equal(target._thinkingDisplay, 'private plan')
})


test('removes protocol delimiters from replayed reasoning summaries', () => {
  const target = message()

  reduceHistoryEvent('THINK_START', { summary: '\\<think\\>hidden\\</think\\>Analyze problem' }, target)
  reduceHistoryEvent('THINK_DELTA', { delta: 'Check boundary' }, target)
  reduceHistoryEvent('THINK', { content: 'Check boundary', summary: '\\<thinking\\>hidden\\</thinking\\>Analysis complete' }, target)

  assert.equal(target.thinkingBlocks[0].summary, 'Analysis complete')
})
