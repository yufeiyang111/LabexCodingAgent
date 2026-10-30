import assert from 'node:assert/strict'
import test from 'node:test'

import { loadAllAgentChangeFeed } from './agentChangeFeedLoader.js'

function feedPage(overrides = {}) {
  return {
    changeSets: [],
    changedFiles: [],
    nextCursor: { afterChangeSetId: 0, tailUpdateTime: '2026-10-06T10:00:00', tailChangeId: '' },
    hasMore: false,
    hasMoreChangeSets: false,
    hasMoreChangedFiles: false,
    ...overrides
  }
}

test('loads all pages until both change-set and tail progress converge', async () => {
  const calls = []
  const pages = [
    feedPage({
      changeSets: [{ changeSetId: 11, files: [{ changeId: 'a', status: 'pending' }] }],
      changedFiles: [{ changeId: 'tail-1', status: 'undone' }],
      nextCursor: { afterChangeSetId: 11, tailUpdateTime: '2026-10-06T10:00:00', tailChangeId: 'tail-1' },
      hasMore: true,
      hasMoreChangeSets: true,
      hasMoreChangedFiles: true
    }),
    feedPage({
      changeSets: [{ changeSetId: 12, files: [{ changeId: 'b', status: 'pending' }] }],
      changedFiles: [{ changeId: 'tail-2', status: 'applied' }],
      nextCursor: { afterChangeSetId: 12, tailUpdateTime: '2026-10-06T10:00:01', tailChangeId: 'tail-2' }
    })
  ]

  const result = await loadAllAgentChangeFeed(async request => {
    calls.push(request)
    return pages.shift()
  }, {
    afterChangeSetId: 10,
    tailUpdateTime: '2026-10-06T09:59:59',
    tailChangeId: 'tail-0',
    limit: 2
  })

  assert.equal(calls.length, 2)
  assert.deepEqual(calls[0], {
    afterChangeSetId: 10,
    tailUpdateTime: '2026-10-06T09:59:59',
    tailChangeId: 'tail-0',
    limit: 2
  })
  assert.deepEqual(calls[1], {
    afterChangeSetId: 11,
    tailUpdateTime: '2026-10-06T10:00:00',
    tailChangeId: 'tail-1',
    limit: 2
  })
  assert.deepEqual(result.changeSets.map(item => item.changeSetId), [11, 12])
  assert.deepEqual(result.changedFiles.map(item => item.changeId), ['tail-1', 'tail-2'])
  assert.deepEqual(result.nextCursor, {
    afterChangeSetId: 12,
    tailUpdateTime: '2026-10-06T10:00:01',
    tailChangeId: 'tail-2'
  })
  assert.equal(result.hasMore, false)
  assert.equal(result.hasMoreChangeSets, false)
  assert.equal(result.hasMoreChangedFiles, false)
})

test('deduplicates repeated feed rows by changeId and status across pages', async () => {
  const pages = [
    feedPage({
      changedFiles: [
        { changeId: 'same', status: 'pending' },
        { changeId: 'same', status: 'applied' }
      ],
      nextCursor: { afterChangeSetId: 0, tailUpdateTime: '2026-10-06T10:00:01', tailChangeId: 'same' },
      hasMore: true,
      hasMoreChangedFiles: true
    }),
    feedPage({
      changedFiles: [
        { changeId: 'same', status: 'pending' },
        { changeId: 'new', status: 'pending' }
      ],
      nextCursor: { afterChangeSetId: 0, tailUpdateTime: '2026-10-06T10:00:02', tailChangeId: 'new' }
    })
  ]

  const result = await loadAllAgentChangeFeed(async () => pages.shift())
  assert.deepEqual(result.changedFiles.map(item => `${item.changeId}:${item.status}`), [
    'same:pending',
    'same:applied',
    'new:pending'
  ])
})

test('refuses to loop forever when a page claims more data without advancing its cursor', async () => {
  await assert.rejects(
    () => loadAllAgentChangeFeed(async () => feedPage({
      nextCursor: { afterChangeSetId: 0, tailUpdateTime: '2026-10-06T10:00:00', tailChangeId: '' },
      hasMore: true,
      hasMoreChangeSets: true
    })),
    /cursor did not advance/
  )
})

test('stops after a bounded number of pages', async () => {
  let calls = 0
  await assert.rejects(
    () => loadAllAgentChangeFeed(async () => {
      calls += 1
      return feedPage({
        nextCursor: { afterChangeSetId: calls, tailUpdateTime: `2026-10-06T10:00:0${calls}`, tailChangeId: '' },
        hasMore: true,
        hasMoreChangeSets: true
      })
    }, { maxPages: 2 }),
    /page limit exceeded/
  )
  assert.equal(calls, 2)
})
