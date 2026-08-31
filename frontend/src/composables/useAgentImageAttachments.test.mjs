import { test } from 'node:test'
import assert from 'node:assert/strict'
import { useAgentImageAttachments } from './useAgentImageAttachments.js'

const createdUrls = []
const revokedUrls = []
URL.createObjectURL = source => {
  createdUrls.push(source)
  return `blob:test-${createdUrls.length}`
}
URL.revokeObjectURL = url => { revokedUrls.push(url) }

function imageFile(name, size, lastModified = 1) {
  return { name, size, type: 'image/png', lastModified }
}

function setup(overrides = {}) {
  const warnings = []
  const previews = new Map()
  const api = overrides.api || {
    async agentImageAttachmentPolicy() {
      return { data: overrides.policy || {} }
    },
    async agentAttachmentPreview(projectId, attachmentId) {
      if (!previews.has(attachmentId)) {
        const error = new Error('not found')
        error.response = { status: overrides.previewStatus || 404 }
        throw error
      }
      return previews.get(attachmentId)
    }
  }
  const composable = useAgentImageAttachments({
    projectId: { value: 7 },
    api,
    notify: { warning: message => warnings.push(message) },
    canAcceptImages: () => overrides.canAccept !== false,
    isBusy: () => overrides.busy === true
  })
  return { composable, warnings, previews }
}

test('addFiles rejects everything when the active model does not accept images', () => {
  const { composable, warnings } = setup({ canAccept: false })
  const accepted = composable.addFiles([imageFile('a.png', 10)])
  assert.equal(accepted.length, 0)
  assert.equal(composable.pendingAttachments.value.length, 0)
  assert.equal(warnings.length, 1)
  assert.match(warnings[0], /图片理解/)
})

test('addFiles enforces mime, per-file size, count cap and total budget', async () => {
  const { composable, warnings } = setup({
    policy: { maxFilesPerMessage: 3, maxFileSizeBytes: 100, maxTotalSizeBytes: 150 }
  })
  await composable.loadPolicy()
  const accepted = composable.addFiles([
    { name: 'note.txt', size: 1, type: 'text/plain', lastModified: 1 },
    imageFile('big.png', 101),
    imageFile('a.png', 60),
    imageFile('b.png', 60),
    imageFile('c.png', 31),
    imageFile('never.png', 10)
  ])
  assert.equal(accepted.length, 2)
  assert.deepEqual(composable.pendingAttachments.value.map(item => item.name), ['a.png', 'b.png'])
  const messages = warnings.join('\n')
  assert.match(messages, /不支持 note\.txt/)
  assert.match(messages, /超过单张图片大小限制/)
  assert.match(messages, /一次最多添加 3 张图片|图片总大小超过当前限制/)
})

test('addFiles skips duplicate files silently', () => {
  const { composable } = setup()
  composable.addFiles([imageFile('dup.png', 5, 9)])
  composable.addFiles([imageFile('dup.png', 5, 9)])
  assert.equal(composable.pendingAttachments.value.length, 1)
})

test('addFiles is a no-op while the agent is busy', () => {
  const { composable } = setup({ busy: true })
  assert.equal(composable.addFiles([imageFile('a.png', 10)]).length, 0)
  assert.equal(composable.pendingAttachments.value.length, 0)
})

test('remove revokes the object URL and reports the removed attachment', () => {
  const { composable } = setup()
  composable.addFiles([imageFile('a.png', 10)])
  const id = composable.pendingAttachments.value[0].id
  const revokedBefore = revokedUrls.length
  const removed = composable.remove(id)
  assert.equal(removed.id, id)
  assert.equal(revokedUrls.length, revokedBefore + 1)
  assert.equal(composable.remove('missing'), null)
})

test('takeAll transfers ownership without revoking previews', () => {
  const { composable } = setup()
  const revokedBefore = revokedUrls.length
  composable.addFiles([imageFile('a.png', 10), imageFile('b.png', 20)])
  const taken = composable.takeAll()
  assert.equal(taken.length, 2)
  assert.equal(taken.every(item => item.previewUrl.startsWith('blob:test-')), true)
  assert.equal(composable.pendingAttachments.value.length, 0)
  assert.equal(revokedUrls.length, revokedBefore)
})

test('clearAndRevoke revokes every pending preview URL', () => {
  const { composable } = setup()
  const revokedBefore = revokedUrls.length
  composable.addFiles([imageFile('a.png', 10), imageFile('b.png', 20)])
  composable.clearAndRevoke()
  assert.equal(composable.pendingAttachments.value.length, 0)
  assert.equal(revokedUrls.length, revokedBefore + 2)
})

test('loadPolicy maps remote limits and falls back to defaults on failure', async () => {
  const mapped = setup({
    policy: { maxFilesPerMessage: 3, maxFileSizeBytes: 5, maxTotalSizeBytes: 9, allowedMimeTypes: ['image/webp'] }
  })
  await mapped.composable.loadPolicy()
  assert.deepEqual(mapped.composable.policy.value, {
    maxFilesPerMessage: 3,
    maxFileSizeBytes: 5,
    maxTotalSizeBytes: 9,
    allowedMimeTypes: ['image/webp']
  })

  const fallback = setup({
    api: {
      async agentImageAttachmentPolicy() { throw new Error('boom') },
      async agentAttachmentPreview() { throw new Error('unused') }
    }
  })
  await fallback.composable.loadPolicy()
  assert.equal(fallback.composable.policy.value.maxFilesPerMessage > 0, true)
})

test('hydrateMessages attaches previews and flags expired history attachments', async () => {
  const { composable, previews } = setup({ previewStatus: 410 })
  previews.set('ok-1', new Blob(['x']))
  const messages = [
    { attachments: [{ id: 'ok-1' }, { id: 'gone-1' }] },
    { attachments: [] },
    {}
  ]
  await composable.hydrateMessages(messages)
  const [okAttachment, goneAttachment] = messages[0].attachments
  assert.match(okAttachment.previewUrl, /^blob:test-/)
  assert.equal(goneAttachment.expired, true)

  await composable.hydrateMessages(messages)
  assert.equal(Object.prototype.hasOwnProperty.call(goneAttachment, 'previewUrl'), false)
})
