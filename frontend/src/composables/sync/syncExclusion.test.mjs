import assert from 'node:assert/strict'
import test from 'node:test'

import { SYNC_EXCLUSION_SEGMENTS, filterSyncPaths, isExcludedPath, normalizeRelativePath } from './syncExclusion.js'

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

test('excludes platform paths at any depth and case-insensitively', () => {
  assert.equal(isExcludedPath('src/.labex/runtime.env'), true)
  assert.equal(isExcludedPath('src/.LABEX/permissions.json'), true)
  assert.equal(isExcludedPath('packages/app/.labex-agent/runtime/x'), true)
  assert.equal(isExcludedPath('src/.labex-rename-123/file.txt'), true)
  assert.equal(isExcludedPath('frontend/NODE_MODULES/vue/index.js'), true)
  assert.equal(isExcludedPath('frontend/.GIT/config'), true)
})

test('keeps single-star within one path segment and supports double-star', () => {
  assert.equal(isExcludedPath('logs/a.log', { ignorePatterns: ['logs/*.log'] }), true)
  assert.equal(isExcludedPath('logs/sub/a.log', { ignorePatterns: ['logs/*.log'] }), false)
  assert.equal(isExcludedPath('logs/sub/a.log', { ignorePatterns: ['logs/**/*.log'] }), true)
  assert.equal(isExcludedPath('a1.log', { ignorePatterns: ['a?.log'] }), true)
  assert.equal(isExcludedPath('a10.log', { ignorePatterns: ['a?.log'] }), false)
})

test('matches directory-only rules and ignores negation rules like the server', () => {
  assert.equal(isExcludedPath('docs/guide.md', { ignorePatterns: ['docs/'] }), true)
  assert.equal(isExcludedPath('src/docs/guide.md', { ignorePatterns: ['docs/'] }), false)
  assert.equal(isExcludedPath('important.log', { ignorePatterns: ['!important.log'] }), false)
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

test('keeps the built-in excluded directory list in sync with ProjectScanPolicy', async () => {
  const { readFile } = await import('node:fs/promises')
  const backend = await readFile(new URL('../../../../backend/src/main/java/com/labex/labexagent/service/ProjectScanPolicy.java', import.meta.url), 'utf8')
  const match = backend.match(/IGNORED_DIRECTORY_NAMES = Set\.of\(([\s\S]*?)\);/)
  assert.ok(match, 'ProjectScanPolicy ignored directory set must remain parseable')
  const backendNames = [...match[1].matchAll(/"([^"]+)"/g)].map(item => item[1]).sort()
  const frontendNames = [...SYNC_EXCLUSION_SEGMENTS].sort()
  assert.deepEqual(frontendNames, backendNames)
})
