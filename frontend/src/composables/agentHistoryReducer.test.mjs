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


test('routes an observation to its durable toolCallId instead of the last tool card', () => {
  const target = message()
  reduceHistoryEvent('TOOL_CALL', { tool: 'read_file', toolCallId: 'call-read', arguments: { file_path: 'README.md' } }, target)
  reduceHistoryEvent('TOOL_CALL', { tool: 'list_files', toolCallId: 'call-list', arguments: { path: 'src' } }, target)

  reduceHistoryEvent('OBSERVE', { toolCallId: 'call-read', success: true, result: 'README content' }, target)

  assert.equal(target.toolCalls[0].result, 'README content')
  assert.equal(target.toolCalls[0].status, 'completed')
  assert.equal(target.toolCalls[1].result, null)
  assert.equal(target.toolCalls[1].status, 'running')
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

test('drops legacy mis-narrated thinking blocks below a question card', () => {
  const target = message()
  reduceHistoryEvent('THINK_DELTA', { delta: '在 question 上遇到错误：正在等待用户输入。' }, target)
  reduceHistoryEvent('THINK', { content: '在 question 上遇到错误：正在等待用户输入。。需要换一种路径处理。', summary: '检查结果' }, target)

  assert.equal(target.thinking, '')
  assert.equal(target.thinkingBlocks.length, 0)
})

test('keeps the answered question narration after interaction resume', () => {
  const target = message()
  reduceHistoryEvent('THINK', { content: '已收到用户输入，继续按新的信息执行。', summary: '检查结果' }, target)

  assert.equal(target.thinkingBlocks.length, 1)
  assert.equal(target.thinkingBlocks[0].content, '已收到用户输入，继续按新的信息执行。')
})

test('reconstructs one-time command approval and terminal execution state without loop resume', () => {
  const target = message()
  reduceHistoryEvent('TOOL_CALL', { tool: 'shell', toolCallId: 'command-call-71', arguments: { command: '<redacted>' }, summary: 'shell' }, target)
  reduceHistoryEvent('COMMAND_APPROVAL_REQUIRED', {
    approvalId: 'approval-71', toolCallId: 'command-call-71', tool: 'shell', displayCommand: 'npm test --token=<redacted>', resumeAgentLoop: false
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

test('keeps a replayed environment wait authoritative over legacy FINAL and DONE events', () => {
  const target = message()
  reduceHistoryEvent('ENVIRONMENT_BLOCKED', {
    taskId: 71, taskStatus: 'waiting_environment', detail: 'Restore DNS and retry.'
  }, target)
  reduceHistoryEvent('TASK_PAUSED', {
    taskId: 71, taskStatus: 'waiting_environment', reason: 'environment', resumeAgentLoop: false
  }, target)
  reduceHistoryEvent('FINAL', { content: 'Legacy pseudo final must stay hidden.' }, target)
  reduceHistoryEvent('DONE', { taskStatus: 'waiting_environment' }, target)

  assert.equal(target.content, 'Restore DNS and retry.')
  assert.equal(target.runState, 'waiting_environment')
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


test('replays the authoritative model retry lifecycle', () => {
  const target = message()

  reduceHistoryEvent('RUN_MODEL_RETRY_SCHEDULED', { taskId: 81, state: 'retrying', attempt: 1 }, target)
  assert.equal(target.taskId, 81)
  assert.equal(target.runState, 'retrying')
  assert.equal(target.isStreaming, false)

  reduceHistoryEvent('RUN_MODEL_RETRY_STARTED', { taskId: 81, state: 'recovering', attempt: 1 }, target)
  assert.equal(target.runState, 'recovering')
  assert.equal(target.isStreaming, true)
})

test('replays a bounded retry terminal failure as visible content', () => {
  const target = message()
  target.runState = 'running'

  reduceHistoryEvent('ERROR', { taskId: 81, message: 'provider failed after bounded retries' }, target)
  reduceHistoryEvent('FINAL', { taskId: 81, content: 'fallback text' }, target)
  reduceHistoryEvent('RUN_STATE_FAILED', { taskId: 81, state: 'failed' }, target)
  reduceHistoryEvent('DONE', { taskId: 81 }, target)

  assert.equal(target.runState, 'failed')
  assert.equal(target.error, 'provider failed after bounded retries')
  assert.equal(target.content, '错误：provider failed after bounded retries')
  assert.equal(target.isStreaming, false)
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

test('replays a nonzero shell observation as an error even when transport succeeded', () => {
  const target = message()
  reduceHistoryEvent('TOOL_CALL', { tool: 'shell', toolCallId: 'shell-history-1', arguments: { command: 'npm run build' } }, target, {})
  reduceHistoryEvent('TOOL_CALL_STATE', {
    tool: 'shell',
    toolCallId: 'shell-history-1',
    status: 'completed',
    metadata: { failureClass: 'non_zero_exit', execution: { status: 'failed', exitCode: 2, workdir: 'frontend' } }
  }, target, {})
  reduceHistoryEvent('OBSERVE', {
    toolCallId: 'shell-history-1',
    success: true,
    result: 'exit=2\nstatus=failed',
    executionStatus: 'failed'
  }, target, {})

  assert.equal(target.toolCalls[0].status, 'error')
  assert.equal(target.toolCalls[0].durableStatus, 'completed')
  assert.equal(target.toolCalls[0].failureClass, 'non_zero_exit')
  assert.equal(target.toolCalls[0].executionResult.exitCode, 2)
  assert.equal(target.toolCalls[0].executionResult.workdir, 'frontend')
})
test('replayed command continuation accepts the durable final after returning to running', () => {
  const target = message()
  target.runState = 'waiting_approval'
  target.toolCalls.push({
    name: 'shell',
    toolCallId: 'command-call-74',
    status: 'waiting_approval',
    commandApproval: { approvalId: 'approval-74', toolCallId: 'command-call-74' }
  })

  reduceHistoryEvent('RUN_COMMAND_APPROVAL_RESUME_QUEUED', {
    taskId: 74, approvalId: 'approval-74', toolCallId: 'command-call-74'
  }, target)
  reduceHistoryEvent('RUN_STATE_RECOVERING', { taskId: 74 }, target)
  reduceHistoryEvent('RUN_STATE_RUNNING', { taskId: 74 }, target)
  reduceHistoryEvent('FINAL', { taskId: 74, content: 'command approval resumed final' }, target)
  reduceHistoryEvent('RUN_STATE_COMPLETED', { taskId: 74 }, target)

  assert.equal(target.content, 'command approval resumed final')
  assert.equal(target.hasDurableFinal, true)
  assert.equal(target.runState, 'completed')
  assert.equal(target.isStreaming, false)
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


test('history replay keeps a terminal provider error visible after partial content', () => {
  const target = message()
  target.content = 'Partial response before disconnect.'

  reduceHistoryEvent('ERROR', { taskId: 91, message: 'Model API failed after bounded retries' }, target)

  assert.equal(target.error, 'Model API failed after bounded retries')
  assert.equal(target.content, 'Partial response before disconnect.\n\n\u9519\u8bef\uff1aModel API failed after bounded retries')
  assert.equal(target.isStreaming, false)
})

test('replayed TOOL_CALL events merge by toolCallId instead of duplicating cards', () => {
  const target = message()
  target.toolCalls.push({
    name: 'shell', toolCallId: 'shell-call-1', args: { command: 'npm run build' },
    summary: 'build', result: null, status: 'running'
  })

  reduceHistoryEvent('TOOL_CALL', {
    tool: 'shell', toolCallId: 'shell-call-1', arguments: { command: 'npm run build' }, summary: 'build'
  }, target)
  reduceHistoryEvent('OBSERVE', { toolCallId: 'shell-call-1', success: true, result: 'status=succeeded' }, target)

  assert.equal(target.toolCalls.length, 1)
  assert.equal(target.toolCalls[0].toolCallId, 'shell-call-1')
  assert.equal(target.toolCalls[0].result, 'status=succeeded')
})

test('snapshot hydration followed by TOOL_CALL replay stays idempotent', () => {
  const target = message()
  target.toolCalls.push({
    name: 'run_tests', toolCallId: 'tests-call-2', args: { strategy: 'test' },
    summary: 'run_tests', result: null, status: 'running', durableStatus: 'running'
  })

  reduceHistoryEvent('TOOL_CALL', { tool: 'run_tests', toolCallId: 'tests-call-2', arguments: {} }, target)

  assert.equal(target.toolCalls.length, 1)
  assert.deepEqual(target.toolCalls[0].args, { strategy: 'test' })
})

test('replayed OBSERVE projects structured executionStatus instead of guessing text', () => {
  const target = message()
  reduceHistoryEvent('TOOL_CALL', { tool: 'shell', toolCallId: 'shell-replay', arguments: {} }, target)

  reduceHistoryEvent('OBSERVE', {
    toolCallId: 'shell-replay', success: false, content: 'status=timed_out', executionStatus: 'timed_out'
  }, target)

  assert.equal(target.toolCalls[0].status, 'error')
  assert.equal(target.toolCalls[0].durableStatus, 'timed_out')
  assert.equal(target.toolCalls[0].executionStatus, 'timed_out')
})


test('reconciles out-of-order thinking streams by messageId without creating an orphan fragment card', () => {
  const target = message()
  const analyzing = '\u5206\u6790\u95ee\u9898'
  const smoke = '\u5192\u70df'
  const smokePassed = '\u5192\u70df\u6d4b\u8bd5\u5168\u90e8\u901a\u8fc7\u3002'
  const completed = '\u601d\u8003\u5b8c\u6210'
  const full = '\u5192\u70df\u6d4b\u8bd5\u5168\u90e8\u901a\u8fc7\u3002\u6240\u6709\u529f\u80fd\u9a8c\u8bc1\u6210\u529f\u3002'

  reduceHistoryEvent('THINK_START', { messageId: 'think-a', summary: analyzing }, target)
  reduceHistoryEvent('THINK_DELTA', { messageId: 'think-a', delta: smoke }, target)

  reduceHistoryEvent('THINK_START', { messageId: 'think-b', summary: analyzing }, target)
  reduceHistoryEvent('THINK_DELTA', { messageId: 'think-b', delta: smokePassed }, target)
  reduceHistoryEvent('THINK', {
    messageId: 'think-b',
    summary: completed,
    content: full
  }, target)

  assert.deepEqual(target.thinkingBlocks.map(block => ({ messageId: block.messageId, summary: block.summary, content: block.content })), [{
    messageId: 'think-b',
    summary: completed,
    content: full
  }])
  assert.equal(target.thinking, '')
})


test('deduplicates replayed durable thinking completion by messageId', () => {
  const target = message()
  const final = '\u9a8c\u8bc1\u5df2\u5b8c\u6210'

  reduceHistoryEvent('THINK_START', { messageId: 'think-replay', summary: '\u5206\u6790' }, target)
  reduceHistoryEvent('THINK_DELTA', { messageId: 'think-replay', delta: '\u524d\u534a\u6bb5' }, target)
  reduceHistoryEvent('THINK', { messageId: 'think-replay', summary: '\u5b8c\u6210', content: final }, target)
  reduceHistoryEvent('THINK', { messageId: 'think-replay', summary: '\u5b8c\u6210', content: final }, target)

  assert.equal(target.thinkingBlocks.length, 1)
  assert.equal(target.thinkingBlocks[0].messageId, 'think-replay')
  assert.equal(target.thinkingBlocks[0].content, final)
})

test('ignores a delayed thinking delta after the durable message has completed', () => {
  const target = message()
  const final = '\u5b8c\u6574\u7684\u5192\u70df\u9a8c\u8bc1\u7ed3\u679c'

  reduceHistoryEvent('THINK_START', { messageId: 'think-complete', summary: '\u5206\u6790' }, target)
  reduceHistoryEvent('THINK_DELTA', { messageId: 'think-complete', delta: '\u5192\u70df' }, target)
  reduceHistoryEvent('THINK', { messageId: 'think-complete', summary: '\u5b8c\u6210', content: final }, target)
  reduceHistoryEvent('THINK_DELTA', { messageId: 'think-complete', delta: '\u8fdf\u5230\u7247\u6bb5' }, target)

  assert.equal(target.thinkingBlocks.length, 1)
  assert.equal(target.thinkingBlocks[0].content, final)
  assert.equal(target.thinking, '')
})


test('replays model step lifecycle onto one stable durable projection', () => {
  const target = message()

  reduceHistoryEvent('MODEL_STEP_STARTED', { iteration: 4, eventSequence: 20 }, target)
  reduceHistoryEvent('MODEL_STEP_COMPLETED', {
    iteration: 4, resultType: 'text', eventSequence: 21
  }, target)
  reduceHistoryEvent('MODEL_STEP_STARTED', { iteration: 4, eventSequence: 20 }, target)

  assert.equal(target.modelSteps.length, 1)
  assert.equal(target.modelSteps[0].partKey, 'model-step:4')
  assert.equal(target.modelSteps[0].status, 'completed')
  assert.equal(target.modelSteps[0].resultType, 'text')
  assert.equal(target.modelSteps[0].sequence, 21)
})


test('replays a finalization blocker as completion feedback', () => {
  const target = message()
  reduceHistoryEvent('FINALIZATION_BLOCKED', {
    taskId: 91,
    reasonCode: 'preview_url_mismatch',
    guidance: 'report only the ready URL',
    recoveryAllowed: false
  }, target)

  assert.equal(target.completionEvidence, null)
  assert.equal(target.completionBlockedEvidence.reasonCode, 'preview_url_mismatch')
  assert.equal(target.pendingFinalContent, '')
})

test('replays a durable workspace change through the explicit workspace projection seam', () => {
  const target = message()
  target.taskId = 71
  const projected = []
  const data = { projectId: 12, taskId: 71, workspaceChangeId: 'approval-71:workspace-changed' }

  reduceHistoryEvent('WORKSPACE_CHANGED', data, target, {
    onWorkspaceChanged: event => projected.push(event)
  })

  assert.deepEqual(projected, [{ eventId: null, data, message: target }])
})


test('history replay keeps a native unverified final visible after the failed terminal state', () => {
  const target = message()

  reduceHistoryEvent('COMPLETION_EVIDENCE', {
    taskId: 9,
    satisfied: false,
    finalResponseVisible: true,
    changedFiles: ['skills/SKILL.md'],
    successfulVerifications: [],
    unresolvedRisks: ['尚未记录成功验证']
  }, target)
  reduceHistoryEvent('FINAL', { taskId: 9, content: '已删除 skill，仍需要补充验证。' }, target)
  reduceHistoryEvent('RUN_STATE_FAILED', { taskId: 9, state: 'failed' }, target)

  assert.equal(target.content, '已删除 skill，仍需要补充验证。')
  assert.equal(target.runState, 'failed')
  assert.equal(target.error ?? null, null)
  assert.equal(target.completionBlockedEvidence.finalResponseVisible, true)
})

test('history replay keeps evidence details when finalization blocks a generated summary', () => {
  const target = message()
  reduceHistoryEvent('COMPLETION_EVIDENCE', {
    taskId: 9, satisfied: false, changedFiles: ['skills/SKILL.md'],
    successfulVerifications: [], failedVerifications: [], unresolvedRisks: ['存在文件改动，但没有成功验证证据']
  }, target)
  reduceHistoryEvent('FINALIZATION_BLOCKED', {
    taskId: 9, reasonCode: 'completion_evidence_unsatisfied',
    guidance: 'Run a server-recognized verification after the latest change.', recoveryAllowed: false
  }, target)

  assert.equal(target.completionEvidence, null)
  assert.equal(target.completionBlockedEvidence.satisfied, false)
  assert.deepEqual(target.completionBlockedEvidence.changedFiles, ['skills/SKILL.md'])
  assert.deepEqual(target.completionBlockedEvidence.unresolvedRisks, ['存在文件改动，但没有成功验证证据'])
  assert.equal(target.completionBlockedEvidence.reasonCode, 'completion_evidence_unsatisfied')
})


test('stores completion readiness as state only and does not fabricate a final reply', () => {
  const target = message()
  reduceHistoryEvent('COMPLETION_READY', {
    taskId: 9,
    reasonCode: 'verified_workspace_change',
    satisfied: true,
    changedFiles: ['src/App.vue']
  }, target)

  assert.equal(target.taskId, 9)
  assert.equal(target.content, '')
  assert.equal(target.completionReadiness.reasonCode, 'verified_workspace_change')
  assert.deepEqual(target.completionReadiness.changedFiles, ['src/App.vue'])
})
test('loop-guard stop remains recoverable and stale final text cannot turn it into user cancellation', () => {
  const target = message()

  reduceHistoryEvent('LOOP_GUARD_STOPPED', {
    taskId: 91,
    taskStatus: 'waiting_recovery',
    reasonCode: 'non_progress',
    recoverable: true,
    resumeAction: 'loop_guard_resume',
    message: '连续 8 次没有取得进展，已触发循环保护。'
  }, target)
  reduceHistoryEvent('FINAL', { content: '模型声称已经完成。' }, target)
  reduceHistoryEvent('DONE', { taskId: 91, taskStatus: 'waiting_recovery' }, target)

  assert.equal(target.runState, 'waiting_recovery')
  assert.equal(target.isStreaming, false)
  assert.equal(target.loopGuardStop.reasonCode, 'non_progress')
  assert.equal(target.loopGuardStop.recoverable, true)
  assert.notEqual(target.content, '模型声称已经完成。')
})

