import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'

const source = await readFile(new URL('./MonacoEditor.vue', import.meta.url), 'utf8')

test('uses the locally bundled Monaco runtime instead of a remote CDN loader', () => {
  assert.ok(source.includes("import { loader, VueMonacoEditor } from '@guolao/vue-monaco-editor'"))
  assert.ok(source.includes("import * as monaco from 'monaco-editor'"))
  assert.ok(source.includes('loader.config({ monaco })'))
  assert.ok(source.includes('MonacoEnvironment'))
  assert.ok(!source.includes('cdn.jsdelivr.net'))
  assert.ok(!source.includes('unpkg.com'))
})
