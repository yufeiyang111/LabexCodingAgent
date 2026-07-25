import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

test('CloudWorkspace delegates local change-set tracking and reversion to useChangeSetState', async () => {
  const source = await readFile(new URL('../views/CloudWorkspace.vue', import.meta.url), 'utf8')

  assert.match(source, /import \{ useChangeSetState \} from '@\/composables\/useChangeSetState'/)
  assert.match(source, /const \{ trackFileChange, revertChange: revertChangeState, removeChange \} = useChangeSetState\(/)
  assert.match(source, /async function revertChange\(change\) \{[\s\S]*?revertChangeState\(change\)/)
  assert.doesNotMatch(source, /function trackFileChange\(data, toolCall\)/)
})
