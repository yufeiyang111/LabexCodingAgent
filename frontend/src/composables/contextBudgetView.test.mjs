import assert from 'node:assert/strict'
import test from 'node:test'

import { normalizeContextBudget } from './contextBudgetView.js'

test('uses explicit backend budget fields when available', () => {
  const budget = normalizeContextBudget({
    staticTokens: 1800, reducibleTokens: 2400, reservedOutputTokens: 4000,
    inputCapacityTokens: 12000,
    staticCategories: { systemPrompt: 1000, toolDefinitions: 800 },
    reducibleCategories: { conversationMessages: 2400 }
  })
  assert.deepEqual(budget, {
    staticTokens: 1800, reducibleTokens: 2400, reservedOutputTokens: 4000,
    inputCapacityTokens: 12000,
    staticCategories: { systemPrompt: 1000, toolDefinitions: 800 },
    reducibleCategories: { conversationMessages: 2400 }
  })
})

test('derives static and reducible categories for older persisted snapshots', () => {
  const budget = normalizeContextBudget({
    contextWindowTokens: 16000,
    categories: {
      systemPrompt: 1000, toolDefinitions: 500, fixedInstructions: 100,
      projectContext: 400, workspaceMemory: 200, conversationMessages: 300
    }
  })
  assert.equal(budget.staticTokens, 1600)
  assert.equal(budget.reducibleTokens, 900)
  assert.equal(budget.reservedOutputTokens, 0)
  assert.deepEqual(Object.keys(budget.staticCategories), ['systemPrompt', 'toolDefinitions', 'fixedInstructions'])
})
