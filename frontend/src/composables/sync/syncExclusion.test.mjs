import assert from 'node:assert/strict'
import test from 'node:test'

import { filterSyncPaths, isExcludedPath, normalizeRelativePath } from './syncExclusion.js'

test('normalizes windows separators and leading prefixes', () => {
  assert.equal(normalizeRelativePath('src\\views\\App.vue'), 'src/views/App.vue')
  assert.equal(normalizeRelativePath('./src/main.js'), 'src/main.js')
  assert.equal(normalizeRelativePath(''), '')
})

test('excludes dependency and build directories', () => {
  assert.equal(isExcludedPath('node_modules/vue/index.js'), true)
  assert.equal(isExcludedPath('frontend/dist/assets/app.js'), true)
  assert.equal(isExcludedPath('backend/target/classes/App.class'), true)
  assert.equal(isExcludedPath('.git/config'), true)
  assert.equal(isExcludedPath('src/__pycache__/mod.pyc'), true)
})

test('excludes platform reserved directories', () => {
  assert.equal(isExcludedPath('.labex/permissions.json'), true)
  assert.equal(isExcludedPath('.labex-agent/artifacts/task-1/out.txt'), true)
})

test('keeps ordinary source paths', () => {
  assert.equal(isExcludedPath('src/composables/agentSse.js'), false)
  assert.equal(isExcludedPath('README.md'), false)
  assert.equal(isExcludedPath('backend/src/main/java/App.java'), false)
})

test('applies ignore patterns with wildcard and exact semantics', () => {
  const options = { ignorePatterns: ['*.log', 'docs/draft.md'] }
  assert.equal(isExcludedPath('logs/app.log', options), true)
  assert.equal(isExcludedPath('docs/draft.md', options), true)
  assert.equal(isExcludedPath('docs/final.md', options), false)
})

test('treats empty paths as excluded', () => {
  assert.equal(isExcludedPath(''), true)
  assert.equal(isExcludedPath(null), true)
})

test('partitions path lists into included and excluded', () => {
  const result = filterSyncPaths(
    ['src/a.js', 'node_modules/b.js', 'dist/c.css', 'src/d.vue'],
    { ignorePatterns: [] }
  )
  assert.deepEqual(result.included, ['src/a.js', 'src/d.vue'])
  assert.deepEqual(result.excluded, ['node_modules/b.js', 'dist/c.css'])
})
