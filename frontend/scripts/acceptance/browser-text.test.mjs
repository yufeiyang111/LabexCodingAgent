import assert from 'node:assert/strict'
import test from 'node:test'

import { bodyIncludesAnyExpression } from './browser-text.mjs'

test('matches a later visible-text candidate instead of short-circuiting on the first async probe', () => {
  const expression = bodyIncludesAnyExpression(['missing localized text', 'Model API failed'])
  const matches = Function('document', `return ${expression}`)({
    body: { innerText: 'Status\nModel API failed\nStop Reason' }
  })

  assert.equal(matches, true)
})

test('returns false when none of the visible-text candidates match', () => {
  const expression = bodyIncludesAnyExpression(['first', 'second'])
  const matches = Function('document', `return ${expression}`)({ body: { innerText: 'third' } })

  assert.equal(matches, false)
})
