import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeWorkspacePath, splitWorkspacePath } from './pathUtils.js'

test('normalizeWorkspacePath handles sandbox work-xxxx container prefixes', () => {
  assert.equal(normalizeWorkspacePath('/work-4425/docs/spec.md'), 'docs/spec.md')
  assert.equal(normalizeWorkspacePath('work-4425/docs/tasks.md'), 'docs/tasks.md')
  assert.equal(normalizeWorkspacePath('work-999/checklist.md'), 'checklist.md')
})

test('normalizeWorkspacePath handles workspace and workspaces prefixes', () => {
  assert.equal(normalizeWorkspacePath('/workspace/src/App.vue'), 'src/App.vue')
  assert.equal(normalizeWorkspacePath('workspaces/proj-123/backend/pom.xml'), 'backend/pom.xml')
  assert.equal(normalizeWorkspacePath('app/workspace/README.md'), 'README.md')
})

test('normalizeWorkspacePath handles relative and backslash paths', () => {
  assert.equal(normalizeWorkspacePath('.\\src\\components\\Foo.vue'), 'src/components/Foo.vue')
  assert.equal(normalizeWorkspacePath('./docs/api.md'), 'docs/api.md')
  assert.equal(normalizeWorkspacePath('docs/api.md'), 'docs/api.md')
})

test('normalizeWorkspacePath handles empty and invalid inputs', () => {
  assert.equal(normalizeWorkspacePath(''), '')
  assert.equal(normalizeWorkspacePath(null), '')
  assert.equal(normalizeWorkspacePath(undefined), '')
})

test('splitWorkspacePath extracts clean filename and directory', () => {
  const s1 = splitWorkspacePath('/work-4425/docs/spec.md')
  assert.equal(s1.name, 'spec.md')
  assert.equal(s1.dir, '/docs')
  assert.equal(s1.path, 'docs/spec.md')

  const s2 = splitWorkspacePath('README.md')
  assert.equal(s2.name, 'README.md')
  assert.equal(s2.dir, '')
  assert.equal(s2.path, 'README.md')
})
