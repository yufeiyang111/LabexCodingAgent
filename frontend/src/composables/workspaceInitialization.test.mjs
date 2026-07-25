import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const workspaceInitialization = await import('./workspaceInitialization.js').catch(() => ({}))
const { loadWorkspaceResources } = workspaceInitialization

test('starts independent workspace loaders together and preserves each settlement', async () => {
  assert.equal(typeof loadWorkspaceResources, 'function')

  const started = []
  let resolveTree
  const treeLoaded = new Promise(resolve => {
    resolveTree = resolve
  })
  const historyFailure = new Error('history unavailable')

  const resultsPromise = loadWorkspaceResources([
    () => {
      started.push('tree')
      return treeLoaded
    },
    () => {
      started.push('history')
      return Promise.reject(historyFailure)
    }
  ])

  assert.deepEqual(started, ['tree', 'history'])
  resolveTree('tree loaded')

  const results = await resultsPromise
  assert.deepEqual(results, [
    { status: 'fulfilled', value: 'tree loaded' },
    { status: 'rejected', reason: historyFailure }
  ])
})

test('CloudWorkspace renders the file tree before waiting for secondary startup resources', async () => {
  const workspaceSource = await readFile(new URL('../views/CloudWorkspace.vue', import.meta.url), 'utf8')

  assert.match(
    workspaceSource,
    /import \{ loadWorkspaceResources \} from '@\/composables\/workspaceInitialization'/,
    'the workspace must use the reusable parallel initializer'
  )
  assert.match(
    workspaceSource,
    /const secondaryResources = loadWorkspaceResources\(\[/,
    'secondary startup requests should begin in parallel without blocking the explorer'
  )
  assert.match(
    workspaceSource,
    /await loadRoot\(\)/,
    'the file tree remains the only startup resource awaited before the workspace becomes usable'
  )
  assert.match(
    workspaceSource,
    /void secondaryResources\.then\(async \(\) => \{/,
    'conversation selection should continue after non-critical resources settle'
  )
})
