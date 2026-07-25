import assert from 'node:assert/strict'
import test from 'node:test'
import { resolveContextUsageStatus } from './contextUsageStatus.js'

test('retains a persisted context snapshot when the runtime registry is empty', () => {
  const persisted = {
    conversationId: 'conversation-1',
    usedTokens: 83800,
    categories: { systemPrompt: 1200, conversationMessages: 82600 },
    updatedAt: '2026-07-22T10:00:00'
  }
  const awaitingFirstRequest = {
    conversationId: 'conversation-1',
    status: 'AWAITING_FIRST_REQUEST',
    categories: {}
  }

  assert.equal(resolveContextUsageStatus(persisted, awaitingFirstRequest), persisted)
})

test('uses a runtime snapshot when one is available', () => {
  const persisted = {
    usedTokens: 1200,
    categories: { systemPrompt: 1200 }
  }
  const runtime = {
    usedTokens: 2200,
    categories: { systemPrompt: 1200, conversationMessages: 1000 }
  }

  assert.equal(resolveContextUsageStatus(persisted, runtime), runtime)
})

test('uses the awaiting state when there is no restored snapshot', () => {
  const awaitingFirstRequest = { status: 'AWAITING_FIRST_REQUEST', categories: {} }

  assert.equal(resolveContextUsageStatus(null, awaitingFirstRequest), awaitingFirstRequest)
})

test('retains a persisted context snapshot when the status request fails', () => {
  const persisted = {
    usedTokens: 1200,
    categories: { systemPrompt: 1200 }
  }

  assert.equal(resolveContextUsageStatus(persisted, null), persisted)
})

test("does not reuse another conversation's persisted snapshot", () => {
  const previousConversation = {
    conversationId: 'conversation-1',
    usedTokens: 1200,
    categories: { systemPrompt: 1200 }
  }
  const awaitingFirstRequest = {
    conversationId: 'conversation-2',
    status: 'AWAITING_FIRST_REQUEST',
    categories: {}
  }

  assert.equal(resolveContextUsageStatus(previousConversation, awaitingFirstRequest, 'conversation-2'), awaitingFirstRequest)
})
