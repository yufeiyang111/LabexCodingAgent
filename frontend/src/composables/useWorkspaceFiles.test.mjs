import assert from 'node:assert/strict'
import test from 'node:test'

import { useWorkspaceFiles } from './useWorkspaceFiles.js'

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function controller(overrides = {}) {
  const notifications = []
  const api = {
    getTreePage: async () => ({ data: { entries: [], nextOffset: null } }),
    readFile: async (_projectId, path) => ({ data: { content: path } }),
    saveFile: async () => ({}),
    createItem: async () => ({}),
    renameItem: async () => ({}),
    deleteItem: async () => ({}),
    ...overrides.api
  }
  return {
    notifications,
    files: useWorkspaceFiles({
      projectId: { value: 42 },
      api,
      notify: {
        warning: value => notifications.push(['warning', value]),
        success: value => notifications.push(['success', value]),
        error: value => notifications.push(['error', value])
      },
      confirmAction: overrides.confirmAction || (async () => true),
      nextTick: async () => {}
    })
  }
}

test('ignores stale open-file responses when a newer file owns the editor', async () => {
  const first = deferred()
  const second = deferred()
  const { files } = controller({ api: { readFile: async (_id, path) => path === 'a.txt' ? first.promise : second.promise } })

  const openA = files.openFile('a.txt')
  const openB = files.openFile('b.txt')
  second.resolve({ data: { content: 'B' } })
  await openB
  first.resolve({ data: { content: 'A' } })
  await openA

  assert.equal(files.activePath.value, 'b.txt')
  assert.equal(files.fileContent.value, 'B')
  assert.deepEqual(files.openFiles.value.map(file => file.path), ['b.txt'])
})

test('exposes failed root tree pages as actionable state', async () => {
  const { files } = controller({ api: { getTreePage: async () => { const error = new Error('missing'); error.response = { status: 404 }; throw error } } })
  await files.loadRoot()
  assert.deepEqual(files.fileTree.value, [])
  assert.match(files.treeError.value, /重启后端/)
})

test('bumps treeRefreshKey after every successful root reload', async () => {
  const { files } = controller({ api: { getTreePage: async () => ({ data: { entries: [{ path: 'a.txt', name: 'a.txt', type: 'file' }], nextOffset: null } }) } })
  const before = files.treeRefreshKey.value
  assert.equal(await files.loadRoot(), true)
  assert.equal(files.treeRefreshKey.value, before + 1)
  assert.equal(files.treeRefreshKey.value, before + 1)
  assert.equal(await files.loadRoot(), true)
  assert.equal(files.treeRefreshKey.value, before + 2)
})

test('does not bump treeRefreshKey when a reload fails', async () => {
  const { files } = controller({ api: { getTreePage: async () => { const error = new Error('missing'); error.response = { status: 404 }; throw error } } })
  const before = files.treeRefreshKey.value
  await files.loadRoot()
  assert.equal(files.treeRefreshKey.value, before)
})

test('requires confirmation before closing a dirty tab', async () => {
  let confirmed = false
  const { files } = controller({ confirmAction: async () => { confirmed = true; throw 'cancel' } })
  files.openFiles.value = [{ path: 'dirty.txt', name: 'dirty.txt', content: 'x', dirty: true, lang: 'plaintext' }]
  files.activeTabIndex.value = 0
  files.activePath.value = 'dirty.txt'

  assert.equal(await files.closeFile(0), false)
  assert.equal(confirmed, true)
  assert.equal(files.openFiles.value.length, 1)
})

test('rename and delete keep tab ownership synchronized', async () => {
  const { files } = controller()
  files.openFiles.value = [
    { path: 'dir/a.txt', name: 'a.txt', content: 'a', dirty: false, lang: 'plaintext' },
    { path: 'other.txt', name: 'other.txt', content: 'o', dirty: false, lang: 'plaintext' }
  ]
  files.activeTabIndex.value = 0
  files.activePath.value = 'dir/a.txt'
  files.renamingItemPath.value = 'dir/a.txt'
  files.renameItemValue.value = 'b.txt'

  assert.equal(await files.confirmRename(), true)
  assert.equal(files.openFiles.value[0].path, 'dir/b.txt')
  assert.equal(files.activePath.value, 'dir/b.txt')

  assert.equal(await files.handleDelete('dir/b.txt'), true)
  assert.deepEqual(files.openFiles.value.map(file => file.path), ['other.txt'])
  assert.equal(files.activePath.value, 'other.txt')
})
