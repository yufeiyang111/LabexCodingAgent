import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'

const cardSource = await readFile(new URL('./ToolCallCard.vue', import.meta.url), 'utf8')
const workspaceSource = await readFile(new URL('../../views/CloudWorkspace.vue', import.meta.url), 'utf8')
const acceptanceSource = await readFile(new URL('../../../scripts/acceptance/agent-browser.mjs', import.meta.url), 'utf8')

test('question actions stay disabled until the durable interaction identity is projected', () => {
  assert.match(cardSource, /const questionRequestReady = computed/)
  assert.match(cardSource, /questionRequest\.value\.requestId \|\| questionRequest\.value\.interactionId/)
  assert.match(cardSource, /:disabled="!questionRequestReady"/)
  assert.match(cardSource, /正在同步可恢复提问请求/)
  assert.match(cardSource, /if \(!questionRequestReady\.value\) return/)
})

test('missing question identity is visible and browser acceptance waits for a submit-ready card', () => {
  assert.match(workspaceSource, /result\.reason === 'request_missing'/)
  assert.match(workspaceSource, /提问请求仍在同步/)
  assert.match(acceptanceSource, /\.tc-question \.tc-approval-btn\.primary:not\(:disabled\)/)
})