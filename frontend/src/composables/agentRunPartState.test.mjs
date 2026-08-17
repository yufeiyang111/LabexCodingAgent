import test from 'node:test'
import assert from 'node:assert/strict'
import { applyRunMessageSnapshot, applyRunPartSnapshot } from './agentRunPartState.js'

function message() {
  return { content: '', thinkingBlocks: [], toolCalls: [], _nextOrder: 0 }
}

test('replays durable tool and reasoning parts idempotently', () => {
  const target = message()
  const parts = [
    { partId: 1, partKey: 'reasoning:1', partType: 'reasoning', output: 'inspect project', sequence: 1 },
    { partId: 2, partKey: 'tool:call-1', partType: 'tool', toolCallId: 'call-1', tool: 'run_tests', input: '{"strategy":"test"}', output: 'passed', status: 'completed', sequence: 2 }
  ]

  applyRunPartSnapshot(target, parts)
  applyRunPartSnapshot(target, parts)

  assert.equal(target.thinkingBlocks.length, 1)
  assert.equal(target.toolCalls.length, 1)
  assert.equal(target.toolCalls[0].durableStatus, 'completed')
  assert.deepEqual(target.toolCalls[0].args, { strategy: 'test' })
})

test('replays a completed shell nonzero exit as a visible error from durable metadata', () => {
  const target = message()
  applyRunPartSnapshot(target, [
    { partId: 7, partKey: 'tool:shell-nonzero', partType: 'tool', toolCallId: 'shell-nonzero',
      tool: 'shell', input: '{"command":"npm run build"}', output: 'exit=2\nstatus=failed',
      status: 'completed',
      metadata: '{"failureClass":"non_zero_exit","execution":{"status":"failed","exitCode":2,"workdir":"frontend","outputPath":".labex-agent/artifacts/task-7/shell-nonzero.log"}}',
      sequence: 7 }
  ])

  assert.equal(target.toolCalls.length, 1)
  assert.equal(target.toolCalls[0].durableStatus, 'completed')
  assert.equal(target.toolCalls[0].status, 'error')
  assert.equal(target.toolCalls[0].failureClass, 'non_zero_exit')
  assert.deepEqual(target.toolCalls[0].executionResult, {
    status: 'failed', exitCode: 2, workdir: 'frontend',
    outputPath: '.labex-agent/artifacts/task-7/shell-nonzero.log'
  })
})

test('projects provider tool_call and tool_result parts into one durable tool card', () => {
  const target = message()
  const parts = [
    { partId: 11, partKey: 'provider:0:tool-call:1:call-1', partType: 'tool_call',
      toolCallId: 'call-1', tool: 'read_file', input: '{"path":"README.md"}',
      output: 'Waiting for user approval.', status: 'waiting_approval', sequence: 2 },
    { partId: 12, partKey: 'provider:0:tool-result:2:call-1', partType: 'tool_result',
      toolCallId: 'call-1', tool: 'read_file', input: '{}', output: 'file content',
      status: 'completed', sequence: 3 }
  ]

  applyRunPartSnapshot(target, parts)

  assert.equal(target.toolCalls.length, 1)
  assert.equal(target.toolCalls[0].toolCallId, 'call-1')
  assert.deepEqual(target.toolCalls[0].args, { path: 'README.md' })
  assert.equal(target.toolCalls[0].durableStatus, 'completed')
  assert.equal(target.toolCalls[0].result, 'file content')
})

test('restores final content and completion evidence from message and part snapshots', () => {
  const target = message()
  applyRunMessageSnapshot(target, [
    { messageId: 7, messageKey: 'assistant:final', sequence: 9, content: 'done', status: 'completed' }
  ])
  applyRunPartSnapshot(target, [
    { partId: 8, partType: 'completion_evidence', output: '{"satisfied":true}', sequence: 8 }
  ])

  assert.equal(target.content, 'done')
  assert.equal(target.completionEvidence.satisfied, true)
})

test('sanitizes durable reasoning and final projections during recovery', () => {
  const fromMessage = message()
  applyRunMessageSnapshot(fromMessage, [
    { messageId: 9, messageKey: 'assistant:final', sequence: 9,
      content: "Visible <THINK data-kind='hidden'>private plan</THINKING> answer", status: 'completed' }
  ])

  const fromParts = message()
  applyRunPartSnapshot(fromParts, [
    { partId: 10, partKey: 'reasoning:10', partType: 'reasoning',
      output: '&lt;think&gt;inspect project&lt;/think&gt;', sequence: 10 },
    { partId: 11, partKey: 'final', partType: 'text',
      output: 'Part <thinking>private plan</thinking> answer', sequence: 11 }
  ])

  assert.equal(fromMessage.content, 'Visible  answer')
  assert.equal(fromParts.content, 'Part  answer')
  assert.equal(fromParts.thinkingBlocks[0].content, 'inspect project')
  assert.doesNotMatch(JSON.stringify({ fromMessage, fromParts }), /<\/?think/i)
})


test('does not append durable reasoning parts over an already rendered live timeline', () => {
  const target = message()
  target.thinkingBlocks.push({ content: 'inspect project', _order: 1 })

  applyRunPartSnapshot(target, [
    { partId: 1, partKey: 'reasoning:1', partType: 'reasoning', output: 'inspect project', sequence: 1 },
    { partId: 2, partKey: 'tool:call-1', partType: 'tool', toolCallId: 'call-1', tool: 'question', status: 'waiting_user', sequence: 2 }
  ])

  assert.equal(target.thinkingBlocks.length, 1)
  assert.equal(target.toolCalls.length, 1)
  assert.equal(target.toolCalls[0].toolCallId, 'call-1')
})


test('keeps the latest model step terminal state when a stale snapshot arrives', () => {
  const target = message()
  applyRunPartSnapshot(target, [
    { partId: 41, partKey: 'model-step:3', partType: 'model_step', status: 'completed',
      input: '{"iteration":3,"resultType":"tool_call"}', sequence: 12 }
  ])
  applyRunPartSnapshot(target, [
    { partId: 40, partKey: 'model-step:3', partType: 'model_step', status: 'running',
      input: '{"iteration":3}', sequence: 11 }
  ])

  assert.equal(target.modelSteps.length, 1)
  assert.equal(target.modelSteps[0].status, 'completed')
  assert.equal(target.modelSteps[0].resultType, 'tool_call')
  assert.equal(target.modelSteps[0].sequence, 12)
})


test('restores a durable finalization blocker without adding a new sidebar card', () => {
  const target = message()
  applyRunPartSnapshot(target, [
    { partId: 52, partKey: 'finalization:evidence-a', partType: 'finalization_blocker', status: 'error',
      input: '{"reasonCode":"preview_url_mismatch","guidance":"report only the ready URL","recoveryAllowed":false}', sequence: 18 }
  ])

  assert.equal(target.completionEvidence, null)
  assert.equal(target.completionBlockedEvidence.reasonCode, 'preview_url_mismatch')
  assert.equal(target.completionBlockedEvidence.recoveryAllowed, false)
})

test('keeps completion evidence details when a durable finalization blocker follows it', () => {
  const target = message()
  applyRunPartSnapshot(target, [
    { partId: 51, partKey: 'completion:evidence-a', partType: 'completion_evidence', status: 'error',
      output: '{"taskId":9,"satisfied":false,"changedFiles":["skills/SKILL.md"],"unresolvedRisks":["missing verification"]}', sequence: 17 },
    { partId: 52, partKey: 'finalization:evidence-a', partType: 'finalization_blocker', status: 'error',
      input: '{"taskId":9,"reasonCode":"completion_evidence_unsatisfied","guidance":"verify latest change","recoveryAllowed":false}', sequence: 18 }
  ])

  assert.equal(target.completionEvidence, null)
  assert.equal(target.completionBlockedEvidence.satisfied, false)
  assert.deepEqual(target.completionBlockedEvidence.changedFiles, ['skills/SKILL.md'])
  assert.deepEqual(target.completionBlockedEvidence.unresolvedRisks, ['missing verification'])
  assert.equal(target.completionBlockedEvidence.reasonCode, 'completion_evidence_unsatisfied')
})


test('retains completion readiness as internal durable state without replacing visible content', () => {
  const target = message()
  applyRunPartSnapshot(target, [
    { partId: 61, partKey: 'completion:ready:evidence-a', partType: 'completion_readiness',
      output: '{"taskId":9,"reasonCode":"verified_workspace_change","satisfied":true}', sequence: 19 }
  ])

  assert.equal(target.content, '')
  assert.equal(target.completionReadiness.reasonCode, 'verified_workspace_change')
  assert.equal(target.completionReadiness.satisfied, true)
})
