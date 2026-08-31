import { ref } from 'vue'
import { copyTextToClipboard, fileNameOf, saveBlobAs } from '../constants/workspaceFiles.js'
import { transferErrorMessage } from './useFileClipboard.js'

/**
 * 文件轻操作集合：复制路径、图片预览（blob 加载，token 不进 URL）、下载、添加到聊天。
 */
export function useFilePreviewActions(options) {
  const projectId = options.projectId
  const projectName = options.projectName
  const notify = options.notify || { success() {}, info() {}, error() {} }
  const openLightbox = options.openLightbox
  const appendToAgentInput = options.appendToAgentInput

  const previewingImage = ref(false)

  async function copyPath(nodePath) {
    try {
      await copyTextToClipboard(buildDisplayPath(nodePath))
      notify.success('路径已复制')
    } catch (error) {
      notify.error(error?.message || '复制失败')
    }
  }

  function buildDisplayPath(nodePath) {
    const projectPrefix = projectName.value ? `/${projectName.value}` : ''
    return `${projectPrefix}/${String(nodePath || '').replace(/^\/+/, '')}`
  }

  let lastPreviewUrl = ''

  async function previewImage(nodePath) {
    if (previewingImage.value) return
    previewingImage.value = true
    try {
      const blob = await options.api.fileImage(projectId.value, nodePath)
      if (lastPreviewUrl) URL.revokeObjectURL(lastPreviewUrl)
      lastPreviewUrl = URL.createObjectURL(blob)
      openLightbox(lastPreviewUrl, fileNameOf(nodePath))
    } catch (error) {
      notify.error('图片加载失败: ' + transferErrorMessage(error))
    } finally {
      previewingImage.value = false
    }
  }

  function dispose() {
    if (lastPreviewUrl) URL.revokeObjectURL(lastPreviewUrl)
    lastPreviewUrl = ''
  }

  async function download(nodePath) {
    try {
      notify.info('开始下载…')
      const blob = await options.api.downloadFile(projectId.value, nodePath)
      saveBlobAs(blob, fileNameOf(nodePath))
    } catch (error) {
      notify.error('下载失败: ' + transferErrorMessage(error))
    }
  }

  function addToChat(nodePath) {
    if (!appendToAgentInput) return
    appendToAgentInput('@' + nodePath + ' ')
    notify.success('已添加到聊天输入框')
  }

  return { previewingImage, copyPath, previewImage, download, addToChat, dispose }
}
