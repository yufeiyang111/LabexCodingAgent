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
    staticTokens: 1800,
    reducibleTokens: 2400,
    reservedOutputTokens: 4000,
    inputCapacityTokens: 12000,
    softLimitTokens: 12000,
    usedTokens: 4200,
    distanceToSoftLimitTokens: 7800,
    usedAgainstSoftLimitPercent: 35,
    softLimitOfWindowPercent: 0,
    softLimitSource: 'input_capacity_fallback',
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
  assert.equal(budget.softLimitTokens, 16000)
  assert.equal(budget.usedTokens, 2500)
  assert.equal(budget.distanceToSoftLimitTokens, 13500)
  assert.equal(budget.usedAgainstSoftLimitPercent, 15.6)
  assert.equal(budget.softLimitOfWindowPercent, 100)
  assert.equal(budget.softLimitSource, 'input_capacity_fallback')
  assert.deepEqual(Object.keys(budget.staticCategories), ['systemPrompt', 'toolDefinitions', 'fixedInstructions'])
})

test('uses the backend effective soft limit instead of the total window threshold', () => {
  const budget = normalizeContextBudget({
    contextWindowTokens: 204800,
    usedTokens: 39227,
    inputCapacityTokens: 153600,
    reservedOutputTokens: 51200,
    softLimitTokens: 153600,
    categories: { conversationMessages: 39227 }
  })

  assert.equal(budget.softLimitTokens, 153600)
  assert.equal(budget.distanceToSoftLimitTokens, 114373)
  assert.equal(budget.usedAgainstSoftLimitPercent, 25.5)
  assert.equal(budget.softLimitOfWindowPercent, 75)
  assert.equal(budget.softLimitSource, 'backend')
})
