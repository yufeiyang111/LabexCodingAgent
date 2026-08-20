import test from 'node:test'
import assert from 'node:assert/strict'
import { resolveEffectiveChanges, resolveMessageChanges, resolveMessageStats } from './useEffectiveChanges.js'

test('resolveEffectiveChanges extracts additions, deletions and patches from toolCalls', () => {
  const messages = [
    {
      toolCalls: [
        {
          name: 'edit_file',
          args: { path: '/work-4425/docs/spec.md' },
          result: '@@ -1,1 +1,3 @@\n-old\n+new1\n+new2\n+new3'
        },
        {
          name: 'write_file',
          args: { path: 'work-4425/docs/tasks.md', content: 'line1\nline2\nline3' }
        }
      ],
      completionEvidence: {
        changedFiles: ['/work-4425/docs/spec.md', 'work-4425/docs/tasks.md', '/work-4425/docs/checklist.md']
      }
    }
  ]

  const changes = resolveEffectiveChanges([], messages)
  assert.equal(changes.length, 3)

  const spec = changes.find(c => c.file === 'docs/spec.md')
  assert.ok(spec)
  assert.equal(spec.additions, 3)
  assert.equal(spec.deletions, 1)
  assert.ok(spec.patch.includes('+new1'))

  const tasks = changes.find(c => c.file === 'docs/tasks.md')
  assert.ok(tasks)
  assert.equal(tasks.additions, 3)
  assert.equal(tasks.deletions, 0)
  assert.equal(tasks.status, 'added')

  const checklist = changes.find(c => c.file === 'docs/checklist.md')
  assert.ok(checklist)
  assert.equal(checklist.additions, 0)
  assert.equal(checklist.deletions, 0)
})

test('resolveEffectiveChanges prioritizes active sessionChanges', () => {
  const sessionChanges = [
    { file: 'docs/spec.md', patch: '@@ -1,1 +1,2 @@\n-a\n+b\n+c', additions: 2, deletions: 1 }
  ]
  const changes = resolveEffectiveChanges(sessionChanges, [])
  assert.equal(changes.length, 1)
  assert.equal(changes[0].file, 'docs/spec.md')
  assert.equal(changes[0].additions, 2)
  assert.equal(changes[0].deletions, 1)
})

test('resolveMessageChanges and resolveMessageStats compute per-message turn statistics accurately', () => {
  const msg1 = {
    role: 'assistant',
    toolCalls: [
      {
        name: 'edit_file',
        args: { path: 'src/main.js' },
        result: '@@ -1,2 +1,4 @@\n-a\n-b\n+c\n+d\n+e\n+f'
      }
    ]
  }
  const msg2 = {
    role: 'assistant',
    toolCalls: [
      {
        name: 'write_file',
        args: { path: 'src/config.json', content: '{\n  "version": 1\n}' }
      }
    ]
  }

  const changes1 = resolveMessageChanges(msg1)
  assert.equal(changes1.length, 1)
  assert.equal(changes1[0].file, 'src/main.js')
  assert.equal(changes1[0].additions, 4)
  assert.equal(changes1[0].deletions, 2)

  const stats1 = resolveMessageStats(msg1)
  assert.equal(stats1.additions, 4)
  assert.equal(stats1.deletions, 2)
  assert.equal(stats1.count, 1)

  const changes2 = resolveMessageChanges(msg2)
  assert.equal(changes2.length, 1)
  assert.equal(changes2[0].file, 'src/config.json')
  assert.equal(changes2[0].additions, 3)
  assert.equal(changes2[0].deletions, 0)
})
