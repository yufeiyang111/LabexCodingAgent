import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'

const script = await readFile(new URL('../../../scripts/acceptance/websearch-runtime-smoke.ps1', import.meta.url), 'utf8')
const harness = await readFile(new URL('../../../scripts/acceptance/WebSearchRuntimeAcceptanceHarness.java', import.meta.url), 'utf8')

test('runtime smoke covers cross-JVM sharing and tenant isolation', () => {
  for (const mode of ['writer', 'reader', 'shared', 'isolation', 'parallel', 'corrupt', 'ttl', 'timeout']) {
    assert.match(script, new RegExp(`'${mode}'`))
  }
  assert.match(harness, /USER_MISMATCH_HIT/)
  assert.match(harness, /PROJECT_MISMATCH_HIT/)
  assert.match(harness, /OWNER_MISMATCH_HIT/)
  assert.match(harness, /MATCHING_KEYS/)
})

test('runtime smoke measures fail-open latency and never reads secrets', () => {
  assert.match(harness, /ELAPSED_MS/)
  assert.match(harness, /RESULT_NULL/)
  assert.doesNotMatch(script, /\.env/)
  assert.doesNotMatch(harness, /queryLeaked|System\.out\.println\(.*query/i)
})
