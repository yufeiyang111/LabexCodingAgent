import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'

const scriptUrl = new URL('./websearch-browser-smoke.mjs', import.meta.url)
const source = await readFile(scriptUrl, 'utf8')

test('browser smoke uses the current composer and real CDP input', () => {
  assert.match(source, /textarea\.composer-textarea/)
  assert.match(source, /button\.btn-send/)
  assert.match(source, /Input\.dispatchMouseEvent/)
  assert.match(source, /Input\.insertText/)
  assert.doesNotMatch(source, /ai-input-text-area/)
  assert.doesNotMatch(source, /ai-submit-btn/)
})

test('browser smoke verifies durable tool completion and Redis cache evidence', () => {
  assert.match(source, /acceptance-web-search/)
  assert.match(source, /data-tool-call-id/)
  assert.match(source, /redisEnabled/)
  assert.match(source, /matchingKeys/)
  assert.match(source, /queryLeaked/)
})