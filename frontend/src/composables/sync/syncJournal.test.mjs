import assert from 'node:assert/strict'
import test from 'node:test'

import {
  buildRollbackSteps,
  createJournal,
  markRolledBack,
  recordApplied,
  recordAppliedBatch,
  summarizeJournal
} from './syncJournal.js'

const writeOverExisting = {
  changeId: 'c1',
  changeSetId: 10,
  path: 'src/app.js',
  op: 'write',
  content: 'new',
  previousContent: 'old',
  previousExisted: true
}

const writeNewFile = {
  changeId: 'c2',
  changeSetId: 11,
  path: 'src/added.js',
  op: 'write',
  content: 'content',
  previousContent: null,
  previousExisted: false
}

const fileDeletion = {
  changeId: 'c3',
  changeSetId: 12,
  path: 'src/legacy.js',
  op: 'delete',
  content: null,
  previousContent: 'legacy',
  previousExisted: true
}

test('records entries immutably and advances the cursor', () => {
  const empty = createJournal()
  const next = recordApplied(empty, writeOverExisting, { appliedAt: 1000 })
  assert.equal(empty.entries.length, 0)
  assert.equal(next.entries.length, 1)
  assert.equal(next.cursor, 10)
  assert.equal(next.entries[0].appliedAt, 1000)
})

test('rolls back a modified file by restoring previous content', () => {
  const journal = recordAppliedBatch(createJournal(), [writeOverExisting])
  const steps = buildRollbackSteps(journal)
  assert.deepEqual(steps, [{ path: 'src/app.js', op: 'write', content: 'old', from: 'c1' }])
})

test('rolls back a newly added file by deleting it', () => {
  const journal = recordAppliedBatch(createJournal(), [writeNewFile])
  const steps = buildRollbackSteps(journal)
  assert.deepEqual(steps, [{ path: 'src/added.js', op: 'delete', content: null, from: 'c2' }])
})

test('rolls back a deletion by restoring the file', () => {
  const journal = recordAppliedBatch(createJournal(), [fileDeletion])
  const steps = buildRollbackSteps(journal)
  assert.deepEqual(steps, [{ path: 'src/legacy.js', op: 'write', content: 'legacy', from: 'c3' }])
})

test('rollback replays in reverse application order', () => {
  const journal = recordAppliedBatch(createJournal(), [writeOverExisting, writeNewFile])
  const paths = buildRollbackSteps(journal).map(step => step.path)
  assert.deepEqual(paths, ['src/added.js', 'src/app.js'])
})

test('rollback limit only unwinds the most recent entries', () => {
  const journal = recordAppliedBatch(createJournal(), [writeOverExisting, writeNewFile])
  const steps = buildRollbackSteps(journal, { limit: 1 })
  assert.deepEqual(steps.map(step => step.path), ['src/added.js'])
})

test('markRolledBack trims entries and recomputes cursor', () => {
  const journal = recordAppliedBatch(createJournal(), [writeOverExisting, writeNewFile, fileDeletion])
  const rolled = markRolledBack(journal, 2)
  assert.equal(rolled.entries.length, 1)
  assert.equal(rolled.cursor, 10)
})

test('summarizes journal with overwrite count', () => {
  const journal = recordAppliedBatch(createJournal(), [
    { ...writeOverExisting, overwritten: true },
    writeNewFile
  ])
  assert.deepEqual(summarizeJournal(journal), { entries: 2, cursor: 11, overwritten: 1 })
})
