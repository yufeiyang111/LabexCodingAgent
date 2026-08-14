import assert from 'node:assert/strict'
import test from 'node:test'

import { DEFAULT_MAX_TOKENS, modelConfigPresets } from './modelPresets.js'

test('all model presets use the OpenCode-aligned 32000 output default', () => {
  assert.equal(DEFAULT_MAX_TOKENS, 32000)
  assert.ok(modelConfigPresets.length > 0)
  for (const preset of modelConfigPresets) {
    assert.equal(preset.maxTokens, DEFAULT_MAX_TOKENS, `${preset.name} must default to 32000 max tokens`)
  }
})
