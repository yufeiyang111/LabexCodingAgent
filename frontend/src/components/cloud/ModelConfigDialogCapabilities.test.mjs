import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'

test('ModelConfigDialog exposes reasoning effort and multimodal capability settings', async () => {
  const dialog = await readFile(new URL('./ModelConfigDialog.vue', import.meta.url), 'utf8')
  const workspace = await readFile(new URL('../../views/CloudWorkspace.vue', import.meta.url), 'utf8')

  assert.match(dialog, /state\.mcForm\.reasoningEffort/)
  assert.match(dialog, /low/)
  assert.match(dialog, /medium/)
  assert.match(dialog, /high/)
  assert.match(dialog, /xhigh/)
  assert.match(dialog, /state\.mcForm\.imageInputEnabled/)
  assert.match(dialog, /支持图片理解/)
  assert.match(workspace, /reasoningEffort:\s*cfg\.reasoningEffort/)
  assert.match(workspace, /imageInputEnabled:\s*cfg\.imageInputEnabled\s*===\s*1/)
  assert.match(workspace, /reasoningEffort:\s*f\.reasoningEffort/)
  assert.match(workspace, /imageInputEnabled:\s*f\.imageInputEnabled/)
})
