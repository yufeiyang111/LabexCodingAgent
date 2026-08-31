import { computed, ref } from 'vue'
import { DEFAULT_AGENT_IMAGE_INPUT_POLICY, imageAcceptValue } from '../constants/agentImageInput.js'

function formatMb(bytes) {
  return Math.max(1, Math.round(Number(bytes || 0) / (1024 * 1024)))
}

function fileFingerprint(file) {
  return [file?.name, file?.size, file?.lastModified].join('::')
}

function clonePolicy(policy) {
  return { ...policy, allowedMimeTypes: [...policy.allowedMimeTypes] }
}

function positiveNumber(value, fallback) {
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback
}

/**
 * Agent 图片附件的唯一前端状态机：策略加载、粘贴/拖拽/选择的准入校验、
 * 待发送列表、历史附件预览水合与 ObjectURL 生命周期。
 * 图片二进制只通过 useAgentStream 的 multipart files 上传，绝不转成 data URL 文本。
 */
export function useAgentImageAttachments(options) {
  const api = options.api
  const projectId = options.projectId
  const notify = options.notify || { success() {}, info() {}, warning() {}, error() {} }
  const canAcceptImages = options.canAcceptImages || (() => false)
  const isBusy = options.isBusy || (() => false)

  const pendingAttachments = ref([])
  const policy = ref(clonePolicy(DEFAULT_AGENT_IMAGE_INPUT_POLICY))
  const acceptValue = computed(() => imageAcceptValue(policy.value.allowedMimeTypes))

  async function loadPolicy() {
    try {
      const response = await api.agentImageAttachmentPolicy(projectId.value)
      const remote = response?.data || {}
      policy.value = {
        maxFilesPerMessage: positiveNumber(remote.maxFilesPerMessage, DEFAULT_AGENT_IMAGE_INPUT_POLICY.maxFilesPerMessage),
        maxFileSizeBytes: positiveNumber(remote.maxFileSizeBytes, DEFAULT_AGENT_IMAGE_INPUT_POLICY.maxFileSizeBytes),
        maxTotalSizeBytes: positiveNumber(remote.maxTotalSizeBytes, DEFAULT_AGENT_IMAGE_INPUT_POLICY.maxTotalSizeBytes),
        allowedMimeTypes: Array.isArray(remote.allowedMimeTypes) && remote.allowedMimeTypes.length > 0
          ? [...remote.allowedMimeTypes]
          : [...DEFAULT_AGENT_IMAGE_INPUT_POLICY.allowedMimeTypes]
      }
    } catch {
      policy.value = clonePolicy(DEFAULT_AGENT_IMAGE_INPUT_POLICY)
    }
  }

  /** 校验并接收图片文件；返回本次实际接收的附件对象。 */
  function addFiles(fileList) {
    const files = Array.from(fileList || []).filter(Boolean)
    if (files.length === 0 || isBusy()) return []
    if (!canAcceptImages()) {
      notify.warning('当前模型未开启图片理解；请在「模型配置」中勾选“支持图片理解”后再试。')
      return []
    }
    const limits = policy.value
    const existing = pendingAttachments.value
    let totalBytes = existing.reduce((sum, attachment) => sum + (attachment.file?.size || 0), 0)
    const seenFingerprints = new Set(existing.map(attachment => fileFingerprint(attachment.file)))
    const accepted = []
    for (const file of files) {
      if (file.type && !file.type.startsWith('image/')) {
        notify.warning(`不支持 ${file.name} 的文件格式，请上传图片`)
        continue
      }
      if (!Number.isFinite(file.size) || file.size <= 0 || file.size > limits.maxFileSizeBytes) {
        notify.warning(`${file.name} 超过单张图片大小限制 (${formatMb(limits.maxFileSizeBytes)}MB)`)
        continue
      }
      if (existing.length + accepted.length >= limits.maxFilesPerMessage) {
        notify.warning(`一次最多添加 ${limits.maxFilesPerMessage} 张图片`)
        break
      }
      if (totalBytes + file.size > limits.maxTotalSizeBytes) {
        notify.warning('图片总大小超过当前限制')
        break
      }
      const fingerprint = fileFingerprint(file)
      if (seenFingerprints.has(fingerprint)) continue
      seenFingerprints.add(fingerprint)
      const attachment = {
        id: crypto.randomUUID(),
        file,
        name: file.name || 'image',
        previewUrl: URL.createObjectURL(file)
      }
      accepted.push(attachment)
      totalBytes += file.size
    }
    if (accepted.length > 0) pendingAttachments.value.push(...accepted)
    return accepted
  }

  /** 移除待发送附件；返回被移除的附件（未知 ID 返回 null），由调用方决定灯箱等联动。 */
  function remove(attachmentId) {
    const index = pendingAttachments.value.findIndex(item => item.id === attachmentId)
    if (index < 0) return null
    const removed = pendingAttachments.value[index]
    if (removed.previewUrl) URL.revokeObjectURL(removed.previewUrl)
    pendingAttachments.value = pendingAttachments.value.filter(item => item.id !== attachmentId)
    return removed
  }

  /** 发送时转移所有权：清空待发送但不回收 ObjectURL（缩略图仍由消息列表持有）。 */
  function takeAll() {
    const taken = pendingAttachments.value.slice()
    pendingAttachments.value = []
    return taken
  }

  function clearAndRevoke() {
    pendingAttachments.value.forEach(revokeAttachmentUrl)
    pendingAttachments.value = []
  }

  function dispose() {
    clearAndRevoke()
  }

  /** 为历史消息中的附件元数据水合预览 URL；过期/失败状态写入标记供 UI 展示。 */
  async function hydrateMessages(messages) {
    const attachments = (messages || []).flatMap(message => message?.attachments || [])
    await Promise.all(attachments.map(attachment => hydrateOne(attachment)))
  }

  async function hydrateOne(attachment) {
    if (!attachment || attachment.previewUrl || attachment.expired || attachment.previewFailed || attachment._previewPromise) return
    attachment._previewPromise = Promise.resolve(api.agentAttachmentPreview(projectId.value, attachment.id))
      .then(blob => {
        if (!(blob instanceof Blob) || blob.size === 0) throw new Error('empty preview')
        attachment.previewUrl = URL.createObjectURL(blob)
      })
      .catch(error => {
        const status = error?.status ?? error?.response?.status
        if (status === 404 || status === 410) attachment.expired = true
        else attachment.previewFailed = true
      })
      .finally(() => { attachment._previewPromise = null })
    await attachment._previewPromise
  }

  function revokeAttachmentUrl(attachment) {
    if (attachment?.previewUrl) URL.revokeObjectURL(attachment.previewUrl)
  }

  return {
    pendingAttachments,
    policy,
    acceptValue,
    loadPolicy,
    addFiles,
    remove,
    takeAll,
    clearAndRevoke,
    hydrateMessages,
    dispose
  }
}
