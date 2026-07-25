import assert from 'node:assert/strict'
import test from 'node:test'
import { ref } from 'vue'

const changeSetModule = await import('./useChangeSetState.js').catch(() => ({}))
const { useChangeSetState } = changeSetModule

function createHarness(apiOverrides = {}) {
  const projectId = ref(42)
  const sessionChanges = ref([])
  const calls = []
  const api = {
    readFile: async (id, path) => {
      calls.push(['read', id, path])
      return { data: { content: 'current content' } }
    },
    saveFile: async (id, path, content) => {
      calls.push(['save', id, path, content])
      return { code: 0 }
    },
    ...apiOverrides
  }
  const reverted = []
  const state = useChangeSetState({
    projectId,
    api,
    sessionChanges,
    onFileReverted: payload => reverted.push(payload)
  })
  return { state, sessionChanges, calls, reverted }
}

test('tracks a changed file with unified-diff statistics and updates it in place', () => {
  assert.equal(typeof useChangeSetState, 'function')
  const { state, sessionChanges } = createHarness()

  state.trackFileChange({ file: 'src/main.js', diff: '--- a\n+++ b\n-old\n+new\n context' }, { name: 'edit_file', args: {} })
  state.trackFileChange({ file: 'src/main.js', diff: '+added\n-removed\n+again', success: true }, { name: 'edit_file', args: {} })

  assert.deepEqual(sessionChanges.value, [{
    file: 'src/main.js', patch: '+added\n-removed\n+again', additions: 2, deletions: 1, status: 'modified'
  }])
})

test('reverts a tracked patch, persists reconstructed content, and notifies the UI', async () => {
  const { state, sessionChanges, calls, reverted } = createHarness()
  sessionChanges.value = [{ file: 'src/main.js', patch: '@@\n context\n-old\n+new' }]

  const result = await state.revertChange(sessionChanges.value[0])

  assert.deepEqual(result, { success: true, file: 'src/main.js', content: 'context\nold' })
  assert.deepEqual(calls, [
    ['read', 42, 'src/main.js'],
    ['save', 42, 'src/main.js', 'context\nold']
  ])
  assert.deepEqual(sessionChanges.value, [])
  assert.deepEqual(reverted, [{ file: 'src/main.js', content: 'context\nold' }])
})

test('removes changes from both local and persisted change payload shapes', () => {
  const { state, sessionChanges } = createHarness()
  sessionChanges.value = [{ file: 'a.js' }, { file: 'b.js' }]

  state.removeChange({ relativePath: 'a.js' })
  state.removeChange({ file: 'b.js' })

  assert.deepEqual(sessionChanges.value, [])
})
